package com.adperf.aggregator;

import com.adperf.aggregator.RowParser.Status;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AggregatorTest {

    private static final String HEADER = "campaign_id,date,impressions,clicks,spend,conversions\n";

    @TempDir
    Path dir;

    @Test
    void sumsRowsPerCampaignAndRanks() throws IOException {
        Report report = analyze(HEADER + """
                CMP001,2025-01-01,12000,300,45.50,12
                CMP002,2025-01-01,8000,120,28.00,4
                CMP001,2025-01-02,14000,340,48.20,15
                CMP003,2025-01-01,5000,60,15.00,3
                CMP002,2025-01-02,8500,150,31.00,5
                """);

        assertThat(report.validRows()).isEqualTo(5);
        assertThat(report.campaignCount()).isEqualTo(3);
        assertThat(report.topByCtr()).containsExactly(
                new Campaign("CMP001", 26_000, 640, 9_370, 27),
                new Campaign("CMP002", 16_500, 270, 5_900, 9),
                new Campaign("CMP003", 5_000, 60, 1_500, 3));
        assertThat(ids(report.topByCpa())).containsExactly("CMP001", "CMP003", "CMP002");
    }

    @Test
    void sumsRepeatedCampaignAndDateInsteadOfDeduplicating() throws IOException {
        Report report = analyze(HEADER + "CMP001,2025-01-01,100,10,1.00,1\nCMP001,2025-01-01,100,10,1.00,1\n");

        assertThat(report.topByCtr()).containsExactly(new Campaign("CMP001", 200, 20, 200, 2));
    }

    @Test
    void handlesReorderedColumnsBomWindowsLineEndingsBlankLinesAndMissingFinalNewline() throws IOException {
        Report report = analyze("﻿conversions,spend,clicks,impressions,date,campaign_id\r\n"
                + "12,45.50,300,12000,2025-01-01,CMP001\r\n"
                + "\r\n"
                + "15,48.20,340,14000,2025-01-02,CMP001");

        assertThat(report.dataRows()).isEqualTo(2);
        assertThat(report.topByCtr()).containsExactly(new Campaign("CMP001", 26_000, 640, 9_370, 27));
    }

    @Test
    void skipsInvalidRowsAndReportsFileLineNumbers() throws IOException {
        Report report = analyze(HEADER + """
                CMP001,2025-01-01,100,10,1.00,1
                ,2025-01-01,100,10,1.00,1

                CMP001,2025-01-01,abc,10,1.00,1
                CMP001,2025-02-30,100,10,1.00,1
                CMP001,2025-01-01,100,10
                """);

        assertThat(report.dataRows()).isEqualTo(5);
        assertThat(report.skippedRows()).isEqualTo(4);
        assertThat(report.skippedByReason()).isEqualTo(Map.of(
                Status.MISSING_VALUE, 1L, Status.INVALID_NUMBER, 1L, Status.INVALID_DATE, 1L, Status.WRONG_FIELD_COUNT, 1L));
        assertThat(report.skippedSamples()).extracting(Report.SkippedRow::lineNumber).containsExactly(3L, 5L, 6L, 7L);
        assertThat(report.topByCtr()).containsExactly(new Campaign("CMP001", 100, 10, 100, 1));
    }

    @Test
    void skipsLineLongerThanBufferAsOneRow() throws IOException {
        Path file = write(HEADER + "CMP001,2025-01-01,100,10,1.00,1\n" + "x".repeat(500) + "\nCMP001,2025-01-01,100,10,1.00,1\n");

        Report report = Aggregator.analyze(file, 1, 64);

        assertThat(report.dataRows()).isEqualTo(3);
        assertThat(report.skippedByReason()).isEqualTo(Map.of(Status.LINE_TOO_LONG, 1L));
        assertThat(report.skippedSamples()).extracting(Report.SkippedRow::lineNumber).containsExactly(3L);
        assertThat(report.topByCtr()).containsExactly(new Campaign("CMP001", 200, 20, 200, 2));
    }

    @Test
    void keepsOnlyTenSkippedSamples() throws IOException {
        Report report = analyze(HEADER + "broken\n".repeat(50));

        assertThat(report.skippedRows()).isEqualTo(50);
        assertThat(report.skippedSamples()).hasSize(10);
        assertThat(report.skippedSamples().getFirst().lineNumber()).isEqualTo(2);
    }

    @Test
    void excludesUndefinedRatiosFromRankings() throws IOException {
        Report report = analyze(HEADER + """
                NO_CONVERSIONS,2025-01-01,100,50,1.00,0
                NO_IMPRESSIONS,2025-01-01,0,0,1.00,1
                NORMAL,2025-01-01,100,10,9.00,3
                """);

        assertThat(ids(report.topByCtr())).containsExactly("NO_CONVERSIONS", "NORMAL");
        assertThat(ids(report.topByCpa())).containsExactly("NO_IMPRESSIONS", "NORMAL");
    }

    @Test
    void keepsTopTenAndBreaksTiesByCampaignId() throws IOException {
        StringBuilder csv = new StringBuilder(HEADER);
        IntStream.rangeClosed(1, 15).forEach(i -> csv.append("CMP%02d,2025-01-01,1000,%d,10.00,%d\n".formatted(i, i, i)));
        csv.append("CMP00,2025-01-01,1000,15,10.00,15\n");

        Report report = analyze(csv.toString());

        List<String> expected = List.of("CMP00", "CMP15", "CMP14", "CMP13", "CMP12", "CMP11", "CMP10", "CMP09", "CMP08", "CMP07");
        assertThat(ids(report.topByCtr())).isEqualTo(expected);
        assertThat(ids(report.topByCpa())).isEqualTo(expected);
    }

    @Test
    void headerOnlyFilesGiveEmptyRankings() throws IOException {
        for (String csv : List.of(HEADER, HEADER.strip())) {
            Report report = analyze(csv);

            assertThat(report.dataRows()).isZero();
            assertThat(report.topByCtr()).isEmpty();
        }
    }

    @Test
    void rejectsEmptyFileAndInvalidHeader() {
        assertThatThrownBy(() -> analyze("")).isInstanceOf(InvalidInputException.class);
        assertThatThrownBy(() -> analyze("campaign_id,date,impressions,clicks,spend\nCMP001,2025-01-01,1,1,1\n"))
                .isInstanceOf(InvalidInputException.class);
    }

    @Test
    void chunkBoundsAlwaysFallOnLineStarts() throws IOException {
        String csv = HEADER + "A,2025-01-01,1,1,1.00,1\n".repeat(7) + "B,2025-01-01,1,1,1.00,1";
        Path file = write(csv);

        try (FileChannel channel = FileChannel.open(file)) {
            long[] bounds = Aggregator.chunkBounds(channel, HEADER.length(), csv.length(), 5);

            assertThat(bounds[0]).isEqualTo(HEADER.length());
            assertThat(bounds[5]).isEqualTo(csv.length());
            for (long bound : bounds) {
                assertThat(bound == HEADER.length() || bound == csv.length() || csv.charAt((int) bound - 1) == '\n')
                        .as("bound %d is a line start", bound).isTrue();
            }
        }
    }

    @Test
    void anyThreadCountAndBufferSizeGivesTheSameReport() throws IOException {
        Path file = write(randomCsv(new Random(42), 20_000));

        Report expected = Aggregator.analyze(file, 1, 1 << 20);
        for (int threads : new int[]{2, 3, 8, 16}) {
            for (int bufferSize : new int[]{64, 1000, 1 << 20}) {
                assertSameReport(Aggregator.analyze(file, threads, bufferSize), expected);
            }
        }
        assertThat(expected.skippedRows()).isPositive();
        assertThat(expected.campaignCount()).isEqualTo(25);
    }

    @Test
    void topMatchesFullSort() {
        List<Integer> items = IntStream.range(0, 10_000).map(i -> (i * 7919) % 10_007).boxed().toList();

        assertThat(Aggregator.top(items, 10, Comparator.naturalOrder()))
                .isEqualTo(items.stream().sorted().limit(10).toList());
        assertThat(Aggregator.top(List.of(2, 1), 10, Comparator.naturalOrder())).containsExactly(1, 2);
    }

    /**
     * Runs the same file single-threaded and with 4 threads and a 64-byte buffer (just longer than any test
     * row, so lines constantly cross buffer refills); both must agree.
     */
    private Report analyze(String csv) throws IOException {
        Path file = write(csv);
        Report report = Aggregator.analyze(file, 1);
        assertSameReport(Aggregator.analyze(file, 4, 64), report);
        return report;
    }

    private static void assertSameReport(Report actual, Report expected) {
        assertThat(actual).usingRecursiveComparison().ignoringFields("elapsed").isEqualTo(expected);
    }

    private static String randomCsv(Random random, int rows) {
        StringBuilder csv = new StringBuilder(HEADER);
        for (int i = 0; i < rows; i++) {
            String newline = random.nextBoolean() ? "\n" : "\r\n";
            switch (random.nextInt(50)) {
                case 0 -> csv.append(newline);
                case 1 -> csv.append("broken row").append(newline);
                case 2 -> csv.append("CMP01,2025-02-30,1,1,1.00,1").append(newline);
                default -> csv.append("CMP%02d,2025-%02d-%02d,%d,%d,%d.%02d,%d".formatted(
                        random.nextInt(25), 1 + random.nextInt(12), 1 + random.nextInt(28), random.nextInt(50_000),
                        random.nextInt(2_000), random.nextInt(2_000), random.nextInt(100), random.nextInt(100)))
                        .append(newline);
            }
        }
        return csv.toString();
    }

    private Path write(String csv) throws IOException {
        return Files.writeString(dir.resolve("ads-" + System.nanoTime() + ".csv"), csv);
    }

    private static List<String> ids(List<Campaign> campaigns) {
        return campaigns.stream().map(Campaign::id).toList();
    }
}

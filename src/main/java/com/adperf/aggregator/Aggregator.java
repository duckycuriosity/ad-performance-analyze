package com.adperf.aggregator;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;

/**
 * Reads the CSV once, line by line, and keeps one running-totals object per campaign.
 * Memory use therefore depends on the number of campaigns, not on the size of the file.
 */
final class Aggregator {

    static final int TOP_N = 10;

    private static final int READ_BUFFER_CHARS = 1 << 20;
    private static final int MAX_SKIPPED_SAMPLES = 10;
    private static final int MAX_SAMPLE_LENGTH = 200;

    private Aggregator() {
    }

    static Report analyze(InputStream input) throws IOException {
        long startNanos = System.nanoTime();
        BufferedReader reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8), READ_BUFFER_CHARS);
        RowParser parser = new RowParser(CsvHeader.parse(reader.readLine()));

        Map<String, Totals> totalsByCampaign = new HashMap<>();
        Map<RowParser.Status, Long> skippedByReason = new EnumMap<>(RowParser.Status.class);
        List<Report.SkippedRow> skippedSamples = new ArrayList<>();
        long lineNumber = 1;
        long dataRows = 0;
        long validRows = 0;

        String line;
        while ((line = reader.readLine()) != null) {
            lineNumber++;
            if (line.isBlank()) {
                continue;
            }
            dataRows++;
            RowParser.Status status = parser.parse(line);
            if (status == RowParser.Status.VALID) {
                validRows++;
                totalsByCampaign.computeIfAbsent(parser.campaignId(), id -> new Totals()).add(parser);
            } else {
                skippedByReason.merge(status, 1L, Long::sum);
                if (skippedSamples.size() < MAX_SKIPPED_SAMPLES) {
                    skippedSamples.add(new Report.SkippedRow(lineNumber, status, truncate(line)));
                }
            }
        }

        List<Campaign> campaigns = new ArrayList<>(totalsByCampaign.size());
        totalsByCampaign.forEach((id, totals) -> campaigns.add(totals.toCampaign(id)));
        return new Report(
                dataRows,
                validRows,
                skippedByReason,
                skippedSamples,
                campaigns.size(),
                top(campaigns.stream().filter(Campaign::hasCtr).toList(), TOP_N, Campaign.BY_CTR_DESC),
                top(campaigns.stream().filter(Campaign::hasCpa).toList(), TOP_N, Campaign.BY_CPA_ASC),
                Duration.ofNanos(System.nanoTime() - startNanos));
    }

    /**
     * Returns the best {@code n} items, best first, in O(K log n) using a heap of size n.
     * The heap keeps the worst of the current best items at its head so it can be evicted cheaply.
     */
    static <T> List<T> top(Collection<T> items, int n, Comparator<? super T> bestFirst) {
        PriorityQueue<T> heap = new PriorityQueue<>(n + 1, bestFirst.reversed());
        for (T item : items) {
            heap.offer(item);
            if (heap.size() > n) {
                heap.poll();
            }
        }
        List<T> best = new ArrayList<>(heap);
        best.sort(bestFirst);
        return best;
    }

    private static String truncate(String line) {
        return line.length() <= MAX_SAMPLE_LENGTH ? line : line.substring(0, MAX_SAMPLE_LENGTH) + "...";
    }

    /** Mutable so adding a row does not allocate. */
    private static final class Totals {
        private long impressions;
        private long clicks;
        private long spendCents;
        private long conversions;

        void add(RowParser row) {
            impressions += row.impressions();
            clicks += row.clicks();
            spendCents += row.spendCents();
            conversions += row.conversions();
        }

        Campaign toCampaign(String id) {
            return new Campaign(id, impressions, clicks, spendCents, conversions);
        }
    }
}

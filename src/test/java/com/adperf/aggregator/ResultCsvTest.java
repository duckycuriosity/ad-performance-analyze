package com.adperf.aggregator;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ResultCsvTest {

    private static final String HEADER = "campaign_id,total_impressions,total_clicks,total_spend,total_conversions,CTR,CPA\n";

    @Test
    void formatsFixedDecimalsAndBlankUndefinedCpa() {
        String csv = ResultCsv.format(List.of(
                new Campaign("CMP042", 125_000, 6_250, 1_250_050, 625),
                new Campaign("CMP007", 1_000, 50, 1_000, 0)));

        assertThat(csv).isEqualTo(HEADER
                + "CMP042,125000,6250,12500.50,625,0.0500,20.00\n"
                + "CMP007,1000,50,10.00,0,0.0500,\n");
    }

    @Test
    void roundsHalfUp() {
        assertThat(new Campaign("A", 20_000, 1, 1, 8).roundedCtr()).hasToString("0.0001");
        assertThat(new Campaign("A", 20_000, 1, 1, 8).roundedCpa()).hasToString("0.00");
        assertThat(new Campaign("B", 1, 0, 5, 2).roundedCpa()).hasToString("0.03");
    }

    @Test
    void writesBothFiles(@TempDir Path dir) throws IOException {
        Campaign campaign = new Campaign("CMP042", 125_000, 6_250, 1_250_050, 625);
        Report report = new Report(1, 1, Map.of(), List.of(), 1, List.of(campaign), List.of(), Duration.ZERO);

        ResultCsv.writeFiles(report, dir.resolve("out"));

        assertThat(dir.resolve("out/top10_ctr.csv")).content().isEqualTo(HEADER + "CMP042,125000,6250,12500.50,625,0.0500,20.00\n");
        assertThat(Files.readString(dir.resolve("out/top10_cpa.csv"))).isEqualTo(HEADER);
    }
}

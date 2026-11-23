package com.adperf.aggregator;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/** Formats ranked campaigns as the result CSV files. */
final class ResultCsv {

    static final String TOP_CTR_FILE = "top10_ctr.csv";
    static final String TOP_CPA_FILE = "top10_cpa.csv";

    private static final String HEADER =
            "campaign_id,total_impressions,total_clicks,total_spend,total_conversions,CTR,CPA";

    private ResultCsv() {
    }

    /** Writes both result files into {@code outputDir}, creating it if needed. */
    static void writeFiles(Report report, Path outputDir) throws IOException {
        Files.createDirectories(outputDir);
        Files.writeString(outputDir.resolve(TOP_CTR_FILE), format(report.topByCtr()), StandardCharsets.UTF_8);
        Files.writeString(outputDir.resolve(TOP_CPA_FILE), format(report.topByCpa()), StandardCharsets.UTF_8);
    }

    /** At most ten rows, so building the whole text in memory is fine. Line endings are always \n. */
    static String format(List<Campaign> campaigns) {
        StringBuilder csv = new StringBuilder(HEADER).append('\n');
        for (Campaign c : campaigns) {
            csv.append(String.join(",",
                    c.id(),
                    Long.toString(c.impressions()),
                    Long.toString(c.clicks()),
                    c.spend().toPlainString(),
                    Long.toString(c.conversions()),
                    plain(c.roundedCtr()),
                    plain(c.roundedCpa()))).append('\n');
        }
        return csv.toString();
    }

    /** An undefined ratio is written as an empty cell. */
    private static String plain(BigDecimal value) {
        return value == null ? "" : value.toPlainString();
    }
}

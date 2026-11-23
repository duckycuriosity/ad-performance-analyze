package com.adperf.aggregator;

import com.adperf.aggregator.RowParser.Status;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class WebServerTest {

    @Test
    void buildsJsonReport() {
        Campaign campaign = new Campaign("CMP042", 125_000, 6_250, 1_250_050, 625);
        Report report = new Report(3, 2, Map.of(Status.MISSING_VALUE, 1L),
                List.of(new Report.SkippedRow(3, Status.MISSING_VALUE, ",x")), 1,
                List.of(campaign), List.of(campaign), Duration.ofMillis(12));

        String json = WebServer.toJson(report);

        assertThat(json).startsWith("{\"dataRows\":3,\"validRows\":2,\"skippedRows\":1,\"campaigns\":1,\"elapsedMillis\":12,")
                .contains("\"skipped\":[\"missing value: 1 rows\",\"line 3 (missing value): ,x\"]")
                .contains("\"topCtrCsv\":\"campaign_id,total_impressions,total_clicks,total_spend,total_conversions,CTR,CPA\\n"
                        + "CMP042,125000,6250,12500.50,625,0.0500,20.00\\n\"");
    }

    @Test
    void escapesJsonStrings() {
        assertThat(WebServer.quote("a\"b\\c\nd\te")).isEqualTo("\"a\\\"b\\\\c\\nd\\u0009e\"");
    }
}

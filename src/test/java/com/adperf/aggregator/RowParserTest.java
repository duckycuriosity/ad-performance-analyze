package com.adperf.aggregator;

import com.adperf.aggregator.RowParser.Status;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

class RowParserTest {

    private final RowParser parser =
            new RowParser(CsvHeader.parse("campaign_id,date,impressions,clicks,spend,conversions"));

    @Test
    void parsesValidRow() {
        assertThat(parser.parse("CMP001,2025-01-01,12000,300,45.50,12")).isEqualTo(Status.VALID);

        assertThat(parser.campaignId()).isEqualTo("CMP001");
        assertThat(parser.impressions()).isEqualTo(12000);
        assertThat(parser.clicks()).isEqualTo(300);
        assertThat(parser.spendCents()).isEqualTo(4550);
        assertThat(parser.conversions()).isEqualTo(12);
    }

    @Test
    void readsFieldsByHeaderPosition() {
        RowParser reordered = new RowParser(CsvHeader.parse("spend,conversions,campaign_id,clicks,date,impressions"));

        assertThat(reordered.parse("45.50,12,CMP001,300,2025-01-01,12000")).isEqualTo(Status.VALID);

        assertThat(reordered.campaignId()).isEqualTo("CMP001");
        assertThat(reordered.impressions()).isEqualTo(12000);
        assertThat(reordered.spendCents()).isEqualTo(4550);
    }

    @Test
    void trimsWhitespaceAroundValues() {
        assertThat(parser.parse(" CMP001 , 2025-01-01 ,12000, 300,45.5 ,12\t")).isEqualTo(Status.VALID);

        assertThat(parser.campaignId()).isEqualTo("CMP001");
        assertThat(parser.spendCents()).isEqualTo(4550);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "CMP001,2025-01-01,12000,300,45.50",
            "CMP001,2025-01-01,12000,300,45.50,12,7",
            "CMP001;2025-01-01;12000;300;45.50;12"})
    void rejectsWrongFieldCount(String line) {
        assertThat(parser.parse(line)).isEqualTo(Status.WRONG_FIELD_COUNT);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            ",2025-01-01,12000,300,45.50,12",
            "   ,2025-01-01,12000,300,45.50,12",
            "CMP001,,12000,300,45.50,12",
            "CMP001,2025-01-01,12000,300,,12",
            "CMP001,2025-01-01,12000,300,45.50,"})
    void rejectsMissingValue(String line) {
        assertThat(parser.parse(line)).isEqualTo(Status.MISSING_VALUE);
    }

    @ParameterizedTest
    @ValueSource(strings = {"2025-13-01", "2025-02-30", "2023-02-29", "4/18/2025", "2025-1-01", "2025/01/01"})
    void rejectsInvalidDate(String date) {
        assertThat(parser.parse("CMP001," + date + ",12000,300,45.50,12")).isEqualTo(Status.INVALID_DATE);
    }

    @Test
    void acceptsLeapDay() {
        assertThat(parser.parse("CMP001,2024-02-29,12000,300,45.50,12")).isEqualTo(Status.VALID);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "CMP001,2025-01-01,-5,300,45.50,12",
            "CMP001,2025-01-01,12k,300,45.50,12",
            "CMP001,2025-01-01,12000,3.5,45.50,12",
            "CMP001,2025-01-01,12000,300,-45.50,12",
            "CMP001,2025-01-01,12000,300,45.505,12",
            "CMP001,2025-01-01,12000,300,45.,12",
            "CMP001,2025-01-01,12000,300,.50,12",
            "CMP001,2025-01-01,9999999999999999999,300,45.50,12"})
    void rejectsInvalidNumber(String line) {
        assertThat(parser.parse(line)).isEqualTo(Status.INVALID_NUMBER);
    }

    @ParameterizedTest
    @CsvSource({"0, 0", "7, 700", "7.5, 750", "7.05, 705", "1394.62, 139462", "0.01, 1"})
    void parsesSpendIntoExactCents(String spend, long cents) {
        assertThat(RowParser.parseCents(spend, 0, spend.length())).isEqualTo(cents);
    }
}

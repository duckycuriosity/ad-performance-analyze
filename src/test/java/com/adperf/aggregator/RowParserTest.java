package com.adperf.aggregator;

import com.adperf.aggregator.RowParser.Status;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class RowParserTest {

    private final RowParser parser =
            new RowParser(CsvHeader.parse("campaign_id,date,impressions,clicks,spend,conversions"));

    @Test
    void parsesValidRow() {
        byte[] line = bytes("CMP001,2025-01-01,12000,300,45.50,12");

        assertThat(parser.parse(line, 0, line.length)).isEqualTo(Status.VALID);
        assertThat(campaignId(parser, line)).isEqualTo("CMP001");
        assertThat(parser.impressions()).isEqualTo(12000);
        assertThat(parser.clicks()).isEqualTo(300);
        assertThat(parser.spendCents()).isEqualTo(4550);
        assertThat(parser.conversions()).isEqualTo(12);
    }

    @Test
    void parsesOnlyTheGivenRange() {
        byte[] buffer = bytes("garbage\nCMP001,2025-01-01,12000,300,45.50,12\nmore");

        assertThat(parser.parse(buffer, 8, 44)).isEqualTo(Status.VALID);
        assertThat(campaignId(parser, buffer)).isEqualTo("CMP001");
        assertThat(parser.conversions()).isEqualTo(12);
    }

    @Test
    void readsFieldsByHeaderPosition() {
        RowParser reordered = new RowParser(CsvHeader.parse("spend,conversions,campaign_id,clicks,date,impressions"));
        byte[] line = bytes("45.50,12,CMP001,300,2025-01-01,12000");

        assertThat(reordered.parse(line, 0, line.length)).isEqualTo(Status.VALID);
        assertThat(campaignId(reordered, line)).isEqualTo("CMP001");
        assertThat(reordered.impressions()).isEqualTo(12000);
        assertThat(reordered.spendCents()).isEqualTo(4550);
    }

    @Test
    void trimsWhitespaceAroundValues() {
        byte[] line = bytes(" CMP001 , 2025-01-01 ,12000, 300,45.5 ,12\t");

        assertThat(parser.parse(line, 0, line.length)).isEqualTo(Status.VALID);
        assertThat(campaignId(parser, line)).isEqualTo("CMP001");
        assertThat(parser.spendCents()).isEqualTo(4550);
    }

    @Test
    void keepsNonAsciiCampaignIds() {
        byte[] line = bytes("Chiến dịch 1,2025-01-01,1,1,1.00,1");

        assertThat(parser.parse(line, 0, line.length)).isEqualTo(Status.VALID);
        assertThat(campaignId(parser, line)).isEqualTo("Chiến dịch 1");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "CMP001,2025-01-01,12000,300,45.50",
            "CMP001,2025-01-01,12000,300,45.50,12,7",
            "CMP001;2025-01-01;12000;300;45.50;12"})
    void rejectsWrongFieldCount(String line) {
        assertThat(parse(line)).isEqualTo(Status.WRONG_FIELD_COUNT);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            ",2025-01-01,12000,300,45.50,12",
            "   ,2025-01-01,12000,300,45.50,12",
            "CMP001,,12000,300,45.50,12",
            "CMP001,2025-01-01,12000,300,,12",
            "CMP001,2025-01-01,12000,300,45.50,"})
    void rejectsMissingValue(String line) {
        assertThat(parse(line)).isEqualTo(Status.MISSING_VALUE);
    }

    @ParameterizedTest
    @ValueSource(strings = {"2025-13-01", "2025-02-30", "2023-02-29", "4/18/2025", "2025-1-01", "2025/01/01"})
    void rejectsInvalidDate(String date) {
        assertThat(parse("CMP001," + date + ",12000,300,45.50,12")).isEqualTo(Status.INVALID_DATE);
    }

    @Test
    void acceptsLeapDay() {
        assertThat(parse("CMP001,2024-02-29,12000,300,45.50,12")).isEqualTo(Status.VALID);
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
        assertThat(parse(line)).isEqualTo(Status.INVALID_NUMBER);
    }

    @ParameterizedTest
    @CsvSource({"0, 0", "7, 700", "7.5, 750", "7.05, 705", "1394.62, 139462", "0.01, 1"})
    void parsesSpendIntoExactCents(String spend, long cents) {
        byte[] bytes = bytes(spend);
        assertThat(RowParser.parseCents(bytes, 0, bytes.length)).isEqualTo(cents);
    }

    private Status parse(String line) {
        byte[] bytes = bytes(line);
        return parser.parse(bytes, 0, bytes.length);
    }

    private static String campaignId(RowParser parser, byte[] line) {
        return new String(line, parser.idStart(), parser.idEnd() - parser.idStart(), StandardCharsets.UTF_8);
    }

    private static byte[] bytes(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }
}

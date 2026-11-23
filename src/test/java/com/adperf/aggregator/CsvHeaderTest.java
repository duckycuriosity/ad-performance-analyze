package com.adperf.aggregator;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CsvHeaderTest {

    @Test
    void mapsColumnsByNameInAnyOrder() {
        CsvHeader header = CsvHeader.parse("spend,conversions,campaign_id,clicks,date,impressions");

        assertThat(header.positionOf(CsvHeader.SPEND)).isZero();
        assertThat(header.positionOf(CsvHeader.CONVERSIONS)).isEqualTo(1);
        assertThat(header.positionOf(CsvHeader.CAMPAIGN_ID)).isEqualTo(2);
        assertThat(header.positionOf(CsvHeader.CLICKS)).isEqualTo(3);
        assertThat(header.positionOf(CsvHeader.DATE)).isEqualTo(4);
        assertThat(header.positionOf(CsvHeader.IMPRESSIONS)).isEqualTo(5);
    }

    @Test
    void ignoresCaseSpacesAndByteOrderMark() {
        CsvHeader header = CsvHeader.parse("﻿ Campaign_ID , DATE,impressions,clicks,spend,conversions ");

        assertThat(header.positionOf(CsvHeader.CAMPAIGN_ID)).isZero();
        assertThat(header.positionOf(CsvHeader.CONVERSIONS)).isEqualTo(5);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = "   ")
    void rejectsMissingHeader(String line) {
        assertThatThrownBy(() -> CsvHeader.parse(line)).isInstanceOf(InvalidInputException.class)
                .hasMessageContaining("no header");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "campaign_id,date,impressions,clicks,spend",
            "campaign_id,date,impressions,clicks,spend,conversions,extra",
            "campaign_id\tdate\timpressions\tclicks\tspend\tconversions"})
    void rejectsWrongColumnCount(String line) {
        assertThatThrownBy(() -> CsvHeader.parse(line)).isInstanceOf(InvalidInputException.class)
                .hasMessageContaining("exactly 6 columns");
    }

    @Test
    void rejectsDuplicateColumn() {
        assertThatThrownBy(() -> CsvHeader.parse("campaign_id,date,clicks,clicks,spend,conversions"))
                .isInstanceOf(InvalidInputException.class)
                .hasMessageContaining("'clicks' appears more than once");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "campaign_id,date,views,clicks,spend,conversions",
            "CMP001,2025-01-01,12000,300,45.50,12"})
    void rejectsUnknownColumns(String line) {
        assertThatThrownBy(() -> CsvHeader.parse(line)).isInstanceOf(InvalidInputException.class)
                .hasMessageContaining("unknown column(s)");
    }
}

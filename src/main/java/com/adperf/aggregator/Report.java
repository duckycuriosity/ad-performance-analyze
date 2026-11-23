package com.adperf.aggregator;

import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * Result of analysing one file.
 *
 * @param dataRows         non-blank lines after the header
 * @param validRows        rows that were aggregated
 * @param skippedByReason  number of skipped rows per reason
 * @param skippedSamples   the first few skipped rows, for diagnostics
 * @param campaignCount    distinct campaigns found
 * @param topByCtr         highest CTR first
 * @param topByCpa         lowest CPA first, campaigns without conversions excluded
 * @param elapsed          processing time
 */
record Report(
        long dataRows,
        long validRows,
        Map<RowParser.Status, Long> skippedByReason,
        List<SkippedRow> skippedSamples,
        int campaignCount,
        List<Campaign> topByCtr,
        List<Campaign> topByCpa,
        Duration elapsed) {

    long skippedRows() {
        return dataRows - validRows;
    }

    /** {@code lineNumber} is 1-based and counts the header as line 1. */
    record SkippedRow(long lineNumber, RowParser.Status reason, String content) {
    }
}

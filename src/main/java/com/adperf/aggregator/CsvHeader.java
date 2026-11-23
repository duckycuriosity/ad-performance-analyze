package com.adperf.aggregator;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * Validates the header line and records where each required column is.
 * Columns may come in any order, but all six names must be present exactly once and nothing else.
 */
final class CsvHeader {

    static final char DELIMITER = ',';

    /** Required column names. The index of a name is the column id used by {@link #positionOf}. */
    static final List<String> COLUMNS = List.of("campaign_id", "date", "impressions", "clicks", "spend", "conversions");
    static final int CAMPAIGN_ID = 0;
    static final int DATE = 1;
    static final int IMPRESSIONS = 2;
    static final int CLICKS = 3;
    static final int SPEND = 4;
    static final int CONVERSIONS = 5;

    private final int[] positions;

    private CsvHeader(int[] positions) {
        this.positions = positions;
    }

    static CsvHeader parse(String line) {
        if (line == null || line.isBlank()) {
            throw new InvalidInputException("File is empty or has no header line.");
        }
        if (line.charAt(0) == '﻿') {
            line = line.substring(1);
        }
        String[] names = line.split(String.valueOf(DELIMITER), -1);
        if (names.length != COLUMNS.size()) {
            throw new InvalidInputException("Header must have exactly %d columns but has %d: %s"
                    .formatted(COLUMNS.size(), names.length, line));
        }

        int[] positions = new int[COLUMNS.size()];
        Arrays.fill(positions, -1);
        List<String> unknown = new ArrayList<>();
        for (int position = 0; position < names.length; position++) {
            String name = names[position].trim().toLowerCase(Locale.ROOT);
            int column = COLUMNS.indexOf(name);
            if (column < 0) {
                unknown.add(names[position]);
            } else if (positions[column] >= 0) {
                throw new InvalidInputException("Header column '%s' appears more than once.".formatted(name));
            } else {
                positions[column] = position;
            }
        }
        if (!unknown.isEmpty()) {
            throw new InvalidInputException("Header has unknown column(s) %s. Expected: %s".formatted(unknown, COLUMNS));
        }
        return new CsvHeader(positions);
    }

    int positionOf(int column) {
        return positions[column];
    }
}

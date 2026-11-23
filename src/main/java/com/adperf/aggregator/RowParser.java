package com.adperf.aggregator;

/**
 * Parses one data line at a time.
 *
 * <p>This runs tens of millions of times, so it scans the line by index instead of using
 * {@link String#split} and parses numbers in place. The only allocation per row is the campaign id string,
 * which is the aggregation key. After {@link #parse} returns {@link Status#VALID}, the accessors return the
 * values of that row. Not thread-safe.
 */
final class RowParser {

    enum Status {
        VALID("valid"),
        WRONG_FIELD_COUNT("wrong number of fields"),
        MISSING_VALUE("missing value"),
        INVALID_DATE("date is not a valid yyyy-MM-dd"),
        INVALID_NUMBER("not a non-negative number");

        final String description;

        Status(String description) {
            this.description = description;
        }
    }

    private static final int FIELD_COUNT = CsvHeader.COLUMNS.size();
    private static final int MAX_INTEGER_DIGITS = 18;

    private final CsvHeader header;
    private final int[] fieldStarts = new int[FIELD_COUNT];
    private final int[] fieldEnds = new int[FIELD_COUNT];

    private String campaignId;
    private long impressions;
    private long clicks;
    private long spendCents;
    private long conversions;

    RowParser(CsvHeader header) {
        this.header = header;
    }

    Status parse(String line) {
        if (!splitFields(line)) {
            return Status.WRONG_FIELD_COUNT;
        }
        for (int field = 0; field < FIELD_COUNT; field++) {
            if (fieldStarts[field] == fieldEnds[field]) {
                return Status.MISSING_VALUE;
            }
        }
        if (!isValidIsoDate(line, start(CsvHeader.DATE), end(CsvHeader.DATE))) {
            return Status.INVALID_DATE;
        }
        impressions = parseNonNegativeLong(line, start(CsvHeader.IMPRESSIONS), end(CsvHeader.IMPRESSIONS));
        clicks = parseNonNegativeLong(line, start(CsvHeader.CLICKS), end(CsvHeader.CLICKS));
        conversions = parseNonNegativeLong(line, start(CsvHeader.CONVERSIONS), end(CsvHeader.CONVERSIONS));
        spendCents = parseCents(line, start(CsvHeader.SPEND), end(CsvHeader.SPEND));
        if (impressions < 0 || clicks < 0 || conversions < 0 || spendCents < 0) {
            return Status.INVALID_NUMBER;
        }
        campaignId = line.substring(start(CsvHeader.CAMPAIGN_ID), end(CsvHeader.CAMPAIGN_ID));
        return Status.VALID;
    }

    String campaignId() {
        return campaignId;
    }

    long impressions() {
        return impressions;
    }

    long clicks() {
        return clicks;
    }

    long spendCents() {
        return spendCents;
    }

    long conversions() {
        return conversions;
    }

    /** Records the whitespace-trimmed [start, end) range of each field. Returns false if the count is wrong. */
    private boolean splitFields(String line) {
        int field = 0;
        int fieldStart = 0;
        for (int i = 0; i <= line.length(); i++) {
            if (i < line.length() && line.charAt(i) != CsvHeader.DELIMITER) {
                continue;
            }
            if (field == FIELD_COUNT) {
                return false;
            }
            int start = fieldStart;
            int end = i;
            while (start < end && Character.isWhitespace(line.charAt(start))) {
                start++;
            }
            while (end > start && Character.isWhitespace(line.charAt(end - 1))) {
                end--;
            }
            fieldStarts[field] = start;
            fieldEnds[field] = end;
            field++;
            fieldStart = i + 1;
        }
        return field == FIELD_COUNT;
    }

    private int start(int column) {
        return fieldStarts[header.positionOf(column)];
    }

    private int end(int column) {
        return fieldEnds[header.positionOf(column)];
    }

    /** Returns the value of a plain non-negative integer, or -1 if the text is anything else. */
    static long parseNonNegativeLong(CharSequence text, int start, int end) {
        if (start == end || end - start > MAX_INTEGER_DIGITS) {
            return -1;
        }
        long value = 0;
        for (int i = start; i < end; i++) {
            int digit = text.charAt(i) - '0';
            if (digit < 0 || digit > 9) {
                return -1;
            }
            value = value * 10 + digit;
        }
        return value;
    }

    /**
     * Parses a non-negative decimal with at most two fractional digits into cents ("64.3" -> 6430),
     * or returns -1. Integer cents keep sums over millions of rows exact.
     */
    static long parseCents(CharSequence text, int start, int end) {
        int dot = start;
        while (dot < end && text.charAt(dot) != '.') {
            dot++;
        }
        long units = parseNonNegativeLong(text, start, dot);
        if (units < 0 || units > Long.MAX_VALUE / 100) {
            return -1;
        }
        if (dot == end) {
            return units * 100;
        }
        int fractionDigits = end - dot - 1;
        long fraction = parseNonNegativeLong(text, dot + 1, end);
        if (fraction < 0 || fractionDigits > 2) {
            return -1;
        }
        return units * 100 + (fractionDigits == 1 ? fraction * 10 : fraction);
    }

    /** Checks for a real calendar date in yyyy-MM-dd form without allocating. */
    static boolean isValidIsoDate(CharSequence text, int start, int end) {
        if (end - start != 10 || text.charAt(start + 4) != '-' || text.charAt(start + 7) != '-') {
            return false;
        }
        long year = parseNonNegativeLong(text, start, start + 4);
        long month = parseNonNegativeLong(text, start + 5, start + 7);
        long day = parseNonNegativeLong(text, start + 8, start + 10);
        if (year < 1 || month < 1 || month > 12 || day < 1) {
            return false;
        }
        boolean leapYear = (year % 4 == 0 && year % 100 != 0) || year % 400 == 0;
        int daysInMonth = switch ((int) month) {
            case 2 -> leapYear ? 29 : 28;
            case 4, 6, 9, 11 -> 30;
            default -> 31;
        };
        return day <= daysInMonth;
    }
}

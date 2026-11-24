package com.adperf.aggregator;

/**
 * Parses one data line directly from the raw file bytes.
 *
 * <p>This runs tens of millions of times, so it never decodes the line into a {@code String}: it scans the
 * bytes for delimiters and parses numbers and the date in place. The campaign id is exposed as a byte range
 * so that {@link CampaignTable} can look it up without allocating. After {@link #parse} returns
 * {@link Status#VALID}, the accessors describe that row. Not thread-safe; each worker has its own instance.
 */
final class RowParser {

    enum Status {
        VALID("valid"),
        WRONG_FIELD_COUNT("wrong number of fields"),
        MISSING_VALUE("missing value"),
        INVALID_DATE("date is not a valid yyyy-MM-dd"),
        INVALID_NUMBER("not a non-negative number"),
        LINE_TOO_LONG("line too long");

        final String description;

        Status(String description) {
            this.description = description;
        }
    }

    private static final int FIELD_COUNT = CsvHeader.COLUMNS.size();
    private static final int MAX_INTEGER_DIGITS = 18;

    private final int[] positions = new int[FIELD_COUNT];
    private final int[] fieldStarts = new int[FIELD_COUNT];
    private final int[] fieldEnds = new int[FIELD_COUNT];

    private int idStart;
    private int idEnd;
    private long impressions;
    private long clicks;
    private long spendCents;
    private long conversions;

    RowParser(CsvHeader header) {
        for (int column = 0; column < FIELD_COUNT; column++) {
            positions[column] = header.positionOf(column);
        }
    }

    /** Parses {@code bytes[from, to)}, which must not include the line terminator. */
    Status parse(byte[] bytes, int from, int to) {
        if (!splitFields(bytes, from, to)) {
            return Status.WRONG_FIELD_COUNT;
        }
        for (int field = 0; field < FIELD_COUNT; field++) {
            if (fieldStarts[field] == fieldEnds[field]) {
                return Status.MISSING_VALUE;
            }
        }
        if (!isValidIsoDate(bytes, start(CsvHeader.DATE), end(CsvHeader.DATE))) {
            return Status.INVALID_DATE;
        }
        impressions = parseNonNegativeLong(bytes, start(CsvHeader.IMPRESSIONS), end(CsvHeader.IMPRESSIONS));
        clicks = parseNonNegativeLong(bytes, start(CsvHeader.CLICKS), end(CsvHeader.CLICKS));
        conversions = parseNonNegativeLong(bytes, start(CsvHeader.CONVERSIONS), end(CsvHeader.CONVERSIONS));
        spendCents = parseCents(bytes, start(CsvHeader.SPEND), end(CsvHeader.SPEND));
        if (impressions < 0 || clicks < 0 || conversions < 0 || spendCents < 0) {
            return Status.INVALID_NUMBER;
        }
        idStart = start(CsvHeader.CAMPAIGN_ID);
        idEnd = end(CsvHeader.CAMPAIGN_ID);
        return Status.VALID;
    }

    int idStart() {
        return idStart;
    }

    int idEnd() {
        return idEnd;
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
    private boolean splitFields(byte[] bytes, int from, int to) {
        int field = 0;
        int fieldStart = from;
        for (int i = from; i <= to; i++) {
            if (i < to && bytes[i] != CsvHeader.DELIMITER) {
                continue;
            }
            if (field == FIELD_COUNT) {
                return false;
            }
            int start = fieldStart;
            int end = i;
            while (start < end && isWhitespace(bytes[start])) {
                start++;
            }
            while (end > start && isWhitespace(bytes[end - 1])) {
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
        return fieldStarts[positions[column]];
    }

    private int end(int column) {
        return fieldEnds[positions[column]];
    }

    /** Space, tab and other ASCII control characters. Bytes of multi-byte UTF-8 characters are negative. */
    private static boolean isWhitespace(byte b) {
        return b >= 0 && b <= ' ';
    }

    /** Returns the value of a plain non-negative integer, or -1 if the bytes are anything else. */
    static long parseNonNegativeLong(byte[] bytes, int start, int end) {
        if (start == end || end - start > MAX_INTEGER_DIGITS) {
            return -1;
        }
        long value = 0;
        for (int i = start; i < end; i++) {
            int digit = bytes[i] - '0';
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
    static long parseCents(byte[] bytes, int start, int end) {
        int dot = start;
        while (dot < end && bytes[dot] != '.') {
            dot++;
        }
        long units = parseNonNegativeLong(bytes, start, dot);
        if (units < 0 || units > Long.MAX_VALUE / 100) {
            return -1;
        }
        if (dot == end) {
            return units * 100;
        }
        int fractionDigits = end - dot - 1;
        long fraction = parseNonNegativeLong(bytes, dot + 1, end);
        if (fraction < 0 || fractionDigits > 2) {
            return -1;
        }
        return units * 100 + (fractionDigits == 1 ? fraction * 10 : fraction);
    }

    /** Checks for a real calendar date in yyyy-MM-dd form without allocating. */
    static boolean isValidIsoDate(byte[] bytes, int start, int end) {
        if (end - start != 10 || bytes[start + 4] != '-' || bytes[start + 7] != '-') {
            return false;
        }
        long year = parseNonNegativeLong(bytes, start, start + 4);
        long month = parseNonNegativeLong(bytes, start + 5, start + 7);
        long day = parseNonNegativeLong(bytes, start + 8, start + 10);
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

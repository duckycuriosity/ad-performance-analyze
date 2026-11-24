package com.adperf.aggregator;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Processes one chunk of the file, {@code [start, end)}, on one worker thread.
 *
 * <p>The chunk boundaries are line starts (see {@link Aggregator}), so every line is handled by exactly one
 * chunk. Bytes are read through a fixed buffer with positional reads, which are safe to run concurrently on a
 * shared {@link FileChannel}. A line split across two buffer fills is moved to the front of the buffer before
 * the next read. Line numbers in the result are local to the chunk; the aggregator turns them into file line
 * numbers.
 */
final class ChunkReader {

    static final int DEFAULT_BUFFER_SIZE = 1 << 20;

    private static final int MAX_SKIPPED_SAMPLES = 10;
    private static final int MAX_SAMPLE_LENGTH = 200;

    /**
     * @param lines           every line in the chunk, including blank ones (used to compute line numbers)
     * @param skippedSamples  first few skipped rows, with line numbers counted from the chunk start
     */
    record Result(
            List<Campaign> campaigns,
            long lines,
            long dataRows,
            long validRows,
            Map<RowParser.Status, Long> skippedByReason,
            List<Report.SkippedRow> skippedSamples) {
    }

    private final RowParser parser;
    private final int bufferSize;
    private final CampaignTable table = new CampaignTable();
    private final Map<RowParser.Status, Long> skippedByReason = new EnumMap<>(RowParser.Status.class);
    private final List<Report.SkippedRow> skippedSamples = new ArrayList<>();
    private long lines;
    private long dataRows;
    private long validRows;

    ChunkReader(CsvHeader header, int bufferSize) {
        this.parser = new RowParser(header);
        this.bufferSize = bufferSize;
    }

    Result read(FileChannel channel, long start, long end) throws IOException {
        byte[] buffer = new byte[bufferSize];
        int filled = 0;
        long position = start;
        boolean skippingLongLine = false;

        while (true) {
            int toRead = (int) Math.min(buffer.length - filled, end - position);
            int read = toRead > 0 ? channel.read(ByteBuffer.wrap(buffer, filled, toRead), position) : 0;
            if (read < 0) {
                throw new IOException("File became shorter while it was being read.");
            }
            position += read;
            filled += read;
            boolean lastFill = position >= end;

            int lineStart = 0;
            for (int i = 0; i < filled; i++) {
                if (buffer[i] == '\n') {
                    if (skippingLongLine) {
                        skippingLongLine = false;
                    } else {
                        line(buffer, lineStart, i);
                    }
                    lineStart = i + 1;
                }
            }

            if (lastFill) {
                if (lineStart < filled && !skippingLongLine) {
                    line(buffer, lineStart, filled);
                }
                return new Result(table.campaigns(), lines, dataRows, validRows, skippedByReason, skippedSamples);
            }

            filled -= lineStart;
            if (filled == buffer.length) {
                if (!skippingLongLine) {
                    lineTooLong(buffer);
                    skippingLongLine = true;
                }
                filled = 0;
            } else {
                System.arraycopy(buffer, lineStart, buffer, 0, filled);
            }
        }
    }

    private void line(byte[] bytes, int from, int to) {
        lines++;
        if (to > from && bytes[to - 1] == '\r') {
            to--;
        }
        if (isBlank(bytes, from, to)) {
            return;
        }
        dataRows++;
        RowParser.Status status = parser.parse(bytes, from, to);
        if (status == RowParser.Status.VALID) {
            validRows++;
            table.add(bytes, parser.idStart(), parser.idEnd(),
                    parser.impressions(), parser.clicks(), parser.spendCents(), parser.conversions());
        } else {
            skip(status, bytes, from, to);
        }
    }

    /** A line that does not fit in the buffer cannot be a valid row; its remainder is skipped. */
    private void lineTooLong(byte[] bytes) {
        lines++;
        dataRows++;
        skip(RowParser.Status.LINE_TOO_LONG, bytes, 0, bytes.length);
    }

    private void skip(RowParser.Status reason, byte[] bytes, int from, int to) {
        skippedByReason.merge(reason, 1L, Long::sum);
        if (skippedSamples.size() < MAX_SKIPPED_SAMPLES) {
            int length = Math.min(to - from, MAX_SAMPLE_LENGTH);
            String content = new String(bytes, from, length, StandardCharsets.UTF_8) + (to - from > length ? "..." : "");
            skippedSamples.add(new Report.SkippedRow(lines, reason, content));
        }
    }

    private static boolean isBlank(byte[] bytes, int from, int to) {
        for (int i = from; i < to; i++) {
            if (bytes[i] < 0 || bytes[i] > ' ') {
                return false;
            }
        }
        return true;
    }
}

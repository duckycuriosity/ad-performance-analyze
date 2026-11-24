package com.adperf.aggregator;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * Splits the file into byte ranges, aggregates each range on its own thread, then merges the results.
 *
 * <pre>
 * 1. Read and validate the header line; data starts right after it.
 * 2. Cut the data into N equal byte ranges and move each cut forward to the start of the next line.
 * 3. Each worker reads its range and sums rows into its own table (no shared state, no locks).
 * 4. Merge the N tables per campaign id, then pick the top 10 by CTR and by CPA.
 * </pre>
 *
 * Memory is O(threads x buffer + campaigns), independent of the file size.
 */
final class Aggregator {

    static final int TOP_N = 10;

    private static final int MAX_HEADER_BYTES = 64 * 1024;
    private static final int MAX_SKIPPED_SAMPLES = 10;

    private Aggregator() {
    }

    static Report analyze(Path file, int threads) throws IOException {
        return analyze(file, threads, ChunkReader.DEFAULT_BUFFER_SIZE);
    }

    static Report analyze(Path file, int threads, int bufferSize) throws IOException {
        long startNanos = System.nanoTime();
        try (FileChannel channel = FileChannel.open(file)) {
            long size = channel.size();
            long dataStart = headerEnd(channel, size);
            CsvHeader header = CsvHeader.parse(readHeaderLine(channel, dataStart));
            long[] bounds = chunkBounds(channel, dataStart, size, threads);
            List<ChunkReader.Result> chunks = readChunks(channel, header, bounds, bufferSize);
            return merge(chunks, Duration.ofNanos(System.nanoTime() - startNanos));
        }
    }

    /** Returns the offset just after the header line, or the file size if the file is only a header. */
    private static long headerEnd(FileChannel channel, long size) throws IOException {
        byte[] head = readBytes(channel, 0, (int) Math.min(size, MAX_HEADER_BYTES));
        for (int i = 0; i < head.length; i++) {
            if (head[i] == '\n') {
                return i + 1;
            }
        }
        if (size > MAX_HEADER_BYTES) {
            throw new InvalidInputException("Header line is longer than %d bytes.".formatted(MAX_HEADER_BYTES));
        }
        return size;
    }

    private static String readHeaderLine(FileChannel channel, long headerEnd) throws IOException {
        String line = new String(readBytes(channel, 0, (int) headerEnd), StandardCharsets.UTF_8);
        return line.strip();
    }

    /**
     * Returns N + 1 offsets: chunk i is {@code [bounds[i], bounds[i + 1])}. Every inner cut is moved to the
     * next line start, so no line is split between two workers. Chunks can be empty for tiny files.
     */
    static long[] chunkBounds(FileChannel channel, long dataStart, long size, int chunks) throws IOException {
        long[] bounds = new long[chunks + 1];
        bounds[0] = dataStart;
        bounds[chunks] = size;
        long dataSize = size - dataStart;
        for (int i = 1; i < chunks; i++) {
            long cut = dataStart + dataSize * i / chunks;
            bounds[i] = Math.max(bounds[i - 1], nextLineStart(channel, cut, size));
        }
        return bounds;
    }

    /** The smallest line start at or after {@code offset}: right after a '\n', or the end of the file. */
    private static long nextLineStart(FileChannel channel, long offset, long size) throws IOException {
        ByteBuffer buffer = ByteBuffer.allocate(8 * 1024);
        long position = offset - 1;
        while (position < size) {
            buffer.clear();
            int read = channel.read(buffer, position);
            for (int i = 0; i < read; i++) {
                if (buffer.get(i) == '\n') {
                    return position + i + 1;
                }
            }
            position += Math.max(read, 1);
        }
        return size;
    }

    private static List<ChunkReader.Result> readChunks(
            FileChannel channel, CsvHeader header, long[] bounds, int bufferSize) throws IOException {
        int chunks = bounds.length - 1;
        try (ExecutorService pool = Executors.newFixedThreadPool(chunks)) {
            List<Future<ChunkReader.Result>> futures = new ArrayList<>(chunks);
            for (int i = 0; i < chunks; i++) {
                long start = bounds[i];
                long end = bounds[i + 1];
                futures.add(pool.submit(() -> new ChunkReader(header, bufferSize).read(channel, start, end)));
            }
            List<ChunkReader.Result> results = new ArrayList<>(chunks);
            for (Future<ChunkReader.Result> future : futures) {
                results.add(await(future));
            }
            return results;
        }
    }

    private static ChunkReader.Result await(Future<ChunkReader.Result> future) throws IOException {
        try {
            return future.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while reading the file.", e);
        } catch (ExecutionException e) {
            if (e.getCause() instanceof IOException io) {
                throw io;
            }
            throw new IllegalStateException("Worker failed", e.getCause());
        }
    }

    /** Chunks are merged in file order so local line numbers can be shifted to file line numbers. */
    private static Report merge(List<ChunkReader.Result> chunks, Duration elapsed) {
        Map<String, Campaign> campaignsById = new HashMap<>();
        Map<RowParser.Status, Long> skippedByReason = new EnumMap<>(RowParser.Status.class);
        List<Report.SkippedRow> skippedSamples = new ArrayList<>();
        long linesBefore = 1;
        long dataRows = 0;
        long validRows = 0;

        for (ChunkReader.Result chunk : chunks) {
            chunk.campaigns().forEach(campaign -> campaignsById.merge(campaign.id(), campaign, Campaign::plus));
            chunk.skippedByReason().forEach((reason, count) -> skippedByReason.merge(reason, count, Long::sum));
            for (Report.SkippedRow row : chunk.skippedSamples()) {
                if (skippedSamples.size() < MAX_SKIPPED_SAMPLES) {
                    skippedSamples.add(new Report.SkippedRow(linesBefore + row.lineNumber(), row.reason(), row.content()));
                }
            }
            linesBefore += chunk.lines();
            dataRows += chunk.dataRows();
            validRows += chunk.validRows();
        }

        Collection<Campaign> campaigns = campaignsById.values();
        return new Report(
                dataRows,
                validRows,
                skippedByReason,
                skippedSamples,
                campaigns.size(),
                top(campaigns.stream().filter(Campaign::hasCtr).toList(), TOP_N, Campaign.BY_CTR_DESC),
                top(campaigns.stream().filter(Campaign::hasCpa).toList(), TOP_N, Campaign.BY_CPA_ASC),
                elapsed);
    }

    /**
     * Returns the best {@code n} items, best first, in O(K log n) using a heap of size n.
     * The heap keeps the worst of the current best items at its head so it can be evicted cheaply.
     */
    static <T> List<T> top(Collection<T> items, int n, Comparator<? super T> bestFirst) {
        PriorityQueue<T> heap = new PriorityQueue<>(n + 1, bestFirst.reversed());
        for (T item : items) {
            heap.offer(item);
            if (heap.size() > n) {
                heap.poll();
            }
        }
        List<T> best = new ArrayList<>(heap);
        best.sort(bestFirst);
        return best;
    }

    private static byte[] readBytes(FileChannel channel, long position, int length) throws IOException {
        ByteBuffer buffer = ByteBuffer.allocate(length);
        while (buffer.hasRemaining()) {
            if (channel.read(buffer, position + buffer.position()) < 0) {
                break;
            }
        }
        return buffer.array();
    }
}

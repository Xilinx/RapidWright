/*
 * Copyright (c) 2026, Advanced Micro Devices, Inc.
 * All rights reserved.
 *
 * This file is part of RapidWright.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 */

package com.xilinx.rapidwright.timing.sdf;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Future;

import com.xilinx.rapidwright.util.ParallelismTools;

/**
 * Locates the positions at which an SDF file can safely be split for parallel parsing, and the line
 * numbers of those positions.
 *
 * This is a pure byte scan: no tokenizing, no allocation per line, just a search for newlines and a
 * short keyword comparison at each line start. It runs at close to memory bandwidth and is itself
 * parallel, so it costs a small fraction of the parse it enables.
 *
 * <b>Why the split points are exact rather than guessed.</b> Every quoted string Vivado writes lies
 * entirely on one line, which was verified across UltraScale+ and Versal output at both process
 * corners and in both write modes. A position immediately after a newline therefore cannot be
 * inside a string, so a scanner that starts at an arbitrary byte and advances to the next line
 * start is synchronised with certainty. From there an anchor is accepted only if the line begins
 * with optional spaces, then {@code (CELL} or {@code (INTERCONNECT}, then a character that ends a
 * token. That last condition is not optional: half of the byte occurrences of {@code (CELL} in a
 * Vivado SDF are the start of {@code (CELLTYPE}.
 *
 * <b>Why {@code INTERCONNECT} is an anchor too.</b> The top-level cell holds every
 * {@code INTERCONNECT} in the design and is typically most of the file. Splitting only at cells
 * would hand one worker the majority of the work and cap the achievable speedup near two,
 * regardless of thread count.
 *
 * Only one anchor per chunk is materialised, so memory stays proportional to the thread count
 * rather than to the tens of millions of anchors a large file contains.
 */
public class SdfChunkIndexer {

    /** Bytes read at a time while scanning. */
    private static final int SCAN_BUFFER_SIZE = 1 << 20;

    /**
     * Bytes of overlap read past the end of a scan range, so a keyword beginning just before the
     * range boundary can still be matched. Comfortably longer than the longest anchor keyword plus
     * any plausible indentation.
     */
    private static final int OVERLAP = 64;

    private static final byte[] CELL_BYTES = "(CELL".getBytes(StandardCharsets.US_ASCII);

    private static final byte[] INTERCONNECT_BYTES =
            "(INTERCONNECT".getBytes(StandardCharsets.US_ASCII);

    private SdfChunkIndexer() {
    }

    /**
     * The position at which a parse worker begins.
     */
    public static final class Anchor {

        /** Byte offset of the opening parenthesis, always a line's first non-space character. */
        public final long byteOffset;

        /** 1-based line number at {@link #byteOffset}. */
        public final long lineNumber;

        /**
         * True when the anchor is a {@code (CELL}, false when it is an {@code (INTERCONNECT} inside
         * the top-level cell. A worker starting at the latter parses a fragment of the top cell's
         * delay entries rather than whole cells.
         */
        public final boolean isCell;

        /**
         * @param byteOffset Byte offset of the anchor.
         * @param lineNumber 1-based line number at that offset.
         * @param isCell True for a CELL anchor, false for an INTERCONNECT anchor.
         */
        public Anchor(long byteOffset, long lineNumber, boolean isCell) {
            this.byteOffset = byteOffset;
            this.lineNumber = lineNumber;
            this.isCell = isCell;
        }

        @Override
        public String toString() {
            return (isCell ? "CELL@" : "INTERCONNECT@") + byteOffset + " (line " + lineNumber + ")";
        }
    }

    /** Per-range results, combined serially into absolute line numbers. */
    private static final class RangeResult {

        /** First byte of the range, always a line start. */
        long snapStart;

        /** One past the last byte of the range. */
        long snapEnd;

        /** The first anchor at or after {@link #snapStart}, or null if the range has none. */
        Anchor anchor;

        /** Newlines in {@code [snapStart, anchor.byteOffset)}. */
        long newlinesBeforeAnchor;

        /** Newlines in {@code [snapStart, snapEnd)}. */
        long newlinesTotal;
    }

    /**
     * Finds up to {@code chunks} split points spread evenly through the file by byte count.
     *
     * The first split point is always the start of the file, so that the worker owning it also
     * parses the {@code DELAYFILE} header.
     *
     * @param fileName The file to index; must be uncompressed and seekable.
     * @param fileSize Size of the file in bytes.
     * @param chunks Desired number of chunks; the result may be shorter if the file has too few
     *               anchors to fill them.
     * @return The split points, in ascending byte order, starting with offset 0.
     */
    public static List<Anchor> findSplitPoints(Path fileName, long fileSize, int chunks) {
        if (chunks <= 1 || fileSize <= 0) {
            List<Anchor> single = new ArrayList<>(1);
            single.add(new Anchor(0, 1, true));
            return single;
        }

        try (FileChannel channel = FileChannel.open(fileName, StandardOpenOption.READ)) {
            // Phase 1: snap each ideal boundary forward to the following line start, in parallel.
            long[] snaps = new long[chunks + 1];
            snaps[0] = 0;
            snaps[chunks] = fileSize;
            List<Future<long[]>> snapFutures = new ArrayList<>(chunks - 1);
            for (int r = 1; r < chunks; r++) {
                final int index = r;
                final long boundary = (long) (fileSize * (double) r / chunks);
                snapFutures.add(ParallelismTools.submit(
                        () -> new long[] {index, findLineStartAtOrAfter(channel, boundary,
                                fileSize)}));
            }
            for (Future<long[]> f : snapFutures) {
                long[] pair = ParallelismTools.get(f);
                snaps[(int) pair[0]] = pair[1];
            }
            // A boundary that found no newline lands at end of file; keep the array monotonic so
            // the ranges below still tile the file.
            for (int r = 1; r <= chunks; r++) {
                if (snaps[r] < snaps[r - 1]) {
                    snaps[r] = snaps[r - 1];
                }
            }

            // Phase 2: scan each range for its first anchor and its newline count, in parallel.
            List<RangeResult> results = new ArrayList<>(chunks);
            List<Future<RangeResult>> scanFutures = new ArrayList<>(chunks);
            for (int r = 0; r < chunks; r++) {
                final long from = snaps[r];
                final long to = snaps[r + 1];
                scanFutures.add(ParallelismTools.submit(() -> scanRange(channel, from, to,
                        fileSize)));
            }
            for (Future<RangeResult> f : scanFutures) {
                results.add(ParallelismTools.get(f));
            }

            // Phase 3: turn range-relative newline counts into absolute line numbers.
            List<Anchor> found = new ArrayList<>(chunks);
            long lineBase = 1;
            for (RangeResult result : results) {
                if (result.anchor != null) {
                    long line = lineBase + result.newlinesBeforeAnchor;
                    found.add(new Anchor(result.anchor.byteOffset, line, result.anchor.isCell));
                }
                lineBase += result.newlinesTotal;
            }

            // The first worker starts at offset 0 because it also parses the DELAYFILE header.
            // Recognising the end of that header means consuming the first cell's "(CELL", so the
            // first worker necessarily owns that cell too and no other chunk may begin there.
            List<Anchor> splitPoints = new ArrayList<>(found.size() + 1);
            splitPoints.add(new Anchor(0, 1, true));
            long firstCellOffset = found.isEmpty() ? Long.MAX_VALUE : found.get(0).byteOffset;
            for (Anchor anchor : found) {
                if (anchor.byteOffset > firstCellOffset) {
                    splitPoints.add(anchor);
                }
            }
            return dedupe(splitPoints);
        } catch (IOException e) {
            throw new UncheckedIOException("ERROR: Couldn't read file : " + fileName, e);
        }
    }

    /**
     * Removes duplicate and out-of-order split points, which can arise when a range is so small
     * that its first anchor is also the next range's.
     *
     * @param splitPoints The raw split points.
     * @return The strictly increasing subsequence.
     */
    private static List<Anchor> dedupe(List<Anchor> splitPoints) {
        List<Anchor> result = new ArrayList<>(splitPoints.size());
        long previous = -1;
        for (Anchor anchor : splitPoints) {
            if (anchor.byteOffset > previous) {
                result.add(anchor);
                previous = anchor.byteOffset;
            }
        }
        return result;
    }

    /**
     * Finds the first byte following a newline at or after the given position.
     *
     * @param channel The file.
     * @param from Position to start searching from.
     * @param fileSize Size of the file.
     * @return The line start, or {@code fileSize} if no newline follows.
     * @throws IOException If reading fails.
     */
    private static long findLineStartAtOrAfter(FileChannel channel, long from, long fileSize)
            throws IOException {
        ByteBuffer buffer = ByteBuffer.allocate(SCAN_BUFFER_SIZE);
        long position = from;
        while (position < fileSize) {
            buffer.clear();
            int read = readFully(channel, buffer, position);
            if (read <= 0) {
                break;
            }
            byte[] array = buffer.array();
            for (int i = 0; i < read; i++) {
                if (array[i] == '\n') {
                    return position + i + 1;
                }
            }
            position += read;
        }
        return fileSize;
    }

    /**
     * Scans one range for its first anchor and its newline count.
     *
     * @param channel The file.
     * @param from First byte of the range; must be a line start.
     * @param to One past the last byte of the range.
     * @param fileSize Size of the file, used to bound the overlap read.
     * @return The range's contribution to the index.
     * @throws IOException If reading fails.
     */
    private static RangeResult scanRange(FileChannel channel, long from, long to, long fileSize)
            throws IOException {
        RangeResult result = new RangeResult();
        result.snapStart = from;
        result.snapEnd = to;
        if (from >= to) {
            return result;
        }

        // Read a little past the range so a keyword starting near its end can still be matched.
        long readEnd = Math.min(fileSize, to + OVERLAP);
        ByteBuffer buffer = ByteBuffer.allocate(SCAN_BUFFER_SIZE + OVERLAP);
        long position = from;
        boolean atLineStart = true;
        long newlines = 0;
        boolean anchorFound = false;

        while (position < to) {
            buffer.clear();
            int want = (int) Math.min(buffer.capacity(), readEnd - position);
            buffer.limit(want);
            int read = readFully(channel, buffer, position);
            if (read <= 0) {
                break;
            }
            byte[] array = buffer.array();
            // Only positions strictly inside the range may start an anchor or be counted; the
            // overlap exists solely so a match can be completed.
            int limit = (int) Math.min(read, to - position);

            for (int i = 0; i < limit; i++) {
                if (atLineStart && !anchorFound) {
                    int kind = matchAnchor(array, i, read);
                    if (kind != 0) {
                        result.anchor = new Anchor(position + i, 0, kind == 1);
                        result.newlinesBeforeAnchor = newlines;
                        anchorFound = true;
                    }
                }
                byte b = array[i];
                if (b == '\n') {
                    newlines++;
                    atLineStart = true;
                } else if (b != ' ') {
                    // Leading spaces do not end the "still at the start of the line" state, since
                    // Vivado indents nested constructs.
                    atLineStart = false;
                }
            }
            position += read;
            if (read < want) {
                break;
            }
        }

        result.newlinesTotal = newlines;
        return result;
    }

    /**
     * Tests whether an anchor keyword starts at the given position.
     *
     * The keyword must be followed by a character that ends a token. Without that check every
     * {@code (CELLTYPE} in the file would be mistaken for a {@code (CELL}, since one is a byte
     * prefix of the other.
     *
     * @param array Buffer to match against.
     * @param i Position within the buffer, known to be at the start of a line's content.
     * @param limit One past the last valid byte in the buffer.
     * @return 1 for {@code (CELL}, 2 for {@code (INTERCONNECT}, 0 for no match.
     */
    private static int matchAnchor(byte[] array, int i, int limit) {
        if (i >= limit || array[i] != '(') {
            return 0;
        }
        if (matchesKeyword(array, i, limit, CELL_BYTES)) {
            return 1;
        }
        if (matchesKeyword(array, i, limit, INTERCONNECT_BYTES)) {
            return 2;
        }
        return 0;
    }

    private static boolean matchesKeyword(byte[] array, int i, int limit, byte[] keyword) {
        if (i + keyword.length >= limit) {
            return false;
        }
        for (int k = 0; k < keyword.length; k++) {
            if (array[i + k] != keyword[k]) {
                return false;
            }
        }
        byte next = array[i + keyword.length];
        return next == ' ' || next == '\n' || next == '\r' || next == '\t';
    }

    /**
     * Reads until the buffer is full or the file ends.
     *
     * @param channel The file.
     * @param buffer Destination; its position is left at the number of bytes read.
     * @param position Absolute position to read from.
     * @return The number of bytes read.
     * @throws IOException If reading fails.
     */
    private static int readFully(FileChannel channel, ByteBuffer buffer, long position)
            throws IOException {
        int total = 0;
        while (buffer.hasRemaining()) {
            int read = channel.read(buffer, position + total);
            if (read < 0) {
                break;
            }
            total += read;
        }
        return total;
    }
}

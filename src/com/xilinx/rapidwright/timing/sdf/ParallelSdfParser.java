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
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Future;

import com.xilinx.rapidwright.tests.CodePerfTracker;
import com.xilinx.rapidwright.util.ParallelismTools;
import com.xilinx.rapidwright.util.StringPool;

/**
 * A parallel SDF parser.
 *
 * The file is indexed once by {@link SdfChunkIndexer} — a cheap byte scan that finds line-start
 * {@code (CELL} and {@code (INTERCONNECT} positions — and then parsed by one worker per chunk. Two
 * properties of that index are what make this both fast and safe.
 *
 * The split points are <b>exact</b>, not guesses. Vivado never writes a quoted string spanning a
 * line, so a scanner that advances to a line start is synchronised with certainty, and a candidate
 * is accepted only when the keyword is followed by a token terminator. There is consequently no
 * resynchronisation heuristic to get wrong and no re-parse loop to recover from a bad guess. The
 * result is nevertheless checked: chunk offsets must increase strictly, exactly one chunk may close
 * the file, and the pieces of the top-level cell must fit together.
 *
 * The split points are also <b>balanced by bytes</b>. Splitting only at cells would be nearly
 * useless here, because the top-level cell holds every {@code INTERCONNECT} in the design and is
 * typically 50 to 90 percent of the file; one worker would get almost all the work. Indexing
 * {@code INTERCONNECT} as well lets the top cell be divided, and the fragments are reassembled in
 * chunk order so the original entry order is preserved exactly.
 *
 * A gzipped input is decompressed to a temporary file first, since a gzip stream cannot be seeked
 * and having every worker decompress from the start would cost more than the parallelism saves.
 */
public class ParallelSdfParser {

    /**
     * Minimum bytes per worker. Below this the indexing pass and thread hand-off cost more than
     * they save, so the file is parsed serially instead.
     */
    public static final long MIN_BYTES_PER_THREAD = 4L * 1024 * 1024;

    private ParallelSdfParser() {
    }

    /**
     * Chooses how many workers to use for a file of the given size.
     *
     * @param fileSize Uncompressed size in bytes.
     * @param maxThreads Upper bound requested by the caller, or 0 for no explicit bound.
     * @return The number of workers, at least 1.
     */
    public static int calcThreads(long fileSize, int maxThreads) {
        if (!ParallelismTools.getParallel()) {
            return 1;
        }
        long byThreshold = fileSize / MIN_BYTES_PER_THREAD;
        long limit = ParallelismTools.maxParallelism();
        if (maxThreads > 0) {
            limit = Math.min(limit, maxThreads);
        }
        return (int) Math.max(1, Math.min(byThreshold, limit));
    }

    /**
     * Parses an SDF file, in parallel when it is large enough to be worth it.
     *
     * @param fileName The file to parse.
     * @return The parsed model.
     */
    public static SdfFile parse(Path fileName) {
        return parse(fileName, 0, CodePerfTracker.SILENT);
    }

    /**
     * Parses an SDF file, in parallel when it is large enough to be worth it.
     *
     * @param fileName The file to parse.
     * @param maxThreads Upper bound on workers, or 0 for the system default.
     * @param t Performance tracker, or {@link CodePerfTracker#SILENT}.
     * @return The parsed model.
     */
    public static SdfFile parse(Path fileName, int maxThreads, CodePerfTracker t) {
        if (SdfInput.isGzipped(fileName)) {
            return parseGzipped(fileName, maxThreads, t);
        }
        long fileSize;
        try {
            fileSize = Files.size(fileName);
        } catch (IOException e) {
            throw new UncheckedIOException("ERROR: Couldn't read file : " + fileName, e);
        }

        int threads = calcThreads(fileSize, maxThreads);
        if (threads <= 1) {
            return SdfParser.parse(fileName, t);
        }

        t.start("Index SDF");
        List<SdfChunkIndexer.Anchor> splitPoints =
                SdfChunkIndexer.findSplitPoints(fileName, fileSize, threads);
        t.stop();

        if (splitPoints.size() <= 1) {
            return SdfParser.parse(fileName, t);
        }

        t.start("Parse SDF");
        List<SdfChunk> chunks = parseChunks(fileName, splitPoints);
        t.stop().start("Merge SDF");
        SdfFile result = merge(fileName, chunks);
        t.stop();
        return result;
    }

    /**
     * Decompresses a gzipped SDF to a temporary file and parses that.
     *
     * @param fileName The compressed file.
     * @param maxThreads Upper bound on workers.
     * @param t Performance tracker.
     * @return The parsed model.
     */
    private static SdfFile parseGzipped(Path fileName, int maxThreads, CodePerfTracker t) {
        Path temp = null;
        try {
            t.start("Decompress SDF");
            temp = Files.createTempFile("rapidwright_sdf", ".sdf");
            try (java.io.InputStream in = SdfInput.open(fileName)) {
                Files.copy(in, temp, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
            t.stop();
            SdfFile result = parse(temp, maxThreads, t);
            result.setSource(fileName);
            return result;
        } catch (IOException e) {
            throw new UncheckedIOException("ERROR: Couldn't read file : " + fileName, e);
        } finally {
            if (temp != null) {
                try {
                    Files.deleteIfExists(temp);
                } catch (IOException e) {
                    // A leftover temporary file is not worth failing the parse over.
                }
            }
        }
    }

    /**
     * Runs one parser per chunk, each on its own stream.
     *
     * @param fileName The file to parse.
     * @param splitPoints Chunk start positions, in ascending order.
     * @return The chunks, in the same order as the split points.
     */
    private static List<SdfChunk> parseChunks(Path fileName,
            List<SdfChunkIndexer.Anchor> splitPoints) {
        StringPool pool = StringPool.concurrentPool();
        List<Future<SdfChunk>> futures = new ArrayList<>(splitPoints.size());
        for (int i = 0; i < splitPoints.size(); i++) {
            final SdfChunkIndexer.Anchor start = splitPoints.get(i);
            final long stop = i + 1 < splitPoints.size()
                    ? splitPoints.get(i + 1).byteOffset : Long.MAX_VALUE;
            futures.add(ParallelismTools.submit(() -> {
                try (SdfParser parser = new SdfParser(fileName, SdfInput.open(fileName), pool)) {
                    return parser.parseChunk(start, stop);
                }
            }));
        }
        List<SdfChunk> chunks = new ArrayList<>(futures.size());
        for (Future<SdfChunk> f : futures) {
            chunks.add(ParallelismTools.get(f));
        }
        return chunks;
    }

    /**
     * Stitches the chunks back into a single file model, checking as it goes that they actually fit
     * together.
     *
     * @param fileName The file that was parsed, for diagnostics.
     * @param chunks The chunks, in ascending byte order.
     * @return The merged model.
     */
    static SdfFile merge(Path fileName, List<SdfChunk> chunks) {
        SdfFile file = chunks.get(0).getHeader();
        if (file == null) {
            throw new SdfParseException(fileName, -1, -1,
                    "the first chunk did not parse the DELAYFILE header");
        }
        file.setSource(fileName);

        long previousOffset = -1;
        int closingChunks = 0;
        SdfCell pendingTopCell = null;
        List<SdfDelayEntry> pendingEntries = null;

        for (SdfChunk chunk : chunks) {
            if (chunk.getStartByteOffset() <= previousOffset) {
                throw new SdfParseException(fileName, -1, chunk.getStartByteOffset(),
                        "chunk offsets are not strictly increasing; the file index is corrupt");
            }
            previousOffset = chunk.getStartByteOffset();

            if (!chunk.getFragmentEntries().isEmpty() || pendingTopCell != null) {
                if (pendingTopCell == null) {
                    throw new SdfParseException(fileName, -1, chunk.getStartByteOffset(),
                            "a chunk continues a cell that no earlier chunk started");
                }
                pendingEntries.addAll(chunk.getFragmentEntries());
            }

            for (SdfCell cell : chunk.getCells()) {
                if (pendingTopCell != null) {
                    throw new SdfParseException(fileName, cell.getLineNumber(),
                            cell.getStartByteOffset(),
                            "a cell follows the top-level cell, which must be last");
                }
                file.addCell(cell);
            }

            if (chunk.getPartialCell() != null) {
                if (pendingTopCell != null) {
                    throw new SdfParseException(fileName, -1, chunk.getStartByteOffset(),
                            "two chunks both started an incomplete cell");
                }
                pendingTopCell = chunk.getPartialCell();
                pendingEntries = new ArrayList<>(pendingTopCell.getDelayEntries());
            }

            if (chunk.sawDelayFileClose()) {
                closingChunks++;
            }
        }

        if (pendingTopCell != null) {
            file.addCell(completeCell(pendingTopCell, pendingEntries));
        }

        if (closingChunks != 1) {
            throw new SdfParseException(fileName, -1, -1, closingChunks == 0
                    ? "no chunk found the closing parenthesis of DELAYFILE; the file is truncated"
                    : closingChunks + " chunks each claimed to close DELAYFILE");
        }

        SdfParser.validate(file);
        return file;
    }

    /**
     * Rebuilds a cell that was split across chunks, with its full entry list.
     *
     * @param head The cell as the starting chunk saw it.
     * @param entries The complete entry list, in file order.
     * @return The completed cell.
     */
    private static SdfCell completeCell(SdfCell head, List<SdfDelayEntry> entries) {
        return new SdfCell(head.getCellType(), head.getInstance(), head.getStyle(),
                head.hasDelay(), head.hasTimingCheck(), head.getPathPulsePercent(),
                entries, head.getTimingChecks(), head.getStartByteOffset(),
                head.getEndByteOffset(), head.getLineNumber());
    }
}

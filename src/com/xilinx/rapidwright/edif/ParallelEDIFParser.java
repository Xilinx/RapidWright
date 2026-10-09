/*
 * Copyright (c) 2022, Xilinx, Inc.
 * Copyright (c) 2022-2023, 2026, Advanced Micro Devices, Inc.
 * All rights reserved.
 *
 * Author: Jakob Wenzel, Xilinx Research Labs.
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

package com.xilinx.rapidwright.edif;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Future;

import com.xilinx.rapidwright.device.Device;
import com.xilinx.rapidwright.tests.CodePerfTracker;
import com.xilinx.rapidwright.util.FileTools;
import com.xilinx.rapidwright.util.ParallelismTools;
import com.xilinx.rapidwright.util.Params;
import com.xilinx.rapidwright.util.StringPool;
import com.xilinx.rapidwright.util.function.InputStreamSupplier;

/**
 * Fast EDIF Parser using parallelism
 */
public class ParallelEDIFParser implements AutoCloseable{
    private static final long MIN_BYTES_PER_THREAD = EDIFTokenizer.DEFAULT_MAX_TOKEN_LENGTH * 8L;
    protected final List<ParallelEDIFParserWorker> workers = new ArrayList<>();
    protected final Path fileName;
    private final long fileSize;
    private final int maxThreads;
    protected final InputStreamSupplier inputStreamSupplier;
    protected final int maxTokenLength;

    /** Whether the input is gzip-compressed, determined once by the caller. */
    private final boolean gzipped;
    protected final StringPool uniquifier;

    /**
     * Estimated ratio of EDIF to gzipped EDIF file size, used in calculating the
     * number of thread workers for parallel EDIF parsing
     */
    public static final int EDIF_GZIP_COMPRESSION_RATIO = 16;

    /**
     * Estimated number of bytes of (uncompressed) EDIF per distinct String in the pool, used to size the pool so
     * that it rarely has to grow while being filled. Pooled Strings per KB of EDIF varied from 0.38 to 14.2 in 14
     * EDIFs, and from 0.38 to 1.5 in those larger than 100 MB (e.g. 1.11 for a 14.1 GB EDIF and 1.48 for a 38.6 GB
     * EDIF, whose 55.6M Strings otherwise doubled the pool's table about 23 times). One String per KB avoids all
     * but the last doubling for such EDIFs, while sizing the pool for at most about 2.6 times as many Strings as
     * the sparsest of them needs.
     */
    private static final int EDIF_BYTES_PER_POOLED_STRING = 1024;

    /**
     * Estimate the number of distinct Strings that parsing an EDIF file will pool, to size the pool.
     * @param fileSize Size of the file on disk, in bytes.
     * @param gzipped Whether the file is gzip-compressed.
     * @return The estimated number of pooled Strings.
     */
    static int estimatePooledStrings(long fileSize, boolean gzipped) {
        final long edifBytes = gzipped ? fileSize * EDIF_GZIP_COMPRESSION_RATIO : fileSize;
        return (int) Math.min(Math.max(edifBytes, 0) / EDIF_BYTES_PER_POOLED_STRING, Integer.MAX_VALUE);
    }

    ParallelEDIFParser(Path fileName, long fileSize, InputStreamSupplier inputStreamSupplier,
            int maxTokenLength, int maxThreads, boolean gzipped) {
        this.fileName = fileName;
        this.fileSize = fileSize;
        this.inputStreamSupplier = inputStreamSupplier;
        this.maxTokenLength = maxTokenLength;
        this.maxThreads = maxThreads;
        this.gzipped = gzipped;
        this.uniquifier = StringPool.concurrentPool(estimatePooledStrings(fileSize, gzipped));
    }

    ParallelEDIFParser(Path fileName, long fileSize, InputStreamSupplier inputStreamSupplier,
            int maxTokenLength, int maxThreads) {
        this(fileName, fileSize, inputStreamSupplier, maxTokenLength, maxThreads,
                EDIFTools.isGzipped(fileName));
    }

    public ParallelEDIFParser(Path fileName, long fileSize, InputStreamSupplier inputStreamSupplier) {
        this(fileName, fileSize, inputStreamSupplier, EDIFTokenizer.DEFAULT_MAX_TOKEN_LENGTH,
                Integer.MAX_VALUE);
    }

    /**
     * @param p        Path to the EDIF file to parse.
     * @param fileSize Size of the file on disk, in bytes.
     * @param gzipped  Whether the file is gzip-compressed. Accepting it here lets a
     *                 caller that has already determined this avoid a second check.
     */
    public ParallelEDIFParser(Path p, long fileSize, boolean gzipped) {
        this(p, fileSize, () -> EDIFTools.openEDIFInputStream(p),
                EDIFTokenizer.DEFAULT_MAX_TOKEN_LENGTH, Integer.MAX_VALUE, gzipped);
    }

    public ParallelEDIFParser(Path p, long fileSize) {
        this(p, fileSize, EDIFTools.isGzipped(p));
    }

    public ParallelEDIFParser(Path p) throws IOException {
        this(p, Files.size(p));
    }

    protected ParallelEDIFParserWorker makeWorker(long offset) throws IOException {
        return new ParallelEDIFParserWorker(fileName, inputStreamSupplier.get(), offset, uniquifier, maxTokenLength);
    }

    public static int calcThreads(long fileSize, int maxThreads, boolean isGzipped) {
        long sizeThreshold = isGzipped ? (MIN_BYTES_PER_THREAD / EDIF_GZIP_COMPRESSION_RATIO)
                : MIN_BYTES_PER_THREAD;
        int maxUsefulThreads = Math.max((int) (fileSize / sizeThreshold), 1);
        return Math.min(maxUsefulThreads, Math.min(ParallelismTools.maxParallelism(), maxThreads));
    }


    protected void initializeWorkers() throws IOException {
        workers.clear();
        int threads = calcThreads(fileSize, maxThreads, gzipped);
        long offsetPerThread = (gzipped ? (fileSize * EDIF_GZIP_COMPRESSION_RATIO) : fileSize)
                / threads;
        for (int i=0;i<threads;i++) {
            ParallelEDIFParserWorker worker = makeWorker(i*offsetPerThread);
            workers.add(worker);
        }
    }

    private int numberOfThreads;

    public EDIFNetlist parseEDIFNetlist() throws IOException {
        EDIFNetlist netlist = parseEDIFNetlist(CodePerfTracker.SILENT);
        if (fileName != null && fileName.toString().endsWith(".gz")
                && Params.RW_DECOMPRESS_GZIPPED_EDIF_TO_DISK) {
            Files.delete(FileTools.getDecompressedGZIPFileName(fileName));
        }
        return netlist;
    }

    public EDIFNetlist parseEDIFNetlist(CodePerfTracker t) throws IOException {

        t.start("Initialize workers");
        initializeWorkers();
        numberOfThreads = workers.size();

        t.stop().start("Parse First Token");
        // For each worker: the worker itself if it failed, else null
        final List<ParallelEDIFParserWorker> failedOrNull = ParallelismTools.invokeAll(workers,
                w -> !w.parseFirstToken() ? w : null);

        for (ParallelEDIFParserWorker failedWorker : failedOrNull) {
            if (failedWorker == null) {
                continue;
            }

            if (!Device.QUIET_MESSAGE) {
                if (failedWorker.parseException != null) {
                    String message = failedWorker.parseException.getMessage();
                    if (failedWorker.parseException instanceof TokenTooLongException) {
                        //Message contains a hint to a constant that the user should adjust.
                        //Token misdetection is the more likely cause, so let's adjust it
                        message = "Likely token misdetection";
                    }
                    System.err.println("Removing failed thread "+failedWorker+": "+ message);
                } else {
                    System.err.println("Removing "+failedWorker+", it started past the last cell.");
                }
            }

            failedWorker.close();
        }
        workers.removeAll(failedOrNull);

        //Propagate parse limit to neighbours
        for (int i = 1; i < workers.size(); i++) {
            workers.get(i - 1).setStopCellToken(workers.get(i).getFirstCellToken());
        }

        t.stop().start("Do Parse and Assemble Libraries");
        final Assembler assembler = doParseAndAssemble();
        assembler.netlist.setDesign(getEdifDesign());

        t.stop().start("Link Netlist");
        processLinks(assembler);
        t.stop();

        return assembler.netlist;
    }

    /**
     * Parse all workers in parallel and, on the calling thread, add the libraries and cells that each has parsed
     * to the netlist in file order, as soon as that worker (and so every worker before it) has finished, while
     * later workers are still parsing.
     * @return The assembler holding the netlist and the lookup maps needed to link it.
     */
    private Assembler doParseAndAssemble() {
        final Assembler assembler = new Assembler(Objects.requireNonNull(workers.get(0).netlist));
        // As EDIFNetlist.exportEDIF() does, submit all workers and then wait for them in order with joinFirst(),
        // which also runs any worker not yet started by the pool on this thread
        final Deque<Future<ParallelEDIFParserWorker>> futures = new ArrayDeque<>(workers.size());
        for (ParallelEDIFParserWorker w : workers) {
            futures.add(ParallelismTools.submit(() -> {
                w.doParse(false);
                return w;
            }));
        }

        //Check if we had misdetected start tokens
        for (int i=0; i<workers.size();i++) {
            final ParallelEDIFParserWorker worker = ParallelismTools.joinFirst(futures);
            assert(worker == workers.get(i));
            if (worker.parseException!=null) {
                throw worker.parseException;
            }
            while (worker.stopTokenMismatch) {
                if (i<workers.size()-1) {
                    // Wait for the next worker to finish parsing before discarding it
                    final ParallelEDIFParserWorker failedWorker = ParallelismTools.joinFirst(futures);
                    assert(failedWorker == workers.get(i + 1));
                    if (!Device.QUIET_MESSAGE) {
                        System.err.println("Token mismatch between "+worker+" and " + failedWorker + ". Discarding second one and reparsing...");
                    }
                    try {
                        failedWorker.close();
                    } catch (IOException e) {
                        throw new UncheckedIOException(e);
                    }
                    workers.remove(i+1);
                } else {
                    throw new IllegalStateException(worker+" claims to have a mismatch with the following thread, but is is the last one");
                }

                if (i<workers.size()-1) {
                    worker.setStopCellToken(workers.get(i + 1).getFirstCellToken());
                } else {
                    worker.setStopCellToken(null);
                }

                //Re-Parse :(
                worker.doParse(true);
                if (worker.parseException!=null) {
                    throw worker.parseException;
                }
            }
            assembler.add(worker);
        }
        return assembler;
    }

    private EDIFDesign getEdifDesign() {
        //We can't just ask the last thread, since it may have parsed nothing at all. The designInfo is then in
        //the previous thread.
        for (int i=workers.size()-1;i>=0;i--) {
            final ParallelEDIFParserWorker worker = workers.get(i);
            if (worker.edifDesign != null) {
                return worker.edifDesign;
            }
        }
        return null;
    }

    /**
     * Adds the libraries and cells parsed by each worker to the netlist. Workers must be added in file order: a
     * cell belongs to the most recent library before it, and name collisions are renamed in order.
     */
    private class Assembler {
        private final EDIFNetlist netlist;
        private final Map<EDIFLibrary, Map<String, EDIFCell>> cellsByLegalName = new HashMap<>();
        private final Map<String, EDIFLibrary> librariesByLegalName = new HashMap<>();
        /** For each cell with renamed ports, the renames recorded by the worker that parsed it */
        private final Map<EDIFCell, EDIFReadLegalNameCache> portRenamesByCell = new IdentityHashMap<>();
        private EDIFLibrary currentLibrary = null;
        private EDIFToken currentToken = null;

        private Assembler(EDIFNetlist netlist) {
            this.netlist = netlist;
        }

        private void add(ParallelEDIFParserWorker worker) {
            for (ParallelEDIFParserWorker.LibraryOrCellResult parsed : worker.librariesAndCells) {
                if (currentToken!=null && parsed.getToken().byteOffset<= currentToken.byteOffset) {
                    throw new IllegalStateException("Not in ascending order! seen: "+currentToken+", now processed "+parsed.getToken());
                }
                currentToken = parsed.getToken();

                currentLibrary = parsed.addToNetlist(netlist, currentLibrary, cellsByLegalName, librariesByLegalName,
                        worker.cache);
            }
            for (EDIFCell cell : worker.cellsWithRenamedPorts) {
                portRenamesByCell.put(cell, worker.cache);
            }
            // Release this worker's results, which are no longer needed
            worker.librariesAndCells.clear();
            worker.cellsWithRenamedPorts.clear();
        }
    }

    /**
     * Link each worker's cell instances to their cells and port insts to their ports, then name and add the port
     * insts. This needs all libraries and cells to have been assembled, as any cell instance may reference a cell
     * parsed by any worker; after that, all of a worker's work is done in one parallel step, so that there is one
     * wait for the slowest worker rather than one per step.
     */
    private void processLinks(Assembler assembler) {
        // Port lookup maps of cells with many ports, created on first use (read-only once constructed)
        final Map<EDIFCell, EDIFPortCache> portCaches = new ConcurrentHashMap<>();
        // Workers (byte ranges) need not take equally long, so each also splits its work with nested
        // forEach()s, whose pieces can be stolen by threads that have finished their own worker:
        //  - Each apply() sets a different cell instance's type, so their order does not matter; the first
        //    forEach() returns before the second looks up those cell instances' types.
        //  - When adding the port insts, we have to make sure that we don't split a parent cell's port instances
        //    between threads. That could lead to ConcurrentModificationExceptions. Each ParentCellPortInsts in
        //    linkPortInstData holds all port insts of one parent cell, which are processed sequentially; this only
        //    modifies those port insts and that parent cell's nets, cell insts and internal port map, whereas other
        //    parent cells' port insts only read cells' ports.
        // Each worker's references and port insts are released once linked, as they are no longer needed.
        ParallelismTools.forEach(workers, w-> {
            ParallelismTools.forEach(w.linkCellReference, cellReferenceData -> cellReferenceData.apply(
                    assembler.librariesByLegalName, assembler.cellsByLegalName));
            w.linkCellReference.clear();
            ParallelismTools.forEach(w.linkPortInstData, links -> links.linkAndAdd(assembler.portRenamesByCell,
                    portCaches, uniquifier));
            w.linkPortInstData.clear();
        });
        // Release each worker's renames only now, as any worker's port insts may have read them
        for (ParallelEDIFParserWorker w : workers) {
            w.cache.clear();
        }
    }

    @Override
    public void close() throws IOException {
        for (ParallelEDIFParserWorker worker : workers) {
            worker.close();
        }
    }

    public int getNumberOfThreads() {
        return numberOfThreads;
    }

    public int getSuccesfulThreads() {
        return workers.size();
    }
}

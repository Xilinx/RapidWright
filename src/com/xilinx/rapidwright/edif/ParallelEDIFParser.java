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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountedCompleter;
import java.util.function.BiFunction;

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

        t.stop().start("Parse, Assemble and Link Netlist");
        final EDIFNetlist netlist = parseAssembleAndLink();
        netlist.setDesign(getEdifDesign());
        t.stop();

        return netlist;
    }

    /**
     * Parse all workers in parallel, add the libraries and cells that each has parsed to the netlist in file order,
     * and link each worker as soon as it (and so every worker before it) has been added, while later workers are
     * still being parsed or added. This requires EDIF's define-before-use rule: a worker's references can then only
     * be to cells that it or an earlier worker parsed, which have been added. Workers are parsed in the pool, and
     * this thread parses any that the pool has not started while it waits for all workers to be parsed.
     * Workers are added by a chain of stages, one per worker, each of which runs once its worker has been parsed and
     * the previous stage has completed, on whichever thread completes the later of the two. So no thread waits for
     * another to add a worker, and one thread may add several workers in a row while others link those added. Once
     * all workers have been parsed, this thread helps with any queued linking until all workers have been added and
     * linked.
     * @return The netlist.
     */
    private EDIFNetlist parseAssembleAndLink() {
        final Assembler assembler = new Assembler(Objects.requireNonNull(workers.get(0).netlist));
        // Port lookup maps of cells with many ports, created on first use by any worker's linking
        final Map<EDIFCell, EDIFPortCache> portCaches = new ConcurrentHashMap<>();
        final List<ParallelEDIFParserWorker> discarded = new ArrayList<>();
        final List<CompletableFuture<Void>> parsed = new ArrayList<>(workers.size());
        for (ParallelEDIFParserWorker w : workers) {
            parsed.add(ParallelismTools.submitCompletable(() -> w.doParse(false)));
        }

        // Completes once the stages below have, and so has every worker's linking, which each stage submits (see
        // link()) as chunks that are its descendants: so a thread waiting for it helps with only that linking
        final CountedCompleter<Void> linked = new CountedCompleter<Void>() {
            @Override
            public void compute() {
                // Never run: completed by the stages and each worker's linking
            }
        };
        // Add a worker that has been parsed, given the worker still to be added (or null if none) if the previous one
        // did not stop exactly at this worker's first cell: this worker then misdetected its start, so is discarded,
        // and that worker parses on up to the following worker's first cell instead. Returns the worker still to be
        // added, if this one (or that one) also did not stop exactly at the following worker's first cell
        final BiFunction<ParallelEDIFParserWorker, Integer, ParallelEDIFParserWorker> add = (mismatched, index) -> {
            final ParallelEDIFParserWorker worker;
            if (mismatched == null) {
                worker = workers.get(index);
            } else {
                worker = mismatched;
                final ParallelEDIFParserWorker failedWorker = workers.get(index);
                if (!Device.QUIET_MESSAGE) {
                    System.err.println("Token mismatch between " + worker + " and " + failedWorker
                            + ". Discarding second one and reparsing...");
                }
                try {
                    failedWorker.close();
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
                discarded.add(failedWorker);
                worker.setStopCellToken(index + 1 < workers.size() ? workers.get(index + 1).getFirstCellToken()
                        : null);
                //Re-Parse :(
                worker.doParse(true);
            }
            if (worker.parseException != null) {
                throw worker.parseException;
            }
            if (worker.stopTokenMismatch) {
                if (index == workers.size() - 1) {
                    throw new IllegalStateException(worker + " claims to have a mismatch with the following " +
                            "thread, but is is the last one");
                }
                return worker;
            }
            assembler.add(worker);
            // An added worker never parses again, so release its input (and tokenizer buffer) while linking
            try {
                worker.close();
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
            link(linked, worker, assembler, portCaches);
            return null;
        };
        // Each stage adds its worker once that has been parsed and the previous stage has completed. Each stage is given
        // its worker's future here, rather than looking it up in parsed once the previous stage has completed: so that
        // the stages do not depend on parsed after this, while joinAll() below waits for (and is done with) each entry
        CompletableFuture<ParallelEDIFParserWorker> stage = parsed.get(0).thenApply(x -> add.apply(null, 0));
        for (int i = 1; i < workers.size(); i++) {
            final int index = i;
            stage = stage.thenCombine(parsed.get(index), (mismatched, x) -> add.apply(mismatched, index));
        }
        stage.whenComplete((x, e) -> {
            if (e == null) {
                linked.tryComplete();
            } else {
                linked.completeExceptionally(e instanceof CompletionException && e.getCause() != null
                        ? e.getCause() : e);
            }
        });
        // There are as many workers as threads, including this one: so, until all workers have been parsed, parse any
        // that no pool thread has started, so that none waits for a pool thread to become free
        ParallelismTools.joinAll(parsed);
        // Until all workers have been added and linked, rather than block, help with any of their linking that is
        // queued; then rethrow any exception from parsing, adding or linking
        ParallelismTools.join(linked);
        // Discarded workers are only removed now, as the stages index into workers (if an exception was rethrown above,
        // they are left in it). All workers have already been closed, so close() closes them again, which is harmless
        workers.removeAll(discarded);
        // Release each worker's renames only now, as any worker's port insts may have read them
        for (ParallelEDIFParserWorker w : workers) {
            w.cache.clear();
        }
        return assembler.netlist;
    }

    /**
     * Link a worker that has been added, as part of the given completer (so that a thread waiting for it helps): its
     * cell references, then, once all have been applied (as linking a port inst needs its cell instance's type), its
     * port insts. Each step's items are split into chunks that any thread can run.
     * Each cell reference sets a different cell instance's type, so their order does not matter.
     * When adding the port insts, we have to make sure that we don't split a parent cell's port instances between
     * threads. That could lead to ConcurrentModificationExceptions. Each ParentCellPortInsts in linkPortInstData
     * holds all port insts of one parent cell, which are processed sequentially; this only modifies those port
     * insts and that parent cell's nets, cell insts and internal port map, whereas other parent cells' port insts
     * only read cells' ports.
     * The worker's references and port insts are released once linked, as they are no longer needed.
     */
    private void link(CountedCompleter<?> linked, ParallelEDIFParserWorker worker, Assembler assembler,
                      Map<EDIFCell, EDIFPortCache> portCaches) {
        // Each step is a completer that is never run itself, only completed by its chunks, as part of linked
        final CountedCompleter<Void> cellReferences = new CountedCompleter<Void>(linked) {
            @Override
            public void compute() {
            }

            @Override
            public void onCompletion(CountedCompleter<?> caller) {
                worker.linkCellReference.clear();
                final CountedCompleter<Void> portInsts = new CountedCompleter<Void>(linked) {
                    @Override
                    public void compute() {
                    }

                    @Override
                    public void onCompletion(CountedCompleter<?> caller) {
                        worker.linkPortInstData.clear();
                    }
                };
                // Counted before this step completes, so that linked cannot complete in between
                linked.addToPendingCount(1);
                ParallelismTools.submit(portInsts, worker.linkPortInstData,
                        parent -> parent.linkAndAdd(assembler.portRenamesByCell, portCaches, uniquifier));
                portInsts.tryComplete();
            }
        };
        linked.addToPendingCount(1);
        ParallelismTools.submit(cellReferences, worker.linkCellReference,
                ref -> ref.apply(assembler.librariesByLegalName, assembler.cellsByLegalName));
        cellReferences.tryComplete();
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
        // Concurrent maps, as workers already added are linked (reading them) while later workers are added
        private final Map<EDIFLibrary, Map<String, EDIFCell>> cellsByLegalName = new ConcurrentHashMap<>();
        private final Map<String, EDIFLibrary> librariesByLegalName = new ConcurrentHashMap<>();
        /** For each cell with renamed ports, the renames recorded by the worker that parsed it */
        private final Map<EDIFCell, EDIFReadLegalNameCache> portRenamesByCell = new ConcurrentHashMap<>();
        private EDIFLibrary currentLibrary = null;
        /** Byte offset of the last library or cell added */
        private long lastResultOffset = -1;

        private Assembler(EDIFNetlist netlist) {
            this.netlist = netlist;
        }

        private void add(ParallelEDIFParserWorker worker) {
            if (!worker.librariesAndCells.isEmpty()) {
                // Each worker's results are in file order, so it is enough to check that they follow the previous
                // worker's
                if (worker.firstResultOffset <= lastResultOffset) {
                    throw new IllegalStateException("Not in ascending order! seen offset " + lastResultOffset
                            + ", now processed offset " + worker.firstResultOffset + " (" + worker + ")");
                }
                lastResultOffset = worker.lastResultOffset;
            }
            for (ParallelEDIFParserWorker.LibraryOrCellResult parsed : worker.librariesAndCells) {
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

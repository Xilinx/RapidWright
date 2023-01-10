/*
 * Copyright (c) 2022, Xilinx, Inc.
 * Copyright (c) 2022, Advanced Micro Devices, Inc.
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
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Future;
import java.util.function.Function;
import java.util.stream.Collectors;

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
    protected StringPool uniquifier = StringPool.concurrentPool();

    protected final EDIFReadLegalNameCache cache;

    /**
     * Estimated ratio of EDIF to gzipped EDIF file size, used in calculating the
     * number of thread workers for parallel EDIF parsing
     */
    public static final int EDIF_GZIP_COMPRESSION_RATIO = 16;

    ParallelEDIFParser(Path fileName, long fileSize, InputStreamSupplier inputStreamSupplier,
            int maxTokenLength, int maxThreads) {
        this.fileName = fileName;
        this.fileSize = fileSize;
        this.inputStreamSupplier = inputStreamSupplier;
        this.maxTokenLength = maxTokenLength;
        this.cache = EDIFReadLegalNameCache.createMultiThreaded();
        this.maxThreads = maxThreads;
    }

    public ParallelEDIFParser(Path fileName, long fileSize, InputStreamSupplier inputStreamSupplier) {
        this(fileName, fileSize, inputStreamSupplier, EDIFTokenizer.DEFAULT_MAX_TOKEN_LENGTH,
                Integer.MAX_VALUE);
    }

    public ParallelEDIFParser(Path p, long fileSize) {
        this(p, fileSize, InputStreamSupplier.fromPath(p,
                p.toString().endsWith(".gz") && Params.RW_DECOMPRESS_GZIPPED_EDIF_TO_DISK));
    }

    public ParallelEDIFParser(Path p) throws IOException {
        this(p, Files.size(p));
    }

    protected ParallelEDIFParserWorker makeWorker(long offset) throws IOException {
        return new ParallelEDIFParserWorker(fileName, inputStreamSupplier.get(), offset, uniquifier, maxTokenLength, cache);
    }

    public static int calcThreads(long fileSize, int maxThreads, boolean isGzipped) {
        long sizeThreshold = isGzipped ? (MIN_BYTES_PER_THREAD / EDIF_GZIP_COMPRESSION_RATIO)
                : MIN_BYTES_PER_THREAD;
        int maxUsefulThreads = Math.max((int) (fileSize / sizeThreshold), 1);
        return Math.min(maxUsefulThreads, Math.min(ParallelismTools.maxParallelism(), maxThreads));
    }


    protected void initializeWorkers() throws IOException {
        workers.clear();
        boolean isGzipped = fileName.toString().endsWith(".gz");
        int threads = calcThreads(fileSize, maxThreads, isGzipped);
        long offsetPerThread = (isGzipped ? (fileSize * EDIF_GZIP_COMPRESSION_RATIO) : fileSize)
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
        final List<Future<ParallelEDIFParserWorker>> futures = ParallelismTools.invokeAll(workers, w -> !w.parseFirstToken() ? w : null);
        final List<ParallelEDIFParserWorker> failedWorkers = futures.stream()
                .map(ParallelismTools::get)
                .filter(Objects::nonNull)
                .collect(Collectors.toList());

        if (!failedWorkers.isEmpty() && !Device.QUIET_MESSAGE) {
            for (ParallelEDIFParserWorker failedWorker : failedWorkers) {
                if (failedWorker.parseException!=null) {
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
        }
        for (ParallelEDIFParserWorker failedWorker : failedWorkers) {
            failedWorker.close();
        }
        workers.removeAll(failedWorkers);

        //Propagate parse limit to neighbours
        for (int i = 1; i < workers.size(); i++) {
            workers.get(i - 1).setStopCellToken(workers.get(i).getFirstCellToken());
        }

        t.stop().start("Do Parse");
        doParse();


        return mergeParseResults(t);
    }

    private void doParse() {
        ParallelismTools.invokeAllRunnable(workers, w->w.doParse(false));

        //Check if we had misdetected start tokens
        for (int i=0; i<workers.size();i++) {
            final ParallelEDIFParserWorker worker = workers.get(i);
            if (worker.parseException!=null) {
                throw worker.parseException;
            }
            while (worker.stopTokenMismatch) {
                if (i<workers.size()-1) {
                    final ParallelEDIFParserWorker failedWorker = workers.get(i + 1);
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
        }
        for (ParallelEDIFParserWorker worker : workers) {
            worker.finish();
        }
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

    private Map<EDIFLibrary, Map<String, EDIFCell>> addCellsAndLibraries(EDIFNetlist netlist) {
        Map<EDIFLibrary, Map<String, EDIFCell>> cellsByLegalName = new HashMap<>();
        EDIFLibrary currentLibrary = null;
        EDIFToken currentToken = null;
        for (ParallelEDIFParserWorker worker : workers) {
            for (ParallelEDIFParserWorker.LibraryOrCellResult parsed : worker.librariesAndCells) {
                if (currentToken!=null && parsed.getToken().byteOffset<= currentToken.byteOffset) {
                    throw new IllegalStateException("Not in ascending order! seen: "+currentToken+", now processed "+parsed.getToken());
                }
                currentToken = parsed.getToken();

                currentLibrary = parsed.addToNetlist(netlist, currentLibrary, cellsByLegalName, cache);
            }
        }
        return cellsByLegalName;
    }

    private void processLinks(CodePerfTracker t, EDIFNetlist netlist, Map<EDIFLibrary, Map<String, EDIFCell>> cellsByLegalName) {
        t.start("Link CellInst+SmallPorts");
        //We have to map from a string representation of the ports' names to the ports.
        //Directly map the ports from small cells, add ports from large cells to this map
        final Map<String, EDIFLibrary> librariesByLegalName = netlist.getLibraries().stream()
                .collect(Collectors.toMap(cache::getLegalEDIFName, Function.identity()));
        Map<EDIFCell, Collection<ParallelEDIFParserWorker.LinkPortInstData>> byPortCell = new ConcurrentHashMap<>();
        ParallelismTools.invokeAllRunnable(workers, w-> {
            for (ParallelEDIFParserWorker.CellReferenceData cellReferenceData : w.linkCellReference) {
                cellReferenceData.apply(librariesByLegalName, cellsByLegalName);
            }
            w.linkSmallPorts(byPortCell);
        });

        t.stop().start("Link Large Port Cells");
        //Now we can create a map of ports just for large cells and look up the ports
        ParallelismTools.invokeAllRunnable(byPortCell.entrySet(), entry -> {
            EDIFCell cell = entry.getKey();
            final EDIFPortCache edifPortCache = new EDIFPortCache(cell, cache);
            for (ParallelEDIFParserWorker.LinkPortInstData linkPortInstData : entry.getValue()) {
                linkPortInstData.enterPort(edifPortCache);
            }
        });

        t.stop().start("Name and Add port insts");
        //When adding the port insts, we have to make sure that we don't split a parent cell's port instances
        // between threads.
        //That could lead to ConcurrentModificationExceptions
        ParallelismTools.invokeAllRunnable(workers,
                w-> {
                    for (List<ParallelEDIFParserWorker.LinkPortInstData> list : w.linkPortInstData) {
                        for (ParallelEDIFParserWorker.LinkPortInstData linkPortInstData : list) {
                            linkPortInstData.name(uniquifier);
                            linkPortInstData.add();
                        }
                    }
                });
        t.stop();
    }

    private EDIFNetlist mergeParseResults(CodePerfTracker t) {
        EDIFNetlist netlist = Objects.requireNonNull(workers.get(0).netlist);
        netlist.setDesign(getEdifDesign());

        t.stop().start("Assemble Libraries");
        final Map<EDIFLibrary, Map<String, EDIFCell>> cellsByLegalName = addCellsAndLibraries(netlist);
        t.stop();
        processLinks(t, netlist, cellsByLegalName);

        return netlist;
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

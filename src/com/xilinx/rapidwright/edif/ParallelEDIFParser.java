/* 
 * Copyright (c) 2022 Xilinx, Inc. 
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
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.LongStream;

import com.xilinx.rapidwright.device.Device;
import com.xilinx.rapidwright.tests.CodePerfTracker;
import com.xilinx.rapidwright.util.ParallelismTools;
import com.xilinx.rapidwright.util.function.InputStreamSupplier;
import org.jetbrains.annotations.NotNull;

public class ParallelEDIFParser implements AutoCloseable{
    private static final long SIZE_PER_THREAD = EDIFTokenizerV2.DEFAULT_MAX_TOKEN_LENGTH * 8L;
    protected final List<ParallelEDIFParserWorker> workers = new ArrayList<>();
    protected final Path fileName;
    private final long fileSize;
    protected final InputStreamSupplier inputStreamSupplier;
    protected final int maxTokenLength;
    protected NameUniquifier uniquifier = NameUniquifier.concurrentUniquifier();

    ParallelEDIFParser(Path fileName, long fileSize, InputStreamSupplier inputStreamSupplier, int maxTokenLength) {
        this.fileName = fileName;
        this.fileSize = fileSize;
        this.inputStreamSupplier = inputStreamSupplier;
        this.maxTokenLength = maxTokenLength;
    }

    public ParallelEDIFParser(Path fileName, long fileSize, InputStreamSupplier inputStreamSupplier) {
        this(fileName, fileSize, inputStreamSupplier, EDIFTokenizerV2.DEFAULT_MAX_TOKEN_LENGTH);
    }

    public ParallelEDIFParser(Path p, long fileSize) {
        this(p, fileSize, InputStreamSupplier.fromPath(p));
    }

    public ParallelEDIFParser(Path p) throws IOException {
        this(p, Files.size(p));
    }

    protected ParallelEDIFParserWorker makeWorker(long offset) throws IOException {
        return new ParallelEDIFParserWorker(fileName, inputStreamSupplier.get(), offset, uniquifier, maxTokenLength);
    }

    private int calcThreads(long fileSize) {
        int maxUsefulThreads = Math.max((int) (fileSize / SIZE_PER_THREAD),1);
        int processors = Runtime.getRuntime().availableProcessors();
        return Math.min(maxUsefulThreads, processors);
    }


    protected void initializeWorkers() throws IOException {
        workers.clear();
        int threads = calcThreads(fileSize);
        long offsetPerThread = fileSize / threads;
        for (int i=0;i<threads;i++) {
            ParallelEDIFParserWorker worker = makeWorker(i*offsetPerThread);
            workers.add(worker);
        }
    }

    private int numberOfThreads;
    public EDIFNetlist parseEDIFNetlist(CodePerfTracker t) throws IOException {

        t.start("Initialize workers");
        initializeWorkers();
        numberOfThreads = workers.size();

        t.stop().start("Parse First Token");
        final List<ParallelEDIFParserWorker> failedWorkers = ParallelismTools.maybeToParallel(workers.stream())
                .filter(w -> !w.parseFirstToken())
                .collect(Collectors.toList());
        if (!failedWorkers.isEmpty() && !Device.QUIET_MESSAGE) {
            for (ParallelEDIFParserWorker failedWorker : failedWorkers) {
                if (failedWorker.parseException!=null) {
                    String message = failedWorker.parseException.getMessage();
                    if (failedWorker.parseException instanceof TokenTooLongException) {
                        //Message contains a hint to a constant that the user should adjust.
                        //Token misdetection is the more likely cause, so let's adjust it
                        message = "Likely token mistetection";
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


    public void printNetlistStats(EDIFNetlist netlist) {

        for (ParallelEDIFParserWorker worker : workers) {
            worker.printParseStats(fileSize);
        }

        System.out.println("cell byte lengths = " + getCellByteLengths().summaryStatistics());

        final LongStream portInstLinksPerThread = workers.stream().mapToLong(w -> w.linkPortInstData.size());

        System.out.println("portInstLinksPerThread = " + portInstLinksPerThread.summaryStatistics());

        final IntStream cellInstsPerCell     = netlist.getLibraries().stream().flatMap(l -> l.getCells().stream()).mapToInt(c -> c.getCellInsts().size());
        final IntStream cellInstPortsPerCell = netlist.getLibraries().stream().flatMap(l -> l.getCells().stream()).mapToInt(c -> c.getCellInsts().stream().mapToInt(ci -> ci.getCellPorts().size()).sum());
        final IntStream cellInstPortsPerCellInst = netlist.getLibraries().stream().flatMap(l -> l.getCells().stream()).flatMap(c -> c.getCellInsts().stream()).mapToInt(ci -> ci.getCellPorts().size());

        final Map<String, Long> countsPerName = netlist.getLibraries().stream().flatMap(l -> l.getCells().stream()).collect(Collectors.groupingBy(EDIFName::getLegalEDIFName, Collectors.counting()));
        LongStream nameCollisions = countsPerName.values().stream().filter(l->l!=1).mapToLong(l->l);


        System.out.println("nameCollisions = " + nameCollisions.summaryStatistics());
        System.out.println("cellInstsPerCell = " + cellInstsPerCell.summaryStatistics());
        System.out.println("cellInstPortsPerCell = " + cellInstPortsPerCell.summaryStatistics());
        System.out.println("cellInstPortsPerCellInst = " + cellInstPortsPerCellInst.summaryStatistics());

        final Map<Boolean, Long> cellReferenceHasLibraryName = workers.stream().flatMap(w -> w.linkCellReference.stream()).collect(Collectors.partitioningBy(d -> d.libraryref != null, Collectors.counting()));
        System.out.println("cellReferenceHasLibraryName = " + cellReferenceHasLibraryName);


    }
    
    @NotNull
    private LongStream getCellByteLengths() {
        ParallelEDIFParserWorker.LibraryOrCellResult lastItem = null;
        final LongStream.Builder lengths = LongStream.builder();
        for (ParallelEDIFParserWorker worker : workers) {
            for (ParallelEDIFParserWorker.LibraryOrCellResult item : worker.librariesAndCells) {
                if (lastItem !=null && (item instanceof ParallelEDIFParserWorker.CellResult)) {
                    lengths.add(item.getToken().byteOffset - lastItem.getToken().byteOffset);
                }
                lastItem = item;
            }
        }
        return lengths.build();
    }


    private void doParse() {
        ParallelismTools.maybeToParallel(workers.stream()).forEach(w->w.doParse(false));

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

    private void addCellsAndLibraries(EDIFNetlist netlist) {
        EDIFLibrary currentLibrary = null;
        EDIFToken currentToken = null;
        for (ParallelEDIFParserWorker worker : workers) {
            for (ParallelEDIFParserWorker.LibraryOrCellResult parsed : worker.librariesAndCells) {
                if (currentToken!=null && parsed.getToken().byteOffset<= currentToken.byteOffset) {
                    throw new IllegalStateException("Not in ascending order! seen: "+currentToken+", now processed "+parsed.getToken());
                }
                currentToken = parsed.getToken();

                currentLibrary = parsed.addToNetlist(netlist, currentLibrary);
            }
        }
    }

    private void processCellInstLinks(EDIFNetlist netlist) {
        final Map<String, EDIFLibrary> librariesByLegalName = netlist.getLibraries().stream()
                .collect(Collectors.toMap(EDIFName::getLegalEDIFName, Function.identity()));

        ParallelismTools.maybeToParallel(workers.stream()).flatMap(ParallelEDIFParserWorker::streamCellReferences).forEach(w->w.apply(librariesByLegalName));
    }

    private void processPortInstLinks(CodePerfTracker t) {
        t.start("Link Small Port Cells");
        //We have to map from a string representation of the ports' names to the ports.
        //Directly map the ports from small cells, add ports from large cells to this map
        Map<EDIFCell, Collection<ParallelEDIFParserWorker.LinkPortInstData>> byPortCell = new ConcurrentHashMap<>();
        ParallelismTools.maybeToParallel(workers.stream())
                .forEach(worker -> worker.linkSmallPorts(byPortCell));
        t.stop().start("Link Large Port Cells");
        //Now we can create a map of ports just for large cells and look up the ports
        ParallelismTools.maybeToParallel(byPortCell.entrySet().stream())
                        .forEach((entry) -> {
                            EDIFCell cell = entry.getKey();
                            final EDIFPortCache edifPortCache = new EDIFPortCache(cell);
                            for (ParallelEDIFParserWorker.LinkPortInstData linkPortInstData : entry.getValue()) {
                                linkPortInstData.enterPort(edifPortCache);
                            }
                        });
        //Order is irrelevant in naming the port insts
        t.stop().start("Name port insts");
                ParallelismTools.maybeToParallel(workers.stream())
                        .flatMap(w -> w.linkPortInstData.stream())
                        .forEach(list -> {
                            for (ParallelEDIFParserWorker.LinkPortInstData linkPortInstData : list) {
                                linkPortInstData.name();
                            }
                        });
        t.stop().start("Add port insts");
        //When adding the port insts, we have to make sure that we don't split a parent cell's port instances between threads
        //That could lead to ConcurrentModificationExceptions
        ParallelismTools.maybeToParallel(workers.stream())
                .flatMap(w -> w.linkPortInstData.stream())
                .forEach(list -> {
                    for (ParallelEDIFParserWorker.LinkPortInstData linkPortInstData : list) {
                        linkPortInstData.add();
                    }
                });
        t.stop();
    }

    private EDIFNetlist mergeParseResults(CodePerfTracker t) {
        EDIFNetlist netlist = Objects.requireNonNull(workers.get(0).netlist);
        netlist.setDesign(getEdifDesign());

        t.stop().start("Assemble Libraries");
        addCellsAndLibraries(netlist);
        t.stop().start("process cell inst links");
        processCellInstLinks(netlist);
        t.stop();
        processPortInstLinks(t);

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

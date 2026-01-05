/*
 *
 * Copyright (c) 2025, Advanced Micro Devices, Inc.
 * All rights reserved.
 *
 * Author: Chris Lavin, AMD Research and Advanced Development.
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

package com.xilinx.rapidwright.edif.partition;

import java.io.BufferedWriter;
import java.io.FileWriter;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.io.BufferedReader;
import java.io.FileReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;

import com.xilinx.rapidwright.edif.EDIFHierCellInst;
import com.xilinx.rapidwright.edif.EDIFHierNet;
import com.xilinx.rapidwright.edif.EDIFHierPortInst;
import com.xilinx.rapidwright.edif.EDIFNetlist;
import com.xilinx.rapidwright.edif.EDIFTools;
import com.xilinx.rapidwright.design.Design;
import com.xilinx.rapidwright.tests.CodePerfTracker;
import com.xilinx.rapidwright.util.MessageGenerator;

/**
 * Command-line tool to partition an EDIFNetlist
 */
public class Partitioner {

    /**
     * Logs current memory usage for debugging.
     * @param label Description label for the audit point
     */
    private static void memAudit(String label) {
        Runtime rt = Runtime.getRuntime();
        long total = rt.totalMemory();
        long free = rt.freeMemory();
        long used = total - free;
        PartitionTools.logPartitionerDebug(
                System.out,
                "%s -> used=%d MB total=%d MB free=%d MB",
                label, used / (1024 * 1024), total / (1024 * 1024), free / (1024 * 1024));
    }

    /**
     * Creates and returns the default partitioner instance.
     * @return A new MtKaHyParPartitioner instance
     */
    public static AbstractPartitioner getDefaultPartitioner() {
        MtKaHyParPartitioner p = new MtKaHyParPartitioner();
        return p;
    }

    /**
     * Prints command-line usage information to console.
     */
    private static void printUsage() {
        System.out.println("===================================================================");
        System.out.println("                       RAPIDWRIGHT PARTITIONER");
        System.out.println("===================================================================");
        System.out.println();
        System.out.println("Usage:");
        System.out.println("  <input.edf|input.dcp>");
        System.out.println("  <# of partitions>");
        System.out.println("  <leafLUTCountLimit>");
        System.out.println();
        System.out.println("Optional Flags:");
        System.out.println("  --seed N                    Set seed");
        System.out.println("  --epsilon E                 Set partition imbalance");
        System.out.println("  --threads T                 Set number of threads");
        System.out.println("  --partition_config TYPE     Set preset (default/deterministic)");
        System.out.println("  --objective OBJ             Set objective (cut/km1/soed)");
        System.out.println("  --part_dir DIR              Set output directory");
        System.out.println("  --mapping_constraints PATH  Specify constraints file");
        System.out.println("  --constraints_debug         Enable constraint debugging");
        System.out.println("  --skip_detailed_reports     Skip detailed report generation,");
        System.out.println("                              HIGHLY advised for large designs.");
        System.out.println();
        System.out.println("===================================================================");
    }
    
    /**
     * Main entry point for the partitioner tool.
     * @param args Command-line arguments for partitioning
     */
    public static void main(String[] args) {
        if (args.length < 3) {
            printUsage();
            return;
        }
        Path inputPath = Paths.get(args[0]);
        int k = Integer.parseInt(args[1]);
        int leafLUTCountLimit = Integer.parseInt(args[2]);
        CodePerfTracker t = new CodePerfTracker("Partitioner");
        
        boolean generateEdifNets = false; // Human readable hypergraph artifacts
        Path constraintsFile = null;
        boolean constraintsDebug = false;
        boolean skipDetailedReports = false;
        



        // ============================================================================
        // ARGUMENT PARSING & INITIALIZATION
        // Parses command-line flags and sets up initial configuration variables.
        // Establishes the output directory where all partition artifacts will be written.
        // Determines whether to generate detailed reports based on the skip_detailed_reports flag.
        // ============================================================================
        Path outDir = (inputPath.getParent() == null)
                ? Paths.get(System.getProperty("user.dir"))
                : inputPath.getParent();
        // parse early flags that affect artifact emission and locations
        for (int i = 3; i < args.length; i++) {
            String arg = args[i];
            if ("--part_dir".equals(arg) && i + 1 < args.length) {
                outDir = Paths.get(args[++i]);
            } else if (arg.startsWith("--part_dir=")) {
                outDir = Paths.get(arg.substring("--part_dir=".length()));
            } else if ("--mapping_constraints".equals(arg) && i + 1 < args.length) {
                constraintsFile = Paths.get(args[++i]);
            } else if (arg.startsWith("--mapping_constraints=")) {
                constraintsFile = Paths.get(arg.substring("--mapping_constraints=".length()));
            } else if ("--constraints_debug".equals(arg)) {
                constraintsDebug = true;
            } else if (arg.startsWith("--constraints_debug=")) {
                //used for debugging constrained partition mode.
                String val = arg.substring("--constraints_debug=".length()).trim();
                constraintsDebug = "1".equals(val) || "true".equalsIgnoreCase(val) ||
                        "yes".equalsIgnoreCase(val);
            } else if ("--skip_detailed_reports".equals(arg)) { //human-readable partition outputs
                skipDetailedReports = true;
            } else if (arg.startsWith("--skip_detailed_reports=")) {
                String val = arg.substring("--skip_detailed_reports=".length()).trim();
                skipDetailedReports = "1".equals(val) || "true".equalsIgnoreCase(val) ||
                        "yes".equalsIgnoreCase(val);
            }
        }
        generateEdifNets = !skipDetailedReports;
        try {
            Files.createDirectories(outDir);
        } catch (IOException ioe) {
            throw new UncheckedIOException(ioe);
        }



        // ============================================================================
        // INPUT READING & VALIDATION
        // Reads the input design from either a DCP or EDIF file.
        // Validates that the design is not encrypted, which is unsupported.
        // Extracts the netlist for partitioning and tracks memory usage.
        // ============================================================================
        memAudit("rapidwright mem usg input before");
        t.start("Read Input");
        EDIFNetlist netlist;
        String inLower = inputPath.toString().toLowerCase();
        if (inLower.endsWith(".dcp")) {
            Design design = Design.readCheckpoint(inputPath.toString());
            netlist = design.getNetlist();
            int encryptedCount = netlist.getEncryptedCells().size();
            if (encryptedCount > 0) {
                throw new RuntimeException("ERROR: Encrypted DCP detected (encryptedCells=" + 
                        encryptedCount + "). Encrypted DCPs are unsupported.");
            }
        } else {
            netlist = EDIFTools.readEdifFile(inputPath);
        }
        t.stop();
        memAudit("rapidwright mem usg input after");



        // ============================================================================
        // NETLIST COARSENING
        // Identifies leaf instances in the netlist hierarchy based on the LUT count threshold.
        // For DCP inputs, backfills missing LUT counts for hierarchical instances.
        // Validates that the leaf LUT count limit is within acceptable bounds.
        // ============================================================================
        t.start("Coarsen Netlist");
        Map<EDIFHierCellInst, Integer> instLutCountMap = new HashMap<>();
        Map<EDIFHierCellInst, Integer> leafInsts = PartitionTools.identifyLeafInstances(netlist,
                leafLUTCountLimit, instLutCountMap);
        System.out.println("Identified " + leafInsts.size() + " leaves");
        {
            final int SAMPLE_TARGET = 100_000;
            final int STEP = Math.max(1, leafInsts.size() / SAMPLE_TARGET);
            long sampledHier = 0;
            long missingHier = 0;
            int idx = 0;
            for (EDIFHierCellInst ci : leafInsts.keySet()) {
                if ((idx++ % STEP) != 0) continue;
                if (!ci.getCellType().isLeafCellOrBlackBox()) {
                    sampledHier++;
                    if (!instLutCountMap.containsKey(ci)) {
                        missingHier++;
                    }
                }
            }
            double pct = sampledHier == 0 ? 0.0 : 100.0 * missingHier / sampledHier;
            /* 
             * Reports sampled coverage of missing lut counts among hierarchical 
             * leaves to confirm incomplete instance coverage
             */
            PartitionTools.logPartitionerDebug(
                    System.out,
                    "leafInsts coverage -> sampledHier=%d missingHier=%d pct=%.4f step=%d",
                    sampledHier, missingHier, pct, STEP); 
        }
        if (inLower.endsWith(".dcp")) {
            PartitionTools.logPartitionerDebug(
                    System.err,
                    "DCP backfill start -> leafCount=%d",
                    leafInsts.size());
            int backfillAttempts = 0;
            int backfillSuccess = 0;
            int backfillSkipped = 0;
            java.util.Map<com.xilinx.rapidwright.edif.EDIFCell, java.lang.Integer> lutCountCache;
            // Caches LUT counts to avoid expensive re-calculation during backfill process.
            lutCountCache = new java.util.HashMap<>();
            for (EDIFHierCellInst leaf : leafInsts.keySet()) {
                if (!leaf.getCellType().isLeafCellOrBlackBox()) {
                    if (instLutCountMap.get(leaf) == null) {
                        backfillAttempts++;
                        if (backfillAttempts <= 10) {
                            PartitionTools.logPartitionerDebug(
                                    System.err,
                                    "DCP backfill attempt -> instPath=%s cellType=%s",
                                    leaf.toString(), leaf.getCellType().getName());
                        }
                        PartitionTools.getLUTCount(leaf, lutCountCache, instLutCountMap);
                        if (instLutCountMap.get(leaf) != null) {
                            backfillSuccess++;
                            if (backfillSuccess <= 10) {
                                PartitionTools.logPartitionerDebug(
                                        System.err,
                                        "DCP backfill success -> instPath=%s lutCount=%d",
                                        leaf.toString(), instLutCountMap.get(leaf));
                            }
                        }
                    } else {
                        backfillSkipped++;
                    }
                }
            }
            PartitionTools.logPartitionerDebug(
                    System.err,
                    "DCP backfill complete -> attempts=%d success=%d skipped=%d",
                    backfillAttempts, backfillSuccess, backfillSkipped);
        }
        int totalLUTs = instLutCountMap.get(netlist.getTopHierCellInst());
        if (leafLUTCountLimit >= totalLUTs || leafLUTCountLimit < 1) {
            throw new RuntimeException("ERROR: Invalid leafLUTCountLimit '" + leafLUTCountLimit
                    + "', must be less than total LUT count in netlist or 1 or greater.");
        }
        t.stop();



        // ============================================================================
        // EDGE DISCOVERY
        // Builds a map of nets (edges) to the instances (vertices) they connect.
        // Uses parent-level nets to avoid duplication and handle hierarchical connectivity.
        // Creates the hypergraph structure that will be partitioned.
        // ============================================================================
        t.start("Find Edges");
        Map<EDIFHierNet, Set<EDIFHierCellInst>> edgesMap = new HashMap<>();
        for (Entry<EDIFHierCellInst, Integer> e : leafInsts.entrySet()) {
            for (EDIFHierPortInst pi : e.getKey().getHierPortInsts()) {
                EDIFHierNet connectedNet = pi.getHierarchicalNet();
                // Skip ambiguous nets, TODO: better way to handle this edge case?
                EDIFHierNet parentNet = null;
                try {
                    parentNet = netlist.getParentNet(connectedNet);
                } catch (RuntimeException ex) {
                    parentNet = null;
                }
                EDIFHierNet keyNet = (parentNet != null) ? parentNet : connectedNet;
                if (edgesMap.containsKey(keyNet))
                    continue;
                edgesMap.put(keyNet, connectedNet.getConnectedInsts(leafInsts.keySet())); 
            }
        }
        t.stop();



        // ============================================================================
        // HYPERGRAPH FILE GENERATION
        // Writes the hypergraph to a .hgr file in hMETIS format for the partitioner.
        // Optionally generates a .eidmap file mapping edge IDs to net names.
        // Frees the edge map memory to reduce memory footprint before partitioning.
        // ============================================================================
        t.start("Write hMETIS File");
        Path hMetisFile = outDir.resolve(inputPath.getFileName().toString() + ".hgr");
        PartitionTools.writeHMetisFile(hMetisFile, edgesMap, leafInsts, generateEdifNets);
        t.stop();

        //free edgesMap memory after .hgr file is written
        edgesMap.clear();
        edgesMap = null;
        System.gc();



        // ============================================================================
        // FIXED VERTICES COMPUTATION
        // Processes user-provided mapping constraints to fix certain instances to
        // specific partitions. Matches constraint paths against leaf instances and
        // assigns partition IDs. Generates a .fix file that tells the partitioner
        // which vertices are constrained.
        // ============================================================================
        Path fixedVertexFile = null; // generate fixed vertices file if constraints were provided
        if (constraintsFile != null) {
            // build label->index map
            java.util.Map<String, Integer> labelToIndex = new java.util.HashMap<>();
            for (int idx = 0; idx < k; idx++) {
                String lbl = PartitionLabel.indexToFpgaLabel(idx);
                labelToIndex.put(lbl, Integer.valueOf(idx));
            }
            // init fix array (1-based vertex ids)
            int numVertices = leafInsts.size();
            int[] vertexPartitionAssignments = new int[numVertices + 1];
            for (int i = 0; i <= numVertices; i++) vertexPartitionAssignments[i] = -1;
            int numFixedVertices = 0;

            // parse mapping_constraints.txt and expand to vertices
            try (BufferedReader cr = new BufferedReader(new FileReader(constraintsFile.toFile()))) {
                String cline;
                while ((cline = cr.readLine()) != null) {
                    String orig = cline.trim();
                    if (orig.isEmpty() || orig.startsWith("#")) continue; //comments and blankline
                    String[] toks = orig.split("\\s+");
                    if (toks.length != 2) {
                        throw new RuntimeException(
                                "constraint '" + orig + "' was not found valid in design");
                    }
                    String path = toks[0];
                    String label = toks[1];
                    Integer blockIndex = labelToIndex.get(label);
                    if (blockIndex == null) {
                        throw new RuntimeException(
                                "constraint '" + orig + "' was not found valid in design");
                    }
                    int matched = 0;
                    for (Entry<EDIFHierCellInst, Integer> e : leafInsts.entrySet()) {
                        String name = e.getKey().toString();
                        int vertex_id = e.getValue().intValue();
                        boolean leafContainsPath = path.equals(name) || 
                                path.startsWith(name + "/");
                        boolean pathContainsLeaf = name.equals(path) || 
                                name.startsWith(path + "/");
                        if (leafContainsPath || pathContainsLeaf) {
                            if (vertexPartitionAssignments[vertex_id] == -1) {
                                vertexPartitionAssignments[vertex_id] = blockIndex.intValue();
                                numFixedVertices++;
                            } else if (vertexPartitionAssignments[vertex_id] != 
                                    blockIndex.intValue()) {
                                throw new RuntimeException(
                                        "constraint '" + orig + "' was not found valid in design");
                            }
                            matched++;
                        }
                    }
                    if (constraintsDebug) {
                        //vertex (LEAF) matches when constraint path is inside leaf
                        // or leaf is inside the constrained subtree.
                        System.out.printf(
                                "partitioner debug: constraint '%s' matched %d vertices%n",
                                orig, matched);
                    }
                    if (matched == 0) {
                        throw new RuntimeException(
                                "constraint '" + orig + "' was not found valid in design");
                    }
                }
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }

            // derive fix file path from .hgr
            // will show constrained modules to MtKahyPar,
            // -1 values for unconstrained vertices
            String hypergraphBasePath = hMetisFile.toString();
            if (hypergraphBasePath.endsWith(".hgr")) {
                fixedVertexFile = Paths.get(
                        hypergraphBasePath.substring(0, hypergraphBasePath.length() - 4) + ".fix");
            } else {
                fixedVertexFile = Paths.get(hypergraphBasePath + ".fix");
            }
            try (BufferedWriter fw = new BufferedWriter(new FileWriter(fixedVertexFile.toFile()))) {
                for (int vertex_id = 1; vertex_id <= numVertices; vertex_id++) {
                    fw.write(Integer.toString(vertexPartitionAssignments[vertex_id]));
                    fw.write("\n");
                }
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
            System.out.printf("partitioner debug: fix-file=%s vertices=%d fixed=%d%n",
                    fixedVertexFile, leafInsts.size(), numFixedVertices);
        }
        



        // ============================================================================
        // PARTITIONER EXECUTION
        // Configures the external MtKaHyPar partitioning tool with user-specified parameters.
        // Sets options like seed, epsilon, threads, preset type, and objective function.
        // Executes the partitioner and tracks memory usage before and after.
        // ============================================================================
        t.start("Run Partitioner");
        AbstractPartitioner partitioner = getDefaultPartitioner();
        partitioner.setInputFile(hMetisFile);
        partitioner.setKPartitions(k);
        // optional args
        if (partitioner instanceof MtKaHyParPartitioner) {
            MtKaHyParPartitioner mtPartitioner = (MtKaHyParPartitioner) partitioner;
            // ensure external tool runs and writes outputs into outDir
            mtPartitioner.setOutputDir(outDir);
            // pass fixed vertices file if detected
            if (fixedVertexFile != null) {
                mtPartitioner.setFixedVerticesFile(fixedVertexFile);
            }
            for (int i = 3; i < args.length; i++) {
                String arg = args[i];
                if (arg.equals("--seed") && i + 1 < args.length) {
                    mtPartitioner.setSeed(Integer.parseInt(args[++i]));
                } else if (arg.startsWith("--seed=")) {
                    mtPartitioner.setSeed(Integer.parseInt(arg.substring("--seed=".length())));
                } else if (arg.equals("--epsilon") && i + 1 < args.length) {
                    mtPartitioner.setEpsilon(Double.parseDouble(args[++i]));
                } else if (arg.startsWith("--epsilon=")) {
                    mtPartitioner.setEpsilon(Double.parseDouble(
                            arg.substring("--epsilon=".length())));
                } else if (arg.equals("--threads") && i + 1 < args.length) {
                    mtPartitioner.setNumThreads(Integer.parseInt(args[++i]));
                } else if (arg.startsWith("--threads=")) {
                    mtPartitioner.setNumThreads(Integer.parseInt(
                            arg.substring("--threads=".length())));
                } else if (arg.equals("--partition_config") && i + 1 < args.length) {
                    mtPartitioner.setPresetType(args[++i]);
                } else if (arg.startsWith("--partition_config=")) {
                    mtPartitioner.setPresetType(arg.substring("--partition_config=".length()));
                } else if (arg.equals("--objective") && i + 1 < args.length) {
                    mtPartitioner.setObjective(args[++i]);
                } else if (arg.startsWith("--objective=")) {
                    mtPartitioner.setObjective(arg.substring("--objective=".length()));
                }
            }
            // print user flags summary
            System.out.printf("Partitioner flag: objective=%s%n", mtPartitioner.getObjective());
            System.out.printf("Partitioner flag: part_dir=%s%n", outDir);
            System.out.printf("Partitioner flag: mapping_constraints=%s%n",
                  constraintsFile != null ? constraintsFile.toString() : "none");
            System.out.printf("Partitioner flag: skip_detailed_reports=%s%n",
                    skipDetailedReports ? "on" : "off");
        }
        memAudit("rapidwright mem usg before partitioner run");
        partitioner.runPartitioner();
        memAudit("rapidwright mem usg after partitioner run");
        t.stop();
        



        // ============================================================================
        // SOLUTION PROCESSING - BARE BONES MODE
        // Reads the partition solution and computes per-partition LUT counts
        // efficiently. Generates minimal output artifacts: io_cuts.txt,
        // lut_report.txt, cells.txt, and mapping.txt. Skips detailed reports like
        // .eidmap and .nets.txt to reduce artifact size.
        // ============================================================================
        t.start("Read Partition Solution");
        Path outputFile = partitioner.getOutputFile();
        String[] instLookup = PartitionTools.createInstLookupArray(leafInsts);
        Map<Integer, Set<String>> partitions;
        partitions = PartitionTools.readSolutionFile(outputFile, instLookup);
        if (skipDetailedReports) {
            int numVertices = instLookup.length - 1;
            int[] vertexToPartition = new int[numVertices + 1];
            try (BufferedReader br = new BufferedReader(new FileReader(outputFile.toFile()))) {
                String line = null;
                int i = 1;
                while ((line = br.readLine()) != null) {
                    int part = Integer.parseInt(line.trim());
                    vertexToPartition[i++] = part;
                }
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
            EDIFHierCellInst[] instanceByVertexID = new EDIFHierCellInst[numVertices + 1];
            for (Entry<EDIFHierCellInst, Integer> e : leafInsts.entrySet()) {
                int vertex_id = e.getValue().intValue();
                instanceByVertexID[vertex_id] = e.getKey();
            }
            int[] lutCountByVertexID = new int[numVertices + 1];
            int dbgLeafLUTs = 0;
            int dbgHierLUTs = 0;
            int dbgHierMissing = 0;
            int dbgHierFound = 0;
            java.util.Map<String, Integer> dbgMissingCellTypes = new java.util.HashMap<>();
            java.util.Map<String, Integer> dbgMissingParentTypes = new java.util.HashMap<>();
            for (int vertex_id = 1; vertex_id <= numVertices; vertex_id++) {
                EDIFHierCellInst inst = instanceByVertexID[vertex_id];
                if (inst == null) continue;
                if (inst.getCellType().isLeafCellOrBlackBox()) {
                    lutCountByVertexID[vertex_id] = logicDiscoveryPolicy.leafLutCount(inst);
                    dbgLeafLUTs += lutCountByVertexID[vertex_id];
                } else {
                    Integer cnt = instLutCountMap.get(inst);
                    lutCountByVertexID[vertex_id] = (cnt == null) ? 0 : cnt.intValue();
                    dbgHierLUTs += lutCountByVertexID[vertex_id];
                    if (cnt == null) {
                        dbgHierMissing++;
                        String cellType = inst.getCellType().getName();
                        dbgMissingCellTypes.put(cellType,
                                dbgMissingCellTypes.getOrDefault(cellType, 0) + 1);
                        if (inst.getParent() != null) {
                            String parentType = inst.getParent().getCellType().getName();
                            dbgMissingParentTypes.put(parentType,
                                    dbgMissingParentTypes.getOrDefault(parentType, 0) + 1);
                        }
                        if (dbgHierMissing <= 10) {
                            String parentInfo = (inst.getParent() != null) ?
                                    inst.getParent().getCellType().getName() : "null";
                            PartitionTools.logPartitionerDebug(
                                    System.err,
                                    "skip_detailed_reports hier missing -> vertex_id=%d " +
                                    "instPath=%s cellType=%s parentType=%s lutCount=0 (missing)",
                                    vertex_id, inst.toString(),
                                    inst.getCellType().getName(), parentInfo);
                        }
                    } else {
                        dbgHierFound++;
                    }
                }
            }
            PartitionTools.logPartitionerDebug(
                    System.err,
                    "skip_detailed_reports lutCountByVertexID -> leafLUTs=%d " +
                            "hierLUTs=%d hierMissing=%d hierFound=%d",
                    dbgLeafLUTs, dbgHierLUTs, dbgHierMissing, dbgHierFound);
            if (!dbgMissingCellTypes.isEmpty()) {
                PartitionTools.logPartitionerDebug(
                        System.err,
                        "skip_detailed_reports missing cell types -> uniqueTypes=%d",
                        dbgMissingCellTypes.size());
                java.util.List<java.util.Map.Entry<String, Integer>> sorted;
                sorted = new java.util.ArrayList<>(dbgMissingCellTypes.entrySet());
                sorted.sort((a, b) -> Integer.compare(b.getValue(), a.getValue()));
                for (int i = 0; i < Math.min(10, sorted.size()); i++) {
                    PartitionTools.logPartitionerDebug(
                            System.err,
                            "missing cell type -> type=%s count=%d",
                            sorted.get(i).getKey(), sorted.get(i).getValue());
                }
            }
            if (!dbgMissingParentTypes.isEmpty()) {
                PartitionTools.logPartitionerDebug(
                        System.err,
                        "missing instances parent types -> uniqueParents=%d",
                        dbgMissingParentTypes.size());
                java.util.List<java.util.Map.Entry<String, Integer>> sortedParents;
                sortedParents = new java.util.ArrayList<>(dbgMissingParentTypes.entrySet());
                sortedParents.sort((a, b) -> Integer.compare(b.getValue(), a.getValue()));
                for (int i = 0; i < Math.min(10, sortedParents.size()); i++) {
                    PartitionTools.logPartitionerDebug(
                            System.err,
                            "missing parent type -> type=%s childrenMissing=%d",
                            sortedParents.get(i).getKey(), sortedParents.get(i).getValue());
                }
            }
            Path hgr = outDir.resolve(inputPath.getFileName().toString() + ".hgr");
            java.util.Map<String, Integer> nameToPartMap = new java.util.LinkedHashMap<>();
            for (int vertex_id = 1; vertex_id <= numVertices; vertex_id++) {
                String instanceName = instLookup[vertex_id];
                if (instanceName != null) {
                    nameToPartMap.put(instanceName,
                            Integer.valueOf(vertexToPartition[vertex_id]));
                }
            }
            Map<String, Integer> pairCounts = PartitionPairCounter.countPartitionPairs(
                    hgr, instLookup, nameToPartMap);
            Path ioCutsFile = outDir.resolve("io_cuts.txt");
            PartitionPairCounter.writePairCountsToFile(ioCutsFile, pairCounts);
            java.util.ArrayList<Integer> lutCounts = new java.util.ArrayList<>();
            int maxPart = -1;
            for (int vertex_id = 1; vertex_id <= numVertices; vertex_id++) {
                int partIndex = vertexToPartition[vertex_id];
                if (partIndex > maxPart) maxPart = partIndex;
            }
            for (int i = 0; i <= maxPart; i++) lutCounts.add(0);
            for (int vertex_id = 1; vertex_id <= numVertices; vertex_id++) {
                int partIndex = vertexToPartition[vertex_id];
                if (partIndex < 0) continue;
                lutCounts.set(partIndex, lutCounts.get(partIndex) + lutCountByVertexID[vertex_id]);
            }
            long sumLuts = 0;
            for (int c : lutCounts) sumLuts += c;
            PartitionTools.logPartitionerDebug(
                    System.err,
                    "skip_detailed_reports partition sum -> sumLUTs=%d " +
                            "topLevelTotal=%d diff=%d",
                    sumLuts, totalLUTs, (sumLuts - totalLUTs));
            int meanLuts = (lutCounts.isEmpty()) ?
                    0 : (int) Math.ceil(sumLuts / (double) lutCounts.size());
            Path lutReport = outDir.resolve("lut_report.txt");
            try (BufferedWriter lw = new BufferedWriter(new FileWriter(lutReport.toFile()))) {
                for (int idx = 0; idx < lutCounts.size(); idx++) {
                    String label = PartitionLabel.indexToFpgaLabel(idx);
                    lw.write(lutCounts.get(idx) + "LUTS " + label);
                    lw.write("\n");
                }
                lw.write(meanLuts + "LUTS mean");
                lw.write("\n");
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
            System.out.println("Partitioner artifact written: " + lutReport);
            if (sumLuts != totalLUTs) {
                System.err.printf("ERROR: LUT count policy mismatch: " + 
                    "per-partitions sum=%d, top-level total=%d%n", sumLuts, totalLUTs);
            }
            try {
                HierMappingWriter.write(outDir, netlist, partitions, instLutCountMap);
                System.out.println("Partitioner artifact written: " + 
                    outDir.resolve("cells.txt"));
                System.out.println("Partitioner artifact written: " + 
                    outDir.resolve("mapping.txt"));
            } catch (RuntimeException ex) {
                System.err.println("WARNING: failed to write hierarchical mapping artifacts: " + 
                    ex.getMessage());
            }
        } else {



            // ============================================================================
            // SOLUTION PROCESSING - FULL MODE
            // Reads the partition solution and writes detailed per-partition instance
            // files. Computes LUT counts for each partition with fallback recomputation
            // if needed. Generates comprehensive artifacts including io cuts, nets
            // report, and hierarchical mappings.
            // ============================================================================
            try {
                IoCutWriter.write(outDir, inputPath, netlist, instLookup, partitions,
                        generateEdifNets);
            } catch (RuntimeException ex) {
                System.err.println("WARNING: failed to write io cuts / nets artifacts: " +
                        ex.getMessage());
            }
            // Caches instance LUT counts to speed up report generation.
            java.util.Map<String, Integer> lutByName = new java.util.HashMap<>();

            {
                int mismatches = 0;
                int maxCheck = Math.min(1000, instLookup.length - 1);
                for (int j = 1; j <= maxCheck; j++) {
                    String nm = instLookup[j];
                    if (nm == null) continue;
                    if (netlist.getHierCellInstFromName(nm) == null) {
                        mismatches++;
                    }
                }
                if (mismatches > 0) {
                    PartitionTools.logPartitionerDebug(
                            System.err,
                            "name roundtrip -> mismatches=%d checked=%d",
                            mismatches, maxCheck);
                } else {
                    PartitionTools.logPartitionerDebug(System.out, "name roundtrip -> ok");
                }
            }
            MessageGenerator.printHeader("Partition Solution Report");
            final int DBG_MAX_MISS_LOGS = 100;
            int dbgMissingLUTCountTotal = 0;
            int dbgMissingLUTCountPrinted = 0;
            int dbgLeafLUTsTotal = 0;
            int dbgHierLUTsTotal = 0;
            int dbgRecomputeAttempts = 0;
            int dbgRecomputeSuccess = 0;
            int dbgCachedByName = 0;
            java.util.ArrayList<Integer> lutCounts = new java.util.ArrayList<>();
            for (int i = 0; i < partitions.size(); i++) {
                int lutCount = 0;
                int partLeafLUTs = 0;
                int partHierLUTs = 0;
                Set<String> names = partitions.get(i);
                String partLabel = PartitionLabel.indexToFpgaLabel(i);
                Path partitionFile = outDir.resolve(
                        inputPath.getFileName().toString() + "." + partLabel);
                try (BufferedWriter bw = new BufferedWriter(
                        new FileWriter(partitionFile.toFile()))) {
                    for (String name : names) {
                        bw.write(name + "\n");
                        EDIFHierCellInst inst = netlist.getHierCellInstFromName(name);
                        if (inst.getCellType().isLeafCellOrBlackBox()) {
                            int leafLUTs = logicDiscoveryPolicy.leafLutCount(inst);
                            lutCount += leafLUTs;
                            partLeafLUTs += leafLUTs;
                        } else {
                            Integer hierLutCount = instLutCountMap.get(inst);
                            if (hierLutCount == null) {
                                Integer cached = lutByName.get(name);
                                if (cached != null) {
                                    hierLutCount = cached;
                                    dbgCachedByName++;
                                }
                            }
                            if (hierLutCount == null) {
                                dbgMissingLUTCountTotal++;
                                if (dbgMissingLUTCountPrinted < DBG_MAX_MISS_LOGS) {
                                    PartitionTools.logPartitionerDebug(
                                            System.err,
                                            "missing lut count -> name=%s instPath=%s type=%s " +
                                                    "depth=%d isLeafOrBB=%s",
                                            name, inst.toString(), inst.getCellType().getName(),
                                            inst.getDepth(),
                                            inst.getCellType().isLeafCellOrBlackBox());
                                    dbgMissingLUTCountPrinted++;
                                }
                                dbgRecomputeAttempts++;
                                Integer recomputed = PartitionTools.getLUTCount(inst,
                                        new java.util.HashMap<>(), instLutCountMap);
                                if (recomputed != null) {
                                    hierLutCount = recomputed;
                                    lutByName.put(name, hierLutCount);
                                    dbgRecomputeSuccess++;
                                }
                            }
                            if (hierLutCount == null) {
                                hierLutCount = 0;
                            }
                            lutCount += hierLutCount.intValue();
                            partHierLUTs += hierLutCount.intValue();
                        }
                    }
                    dbgLeafLUTsTotal += partLeafLUTs;
                    dbgHierLUTsTotal += partHierLUTs;
                    System.out.printf("  Partition %3d %10d LUTs %s\n", i, lutCount, partitionFile);
                    lutCounts.add(Integer.valueOf(lutCount));
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            }
            PartitionTools.logPartitionerDebug(
                    System.err,
                    "all partitions -> leafLUTsTotal=%d hierLUTsTotal=%d " +
                            "cachedByName=%d recomputeAttempts=%d recomputeSuccess=%d",
                    dbgLeafLUTsTotal, dbgHierLUTsTotal, dbgCachedByName,
                    dbgRecomputeAttempts, dbgRecomputeSuccess);
            long sumLuts = 0;
            for (int c : lutCounts) sumLuts += c;
            PartitionTools.logPartitionerDebug(
                    System.err,
                    "non-skip_detailed_reports partition sum -> " + 
                        "sumLUTs=%d topLevelTotal=%d diff=%d",
                        sumLuts, totalLUTs, (sumLuts - totalLUTs));

            int meanLuts = (lutCounts.isEmpty()) ?
                    0 : (int) Math.ceil(sumLuts / (double) lutCounts.size());
            Path lutReport = outDir.resolve("lut_report.txt");
            try (BufferedWriter lw = new BufferedWriter(new FileWriter(lutReport.toFile()))) {
                for (int idx = 0; idx < lutCounts.size(); idx++) {
                    String label = PartitionLabel.indexToFpgaLabel(idx);
                    lw.write(lutCounts.get(idx) + "LUTS " + label);
                    lw.write("\n");
                }
                lw.write(meanLuts + "LUTS mean");
                lw.write("\n");
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
            System.out.println("Partitioner artifact written: " + lutReport);
            if (sumLuts != totalLUTs) {
                System.err.printf(
                        "ERROR: LUT count policy mismatch: per-partitions sum=%d, " +
                                "top-level total=%d%n",
                        sumLuts, totalLUTs);
            }
            PartitionTools.logPartitionerDebug(
                    System.out,
                    "missing lut count summary -> total=%d printed=%d limit=%d",
                    dbgMissingLUTCountTotal, dbgMissingLUTCountPrinted, DBG_MAX_MISS_LOGS);
            System.out.println("-----------------------------------------------------------");
            System.out.printf("        Total : %10d LUTs\n\n\n", totalLUTs);

            try {
                HierMappingWriter.write(outDir, netlist, partitions, instLutCountMap);
                System.out.println("Partitioner artifact written: " +
                        outDir.resolve("cells.txt"));
                System.out.println("Partitioner artifact written: " +
                        outDir.resolve("mapping.txt"));
            } catch (RuntimeException ex) {
                System.err.println("WARNING: failed to write hierarchical " +
                        "mapping artifacts: " + ex.getMessage());
            }
        }

        t.stop();
        t.printSummary();
    }
}

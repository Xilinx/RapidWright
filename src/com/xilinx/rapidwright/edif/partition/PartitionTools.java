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

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Queue;
import java.util.Set;

import com.xilinx.rapidwright.edif.EDIFCell;
import com.xilinx.rapidwright.edif.EDIFCellInst;
import com.xilinx.rapidwright.edif.EDIFHierCellInst;
import com.xilinx.rapidwright.edif.EDIFHierNet;
import com.xilinx.rapidwright.edif.EDIFNetlist;

/**
 * Collection of methods used to export, analyze and parse hMETIS-based
 * partitioning problems and solutions from RapidWright Netlists.
 */
public class PartitionTools {


    /**
     * Helper to print partitioner debug messages with a consistent prefix.
     * @param stream Output stream for debug messages
     * @param format Format string for the message
     * @param args Arguments for the format string
     */
    public static void logPartitionerDebug(java.io.PrintStream stream, String format, 
            Object... args) {
        if (stream == null) {
            stream = System.out;
        }
        stream.printf("PARTITIONER DEBUG: " + format + "%n", args);
    }

    /**
     * Calculates LUT count for hierarchical instance.
     * @param hierInst Hierarchical cell instance
     * @param cellTypeCache Cell type cache
     * @param instMap Instance map
     * @return LUT count
     */
    public static Integer getLUTCount(EDIFHierCellInst hierInst,
            Map<EDIFCell, Integer> cellTypeCache,
            Map<EDIFHierCellInst, Integer> instMap) {
        return LUTCountingUtils.computeLUTCount(hierInst, cellTypeCache, instMap);
    }

    /**
     * A method to select coarser-grained leaves for the netlist being presented to
     * the partitioner by identifying hierarchical cells that are of a certain LUT
     * count.
     * 
     * @param netlist  The current netlist considered for partitioning.
     * @param lutCount The LUT count threshold under which hierarchical cells should
     *                 be considered as leaves in the partitioner-presented graph.
     * @return A map of all coarsened leaf hierarchical instances mapped to a unique
     *         index.
     */
    public static Map<EDIFHierCellInst, Integer> identifyLeafInstances(EDIFNetlist netlist,
            int lutCount, 
            Map<EDIFHierCellInst, Integer> instMap) {
        EDIFHierCellInst topInst = netlist.getTopHierCellInst();
        Map<EDIFCell, Integer> lutCountMap = new HashMap<>();
        LUTCountingUtils.dbgCacheHits = 0;
        LUTCountingUtils.dbgRecursiveCalls = 0;
        LUTCountingUtils.dbgInstMapWrites = 0;
        LUTCountingUtils.dbgCacheHitInstMapWrites = 0;
        LUTCountingUtils.dbgCacheHitLogBudget = 10;
        logPartitionerDebug(System.err,
                "identifyLeafInstances start -> topInst=%s lutCountThreshold=%d",
                topInst.toString(), lutCount);
        getLUTCount(topInst, lutCountMap, instMap);
        logPartitionerDebug(System.err,
                "getLUTCount complete -> topLUTs=%d instMapSize=%d lutCountMapSize=%d "
                + "cacheHitInstMapWrites=%d",
                instMap.get(topInst), instMap.size(), lutCountMap.size(),
                LUTCountingUtils.dbgCacheHitInstMapWrites);
        int dbgNullLUTCountDecisions = 0;
        int dbgNullButHier = 0;
        int dbgNullButLeaf = 0;

        Map<EDIFHierCellInst, Integer> leafInsts = new HashMap<>();
        Queue<EDIFHierCellInst> instQueue = new LinkedList<>();
        instQueue.add(topInst);
        while (!instQueue.isEmpty()) {
            EDIFHierCellInst currentInst = instQueue.poll();

            //A null here means LUTCounting did not populate instMap for this instance
            //we MUST still make a coarsening decision, log and treat as eligible 
            //leaf vertex.
            Integer lutSize = instMap.get(currentInst);
            if (lutSize == null) {
                //debug only, track case where LUT size is missing
                //but we had to decide if leaf or to descend
                dbgNullLUTCountDecisions++;
                if (currentInst.getCellType().isLeafCellOrBlackBox()) {
                    dbgNullButLeaf++;
                    if (dbgNullButLeaf <= 10) {
                        logPartitionerDebug(System.err, 
                            "null LUT count (leaf) -> instPath=%s cellType=%s isLeaf=%s",
                            currentInst.toString(), currentInst.getCellType().getName(), 
                            currentInst.getCellType().isLeafCellOrBlackBox());
                    }
                //split up debug logs so we can see if missing LUT counts are on primitive leaf cells 
                //or on hierarchical (Which is not a leaf) cell.
                } else {
                    dbgNullButHier++;
                    if (dbgNullButHier <= 10) {
                        logPartitionerDebug(System.err, 
                            "null LUT count (hier) -> instPath=%s cellType=%s depth=%d " 
                            + "childCount=%d",
                            currentInst.toString(), currentInst.getCellType().getName(), 
                            currentInst.getDepth(), 
                            currentInst.getCellType().getCellInsts().size());
                    }
                }
            }
            if (lutSize == null || lutSize <= lutCount) {
                leafInsts.put(currentInst, leafInsts.size() + 1);
                continue;
            }
            assert (lutSize > lutCount);
            for (EDIFCellInst childInst : currentInst.getCellType().getCellInsts()) {
                instQueue.add(currentInst.getChild(childInst));
            }
        }
    
        logPartitionerDebug(System.out,
                "lutCount stats -> cacheHits=%d recursiveCalls=%d instMapWrites=%d "
                + "cacheHitInstMapWrites=%d uniqueCells=%d instMapSize=%d",
                LUTCountingUtils.dbgCacheHits, LUTCountingUtils.dbgRecursiveCalls,
                LUTCountingUtils.dbgInstMapWrites, LUTCountingUtils.dbgCacheHitInstMapWrites,
                lutCountMap.size(), instMap.size());
        logPartitionerDebug(System.out,
                "identifyLeafInstances -> leaves=%d nullLUTCountDecisions=%d nullButHier=%d "
                + "nullButLeaf=%d",
                leafInsts.size(), dbgNullLUTCountDecisions, dbgNullButHier, dbgNullButLeaf);
        logPartitionerDebug(System.err,
                "fix validation -> cacheHits=%d cacheHitInstMapWrites=%d shouldMatch=%s "
                + "subtreePopulations=%d subtreeInstances=%d",
                LUTCountingUtils.dbgCacheHits, LUTCountingUtils.dbgCacheHitInstMapWrites,
                (LUTCountingUtils.dbgCacheHits == LUTCountingUtils.dbgCacheHitInstMapWrites),
                LUTCountingUtils.dbgSubtreePopulations, LUTCountingUtils.dbgSubtreeInstances);
        return leafInsts;
    }
    
    /**
     * Create an array of cell instance string names such that the strings are
     * stored at their respective index.
     * @param leafInsts The map of cell instances to their assigned index
     * @return String array of instance names at assigned indices
     */
    public static String[] createInstLookupArray(Map<EDIFHierCellInst, Integer> leafInsts) {
        String[] lookup = new String[leafInsts.size() + 1];
        lookup[0] = null;
        for (Entry<EDIFHierCellInst, Integer> entry : leafInsts.entrySet()) {
            lookup[entry.getValue()] = entry.getKey().toString();
        }
        return lookup;
    }

    /**
     * Writes out instance names to integers to store the enumerated mapping.
     * @param mappingFile File that maps index to instance name
     * @param instLookup Current enumeration map for instances
     */
    public static void writeInstMappingFile(Path mappingFile, String[] instLookup) {
        try (BufferedWriter writer = new BufferedWriter(new FileWriter(mappingFile.toFile()))) {
            writer.write(instLookup.length + "\n");
            for (int index = 1; index < instLookup.length; index++) {
                writer.write(instLookup[index] + "\n");
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }    
    }

    /**
     * Creates the hMETIS formatted problem file for the partitioner. This follows
     * conventional hMETIS file format.
     * @param filePath Path to the output .hgr file
     * @param edgesMap Map of edges to include
     * @param leafInsts Map of nodes to include
     * @param writeEidmap Whether to write edge ID mapping file
     */
    public static void writeHMetisFile(Path filePath, 
            Map<EDIFHierNet, Set<EDIFHierCellInst>> edgesMap, 
            Map<EDIFHierCellInst, Integer> leafInsts, boolean writeEidmap) {
        // Derive .eidmap path alongside .hgr (replace .hgr suffix if present)
        Path eidmapPath;
        String base = filePath.toString();
        if (base.endsWith(".hgr")) {
            eidmapPath = Paths.get(base.substring(0, base.length() - 4) + ".eidmap");
        } else {
            eidmapPath = Paths.get(base + ".eidmap");
        }
        if (writeEidmap) {
            try (BufferedWriter hgrWriter = new BufferedWriter(new FileWriter(filePath.toFile()));
                 BufferedWriter eidmapWriter = new BufferedWriter(
                     new FileWriter(eidmapPath.toFile()))) {
                // <total edge count> <total node count>
                hgrWriter.write(edgesMap.size() + " " + leafInsts.size() + "\n");
                int unnamedCounter = 0; // assign unique ids for unnamed nets
                for (Entry<EDIFHierNet, Set<EDIFHierCellInst>> entry : edgesMap.entrySet()) {
                    // Write net name safely even if key is null
                    // Might be a better way to resolve this?
                    String netName = (entry.getKey() == null) ? 
                        "<unnamed_net_" + (++unnamedCounter) + ">" : entry.getKey().toString();
                    eidmapWriter.write(netName);
                    eidmapWriter.write("\n");
                    for (EDIFHierCellInst hierInst : entry.getValue()) {
                        Integer nodeIdx = leafInsts.get(hierInst);
                        if (nodeIdx == null) {
                            throw new RuntimeException(
                                    "ERROR: Inconsistent netlist, cannot export .hgr.");
                        }
                        hgrWriter.write(nodeIdx + " ");
                    }
                    hgrWriter.write("\n");
                }
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        } else {
            try (BufferedWriter hgrWriter = new BufferedWriter(new FileWriter(filePath.toFile()))) {
                // <total edge count> <total node count>
                hgrWriter.write(edgesMap.size() + " " + leafInsts.size() + "\n");
                for (Entry<EDIFHierNet, Set<EDIFHierCellInst>> entry : edgesMap.entrySet()) {
                    for (EDIFHierCellInst hierInst : entry.getValue()) {
                        Integer nodeIdx = leafInsts.get(hierInst);
                        if (nodeIdx == null) {
                            throw new RuntimeException(
                                    "ERROR: Inconsistent netlist, cannot export .hgr.");
                        }
                        hgrWriter.write(nodeIdx + " ");
                    }
                    hgrWriter.write("\n");
                }
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
    }

    /**
     * Reads the output of the partitioner and creates a map of the partitions using
     * cell instance names.
     * @param solutionFile The partitioner output file to read
     * @param instLookup Cell instance names by assigned index
     * @return Map of partition index to instance names
     */
    public static Map<Integer, Set<String>> readSolutionFile(Path solutionFile, 
            String[] instLookup) {
        Map<Integer, Set<String>> partitions = new HashMap<>();
    
        try (BufferedReader reader = new BufferedReader(new FileReader(solutionFile.toFile()))) {
            String line = null;
            int lineIndex = 1;
            while ((line = reader.readLine()) != null) {
                int partitionId = Integer.parseInt(line.trim());
                Set<String> set = partitions.computeIfAbsent(partitionId, s -> new HashSet<>());
                String name = instLookup[lineIndex];
                if (!set.add(name)) {
                    System.err.println("Found duplicate entry in partition file: " + line);
                }
                lineIndex++;
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    
        return partitions;
    }

    /**
     * Writes a human-readable connectivity report with net names using:
     *  - .hgr         edge -> list of vertex IDs
     *  - .eidmap      edge ID -> net name
     *  - instLookup   vertex ID -> instance name
     *  - nameToPart   instance name -> partition index
     *
     * output for every net and edge
     *   <net_id> : <net_name>
     *     cut=<true|false> partitions={p0:cnt0, p1:cnt1, ...}
     *     <vid>: <instance_name> p=<partition>
     * @param hgrFile Path to hypergraph file
     * @param eidmapFile Path to edge ID mapping file
     * @param instLookup Vertex ID to instance name mapping
     * @param nameToPart Instance name to partition mapping
     * @param netsOut Output path for connectivity report
     */
    public static void writeConnectivityReportWithNames(Path hgrFile, Path eidmapFile, String[] 
            instLookup, Map<String, Integer> nameToPart, Path netsOut) {
        try (BufferedReader hgrReader = new BufferedReader(new FileReader(hgrFile.toFile()));
             BufferedReader eidmapReader = new BufferedReader(new FileReader(eidmapFile.toFile()));
             BufferedWriter reportWriter = new BufferedWriter(new FileWriter(netsOut.toFile()))) {

            // Load eidmap: 1-based edge IDs
            ArrayList<String> netNames = new ArrayList<>();
            String line;
            while ((line = eidmapReader.readLine()) != null) {
                netNames.add(line);
            }

            // Read header line from .hgr
            String header = hgrReader.readLine(); // may be "E V" or "E V fmt"
            int edgeIdx = 0;

            while ((line = hgrReader.readLine()) != null) {
                edgeIdx++;
                String netName = edgeIdx <= netNames.size() ? netNames.get(edgeIdx - 1)
                        : ("<edge_" + edgeIdx + ">");

                String[] tokens = line.trim().isEmpty() ? 
                    new String[0] : line.trim().split("\\s+");
                Map<Integer, Integer> partCounts = new HashMap<>();
                ArrayList<String> members = new ArrayList<>();

                for (String token : tokens) {
                    if (token.isEmpty()) continue;
                    int vertexId;
                    try {
                        vertexId = Integer.parseInt(token);
                    } catch (NumberFormatException numberFormatException) {
                        continue;
                    }
                    String instName = (vertexId >= 0 && vertexId < instLookup.length) ? 
                        instLookup[vertexId] : null;
                    Integer partition = (instName == null) ? null : nameToPart.get(instName);
                    int partitionId = (partition == null) ? -1 : partition.intValue();
                    partCounts.put(partitionId, partCounts.getOrDefault(partitionId, 0) + 1);
                    String partitionLabel = (partitionId >= 0) ? 
                        PartitionLabel.indexToFpgaLabel(partitionId) : "UNASSIGNED";
                    members.add(String.format("  %d: %s part=%s", 
                        vertexId, instName, partitionLabel));
                }
                boolean cut = partCounts.size() > 1;

                reportWriter.write(String.format("Net %d: %s%n", edgeIdx, netName));
                // Print blocks with pin counts
                StringBuilder description = new StringBuilder();
                description.append("  cut=").append(cut ? "true" : "false").append(" blocks: ");
                if (partCounts.isEmpty()) {
                    description.append("none");
                } else {
                    java.util.List<java.util.Map.Entry<Integer,Integer>> entries = 
                        new java.util.ArrayList<>(partCounts.entrySet());
                    // Sort by block id
                    entries.sort((a,b) -> Integer.compare(a.getKey(), b.getKey())); 
                    for (int i = 0; i < entries.size(); i++) {
                        int blockId = entries.get(i).getKey();
                        int pinCount = entries.get(i).getValue();
                        String blockLabel = (blockId >= 0) ? 
                            PartitionLabel.indexToFpgaLabel(blockId) : "UNASSIGNED";
                        description.append(blockLabel).append("=").append(pinCount).append(" ")
                            .append(pinCount == 1 ? "pin" : "pins");
                        if (i + 1 < entries.size()) description.append(", ");
                    }
                }
                //'blocks' lists how many pins of net are inside each partition
                //pn is the partition id p2 = partition 2, p0 = partition 0
                //'p0=1 pin, p1=1 pin' means one pin in p0 and one pin in p1; 
                //'p6=2 pins' means two pins in p6
                reportWriter.write(description.toString());
                reportWriter.write("\n");
                for (String member : members) {
                    reportWriter.write(member);
                    reportWriter.write("\n");
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}

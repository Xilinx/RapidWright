/*
 *
 * Copyright (c) 2025, Advanced Micro Devices, Inc.
 * All rights reserved.
 *
 * Author: Perry Newlin
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
import java.io.FileReader;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import com.xilinx.rapidwright.edif.EDIFNetlist;

/**
 * Writes io_cuts.txt (direction-agnostic connection summary).
 * Optionally writes detailed human-readable hypergraph nets.txt report.
 */
public final class IoCutWriter {

    private IoCutWriter() {
        // no instances.
    }

    /**
     * Writes io_cuts.txt (in outDir) and optionally the detailed nets.txt.
     * @param outDir Output directory
     * @param inputEdif The input EDIF file path used to derive artifact names
     * @param netlist The EDIF netlist
     * @param instLookup Array mapping vertex IDs to instance names
     * @param partitions Map of partition IDs to sets of instance names
     * @param generateEdifNets Whether to generate the detailed nets.txt report
     */
    public static void write(Path outDir
            , Path inputEdif
            , EDIFNetlist netlist
            , String[] instLookup
            , Map<Integer, Set<String>> partitions
            , boolean generateEdifNets) {
        // build a deterministic name -> partition id lookup so we can translate each
        // hypergraph member to its block id.
        Map<String, Integer> nameToPart = new LinkedHashMap<>();
        for (Map.Entry<Integer, Set<String>> partitionEntry : partitions.entrySet()) {
            for (String instanceName : partitionEntry.getValue()) {
                nameToPart.put(instanceName, partitionEntry.getKey());
            }
        }

        // derive artifact paths w/ the input edif; these are produced earlier in the flow:
        // - .hgr lists each edge as a space-separated list of vertex ids
        // - .eidmap is a 1-based list of hierarchical net names aligned with the edges in .hgr
        // - .nets.txt is the verbose connectivity report we keep centralized here for convenience
        String base = inputEdif.getFileName().toString();
        Path hgr = outDir.resolve(base + ".hgr");
        Path eidmap = outDir.resolve(base + ".eidmap");
        Path netsOut = outDir.resolve(base + ".nets.txt");

        // write the direction-agnostic io cuts into io_cuts.txt.
        // this uses .hgr membership and ignores driver direction, emitting counts for both
        // permutations.
        writeIoCutsUndirected(outDir, hgr, instLookup, nameToPart);

        // generate the detailed per-net report
        if (generateEdifNets) {
            writeDetailedNetsReport(hgr, eidmap, instLookup, nameToPart, netsOut);
        }
    }

    /**
     * Writes io_cuts.txt using PartitionPairCounter.
     * @param outDir Output directory
     * @param hgrFile Path to the hypergraph (.hgr) file
     * @param instLookup Array mapping vertex IDs to instance names
     * @param nameToPart Map from instance names to partition IDs
     */
    private static void writeIoCutsUndirected(Path outDir
            , Path hgrFile
            , String[] instLookup
            , Map<String, Integer> nameToPart) {
        Map<String, Integer> pairCounts = PartitionPairCounter.countPartitionPairs(
                hgrFile, instLookup, nameToPart);
        Path ioCuts = outDir.resolve("io_cuts.txt");
        PartitionPairCounter.writePairCountsToFile(ioCuts, pairCounts);
    }

    /**
     * Writes detailed nets.txt using existing PartitionTools logic.
     * @param hgrFile Path to the hypergraph (.hgr) file
     * @param eidmapFile Path to the edge ID mapping (.eidmap) file
     * @param instLookup Array mapping vertex IDs to instance names
     * @param nameToPart Map from instance names to partition IDs
     * @param netsOut Path where nets.txt report will be written
     */
    private static void writeDetailedNetsReport(Path hgrFile
            , Path eidmapFile
            , String[] instLookup
            , Map<String, Integer> nameToPart
            , Path netsOut) {
        try {
            PartitionTools.writeConnectivityReportWithNames(hgrFile,
                    eidmapFile,
                    instLookup,
                    nameToPart,
                    netsOut);
            PartitionTools.logPartitionerDebug(null, "Partitioner artifact written: %s",
                    netsOut.toString());
        } catch (UncheckedIOException ex) {
            PartitionTools.logPartitionerDebug(null,
                    "WARNING: failed to write nets report: %s",
                    ex.getMessage());
        }
    }
}

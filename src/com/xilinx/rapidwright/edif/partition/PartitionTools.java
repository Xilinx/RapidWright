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
     * Calculates the number of LUTs in a given hierarchical cell instance.
     * 
     * @param inst    The hierarchical cell instance to calculate a LUT count.
     * @param map     A caching map to avoid recalculating LUTs for each cell type.
     * @param instMap A map that keeps track of all hierarchical instances and their
     *                LUT counts.
     * @return The number of LUTs in the hierarchical cell instance.
     */
    public static Integer getLUTCount(EDIFHierCellInst inst, Map<EDIFCell, Integer> map,
            Map<EDIFHierCellInst, Integer> instMap) {
        Integer totalLUTs = 0;
        for (EDIFCellInst i : inst.getCellType().getCellInsts()) {
            if (i.getCellType().isLeafCellOrBlackBox()) {
                totalLUTs += i.getCellType().getName().contains("LUT") ? 1 : 0;
            } else if (map.containsKey(i.getCellType())) {
                totalLUTs += map.get(i.getCellType());
            } else {
                totalLUTs += getLUTCount(inst.getChild(i), map, instMap);
            }
        }
        Integer prevCount = map.put(inst.getCellType(), totalLUTs);
        if (prevCount != null && !prevCount.equals(totalLUTs)) {
            throw new RuntimeException("ERROR: Inconsistent netlist");
        }
        instMap.put(inst, totalLUTs);
    
        return totalLUTs;
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
    public static Map<EDIFHierCellInst, Integer> identifyLeafInstances(EDIFNetlist netlist, int lutCount, 
            Map<EDIFHierCellInst, Integer> instMap) {
        EDIFHierCellInst topInst = netlist.getTopHierCellInst();
        Map<EDIFCell, Integer> lutCountMap = new HashMap<>();
        getLUTCount(topInst, lutCountMap, instMap);

        Map<EDIFHierCellInst, Integer> leafInsts = new HashMap<>();
        Queue<EDIFHierCellInst> q = new LinkedList<>();
        q.add(topInst);
        while (!q.isEmpty()) {
            EDIFHierCellInst curr = q.poll();
            Integer lutSize = instMap.get(curr);
            if (lutSize == null || lutSize <= lutCount) {
                leafInsts.put(curr, leafInsts.size()+1);
                continue;
            }
            assert (lutSize > lutCount);
            for (EDIFCellInst child : curr.getCellType().getCellInsts()) {
                q.add(curr.getChild(child));
            }
        }
    
        return leafInsts;
    }
    
    /**
     * Create an array of cell instance string names such that the strings are
     * stored at their respective index.
     * 
     * @param leafInsts The map of cell instances to their assigned index.
     * @return The string array of cell instance names stored at their assigned
     *         index.
     */
    public static String[] createInstLookupArray(Map<EDIFHierCellInst, Integer> leafInsts) {
        String[] lookup = new String[leafInsts.size() + 1];
        lookup[0] = null;
        for (Entry<EDIFHierCellInst, Integer> e : leafInsts.entrySet()) {
            lookup[e.getValue()] = e.getKey().toString();
        }
        return lookup;
    }

    /**
     * Writes out instance names to integers to store the enumerated mapping.
     * 
     * @param mappingFile A file that maps index to instance name (one per line) to
     *                    the partitioned solution can be decoded.
     * @param leafInsts   Current enumeration map for the instances used.
     */
    public static void writeInstMappingFile(Path mappingFile, String[] instLookup) {
        try (BufferedWriter bw = new BufferedWriter(new FileWriter(mappingFile.toFile()))) {
            bw.write(instLookup.length + "\n");
            for (int i = 1; i < instLookup.length; i++) {
                bw.write(instLookup[i] + "\n");
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }    
    }

    /**
     * Creates the hMETIS formatted problem file for the partitioner. This follows
     * conventional hMETIS file format.
     * 
     * @param filePath  Path to the output file.
     * @param edgesMap  Map of edges to be included in the output file.
     * @param leafInsts Map of nodes or leaves to be included in the output file.
     */
    public static void writeHMetisFile(Path filePath, Map<EDIFHierNet, Set<EDIFHierCellInst>> edgesMap, 
            Map<EDIFHierCellInst, Integer> leafInsts) { 
        try (BufferedWriter bw = new BufferedWriter(new FileWriter(filePath.toFile()))) {
            // <Total Edge Count> <Total Node Count>
            bw.write(edgesMap.size() + " " + leafInsts.size() + "\n");
            for (Entry<EDIFHierNet, Set<EDIFHierCellInst>> e : edgesMap.entrySet()) {
                for (EDIFHierCellInst i : e.getValue()) {
                    Integer nodeIdx = leafInsts.get(i);
                    if (nodeIdx == null) {
                        throw new RuntimeException(
                                "ERROR: Inconsistent netlist, cannot export .hgr.");
                    }
                    bw.write(nodeIdx + " ");
                }
                bw.write("\n");
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * Reads the output of the partitioner and creates a map of the partitions using
     * cell instance names.
     * 
     * @param fileName     The file generated from the partitioner to read in.
     * @param nameMappings The cell instances names stored by their assigned index.
     * @return A map keyed by partition index to the respective set of cell instance
     *         names to be included in that partition.
     */
    public static Map<Integer, Set<String>> readSolutionFile(Path solutionFile, String[] instLookup) {
        Map<Integer, Set<String>> partitions = new HashMap<>();
    
        try (BufferedReader br = new BufferedReader(new FileReader(solutionFile.toFile()))) {
            String line = null;
            int i = 1;
            while ((line = br.readLine()) != null) {
                int part = Integer.parseInt(line.trim());
                Set<String> partition = partitions.computeIfAbsent(part, s -> new HashSet<>());
                String name = instLookup[i];
                if (!partition.add(name)) {
                    System.err.println("Found duplicate entry in partition file: " + line);
                }
                i++;
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    
        return partitions;
    }
}

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

import java.io.BufferedWriter;
import java.io.FileWriter;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.xilinx.rapidwright.edif.EDIFCellInst;
import com.xilinx.rapidwright.edif.EDIFDirection;
import com.xilinx.rapidwright.edif.EDIFHierCellInst;
import com.xilinx.rapidwright.edif.EDIFHierNet;
import com.xilinx.rapidwright.edif.EDIFHierPortInst;
import com.xilinx.rapidwright.edif.EDIFNet;
import com.xilinx.rapidwright.edif.EDIFNetlist;
import com.xilinx.rapidwright.edif.EDIFPortInst;

/**
 * Analyzes FPGA-level IO cuts with direction awareness.
 * 
 * Unlike the topology-unaware PartitionPairCounter which counts nets touching
 * multiple partitions in the coarsened hypergraph, this analyzer:
 * 
 * 1. Identifies signal DIRECTION (driver vs load) using EDIF port directions
 * 2. Counts signals at the FPGA partition boundary level
 * 3. Reports directional flow: FPGA_A -> FPGA_B vs FPGA_B -> FPGA_A
 * 4. Filters out constant nets (VCC, GND, const0, const1)
 * 5. Separately categorizes global signals (clock, reset)
 */
public final class FpgaIoCutAnalyzer {

    private FpgaIoCutAnalyzer() {
        // No instances
    }

    /**
     * Result container for FPGA IO cut analysis.
     */
    public static class FpgaIoCutResult {
        /** Directional cut counts: key = "FPGA_A->FPGA_B", value = count */
        public final Map<String, Integer> directionalCuts = new LinkedHashMap<>();
        
        /** Nets identified as clock signals crossing partitions */
        public final List<String> clockNets = new ArrayList<>();
        
        /** Nets identified as reset signals crossing partitions */
        public final List<String> resetNets = new ArrayList<>();
        
        /** Constant nets that were filtered out */
        public final List<String> filteredConstantNets = new ArrayList<>();
        
        /** Total data signals (excluding clock/reset/constants) */
        public int totalDataSignals = 0;
        
        /** Debug: nets that couldn't be fully analyzed */
        public final List<String> unanalyzedNets = new ArrayList<>();
    }

    /**
     * Analyzes the netlist for FPGA-level IO cuts with direction awareness.
     * 
     * @param netlist The EDIF netlist to analyze
     * @param leafInsts Map of coarsened leaf instances to their vertex indices
     * @param nameToPart Map from instance name to partition index
     * @return Analysis result with directional cuts and categorized signals
     */
    public static FpgaIoCutResult analyze(
            EDIFNetlist netlist,
            Map<EDIFHierCellInst, Integer> leafInsts,
            Map<String, Integer> nameToPart) {
        
        FpgaIoCutResult result = new FpgaIoCutResult();
        
        // Build reverse lookup: instance name -> partition label
        Map<String, String> instToFpgaLabel = new HashMap<>();
        for (Map.Entry<String, Integer> entry : nameToPart.entrySet()) {
            String instName = entry.getKey();
            int partIdx = entry.getValue();
            String fpgaLabel = PartitionLabel.indexToFpgaLabel(partIdx);
            instToFpgaLabel.put(instName, fpgaLabel);
        }
        
        System.out.println("DEBUG: FpgaIoCutAnalyzer start");
        System.out.println("DEBUG: instToFpgaLabel size: " + instToFpgaLabel.size());
        if (!instToFpgaLabel.isEmpty()) {
            System.out.println("DEBUG: Sample mapping: " + instToFpgaLabel.entrySet().iterator().next());
        }
        
        // Track processed nets to avoid duplicates
        Set<String> processedNets = new HashSet<>();
        
        // Iterate through all leaf instances and their connected nets
        int netsProcessed = 0;
        for (EDIFHierCellInst leafInst : leafInsts.keySet()) {
            for (EDIFHierPortInst portInst : leafInst.getHierPortInsts()) {
                EDIFHierNet hierNet = portInst.getHierarchicalNet();
                if (hierNet == null) continue;
                
                // Get canonical net name to avoid processing same net multiple times
                String netName = hierNet.getHierarchicalNetName();
                if (netName == null) {
                    netName = hierNet.toString();
                }
                if (processedNets.contains(netName)) continue;
                processedNets.add(netName);
                netsProcessed++;
                
                EDIFNet net = hierNet.getNet();
                if (net != null && (net.isVCC() || net.isGND())) {
                    result.filteredConstantNets.add(netName);
                    continue;
                }
                
                // Analyze this net for cross-partition signals
                analyzeNetForCrossings(hierNet, leafInsts, instToFpgaLabel, result, netName);
            }
        }
        System.out.println("DEBUG: Nets processed: " + netsProcessed);
        
        return result;
    }

    // Debug counter for first few nets
    private static int debugNetCount = 0;
    private static final int DEBUG_NET_LIMIT = 5;
    
    /**
     * Analyzes a single net for FPGA boundary crossings.
     */
    private static void analyzeNetForCrossings(
            EDIFHierNet hierNet,
            Map<EDIFHierCellInst, Integer> leafInsts,
            Map<String, String> instToFpgaLabel,
            FpgaIoCutResult result,
            String netName) {
        
        boolean debugThis = (debugNetCount < DEBUG_NET_LIMIT);
        
        // Find all port instances on this net
        Collection<EDIFHierPortInst> allPortInsts = hierNet.getPortInsts();
        if (allPortInsts == null || allPortInsts.isEmpty()) {
            if (debugThis) {
                System.out.println("DEBUG NET [" + netName + "]: No port instances found");
                debugNetCount++;
            }
            return;
        }
        
        if (debugThis) {
            System.out.println("DEBUG NET [" + netName + "]: Found " + allPortInsts.size() + " port instances");
        }
        
        // Separate drivers from loads
        List<PartitionedPort> drivers = new ArrayList<>();
        List<PartitionedPort> loads = new ArrayList<>();
        int nullPiCount = 0;
        int nullParentCount = 0;
        int nullFpgaLabelCount = 0;
        
        for (EDIFHierPortInst portInst : allPortInsts) {
            // Get direction from the underlying port instance
            EDIFPortInst pi = portInst.getPortInst();
            if (pi == null) {
                nullPiCount++;
                continue;
            }
            EDIFDirection direction = pi.getDirection();
            
            // Get the actual cell instance that owns this port (not the hierarchical context)
            EDIFCellInst cellInst = pi.getCellInst();
            if (cellInst == null) {
                // This is a top-level port (e.g., clk, reset input to the design)
                nullParentCount++;
                continue;
            }
            
            // Get the cell instance name - this is what we need to match against the partition map
            String cellInstName = cellInst.getName();
            
            // Also try to construct full hierarchical path
            EDIFHierCellInst hierContext = portInst.getHierarchicalInst();
            String fullHierPath = "";
            if (hierContext != null && hierContext.toString() != null && !hierContext.toString().isEmpty()) {
                fullHierPath = hierContext.toString() + "/" + cellInstName;
            } else {
                fullHierPath = cellInstName;
            }
            
            // Find which partition this instance belongs to using cell instance name
            String fpgaLabel = resolveInstanceByName(cellInstName, fullHierPath, instToFpgaLabel);
            if (fpgaLabel == null) {
                nullFpgaLabelCount++;
                if (debugThis && nullFpgaLabelCount <= 3) {
                    System.out.println("DEBUG NET [" + netName + "]: Could not resolve - cellInstName=[" + 
                        cellInstName + "] fullHierPath=[" + fullHierPath + "]");
                }
                continue;
            }
            
            PartitionedPort pp = new PartitionedPort(portInst, fpgaLabel);
            
            if (direction == EDIFDirection.OUTPUT) {
                drivers.add(pp);
            } else if (direction == EDIFDirection.INPUT) {
                loads.add(pp);
            } else if (direction == EDIFDirection.INOUT) {
                // INOUT pins can be both driver and load
                drivers.add(pp);
                loads.add(pp);
            }
        }
        
        if (debugThis) {
            System.out.println("DEBUG NET [" + netName + "]: drivers=" + drivers.size() + 
                " loads=" + loads.size() + " nullPi=" + nullPiCount + 
                " nullParent=" + nullParentCount + " nullFpgaLabel=" + nullFpgaLabelCount);
            debugNetCount++;
        }
        
        // If no drivers or no loads, no crossing possible
        if (drivers.isEmpty() || loads.isEmpty()) {
            return;
        }
        
        // Check for clock/reset patterns
        boolean isClockNet = isClockSignal(netName);
        boolean isResetNet = isResetSignal(netName);
        
        // For each driver, check if any loads are in different partitions
        Set<String> crossingsForThisNet = new HashSet<>();
        
        for (PartitionedPort driver : drivers) {
            for (PartitionedPort load : loads) {
                if (!driver.fpgaLabel.equals(load.fpgaLabel)) {
                    // This is a cross-partition signal!
                    String crossingKey = driver.fpgaLabel + "->" + load.fpgaLabel;
                    crossingsForThisNet.add(crossingKey);
                }
            }
        }
        
        // Record the crossings
        if (!crossingsForThisNet.isEmpty()) {
            if (isClockNet) {
                result.clockNets.add(netName);
            } else if (isResetNet) {
                result.resetNets.add(netName);
            } else {
                result.totalDataSignals++;
                for (String crossing : crossingsForThisNet) {
                    result.directionalCuts.put(crossing, 
                        result.directionalCuts.getOrDefault(crossing, 0) + 1);
                }
            }
        }
    }

    /**
     * Resolves an instance to its FPGA partition label using name matching.
     * Tries simple name first, then full hierarchical path, then partial matches.
     * 
     * @param simpleName The simple cell instance name (e.g., "result_reg[16]")
     * @param fullHierPath The full hierarchical path (e.g., "hw_top/result_reg[16]")
     * @param instToFpgaLabel Map from instance name to FPGA label
     * @return The FPGA label, or null if not found
     */
    private static String resolveInstanceByName(
            String simpleName,
            String fullHierPath,
            Map<String, String> instToFpgaLabel) {
        
        // Try simple name first (this is what instLookup uses)
        if (simpleName != null && instToFpgaLabel.containsKey(simpleName)) {
            return instToFpgaLabel.get(simpleName);
        }
        
        // Try full hierarchical path
        if (fullHierPath != null && !fullHierPath.isEmpty() && instToFpgaLabel.containsKey(fullHierPath)) {
            return instToFpgaLabel.get(fullHierPath);
        }
        
        // Try partial path matching - check if this instance is a child of a mapped instance
        if (fullHierPath != null && !fullHierPath.isEmpty()) {
            for (Map.Entry<String, String> entry : instToFpgaLabel.entrySet()) {
                String mappedPath = entry.getKey();
                // Check if fullHierPath starts with mappedPath (instance is child of mapped)
                if (fullHierPath.startsWith(mappedPath + "/") || fullHierPath.equals(mappedPath)) {
                    return entry.getValue();
                }
                // Check if mappedPath starts with fullHierPath (mapped is child of this instance)
                if (mappedPath.startsWith(fullHierPath + "/")) {
                    return entry.getValue();
                }
            }
        }
        
        // Try matching with just the last component of the path
        if (fullHierPath != null && fullHierPath.contains("/")) {
            String[] parts = fullHierPath.split("/");
            for (int i = parts.length - 1; i >= 0; i--) {
                String partialPath = String.join("/", java.util.Arrays.copyOfRange(parts, i, parts.length));
                if (instToFpgaLabel.containsKey(partialPath)) {
                    return instToFpgaLabel.get(partialPath);
                }
                // Also check if any mapped path contains this partial path
                for (Map.Entry<String, String> entry : instToFpgaLabel.entrySet()) {
                    String mappedPath = entry.getKey();
                    if (mappedPath.endsWith("/" + partialPath) || mappedPath.equals(partialPath)) {
                        return entry.getValue();
                    }
                }
            }
        }
        
        return null;
    }

    /**
     * Checks if a net name appears to be a clock signal.
     * 
     * TODO: Better source of truth? EDIF net domain?
     */
    private static boolean isClockSignal(String netName) {
        if (netName == null) return false;
        String lower = netName.toLowerCase();
        // Common clock naming patterns
        return lower.equals("clk") || 
               lower.equals("clock") ||
               lower.endsWith("/clk") ||
               lower.endsWith("/clock") ||
               lower.contains("_clk") ||
               lower.contains("clk_");
    }

    /**
     * Checks if a net name appears to be a reset signal.
     * 
     * TODO: Better source of truth? EDIF net domain?
     */
    private static boolean isResetSignal(String netName) {
        if (netName == null) return false;
        String lower = netName.toLowerCase();
        // Common reset naming patterns
        return lower.equals("reset") || 
               lower.equals("rst") ||
               lower.equals("resetn") ||
               lower.equals("rst_n") ||
               lower.endsWith("/reset") ||
               lower.endsWith("/rst") ||
               lower.contains("_reset") ||
               lower.contains("_rst");
    }

    /**
     * Writes the FPGA IO cut analysis to a file.
     * 
     * @param outputDir Output directory
     * @param result Analysis result to write
     */
    public static void writeReport(Path outputDir, FpgaIoCutResult result) {
        Path outputFile = outputDir.resolve("fpga_io_cuts.txt");
        
        try (BufferedWriter writer = new BufferedWriter(new FileWriter(outputFile.toFile()))) {
            writer.write("=== FPGA IO Cut Report (Direction-Aware) ===\n");
            writer.write("\n");
            
            // Directional data signal counts
            writer.write("--- Data Signals by Direction ---\n");
            if (result.directionalCuts.isEmpty()) {
                writer.write("  No data signal crossings detected.\n");
            } else {
                // Sort entries for consistent output
                List<Map.Entry<String, Integer>> sorted = new ArrayList<>(result.directionalCuts.entrySet());
                sorted.sort((a, b) -> a.getKey().compareTo(b.getKey()));
                
                for (Map.Entry<String, Integer> entry : sorted) {
                    writer.write(String.format("  %s: %d signal(s)\n", 
                        entry.getKey(), entry.getValue()));
                }
            }
            writer.write("\n");
            
            // Summary
            writer.write("--- Summary ---\n");
            writer.write(String.format("  Total data signals crossing FPGA boundaries: %d\n", 
                result.totalDataSignals));
            writer.write(String.format("  Clock nets crossing: %d\n", result.clockNets.size()));
            writer.write(String.format("  Reset nets crossing: %d\n", result.resetNets.size()));
            writer.write(String.format("  Constant nets filtered: %d\n", 
                result.filteredConstantNets.size()));
            writer.write("\n");
            
            // Clock net details
            if (!result.clockNets.isEmpty()) {
                writer.write("--- Clock Nets ---\n");
                for (String clkNet : result.clockNets) {
                    writer.write(String.format("  %s\n", clkNet));
                }
                writer.write("\n");
            }
            
            // Reset net details
            if (!result.resetNets.isEmpty()) {
                writer.write("--- Reset Nets ---\n");
                for (String rstNet : result.resetNets) {
                    writer.write(String.format("  %s\n", rstNet));
                }
                writer.write("\n");
            }
            
            // Filtered constants (for debugging)
            if (!result.filteredConstantNets.isEmpty()) {
                writer.write("--- Filtered Constant Nets ---\n");
                int maxToShow = Math.min(10, result.filteredConstantNets.size());
                for (int i = 0; i < maxToShow; i++) {
                    writer.write(String.format("  %s\n", result.filteredConstantNets.get(i)));
                }
                if (result.filteredConstantNets.size() > maxToShow) {
                    writer.write(String.format("  ... and %d more\n", 
                        result.filteredConstantNets.size() - maxToShow));
                }
                writer.write("\n");
            }
            
            PartitionTools.logPartitionerDebug(null, 
                "Partitioner artifact written: %s", outputFile.toString());
            
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * Helper class to associate a port instance with its FPGA partition.
     */
    private static class PartitionedPort {
        final EDIFHierPortInst portInst;
        final String fpgaLabel;
        
        PartitionedPort(EDIFHierPortInst portInst, String fpgaLabel) {
            this.portInst = portInst;
            this.fpgaLabel = fpgaLabel;
        }
    }
}

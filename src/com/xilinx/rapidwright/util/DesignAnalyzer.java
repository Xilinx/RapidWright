/*
 * Copyright (c) 2026, Advanced Micro Devices, Inc.
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

package com.xilinx.rapidwright.util;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.xilinx.rapidwright.design.Design;
import com.xilinx.rapidwright.design.tools.LUTTools;
import com.xilinx.rapidwright.edif.EDIFCell;
import com.xilinx.rapidwright.edif.EDIFCellInst;
import com.xilinx.rapidwright.edif.EDIFHierCellInst;
import com.xilinx.rapidwright.edif.EDIFNetlist;
import com.xilinx.rapidwright.edif.EDIFTools;

import joptsimple.OptionParser;
import joptsimple.OptionSet;

/**
 * CLI tool for analyzing Vivado DCP or EDIF designs.
 * Supports LUT counting at different hierarchy levels for a given
 * EDIF or DCP input file.
 */
public final class DesignAnalyzer {

    private static final String LUTCOUNT_CMD = "LUTCount";
    private static final String INPUT_OPT = "i";
    private static final String LEVEL_OPT = "l";
    private static final String HELP_OPT = "h";
    private static final int DEFAULT_LEVEL = 0;
    private static final String DESC_INPUT = "Input EDIF or DCP file";
    private static final String DESC_LEVEL = "Hierarchy depth (0=total, 1=children, etc.)";

    private DesignAnalyzer() {
    }

    /**
     * Prints help showing available commands.
     */
    private static void printMainHelp() {
        MessageGenerator.printHeader("DesignAnalyzer");
        System.out.println("Analyze Vivado DCP or EDIF designs.\n");
        System.out.println("Usage: rapidwright DesignAnalyzer <command> [options]\n");
        System.out.println("Available commands:");
        System.out.println("  " + LUTCOUNT_CMD + "    Count logic LUT usage at different hierarchy levels");
        System.out.println();
        System.out.println("Use 'rapidwright DesignAnalyzer <command> --help' for command-specific options.");
    }

    /**
     * Entry point. Dispatches to the appropriate subcommand handler.
     * 
     * @param args command-line arguments
     */
    public static void main(String[] args) {
        if (args.length == 0 || args[0].equals("-h") || args[0].equals("--help") || args[0].equals("-?")) {
            printMainHelp();
            return;
        }

        String command = args[0];
        String[] commandArgs = Arrays.copyOfRange(args, 1, args.length);

        if (command.equalsIgnoreCase(LUTCOUNT_CMD)) {
            runLUTCount(commandArgs);
        } else {
            System.err.println("ERROR: Unknown command '" + command + "'");
            printMainHelp();
            System.exit(1);
        }
    }

    /**
     * Creates option parser for LUTCount subcommand.
     * 
     * @return configured OptionParser
     */
    private static OptionParser createLUTCountOptionParser() {
        OptionParser optParser = new OptionParser();

        optParser.acceptsAll(Arrays.asList(INPUT_OPT, "input"))
                .withRequiredArg()
                .required()
                .describedAs(DESC_INPUT);

        optParser.acceptsAll(Arrays.asList(LEVEL_OPT, "level"))
                .withOptionalArg()
                .ofType(Integer.class)
                .defaultsTo(DEFAULT_LEVEL)
                .describedAs(DESC_LEVEL);

        optParser.acceptsAll(Arrays.asList(HELP_OPT, "help", "?"), "Print Help")
                .forHelp();

        return optParser;
    }

    /**
     * Prints help for LUTCount subcommand.
     * 
     * @param optParser option parser with definitions
     */
    private static void printLUTCountHelp(OptionParser optParser) {
        MessageGenerator.printHeader("LUTCount");
        System.out.println("Count logic LUT usage from EDIF or Vivado DCP.\n");
        try {
            optParser.printHelpOn(System.out);
        } catch (IOException ioException) {
            ioException.printStackTrace();
        }
    }

    /**
     * Runs LUTCount: loads design, counts LUTs, prints report.
     * 
     * @param args subcommand arguments
     */
    private static void runLUTCount(String[] args) {
        OptionParser optParser = createLUTCountOptionParser();
        OptionSet opts;

        try {
            opts = optParser.parse(args);
        } catch (Exception parseException) {
            System.err.println("ERROR: " + parseException.getMessage());
            printLUTCountHelp(optParser);
            return;
        }

        if (opts.has(HELP_OPT)) {
            printLUTCountHelp(optParser);
            return;
        }

        Path inputPath = Paths.get((String) opts.valueOf(INPUT_OPT));
        int maxDepth = (int) opts.valueOf(LEVEL_OPT);

        EDIFNetlist netlist;
        boolean isDcp = inputPath.toString().toLowerCase().endsWith(".dcp");
        if (isDcp) {
            Design design = Design.readCheckpoint(inputPath.toString());
            netlist = design.getNetlist();
            int encryptedCount = netlist.getEncryptedCells().size();
            if (encryptedCount > 0) {
                System.err.println("ERROR: Encrypted DCP detected (encryptedCells="
                        + encryptedCount + "). Encrypted DCPs are unsupported.");
                System.exit(1);
            }
        } else {
            netlist = EDIFTools.readEdifFile(inputPath);
        }

        EDIFHierCellInst topInst = netlist.getTopHierCellInst();
        Map<EDIFCell, Integer> cellTypeCache = new HashMap<>();
        Map<EDIFHierCellInst, Integer> instanceLutCounts = new HashMap<>();
        int totalLuts = computeLUTCount(topInst, cellTypeCache, instanceLutCounts);

        System.out.println("-----------------------------------------------------------");
        System.out.println("LUT Count Report");
        System.out.println("Input     : " + inputPath);
        System.out.println("-----------------------------------------------------------");
        System.out.printf("Total LUTs: %,d%n", totalLuts);

        if (maxDepth > 0) {
            System.out.println();
            printHierarchy(topInst, 1, maxDepth, instanceLutCounts);
        }
    }

    /**
     * Recursively counts LUTs in the hierarchy with caching.
     * Useful for netlist coarsening in partitioning flows on
     * monolithic netlists.
     * 
     * @param hierInst instance to analyze
     * @param cellTypeCache cache of cell type to LUT count
     * @param instanceLutCounts map to store per-instance counts (can be null)
     * @return total LUT count for this instance
     */
    public static Integer computeLUTCount(EDIFHierCellInst hierInst,
            Map<EDIFCell, Integer> cellTypeCache,
            Map<EDIFHierCellInst, Integer> instanceLutCounts) {
        Integer totalLuts = 0;
        for (EDIFCellInst childInst : hierInst.getCellType().getCellInsts()) {
            // Leaf cell: check if it's a LUT primitive
            if (childInst.getCellType().isLeafCellOrBlackBox()) {
                int lutValue = LUTTools.isCellALUT(childInst) ? 1 : 0;
                totalLuts += lutValue;
            // Cache hit: reuse previously computed count for this cell type
            } else if (cellTypeCache.containsKey(childInst.getCellType())) {
                Integer cachedValue = cellTypeCache.get(childInst.getCellType());
                totalLuts += cachedValue;
                // Fill per-instance counts from cache if tracking instances
                if (instanceLutCounts != null) {
                    EDIFHierCellInst childHierInst = hierInst.getChild(childInst);
                    populateFromCache(childHierInst, cellTypeCache, instanceLutCounts);
                }
            // Cache miss: recurse into hierarchical cell
            } else {
                totalLuts += computeLUTCount(hierInst.getChild(childInst),
                        cellTypeCache, instanceLutCounts);
            }
        }

        // Store result in cache and check for netlist consistency
        Integer previousCount = cellTypeCache.put(hierInst.getCellType(), totalLuts);
        if (previousCount != null && !previousCount.equals(totalLuts)) {
            throw new RuntimeException("ERROR: Inconsistent netlist detected - "
                    + "cell type " + hierInst.getCellType().getName()
                    + " has conflicting LUT counts");
        }

        // Track this instance's count if per-instance tracking enabled
        if (instanceLutCounts != null) {
            instanceLutCounts.put(hierInst, totalLuts);
        }
        return totalLuts;
    }

    /**
     * Fills per-instance LUT counts from cached cell type values.
     * 
     * @param hierInst instance whose subtree to populate
     * @param cellTypeCache cache with precomputed counts
     * @param instanceLutCounts map to populate
     */
    public static void populateFromCache(EDIFHierCellInst hierInst,
            Map<EDIFCell, Integer> cellTypeCache,
            Map<EDIFHierCellInst, Integer> instanceLutCounts) {
        if (instanceLutCounts == null) {
            return;
        }

        Integer lutCount = cellTypeCache.get(hierInst.getCellType());
        if (lutCount != null) {
            instanceLutCounts.put(hierInst, lutCount);
        }

        // Recurse into children if not a leaf
        if (!hierInst.getCellType().isLeafCellOrBlackBox()) {
            for (EDIFCellInst childInst : hierInst.getCellType().getCellInsts()) {
                populateFromCache(hierInst.getChild(childInst), cellTypeCache, instanceLutCounts);
            }
        }
    }

    /**
     * Prints LUT counts for child instances up to maxDepth.
     * 
     * @param parent parent instance
     * @param currentDepth current traversal depth
     * @param maxDepth max depth to print
     * @param instanceLutCounts precomputed counts
     */
    private static void printHierarchy(EDIFHierCellInst parent, int currentDepth, int maxDepth,
            Map<EDIFHierCellInst, Integer> instanceLutCounts) {
        if (parent.getCellType().getCellInsts() == null) {
            return;
        }

        List<String[]> lutCountEntries = new ArrayList<>();
        for (EDIFCellInst childInst : parent.getCellType().getCellInsts()) {
            EDIFHierCellInst childHierInst = parent.getChild(childInst);
            Integer lutCount = instanceLutCounts.get(childHierInst);
            if (lutCount == null || lutCount == 0) {
                continue;
            }
            String instancePath = childHierInst.toString();
            String formattedCount = String.format("%,d", lutCount);
            lutCountEntries.add(new String[] { instancePath, formattedCount });
        }

        lutCountEntries.sort((entryA, entryB) -> entryA[0].compareTo(entryB[0]));

        for (String[] entry : lutCountEntries) {
            String instancePath = entry[0];
            String formattedCount = entry[1];
            System.out.printf("%-60s %12s LUTs%n", instancePath, formattedCount);
        }

        if (currentDepth < maxDepth) {
            for (EDIFCellInst childInst : parent.getCellType().getCellInsts()) {
                EDIFHierCellInst childHierInst = parent.getChild(childInst);
                printHierarchy(childHierInst, currentDepth + 1, maxDepth, instanceLutCounts);
            }
        }
    }
}

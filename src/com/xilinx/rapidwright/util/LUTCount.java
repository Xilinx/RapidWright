/*
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

package com.xilinx.rapidwright.util;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;

import com.xilinx.rapidwright.design.Design;
import com.xilinx.rapidwright.edif.EDIFCell;
import com.xilinx.rapidwright.edif.EDIFHierCellInst;
import com.xilinx.rapidwright.edif.EDIFNetlist;
import com.xilinx.rapidwright.edif.EDIFTools;
import com.xilinx.rapidwright.edif.partition.PartitionTools;

/**
 * CLI utility to count logic LUT usage from EDIF (.edf/.edif) or Vivado DCP (.dcp).
 */
public final class LUTCount {

    private LUTCount() {
        // no instances
    }

    /**
     * Prints usage information.
     */
    private static void usage() {
        System.out.println("Usage:");
        System.out.println("  LUTCount <input.edf|input.dcp>");
        System.out.println();
        System.out.println("Examples:");
        System.out.println("  rapidwright LUTCount design.edf");
        System.out.println("  rapidwright LUTCount design.dcp");
    }

    /**
     * Main entry point.
     *
     * @param args Command line arguments.
     */
    public static void main(String[] args) {
        if (args.length != 1) {
            usage();
            return;
        }

        Path inputPath = Paths.get(args[0]);

        // read netlist (EDIF or DCP)
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

        // compute logic-only LUT count via PartitionTools aggregation
        EDIFHierCellInst topInst = netlist.getTopHierCellInst();
        Map<EDIFCell, Integer> lutCache = new HashMap<>();
        int logicLuts = PartitionTools.getLUTCount(topInst, lutCache, null);

        System.out.println("-----------------------------------------------------------");
        System.out.println("LUT Count Report");
        System.out.println("Input     : " + inputPath);
        System.out.println("-----------------------------------------------------------");
        System.out.printf("Logic LUTs: %d%n", logicLuts);
    }

}

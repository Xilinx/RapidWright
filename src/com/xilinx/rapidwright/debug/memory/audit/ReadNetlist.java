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

package com.xilinx.rapidwright.debug.memory.audit;

import java.io.File;

import com.xilinx.rapidwright.design.Design;
import com.xilinx.rapidwright.edif.EDIFTools;

/**
 * Memory audit utility for reading netlists.
 */
public class ReadNetlist {

    /**
     * Prints memory usage.
     *
     * @param label The label for this audit point.
     */
    private static void memAudit(String label) {
        Runtime rt = Runtime.getRuntime();
        long total = rt.totalMemory();
        long free = rt.freeMemory();
        long used = total - free;
        System.out.printf("PARTITIONER DEBUG: %s -> used=%d MB total=%d MB free=%d MB%n",
                label, used / (1024 * 1024), total / (1024 * 1024), free / (1024 * 1024));
    }

    /**
     * Main entry point.
     *
     * @param args Command line arguments.
     */
    public static void main(String[] args) {
        if (args.length < 1) {
            System.err.println("Usage: ReadNetlist <netlist.[edf|dcp]>");
            return;
        }

        File input = new File(args[0]);
        if (!input.exists()) {
            System.err.printf("ERROR: File not found: %s%n", args[0]);
            return;
        }

        memAudit("readnetlist mem usg before read");
        System.out.println("Reading netlist....");
        Design design = null;
        if (args[0].endsWith(".dcp")) {
            design = Design.readCheckpoint(args[0]);
        } else {
            design = new Design(EDIFTools.readEdifFile(args[0]));
        }
        memAudit("readnetlist mem usg after read");

        // kept reference to prevent GC until after waiting
        if (design == null) {
            System.err.println("ERROR: Failed to load design.");
        }
    }
}

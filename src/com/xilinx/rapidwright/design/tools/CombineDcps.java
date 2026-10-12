/*
 *
 * Copyright (c) 2026, Advanced Micro Devices, Inc.
 * All rights reserved.
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

package com.xilinx.rapidwright.design.tools;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Future;

import com.xilinx.rapidwright.design.Design;
import com.xilinx.rapidwright.design.DesignTools;
import com.xilinx.rapidwright.tests.CodePerfTracker;
import com.xilinx.rapidwright.util.ParallelismTools;
import com.xilinx.rapidwright.util.Params;

/**
 * Combines a placed and routed design that has black boxes, such as the static part of a Dynamic
 * Function eXchange (DFX) design, with placed and routed implementations of those black boxes, such
 * as reconfigurable modules, into one design checkpoint. This is what Vivado does with
 * {@code read_checkpoint -cell} and {@code link_design}.
 * <p>
 * The checkpoints are read in parallel. Each black box is filled with
 * {@link DesignTools#populateBlackBox(Design, Map, boolean)}, keeping the routing on its boundary,
 * which also adds each module's constraints to the design, scoped to its black box. Physical nets
 * whose names Vivado would resolve to another logical net are then renamed
 * ({@link DesignTools#renameAmbiguousPhysNets(Design)}).
 * <p>
 * The result is written in the 2024.1 DCP format, which keeps everything Vivado needs to read such
 * a design back, including its DFX state.
 * <p>
 * A checkpoint with encrypted cells needs a readable EDIF, as for {@link Design#readCheckpoint(Path)}:
 * either one with the same name next to it (foo.dcp -> foo.edf), or one generated with Vivado.
 */
public class CombineDcps {

    private static final String USAGE = "USAGE: CombineDcps <static.dcp> <output.dcp> <cell>=<module.dcp> "
            + "[<cell>=<module.dcp> ...]\n"
            + "  <static.dcp>  Placed and routed design with one or more black boxes\n"
            + "  <output.dcp>  Combined design to write\n"
            + "  <cell>        Hierarchical name of a black box cell in <static.dcp>\n"
            + "  <module.dcp>  Placed and routed implementation of that black box";

    /**
     * Combines a design with implementations of its black boxes.
     *
     * @param staticDcp The design with the black boxes.
     * @param modules   The checkpoint to fill each black box with, keyed by the hierarchical name of
     *                  the black box cell.
     * @param outputDcp Where to write the combined design.
     * @return The combined design.
     */
    public static Design combine(Path staticDcp, Map<String, Path> modules, Path outputDcp) {
        CodePerfTracker t = new CodePerfTracker("CombineDcps");
        t.start("Read DCPs");
        Future<Design> top = ParallelismTools.submit(() -> Design.readCheckpoint(staticDcp, CodePerfTracker.SILENT));
        Map<String, Future<Design>> moduleFutures = new LinkedHashMap<>();
        for (Map.Entry<String, Path> e : modules.entrySet()) {
            moduleFutures.put(e.getKey(),
                    ParallelismTools.submit(() -> Design.readCheckpoint(e.getValue(), CodePerfTracker.SILENT)));
        }
        Design design = ParallelismTools.get(top);
        Map<String, Design> blackBoxes = new LinkedHashMap<>();
        for (Map.Entry<String, Future<Design>> e : moduleFutures.entrySet()) {
            blackBoxes.put(e.getKey(), ParallelismTools.get(e.getValue()));
        }

        t.stop().start("Populate black boxes");
        DesignTools.populateBlackBox(design, blackBoxes, true);
        DesignTools.renameAmbiguousPhysNets(design);

        t.stop().start("Write DCP");
        boolean orig2024Dot1 = Params.RW_WRITE_DCP_2024_1;
        Params.RW_WRITE_DCP_2024_1 = true;
        try {
            design.writeCheckpoint(outputDcp, CodePerfTracker.SILENT);
        } finally {
            Params.RW_WRITE_DCP_2024_1 = orig2024Dot1;
        }
        t.stop().printSummary();
        return design;
    }

    /**
     * Parses the {@code <cell>=<module.dcp>} arguments.
     *
     * @param args The command line arguments.
     * @param from The index of the first {@code <cell>=<module.dcp>} argument.
     * @return The checkpoint for each black box, in the order given.
     * @throws IllegalArgumentException If an argument is malformed, names a black box twice or names
     *                                  a checkpoint that does not exist.
     */
    static Map<String, Path> parseModules(String[] args, int from) {
        Map<String, Path> modules = new LinkedHashMap<>();
        for (int i = from; i < args.length; i++) {
            int eq = args[i].indexOf('=');
            if (eq <= 0 || eq == args[i].length() - 1) {
                throw new IllegalArgumentException("Expected <cell>=<module.dcp>, got '" + args[i] + "'");
            }
            String cell = args[i].substring(0, eq);
            Path dcp = Paths.get(args[i].substring(eq + 1));
            if (!Files.isRegularFile(dcp)) {
                throw new IllegalArgumentException("Checkpoint not found: " + dcp);
            }
            if (modules.put(cell, dcp) != null) {
                throw new IllegalArgumentException("Black box '" + cell + "' is given more than once");
            }
        }
        return modules;
    }

    public static void main(String[] args) {
        if (args.length < 3) {
            System.out.println(USAGE);
            return;
        }
        Path staticDcp = Paths.get(args[0]);
        if (!Files.isRegularFile(staticDcp)) {
            throw new IllegalArgumentException("Checkpoint not found: " + staticDcp);
        }
        combine(staticDcp, parseModules(args, 2), Paths.get(args[1]));
    }
}

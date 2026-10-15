/*
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

package com.xilinx.rapidwright.edif.optimizer;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Paths;

import com.xilinx.rapidwright.edif.EDIFCell;
import com.xilinx.rapidwright.edif.EDIFNetlist;
import com.xilinx.rapidwright.edif.EDIFTools;

/**
 * Netlist optimizer equivalent to Vivado's opt_design -propconst -sweep: constant propagation
 * and dead logic elimination as edits of an {@link EDIFNetlist}. An {@link EDIFLeafIndex} gives
 * the flat connectivity, an {@link EDIFConstPropEngine} decides what changes and an
 * {@link EDIFOptEditor} applies the changes to the hierarchical netlist. The usual entry points
 * are {@link EDIFTools#propagateConstants(EDIFNetlist)}, {@link EDIFTools#sweepDeadLogic(EDIFNetlist)}
 * and {@link EDIFTools#optimizeNetlist(EDIFNetlist, EDIFOptOptions)}; {@link #main(String[])} runs
 * the optimizer on an EDIF file.
 */
public class EDIFNetlistOptimizer {

    /**
     * Runs constant propagation and/or sweep (as selected in the options) on the netlist,
     * equivalent to Vivado's opt_design -propconst -sweep. Both analyses share one flat
     * connectivity index and all netlist edits are applied once at the end.
     * @param netlist The netlist to optimize (modified in place).
     * @param opts Options selecting the optimizations.
     * @return Statistics of the changes, counted as in Vivado's opt_design summary.
     */
    public static EDIFOptStats optimize(EDIFNetlist netlist, EDIFOptOptions opts) {
        EDIFOptStats stats = new EDIFOptStats();
        long t = System.nanoTime();
        // The ports of preserved (DONT_TOUCH) hierarchical instances are optimization boundaries
        EDIFLeafIndex idx = new EDIFLeafIndex(netlist, EDIFConstPropEngine::hasPreserveProp);
        stats.addTime("Build index", System.nanoTime() - t);
        EDIFConstPropEngine engine = new EDIFConstPropEngine(idx, opts);
        engine.run(stats);
        t = System.nanoTime();
        EDIFOptEditor editor = new EDIFOptEditor(engine, stats);
        editor.generate();
        EDIFOptReport report = opts.getReport() == null ? null
                : new EDIFOptReport(opts.getReport());
        if (report != null) {
            // Before any edit, so that cells and nets have their input names
            report.addDecisions(engine);
            editor.setReport(report);
        }
        EDIFCell[] defs = editor.uniquify();
        editor.apply(defs);
        stats.addTime("Apply edits", System.nanoTime() - t);
        t = System.nanoTime();
        int emptyHier = editor.cleanup(opts.isSweep());
        stats.addRemoved(EDIFOptStats.Phase.SWEEP, emptyHier);
        netlist.removeUnusedCellsFromAllWorkLibraries();
        netlist.resetParentNetMap();
        stats.addTime("Cleanup", System.nanoTime() - t);
        if (report != null) {
            report.finish();
        }
        return stats;
    }

    public static void main(String[] args) {
        if (args.length < 2) {
            System.out.println("USAGE: <input.edf[.gz]> <output.edf> [-propconst] [-sweep]"
                    + " [-no_push_inverters] [-no_post_process] [-report <file>]");
            System.out.println("  With neither -propconst nor -sweep, both are run.");
            return;
        }
        EDIFOptOptions opts = new EDIFOptOptions().setPropConst(false).setSweep(false);
        boolean any = false;
        String reportFile = null;
        for (int i = 2; i < args.length; i++) {
            switch (args[i]) {
                case "-propconst":
                    opts.setPropConst(true);
                    any = true;
                    break;
                case "-sweep":
                    opts.setSweep(true);
                    any = true;
                    break;
                case "-no_push_inverters":
                    opts.setPushInverters(false);
                    break;
                case "-no_post_process":
                    opts.setPostProcess(false);
                    break;
                case "-report":
                    reportFile = args[++i];
                    break;
                default:
                    throw new RuntimeException("Unknown option " + args[i]);
            }
        }
        if (!any) {
            opts.setPropConst(true).setSweep(true);
        }
        long t0 = System.nanoTime();
        EDIFNetlist netlist = EDIFTools.readEdifFile(Paths.get(args[0]));
        long t1 = System.nanoTime();
        EDIFOptStats stats;
        if (reportFile == null) {
            stats = optimize(netlist, opts);
        } else {
            try (Writer report = Files.newBufferedWriter(Paths.get(reportFile))) {
                stats = optimize(netlist, opts.setReport(report));
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
        long t2 = System.nanoTime();
        netlist.exportEDIF(Paths.get(args[1]));
        long t3 = System.nanoTime();
        System.out.print(stats);
        System.out.println(String.format("TIME_read=%.3f", (t1 - t0) / 1e9));
        System.out.println(String.format("TIME_opt=%.3f", (t2 - t1) / 1e9));
        System.out.println(String.format("TIME_write=%.3f", (t3 - t2) / 1e9));
        System.out.println(String.format("TIME_total=%.3f", (t3 - t0) / 1e9));
    }
}

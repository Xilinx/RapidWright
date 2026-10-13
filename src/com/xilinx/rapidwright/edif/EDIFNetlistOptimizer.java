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

package com.xilinx.rapidwright.edif;

import java.nio.file.Paths;

/**
 * Command-line entry point running {@link EDIFTools#optimizeNetlist(EDIFNetlist, EDIFOptOptions)}
 * (Vivado opt_design -propconst -sweep equivalent) on an EDIF file.
 */
public class EDIFNetlistOptimizer {

    public static void main(String[] args) {
        if (args.length < 2) {
            System.out.println("USAGE: <input.edf[.gz]> <output.edf> [-propconst] [-sweep]"
                    + " [-no_push_inverters] [-no_post_process]");
            System.out.println("  With neither -propconst nor -sweep, both are run.");
            return;
        }
        EDIFOptOptions opts = new EDIFOptOptions().setPropConst(false).setSweep(false);
        boolean any = false;
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
        EDIFOptStats stats = EDIFTools.optimizeNetlist(netlist, opts);
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

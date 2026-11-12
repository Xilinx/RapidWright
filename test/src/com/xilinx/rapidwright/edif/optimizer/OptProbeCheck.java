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

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

import com.xilinx.rapidwright.edif.EDIFNetlist;
import com.xilinx.rapidwright.edif.EDIFTools;
import com.xilinx.rapidwright.edif.compare.EDIFLeafNetlistComparator;

/**
 * Runs the RapidWright optimizer on every probe input and compares the result with Vivado's
 * opt_design output for the same probe.
 * Usage: OptProbeCheck <in_dir> <vivado_out_dir> [mode=ps] [regex] [-v]
 */
public class OptProbeCheck {

    public static void main(String[] args) throws Exception {
        Path in = Paths.get(args[0]);
        Path out = Paths.get(args[1]);
        String mode = args.length > 2 ? args[2] : "ps";
        String regex = args.length > 3 ? args[3] : ".*";
        boolean verbose = args.length > 4 && args[4].equals("-v");
        List<String> names = Files.readAllLines(in.resolve("probes.txt"));
        int pass = 0;
        int fail = 0;
        int missing = 0;
        for (String name : names) {
            if (!name.matches(regex)) {
                continue;
            }
            Path gold = out.resolve(name + "." + mode + ".edf");
            if (!Files.exists(gold)) {
                missing++;
                continue;
            }
            EDIFNetlist nl = EDIFTools.readEdifFile(in.resolve(name + ".edf"));
            EDIFOptOptions opts = new EDIFOptOptions().setPropConst(mode.contains("p"))
                    .setSweep(mode.contains("s"));
            String err = null;
            EDIFOptStats stats = null;
            try {
                stats = EDIFTools.optimizeNetlist(nl, opts);
            } catch (RuntimeException e) {
                err = e.toString();
                e.printStackTrace();
            }
            if (err == null && args.length > 5) {
                Files.createDirectories(Paths.get(args[5]));
                nl.exportEDIF(Paths.get(args[5]).resolve(name + ".rw.edf"));
            }
            EDIFLeafNetlistComparator c = new EDIFLeafNetlistComparator();
            int diffs = err != null ? -1 : c.compare(EDIFTools.readEdifFile(gold), nl);
            if (diffs == 0) {
                pass++;
                System.out.println("PASS " + name + "  " + oneLine(stats));
            } else {
                fail++;
                System.out.println("FAIL " + name + " diffs=" + diffs + "  " + oneLine(stats)
                        + (err != null ? " " + err : ""));
                if (err == null) {
                    ByteArrayOutputStream bos = new ByteArrayOutputStream();
                    c.printReport(new PrintStream(bos));
                    for (String line : bos.toString().split("\n")) {
                        if (line.startsWith("  ") || verbose) {
                            System.out.println("    " + line);
                        }
                    }
                    if (verbose) {
                        EDIFLeafNetlistComparator.dump(nl, System.out);
                    }
                }
            }
        }
        System.out.println("SUMMARY pass=" + pass + " fail=" + fail + " missing=" + missing);
    }

    private static String oneLine(EDIFOptStats s) {
        if (s == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (EDIFOptStats.Phase p : EDIFOptStats.Phase.values()) {
            sb.append(p.name().charAt(0)).append(':').append(s.getCreated(p)).append('/')
                    .append(s.getRemoved(p)).append(' ');
        }
        return sb.toString();
    }
}

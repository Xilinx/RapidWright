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

import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;

import com.xilinx.rapidwright.edif.EDIFCellInst;
import com.xilinx.rapidwright.edif.EDIFNetlist;
import com.xilinx.rapidwright.edif.EDIFPropertyValue;
import com.xilinx.rapidwright.edif.EDIFTools;

/**
 * Analysis helper: for leaf cells removed as constant (present in the input, absent in the gold
 * result), prints how many of the surviving loads of their output nets the gold result marks
 * with OPT_MODIFIED, grouped by load cell type and pin.
 * Usage: OptMarkAnalysis <input.edf> <gold.edf> [max_detail]
 */
public class OptMarkAnalysis {

    public static void main(String[] args) {
        EDIFNetlist in = EDIFTools.readEdifFile(Paths.get(args[0]));
        EDIFNetlist gold = EDIFTools.readEdifFile(Paths.get(args[1]));
        int maxDetail = args.length > 2 ? Integer.parseInt(args[2]) : 20;
        EDIFLeafIndex a = new EDIFLeafIndex(in);
        EDIFLeafIndex g = new EDIFLeafIndex(gold);
        Map<String, Integer> gl = new HashMap<>();
        for (int l = 0; l < g.getNumLeaves(); l++) {
            gl.put(g.getLeafName(l), l);
        }
        Map<String, int[]> stats = new HashMap<>();
        int detail = 0;
        for (int l = 0; l < a.getNumLeaves(); l++) {
            String name = a.getLeafName(l);
            if (gl.containsKey(name) || a.getLeafInst(l).getCellType().isStaticSource()) {
                continue;
            }
            for (int p = a.getLeafPinStart(l); p < a.getLeafPinEnd(l); p++) {
                int n = a.getPinNet(p);
                if (a.getPinDir(p) != EDIFLeafIndex.DRIVER || n < 0) {
                    continue;
                }
                int marked = 0;
                int total = 0;
                StringBuilder sb = new StringBuilder();
                for (int i = a.getNetPinStart(n); i < a.getNetPinEnd(n); i++) {
                    int q = a.getNetPin(i);
                    int ql = a.getPinLeaf(q);
                    if (q == p || ql < 0) {
                        continue;
                    }
                    Integer gq = gl.get(a.getLeafName(ql));
                    if (gq == null) {
                        continue;
                    }
                    EDIFCellInst gi = g.getLeafInst(gq);
                    EDIFPropertyValue m = gi.getProperty("OPT_MODIFIED");
                    EDIFPropertyValue orig = a.getLeafInst(ql).getProperty("OPT_MODIFIED");
                    boolean isMarked = m != null && (orig == null
                            || !m.getValue().equals(orig.getValue()));
                    String key = a.getLeafInst(l).getCellType().getName() + " -> "
                            + gi.getCellType().getName() + "/"
                            + a.getPinPortInst(q).getPort().getBusName();
                    int[] st = stats.computeIfAbsent(key, k -> new int[2]);
                    st[0]++;
                    total++;
                    if (isMarked) {
                        st[1]++;
                        marked++;
                    }
                    sb.append(' ').append(gi.getCellType().getName()).append(':')
                            .append(a.getLeafInst(ql).getName()).append('/')
                            .append(a.getPinPortInst(q).getName()).append(isMarked ? "*" : "");
                }
                String filter = System.getProperty("perdriver");
                if (total > 0 && filter != null && name.contains(filter)) {
                    System.out.println("DRIVER " + a.getLeafInst(l).getCellType().getName() + " "
                            + name + " total=" + total + " marked=" + marked + " fanout="
                            + (a.getNetPinEnd(n) - a.getNetPinStart(n) - 1) + " net="
                            + a.getPinPortInst(p).getNet().getName() + " loads:" + sb);
                }
                if (total > 0 && marked > 0 && marked < total && detail++ < maxDetail) {
                    System.out.println("MIXED " + name + " ("
                            + a.getLeafInst(l).getCellType().getName() + ") hier="
                            + a.getHierName(a.getLeafHier(l)) + " marked=" + marked + "/"
                            + total);
                    for (int i = a.getNetPinStart(n); i < a.getNetPinEnd(n); i++) {
                        int q = a.getNetPin(i);
                        int ql = a.getPinLeaf(q);
                        if (q == p || ql < 0 || !gl.containsKey(a.getLeafName(ql))) {
                            continue;
                        }
                        EDIFPropertyValue m = g.getLeafInst(gl.get(a.getLeafName(ql)))
                                .getProperty("OPT_MODIFIED");
                        System.out.println("    " + a.getPinName(q) + " hier="
                                + a.getHierName(a.getLeafHier(ql)) + " seg="
                                + a.getPinPortInst(q).getNet().getName() + " "
                                + (m == null ? "-" : m.getValue()));
                    }
                }
            }
        }
        for (Map.Entry<String, int[]> e : stats.entrySet()) {
            System.out.println("STAT " + e.getKey() + " total=" + e.getValue()[0] + " marked="
                    + e.getValue()[1]);
        }
    }
}

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
 * Debug helper: prints the optimizer's classification of the named leaf cells.
 * Usage: OptDebug <edf> <leaf name>...
 */
public class OptDebug {

    public static void main(String[] args) {
        EDIFNetlist nl = EDIFTools.readEdifFile(Paths.get(args[0]));
        EDIFLeafIndex idx = new EDIFLeafIndex(nl);
        EDIFConstPropEngine e = new EDIFConstPropEngine(idx, new EDIFOptOptions());
        java.util.Map<String, Integer> byName = new java.util.HashMap<>();
        for (int l = 0; l < idx.getNumLeaves(); l++) {
            byName.put(idx.getLeafName(l), l);
        }
        for (int i = 1; i < args.length; i++) {
            Integer l = byName.get(args[i]);
            if (l == null) {
                System.out.println(args[i] + " not found");
                continue;
            }
            System.out.println(args[i] + " kind=" + e.kind[l] + " frozen=" + e.frozen[l]
                    + " blackbox=" + idx.isLeafBlackBox(l));
            for (int h = idx.getLeafHier(l); h > 0; h = idx.getHierParent(h)) {
                EDIFCellInst hi = idx.getHierInst(h);
                System.out.println("  hier " + hi.getName() + " (" + hi.getCellType().getName()
                        + ") " + hi.getPropertiesMap());
            }
            for (int p = idx.getLeafPinStart(l); p < idx.getLeafPinEnd(l); p++) {
                int n = idx.getPinNet(p);
                System.out.println("  pin " + idx.getPinPortInst(p).getName() + " net=" + n
                        + (n >= 0 ? " opaque=" + e.netOpaque[n] + " dt=" + e.netDT[n]
                        + " driver=" + e.netDriver[n] + " val=" + e.netVal[n] : ""));
                if (n >= 0) {
                    for (int s = idx.getNetSegStart(n); s < idx.getNetSegEnd(n); s++) {
                        EDIFNet net = idx.getSegEDIFNet(idx.getNetSeg(s));
                        if (!net.getPropertiesMap().isEmpty()) {
                            System.out.println("    seg " + net.getName() + " "
                                    + net.getPropertiesMap());
                        }
                    }
                }
            }
        }
        e.propagate();
        for (int i = 1; i < args.length; i++) {
            Integer l = byName.get(args[i]);
            if (l != null) {
                EDIFConstPropEngine.Lut s = e.lut[l];
                System.out.println(args[i] + " after propagate: removed=" + e.removed[l]
                        + (s == null ? " lut=null" : " lut size=" + s.size + " table="
                        + Long.toHexString(s.table) + " changed=" + s.changed));
            }
        }
    }
}

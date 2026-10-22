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
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.TreeMap;

import com.xilinx.rapidwright.edif.EDIFCell;
import com.xilinx.rapidwright.edif.EDIFCellInst;
import com.xilinx.rapidwright.edif.EDIFNetlist;
import com.xilinx.rapidwright.edif.EDIFTools;

/**
 * Prints statistics of a netlist relevant to the optimizer: leaf cells by type, hierarchical
 * instances and shared definitions, black boxes and preserve attributes.
 * Usage: OptNetlistStats <edf>
 */
public class OptNetlistStats {

    public static void main(String[] args) {
        long t0 = System.nanoTime();
        EDIFNetlist nl = EDIFTools.readEdifFile(Paths.get(args[0]));
        long t1 = System.nanoTime();
        EDIFLeafIndex idx = new EDIFLeafIndex(nl);
        long t2 = System.nanoTime();
        Map<String, Integer> types = new TreeMap<>();
        int blackBoxes = 0;
        int dtLeaves = 0;
        int keepLeaves = 0;
        for (int l = 0; l < idx.getNumLeaves(); l++) {
            EDIFCellInst inst = idx.getLeafInst(l);
            types.merge(inst.getCellType().getName(), 1, Integer::sum);
            if (idx.isLeafBlackBox(l)) {
                blackBoxes++;
            }
            if (EDIFConstPropEngine.hasPreserveProp(inst)) {
                dtLeaves++;
            }
            for (String k : inst.getPropertiesMap().keySet()) {
                if (k.equalsIgnoreCase("KEEP")) {
                    keepLeaves++;
                }
            }
        }
        Map<EDIFCell, Integer> defUse = new IdentityHashMap<>();
        int dtHier = 0;
        for (int h = 1; h < idx.getNumHierInsts(); h++) {
            defUse.merge(idx.getHierCell(h), 1, Integer::sum);
            if (EDIFConstPropEngine.hasPreserveProp(idx.getHierInst(h))) {
                dtHier++;
            }
        }
        int shared = 0;
        int sharedInsts = 0;
        for (int c : defUse.values()) {
            if (c > 1) {
                shared++;
                sharedInsts += c;
            }
        }
        int dtNets = 0;
        for (int s = 0; s < idx.getNumSegs(); s++) {
            if (EDIFConstPropEngine.hasPreserveProp(idx.getSegEDIFNet(s))) {
                dtNets++;
            }
        }
        System.out.println("leaves=" + idx.getNumLeaves() + " pins=" + idx.getNumLeafPins()
                + " nets=" + idx.getNumNets() + " hierInsts=" + idx.getNumHierInsts()
                + " defs=" + defUse.size() + " sharedDefs=" + shared + " (" + sharedInsts
                + " instances)");
        System.out.println("blackBoxes=" + blackBoxes + " dontTouchOrMarkDebugLeaves=" + dtLeaves
                + " dontTouchHierInsts=" + dtHier + " dontTouchOrMarkDebugNetSegments=" + dtNets
                + " keepLeaves=" + keepLeaves);
        StringBuilder sb = new StringBuilder("types:");
        types.entrySet().stream().sorted((a, b) -> b.getValue() - a.getValue()).limit(40)
                .forEach(e -> sb.append(' ').append(e.getKey()).append('=').append(e.getValue()));
        System.out.println(sb);
        System.out.println(String.format("TIME_read=%.2f TIME_index=%.2f", (t1 - t0) / 1e9,
                (t2 - t1) / 1e9));
    }
}

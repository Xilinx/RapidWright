/*
 * Copyright (c) 2026, Advanced Micro Devices, Inc.
 * All rights reserved.
 *
 * Author: Chris Lavin, Advanced Micro Devices, Inc.
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

package com.xilinx.rapidwright.lec;

import java.io.PrintStream;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import com.xilinx.rapidwright.device.Series;
import com.xilinx.rapidwright.edif.EDIFDirection;
import com.xilinx.rapidwright.edif.EDIFPort;

/**
 * Counts gathered while classifying one netlist: how big it is, how its cells
 * divide between the roles in {@link CellRole}, how many cone roots there are,
 * and which cell types have no functional model.
 *
 * This is the output of the classification pass and is a useful netlist audit
 * in its own right. In particular the unmodelled type list shows which
 * primitives in a design have no functional model.
 */
public class NetlistStats {

    private final String label;

    private final String partName;

    private final Series series;

    private int instCount;

    private int leafCount;

    private int netCount;

    private int rootCount;

    private int leafPinCount;

    private int partitionCount;

    private final Map<CellRole, Integer> roleCounts = new EnumMap<>(CellRole.class);

    private final Map<String, Integer> typeCounts = new TreeMap<>();

    private final Map<String, Integer> blackBoxTypeCounts = new TreeMap<>();

    private final Map<String, Integer> opaquePrimitiveCounts = new TreeMap<>();

    private Map<String, Integer> rootKindCounts = new TreeMap<>();

    private int terminalCount;

    private int checkedPinCount;

    private int clockPinCount;

    private int unconnectedCheckedPinCount;

    private int undrivenCheckedPinCount;

    private int topInputBits;

    private int topOutputBits;

    private int topInoutBits;

    private int unconnectedTopPortBits;

    public NetlistStats(String label, String partName, Series series) {
        this.label = label;
        this.partName = partName;
        this.series = series;
        for (CellRole role : CellRole.values()) {
            roleCounts.put(role, 0);
        }
    }

    /**
     * Walks every atom in the flattened netlist, classifying it and counting the
     * cone roots it contributes.
     */
    public static NetlistStats collect(String label, FlatNetlist flat, Connectivity conn,
            CellClassifier classifier, LecLog log) {
        NetlistStats stats = new NetlistStats(label, NetlistLoader.getPartName(flat.getNetlist()),
                classifier.getClockPinTable().getSeries());
        stats.instCount = flat.getInstCount();
        stats.leafCount = flat.getLeafCount();
        stats.netCount = flat.getNetCount();
        stats.leafPinCount = flat.getLeafPinCount();
        stats.rootCount = conn.getRootCount();
        stats.rootKindCounts = conn.getRootKindCounts();
        stats.partitionCount = countPartitions(flat);

        for (int instId : flat.getLeafInstIds()) {
            CellRole role = classifier.classify(flat.getCellType(instId));
            stats.roleCounts.merge(role, 1, Integer::sum);
            String typeName = flat.getCellTypeName(instId);
            stats.typeCounts.merge(typeName, 1, Integer::sum);
            if (role == CellRole.UNINTERPRETED) {
                stats.blackBoxTypeCounts.merge(typeName, 1, Integer::sum);
            }
            if (role == CellRole.TERMINAL) {
                stats.terminalCount++;
                stats.countTerminalPins(flat, conn, classifier, instId, typeName);
                if (!isWellKnownTerminal(typeName)) {
                    stats.opaquePrimitiveCounts.merge(typeName, 1, Integer::sum);
                }
            }
        }
        stats.countTopPorts(flat);
        return stats;
    }

    /**
     * Counts the checked cone roots a single terminal contributes: every input
     * pin except clock fanout.
     */
    private void countTerminalPins(FlatNetlist flat, Connectivity conn, CellClassifier classifier,
            int instId, String typeName) {
        CellIndex index = flat.getIndex(instId);
        for (int bit = 0; bit < index.getPortBitCount(); bit++) {
            EDIFPort port = index.getPortOfBit(bit);
            if (port.getDirection() != EDIFDirection.INPUT) {
                continue;
            }
            String pinName = index.getPortBitName(bit);
            if (classifier.isClockPin(typeName, pinName)) {
                clockPinCount++;
                continue;
            }
            checkedPinCount++;
            int root = conn.getPinRoot(flat.getGlobalPin(instId, bit));
            if (root < 0) {
                unconnectedCheckedPinCount++;
            } else if (conn.getRootKind(root) == Connectivity.UNDRIVEN) {
                undrivenCheckedPinCount++;
            }
        }
    }

    /**
     * @return True if the cell type is a well known sequential, memory or DSP
     *         terminal, as opposed to a hard block treated as opaque.
     */
    private static boolean isWellKnownTerminal(String typeName) {
        return typeName.startsWith("FD") || typeName.startsWith("LD") || typeName.startsWith("SRL")
                || typeName.startsWith("RAM") || typeName.startsWith("URAM")
                || typeName.startsWith("DSP") || typeName.equals("CFGLUT5");
    }

    /** Top level ports are terminals: inputs are sources, outputs are cone roots. */
    private void countTopPorts(FlatNetlist flat) {
        int topId = flat.getTopInstId();
        if (flat.isLeaf(topId)) {
            return;
        }
        CellIndex index = flat.getIndex(topId);
        for (int bit = 0; bit < index.getPortBitCount(); bit++) {
            EDIFDirection dir = index.getPortOfBit(bit).getDirection();
            if (index.getBoundaryNet(bit) < 0) {
                unconnectedTopPortBits++;
            }
            if (dir == EDIFDirection.INPUT) {
                topInputBits++;
            } else if (dir == EDIFDirection.OUTPUT) {
                topOutputBits++;
                checkedPinCount++;
            } else if (dir == EDIFDirection.INOUT) {
                topInoutBits++;
            }
        }
    }

    /**
     * @return The number of direct children of the top cell.
     */
    private static int countPartitions(FlatNetlist flat) {
        int topId = flat.getTopInstId();
        if (flat.isLeaf(topId)) {
            return 0;
        }
        return flat.getIndex(topId).getChildCount();
    }

    /** Prints the full classification audit for this netlist. */
    public void print(PrintStream out) {
        out.println();
        out.println("=== " + label + " ===");
        out.println("  part                     : " + (partName == null ? "(unknown)" : partName));
        out.println("  series                   : " + (series == null ? "(unknown)" : series));
        out.println("  top level children       : " + partitionCount);
        out.println("  instances                : " + instCount);
        out.println("  leaf primitives          : " + leafCount);
        out.println("  hierarchical nets        : " + netCount);
        out.println("  electrical nodes         : " + rootCount);
        out.println("  leaf pins                : " + leafPinCount);
        out.println("  terminals                : " + terminalCount);
        out.println("  checked cone roots       : " + checkedPinCount);
        out.println("  clock pins excluded      : " + clockPinCount);
        out.println("  unconnected checked pins : " + unconnectedCheckedPinCount);
        out.println("  undriven checked pins    : " + undrivenCheckedPinCount);
        out.println("  top ports in/out/inout   : " + topInputBits + " / " + topOutputBits + " / "
                + topInoutBits);
        out.println("  unconnected top port bits: " + unconnectedTopPortBits);
        printRoleCounts(out);
        printMap(out, "  electrical node kinds:", rootKindCounts);
        printTypeBreakdown(out, "opaque primitives", opaquePrimitiveCounts);
        printTypeBreakdown(out, "black box cell types", blackBoxTypeCounts);
    }

    private void printRoleCounts(PrintStream out) {
        out.println("  leaf cells by role:");
        for (CellRole role : CellRole.values()) {
            int count = roleCounts.get(role);
            if (count > 0) {
                out.println("    " + pad(role.name().toLowerCase().replace('_', ' ')) + count);
            }
        }
    }

    private static void printMap(PrintStream out, String heading, Map<String, Integer> map) {
        if (map.isEmpty()) {
            return;
        }
        out.println(heading);
        for (Map.Entry<String, Integer> e : map.entrySet()) {
            out.println("    " + pad(e.getKey()) + e.getValue());
        }
    }

    /**
     * Lists cell types in a category, most frequent first. The two categories
     * that matter are hard blocks cut as opaque terminals, which is expected,
     * and black boxes, which weaken the verdict and are the list that decides
     * what the model library still needs.
     */
    private static void printTypeBreakdown(PrintStream out, String heading,
            Map<String, Integer> counts) {
        if (counts.isEmpty()) {
            out.println("  " + pad(heading) + "none");
            return;
        }
        int total = 0;
        for (int count : counts.values()) {
            total += count;
        }
        out.println("  " + pad(heading) + counts.size() + " distinct, " + total + " instances");
        List<Map.Entry<String, Integer>> sorted = new ArrayList<>(counts.entrySet());
        sorted.sort(Comparator.<Map.Entry<String, Integer>>comparingInt(e -> -e.getValue())
                .thenComparing(Map.Entry::getKey));
        for (Map.Entry<String, Integer> e : sorted) {
            out.println("    " + pad(e.getKey()) + e.getValue());
        }
    }

    private static String pad(String s) {
        StringBuilder sb = new StringBuilder(s);
        while (sb.length() < 25) {
            sb.append(' ');
        }
        return sb.append(": ").toString();
    }

    public String getLabel() {
        return label;
    }

    public Series getSeries() {
        return series;
    }

    public String getPartName() {
        return partName;
    }

    public int getInstCount() {
        return instCount;
    }

    public int getLeafCount() {
        return leafCount;
    }

    public int getNetCount() {
        return netCount;
    }

    public int getRootCount() {
        return rootCount;
    }

    public int getLeafPinCount() {
        return leafPinCount;
    }

    public int getTerminalCount() {
        return terminalCount;
    }

    public int getCheckedPinCount() {
        return checkedPinCount;
    }

    public int getClockPinCount() {
        return clockPinCount;
    }

    public int getPartitionCount() {
        return partitionCount;
    }

    public int getRoleCount(CellRole role) {
        return roleCounts.get(role);
    }

    /** @return Black box cell types, mapped to instance counts. */
    public Map<String, Integer> getBlackBoxTypeCounts() {
        return new LinkedHashMap<>(blackBoxTypeCounts);
    }

    /** @return Hard block types cut as opaque terminals, mapped to instance counts. */
    public Map<String, Integer> getOpaquePrimitiveCounts() {
        return new LinkedHashMap<>(opaquePrimitiveCounts);
    }

    public int getUndrivenCheckedPinCount() {
        return undrivenCheckedPinCount;
    }

    public int getUnconnectedCheckedPinCount() {
        return unconnectedCheckedPinCount;
    }

    /** @return Every leaf cell type present, mapped to instance counts. */
    public Map<String, Integer> getTypeCounts() {
        return new LinkedHashMap<>(typeCounts);
    }
}

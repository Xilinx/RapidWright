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

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.xilinx.rapidwright.edif.EDIFCell;
import com.xilinx.rapidwright.edif.EDIFCellInst;
import com.xilinx.rapidwright.edif.EDIFDirection;
import com.xilinx.rapidwright.edif.EDIFNet;
import com.xilinx.rapidwright.edif.EDIFPortInst;
import com.xilinx.rapidwright.edif.EDIFTools;

/**
 * Builds the electrical node structure of a flattened netlist.
 *
 * RapidWright's own alias traversal cannot be used here: both
 * {@code EDIFNetlist.getNetAliases} and
 * {@code EDIFHierNet.getLeafHierPortInsts} raise an error the moment they find
 * a second driver, and multi-driver nets do occur in real netlists. Merging
 * here is direction agnostic, so driver multiplicity is discovered and
 * classified rather than being fatal.
 */
public class ConnectivityBuilder {

    /** Warning category for pins whose port bit could not be resolved. */
    public static final String UNRESOLVED_PIN = "Unresolved port bit";

    /** Warning category for nets with two or more drivers. */
    public static final String MULTI_DRIVER = "Multi-driver net";

    /** Warning category for nets with no driver at all. */
    public static final String UNDRIVEN = "Undriven net";

    /** Warning category for top level bidirectional ports. */
    public static final String TOP_INOUT = "Top level inout port";

    /** No constant evidence has been seen for a root. */
    private static final byte CONST_NONE = 0;

    /** Contradictory constant evidence: the root is tied both high and low. */
    private static final byte CONST_CONFLICT = -1;

    private ConnectivityBuilder() {
    }

    /**
     * Merges hierarchical nets into electrical nodes and classifies each node by
     * what drives it.
     */
    public static Connectivity build(FlatNetlist flat, LecLog log) {
        NetUnionFind uf = new NetUnionFind(flat.getNetCount());
        DriverList drivers = new DriverList();
        List<Integer> constZeroNets = new ArrayList<>();
        List<Integer> constOneNets = new ArrayList<>();
        int[] pinNet = new int[flat.getLeafPinCount()];
        Arrays.fill(pinNet, -1);

        unionBoundaries(flat, uf, drivers, pinNet, constZeroNets, constOneNets, log);

        int[] netRoot = uf.compact();
        int rootCount = uf.getSetCount();
        int[] pinRoot = new int[pinNet.length];
        for (int p = 0; p < pinNet.length; p++) {
            pinRoot[p] = pinNet[p] < 0 ? -1 : netRoot[pinNet[p]];
        }
        return classifyRoots(flat, netRoot, pinRoot, rootCount, drivers, constZeroNets,
                constOneNets, log);
    }

    /**
     * Single sweep over every hierarchical net. Each port instance belonging to
     * a child instance merges the outer net with the child's corresponding
     * internal net, which is what carries a cone through a hierarchy boundary.
     * The same sweep records which root each leaf pin attaches to, which leaf
     * output pins are drivers, and which nets are named as logical constants.
     */
    private static void unionBoundaries(FlatNetlist flat, NetUnionFind uf, DriverList drivers,
            int[] pinNet, List<Integer> constZeroNets, List<Integer> constOneNets, LecLog log) {
        int instCount = flat.getInstCount();
        for (int instId = 0; instId < instCount; instId++) {
            if (flat.isLeaf(instId)) {
                continue;
            }
            CellIndex index = flat.getIndex(instId);
            for (EDIFNet net : flat.getCellType(instId).getNets()) {
                int outerNet = flat.getGlobalNet(instId, index.localNetIndex(net));
                String netName = net.getName();
                if (EDIFTools.LOGICAL_GND_NET_NAME.equals(netName)) {
                    constZeroNets.add(outerNet);
                } else if (EDIFTools.LOGICAL_VCC_NET_NAME.equals(netName)) {
                    constOneNets.add(outerNet);
                }
                for (EDIFPortInst portInst : net.getPortInsts()) {
                    EDIFCellInst childInst = portInst.getCellInst();
                    if (childInst == null) {
                        continue;
                    }
                    connectChildPin(flat, uf, drivers, pinNet, log, instId, index, outerNet,
                            portInst, childInst);
                }
            }
        }
    }

    /**
     * Handles one port instance on a child of the cell being swept: either merge
     * across the boundary into the child's internal net, or, when the child is
     * an atom, record the pin.
     */
    private static void connectChildPin(FlatNetlist flat, NetUnionFind uf, DriverList drivers,
            int[] pinNet, LecLog log, int instId, CellIndex index, int outerNet,
            EDIFPortInst portInst, EDIFCellInst childInst) {
        int childId = flat.getChild(instId, index.localChildIndex(childInst));
        if (childId < 0) {
            log.warn(UNRESOLVED_PIN, "unknown child instance", childInst.getName());
            return;
        }
        CellIndex childIndex = flat.getIndex(childId);
        int bit = childIndex.portBitIndex(portInst);
        if (bit < 0) {
            log.warn(UNRESOLVED_PIN,
                    flat.getCellTypeName(childId) + "." + portInst.getName(),
                    flat.getInstName(childId));
            return;
        }
        int innerNet = childIndex.getBoundaryNet(bit);
        if (innerNet >= 0) {
            uf.union(outerNet, flat.getGlobalNet(childId, innerNet));
            return;
        }
        if (!flat.isLeaf(childId)) {
            return;
        }
        int globalPin = flat.getGlobalPin(childId, bit);
        pinNet[globalPin] = outerNet;
        // A bidirectional leaf pin, such as an IOBUF's IO, drives its net too.
        if (portInst.isOutput() || portInst.getDirection() == EDIFDirection.INOUT) {
            drivers.add(outerNet, childId, globalPin);
        }
    }

    /**
     * Resolves every recorded driver against the compacted roots and assigns
     * each root a kind.
     */
    private static Connectivity classifyRoots(FlatNetlist flat, int[] netRoot, int[] pinRoot,
            int rootCount, DriverList drivers, List<Integer> constZeroNets,
            List<Integer> constOneNets, LecLog log) {
        byte[] rootKind = new byte[rootCount];
        int[] rootDriverPin = new int[rootCount];
        int[] rootTopPortBit = new int[rootCount];
        int[] driverCount = new int[rootCount];
        byte[] constVote = new byte[rootCount];
        Arrays.fill(rootDriverPin, -1);
        Arrays.fill(rootTopPortBit, -1);
        Arrays.fill(rootKind, Connectivity.UNDRIVEN);

        Map<Integer, int[]> extraDrivers = new HashMap<>();
        boolean[] logicDriven = new boolean[rootCount];
        for (int i = 0; i < drivers.size; i++) {
            int root = netRoot[drivers.net[i]];
            driverCount[root]++;
            if (rootDriverPin[root] < 0) {
                rootDriverPin[root] = drivers.pin[i];
            } else {
                appendDriver(extraDrivers, root, rootDriverPin[root], drivers.pin[i]);
            }
            byte constKind = constKindOf(flat, drivers.inst[i]);
            if (constKind == CONST_NONE) {
                logicDriven[root] = true;
            }
            constVote[root] = mergeConstVote(constVote[root], constKind);
        }
        for (int net : constZeroNets) {
            int root = netRoot[net];
            constVote[root] = mergeConstVote(constVote[root], Connectivity.CONST0);
        }
        for (int net : constOneNets) {
            int root = netRoot[net];
            constVote[root] = mergeConstVote(constVote[root], Connectivity.CONST1);
        }

        int[] topSources = new int[rootCount];
        markTopPorts(flat, netRoot, rootTopPortBit, topSources, log);

        for (int root = 0; root < rootCount; root++) {
            int sources = driverCount[root] + topSources[root];
            // Constant evidence only stands when nothing else drives the node; a
            // constant shorted to logic or to a port is a multi-driver net.
            boolean constant = constVote[root] == Connectivity.CONST0
                    || constVote[root] == Connectivity.CONST1;
            if (constant && !logicDriven[root] && topSources[root] == 0) {
                rootKind[root] = constVote[root];
            } else if (sources > 1) {
                rootKind[root] = Connectivity.MULTI_DRIVER;
            } else if (driverCount[root] == 1) {
                rootKind[root] = Connectivity.DRIVEN_BY_LEAF;
            } else if (topSources[root] > 0) {
                rootKind[root] = isTopInout(flat, netRoot, rootTopPortBit[root])
                        ? Connectivity.TOP_INOUT : Connectivity.TOP_INPUT;
            } else {
                rootKind[root] = Connectivity.UNDRIVEN;
            }
        }

        reportAnomalies(flat, rootCount, rootKind, driverCount, extraDrivers, rootDriverPin, log);
        return new Connectivity(netRoot, pinRoot, rootCount, rootKind, rootDriverPin,
                rootTopPortBit, extraDrivers);
    }

    private static void appendDriver(Map<Integer, int[]> extraDrivers, int root, int first,
            int next) {
        int[] existing = extraDrivers.get(root);
        if (existing == null) {
            existing = new int[] { first, next };
        } else {
            existing = Arrays.copyOf(existing, existing.length + 1);
            existing[existing.length - 1] = next;
        }
        extraDrivers.put(root, existing);
    }

    /**
     * Records which roots touch the top cell's own interface. A top level input
     * is a source terminal; a bidirectional port is both a source and a sink and
     * is reported because it weakens the proof.
     *
     * A root's port bit names its source variable, so a source port always
     * takes precedence over an output port on the same node. Otherwise a
     * feedthrough would name its input after the output it drives.
     */
    private static void markTopPorts(FlatNetlist flat, int[] netRoot, int[] rootTopPortBit,
            int[] topSources, LecLog log) {
        int topId = flat.getTopInstId();
        if (flat.isLeaf(topId)) {
            return;
        }
        CellIndex index = flat.getIndex(topId);
        for (int bit = 0; bit < index.getPortBitCount(); bit++) {
            int localNet = index.getBoundaryNet(bit);
            if (localNet < 0) {
                continue;
            }
            int root = netRoot[flat.getGlobalNet(topId, localNet)];
            EDIFDirection dir = index.getPortOfBit(bit).getDirection();
            boolean source = dir == EDIFDirection.INPUT || dir == EDIFDirection.INOUT;
            if (source) {
                if (topSources[root] == 0) {
                    rootTopPortBit[root] = bit;
                }
                topSources[root]++;
            } else if (rootTopPortBit[root] < 0) {
                rootTopPortBit[root] = bit;
            }
            if (dir == EDIFDirection.INOUT) {
                log.warn(TOP_INOUT, index.getPortBitName(bit), null);
            }
        }
    }

    private static boolean isTopInout(FlatNetlist flat, int[] netRoot, int topPortBit) {
        if (topPortBit < 0) {
            return false;
        }
        CellIndex index = flat.getIndex(flat.getTopInstId());
        return index.getPortOfBit(topPortBit).getDirection() == EDIFDirection.INOUT;
    }

    /**
     * Emits de-duplicated warnings for the two conditions that weaken a verdict:
     * nets with several drivers, and nets with no driver.
     */
    private static void reportAnomalies(FlatNetlist flat, int rootCount, byte[] rootKind,
            int[] driverCount, Map<Integer, int[]> extraDrivers, int[] rootDriverPin, LecLog log) {
        for (int root = 0; root < rootCount; root++) {
            if (rootKind[root] == Connectivity.MULTI_DRIVER) {
                int[] all = extraDrivers.get(root);
                int count = all != null ? all.length : driverCount[root];
                int examplePin = all != null && all.length > 0 ? all[0] : rootDriverPin[root];
                String example = null;
                if (examplePin >= 0) {
                    int owner = flat.getPinOwner(examplePin);
                    example = flat.getPinName(owner, flat.getPinPortBit(examplePin));
                }
                log.warn(MULTI_DRIVER, count + " leaf drivers", example);
            } else if (rootKind[root] == Connectivity.UNDRIVEN) {
                log.warn(UNDRIVEN, "no driver", null);
            }
        }
    }

    /**
     * @return {@link Connectivity#CONST0} or {@link Connectivity#CONST1} if the
     *         driving instance is a tie cell, otherwise {@link #CONST_NONE}.
     */
    private static byte constKindOf(FlatNetlist flat, int instId) {
        EDIFCell cell = flat.getCellType(instId);
        if (cell.isGNDSource()) {
            return Connectivity.CONST0;
        }
        if (cell.isVCCSource()) {
            return Connectivity.CONST1;
        }
        return CONST_NONE;
    }

    /**
     * Combines constant evidence for a root. Agreeing evidence is kept;
     * disagreeing evidence, meaning a net tied both high and low, becomes a
     * conflict so the root falls through to the multi-driver case.
     */
    private static byte mergeConstVote(byte current, byte incoming) {
        if (incoming == CONST_NONE || current == CONST_CONFLICT) {
            return current;
        }
        if (current == CONST_NONE) {
            return incoming;
        }
        return current == incoming ? current : CONST_CONFLICT;
    }

    /**
     * Growable parallel arrays recording every leaf output pin found, before
     * roots are known. Avoids a second full traversal of the netlist.
     */
    private static class DriverList {

        private int[] net = new int[1024];

        private int[] inst = new int[1024];

        private int[] pin = new int[1024];

        private int size;

        void add(int globalNet, int instId, int globalPin) {
            if (size == net.length) {
                int capacity = size * 2;
                net = Arrays.copyOf(net, capacity);
                inst = Arrays.copyOf(inst, capacity);
                pin = Arrays.copyOf(pin, capacity);
            }
            net[size] = globalNet;
            inst[size] = instId;
            pin[size] = globalPin;
            size++;
        }
    }
}

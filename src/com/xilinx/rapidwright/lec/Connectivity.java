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

import java.util.Map;
import java.util.TreeMap;

/**
 * The electrical node structure of a flattened netlist: which hierarchical nets
 * are the same node, and what drives each node.
 *
 * Every hierarchical net is mapped to a dense <i>root</i> identifier. Nets on
 * either side of a hierarchy boundary share a root, which is what makes cone
 * construction transparent to how the design is divided into hierarchy.
 *
 * A root is classified by what drives it. Unlike RapidWright's own net alias
 * traversal, more than one driver is tolerated rather than fatal, because
 * multi-driver nets do occur in real netlists.
 */
public class Connectivity {

    /** Driven by exactly one leaf cell output pin. */
    public static final byte DRIVEN_BY_LEAF = 0;

    /** Driven by a top level input port, a source terminal. */
    public static final byte TOP_INPUT = 1;

    /** Attached to a top level bidirectional port. */
    public static final byte TOP_INOUT = 2;

    /** Tied to logic zero, either by a GND cell or a {@code <const0>} net. */
    public static final byte CONST0 = 3;

    /** Tied to logic one, either by a VCC cell or a {@code <const1>} net. */
    public static final byte CONST1 = 4;

    /** No driver at all. Sinks resolve through the cell pin default tie value. */
    public static final byte UNDRIVEN = 5;

    /** Two or more drivers. See {@link #getExtraDrivers(int)}. */
    public static final byte MULTI_DRIVER = 6;

    /** Maps a global hierarchical net identifier to its dense root identifier. */
    private final int[] netRoot;

    /**
     * Maps a dense leaf pin identifier directly to the root it is attached to,
     * or -1 if the pin is unconnected. Precomputed because cone construction
     * resolves this for every leaf input pin in the design; doing it by name
     * lookup would allocate a string per query.
     */
    private final int[] pinRoot;

    private final byte[] rootKind;

    /**
     * The single driving leaf pin of each root, as a dense global pin
     * identifier, or -1 when the root has no leaf driver. For a multi-driver
     * root this holds the first driver found; the rest are in
     * {@link #extraDrivers}.
     */
    private final int[] rootDriverPin;

    /** Additional driving leaf pins, keyed by root, only for multi-driver roots. */
    private final Map<Integer, int[]> extraDrivers;

    /**
     * For roots attached to the top cell's interface, the port bit index of that
     * attachment, or -1. Used to name top level ports as source terminals.
     */
    private final int[] rootTopPortBit;

    private final int rootCount;

    Connectivity(int[] netRoot, int[] pinRoot, int rootCount, byte[] rootKind, int[] rootDriverPin,
            int[] rootTopPortBit, Map<Integer, int[]> extraDrivers) {
        this.netRoot = netRoot;
        this.pinRoot = pinRoot;
        this.rootCount = rootCount;
        this.rootKind = rootKind;
        this.rootDriverPin = rootDriverPin;
        this.rootTopPortBit = rootTopPortBit;
        this.extraDrivers = extraDrivers;
    }

    /**
     * @return The dense root identifier of a hierarchical net, or -1 if the net
     *         identifier is out of range.
     */
    public int getRoot(int globalNet) {
        if (globalNet < 0 || globalNet >= netRoot.length) {
            return -1;
        }
        return netRoot[globalNet];
    }

    /**
     * @param globalPin A dense leaf pin identifier from
     *                  {@link FlatNetlist#getGlobalPin(int, int)}.
     * @return The root the pin is attached to, or -1 if the pin is unconnected.
     */
    public int getPinRoot(int globalPin) {
        if (globalPin < 0 || globalPin >= pinRoot.length) {
            return -1;
        }
        return pinRoot[globalPin];
    }

    public int getRootCount() {
        return rootCount;
    }

    public byte getRootKind(int root) {
        return rootKind[root];
    }

    /**
     * @return The dense global pin identifier of the root's driver, or -1 when
     *         the root is not driven by a leaf cell output.
     */
    public int getDriverPin(int root) {
        return rootDriverPin[root];
    }

    /**
     * @return Every driving leaf pin of a multi-driver root, including the one
     *         returned by {@link #getDriverPin(int)}, or null if the root has at
     *         most one driver.
     */
    public int[] getExtraDrivers(int root) {
        return extraDrivers.get(root);
    }

    /**
     * @return The number of leaf drivers on this root: 0, 1, or the length of
     *         the multi-driver list.
     */
    public int getDriverCount(int root) {
        int[] all = extraDrivers.get(root);
        if (all != null) {
            return all.length;
        }
        return rootDriverPin[root] >= 0 ? 1 : 0;
    }

    public int getTopPortBit(int root) {
        return rootTopPortBit[root];
    }

    /**
     * @return True if the root carries a compile time constant, whether from a
     *         tie cell or a constant named net.
     */
    public boolean isConstant(int root) {
        return rootKind[root] == CONST0 || rootKind[root] == CONST1;
    }

    /**
     * @return A count of roots by kind, keyed by the human readable kind name,
     *         for the classification report.
     */
    public Map<String, Integer> getRootKindCounts() {
        Map<String, Integer> counts = new TreeMap<>();
        for (int r = 0; r < rootCount; r++) {
            String name = kindName(rootKind[r]);
            counts.merge(name, 1, Integer::sum);
        }
        return counts;
    }

    public static String kindName(byte kind) {
        switch (kind) {
        case DRIVEN_BY_LEAF:
            return "driven by leaf";
        case TOP_INPUT:
            return "top level input";
        case TOP_INOUT:
            return "top level inout";
        case CONST0:
            return "constant 0";
        case CONST1:
            return "constant 1";
        case UNDRIVEN:
            return "undriven";
        case MULTI_DRIVER:
            return "multi-driver";
        default:
            return "unknown";
        }
    }
}

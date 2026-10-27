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

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

/**
 * An and-inverter graph: a directed acyclic graph in which every internal node
 * is a two input AND and every edge carries an inversion flag.
 *
 * <h2>Encoding</h2>
 *
 * A <i>literal</i> is {@code 2 * node + complement}, so inverting is
 * {@code lit ^ 1} and costs nothing. Node 0 is the constant node, giving
 * literal 0 for false and literal 1 for true. Each node stores its two fanin
 * literals in a flat {@code int} array, so a node costs eight bytes and there
 * are no per node objects; at the scale this tool targets an object based graph
 * would need gigabytes for the same content.
 *
 * <h2>Structural hashing</h2>
 *
 * Before creating an AND, the canonically ordered fanin pair is looked up in a
 * hash table and any existing node is reused. Identical subcircuits therefore
 * collapse onto the same node automatically.
 *
 * That property is what makes the whole comparison cheap. Both netlists are
 * built into a single graph over shared, name keyed primary inputs, so when the
 * reference and the implementation compute a cone the same way the two cone
 * literals are <i>equal integers</i> and the cone is proven equivalent with no
 * solver at all.
 *
 * <h2>Node ordering</h2>
 *
 * A node's fanins always exist before the node itself, so ascending node index
 * is a valid topological order. Simulation relies on this to evaluate the whole
 * graph in one linear sweep.
 */
public class Aig {

    /** Literal of the constant false. */
    public static final int FALSE = 0;

    /** Literal of the constant true. */
    public static final int TRUE = 1;

    private static final int CONST_MARK = -2;

    private static final int PI_MARK = -1;

    /** Two entries per node: the fanin literals, canonically ordered. */
    private int[] fanin;

    private int nodeCount;

    /** Open addressed table of {@code node + 1}, zero meaning empty. */
    private int[] strash;

    private int strashMask;

    private int strashCount;

    private int[] piNodes = new int[64];

    private String[] piNames = new String[64];

    private boolean[] piStable = new boolean[64];

    private int piCount;

    private final Map<String, Integer> piByName = new HashMap<>();

    private long andCallCount;

    public Aig() {
        this(1 << 16);
    }

    public Aig(int expectedNodes) {
        int capacity = Math.max(16, Integer.highestOneBit(Math.max(1, expectedNodes - 1)) * 2);
        fanin = new int[capacity * 2];
        fanin[0] = CONST_MARK;
        fanin[1] = 0;
        nodeCount = 1;
        int tableSize = Math.max(1024, Integer.highestOneBit(capacity) * 2);
        strash = new int[tableSize];
        strashMask = tableSize - 1;
    }

    /** @return The literal with its inversion flag flipped. */
    public static int not(int lit) {
        return lit ^ 1;
    }

    /** @return True if the literal is inverted relative to its node. */
    public static boolean isComplement(int lit) {
        return (lit & 1) != 0;
    }

    /** @return The node a literal refers to. */
    public static int nodeOf(int lit) {
        return lit >>> 1;
    }

    /** @return The non-inverted literal of a node. */
    public static int litOf(int node) {
        return node << 1;
    }

    /** @return True if the literal is one of the two constants. */
    public static boolean isConstant(int lit) {
        return (lit >>> 1) == 0;
    }

    /**
     * Creates, or returns, the primary input with the given name.
     *
     * Reusing an existing input by name is what lets the reference and the
     * implementation be expressed over the same variables: both netlists ask for
     * the terminal by its canonical key and receive the same literal.
     *
     * @param name   The canonical key, from {@link TerminalKey}.
     * @param stable Whether the key is guaranteed to mean the same thing in both
     *               netlists. An unstable input taints every cone that reaches
     *               it, which can then only be reported inconclusive.
     * @return The literal of the input.
     */
    public int newInput(String name, boolean stable) {
        Integer existing = piByName.get(name);
        if (existing != null) {
            return existing;
        }
        int node = allocateNode();
        fanin[2 * node] = PI_MARK;
        fanin[2 * node + 1] = piCount;
        if (piCount == piNodes.length) {
            piNodes = Arrays.copyOf(piNodes, piCount * 2);
            piNames = Arrays.copyOf(piNames, piCount * 2);
            piStable = Arrays.copyOf(piStable, piCount * 2);
        }
        piNodes[piCount] = node;
        piNames[piCount] = name;
        piStable[piCount] = stable;
        piCount++;
        int lit = litOf(node);
        piByName.put(name, lit);
        return lit;
    }

    /** @return The literal of an existing input, or null if there is none. */
    public Integer findInput(String name) {
        return piByName.get(name);
    }

    /**
     * The one structural operation. Applies the trivial simplifications, orders
     * the pair canonically, then reuses an identical existing node if there is
     * one.
     */
    public int and(int a, int b) {
        andCallCount++;
        if (a == FALSE || b == FALSE) {
            return FALSE;
        }
        if (a == TRUE) {
            return b;
        }
        if (b == TRUE) {
            return a;
        }
        if (a == b) {
            return a;
        }
        if (a == (b ^ 1)) {
            return FALSE;
        }
        int lo = Math.min(a, b);
        int hi = Math.max(a, b);
        int slot = findSlot(lo, hi);
        if (strash[slot] != 0) {
            return litOf(strash[slot] - 1);
        }
        int node = allocateNode();
        fanin[2 * node] = lo;
        fanin[2 * node + 1] = hi;
        if ((strashCount + 1) * 2 >= strash.length) {
            growStrash();
            slot = findSlot(lo, hi);
        }
        strash[slot] = node + 1;
        strashCount++;
        return litOf(node);
    }

    public int or(int a, int b) {
        return not(and(not(a), not(b)));
    }

    public int nand(int a, int b) {
        return not(and(a, b));
    }

    public int nor(int a, int b) {
        return not(or(a, b));
    }

    /** Exclusive or, built from three AND nodes. */
    public int xor(int a, int b) {
        if (a == FALSE) {
            return b;
        }
        if (b == FALSE) {
            return a;
        }
        if (a == TRUE) {
            return not(b);
        }
        if (b == TRUE) {
            return not(a);
        }
        if (a == b) {
            return FALSE;
        }
        if (a == (b ^ 1)) {
            return TRUE;
        }
        return or(and(a, not(b)), and(not(a), b));
    }

    public int xnor(int a, int b) {
        return not(xor(a, b));
    }

    /**
     * Selects between two literals. The special cases are worth handling here
     * rather than leaving to {@link #and(int, int)}, because they are extremely
     * common when lowering a look up table and each one saves nodes.
     *
     * @return {@code select ? whenTrue : whenFalse}
     */
    public int mux(int select, int whenTrue, int whenFalse) {
        if (whenTrue == whenFalse) {
            return whenTrue;
        }
        if (select == TRUE) {
            return whenTrue;
        }
        if (select == FALSE) {
            return whenFalse;
        }
        if (whenTrue == TRUE) {
            return or(select, whenFalse);
        }
        if (whenTrue == FALSE) {
            return and(not(select), whenFalse);
        }
        if (whenFalse == TRUE) {
            return or(not(select), whenTrue);
        }
        if (whenFalse == FALSE) {
            return and(select, whenTrue);
        }
        if (whenTrue == (whenFalse ^ 1)) {
            return xor(select, whenFalse);
        }
        return or(and(select, whenTrue), and(not(select), whenFalse));
    }

    private int allocateNode() {
        if (2 * nodeCount + 2 > fanin.length) {
            fanin = Arrays.copyOf(fanin, fanin.length * 2);
        }
        return nodeCount++;
    }

    private int findSlot(int lo, int hi) {
        int slot = hash(lo, hi) & strashMask;
        while (true) {
            int entry = strash[slot];
            if (entry == 0) {
                return slot;
            }
            int node = entry - 1;
            if (fanin[2 * node] == lo && fanin[2 * node + 1] == hi) {
                return slot;
            }
            slot = (slot + 1) & strashMask;
        }
    }

    private void growStrash() {
        int[] old = strash;
        strash = new int[old.length * 2];
        strashMask = strash.length - 1;
        for (int entry : old) {
            if (entry == 0) {
                continue;
            }
            int node = entry - 1;
            int slot = hash(fanin[2 * node], fanin[2 * node + 1]) & strashMask;
            while (strash[slot] != 0) {
                slot = (slot + 1) & strashMask;
            }
            strash[slot] = entry;
        }
    }

    private static int hash(int lo, int hi) {
        int h = lo * 0x9E3779B1;
        h ^= hi * 0x85EBCA6B;
        h ^= h >>> 15;
        h *= 0xC2B2AE35;
        return (h ^ (h >>> 13)) & 0x7FFFFFFF;
    }

    public int getNodeCount() {
        return nodeCount;
    }

    public int getAndCount() {
        return nodeCount - 1 - piCount;
    }

    public int getPiCount() {
        return piCount;
    }

    /** @return The number of times an AND was requested, including reuses. */
    public long getAndCallCount() {
        return andCallCount;
    }

    public boolean isPi(int node) {
        return fanin[2 * node] == PI_MARK;
    }

    public boolean isAnd(int node) {
        return node != 0 && fanin[2 * node] != PI_MARK;
    }

    public int getFanin0(int node) {
        return fanin[2 * node];
    }

    public int getFanin1(int node) {
        return fanin[2 * node + 1];
    }

    /** @return The dense input index of a primary input node, or -1. */
    public int getPiIndex(int node) {
        return fanin[2 * node] == PI_MARK ? fanin[2 * node + 1] : -1;
    }

    public String getPiName(int piIndex) {
        return piNames[piIndex];
    }

    public boolean isPiStable(int piIndex) {
        return piStable[piIndex];
    }

    public int getPiNode(int piIndex) {
        return piNodes[piIndex];
    }

    /** @return The name of the input a literal refers to, or null. */
    public String getInputName(int lit) {
        int node = nodeOf(lit);
        int pi = getPiIndex(node);
        return pi < 0 ? null : piNames[pi];
    }

    /**
     * @return Approximate resident size in bytes of the graph, for the report's
     *         memory line.
     */
    public long getApproximateBytes() {
        return (long) fanin.length * 4 + (long) strash.length * 4;
    }
}

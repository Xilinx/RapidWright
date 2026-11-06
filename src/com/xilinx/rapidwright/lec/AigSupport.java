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

/**
 * Finds the primary inputs and the nodes a cone reaches.
 *
 * The traversal marks nodes with a per query stamp rather than clearing a
 * visited array, so the cost of a query is proportional to the size of the cone
 * rather than to the size of the graph. That matters because the graph holds
 * both netlists while an individual cone is usually tiny.
 *
 * Note that this is <i>structural</i> reachability, which over-approximates the
 * variables a function actually depends on. A cone can reach an input that
 * constant folding has made irrelevant. Where the distinction matters, the
 * report says which of the two it is showing.
 */
public class AigSupport {

    private final Aig aig;

    private final int[] stamp;

    private int epoch;

    private int[] stack = new int[1024];

    public AigSupport(Aig aig) {
        this.aig = aig;
        this.stamp = new int[aig.getNodeCount()];
    }

    /**
     * @return The dense indices of the primary inputs the cone reaches, sorted
     *         ascending.
     */
    public int[] support(int rootLit) {
        return support(new int[] { rootLit });
    }

    /** @return The primary inputs reached by any of the given cone roots. */
    public int[] support(int[] rootLits) {
        epoch++;
        int found = 0;
        int[] inputs = new int[16];
        int depth = 0;
        for (int lit : rootLits) {
            depth = pushNode(Aig.nodeOf(lit), depth);
        }
        while (depth > 0) {
            int node = stack[--depth];
            if (aig.isPi(node)) {
                if (found == inputs.length) {
                    inputs = Arrays.copyOf(inputs, found * 2);
                }
                inputs[found++] = aig.getPiIndex(node);
                continue;
            }
            if (node == 0) {
                continue;
            }
            depth = pushNode(Aig.nodeOf(aig.getFanin0(node)), depth);
            depth = pushNode(Aig.nodeOf(aig.getFanin1(node)), depth);
        }
        int[] result = Arrays.copyOf(inputs, found);
        Arrays.sort(result);
        return result;
    }

    /** @return The number of AND nodes in the cone, used to size solver calls. */
    public int coneSize(int[] rootLits) {
        epoch++;
        int count = 0;
        int depth = 0;
        for (int lit : rootLits) {
            depth = pushNode(Aig.nodeOf(lit), depth);
        }
        while (depth > 0) {
            int node = stack[--depth];
            if (node == 0 || aig.isPi(node)) {
                continue;
            }
            count++;
            depth = pushNode(Aig.nodeOf(aig.getFanin0(node)), depth);
            depth = pushNode(Aig.nodeOf(aig.getFanin1(node)), depth);
        }
        return count;
    }

    /**
     * Collects every node of a cone in ascending index order, which is a valid
     * topological order and therefore the order a solver encoding needs.
     */
    public int[] coneNodes(int[] rootLits) {
        epoch++;
        int[] nodes = new int[64];
        int count = 0;
        int depth = 0;
        for (int lit : rootLits) {
            depth = pushNode(Aig.nodeOf(lit), depth);
        }
        while (depth > 0) {
            int node = stack[--depth];
            if (count == nodes.length) {
                nodes = Arrays.copyOf(nodes, count * 2);
            }
            nodes[count++] = node;
            if (node == 0 || aig.isPi(node)) {
                continue;
            }
            depth = pushNode(Aig.nodeOf(aig.getFanin0(node)), depth);
            depth = pushNode(Aig.nodeOf(aig.getFanin1(node)), depth);
        }
        int[] result = Arrays.copyOf(nodes, count);
        Arrays.sort(result);
        return result;
    }

    private int pushNode(int node, int depth) {
        if (stamp[node] == epoch) {
            return depth;
        }
        stamp[node] = epoch;
        if (depth == stack.length) {
            stack = Arrays.copyOf(stack, depth * 2);
        }
        stack[depth++] = node;
        return depth;
    }

    /** @return The names of the given primary inputs, for reporting. */
    public String[] names(int[] piIndices) {
        String[] result = new String[piIndices.length];
        for (int i = 0; i < piIndices.length; i++) {
            result[i] = aig.getPiName(piIndices[i]);
        }
        return result;
    }

    /**
     * @return True if any of the inputs is unmatchable between the two netlists,
     *         which makes a failing comparison inconclusive rather than a
     *         genuine difference.
     */
    public boolean isTainted(int[] piIndices) {
        for (int pi : piIndices) {
            if (!aig.isPiStable(pi)) {
                return true;
            }
        }
        return false;
    }

    /** @return The unmatchable inputs among the given set, for the report. */
    public String[] taintedNames(int[] piIndices) {
        int count = 0;
        for (int pi : piIndices) {
            if (!aig.isPiStable(pi)) {
                count++;
            }
        }
        String[] result = new String[count];
        int at = 0;
        for (int pi : piIndices) {
            if (!aig.isPiStable(pi)) {
                result[at++] = aig.getPiName(pi);
            }
        }
        return result;
    }
}

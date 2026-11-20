/*
 *
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

package com.xilinx.rapidwright.timing.clock;

import java.util.Arrays;
import java.util.BitSet;

import com.xilinx.rapidwright.timing.family.Corner;

/**
 * A forest of clock path nodes (clock sources, routing nodes, buffers, cell pins) with the delay of
 * each node from its parent at every corner, and the cumulative delay (insertion delay) from its
 * root. A node is always added after its parent, so a forward sweep computes the cumulative
 * delays ({@link #finish()}).
 * <p>
 * Clock pessimism removal (CRPR) uses the lowest common ancestor (LCA) of the launch and capture
 * clock pins' nodes, found by climbing from the deeper one to the other's depth and then from both:
 * the setup credit is the difference between the SLOW_MAX and SLOW_MIN insertion delays of the
 * LCA (the late launch and early capture clocks share the path to it), the hold credit that between
 * FAST_MAX and FAST_MIN. For an LCA marked to be credited at its start
 * ({@link #setCreditAtStart(int)}), the credit is that of its parent, unless the LCA is the
 * parent of both the launch and the capture node.
 */
final class ClockTree {

    /** Parent of a root node */
    static final int NO_PARENT = -1;

    private int numNodes;
    private int[] parent = new int[64];
    private int[] depth = new int[64];
    /** [corner][node]: delay from the parent */
    private float[][] delay = new float[ClockDelayParams.NUM_CORNERS][64];
    /** [corner][node]: cumulative delay from the root */
    private float[][] cum = new float[ClockDelayParams.NUM_CORNERS][0];
    /** The nodes whose CRPR credit, as an LCA, is that of their parent (see {@link #setCreditAtStart(int)}) */
    private final BitSet creditAtStart = new BitSet();
    private boolean finished;

    /**
     * Adds a node.
     * @param parentNode Its parent (added before), or {@link #NO_PARENT} for a root.
     * @param delays Its delay from its parent per corner ordinal, or null for 0.
     * @return Its id.
     */
    int addNode(int parentNode, float[] delays) {
        if (parentNode >= numNodes) {
            throw new IllegalArgumentException("The parent of a node must be added before it");
        }
        if (numNodes == parent.length) {
            int n = numNodes * 2;
            parent = Arrays.copyOf(parent, n);
            depth = Arrays.copyOf(depth, n);
            for (int c = 0; c < delay.length; c++) {
                delay[c] = Arrays.copyOf(delay[c], n);
            }
        }
        int id = numNodes++;
        parent[id] = parentNode;
        depth[id] = parentNode < 0 ? 0 : depth[parentNode] + 1;
        for (int c = 0; c < delay.length; c++) {
            delay[c][id] = delays == null ? 0f : delays[c];
        }
        finished = false;
        return id;
    }

    /**
     * Marks a node whose CRPR credit, when it is the LCA of a launch and a capture node, is that of its
     * parent (the clock arrival at its start): e.g. a clock routing node where the clock paths branch,
     * whose delay is that of the clock buffer driving it, on UltraScale+. Not when the node is the parent
     * of both the launch and the capture node (the routing node of the site pin of both).
     */
    void setCreditAtStart(int node) {
        creditAtStart.set(node);
    }

    /** Sets the delay of a node from its parent at a corner (call {@link #finish()} afterwards). */
    void setDelay(int node, int corner, float d) {
        delay[corner][node] = d;
        finished = false;
    }

    float getDelay(int node, int corner) {
        return delay[corner][node];
    }

    /** Computes the cumulative delays. */
    void finish() {
        for (int c = 0; c < delay.length; c++) {
            float[] cc = new float[numNodes];
            float[] d = delay[c];
            for (int i = 0; i < numNodes; i++) {
                int p = parent[i];
                cc[i] = p < 0 ? d[i] : cc[p] + d[i];
            }
            cum[c] = cc;
        }
        finished = true;
    }

    private void ensureFinished() {
        if (!finished) {
            finish();
        }
    }

    int getParent(int node) {
        return parent[node];
    }

    int getNumNodes() {
        return numNodes;
    }

    int getDepth(int node) {
        return depth[node];
    }

    /** @return The cumulative delay (insertion delay) of a node at a corner, in ps. */
    float getInsertion(int node, Corner corner) {
        ensureFinished();
        return cum[corner.ordinal()][node];
    }

    /**
     * Gets the lowest common ancestor of two nodes.
     * @return The LCA, or {@link #NO_PARENT} if the nodes are in different trees.
     */
    int getLca(int a, int b) {
        if (a == b) {
            return a;
        }
        if (a < 0 || b < 0) {
            return NO_PARENT;
        }
        while (depth[a] > depth[b]) {
            a = parent[a];
        }
        while (depth[b] > depth[a]) {
            b = parent[b];
        }
        while (a != b) {
            a = parent[a];
            b = parent[b];
            if (a < 0 || b < 0) {
                return NO_PARENT;
            }
        }
        return a;
    }

    /**
     * Gets the node whose insertion delays give the CRPR credit of a launch and a capture node: their LCA,
     * or its parent if the LCA is credited at its start (see {@link #setCreditAtStart(int)}).
     * @return The node, or {@link #NO_PARENT} if none.
     */
    int getCreditNode(int launchNode, int captureNode) {
        int lca = getLca(launchNode, captureNode);
        if (lca < 0 || !creditAtStart.get(lca) || parent[launchNode] == lca && parent[captureNode] == lca) {
            return lca;
        }
        return parent[lca];
    }

    /**
     * Gets the setup CRPR credit of a launch and a capture clock pin node: SLOW_MAX - SLOW_MIN
     * insertion of their LCA (see {@link #getCreditNode(int, int)}; 0 if none).
     */
    float getSetupCredit(int launchNode, int captureNode) {
        int lca = getCreditNode(launchNode, captureNode);
        if (lca < 0) {
            return 0f;
        }
        ensureFinished();
        return cum[Corner.SLOW_MAX.ordinal()][lca] - cum[Corner.SLOW_MIN.ordinal()][lca];
    }

    /**
     * Gets the hold CRPR credit of a launch and a capture clock pin node: FAST_MAX - FAST_MIN
     * insertion of their LCA (see {@link #getCreditNode(int, int)}; 0 if none).
     */
    float getHoldCredit(int launchNode, int captureNode) {
        int lca = getCreditNode(launchNode, captureNode);
        if (lca < 0) {
            return 0f;
        }
        ensureFinished();
        return cum[Corner.FAST_MAX.ordinal()][lca] - cum[Corner.FAST_MIN.ordinal()][lca];
    }
}

/*
 *
 * Copyright (c) 2024 The Chinese University of Hong Kong.
 * Copyright (c) 2022-2024, Advanced Micro Devices, Inc.
 * All rights reserved.
 *
 * Author: Wenhao Lin, The Chinese University of Hong Kong.
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

package com.xilinx.rapidwright.rwroute;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

// partitioning-tree-related classes ->
public class CUFRpartitionTree {
    /* The bounding box of a partition */
    public class PartitionBBox {
        public int xMin;
        public int xMax;
        public int yMin;
        public int yMax;

        public PartitionBBox(int xMin, int xMax, int yMin, int yMax) {
            this.xMin = xMin;
            this.xMax = xMax;
            this.yMin = yMin;
            this.yMax = yMax;
        }

        @Override
        public String toString() {
            return "bbox: [( " + xMin + ", " + yMin + " ), -> ( " + xMax + ", " + yMax + " )]";
        }
    }

    /**
     * The data structure of a tree node of RPTT.
     */
    public class PartitionTreeNode {
        /**
         * Bounding box of this node.
         * All bounding boxes of the connections in this node should be within this box.
         */
        private PartitionBBox bbox;
        /* The union of all connections contained in the following three sub-trees */
        List<Connection> connections;
        /**
         * Middle subtree
         * This subtree contains the connections crossing the chosen cutline,
         * and it would be routed prior to the other two.
         */
        PartitionTreeNode middle;
        /* Two subtrees for the two sub-partitions */
        PartitionTreeNode left;
        PartitionTreeNode right;

        public PartitionTreeNode() {
            connections = null;
            left = null;
            right = null;
            bbox = null;
        }
    }

    /* The direction in which the cutline cuts the partition */
    public enum PartitionAxis {
        X,
        Y
    }

    public PartitionTreeNode root;
    private PartitionBBox bbox;

    public CUFRpartitionTree(List<Connection> connections, int xMax, int yMax) {
        bbox = new PartitionBBox(0, xMax, 0, yMax);
        root = new PartitionTreeNode();
        root.bbox = bbox;
        root.connections = connections;
        build(root);
    }

    private void build(PartitionTreeNode cur) {
        // sort the connections for routing
        Collections.sort(cur.connections);

        // find the best cutline ->
        // THIS PART CORRESPONDS TO Algorithm 2: Balance-driven Cutline IN THE PAPER ->

        int W = cur.bbox.xMax - cur.bbox.xMin + 1;
        int H = cur.bbox.yMax - cur.bbox.yMin + 1;

        /*
         * |xTotalBefore[x] - xTotalAfter[x]| is the difference in the number of connections between the
         * two sub-partitions when the cutline is positioned between locations x and (x+1) on the X-axis.
         *
         * So as to yTotalBefore[] and yTotalAfter[]
         */

        int[] xTotalBefore = new int[W - 1];
        int[] xTotalAfter = new int[W - 1];
        int[] yTotalBefore = new int[H - 1];
        int[] yTotalAfter = new int[H - 1];

        for (Connection connection : cur.connections) {
            int xStart = Math.max(cur.bbox.xMin, clampX(connection.getXMinBB())) - cur.bbox.xMin;
            int xEnd = Math.min(cur.bbox.xMax, clampX(connection.getXMaxBB())) - cur.bbox.xMin;
            assert (xStart >= 0);
            for (int x = xStart; x < W - 1; x++) {
                xTotalBefore[x]++;
            }
            for (int x = 0; x < xEnd; x++) {
                xTotalAfter[x]++;
            }

            int yStart = Math.max(cur.bbox.yMin, clampY(connection.getYMinBB())) - cur.bbox.yMin;
            int yEnd = Math.min(cur.bbox.yMax, clampY(connection.getYMaxBB())) - cur.bbox.yMin;
            assert (yStart >= 0);
            for (int y = yStart; y < H - 1; y++) {
                yTotalBefore[y]++;
            }
            for (int y = 0; y < yEnd; y++) {
                yTotalAfter[y]++;
            }
        }

        double bestScore = Double.MAX_VALUE;

        /* The position of the optimal cutline */
        double bestPos = Double.NaN;

        /* The direction of the optimal cutline */
        PartitionAxis bestAxis = PartitionAxis.X;

        int maxXBefore = xTotalBefore[W - 2];
        int maxXAfter = xTotalAfter[0];
        for (int x = 0; x < W - 1; x++) {
            int before = xTotalBefore[x];
            int after = xTotalAfter[x];
            if (before == maxXBefore || after == maxXAfter)
                continue;
            double score = (double) Math.abs(xTotalBefore[x] - xTotalAfter[x]) / Math.max(xTotalBefore[x], xTotalAfter[x]);
            if (score < bestScore) {
                bestScore = score;
                bestPos = cur.bbox.xMin + x + 0.5;
                bestAxis = PartitionAxis.X;
            }
        }

        int maxYBefore = yTotalBefore[H - 2];
        int maxYAfter = yTotalAfter[0];
        for (int y = 0; y < H - 1; y++) {
            int before = yTotalBefore[y];
            int after = yTotalAfter[y];
            if (before == maxYBefore || after == maxYAfter)
                continue;
            double score = (double) Math.abs(yTotalBefore[y] - yTotalAfter[y]) / Math.max(yTotalBefore[y], yTotalAfter[y]);
            if (score < bestScore) {
                bestScore = score;
                bestPos = cur.bbox.yMin + y + 0.5;
                bestAxis = PartitionAxis.Y;
            }
        }

        // THIS PART CORRESPONDS TO Algorithm 2: Balance-driven Cutline IN THE PAPER <-
        // find the best cutline <-

        // THIS PART CORRESPONDS TO line 13 of Algorithm 1: RPTT-based Parallel Routing ->

        /*
         * If bestPos is never updated, meaning that a cutline that can divide the original partition into two non-empty partitions cannot be found,
         * then the recursion to build the subtrees will not continue, and all three subtrees will be null.
         */
        if (Double.isNaN(bestPos))
            return;

        // THIS PART CORRESPONDS TO line 13 of Algorithm 1: RPTT-based Parallel Routing <-

        // recursively build tree ->
        // THIS PART CORRESPONDS TO line 8-12 of Algorithm 1: RPTT-based Parallel Routing ->
        cur.left = new PartitionTreeNode();
        cur.left.connections = new ArrayList<>();
        cur.middle = new PartitionTreeNode();
        cur.middle.connections = new ArrayList<>();
        cur.right = new PartitionTreeNode();
        cur.right.connections = new ArrayList<>();

        if (bestAxis == PartitionAxis.X) {
            for (Connection connection : cur.connections) {
                if (clampX(connection.getXMaxBB()) < bestPos) {
                    cur.left.connections.add(connection);
                } else if (clampX(connection.getXMinBB()) > bestPos) {
                    cur.right.connections.add(connection);
                } else {
                    /* Those connections crossing the cutline will go into the middle sub-tree */
                    cur.middle.connections.add(connection);
                }
            }
            cur.left.bbox = new PartitionBBox(cur.bbox.xMin, (int) Math.floor(bestPos), cur.bbox.yMin, cur.bbox.yMax);
            cur.right.bbox = new PartitionBBox((int) Math.floor(bestPos) + 1, cur.bbox.xMax, cur.bbox.yMin, cur.bbox.yMax);
            cur.middle.bbox = cur.bbox;
        } else {
            assert (bestAxis == PartitionAxis.Y);
            for (Connection connection : cur.connections) {
                if (clampY(connection.getYMaxBB()) < bestPos) {
                    cur.left.connections.add(connection);
                } else if (clampY(connection.getYMinBB()) > bestPos) {
                    cur.right.connections.add(connection);
                } else {
                    /* Those connections crossing the cutline will go into the middle sub-tree */
                    cur.middle.connections.add(connection);
                }
            }
            cur.left.bbox = new PartitionBBox(cur.bbox.xMin, cur.bbox.xMax, cur.bbox.yMin, (int) Math.floor(bestPos));
            cur.right.bbox = new PartitionBBox(cur.bbox.xMin, cur.bbox.xMax, (int) Math.floor(bestPos) + 1, cur.bbox.yMax);
            cur.middle.bbox = cur.bbox;
        }
        assert (cur.left.connections.size() > 0 && cur.right.connections.size() > 0);
        build(cur.left);
        build(cur.right);
        if (cur.middle.connections.size() > 0) {
            build(cur.middle);
        } else {
            cur.middle = null;
        }
        // THIS PART CORRESPONDS TO line 8-12 of Algorithm 1: RPTT-based Parallel Routing <-
        // recursively build tree <-
    }

    /*
     * Some connections, when expanding their bounding boxes during initialization,
     * may cause the bounding boxes to exceed the range of the FPGA device, so they need to be clamped.
     */
    private int clampX(int x) {
        return Math.min(Math.max(x, bbox.xMin), bbox.xMax);
    }

    private int clampY(int y) {
        return Math.min(Math.max(y, bbox.yMin), bbox.yMax);
    }
}

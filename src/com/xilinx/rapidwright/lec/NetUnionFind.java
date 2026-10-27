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
 * Disjoint set over dense integer net identifiers, using union by size and path
 * halving.
 *
 * This is how nets on either side of a hierarchy boundary are merged into a
 * single electrical node. It is deliberately primitive-array based: at the
 * scale this tool targets there can be a few million hierarchical nets, and an
 * object or map based structure costs an order of magnitude more memory.
 *
 * Unlike RapidWright's own net alias traversal, merging here is direction
 * agnostic, so a net with several drivers is merged normally rather than
 * raising an error. Driver multiplicity is a separate concern, resolved in
 * {@link ConnectivityBuilder}.
 */
public class NetUnionFind {

    private final int[] parent;

    private final int[] size;

    private int setCount;

    public NetUnionFind(int n) {
        parent = new int[n];
        size = new int[n];
        for (int i = 0; i < n; i++) {
            parent[i] = i;
            size[i] = 1;
        }
        setCount = n;
    }

    /**
     * Finds the representative of the set containing x, halving the path to the
     * root as it goes.
     *
     * @param x A net identifier.
     * @return The representative net identifier of x's set.
     */
    public int find(int x) {
        while (parent[x] != x) {
            parent[x] = parent[parent[x]];
            x = parent[x];
        }
        return x;
    }

    /**
     * Merges the sets containing a and b. The larger set absorbs the smaller so
     * that tree depth stays bounded.
     *
     * @return The representative of the merged set.
     */
    public int union(int a, int b) {
        int rootA = find(a);
        int rootB = find(b);
        if (rootA == rootB) {
            return rootA;
        }
        if (size[rootA] < size[rootB]) {
            int swap = rootA;
            rootA = rootB;
            rootB = swap;
        }
        parent[rootB] = rootA;
        size[rootA] += size[rootB];
        setCount--;
        return rootA;
    }

    /**
     * @return True if a and b are in the same set.
     */
    public boolean connected(int a, int b) {
        return find(a) == find(b);
    }

    /**
     * @return The number of elements this structure was built over.
     */
    public int getElementCount() {
        return parent.length;
    }

    /**
     * @return The number of distinct sets, i.e. the number of electrical nodes.
     */
    public int getSetCount() {
        return setCount;
    }

    /**
     * Assigns a dense identifier in the range 0 to {@link #getSetCount()}-1 to
     * every set.
     *
     * Sets are numbered by their smallest member, ascending. Numbering by the
     * internal representative instead would not be stable: union by size picks
     * whichever tree happens to be larger, so the same partition built in a
     * different order would come out numbered differently. Since these
     * identifiers order the report, that would make two runs over the same
     * design produce different output, which in an equivalence checker destroys
     * trust faster than almost anything else.
     *
     * @return An array indexed by element, giving the dense identifier of the
     *         set that element belongs to.
     */
    public int[] compact() {
        int[] rootToDense = new int[parent.length];
        Arrays.fill(rootToDense, -1);
        int next = 0;
        for (int i = 0; i < parent.length; i++) {
            int root = find(i);
            if (rootToDense[root] < 0) {
                rootToDense[root] = next++;
            }
        }
        int[] dense = new int[parent.length];
        for (int i = 0; i < parent.length; i++) {
            dense[i] = rootToDense[find(i)];
        }
        return dense;
    }
}

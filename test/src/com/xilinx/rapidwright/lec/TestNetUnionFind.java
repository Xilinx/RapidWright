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

import java.util.HashSet;
import java.util.Random;
import java.util.Set;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

/**
 * Checks the disjoint set that merges hierarchical nets into electrical nodes.
 */
public class TestNetUnionFind {

    @Test
    public void testStartsFullyDisjoint() {
        NetUnionFind uf = new NetUnionFind(10);
        Assertions.assertEquals(10, uf.getElementCount());
        Assertions.assertEquals(10, uf.getSetCount());
        for (int i = 0; i < 10; i++) {
            Assertions.assertEquals(i, uf.find(i));
        }
    }

    @Test
    public void testUnionIsTransitiveAndSymmetric() {
        NetUnionFind uf = new NetUnionFind(6);
        uf.union(0, 1);
        uf.union(1, 2);
        Assertions.assertTrue(uf.connected(0, 2));
        Assertions.assertTrue(uf.connected(2, 0));
        Assertions.assertFalse(uf.connected(0, 3));
        Assertions.assertEquals(4, uf.getSetCount());
    }

    @Test
    public void testRepeatedUnionDoesNotChangeSetCount() {
        NetUnionFind uf = new NetUnionFind(4);
        uf.union(0, 1);
        int after = uf.getSetCount();
        uf.union(0, 1);
        uf.union(1, 0);
        Assertions.assertEquals(after, uf.getSetCount());
    }

    /**
     * The dense numbering must cover exactly the range zero to set count minus
     * one, and two elements must share an identifier precisely when they are in
     * the same set.
     */
    @Test
    public void testCompactProducesDenseConsistentIds() {
        NetUnionFind uf = new NetUnionFind(8);
        uf.union(0, 3);
        uf.union(3, 6);
        uf.union(1, 4);

        int[] dense = uf.compact();
        Set<Integer> ids = new HashSet<>();
        for (int id : dense) {
            ids.add(id);
        }
        Assertions.assertEquals(uf.getSetCount(), ids.size());
        for (int id : dense) {
            Assertions.assertTrue(id >= 0 && id < uf.getSetCount(), "Identifier out of range: " + id);
        }
        for (int a = 0; a < 8; a++) {
            for (int b = 0; b < 8; b++) {
                Assertions.assertEquals(uf.connected(a, b), dense[a] == dense[b],
                        "Dense identifiers disagree with the sets for " + a + " and " + b);
            }
        }
    }

    /**
     * The numbering must not depend on the order unions happened in, otherwise
     * two runs of the tool over the same design would produce different reports.
     */
    @Test
    public void testCompactIsDeterministic() {
        int[] first = buildAndCompact(new int[][] { { 0, 5 }, { 5, 9 }, { 2, 7 } });
        int[] second = buildAndCompact(new int[][] { { 2, 7 }, { 9, 5 }, { 5, 0 } });
        Assertions.assertArrayEquals(first, second);
    }

    private static int[] buildAndCompact(int[][] unions) {
        NetUnionFind uf = new NetUnionFind(12);
        for (int[] pair : unions) {
            uf.union(pair[0], pair[1]);
        }
        return uf.compact();
    }

    /**
     * Path halving must not lose track of any element. A long chain is the shape
     * that would expose a broken compression step.
     */
    @Test
    public void testDeepChain() {
        int n = 100000;
        NetUnionFind uf = new NetUnionFind(n);
        for (int i = 1; i < n; i++) {
            uf.union(i - 1, i);
        }
        Assertions.assertEquals(1, uf.getSetCount());
        int root = uf.find(0);
        for (int i = 0; i < n; i++) {
            Assertions.assertEquals(root, uf.find(i));
        }
    }

    /** Random unions cross-checked against a straightforward reference. */
    @Test
    public void testAgainstReferenceImplementation() {
        int n = 400;
        NetUnionFind uf = new NetUnionFind(n);
        int[] reference = new int[n];
        for (int i = 0; i < n; i++) {
            reference[i] = i;
        }
        Random random = new Random(12345);
        for (int step = 0; step < 2000; step++) {
            int a = random.nextInt(n);
            int b = random.nextInt(n);
            uf.union(a, b);
            int from = reference[a];
            int to = reference[b];
            for (int i = 0; i < n; i++) {
                if (reference[i] == from) {
                    reference[i] = to;
                }
            }
        }
        for (int a = 0; a < n; a++) {
            for (int b = 0; b < n; b++) {
                Assertions.assertEquals(reference[a] == reference[b], uf.connected(a, b),
                        "Disagreement on " + a + " and " + b);
            }
        }
    }
}

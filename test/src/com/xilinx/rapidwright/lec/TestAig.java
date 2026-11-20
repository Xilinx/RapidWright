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

import java.util.Random;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

/**
 * Checks the and-inverter graph, whose two properties the whole tool rests on:
 * that a node reused for an identical pair of fanins really is the same node,
 * and that the values it computes are correct.
 *
 * If structural hashing ever failed to reuse a node the tool would still be
 * correct, merely slower and more dependent on the solver. If it ever reused a
 * node it should not have, every verdict would be suspect. The determinism
 * tests guard the first and the evaluation tests the second.
 */
public class TestAig {

    @Test
    public void testConstantsAndInversion() {
        Aig aig = new Aig();
        Assertions.assertEquals(Aig.TRUE, Aig.not(Aig.FALSE));
        Assertions.assertEquals(Aig.FALSE, Aig.not(Aig.TRUE));
        Assertions.assertTrue(Aig.isConstant(Aig.FALSE));
        Assertions.assertTrue(Aig.isConstant(Aig.TRUE));

        int a = aig.newInput("a", true);
        Assertions.assertFalse(Aig.isConstant(a));
        Assertions.assertEquals(a, Aig.not(Aig.not(a)));
        Assertions.assertTrue(aig.isPi(Aig.nodeOf(a)));
    }

    /** The simplifications that must happen before a node is ever created. */
    @Test
    public void testTrivialSimplifications() {
        Aig aig = new Aig();
        int a = aig.newInput("a", true);
        int before = aig.getAndCount();

        Assertions.assertEquals(Aig.FALSE, aig.and(a, Aig.FALSE));
        Assertions.assertEquals(a, aig.and(a, Aig.TRUE));
        Assertions.assertEquals(a, aig.and(a, a));
        Assertions.assertEquals(Aig.FALSE, aig.and(a, Aig.not(a)));
        Assertions.assertEquals(Aig.FALSE, aig.xor(a, a));
        Assertions.assertEquals(Aig.TRUE, aig.xor(a, Aig.not(a)));
        Assertions.assertEquals(a, aig.mux(Aig.TRUE, a, Aig.FALSE));
        Assertions.assertEquals(a, aig.mux(a, Aig.TRUE, Aig.FALSE));

        Assertions.assertEquals(before, aig.getAndCount(),
                "None of these should have created a node");
    }

    /** An identical pair of fanins must yield the identical node, in any order. */
    @Test
    public void testStructuralHashing() {
        Aig aig = new Aig();
        int a = aig.newInput("a", true);
        int b = aig.newInput("b", true);

        int first = aig.and(a, b);
        int second = aig.and(b, a);
        Assertions.assertEquals(first, second, "AND must be commutative in the hash");
        Assertions.assertEquals(1, aig.getAndCount());

        int negated = aig.and(a, Aig.not(b));
        Assertions.assertNotEquals(first, negated,
                "Different polarities must not share a node");
        Assertions.assertEquals(2, aig.getAndCount());
    }

    /** An input asked for twice by name is the same variable, which is what lets
     * the two netlists share terminals. */
    @Test
    public void testInputsAreSharedByName() {
        Aig aig = new Aig();
        int first = aig.newInput("PART_1/ff/Q", true);
        int second = aig.newInput("PART_1/ff/Q", true);
        Assertions.assertEquals(first, second);
        Assertions.assertEquals(1, aig.getPiCount());
        Assertions.assertEquals(first, aig.findInput("PART_1/ff/Q").intValue());
        Assertions.assertNull(aig.findInput("absent"));
    }

    /** Unmatchable variables must be distinguishable from terminal variables. */
    @Test
    public void testInputStabilityIsRecorded() {
        Aig aig = new Aig();
        int stable = aig.newInput("terminal", true);
        int unstable = aig.newInput("!loop:x", false);
        Assertions.assertTrue(aig.isPiStable(aig.getPiIndex(Aig.nodeOf(stable))));
        Assertions.assertFalse(aig.isPiStable(aig.getPiIndex(Aig.nodeOf(unstable))));
    }

    /**
     * Random expressions, checked by evaluating the graph against a direct
     * computation on the same bit parallel words.
     */
    @Test
    public void testRandomExpressionsEvaluateCorrectly() {
        Aig aig = new Aig();
        int[] lits = new int[6];
        for (int i = 0; i < 6; i++) {
            lits[i] = aig.newInput("v" + i, true);
        }
        Random random = new Random(31337);
        int trials = 4000;
        int[] built = new int[trials];
        long[] expected = new long[trials];
        long[] values = new long[6];
        for (int i = 0; i < 6; i++) {
            values[i] = TruthTable.VAR_MASK[i];
        }
        int[] pool = lits.clone();
        long[] poolValues = values.clone();

        for (int trial = 0; trial < trials; trial++) {
            int x = random.nextInt(pool.length);
            int y = random.nextInt(pool.length);
            int operation = random.nextInt(4);
            boolean invertX = random.nextBoolean();
            boolean invertY = random.nextBoolean();
            int litX = invertX ? Aig.not(pool[x]) : pool[x];
            int litY = invertY ? Aig.not(pool[y]) : pool[y];
            long valX = invertX ? ~poolValues[x] : poolValues[x];
            long valY = invertY ? ~poolValues[y] : poolValues[y];

            int lit;
            long value;
            switch (operation) {
            case 0:
                lit = aig.and(litX, litY);
                value = valX & valY;
                break;
            case 1:
                lit = aig.or(litX, litY);
                value = valX | valY;
                break;
            case 2:
                lit = aig.xor(litX, litY);
                value = valX ^ valY;
                break;
            default:
                lit = aig.mux(pool[x], litY, pool[y]);
                value = (poolValues[x] & valY) | (~poolValues[x] & poolValues[y]);
                break;
            }
            built[trial] = lit;
            expected[trial] = value;
            pool = append(pool, lit);
            poolValues = append(poolValues, value);
        }

        AigEvaluation evaluation = AigEvaluation.overAllAssignments(aig);
        for (int trial = 0; trial < trials; trial++) {
            Assertions.assertEquals(expected[trial], evaluation.valueOf(built[trial]),
                    "Expression " + trial + " evaluates incorrectly");
        }
    }

    /** Growing past the initial capacity must not lose or corrupt any node. */
    @Test
    public void testGrowthPreservesEverything() {
        Aig aig = new Aig(16);
        int a = aig.newInput("a", true);
        int b = aig.newInput("b", true);
        int[] nodes = new int[5000];
        int current = a;
        for (int i = 0; i < nodes.length; i++) {
            current = aig.and(current, i % 2 == 0 ? b : Aig.not(b));
            nodes[i] = current;
        }
        for (int i = 0; i < nodes.length; i++) {
            Assertions.assertTrue(nodes[i] == Aig.FALSE || Aig.nodeOf(nodes[i]) > 0);
        }
        // Rebuilding the same chain must reuse every node.
        int andCount = aig.getAndCount();
        current = a;
        for (int i = 0; i < nodes.length; i++) {
            current = aig.and(current, i % 2 == 0 ? b : Aig.not(b));
            Assertions.assertEquals(nodes[i], current, "Node " + i + " was not reused");
        }
        Assertions.assertEquals(andCount, aig.getAndCount(),
                "Rebuilding an identical chain must not add nodes");
    }

    /** Exclusive or and select must cost the documented three nodes. */
    @Test
    public void testXorAndMuxNodeCounts() {
        Aig aig = new Aig();
        int a = aig.newInput("a", true);
        int b = aig.newInput("b", true);
        int c = aig.newInput("c", true);
        int before = aig.getAndCount();
        aig.xor(a, b);
        Assertions.assertEquals(before + 3, aig.getAndCount());
        before = aig.getAndCount();
        aig.mux(a, b, c);
        Assertions.assertEquals(before + 3, aig.getAndCount());
    }

    private static int[] append(int[] array, int value) {
        int[] result = java.util.Arrays.copyOf(array, array.length + 1);
        result[array.length] = value;
        return result;
    }

    private static long[] append(long[] array, long value) {
        long[] result = java.util.Arrays.copyOf(array, array.length + 1);
        result[array.length] = value;
        return result;
    }
}

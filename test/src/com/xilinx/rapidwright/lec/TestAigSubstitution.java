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
 * Checks the simultaneous input substitution the retiming check is built on.
 *
 * Results are judged by evaluating truth tables, not by inspecting the graph,
 * so a substitution is right exactly when the rewritten function equals the
 * original function composed with the replacement functions.
 */
public class TestAigSubstitution {

    private static int pi(Aig aig, int lit) {
        return aig.getPiIndex(Aig.nodeOf(lit));
    }

    /** A cone that reaches no replaced input comes back as the same literal. */
    @Test
    public void testUntouchedConeIsUnchanged() {
        Aig aig = new Aig();
        int a = aig.newInput("a", true);
        int b = aig.newInput("b", true);
        int c = aig.newInput("c", true);
        int f = aig.or(aig.and(a, b), Aig.not(b));
        AigSubstitution sub = new AigSubstitution(aig, new int[] { pi(aig, c) });
        sub.setValue(pi(aig, c), Aig.TRUE);
        int nodesBefore = aig.getNodeCount();
        Assertions.assertEquals(f, sub.apply(f));
        Assertions.assertEquals(Aig.not(f), sub.apply(Aig.not(f)));
        Assertions.assertEquals(nodesBefore, aig.getNodeCount(), "No node may be created");
    }

    /**
     * Swapping two inputs must swap them once: if a replacement were rewritten
     * again the swap would undo itself.
     */
    @Test
    public void testSwapIsSimultaneous() {
        Aig aig = new Aig();
        int x = aig.newInput("x", true);
        int y = aig.newInput("y", true);
        int f = aig.and(x, Aig.not(y));
        AigSubstitution swap = new AigSubstitution(aig, new int[] { pi(aig, x), pi(aig, y) });
        swap.setValue(pi(aig, x), y);
        swap.setValue(pi(aig, y), x);
        Assertions.assertEquals(aig.and(y, Aig.not(x)), swap.apply(f));
    }

    /** Replacing every input by a constant folds the cone to a constant. */
    @Test
    public void testConstantsFold() {
        Aig aig = new Aig();
        int a = aig.newInput("a", true);
        int b = aig.newInput("b", true);
        int f = aig.xor(a, b);
        AigSubstitution sub = new AigSubstitution(aig, new int[] { pi(aig, a), pi(aig, b) });
        sub.setValue(pi(aig, a), Aig.TRUE);
        sub.setValue(pi(aig, b), Aig.FALSE);
        Assertions.assertEquals(Aig.TRUE, sub.apply(f));
        Assertions.assertEquals(Aig.FALSE, sub.apply(Aig.not(f)));
    }

    /** A carry-chain-like cone far deeper than any call stack must not overflow. */
    @Test
    public void testDeepChain() {
        Aig aig = new Aig();
        int a = aig.newInput("a", true);
        int lit = a;
        for (int i = 0; i < 100000; i++) {
            lit = aig.and(lit, aig.newInput("i" + i, true));
        }
        AigSubstitution sub = new AigSubstitution(aig, new int[] { pi(aig, a) });
        sub.setValue(pi(aig, a), Aig.FALSE);
        Assertions.assertEquals(Aig.FALSE, sub.apply(lit));
    }

    /**
     * Random cones over four inputs, with two of them replaced by random
     * functions of the others: the rewritten table must equal the composition.
     */
    @Test
    public void testRandomCompositionMatchesEvaluation() {
        Random random = new Random(7);
        for (int trial = 0; trial < 200; trial++) {
            Aig aig = new Aig();
            int[] in = new int[4];
            for (int i = 0; i < in.length; i++) {
                in[i] = aig.newInput("in" + i, true);
            }
            int f = randomCone(aig, in, random, 12);
            int g0 = randomCone(aig, in, random, 6);
            int g1 = randomCone(aig, in, random, 6);
            AigSubstitution sub = new AigSubstitution(aig,
                    new int[] { pi(aig, in[0]), pi(aig, in[1]) });
            sub.setValue(pi(aig, in[0]), g0);
            sub.setValue(pi(aig, in[1]), g1);
            int composed = sub.apply(f);

            AigEvaluation all = AigEvaluation.overAllAssignments(aig);
            for (int assignment = 0; assignment < 16; assignment++) {
                long[] values = new long[aig.getPiCount()];
                values[pi(aig, in[0])] = all.bitOf(g0, assignment) ? 1L : 0L;
                values[pi(aig, in[1])] = all.bitOf(g1, assignment) ? 1L : 0L;
                values[pi(aig, in[2])] = (assignment >> 2) & 1;
                values[pi(aig, in[3])] = (assignment >> 3) & 1;
                boolean expected = AigEvaluation.of(aig, values).bitOf(f, 0);
                Assertions.assertEquals(expected, all.bitOf(composed, assignment),
                        "trial " + trial + ", assignment " + assignment);
            }
        }
    }

    /** Nodes created after the substitution was built are rewritten too. */
    @Test
    public void testNodesCreatedLaterAreRewritten() {
        Aig aig = new Aig();
        int a = aig.newInput("a", true);
        int b = aig.newInput("b", true);
        AigSubstitution sub = new AigSubstitution(aig, new int[] { pi(aig, a) });
        sub.setValue(pi(aig, a), Aig.TRUE);
        Assertions.assertEquals(b, sub.apply(aig.and(a, b)));
        int later = aig.or(a, Aig.not(b));
        Assertions.assertEquals(Aig.TRUE, sub.apply(later));
    }

    /** A replaced input reached before its value is given is an error. */
    @Test
    public void testMissingValueIsAnError() {
        Aig aig = new Aig();
        int a = aig.newInput("a", true);
        AigSubstitution sub = new AigSubstitution(aig, new int[] { pi(aig, a) });
        Assertions.assertThrows(IllegalStateException.class, () -> sub.apply(a));
    }

    private static int randomCone(Aig aig, int[] in, Random random, int gates) {
        int[] pool = new int[in.length + gates];
        System.arraycopy(in, 0, pool, 0, in.length);
        for (int i = in.length; i < pool.length; i++) {
            int x = pool[random.nextInt(i)] ^ (random.nextBoolean() ? 1 : 0);
            int y = pool[random.nextInt(i)] ^ (random.nextBoolean() ? 1 : 0);
            pool[i] = aig.and(x, y);
        }
        return pool[pool.length - 1];
    }
}

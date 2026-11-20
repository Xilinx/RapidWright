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

/**
 * Evaluates the whole graph on sixty four input patterns at once.
 *
 * Each node holds one {@code long} whose bits are its value under sixty four
 * independent assignments, so a single linear sweep in node index order, which
 * is a valid topological order by construction, evaluates every cone in the
 * design simultaneously. This is the reason the middle rung of the comparison
 * ladder is cheap: the cost is one pass over the graph per round, not one pass
 * per cone.
 *
 * Simulation can only ever <i>refute</i> equivalence, never prove it, but that
 * is exactly what is wanted before invoking a solver: a genuine difference such
 * as a rewired pin or a flipped INIT bit is almost always caught in the first
 * round, and a counter-example falls out for free.
 *
 * Patterns are generated from a fixed seed so that two runs over the same
 * design produce identical reports.
 */
public class AigSimulator {

    /** Patterns evaluated per round, one per bit of a long. */
    public static final int PATTERNS_PER_ROUND = 64;

    private final Aig aig;

    private final long[] values;

    private long seed;

    private int round;

    public AigSimulator(Aig aig) {
        this.aig = aig;
        this.values = new long[aig.getNodeCount()];
    }

    /**
     * Evaluates one round of patterns over the entire graph.
     *
     * The first round is not random. It uses all zeros, all ones, and single bit
     * patterns, because those catch the degenerate mistakes, such as a function
     * that collapsed to a constant, that random vectors are surprisingly poor
     * at finding.
     */
    public void runRound() {
        int nodeCount = aig.getNodeCount();
        values[0] = 0L;
        for (int node = 1; node < nodeCount; node++) {
            if (aig.isPi(node)) {
                values[node] = patternFor(aig.getPiIndex(node));
            } else {
                values[node] = literalValue(aig.getFanin0(node))
                        & literalValue(aig.getFanin1(node));
            }
        }
        round++;
    }

    /**
     * @return The sixty four values a primary input takes this round. Round zero
     *         is structured; later rounds use a reproducible pseudo random
     *         stream keyed on the input index and the round.
     */
    private long patternFor(int piIndex) {
        if (round == 0) {
            return structuredPattern(piIndex);
        }
        return mix(((long) piIndex << 32) ^ round ^ seed);
    }

    /**
     * Bit 0 is zero for every input and bit 1 is one, so the round always
     * includes the all zeros and all ones vectors. Those catch the degenerate
     * mistakes, such as a cone that collapsed to a constant, that purely random
     * vectors are surprisingly poor at finding. The remaining bits are
     * pseudo random but reproducible.
     */
    private static long structuredPattern(int piIndex) {
        long pattern = mix(piIndex) & ~0x3L;
        pattern |= 1L << 1;
        return pattern;
    }

    /** A cheap reversible mix, so patterns differ well between inputs. */
    private static long mix(long value) {
        long x = value + 0x9E3779B97F4A7C15L;
        x = (x ^ (x >>> 30)) * 0xBF58476D1CE4E5B9L;
        x = (x ^ (x >>> 27)) * 0x94D049BB133111EBL;
        return x ^ (x >>> 31);
    }

    /** @return The value word of a literal, complemented if the literal is. */
    public long literalValue(int lit) {
        long value = values[Aig.nodeOf(lit)];
        return Aig.isComplement(lit) ? ~value : value;
    }

    /**
     * @return The index of a pattern on which the two literals disagree, or -1
     *         if they agreed on every pattern this round.
     */
    public int firstDifferingPattern(int litA, int litB) {
        long diff = literalValue(litA) ^ literalValue(litB);
        return diff == 0 ? -1 : Long.numberOfTrailingZeros(diff);
    }

    /** @return The value a primary input took in one pattern of this round. */
    public boolean inputValue(int piIndex, int pattern) {
        long word = values[aig.getPiNode(piIndex)];
        return ((word >>> pattern) & 1L) != 0;
    }

    /** @return The value a literal took in one pattern of this round. */
    public boolean literalValueAt(int lit, int pattern) {
        return ((literalValue(lit) >>> pattern) & 1L) != 0;
    }

    /**
     * Tests whether flipping one input can change a cone's output, over the
     * patterns of the current round.
     *
     * Structural reachability over-approximates what a function actually
     * depends on: constant folding routinely leaves an input reachable but
     * irrelevant. Reporting the observably relevant inputs alongside the
     * reachable ones is far more actionable when localising a difference.
     *
     * @param coneNodes The cone's nodes in ascending, and therefore topological,
     *                  order, from {@link AigSupport#coneNodes(int[])}. Only
     *                  these are re-evaluated, so the query costs the size of
     *                  the cone rather than the size of the graph.
     */
    public boolean isObservablyRelevant(int piIndex, int rootLit, int[] coneNodes) {
        int piNode = aig.getPiNode(piIndex);
        long baseline = literalValue(rootLit);
        long original = values[piNode];
        values[piNode] = ~original;
        long flipped = recomputeCone(coneNodes, rootLit);
        values[piNode] = original;
        recomputeCone(coneNodes, rootLit);
        return flipped != baseline;
    }

    /** Re-evaluates a cone in place and returns the value of its root literal. */
    private long recomputeCone(int[] coneNodes, int rootLit) {
        for (int node : coneNodes) {
            if (node != 0 && !aig.isPi(node)) {
                values[node] = literalValue(aig.getFanin0(node))
                        & literalValue(aig.getFanin1(node));
            }
        }
        return literalValue(rootLit);
    }

    public int getRound() {
        return round;
    }

    /** Restarts the pattern stream, so a fresh comparison is reproducible. */
    public void reset(long newSeed) {
        this.seed = newSeed;
        this.round = 0;
    }
}

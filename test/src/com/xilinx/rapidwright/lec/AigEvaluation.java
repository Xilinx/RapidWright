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
 * An evaluator for the graph, written independently of {@link AigSimulator} so
 * that a bug in the simulator cannot hide behind itself when the tests use it
 * as an oracle.
 *
 * The bit parallel trick used here is what makes exhaustive testing practical.
 * Give input {@code i} the value word {@code TruthTable.VAR_MASK[i]} and
 * propagate: every node's word then holds its value under all sixty four
 * assignments of six variables at once, so the low bits of a node's word
 * <i>are</i> its truth table. Verifying a lowered function against the table it
 * came from becomes a single integer comparison after one linear pass.
 */
public class AigEvaluation {

    private final Aig aig;

    private final long[] values;

    private AigEvaluation(Aig aig, long[] values) {
        this.aig = aig;
        this.values = values;
    }

    /**
     * Propagates values through the whole graph, giving primary input
     * {@code i} the standard variable mask so that every node's word becomes a
     * truth table over the first six inputs.
     */
    public static AigEvaluation overAllAssignments(Aig aig) {
        long[] piValues = new long[Math.max(1, aig.getPiCount())];
        for (int i = 0; i < aig.getPiCount(); i++) {
            piValues[i] = i < TruthTable.MAX_VARS ? TruthTable.VAR_MASK[i] : 0L;
        }
        return of(aig, piValues);
    }

    /** Propagates a caller supplied word for each primary input. */
    public static AigEvaluation of(Aig aig, long[] piValues) {
        long[] values = new long[aig.getNodeCount()];
        for (int node = 1; node < aig.getNodeCount(); node++) {
            if (aig.isPi(node)) {
                int pi = aig.getPiIndex(node);
                values[node] = pi < piValues.length ? piValues[pi] : 0L;
            } else {
                values[node] = literal(aig, values, aig.getFanin0(node))
                        & literal(aig, values, aig.getFanin1(node));
            }
        }
        return new AigEvaluation(aig, values);
    }

    private static long literal(Aig aig, long[] values, int lit) {
        long value = Aig.nodeOf(lit) == 0 ? 0L : values[Aig.nodeOf(lit)];
        return Aig.isComplement(lit) ? ~value : value;
    }

    /** @return The value word of a literal under the propagated assignment. */
    public long valueOf(int lit) {
        return literal(aig, values, lit);
    }

    /** @return The value of a literal in one of the parallel assignments. */
    public boolean bitOf(int lit, int assignment) {
        return ((valueOf(lit) >>> assignment) & 1L) != 0;
    }

    /**
     * @return The literal's truth table over the first {@code vars} inputs,
     *         masked to the valid width.
     */
    public long tableOf(int lit, int vars) {
        return valueOf(lit) & TruthTable.widthMask(vars);
    }
}

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

import java.util.HashMap;
import java.util.Map;

/**
 * Lowers a truth table of at most six variables into and-inverter graph nodes.
 *
 * <h2>Why the simplification steps matter</h2>
 *
 * The decomposition is deterministic but not canonical: two different tables of
 * the same function would lower differently. That is tolerable because the
 * comparison escalates to simulation and then to a solver. What is <i>not</i>
 * tolerable is missing the easy matches, so the table is simplified first:
 *
 * <ol>
 * <li>inputs driven by a constant are folded into the table, which is extremely
 * common because Vivado ties unused look up table inputs high;</li>
 * <li>variables the function does not actually depend on are dropped, so a six
 * input INIT that ignores its top input lowers to exactly the same nodes as the
 * equivalent five input cell;</li>
 * <li>inputs that resolve to the same literal, or to opposite polarities of one
 * literal, are merged.</li>
 * </ol>
 *
 * After those steps the lowering is canonical under the transformations that
 * actually occur between two implementations of one design, so most cones match
 * by structural hashing alone and never reach a solver.
 */
public class TruthTableSynth {

    private TruthTableSynth() {
    }

    /**
     * Builds the function into the graph.
     *
     * @param aig       The graph to build into.
     * @param table     The function, in the bit ordering described by
     *                  {@link TruthTable}.
     * @param vars      How many variables the table covers, at most six.
     * @param inputLits The literal driving each variable. Entries beyond
     *                  {@code vars} are ignored.
     * @return The literal computing the function.
     */
    public static int synthesize(Aig aig, long table, int vars, int[] inputLits) {
        long expanded = TruthTable.expand(table, vars);
        int[] lits = new int[TruthTable.MAX_VARS];
        for (int v = 0; v < TruthTable.MAX_VARS; v++) {
            lits[v] = v < vars ? inputLits[v] : Aig.FALSE;
        }
        expanded = simplify(expanded, lits, vars);
        if (expanded == 0L) {
            return Aig.FALSE;
        }
        if (expanded == -1L) {
            return Aig.TRUE;
        }
        return decompose(aig, expanded, lits, new HashMap<Long, Integer>());
    }

    /**
     * Folds constants, drops irrelevant variables and merges duplicated inputs.
     * Variables that drop out have their literal set to false so that the
     * decomposition can never reach them.
     */
    private static long simplify(long table, int[] lits, int vars) {
        for (int v = 0; v < vars; v++) {
            if (lits[v] == Aig.FALSE) {
                table = TruthTable.cofactor0(table, v);
            } else if (lits[v] == Aig.TRUE) {
                table = TruthTable.cofactor1(table, v);
            }
        }
        for (int v = 0; v < vars; v++) {
            for (int w = v + 1; w < vars; w++) {
                if (!TruthTable.dependsOn(table, w) || Aig.isConstant(lits[w])) {
                    continue;
                }
                if (lits[w] == lits[v]) {
                    table = TruthTable.substitute(table, w, v);
                } else if (lits[w] == Aig.not(lits[v])) {
                    table = TruthTable.substituteInverted(table, w, v);
                }
            }
        }
        for (int v = 0; v < TruthTable.MAX_VARS; v++) {
            if (!TruthTable.dependsOn(table, v)) {
                lits[v] = Aig.FALSE;
            }
        }
        return table;
    }

    /**
     * Shannon expansion about the highest variable still in the support,
     * memoised on the residual table. Choosing the pivot deterministically is
     * what makes two identical functions lower to bit identical nodes and hence
     * strash together.
     */
    private static int decompose(Aig aig, long table, int[] lits, Map<Long, Integer> memo) {
        if (table == 0L) {
            return Aig.FALSE;
        }
        if (table == -1L) {
            return Aig.TRUE;
        }
        Integer cached = memo.get(table);
        if (cached != null) {
            return cached;
        }
        int pivot = -1;
        for (int v = TruthTable.MAX_VARS - 1; v >= 0; v--) {
            if (TruthTable.dependsOn(table, v)) {
                pivot = v;
                break;
            }
        }
        if (pivot < 0) {
            throw new LecException("Truth table " + Long.toHexString(table)
                    + " is neither constant nor dependent on any variable");
        }
        int whenTrue = decompose(aig, TruthTable.cofactor1(table, pivot), lits, memo);
        int whenFalse = decompose(aig, TruthTable.cofactor0(table, pivot), lits, memo);
        int result = aig.mux(lits[pivot], whenTrue, whenFalse);
        memo.put(table, result);
        return result;
    }
}

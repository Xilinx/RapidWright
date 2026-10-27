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
 * Verifies that lowering a truth table into graph nodes preserves the function.
 *
 * This is the highest value test in the suite. Every look up table in every
 * design goes through this path, so an error here would silently corrupt the
 * function of most of the logic while leaving the tool looking healthy: both
 * netlists would be corrupted the same way and would still compare equal.
 *
 * Coverage is exhaustive where that is possible. Every one of the 65536
 * functions of four variables is lowered and checked, which is feasible because
 * all of them can be built into one graph and verified in a single bit parallel
 * pass.
 */
public class TestTruthTableSynth {

    private static int[] inputs(Aig aig, int count) {
        int[] lits = new int[count];
        for (int i = 0; i < count; i++) {
            lits[i] = aig.newInput("i" + i, true);
        }
        return lits;
    }

    /**
     * All 65536 functions of four variables, lowered into one shared graph and
     * verified together.
     */
    @Test
    public void testExhaustiveFourVariableFunctions() {
        Aig aig = new Aig(1 << 16);
        int[] lits = inputs(aig, 4);
        int[] results = new int[1 << 16];
        for (int table = 0; table < (1 << 16); table++) {
            results[table] = TruthTableSynth.synthesize(aig, table, 4, lits);
        }
        AigEvaluation evaluation = AigEvaluation.overAllAssignments(aig);
        for (int table = 0; table < (1 << 16); table++) {
            Assertions.assertEquals(table, evaluation.tableOf(results[table], 4),
                    "Function 0x" + Integer.toHexString(table) + " lowered incorrectly");
        }
    }

    /** Random functions at the two larger widths a look up table can have. */
    @Test
    public void testRandomFiveAndSixVariableFunctions() {
        for (int vars : new int[] { 5, 6 }) {
            Aig aig = new Aig(1 << 16);
            int[] lits = inputs(aig, vars);
            Random random = new Random(0xC0FFEE + vars);
            int count = 20000;
            long[] tables = new long[count];
            int[] results = new int[count];
            for (int i = 0; i < count; i++) {
                tables[i] = random.nextLong() & TruthTable.widthMask(vars);
                results[i] = TruthTableSynth.synthesize(aig, tables[i], vars, lits);
            }
            AigEvaluation evaluation = AigEvaluation.overAllAssignments(aig);
            for (int i = 0; i < count; i++) {
                Assertions.assertEquals(tables[i], evaluation.tableOf(results[i], vars),
                        vars + " variable function 0x" + Long.toHexString(tables[i])
                                + " lowered incorrectly");
            }
        }
    }

    /**
     * An input tied to a constant must be folded into the table rather than
     * left as a node. Vivado ties unused look up table inputs high, so this path
     * runs on essentially every real design.
     */
    @Test
    public void testConstantInputsAreFolded() {
        Aig aig = new Aig();
        int a = aig.newInput("a", true);
        int b = aig.newInput("b", true);
        long andTable = TruthTable.VAR_MASK[0] & TruthTable.VAR_MASK[1] & 0xFL;

        int withTrue = TruthTableSynth.synthesize(aig, andTable, 2, new int[] { a, Aig.TRUE });
        Assertions.assertEquals(a, withTrue, "AND with a constant one should reduce to the input");

        int withFalse = TruthTableSynth.synthesize(aig, andTable, 2, new int[] { a, Aig.FALSE });
        Assertions.assertEquals(Aig.FALSE, withFalse,
                "AND with a constant zero should reduce to zero");

        int unrelated = TruthTableSynth.synthesize(aig, andTable, 2, new int[] { a, b });
        Assertions.assertNotEquals(a, unrelated);
    }

    /**
     * A six input table that ignores its top input must lower to exactly the
     * same nodes as the equivalent five input table.
     *
     * This is what makes structural hashing find matches across a design where
     * one flow emitted a LUT6 and the other a LUT5 for the same function.
     */
    @Test
    public void testUnusedVariablesAreDropped() {
        Aig aig = new Aig();
        int[] lits = inputs(aig, 6);
        long fiveVarTable = 0xDEADBEEFL & TruthTable.widthMask(5);
        long asSixVars = TruthTable.expand(fiveVarTable, 5) & TruthTable.widthMask(6);

        int asFive = TruthTableSynth.synthesize(aig, fiveVarTable, 5, lits);
        int asSix = TruthTableSynth.synthesize(aig, asSixVars, 6, lits);
        Assertions.assertEquals(asFive, asSix,
                "A six input table ignoring its top input must lower like a five input table");
    }

    /** Two inputs driven by the same literal must be merged, not duplicated. */
    @Test
    public void testDuplicatedInputsAreMerged() {
        Aig aig = new Aig();
        int a = aig.newInput("a", true);
        long xorTable = TruthTable.VAR_MASK[0] ^ TruthTable.VAR_MASK[1];
        int result = TruthTableSynth.synthesize(aig, xorTable & 0xFL, 2, new int[] { a, a });
        Assertions.assertEquals(Aig.FALSE, result, "x xor x should reduce to zero");

        long andTable = TruthTable.VAR_MASK[0] & TruthTable.VAR_MASK[1];
        int conjunction = TruthTableSynth.synthesize(aig, andTable & 0xFL, 2, new int[] { a, a });
        Assertions.assertEquals(a, conjunction, "x and x should reduce to x");
    }

    /** Inputs driven by opposite polarities of one literal must also be merged. */
    @Test
    public void testInvertedDuplicateInputsAreMerged() {
        Aig aig = new Aig();
        int a = aig.newInput("a", true);
        long andTable = (TruthTable.VAR_MASK[0] & TruthTable.VAR_MASK[1]) & 0xFL;
        int result = TruthTableSynth.synthesize(aig, andTable, 2, new int[] { a, Aig.not(a) });
        Assertions.assertEquals(Aig.FALSE, result, "x and not x should reduce to zero");

        long orTable = (TruthTable.VAR_MASK[0] | TruthTable.VAR_MASK[1]) & 0xFL;
        int disjunction = TruthTableSynth.synthesize(aig, orTable, 2, new int[] { a, Aig.not(a) });
        Assertions.assertEquals(Aig.TRUE, disjunction, "x or not x should reduce to one");
    }

    /**
     * The same function lowered twice must produce the same nodes, otherwise
     * structural hashing would miss matches it should find.
     */
    @Test
    public void testLoweringIsDeterministic() {
        Aig aig = new Aig();
        int[] lits = inputs(aig, 6);
        Random random = new Random(99);
        for (int i = 0; i < 500; i++) {
            long table = random.nextLong();
            int first = TruthTableSynth.synthesize(aig, table, 6, lits);
            int second = TruthTableSynth.synthesize(aig, table, 6, lits);
            Assertions.assertEquals(first, second);
        }
    }

    /** The cofactor and substitution helpers, checked against direct evaluation. */
    @Test
    public void testTruthTableHelpers() {
        Random random = new Random(7);
        for (int trial = 0; trial < 2000; trial++) {
            long table = random.nextLong();
            int var = random.nextInt(TruthTable.MAX_VARS);
            long zero = TruthTable.cofactor0(table, var);
            long one = TruthTable.cofactor1(table, var);
            for (int assignment = 0; assignment < 64; assignment++) {
                int withZero = assignment & ~(1 << var);
                int withOne = assignment | (1 << var);
                Assertions.assertEquals(TruthTable.evaluate(table, withZero),
                        TruthTable.evaluate(zero, assignment));
                Assertions.assertEquals(TruthTable.evaluate(table, withOne),
                        TruthTable.evaluate(one, assignment));
            }
            Assertions.assertFalse(TruthTable.dependsOn(zero, var),
                    "A cofactor must not depend on the variable it was taken about");
        }
    }

    /**
     * Over every four variable function, the reduced variable set and the
     * projected table must compute exactly the original function.
     *
     * This is the property the cone builder relies on when it declares a look up
     * table's support from its INIT: dropping the ignored variables and
     * renumbering the rest must be invisible.
     */
    @Test
    public void testDependentVarsAndProjectPreserveTheFunction() {
        for (int table = 0; table < 65536; table++) {
            int[] keep = TruthTable.dependentVars(table, 4);
            long projected = TruthTable.project(table, keep);
            for (int assignment = 0; assignment < 16; assignment++) {
                int reduced = 0;
                for (int j = 0; j < keep.length; j++) {
                    if (((assignment >>> keep[j]) & 1) != 0) {
                        reduced |= 1 << j;
                    }
                }
                Assertions.assertEquals(TruthTable.evaluate(table, assignment),
                        TruthTable.evaluate(projected, reduced),
                        "Projection changed the function of table " + table);
            }
            for (int v = 0; v < 4; v++) {
                boolean kept = false;
                for (int k : keep) {
                    kept |= k == v;
                }
                Assertions.assertEquals(TruthTable.dependsOn(TruthTable.expand(table, 4), v), kept,
                        "dependentVars disagrees with dependsOn on table " + table);
            }
        }
    }

    /** A table that ignores everything reduces to no variables at all. */
    @Test
    public void testConstantTableHasEmptySupport() {
        Assertions.assertEquals(0, TruthTable.dependentVars(0x0000L, 4).length,
                "An all zero INIT depends on nothing");
        Assertions.assertEquals(0, TruthTable.dependentVars(0xFFFFL, 4).length,
                "An all ones INIT depends on nothing");
        // A typical LUT6_2 O5 half packed beside an unrelated O6: I0 & I1 only.
        Assertions.assertArrayEquals(new int[] { 0, 1 },
                TruthTable.dependentVars(0x88888888L, 5),
                "0x88888888 is I0 & I1, so I2 to I4 must be dropped");
    }
}

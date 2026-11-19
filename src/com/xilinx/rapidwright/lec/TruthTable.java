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
 * Bit manipulation of Boolean functions of at most six variables, held as a
 * 64 bit truth table.
 *
 * Bit {@code k} of the table is the function's value when the input vector is
 * the binary encoding of {@code k}, with variable 0 as the least significant
 * bit. That is exactly the convention Xilinx uses for a look up table's INIT
 * property, so a LUT's INIT is already a truth table in this form.
 *
 * Tables are always held expanded to six variables, with any unused variable
 * replicated so that it is genuinely irrelevant to the function. This makes the
 * table a canonical name for the function regardless of how many variables the
 * caller thinks there are, which is what lets the lowering in
 * {@link TruthTableSynth} memoise on it.
 */
public class TruthTable {

    /** For each variable, the bits of a six variable table where it is one. */
    public static final long[] VAR_MASK = {
        0xAAAAAAAAAAAAAAAAL,
        0xCCCCCCCCCCCCCCCCL,
        0xF0F0F0F0F0F0F0F0L,
        0xFF00FF00FF00FF00L,
        0xFFFF0000FFFF0000L,
        0xFFFFFFFF00000000L
    };

    /** The largest function width a single 64 bit table can hold. */
    public static final int MAX_VARS = 6;

    private TruthTable() {
    }

    /**
     * Expands a table of {@code vars} variables to the full six variable form by
     * replicating it, which leaves the higher variables irrelevant.
     */
    public static long expand(long table, int vars) {
        long result = table & widthMask(vars);
        for (int v = vars; v < MAX_VARS; v++) {
            result |= result << (1 << v);
        }
        return result;
    }

    /** @return A mask of the valid bits of a table of the given width. */
    public static long widthMask(int vars) {
        if (vars >= MAX_VARS) {
            return -1L;
        }
        return (1L << (1 << vars)) - 1;
    }

    /** @return The function with variable {@code var} forced to zero. */
    public static long cofactor0(long table, int var) {
        long c = table & ~VAR_MASK[var];
        return c | (c << (1 << var));
    }

    /** @return The function with variable {@code var} forced to one. */
    public static long cofactor1(long table, int var) {
        long c = table & VAR_MASK[var];
        return c | (c >>> (1 << var));
    }

    /**
     * @return True if the function actually depends on the variable. A table
     *         that ignores a variable is common: Vivado routinely emits a six
     *         input INIT for a function of fewer inputs.
     */
    public static boolean dependsOn(long table, int var) {
        return cofactor0(table, var) != cofactor1(table, var);
    }

    /**
     * Substitutes one variable for another, for the case where two inputs of a
     * cell turn out to be driven by the same signal.
     *
     * @return The function with {@code from} replaced by {@code to}, leaving
     *         {@code from} irrelevant.
     */
    public static long substitute(long table, int from, int to) {
        long bothOne = cofactor1(cofactor1(table, to), from);
        long bothZero = cofactor0(cofactor0(table, to), from);
        return (bothOne & VAR_MASK[to]) | (bothZero & ~VAR_MASK[to]);
    }

    /**
     * Substitutes the complement of one variable for another, for the case where
     * two inputs turn out to be driven by opposite polarities of one signal.
     *
     * @return The function with {@code from} replaced by {@code !to}, leaving
     *         {@code from} irrelevant.
     */
    public static long substituteInverted(long table, int from, int to) {
        long toOne = cofactor0(cofactor1(table, to), from);
        long toZero = cofactor1(cofactor0(table, to), from);
        return (toOne & VAR_MASK[to]) | (toZero & ~VAR_MASK[to]);
    }

    /** @return A table equal to the given variable. */
    public static long var(int index) {
        return VAR_MASK[index];
    }

    /**
     * Evaluates the function for one input vector, used by the tests to check a
     * lowered graph against the table it came from.
     */
    public static boolean evaluate(long table, int inputVector) {
        return ((table >>> inputVector) & 1L) != 0;
    }

    /**
     * The variables a function of {@code vars} inputs actually depends on, in
     * ascending order.
     *
     * Expands first, because {@link #dependsOn} reads a table in the full six
     * variable form and a narrower table has zeros above its width that would
     * otherwise read as a dependence on the padding variables.
     */
    public static int[] dependentVars(long table, int vars) {
        long expanded = expand(table, vars);
        int[] found = new int[vars];
        int count = 0;
        for (int v = 0; v < vars; v++) {
            if (dependsOn(expanded, v)) {
                found[count++] = v;
            }
        }
        return Arrays.copyOf(found, count);
    }

    /**
     * Restricts a function to a subset of its variables, renumbering them to
     * {@code 0 .. keep.length-1} in the order given.
     *
     * Only meaningful when the function is independent of every variable left
     * out, in which case the omitted ones can be held at zero without changing
     * anything. Pair it with {@link #dependentVars} to drop the variables a
     * table ignores.
     */
    public static long project(long table, int[] keep) {
        long result = 0;
        for (int reduced = 0; reduced < (1 << keep.length); reduced++) {
            int full = 0;
            for (int j = 0; j < keep.length; j++) {
                if (((reduced >>> j) & 1) != 0) {
                    full |= 1 << keep[j];
                }
            }
            if (((table >>> full) & 1L) != 0) {
                result |= 1L << reduced;
            }
        }
        return result;
    }

    /** @return The variables the function depends on, as a bit set. */
    public static int support(long table) {
        int mask = 0;
        for (int v = 0; v < MAX_VARS; v++) {
            if (dependsOn(table, v)) {
                mask |= 1 << v;
            }
        }
        return mask;
    }
}

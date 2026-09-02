/*
 * Copyright (c) 2026, Advanced Micro Devices, Inc.
 * All rights reserved.
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

package com.xilinx.rapidwright.timing.sdf;

import java.util.Arrays;

/**
 * An SDF {@code delval_list}: an ordered list of delay values, each of which is either absent
 * (written {@code ()} in the file) or a {@code min:typ:max} triple.
 *
 * Vivado emits two delvals (rise, fall) for most arcs and six for tri-state outputs (using the
 * standard 01, 10, 0Z, Z1, 1Z, Z0 ordering), where the 0Z/Z1 slots may be absent. A
 * {@code PATHPULSEPERCENT} carries a single delval.
 *
 * Values are stored as {@code int} tenths of the enclosing {@link SdfFile}'s
 * {@link SdfFile#getTimeScale() TIMESCALE} unit rather than as {@code float}, for two independent
 * reasons. Vivado writes every value with exactly one fractional digit, so tenths are exact,
 * whereas round-tripping through {@code float} would not reproduce the input bytes. And a large
 * design contains tens of millions of these values, so avoiding {@code Float.parseFloat} and any
 * intermediate {@code String} matters for parse throughput.
 *
 * Conversion to picoseconds happens once, at annotation time; see
 * {@link SdfFile#getTimeScaleToPsNumerator()}.
 */
public class SdfDelayValues {

    /**
     * Sentinel stored in place of a value of {@code -0.0}, which is otherwise indistinguishable
     * from {@code 0.0} once scaled to an integer but which must be written back out verbatim.
     */
    public static final int NEG_ZERO = Integer.MIN_VALUE;

    /** Number of components in a triple: min, typ, max. */
    public static final int TRIPLE_SIZE = 3;

    /** Index of the {@code min} component within a triple. */
    public static final int MIN = 0;

    /** Index of the {@code typ} component within a triple. */
    public static final int TYP = 1;

    /** Index of the {@code max} component within a triple. */
    public static final int MAX = 2;

    /** An empty delval list, i.e. an arc written with no values at all. */
    public static final SdfDelayValues EMPTY = new SdfDelayValues(new int[0], 0L, 0L, 0L, 0);

    /**
     * Tenths of the file's time unit, {@link #TRIPLE_SIZE} entries per delval slot. Entries
     * belonging to an absent slot are zero and must not be read; consult {@link #isPresent(int)}.
     */
    private final int[] tenths;

    /** Bit <i>i</i> is set when delval slot <i>i</i> is present rather than written {@code ()}. */
    private final long presentMask;

    /**
     * Bit <i>i</i> is set when delval slot <i>i</i> was written as a {@code min:typ:max} triple
     * rather than as a single value standing for all three. Vivado uses the triple form everywhere
     * except {@code PATHPULSEPERCENT}, and the distinction has to be recorded for the writer to
     * reproduce the file exactly.
     */
    private final long tripleMask;

    /**
     * Bit <i>i</i> is set when delval slot <i>i</i> was written with a single space between its
     * opening parenthesis and its first value, as in {@code ( 41.0:53.0:53.0)}.
     *
     * Vivado inserts this space in every triple of a file written with
     * {@code write_sdf -process_corner fast} and in none of a {@code slow} one. It is purely
     * cosmetic, but reproducing it is what allows a round-trip of a fast-corner file to be
     * byte-identical.
     */
    private final long paddedMask;

    /** Total number of delval slots, present or not. */
    private final int size;

    /**
     * @param tenths Tenths of the file's time unit, 3 per slot; length must be 3 * size.
     * @param presentMask Bit i set when slot i is present.
     * @param tripleMask Bit i set when slot i was written as a triple rather than a single value.
     * @param paddedMask Bit i set when slot i had a space after its opening parenthesis.
     * @param size Number of delval slots.
     */
    public SdfDelayValues(int[] tenths, long presentMask, long tripleMask, long paddedMask,
            int size) {
        if (tenths.length != size * TRIPLE_SIZE) {
            throw new IllegalArgumentException("ERROR: tenths.length=" + tenths.length
                    + " does not match size=" + size);
        }
        this.tenths = tenths;
        this.presentMask = presentMask;
        this.tripleMask = tripleMask;
        this.paddedMask = paddedMask;
        this.size = size;
    }

    /**
     * @param slot Delval slot index.
     * @return True if this slot was written with a space between its opening parenthesis and its
     *         first value, which is how Vivado formats fast-corner files.
     */
    public boolean isPadded(int slot) {
        return slot < Long.SIZE && (paddedMask & (1L << slot)) != 0;
    }

    /**
     * @return The raw padding mask, bit i set when slot i had a leading space.
     */
    public long getPaddedMask() {
        return paddedMask;
    }

    /**
     * @param slot Delval slot index.
     * @return True if this slot was written as a {@code min:typ:max} triple, false if it was
     *         written as a single value standing for all three components.
     */
    public boolean isTriple(int slot) {
        return slot < Long.SIZE && (tripleMask & (1L << slot)) != 0;
    }

    /**
     * @return The raw triple mask, bit i set when slot i was written as a triple.
     */
    public long getTripleMask() {
        return tripleMask;
    }

    /**
     * @return The number of delval slots, including absent ones.
     */
    public int size() {
        return size;
    }

    /**
     * @param slot Delval slot index.
     * @return True if this slot carries a triple, false if it was written {@code ()}.
     */
    public boolean isPresent(int slot) {
        return slot < Long.SIZE && (presentMask & (1L << slot)) != 0;
    }

    /**
     * @return The raw present mask, bit i set when slot i is present.
     */
    public long getPresentMask() {
        return presentMask;
    }

    /**
     * Returns one component of one delval, in tenths of the file's time unit.
     *
     * @param slot Delval slot index.
     * @param component One of {@link #MIN}, {@link #TYP} or {@link #MAX}.
     * @return The value in tenths, or {@link #NEG_ZERO} for a literal {@code -0.0}.
     */
    public int getTenths(int slot, int component) {
        if (!isPresent(slot)) {
            throw new IllegalStateException("ERROR: delval slot " + slot + " is absent");
        }
        return tenths[slot * TRIPLE_SIZE + component];
    }

    /**
     * Returns one component of one delval as a numeric value in the file's time unit, mapping the
     * {@link #NEG_ZERO} sentinel back to zero.
     *
     * @param slot Delval slot index.
     * @param component One of {@link #MIN}, {@link #TYP} or {@link #MAX}.
     * @return The value, in the file's time unit.
     */
    public double getValue(int slot, int component) {
        int t = getTenths(slot, component);
        return t == NEG_ZERO ? 0.0 : t / 10.0;
    }

    /**
     * @return The raw backing array; callers must not modify it.
     */
    int[] getTenthsArray() {
        return tenths;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        SdfDelayValues that = (SdfDelayValues) o;
        return size == that.size && presentMask == that.presentMask
                && tripleMask == that.tripleMask && paddedMask == that.paddedMask
                && Arrays.equals(tenths, that.tenths);
    }

    @Override
    public int hashCode() {
        int h = 31 * size + Long.hashCode(presentMask);
        h = 31 * h + Long.hashCode(tripleMask);
        h = 31 * h + Long.hashCode(paddedMask);
        return 31 * h + Arrays.hashCode(tenths);
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < size; i++) {
            if (i > 0) sb.append(' ');
            if (!isPresent(i)) {
                sb.append("()");
                continue;
            }
            sb.append('(');
            if (isPadded(i)) {
                sb.append(' ');
            }
            if (isTriple(i)) {
                for (int c = 0; c < TRIPLE_SIZE; c++) {
                    if (c > 0) sb.append(':');
                    SdfNumbers.appendTenths(sb, tenths[i * TRIPLE_SIZE + c]);
                }
            } else {
                SdfNumbers.appendTenths(sb, tenths[i * TRIPLE_SIZE]);
            }
            sb.append(')');
        }
        return sb.toString();
    }
}

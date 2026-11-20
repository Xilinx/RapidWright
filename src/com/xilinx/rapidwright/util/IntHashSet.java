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

package com.xilinx.rapidwright.util;

/**
 * A set of {@code int} values in a single open addressing table, without boxing. For millions of
 * values (e.g. the hash codes of all the net names of a design), it is faster and much smaller than a
 * {@code HashSet<Integer>}, which allocates an {@code Integer} and a node per value. The table grows
 * as needed; sizing it with the expected number of values avoids rehashing.
 */
public final class IntHashSet {

    /** The values, 0 marking an empty slot (0 itself is kept aside, see {@link #hasZero}) */
    private int[] table;
    private int mask;
    private int size;
    private boolean hasZero;

    /** Creates an empty set. */
    public IntHashSet() {
        this(16);
    }

    /**
     * Creates an empty set.
     * @param expectedSize The number of values expected (the set grows beyond it if needed).
     */
    public IntHashSet(int expectedSize) {
        allocate(capacityFor(expectedSize));
    }

    private static int capacityFor(int size) {
        // (at most half full)
        return Integer.highestOneBit(Math.max(4, size * 2) - 1) << 1;
    }

    private void allocate(int capacity) {
        table = new int[capacity];
        mask = capacity - 1;
    }

    private static int spread(int h) {
        h *= 0x9E3779B9;
        return h ^ (h >>> 16);
    }

    /**
     * Adds a value.
     * @return True if the value was not in the set.
     */
    public boolean add(int value) {
        if (value == 0) {
            if (hasZero) {
                return false;
            }
            hasZero = true;
            size++;
            return true;
        }
        int i = spread(value) & mask;
        int v;
        while ((v = table[i]) != 0) {
            if (v == value) {
                return false;
            }
            i = (i + 1) & mask;
        }
        table[i] = value;
        size++;
        if (2 * size > table.length) {
            rehash();
        }
        return true;
    }

    private void rehash() {
        int[] old = table;
        allocate(old.length * 2);
        for (int v : old) {
            if (v != 0) {
                int i = spread(v) & mask;
                while (table[i] != 0) {
                    i = (i + 1) & mask;
                }
                table[i] = v;
            }
        }
    }

    /** Checks whether the set contains a value. */
    public boolean contains(int value) {
        if (value == 0) {
            return hasZero;
        }
        int i = spread(value) & mask;
        int v;
        while ((v = table[i]) != 0) {
            if (v == value) {
                return true;
            }
            i = (i + 1) & mask;
        }
        return false;
    }

    /** @return The number of values in the set. */
    public int size() {
        return size;
    }

    /** @return True if the set has no values. */
    public boolean isEmpty() {
        return size == 0;
    }
}

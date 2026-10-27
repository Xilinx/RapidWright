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

package com.xilinx.rapidwright.util.routestatus;

/**
 * A set of ints, in one open addressing table (0 marks empty slots, so is kept aside). It holds the
 * hash codes of the names of all physical nets, and is queried for every net segment of the netlist:
 * a {@code Set<Integer>} would box each of them.
 */
final class IntHashSet {

    private final int[] table;
    private final int mask;
    private boolean hasZero;

    IntHashSet(int expected) {
        int capacity = Integer.highestOneBit(Math.max(4, expected * 2) - 1) << 1;
        table = new int[capacity];
        mask = capacity - 1;
    }

    static int spread(int h) {
        h *= 0x9E3779B9;
        return h ^ (h >>> 16);
    }

    void add(int v) {
        if (v == 0) {
            hasZero = true;
            return;
        }
        int i = spread(v) & mask;
        while (table[i] != 0) {
            if (table[i] == v) {
                return;
            }
            i = (i + 1) & mask;
        }
        table[i] = v;
    }

    boolean contains(int v) {
        if (v == 0) {
            return hasZero;
        }
        int i = spread(v) & mask;
        int t;
        while ((t = table[i]) != 0) {
            if (t == v) {
                return true;
            }
            i = (i + 1) & mask;
        }
        return false;
    }
}

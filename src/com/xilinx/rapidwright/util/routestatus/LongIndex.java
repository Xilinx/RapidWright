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

import java.util.Arrays;

/**
 * Assigns consecutive indices to distinct long keys, in an open addressing table. It indexes the
 * nodes of the routing of each net (see {@link RouteGraph}): a {@code Map<Long, Integer>} would box
 * each key and index.
 */
final class LongIndex {

    private final long[] table;
    /** Index + 1 of the key in each slot (0 if empty) */
    private final int[] slots;
    private final long[] order;
    private final int mask;
    private int size;

    LongIndex(int expected) {
        int capacity = Integer.highestOneBit(Math.max(4, expected * 2) - 1) << 1;
        table = new long[capacity];
        slots = new int[capacity];
        order = new long[Math.max(1, expected)];
        mask = capacity - 1;
    }

    private static int spread(long k) {
        long h = k * 0x9E3779B97F4A7C15L;
        return (int) (h ^ (h >>> 32));
    }

    /** Gets the index of a key, adding it if new. */
    int add(long k) {
        int i = spread(k) & mask;
        int s;
        while ((s = slots[i]) != 0) {
            if (table[i] == k) {
                return s - 1;
            }
            i = (i + 1) & mask;
        }
        table[i] = k;
        order[size] = k;
        slots[i] = ++size;
        return size - 1;
    }

    /** Gets the index of a key, or -1 if absent. */
    int get(long k) {
        int i = spread(k) & mask;
        int s;
        while ((s = slots[i]) != 0) {
            if (table[i] == k) {
                return s - 1;
            }
            i = (i + 1) & mask;
        }
        return -1;
    }

    /** Gets the keys, in order of their indices. */
    long[] keys() {
        return Arrays.copyOf(order, size);
    }
}

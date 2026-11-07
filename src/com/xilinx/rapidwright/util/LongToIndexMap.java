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

import java.util.Arrays;

/**
 * Assigns consecutive indices (0, 1, 2...) to distinct {@code long} keys, in the order they are
 * added, in a single open addressing table without boxing. It turns keys (e.g. packed tile and wire
 * indices of nodes) into indices into arrays. For many keys it is faster and smaller than a
 * {@code HashMap<Long, Integer>}, which allocates a {@code Long}, an {@code Integer} and a node per
 * key. The table grows as needed; sizing it with the expected number of keys avoids rehashing.
 */
public final class LongToIndexMap {

    /** The keys, in their slots */
    private long[] table;
    /** The index + 1 of the key in each slot (0 if the slot is empty) */
    private int[] slots;
    /** The keys, by index */
    private long[] keys;
    private int mask;
    private int size;

    /** Creates an empty map. */
    public LongToIndexMap() {
        this(16);
    }

    /**
     * Creates an empty map.
     * @param expectedSize The number of keys expected (the map grows beyond it if needed).
     */
    public LongToIndexMap(int expectedSize) {
        allocate(capacityFor(expectedSize));
        keys = new long[Math.max(1, expectedSize)];
    }

    private static int capacityFor(int size) {
        // (at most half full)
        return Integer.highestOneBit(Math.max(4, size * 2) - 1) << 1;
    }

    private void allocate(int capacity) {
        table = new long[capacity];
        slots = new int[capacity];
        mask = capacity - 1;
    }

    private static int spread(long k) {
        long h = k * 0x9E3779B97F4A7C15L;
        return (int) (h ^ (h >>> 32));
    }

    /**
     * Gets the index of a key, adding the key with the next index if it is new.
     * @return The index of the key.
     */
    public int add(long key) {
        int i = spread(key) & mask;
        int s;
        while ((s = slots[i]) != 0) {
            if (table[i] == key) {
                return s - 1;
            }
            i = (i + 1) & mask;
        }
        if (size == keys.length) {
            keys = Arrays.copyOf(keys, 2 * size);
        }
        table[i] = key;
        keys[size] = key;
        slots[i] = ++size;
        if (2 * size > table.length) {
            rehash();
        }
        return size - 1;
    }

    private void rehash() {
        allocate(table.length * 2);
        for (int index = 0; index < size; index++) {
            int i = spread(keys[index]) & mask;
            while (slots[i] != 0) {
                i = (i + 1) & mask;
            }
            table[i] = keys[index];
            slots[i] = index + 1;
        }
    }

    /**
     * Gets the index of a key.
     * @return The index of the key, or -1 if it has none.
     */
    public int get(long key) {
        int i = spread(key) & mask;
        int s;
        while ((s = slots[i]) != 0) {
            if (table[i] == key) {
                return s - 1;
            }
            i = (i + 1) & mask;
        }
        return -1;
    }

    /**
     * Gets the key of an index.
     * @param index An index from 0 to {@link #size()} - 1.
     */
    public long getKey(int index) {
        if (index < 0 || index >= size) {
            throw new IndexOutOfBoundsException("Index " + index + ", size " + size);
        }
        return keys[index];
    }

    /** @return The keys, in the order of their indices. */
    public long[] keys() {
        return Arrays.copyOf(keys, size);
    }

    /** @return The number of keys. */
    public int size() {
        return size;
    }
}

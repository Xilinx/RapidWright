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
import java.util.Collection;

import com.xilinx.rapidwright.device.Node;

/**
 * Keys of the nodes of a device (packing their tile and wire), which the route status engine sorts
 * and compares instead of {@link Node} objects.
 */
final class NodeKeys {

    private NodeKeys() {
    }

    /** Gets the key of a node. */
    static long nodeKey(Node node) {
        return ((long) node.getTile().getUniqueAddress() << 32) | (node.getWireIndex() & 0xffffffffL);
    }

    /** Sorts the first n keys of an array (in place) and gets the distinct ones. */
    static long[] sortedDistinct(long[] keys, int n) {
        if (n == 1) {
            return (keys.length == 1) ? keys : Arrays.copyOf(keys, 1);
        }
        Arrays.sort(keys, 0, n);
        int distinct = 0;
        for (int i = 0; i < n; i++) {
            if (distinct == 0 || keys[distinct - 1] != keys[i]) {
                keys[distinct++] = keys[i];
            }
        }
        return Arrays.copyOf(keys, distinct);
    }

    /** Concatenates two arrays, either of which may be null. */
    static long[] concat(long[] a, long[] b) {
        if (a == null || b == null) {
            return (a == null) ? b : a;
        }
        long[] c = Arrays.copyOf(a, a.length + b.length);
        System.arraycopy(b, 0, c, a.length, b.length);
        return c;
    }

    /** Gets the sorted distinct keys of a collection of keys. */
    static long[] toSortedDistinct(Collection<Long> keys) {
        long[] array = new long[keys.size()];
        int i = 0;
        for (long key : keys) {
            array[i++] = key;
        }
        return sortedDistinct(array, array.length);
    }
}

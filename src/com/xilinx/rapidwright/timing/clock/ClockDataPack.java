/*
 *
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

package com.xilinx.rapidwright.timing.clock;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Decodes the clock data of a timing data file (its section
 * {@link com.xilinx.rapidwright.timing.family.TimingData#CLOCK}): a tree of maps, lists, strings and numbers
 * (the fitted clock data that {@link ClockData} describes), as a table of strings and integers.
 * <p>
 * After a magic number, the integers encode the tree: a tag (0 null, 1 number as the bits of a float, 2
 * string, 3 list, 4 map, 5 false, 6 true) followed by its value: the float bits, a string index, or a count
 * and the elements (for a map, the key's string index before each element).
 */
final class ClockDataPack {

    /** First integer of the data ("CLK1") */
    static final int MAGIC = 0x434c4b31;

    private final String[] strings;
    private final int[] ints;
    private int pos;

    private ClockDataPack(String[] strings, int[] ints) {
        this.strings = strings;
        this.ints = ints;
    }

    /**
     * Decodes clock data.
     * @param strings The strings.
     * @param ints The integers.
     * @return The tree: a map of the top-level keys.
     * @throws IllegalArgumentException If the integers are not clock data.
     */
    @SuppressWarnings("unchecked")
    static Map<String, Object> decode(String[] strings, int[] ints) {
        ClockDataPack p = new ClockDataPack(strings, ints);
        if (ints.length == 0 || ints[0] != MAGIC) {
            throw new IllegalArgumentException("Not clock data");
        }
        p.pos = 1;
        Object tree;
        try {
            tree = p.value();
        } catch (ArrayIndexOutOfBoundsException e) {
            throw new IllegalArgumentException("Clock data ends early", e);
        }
        if (!(tree instanceof Map) || p.pos != ints.length) {
            throw new IllegalArgumentException("Not clock data");
        }
        return (Map<String, Object>) tree;
    }

    private Object value() {
        int tag = ints[pos++];
        switch (tag) {
            case 0:
                return null;
            case 1:
                return (double) Float.intBitsToFloat(ints[pos++]);
            case 2:
                return strings[ints[pos++]];
            case 3: {
                int n = ints[pos++];
                List<Object> l = new ArrayList<>(n);
                for (int i = 0; i < n; i++) {
                    l.add(value());
                }
                return l;
            }
            case 4: {
                int n = ints[pos++];
                Map<String, Object> m = new LinkedHashMap<>();
                for (int i = 0; i < n; i++) {
                    String key = strings[ints[pos++]];
                    m.put(key, value());
                }
                return m;
            }
            case 5:
                return Boolean.FALSE;
            case 6:
                return Boolean.TRUE;
            default:
                throw new IllegalArgumentException("Not clock data (tag " + tag + ")");
        }
    }
}

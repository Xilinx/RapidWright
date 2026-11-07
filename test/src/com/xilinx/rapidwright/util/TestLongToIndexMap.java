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

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

public class TestLongToIndexMap {

    @Test
    public void testIndicesInOrderOfAddition() {
        LongToIndexMap map = new LongToIndexMap();
        Assertions.assertEquals(0, map.add(42L));
        Assertions.assertEquals(1, map.add(-7L));
        Assertions.assertEquals(0, map.add(42L));
        Assertions.assertEquals(2, map.add(0L));
        Assertions.assertEquals(3, map.size());
        Assertions.assertEquals(1, map.get(-7L));
        Assertions.assertEquals(2, map.get(0L));
        Assertions.assertEquals(-1, map.get(43L));
        Assertions.assertEquals(-7L, map.getKey(1));
        Assertions.assertArrayEquals(new long[]{42L, -7L, 0L}, map.keys());
        Assertions.assertThrows(IndexOutOfBoundsException.class, () -> map.getKey(3));
    }

    @Test
    public void testGrowsBeyondExpectedSize() {
        LongToIndexMap map = new LongToIndexMap(2);
        Map<Long, Integer> expected = new HashMap<>();
        List<Long> order = new ArrayList<>();
        Random random = new Random(0);
        for (int i = 0; i < 100_000; i++) {
            // (few distinct high bits, as packed tile and wire indices have)
            long k = ((long) random.nextInt(1000) << 32) | random.nextInt(100);
            Integer index = expected.get(k);
            if (index == null) {
                index = order.size();
                expected.put(k, index);
                order.add(k);
            }
            Assertions.assertEquals((int) index, map.add(k));
        }
        Assertions.assertEquals(order.size(), map.size());
        long[] keys = map.keys();
        for (int i = 0; i < order.size(); i++) {
            Assertions.assertEquals((long) order.get(i), keys[i]);
            Assertions.assertEquals(i, map.get(order.get(i)));
        }
    }
}

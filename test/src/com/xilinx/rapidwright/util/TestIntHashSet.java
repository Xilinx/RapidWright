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

import java.util.HashSet;
import java.util.Random;
import java.util.Set;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

public class TestIntHashSet {

    @Test
    public void testAddContains() {
        IntHashSet set = new IntHashSet();
        Assertions.assertTrue(set.isEmpty());
        Assertions.assertTrue(set.add(5));
        Assertions.assertFalse(set.add(5));
        Assertions.assertTrue(set.add(-5));
        Assertions.assertTrue(set.contains(5));
        Assertions.assertTrue(set.contains(-5));
        Assertions.assertFalse(set.contains(6));
        Assertions.assertEquals(2, set.size());
    }

    @Test
    public void testZero() {
        // (0 marks the empty slots of the table, so is kept aside)
        IntHashSet set = new IntHashSet();
        Assertions.assertFalse(set.contains(0));
        Assertions.assertTrue(set.add(0));
        Assertions.assertFalse(set.add(0));
        Assertions.assertTrue(set.contains(0));
        Assertions.assertEquals(1, set.size());
    }

    @Test
    public void testGrowsBeyondExpectedSize() {
        IntHashSet set = new IntHashSet(2);
        Set<Integer> expected = new HashSet<>();
        Random random = new Random(0);
        for (int i = 0; i < 100_000; i++) {
            int v = random.nextInt();
            Assertions.assertEquals(expected.add(v), set.add(v));
        }
        Assertions.assertEquals(expected.size(), set.size());
        for (int v : expected) {
            Assertions.assertTrue(set.contains(v));
        }
        for (int i = 0; i < 1000; i++) {
            int v = random.nextInt();
            Assertions.assertEquals(expected.contains(v), set.contains(v));
        }
    }
}

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

package com.xilinx.rapidwright.support;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

public class TestShardAssignment {

    private static Map<String, List<String>> classMethods() {
        Map<String, List<String>> classMethods = new TreeMap<>();
        classMethods.put("a.Heavy", Arrays.asList("a.Heavy#m1()", "a.Heavy#m2()", "a.Heavy#m3()"));
        classMethods.put("a.Light1", Arrays.asList("a.Light1#m()"));
        classMethods.put("a.Light2", Arrays.asList("a.Light2#m()", "a.Light2#n()"));
        classMethods.put("a.Unknown", Arrays.asList("a.Unknown#m()"));
        return classMethods;
    }

    private static Map<String, Double> durations() {
        Map<String, Double> durations = new HashMap<>();
        durations.put("a.Heavy#m1()", 100.0);
        durations.put("a.Heavy#m2()", 90.0);
        durations.put("a.Heavy#m3()", 80.0);
        durations.put("a.Light1#m()", 10.0);
        durations.put("a.Light2#m()", 5.0);
        durations.put("a.Light2#n()", 5.0);
        return durations;
    }

    @Test
    public void testEveryMethodIsInExactlyOneShard() {
        Map<String, List<String>> classMethods = classMethods();
        ShardAssignment assignment = ShardAssignment.assign(classMethods, durations(), 3);
        for (Map.Entry<String, List<String>> e : classMethods.entrySet()) {
            for (String method : e.getValue()) {
                String unit = assignment.getUnit(e.getKey(), method);
                Integer shard = assignment.unitShards.get(unit);
                Assertions.assertNotNull(shard, method);
                int found = 0;
                for (int i = 1; i <= 3; i++) {
                    found += assignment.getUnits(i).contains(unit) ? 1 : 0;
                }
                Assertions.assertEquals(1, found, method);
            }
        }
        // 291 s in total: 100 + 90 + 80 + 10 + 5 + 5 + 1 (the unknown method)
        Assertions.assertEquals(291.0, assignment.totalSeconds, 1e-9);
        Assertions.assertEquals(291.0, Arrays.stream(assignment.shardSeconds).sum(), 1e-9);
    }

    @Test
    public void testOnlyHeavyClassesAreSplit() {
        ShardAssignment assignment = ShardAssignment.assign(classMethods(), durations(), 3);
        // A quarter of a shard's share is 291 / 3 / 4 = 24.25 s
        Assertions.assertTrue(assignment.splitClasses.contains("a.Heavy"));
        Assertions.assertFalse(assignment.splitClasses.contains("a.Light1"));
        Assertions.assertFalse(assignment.splitClasses.contains("a.Light2"));
        // The three heavy methods go to three different shards
        Assertions.assertNotEquals(assignment.unitShards.get("a.Heavy#m1()"), assignment.unitShards.get("a.Heavy#m2()"));
        Assertions.assertNotEquals(assignment.unitShards.get("a.Heavy#m1()"), assignment.unitShards.get("a.Heavy#m3()"));
        Assertions.assertNotEquals(assignment.unitShards.get("a.Heavy#m2()"), assignment.unitShards.get("a.Heavy#m3()"));
        Assertions.assertTrue(Arrays.stream(assignment.shardSeconds).max().getAsDouble() <= 100.0);
    }

    @Test
    public void testAssignmentIsDeterministic() {
        ShardAssignment a = ShardAssignment.assign(classMethods(), durations(), 2);
        ShardAssignment b = ShardAssignment.assign(new TreeMap<>(classMethods()), durations(), 2);
        Assertions.assertEquals(a.unitShards, b.unitShards);
    }
}

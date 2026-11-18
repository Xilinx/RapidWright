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

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * An assignment of test classes, or of the test methods of split classes, to shards: used to split tests between CI
 * machines ({@link TestShardFilter}) or LSF jobs (LaunchTestsOnLsf).
 */
public class ShardAssignment {

    /** Estimated seconds of test methods without a recorded duration */
    public static final double DEFAULT_METHOD_SECONDS = 1.0;

    /** Classes estimated to take longer than this fraction of a shard's share are split into methods */
    public static final double SPLIT_CLASS_FRACTION_OF_SHARD = 0.25;

    /** Shard (1-based) of each unit: a test class name, or a method key of a split class */
    public final Map<String, Integer> unitShards = new HashMap<>();

    /** Classes whose methods are assigned individually */
    public final Set<String> splitClasses = new HashSet<>();

    /** Estimated seconds of each shard (index 0 is shard 1) */
    public final double[] shardSeconds;

    /** Number of units of each shard (index 0 is shard 1) */
    public final int[] shardUnits;

    /** Estimated seconds of all shards together */
    public double totalSeconds;

    private ShardAssignment(int numShards) {
        shardSeconds = new double[numShards];
        shardUnits = new int[numShards];
    }

    /**
     * @return The unit (class name, or method key if the class is split) a test method belongs to.
     */
    public String getUnit(String className, String methodKey) {
        return splitClasses.contains(className) ? methodKey : className;
    }

    /**
     * @return The units of the given shard (1-based), in a deterministic order.
     */
    public List<String> getUnits(int shard) {
        List<String> units = new ArrayList<>();
        for (Map.Entry<String, Integer> e : unitShards.entrySet()) {
            if (e.getValue() == shard) {
                units.add(e.getKey());
            }
        }
        Collections.sort(units);
        return units;
    }

    /**
     * Assigns test classes to shards, longest first to the least-loaded shard. Classes estimated to take more than
     * {@link #SPLIT_CLASS_FRACTION_OF_SHARD} of a shard's share are split into their test methods, which are assigned
     * individually.
     * @param classMethods The method keys (see {@link TestShardFilter#getMethodKey}) of each top-level test class.
     * @param durations Estimated seconds per method key; missing methods count as {@value #DEFAULT_METHOD_SECONDS} s.
     * @param numShards Number of shards.
     * @return The assignment; the same inputs always give the same assignment.
     */
    public static ShardAssignment assign(Map<String, List<String>> classMethods, Map<String, Double> durations,
                                         int numShards) {
        ShardAssignment assignment = new ShardAssignment(numShards);
        Map<String, Double> classSeconds = new HashMap<>();
        for (Map.Entry<String, List<String>> e : classMethods.entrySet()) {
            double seconds = 0;
            for (String method : e.getValue()) {
                seconds += durations.getOrDefault(method, DEFAULT_METHOD_SECONDS);
            }
            classSeconds.put(e.getKey(), seconds);
            assignment.totalSeconds += seconds;
        }

        // Units to assign: whole classes, or the methods of the longest classes
        double splitSeconds = SPLIT_CLASS_FRACTION_OF_SHARD * assignment.totalSeconds / numShards;
        Map<String, Double> units = new HashMap<>();
        for (Map.Entry<String, List<String>> e : classMethods.entrySet()) {
            if (classSeconds.get(e.getKey()) <= splitSeconds) {
                units.put(e.getKey(), classSeconds.get(e.getKey()));
                continue;
            }
            assignment.splitClasses.add(e.getKey());
            for (String method : e.getValue()) {
                units.merge(method, durations.getOrDefault(method, DEFAULT_METHOD_SECONDS), Double::sum);
            }
        }

        // Longest first (ties by name, so that the assignment is deterministic)
        List<Map.Entry<String, Double>> sorted = new ArrayList<>(units.entrySet());
        sorted.sort(Comparator.<Map.Entry<String, Double>>comparingDouble(Map.Entry::getValue).reversed()
                .thenComparing(Map.Entry::getKey));
        for (Map.Entry<String, Double> unit : sorted) {
            int best = 0;
            for (int i = 1; i < numShards; i++) {
                if (assignment.shardSeconds[i] < assignment.shardSeconds[best]) {
                    best = i;
                }
            }
            assignment.shardSeconds[best] += unit.getValue();
            assignment.shardUnits[best]++;
            assignment.unitShards.put(unit.getKey(), best + 1);
        }
        return assignment;
    }
}

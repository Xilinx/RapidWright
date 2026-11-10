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

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.platform.engine.FilterResult;
import org.junit.platform.engine.TestDescriptor;
import org.junit.platform.engine.TestSource;
import org.junit.platform.engine.support.descriptor.ClassSource;
import org.junit.platform.engine.support.descriptor.MethodSource;
import org.junit.platform.launcher.PostDiscoveryFilter;

/**
 * Runs only one shard of the tests, so that CI can split them between machines.
 *
 * Enabled by the system property {@value #SHARD_PROPERTY}, set to "i/n" to run shard i (1-based)
 * of n (Gradle: -PtestShard=i/n). Every shard discovers all tests and computes the same
 * assignment: test classes are assigned whole, except classes whose estimated duration exceeds a
 * quarter of a shard's share, which are split into their test methods. These units are assigned,
 * longest first, to the shard with the least estimated duration so far.
 *
 * Estimated durations come from {@value #DURATIONS_RESOURCE} (see {@link TestDurationsListener}
 * for how to regenerate it). Test methods missing from it count as
 * {@value #DEFAULT_METHOD_SECONDS} s, so new tests still run, in some shard.
 */
public class TestShardFilter implements PostDiscoveryFilter {

    public static final String SHARD_PROPERTY = "rapidwright.test.shard";

    public static final String DURATIONS_RESOURCE = "/com/xilinx/rapidwright/support/test-durations.txt";

    public static final double DEFAULT_METHOD_SECONDS = 1.0;

    /** Classes estimated to take longer than this fraction of a shard's share are split into methods */
    private static final double SPLIT_CLASS_FRACTION_OF_SHARD = 0.25;

    private final int shard;
    private final int numShards;

    /** The root the assignment below was computed for, and the shard of each unit (class or method key) */
    private TestDescriptor assignedRoot;
    private final Map<String, Integer> unitShards = new HashMap<>();
    private final Map<String, Boolean> splitClasses = new HashMap<>();

    public TestShardFilter() {
        String value = System.getProperty(SHARD_PROPERTY);
        if (value == null || value.isEmpty()) {
            shard = numShards = 0;
            return;
        }
        String[] parts = value.split("/");
        try {
            shard = Integer.parseInt(parts[0].trim());
            numShards = Integer.parseInt(parts[1].trim());
        } catch (RuntimeException e) {
            throw new IllegalArgumentException(SHARD_PROPERTY + " must be \"i/n\", not \"" + value + "\"", e);
        }
        if (parts.length != 2 || numShards < 1 || shard < 1 || shard > numShards) {
            throw new IllegalArgumentException(SHARD_PROPERTY + " must be \"i/n\" with 1 <= i <= n, not \"" + value + "\"");
        }
    }

    /**
     * @return A key that identifies a test method (including its parameter types, to tell overloads apart).
     */
    public static String getMethodKey(MethodSource source) {
        return source.getClassName() + "#" + source.getMethodName()
                + "(" + source.getMethodParameterTypes().replace(" ", "") + ")";
    }

    /**
     * @return The test method source of this descriptor, if it is a test method (or test template/factory)
     *         rather than one invocation of a test template.
     */
    static Optional<MethodSource> getTestMethodSource(TestDescriptor descriptor) {
        Optional<TestSource> source = descriptor.getSource();
        if (!source.isPresent() || !(source.get() instanceof MethodSource)) {
            return Optional.empty();
        }
        Optional<TestDescriptor> parent = descriptor.getParent();
        if (parent.isPresent() && parent.get().getSource().filter(s -> s instanceof MethodSource).isPresent()) {
            return Optional.empty();
        }
        return Optional.of((MethodSource) source.get());
    }

    /**
     * @return The outermost test class containing this descriptor (to keep nested classes with it).
     */
    private static Optional<String> getTopLevelClassName(TestDescriptor descriptor) {
        String className = null;
        for (TestDescriptor d = descriptor; d != null; d = d.getParent().orElse(null)) {
            Optional<TestSource> source = d.getSource();
            if (source.isPresent() && source.get() instanceof ClassSource) {
                className = ((ClassSource) source.get()).getClassName();
            }
        }
        return Optional.ofNullable(className);
    }

    static Map<String, Double> readDurations() {
        Map<String, Double> durations = new HashMap<>();
        InputStream in = TestShardFilter.class.getResourceAsStream(DURATIONS_RESOURCE);
        if (in == null) {
            return durations;
        }
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith("#")) {
                    continue;
                }
                String[] parts = line.split("\\s+", 2);
                durations.put(parts[1], Double.parseDouble(parts[0]));
            }
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        return durations;
    }

    private static void collectTestMethods(TestDescriptor descriptor, Map<String, List<String>> classMethods) {
        Optional<MethodSource> source = getTestMethodSource(descriptor);
        if (source.isPresent()) {
            Optional<String> className = getTopLevelClassName(descriptor);
            if (className.isPresent()) {
                classMethods.computeIfAbsent(className.get(), k -> new ArrayList<>()).add(getMethodKey(source.get()));
            }
            return;
        }
        for (TestDescriptor child : descriptor.getChildren()) {
            collectTestMethods(child, classMethods);
        }
    }

    private void assignShards(TestDescriptor root) {
        unitShards.clear();
        splitClasses.clear();
        Map<String, Double> durations = readDurations();
        Map<String, List<String>> classMethods = new HashMap<>();
        collectTestMethods(root, classMethods);

        Map<String, Double> classSeconds = new HashMap<>();
        double totalSeconds = 0;
        for (Map.Entry<String, List<String>> e : classMethods.entrySet()) {
            double seconds = 0;
            for (String method : e.getValue()) {
                seconds += durations.getOrDefault(method, DEFAULT_METHOD_SECONDS);
            }
            classSeconds.put(e.getKey(), seconds);
            totalSeconds += seconds;
        }

        // Units to assign: whole classes, or the methods of the longest classes
        double splitSeconds = SPLIT_CLASS_FRACTION_OF_SHARD * totalSeconds / numShards;
        Map<String, Double> units = new HashMap<>();
        for (Map.Entry<String, List<String>> e : classMethods.entrySet()) {
            boolean split = classSeconds.get(e.getKey()) > splitSeconds;
            splitClasses.put(e.getKey(), split);
            if (!split) {
                units.put(e.getKey(), classSeconds.get(e.getKey()));
                continue;
            }
            for (String method : e.getValue()) {
                units.merge(method, durations.getOrDefault(method, DEFAULT_METHOD_SECONDS), Double::sum);
            }
        }

        // Longest first (ties by name, so that every shard computes the same assignment)
        List<Map.Entry<String, Double>> sorted = new ArrayList<>(units.entrySet());
        sorted.sort(Comparator.<Map.Entry<String, Double>>comparingDouble(Map.Entry::getValue).reversed()
                .thenComparing(Map.Entry::getKey));
        double[] shardSeconds = new double[numShards];
        int[] shardUnits = new int[numShards];
        for (Map.Entry<String, Double> unit : sorted) {
            int best = 0;
            for (int i = 1; i < numShards; i++) {
                if (shardSeconds[i] < shardSeconds[best]) {
                    best = i;
                }
            }
            shardSeconds[best] += unit.getValue();
            shardUnits[best]++;
            unitShards.put(unit.getKey(), best + 1);
        }
        assignedRoot = root;
        System.out.printf("Test shard %d/%d: %d of %d classes/methods, estimated %.0f s of %.0f s%n",
                shard, numShards, shardUnits[shard - 1], units.size(), shardSeconds[shard - 1], totalSeconds);
    }

    @Override
    public FilterResult apply(TestDescriptor descriptor) {
        if (numShards == 0) {
            return FilterResult.included("Not sharded");
        }
        Optional<MethodSource> source = getTestMethodSource(descriptor);
        Optional<String> className = getTopLevelClassName(descriptor);
        if (!source.isPresent() || !className.isPresent()) {
            return FilterResult.included("Not a test method");
        }
        TestDescriptor root = descriptor;
        while (root.getParent().isPresent()) {
            root = root.getParent().get();
        }
        if (root != assignedRoot) {
            assignShards(root);
        }
        String unit = splitClasses.getOrDefault(className.get(), false) ? getMethodKey(source.get()) : className.get();
        int unitShard = unitShards.getOrDefault(unit, 1);
        return unitShard == shard
                ? FilterResult.included("In shard " + shard + "/" + numShards)
                : FilterResult.excluded("In shard " + unitShard + "/" + numShards);
    }
}

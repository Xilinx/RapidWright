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
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
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
 * assignment (see {@link ShardAssignment}).
 *
 * Estimated durations are read from the file named by the system property
 * {@value #DURATIONS_IN_PROPERTY} (Gradle: -PtestDurations=file), as written by
 * {@link TestDurationsListener}; CI passes on the durations recorded by the latest run of master.
 * Test methods missing from it (or all of them, without the file) count as
 * {@value #DEFAULT_METHOD_SECONDS} s, so every test still runs, in exactly one shard: the
 * durations only affect how evenly the tests are split.
 */
public class TestShardFilter implements PostDiscoveryFilter {

    public static final String SHARD_PROPERTY = "rapidwright.test.shard";

    /** System property naming the file of estimated test durations to balance the shards with */
    public static final String DURATIONS_IN_PROPERTY = "rapidwright.test.durationsIn";

    public static final double DEFAULT_METHOD_SECONDS = ShardAssignment.DEFAULT_METHOD_SECONDS;

    private final int shard;
    private final int numShards;

    /** The root the assignment below was computed for */
    private TestDescriptor assignedRoot;
    private ShardAssignment assignment;

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
    public static Optional<MethodSource> getTestMethodSource(TestDescriptor descriptor) {
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
    public static Optional<String> getTopLevelClassName(TestDescriptor descriptor) {
        String className = null;
        for (TestDescriptor d = descriptor; d != null; d = d.getParent().orElse(null)) {
            Optional<TestSource> source = d.getSource();
            if (source.isPresent() && source.get() instanceof ClassSource) {
                className = ((ClassSource) source.get()).getClassName();
            }
        }
        return Optional.ofNullable(className);
    }

    /**
     * @return The estimated test durations from the file named by {@value #DURATIONS_IN_PROPERTY},
     *         or none if it is not set or the file does not exist.
     */
    static Map<String, Double> readDurations() {
        String durationsIn = System.getProperty(DURATIONS_IN_PROPERTY);
        return durationsIn == null || durationsIn.isEmpty() ? new HashMap<>() : readDurations(Paths.get(durationsIn));
    }

    /**
     * Reads estimated test durations, as written by {@link TestDurationsListener}.
     * @param path The file to read.
     * @return Seconds per method key, empty if the file does not exist.
     */
    public static Map<String, Double> readDurations(Path path) {
        if (!Files.exists(path)) {
            return new HashMap<>();
        }
        try {
            return readDurations(Files.newInputStream(path));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static Map<String, Double> readDurations(InputStream in) {
        Map<String, Double> durations = new HashMap<>();
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

    /**
     * @return The test methods of each top-level test class under this descriptor, as method keys.
     */
    public static Map<String, List<String>> collectTestMethods(TestDescriptor root) {
        Map<String, List<String>> classMethods = new HashMap<>();
        collectTestMethods(root, classMethods);
        return classMethods;
    }

    private void assignShards(TestDescriptor root) {
        Map<String, Double> durations = readDurations();
        assignment = ShardAssignment.assign(collectTestMethods(root), durations, numShards);
        assignedRoot = root;
        System.out.printf("Test shard %d/%d: %d of %d classes/methods, estimated %.0f s of %.0f s"
                + " (from %d recorded test durations)%n",
                shard, numShards, assignment.shardUnits[shard - 1], assignment.unitShards.size(),
                assignment.shardSeconds[shard - 1], assignment.totalSeconds, durations.size());
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
        String unit = assignment.getUnit(className.get(), getMethodKey(source.get()));
        int unitShard = assignment.unitShards.getOrDefault(unit, 1);
        return unitShard == shard
                ? FilterResult.included("In shard " + shard + "/" + numShards)
                : FilterResult.excluded("In shard " + unitShard + "/" + numShards);
    }
}

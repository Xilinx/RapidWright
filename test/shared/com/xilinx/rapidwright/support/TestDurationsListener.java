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

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;

import org.junit.platform.engine.TestExecutionResult;
import org.junit.platform.engine.support.descriptor.MethodSource;
import org.junit.platform.launcher.TestExecutionListener;
import org.junit.platform.launcher.TestIdentifier;
import org.junit.platform.launcher.TestPlan;

/**
 * Records how long each test method (all invocations of a parameterized test together) takes, for
 * {@link TestShardFilter} to balance the CI shards.
 *
 * Enabled by the system property {@value #DURATIONS_FILE_PROPERTY}, set to the file to write
 * (Gradle: -PrecordTestDurations writes build/test-durations/testJava.txt). To refresh the
 * estimates, run all tests without sharding (CI's slowest configuration is single-threaded), e.g.
 * <pre>./gradlew testJava -PlimitToSingleProc=single-threaded -PrecordTestDurations</pre>
 * and copy the file over test/resources{@value TestShardFilter#DURATIONS_RESOURCE}.
 */
public class TestDurationsListener implements TestExecutionListener {

    public static final String DURATIONS_FILE_PROPERTY = "rapidwright.test.durationsFile";

    private final String durationsFile;

    /**
     * Writes durations to the file given by the system property {@value #DURATIONS_FILE_PROPERTY}, if set.
     */
    public TestDurationsListener() {
        this(System.getProperty(DURATIONS_FILE_PROPERTY));
    }

    /**
     * @param durationsFile The file to write durations to, or null to record nothing.
     */
    public TestDurationsListener(String durationsFile) {
        this.durationsFile = durationsFile;
    }

    private final Map<String, Long> startNanos = new ConcurrentHashMap<>();

    private final Map<String, Double> seconds = new ConcurrentHashMap<>();

    private Optional<String> getMethodKey(TestIdentifier id, TestPlan plan) {
        if (!id.getSource().isPresent() || !(id.getSource().get() instanceof MethodSource)) {
            return Optional.empty();
        }
        // Only the test method itself, not each invocation of a test template
        Optional<TestIdentifier> parent = plan.getParent(id);
        if (parent.isPresent() && parent.get().getSource().filter(s -> s instanceof MethodSource).isPresent()) {
            return Optional.empty();
        }
        return Optional.of(TestShardFilter.getMethodKey((MethodSource) id.getSource().get()));
    }

    private TestPlan testPlan;

    @Override
    public void testPlanExecutionStarted(TestPlan testPlan) {
        this.testPlan = testPlan;
    }

    @Override
    public void executionStarted(TestIdentifier id) {
        if (durationsFile != null) {
            getMethodKey(id, testPlan).ifPresent(key -> startNanos.put(key, System.nanoTime()));
        }
    }

    @Override
    public void executionFinished(TestIdentifier id, TestExecutionResult result) {
        if (durationsFile == null) {
            return;
        }
        getMethodKey(id, testPlan).ifPresent(key -> {
            Long start = startNanos.remove(key);
            if (start != null) {
                seconds.merge(key, (System.nanoTime() - start) / 1e9, Double::sum);
            }
        });
    }

    @Override
    public void testPlanExecutionFinished(TestPlan testPlan) {
        if (durationsFile == null) {
            return;
        }
        List<String> lines = new ArrayList<>();
        lines.add("# Seconds per test method, written by " + TestDurationsListener.class.getName());
        for (Map.Entry<String, Double> e : new TreeMap<>(seconds).entrySet()) {
            lines.add(String.format(Locale.ROOT, "%.1f %s", e.getValue(), e.getKey()));
        }
        try {
            Path path = Paths.get(durationsFile);
            if (path.getParent() != null) {
                Files.createDirectories(path.getParent());
            }
            Files.write(path, lines, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}

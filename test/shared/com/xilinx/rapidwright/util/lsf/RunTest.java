/*
 * Copyright (c) 2022, Xilinx, Inc.
 * Copyright (c) 2022, Advanced Micro Devices, Inc.
 * All rights reserved.
 *
 * Author: Jakob Wenzel, Xilinx Research Labs.
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

package com.xilinx.rapidwright.util.lsf;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;

import com.xilinx.rapidwright.support.LargeTest;
import com.xilinx.rapidwright.support.TestDurationsListener;
import com.xilinx.rapidwright.support.TestShardFilter;
import org.junit.platform.engine.FilterResult;
import org.junit.platform.engine.TestDescriptor;
import org.junit.platform.engine.TestTag;
import org.junit.platform.engine.UniqueId;
import org.junit.platform.engine.support.descriptor.MethodSource;
import org.junit.platform.launcher.Launcher;
import org.junit.platform.launcher.LauncherDiscoveryRequest;
import org.junit.platform.launcher.PostDiscoveryFilter;
import org.junit.platform.launcher.TestExecutionListener;
import org.junit.platform.launcher.TestIdentifier;
import org.junit.platform.launcher.core.LauncherFactory;
import org.junit.platform.launcher.listeners.SummaryGeneratingListener;
import org.junit.platform.launcher.listeners.TestExecutionSummary;
import org.junit.platform.reporting.legacy.xml.LegacyXmlReportGeneratingListener;

/**
 * JUnit test runner that runs a specific subset of tests. Output is written to the current directory.
 *
 * Arguments: the tests jar, then either a test's unique ID (to run that test only) or an empty string (to run all
 * tests not tagged as LSF tests). With an empty string, two more optional arguments are accepted: a file listing the
 * test classes and test method keys (see {@link TestShardFilter#getMethodKey}) to run, one per line, so that the tests
 * not tagged as LSF tests can be split between several jobs; and a file to write each test method's duration to
 * (see {@link TestDurationsListener}).
 */
public class RunTest {
    /** File in the working directory that this runner writes its start and end times (in ms) to */
    public static final String RUN_TIMES_FILE = "run-times.txt";

    public static boolean isLsfTest(Set<TestTag> tags) {
        return tags.stream().anyMatch(t -> t.getName().equals(LargeTest.LARGE_TEST));
    }

    public static boolean isLsfTest(TestIdentifier ti, Map<String, TestIdentifier> idToTest) {
        if (isLsfTest(ti.getTags())) {
            return true;
        }
        return ti.getParentId().map(p-> isLsfTest(idToTest.get(p), idToTest)).orElse(false);
    }



    /**
     * @param units Test classes and test method keys.
     * @return Whether this descriptor is a test method listed in units, or belongs to a test class listed in units.
     *         Descriptors that are not test methods (e.g. classes) are included, so that their tests can be.
     */
    public static boolean isInUnits(TestDescriptor descriptor, Set<String> units) {
        Optional<MethodSource> source = TestShardFilter.getTestMethodSource(descriptor);
        Optional<String> className = TestShardFilter.getTopLevelClassName(descriptor);
        if (!source.isPresent() || !className.isPresent()) {
            return true;
        }
        return units.contains(className.get()) || units.contains(TestShardFilter.getMethodKey(source.get()));
    }

    private void executeTests(PrintWriter out, Predicate<TestDescriptor> discoveryFilter, UniqueId filterName,
                              Path testsJar, String durationsFile) {
        Launcher launcher = LauncherFactory.create();
        SummaryGeneratingListener summaryListener = registerListeners(out, launcher, filterName);
        if (durationsFile != null) {
            launcher.registerTestExecutionListeners(new TestDurationsListener(durationsFile));
        }

        LauncherDiscoveryRequest discoveryRequest = LaunchTestsOnLsf.getLauncherDiscoveryRequestBuilder(testsJar)
                        .filters((PostDiscoveryFilter) descriptor -> {
                            if (!discoveryFilter.test(descriptor)) {
                                return FilterResult.excluded("not our test");
                            }
                            return FilterResult.included("");
                        }).build();
        launcher.execute(discoveryRequest);

        TestExecutionSummary summary = summaryListener.getSummary();
        if (summary.getTotalFailureCount() > 0 ) {
            printSummary(summary, out);
        }
    }

    private SummaryGeneratingListener registerListeners(PrintWriter out, Launcher launcher, UniqueId filter) {
        SummaryGeneratingListener summaryListener = new SummaryGeneratingListener();
        TestExecutionListener[] listeners = new TestExecutionListener[]{
                summaryListener,
                createXmlWritingListener(out)
        };
        if (filter == null) {
            launcher.registerTestExecutionListeners(listeners);
        } else {
            launcher.registerTestExecutionListeners(new ModifyingExecutionListener(filter, Arrays.asList(listeners), ModifyingExecutionListener::modifyLegacyName));
        }
        return summaryListener;
    }

    private TestExecutionListener createXmlWritingListener(PrintWriter out) {
        return new LegacyXmlReportGeneratingListener(Paths.get("."), out);
    }

    private void printSummary(TestExecutionSummary summary, PrintWriter out) {
        summary.printFailuresTo(out);
        summary.printTo(out);
    }

    public static void main(String[] args) throws IOException {
        Path runTimes = Paths.get(RUN_TIMES_FILE);
        Files.write(runTimes, Arrays.asList("start " + System.currentTimeMillis()), StandardCharsets.UTF_8);
        Predicate<TestDescriptor> discoveryFilter;
        Path testsJar = Paths.get(args[0]);
        UniqueId filterArg = args[1].isEmpty() ? null : UniqueId.parse(args[1]);
        String durationsFile = null;
        if (filterArg == null) {
            Set<String> units = args.length > 2 && !args[2].isEmpty()
                    ? new HashSet<>(Files.readAllLines(Paths.get(args[2]), StandardCharsets.UTF_8))
                    : null;
            durationsFile = args.length > 3 && !args[3].isEmpty() ? args[3] : null;
            discoveryFilter = (TestDescriptor descriptor) -> !RunTest.isLsfTest(descriptor.getTags())
                    && (units == null || isInUnits(descriptor, units));
        } else {
            discoveryFilter = (TestDescriptor descriptor) -> filterArg.hasPrefix(descriptor.getUniqueId());
            //Filtering tests in discovery only works on method level.
            //We want finer control of dynamic tests and test templates, so intercept actual test runs
            LsfInterceptor.ENABLED = true;
            LsfInterceptor.allowedId = filterArg.toString();
        }
        new RunTest().executeTests(new PrintWriter(System.out), discoveryFilter, filterArg, testsJar, durationsFile);

        XmlReportPatcher.fixOutputXmls(Paths.get("."));
        Files.write(runTimes, Arrays.asList("end " + System.currentTimeMillis()), StandardCharsets.UTF_8,
                StandardOpenOption.APPEND);
    }



}


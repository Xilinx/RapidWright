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
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

import com.xilinx.rapidwright.support.LargeTest;
import org.junit.platform.engine.FilterResult;
import org.junit.platform.engine.TestDescriptor;
import org.junit.platform.engine.TestTag;
import org.junit.platform.engine.UniqueId;
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
 * If called with no parameters: Runs all tests not tagged as LSF
 * If called with one parameter: Run that specific test identified by unique ID
 */
public class RunTest {
    public static boolean isLsfTest(Set<TestTag> tags) {
        return tags.stream().anyMatch(t -> t.getName().equals(LargeTest.LARGE_TEST));
    }

    public static boolean isLsfTest(TestIdentifier ti, Map<String, TestIdentifier> idToTest) {
        if (isLsfTest(ti.getTags())) {
            return true;
        }
        return ti.getParentId().map(p-> isLsfTest(idToTest.get(p), idToTest)).orElse(false);
    }



    private void executeTests(PrintWriter out, Predicate<TestDescriptor> discoveryFilter, UniqueId filterName, Path testsJar) {
        Launcher launcher = LauncherFactory.create();
        SummaryGeneratingListener summaryListener = registerListeners(out, launcher, filterName);

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
        Predicate<TestDescriptor> discoveryFilter;
        Path testsJar = Paths.get(args[0]);
        UniqueId filterArg = args[1].isEmpty() ? null : UniqueId.parse(args[1]);
        if (filterArg == null) {
            discoveryFilter = (TestDescriptor descriptor) -> !RunTest.isLsfTest(descriptor.getTags());
        } else {
            discoveryFilter = (TestDescriptor descriptor) -> filterArg.hasPrefix(descriptor.getUniqueId());
            //Filtering tests in discovery only works on method level.
            //We want finer control of dynamic tests and test templates, so intercept actual test runs
            LsfInterceptor.ENABLED = true;
            LsfInterceptor.allowedId = filterArg.toString();
        }
        new RunTest().executeTests(new PrintWriter(System.out), discoveryFilter, filterArg, testsJar);

        XmlReportPatcher.fixOutputXmls(Paths.get("."));
    }



}


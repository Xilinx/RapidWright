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

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import com.xilinx.rapidwright.support.LargeTest;
import com.xilinx.rapidwright.util.FileTools;
import com.xilinx.rapidwright.util.Installer;
import com.xilinx.rapidwright.util.Job;
import com.xilinx.rapidwright.util.JobQueue;
import com.xilinx.rapidwright.util.LSFJob;
import com.xilinx.rapidwright.util.Pair;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.engine.config.JupiterConfiguration;
import org.junit.platform.engine.TestExecutionResult;
import org.junit.platform.engine.TestSource;
import org.junit.platform.engine.discovery.DiscoverySelectors;
import org.junit.platform.engine.support.descriptor.ClassSource;
import org.junit.platform.engine.support.descriptor.MethodSource;
import org.junit.platform.launcher.Launcher;
import org.junit.platform.launcher.LauncherConstants;
import org.junit.platform.launcher.LauncherDiscoveryRequest;
import org.junit.platform.launcher.TestExecutionListener;
import org.junit.platform.launcher.TestIdentifier;
import org.junit.platform.launcher.TestPlan;
import org.junit.platform.launcher.core.LauncherDiscoveryRequestBuilder;
import org.junit.platform.launcher.core.LauncherFactory;

/**
 * JUnit test runner that creates LSF Jobs for individual tests.
 *
 * The working directory is cleared! Then outputs are placed there.
 */
public class LaunchTestsOnLsf {

    private static String toJobDir(String id) {
        if (id.isEmpty()) {
            return "regularTests";
        }
        return Installer.calculateMD5OfStream(new ByteArrayInputStream(id.getBytes(StandardCharsets.UTF_8)));
    }

    public static Stream<Pair<String, Integer>> discoverTests(Launcher launcher, TestPlan testPlan) {
        //We have to do a fake test execution to enumerate dynamic tests and test template instantiations
        LsfInterceptor.ENABLED = true;
        Map<String, TestIdentifier> ids = new HashMap<>();
        launcher.registerTestExecutionListeners(new TestExecutionListener() {
            @Override
            public void dynamicTestRegistered(TestIdentifier testIdentifier) {
                ids.put(testIdentifier.getUniqueId(), testIdentifier);
            }

            @Override
            public void executionSkipped(TestIdentifier testIdentifier, String reason) {
                ids.put(testIdentifier.getUniqueId(), testIdentifier);
            }

            @Override
            public void executionStarted(TestIdentifier testIdentifier) {
                ids.put(testIdentifier.getUniqueId(), testIdentifier);
            }

            @Override
            public void executionFinished(TestIdentifier testIdentifier, TestExecutionResult testExecutionResult) {
                ids.put(testIdentifier.getUniqueId(), testIdentifier);
            }
        });
        launcher.execute(testPlan);

        Stream<Pair<String, Integer>> lsfTests = ids.values().stream()
                .filter(TestIdentifier::isTest)
                .filter(ti -> RunTest.isLsfTest(ti, ids))
                .map(ti -> new Pair<>(ti.getUniqueId(), requireLsfMem(ti, ids)));

        if (ids.isEmpty()) {
            throw new RuntimeException("did not find any test?!");
        }

        return Stream.concat(lsfTests, Stream.of(new Pair<>("", LargeTest.DEFAULT_MAX_MEMORY_GB)));
    }

    public static void main(String[] args) {
        //Testcases may have been removed since the last run, delete all to be sure
        if (!FileTools.deleteFolderContents(".")) {
            throw new RuntimeException("could not empty working dir");
        }

        Path testsJar = Paths.get(args[0]);

        Launcher launcher = LauncherFactory.create();

        LauncherDiscoveryRequest request = getLauncherDiscoveryRequestBuilder(testsJar).build();
        TestPlan testPlan = launcher.discover(request);

        JobQueue jq = new JobQueue(false);
        Map<Job, String> jobsToTests = new HashMap<>();

        discoverTests(launcher, testPlan).forEach(pair -> {
            int memMB = pair.getSecond()*1024;
            LSFJob job = new LSFJob();
            job.setRunDir(toJobDir(pair.getFirst()));
            job.setRapidWrightCommand(RunTest.class, memMB, true,
                    '"'+testsJar.toString()+"\" \""+pair.getFirst()+'"');
            job.setLsfResourceMemoryLimit(memMB);
            jq.addJob(job);
            jobsToTests.put(job, pair.getFirst());
        });

        //Don't impose more limits on parallelism than what LSF allows us
        if (!jq.runAllToCompletion(Integer.MAX_VALUE)) {
            StringBuilder sb = new StringBuilder();
            sb.append("LSF workers failed to execute tests:");
            Map<String, String> perTestMessages = new HashMap<>();
            jobsToTests.forEach((job, test) -> {
                if (!job.jobWasSuccessful()) {
                    sb.append("\n");
                    sb.append("Job ").append(job.getJobNumber()).append(" running in ").append(job.getRunDir());
                    if (test.isEmpty()) {
                        sb.append(" executing non-lsf-tests");
                    } else {
                        sb.append(" executing test ").append(test);
                        Optional<List<String>> lastLogLines = job.getLastLogLines();
                        if (lastLogLines.isPresent()) {
                            String log = lastLogLines.get().stream().collect(Collectors.joining("\n","Execution failed. Last Lines of Log:\n",""));
                            perTestMessages.put(test, log);
                        } else {
                            perTestMessages.put(test, "Execution failed, no log was generated");
                        }
                    }
                }
            });
            StaticReportGenerator.writeMasterXmlLog(sb.toString(), perTestMessages, testPlan);

            System.exit(1);
        }
    }

    private static Optional<Integer> sourceToMem(TestSource ts) {
        if (ts instanceof ClassSource) {
            LargeTest annotation = ((ClassSource) ts).getJavaClass().getAnnotation(LargeTest.class);
            return Optional.ofNullable(annotation).map(LargeTest::max_memory_gb);
        } else if (ts instanceof MethodSource) {
            LargeTest annotation = ((MethodSource) ts).getJavaMethod().getAnnotation(LargeTest.class);
            return Optional.ofNullable(annotation).map(LargeTest::max_memory_gb);
        } else {
            throw new RuntimeException("Cannot get memory from "+ts);
        }
    }

    private static Optional<Integer> getLsfMem(TestIdentifier ti, Map<String, TestIdentifier> ids) {
        Optional<Integer> fromSource = ti.getSource().flatMap(LaunchTestsOnLsf::sourceToMem);
        if (fromSource.isPresent()) {
            return fromSource;
        }
        return ti.getParentId().flatMap(p->getLsfMem(ids.get(p), ids));
    }

    private static int requireLsfMem(TestIdentifier x, Map<String, TestIdentifier> ids) {
        return getLsfMem(x, ids).orElseThrow(()->new RuntimeException("Did not find memory requirement of test "+x.getUniqueId()));
    }

    @NotNull
    public static LauncherDiscoveryRequestBuilder getLauncherDiscoveryRequestBuilder(Path testsJar) {
        return LauncherDiscoveryRequestBuilder.request()
                .configurationParameter(
                        JupiterConfiguration.EXTENSIONS_AUTODETECTION_ENABLED_PROPERTY_NAME, "true"
                )
                .configurationParameter(LauncherConstants.CAPTURE_STDOUT_PROPERTY_NAME, "true")
                .configurationParameter(LauncherConstants.CAPTURE_STDERR_PROPERTY_NAME, "true")
                .selectors(DiscoverySelectors.selectClasspathRoots(Collections.singleton(testsJar)));
    }
}

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
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import com.xilinx.rapidwright.support.LargeTest;
import com.xilinx.rapidwright.support.ShardAssignment;
import com.xilinx.rapidwright.support.TestShardFilter;
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
/**
 * Runs the tests of a tests jar on LSF.
 *
 * Each test tagged {@link LargeTest} (each invocation, for parameterized tests) runs in its own job, with the memory
 * the tag asks for. The other ("regular") tests are split between several jobs. With estimated durations (a file
 * written by an earlier run, given by the system property {@value #DURATIONS_IN_PROPERTY}), the regular tests are
 * split into jobs of about {@value #REGULAR_JOB_TARGET_SECONDS} s each (see {@link ShardAssignment#assign}); without,
 * each test class runs in its own job. Each run writes the measured durations of the regular tests to
 * {@value #DURATIONS_FILE}, and the pending and run times of every job to {@value #JOBS_FILE}, in the working
 * directory.
 */
public class LaunchTestsOnLsf {

    /** System property naming a file of estimated test durations, as written to {@value #DURATIONS_FILE} */
    public static final String DURATIONS_IN_PROPERTY = "rapidwright.test.durationsIn";

    /** Measured durations of the regular tests, written to the working directory */
    public static final String DURATIONS_FILE = "test-durations.txt";

    /** Pending and run time of each job, written to the working directory */
    public static final String JOBS_FILE = "lsf-jobs.csv";

    /** Estimated duration to aim for in each job running regular tests */
    public static final int REGULAR_JOB_TARGET_SECONDS = 5 * 60;

    /** Upper limit on the number of jobs running regular tests, when estimated durations are available */
    public static final int MAX_REGULAR_JOBS = 32;

    /**
     * Memory reserved for each job running regular tests. Their JVM gets the default heap of
     * {@link LargeTest#DEFAULT_MAX_MEMORY_GB}; the rest leaves room for the Vivado processes some tests start.
     */
    public static final int REGULAR_JOB_RESERVED_GB = 8;

    private static final String UNITS_FILE = "units.txt";

    private static final String JOB_DURATIONS_FILE = "durations.txt";

    /** A job and what it runs */
    private static class TestJob {
        final LSFJob job;
        /** Unique id of the LSF test it runs, or null for a job running regular tests */
        final String testId;
        /** For jobs running regular tests: the test classes and method keys, and their estimated seconds */
        final List<String> units;
        final double estimatedSeconds;

        TestJob(LSFJob job, String testId, List<String> units, double estimatedSeconds) {
            this.job = job;
            this.testId = testId;
            this.units = units;
            this.estimatedSeconds = estimatedSeconds;
        }

        String describe() {
            return testId != null ? testId : units.size() + " regular test classes/methods";
        }
    }

    private static String toJobDir(String id) {
        if (id.isEmpty()) {
            return "regularTests";
        }
        return Installer.calculateMD5OfStream(new ByteArrayInputStream(id.getBytes(StandardCharsets.UTF_8)));
    }

    /**
     * Does a fake execution of the test plan to enumerate dynamic tests and test template invocations.
     * @return All tests and containers, by unique ID.
     */
    private static Map<String, TestIdentifier> enumerateTests(Launcher launcher, TestPlan testPlan) {
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
        if (ids.isEmpty()) {
            throw new RuntimeException("did not find any test?!");
        }
        return ids;
    }

    /**
     * @return The tests that run in their own job (unique ID and memory in GB), followed by an empty ID standing for
     *         all other tests.
     */
    public static Stream<Pair<String, Integer>> discoverTests(Launcher launcher, TestPlan testPlan) {
        Map<String, TestIdentifier> ids = enumerateTests(launcher, testPlan);
        return Stream.concat(getLsfTests(ids), Stream.of(new Pair<>("", LargeTest.DEFAULT_MAX_MEMORY_GB)));
    }

    private static Stream<Pair<String, Integer>> getLsfTests(Map<String, TestIdentifier> ids) {
        return ids.values().stream()
                .filter(TestIdentifier::isTest)
                .filter(ti -> RunTest.isLsfTest(ti, ids))
                .map(ti -> new Pair<>(ti.getUniqueId(), requireLsfMem(ti, ids)));
    }

    private static boolean hasMethodSource(TestIdentifier ti) {
        return ti != null && ti.getSource().filter(s -> s instanceof MethodSource).isPresent();
    }

    /**
     * @return The test method this test belongs to: the outermost node with a method source, so that the invocations
     *         of a test template and the dynamic tests of a test factory belong to their method.
     */
    private static Optional<TestIdentifier> getTestMethod(TestIdentifier ti, Map<String, TestIdentifier> ids) {
        TestIdentifier method = null;
        for (TestIdentifier t = ti; t != null; t = t.getParentId().map(ids::get).orElse(null)) {
            if (hasMethodSource(t)) {
                method = t;
            }
        }
        return Optional.ofNullable(method);
    }

    /**
     * @return The outermost test class containing this test (to keep nested classes with it), if any.
     */
    private static Optional<String> getTopLevelClassName(TestIdentifier ti, Map<String, TestIdentifier> ids) {
        String className = null;
        for (TestIdentifier t = ti; t != null; t = t.getParentId().map(ids::get).orElse(null)) {
            Optional<TestSource> source = t.getSource();
            if (source.isPresent() && source.get() instanceof ClassSource) {
                className = ((ClassSource) source.get()).getClassName();
            }
        }
        return Optional.ofNullable(className);
    }

    /**
     * @return The test methods that are not LSF tests, as method keys, by top-level test class; and in unitIds, the
     *         unique IDs of the tests of each method key and class (for reporting). Classes without any such method (e.g. disabled ones) are included
     *         with no methods, so that they are still reported, unless all their tests are LSF tests.
     */
    private static Map<String, List<String>> getRegularTestMethods(Map<String, TestIdentifier> ids,
                                                                   Map<String, List<String>> unitIds) {
        Map<String, Set<String>> classMethods = new TreeMap<>();
        Set<String> classesWithLsfTests = new HashSet<>();
        for (TestIdentifier ti : ids.values()) {
            Optional<String> className = getTopLevelClassName(ti, ids);
            if (!className.isPresent()) {
                continue;
            }
            if (RunTest.isLsfTest(ti, ids)) {
                classesWithLsfTests.add(className.get());
                continue;
            }
            Set<String> methods = classMethods.computeIfAbsent(className.get(), k -> new TreeSet<>());
            Optional<TestIdentifier> method = getTestMethod(ti, ids);
            if (!method.isPresent()) {
                continue;
            }
            String methodKey = TestShardFilter.getMethodKey((MethodSource) method.get().getSource().get());
            methods.add(methodKey);
            // Report each test, including each invocation of a test template and each dynamic test
            if (ti.isTest()) {
                unitIds.computeIfAbsent(methodKey, k -> new ArrayList<>()).add(ti.getUniqueId());
                unitIds.computeIfAbsent(className.get(), k -> new ArrayList<>()).add(ti.getUniqueId());
            }
        }
        Map<String, List<String>> result = new TreeMap<>();
        classMethods.forEach((c, m) -> {
            // Skip classes whose tests all run in their own jobs
            if (!m.isEmpty() || !classesWithLsfTests.contains(c)) {
                result.put(c, new ArrayList<>(m));
            }
        });
        return result;
    }

    /**
     * Splits the regular tests between jobs: by estimated durations if there are any, else one job per class.
     * @return The test classes and method keys of each job, with its estimated seconds (or 0 if unknown).
     */
    private static List<Pair<List<String>, Double>> splitRegularTests(Map<String, List<String>> classMethods,
                                                                      Map<String, Double> durations) {
        List<Pair<List<String>, Double>> jobs = new ArrayList<>();
        if (durations.isEmpty()) {
            for (String className : classMethods.keySet()) {
                jobs.add(new Pair<>(Collections.singletonList(className), 0.0));
            }
            return jobs;
        }
        double totalSeconds = 0;
        for (List<String> methods : classMethods.values()) {
            for (String method : methods) {
                totalSeconds += durations.getOrDefault(method, TestShardFilter.DEFAULT_METHOD_SECONDS);
            }
        }
        int numJobs = (int) Math.max(1, Math.min(MAX_REGULAR_JOBS,
                Math.ceil(totalSeconds / REGULAR_JOB_TARGET_SECONDS)));
        ShardAssignment assignment = ShardAssignment.assign(classMethods, durations, numJobs);
        for (int shard = 1; shard <= numJobs; shard++) {
            List<String> units = assignment.getUnits(shard);
            if (!units.isEmpty()) {
                jobs.add(new Pair<>(units, assignment.shardSeconds[shard - 1]));
            }
        }
        // Longest first, so that they start first
        jobs.sort(Comparator.comparingDouble((Pair<List<String>, Double> p) -> p.getSecond()).reversed());
        return jobs;
    }

    /**
     * @return The estimated durations given by {@value #DURATIONS_IN_PROPERTY}, or none if it is not set.
     */
    private static Map<String, Double> readEstimatedDurations() {
        String durationsIn = System.getProperty(DURATIONS_IN_PROPERTY);
        if (durationsIn == null || durationsIn.isEmpty()) {
            return new HashMap<>();
        }
        Map<String, Double> durations = TestShardFilter.readDurations(Paths.get(durationsIn));
        if (durations.isEmpty()) {
            System.err.println("WARNING: no estimated test durations in " + durationsIn + ", running one job per class");
        }
        return durations;
    }

    private static String quote(String s) {
        return '"' + s + '"';
    }

    public static void main(String[] args) throws IOException {
        // Read the estimates first: they may be the durations file of the previous run in this working dir
        Map<String, Double> durations = readEstimatedDurations();

        //Testcases may have been removed since the last run, delete all to be sure
        if (!FileTools.deleteFolderContents(".")) {
            throw new RuntimeException("could not empty working dir");
        }

        Path testsJar = Paths.get(args[0]);

        Launcher launcher = LauncherFactory.create();

        LauncherDiscoveryRequest request = getLauncherDiscoveryRequestBuilder(testsJar).build();
        TestPlan testPlan = launcher.discover(request);
        Map<String, TestIdentifier> ids = enumerateTests(launcher, testPlan);

        Map<String, List<String>> unitIds = new HashMap<>();
        Map<String, List<String>> classMethods = getRegularTestMethods(ids, unitIds);

        JobQueue jq = new JobQueue(false);
        List<TestJob> testJobs = new ArrayList<>();

        // The regular tests first, as their jobs are usually the longest
        List<Pair<List<String>, Double>> regularJobs = splitRegularTests(classMethods, durations);
        for (int i = 0; i < regularJobs.size(); i++) {
            List<String> units = regularJobs.get(i).getFirst();
            String dir = String.format("regularTests-%03d", i + 1);
            FileTools.makeDirs(dir);
            Files.write(Paths.get(dir, UNITS_FILE), units, StandardCharsets.UTF_8);
            LSFJob job = new LSFJob();
            job.setRunDir(dir);
            job.setRapidWrightCommand(RunTest.class, LargeTest.DEFAULT_MAX_MEMORY_GB * 1024, true,
                    quote(testsJar.toString()) + " \"\" " + quote(UNITS_FILE) + " " + quote(JOB_DURATIONS_FILE));
            job.setLsfResourceMemoryLimit(REGULAR_JOB_RESERVED_GB * 1024);
            jq.addJob(job);
            testJobs.add(new TestJob(job, null, units, regularJobs.get(i).getSecond()));
        }
        System.out.printf("Running %d regular test classes in %d jobs (%s), estimated %.0f s in total%n",
                classMethods.size(), regularJobs.size(),
                durations.isEmpty() ? "one per class, no estimated durations" : "balanced by estimated durations",
                regularJobs.stream().mapToDouble(Pair::getSecond).sum());

        getLsfTests(ids).forEach(pair -> {
            int memMB = pair.getSecond()*1024;
            LSFJob job = new LSFJob();
            job.setRunDir(toJobDir(pair.getFirst()));
            job.setRapidWrightCommand(RunTest.class, memMB, true,
                    quote(testsJar.toString()) + " " + quote(pair.getFirst()));
            job.setLsfResourceMemoryLimit(memMB);
            jq.addJob(job);
            testJobs.add(new TestJob(job, pair.getFirst(), null, 0));
        });

        //Don't impose more limits on parallelism than what LSF allows us
        long submitTime = System.currentTimeMillis();
        boolean success = jq.runAllToCompletion(Integer.MAX_VALUE);

        writeDurations(testJobs, durations, classMethods);
        writeJobTimes(testJobs, submitTime);

        if (!success) {
            StringBuilder sb = new StringBuilder();
            sb.append("LSF workers failed to execute tests:");
            Map<String, String> perTestMessages = new HashMap<>();
            for (TestJob testJob : testJobs) {
                LSFJob job = testJob.job;
                if (job.jobWasSuccessful()) {
                    continue;
                }
                sb.append("\n");
                sb.append("Job ").append(job.getJobNumber()).append(" running in ").append(job.getRunDir());
                sb.append(" executing ").append(testJob.describe());
                Optional<List<String>> lastLogLines = job.getLastLogLines();
                String log = lastLogLines.isPresent()
                        ? lastLogLines.get().stream().collect(Collectors.joining("\n","Execution failed. Last Lines of Log:\n",""))
                        : "Execution failed, no log was generated";
                if (testJob.testId != null) {
                    perTestMessages.put(testJob.testId, log);
                } else if (!hasXmlReport(job.getRunDir())) {
                    // The job did not get to write its report, so report each of its tests as failed
                    for (String unit : testJob.units) {
                        for (String id : unitIds.getOrDefault(unit, Collections.emptyList())) {
                            perTestMessages.put(id, log);
                        }
                    }
                }
            }
            StaticReportGenerator.writeMasterXmlLog(sb.toString(), perTestMessages, testPlan);

            System.exit(1);
        }
    }

    private static boolean hasXmlReport(String dir) {
        File[] files = new File(dir).listFiles((d, name) -> name.startsWith("TEST-") && name.endsWith(".xml"));
        return files != null && files.length > 0;
    }

    /**
     * Writes the measured durations of the regular tests to {@value #DURATIONS_FILE}, keeping the earlier estimates
     * of current tests that were not measured (e.g. because their job failed).
     */
    private static void writeDurations(List<TestJob> testJobs, Map<String, Double> estimates,
                                       Map<String, List<String>> classMethods) {
        Map<String, Double> measured = new TreeMap<>();
        for (TestJob testJob : testJobs) {
            if (testJob.testId == null) {
                measured.putAll(TestShardFilter.readDurations(Paths.get(testJob.job.getRunDir(), JOB_DURATIONS_FILE)));
            }
        }
        for (List<String> methods : classMethods.values()) {
            for (String method : methods) {
                if (!measured.containsKey(method) && estimates.containsKey(method)) {
                    measured.put(method, estimates.get(method));
                }
            }
        }
        List<String> lines = new ArrayList<>();
        lines.add("# Seconds per test method, written by " + LaunchTestsOnLsf.class.getName());
        measured.forEach((k, v) -> lines.add(String.format(Locale.ROOT, "%.1f %s", v, k)));
        try {
            Files.write(Paths.get(DURATIONS_FILE), lines, StandardCharsets.UTF_8);
        } catch (IOException e) {
            System.err.println("WARNING: could not write " + DURATIONS_FILE + ": " + e);
        }
    }

    private static long[] readRunTimes(String dir) {
        long[] times = {-1, -1};
        Path path = Paths.get(dir, RunTest.RUN_TIMES_FILE);
        if (!Files.exists(path)) {
            return times;
        }
        try {
            for (String line : Files.readAllLines(path, StandardCharsets.UTF_8)) {
                String[] parts = line.trim().split("\\s+");
                if (parts.length == 2) {
                    times[parts[0].equals("start") ? 0 : 1] = Long.parseLong(parts[1]);
                }
            }
        } catch (IOException | RuntimeException e) {
            // Leave the times unknown
        }
        return times;
    }

    /**
     * Writes how long each job waited for LSF and ran to {@value #JOBS_FILE}, and prints the slowest jobs.
     * Times are measured by the test runner in each job, so they exclude JVM startup.
     */
    private static void writeJobTimes(List<TestJob> testJobs, long submitTime) {
        List<String> lines = new ArrayList<>();
        lines.add("dir,job,estimated_s,pending_s,run_s,successful,tests");
        List<Pair<Double, String>> slowest = new ArrayList<>();
        for (TestJob testJob : testJobs) {
            long[] times = readRunTimes(testJob.job.getRunDir());
            double pending = times[0] < 0 ? -1 : (times[0] - submitTime) / 1000.0;
            double run = times[0] < 0 || times[1] < 0 ? -1 : (times[1] - times[0]) / 1000.0;
            String tests = testJob.testId != null ? testJob.testId : String.join(" ", testJob.units);
            lines.add(String.format(Locale.ROOT, "%s,%d,%.0f,%.0f,%.0f,%b,\"%s\"", testJob.job.getRunDir(),
                    testJob.job.getJobNumber(), testJob.estimatedSeconds, pending, run,
                    testJob.job.jobWasSuccessful(), tests.replace("\"", "\"\"")));
            slowest.add(new Pair<>(Math.max(0, pending) + Math.max(0, run), String.format(Locale.ROOT,
                    "%6.0f s pending %6.0f s running %6.0f s estimated  %s (%s)", pending, run,
                    testJob.estimatedSeconds, testJob.job.getRunDir(), testJob.describe())));
        }
        try {
            Files.write(Paths.get(JOBS_FILE), lines, StandardCharsets.UTF_8);
        } catch (IOException e) {
            System.err.println("WARNING: could not write " + JOBS_FILE + ": " + e);
        }
        slowest.sort(Comparator.comparingDouble((Pair<Double, String> p) -> p.getFirst()).reversed());
        System.out.println("Slowest LSF jobs:");
        slowest.stream().limit(10).forEach(p -> System.out.println("  " + p.getSecond()));
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

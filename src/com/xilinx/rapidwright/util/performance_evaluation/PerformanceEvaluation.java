/*
 * Copyright (c) 2021 Xilinx, Inc.
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
package com.xilinx.rapidwright.util.performance_evaluation;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.xilinx.rapidwright.design.Design;
import com.xilinx.rapidwright.util.FileTools;
import com.xilinx.rapidwright.util.Job;
import com.xilinx.rapidwright.util.JobQueue;
import com.xilinx.rapidwright.util.Pair;
import joptsimple.ArgumentAcceptingOptionSpec;
import joptsimple.OptionParser;
import joptsimple.OptionSet;
import joptsimple.OptionSpec;

/**
 * Take a unrouted or partially routed design, route it in Vivado with auto generated timing constraints to find
 * the maximum clock frequency the design supports.
 *
 * The minimum clock period is found via a three-stage approach, where each stage consists of a number of Vivado runs:
 * <ul>
 *     <li>Route at user-provided frequency</li>
 *     <li>Calculate new period a bit tighter than what was achieved. In 0.05ns increments, schedule runs around that center </li>
 *     <li>Search between the best result where the constraints were met and the best result where the constraints were not met. This schedules runs in 0.002ns increments</li>
 * </ul>
 *
 * The number of runs can be quite large, so using LSF is recommended.
 *
 * This tool only changes timing constraints of one user-specified clock input. Other clocks should already have
 * timing constraints assigned to them before passing the design to this tool.
 */
public class PerformanceEvaluation {

    private final Path workDir;
    private final Path dcp;
    protected final boolean reuseExistingResults;
    protected final String clkPortName;

    private final List<TimingResults> allResults = new ArrayList<>();

    public PerformanceEvaluation(Path workDir, Path dcp, boolean reuseExistingResults, String clkPortName) {
        this.workDir = workDir;
        this.dcp = dcp;
        this.reuseExistingResults = reuseExistingResults;
        this.clkPortName = clkPortName;
    }

    public static class RouteRun {
        protected final Path jobDir;
        protected final double clockPeriod;
        protected final boolean reuseExistingResults;
        protected final Path dcp;
        protected final String clkPortName;

        public RouteRun(Path jobDir, double clockPeriod, boolean reuseExistingResults, Path dcp, String clkPortName) {
            this.jobDir = jobDir;
            this.clockPeriod = clockPeriod;
            this.reuseExistingResults = reuseExistingResults;
            this.dcp = dcp;
            this.clkPortName = clkPortName;
        }


        protected Path getRouteStatusPath() {
            return jobDir.resolve("route_status.txt");
        }

        protected Path getTimingReportPath() {
            return jobDir.resolve("timing.txt");
        }

        protected Path getTimingSummaryReportPath() {
            return jobDir.resolve("timing_summary.txt");
        }

        protected Path getRoutedDcp() {
            return jobDir.resolve("routed.dcp");
        }

        protected Path getScriptName() {
            return jobDir.resolve("route.tcl");
        }

        boolean jobCreated = false;

        private Job createJob() {
            if (jobCreated) {
                return null;
            }
            jobCreated = true;
            if (reuseExistingResults && Files.exists(getRoutedDcp())) {
                return null;
            }

            Job j = JobQueue.createJob();
            j.setRunDir(getCommandDir());
            j.setCommand(getCommand());
            try {
                Files.createDirectories(jobDir);
                createRouteScript();
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
            return j;
        }

        protected String getCommandDir() {
            return jobDir.toString();
        }

        protected String getCommand() {
            return FileTools.getVivadoPath() + " -mode batch -source " + getScriptName();
        }

        public void createRouteScript() throws IOException {
            try (PrintWriter pw = new PrintWriter(Files.newBufferedWriter(getScriptName()))) {

                pw.println("open_checkpoint " + dcp);
                createClock(pw);
                routeAndSave(pw);
            }
        }

        protected void routeAndSave(PrintWriter pw) {
            routeAndSave(pw, null);
        }

        protected void routeAndSave(PrintWriter pw, String cell) {
            pw.println("route_design");
            pw.println("report_route_status -file " + getRouteStatusPath());
            pw.println("report_timing -file " + getTimingReportPath());
            pw.println("report_timing_summary -file " + getTimingSummaryReportPath());
            if (cell != null) {
                cell = "-cell " + cell + " ";
            } else {
                cell = "";
            }
            pw.println("write_checkpoint -force " + cell + getRoutedDcp());
            pw.println("write_edif -force " + cell + FileTools.replaceExtension(getRoutedDcp(), ".edf"));
        }

        protected String createClock(String clockName) {
            return "create_clock -name " + clockName + " -period " + clockPeriod + " [get_ports " + clkPortName + "]";
        }

        protected void createClock(PrintWriter pw, String clockName) {
            pw.println(createClock(clockName));
        }

        protected void createClock(PrintWriter pw) {
            createClock(pw, clkPortName);
        }


        protected TimingResults results;

        protected TimingResults getResults() {
            if (results == null) {
                try {
                    results = TimingResults.parseTimingSummaryFile(getTimingSummaryReportPath(), getRouteStatusPath(), clockPeriod, getRoutedDcp());
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            }
            return results;
        }
    }

    Map<String, RouteRun> runs = new HashMap<>();

    public RouteRun createRouteRun(double clockPeriod) {
        String periodString = String.format("%.3f", clockPeriod);
        return runs.computeIfAbsent(periodString, s -> {
            Path jobDir = workDir.resolve("route_" + periodString);
            return getRouteRun(clockPeriod, jobDir);
        });
    }

    protected RouteRun getRouteRun(double clockPeriod, Path jobDir) {
        return new RouteRun(jobDir, clockPeriod, reuseExistingResults, dcp, clkPortName);
    }


    private double getInitialPeriod(double estimatedMaxFreqMhz) {
        double initialPeriod = roundTo(1000 / estimatedMaxFreqMhz, 0.1);
        RouteRun routeRun = createRouteRun(initialPeriod);

        runAll(Collections.singletonList(routeRun), false);
        printResults("Initial", Collections.singletonList(routeRun));

        TimingResults results = routeRun.getResults();

        return getNextStagePeriod(results);
    }

    private double getNextStagePeriod(TimingResults results) {
        //Set the constraint somewhat tighter than what we already achieved
        double extraEffort;
        if (results.allOk()) {
            extraEffort = 1.4;
        } else {
            extraEffort = 1 / 1.1;
        }
        double exact = results.clockPeriod - extraEffort * results.worstSlack;
        return roundTo(exact, 0.1);
    }

    void runAll(List<RouteRun> runs, boolean saveResult) {
        JobQueue queue = new JobQueue();
        for (RouteRun run : runs) {
            Job j = run.createJob();
            if (j != null) {
                queue.addJob(j);
            }

        }
        boolean success = queue.runAllToCompletion();
        if (!success) {
            throw new RuntimeException("There were failing jobs...");
        }

        if (saveResult) {
            for (RouteRun run : runs) {
                allResults.add(run.getResults());
            }
        }
    }

    List<RouteRun> createAndRun(double center, int countPerSide, double spacing) {

        List<RouteRun> runs = new ArrayList<>();
        for (int i = -countPerSide; i <= countPerSide; i++) {
            double deviation = i * spacing;
            double period = center + deviation;

            RouteRun run = createRouteRun(period);
            runs.add(run);

        }

        runAll(runs, true);
        return runs;
    }

    private Pair<Optional<TimingResults>, Optional<TimingResults>> findBest() {

        Optional<TimingResults> bestWorking = allResults.stream().filter(r -> r.allOk()).min(Comparator.comparing(TimingResults::getMinPeriod));
        Optional<TimingResults> bestNonWorking = allResults.stream().filter(r -> !r.allOk()).min(Comparator.comparing(TimingResults::getMinPeriod));

        return new Pair<>(bestWorking, bestNonWorking);
    }


    private Pair<Double, Double> findAreaOfInterest() {
        Pair<Optional<TimingResults>, Optional<TimingResults>> best = findBest();
        double bestNonWorking = best.getSecond().orElseThrow(() -> {
            Optional<TimingResults> working = best.getFirst();
            return working.map(timingResults -> new RuntimeException("constraints were always met, best working was " + timingResults.getMinPeriod() + "ns (" + timingResults.getMaxFrequency() + "MHz)"))
                    .orElseGet(() -> new RuntimeException("neither working nor nonworking found. Did we run anything?"));

        }).getMinPeriod();
        double bestWorking = best.getFirst().orElseThrow(() -> new RuntimeException("constraints were never met, best nonworking was " + bestNonWorking + "ns (" + (1000 / bestNonWorking) + "MHz)")).getMinPeriod();

        double lower = roundDownTo(Math.min(bestWorking, bestNonWorking), 0.05);
        double upper = roundUpTo(Math.max(bestWorking, bestNonWorking), 0.05);
        System.out.println("Exact: Need to look between " + bestWorking + " and " + bestNonWorking);
        System.out.println("Rounded: Need to look between " + lower + " and " + upper);

        return new Pair<>(lower, upper);
    }

    public static double roundDownTo(double value, double increment) {
        return Math.floor(value / increment) * increment;
    }

    public static double roundUpTo(double value, double increment) {
        return Math.ceil(value / increment) * increment;
    }


    public static double roundTo(double value, double increment) {
        return Math.round(value / increment) * increment;
    }


    private List<RouteRun> thirdStageRuns() {
        Pair<Double, Double> areaOfInterest = findAreaOfInterest();
        Double lower = areaOfInterest.getFirst();
        Double upper = areaOfInterest.getSecond();

        List<RouteRun> runs = new ArrayList<>();
        for (double d = lower; d < (upper + 1E-6); d += 0.002) {
            runs.add(createRouteRun(d));
        }
        runAll(runs, true);
        return runs;
    }

    public Pair<TimingResults, TimingResults> run(double estimatedMaxFreqMhz) {
        try {
            double center = getInitialPeriod(estimatedMaxFreqMhz);


            List<Double> seenCenters = new ArrayList<>();
            int iterations = 0;

            do {
                System.out.println("searching around " + center);
                if (iterations++ > 20) {
                    throw new RuntimeException("Took to many iterations. Something is wrong!");
                }
                if (center > 20) {
                    throw new RuntimeException("Design is slower than " + center + ". Stopping search.");
                }

                seenCenters.add(center);

                List<RouteRun> secondStageRuns = createAndRun(center, 5, 0.05);
                printResults("Second Stage", secondStageRuns);
                Pair<Optional<TimingResults>, Optional<TimingResults>> best = findBest();

                if (best.getFirst().isPresent() && best.getSecond().isPresent()) {
                    break;
                }

                System.out.println("Need to expand second stage search");

                TimingResults res;
                if (best.getFirst().isPresent()) {
                    res = best.getFirst().get();
                } else if (best.getSecond().isPresent()) {
                    res = best.getSecond().get();
                } else {
                    throw new RuntimeException("no results are present???");
                }

                final double newCenter = getNextStagePeriod(res);
                if (seenCenters.stream().anyMatch(seenCenter -> Math.abs(seenCenter - newCenter) < 1E-6)) {
                    if (res.timingConstraintsMet) {
                        center = center - 0.25;
                    } else {
                        center = center + 0.25;
                    }
                    System.out.println("Slack-Guided search resulted in an already seen center, " + newCenter + ". Moved center to " + center);
                } else {
                    center = newCenter;
                }

            } while (true);


            List<RouteRun> thirdStageRuns = thirdStageRuns();
            printResults("Third Stage", thirdStageRuns);
        } finally {
            writeResultsToFile();
        }

        Pair<Optional<TimingResults>, Optional<TimingResults>> best = findBest();
        if (!best.getFirst().isPresent() || !best.getSecond().isPresent()) {
            throw new RuntimeException("Not all results present?? We should never have gotten here");
        }

        TimingResults bestMet = best.getFirst().get();
        System.out.println("best with constraints met:     " + bestMet.getMinPeriod() + " ns");
        System.out.println("best with constraints met:     " + bestMet.getMaxFrequency() + " MHz");
        TimingResults bestNotMet = best.getSecond().get();
        System.out.println("best with constraints not met: " + bestNotMet.getMinPeriod() + " ns");
        System.out.println("best with constraints not met: " + bestNotMet.getMaxFrequency() + " MHz");


        return new Pair<>(bestMet, bestNotMet);

    }

    private void writeResultsToFile() {
        Path graphOutput = workDir.resolve("maxFreq.tsv");
        try (PrintWriter pw = new PrintWriter(Files.newBufferedWriter(graphOutput))) {
            for (TimingResults results : allResults) {
                pw.println(results.clockPeriod + "\t" + results.getMinPeriod() + "\t" + results.timingConstraintsMet + "\t" + results.routeOverlaps + "\t" + results.allOk());
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private void printResults(String name, List<RouteRun> runs) {
        System.out.println(name);
        System.out.format("Requested P\tActual P\tRequested F\tActual f\tSlack\tMet\tOverlaps\n");
        for (RouteRun r : runs) {
            TimingResults res = r.getResults();
            System.out.format("%4.3f\t\t%4.3f\t\t%4.3f\t\t%4.3f\t\t%4.3f\t%s\t%d\n", res.clockPeriod, res.getMinPeriod(), res.clockFrequency(), res.getMaxFrequency(), res.worstSlack, res.timingConstraintsMet, res.routeOverlaps);
        }
        System.out.println();
    }

    public static Pair<TimingResults, TimingResults> getMaxFrequency(Path dcp, String clockPortName, Path workDir, boolean reuseExistingResults, double estimatedMaxFreqMhz) {
        return new PerformanceEvaluation(workDir.toAbsolutePath(), dcp.toAbsolutePath(), reuseExistingResults, clockPortName).run(estimatedMaxFreqMhz);
    }

    public static Pair<TimingResults, TimingResults> getMaxFrequency(Design design, String clockPortName, Path workDir, boolean reuseExistingResults, double estimatedMaxFreqMhz) {
        try {
            Files.createDirectories(workDir);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        Path dcp = workDir.resolve("design.dcp");
        design.writeCheckpoint(dcp);
        return getMaxFrequency(dcp, clockPortName, workDir, reuseExistingResults, estimatedMaxFreqMhz);
    }

    public static void main(String[] args) throws FileNotFoundException {

        if (JobQueue.isLSFAvailable()) {
            System.out.println("Running Jobs in LSF");
        } else {
            System.out.println("Running Jobs locally");
        }

        OptionParser optionParser = new OptionParser();
        ArgumentAcceptingOptionSpec<String> dcpFileOption = optionParser.accepts("dcp", "Input DCP file").withRequiredArg().required();
        ArgumentAcceptingOptionSpec<Double> maxFreqOption = optionParser.accepts("maxFreq", "Estimated Maximum Frequency").withRequiredArg().ofType(double.class).required();
        ArgumentAcceptingOptionSpec<String> clkPortOption = optionParser.accepts("port", "Clock Port Name").withRequiredArg().required();
        OptionSpec<?> reuseOption = optionParser.accepts("reuse", "Reuse existing Runs");


        OptionSet options;
        try {
            options = optionParser.parse(args);
        } catch (RuntimeException e) {
            try {
                optionParser.printHelpOn(System.out);
            } catch (IOException ioException) {
                throw new UncheckedIOException(ioException);
            }
            throw e;
        }

        Path dcpFile = Paths.get(options.valueOf(dcpFileOption));
        if (!Files.exists(dcpFile)) {
            throw new FileNotFoundException("Input DCP does not exist at " + dcpFile);
        }

        String clockPortName = options.valueOf(clkPortOption);
        boolean reuse = options.has(reuseOption);
        double maxFreq = options.valueOf(maxFreqOption);


        getMaxFrequency(dcpFile, clockPortName, Paths.get("."), reuse, maxFreq);
    }
}

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

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class TimingResults {
    public final String constraintName;
    public final double worstSlack;
    public final double totalNegSlack;
    public final boolean timingConstraintsMet;
    public final double clockPeriod;
    public final int routeOverlaps;
    public final Path checkpoint;

    public TimingResults(String constraintName, double worstSlack, double totalNegSlack, boolean constraintsMet, double clockPeriod, int routeOverlaps, Path checkpoint) {
        this.constraintName = constraintName;
        this.worstSlack = worstSlack;
        this.totalNegSlack = totalNegSlack;
        this.timingConstraintsMet = constraintsMet;
        this.clockPeriod = clockPeriod;
        this.routeOverlaps = routeOverlaps;
        this.checkpoint = checkpoint;
    }

    public double clockFrequency() {
        return 1000/clockPeriod;
    }

    public double getMaxFrequency() {
        return 1000/getMinPeriod();
    }

    enum ParseState {
        BeforeMetLine,
        BeforeTable,
        BeforeSeparator,
        InContent,
        After
    }
    private static TimingResults parseContentLine(String line, boolean met, double clockPeriod, int routeOverlaps, Path checkpoint) {
        String[] split = line.trim().split("\\s+");
        String worstHold = split[5];
        final TimingResults timingResults = new TimingResults(split[0], Double.parseDouble(split[1]), Double.parseDouble(split[2]), met, clockPeriod, routeOverlaps, checkpoint);
        if (worstHold.contains("-")) {
            System.err.println("Design has worst hold violation of " + worstHold + "ns. Other results: " + timingResults+". Check if there is a clock routing issue.");
        }
        return timingResults;
    }

    private static int parseRouteOverlaps(Path routeStatusFile) throws IOException {
        if (routeStatusFile == null) {
            return 0;
        }
        try (Stream<String> lines = Files.lines(routeStatusFile)) {
            final String prefix = "# of nets with routing errors.......... :";
            final String errorsLine = lines.filter(l -> l.contains(prefix))
                    .findAny()
                    .orElseThrow(()->new RuntimeException("Did not find Routing Errors line in Route Status Report at "+routeStatusFile));
            final String trimmed = errorsLine.replace(prefix, "").replace(":", "").trim();
            return Integer.parseInt(trimmed);
        }
    }

    public static TimingResults parseTimingSummaryFile(Path timingSummaryFile, Path routeStatusFile, double clockPeriod, Path checkpoint) throws IOException {
        return parseTimingSummaryFile(timingSummaryFile, routeStatusFile, clockPeriod, checkpoint,null);
    }
    public static TimingResults parseTimingSummaryFile(Path timingSummaryFile, Path routeStatusFile, double clockPeriod, Path checkpoint, String constraintName) throws IOException {

        int routeOverlaps =  parseRouteOverlaps(routeStatusFile);
        ParseState ps = ParseState.BeforeMetLine;

        List<String> contentLine = new ArrayList<>();
        Boolean met = null;
        try (Stream<String> lines = Files.lines(timingSummaryFile)) {
            LINE_LOOP:
            for (String line : (Iterable<String>) lines::iterator) {
                switch (ps) {
                    case BeforeMetLine:
                        if (line.startsWith("All user specified timing constraints are met.")) {
                            met = true;
                            ps = ParseState.BeforeTable;
                        } else if (line.startsWith("Timing constraints are not met.")) {
                            met = false;
                            ps = ParseState.BeforeTable;
                        }
                        break;
                    case BeforeTable:
                        if (line.contains("| Intra Clock Table")) {
                            ps = ParseState.BeforeSeparator;
                        }
                        break;
                    case BeforeSeparator:
                        if (line.contains("    ") && line.contains("----")) {
                            ps = ParseState.InContent;
                        }
                        break;
                    case InContent:
                        //After table?
                        if (line.trim().isEmpty()) {
                            break LINE_LOOP;
                        }
                        contentLine.add(line);
                        break;
                }

            }
        }
        if (met == null || contentLine.isEmpty()) {
            throw new RuntimeException("did not find timing resuult line in "+timingSummaryFile);
        }
        try {
            boolean finalMet = met;
            final Map<String, TimingResults> resultsByConstraint = contentLine.stream().map(s -> parseContentLine(s, finalMet, clockPeriod, routeOverlaps, checkpoint))
                    .collect(Collectors.toMap(l -> l.constraintName, Function.identity()));
            if (constraintName == null) {
                if (resultsByConstraint.size() != 1) {
                    throw new RuntimeException("Got design with multiple clocks but no clock name was supplied. Results: " + resultsByConstraint.values());
                }
                return resultsByConstraint.values().iterator().next();
            }

            final TimingResults res = resultsByConstraint.get(constraintName);
            if (res == null) {
                throw new RuntimeException("no constraint of name "+constraintName+" exists in results: "+resultsByConstraint.values());
            }
            return res;
        } catch (RuntimeException e) {
            throw new RuntimeException("could not parse timing result line in "+timingSummaryFile,e);
        }
    }

    @Override
    public String toString() {
        return "TimingResults{" +
                "constraintName='" + constraintName + '\'' +
                ", worstSlack=" + worstSlack +
                ", totalNegSlack=" + totalNegSlack +
                ", constraintsMet=" + timingConstraintsMet +
                ", clockPeriod=" + clockPeriod +
                ", routeOverlaps=" + routeOverlaps +
                ", checkpoint=" + checkpoint +
                '}';
    }

    public double getMinPeriod() {
        return clockPeriod - worstSlack;
    }

    public boolean allOk() {
        return timingConstraintsMet && routeOverlaps == 0;
    }
}

/*
 * Copyright (c) 2023, Advanced Micro Devices, Inc.
 * All rights reserved.
 *
 * Author: Zak Nafziger, Advanced Micro Devices, Inc.
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

package com.xilinx.rapidwright.util;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Dictionary;
import java.util.Hashtable;
import java.util.List;

import com.xilinx.rapidwright.design.Design;

/**
 * Utility methods to run access vivado and parse logs
 * 
 * @author zakn
 *
 */
public class VivadoTools {

    public static class ReportRouteStatusResult {
        public Dictionary<String, Integer> routeStatus = new Hashtable<String, Integer>();

        public ReportRouteStatusResult() {
            routeStatus.put("# of logical nets", -1);
            routeStatus.put("# of nets not needing routing", -1);
            routeStatus.put("# of internally routed nets", -1);
            routeStatus.put("# of nets with no loads", -1);
            routeStatus.put("# of implicitly routed ports", -1);
            routeStatus.put("# of routable nets", -1);
            routeStatus.put("# of unrouted nets", -1);
            routeStatus.put("# of fully routed nets", -1);
            routeStatus.put("# of nets with routing errors", -1);
        }

        public int logicalNets() {
            return routeStatus.get("# of logical nets");
        }

        public int netsNotNeedingRouting() {
            return routeStatus.get("# of nets not needing routing");
        }

        public int internallyRoutedNets() {
            return routeStatus.get("# of internally routed nets");
        }

        public int netsWithNoLoads() {
            return routeStatus.get("# of nets with no loads");
        }

        public int implicitlyRoutedPorts() {
            return routeStatus.get("# of implicitly routed ports");
        }

        public int routableNets() {
            return routeStatus.get("# of routable nets");
        }

        public int unroutedNets() {
            return routeStatus.get("# of unrouted nets");
        }

        public int fullyRoutedNets() {
            return routeStatus.get("# of fully routed nets");
        }

        public int netsWithRoutingErrors() {
            return routeStatus.get("# of nets with routing errors");
        }
    }

    /**
     * builds a tcl script to open the Design d in vivado and run the command
     * `report_route_status`
     * 
     * @param d       the design to be analyzed
     * @param workdir a temporary directory to run vivado in (will contain logs,
     *                dcp, etc)
     * @return ReportRouteStatus object with fields initialized with the output of
     *         `report_route_status`
     */
    public static ReportRouteStatusResult reportRouteStatus(Design d, Path workdir) {
        final Path dcp = workdir.resolve("checkpoint.dcp");
        final Path tclScript = workdir.resolve("tclScript.tcl");

        d.writeCheckpoint(dcp);
        List<String> lines = new ArrayList<>();
        lines.add("open_checkpoint " + dcp);
        lines.add("report_route_status");
        lines.add("exit");
        FileTools.writeLinesToTextFile(lines, tclScript.toString());
        List<String> log = new ArrayList<>();
        log = VivadoTools.runTcl(workdir.resolve("outputLog.log"), tclScript, true);

        ReportRouteStatusResult results = new ReportRouteStatusResult();

        for (String k : Collections.list(results.routeStatus.keys())) {
            int v = Integer
                    .parseInt(VivadoTools.searchVivadoLog(log, k).get(0).replaceAll("[^\\d]", ""));
            results.routeStatus.put(k, v);
        }

        return results;
    }

    /**
     * method to search a vivado log for a specific key phrase
     * 
     * @param log Vivado log as list of strings
     * @param key Key phrase to search for
     * @return List of lines that contain the key phrase
     */
    public static List<String> searchVivadoLog(List<String> log, String key) {
        List<String> results = new ArrayList<>();
        for (String l : log) {
            if (l.contains(key)) {
                results.add(l);
            }
        }
        return results;
    }

    /**
     * method to run a single tcl command in vivado
     * 
     * @param outputLog Path to the log file that vivado will generate
     * @param tclCmd    tcl command to run
     * @param verbose   If true vivado command line and std.out/err will be printed
     *                  to std.out
     * @return the contents of the log file as a list of strings
     */
    public static List<String> runTcl(Path outputLog, String tclCmd, boolean verbose) {
        Path tclScript = outputLog.getParent().resolve("tclScript.tcl");
        List<String> lines = new ArrayList<>();
        lines.add(tclCmd);
        FileTools.writeLinesToTextFile(lines, tclScript.toString());
        return runTcl(outputLog, tclScript, verbose);
    }

    /**
     * method to run a tcl script in vivado
     * 
     * @param outputLog Path to the log file that vivado will generate
     * @param tclScript Path to the tcl script that will be run
     * @param verbose   If true vivado command line and std.out/err will be printed
     *                  to std.out
     * @return the contents of the log file as a list of strings
     */
    public static List<String> runTcl(Path outputLog, Path tclScript, boolean verbose) {
        final String vivadoCmd = "vivado -log " + outputLog.toString() + " -mode batch -source "
                + tclScript.toString();
        FileTools.runCommand(vivadoCmd, verbose);
        List<String> log = new ArrayList<>();
        log = FileTools.getLinesFromTextFile(outputLog.toString());
        return log;
    }
}
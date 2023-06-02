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

import java.io.File;
import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import com.xilinx.rapidwright.design.Design;

/**
 * Utility methods to provide access to vivado and parse logs
 *
 */
public class VivadoTools {

    public static class ReportRouteStatusResult {

        public final int logicalNets;
        public final int netsNotNeedingRouting;
        public final int internallyRoutedNets;
        public final int netsWithNoLoads;
        public final int implicitlyRoutedPorts;
        public final int routableNets;
        public final int unroutedNets;
        public final int fullyRoutedNets;
        public final int netsWithRoutingErrors;

        private static int parseLog(List<String> log, String key) {
            return Integer.parseInt(
                    VivadoTools.searchVivadoLog(log, key).get(0).replaceAll("[^\\d]", ""));
        }

        /**
         * builds a Tcl script to open the Design d in vivado and run the command
         * `report_route_status`
         * 
         * @param d the design to be analyzed
         */
        ReportRouteStatusResult(Design d) {
            final Path workdir = FileSystems.getDefault()
                    .getPath("vivadoToolsWorkdir" + FileTools.getUniqueProcessAndHostID());
            File workdirHandle = new File(workdir.toString());
            workdirHandle.mkdirs();
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

            logicalNets = parseLog(log, "# of logical nets");
            netsNotNeedingRouting = parseLog(log, "# of nets not needing routing");
            internallyRoutedNets = parseLog(log, "# of internally routed nets");
            netsWithNoLoads = parseLog(log, "# of nets with no loads");
            implicitlyRoutedPorts = parseLog(log, "# of implicitly routed ports");
            routableNets = parseLog(log, "# of routable nets");
            unroutedNets = parseLog(log, "# of unrouted nets");
            fullyRoutedNets = parseLog(log, "# of fully routed nets");
            netsWithRoutingErrors = parseLog(log, "# of nets with routing errors");

            FileTools.deleteFolder(workdirHandle.toString());
        }
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
     * method to run a single Tcl command in vivado
     * 
     * @param outputLog Path to the log file that vivado will generate
     * @param tclCmd    Tcl command to run
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
     * method to run a Tcl script in vivado
     * 
     * @param outputLog Path to the log file that vivado will generate
     * @param tclScript Path to the Tcl script that will be run
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

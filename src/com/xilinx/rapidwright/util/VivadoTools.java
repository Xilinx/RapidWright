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

import com.xilinx.rapidwright.design.Design;

import java.io.File;
import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Utility methods to provide access to vivado and parse logs
 *
 */
public class VivadoTools {

    public static final String REPORT_ROUTE_STATUS = "report_route_status";

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
        return FileTools.getLinesFromTextFile(outputLog.toString());
    }

    /**
     * Run Vivado's `report_route_status` command on the provided Design object
     * and return its result as a ReportRouteStatusResult object.
     *
     * @param design Design object to report on.
     * @return ReportRouteStatusResult object.
     */
    public static ReportRouteStatusResult reportRouteStatus(Design design) {
        final Path workdir = FileSystems.getDefault()
                .getPath("vivadoToolsWorkdir" + FileTools.getUniqueProcessAndHostID());
        File workdirHandle = new File(workdir.toString());
        workdirHandle.mkdirs();
        final Path dcp = workdir.resolve("checkpoint.dcp");
        design.writeCheckpoint(dcp);

        ReportRouteStatusResult rrs = reportRouteStatus(dcp, workdir);

        FileTools.deleteFolder(workdir.toString());

        return rrs;
    }

    /**
     * Run Vivado's `report_route_status` command on the provided DCP path
     * and return its result as a ReportRouteStatusResult object.
     *
     * @param dcp Path to DCP to report on.
     * @return ReportRouteStatusResult object.
     */
    public static ReportRouteStatusResult reportRouteStatus(Path dcp) {
        final Path workdir = FileSystems.getDefault()
                .getPath("vivadoToolsWorkdir" + FileTools.getUniqueProcessAndHostID());
        File workdirHandle = new File(workdir.toString());
        workdirHandle.mkdirs();

        ReportRouteStatusResult rrs = reportRouteStatus(dcp, workdir);

        FileTools.deleteFolder(workdir.toString());

        return rrs;
    }

    /**
     * Run Vivado's `report_route_status` command on the provided DCP path
     * and return its result as a ReportRouteStatusResult object.
     *
     * @param dcp Path to DCP to report on.
     * @param workdir Directory to work within.
     * @return ReportRouteStatusResult object.
     */
    public static ReportRouteStatusResult reportRouteStatus(Path dcp, Path workdir) {
        final Path outputLog = workdir.resolve("outputLog.log");

        StringBuilder sb = new StringBuilder();
        sb.append("open_checkpoint " + dcp + "; ");
        sb.append(REPORT_ROUTE_STATUS);

        List<String> log = VivadoTools.runTcl(outputLog, sb.toString(), true);
        return new ReportRouteStatusResult(log);
    }
}

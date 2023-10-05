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
import com.xilinx.rapidwright.edif.EDIFTools;

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
        return runTcl(outputLog, tclScript, verbose, null, null);
    }

    /**
     * method to run a Tcl script in vivado
     * 
     * @param outputLog Path to the log file that vivado will generate
     * @param tclScript Path to the Tcl script that will be run
     * @param verbose   If true vivado command line and std.out/err will be printed
     *                  to std.out
     * @param environ   array of strings, each element of which has environment
     *                  variable settings in the format name=value, or null if the
     *                  subprocess should inherit the environment of the current
     *                  process.
     * @param runDir    the working directory of the subprocess, or null if the
     *                  subprocess should inherit the working directory of the
     *                  current process.
     * @return the contents of the log file as a list of strings
     */
    public static List<String> runTcl(Path outputLog, Path tclScript, boolean verbose, String[] environ, File runDir) {
        if (!FileTools.isVivadoOnPath()) {
            throw new RuntimeException(
                    "ERROR: Could not find vivado executable, current PATH=" + System.getenv("PATH"));
        }
        final String vivadoCmd = "vivado -log " + outputLog.toString() + " -nojournal -mode batch -source "
                + tclScript.toString();
        Integer exitCode = FileTools.runCommand(vivadoCmd, verbose, environ, runDir);
        if (exitCode != 0) {
            throw new RuntimeException("Vivado exited with code: " + exitCode);
        }
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
        final Path dcp = writeCheckpoint(design);
        boolean encrypted = !design.getNetlist().getEncryptedCells().isEmpty();
        ReportRouteStatusResult rrs = reportRouteStatus(dcp, dcp.getParent(), encrypted);

        FileTools.deleteFolder(dcp.getParent().toString());

        return rrs;
    }

    /**
     * Run Vivado's `report_route_status` command on the named net inside the
     * provided Design object and return its specific route status (e.g. ROUTED).
     *
     * @param design Design object to report on.
     * @return String containing net's specific route status.
     */
    public static String reportRouteStatus(Design design, String netName) {
        final Path dcp = writeCheckpoint(design);
        try {
            boolean encrypted = !design.getNetlist().getEncryptedCells().isEmpty();
            return reportRouteStatus(netName, dcp, dcp.getParent(), encrypted);
        } finally {
            FileTools.deleteFolder(dcp.getParent().toString());
        }
    }

    private static Path writeCheckpoint(Design design) {
        final Path workdir = FileSystems.getDefault()
                .getPath("vivadoToolsWorkdir" + FileTools.getUniqueProcessAndHostID());
        File workdirHandle = new File(workdir.toString());
        workdirHandle.mkdirs();
        final Path dcp = workdir.resolve("checkpoint.dcp");
        design.writeCheckpoint(dcp);
        return dcp;
    }

    /**
     * Run Vivado's `write_bitstream` on the provided DCP file to generate a bit
     * file at the specified location.
     * 
     * @param dcp            The DCP file from which to generate a bitstream.
     * @param bitFile        The location of the bit file to generate
     * @param hasEncryptedIP Flag indicating if the provided DCP contains encrypted
     *                       IP and was written by RapidWright such that it needs to
     *                       be loaded with a Tcl script.
     * @return The output of Vivado as a list of Strings
     */
    public static List<String> writeBitstream(Path dcp, Path bitFile, boolean hasEncryptedIP) {
        final Path outputLog = dcp.getParent().resolve("outputLog.log");
        StringBuilder sb = new StringBuilder();
        sb.append(createTclDCPLoadCommand(dcp, hasEncryptedIP));
        sb.append("write_bitstream " + bitFile.toString());
        List<String> log = VivadoTools.runTcl(outputLog, sb.toString(), true);
        FileTools.deleteFolder(dcp.getParent().toString());
        return log;
    }

    /**
     * Run Vivado's `write_bitstream` on the provided design to generate a bit file
     * at the specified location.
     * 
     * @param design  The design from which to generate a bitstream.
     * @param bitFile The location of the bit file to generate
     * @return The output of Vivado as a list of Strings
     */
    public static List<String> writeBitstream(Design design, Path bitFile) {
        Path dcp = writeCheckpoint(design);
        boolean hasEncryptedIP = !design.getNetlist().getEncryptedCells().isEmpty();
        return writeBitstream(dcp, bitFile, hasEncryptedIP);
    }

    /**
     * Run Vivado's `report_route_status` command on the provided DCP (which is assumed
     * to be unencrypted) path and return its result as a ReportRouteStatusResult object.
     *
     * @param dcp Path to DCP to report on.
     * @return ReportRouteStatusResult object.
     */
    public static ReportRouteStatusResult reportRouteStatus(Path dcp) {
        return reportRouteStatus(dcp, false);
    }

    /**
     * Run Vivado's `report_route_status` command on the provided DCP path
     * and return its result as a ReportRouteStatusResult object.
     *
     * @param dcp Path to DCP to report on.
     * @param encrypted Indicates whether DCP contains encrypted EDIF cells.
     * @return ReportRouteStatusResult object.
     */
    public static ReportRouteStatusResult reportRouteStatus(Path dcp, boolean encrypted) {
        final Path workdir = FileSystems.getDefault()
                .getPath("vivadoToolsWorkdir" + FileTools.getUniqueProcessAndHostID());
        File workdirHandle = new File(workdir.toString());
        workdirHandle.mkdirs();

        ReportRouteStatusResult rrs = reportRouteStatus(dcp, workdir, encrypted);

        FileTools.deleteFolder(workdir.toString());

        return rrs;
    }

    /**
     * Run Vivado's `report_route_status` command on the provided DCP path
     * and return its result as a ReportRouteStatusResult object.
     *
     * @param dcp Path to DCP to report on.
     * @param workdir Directory to work within.
     * @param encrypted Indicates whether DCP contains encrypted EDIF cells.
     * @return ReportRouteStatusResult object.
     */
    public static ReportRouteStatusResult reportRouteStatus(Path dcp, Path workdir, boolean encrypted) {
        final Path outputLog = workdir.resolve("outputLog.log");

        StringBuilder sb = new StringBuilder();
        sb.append(createTclDCPLoadCommand(dcp, encrypted));
        sb.append(REPORT_ROUTE_STATUS);

        List<String> log = VivadoTools.runTcl(outputLog, sb.toString(), true);
        return new ReportRouteStatusResult(log);
    }

    /**
     * Run Vivado's `report_route_status` command on the named net inside the
     * provided DCP path and return its route status.
     *
     * @param netName Net name.
     * @param dcp Path to DCP to report on.
     * @param workdir Directory to work within.
     * @param encrypted Indicates whether DCP contains encrypted EDIF cells.
     * @return True if net was fully routed.
     */
    public static String reportRouteStatus(String netName, Path dcp, Path workdir, boolean encrypted) {
        final Path outputLog = workdir.resolve("outputLog.log");

        StringBuilder sb = new StringBuilder();
        sb.append(createTclDCPLoadCommand(dcp, encrypted));
        sb.append(REPORT_ROUTE_STATUS + " -of [get_nets {" + netName + "}]");

        List<String> log = VivadoTools.runTcl(outputLog, sb.toString(), true);
        List<String> matchingLines = VivadoTools.searchVivadoLog(log, "Route status: ");
        if (matchingLines.isEmpty()) {
            return null;
        }
        return matchingLines.get(0).trim().split("\\s+", 3)[2];
    }

    private static String createTclDCPLoadCommand(Path dcp, boolean encrypted) {
        if (encrypted) {
            Path tclFileName = FileTools.replaceExtension(dcp.getFileName(), EDIFTools.LOAD_TCL_SUFFIX);
            return "source " + tclFileName + "; ";
        } else {
            return "open_checkpoint " + dcp + "; ";
        }
    }
}

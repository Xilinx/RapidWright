/*
 * Copyright (c) 2024, Advanced Micro Devices, Inc.
 * All rights reserved.
 *
 * Author: Chris Lavin, AMD Research and Advanced Development.
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
package com.xilinx.rapidwright.placer.dreamplacefpga;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;

import com.xilinx.rapidwright.design.Design;
import com.xilinx.rapidwright.device.Device;
import com.xilinx.rapidwright.edif.EDIFNetlist;
import com.xilinx.rapidwright.edif.EDIFTools;
import com.xilinx.rapidwright.interchange.DeviceResourcesWriter;
import com.xilinx.rapidwright.interchange.Interchange;
import com.xilinx.rapidwright.interchange.LogNetlistWriter;
import com.xilinx.rapidwright.interchange.PhysNetlistReader;
import com.xilinx.rapidwright.interchange.PhysicalNetlistToDcp;
import com.xilinx.rapidwright.tests.CodePerfTracker;
import com.xilinx.rapidwright.util.FileTools;

/**
 * Utility wrapper class to facilitate using DREAMPlaceFPGA as an external
 * placer.
 */
public class DREAMPlaceFPGA {

    public static final String dreamPlaceFPGAExec = "dreamplacefpga";

    public static final String MAKE_DCP_OUT_OF_CONTEXT = PhysicalNetlistToDcp.MAKE_DCP_OUT_OF_CONTEXT;

    /**
     * Given a EDIFNetlist object, place it using DREAMPlaceFPGA.
     * @param netlist EDIFNetlist object to be placed.
     * @return Placed Design object.
     * @throws IOException
     */
    public static Design placeDesign(EDIFNetlist netlist) throws IOException {
        return placeDesign(netlist, null, false);
    }

    /**
     * Given a EDIFNetlist object, place it using DREAMPlaceFPGA.
     * @param netlist EDIFNetlist object to be placed.
     * @param workDir Path to working directory (null to use a temporary directory which gets deleted on return)
     * @return Placed Design object.
     * @throws IOException
     */
    public static Design placeDesign(EDIFNetlist netlist, Path workDir, boolean makeOutOfContext) throws IOException {
        boolean removeWorkDir = false;
        if (workDir == null) {
            workDir = Paths.get("DREAMPlaceFPGAWorkdir" + FileTools.getUniqueProcessAndHostID());
            FileTools.makeDirs(workDir.toString());
            removeWorkDir = true;
        }

        // Create interchange netlist file
        String inputLogNetlistName = "input" + Interchange.LOG_NETLIST_EXT;
        String inputLogNetlistPath = workDir.resolve(inputLogNetlistName).toString();
        LogNetlistWriter.writeLogNetlist(netlist, inputLogNetlistPath);

        // Create device file if it doesn't already exist
        String partName = EDIFTools.getPartName(netlist);
        if (partName == null) {
            throw new RuntimeException("ERROR: Netlist has no part name");
        }
        Device device = netlist.getDevice();
        Path deviceDir = FileTools.getUserSpecificRapidWrightDataPath();
        Path deviceFile = deviceDir.resolve(device.getName() + ".device");
        if (!Files.exists(deviceFile)) {
            try {
                DeviceResourcesWriter.writeDeviceResourcesFile(partName, device,
                        new CodePerfTracker("Create IF Device"), deviceFile.toString(), true);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }

        // Run DREAMPlaceFPGA
        List<String> exec = new ArrayList<>();
        exec.add(dreamPlaceFPGAExec);
        exec.add("-interchange_netlist");
        exec.add(inputLogNetlistName);
        exec.add("-interchange_device");
        exec.add(deviceFile.toString());
        exec.add("-result_dir");
        exec.add(".");

        boolean verbose = true;
        String[] environ = null;
        Integer exitCode = FileTools.runCommand(exec.toArray(new String[0]), verbose, environ, workDir.toFile());
        if (exitCode != 0) {
            throw new RuntimeException("DREAMPlaceFPGA with code: " + exitCode);
        }

        // Load placed result
        Design placedDesign;
        String outputPhysNetlistPath = workDir.resolve("design/design.phys").toString();
        try {
            placedDesign = PhysNetlistReader.readPhysNetlist(outputPhysNetlistPath,
                    netlist);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }

        if (makeOutOfContext) {
            placedDesign.setAutoIOBuffers(false);
            placedDesign.setDesignOutOfContext(true);
        }

        placedDesign.routeSites();

        if (removeWorkDir) {
            FileTools.deleteFolder(workDir.toString());
        }
        return placedDesign;
    }

    /**
     * Checks if dreamplacefpga is available on current PATH (uses unix 'which' or windows 'where').
     * @return true if yosys is on current PATH, false otherwise.
     */
    public static boolean isDREAMPlaceFPGAOnPath() {
        return FileTools.isExecutableOnPath(dreamPlaceFPGAExec);
    }

    public static void main(String[] args) throws IOException {
        // Usage: <input DCP> <output DCP> [--out_of_context] [work directory]
        if (args.length < 2 || args.length > 4) {
            System.out.println("USAGE: <input DCP> <output DCP> [" 
                            + MAKE_DCP_OUT_OF_CONTEXT + "] [work directory]");
            return;
        }
        Design input = Design.readCheckpoint(args[0]);

        boolean makeOutOfContext = false;
        if (args.length >= 3) {
            if (args[2].equals(MAKE_DCP_OUT_OF_CONTEXT)) {
                makeOutOfContext = true;
            } 
        }

        Path workDir = args.length == 4 ? Paths.get(args[3]) :
                args.length == 3 && !makeOutOfContext ? Paths.get(args[2]) :
                null;
        Design placed = placeDesign(input.getNetlist(), workDir, makeOutOfContext);
        placed.writeCheckpoint(args[1]);
    }
}

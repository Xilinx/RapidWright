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
import java.util.HashMap;
import java.util.Map;
import java.util.Map.Entry;

import com.xilinx.rapidwright.design.Design;
import com.xilinx.rapidwright.design.shapes.ShapeTools;
import com.xilinx.rapidwright.edif.EDIFTools;
import com.xilinx.rapidwright.edif.EDIFNetlist;
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

    public static final String INTERCHANGE_NETLIST = "interchange_netlist";
    public static final String INTERCHANGE_DEVICE = "interchange_device";
    public static final String IO_PL = "io_pl";
    public static final String GPU = "gpu";
    public static final String NUM_BINS_X = "num_bins_x";
    public static final String NUM_BINS_Y = "num_bins_y";
    public static final String GLOBAL_PLACE_STAGES = "global_place_stages";
    public static final String TARGET_DENSITY = "target_density";
    public static final String DENSITY_WEIGHT = "density_weight";
    public static final String RANDOM_SEED = "random_seed";
    public static final String SCALE_FACTOR = "scale_factor";
    public static final String GLOBAL_PLACE_FLAG = "global_place_flag";
    public static final String PLACE_SOL = "place_sol";
    public static final String ROUTABILITY_OPT_FLAG = "routability_opt_flag";
    public static final String LEGALIZE_FLAG = "legalize_flag";
    public static final String DETAILED_PLACE_FLAG = "detailed_place_flag";
    public static final String DTYPE = "dtype";
    public static final String PLOT_FLAG = "plot_flag";
    public static final String NUM_THREADS = "num_threads";
    public static final String DETERMINISTIC_FLAG = "deterministic_flag";
    public static final String ENABLE_IF = "enable_if";
    public static final String ENABLE_SITE_ROUTING = "enable_site_routing";

    public static final boolean GPU_DEFAULT = false;
    public static final int NUM_BINS_X_DEFAULT = 512;
    public static final int NUM_BINS_Y_DEFAULT = 512;
    public static final String GLOBAL_PLACE_STAGES_DEFAULT = 
            "[\n{\"num_bins_x\" : 512,"
            + " \"num_bins_y\" : 512,"
            + " \"iteration\" : 2000,"
            + " \"learning_rate\" : 0.01,"
            + " \"wirelength\" : \"weighted_average\","
            + " \"optimizer\" : \"nesterov\"}\n]";
    public static final double TARGET_DENSITY_DEFAULT = 1.0;
    public static final double DENSITY_WEIGHT_DEFAULT = 8e-5;
    public static final int RANDOM_SEED_DEFAULT = 1000;
    public static final double SCALE_FACTOR_DEFAULT = 1.0;
    public static final boolean GLOBAL_PLACE_FLAG_DEFAULT = true;
    public static final boolean ROUTABILITY_OPT_FLAG_DEFAULT = false;
    public static final boolean LEGALIZE_FLAG_DEFAULT = true;
    public static final boolean DETAILED_PLACE_FLAG_DEFAULT = false;
    public static final String DTYPE_DEFAULT = "float32";
    public static final boolean PLOT_FLAG_DEFAULT = false;
    public static final int NUM_THREADS_DEFAULT = 8;
    public static final boolean DETERMINISTIC_FLAG_DEFAULT = true;
    public static final boolean ENABLE_IF_DEFAULT = true;
    public static final boolean ENABLE_SITE_ROUTING_DEFAULT = true;

    public static final String dreamPlaceFPGAExec = "python dreamplacefpga/Placer.py";

    public static Map<String, Object> getSettingsMap() {
        Map<String, Object> map = new HashMap<>();

        map.put(GPU, GPU_DEFAULT);
        map.put(NUM_BINS_X, NUM_BINS_X_DEFAULT);
        map.put(NUM_BINS_Y, NUM_BINS_Y_DEFAULT);
        map.put(GLOBAL_PLACE_STAGES, GLOBAL_PLACE_STAGES_DEFAULT);
        map.put(TARGET_DENSITY, TARGET_DENSITY_DEFAULT);
        map.put(DENSITY_WEIGHT, DENSITY_WEIGHT_DEFAULT);
        map.put(RANDOM_SEED, RANDOM_SEED_DEFAULT);
        map.put(SCALE_FACTOR, SCALE_FACTOR_DEFAULT);
        map.put(GLOBAL_PLACE_FLAG, GLOBAL_PLACE_FLAG_DEFAULT);
        map.put(ROUTABILITY_OPT_FLAG, ROUTABILITY_OPT_FLAG_DEFAULT);
        map.put(LEGALIZE_FLAG, LEGALIZE_FLAG_DEFAULT);
        map.put(DETAILED_PLACE_FLAG, DETAILED_PLACE_FLAG_DEFAULT);
        map.put(DTYPE, DTYPE_DEFAULT);
        map.put(PLOT_FLAG, PLOT_FLAG_DEFAULT);
        map.put(NUM_THREADS, NUM_THREADS_DEFAULT);
        map.put(DETERMINISTIC_FLAG, DETERMINISTIC_FLAG_DEFAULT);
        map.put(ENABLE_IF, ENABLE_IF_DEFAULT);
        map.put(ENABLE_SITE_ROUTING, ENABLE_SITE_ROUTING_DEFAULT);

        return map;
    }

    public static void writeJSONForDREAMPlaceFPGA(Path jsonPath, Map<String, Object> attributes) {
        try (BufferedWriter bw = new BufferedWriter(new FileWriter(jsonPath.toFile()))) {
            bw.write("{\n");
            boolean first = true;
            for (Entry<String, Object> e : attributes.entrySet()) {
                if (first) {
                    first = false;
                } else {
                    bw.write(",\n");
                }
                bw.write("    \"" + e.getKey() + "\"");
                bw.write(" : ");
                if (e.getValue() instanceof String && !e.getKey().equals(GLOBAL_PLACE_STAGES)) {
                    bw.write("\"" + e.getValue().toString() + "\"");
                } else if (e.getValue() instanceof Boolean) {
                    bw.write((boolean) e.getValue() ? "1" : "0");
                } else {
                    bw.write(e.getValue().toString() + "");
                }

            }
            bw.write("\n}\n");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }

    }

    public static Design placeDesign(Design design, String ioPlacementFile, boolean makeOutOfContext, Path workDir) {
        // Create interchange netlist file
        String interchangeRootFile = workDir.toString() + File.separator + "design";
        EDIFNetlist flatNetlist = EDIFTools.createFlatNetlist(design.getNetlist(), design.getPartName());
        flatNetlist.setShapes(ShapeTools.extractShapes(design, null));
        try {
            LogNetlistWriter.writeLogNetlist(flatNetlist, interchangeRootFile + Interchange.LOG_NETLIST_EXT);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        // Interchange.writeDesignToInterchange(design, interchangeRootFile);

        design.getNetlist().expandMacroUnisims(design.getDevice().getSeries());
        // Create device file if it doesn't already exist
        String deviceName = design.getDevice().getName();
        Path deviceFile = workDir.resolve(deviceName + ".device");
        if (!Files.exists(deviceFile)) {
            try {
                DeviceResourcesWriter.writeDeviceResourcesFile(design.getPartName(), design.getDevice(),
                        new CodePerfTracker("Create IF Device"), deviceFile.toString(), true);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }

        // Create JSON file for DREAMPlaceFPGA
        Path jsonFile = workDir.resolve("design.json");
        Map<String, Object> settings = getSettingsMap();
        settings.put(INTERCHANGE_DEVICE, deviceFile.toString());
        settings.put(INTERCHANGE_NETLIST, interchangeRootFile + Interchange.LOG_NETLIST_EXT);
        settings.put(PLACE_SOL, "");
        settings.put(IO_PL, ioPlacementFile);
        writeJSONForDREAMPlaceFPGA(jsonFile, settings);

        // Run DREAMPlaceFPGA
        String exec = dreamPlaceFPGAExec + " " + jsonFile.toString();
        boolean verbose = true;
        String[] environ = null;
        Integer exitCode = FileTools.runCommand(exec, verbose, environ, workDir.toFile());
        if (exitCode != 0) {
            throw new RuntimeException("DREAMPlaceFPGA with code: " + exitCode);
        }

        // Load placed result
        Design placedDesign = null;
        String interchangeResultFile = workDir.toString() + File.separator + "results/design/design";
        try {
            placedDesign = PhysNetlistReader.readPhysNetlist(interchangeResultFile + Interchange.PHYS_NETLIST_EXT,
                    design.getNetlist());
            if (makeOutOfContext) {
                placedDesign.setAutoIOBuffers(false);
                placedDesign.setDesignOutOfContext(true);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return placedDesign;
    }

    public static void main(String[] args) {
        // Usage: <input DCP> <output DCP> [IO placement file (.pl)] [work directory]
        if (args.length < 2 || args.length > 4) {
            System.out.println("USAGE: <input DCP> <output DCP> [IO placement file (.pl)] [work directory]");
            return;
        }
        Design input = Design.readCheckpoint(args[0]);

        boolean makeOutOfContext = true;
        String ioPlacementFile = "";
        if (args.length >= 3) {
            File file = new File(args[2]);
            if (!file.isDirectory()) {
                ioPlacementFile = args[2];
                makeOutOfContext = false;
            } 
        }

        Path workDir = args.length == 4 ? Paths.get(args[3]) : args.length == 3 && makeOutOfContext ? Paths.get(args[2]) : Paths.get(System.getProperty("user.dir"));
        Design placed = placeDesign(input, ioPlacementFile, makeOutOfContext, workDir);
        placed.writeCheckpoint(args[1]);
    }
}

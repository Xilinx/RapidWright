/*
 * Copyright (c) 2024, Advanced Micro Devices, Inc.
 * All rights reserved.
 *
 * Author: Eddie Hung, Advanced Micro Devices, Inc.
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

package com.xilinx.rapidwright.edif;

import com.xilinx.rapidwright.util.FileTools;

import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public class YosysTools {
    public static final String yosysExec = "yosys";

    public static final String SYNTH_XILINX = "synth_xilinx";

    public static final String SYNTH_XILINX_FLAG_FAMILY_XCUP = " -family xcup";
    public static final String SYNTH_XILINX_FLAG_EDIF = " -edif ";
    public static final String SYNTH_XILINX_FLAG_FLATTEN = " -flatten";
    public static final String SYNTH_XILINX_FLAG_OUT_OF_CONTEXT = " -noclkbuf -noiopad";

    /**
     * Run the given command string in Yosys, on the files given.
     * @param command Yosys command(s), separated by ';'
     * @param workDir Working directory
     * @param paths Path objects of input files
     */
    public static void run(String command, Path workDir, Path... paths) {
        List<String> exec = new ArrayList<>();
        exec.add(FileTools.getPath(yosysExec));
        exec.add("-p");
        exec.add(command);
        for (Path path : paths) {
            exec.add(path.toString());
        }

        boolean verbose = true;
        String[] environ = null;
        Integer exitCode = FileTools.runCommand(exec.toArray(new String[0]), verbose, environ, workDir.toFile());
        if (exitCode != 0) {
            throw new RuntimeException("Yosys exited with code: " + exitCode);
        }
    }

    /**
     * Call Yosys' 'synth_xilinx' command with the given flags on the files given.
     * @param flags String with flags to be provided to 'synth_xilinx', in addition to
     *              '-edif <workDir>/output.edf'.
     * @param workDir Working directory
     * @param paths Path objects of input files
     * @return EDIFNetlist object of Yosys' result
     */
    public static EDIFNetlist synthXilinxWithWorkDir(String flags, Path workDir, Path... paths) {
        final Path edf = workDir.resolve("output.edf");
        String command = SYNTH_XILINX;
        command += SYNTH_XILINX_FLAG_EDIF + edf;
        command += flags;
        run(command, workDir, paths);
        return EDIFTools.readEdifFile(edf);
    }

    /**
     * Call Yosys' 'synth_xilinx' command with the default flags '-family xcvup -flatten
     * -edif <workDir>/output.edf' on the files given.
     * @param workDir Working directory
     * @param paths Path objects of input files
     * @return EDIFNetlist object of Yosys' result
     */
    public static EDIFNetlist synthXilinxWithWorkDir(Path workDir, Path... paths) {
        return synthXilinxWithWorkDir(SYNTH_XILINX_FLAG_FAMILY_XCUP + SYNTH_XILINX_FLAG_FLATTEN, workDir, paths);
    }

    /**
     * Call Yosys' 'synth_xilinx' command with the given flags on the files given.
     * @param flags String with flags to be provided to 'synth_xilinx', in addition to
     *              '-edif <workDir>/output.edf'.
     * @param paths Path objects of input files
     * @return EDIFNetlist object of Yosys' result
     */
    public static EDIFNetlist synthXilinx(String flags, Path... paths) {
        final Path workDir = FileSystems.getDefault()
                .getPath("yosysToolsWorkdir" + FileTools.getUniqueProcessAndHostID());
        workDir.toFile().mkdirs();

        EDIFNetlist netlist = synthXilinxWithWorkDir(flags, workDir, paths);

        FileTools.deleteFolder(workDir.toString());
        return netlist;
    }

    /**
     * Call Yosys' 'synth_xilinx' command with the default flags '-family xcvup -flatten
     * -edif <workDir>/output.edf' on the files given.
     * @param paths Path objects of input files
     * @return EDIFNetlist object of Yosys' result
     */
    public static EDIFNetlist synthXilinx(Path... paths) {
        final Path workDir = FileSystems.getDefault()
                .getPath("yosysToolsWorkdir" + FileTools.getUniqueProcessAndHostID());
        workDir.toFile().mkdirs();

        EDIFNetlist netlist = synthXilinxWithWorkDir(workDir, paths);

        FileTools.deleteFolder(workDir.toString());
        return netlist;
    }
}

/*
 * Copyright (c) 2025, Advanced Micro Devices, Inc.
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

package com.xilinx.rapidwright.design.tools;

import java.nio.file.Path;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.xilinx.rapidwright.support.RapidWrightDCP;
import com.xilinx.rapidwright.util.FileTools;
import com.xilinx.rapidwright.util.VivadoTools;
import com.xilinx.rapidwright.util.VivadoToolsHelper;

public class TestPathExtractor {

    @Test
    public void testPathExtractor(@TempDir Path dir) {
        Assumptions.assumeTrue(FileTools.isVivadoOnPath());

        Path dcpPath = RapidWrightDCP.getPath("microblazeAndILA_3pblocks_2024.1.dcp");

        Path pathTxt = dir.resolve("path.txt");
        String tclCommand = "open_checkpoint " + dcpPath + ";";
        tclCommand += "set fp [open " + pathTxt + " \"w\"];";
        tclCommand += " foreach p [get_pins -of [get_timing_paths -nworst 1 ]] {puts $fp $p};";
        tclCommand += "close $fp";
        VivadoTools.runTcl(dir.resolve("out.log"), tclCommand, true);

        Path outputDCP = dir.resolve("path.dcp");
        
        PathExtractor.main(new String[] {dcpPath.toString(), outputDCP.toString(), pathTxt.toString()});

        VivadoToolsHelper.assertFullyRouted(outputDCP);
    }
}

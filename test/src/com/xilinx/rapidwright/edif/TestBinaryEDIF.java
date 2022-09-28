/*
 *
 * Copyright (c) 2022, Xilinx, Inc.
 * Copyright (c) 2022, Advanced Micro Devices, Inc.
 * All rights reserved.
 *
 * Author: Chris Lavin, Xilinx Research Labs.
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
/**
 *
 */
package com.xilinx.rapidwright.edif;

import java.nio.file.Path;
import java.nio.file.Paths;

import com.xilinx.rapidwright.design.Design;
import com.xilinx.rapidwright.support.RapidWrightDCP;
import com.xilinx.rapidwright.util.FileTools;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

public class TestBinaryEDIF {

    public static final String RW_TEST_DCP_PATH_VAR_NAME = "RW_TEST_DCP_PATH";
    public static final String RW_TEST_WORKING_DIR_VAR_NAME = "RW_TEST_WORKING_DIR";


    @Test
    public void testBinaryEDIF(@TempDir Path tempDir) {
        String dcpPath = System.getenv(RW_TEST_DCP_PATH_VAR_NAME);
        Assumptions.assumeTrue(dcpPath == null);
        Path dcp = RapidWrightDCP.getPath("optical-flow.dcp");
        testBinaryEDIF(tempDir, dcp);
    }

    @Test
    public void runTestBinaryEDIFOnLSF() {
        String dcpPath = System.getenv(RW_TEST_DCP_PATH_VAR_NAME);
        Assumptions.assumeTrue(dcpPath != null);
        String workingDir = System.getenv(RW_TEST_WORKING_DIR_VAR_NAME);
        FileTools.makeDirs(workingDir);
        System.out.println(RW_TEST_DCP_PATH_VAR_NAME + "=" + dcpPath);
        System.out.println(RW_TEST_WORKING_DIR_VAR_NAME + "=" + workingDir);
        testBinaryEDIF(Paths.get(workingDir), Paths.get(dcpPath));
        FileTools.writeStringToTextFile("SUCCESS", workingDir + "/SUCCESS");
    }

    public void testBinaryEDIF(Path workingDir, Path dcp) {
        boolean noXdef = true;
        Design design = Design.readCheckpoint(dcp, noXdef);
        EDIFNetlist netlist = design.getNetlist();
        // Reading the checkpoint will expand the macros automatically, we need to collapse
        // before writing out the netlist
        netlist.collapseMacroUnisims(design.getDevice().getSeries());
        Path goldenPath = workingDir.resolve("golden.edf");
        netlist.exportEDIF(goldenPath);

        EDIFNetlist golden = EDIFTools.readEdifFile(goldenPath);

        Path binaryPath = workingDir.resolve("test.bedf");
        netlist.writeBinaryEDIF(binaryPath);
        EDIFNetlist test = EDIFNetlist.readBinaryEDIF(binaryPath);

        Assertions.assertTrue(EquivalentEDIF.equivalentEDIFNetlists(golden, test));

        Path testPath = workingDir.resolve("test.edf");
        test.exportEDIF(testPath);

        Assertions.assertTrue(EquivalentEDIF.compareEDIFFiles(goldenPath, testPath));
    }
}

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

package com.xilinx.rapidwright.placer;

import com.xilinx.rapidwright.design.Design;
import com.xilinx.rapidwright.edif.EDIFNetlist;
import com.xilinx.rapidwright.placer.dreamplacefpga.DREAMPlaceFPGA;
import com.xilinx.rapidwright.support.RapidWrightDCP;
import com.xilinx.rapidwright.util.ReportRouteStatusResult;
import com.xilinx.rapidwright.util.VivadoTools;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;

public class TestDREAMPlaceFPGA {

    @Test
    public void testDREAMPlaceFPGAMain(@TempDir Path tempDir) throws IOException {
        // Skip test if dreamplacefpga is not on PATH
        Assumptions.assumeTrue(DREAMPlaceFPGA.isDREAMPlaceFPGAOnPath());

        String inputDcp = RapidWrightDCP.getString("gnl_2_4_3_1.3_gnl_3000_07_3_80_80_placed.dcp");
        String outputDcp = tempDir.resolve("output.dcp").toString();
        DREAMPlaceFPGA.main(new String[]{inputDcp, outputDcp, DREAMPlaceFPGA.MAKE_DCP_OUT_OF_CONTEXT, tempDir.toString()});

        ReportRouteStatusResult rrs = VivadoTools.reportRouteStatus(Paths.get(outputDcp));
        Assertions.assertEquals(3465, rrs.routableNets);
        Assertions.assertEquals(3465, rrs.unroutedNets);
        Assertions.assertEquals(0, rrs.netsWithRoutingErrors);
    }

    @Test
    public void testDREAMPlaceFPGA(@TempDir Path tempDir) throws IOException {
        // Skip test if dreamplacefpga is not on PATH
        Assumptions.assumeTrue(DREAMPlaceFPGA.isDREAMPlaceFPGAOnPath());

        boolean skipXdef = true;
        Design design = RapidWrightDCP.loadDCP("gnl_2_4_3_1.3_gnl_3000_07_3_80_80_placed.dcp", skipXdef);
        Assertions.assertTrue(design.getSiteInsts().isEmpty());
        EDIFNetlist netlist = design.getNetlist();
        design = null;

        boolean makeOutOfContext = true;
        design = DREAMPlaceFPGA.placeDesign(netlist, tempDir, makeOutOfContext);

        Path outputDcp = tempDir.resolve("output.dcp");
        design.writeCheckpoint(outputDcp);
        boolean encrypted = !netlist.getEncryptedCells().isEmpty();
        ReportRouteStatusResult rrs = VivadoTools.reportRouteStatus(outputDcp, tempDir, encrypted);
        Assertions.assertEquals(3465, rrs.routableNets);
        Assertions.assertEquals(3465, rrs.unroutedNets);
        Assertions.assertEquals(0, rrs.netsWithRoutingErrors);
    }
}

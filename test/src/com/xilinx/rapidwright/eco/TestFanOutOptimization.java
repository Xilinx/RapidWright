/*
 * Copyright (c) 2025, Advanced Micro Devices, Inc.
 * All rights reserved.
 *
 * Author: Chris Lavin, AMD Advanced Research and Development.
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

package com.xilinx.rapidwright.eco;

import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.xilinx.rapidwright.design.Cell;
import com.xilinx.rapidwright.design.Design;
import com.xilinx.rapidwright.design.Net;
import com.xilinx.rapidwright.edif.EDIFHierPortInst;
import com.xilinx.rapidwright.rwroute.RWRoute;
import com.xilinx.rapidwright.support.RapidWrightDCP;
import com.xilinx.rapidwright.util.VivadoToolsHelper;

public class TestFanOutOptimization {

    private void runAndValidateCut(Design design, String netName, boolean useOnlyEmptySites,
            int partitionCount) {
        Net net = design.getNet(netName);
        List<EDIFHierPortInst> srcs = net.getLogicalHierNet().getLeafHierPortInsts(true, false,
                false);
        Assertions.assertEquals(1, srcs.size());
        Cell driverCell = design.getCell(srcs.get(0).getFullHierarchicalInstName());
        int sinkCount = net.getSinkPins().size();

        FanOutOptimization.cutFanOutOfRoutedNet(design, net, partitionCount, useOnlyEmptySites);

        int afterSinkCount = net.getSinkPins().size();

        for (int i = 1; i < partitionCount; i++) {
            String suffix = FanOutOptimization.UNIQUE_SUFFIX + i;
            Net copy = design.getNet(net.getName() + suffix);
            afterSinkCount += copy.getSinkPins().size();
            Cell driverCopy = design.getCell(driverCell.getName() + suffix);
            Assertions.assertNotNull(driverCopy);
            Assertions.assertTrue(driverCopy.isPlaced());
        }

        Assertions.assertEquals(sinkCount, afterSinkCount);
    }

    @Test
    public void testFanOutOptimization(@TempDir Path dir) {
        Design design = RapidWrightDCP.loadDCP("bnn.dcp");

        RWRoute.preprocess(design);

        // Driven by flop which has a source outside the site
        runAndValidateCut(design, "bd_0_i/hls_inst/inst/ap_CS_fsm_state3", false, 3);

        // Driven by a flop which has a source inside the same site
        runAndValidateCut(design, "bd_0_i/hls_inst/inst/grp_bin_conv_fu_485/ap_CS_fsm_state46", true, 3);

        // Driven by a LUT6
        runAndValidateCut(design, "bd_0_i/hls_inst/inst/grp_bin_conv_fu_485/p_Val2_s_fu_662[63]_i_2_n_0", false, 3);

        Path outputDCP = dir.resolve("bnn_mod.dcp");
        design.writeCheckpoint(outputDCP);

        VivadoToolsHelper.assertCanBeFullyRoutedByVivado(outputDCP, dir, false);
    }
}

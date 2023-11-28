/*
 * Copyright (c) 2023, Advanced Micro Devices, Inc.
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

package com.xilinx.rapidwright.router;

import com.xilinx.rapidwright.design.Design;
import com.xilinx.rapidwright.design.Net;
import com.xilinx.rapidwright.design.blocks.PBlock;
import com.xilinx.rapidwright.support.RapidWrightDCP;
import com.xilinx.rapidwright.util.FileTools;
import com.xilinx.rapidwright.util.VivadoTools;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.io.IOException;

public class TestSATRouter {
    @Test
    public void testApplyResult() {
        // Adapted from https://github.com/clavin-xlnx/RapidWright-binder/blob/24527f33b6aea283cf430ab8f4eab3dc01fa5d64/SATRouter.ipynb
        Design design = RapidWrightDCP.loadDCP("reduce_or_routed_7overlaps.dcp");

        for (Net net : design.getNets()) {
            if (net.isClockNet() || net.isStaticNet()) {
                continue;
            }
            net.unroute();
        }

        PBlock pblock = new PBlock(design.getDevice(), " SLICE_X108Y660:SLICE_X111Y664");
        SATRouter satRouter = new SATRouter(design, pblock, false);

        FileTools.copyFile(RapidWrightDCP.getString("reduce_or_routed_7overlaps_solution.txt"),
                satRouter.getOutputFile());

        satRouter.applyRoutingResult();

        if (FileTools.isVivadoOnPath()) {
            Assertions.assertTrue(VivadoTools.reportRouteStatus(design).isFullyRouted());
        }
    }
}

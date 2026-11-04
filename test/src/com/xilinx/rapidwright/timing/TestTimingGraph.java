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

package com.xilinx.rapidwright.timing;

import org.jgrapht.GraphPath;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import com.xilinx.rapidwright.design.Cell;
import com.xilinx.rapidwright.design.Design;
import com.xilinx.rapidwright.design.Net;
import com.xilinx.rapidwright.design.Unisim;
import com.xilinx.rapidwright.design.tools.LUTTools;
import com.xilinx.rapidwright.eco.ECOTools;
import com.xilinx.rapidwright.edif.EDIFHierNet;
import com.xilinx.rapidwright.rwroute.RWRouteConfig;
import com.xilinx.rapidwright.support.RapidWrightDCP;
import com.xilinx.rapidwright.timing.delayestimator.DelayEstimatorBase;
import com.xilinx.rapidwright.timing.delayestimator.InterconnectInfo;

public class TestTimingGraph {

    @Test
    public void testGetTimingPaths() {
        Design d = RapidWrightDCP.loadDCP("picoblaze_ooc_X10Y235_2022_1.dcp");

        TimingManager tm = new TimingManager(d);
        TimingGraph tg = tm.getTimingGraph();

        GraphPath<TimingVertex, TimingEdge> criticalPath = tg.getMaxDelayPath();

        EDIFHierNet clk = tg.getClockNet(criticalPath);
        Assertions.assertEquals("clk", clk.toString());

        tg.prettyPrintPathDelays(criticalPath);

        Assertions.assertEquals(2437.7f, tg.getPathDelay(criticalPath));

        GraphPath<TimingVertex, TimingEdge> otherPath = tg.getTimingPath(
                "processor/sx_addr4_flop/Q",
                "output_port_w_reg[6]/D");

        clk = tg.getClockNet(otherPath);
        Assertions.assertEquals("clk", clk.toString());

        tg.prettyPrintPathDelays(otherPath);
        
        Assertions.assertEquals(1611.1f, tg.getPathDelay(otherPath));
    }

    /**
     * Tests the delay from a LUT6 to the flip-flop beside it, a hop within a slice that depends only on the timing
     * model's data, not on any routing: whether the LUT6 output drives only that flip-flop, or also leaves the slice
     * for another (where that flip-flop is reached from the LUT6 without a site pin, while the net has one).
     * @param alsoLeavesSite Whether the LUT6 output also drives a flip-flop in another slice.
     */
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    public void testLutToFlipFlopIntraSiteDelay(boolean alsoLeavesSite) {
        Design design = new Design("top", "xcvu3p-ffvc1517-2-e");
        Cell lut = design.createAndPlaceCell("lut", Unisim.LUT6, "SLICE_X0Y0/A6LUT");
        LUTTools.configureLUT(lut, "O=I0");
        Cell ff = design.createAndPlaceCell("ff", Unisim.FDRE, "SLICE_X0Y0/AFF");
        Net net = design.createNet("net");
        ECOTools.connectNet(design, lut, "O", net);
        ECOTools.connectNet(design, ff, "D", net);
        if (alsoLeavesSite) {
            Cell other = design.createAndPlaceCell("other", Unisim.FDRE, "SLICE_X0Y1/AFF");
            ECOTools.connectNet(design, other, "D", net);
            Assertions.assertFalse(net.getSinkPins().isEmpty());
        } else {
            Assertions.assertTrue(net.getSinkPins().isEmpty());
        }

        TimingGraph tg = new TimingManager(design).getTimingGraph();
        GraphPath<TimingVertex, TimingEdge> path = tg.getTimingPath("lut/O", "ff/D");
        // A6LUT/O6 to AFF/D, from $RAPIDWRIGHT_PATH/timing/ultrascaleplus/intrasite_delay_terms.txt
        Assertions.assertEquals(50f, tg.getPathDelay(path));
    }

    /**
     * Tests that a cell whose every pin is on a net that never leaves its site (here a MUXF7 between two LUT6s and the
     * flip-flop beside it) has its arcs in the timing graph, also when it is built for partial routing (as
     * PartialRouter does), which gives arcs only to the cells of the nets it is given.
     * @param isPartialRouting Whether the timing graph is built for partial routing.
     */
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    public void testIntraSiteOnlyCellArcs(boolean isPartialRouting) {
        Design design = new Design("top", "xcvu3p-ffvc1517-2-e");
        Cell lutA = design.createAndPlaceCell("lutA", Unisim.LUT6, "SLICE_X0Y0/A6LUT");
        LUTTools.configureLUT(lutA, "O=I0");
        Cell lutB = design.createAndPlaceCell("lutB", Unisim.LUT6, "SLICE_X0Y0/B6LUT");
        LUTTools.configureLUT(lutB, "O=I0");
        Cell mux = design.createAndPlaceCell("mux", Unisim.MUXF7, "SLICE_X0Y0/F7MUX_AB");
        Cell ff = design.createAndPlaceCell("ff", Unisim.FDRE, "SLICE_X0Y0/BFF");
        String[][] connections = {{"lutA", "O", "mux", "I1"}, {"lutB", "O", "mux", "I0"}, {"mux", "O", "ff", "D"}};
        for (String[] c : connections) {
            Net net = design.createNet(c[0] + "_" + c[1]);
            ECOTools.connectNet(design, design.getCell(c[0]), c[1], net);
            ECOTools.connectNet(design, design.getCell(c[2]), c[3], net);
            Assertions.assertTrue(net.getSinkPins().isEmpty());
        }

        RWRouteConfig config = new RWRouteConfig(new String[] {});
        DelayEstimatorBase<InterconnectInfo> estimator = new DelayEstimatorBase<>(design.getDevice(),
                new InterconnectInfo(), config.isUseUTurnNodes(), 0);
        TimingGraph tg = new TimingManager(design, null, config, null, design.getNets(), isPartialRouting, estimator)
                .getTimingGraph();
        GraphPath<TimingVertex, TimingEdge> path = tg.getTimingPath("lutA/O", "ff/D");
        // A6LUT/O6 to F7MUX_AB/1 (21), the MUXF7 from 1 (70), F7MUX_AB/OUT to BFF/D (32), from
        // $RAPIDWRIGHT_PATH/timing/ultrascaleplus/intrasite_delay_terms.txt
        Assertions.assertNotNull(path);
        Assertions.assertEquals(21f + 70 + 32, tg.getPathDelay(path));
    }
}

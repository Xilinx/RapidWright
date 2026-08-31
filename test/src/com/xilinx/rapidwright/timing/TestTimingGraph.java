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

import com.xilinx.rapidwright.design.Design;
import com.xilinx.rapidwright.edif.EDIFHierNet;
import com.xilinx.rapidwright.support.RapidWrightDCP;

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
}

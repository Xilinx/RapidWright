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

import java.util.List;

import org.jgrapht.GraphPath;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import com.xilinx.rapidwright.design.Design;
import com.xilinx.rapidwright.device.Series;
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

    /**
     * Verifies that a TimingGraph can be built for a Versal design in
     * topology-only mode. The lightweight delay model has no Versal data,
     * so all delays are 0; the structural graph (vertices, edges with
     * Net/SitePinInst annotations, and Q->D paths between FDRE/FDSE/FDPE/FDCE
     * cells) must still be produced.
     */
    @Test
    public void testVersalTopologyOnly() {
        Design d = RapidWrightDCP.loadDCP("picoblaze_2022.2.dcp");
        Assertions.assertEquals(Series.Versal, d.getDevice().getSeries(),
                "picoblaze_2022.2.dcp is expected to be a Versal design");

        TimingManager tm = new TimingManager(d);
        Assertions.assertTrue(tm.getTimingModel().isTopologyOnly(),
                "TimingModel should fall back to topology-only mode on Versal");

        TimingGraph tg = tm.getTimingGraph();
        Assertions.assertTrue(tg.vertexSet().size() > 0, "graph has no vertices");
        Assertions.assertTrue(tg.edgeSet().size() > 0, "graph has no edges");

        // At least one edge should carry a physical Net annotation (proves the
        // structural information survives even with no delay data).
        long edgesWithNet = tg.edgeSet().stream()
                .filter(e -> e.getNet() != null)
                .count();
        Assertions.assertTrue(edgesWithNet > 0,
                "no TimingEdges carry a physical Net annotation");

        // FF vertices should be marked from FDRE/FDSE/FDPE/FDCE cells in the design.
        long flopOutputs = tg.vertexSet().stream().filter(TimingVertex::getFlopOutput).count();
        long flopInputs  = tg.vertexSet().stream().filter(TimingVertex::getFlopInput ).count();
        Assertions.assertTrue(flopOutputs > 0, "no FF output vertices were marked");
        Assertions.assertTrue(flopInputs  > 0, "no FF input vertices were marked");

        // Path enumeration: getMaxDelayPath() internally calls buildGraphPaths(1)
        // (K-shortest-paths through super-source/super-sink), which is the working
        // entry point for path enumeration. With all delays = 0, the returned path
        // is still a structurally valid super-source -> super-sink walk.
        GraphPath<TimingVertex, TimingEdge> path = tg.getMaxDelayPath();
        Assertions.assertNotNull(path, "getMaxDelayPath() returned null");
        Assertions.assertTrue(path.getEdgeList().size() > 0,
                "max-delay path has no edges");

        // The internal cache populated by getMaxDelayPath() should contain >= 1 path.
        Assertions.assertTrue(tg.getGraphPaths().size() > 0,
                "graph path cache is empty after getMaxDelayPath()");

        // Walking the path's edges, at least one interior edge should carry a Net.
        long pathEdgesWithNet = path.getEdgeList().stream()
                .filter(e -> e.getNet() != null)
                .count();
        Assertions.assertTrue(pathEdgesWithNet > 0,
                "enumerated path has no edges with Net annotation");
    }
}

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

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import com.xilinx.rapidwright.design.Cell;
import com.xilinx.rapidwright.design.Design;
import com.xilinx.rapidwright.design.Net;
import com.xilinx.rapidwright.device.BELPin;
import com.xilinx.rapidwright.design.Unisim;
import com.xilinx.rapidwright.design.tools.LUTTools;
import com.xilinx.rapidwright.eco.ECOTools;
import com.xilinx.rapidwright.edif.EDIFHierNet;
import com.xilinx.rapidwright.edif.EDIFHierPortInst;
import com.xilinx.rapidwright.rwroute.RWRoute;
import com.xilinx.rapidwright.rwroute.RWRouteConfig;
import com.xilinx.rapidwright.support.RapidWrightDCP;
import com.xilinx.rapidwright.timing.sta.StaGraph;
import com.xilinx.rapidwright.timing.sta.TimingReport;
import com.xilinx.rapidwright.timing.delayestimator.DelayEstimator;

/**
 * Tests the paths that {@link TimingManager} times, through {@link TimingReport}: their delays, the cell arcs and
 * intra-site hops of the timing graph, with the timing model of each series.
 */
public class TestTimingPaths {

    private static final float TOLERANCE = 0.01f;
    /** The delays of the paths of {@link #testGetTimingPaths()} */
    private static final float CRITICAL_PATH_DELAY_PS = 2489.0f;
    private static final float OTHER_PATH_DELAY_PS = 1447.54f;

    /**
     * Gets the delay of the path of largest delay between two pins of a design's timing graph.
     * @param tm The timing manager of the design.
     * @param from The name of the first pin, e.g. "lut/O".
     * @param to The name of the last pin.
     * @return The delay of the path in ps.
     */
    private static float getPathDelay(TimingManager tm, String from, String to) {
        StaGraph g = tm.getStaGraph();
        int fromPin = g.getPin(from);
        int toPin = g.getPin(to);
        Assertions.assertTrue(fromPin >= 0, from);
        Assertions.assertTrue(toPin >= 0, to);
        TimingReport report = new TimingReport(tm.getStaClockAnalysis());
        int[] path = report.getMaxDelayPath(fromPin, toPin);
        Assertions.assertNotNull(path, from + " -> " + to);
        return report.getPathDelay(path);
    }

    /**
     * Gets the clock net of the cell that launches a path: the parent net of the clock pin of the first cell of the
     * path that has one.
     * @return The net, or null if no cell of the path has a clock pin.
     */
    private static EDIFHierNet getClockNet(Design d, StaGraph g, int[] path) {
        for (int i = 0; i <= path.length; i++) {
            int pin = i < path.length ? g.getEdgeFrom(path[i]) : g.getEdgeTo(path[path.length - 1]);
            EDIFHierPortInst portInst = g.getHierPortInst(pin);
            Cell c = portInst == null ? null : portInst.getPhysicalCell(d);
            if (c == null) continue;
            for (BELPin p : c.getBEL().getPins()) {
                String logicalClockPin = p.isClock() ? c.getLogicalPinMapping(p.getName()) : null;
                EDIFHierPortInst clockPortInst = logicalClockPin == null ? null
                        : c.getEDIFHierCellInst().getPortInst(logicalClockPin);
                if (clockPortInst != null) {
                    return d.getNetlist().getParentNet(clockPortInst.getHierarchicalNet());
                }
            }
        }
        return null;
    }

    /**
     * Tests the critical path of the UltraScale+ picoblaze and the path between two of its pins, with the fitted
     * timing model (frozen values; see also TestStaEquivalence, which checks the same graph against the reference
     * propagation).
     */
    @Test
    public void testGetTimingPaths() {
        Design d = RapidWrightDCP.loadDCP("picoblaze_ooc_X10Y235_2022_1.dcp");

        TimingManager tm = new TimingManager(d);
        StaGraph g = tm.getStaGraph();
        TimingReport report = new TimingReport(tm.getStaClockAnalysis());

        int[] criticalPath = report.getCriticalPath();

        EDIFHierNet clk = getClockNet(d, g, criticalPath);
        Assertions.assertEquals("clk", clk.toString());

        tm.prettyPrintPathDelays(criticalPath);

        Assertions.assertEquals(CRITICAL_PATH_DELAY_PS, report.getPathDelay(criticalPath), TOLERANCE);

        int[] otherPath = report.getMaxDelayPath(g.getPin("processor/sx_addr4_flop/Q"),
                g.getPin("output_port_w_reg[6]/D"));

        clk = getClockNet(d, g, otherPath);
        Assertions.assertEquals("clk", clk.toString());

        tm.prettyPrintPathDelays(otherPath);

        Assertions.assertEquals(OTHER_PATH_DELAY_PS, report.getPathDelay(otherPath), TOLERANCE);
    }

    /**
     * Gets the largest logic delay of the cell arcs of a timing graph from a cell type's pin.
     * @return The delay in ps, or -1 if the graph has no such arc.
     */
    private static float getMaxLogicDelay(Design d, StaGraph g, String cellType, String fromPinPrefix) {
        float max = -1;
        for (int e = 0; e < g.getNumEdges(); e++) {
            byte kind = g.getEdgeKind(e);
            if (kind == StaGraph.NET || kind == StaGraph.NET_INTRASITE) continue;
            String from = g.getPinName(g.getEdgeFrom(e));
            int slash = from.lastIndexOf('/');
            if (slash < 0) continue;
            Cell c = d.getCell(from.substring(0, slash));
            if (c != null && c.getType().equals(cellType) && from.substring(slash + 1).startsWith(fromPinPrefix)) {
                max = Math.max(max, g.getEdgeDelayMax(e));
            }
        }
        return max;
    }

    @Test
    public void testVersalTimingArcs() {
        Design d = RapidWrightDCP.loadDCP("picoblaze_2022.2.dcp");
        TimingManager tm = new TimingManager(d);
        StaGraph g = tm.getStaGraph();

        // Clock-to-output, LUT and carry arcs all have delays
        float clkToQ = getMaxLogicDelay(d, g, "FDRE", "C");
        Assertions.assertTrue(clkToQ > 50 && clkToQ < 200, "FDRE C->Q: " + clkToQ);
        float lut = getMaxLogicDelay(d, g, "LUT6", "I");
        Assertions.assertTrue(lut > 20 && lut < 300, "LUT6 I->O: " + lut);
        float carry = getMaxLogicDelay(d, g, "LOOKAHEAD8", "CIN");
        Assertions.assertTrue(carry > 10 && carry < 300, "LOOKAHEAD8 CIN->COUT: " + carry);
        float lutRam = getMaxLogicDelay(d, g, "RAMD32", "CLK");
        Assertions.assertTrue(lutRam > 100 && lutRam < 1000, "RAMD32 CLK->O: " + lutRam);

        // Paths start at the clock pins of flip-flops, which no edge drives
        int clk = g.getPin("processor/carry_flag_flop/C");
        Assertions.assertTrue(clk >= 0);
        Assertions.assertEquals(0, g.getInDegree(clk));
        Assertions.assertTrue(g.isPinKind(clk, StaGraph.PIN_START));

        float max = tm.computeArrivalRequiredTimes();
        Assertions.assertTrue(max > 1000 && max < 5000, "max delay " + max);
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

        TimingManager tm = new TimingManager(design);
        // A6LUT/O6 to AFF/D, from the fitted UltraScale+ cell arcs (the ultrascaleplus timing data)
        Assertions.assertEquals(48f, getPathDelay(tm, "lut/O", "ff/D"));
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
        DelayEstimator estimator = DelayEstimator.create(design, config.isUseUTurnNodes());
        TimingManager tm = new TimingManager(design, null, config, design.getNets(), isPartialRouting,
                estimator);
        // A6LUT/O6 to F7MUX_AB/1, the MUXF7 from 1, F7MUX_AB/OUT to BFF/D, from the fitted UltraScale+ cell arcs
        // (the ultrascaleplus timing data)
        Assertions.assertEquals(96f, getPathDelay(tm, "lutA/O", "ff/D"));
    }

    /**
     * Tests that a cell all of whose nets are routed (here a LUT6 between flip-flops in two other slices) has its arcs
     * in the timing graph when it is built for partial routing (as PartialRouter does, whose nets being routed keep
     * their routing until it is done): its path is as long as when the timing graph is not built for partial routing.
     */
    @Test
    public void testRoutedNetCellArcs() {
        Design design = new Design("top", "xcvu3p-ffvc1517-2-e");
        design.createAndPlaceCell("ffA", Unisim.FDRE, "SLICE_X0Y0/AFF");
        Cell lut = design.createAndPlaceCell("lut", Unisim.LUT6, "SLICE_X0Y1/A6LUT");
        LUTTools.configureLUT(lut, "O=I0");
        design.createAndPlaceCell("ffB", Unisim.FDRE, "SLICE_X0Y2/AFF");
        String[][] connections = {{"ffA", "Q", "lut", "I0"}, {"lut", "O", "ffB", "D"}};
        for (String[] c : connections) {
            Net net = design.createNet(c[0] + "_" + c[1]);
            ECOTools.connectNet(design, design.getCell(c[0]), c[1], net);
            ECOTools.connectNet(design, design.getCell(c[2]), c[3], net);
        }
        RWRoute.routeDesignFullNonTimingDriven(design);
        for (String[] c : connections) {
            Assertions.assertTrue(design.getNet(c[0] + "_" + c[1]).hasPIPs());
        }

        RWRouteConfig config = new RWRouteConfig(new String[] {});
        DelayEstimator estimator = DelayEstimator.create(design, config.isUseUTurnNodes());
        float[] pathDelays = new float[2];
        for (int i = 0; i < 2; i++) {
            boolean isPartialRouting = (i == 1);
            TimingManager tm = new TimingManager(design, null, config, design.getNets(), isPartialRouting,
                    estimator);
            pathDelays[i] = getPathDelay(tm, "ffA/Q", "ffB/D");
        }
        Assertions.assertEquals(pathDelays[0], pathDelays[1]);
    }
}

/*
 *
 * Copyright (c) 2026, Advanced Micro Devices, Inc.
 * All rights reserved.
 *
 * Author: Chris Lavin, Advanced Micro Devices, Inc.
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

package com.xilinx.rapidwright.timing.sta;

import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import com.xilinx.rapidwright.design.ConstraintGroup;
import com.xilinx.rapidwright.design.Design;
import com.xilinx.rapidwright.design.Net;
import com.xilinx.rapidwright.design.SitePinInst;
import com.xilinx.rapidwright.design.Unisim;
import com.xilinx.rapidwright.device.PartNameTools;
import com.xilinx.rapidwright.edif.EDIFCell;
import com.xilinx.rapidwright.edif.EDIFCellInst;
import com.xilinx.rapidwright.edif.EDIFDirection;
import com.xilinx.rapidwright.edif.EDIFHierPortInst;
import com.xilinx.rapidwright.edif.EDIFNet;
import com.xilinx.rapidwright.edif.EDIFNetlist;
import com.xilinx.rapidwright.edif.EDIFTools;
import com.xilinx.rapidwright.support.RapidWrightDCP;
import com.xilinx.rapidwright.timing.clock.Clock;
import com.xilinx.rapidwright.timing.clock.ClockData;
import com.xilinx.rapidwright.timing.clock.ClockModel;
import com.xilinx.rapidwright.timing.clock.ClockModelBuilder;
import com.xilinx.rapidwright.timing.clock.ClockNetwork;
import com.xilinx.rapidwright.timing.clock.ClockNodeDelayModel;
import com.xilinx.rapidwright.timing.clock.ClockRelations;
import com.xilinx.rapidwright.timing.clock.ClockRoute;
import com.xilinx.rapidwright.timing.clock.TimingConstraints;
import com.xilinx.rapidwright.timing.clock.UncertaintyModel;
import com.xilinx.rapidwright.timing.family.Corner;
import com.xilinx.rapidwright.timing.family.SpeedGrade;
import com.xilinx.rapidwright.timing.family.TimingFamilies;

/**
 * Clocks and multi-clock analysis of real designs of RapidWrightDCP.
 */
public class TestStaClockDesigns {

    private static Design load(String name) {
        Assumptions.assumeTrue(Files.exists(RapidWrightDCP.getPath(name)), name + " not available");
        return RapidWrightDCP.loadDCP(name);
    }

    private static void assertClock(ClockNetwork cn, String name, double periodNs, double latencyNs) {
        Clock c = cn.getClock(name);
        Assertions.assertNotNull(c, name);
        Assertions.assertEquals(periodNs * 1000, c.getPeriod(), 1e-3, name);
        Assertions.assertEquals(latencyNs * 1000, c.getSourceLatency(), 1e-3, name);
    }

    @Test
    public void testVerilogEthernet() {
        Design d = load("verilog_ethernet.dcp");
        TimingConstraints tc = TimingConstraints.fromDesign(d);
        Assertions.assertEquals(2, tc.getClocks().size());
        // Unsupported I/O delays and max delays are warned about once per kind
        Assertions.assertTrue(tc.getWarnings().containsKey("set_input_delay (not supported, ignored)"));
        ClockNetwork cn = ClockNetwork.build(d, tc);
        assertClock(cn, "clk", 10, 0);
        assertClock(cn, "phy_rx_clk", 8, 0);
        // MMCM outputs, named after their nets; the 90 degree output shifts the waveform (the default phase shift
        // mode of the UltraScale MMCM is WAVEFORM)
        assertClock(cn, "clk_mmcm_out", 8, 0);
        assertClock(cn, "clk90_mmcm_out", 8, 0);
        Assertions.assertArrayEquals(new double[]{2000, 6000}, cn.getClock("clk90_mmcm_out").getWaveform(), 1e-6);
        assertClock(cn, "clk_200_mmcm_out", 5, 0);
        Clock main = cn.getClock("clk_mmcm_out");
        Assertions.assertSame(cn.getClock("clk"), main.getRoot());
        Assertions.assertTrue(cn.getNumClockPins() > 3000);

        ClockRelations rel = ClockRelations.build(cn);
        int rx = cn.getClock("phy_rx_clk").getId();
        Assertions.assertTrue(rel.isFalseSetup(main.getId(), rx));
        Assertions.assertFalse(rel.isFalseSetup(main.getId(), cn.getClock("clk_200_mmcm_out").getId()));

        ClockModel model = ClockModel.build(d, cn, ClockModelBuilder.nodeModel(d.getDevice().getSeries(), "BUFGCE",
                new float[]{100, 90, 60, 66}));
        Assertions.assertEquals(cn.getNumClockPins(), model.getNumRoutedPins() + model.getNumEstimatedPins());
        int cp = cn.getClockPinsOfCell(cn.getClockPin(0).getFullHierarchicalInstName()).get(0);
        Assertions.assertTrue(model.getInsertion(cp, Corner.SLOW_MAX) >= model.getInsertion(cp, Corner.FAST_MIN));

    }

    @Test
    public void testMicroblaze() {
        // UltraScale: an MMCME3 clock wizard, BSCAN clocks, IP constraints with current_instance
        Design d = load("microblazeAndILA_3pblocks_2024.1.dcp");
        TimingConstraints tc = TimingConstraints.fromDesign(d);
        ClockNetwork cn = ClockNetwork.build(d, tc);
        assertClock(cn, "default_sysclk_300_clk_p", 3.333, 0);
        Clock main = cn.getClock("clk_out1_base_mb_clk_wiz_1_0");
        Assertions.assertNotNull(main);
        Assertions.assertEquals(9999, main.getPeriod(), 1e-3);
        Assertions.assertTrue(cn.getNumClockPins() > 3000);
        // BSCAN clocks are unrelated to the MMCM's
        ClockRelations rel = ClockRelations.build(cn);
        int tck = -1;
        for (Clock c : cn.getClocks()) {
            if (c.getName().endsWith("/TCK")) tck = c.getId();
        }
        Assertions.assertTrue(tck >= 0);
        Assertions.assertTrue(rel.isFalseSetup(tck, main.getId()));
    }

    @Test
    public void testPicoblazeAnalysis() {
        Design d = load("picoblaze_ooc_X10Y235_2022_1.dcp");
        TimingConstraints tc = TimingConstraints.fromDesign(d);
        // The port is found by a -filter on its NAME and DIRECTION
        Assertions.assertEquals(1, tc.getClocks().get(0).sources.size());
        ClockNetwork cn = ClockNetwork.build(d, tc);
        assertClock(cn, "clk", 2.85, 0);
        Clock main = cn.getClock("clk");
        ClockRelations rel = ClockRelations.build(cn);
        ClockModel model = ClockModel.build(d, cn, ClockModelBuilder.nodeModel(
                d.getDevice().getSeries()));
        Assertions.assertEquals(cn.getNumClockPins(), model.getNumRoutedPins() + model.getNumEstimatedPins());
        StaGraph g = new StaGraphBuilder(d, new FamilyArcProvider(d, Corner.SLOW_MAX)).build();
        UncertaintyModel unc = new UncertaintyModel(cn.getClocks(), cn, tc, new UncertaintyModel.Coefficients());
        StaClockBinding b = StaClockBinding.build(g, cn, rel, unc, model, StaClockBinding.NO_CHECK_TIMES);
        StaClockAnalysis an = new StaClockAnalysis(b);
        an.update();
        an.updateHold();
        an.computeCriticality(0.99f, 3);
        Assertions.assertTrue(an.getNumEndpoints() > 100);
        List<StaClockAnalysis.GroupSummary> groups = an.getGroupSummaries();
        Assertions.assertEquals(1, groups.size());
        StaClockAnalysis.GroupSummary s = groups.get(0);
        Assertions.assertEquals(main.getId(), s.launchClock);
        Assertions.assertEquals(2850f, s.requirement);
        Assertions.assertTrue(s.endpoints > 100);
        // Ideal clocks: the same-clock setup uncertainty is the default system jitter plus the user's (the
        // last set_clock_uncertainty, 0)
        StaClockAnalysis.Check worst = null;
        for (int k = 0; k < an.getNumEndpoints(); k++) {
            StaClockAnalysis.Check c = an.getWorstSetupCheck(an.getEndpointPin(k));
            if (c != null && (worst == null || c.slack < worst.slack)) worst = c;
        }
        Assertions.assertEquals(s.wns, worst.slack);
        Assertions.assertEquals(35.355f, worst.uncertainty, 1e-3);
        Assertions.assertEquals(2850f - 35.355f - worst.arrival, worst.slack, 1e-2);
        for (int c = 0; c < g.getNumConnections(); c++) {
            float crit = an.getCriticality(c);
            Assertions.assertTrue(crit >= 0 && crit <= 0.99f, "criticality " + crit);
        }
        Assertions.assertTrue(StaClockReport.format(an).contains("Worst hold checks"));

        // Setup and hold times from the fitted cell arcs: FDRE C->D (Vivado: setup -0.025, hold 0.046 ns)
        FittedCheckTimes times = new FittedCheckTimes(d);
        int numFDRE = 0;
        for (int k = 0; k < an.getNumEndpoints(); k++) {
            int p = an.getEndpointPin(k);
            if (!g.getCell(p).getCellName().equals("FDRE") || !g.getPortInst(p).getName().equals("D")) continue;
            EDIFHierPortInst data = g.getHierPortInst(p);
            EDIFHierPortInst clk = cn.getClockPin(b.getCaptureClockPin(p));
            float su = times.getSetup(data, clk);
            float ho = times.getHold(data, clk);
            Assertions.assertTrue(su > -100 && su < 100 && su != 0f, "setup " + su);
            Assertions.assertTrue(ho > 0 && ho < 150, "hold " + ho);
            numFDRE++;
        }
        Assertions.assertTrue(numFDRE > 10);
        StaClockBinding b2 = StaClockBinding.build(g, cn, rel, unc, model, times);
        StaClockAnalysis an2 = new StaClockAnalysis(b2);
        an2.update();
        Assertions.assertNotEquals(an.getGroupSummaries().get(0).wns, an2.getGroupSummaries().get(0).wns);
    }

    /** Analyzes a design with its constraints and extra XDC lines (ideal clocks). */
    private static StaClockAnalysis analyze(Design d, StaGraph g, String... extra) {
        List<String> lines = new ArrayList<>();
        for (ConstraintGroup cg : ConstraintGroup.values()) {
            List<String> c = d.getXDCConstraints(cg);
            if (c != null) lines.addAll(c);
        }
        lines.addAll(Arrays.asList(extra));
        TimingConstraints tc = TimingConstraints.parse(d.getNetlist(), lines);
        ClockNetwork cn = ClockNetwork.build(d, tc);
        ClockModel model = ClockModel.build(d, cn, ClockModelBuilder.nodeModel(
                d.getDevice().getSeries()));
        UncertaintyModel unc = new UncertaintyModel(cn.getClocks(), cn, tc, new UncertaintyModel.Coefficients());
        StaClockAnalysis an = new StaClockAnalysis(StaClockBinding.build(g, cn, ClockRelations.build(cn), unc,
                model, StaClockBinding.NO_CHECK_TIMES));
        an.update();
        return an;
    }

    /**
     * Path exceptions on nets: -to a net stands for its loads, -from a net for its driver's startpoints.
     */
    @Test
    public void testNetExceptions() {
        Design d = load("picoblaze_ooc_X10Y235_2022_1.dcp");
        StaGraph g = new StaGraphBuilder(d, new FamilyArcProvider(d, Corner.SLOW_MAX)).build();
        StaClockAnalysis an = analyze(d, g);
        StaClockAnalysis.Check worst = null;
        for (int k = 0; k < an.getNumEndpoints(); k++) {
            StaClockAnalysis.Check c = an.getWorstSetupCheck(an.getEndpointPin(k));
            if (c != null && (worst == null || c.slack < worst.slack)) worst = c;
        }
        Assertions.assertNotNull(worst);
        String toNet = g.getHierPortInst(worst.endpoint).getHierarchicalNet().getHierarchicalNetName();
        int[] path = an.getPath(worst);
        // The launching cell and the net its output drives
        String fromCell = g.getHierPortInst(g.getEdgeFrom(path[0])).getFullHierarchicalInstName();
        String fromNet = null;
        for (int e : path) {
            EDIFHierPortInst out = g.getHierPortInst(g.getEdgeFrom(e));
            if (out.isOutput() && out.getFullHierarchicalInstName().equals(fromCell)) {
                fromNet = out.getHierarchicalNet().getHierarchicalNetName();
            }
        }
        Assertions.assertNotNull(fromNet);

        StaClockAnalysis to = analyze(d, g, "set_false_path -to [get_nets {" + toNet + "}]");
        Assertions.assertNull(to.getWorstSetupCheck(worst.endpoint));
        Assertions.assertEquals(an.getNumEndpoints(), to.getNumEndpoints());

        StaClockAnalysis from = analyze(d, g, "set_false_path -from [get_nets {" + fromNet + "}]");
        int timed = 0;
        for (int k = 0; k < from.getNumEndpoints(); k++) {
            StaClockAnalysis.Check c = from.getWorstSetupCheck(from.getEndpointPin(k));
            if (c == null) continue;
            timed++;
            int[] pth = from.getPath(c);
            Assertions.assertTrue(pth.length == 0
                    || !g.getHierPortInst(g.getEdgeFrom(pth[0])).getFullHierarchicalInstName().equals(fromCell));
        }
        Assertions.assertTrue(timed > 100);
    }

    private static String pinName(EDIFHierPortInst p) {
        return p.getFullHierarchicalInstName() + "/" + p.getPortInst().getName();
    }

    /**
     * Path exceptions with -through: an endpoint pin without fanout (or a net whose loads are such pins) ends every
     * path through it, so it applies as -to; other -through objects (pins in the middle of paths) are not applied.
     */
    @Test
    public void testThroughExceptions() {
        Design d = load("picoblaze_ooc_X10Y235_2022_1.dcp");
        StaGraph g = new StaGraphBuilder(d, new FamilyArcProvider(d, Corner.SLOW_MAX)).build();
        StaClockAnalysis an = analyze(d, g);
        StaClockAnalysis.Check worst = null;
        for (int k = 0; k < an.getNumEndpoints(); k++) {
            StaClockAnalysis.Check c = an.getWorstSetupCheck(an.getEndpointPin(k));
            if (c != null && (worst == null || c.slack < worst.slack)) worst = c;
        }
        Assertions.assertNotNull(worst);
        EDIFHierPortInst end = g.getHierPortInst(worst.endpoint);
        String endPin = pinName(end);
        String endNet = end.getHierarchicalNet().getHierarchicalNetName();
        // An output pin in the middle of the worst path
        String midPin = null;
        int[] path = an.getPath(worst);
        for (int i = 1; i < path.length; i++) {
            EDIFHierPortInst p = g.getHierPortInst(g.getEdgeFrom(path[i]));
            if (p.isOutput() && g.getOutDegree(g.getEdgeFrom(path[i])) > 0) midPin = pinName(p);
        }
        Assertions.assertNotNull(midPin);

        StaClockAnalysis thruPin = analyze(d, g, "set_false_path -through [get_pins {" + endPin + "}]");
        Assertions.assertNull(thruPin.getWorstSetupCheck(worst.endpoint));
        Assertions.assertNull(thruPin.getWorstHoldCheck(worst.endpoint));
        Assertions.assertEquals(an.getBinding().getWarnings(), thruPin.getBinding().getWarnings());
        StaClockAnalysis thruNet = analyze(d, g, "set_false_path -setup -through [get_nets {" + endNet + "}]");
        Assertions.assertNull(thruNet.getWorstSetupCheck(worst.endpoint));
        Assertions.assertNotNull(thruNet.getWorstHoldCheck(worst.endpoint));

        // A multicycle path through the endpoint: one more period of the capture clock
        StaClockAnalysis mc = analyze(d, g, "set_multicycle_path 2 -setup -through [get_pins {" + endPin + "}]");
        StaClockAnalysis.Check mcWorst = mc.getWorstSetupCheck(worst.endpoint);
        Assertions.assertEquals(2 * worst.relation, mcWorst.relation, 1e-3);
        Assertions.assertEquals(worst.slack + worst.relation, mcWorst.slack, 1e-2);

        // With -to: their common part (none with another endpoint)
        int other = an.getEndpointPin(0) == worst.endpoint ? an.getEndpointPin(1) : an.getEndpointPin(0);
        StaClockAnalysis disjoint = analyze(d, g, "set_false_path -through [get_pins {" + endPin + "}] -to [get_pins {"
                + pinName(g.getHierPortInst(other)) + "}]");
        Assertions.assertNotNull(disjoint.getWorstSetupCheck(worst.endpoint));
        StaClockAnalysis common = analyze(d, g, "set_false_path -through [get_pins {" + endPin + "}] -to [get_pins {"
                + endPin + "}]");
        Assertions.assertNull(common.getWorstSetupCheck(worst.endpoint));

        // A pin in the middle of paths is not applied (warning), nor are several -through options
        StaClockAnalysis mid = analyze(d, g, "set_false_path -through [get_pins {" + midPin + "}]");
        Assertions.assertEquals(worst.slack, mid.getWorstSetupCheck(worst.endpoint).slack, 1e-3);
        Assertions.assertEquals(1, mid.getBinding().getWarnings()
                .get("exception -through objects other than endpoints without fanout (not applied to them)"));
        StaClockAnalysis two = analyze(d, g, "set_false_path -through [get_pins {" + midPin + "}] -through [get_pins {"
                + endPin + "}]");
        Assertions.assertNotNull(two.getWorstSetupCheck(worst.endpoint));
        Assertions.assertEquals(1, two.getBinding().getWarnings().get("exception with several -through options (ignored)"));
    }

    private static List<String> names(List<EDIFHierPortInst> pins) {
        if (pins == null) return null;
        List<String> n = new ArrayList<>();
        for (EDIFHierPortInst p : pins) n.add(pinName(p));
        java.util.Collections.sort(n);
        return n;
    }

    /**
     * The leaf loads below a hierarchical input pin (a -through object): only the branch of its net inside the cell, so
     * that every path to them passes through the pin. Cell h: input I drives i/D, but its net also comes back from h's
     * output O (driven by s/Q inside h, which also drives r/D); input J also leaves through output P; input K goes
     * down into a child cell to x/D and y/D.
     */
    @Test
    public void testThroughHierarchicalPinBranch() {
        EDIFNetlist netlist = EDIFTools.createNewNetlist("top");
        EDIFCell fdre = Design.getUnisimCell(Unisim.FDRE);
        EDIFTools.ensureCellInLibraries(netlist, fdre);
        EDIFCell sub = new EDIFCell(netlist.getWorkLibrary(), "sub");
        sub.createPort("A", EDIFDirection.INPUT, 1);
        EDIFNet a = sub.createNet("a");
        a.createPortInst("A", sub);
        a.createPortInst("D", sub.createChildCellInst("x", fdre));
        a.createPortInst("D", sub.createChildCellInst("y", fdre));
        EDIFCell hc = new EDIFCell(netlist.getWorkLibrary(), "hc");
        for (String in : new String[]{"I", "J", "K"}) hc.createPort(in, EDIFDirection.INPUT, 1);
        for (String out : new String[]{"O", "P"}) hc.createPort(out, EDIFDirection.OUTPUT, 1);
        EDIFNet ni = hc.createNet("ni");
        ni.createPortInst("I", hc);
        ni.createPortInst("D", hc.createChildCellInst("i", fdre));
        EDIFNet no = hc.createNet("no");
        no.createPortInst("Q", hc.createChildCellInst("s", fdre));
        no.createPortInst("D", hc.createChildCellInst("r", fdre));
        no.createPortInst("O", hc);
        EDIFNet nj = hc.createNet("nj");
        nj.createPortInst("J", hc);
        nj.createPortInst("D", hc.createChildCellInst("j", fdre));
        nj.createPortInst("P", hc);
        EDIFNet nk = hc.createNet("nk");
        nk.createPortInst("K", hc);
        nk.createPortInst("A", hc.createChildCellInst("u", sub));
        EDIFCell top = netlist.getTopCell();
        EDIFCellInst h = top.createChildCellInst("h", hc);
        EDIFNet loop = top.createNet("loop");
        loop.createPortInst("O", h);
        loop.createPortInst("I", h);
        for (String in : new String[]{"J", "K"}) top.createNet("n" + in).createPortInst(in, h);

        Assertions.assertEquals(Arrays.asList("h/i/D"), names(StaClockBinding.getLeafLoadsBelow(
                netlist.getHierPortInstFromName("h/I"))));
        Assertions.assertNull(StaClockBinding.getLeafLoadsBelow(netlist.getHierPortInstFromName("h/J")));
        Assertions.assertEquals(Arrays.asList("h/u/x/D", "h/u/y/D"), names(StaClockBinding.getLeafLoadsBelow(
                netlist.getHierPortInstFromName("h/K"))));
        Assertions.assertNull(StaClockBinding.getLeafLoadsBelow(netlist.getHierPortInstFromName("h/O")));
    }

    /**
     * An out-of-context design with DSPs (optical-flow: the clock port drives the clock pins without a
     * buffer or routing): its clock pins get their leaf delay alone, as Vivado gives them (at SLOW_MAX 28 to
     * 30 ps on a flip-flop, 60 ps on a DSP), and the DSP pins that its OPMODE, multiplier selection and register
     * settings leave unused are no endpoints, as in Vivado.
     */
    @Test
    public void testOutOfContextDspDesign() {
        Design d = load("optical-flow.dcp");
        TimingConstraints tc = TimingConstraints.fromDesign(d);
        ClockNetwork cn = ClockNetwork.build(d, tc);
        ClockData data = ClockData.get(d.getDevice(), SpeedGrade.of("2"));
        ClockModel cm = ClockModel.build(d, cn, data.createNodeDelayModel());
        Assertions.assertTrue(cn.getNumClockPins() > 1000);
        int numFlipFlops = 0;
        int numDsps = 0;
        for (int i = 0; i < cn.getNumClockPins(); i++) {
            String pin = cn.getClockPin(i).toString();
            String type = d.getCell(cn.getClockPin(i).getFullHierarchicalInstName()).getType();
            float ins = cm.getInsertion(i, Corner.SLOW_MAX);
            if (type.startsWith("FD")) {
                Assertions.assertTrue(ins >= 28f && ins <= 30f, pin + " " + ins);
                numFlipFlops++;
            } else if (type.startsWith("DSP_")) {
                Assertions.assertEquals(60f, ins, pin);
                numDsps++;
            }
            for (Corner c : Corner.values()) {
                Assertions.assertTrue(cm.getInsertion(i, c) >= 0f, pin);
            }
        }
        Assertions.assertTrue(numFlipFlops > 1000 && numDsps > 100);
        // The fitted UltraScale+ model has the min (FAST_MIN) delays
        StaGraph g = new StaGraphBuilder(d, new FamilyArcProvider(d, Corner.SLOW_MAX, Corner.FAST_MIN)).build();
        StaClockBinding b = StaClockBinding.build(g, cn, ClockRelations.build(cn), data.createUncertaintyModel(cn, tc),
                cm, new FittedCheckTimes(d));
        StaClockAnalysis an = new StaClockAnalysis(b);
        an.update();
        an.updateHold();
        Set<String> unused = new HashSet<>(Arrays.asList("C_DATA", "P_FDBK", "P_FDBK_47", "A_ALU", "B_ALU",
                "AMULT26", "BMULT17", "CCOUT", "XOR_MX"));
        int dspEndpoints = 0;
        for (int k = 0; k < an.getNumEndpoints(); k++) {
            int p = an.getEndpointPin(k);
            String cell = g.getCell(p).getCellType().getName();
            if (!cell.startsWith("DSP_")) continue;
            dspEndpoints++;
            String pin = g.getPortInst(p).getName().replaceAll("\\[\\d+\\]$", "");
            Assertions.assertFalse(unused.contains(pin) && (cell.equals("DSP_ALU") || cell.equals("DSP_OUTPUT")),
                    g.getPinName(p));
            // Launches only at clock pins: a hold check's path starts at a clock-to-output arc
            StaClockAnalysis.Check h = an.getWorstHoldCheck(p);
            if (h == null) continue;
            int[] path = an.getPath(h.endpoint, h.tag);
            Assertions.assertTrue(path.length > 0, g.getPinName(p));
            Assertions.assertEquals(StaGraph.CLK_TO_Q, g.getEdgeKind(path[0]), g.getPinName(p));
        }
        Assertions.assertTrue(dspEndpoints > 100);
    }

    /**
     * The hard blocks without timing arcs (a NoC design's DDR memory controller): the controller's inputs are no
     * endpoints and its dedicated connections to its PHY launch nothing, as Vivado has no timing arcs for them.
     */
    @Test
    public void testHardBlocksWithoutArcs() {
        Design d = load("noc_tutorial_routed.dcp");
        TimingConstraints tc = TimingConstraints.fromDesign(d);
        ClockNetwork cn = ClockNetwork.build(d, tc);
        ClockData data = ClockData.get(d.getDevice(), TimingFamilies.of(d.getDevice())
                .resolveSpeedGrade(PartNameTools.getPart(d.getPartName())));
        ClockModel cm = ClockModel.build(d, cn, data.createNodeDelayModel());
        StaGraph g = new StaGraphBuilder(d, new FamilyArcProvider(d, Corner.SLOW_MAX, Corner.FAST_MIN)).build();
        int ddrmcInputs = 0;
        for (int p = 0; p < g.getNumPins(); p++) {
            if (g.getCell(p).getCellType().getName().equals("DDRMC") && g.getInDegree(p) > 0) {
                ddrmcInputs++;
            }
        }
        Assertions.assertTrue(ddrmcInputs > 1000);
        int[] fitted = hardBlockChecks(d, g, cn, tc, data, cm);
        Assertions.assertEquals(0, fitted[0]);
        Assertions.assertEquals(0, fitted[1]);
    }

    /** @return {timed endpoints on DDRMC cells, timed endpoints launched from DDRMC cells} */
    private static int[] hardBlockChecks(Design d, StaGraph g, ClockNetwork cn, TimingConstraints tc,
                                         ClockData data, ClockModel cm) {
        StaClockBinding b = StaClockBinding.build(g, cn, ClockRelations.build(cn), data.createUncertaintyModel(cn, tc),
                cm, new FittedCheckTimes(d));
        StaClockAnalysis an = new StaClockAnalysis(b);
        an.update();
        int[] out = new int[2];
        for (int k = 0; k < an.getNumEndpoints(); k++) {
            int p = an.getEndpointPin(k);
            StaClockAnalysis.Check c = an.getWorstSetupCheck(p);
            if (c == null || Float.isInfinite(c.slack)) continue;
            if (g.getCell(p).getCellType().getName().equals("DDRMC")) {
                out[0]++;
            }
            int[] path = an.getPath(c);
            if (path.length > 0 && g.getCell(g.getEdgeFrom(path[0])).getCellType().getName().equals("DDRMC")) {
                out[1]++;
            }
        }
        return out;
    }

    @Test
    public void testDesignWithoutClocks() {
        Design d = load("picoblaze_2022.2.dcp");
        TimingConstraints tc = TimingConstraints.fromDesign(d);
        ClockNetwork cn = ClockNetwork.build(d, tc);
        Assertions.assertEquals(0, cn.getNumClocks());
        // Without clocks, the single-clock binding has the criticalities of the reference propagation
        StaGraph g = new StaGraphBuilder(d, new FamilyArcProvider(d, Corner.SLOW_MAX)).build();
        float[][] ref = TestStaSingleClock.referenceTimes(g, 3000f);
        StaClockAnalysis an = new StaClockAnalysis(StaClockBinding.singleClock(g, 3000f));
        an.computeCriticality(0.99f, 3);
        for (int c = 0; c < g.getNumConnections(); c++) {
            Assertions.assertEquals(TestStaSingleClock.referenceCriticality(g, ref, c, 0.99f, 3), an.getCriticality(c));
        }
    }

    /**
     * The inter-SLR compensation of a multi-SLR Versal design with the modeled clocks of its family:
     * every check whose launch and capture clock pins are in different SLRs, or whose data path
     * crosses an SLR boundary, loses (DCD - CCD) * PF from its CRPR credit; no other check does.
     */
    @Test
    public void testInterSlrCompensation() {
        Design d = load("versal_slr_crossing.dcp");
        StaGraph g = new StaGraphBuilder(d, new FamilyArcProvider(d, Corner.SLOW_MAX, Corner.FAST_MIN)).build();
        TimingConstraints tc = TimingConstraints.fromDesign(d);
        ClockNetwork cn = ClockNetwork.build(d, tc);
        ClockData data = ClockData.get(d.getDevice(), SpeedGrade.of("2MHP"));
        ClockModel cm = ClockModel.build(d, cn, data.createNodeDelayModel());
        float pf = data.getInterSlrProrate();
        Assertions.assertTrue(pf > 0);
        StaClockBinding b = StaClockBinding.build(g, cn, ClockRelations.build(cn), data.createUncertaintyModel(cn, tc),
                cm, StaClockBinding.NO_CHECK_TIMES);
        b.setInterSlrProrate(pf);
        StaClockAnalysis an = new StaClockAnalysis(b);
        an.update();
        an.updateHold();
        int clockPinCrossings = 0;
        for (int k = 0; k < an.getNumEndpoints(); k++) {
            int p = an.getEndpointPin(k);
            for (boolean setup : new boolean[]{true, false}) {
                StaClockAnalysis.Check ch = setup ? an.getWorstSetupCheck(p) : an.getWorstHoldCheck(p);
                if (ch == null || ch.launchClockPin < 0) continue;
                int lp = ch.launchClockPin;
                int cp = ch.captureClockPin;
                float crpr = setup ? cm.getSetupCrpr(lp, cp) : cm.getHoldCrpr(lp, cp);
                float isc = crpr - ch.crpr;
                Corner corner = setup ? Corner.SLOW_MIN : Corner.FAST_MAX;
                if (cm.getSlr(lp) != cm.getSlr(cp)) {
                    Assertions.assertEquals(cm.getInterSlrCompensation(lp, cp, corner, pf), isc, 1e-2);
                    Assertions.assertTrue(isc > 0);
                    clockPinCrossings++;
                } else if (isc != 0) {
                    // Same-SLR clock pins, but a data path through another SLR
                    Assertions.assertEquals(0f, cm.getInterSlrCompensation(lp, cp, corner, pf));
                    Assertions.assertEquals(cm.getInterSlrCompensation(lp, cp, corner, pf, true), isc, 1e-2);
                }
            }
        }
        Assertions.assertTrue(clockPinCrossings > 0, "clock pin crossings " + clockPinCrossings);
    }

    /**
     * The clock route walker that times the clock nets carrying data ({@link ClockRoute}) and the clock model
     * time a routed clock net alike: between two routed flip-flop clock pins of a net with the same leaf, the
     * difference of their insertion delays is that of their route delays.
     */
    @Test
    public void testClockRouteMatchesClockModel() {
        Design d = load("picoblaze_partial.dcp");
        ClockNetwork cn = ClockNetwork.build(d, TimingConstraints.fromDesign(d));
        ClockNodeDelayModel nm = ClockData.get(d.getDevice(), TimingFamilies.of(d.getDevice())
                .resolveSpeedGrade(PartNameTools.getPart(d.getPartName()))).createNodeDelayModel();
        ClockModel cm = ClockModel.build(d, cn, nm);
        Map<Net, ClockRoute> routes = new HashMap<>();
        Map<String, float[]> first = new HashMap<>();
        int checked = 0;
        for (int i = 0; i < cn.getNumClockPins(); i++) {
            EDIFHierPortInst p = cn.getClockPin(i);
            if (!cm.isRouted(i) || !p.getPortInst().getName().equals("C")) continue;
            SitePinInst spi = p.getRoutedSitePinInst(d);
            Net net = spi == null ? null : spi.getNet();
            if (net == null || !net.hasPIPs()) continue;
            float[] route = routes.computeIfAbsent(net, n -> new ClockRoute(n, nm, d)).getDelays(spi);
            if (route == null) continue;
            // (Pins of the same stage, load site pin and CLE clock modifier tap)
            String key = cn.getClockPinStage(i) + "|" + spi.getSiteInst().getSiteTypeEnum() + "." + spi.getName() + "|"
                    + ClockModel.getClockModifierTap(d, spi.getSite());
            float[] v = new float[2 * route.length];
            for (Corner c : Corner.values()) {
                v[c.ordinal()] = cm.getInsertion(i, c);
                v[route.length + c.ordinal()] = route[c.ordinal()];
            }
            float[] ref = first.putIfAbsent(key, v);
            if (ref == null) continue;
            for (int c = 0; c < route.length; c++) {
                Assertions.assertEquals(v[c] - ref[c], v[route.length + c] - ref[route.length + c], 0.05,
                        p + " " + Corner.values()[c]);
            }
            checked++;
        }
        Assertions.assertTrue(checked > 1000, "checked " + checked);
    }
}

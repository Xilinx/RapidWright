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

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import com.xilinx.rapidwright.design.Cell;
import com.xilinx.rapidwright.design.Design;
import com.xilinx.rapidwright.design.DesignTools;
import com.xilinx.rapidwright.design.Net;
import com.xilinx.rapidwright.design.NetType;
import com.xilinx.rapidwright.design.SitePinInst;
import com.xilinx.rapidwright.design.Unisim;
import com.xilinx.rapidwright.device.BELPin;
import com.xilinx.rapidwright.device.Device;
import com.xilinx.rapidwright.device.PIP;
import com.xilinx.rapidwright.device.PartNameTools;
import com.xilinx.rapidwright.edif.EDIFDirection;
import com.xilinx.rapidwright.edif.EDIFHierCellInst;
import com.xilinx.rapidwright.edif.EDIFHierNet;
import com.xilinx.rapidwright.edif.EDIFNetlist;
import com.xilinx.rapidwright.edif.EDIFPortInst;
import com.xilinx.rapidwright.edif.EDIFTools;
import com.xilinx.rapidwright.support.RapidWrightDCP;
import com.xilinx.rapidwright.timing.delayestimator.DelayEstimator;
import com.xilinx.rapidwright.timing.family.Corner;
import com.xilinx.rapidwright.timing.family.FittedCellArcLibrary;
import com.xilinx.rapidwright.timing.family.SpeedGrade;
import com.xilinx.rapidwright.timing.family.TimingFamilies;
import com.xilinx.rapidwright.timing.family.TimingFamily;

public class TestFamilyArcProvider {

    private static float maxArrival(FamilyArcProvider provider, Design d, boolean parallel) {
        StaGraphBuilder builder = new StaGraphBuilder(d, provider);
        builder.setParallel(parallel);
        return maxArrival(builder.build());
    }

    /** The largest arrival time of the endpoints of a graph */
    private static float maxArrival(StaGraph g) {
        StaClockAnalysis an = new StaClockAnalysis(StaClockBinding.singleClock(g, 10000f));
        an.update();
        float max = 0;
        for (int k = 0; k < an.getNumEndpoints(); k++) {
            max = Math.max(max, an.getMaxArrival(an.getEndpointPin(k)));
        }
        return max;
    }

    @ParameterizedTest
    @CsvSource({
            // Versal: frozen result. (No path goes through processor/push_pop_lut/LUT5, whose output is constant
            // once its GND inputs are propagated, as Vivado 2026.1 times none.)
            "picoblaze_2022.2.dcp,2290.9,0.01",
            // UltraScale+ (fitted interconnect): no Vivado reference; the band is that of the frozen result. The
            // LUT inputs that the function ignores have no arcs.
            "picoblaze_ooc_X10Y235_2022_1.dcp,2480.8,0.01",
    })
    public void testMaxArrival(String dcp, float expected, float tolerance) {
        Design d = RapidWrightDCP.loadDCP(dcp);
        FamilyArcProvider slow = new FamilyArcProvider(d, Corner.SLOW_MAX);
        float slowMax = maxArrival(slow, d, false);
        Assertions.assertEquals(expected, slowMax, expected * tolerance);
        Assertions.assertTrue(slow.getNumCellArcs() > 1000);
        Assertions.assertTrue(slow.getUnknownCellTypes().isEmpty(), slow.getUnknownCellTypes().toString());
        // Cell arcs in parallel give the same graph
        Assertions.assertEquals(slowMax, maxArrival(new FamilyArcProvider(d, Corner.SLOW_MAX), d, true));
        // Hold analysis corner
        float fastMin = maxArrival(new FamilyArcProvider(d, Corner.FAST_MIN), d, false);
        Assertions.assertTrue(fastMin < 0.7f * slowMax, fastMin + " " + slowMax);
        // Two corners: max and min delays of the graph
        StaGraph g = new StaGraphBuilder(d, new FamilyArcProvider(d, Corner.SLOW_MAX, Corner.FAST_MIN)).build();
        Assertions.assertEquals(slowMax, maxArrival(g), 1e-3f);
    }

    /**
     * Gets the LUT input pins of a graph, split by whether they have a cell arc.
     * @return The names of the pins without an arc in the first set, with one in the second.
     */
    private static Set<String>[] getLutInputPins(StaGraph g) {
        @SuppressWarnings({"unchecked", "rawtypes"})
        Set<String>[] pins = new Set[] {new HashSet<>(), new HashSet<>()};
        for (int p = 0; p < g.getNumPins(); p++) {
            EDIFPortInst portInst = g.getPortInst(p);
            if (portInst == null || !portInst.isInput()
                    || LutInputs.getSize(g.getCell(p).getCellType().getName()) == 0) {
                continue;
            }
            boolean hasArc = false;
            for (int i = 0; i < g.getOutDegree(p); i++) {
                hasArc |= g.getEdgeKind(g.getOutEdge(p, i)) == StaGraph.CELL_COMB;
            }
            pins[hasArc ? 1 : 0].add(g.getPinName(p));
        }
        return pins;
    }

    @Test
    public void testLutInputsWithoutArcs() {
        // Like Vivado, no arc from the inputs that a LUT function ignores,
        // such as I4 of the LUT5 half of a LUT6_2 whose lower half does not depend on it
        Design d = RapidWrightDCP.loadDCP("picoblaze_2022.2.dcp");
        Set<String>[] fitted = getLutInputPins(new StaGraphBuilder(d, new FamilyArcProvider(d, Corner.SLOW_MAX)).build());
        Assertions.assertFalse(fitted[0].isEmpty());
        Assertions.assertFalse(fitted[1].isEmpty());
        // The fitted arcs also propagate constants (as Vivado does): the LUT5 of push_pop_lut has INIT 0 once its GND
        // inputs I0 and I1 are fixed, so no arcs at all, as Vivado times no path through it
        for (int i = 0; i < 5; i++) {
            Assertions.assertFalse(fitted[1].contains("processor/push_pop_lut/LUT5/I" + i));
        }
    }

    @Test
    public void testRoutingCacheInvalidation() {
        Design d = RapidWrightDCP.loadDCP("picoblaze_2022.2.dcp");
        FamilyArcProvider provider = new FamilyArcProvider(d, Corner.SLOW_MAX);
        // Two routed nets with as many PIPs, the first with a sink its routing reaches
        Map<Integer, Net> byCount = new HashMap<>();
        Net a = null;
        Net b = null;
        SitePinInst sink = null;
        for (Net n : d.getNets()) {
            if (n.isStaticNet() || n.isClockNet() || !n.hasPIPs() || n.getSource() == null) continue;
            Net other = byCount.putIfAbsent(n.getPIPs().size(), n);
            if (other == null) continue;
            for (SitePinInst p : other.getSinkPins()) {
                if (provider.getRoutedDelays(other, other.getSource(), p) != null) {
                    a = other;
                    b = n;
                    sink = p;
                    break;
                }
            }
            if (a != null) break;
        }
        Assertions.assertNotNull(a);
        float[] routed = provider.getRoutedDelays(a, a.getSource(), sink);
        // Reroute a with as many PIPs (those of b): the cache must not keep a's old routing
        List<PIP> old = new ArrayList<>(a.getPIPs());
        a.setPIPs(new ArrayList<>(b.getPIPs()));
        try {
            provider.invalidateRouting();
            Assertions.assertNull(provider.getRoutedDelays(a, a.getSource(), sink));
        } finally {
            a.setPIPs(old);
        }
        provider.invalidateRouting();
        Assertions.assertArrayEquals(routed, provider.getRoutedDelays(a, a.getSource(), sink));
    }

    /** Times a routed connection of a design with a provider that is then dropped */
    private static WeakReference<Design> timeAndDrop() {
        Design d = RapidWrightDCP.loadDCP("picoblaze_2022.2.dcp");
        FamilyArcProvider provider = new FamilyArcProvider(d, Corner.SLOW_MAX);
        boolean routed = false;
        for (Net n : d.getNets()) {
            if (n.isStaticNet() || n.isClockNet() || !n.hasPIPs() || n.getSource() == null) continue;
            for (SitePinInst p : n.getSinkPins()) {
                routed |= provider.getRoutedDelays(n, n.getSource(), p) != null;
            }
            if (routed) break;
        }
        Assertions.assertTrue(routed);
        return new WeakReference<>(d);
    }

    /** The routing cache of a provider does not outlive it: a dropped provider keeps no design reachable. */
    @Test
    public void testRoutingCacheDoesNotOutliveProvider() throws InterruptedException {
        WeakReference<Design> ref = timeAndDrop();
        for (int i = 0; i < 20 && ref.get() != null; i++) {
            System.gc();
            Thread.sleep(50);
        }
        Assertions.assertNull(ref.get());
    }

    /**
     * A cell created without properties takes the UNISIM defaults of its type: a RAMB36E2 (DOA_REG and
     * DOB_REG 1 by default) launches its outputs from its clock, as a Vivado one in that configuration.
     */
    @Test
    public void testDefaultPropertiesOfNewCells() {
        Design d = new Design("bram", "xcvu3p-ffvc1517-2-e");
        Cell bram = d.createAndPlaceCell("bram", Unisim.RAMB36E2, "RAMB36_X0Y0/RAMB36E2");
        Assertions.assertTrue(bram.getEDIFHierCellInst().getInst().getPropertiesMap().isEmpty()
                || !bram.getEDIFHierCellInst().getInst().getPropertiesMap().containsKey("DOA_REG"));
        Map<String, String> props = FamilyArcProvider.getProperties(d.getDevice().getSeries(), bram,
                new HashSet<>(Arrays.asList("DOA_REG", "DOB_REG")));
        Assertions.assertEquals("1", props.get("DOA_REG"));
        // The clock-to-output arcs of the library (keyed by DOA_REG/DOB_REG) match these defaults
        FittedCellArcLibrary lib = new FamilyArcProvider(d, Corner.SLOW_MAX).getLibrary();
        Set<String> keys = new HashSet<>(lib.getConfigKeys("RAMB36E2"));
        Map<String, String> attrs = FamilyArcProvider.getProperties(d.getDevice().getSeries(), bram, keys);
        int found = 0;
        for (FittedCellArcLibrary.ArcKey key : lib.getArcKeys("RAMB36E2")) {
            if (key.getRole() != FittedCellArcLibrary.Role.CLK_TO_Q) continue;
            FittedCellArcLibrary.Result r = lib.lookup("RAMB36E2", bram.getBELName(),
                    bram.getSiteInst().getSiteTypeEnum().name(), key.getRole(), key.getFrom(), -1, key.getTo(), -1,
                    attrs, null, null);
            if (r.getArc() != null && r.getMismatch() < 4) found++;
        }
        Assertions.assertTrue(found > 0, "no clock-to-output arcs");
    }

    private static StaGraph buildFittedGraph(Design d) {
        return new StaGraphBuilder(d, new FamilyArcProvider(d, Corner.SLOW_MAX, Corner.FAST_MIN)).build();
    }

    /** Whether a pin of a graph has an out-edge of a kind */
    private static boolean hasOutEdge(StaGraph g, String pinName, byte kind) {
        int p = g.getPin(pinName);
        for (int i = 0; p >= 0 && i < g.getOutDegree(p); i++) {
            if (g.getEdgeKind(g.getOutEdge(p, i)) == kind) {
                return true;
            }
        }
        return false;
    }

    private static boolean isEndpoint(StaGraph g, String pinName) {
        int p = g.getPin(pinName);
        return p >= 0 && g.isPinKind(p, StaGraph.PIN_END);
    }

    /** The number of endpoints of a cell, and of clock-to-output arcs from its pins */
    private static int[] getEndpointsAndLaunches(StaGraph g, String cellName) {
        int[] n = new int[2];
        for (int p = 0; p < g.getNumPins(); p++) {
            if (!g.getPinName(p).startsWith(cellName + "/")) continue;
            if (g.isPinKind(p, StaGraph.PIN_END)) {
                n[0]++;
            }
            for (int i = 0; i < g.getOutDegree(p); i++) {
                if (g.getEdgeKind(g.getOutEdge(p, i)) == StaGraph.CLK_TO_Q) {
                    n[1]++;
                }
            }
        }
        return n;
    }

    /**
     * An inout port (an input buffer whose pad an output buffer also drives) breaks the timing paths, as in
     * Vivado: no arc from the pad of the input buffer.
     */
    @Test
    public void testPadLoopback() {
        Design d = RapidWrightDCP.loadDCP("inout.dcp");
        EDIFNetlist netlist = d.getNetlist();
        String pad = "i_IBUF_inst/INBUF_INST/PAD";
        Assertions.assertTrue(hasOutEdge(buildFittedGraph(d), pad, StaGraph.CELL_COMB));
        // Make the input port i an inout port: an output buffer in the same IOB drives its pad net too
        EDIFHierNet padNet = netlist.getParentNet(netlist.getHierPortInstFromName(pad).getHierarchicalNet());
        Cell obuft = d.createAndPlaceCell("i_OBUFT_inst", Unisim.OBUFT, "IOB_X1Y253/OUTBUF");
        padNet.getNet().createPortInst("O", obuft.getEDIFHierCellInst().getInst());
        netlist.getTopCell().getPort("i").setDirection(EDIFDirection.INOUT);
        netlist.resetParentNetMap();
        Assertions.assertFalse(hasOutEdge(buildFittedGraph(d), pad, StaGraph.CELL_COMB));
    }

    /**
     * The registers of an UltraScale+ DSP48E2 that are bypassed or never enabled are not timed, as in Vivado:
     * no check at the clock enable of a bypassed register, and neither checks nor launches in a register group
     * whose clock enables are all tied to ground.
     */
    @Test
    public void testDspUnusedRegisters() {
        Design d = RapidWrightDCP.loadDCP("optical-flow.dcp");
        EDIFNetlist netlist = d.getNetlist();
        String mult = "bd_0_i/hls_inst/inst/flow_calc_U0/r_V_10_fu_462_p2";
        String reg = "bd_0_i/hls_inst/inst/flow_calc_U0/r_V_10_reg_707_reg";
        String outputCell = reg + "/DSP_OUTPUT_INST";
        StaGraph g = buildFittedGraph(d);
        // AREG=1: a check at CEA2 (that of the A2 register), none at CEA1, which ground drives
        Assertions.assertTrue(isEndpoint(g, mult + "/DSP_A_B_DATA_INST/CEA2"));
        Assertions.assertFalse(isEndpoint(g, mult + "/DSP_A_B_DATA_INST/CEA1"));
        // With CEA1 driven by logic too, Vivado checks it as well (AREG=1)
        EDIFHierCellInst multInst = netlist.getHierCellInstFromName(mult);
        EDIFPortInst cea1 = multInst.getInst().getPortInst("CEA1");
        cea1.getNet().removePortInst(cea1);
        multInst.getInst().getPortInst("CEA2").getNet().addPortInst(cea1);
        netlist.resetParentNetMap();
        g = buildFittedGraph(d);
        Assertions.assertTrue(isEndpoint(g, mult + "/DSP_A_B_DATA_INST/CEA1"));
        Assertions.assertTrue(isEndpoint(g, mult + "/DSP_A_B_DATA_INST/CEA2"));
        int[] before = getEndpointsAndLaunches(g, outputCell);
        Assertions.assertTrue(isEndpoint(g, outputCell + "/CEP"));
        Assertions.assertTrue(before[0] > 1);
        Assertions.assertTrue(before[1] > 0);

        // Bypass the A registers: no check at CEA1 or CEA2
        multInst.getInst().addProperty("AREG", 0);
        // Tie the P register's clock enable to ground: the output register group never loads
        EDIFHierCellInst regInst = netlist.getHierCellInstFromName(reg);
        EDIFPortInst cep = regInst.getInst().getPortInst("CEP");
        cep.getNet().removePortInst(cep);
        EDIFTools.getStaticNet(NetType.GND, regInst.getParent().getCellType(), netlist).addPortInst(cep);
        netlist.resetParentNetMap();

        g = buildFittedGraph(d);
        Assertions.assertFalse(isEndpoint(g, mult + "/DSP_A_B_DATA_INST/CEA2"));
        Assertions.assertFalse(isEndpoint(g, mult + "/DSP_A_B_DATA_INST/CEA1"));
        Assertions.assertArrayEquals(new int[]{0, 0}, getEndpointsAndLaunches(g, outputCell));
    }

    /** The intra-site delays of a slice's bypass input AX to its flip-flop AFF, at a part */
    private static float[] getBypassDelays(String part) {
        Design d = new Design("bypass", part);
        Cell ff = d.createAndPlaceCell("ff", Unisim.FDRE, "SLICE_X0Y0/AFF");
        return new FamilyArcProvider(d, Corner.SLOW_MAX).getSinkIntraSiteDelays(ff.getSiteInst(), "AX",
                ff.getBEL().getPin("D"));
    }

    @Test
    public void testConfigurationDelaySpeedFactors() {
        // UltraScale+: the delay from AX to the flip-flop scales from -2 to -3 by a factor of its own, not by that of
        // all configuration delays
        float[] ref = getBypassDelays("xcvu3p-ffvc1517-2-e");
        float[] fast = getBypassDelays("xcvu3p-ffvc1517-3-e");
        Design d = new Design("lib", "xcvu3p-ffvc1517-3-e");
        FittedCellArcLibrary lib = new FamilyArcProvider(d, Corner.SLOW_MAX).getLibrary();
        String site = d.getDevice().getSite("SLICE_X0Y0").getSiteTypeEnum().name();
        FittedCellArcLibrary.ConfigResult r = lib.config(site, "AX", "AFF/D", "");
        int max = FittedCellArcLibrary.getDataIndex(Corner.SLOW_MAX);
        Assertions.assertEquals(r.getDelays()[max], ref[0], 1e-3f);
        Assertions.assertEquals(lib.scaleConfig(r, "xcvu3p", "3", max), fast[0], 1e-3f);
        // (The factor of all configuration delays, from an entry without one of its own: a site pin's default)
        FittedCellArcLibrary.ConfigResult def = lib.config(site, "NONE/O", "AMUX", "");
        float category = lib.scaleConfig(def, "xcvu3p", "3", max) / def.getDelays()[max];
        Assertions.assertTrue(fast[0] < 0.95f * category * ref[0], fast[0] + " " + ref[0] + " " + category);
    }

    /**
     * A global clock buffer that logic drives (a clock buffer used as data, e.g. on a high-fanout clock enable) is
     * timed: from its driver, through the buffer, to the loads of its output net, as in Vivado.
     */
    @Test
    public void testDataClockBuffer() {
        Design d = new Design("bufg_data", "xcvu3p-ffvc1517-2-e");
        Cell src = d.createAndPlaceCell("src", Unisim.FDRE, "SLICE_X10Y60/AFF");
        Cell buf = d.createAndPlaceCell("buf", Unisim.BUFGCE, "BUFGCE_X0Y24/BUFCE");
        Cell dst = d.createAndPlaceCell("dst", Unisim.FDRE, "SLICE_X12Y62/AFF");
        Net enable = d.createNet("enable");
        enable.connect(src, "Q");
        enable.connect(buf, "I");
        Net bufferedEnable = d.createNet("enable_bufg");
        bufferedEnable.connect(buf, "O");
        bufferedEnable.connect(dst, "CE");
        Assertions.assertTrue(bufferedEnable.isClockNet());

        StaGraph g = buildFittedGraph(d);
        Assertions.assertTrue(hasOutEdge(g, "src/Q", StaGraph.NET));
        Assertions.assertTrue(hasOutEdge(g, "buf/I", StaGraph.CELL_COMB));
        Assertions.assertTrue(hasOutEdge(g, "buf/O", StaGraph.NET));
        Assertions.assertTrue(isEndpoint(g, "dst/CE"));
        // Through a connection of the timing graph to the site pin of the clock enable
        int ce = g.getPin("dst/CE");
        Assertions.assertEquals(1, g.getInDegree(ce));
        int conn = g.getEdgeConnection(g.getInEdgeStart(ce));
        Assertions.assertTrue(conn >= 0);
        Assertions.assertSame(bufferedEnable, g.getConnectionSink(conn).getNet());
    }

    /**
     * A register of the Versal XPHY bus logic interface (a BLI_DFF BEL) is clocked through its CLK BEL pin, which the
     * device data does not mark as a clock pin: it is a clock pin of the timing graph all the same, so the clock net
     * into it is not timed as data and the pin is no endpoint.
     */
    @Test
    public void testVersalBLIRegisterClockPin() {
        Design d = new Design("bli_reg", "xcvc1902-vsva2197-2MP-e-S");
        Cell buf = d.createAndPlaceCell("buf", Unisim.BUFGCE, "BUFGCE_X0Y0/BUFCE");
        Cell src = d.createAndPlaceCell("src", Unisim.FDRE, "SLICE_X0Y326/AFF");
        Cell bli = d.createAndPlaceCell("bli", Unisim.FDRE, "BLI_A_GRP0_X0Y1/A_GRP0_IFF0");
        Net clk = d.createNet("clk");
        clk.connect(buf, "O");
        clk.connect(src, "C");
        clk.connect(bli, "C");
        Net data = d.createNet("data");
        data.connect(src, "Q");
        data.connect(bli, "D");
        // The output's net has no load (a route out of the BLI site is out of reach of the estimate of a test)
        d.createNet("q").connect(bli, "Q");

        Assertions.assertEquals("BLI_DFF", bli.getBEL().getBELType());
        BELPin clkPin = bli.getBEL().getPin(bli.getPhysicalPinMapping("C"));
        Assertions.assertFalse(clkPin.isClock());
        Assertions.assertTrue(StaGraphBuilder.isClockBELPin(clkPin));
        Assertions.assertFalse(StaGraphBuilder.isClockBELPin(bli.getBEL().getPin(bli.getPhysicalPinMapping("D"))));
        Assertions.assertTrue(StaGraphBuilder.isClockBELPin(src.getBEL().getPin(src.getPhysicalPinMapping("C"))));
        Assertions.assertFalse(StaGraphBuilder.isClockBELPin(null));

        StaGraph g = buildFittedGraph(d);
        // Without the rule, the clock net was timed into the register's clock pin, an endpoint
        Assertions.assertFalse(isEndpoint(g, "bli/C"));
        Assertions.assertFalse(hasOutEdge(g, "buf/O", StaGraph.NET));
        Assertions.assertTrue(isEndpoint(g, "bli/D"));
        Assertions.assertTrue(hasOutEdge(g, "src/Q", StaGraph.NET));
        Assertions.assertTrue(hasOutEdge(g, "src/C", StaGraph.CLK_TO_Q));
        // The register launches from its clock pin, as the slice register does
        Assertions.assertTrue(hasOutEdge(g, "bli/C", StaGraph.CLK_TO_Q));
        Assertions.assertEquals(0, g.getInDegree(g.getPin("bli/C")));
    }

    /** The distinct connections of the in-edges of a pin */
    private static Set<Integer> getInConnections(StaGraph g, String pinName) {
        Set<Integer> conns = new HashSet<>();
        int p = g.getPin(pinName);
        for (int e = p < 0 ? 0 : g.getInEdgeStart(p); p >= 0 && e < g.getInEdgeEnd(p); e++) {
            if (g.getEdgeConnection(e) != StaGraph.NO_CONN) {
                conns.add(g.getEdgeConnection(e));
            }
        }
        return conns;
    }

    /**
     * An UltraScale+ block RAM times what Vivado times: its B port, whose clock CLKBWRCLK is tied in this design,
     * has neither checks nor clock-to-output arcs; WEA[1] to WEA[3], wired to no physical pin (every write enable
     * site pin is WEA[0]'s), are not checked; the A port is.
     */
    @Test
    public void testBlockRamPortsAndUnmappedPins() {
        Design d = RapidWrightDCP.loadDCP("picoblaze_ooc_X10Y235.dcp");
        String ram = "your_program/ram_4096x8";
        Assertions.assertEquals("RAMB36E2", d.getCell(ram).getType());
        StaGraph g = buildFittedGraph(d);
        Assertions.assertTrue(isEndpoint(g, ram + "/ADDRARDADDR[5]"));
        Assertions.assertTrue(isEndpoint(g, ram + "/WEA[0]"));
        for (String pin : new String[]{"WEA[1]", "WEA[2]", "WEA[3]", "DINBDIN[0]", "DINPBDINP[0]"}) {
            Assertions.assertFalse(isEndpoint(g, ram + "/" + pin), pin);
        }
        Assertions.assertFalse(hasOutEdge(g, ram + "/CLKBWRCLK", StaGraph.CLK_TO_Q));
        Assertions.assertTrue(hasOutEdge(g, ram + "/CLKARDCLK", StaGraph.CLK_TO_Q));
    }

    /**
     * Each site pin of a block RAM's logical pin (a RAMB36's address on the site pins of both its RAMB18 halves, its
     * write enable on all eight) is a connection of its own into that pin, so the slowest and the fastest are timed.
     */
    @Test
    public void testBlockRamPinOnSeveralSitePins() {
        Design d = RapidWrightDCP.loadDCP("picoblaze_ooc_X10Y235.dcp");
        String ram = "your_program/ram_4096x8";
        StaGraph g = buildFittedGraph(d);
        Assertions.assertEquals(2, getInConnections(g, ram + "/ADDRARDADDR[5]").size());
        Assertions.assertEquals(8, getInConnections(g, ram + "/WEA[0]").size());
    }

    /**
     * The address of a LUT RAM is on the read address pins of its LUT and on the write address pins of its site,
     * which come from the site pins of another LUT: each of those site pins is a connection of its own into the
     * address pin, so the route to the LUT's own site pin times its read path.
     */
    @Test
    public void testLutRamAddressOnReadAndWriteSitePins() {
        Design d = RapidWrightDCP.loadDCP("bnn.dcp");
        DesignTools.createMissingSitePinInsts(d);
        StaGraph g = buildFittedGraph(d);
        int numChecked = 0;
        for (Cell c : d.getCells()) {
            if (c.getBEL() == null || !c.getBEL().isLUT() || !c.getType().startsWith("RAM")) continue;
            char lut = c.getBELName().charAt(0);
            if (lut == 'H') continue;
            for (EDIFPortInst pi : c.getEDIFCellInst().getPortInsts()) {
                Set<String> phys = c.getAllPhysicalPinMappings(pi.getName());
                if (!pi.isInput() || phys == null || phys.size() < 2) continue;
                Set<String> sitePins = new HashSet<>();
                for (int conn : getInConnections(g, c.getName() + "/" + pi.getName())) {
                    sitePins.add(g.getConnectionSink(conn).getName());
                }
                if (sitePins.isEmpty()) continue;
                // (An address bit on the LUT's own site pin and on that of the write address of the site)
                String read = null;
                for (String p : phys) {
                    if (!p.startsWith("WA")) read = lut + p.substring(1);
                }
                Assertions.assertTrue(sitePins.size() == 2 && sitePins.contains(read), c + " " + pi + " " + sitePins);
                numChecked++;
            }
        }
        Assertions.assertTrue(numChecked > 0);
    }

    /**
     * Asserts that two graphs of a design have the same pins, edges and connections, with the same delays; the
     * second min route delays of the connections are compared only if {@code min2Routes} is set.
     */
    private static void assertSameGraph(StaGraph expected, StaGraph actual, boolean min2Routes) {
        Assertions.assertEquals(expected.getNumPins(), actual.getNumPins());
        Assertions.assertEquals(expected.getNumEdges(), actual.getNumEdges());
        Assertions.assertEquals(expected.getNumConnections(), actual.getNumConnections());
        Assertions.assertEquals(expected.hasSecondMinCorner(), actual.hasSecondMinCorner());
        for (int p = 0; p < expected.getNumPins(); p++) {
            Assertions.assertEquals(expected.getPinName(p), actual.getPinName(p));
        }
        for (int e = 0; e < expected.getNumEdges(); e++) {
            String what = "edge " + e;
            Assertions.assertEquals(expected.getEdgeFrom(e), actual.getEdgeFrom(e), what);
            Assertions.assertEquals(expected.getEdgeTo(e), actual.getEdgeTo(e), what);
            Assertions.assertEquals(expected.getEdgeKind(e), actual.getEdgeKind(e), what);
            Assertions.assertEquals(expected.getEdgeConnection(e), actual.getEdgeConnection(e), what);
            Assertions.assertEquals(expected.getEdgeFixedMax(e), actual.getEdgeFixedMax(e), what);
            Assertions.assertEquals(expected.getEdgeFixedMin(e), actual.getEdgeFixedMin(e), what);
            Assertions.assertEquals(expected.getEdgeFixedMin2(e), actual.getEdgeFixedMin2(e), what);
            Assertions.assertEquals(expected.getEdgeIntraSiteMax(e), actual.getEdgeIntraSiteMax(e), what);
        }
        for (int c = 0; c < expected.getNumConnections(); c++) {
            String what = "connection " + c;
            Assertions.assertSame(expected.getConnectionSink(c), actual.getConnectionSink(c), what);
            Assertions.assertEquals(expected.getConnectionRouteMax(c), actual.getConnectionRouteMax(c), what);
            Assertions.assertEquals(expected.getConnectionRouteMin(c), actual.getConnectionRouteMin(c), what);
            if (min2Routes && expected.hasSecondMinCorner()) {
                Assertions.assertEquals(expected.getConnectionRouteMin2(c), actual.getConnectionRouteMin2(c), what);
            }
        }
    }

    /**
     * The constructor that takes the delay estimators of all three corners gives the graph of the existing
     * constructors, when given the estimators those create or null; given an estimator of its own for the second
     * min corner, only the second min route delays of the connections change: to those of that estimator.
     */
    @Test
    public void testEstimatorsOfAllCorners() {
        Design d = RapidWrightDCP.loadDCP("picoblaze_2022.2.dcp");
        Device device = d.getDevice();
        TimingFamily family = TimingFamilies.of(device);
        SpeedGrade grade = family.resolveSpeedGrade(PartNameTools.getPart(d.getPartName()));
        DelayEstimator max = family.createDelayEstimator(device, grade, Corner.SLOW_MAX, true);
        DelayEstimator min = family.createDelayEstimator(device, grade, Corner.FAST_MIN, true);
        DelayEstimator min2 = family.createDelayEstimator(device, grade, Corner.SLOW_MIN, true);
        StaGraph ref = new StaGraphBuilder(d, new FamilyArcProvider(d, null, Corner.SLOW_MAX, null, Corner.FAST_MIN,
                Corner.SLOW_MIN)).build();
        Assertions.assertTrue(ref.hasSecondMinCorner());
        Assertions.assertTrue(ref.getNumConnections() > 100);
        assertSameGraph(ref, new StaGraphBuilder(d, new FamilyArcProvider(d, max, Corner.SLOW_MAX, min,
                Corner.FAST_MIN, min2, Corner.SLOW_MIN)).build(), true);
        assertSameGraph(ref, new StaGraphBuilder(d, new FamilyArcProvider(d, null, Corner.SLOW_MAX, null,
                Corner.FAST_MIN, null, Corner.SLOW_MIN)).build(), true);
        // Without a second min corner, its estimator is ignored
        assertSameGraph(new StaGraphBuilder(d, new FamilyArcProvider(d, max, Corner.SLOW_MAX, min, Corner.FAST_MIN,
                null)).build(), new StaGraphBuilder(d, new FamilyArcProvider(d, max, Corner.SLOW_MAX, min,
                Corner.FAST_MIN, min2, null)).build(), true);

        // The SLOW_MAX estimator at the second min corner: the second min route delays of the connections that the
        // estimator times become their max route delays; nothing else changes
        StaGraph g = new StaGraphBuilder(d, new FamilyArcProvider(d, max, Corner.SLOW_MAX, min, Corner.FAST_MIN,
                max, Corner.SLOW_MIN)).build();
        assertSameGraph(ref, g, false);
        int changed = 0;
        for (int c = 0; c < g.getNumConnections(); c++) {
            float routeMin2 = g.getConnectionRouteMin2(c);
            if (routeMin2 != ref.getConnectionRouteMin2(c)) {
                Assertions.assertEquals(g.getConnectionRouteMax(c), routeMin2, "connection " + c);
                changed++;
            }
        }
        Assertions.assertTrue(changed > 100, Integer.toString(changed));
    }
}

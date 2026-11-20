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

package com.xilinx.rapidwright.timing.clock;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import com.xilinx.rapidwright.design.Design;
import com.xilinx.rapidwright.design.NetType;
import com.xilinx.rapidwright.design.Unisim;
import com.xilinx.rapidwright.edif.EDIFCell;
import com.xilinx.rapidwright.edif.EDIFCellInst;
import com.xilinx.rapidwright.edif.EDIFDirection;
import com.xilinx.rapidwright.edif.EDIFNet;
import com.xilinx.rapidwright.edif.EDIFNetlist;
import com.xilinx.rapidwright.edif.EDIFPort;
import com.xilinx.rapidwright.edif.EDIFTools;

public class TestClockNetwork {

    /** Builds small flat netlists of unisim cells */
    static final class NetlistBuilder {
        final EDIFNetlist netlist = EDIFTools.createNewNetlist("top");
        final EDIFCell top = netlist.getTopCell();
        final Map<String, EDIFNet> nets = new HashMap<>();
        final Map<String, EDIFCellInst> insts = new HashMap<>();

        EDIFCellInst inst(String name, Unisim type, String... props) {
            EDIFCell cell = Design.getUnisimCell(type);
            EDIFTools.ensureCellInLibraries(netlist, cell);
            EDIFCellInst ci = top.createChildCellInst(name, cell);
            for (int i = 0; i + 1 < props.length; i += 2) {
                ci.addProperty(props[i], props[i + 1]);
            }
            insts.put(name, ci);
            return ci;
        }

        EDIFNet net(String name) {
            return nets.computeIfAbsent(name, n -> top.createNet(n));
        }

        /** Connects a pin "inst/port" (or a top port name) to a net */
        NetlistBuilder pin(String netName, String pin) {
            EDIFNet net = net(netName);
            int slash = pin.indexOf('/');
            if (slash < 0) {
                EDIFPort port = top.getPort(pin);
                if (port == null) {
                    port = top.createPort(pin, EDIFDirection.INPUT, 1);
                }
                net.createPortInst(port);
            } else {
                net.createPortInst(pin.substring(slash + 1), insts.get(pin.substring(0, slash)));
            }
            return this;
        }

        NetlistBuilder connect(String netName, String... pins) {
            for (String p : pins) {
                pin(netName, p);
            }
            return this;
        }

        /** Ties the bits of a bus input of a cell to a value, e.g. tie("cm/QPLL0REFCLKSEL", 3, 1) */
        void tie(String busPin, int bits, int value) {
            int slash = busPin.indexOf('/');
            EDIFCellInst ci = insts.get(busPin.substring(0, slash));
            String bus = busPin.substring(slash + 1);
            for (int b = 0; b < bits; b++) {
                NetType t = ((value >> b) & 1) != 0 ? NetType.VCC : NetType.GND;
                EDIFTools.getStaticNet(t, top, netlist).createPortInst(bits == 1 ? bus : bus + "[" + b + "]", ci);
            }
        }

        /** A flip-flop clocked by a net, with D/Q looped through a net of its own */
        void flop(String name, String clkNet, String... props) {
            inst(name, Unisim.FDRE, props);
            connect(clkNet, name + "/C");
            connect(name + "_q", name + "/Q", name + "/D");
        }
    }

    /**
     * clk_a -> IBUF -> MMCME4 (x8, CLKOUT0 /4, CLKOUT0B, CLKOUT1 /5 at 90 degrees, BUFG feedback)
     * -> BUFGs / BUFGCE_DIV (/4) / LUT1 inverter -> flops; clk_b -> IBUF -> BUFG -> flops.
     */
    static NetlistBuilder mmcmNetlist() {
        NetlistBuilder nb = new NetlistBuilder();
        nb.inst("ib_a", Unisim.IBUF);
        nb.inst("ib_b", Unisim.IBUF);
        nb.inst("mmcm", Unisim.MMCME4_ADV, "CLKFBOUT_MULT_F", "8.0", "DIVCLK_DIVIDE", "1",
                "CLKOUT0_DIVIDE_F", "4.0", "CLKOUT1_DIVIDE", "5", "CLKOUT1_PHASE", "90.0");
        nb.inst("bfb", Unisim.BUFG);
        nb.inst("b0", Unisim.BUFG);
        nb.inst("b0b", Unisim.BUFG);
        nb.inst("bdiv", Unisim.BUFGCE_DIV, "BUFGCE_DIVIDE", "4");
        nb.inst("bb", Unisim.BUFG);
        nb.inst("inv", Unisim.LUT1, "INIT", "2'h1");
        nb.connect("clk_a", "clk_a", "ib_a/I");
        nb.connect("a_ib", "ib_a/O", "mmcm/CLKIN1");
        nb.connect("fb", "mmcm/CLKFBOUT", "bfb/I");
        nb.connect("fbb", "bfb/O", "mmcm/CLKFBIN");
        nb.connect("o0", "mmcm/CLKOUT0", "b0/I");
        nb.connect("o0b", "mmcm/CLKOUT0B", "b0b/I");
        nb.connect("o1", "mmcm/CLKOUT1", "bdiv/I");
        nb.connect("c0", "b0/O", "inv/I0");
        nb.connect("c0b", "b0b/O");
        nb.connect("cdiv", "bdiv/O");
        nb.connect("c0n", "inv/O");
        nb.flop("r0", "c0");
        nb.flop("r0b", "c0b");
        nb.flop("rdiv", "cdiv");
        nb.flop("rinv", "c0n");
        nb.flop("rneg", "c0", "IS_C_INVERTED", "1'b1");
        nb.connect("clk_b", "clk_b", "ib_b/I");
        nb.connect("b_ib", "ib_b/O", "bb/I");
        nb.connect("cb", "bb/O");
        nb.flop("rb", "cb");
        return nb;
    }

    private static int[] edgesOf(ClockNetwork cn, String pin) {
        int cp = cn.getClockPinIndex(pin);
        Assertions.assertTrue(cp >= 0, pin);
        int[] e = cn.getClockPinEdges(cp).clone();
        Arrays.sort(e);
        return e;
    }

    private static void assertClock(Clock c, double period, double rise, double fall, double latency) {
        Assertions.assertNotNull(c);
        Assertions.assertEquals(period, c.getPeriod(), 1e-6, c.getName());
        Assertions.assertEquals(rise, c.getWaveform()[0], 1e-6, c.getName());
        Assertions.assertEquals(fall, c.getWaveform()[1], 1e-6, c.getName());
        Assertions.assertEquals(latency, c.getSourceLatency(), 1e-6, c.getName());
    }

    @Test
    public void testClockOnPortNetReplacesPortClock() {
        // A clock on the net of a top-level port is on that port: it replaces an earlier clock there
        NetlistBuilder nb = mmcmNetlist();
        TimingConstraints tc = TimingConstraints.parse(nb.netlist, Arrays.asList(
                "create_clock -period 8 -name clk_a [get_ports clk_a]",
                "create_clock -period 10 -name sys_clk [get_ports clk_b]",
                "create_clock -period 10 -name ctx_clk -waveform {0.000 5.000} [get_nets clk_b]"));
        ClockNetwork cn = ClockNetwork.build(nb.netlist, tc);
        Assertions.assertNull(cn.getClock("sys_clk"));
        Clock c = cn.getClock("ctx_clk");
        Assertions.assertNotNull(c);
        Assertions.assertArrayEquals(new int[]{2 * c.getId()}, edgesOf(cn, "rb/C"));

        // The same generated clock twice (e.g. the constraints of two XDC groups): one clock
        String gen = "create_generated_clock -name gen0 -source [get_pins mmcm/CLKIN1] -multiply_by 2 "
                + "[get_pins mmcm/CLKOUT0]";
        TimingConstraints tc2 = TimingConstraints.parse(nb.netlist, Arrays.asList(
                "create_clock -period 8 -name clk_a [get_ports clk_a]", gen, gen));
        ClockNetwork cn2 = ClockNetwork.build(nb.netlist, tc2);
        int named = 0;
        for (Clock k : cn2.getClocks()) {
            if (k.getName().equals("gen0")) named++;
        }
        Assertions.assertEquals(1, named);
        Assertions.assertTrue(cn2.getWarnings().keySet().stream().noneMatch(w -> w.contains("duplicate")));
    }

    @Test
    public void testDerivedClocks() {
        NetlistBuilder nb = mmcmNetlist();
        TimingConstraints tc = TimingConstraints.parse(nb.netlist, Arrays.asList(
                "create_clock -period 8 -name clk_a [get_ports clk_a]",
                "create_clock -period 10 -name clk_b [get_ports clk_b]",
                "create_generated_clock -name ren [get_pins mmcm/CLKOUT0B]"));
        ClockNetwork cn = ClockNetwork.build(nb.netlist, tc);
        Assertions.assertTrue(cn.getWarnings().isEmpty(), cn.getWarnings().toString());
        Clock a = cn.getClock("clk_a");
        assertClock(a, 8000, 0, 4000, 0);
        // Feedback clock (it drives a BUFG), named after its net
        Clock fb = cn.getClock("fb");
        assertClock(fb, 8000, 0, 4000, 0);
        Assertions.assertSame(a, fb.getMaster());
        assertClock(cn.getClock("o0"), 4000, 0, 2000, 0);
        // Inverted output, renamed by create_generated_clock
        assertClock(cn.getClock("ren"), 4000, 2000, 4000, 0);
        Assertions.assertNull(cn.getClock("o0b"));
        // Phase shift in latency mode: 90 degrees of 5 ns
        Clock o1 = cn.getClock("o1");
        assertClock(o1, 5000, 0, 2500, 1250);
        Assertions.assertTrue(o1.isAutoDerived());
        Assertions.assertEquals("mmcm", o1.getGeneratorCell());
        // BUFGCE_DIV: divide by 4 (edges 1, 5, 9 of the master)
        Clock div = cn.getClock("cdiv");
        assertClock(div, 20000, 0, 10000, 1250);
        Assertions.assertSame(o1, div.getMaster());
        Assertions.assertSame(a, div.getRoot());

        int o0 = cn.getClock("o0").getId();
        Assertions.assertArrayEquals(new int[]{2 * o0}, edgesOf(cn, "r0/C"));
        Assertions.assertArrayEquals(new int[]{2 * cn.getClock("ren").getId()}, edgesOf(cn, "r0b/C"));
        Assertions.assertArrayEquals(new int[]{2 * div.getId()}, edgesOf(cn, "rdiv/C"));
        // Inverted by a LUT1 inverter, and by IS_C_INVERTED
        Assertions.assertArrayEquals(new int[]{2 * o0 + 1}, edgesOf(cn, "rinv/C"));
        Assertions.assertArrayEquals(new int[]{2 * o0 + 1}, edgesOf(cn, "rneg/C"));
        Assertions.assertArrayEquals(new int[]{2 * cn.getClock("clk_b").getId()}, edgesOf(cn, "rb/C"));
        Assertions.assertEquals(6, cn.getNumClockPins());
        Assertions.assertEquals(cn.getClockPinIndex("r0/C"), cn.getClockPinOf("r0", "Q"));

        Assertions.assertTrue(cn.areRelated(div, cn.getClock("o0")));
        Assertions.assertFalse(cn.areRelated(div, cn.getClock("clk_b")));

        // Clock queries resolved against the clocks
        TimingConstraints q = TimingConstraints.parse(nb.netlist, Arrays.asList(
                "set_false_path -from [get_clocks -include_generated_clocks clk_a] -to [get_clocks o*]",
                "set_false_path -from [get_clocks -of_objects [get_pins r0/C]]",
                "set_false_path -from [get_clocks -of_objects [get_cells rdiv]]"));
        List<TimingConstraints.PathExceptionDef> ex = q.getExceptions();
        // clk_a, fb, o0, o1, cdiv; not ren, whose rename (command 2 of the clock constraints) comes after
        // this query (command 0 of these), as clock queries only see the clocks defined before them
        Assertions.assertEquals(5, cn.resolveClocks(ex.get(0).from).size());
        Assertions.assertEquals(2, cn.resolveClocks(ex.get(0).to).size());
        Assertions.assertEquals(o0, (int) cn.resolveClocks(ex.get(1).from).iterator().next());
        Assertions.assertEquals(div.getId(), (int) cn.resolveClocks(ex.get(2).from).iterator().next());

        // The stages: the MMCM outputs hang below its CLKIN pin
        boolean found = false;
        for (ClockNetwork.Stage st : cn.getStages()) {
            if (st.getDriver() != null && st.getDriver().toString().equals("mmcm/CLKOUT1")) {
                Assertions.assertEquals(ClockNetwork.StageKind.GENERATOR, st.getKind());
                Assertions.assertEquals("mmcm/CLKIN1", st.getParentPin().toString());
                found = true;
            }
        }
        Assertions.assertTrue(found);
    }

    /** Objects of clock commands given as text are a port, else a pin, else a net of that name */
    @Test
    public void testClockObjectsAsText() {
        NetlistBuilder nb = mmcmNetlist();
        TimingConstraints tc = TimingConstraints.parse(nb.netlist, Arrays.asList(
                "create_clock -period 8 -name clk_a clk_a",
                "create_clock -period 10 -name clk_b cb",
                // Renames of auto-derived clocks, as Vivado writes them
                "create_generated_clock -name ren0 mmcm/CLKOUT0",
                "create_generated_clock -name ren1 {mmcm/CLKOUT1}",
                "create_generated_clock -name g0b -source mmcm/CLKIN1 -multiply_by 2 -invert mmcm/CLKOUT0B"));
        Assertions.assertEquals(XdcObject.Kind.PORT, tc.getClocks().get(0).sources.get(0).getKind());
        Assertions.assertEquals(XdcObject.Kind.NET, tc.getClocks().get(1).sources.get(0).getKind());
        for (TimingConstraints.GeneratedClockDef g : tc.getGeneratedClocks()) {
            Assertions.assertEquals(XdcObject.Kind.PIN, g.targets.get(0).getKind(), g.name);
        }
        Assertions.assertEquals(XdcObject.Kind.PIN, tc.getGeneratedClocks().get(2).source.getKind());

        ClockNetwork cn = ClockNetwork.build(nb.netlist, tc);
        Assertions.assertTrue(cn.getWarnings().isEmpty(), cn.getWarnings().toString());
        Assertions.assertNull(cn.getClock("o0"));
        Assertions.assertNull(cn.getClock("o1"));
        assertClock(cn.getClock("ren0"), 4000, 0, 2000, 0);
        assertClock(cn.getClock("ren1"), 5000, 0, 2500, 1250);
        Assertions.assertArrayEquals(new int[]{2 * cn.getClock("ren0").getId()}, edgesOf(cn, "r0/C"));
        Assertions.assertArrayEquals(new int[]{2 * cn.getClock("g0b").getId()}, edgesOf(cn, "r0b/C"));
        Assertions.assertSame(cn.getClock("ren1"), cn.getClock("cdiv").getMaster());
        Assertions.assertArrayEquals(new int[]{2 * cn.getClock("clk_b").getId()}, edgesOf(cn, "rb/C"));

        // Relative to the current instance; a top-level port first, also a bit of a bus port
        EDIFNetlist nl = hierarchicalNetlist();
        nl.getTopCell().createPort("cb[1:0]", EDIFDirection.INPUT, 2);
        nl.getTopCell().createNet("cb[0]");
        tc = TimingConstraints.parse(nl, Arrays.asList(
                "current_instance h",
                "create_clock -period 4 -name hk ck",
                "current_instance",
                "create_clock -period 4 -name cb0 {cb[0]}"));
        Assertions.assertEquals(XdcObject.Kind.NET, tc.getClocks().get(0).sources.get(0).getKind());
        Assertions.assertEquals(XdcObject.Kind.PORT, tc.getClocks().get(1).sources.get(0).getKind());
        cn = ClockNetwork.build(nl, tc);
        Assertions.assertArrayEquals(new int[]{2 * cn.getClock("hk").getId()}, edgesOf(cn, "h/r/C"));
    }

    /** get_pins -filter {NAME =~ glob} without a pattern: the pins whose full name matches */
    @Test
    public void testGetPinsByNameFilter() {
        // Vivado-written XDC files rename auto-derived clocks this way
        NetlistBuilder nb = mmcmNetlist();
        TimingConstraints tc = TimingConstraints.parse(nb.netlist, Arrays.asList(
                "create_clock -period 8 -name clk_a [get_ports clk_a]",
                "create_generated_clock -name ren1 [get_pins -hierarchical -filter {NAME =~ *mm*/CLKOUT1}]"));
        Assertions.assertEquals("mmcm/CLKOUT1", tc.getGeneratedClocks().get(0).targets.get(0).getName());
        ClockNetwork cn = ClockNetwork.build(nb.netlist, tc);
        Assertions.assertNull(cn.getClock("o1"));
        assertClock(cn.getClock("ren1"), 5000, 0, 2500, 1250);

        // '*' matches across hierarchy levels; the pins of all cells with -hierarchical (hierarchical cells too),
        // else of the children of the current instance
        EDIFNetlist nl = hierarchicalNetlist();
        tc = TimingConstraints.parse(nl, Arrays.asList(
                "set_false_path -to [get_pins -hierarchical -filter {NAME =~ *r*/C}]",
                "set_false_path -to [get_pins -hierarchical -filter {NAME =~ *clk_in}]",
                "set_false_path -to [get_pins -filter {NAME =~ \"*/?\"}]",
                "set_false_path -to [get_pins -filter {NAME =~ h/*/C}]",
                "current_instance h",
                "set_false_path -to [get_pins -filter {NAME =~ h/*/?}]"));
        List<TimingConstraints.PathExceptionDef> ex = tc.getExceptions();
        Assertions.assertEquals(Arrays.asList("h/r/C", "h/r2/C"), names(ex.get(0).to));
        Assertions.assertEquals(Arrays.asList("h/clk_in"), names(ex.get(1).to));
        Assertions.assertEquals(Arrays.asList("o/C", "o/D", "o/Q"), names(ex.get(2).to));
        Assertions.assertEquals(Arrays.asList("h/r/C", "h/r/D", "h/r/Q", "h/r2/C", "h/r2/D", "h/r2/Q"),
                names(ex.get(3).to));
        Assertions.assertEquals(4, ex.size());
        // Without -hierarchical, h/r/C is not a pin of a child of the top
        Assertions.assertEquals(1,
                tc.getWarnings().get("set_false_path with -from or -to matching no object (ignored)"));
        Assertions.assertEquals(1, tc.getWarnings().size(), tc.getWarnings().toString());

        // Other filters without a pattern: of all pins
        tc = TimingConstraints.parse(nl, Arrays.asList(
                "set_false_path -to [get_pins -hierarchical -filter {REF_PIN_NAME == C}]"));
        Assertions.assertEquals(Arrays.asList("h/r/C", "h/r2/C", "o/C"), names(tc.getExceptions().get(0).to));
        Assertions.assertTrue(tc.getWarnings().isEmpty(), tc.getWarnings().toString());
    }

    private static List<String> names(List<XdcObject> objects) {
        List<String> names = new ArrayList<>();
        for (XdcObject o : objects) {
            names.add(o.getName());
        }
        Collections.sort(names);
        return names;
    }

    @Test
    public void testVersalClocks() {
        // X5PLL (x21 /1: CLKOUT1 /4, CLKOUTPHY_0 and _90 /1) and MBUFGCE (O1 /1, O2 /2)
        NetlistBuilder nb = new NetlistBuilder();
        nb.inst("pll", Unisim.X5PLL, "CLKFBOUT_MULT", "21", "DIVCLK_DIVIDE", "1", "CLKOUT1_DIVIDE", "4",
                "CLKOUTPHY_DIVIDE", "DIV1");
        nb.inst("mb", Unisim.MBUFGCE);
        nb.connect("ref", "ref", "pll/CLKIN");
        nb.connect("ub_clk", "pll/CLKOUT1", "mb/I");
        nb.connect("phy0", "pll/CLKOUTPHY_0");
        nb.connect("phy90", "pll/CLKOUTPHY_90");
        nb.connect("o1", "mb/O1");
        nb.connect("o2", "mb/O2");
        nb.flop("r1", "o1");
        nb.flop("r2", "o2");
        nb.flop("rp", "phy0");
        nb.flop("rp90", "phy90");
        TimingConstraints tc = TimingConstraints.parse(nb.netlist, Arrays.asList(
                "create_clock -period 5 -name ref [get_ports ref]"));
        ClockNetwork cn = ClockNetwork.build(nb.netlist, tc);
        double vco = 5000.0 / 21;
        assertClock(cn.getClock("ub_clk"), vco * 4, 0, vco * 2, 0);
        assertClock(cn.getClock("phy0"), vco, 0, vco / 2, 0);
        assertClock(cn.getClock("phy90"), vco, 0, vco / 2, vco / 4);
        assertClock(cn.getClock("o1"), vco * 4, 0, vco * 2, 0);
        assertClock(cn.getClock("o2"), vco * 8, 0, vco * 4, 0);
        Assertions.assertSame(cn.getClock("ub_clk"), cn.getClock("o1").getMaster());
        Assertions.assertArrayEquals(new int[]{2 * cn.getClock("o2").getId()}, edgesOf(cn, "r2/C"));
    }

    /**
     * The clock source mux of a Versal DSP58 (DSP_SRCMX_OPTINV, here inverting) passes its clock to the DSP's
     * other parts; a clock generator's DRP clock (DCLK) is a clock pin, that of its DRP ports.
     */
    @Test
    public void testDspClockMuxAndGeneratorDrpClock() {
        NetlistBuilder nb = mmcmNetlist();
        nb.connect("cb", "mmcm/DCLK");
        nb.inst("mx", Unisim.DSP_SRCMX_OPTINV, "IS_CLK_IN_INVERTED", "1'b1");
        nb.inst("dout", Unisim.DSP_OUTPUT58);
        nb.connect("cb", "mx/CLK_IN");
        nb.connect("dsp_clk", "mx/CLK", "dout/CLK");
        TimingConstraints tc = TimingConstraints.parse(nb.netlist, Arrays.asList(
                "create_clock -period 10 -name clk_a [get_ports clk_a]",
                "create_clock -period 8 -name clk_b [get_ports clk_b]"));
        ClockNetwork cn = ClockNetwork.build(nb.netlist, tc);
        int b = cn.getClock("clk_b").getId();
        Assertions.assertArrayEquals(new int[]{2 * b + 1}, edgesOf(cn, "dout/CLK"));
        Assertions.assertTrue(cn.getClockPinIndex("mx/CLK_IN") < 0);
        Assertions.assertArrayEquals(new int[]{2 * b}, edgesOf(cn, "mmcm/DCLK"));
        int dclk = cn.getClockPinOf("mmcm", "DI[3]");
        Assertions.assertEquals("DCLK", cn.getClockPin(dclk).getPortInst().getName());
    }

    /** A clock on the net of a top-level input port is the port's clock (no buffer, as out of context) */
    @Test
    public void testClockOnTopLevelPortNet() {
        NetlistBuilder nb = new NetlistBuilder();
        nb.connect("clk", "clk");
        nb.flop("r", "clk");
        TimingConstraints tc = TimingConstraints.parse(nb.netlist, Arrays.asList(
                "create_clock -period 5 -name k [get_nets clk]"));
        ClockNetwork cn = ClockNetwork.build(nb.netlist, tc);
        int cp = cn.getClockPinIndex("r/C");
        Assertions.assertTrue(cp >= 0);
        Assertions.assertEquals(ClockNetwork.StageKind.PORT, cn.getStages().get(cn.getClockPinStage(cp)).getKind());
    }

    /** -master_clock as a get_clocks query: a pattern, or -of_objects */
    @Test
    public void testMasterClockQuery() {
        for (String master : new String[]{"[get_clocks -of_objects [get_ports clk]]", "[get_clocks {k*}]",
            "[get_clocks -regexp {k.?}]"}) {
            NetlistBuilder nb = new NetlistBuilder();
            nb.connect("clk", "clk");
            nb.flop("div", "clk");
            nb.flop("r2", "div_q");
            TimingConstraints tc = TimingConstraints.parse(nb.netlist, Arrays.asList(
                    "create_clock -period 5 -name k [get_ports clk]",
                    "create_generated_clock -name g -source [get_pins div/C] -master_clock " + master
                            + " -divide_by 2 [get_pins div/Q]"));
            ClockNetwork cn = ClockNetwork.build(nb.netlist, tc);
            Clock g = cn.getClock("g");
            Assertions.assertNotNull(g, master);
            Assertions.assertEquals(10000, g.getPeriod(), 1e-6, master);
            Assertions.assertSame(cn.getClock("k"), g.getMaster(), master);
        }
    }

    /**
     * top: port clk -> h/clk_in (a hierarchical cell: flop r, and r2 clocked by r's Q) and flop o outside h.
     */
    private static EDIFNetlist hierarchicalNetlist() {
        EDIFNetlist nl = EDIFTools.createNewNetlist("top");
        EDIFCell top = nl.getTopCell();
        EDIFCell fd = Design.getUnisimCell(Unisim.FDRE);
        EDIFTools.ensureCellInLibraries(nl, fd);
        EDIFCell sub = new EDIFCell(nl.getWorkLibrary(), "sub");
        EDIFPort clkIn = sub.createPort("clk_in", EDIFDirection.INPUT, 1);
        EDIFCellInst r = sub.createChildCellInst("r", fd);
        EDIFCellInst r2 = sub.createChildCellInst("r2", fd);
        EDIFNet ck = sub.createNet("ck");
        ck.createPortInst(clkIn);
        ck.createPortInst("C", r);
        EDIFNet q = sub.createNet("q");
        q.createPortInst("Q", r);
        q.createPortInst("D", r);
        q.createPortInst("C", r2);
        EDIFNet q2 = sub.createNet("q2");
        q2.createPortInst("Q", r2);
        q2.createPortInst("D", r2);
        EDIFCellInst h = top.createChildCellInst("h", sub);
        EDIFCellInst o = top.createChildCellInst("o", fd);
        EDIFPort clkPort = top.createPort("clk", EDIFDirection.INPUT, 1);
        EDIFNet clk = top.createNet("clk");
        clk.createPortInst(clkPort);
        clk.createPortInst("clk_in", h);
        clk.createPortInst("C", o);
        EDIFNet oq = top.createNet("oq");
        oq.createPortInst("Q", o);
        oq.createPortInst("D", o);
        return nl;
    }

    /** Clocks defined on, and generated clocks sourced at, a hierarchical pin */
    @Test
    public void testHierarchicalPinClocks() {
        EDIFNetlist nl = hierarchicalNetlist();
        TimingConstraints tc = TimingConstraints.parse(nl, Arrays.asList(
                "create_clock -period 4 -name hc [get_pins h/clk_in]",
                "create_generated_clock -name g -source [get_pins h/clk_in] -divide_by 2 [get_pins h/r/Q]"));
        ClockNetwork cn = ClockNetwork.build(nl, tc);
        int hc = cn.getClock("hc").getId();
        Assertions.assertArrayEquals(new int[]{2 * hc}, edgesOf(cn, "h/r/C"));
        Assertions.assertTrue(cn.getClockPinIndex("o/C") < 0);
        Clock g = cn.getClock("g");
        Assertions.assertNotNull(g);
        Assertions.assertSame(cn.getClock("hc"), g.getMaster());
        Assertions.assertArrayEquals(new int[]{2 * g.getId()}, edgesOf(cn, "h/r2/C"));
        Assertions.assertNull(cn.getWarnings().get("clock reaching no clock pin"));

        // A generated clock sourced at a hierarchical pin the port's clock goes through
        tc = TimingConstraints.parse(nl, Arrays.asList(
                "create_clock -period 4 -name pk [get_ports clk]",
                "create_generated_clock -name g2 -source [get_pins h/clk_in] -divide_by 2 [get_pins h/r/Q]"));
        cn = ClockNetwork.build(nl, tc);
        Assertions.assertSame(cn.getClock("pk"), cn.getClock("g2").getMaster());
        Assertions.assertArrayEquals(new int[]{2 * cn.getClock("pk").getId()}, edgesOf(cn, "o/C"));

        // A clock whose source reaches no clock pin
        tc = TimingConstraints.parse(nl, Arrays.asList("create_clock -period 4 -name dead [get_pins o/D]"));
        cn = ClockNetwork.build(nl, tc);
        Assertions.assertEquals(1, cn.getWarnings().get("clock reaching no clock pin"));
    }

    @Test
    public void testGtClocks() {
        // 156.25 MHz reference -> GTYE4_COMMON QPLL0 (x66, FULL rate) -> channel: TX PMA clock (4 bytes of 8
        // bits) through a BUFG_GT /2, RX programmable divider (/33) through a BUFG_GT whose DIV comes from
        // set_case_analysis
        NetlistBuilder nb = new NetlistBuilder();
        nb.inst("ib", Unisim.IBUFDS_GTE4);
        nb.inst("cm", Unisim.GTYE4_COMMON, "QPLL0_FBDIV", "66", "QPLL0_REFCLK_DIV", "1", "QPLL0CLKOUT_RATE",
                "FULL");
        nb.inst("ch", Unisim.GTYE4_CHANNEL, "TXOUT_DIV", "1", "TX_DATA_WIDTH", "32", "TX_INT_DATAWIDTH", "1",
                "RX_PROGDIV_CFG", "33.0", "RX_PROGDIV_RATE", "16'b0000000000000001");
        nb.inst("btx", Unisim.BUFG_GT);
        nb.inst("brx", Unisim.BUFG_GT);
        nb.connect("refp", "refp", "ib/I");
        nb.connect("refclk", "ib/O", "cm/GTREFCLK00");
        nb.tie("cm/QPLL0REFCLKSEL", 3, 1);
        nb.connect("qpll0", "cm/QPLL0OUTCLK", "ch/QPLL0CLK");
        nb.tie("ch/TXPLLCLKSEL", 2, 3);
        nb.tie("ch/RXPLLCLKSEL", 2, 3);
        nb.tie("ch/TXOUTCLKSEL", 3, 2);
        nb.tie("ch/RXOUTCLKSEL", 3, 5);
        nb.connect("txoutclk", "ch/TXOUTCLK", "btx/I");
        nb.connect("txoutclkpcs", "ch/TXOUTCLKPCS");
        nb.flop("rpcs", "txoutclkpcs");
        nb.connect("rxoutclk", "ch/RXOUTCLK", "brx/I");
        nb.tie("btx/DIV", 3, 1);
        nb.tie("brx/DIV[1]", 1, 0);
        nb.tie("brx/DIV[2]", 1, 0);
        nb.connect("txusr", "btx/O", "ch/TXUSRCLK");
        nb.connect("rxusr", "brx/O");
        nb.flop("rtx", "txusr");
        nb.flop("rrx", "rxusr");
        nb.connect("rtx_q", "brx/DIV[0]");
        TimingConstraints tc = TimingConstraints.parse(nb.netlist, Arrays.asList(
                "create_clock -period 6.400 -name ref [get_ports refp]",
                "set_case_analysis 1 [get_pins {brx/DIV[0]}]"));
        Assertions.assertEquals(1, tc.getCaseValue("brx/DIV[0]"));
        ClockNetwork cn = ClockNetwork.build(nb.netlist, tc);
        double fpll = 0.15625 * 66;
        double txPeriod = 1000 / (fpll * 2 / 32);
        Clock tx = cn.getClock("txoutclk");
        assertClock(tx, txPeriod, 0, txPeriod / 2, 0);
        Assertions.assertEquals("GT", tx.getKind());
        Assertions.assertSame(cn.getClock("ref"), tx.getMaster());
        // TXOUTCLKPCS: the PCS parallel clock, as the PMA one
        assertClock(cn.getClock("txoutclkpcs"), txPeriod, 0, txPeriod / 2, 0);
        double rxPeriod = 1000 / (fpll / 33);
        assertClock(cn.getClock("rxoutclk"), rxPeriod, 0, rxPeriod / 2, 0);
        // BUFG_GT: divided by DIV + 1
        assertClock(cn.getClock("txusr"), 2 * txPeriod, 0, txPeriod, 0);
        assertClock(cn.getClock("rxusr"), 2 * rxPeriod, 0, rxPeriod, 0);
        Assertions.assertArrayEquals(new int[]{2 * cn.getClock("txusr").getId()}, edgesOf(cn, "rtx/C"));
        // Other clock inputs of the channel are clock pins
        Assertions.assertTrue(cn.getClockPinIndex("ch/TXUSRCLK") >= 0);
        // IBUFDS_GTE4 ODIV2: O/2 with REFCLK_HROW_CK_SEL 2'b01, O with 2'b00
        for (String sel : new String[]{"2'b00", "2'b01"}) {
            NetlistBuilder nb2 = new NetlistBuilder();
            nb2.inst("ib", Unisim.IBUFDS_GTE4, "REFCLK_HROW_CK_SEL", sel);
            nb2.inst("bg", Unisim.BUFG_GT);
            nb2.connect("refp", "refp", "ib/I");
            nb2.connect("refclk", "ib/O");
            nb2.connect("odiv2", "ib/ODIV2", "bg/I");
            nb2.connect("refbufg", "bg/O");
            nb2.flop("r", "refbufg");
            ClockNetwork cn2 = ClockNetwork.build(nb2.netlist, TimingConstraints.parse(nb2.netlist,
                    Arrays.asList("create_clock -period 6.400 -name ref [get_ports refp]")));
            Clock rc = cn2.getClock(edgesOf(cn2, "r/C")[0] >> 1);
            if (sel.endsWith("1")) {
                assertClock(rc, 12800, 0, 6400, 0);
                Assertions.assertSame(cn2.getClock("ref"), rc.getMaster());
            } else {
                Assertions.assertSame(cn2.getClock("ref"), rc);
            }
        }
        // TXOUTCLKSEL 3 and 4: the reference clock, /1 and /2; 1: PMA clock of 2 bytes of 10 bits
        Assertions.assertEquals(0.15625, GtClocks.outputFrequency(10, 0.15625, 1, 0, 0, 32, 1, 3), 1e-12);
        Assertions.assertEquals(0.078125, GtClocks.outputFrequency(10, 0.15625, 1, 0, 0, 32, 1, 4), 1e-12);
        Assertions.assertEquals(10 * 2 / 2.0 / 20, GtClocks.outputFrequency(10, 0.15625, 2, 0, 0, 20, 0, 1), 1e-12);
        // TXOUTCLKSEL 0 (static), 6 or 7: no clock
        Assertions.assertTrue(Double.isNaN(GtClocks.outputFrequency(10, 0.15625, 1, 0, 0, 32, 1, 0)));
        Assertions.assertTrue(Double.isNaN(GtClocks.outputFrequency(10, 0.15625, 1, 0, 0, 32, 1, 6)));
        // CPLL: fref*FBDIV_45*FBDIV/REFCLK_DIV
        Assertions.assertEquals(0.15625 * 5 * 4, GtClocks.pllFrequency(0.15625, false, 4, 5, 1, true), 1e-12);
        Assertions.assertEquals(fpll / 2, GtClocks.pllFrequency(0.15625, true, 66, Double.NaN, 1, false), 1e-12);
    }

    @Test
    public void testVersalGtQuad() {
        // A Versal GT quad states its output frequencies: 156.25 MHz reference, 322.266 MHz TX output
        NetlistBuilder nb = new NetlistBuilder();
        nb.inst("ib", Unisim.IBUFDS_GTE4);
        nb.inst("quad", Unisim.GTYP_QUAD, "CH0_TXOUTCLK_FREQ", "322.266000 MHz", "CH0_TXOUTCLK_REF_FREQ",
                "156.250000 MHz", "CH0_TXOUTCLK_REF_SOURCE", "HSCLK0_LCPLLGTREFCLK0");
        nb.connect("refp", "refp", "ib/I");
        nb.connect("refclk", "ib/O", "quad/HSCLK0_LCPLLGTREFCLK0");
        nb.connect("ch0_txoutclk", "quad/CH0_TXOUTCLK");
        nb.flop("r", "ch0_txoutclk");
        TimingConstraints tc = TimingConstraints.parse(nb.netlist, Arrays.asList(
                "create_clock -period 6.400 -name ref [get_ports refp]"));
        ClockNetwork cn = ClockNetwork.build(nb.netlist, tc);
        double period = 6400 * 156.25 / 322.266;
        assertClock(cn.getClock("ch0_txoutclk"), period, 0, period / 2, 0);
        Assertions.assertEquals(3103, cn.getClock("ch0_txoutclk").getPeriod(), 0.5);
    }

    @Test
    public void testPinAlias() {
        // Vivado's internal clock pins of the processing systems
        Assertions.assertEquals("PMCRCLKCLK[0]", XdcObjectFinder.getPinAlias("PMCRCLKCLKINT[0]"));
        Assertions.assertEquals("PMCRCLKCLK[*]", XdcObjectFinder.getPinAlias("PMCRCLKCLKINT[*]"));
        Assertions.assertNull(XdcObjectFinder.getPinAlias("CLKOUT0"));
    }

    @Test
    public void testUserGeneratedClocks() {
        NetlistBuilder nb = mmcmNetlist();
        // A register divider on clk_b, and two clocks on one port
        nb.inst("div2", Unisim.FDRE);
        nb.inst("bd2", Unisim.BUFG);
        nb.connect("cb", "div2/C");
        nb.connect("d2q", "div2/Q", "bd2/I");
        nb.connect("cd2", "bd2/O");
        nb.flop("rd2", "cd2");
        TimingConstraints tc = TimingConstraints.parse(nb.netlist, Arrays.asList(
                "create_clock -period 10 -name clk_b [get_ports clk_b]",
                "create_clock -period 12 -name clk_b2 -add [get_ports clk_b]",
                "set_clock_groups -logically_exclusive -group [get_clocks -include_generated_clocks clk_b] "
                        + "-group [get_clocks -include_generated_clocks clk_b2]",
                "create_generated_clock -name d2 -source [get_pins bb/O] -divide_by 2 [get_pins div2/Q]",
                "create_generated_clock -name inv8 -source [get_ports clk_b] -master_clock clk_b2 "
                        + "-edges {1 2 3} -edge_shift {0 2 0} [get_pins bd2/O]"));
        ClockNetwork cn = ClockNetwork.build(nb.netlist, tc);
        Clock d2 = cn.getClock("d2");
        assertClock(d2, 20000, 0, 10000, 0);
        Assertions.assertEquals("clk_b", d2.getMaster().getName());
        // A user clock on a pin blocks the clocks propagating to it
        Clock e = cn.getClock("inv8");
        assertClock(e, 12000, 0, 8000, 0);
        Assertions.assertArrayEquals(new int[]{2 * e.getId()}, edgesOf(cn, "rd2/C"));
        // Two clocks on the same port are related
        Assertions.assertTrue(cn.areRelated(cn.getClock("clk_b"), cn.getClock("clk_b2")));
        Assertions.assertArrayEquals(new int[]{2 * cn.getClock("clk_b").getId(), 2 * cn.getClock("clk_b2").getId()},
                edgesOf(cn, "rb/C"));
        // The groups were set before d2 existed: it is not in them (as in Vivado)
        ClockRelations rel = ClockRelations.build(cn);
        int b = cn.getClock("clk_b").getId();
        int b2 = cn.getClock("clk_b2").getId();
        Assertions.assertTrue(rel.isFalseSetup(b, b2));
        Assertions.assertTrue(rel.isFalseHold(b2, b));
        Assertions.assertFalse(rel.isFalseSetup(b, b));
        Assertions.assertFalse(rel.isFalseSetup(b2, d2.getId()));
    }

    @Test
    public void testGeneratedClockEdges() {
        NetlistBuilder nb = mmcmNetlist();
        for (String b : new String[]{"g5", "g5i", "g2", "g4"}) {
            nb.inst(b, Unisim.BUFG);
            nb.connect(b + "_o", b + "/O");
            nb.flop("r" + b, b + "_o");
        }
        nb.connect("cb", "g5/I", "g5i/I", "g2/I", "g4/I");
        TimingConstraints tc = TimingConstraints.parse(nb.netlist, Arrays.asList(
                "create_clock -period 12 -name clk_b [get_ports clk_b]",
                // Master edges at 0, 6, 12, 30, 36 ns: two pulses per 36 ns period
                "create_generated_clock -name e5 -source [get_ports clk_b] -edges {1 2 3 6 7} [get_pins g5/O]",
                "create_generated_clock -name e5i -source [get_ports clk_b] -edges {1 2 3 6 7} -invert "
                        + "[get_pins g5i/O]",
                // Not an odd number (>= 3) of edges: ignored
                "create_generated_clock -name e2 -source [get_ports clk_b] -edges {1 3} [get_pins g2/O]",
                "create_generated_clock -name e4 -source [get_ports clk_b] -edges {1 2 3 4} [get_pins g4/O]",
                "create_generated_clock -name e3 -source [get_ports clk_b] -edges {1 3 2} [get_pins g4/O]",
                "create_generated_clock -name s3 -source [get_ports clk_b] -edges {1 2 3} -edge_shift {0 1} "
                        + "[get_pins g4/O]"));
        Assertions.assertEquals(3, tc.getWarnings().get(
                "create_generated_clock -edges not an odd number (>= 3) of increasing master edges (ignored)"));
        Assertions.assertEquals(1, tc.getWarnings().get(
                "create_generated_clock -edge_shift without one shift per -edges edge (ignored)"));
        Assertions.assertEquals(2, tc.getGeneratedClocks().size());
        ClockNetwork cn = ClockNetwork.build(nb.netlist, tc);
        Clock e5 = cn.getClock("e5");
        Assertions.assertEquals(36000, e5.getPeriod(), 1e-6);
        Assertions.assertArrayEquals(new double[]{0, 6000, 12000, 30000}, e5.getWaveform(), 1e-6);
        Clock e5i = cn.getClock("e5i");
        Assertions.assertArrayEquals(new double[]{6000, 12000, 30000, 36000}, e5i.getWaveform(), 1e-6);
        Assertions.assertNull(cn.getClock("e2"));
        Assertions.assertNull(cn.getClock("e4"));
        // The clock-edge relations of a multi-pulse clock: e5 -> e5 rising edges 12 ns apart at worst
        ClockRelations rel = ClockRelations.build(cn);
        Assertions.assertEquals(12000, rel.getSetup(2 * e5.getId(), 2 * e5.getId()), 1e-6);
    }

    /**
     * The programmable inversions of clock buffer and divider inputs (IS_I_INVERTED, IS_I0_INVERTED): their
     * outputs carry the falling edges of their input clock. Vivado 2026.1 (xcvc1902): setup requirement 5 ns from
     * a rising-edge flop to a flop behind an inverting BUFGCE or BUFGCTRL, 10 ns to a falling-edge flop behind
     * the BUFGCE; a BUFGCE_DIV /2 with an inverted input derives {5 15} (20 ns); an inverting MBUFGCE {5 10}
     * (10 ns) on O1 and {5 15} (20 ns) on O2.
     */
    @Test
    public void testInvertedBufferInputs() {
        NetlistBuilder nb = new NetlistBuilder();
        nb.inst("ib", Unisim.IBUF);
        nb.inst("bg", Unisim.BUFG);
        nb.inst("binv", Unisim.BUFGCE, "IS_I_INVERTED", "1'b1");
        nb.inst("bdiv", Unisim.BUFGCE_DIV, "BUFGCE_DIVIDE", "2", "IS_I_INVERTED", "1'b1");
        nb.inst("bctrl", Unisim.BUFGCTRL, "IS_I0_INVERTED", "1'b1");
        nb.inst("mb", Unisim.MBUFGCE, "IS_I_INVERTED", "1'b1");
        nb.connect("clk", "clk", "ib/I");
        nb.connect("ci", "ib/O", "bg/I", "binv/I", "bdiv/I", "bctrl/I0", "mb/I");
        nb.connect("cg", "bg/O");
        nb.connect("c_inv", "binv/O");
        nb.connect("c_div", "bdiv/O");
        nb.connect("c_ctrl", "bctrl/O");
        nb.connect("mo1", "mb/O1");
        nb.connect("mo2", "mb/O2");
        nb.flop("r_plain", "cg");
        nb.flop("r_inv", "c_inv");
        nb.flop("r_invf", "c_inv", "IS_C_INVERTED", "1'b1");
        nb.flop("r_div", "c_div");
        nb.flop("r_ctrl", "c_ctrl");
        nb.flop("r_mo1", "mo1");
        nb.flop("r_mo2", "mo2");
        TimingConstraints tc = TimingConstraints.parse(nb.netlist, Arrays.asList(
                "create_clock -period 10 -name clk [get_ports clk]"));
        ClockNetwork cn = ClockNetwork.build(nb.netlist, tc);
        int clk = cn.getClock("clk").getId();
        Assertions.assertArrayEquals(new int[]{2 * clk}, edgesOf(cn, "r_plain/C"));
        Assertions.assertArrayEquals(new int[]{2 * clk + 1}, edgesOf(cn, "r_inv/C"));
        Assertions.assertArrayEquals(new int[]{2 * clk}, edgesOf(cn, "r_invf/C"));
        Assertions.assertArrayEquals(new int[]{2 * clk + 1}, edgesOf(cn, "r_ctrl/C"));
        assertClock(cn.getClock("c_div"), 20000, 5000, 15000, 0);
        assertClock(cn.getClock("mo1"), 10000, 5000, 10000, 0);
        assertClock(cn.getClock("mo2"), 20000, 5000, 15000, 0);
        ClockRelations rel = ClockRelations.build(cn);
        Assertions.assertEquals(5000, rel.getSetup(2 * clk, edgesOf(cn, "r_inv/C")[0]), 1e-6);
        Assertions.assertEquals(10000, rel.getSetup(2 * clk, edgesOf(cn, "r_invf/C")[0]), 1e-6);
        Assertions.assertEquals(5000, rel.getSetup(2 * clk, edgesOf(cn, "r_ctrl/C")[0]), 1e-6);
        Assertions.assertEquals(5000, rel.getSetup(2 * clk, edgesOf(cn, "r_div/C")[0]), 1e-6);
    }

    /**
     * Clock generators: an inverted input (IS_CLKIN1_INVERTED) shifts the outputs to the input's falling edges
     * (Vivado 2026.1, xcvc1902 MMCME5: {5 10} for a 10 ns output of a 10 ns input); the default phase shift mode
     * of the UltraScale MMCM is WAVEFORM (xcku040 MMCME3_ADV: CLKOUT1 at 90 degrees of 10 ns {2.5 7.5}), that of
     * later ones LATENCY; with CLKOUT4_CASCADE the CLKOUT6 divider feeds the CLKOUT4 divider.
     */
    @Test
    public void testGeneratorAttributes() {
        NetlistBuilder nb = new NetlistBuilder();
        nb.inst("ib", Unisim.IBUF);
        nb.inst("bg", Unisim.BUFG);
        nb.connect("clk", "clk", "ib/I");
        nb.connect("ci", "ib/O", "bg/I");
        List<String> clkin = new ArrayList<>();
        String[][] mmcms = {
            {"m_inv", "MMCME4_ADV", "IS_CLKIN1_INVERTED", "1'b1"},
            {"m_us", "MMCME3_ADV", "CLKOUT1_PHASE", "90.0"},
            {"m_usp", "MMCME4_ADV", "CLKOUT1_PHASE", "90.0"},
            {"m_wave", "MMCME4_ADV", "PHASESHIFT_MODE", "WAVEFORM"},
            {"m_casc", "MMCME4_ADV", "CLKOUT4_CASCADE", "TRUE"},
        };
        for (String[] m : mmcms) {
            EDIFCellInst ci = nb.inst(m[0], Unisim.valueOf(m[1]), "CLKFBOUT_MULT_F", "10.0", "DIVCLK_DIVIDE", "1",
                    "COMPENSATION", "INTERNAL", "CLKOUT0_DIVIDE_F", "10.0", "CLKOUT1_DIVIDE", "10",
                    "CLKOUT4_DIVIDE", "4", "CLKOUT6_DIVIDE", "10", m[2], m[3]);
            if (m[0].equals("m_wave")) {
                ci.addProperty("CLKOUT1_PHASE", "90.0");
            }
            clkin.add(m[0] + "/CLKIN1");
            nb.connect(m[0] + "_fb", m[0] + "/CLKFBOUT", m[0] + "/CLKFBIN");
            for (String out : new String[]{"CLKOUT0", "CLKOUT1", "CLKOUT4"}) {
                nb.connect(m[0] + "_" + out, m[0] + "/" + out);
                nb.flop("r_" + m[0] + "_" + out, m[0] + "_" + out);
            }
        }
        nb.connect("cg", clkin.toArray(new String[0]));
        nb.pin("cg", "bg/O");
        TimingConstraints tc = TimingConstraints.parse(nb.netlist, Arrays.asList(
                "create_clock -period 10 -name clk [get_ports clk]"));
        ClockNetwork cn = ClockNetwork.build(nb.netlist, tc);
        Assertions.assertTrue(cn.getWarnings().isEmpty(), cn.getWarnings().toString());
        assertClock(cn.getClock("m_inv_CLKOUT0"), 10000, 5000, 10000, 0);
        assertClock(cn.getClock("m_us_CLKOUT1"), 10000, 2500, 7500, 0);
        assertClock(cn.getClock("m_usp_CLKOUT1"), 10000, 0, 5000, 2500);
        assertClock(cn.getClock("m_wave_CLKOUT1"), 10000, 2500, 7500, 0);
        assertClock(cn.getClock("m_usp_CLKOUT4"), 4000, 0, 2000, 0);
        assertClock(cn.getClock("m_casc_CLKOUT4"), 40000, 0, 20000, 0);
    }

    /**
     * The outputs O1 to O4 of an MBUFGCE_DIV divide by BUFGCE_DIVIDE, 2*BUFGCE_DIVIDE, ..., those of an MBUFG_GT by
     * DIV + 1, 2*(DIV + 1), ... (Vivado 2026.1, xcvc1902: 20, 40, 80 and 160 ns from a 10 ns clock, with
     * BUFGCE_DIVIDE 2 and DIV 3'b001; 40 to 320 ns with DIV 3'b011).
     */
    @Test
    public void testMultiOutputDividers() {
        NetlistBuilder nb = new NetlistBuilder();
        nb.inst("md", Unisim.MBUFGCE_DIV, "BUFGCE_DIVIDE", "2");
        nb.inst("mg", Unisim.MBUFG_GT);
        nb.tie("mg/DIV", 3, 1);
        nb.connect("clk", "clk", "md/I", "mg/I");
        for (int o = 1; o <= 4; o++) {
            for (String b : new String[]{"md", "mg"}) {
                nb.connect(b + o, b + "/O" + o);
                nb.flop("r_" + b + o, b + o);
            }
        }
        TimingConstraints tc = TimingConstraints.parse(nb.netlist, Arrays.asList(
                "create_clock -period 10 -name clk [get_ports clk]"));
        ClockNetwork cn = ClockNetwork.build(nb.netlist, tc);
        for (int o = 1; o <= 4; o++) {
            double period = 10000 * 2 * (1 << (o - 1));
            assertClock(cn.getClock("md" + o), period, 0, period / 2, 0);
            assertClock(cn.getClock("mg" + o), period, 0, period / 2, 0);
        }

        // An odd divide: high for (N-1)/2 periods of the master (Vivado 2026.1, xcvu3p and xcvc1902: a BUFGCE_DIV
        // /3 of a 10 ns clock derives {0 10} (30 ns); an MBUFGCE_DIV with BUFGCE_DIVIDE 3 {0 10}, {0 30}, ...)
        nb = new NetlistBuilder();
        nb.inst("d3", Unisim.BUFGCE_DIV, "BUFGCE_DIVIDE", "3");
        nb.inst("md", Unisim.MBUFGCE_DIV, "BUFGCE_DIVIDE", "3");
        nb.connect("clk", "clk", "d3/I", "md/I");
        nb.connect("c3", "d3/O");
        nb.flop("r3", "c3");
        nb.connect("md1", "md/O1");
        nb.flop("rmd1", "md1");
        nb.connect("md2", "md/O2");
        nb.flop("rmd2", "md2");
        cn = ClockNetwork.build(nb.netlist, TimingConstraints.parse(nb.netlist, Arrays.asList(
                "create_clock -period 10 -name clk [get_ports clk]")));
        assertClock(cn.getClock("c3"), 30000, 0, 10000, 0);
        assertClock(cn.getClock("md1"), 30000, 0, 10000, 0);
        assertClock(cn.getClock("md2"), 60000, 0, 30000, 0);
    }

    /**
     * A latch gate reached by a clock is not a clock pin: one warning per latch. A pin of a cell with several
     * clock pins that matches none of them is timed with the first one, with a warning per cell type and pin.
     */
    @Test
    public void testLatchAndFallbackClockPinWarnings() {
        NetlistBuilder nb = new NetlistBuilder();
        nb.inst("lat", Unisim.LDCE);
        nb.inst("ram", Unisim.RAMB36E2);
        nb.connect("clk", "clk", "lat/G", "ram/CLKARDCLK", "ram/CLKBWRCLK");
        nb.connect("lq", "lat/Q", "lat/D");
        TimingConstraints tc = TimingConstraints.parse(nb.netlist, Arrays.asList(
                "create_clock -period 10 -name clk [get_ports clk]"));
        ClockNetwork cn = ClockNetwork.build(nb.netlist, tc);
        Assertions.assertEquals(-1, cn.getClockPinIndex("lat/G"));
        Assertions.assertEquals(1, cn.getWarnings().get("latch gate reached by a clock (latch paths are not timed): lat"));
        Assertions.assertEquals(cn.getClockPinIndex("ram/CLKARDCLK"), cn.getClockPinOf("ram", "ADDRARDADDR[3]"));
        Assertions.assertTrue(cn.getWarnings().keySet().stream().noneMatch(w -> w.startsWith("pin of a cell")));
        int first = cn.getClockPinOf("ram", "SLEEP");
        cn.getClockPinOf("ram", "SLEEP");
        Assertions.assertTrue(first >= 0);
        Assertions.assertEquals(2, cn.getWarnings().get("pin of a cell with several clock pins timed with its first "
                + "clock pin (" + cn.getClockPin(first).getPortInst().getName() + "): RAMB36E2/SLEEP"));
    }

    /**
     * Vivado 2026.1 overrides earlier primary clocks per source ("Clock 'cka' partially overrides clock 'ckab'":
     * ckab stays on clk_b) and by name ("A clock with name 'ck' already exists, overwriting the previous clock").
     */
    @Test
    public void testPrimaryClockOverrides() {
        NetlistBuilder nb = mmcmNetlist();
        TimingConstraints tc = TimingConstraints.parse(nb.netlist, Arrays.asList(
                "create_clock -period 10 -name ckab [get_ports {clk_a clk_b}]",
                "create_clock -period 6 -name cka [get_ports clk_a]"));
        ClockNetwork cn = ClockNetwork.build(nb.netlist, tc);
        Clock ab = cn.getClock("ckab");
        Assertions.assertNotNull(ab);
        Assertions.assertEquals(Collections.singletonList("port:clk_b"), ab.getSources());
        Assertions.assertArrayEquals(new int[]{2 * ab.getId()}, edgesOf(cn, "rb/C"));
        Assertions.assertEquals(6000, cn.getClock("cka").getPeriod(), 1e-6);

        tc = TimingConstraints.parse(nb.netlist, Arrays.asList(
                "create_clock -period 10 -name ck [get_ports clk_a]",
                "create_clock -period 7 -name ck [get_ports clk_b]"));
        cn = ClockNetwork.build(nb.netlist, tc);
        Assertions.assertEquals(1, cn.getClocks().stream().filter(c -> c.getName().equals("ck")).count());
        Assertions.assertEquals(7000, cn.getClock("ck").getPeriod(), 1e-6);
        Assertions.assertArrayEquals(new int[]{2 * cn.getClock("ck").getId()}, edgesOf(cn, "rb/C"));
        Assertions.assertEquals(-1, cn.getClockPinIndex("r0/C"));
        Assertions.assertTrue(cn.getWarnings().keySet().stream().noneMatch(w -> w.startsWith("duplicate")));

        // A clock on a pin redefined by name on the port upstream: the pin does not block the port's clock
        tc = TimingConstraints.parse(nb.netlist, Arrays.asList(
                "create_clock -period 10 -name ck [get_pins ib_b/O]",
                "create_clock -period 7 -name ck [get_ports clk_b]"));
        cn = ClockNetwork.build(nb.netlist, tc);
        Assertions.assertArrayEquals(new int[]{2 * cn.getClock("ck").getId()}, edgesOf(cn, "rb/C"));
    }

    /**
     * A clock has one of the kinds of {@link Clock#KINDS}: setting another one (or none) is refused and leaves the
     * kind unchanged, so that a new kind cannot be assigned without being listed there.
     */
    @Test
    public void testKindsOfClocks() {
        Clock c = Clock.createPrimary(0, "c", 4000, new double[]{0, 2000}, "port");
        Assertions.assertEquals("PORT", c.getKind());
        for (String kind : Clock.KINDS) {
            c.setKind(kind);
            Assertions.assertEquals(kind, c.getKind());
        }
        Assertions.assertThrows(IllegalArgumentException.class, () -> c.setKind("NO_SUCH_KIND"));
        Assertions.assertThrows(IllegalArgumentException.class, () -> c.setKind(null));
        Assertions.assertEquals(Clock.KINDS.get(Clock.KINDS.size() - 1), c.getKind());
    }

    /** The attributes of an XPHY nibble whose read clock is derived (DQS_SRC LOCAL, RX_DATA_WIDTH 8, ...) */
    private static final String[] XPHY_READ_CLOCK_ATTRIBUTES = {"SERIAL_MODE", "FALSE", "INV_RXCLK", "FALSE",
            "RX_DATA_WIDTH", "8", "EN_OTHER_PCLK", "FALSE", "EN_OTHER_NCLK", "FALSE", "DQS_SRC", "LOCAL",
            "FIFO_MODE_0", "BYPASS", "FIFO_MODE_1", "BYPASS", "FIFO_MODE_2", "BYPASS", "FIFO_MODE_3", "BYPASS",
            "FIFO_MODE_4", "BYPASS", "FIFO_MODE_5", "BYPASS"};

    /**
     * A strobe port (through an INV if inverted) to the DATAIN[0] of an XPHY, whose FIFO_WR_CLK clocks a flip-flop
     * through a BUFGCE and drives an XPLL (CLKOUT0 = CLKIN x 8 / 8) clocking another one.
     * @param attributes Changes to the XPHY's attributes {@link #XPHY_READ_CLOCK_ATTRIBUTES} (name, value, ...; a
     * null value removes the attribute).
     */
    private static NetlistBuilder xphyNetlist(boolean inverted, String... attributes) {
        Map<String, String> attrs = new LinkedHashMap<>();
        for (int i = 0; i + 1 < XPHY_READ_CLOCK_ATTRIBUTES.length; i += 2) {
            attrs.put(XPHY_READ_CLOCK_ATTRIBUTES[i], XPHY_READ_CLOCK_ATTRIBUTES[i + 1]);
        }
        for (int i = 0; i + 1 < attributes.length; i += 2) {
            if (attributes[i + 1] == null) {
                attrs.remove(attributes[i]);
            } else {
                attrs.put(attributes[i], attributes[i + 1]);
            }
        }
        List<String> props = new ArrayList<>();
        for (Map.Entry<String, String> e : attrs.entrySet()) {
            props.add(e.getKey());
            props.add(e.getValue());
        }
        NetlistBuilder nb = new NetlistBuilder();
        nb.inst("x", Unisim.XPHY, props.toArray(new String[0]));
        nb.inst("bg", Unisim.BUFGCE);
        nb.inst("pll", Unisim.XPLL, "CLKFBOUT_MULT", "8", "DIVCLK_DIVIDE", "1", "CLKOUT0_DIVIDE", "8");
        if (inverted) {
            nb.inst("inv", Unisim.INV);
            nb.connect("dqs", "dqs", "inv/I");
            nb.connect("dqs_n", "inv/O", "x/DATAIN[0]");
        } else {
            nb.connect("dqs", "dqs", "x/DATAIN[0]");
        }
        nb.connect("rclk", "x/FIFO_WR_CLK", "bg/I", "pll/CLKIN");
        nb.connect("rclk_g", "bg/O");
        nb.flop("r", "rclk_g");
        nb.connect("pclk", "pll/CLKOUT0");
        nb.flop("rp", "pclk");
        return nb;
    }

    /** The clock network of a netlist with a 2.5 ns clock on its port dqs and further constraints */
    private static ClockNetwork xphyNetwork(NetlistBuilder nb, String... xdc) {
        List<String> lines = new ArrayList<>();
        lines.add("create_clock -period 2.5 -name dqs [get_ports dqs]");
        lines.addAll(Arrays.asList(xdc));
        return ClockNetwork.build(nb.netlist, TimingConstraints.parse(nb.netlist, lines));
    }

    /**
     * The read clock of a Versal XPHY nibble: Vivado 2026.1 (xcvp1902) derives it from the strobe's clock (here a
     * 2.5 ns port clock) on the XPHY's internal pin RX_DQS_DIV4_0, which reaches FIFO_WR_CLK: master edges
     * {3 8 11} with edge shifts {1.25 0 1.25}, i.e. 10 ns, {3.75 8.75}, named &lt;cell&gt;/RX_DQS_DIV4_0. The
     * clocks of an XPLL behind FIFO_WR_CLK are derived from it.
     */
    @Test
    public void testXphyReadClock() {
        ClockNetwork cn = xphyNetwork(xphyNetlist(false));
        Clock dqs = cn.getClock("dqs");
        Clock rd = cn.getClock("x/RX_DQS_DIV4_0");
        assertClock(rd, 10000, 3750, 8750, 0);
        Assertions.assertSame(dqs, rd.getMaster());
        Assertions.assertTrue(rd.isAutoDerived());
        Assertions.assertTrue(rd.isGenerated());
        Assertions.assertEquals("XPHY", rd.getGeneratorType());
        // The XPHY is no clock generator: the read clock has its master's kind
        Assertions.assertEquals("PORT", dqs.getKind());
        Assertions.assertEquals("PORT", rd.getKind());
        Assertions.assertEquals(Collections.singletonList("x/FIFO_WR_CLK"), rd.getSources());
        Assertions.assertEquals(Collections.singletonList(2 * rd.getId()), cn.getPinClockEdges("x/FIFO_WR_CLK"));
        Assertions.assertArrayEquals(new int[]{2 * rd.getId()}, edgesOf(cn, "r/C"));
        Assertions.assertTrue(cn.areRelated(dqs, rd));
        // The stage of FIFO_WR_CLK is below the strobe's stage, through DATAIN[0] (a sink of it)
        ClockNetwork.Stage st = cn.getStages().get(
                cn.getStages().get(cn.getClockPinStage(cn.getClockPinIndex("r/C"))).getParentStage());
        Assertions.assertEquals(ClockNetwork.StageKind.DIVIDER, st.getKind());
        Assertions.assertEquals("x/FIFO_WR_CLK", st.getDriver().toString());
        Assertions.assertEquals("x/DATAIN[0]", st.getParentPin().toString());
        Assertions.assertEquals(rd.getId(), st.getDerivedClock());
        Assertions.assertTrue(cn.getStages().get(st.getParentStage()).getSinks().stream()
                .anyMatch(p -> p.toString().equals("x/DATAIN[0]")));
        // The XPLL behind it
        Clock pclk = cn.getClock("pclk");
        Assertions.assertNotNull(pclk);
        Assertions.assertSame(rd, pclk.getMaster());
        Assertions.assertEquals(10000, pclk.getPeriod(), 1e-6);
        Assertions.assertEquals("PLL", pclk.getKind());
        Assertions.assertArrayEquals(new int[]{2 * pclk.getId()}, edgesOf(cn, "rp/C"));
        Assertions.assertTrue(cn.getWarnings().keySet().stream().noneMatch(w -> w.contains("XPHY")));

        // A 1 ns strobe: 4 ns, {1.5 3.5}; an RX_DATA_WIDTH written as a Verilog literal
        NetlistBuilder nb = xphyNetlist(false, "RX_DATA_WIDTH", "4'd8");
        cn = ClockNetwork.build(nb.netlist, TimingConstraints.parse(nb.netlist, Arrays.asList(
                "create_clock -period 1 -name dqs [get_ports dqs]")));
        assertClock(cn.getClock("x/RX_DQS_DIV4_0"), 4000, 1500, 3500, 0);

        // Two clocks on the strobe: a read clock each, the second one's name made unique
        cn = xphyNetwork(xphyNetlist(false), "create_clock -period 2 -name dqs2 -add [get_ports dqs]");
        assertClock(cn.getClock("x/RX_DQS_DIV4_0"), 10000, 3750, 8750, 0);
        assertClock(cn.getClock("x/RX_DQS_DIV4_0_1"), 8000, 3000, 7000, 0);
        Assertions.assertEquals("dqs2", cn.getClock("x/RX_DQS_DIV4_0_1").getMaster().getName());

        // A FIFO_WR_CLK without loads: no clock
        nb = new NetlistBuilder();
        nb.inst("x", Unisim.XPHY, XPHY_READ_CLOCK_ATTRIBUTES);
        nb.connect("dqs", "dqs", "x/DATAIN[0]");
        nb.connect("rclk", "x/FIFO_WR_CLK");
        cn = xphyNetwork(nb);
        Assertions.assertEquals(1, cn.getNumClocks());
        Assertions.assertTrue(cn.getWarnings().keySet().stream().noneMatch(w -> w.contains("XPHY")));
    }

    /**
     * The configurations of an XPHY whose read clock is not derived (not checked against Vivado), with a warning:
     * serial mode, an inverted RX clock, an RX_DATA_WIDTH other than 8, inter-nibble clocking, a read FIFO mode other
     * than BYPASS (or absent), the strobe of another nibble (DQS_SRC EXTERN, or absent), an inverted strobe clock.
     */
    @Test
    public void testXphyReadClockNotDerived() {
        String[][] cases = {
                {"SERIAL_MODE", "TRUE"},
                {"INV_RXCLK", "TRUE"},
                {"RX_DATA_WIDTH", "4"},
                {"RX_DATA_WIDTH", "x"},
                {"EN_OTHER_PCLK", "TRUE"},
                {"EN_OTHER_NCLK", "TRUE"},
                {"FIFO_MODE_0", "ASYNC"},
                {"FIFO_MODE_3", null},
                {"DQS_SRC", "EXTERN"},
                {"DQS_SRC", null},
        };
        for (String[] c : cases) {
            ClockNetwork cn = xphyNetwork(xphyNetlist(false, c[0], c[1]));
            String what = c[0] + " " + c[1];
            Assertions.assertNull(cn.getClock("x/RX_DQS_DIV4_0"), what);
            Assertions.assertEquals(-1, cn.getClockPinIndex("r/C"), what);
            Assertions.assertEquals(-1, cn.getClockPinIndex("rp/C"), what);
            Assertions.assertEquals(1, cn.getWarnings().get("XPHY read clock not derived, configuration not "
                    + "supported (" + what + "): x"), what);
        }
        // An absent attribute with a supported default
        for (String a : new String[]{"SERIAL_MODE", "INV_RXCLK", "RX_DATA_WIDTH", "EN_OTHER_PCLK", "EN_OTHER_NCLK"}) {
            Assertions.assertNotNull(xphyNetwork(xphyNetlist(false, a, null)).getClock("x/RX_DQS_DIV4_0"), a);
        }
        // An inverted strobe clock
        ClockNetwork cn = xphyNetwork(xphyNetlist(true));
        Assertions.assertNull(cn.getClock("x/RX_DQS_DIV4_0"));
        Assertions.assertEquals(-1, cn.getClockPinIndex("r/C"));
        Assertions.assertEquals(1, cn.getWarnings().get(
                "XPHY strobe reached by an inverted clock only (no read clock derived): x"));
    }

    /**
     * A clock defined on FIFO_WR_CLK by the constraints replaces the read clock. One defined on the internal pin
     * RX_DQS_DIV4_0 (as Vivado allows) is not in the netlist: the constraints drop it, and the read clock is derived
     * with the same period and waveform, under its default name.
     */
    @Test
    public void testXphyReadClockDefinedByConstraints() {
        ClockNetwork cn = xphyNetwork(xphyNetlist(false),
                "create_generated_clock -name rd -source [get_ports dqs] -divide_by 2 [get_pins x/FIFO_WR_CLK]");
        Assertions.assertNull(cn.getClock("x/RX_DQS_DIV4_0"));
        Clock rd = cn.getClock("rd");
        assertClock(rd, 5000, 0, 2500, 0);
        Assertions.assertArrayEquals(new int[]{2 * rd.getId()}, edgesOf(cn, "r/C"));
        Assertions.assertSame(rd, cn.getClock("pclk").getMaster());

        cn = xphyNetwork(xphyNetlist(false), "create_clock -period 3 -name rd [get_pins x/FIFO_WR_CLK]");
        Assertions.assertNull(cn.getClock("x/RX_DQS_DIV4_0"));
        Assertions.assertArrayEquals(new int[]{2 * cn.getClock("rd").getId()}, edgesOf(cn, "r/C"));

        NetlistBuilder nb = xphyNetlist(false);
        TimingConstraints tc = TimingConstraints.parse(nb.netlist, Arrays.asList(
                "create_clock -period 2.5 -name dqs [get_ports dqs]",
                "create_generated_clock -name rdq -source [get_ports dqs] -edges {3 8 11} -edge_shift {1.25 0 1.25} "
                        + "[get_pins x/RX_DQS_DIV4_0]"));
        Assertions.assertEquals(1, tc.getWarnings().get("create_generated_clock without objects (ignored)"));
        cn = ClockNetwork.build(nb.netlist, tc);
        Assertions.assertNull(cn.getClock("rdq"));
        assertClock(cn.getClock("x/RX_DQS_DIV4_0"), 10000, 3750, 8750, 0);
    }
}

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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import com.xilinx.rapidwright.design.Design;
import com.xilinx.rapidwright.design.Unisim;
import com.xilinx.rapidwright.edif.EDIFCell;
import com.xilinx.rapidwright.edif.EDIFCellInst;
import com.xilinx.rapidwright.edif.EDIFDirection;
import com.xilinx.rapidwright.edif.EDIFNet;
import com.xilinx.rapidwright.edif.EDIFNetlist;
import com.xilinx.rapidwright.edif.EDIFTools;

public class TestTimingConstraints {

    private static TimingConstraints parse(String... lines) {
        return TimingConstraints.parse((EDIFNetlist) null, Arrays.asList(lines));
    }

    @Test
    public void testCreateClock() {
        TimingConstraints tc = parse(
                "# a comment",
                "create_clock -period 8.000 -name clk_a [get_ports clk_a]",
                "create_clock -name clk_b -period 10 -waveform {1.000 6.000} [get_ports {clk_b}]; "
                        + "create_clock -period 12 -name clk_b2 -add [get_ports clk_b]",
                "create_clock -period 5.000 -name vclk",
                "create_clock -period 4 \\",
                "    [get_ports clk_c]");
        List<TimingConstraints.ClockDef> c = tc.getClocks();
        Assertions.assertEquals(5, c.size());
        Assertions.assertEquals("clk_a", c.get(0).name);
        Assertions.assertEquals(8.0, c.get(0).period);
        Assertions.assertArrayEquals(new double[]{0, 4}, c.get(0).waveform);
        Assertions.assertEquals(XdcObject.Kind.PORT, c.get(0).sources.get(0).getKind());
        Assertions.assertEquals("clk_a", c.get(0).sources.get(0).getName());
        Assertions.assertArrayEquals(new double[]{1, 6}, c.get(1).waveform);
        Assertions.assertFalse(c.get(1).add);
        Assertions.assertTrue(c.get(2).add);
        Assertions.assertTrue(c.get(3).sources.isEmpty());
        // Named after its source when -name is missing; continued line
        Assertions.assertEquals("clk_c", c.get(4).name);
        Assertions.assertEquals(4.0, c.get(4).period);
        Assertions.assertTrue(tc.getWarnings().isEmpty(), tc.getWarnings().toString());
    }

    /** expr: + - * /, parentheses, unary minus, variables inside braces; invalid periods and waveforms */
    @Test
    public void testExprAndClockValidation() {
        TimingConstraints tc = parse(
                "set p 10",
                "create_clock -period [expr 10 - 5] -name a",
                "create_clock -period [expr {$p - 2 * 3}] -name b",
                "create_clock -period [expr {(${p} + 2) / -(-4)}] -name c",
                "create_clock -period [expr 2.5e1 * 0.2] -name d -waveform [list 0 [expr {$p / 4}]]",
                "create_clock -period [expr 10 / 0] -name bad1",
                "create_clock -period [expr {$p - }] -name bad2",
                "create_clock -period 0 -name bad3",
                "create_clock -period 10 -name bad4 -waveform {6 2}",
                "create_clock -period 10 -name bad5 -waveform {0 5 7}",
                "create_clock -period 10 -name bad6 -waveform {0 10}");
        List<TimingConstraints.ClockDef> c = tc.getClocks();
        Assertions.assertEquals(4, c.size());
        Assertions.assertEquals(5.0, c.get(0).period, 1e-9);
        Assertions.assertEquals(4.0, c.get(1).period, 1e-9);
        Assertions.assertEquals(3.0, c.get(2).period, 1e-9);
        Assertions.assertEquals(5.0, c.get(3).period, 1e-9);
        Assertions.assertArrayEquals(new double[]{0, 2.5}, c.get(3).waveform, 1e-9);
        Assertions.assertEquals(2,
                tc.getWarnings().get("create_clock with an expr that cannot be evaluated (ignored)"));
        Assertions.assertEquals(1, tc.getWarnings().get("create_clock with a period that is not positive (ignored)"));
        Assertions.assertEquals(3, tc.getWarnings().get("create_clock with an invalid waveform (ignored)"));
    }

    @Test
    public void testGeneratedClock() {
        TimingConstraints tc = parse(
                "create_generated_clock -name div2 -source [get_pins bcc/O] -divide_by 2 [get_pins div2_reg/Q]",
                "create_generated_clock -name e -source [get_pins a/I] -master_clock [get_clocks m] "
                        + "-edges {1 3 5} -edge_shift {0 0.5 0} -invert [get_pins a/O]",
                "create_generated_clock -name ren [get_pins mmcm/CLKOUT0]");
        List<TimingConstraints.GeneratedClockDef> g = tc.getGeneratedClocks();
        Assertions.assertEquals(3, g.size());
        Assertions.assertEquals("div2", g.get(0).name);
        Assertions.assertEquals("bcc/O", g.get(0).source.getName());
        Assertions.assertEquals(2, g.get(0).divideBy);
        Assertions.assertEquals("div2_reg/Q", g.get(0).targets.get(0).getName());
        Assertions.assertFalse(g.get(0).isRename());
        Assertions.assertEquals("m", g.get(1).masterClock);
        Assertions.assertArrayEquals(new int[]{1, 3, 5}, g.get(1).edges);
        Assertions.assertArrayEquals(new double[]{0, 0.5, 0}, g.get(1).edgeShift);
        Assertions.assertTrue(g.get(1).invert);
        Assertions.assertTrue(g.get(2).isRename());
    }

    /**
     * set_max_delay -datapath_only: Vivado does not check hold on the paths it covers (UG903), read as a false path for
     * hold only; a set_max_delay without -datapath_only leaves hold timed (no exception), and one without -from or
     * with an edge-specific option is ignored
     */
    @Test
    public void testMaxDelayDatapathOnlyIsHoldFalsePath() {
        TimingConstraints tc = parse(
                "set_max_delay -datapath_only -from [get_clocks a] -to [get_clocks b] 4.000",
                "set_max_delay -from [get_cells s_reg] -to [get_cells d_reg] 2.0",
                "set_max_delay -datapath_only -from [get_cells s_reg] -through [get_pins x/I] 3.0 -quiet",
                "set_max_delay -datapath_only -to [get_cells d_reg] 3.0",
                "set_max_delay -datapath_only -rise_from [get_cells s_reg] 3.0");
        List<TimingConstraints.PathExceptionDef> ex = tc.getExceptions();
        Assertions.assertEquals(2, ex.size());
        for (TimingConstraints.PathExceptionDef e : ex) {
            Assertions.assertEquals(TimingConstraints.ExceptionKind.FALSE_PATH, e.kind);
            Assertions.assertFalse(e.setup);
            Assertions.assertTrue(e.hold);
        }
        Assertions.assertEquals("a", ex.get(0).from.get(0).getName());
        Assertions.assertEquals("b", ex.get(0).to.get(0).getName());
        Assertions.assertTrue(ClockRelations.isClockOnly(ex.get(0)));
        Assertions.assertEquals("s_reg", ex.get(1).from.get(0).getName());
        Assertions.assertNull(ex.get(1).to);
        Assertions.assertEquals(1, ex.get(1).through.size());
        Map<String, Integer> w = tc.getWarnings();
        Assertions.assertEquals(2, w.get("set_max_delay -datapath_only (maximum delay not modeled; read as a false path"
                + " for hold)"));
        Assertions.assertEquals(1, w.get("set_max_delay (not supported, ignored)"));
        Assertions.assertEquals(1, w.get("set_max_delay -datapath_only without -from (ignored)"));
        Assertions.assertEquals(1, w.get("set_max_delay -rise_from (constraint ignored)"));
        Assertions.assertEquals(4, w.size(), w.toString());

        // Between two clocks: hold is false, setup still timed
        tc = parse("create_clock -period 4 -name a [get_ports a]",
                "create_clock -period 6 -name b [get_ports b]",
                "set_max_delay -datapath_only -from [get_clocks a] -to [get_clocks b] 4.000");
        ClockNetwork cn = ClockNetwork.build((EDIFNetlist) null, tc);
        ClockRelations rel = ClockRelations.build(cn);
        rel.setUnrelatedFalse(false);
        int a = cn.getClock("a").getId();
        int b = cn.getClock("b").getId();
        Assertions.assertTrue(rel.isFalseHold(a, b));
        Assertions.assertFalse(rel.isFalseSetup(a, b));
        Assertions.assertFalse(rel.isFalseHold(b, a));
    }

    /**
     * Ports clk and din. clk -> IBUF ib -> BUFG bg -> the clock of the flip-flops a_reg, b_reg, c_reg, e_reg, dst_reg,
     * dst2_reg, of the DSP dsp and of sub/s_reg and sub/d_reg (in the hierarchical cell sub, through its input clk_in;
     * sub/d_reg/D = sub/s_reg/Q, which also leaves sub through the output q). dst_reg/D = LUT3 lut (I0 a_reg/Q,
     * I1 sub/q, I2 GND); dst2_reg/D = LUT1 buf_lut (I0 din); a_reg/D = din; c_reg/D = CARRY8 cy/CO[0] (S[0] a_reg/Q,
     * S[1] b_reg/Q; CO[7] used too); e_reg/D = dsp/P[0] (C[0] a_reg/Q).
     */
    private static EDIFNetlist faninNetlist() {
        TestClockNetwork.NetlistBuilder nb = new TestClockNetwork.NetlistBuilder();
        EDIFCell fdre = Design.getUnisimCell(Unisim.FDRE);
        EDIFCell sub = new EDIFCell(nb.netlist.getWorkLibrary(), "sub_cell");
        sub.createPort("clk_in", EDIFDirection.INPUT, 1);
        sub.createPort("q", EDIFDirection.OUTPUT, 1);
        EDIFCellInst s = sub.createChildCellInst("s_reg", fdre);
        EDIFNet subClk = sub.createNet("clk_in");
        subClk.createPortInst(sub.getPort("clk_in"));
        subClk.createPortInst("C", s);
        EDIFNet subQ = sub.createNet("q_i");
        subQ.createPortInst("Q", s);
        subQ.createPortInst(sub.getPort("q"));
        EDIFCellInst d = sub.createChildCellInst("d_reg", fdre);
        subClk.createPortInst("C", d);
        subQ.createPortInst("D", d);
        nb.insts.put("sub", nb.top.createChildCellInst("sub", sub));
        nb.inst("ib", Unisim.IBUF);
        nb.inst("bg", Unisim.BUFG);
        for (String r : new String[]{"a_reg", "b_reg", "c_reg", "e_reg", "dst_reg", "dst2_reg"}) {
            nb.inst(r, Unisim.FDRE);
        }
        nb.inst("cy", Unisim.CARRY8);
        nb.inst("dsp", Unisim.DSP48E2);
        nb.inst("lut", Unisim.LUT3, "INIT", "8'h80");
        nb.inst("buf_lut", Unisim.LUT1, "INIT", "2'h2");
        nb.connect("clk", "clk", "ib/I");
        nb.connect("clk_ib", "ib/O", "bg/I");
        nb.connect("c", "bg/O", "a_reg/C", "b_reg/C", "c_reg/C", "e_reg/C", "dst_reg/C", "dst2_reg/C", "dsp/CLK",
                "sub/clk_in");
        nb.connect("din", "din", "a_reg/D", "buf_lut/I0");
        nb.connect("aq", "a_reg/Q", "lut/I0", "cy/S[0]", "dsp/C[0]");
        nb.connect("bq", "b_reg/Q", "cy/S[1]");
        nb.connect("co0", "cy/CO[0]", "c_reg/D");
        nb.connect("co7", "cy/CO[7]");
        nb.connect("p0", "dsp/P[0]", "e_reg/D");
        nb.connect("sq", "sub/q", "lut/I1");
        nb.tie("lut/I2", 1, 0);
        nb.connect("lo", "lut/O", "dst_reg/D");
        nb.connect("bo", "buf_lut/O", "dst2_reg/D");
        return nb.netlist;
    }

    /** The names of the -from objects of the one exception of "set_false_path -from objects", or null if dropped */
    private static List<String> fromNames(EDIFNetlist netlist, String objects) {
        TimingConstraints tc = TimingConstraints.parse(netlist,
                Collections.singletonList("set_false_path -from " + objects));
        return tc.getExceptions().isEmpty() ? null : names(tc.getExceptions().get(0).from);
    }

    /**
     * all_fanin -flat -startpoints_only [-only_cells]: the clock pins of the registers, the input ports and the clock
     * generator outputs in the fan-in of pins or nets, across the hierarchy; get_pins -filter IS_CLOCK
     */
    @Test
    public void testAllFaninStartpoints() {
        EDIFNetlist netlist = faninNetlist();
        Assertions.assertEquals(Arrays.asList("a_reg/C", "sub/s_reg/C"),
                fromNames(netlist, "[all_fanin -flat -startpoints_only [get_pins dst_reg/D]]"));
        Assertions.assertEquals(Arrays.asList("a_reg", "sub/s_reg"),
                fromNames(netlist, "[all_fanin -quiet -flat -startpoints_only -only_cells [get_pins dst_reg/D]]"));
        Assertions.assertEquals(Arrays.asList("a_reg/C", "sub/s_reg/C"),
                fromNames(netlist, "[all_fanin -flat -startpoints_only -levels 0 [get_nets lo]]"));
        // An input port is a startpoint (not a cell)
        Assertions.assertEquals(Arrays.asList("a_reg/C", "din", "sub/s_reg/C"),
                fromNames(netlist, "[all_fanin -flat -startpoints_only [get_pins {dst_reg/D dst2_reg/D}]]"));
        Assertions.assertNull(fromNames(netlist,
                "[all_fanin -flat -startpoints_only -only_cells [get_pins dst2_reg/D]]"));
        // Clock fan-in through the buffers to the clock port
        Assertions.assertEquals(Collections.singletonList("clk"),
                fromNames(netlist, "[all_fanin -flat -startpoints_only [get_pins dst_reg/C]]"));
        // The form of the constraints of IP: the clock pins of the startpoints
        TimingConstraints tc = TimingConstraints.parse(netlist, Arrays.asList(
                "set s [all_fanin -flat -startpoints_only [get_pins {dst_reg/D dst2_reg/D}]]",
                "set_false_path -hold -from [get_pins -quiet $s -filter IS_CLOCK] -to [get_pins dst_reg/D]"));
        Assertions.assertEquals(1, tc.getExceptions().size());
        Assertions.assertEquals(Arrays.asList("a_reg/C", "sub/s_reg/C"), names(tc.getExceptions().get(0).from));
        Assertions.assertTrue(tc.getWarnings().isEmpty(), tc.getWarnings().toString());
        Assertions.assertEquals(Arrays.asList("a_reg/C", "b_reg/C", "c_reg/C", "dst2_reg/C", "dst_reg/C", "e_reg/C",
                "sub/d_reg/C", "sub/s_reg/C"),
                fromNames(netlist, "[get_pins -filter {IS_CLOCK && DIRECTION == IN} -of [get_cells -hier *_reg]]"));

        // A clock generator is a startpoint at its output (UG835)
        EDIFNetlist mmcm = TestClockNetwork.mmcmNetlist().netlist;
        Assertions.assertEquals(Collections.singletonList("mmcm/CLKOUT0"),
                fromNames(mmcm, "[all_fanin -flat -startpoints_only [get_pins r0/C]]"));
        Assertions.assertEquals(Collections.singletonList("mmcm"),
                fromNames(mmcm, "[all_fanin -flat -startpoints_only -only_cells [get_pins r0/C]]"));

        // Unsupported forms: the command using them is not applied
        for (String form : new String[]{"-startpoints_only [get_pins dst_reg/D]", "-flat [get_pins dst_reg/D]",
            "-flat -startpoints_only -levels 2 [get_pins dst_reg/D]",
            "-flat -startpoints_only -pin_levels 1 [get_pins dst_reg/D]",
            "-flat -startpoints_only -trace_arcs all [get_pins dst_reg/D]",
            "-flat -startpoints_only [get_cells dst_reg]", "-flat -startpoints_only dst_reg/D",
            // A carry chain: CO[0] does not depend on S[1], but crossing all inputs would also return b_reg/C
            "-flat -startpoints_only [get_pins c_reg/D]"}) {
            tc = TimingConstraints.parse(netlist, Collections.singletonList(
                    "set_false_path -from [all_fanin " + form + "]"));
            Assertions.assertTrue(tc.getExceptions().isEmpty(), form);
            Assertions.assertEquals(1,
                    tc.getWarnings().get("set_false_path with an unsupported nested command (ignored)"),
                    form + ": " + tc.getWarnings());
        }
    }

    /**
     * all_fanin under a current instance (as in the constraints of IP): get_pins keeps the pins all_fanin returns as
     * they are (their full names are not relative to the current instance)
     */
    @Test
    public void testAllFaninUnderCurrentInstance() {
        EDIFNetlist netlist = faninNetlist();
        TimingConstraints tc = TimingConstraints.parse(netlist, Arrays.asList(
                "current_instance sub",
                "set_false_path -hold -from [get_pins -quiet [all_fanin -flat -startpoints_only [get_pins d_reg/D]]"
                        + " -filter IS_CLOCK] -to [get_pins d_reg/D]"));
        Assertions.assertEquals(1, tc.getExceptions().size(), tc.getWarnings().toString());
        Assertions.assertEquals(Collections.singletonList("sub/s_reg/C"), names(tc.getExceptions().get(0).from));
        Assertions.assertEquals(Collections.singletonList("sub/d_reg/D"), names(tc.getExceptions().get(0).to));
    }

    /** IS_CLOCK and the startpoints of all_fanin: a bus named like a clock pin is data, not a clock */
    @Test
    public void testClockPinIsScalar() {
        EDIFNetlist netlist = faninNetlist();
        // The C operand of a DSP
        Assertions.assertEquals(Collections.singletonList("dsp/CLK"),
                fromNames(netlist, "[get_pins -filter IS_CLOCK -of [get_cells dsp]]"));
        Assertions.assertEquals(Collections.singletonList("dsp/CLK"),
                fromNames(netlist, "[all_fanin -flat -startpoints_only [get_pins e_reg/D]]"));
    }

    @Test
    public void testUnquotedBusIndexInObjectName() {
        // Vivado keeps an unquoted bracketed integer or '*' in an object name as text (a bus index), not a command call
        TimingConstraints tc = parse("create_generated_clock -name ren u0/g[1].b/O",
                "set_false_path -to [get_cells u0/d_reg[*]]");
        List<TimingConstraints.GeneratedClockDef> g = tc.getGeneratedClocks();
        Assertions.assertEquals(1, g.size());
        Assertions.assertTrue(g.get(0).isRename());
        Assertions.assertEquals("u0/g[1].b/O", g.get(0).targets.get(0).getName());
        Assertions.assertEquals("u0/d_reg[*]", tc.getExceptions().get(0).to.get(0).getName());
        Assertions.assertTrue(tc.getWarnings().keySet().stream().noneMatch(w -> w.contains("nested command")),
                tc.getWarnings().toString());
    }

    @Test
    public void testExceptionsAndGroups() {
        TimingConstraints tc = parse(
                "set_clock_groups -asynchronous -group [get_clocks clk3] -group [get_clocks {clk4 clk4b}]",
                "set_clock_groups -logically_exclusive -group clk4 -group clk4b",
                // Vivado writes the multiplier after the options
                "set_multicycle_path -setup -to [get_cells {mc_dst_reg[*]}] 3",
                "set_multicycle_path 2 -hold -end -to [get_cells {mc_dst_reg[*]}]",
                "set_multicycle_path 2 -setup -start -from [get_clocks clk1] -to [get_clocks clk2]",
                "set_multicycle_path 2 -through [get_pins thru_lut/O]",
                "set_false_path -from [get_clocks clk5] -to [get_clocks clk1]",
                "set_false_path -hold -to [get_cells {re_reg[*]}]",
                "set_false_path -from [get_clocks clk1] -through [get_pins fp_lut/O]",
                "set_max_delay 1.5 -from [get_cells {src_reg[*]}]",
                "set_input_delay -clock clk 1.0 [get_ports din]",
                "set_input_delay -clock clk 2.0 [get_ports din2]");
        Assertions.assertEquals(2, tc.getClockGroups().size());
        TimingConstraints.ClockGroupsDef g0 = tc.getClockGroups().get(0);
        Assertions.assertEquals(TimingConstraints.GroupKind.ASYNCHRONOUS, g0.kind);
        Assertions.assertEquals(2, g0.groups.size());
        Assertions.assertEquals(2, g0.groups.get(1).size());
        Assertions.assertEquals(TimingConstraints.GroupKind.LOGICALLY_EXCLUSIVE, tc.getClockGroups().get(1).kind);
        Assertions.assertEquals("clk4b", tc.getClockGroups().get(1).groups.get(1).get(0).getName());

        List<TimingConstraints.PathExceptionDef> ex = tc.getExceptions();
        // -through objects are kept, one list per option
        Assertions.assertEquals(7, ex.size());
        TimingConstraints.PathExceptionDef m0 = ex.get(0);
        Assertions.assertEquals(TimingConstraints.ExceptionKind.MULTICYCLE_PATH, m0.kind);
        Assertions.assertEquals(3, m0.multiplier);
        Assertions.assertTrue(m0.setup);
        Assertions.assertFalse(m0.hold);
        Assertions.assertNull(m0.start);
        Assertions.assertNull(m0.from);
        Assertions.assertEquals("mc_dst_reg[*]", m0.to.get(0).getName());
        Assertions.assertEquals(Boolean.FALSE, ex.get(1).start);
        Assertions.assertTrue(ex.get(1).hold);
        Assertions.assertFalse(ex.get(1).setup);
        Assertions.assertEquals(Boolean.TRUE, ex.get(2).start);
        Assertions.assertEquals(XdcObject.Kind.CLOCK, ex.get(2).from.get(0).getKind());
        Assertions.assertNull(ex.get(2).through);
        TimingConstraints.PathExceptionDef m3 = ex.get(3);
        Assertions.assertEquals(TimingConstraints.ExceptionKind.MULTICYCLE_PATH, m3.kind);
        Assertions.assertEquals(2, m3.multiplier);
        Assertions.assertNull(m3.from);
        Assertions.assertNull(m3.to);
        Assertions.assertEquals(1, m3.through.size());
        Assertions.assertEquals("thru_lut/O", m3.through.get(0).get(0).getName());
        TimingConstraints.PathExceptionDef f0 = ex.get(4);
        Assertions.assertEquals(TimingConstraints.ExceptionKind.FALSE_PATH, f0.kind);
        Assertions.assertTrue(f0.setup && f0.hold);
        Assertions.assertFalse(ex.get(5).setup);
        Assertions.assertTrue(ex.get(5).hold);
        TimingConstraints.PathExceptionDef f2 = ex.get(6);
        Assertions.assertEquals(TimingConstraints.ExceptionKind.FALSE_PATH, f2.kind);
        Assertions.assertEquals("fp_lut/O", f2.through.get(0).get(0).getName());
        Assertions.assertEquals(XdcObject.Kind.CLOCK, f2.from.get(0).getKind());
        // An exception with -through is never folded into the clock relations, even with clocks only as -from
        Assertions.assertTrue(ClockRelations.isClockOnly(f0));
        Assertions.assertFalse(ClockRelations.isClockOnly(f2));

        // One warning per kind
        Assertions.assertNull(tc.getWarnings().get("set_multicycle_path -through (constraint ignored)"));
        Assertions.assertNull(tc.getWarnings().get("set_false_path -through (constraint ignored)"));
        Assertions.assertEquals(1, tc.getWarnings().get("set_max_delay (not supported, ignored)"));
        Assertions.assertEquals(2, tc.getWarnings().get("set_input_delay (not supported, ignored)"));
    }

    /** -through options: one object list each, in order; -rise_through and -fall_through still drop the exception */
    @Test
    public void testThroughOptions() {
        TimingConstraints tc = parse(
                "set_false_path -from [get_cells a_reg] -through [get_pins {l1/O l2/O}] -through [get_nets n3]"
                        + " -to [get_cells b_reg]",
                "set_multicycle_path 2 -setup -rise_through [get_pins l1/O]",
                "set_false_path -fall_through [get_pins l1/O]");
        Assertions.assertEquals(1, tc.getExceptions().size());
        TimingConstraints.PathExceptionDef e = tc.getExceptions().get(0);
        Assertions.assertEquals("a_reg", e.from.get(0).getName());
        Assertions.assertEquals("b_reg", e.to.get(0).getName());
        Assertions.assertEquals(2, e.through.size());
        Assertions.assertEquals(2, e.through.get(0).size());
        Assertions.assertEquals(XdcObject.Kind.PIN, e.through.get(0).get(1).getKind());
        Assertions.assertEquals("l2/O", e.through.get(0).get(1).getName());
        Assertions.assertEquals(XdcObject.Kind.NET, e.through.get(1).get(0).getKind());
        Assertions.assertEquals(1, tc.getWarnings().get("set_multicycle_path -rise_through (constraint ignored)"));
        Assertions.assertEquals(1, tc.getWarnings().get("set_false_path -fall_through (constraint ignored)"));
    }

    /** get_pins / get_cells -regexp: regular expressions matching whole names */
    @Test
    public void testRegexpQueries() {
        EDIFNetlist netlist = TestClockNetwork.mmcmNetlist().netlist;
        TimingConstraints tc = TimingConstraints.parse(netlist, Arrays.asList(
                "set_multicycle_path 2 -to [get_pins -hierarchical -regexp {r0b?\\/D}]",
                "set_false_path -from [get_cells -regexp {r(div|inv)}]",
                "set_false_path -to [get_pins -hierarchical -regexp -nocase {R0/d}]"));
        List<TimingConstraints.PathExceptionDef> ex = tc.getExceptions();
        Assertions.assertEquals(3, ex.size());
        Assertions.assertEquals(2, ex.get(0).to.size());
        Assertions.assertEquals(2, ex.get(1).from.size());
        Assertions.assertEquals(1, ex.get(2).to.size());
        Assertions.assertEquals("r0/D", ex.get(2).to.get(0).getName());
    }

    /** get_clocks -regexp / -nocase, get_ports and get_nets -regexp */
    @Test
    public void testRegexpClocksPortsNets() {
        TestClockNetwork.NetlistBuilder nb = TestClockNetwork.mmcmNetlist();
        TimingConstraints tc = TimingConstraints.parse(nb.netlist, Arrays.asList(
                "create_clock -period 10 -name CLK_A [get_ports -regexp {clk_.}]",
                "create_clock -period 8 -name clk_b2 [get_nets -regexp {c(b|0)}]",
                "set_clock_groups -asynchronous -group [get_clocks -regexp {CLK_[A-Z]}] "
                        + "-group [get_clocks -regexp -nocase {CLK_B.*}]"));
        List<TimingConstraints.ClockDef> c = tc.getClocks();
        Assertions.assertEquals(2, c.get(0).sources.size());
        Assertions.assertEquals(2, c.get(1).sources.size());
        ClockNetwork cn = ClockNetwork.build(nb.netlist, tc);
        TimingConstraints.ClockGroupsDef g = tc.getClockGroups().get(0);
        Assertions.assertEquals(Collections.singleton(cn.getClock("CLK_A").getId()),
                cn.resolveClocks(g.groups.get(0)));
        Assertions.assertEquals(Collections.singleton(cn.getClock("clk_b2").getId()),
                cn.resolveClocks(g.groups.get(1)));
    }

    /**
     * The arguments of the commands TimingConstraints does not read are not evaluated: their object queries are not
     * run, and the commands it reads, their arguments and their indices are the same.
     */
    @Test
    public void testIgnoredCommandsNotEvaluated() {
        EDIFNetlist netlist = TestClockNetwork.mmcmNetlist().netlist;
        String script = String.join("\n",
                "create_clock -period 8 -name clk_a [get_ports clk_a]",
                "set_min_delay 1.5 -from [get_cells -bogus1 r0] -to [get_cells nosuch*]",
                "add_cells_to_pblock [get_pblocks pb] [get_cells -hierarchical -bogus2 *]",
                "set_property LOC SLICE_X0Y0 [get_cells -bogus3 r0]",
                "set_property TOOL_DERIVED_CLK_NAMES 1:0:CLKIN1:CLKOUT0:clk_a:mo0 [get_cells mmcm]",
                "set_false_path -from [get_clocks clk_a] -to [get_cells r0b]");
        List<String> evaluatedAll = new ArrayList<>();
        Map<String, Integer> warningsAll = new LinkedHashMap<>();
        new XdcInterpreter(new XdcObjectFinder(netlist), (n, a, i) -> evaluatedAll.add(i + " " + n + " " + a),
                warningsAll).evalScript(script);
        List<String> evaluatedSome = new ArrayList<>();
        Map<String, Integer> warningsSome = new LinkedHashMap<>();
        XdcInterpreter interp = new XdcInterpreter(new XdcObjectFinder(netlist),
                (n, a, i) -> evaluatedSome.add(i + " " + n + " " + a), warningsSome);
        interp.setEvaluatedCommands(TimingConstraints::isHandled);
        interp.evalScript(script);

        // Evaluated, set_min_delay and set_property LOC have a query with an unknown option and add_cells_to_pblock
        // uses get_pblocks, which the reader does not evaluate: they are not applied
        Assertions.assertEquals(3, evaluatedAll.size());
        Assertions.assertEquals(6, evaluatedSome.size());
        Assertions.assertEquals(evaluatedAll.get(0), evaluatedSome.get(0));
        Assertions.assertEquals(evaluatedAll.get(1), evaluatedSome.get(4));
        Assertions.assertEquals(evaluatedAll.get(2), evaluatedSome.get(5));
        Assertions.assertEquals("1 set_min_delay []", evaluatedSome.get(1));
        Assertions.assertEquals("2 add_cells_to_pblock []", evaluatedSome.get(2));
        Assertions.assertEquals("3 set_property []", evaluatedSome.get(3));
        // The queries of the ignored commands ran only when all arguments were evaluated
        Assertions.assertEquals(1, warningsAll.get("get_cells with an unknown or ambiguous option -bogus1 (not valid)"));
        Assertions.assertEquals(1, warningsAll.get("unsupported nested command get_pblocks"));
        Assertions.assertTrue(warningsSome.isEmpty(), warningsSome.toString());

        TimingConstraints tc = TimingConstraints.parse(netlist, Collections.singletonList(script));
        Assertions.assertEquals(6, tc.getNumCommands());
        Assertions.assertEquals("mo0", tc.getDerivedClockName("mmcm", "CLKOUT0", "clk_a"));
        Assertions.assertEquals(1, tc.getExceptions().size());
        Assertions.assertEquals(5, tc.getExceptions().get(0).index);
        // Only the timing constraint is warned about, not the commands that are no timing constraints
        Assertions.assertEquals(1, tc.getWarnings().get("set_min_delay (not supported, ignored)"));
        Assertions.assertEquals(1, tc.getWarnings().size(), tc.getWarnings().toString());

        // The arguments of an ignored command are evaluated when they set a variable or the current instance
        tc = TimingConstraints.parse(netlist, Arrays.asList(
                "set_min_delay 1.5 -from [get_cells [set v r0]]",
                "set_false_path -to [get_cells $v]",
                "set cmd set",
                "set_min_delay 1.5 -from [get_cells [$cmd w r0b]]",
                "set_false_path -to [get_cells $w]",
                "set_min_delay 1.5 -from [get_cells [\"$cmd\" x r0]]",
                "set_false_path -to [get_cells $x]"));
        Assertions.assertEquals("r0", tc.getExceptions().get(0).to.get(0).getName());
        Assertions.assertEquals("r0b", tc.getExceptions().get(1).to.get(0).getName());
        Assertions.assertEquals("r0", tc.getExceptions().get(2).to.get(0).getName());

        // set_property with options before the property name, or with -dict, is ignored too
        Map<String, Integer> warnings = new LinkedHashMap<>();
        interp = new XdcInterpreter(new XdcObjectFinder(netlist), (n, a, i) -> { }, warnings);
        interp.setEvaluatedCommands(TimingConstraints::isHandled);
        interp.evalScript(String.join("\n",
                "set_property -quiet LOC SLICE_X0Y0 [get_cells -bogus4 r0]",
                "set_property -dict {LOC SLICE_X0Y0} [get_cells -bogus5 r0]"));
        Assertions.assertTrue(warnings.isEmpty(), warnings.toString());
    }

    @Test
    public void testUncertaintyAndJitter() {
        TimingConstraints tc = parse(
                "set_system_jitter 0.070",
                "set_input_jitter clk1 0.120",
                "set_clock_uncertainty 0.150 [get_clocks clk2]",
                "set_clock_uncertainty -setup 0.250 -from [get_clocks clk1] -to [get_clocks clk2]",
                "set_clock_uncertainty -hold 0.080 -from clk1 -to clk2");
        Assertions.assertEquals(0.070, tc.getSystemJitter(), 1e-12);
        Assertions.assertEquals(1, tc.getInputJitters().size());
        Assertions.assertEquals("clk1", tc.getInputJitters().get(0).clocks.get(0).getName());
        List<TimingConstraints.UncertaintyDef> u = tc.getUncertainties();
        Assertions.assertEquals(3, u.size());
        Assertions.assertEquals("clk2", u.get(0).clocks.get(0).getName());
        Assertions.assertTrue(u.get(0).setup && u.get(0).hold);
        Assertions.assertTrue(u.get(1).setup);
        Assertions.assertFalse(u.get(1).hold);
        Assertions.assertEquals("clk1", u.get(1).from.get(0).getName());
        Assertions.assertEquals("clk2", u.get(2).to.get(0).getName());
        Assertions.assertEquals(0.080, u.get(2).value, 1e-12);
    }

    @Test
    public void testTclSyntax() {
        TimingConstraints tc = parse(
                "set p 6.4",
                "set clkname \"my_clk\"",
                "create_clock -period $p -name ${clkname} [get_ports [list clk]]",
                // Option abbreviations and nested queries
                "set_false_path -fr [get_clocks -of [get_ports clk]] -to [get_clocks {a* b?}]",
                "set_false_path -from [get_clocks -i a] -to [get_clocks -include b]",
                "current_instance u0/u1",
                "set_false_path -to [get_cells x_reg]",
                "current_instance -quiet",
                "set_false_path -to [get_cells y_reg]",
                "set_property TOOL_DERIVED_CLK_NAMES 1:0:CLKIN1:CLKOUT0:sys_clk:mo0::1:0:CLKIN1:CLKOUT1:sys_clk:mo1 "
                        + "[get_cells u_mmcm]");
        Assertions.assertEquals(6.4, tc.getClocks().get(0).period);
        Assertions.assertEquals("my_clk", tc.getClocks().get(0).name);
        TimingConstraints.PathExceptionDef e = tc.getExceptions().get(0);
        Assertions.assertNotNull(e.from.get(0).getClockOf());
        Assertions.assertEquals("clk", e.from.get(0).getClockOf().get(0).getName());
        Assertions.assertEquals(2, e.to.size());
        Assertions.assertEquals("a*", e.to.get(0).getName());
        // -i and -include abbreviate -include_generated_clocks
        Assertions.assertTrue(tc.getExceptions().get(1).from.get(0).isIncludeGenerated());
        Assertions.assertTrue(tc.getExceptions().get(1).to.get(0).isIncludeGenerated());
        Assertions.assertEquals("u0/u1/x_reg", tc.getExceptions().get(2).to.get(0).getName());
        Assertions.assertEquals("y_reg", tc.getExceptions().get(3).to.get(0).getName());
        Assertions.assertEquals("mo1", tc.getDerivedClockName("u_mmcm", "CLKOUT1", "sys_clk"));
        Assertions.assertNull(tc.getDerivedClockName("u_mmcm", "CLKOUT2", "sys_clk"));
        // Clock queries remember when they were made
        Assertions.assertTrue(e.to.get(0).getQueryIndex() > tc.getClocks().get(0).index);
    }

    /**
     * top: input ports clk_p[0] (a one-bit bus written as a scalar port) and rst; ip (cell ip_core: input clk_in1,
     * flip-flop q_reg clocked by it, a property MY_PROP on q_reg); a BUFG b0 on clk_p[0]; clk_p[0] reaches ip/clk_in1.
     */
    private static EDIFNetlist scopedNetlist() {
        EDIFNetlist netlist = EDIFTools.createNewNetlist("top");
        EDIFCell top = netlist.getTopCell();
        EDIFCell fdre = Design.getUnisimCell(Unisim.FDRE);
        EDIFCell bufg = Design.getUnisimCell(Unisim.BUFG);
        EDIFTools.ensureCellInLibraries(netlist, fdre);
        EDIFTools.ensureCellInLibraries(netlist, bufg);
        EDIFCell ip = new EDIFCell(netlist.getWorkLibrary(), "ip_core");
        ip.createPort("clk_in1", EDIFDirection.INPUT, 1);
        EDIFCellInst q = ip.createChildCellInst("q_reg", fdre);
        q.addProperty("MY_PROP", "yes");
        EDIFNet inner = ip.createNet("clk_in1");
        inner.createPortInst(ip.getPort("clk_in1"));
        inner.createPortInst("C", q);
        top.createPort("clk_p[0]", EDIFDirection.INPUT, 1);
        top.createPort("rst", EDIFDirection.INPUT, 1);
        EDIFCellInst ipInst = top.createChildCellInst("ip", ip);
        EDIFCellInst b0 = top.createChildCellInst("b0", bufg);
        EDIFNet clk = top.createNet("clk_p_0");
        clk.createPortInst(top.getPort("clk_p[0]"));
        clk.createPortInst("clk_in1", ipInst);
        clk.createPortInst("I", b0);
        top.createNet("rst").createPortInst(top.getPort("rst"));
        // An output of ip driven inside it (ip/r/Q -> ip/clk_out -> top-level clk_out); a renamed copy of ip_core
        EDIFCellInst r = ip.createChildCellInst("r", fdre);
        ip.createPort("clk_out", EDIFDirection.OUTPUT, 1);
        EDIFNet out = ip.createNet("clk_out_i");
        out.createPortInst("Q", r);
        out.createPortInst(ip.getPort("clk_out"));
        top.createPort("clk_out", EDIFDirection.OUTPUT, 1);
        EDIFNet topOut = top.createNet("clk_out");
        topOut.createPortInst("clk_out", ipInst);
        topOut.createPortInst(top.getPort("clk_out"));
        EDIFCell renamed = new EDIFCell(netlist.getWorkLibrary(), "ip_core_renamed");
        renamed.addProperty("ORIG_REF_NAME", "ip_core");
        top.createChildCellInst("ip2", renamed);
        return netlist;
    }

    private static List<String> names(List<XdcObject> objects) {
        List<String> n = new ArrayList<>();
        for (XdcObject o : objects) {
            n.add(o.getName());
        }
        Collections.sort(n);
        return n;
    }

    /** get_ports matches the bus name of a scalar port named as a bus bit, as Vivado does */
    @Test
    public void testScalarBusBitPorts() {
        EDIFNetlist netlist = scopedNetlist();
        Assertions.assertFalse(netlist.getTopCell().getPort("clk_p[0]").isBus());
        TimingConstraints tc = TimingConstraints.parse(netlist, Arrays.asList(
                "create_clock -period 10 [get_ports clk_p]",
                "create_clock -period 8 -name c2 [get_ports -regexp {clk_.}]",
                "set_false_path -from [get_ports clk_*]"));
        Assertions.assertEquals("clk_p[0]", tc.getClocks().get(0).name);
        Assertions.assertEquals("clk_p[0]", tc.getClocks().get(0).sources.get(0).getName());
        Assertions.assertEquals(1, tc.getClocks().get(1).sources.size());
        Assertions.assertEquals(Arrays.asList("clk_out", "clk_p[0]"), names(tc.getExceptions().get(0).from));
        Assertions.assertTrue(tc.getWarnings().isEmpty(), tc.getWarnings().toString());
    }

    /**
     * get_ports -scoped_to_current_instance: the pins of the current instance, traversed to the top-level port their
     * net connects to unless -no_traverse; at the top, ordinary ports. get_ports -of_objects: the ports of nets/pins.
     */
    @Test
    public void testScopedAndOfObjectsPorts() {
        EDIFNetlist netlist = scopedNetlist();
        TimingConstraints tc = TimingConstraints.parse(netlist, Arrays.asList(
                "current_instance ip",
                "create_clock -period 3.333 [get_ports -scoped_to_current_instance clk_in1]",
                "create_clock -period 4 -name ip_pin [get_ports -scoped_to_current_instance -no_traverse clk_in1]",
                "current_instance -quiet",
                "create_clock -period 5 -name top_scoped [get_ports -scoped_to_current_instance rst]",
                "create_clock -period 6 -name of_net [get_ports -of_objects [get_nets clk_p_0]]",
                "create_clock -period 7 -name of_pin [get_ports -of_objects [get_pins b0/I]]",
                // Nets and pins whose net is driven inside a child and leaves through a top-level port
                "create_clock -period 8 -name of_out [get_ports -of_objects [get_nets clk_out]]",
                "create_clock -period 9 -name of_driver [get_ports -of_objects [get_pins ip/r/Q]]",
                "current_instance ip",
                "create_clock -period 10 -name scoped_out [get_ports -scoped_to_current_instance clk_out]"));
        List<TimingConstraints.ClockDef> c = tc.getClocks();
        Assertions.assertEquals(8, c.size(), tc.getWarnings().toString());
        for (int i = 5; i < 8; i++) {
            Assertions.assertEquals(XdcObject.Kind.PORT, c.get(i).sources.get(0).getKind());
            Assertions.assertEquals("clk_out", c.get(i).sources.get(0).getName());
        }
        Assertions.assertEquals("clk_p[0]", c.get(0).name);
        Assertions.assertEquals(XdcObject.Kind.PORT, c.get(0).sources.get(0).getKind());
        Assertions.assertEquals(XdcObject.Kind.PIN, c.get(1).sources.get(0).getKind());
        Assertions.assertEquals("ip/clk_in1", c.get(1).sources.get(0).getName());
        Assertions.assertEquals("rst", c.get(2).sources.get(0).getName());
        Assertions.assertEquals("clk_p[0]", c.get(3).sources.get(0).getName());
        Assertions.assertEquals("clk_p[0]", c.get(4).sources.get(0).getName());
        Assertions.assertTrue(tc.getWarnings().isEmpty(), tc.getWarnings().toString());
    }

    /** Filters on ORIG_REF_NAME, PRIMITIVE_TYPE / _GROUP / _SUBGROUP and properties the netlist holds */
    @Test
    public void testFilterProperties() {
        EDIFNetlist netlist = scopedNetlist();
        TimingConstraints tc = TimingConstraints.parse(netlist, Arrays.asList(
                "set_false_path -to [get_cells -hierarchical -filter {PRIMITIVE_TYPE =~ REGISTER.SDR.*}]",
                "set_false_path -to [get_cells -hierarchical -filter {PRIMITIVE_GROUP == CLOCK}]",
                "set_false_path -to [get_cells -hierarchical -filter {PRIMITIVE_SUBGROUP == BUFFER || ORIG_REF_NAME == FDRE}]",
                "set_false_path -to [get_cells -hierarchical -filter {ORIG_REF_NAME == ip_core}]",
                "set_false_path -to [get_cells -hierarchical -filter {MY_PROP == yes}]",
                "set_false_path -to [get_cells -hierarchical -filter {IS_PRIMITIVE == 0 && PRIMITIVE_TYPE !~ *.*}]"));
        List<TimingConstraints.PathExceptionDef> ex = tc.getExceptions();
        Assertions.assertEquals(6, ex.size(), tc.getWarnings().toString());
        Assertions.assertEquals(Arrays.asList("ip/q_reg", "ip/r"), names(ex.get(0).to));
        Assertions.assertEquals(Arrays.asList("b0"), names(ex.get(1).to));
        Assertions.assertEquals(Arrays.asList("b0", "ip/q_reg", "ip/r"), names(ex.get(2).to));
        // ip (reference name) and ip2 (a renamed type whose ORIG_REF_NAME is ip_core)
        Assertions.assertEquals(Arrays.asList("ip", "ip2"), names(ex.get(3).to));
        Assertions.assertEquals("ip/q_reg", ex.get(4).to.get(0).getName());
        // Hierarchical cells have an empty PRIMITIVE_TYPE (ip2, an empty cell, is a black box)
        Assertions.assertEquals(Arrays.asList("ip"), names(ex.get(5).to));
        // A property the netlist holds on some cells only is taken as empty on the others, with a warning
        Assertions.assertEquals(1, tc.getWarnings().get(
                "-filter property MY_PROP not in the netlist on some cells (taken as empty)"));
        Assertions.assertEquals(1, tc.getWarnings().size(), tc.getWarnings().toString());
    }

    /** IS_CONNECTED on pins: a pin with a net attached (an FDRE's Q driving a net, a BUFG's input) */
    @Test
    public void testFilterIsConnected() {
        EDIFNetlist netlist = scopedNetlist();
        TimingConstraints tc = TimingConstraints.parse(netlist, Arrays.asList(
                "set_false_path -from [get_pins -of_objects [get_cells ip/r] -filter {DIRECTION == OUT && IS_CONNECTED}]",
                "set_false_path -to [get_pins -of_objects [get_cells b0] -filter {IS_CONNECTED}]",
                "set_false_path -to [get_pins -of_objects [get_cells ip/q_reg] -filter {DIRECTION == IN && IS_CONNECTED}]"));
        List<TimingConstraints.PathExceptionDef> ex = tc.getExceptions();
        Assertions.assertEquals(3, ex.size(), tc.getWarnings().toString());
        Assertions.assertEquals(Arrays.asList("ip/r/Q"), names(ex.get(0).from));
        Assertions.assertEquals(Arrays.asList("b0/I"), names(ex.get(1).to));
        Assertions.assertEquals(Arrays.asList("ip/q_reg/C"), names(ex.get(2).to));
        Assertions.assertTrue(tc.getWarnings().isEmpty(), tc.getWarnings().toString());
    }

    /**
     * A command whose evaluation fails (an undefined variable, a nested command that is not supported, an expr that
     * cannot be evaluated) is not applied, as Tcl stops at the error; an empty pattern matches no object. Neither
     * gives "all objects" (e.g. a false path between all clocks).
     */
    @Test
    public void testFailedEvaluationDropsCommand() {
        EDIFNetlist netlist = scopedNetlist();
        TimingConstraints tc = TimingConstraints.parse(netlist, Arrays.asList(
                "create_clock -period 10 -name a [get_ports clk_p]",
                "set_false_path -from [get_clocks $clk_src] -to [get_clocks $clk_dst]",
                "set_false_path -to [get_cells -hierarchical [lindex $regs 0]]",
                "set_false_path -to [get_ports [get_property NAME [get_ports rst]]]",
                "set_false_path -to [get_cells -hierarchical {}]",
                "set_false_path -from [get_clocks {}]",
                "set p [expr 1 / 0]",
                "create_clock -period $p -name b",
                "set_false_path -from [get_clocks a] -to [get_cells ip/${undefined}r]"));
        Assertions.assertEquals(1, tc.getClocks().size());
        Assertions.assertTrue(tc.getExceptions().isEmpty(), tc.getExceptions().toString());
        Map<String, Integer> w = tc.getWarnings();
        Assertions.assertEquals(3, w.get("set_false_path with an undefined Tcl variable (ignored)"));
        Assertions.assertEquals(1, w.get("set_false_path with an unsupported nested command (ignored)"));
        Assertions.assertEquals(1, w.get("unsupported nested command get_property"));
        Assertions.assertEquals(2, w.get("set_false_path with -from or -to matching no object (ignored)"));
        Assertions.assertEquals(1, w.get("set with an expr that cannot be evaluated (ignored)"));
        Assertions.assertEquals(1, w.get("create_clock with an undefined Tcl variable (ignored)"));
    }

    /** [list ...] of object queries is a list of the objects, in -from/-to/-through, clock sources and -group */
    @Test
    public void testListOfQueries() {
        EDIFNetlist netlist = scopedNetlist();
        TimingConstraints tc = TimingConstraints.parse(netlist, Arrays.asList(
                "create_clock -period 10 -name a [get_ports clk_p]",
                "create_clock -period 5 -name b [list [get_ports rst] [get_pins b0/I]]",
                "set_false_path -from [list [get_cells ip/q_reg] [get_cells ip/r]] -to [get_cells b0]",
                "set_false_path -through [list [get_pins b0/I] [get_pins ip/r/Q]]",
                "set_false_path -from [list clk_a clk_b]",
                "set_clock_groups -asynchronous -group [list [get_clocks a] [get_clocks -include_generated_clocks b]]"
                        + " -group [get_clocks c]",
                "create_generated_clock -name g -source [list [get_pins b0/I]] -divide_by 2 [list [get_pins ip/r/Q]]"));
        TimingConstraints.ClockDef b = tc.getClocks().get(1);
        Assertions.assertEquals(Arrays.asList("b0/I", "rst"), names(b.sources));
        Assertions.assertEquals(XdcObject.Kind.PORT, b.sources.get(0).getKind());
        Assertions.assertEquals(XdcObject.Kind.PIN, b.sources.get(1).getKind());
        List<TimingConstraints.PathExceptionDef> ex = tc.getExceptions();
        Assertions.assertEquals(Arrays.asList("ip/q_reg", "ip/r"), names(ex.get(0).from));
        Assertions.assertEquals(XdcObject.Kind.CELL, ex.get(0).from.get(0).getKind());
        Assertions.assertEquals(Arrays.asList("b0/I", "ip/r/Q"), names(ex.get(1).through.get(0)));
        Assertions.assertEquals(XdcObject.Kind.PIN, ex.get(1).through.get(0).get(0).getKind());
        Assertions.assertEquals(Arrays.asList("clk_a", "clk_b"), names(ex.get(2).from));
        Assertions.assertEquals(XdcObject.Kind.CLOCK, ex.get(2).from.get(0).getKind());
        List<XdcObject> g0 = tc.getClockGroups().get(0).groups.get(0);
        Assertions.assertEquals(Arrays.asList("a", "b"), names(g0));
        Assertions.assertTrue(g0.get(1).isIncludeGenerated());
        TimingConstraints.GeneratedClockDef g = tc.getGeneratedClocks().get(0);
        Assertions.assertEquals("b0/I", g.source.getName());
        Assertions.assertEquals(Arrays.asList("ip/r/Q"), names(g.targets));
        Assertions.assertTrue(tc.getWarnings().isEmpty(), tc.getWarnings().toString());
    }

    /**
     * A command with an unknown or ambiguous option (e.g. -t for -to or -through) is not applied, as Vivado rejects
     * it; so is a get_clocks -of_objects with a pattern
     */
    @Test
    public void testInvalidCommandsNotApplied() {
        EDIFNetlist netlist = scopedNetlist();
        TimingConstraints tc = TimingConstraints.parse(netlist, Arrays.asList(
                "create_clock -period 10 -name a [get_ports clk_p]",
                "set_false_path -from [get_clocks a] -t [get_cells b0]",
                "set_multicycle_path 2 -bogus -to [get_cells b0]",
                "set_clock_groups -asynchronous -bogus -group [get_clocks a]",
                "set_clock_uncertainty -x 0.1 [get_clocks a]",
                "create_clock -period 10 -nme x [get_ports rst]",
                "create_generated_clock -name g -src [get_pins b0/I] -divide_by 2 [get_pins ip/r/Q]",
                "set_clock_latency -source -bogus 0.5 [get_clocks a]",
                "set_false_path -from [get_clocks -of_objects [get_ports clk_p] nomatch*] -to [get_cells b0]",
                "set_system_jitter -bogus 0.2",
                "set_case_analysis -bogus 0 [get_pins b0/I]",
                "create_clock -period [expr 1 / 0; expr 10] -name c"));
        Assertions.assertEquals(1, tc.getClocks().size());
        Assertions.assertTrue(tc.getExceptions().isEmpty());
        Assertions.assertTrue(tc.getClockGroups().isEmpty());
        Assertions.assertTrue(tc.getUncertainties().isEmpty());
        Assertions.assertTrue(tc.getGeneratedClocks().isEmpty());
        Assertions.assertTrue(tc.getClockLatencies().isEmpty());
        Assertions.assertTrue(Double.isNaN(tc.getSystemJitter()));
        Assertions.assertEquals(-1, tc.getCaseValue("b0/I"));
        Map<String, Integer> w = tc.getWarnings();
        Assertions.assertEquals(1, w.get("set_false_path with an unknown or ambiguous option -t (ignored)"));
        Assertions.assertEquals(1, w.get("set_multicycle_path with an unknown or ambiguous option -bogus (ignored)"));
        Assertions.assertEquals(1, w.get("set_clock_groups with an unknown or ambiguous option -bogus (ignored)"));
        Assertions.assertEquals(1, w.get("set_clock_uncertainty with an unknown or ambiguous option -x (ignored)"));
        Assertions.assertEquals(1, w.get("create_clock with an unknown or ambiguous option -nme (ignored)"));
        Assertions.assertEquals(1, w.get("create_generated_clock with an unknown or ambiguous option -src (ignored)"));
        Assertions.assertEquals(1, w.get("set_clock_latency with an unknown or ambiguous option -bogus (ignored)"));
        Assertions.assertEquals(1, w.get("get_clocks -of_objects with a pattern (not valid)"));
        Assertions.assertEquals(1, w.get("set_false_path with an object query that is not valid (ignored)"));
        Assertions.assertEquals(1, w.get("set_system_jitter with an unknown or ambiguous option -bogus (ignored)"));
        Assertions.assertEquals(1, w.get("set_case_analysis with an unknown or ambiguous option -bogus (ignored)"));
        Assertions.assertEquals(1, w.get("create_clock with an expr that cannot be evaluated (ignored)"));
    }

    /**
     * create_clock on objects that match nothing is not applied (not a virtual clock); get_nets without a pattern
     * matches all nets
     */
    @Test
    public void testCreateClockOnNoObject() {
        EDIFNetlist netlist = scopedNetlist();
        TimingConstraints tc = TimingConstraints.parse(netlist, Arrays.asList(
                "create_clock -period 5 -name x [get_nets -hierarchical -filter {NAME =~ */nosuch}]",
                "create_clock -period 5 -name y [get_nets -hierarchical -filter {NAME =~ *clk_in1}]",
                "create_clock -period 5 -name v"));
        Assertions.assertEquals(Arrays.asList("y", "v"),
                Arrays.asList(tc.getClocks().get(0).name, tc.getClocks().get(1).name));
        Assertions.assertEquals(Arrays.asList("ip/clk_in1"), names(tc.getClocks().get(0).sources));
        Assertions.assertTrue(tc.getClocks().get(1).sources.isEmpty());
        Assertions.assertEquals(1, tc.getWarnings().get("create_clock on objects matching nothing (ignored)"));
    }

    /** The parsed constraints do not keep the interpreter (with its netlist index and query results) */
    @Test
    public void testInterpreterReleasedAfterParsing() throws ReflectiveOperationException {
        TimingConstraints tc = TimingConstraints.parse(scopedNetlist(),
                Arrays.asList("set_false_path -to [get_cells -hierarchical q_reg]"));
        Assertions.assertEquals(1, tc.getExceptions().size());
        java.lang.reflect.Field interp = TimingConstraints.class.getDeclaredField("interp");
        interp.setAccessible(true);
        Assertions.assertNull(interp.get(tc));
    }
}

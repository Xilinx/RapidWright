/*
 * Copyright (c) 2026, Advanced Micro Devices, Inc.
 * All rights reserved.
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

package com.xilinx.rapidwright.edif.optimizer;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import java.util.stream.Stream;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import com.xilinx.rapidwright.design.Unisim;
import com.xilinx.rapidwright.edif.EDIFCell;
import com.xilinx.rapidwright.edif.EDIFCellInst;
import com.xilinx.rapidwright.edif.EDIFDirection;
import com.xilinx.rapidwright.edif.EDIFHierNet;
import com.xilinx.rapidwright.edif.EDIFHierPortInst;
import com.xilinx.rapidwright.edif.EDIFLibrary;
import com.xilinx.rapidwright.edif.EDIFNet;
import com.xilinx.rapidwright.edif.EDIFNetlist;
import com.xilinx.rapidwright.edif.EDIFPort;
import com.xilinx.rapidwright.edif.EDIFPortInst;
import com.xilinx.rapidwright.edif.EDIFTools;
import com.xilinx.rapidwright.edif.EDIFValueType;
import com.xilinx.rapidwright.edif.compare.EDIFLeafNetlistComparator;
import com.xilinx.rapidwright.edif.compare.EDIFRandomSimEquivalence;

/**
 * Tests of the netlist optimizer (constant propagation and sweep, equivalent to Vivado's
 * opt_design -propconst -sweep), its connectivity index and the comparison tools used to
 * validate it.
 */
public class TestEDIFNetlistOptimizer {

    private static Map<String, String> optProbeExpected;

    /** Loads Vivado's opt_design -propconst -sweep results for the probes (leaf dumps). */
    private static synchronized Map<String, String> getOptProbeExpected() throws Exception {
        if (optProbeExpected == null) {
            optProbeExpected = new java.util.HashMap<>();
            try (java.io.InputStream is = TestEDIFNetlistOptimizer.class.getResourceAsStream(
                    "/edif/optimizer/opt_probes_expected.txt")) {
                java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
                byte[] buf = new byte[8192];
                int n;
                while ((n = is.read(buf)) > 0) {
                    bos.write(buf, 0, n);
                }
                String all = new String(bos.toByteArray(), StandardCharsets.UTF_8);
                for (String section : all.split("### ")) {
                    int nl = section.indexOf('\n');
                    if (nl > 0) {
                        optProbeExpected.put(section.substring(0, nl),
                                section.substring(nl + 1));
                    }
                }
            }
        }
        return optProbeExpected;
    }

    static Stream<String> optProbeNames() throws Exception {
        Map<String, String> expected = getOptProbeExpected();
        return OptProbes.getProbes().keySet().stream().filter(expected::containsKey);
    }

    private static String dump(EDIFNetlist netlist) {
        java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
        EDIFLeafNetlistComparator.dump(netlist, new java.io.PrintStream(bos));
        return bos.toString();
    }

    @ParameterizedTest
    @MethodSource("optProbeNames")
    public void testOptimizeNetlistMatchesVivado(String probe) throws Exception {
        EDIFNetlist netlist = OptProbes.getProbes().get(probe).get();
        EDIFTools.optimizeNetlist(netlist, new EDIFOptOptions());
        Assertions.assertEquals(getOptProbeExpected().get(probe), dump(netlist));
    }

    static Stream<String> optProbeNamesIdempotent() throws Exception {
        // Inverter absorption does not re-simplify the absorbing LUT (as in Vivado), so a
        // second pass may still find something to do
        return optProbeNames().filter(n -> !n.equals("v2_inv_same_var"));
    }

    @ParameterizedTest
    @MethodSource("optProbeNamesIdempotent")
    public void testOptimizeNetlistIsIdempotent(String probe) {
        EDIFNetlist netlist = OptProbes.getProbes().get(probe).get();
        EDIFTools.optimizeNetlist(netlist, new EDIFOptOptions());
        String first = dump(netlist);
        EDIFOptStats again = EDIFTools.optimizeNetlist(netlist, new EDIFOptOptions());
        Assertions.assertEquals(first, dump(netlist));
        for (EDIFOptStats.Phase phase : EDIFOptStats.Phase.values()) {
            Assertions.assertEquals(0, again.getCreated(phase));
            Assertions.assertEquals(0, again.getRemoved(phase));
        }
    }

    @ParameterizedTest
    @MethodSource("optProbeNames")
    public void testOptimizeNetlistIsEquivalent(String probe) {
        EDIFNetlist orig = OptProbes.getProbes().get(probe).get();
        EDIFNetlist opt = OptProbes.getProbes().get(probe).get();
        EDIFTools.optimizeNetlist(opt, new EDIFOptOptions());
        EDIFRandomSimEquivalence eq = new EDIFRandomSimEquivalence();
        Assertions.assertEquals(0, eq.check(orig, opt, 4), eq.getExamples().toString());
    }

    @Test
    public void testRandomSimEquivalenceDetectsDifference() {
        EDIFNetlist orig = OptProbes.getProbes().get("lut_chain").get();
        EDIFNetlist bad = OptProbes.getProbes().get("lut_chain").get();
        bad.getTopCell().getCellInst("C3").addProperty("INIT", "4'h9");
        Assertions.assertTrue(new EDIFRandomSimEquivalence().check(orig, bad, 2) > 0);
    }

    @Test
    public void testOptimizeLUTRules() {
        OptProbes.B b = new OptProbes.B("lut_rules");
        b.in("a", "b", "c");
        b.out("o", "p");
        // LUT4 with I2 tied low shrinks to a LUT3 with its remaining pins in order
        b.lut("L", "6996", "o", "a", "b", "0", "c");
        // LUT2 AND with I1 tied high is a buffer: removed, its load connects to 'a'
        b.lut("BF", "8", "m", "a", "1");
        b.lut("L2", "E", "p", "m", "b");
        EDIFOptStats stats = EDIFTools.propagateConstants(b.nl);
        EDIFCellInst l = b.top.getCellInst("L");
        Assertions.assertEquals("LUT3", l.getCellType().getName());
        Assertions.assertEquals("8'h96", l.getProperty("INIT").getValue());
        Assertions.assertEquals("PROPCONST", l.getProperty("OPT_MODIFIED").getValue());
        Assertions.assertEquals("c", l.getPortInst("I2").getNet().getName());
        Assertions.assertNull(b.top.getCellInst("BF"));
        Assertions.assertEquals("a", b.top.getCellInst("L2").getPortInst("I0").getNet().getName());
        Assertions.assertEquals(1, stats.getCreated(EDIFOptStats.Phase.CONSTANT_PROPAGATION));
        Assertions.assertEquals(2, stats.getRemoved(EDIFOptStats.Phase.CONSTANT_PROPAGATION));
    }

    @Test
    public void testOptimizeFlipFlopRules() {
        OptProbes.B b = new OptProbes.B("ff_rules");
        b.in("clk", "a", "r", "ce");
        b.out("q0", "q1", "q2");
        // CE low and reset value equal to INIT: constant 0
        b.fdre("F0", "a", "0", "r", "q0", "0");
        // CE low but reset can change INIT=1: kept
        b.fdre("F1", "a", "0", "r", "q1", "1");
        // D constant equal to INIT, no reset: constant 1
        b.fdre("F2", "1", "ce", "0", "q2", "1");
        EDIFTools.propagateConstants(b.nl);
        Assertions.assertNull(b.top.getCellInst("F0"));
        Assertions.assertNotNull(b.top.getCellInst("F1"));
        Assertions.assertNull(b.top.getCellInst("F2"));
        EDIFNet q0 = b.top.getInternalNet("q0");
        Assertions.assertTrue(q0.isGND());
        Assertions.assertTrue(b.top.getInternalNet("q2").isVCC());
    }

    @Test
    public void testOptimizeInverterAbsorption() {
        OptProbes.B b = new OptProbes.B("inv_rules");
        b.in("clk", "a", "b", "x");
        b.out("o", "q");
        b.lut("IV", "1", "n", "a");
        b.lut("L", "6", "o", "n", "b");
        b.fdre("F", "x", "1", "n", "q", "0");
        EDIFTools.propagateConstants(b.nl);
        // The inverter is absorbed by the LUT (INIT) and the flip-flop reset (IS_R_INVERTED)
        Assertions.assertNull(b.top.getCellInst("IV"));
        Assertions.assertEquals("4'h9", b.top.getCellInst("L").getProperty("INIT").getValue());
        EDIFCellInst f = b.top.getCellInst("F");
        Assertions.assertEquals("1'b1", f.getProperty("IS_R_INVERTED").getValue());
        Assertions.assertEquals("a", f.getPortInst("R").getNet().getName());
    }

    private static void assertOptimizedEquivalent(Supplier<EDIFNetlist> s, EDIFOptOptions opts) {
        EDIFNetlist orig = s.get();
        EDIFNetlist opt = s.get();
        EDIFTools.optimizeNetlist(opt, opts);
        EDIFRandomSimEquivalence eq = new EDIFRandomSimEquivalence();
        Assertions.assertEquals(0, eq.check(orig, opt, 4), eq.getExamples().toString());
        Assertions.assertTrue(eq.getChecked() > 0);
    }

    /** Two inverters in series, the first one also driving a flip-flop data input. */
    private static EDIFNetlist inverterPairPartialAbsorption(String first, String second) {
        OptProbes.B b = new OptProbes.B("inv_pair");
        b.in("clk", "a");
        b.out("o", "q");
        b.lut(first, "1", "n", "a");
        b.lut(second, "1", "o", "n");
        b.fdre("F", "n", "1", "0", "q", "0");
        return b.nl;
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    public void testOptimizePartialInverterAbsorptionKeepsPolarity(boolean swapNames) {
        // The second inverter absorbs the first one and becomes a buffer of 'a'
        String first = swapNames ? "B" : "A";
        String second = swapNames ? "A" : "B";
        Supplier<EDIFNetlist> s = () -> inverterPairPartialAbsorption(first, second);
        EDIFNetlist netlist = s.get();
        EDIFTools.optimizeNetlist(netlist, new EDIFOptOptions());
        EDIFCell top = netlist.getTopCell();
        Assertions.assertNull(top.getCellInst(second));
        Assertions.assertSame(top.getInternalNet("a"), top.getInternalNet("o"));
        assertOptimizedEquivalent(s, new EDIFOptOptions());
        assertOptimizedEquivalent(s, new EDIFOptOptions().setSweep(false));
    }

    /**
     * A LUT drives two inverters in series; the second one drives a flip-flop reset (which
     * absorbs it) and a flip-flop that is swept.
     */
    private static EDIFNetlist inverterChainToReset(String i0, String i1) {
        OptProbes.B b = new OptProbes.B("inv_chain_reset");
        b.in("clk", "a", "b", "d");
        b.out("q");
        b.lut("L", "6", "x", "a", "b");
        b.lut(i0, "1", "n", "x");
        b.lut(i1, "1", "m", "n");
        b.fdre("F", "d", "1", "m", "q", "0");
        b.fdre("F2", "n", "1", "0", "", "0");
        return b.nl;
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    public void testOptimizeTracksLoadsMovedOntoAliasedNets(boolean swapNames) {
        String i0 = swapNames ? "I1" : "I0";
        String i1 = swapNames ? "I0" : "I1";
        Supplier<EDIFNetlist> s = () -> inverterChainToReset(i0, i1);
        EDIFNetlist netlist = s.get();
        EDIFTools.optimizeNetlist(netlist, new EDIFOptOptions());
        EDIFCellInst f = netlist.getTopCell().getCellInst("F");
        Assertions.assertNotNull(f.getPortInst("R"));
        Assertions.assertNotNull(f.getPortInst("R").getNet());
        Assertions.assertNotNull(netlist.getTopCell().getCellInst("L"));
        assertOptimizedEquivalent(s, new EDIFOptOptions());
    }

    static Stream<Supplier<EDIFNetlist>> optConnectivityCases() {
        return Stream.concat(OptProbes.getProbes().values().stream(), Stream.of(
                () -> inverterPairPartialAbsorption("A", "B"),
                () -> inverterPairPartialAbsorption("B", "A"),
                () -> inverterChainToReset("I0", "I1"),
                () -> inverterChainToReset("I1", "I0"),
                () -> inverterPairManyLoads("A", "B", 20),
                () -> inverterPairManyLoads("B", "A", 20),
                TestEDIFNetlistOptimizer::inverterIntoChild,
                TestEDIFNetlistOptimizer::lut62BufferedInvertedInput,
                TestEDIFNetlistOptimizer::reselectedDroppedPin));
    }

    @ParameterizedTest
    @MethodSource("optConnectivityCases")
    public void testOptimizerConnectivityInvariants(Supplier<EDIFNetlist> s) {
        for (boolean propConst : new boolean[] {false, true}) {
            EDIFNetlist netlist = s.get();
            EDIFLeafIndex idx = new EDIFLeafIndex(netlist, EDIFConstPropEngine::hasPreserveProp);
            EDIFConstPropEngine e = new EDIFConstPropEngine(idx,
                    new EDIFOptOptions().setPropConst(propConst));
            e.run(new EDIFOptStats());
            Assertions.assertTrue(e.checkConsistency());
        }
    }

    @Test
    public void testSweepKeepsBufferDrivingInoutPort() {
        Supplier<EDIFNetlist> s = () -> {
            OptProbes.B b = new OptProbes.B("iobuf_inout");
            b.in("a");
            EDIFPort io = b.top.createPort("io", EDIFDirection.INOUT, 1);
            b.net(b.top, "io").createPortInst(io);
            b.inst(Unisim.IOBUF, "IOB", "I=a", "T=0", "IO=io");
            return b.nl;
        };
        EDIFNetlist netlist = s.get();
        EDIFOptStats stats = EDIFTools.optimizeNetlist(netlist, new EDIFOptOptions());
        Assertions.assertEquals(0, stats.getRemoved(EDIFOptStats.Phase.SWEEP));
        EDIFCellInst iob = netlist.getTopCell().getCellInst("IOB");
        Assertions.assertNotNull(iob);
        Assertions.assertEquals("a", iob.getPortInst("I").getNet().getName());
        Assertions.assertEquals("io", iob.getPortInst("IO").getNet().getName());
    }

    @Test
    public void testOptimizeGeneratesUniqueHierarchicalNetNames(@TempDir Path dir) {
        Supplier<EDIFNetlist> s = () -> {
            OptProbes.B b = new OptProbes.B("hier_names");
            // An existing top-level input/net has the name a routed signal would get
            b.in("clk", "a", "b", "u_x_opt_o");
            b.out("o", "q", "r");
            EDIFCell sub = b.cell("sub");
            b.in(sub, "i0", "i1");
            b.out(sub, "y");
            // Not a LUT: the inverter cannot be pulled back into it
            b.instIn(sub, Unisim.FDRE, "X", "C=i1", "D=i0", "CE=1", "R=0", "Q=x", "@INIT=1'b0");
            b.lut(sub, "IV", "1", "y", "x");
            b.inst(sub, "u", "i0=a", "i1=b", "y=n");
            // n: one load absorbs the inversion (and moves to u/x), the other cannot
            b.lut("L", "8", "o", "n", "b");
            b.fdre("F", "n", "1", "0", "q", "0");
            b.lut("R", "2", "r", "u_x_opt_o");
            return b.nl;
        };
        EDIFNetlist netlist = s.get();
        EDIFTools.optimizeNetlist(netlist, new EDIFOptOptions());
        EDIFCell top = netlist.getTopCell();
        EDIFNet existing = top.getNet("u_x_opt_o");
        Assertions.assertEquals(2, existing.getPortInsts().size());
        EDIFNet moved = top.getCellInst("L").getPortInst("I0").getNet();
        Assertions.assertNotSame(existing, moved);
        Assertions.assertSame(top.getCellInst("u"), moved.getPortInsts().stream()
                .filter(pi -> pi.isOutput()).findFirst().get().getCellInst());
        assertOptimizedEquivalent(s, new EDIFOptOptions());
        // The result survives an EDIF round trip
        Path f = dir.resolve("hier_names.edf");
        netlist.exportEDIF(f);
        EDIFNetlist back = EDIFTools.readEdifFile(f);
        EDIFRandomSimEquivalence eq = new EDIFRandomSimEquivalence();
        Assertions.assertEquals(0, eq.check(s.get(), back, 4), eq.getExamples().toString());
    }

    /**
     * Top-level inverter A drives a flip-flop and inverter B inside a child instance, B drives
     * output o (o = a).
     */
    private static EDIFNetlist inverterIntoChild() {
        OptProbes.B b = new OptProbes.B("inv_into_child");
        b.in("clk", "a");
        b.out("o", "q");
        EDIFCell sub = b.cell("sub");
        b.in(sub, "x");
        b.out(sub, "z");
        b.lut(sub, "B", "1", "z", "x");
        b.lut("A", "1", "n", "a");
        b.fdre("F", "n", "1", "0", "q", "0");
        b.inst(sub, "u", "x=n", "z=o");
        return b.nl;
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    public void testOptimizePartialInverterAbsorptionIntoChild(boolean sweep) {
        // B absorbs A's inversion and becomes a buffer of 'a', which must be routed into 'u'
        EDIFOptOptions opts = new EDIFOptOptions().setSweep(sweep);
        EDIFNetlist netlist = inverterIntoChild();
        EDIFTools.optimizeNetlist(netlist, opts);
        EDIFCell top = netlist.getTopCell();
        Assertions.assertNotNull(top.getCellInst("u"));
        Assertions.assertNotNull(top.getInternalNet("o"));
        assertOptimizedEquivalent(TestEDIFNetlistOptimizer::inverterIntoChild, opts);
    }

    /**
     * Two inverters in series; each partially absorbs into many LUT loads (the same loads move
     * twice).
     */
    private static EDIFNetlist inverterPairManyLoads(String first, String second, int loads) {
        OptProbes.B b = new OptProbes.B("inv_pair_loads");
        b.in("clk", "a", "b");
        b.out("q0", "q1");
        b.lut(first, "1", "n", "a");
        b.lut(second, "1", "m", "n");
        // Loads that cannot absorb an inversion keep both inverters
        b.fdre("F0", "n", "1", "0", "q0", "0");
        b.fdre("F1", "m", "1", "0", "q1", "0");
        for (int i = 0; i < loads; i++) {
            b.lut("L" + i, "8", "d" + i, "m", "b");
        }
        return b.nl;
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    public void testInverterAbsorptionIsLinearInMovedPins(boolean swapNames) {
        int loads = 250000;
        EDIFNetlist netlist = inverterPairManyLoads(swapNames ? "B" : "A", swapNames ? "A" : "B",
                loads);
        EDIFOptStats stats = EDIFTools.propagateConstants(netlist);
        // Every load reads 'a' through an absorbed double inversion
        EDIFCellInst l0 = netlist.getTopCell().getCellInst("L0");
        Assertions.assertEquals("a", l0.getPortInst("I0").getNet().getName());
        Assertions.assertEquals("4'h8", l0.getProperty("INIT").getValue());
        // A quadratic update of the moved pins takes several seconds here
        Assertions.assertTrue(stats.getTimes().get("Constant propagation") < 2_000_000_000L,
                stats.getTimes().toString());
    }

    /**
     * An inverter drives input I0 of a LUT6_2 whose O5 buffers I0 into a flip-flop while O6
     * stays in use.
     */
    private static EDIFNetlist lut62BufferedInvertedInput() {
        OptProbes.B b = new OptProbes.B("lut62_alias");
        b.in("b", "clk");
        b.out("q", "o6");
        b.lut("A", "1", "inv", "b");
        b.inst(Unisim.LUT6_2, "B", "I0=inv", "I1=b", "I2=0", "I3=0", "I4=0", "I5=1", "O5=n5",
                "O6=o6", "@INIT=64'h66666666AAAAAAAA");
        b.fdre("F", "n5", "1", "0", "q", "0");
        return b.nl;
    }

    @Test
    public void testOptimizeBypassedOutputKeepsItsSource() {
        // O5 is removed (its load reads 'inv'); the inverter is then absorbed by the LUT
        EDIFNetlist netlist = lut62BufferedInvertedInput();
        EDIFTools.optimizeNetlist(netlist, new EDIFOptOptions());
        EDIFNet d = netlist.getTopCell().getCellInst("F").getPortInst("D").getNet();
        Assertions.assertEquals("A", d.getPortInsts().stream().filter(EDIFPortInst::isOutput)
                .findFirst().get().getCellInst().getName());
        assertOptimizedEquivalent(TestEDIFNetlistOptimizer::lut62BufferedInvertedInput,
                new EDIFOptOptions());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    public void testSweepRemovesHierarchyItEmpties(boolean reoptimize) {
        // Sweep removes the inverter and every net of the child (its input has no other load)
        OptProbes.B b = new OptProbes.B("hier_emptied");
        b.in("a", "c");
        b.out("o");
        EDIFCell sub = b.cell("sub");
        b.in(sub, "x");
        b.lut(sub, "IV", "1", "y", "x");
        b.inst(sub, "u", "x=a");
        b.lut("L", "1", "o", "c");
        EDIFOptStats stats = EDIFTools.optimizeNetlist(b.nl, new EDIFOptOptions());
        if (reoptimize) {
            stats = EDIFTools.optimizeNetlist(b.nl, new EDIFOptOptions());
            Assertions.assertEquals(0, stats.getRemoved(EDIFOptStats.Phase.SWEEP));
        } else {
            // The inverter and the emptied instance
            Assertions.assertEquals(2, stats.getRemoved(EDIFOptStats.Phase.SWEEP));
        }
        Assertions.assertNull(b.top.getCellInst("u"));
        Assertions.assertNull(b.nl.getWorkLibrary().getCell("sub"));
    }

    @Test
    public void testBufferRemovalIsLinearInSharedInputFanout() {
        // Many buffers of the same input, each driving its own flip-flop
        OptProbes.B b = new OptProbes.B("shared_buffers");
        b.in("clk", "a");
        int buffers = 64000;
        for (int i = 0; i < buffers; i++) {
            b.lut("U" + i, "2", "n" + i, "a");
            b.fdre("F" + i, "n" + i, "1", "0", "", "0");
        }
        EDIFOptStats stats = EDIFTools.propagateConstants(b.nl);
        Assertions.assertEquals(buffers, stats.getRemoved(EDIFOptStats.Phase.CONSTANT_PROPAGATION));
        Assertions.assertEquals("a", b.top.getCellInst("F0").getPortInst("D").getNet().getName());
        // A quadratic propagation takes about 30s here
        Assertions.assertTrue(stats.getTimes().get("Constant propagation") < 3_000_000_000L,
                stats.getTimes().toString());
    }

    @Test
    public void testSweepIsLinearInFanout() {
        // One surviving flip-flop drives many dead LUTs
        OptProbes.B b = new OptProbes.B("sweep_fanout");
        b.in("clk", "a", "b");
        b.out("q");
        b.fdre("F", "a", "1", "0", "n", "0");
        b.fdre("G", "n", "1", "0", "q", "0");
        int loads = 100000;
        for (int i = 0; i < loads; i++) {
            b.lut("L" + i, "6", "d" + i, "n", "b");
        }
        EDIFOptStats stats = EDIFTools.sweepDeadLogic(b.nl);
        Assertions.assertEquals(loads, stats.getRemoved(EDIFOptStats.Phase.SWEEP));
        Assertions.assertNotNull(b.top.getCellInst("F"));
        // A quadratic sweep takes minutes here
        Assertions.assertTrue(stats.getTimes().get("Sweep") < 5_000_000_000L,
                stats.getTimes().toString());
    }

    @Test
    public void testVerilogLiterals() {
        Assertions.assertEquals(BigInteger.valueOf(10), VerilogLiterals.parseNumber("4'hA"));
        Assertions.assertEquals(BigInteger.valueOf(5), VerilogLiterals.parseNumber("3'b1_01"));
        Assertions.assertEquals(BigInteger.valueOf(12), VerilogLiterals.parseNumber("'d12"));
        Assertions.assertEquals(BigInteger.valueOf(12), VerilogLiterals.parseNumber("12"));
        // Signs, widths and signed literals
        Assertions.assertEquals(BigInteger.valueOf(-1), VerilogLiterals.parseNumber("-01"));
        Assertions.assertEquals(BigInteger.ONE, VerilogLiterals.parseNumber("+1"));
        Assertions.assertEquals(BigInteger.valueOf(-1), VerilogLiterals.parseNumber("8'shff"));
        Assertions.assertEquals(BigInteger.valueOf(127), VerilogLiterals.parseNumber("8'sh7f"));
        Assertions.assertEquals(BigInteger.valueOf(255), VerilogLiterals.parseNumber("8'hff"));
        Assertions.assertEquals(BigInteger.valueOf(-2), VerilogLiterals.parseNumber("'shfffffffe"));
        Assertions.assertEquals(BigInteger.valueOf(15), VerilogLiterals.parseNumber("4'hff"));
        // Unsized literals are at least 32 bits wide, wider if their digits need it
        Assertions.assertEquals(BigInteger.ONE.shiftLeft(32),
                VerilogLiterals.parseNumber("'sh100000000"));
        Assertions.assertEquals(Integer.valueOf(36), VerilogLiterals.parseWidth("'sh100000000"));
        Assertions.assertEquals(BigInteger.valueOf(-1), VerilogLiterals.parseNumber("'shfffffffff"));
        Assertions.assertEquals(Integer.valueOf(32), VerilogLiterals.parseWidth("'h1"));
        Assertions.assertEquals(Integer.valueOf(8), VerilogLiterals.parseWidth("8'shff"));
        Assertions.assertEquals(Integer.valueOf(-1), VerilogLiterals.parseWidth("-12"));
        Assertions.assertNull(VerilogLiterals.parseWidth("SYNC"));
        // Values and widths of unsized literals as given by Vivado's simulator (xsim 2025.1)
        String[][] xsim = {
            {"'sd2147483648", "-2147483648", "32"},
            {"'sd4294967295", "-1", "32"},
            {"'sd4294967296", "0", "32"},
            {"'sd8589934591", "-1", "32"},
            {"'sd12", "12", "32"},
            {"'d4294967296", "0", "32"},
            {"'d4294967295", "4294967295", "32"},
            {"'sh800000000", "-34359738368", "36"},
            {"'sh100000000", "4294967296", "36"},
            {"'sh0100000000", "4294967296", "40"},
            {"'sb100000000000000000000000000000000", "-4294967296", "33"},
            {"'so40000000000", "-4294967296", "33"},
        };
        for (String[] x : xsim) {
            Assertions.assertEquals(new BigInteger(x[1]), VerilogLiterals.parseNumber(x[0]), x[0]);
            Assertions.assertEquals(Integer.valueOf(x[2]), VerilogLiterals.parseWidth(x[0]), x[0]);
        }
        Assertions.assertNull(VerilogLiterals.parseNumber("1'bx"));
        Assertions.assertNull(VerilogLiterals.parseNumber("2'b12"));
        Assertions.assertNull(VerilogLiterals.parseNumber("/tmp/a.mem"));
        for (String t : new String[] {"TRUE", "true", "yes", "1", "1'b1", "1'h1", "2'b10"}) {
            Assertions.assertTrue(VerilogLiterals.isTrue(t), t);
        }
        for (String f : new String[] {"FALSE", "no", "0", "1'b0", "4'h0"}) {
            Assertions.assertEquals(Boolean.FALSE, VerilogLiterals.parseBoolean(f), f);
        }
        Assertions.assertNull(VerilogLiterals.parseBoolean("SYNC"));
    }

    @Test
    public void testRandomSimEquivalenceChecksRemovedShiftRegister() {
        // INIT is not uniform: with CE low the output stays at INIT bit 1 (address 1)
        OptProbes.B o = new OptProbes.B("srl_check");
        o.in("clk", "d");
        o.out("q");
        // D equals INIT bit 0, so a constant 0 would look inductively stable
        o.inst(Unisim.SRL16E, "S", "CLK=clk", "D=0", "CE=0", "A0=1", "A1=0", "A2=0", "A3=0",
                "Q=q", "@INIT=16'h0002");
        // A replacement that wrongly drives the output low
        OptProbes.B r = new OptProbes.B("srl_check");
        r.in("clk", "d");
        r.out("q");
        r.inst(Unisim.GND, "GND_q", "G=q");
        EDIFRandomSimEquivalence eq = new EDIFRandomSimEquivalence();
        Assertions.assertTrue(eq.check(o.nl, r.nl, 2) > 0);
    }

    @Test
    public void testRandomSimEquivalenceComparesSignedIntegers() {
        java.util.function.Function<String, EDIFNetlist> build = value -> {
            OptProbes.B b = new OptProbes.B("signed_property");
            b.in("a");
            b.out("q");
            EDIFCell bb = b.cell("opaque");
            bb.createPort("D", EDIFDirection.INPUT, 1);
            bb.createPort("Q", EDIFDirection.OUTPUT, 1);
            EDIFCellInst inst = b.inst(bb, "X", "D=a", "Q=q");
            inst.addProperty("OFFSET", value, EDIFValueType.INTEGER);
            return b.nl;
        };
        for (String[] same : new String[][] {{"-1", "-01"}, {"+1", "1"}, {"8'shff", "-1"}}) {
            Assertions.assertEquals(0, new EDIFRandomSimEquivalence().check(build.apply(same[0]),
                    build.apply(same[1]), 2), same[0]);
        }
        for (String[] diff : new String[][] {{"8'shff", "255"}, {"8'shff", "8'hff"}}) {
            Assertions.assertTrue(new EDIFRandomSimEquivalence().check(build.apply(diff[0]),
                    build.apply(diff[1]), 2) > 0, diff[0]);
        }
    }

    @Test
    public void testRandomSimEquivalenceComparesBitVectorsAtDeclaredWidth() {
        // SRVAL_A of a RAMB36E5 is a 36-bit HEX parameter
        java.util.function.Function<String, EDIFNetlist> build = value -> {
            OptProbes.B b = new OptProbes.B("vector_property");
            b.in("clk");
            b.inst(Unisim.RAMB36E5, "RAM", "CLKARDCLK=clk", "@SRVAL_A=" + value);
            return b.nl;
        };
        for (String[] same : new String[][] {{"36'shfffffffff", "36'hfffffffff"},
                {"8'shff", "36'hfffffffff"}, {"'sh100000000", "36'h100000000"},
                {"'sd2147483648", "36'hf80000000"},
                {"36'h0_0000_00FF", "8'hff"}}) {
            EDIFRandomSimEquivalence eq = new EDIFRandomSimEquivalence();
            Assertions.assertEquals(0, eq.check(build.apply(same[0]), build.apply(same[1]), 2),
                    same[0] + " " + eq.getExamples());
        }
        for (String[] diff : new String[][] {{"'sh100000000", "36'h000000000"},
                {"8'hff", "36'hfffffffff"}, {"'sd2147483648", "36'h080000000"}}) {
            Assertions.assertTrue(new EDIFRandomSimEquivalence().check(build.apply(diff[0]),
                    build.apply(diff[1]), 2) > 0, diff[0]);
        }
    }

    /**
     * q = a ^ b ^ c through a LUT whose first input (m, a buffer of n) is initially unused: once
     * the buffer is removed, that input represents n again.
     */
    private static EDIFNetlist reselectedDroppedPin() {
        OptProbes.B b = new OptProbes.B("dropped_pin");
        b.in("a", "b", "c");
        b.out("q");
        b.lut("A", "3C", "q", "m", "n", "c");
        b.lut("G", "6", "n", "a", "b");
        b.lut("Z", "2", "m", "n");
        return b.nl;
    }

    @Test
    public void testOptimizeRestoresReselectedInputPin() {
        EDIFNetlist netlist = reselectedDroppedPin();
        EDIFTools.optimizeNetlist(netlist, new EDIFOptOptions());
        EDIFCell top = netlist.getTopCell();
        Assertions.assertNotNull(top.getCellInst("G"));
        EDIFCellInst a = top.getCellInst("A");
        Assertions.assertEquals("LUT2", a.getCellType().getName());
        for (EDIFPortInst pi : a.getPortInsts()) {
            Assertions.assertNotNull(pi.getNet(), pi.getName());
        }
        assertOptimizedEquivalent(TestEDIFNetlistOptimizer::reselectedDroppedPin,
                new EDIFOptOptions());
        assertOptimizedEquivalent(TestEDIFNetlistOptimizer::reselectedDroppedPin,
                new EDIFOptOptions().setSweep(false));
    }

    /** A set/preset flip-flop or latch without INIT (default 1) that can never change. */
    private static EDIFNetlist heldDefaultInit(Unisim type) {
        OptProbes.B b = new OptProbes.B("default_init");
        b.in("clk", "d");
        b.out("q");
        if (type == Unisim.LDPE) {
            b.inst(type, "F", "G=clk", "GE=0", "PRE=0", "D=d", "Q=q");
        } else {
            b.inst(type, "F", "C=clk", "CE=0", (type == Unisim.FDPE ? "PRE" : "S") + "=0", "D=d",
                    "Q=q");
        }
        return b.nl;
    }

    @ParameterizedTest
    @EnumSource(value = Unisim.class, names = {"FDSE", "FDPE", "LDPE"})
    public void testDefaultInitOfSetFlipFlops(Unisim type) {
        // The optimizer and the checker both use the primitive's default INIT (1)
        EDIFNetlist opt = heldDefaultInit(type);
        EDIFTools.propagateConstants(opt);
        Assertions.assertTrue(opt.getTopCell().getInternalNet("q").isVCC());
        Assertions.assertEquals(0, new EDIFRandomSimEquivalence().check(heldDefaultInit(type),
                opt, 4));
        OptProbes.B gnd = new OptProbes.B("default_init");
        gnd.in("clk", "d");
        gnd.out("q");
        gnd.inst(Unisim.GND, "G", "G=q");
        Assertions.assertTrue(new EDIFRandomSimEquivalence().check(heldDefaultInit(type), gnd.nl,
                4) > 0);
    }

    @Test
    public void testPrimitiveDefaultsMatchDeclaredDefaults() {
        for (Unisim u : new Unisim[] {Unisim.FDRE, Unisim.FDSE, Unisim.FDCE, Unisim.FDPE,
                Unisim.LDCE, Unisim.LDPE, Unisim.SRL16E, Unisim.SRLC32E}) {
            EDIFNetlist nl = EDIFTools.createNewNetlist("defaults");
            String ours = PrimitiveDefaults.getDefault(nl.getHDIPrimitive(u), "INIT");
            String declared = com.xilinx.rapidwright.design.Design.getDefaultCellProperties(
                    com.xilinx.rapidwright.device.Series.Versal, u.name()).get("INIT").getValue();
            Assertions.assertEquals(VerilogLiterals.parseNumber(declared),
                    VerilogLiterals.parseNumber(ours), u.name());
        }
        // A user cell named like a primitive has no defaults
        EDIFNetlist nl = EDIFTools.createNewNetlist("defaults");
        Assertions.assertNull(PrimitiveDefaults.getDefault(new EDIFCell(nl.getWorkLibrary(),
                "FDSE"), "INIT"));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    public void testOptimizeKeepsPreservedStaticSource(boolean sweep) {
        // The LUT's only use of the preserved ground simplifies away; the ground stays (as in
        // Vivado, which removes its net)
        OptProbes.B b = new OptProbes.B("preserve");
        b.in("a");
        b.out("o");
        b.inst(Unisim.GND, "G", "G=z", "@DONT_TOUCH=TRUE");
        b.lut("L", "E", "o", "a", "z");
        EDIFTools.optimizeNetlist(b.nl, new EDIFOptOptions().setSweep(sweep));
        EDIFCellInst g = b.top.getCellInst("G");
        Assertions.assertNotNull(g);
        Assertions.assertNull(b.top.getCellInst("L"));
        Assertions.assertSame(b.top.getInternalNet("a"), b.top.getInternalNet("o"));
    }

    @Test
    public void testSweepIsLinearInPreservedNetFanout() {
        // A preserved (DONT_TOUCH) net keeps all its loads live; each live load used to rescan
        // the whole net for its drivers
        OptProbes.B b = new OptProbes.B("dt_fanout");
        b.in("clk", "a", "c");
        b.fdre("F", "a", "1", "0", "n", "0");
        b.net(b.top, "n").addProperty("DONT_TOUCH", "TRUE");
        int loads = 100000;
        for (int i = 0; i < loads; i++) {
            b.lut("L" + i, "6", "d" + i, "n", "c");
        }
        EDIFOptStats stats = EDIFTools.sweepDeadLogic(b.nl);
        Assertions.assertEquals(0, stats.getRemoved(EDIFOptStats.Phase.SWEEP));
        Assertions.assertEquals(loads + 1, b.top.getNet("n").getPortInsts().size());
        // A quadratic sweep takes minutes here
        Assertions.assertTrue(stats.getTimes().get("Sweep") < 3_000_000_000L,
                stats.getTimes().toString());
    }

    @Test
    public void testEditsAreLinearInHierarchicalFanout() {
        // Net 'a' and its inverse 'n' reach every instance of a shared child; inside each one, a
        // LUT absorbs the inverter and moves onto 'a' (a top-level flip-flop keeps the inverter)
        OptProbes.B b = new OptProbes.B("hier_fanout");
        b.in("clk", "a");
        b.out("q");
        EDIFCell sub = b.cell("sub");
        b.in(sub, "x", "w");
        b.out(sub, "z");
        b.lut(sub, "L", "6", "z", "x", "w");
        b.lut("A", "1", "n", "a");
        b.fdre("F", "n", "1", "0", "q", "0");
        int subs = 20000;
        for (int i = 0; i < subs; i++) {
            b.out("o" + i);
            b.inst(sub, "u" + i, "x=n", "w=a", "z=o" + i);
        }
        Supplier<EDIFNetlist> small = () -> {
            OptProbes.B c = new OptProbes.B("hier_fanout");
            c.in("clk", "a");
            c.out("q");
            EDIFCell cs = c.cell("sub");
            c.in(cs, "x", "w");
            c.out(cs, "z");
            c.lut(cs, "L", "6", "z", "x", "w");
            c.lut("A", "1", "n", "a");
            c.fdre("F", "n", "1", "0", "q", "0");
            for (int i = 0; i < 3; i++) {
                c.out("o" + i);
                c.inst(cs, "u" + i, "x=n", "w=a", "z=o" + i);
            }
            return c.nl;
        };
        assertOptimizedEquivalent(small, new EDIFOptOptions());
        EDIFOptStats stats = EDIFTools.optimizeNetlist(b.nl, new EDIFOptOptions());
        EDIFCellInst l = b.top.getCellInst("u0").getCellType().getCellInst("L");
        Assertions.assertEquals("4'h9", l.getProperty("INIT").getValue());
        // Searching all segments of 'a' for every instance takes minutes here
        Assertions.assertTrue(stats.getTimes().get("Apply edits") < 5_000_000_000L,
                stats.getTimes().toString());
    }

    @Test
    public void testBufferChainMergesAreLinear() {
        // A chain of buffers, each stage also loading a flip-flop: every stage's net merges into
        // the next one towards the input
        OptProbes.B b = new OptProbes.B("chain");
        b.in("a", "clk");
        int size = 32000;
        for (int i = 0; i < size; i++) {
            String in = String.format("n%06d", i + 1);
            String out = String.format("n%06d", i);
            b.lut("B" + i, "2", out, i == size - 1 ? "a" : in);
            b.fdre("F" + i, out, "1", "0", "", "0");
        }
        EDIFOptStats stats = EDIFTools.propagateConstants(b.nl);
        Assertions.assertEquals(size, stats.getRemoved(EDIFOptStats.Phase.CONSTANT_PROPAGATION));
        // The input port and every flip-flop
        Assertions.assertEquals(size + 1, b.top.getInternalNet("a").getPortInsts().size());
        // Moving the accumulated loads at every merge takes over a minute here
        Assertions.assertTrue(stats.getTimes().get("Apply edits") < 5_000_000_000L,
                stats.getTimes().toString());
    }

    @Test
    public void testComparatorsCheckTopLevelInterface() {
        Supplier<OptProbes.B> base = () -> {
            OptProbes.B b = new OptProbes.B("ports");
            b.in("a");
            b.out("o");
            b.lut("L", "2", "o", "a");
            return b;
        };
        // An extra (connected) output, an extra input, an output of another width
        OptProbes.B extraOut = base.get();
        EDIFPort extra = extraOut.top.createPort("extra", EDIFDirection.OUTPUT, 1);
        extraOut.top.getNet("o").createPortInst(extra);
        OptProbes.B extraIn = base.get();
        extraIn.in("b");
        OptProbes.B wide = base.get();
        wide.top.createPort("w[1:0]", EDIFDirection.OUTPUT, 2);
        OptProbes.B wide1 = base.get();
        wide1.top.createPort("w[0:0]", EDIFDirection.OUTPUT, 1);
        for (OptProbes.B[] pair : new OptProbes.B[][] {{base.get(), extraOut},
                {base.get(), extraIn}, {extraOut, base.get()}, {wide, wide1}}) {
            Assertions.assertTrue(new EDIFLeafNetlistComparator().compare(pair[0].nl,
                    pair[1].nl) > 0);
            Assertions.assertTrue(new EDIFRandomSimEquivalence().check(pair[0].nl, pair[1].nl,
                    2) > 0);
        }
        Assertions.assertEquals(0, new EDIFLeafNetlistComparator().compare(base.get().nl,
                base.get().nl));
    }

    @Test
    public void testRandomSimEquivalenceIsLinearInFanout() {
        // A LUT drives many LUTs, each feeding a flip-flop (observed cut cells)
        Supplier<EDIFNetlist> s = () -> {
            OptProbes.B b = new OptProbes.B("sim_fanout");
            b.in("clk", "a", "c");
            b.lut("G", "6", "n", "a", "c");
            for (int i = 0; i < 150000; i++) {
                b.lut("L" + i, "8", "d" + i, "n", "c");
                b.fdre("F" + i, "d" + i, "1", "0", "", "0");
            }
            return b.nl;
        };
        EDIFNetlist a = s.get();
        EDIFNetlist b = s.get();
        long t = System.nanoTime();
        EDIFRandomSimEquivalence eq = new EDIFRandomSimEquivalence();
        Assertions.assertEquals(0, eq.check(a, b, 1));
        // Looking up the driver of the shared net once per load takes about 40s here
        Assertions.assertTrue(System.nanoTime() - t < 10_000_000_000L);
    }

    /**
     * A ripple counter whose first stage has its clock tied high (or, if gated, gated off by a
     * constant enable): no stage ever sees a clock edge, so each holds its INIT (1, also the
     * preset value).
     */
    private static EDIFNetlist stoppedRippleCounter(boolean invertedClocks, boolean gated) {
        OptProbes.B b = new OptProbes.B("ripple");
        b.in("p", "clk");
        b.out("o");
        String clk = "1";
        if (gated) {
            // Clock gate: clk AND enable, the enable being constant 0
            b.lut("G", "8", "gclk", "clk", "0");
            clk = "gclk";
        }
        for (int i = 0; i < 3; i++) {
            b.lut("N" + i, "1", "d" + i, "q" + i);
            b.inst(Unisim.FDPE, "S" + i, "C=" + clk, "CE=1", "D=d" + i, "PRE=p", "Q=q" + i,
                    "@INIT=1'b1");
            clk = "q" + i;
            if (invertedClocks) {
                // The next stage is clocked through an inverter
                b.lut("K" + i, "1", "k" + i, clk);
                clk = "k" + i;
            }
        }
        b.lut("X", "96", "o", "q0", "q1", "q2");
        return b.nl;
    }

    @ParameterizedTest
    @CsvSource({"false,false", "true,false", "false,true"})
    public void testOptimizeRemovesStoppedRippleCounter(boolean invertedClocks, boolean gated) {
        // The optimizer removes every stage as constant; the checker must accept it (a clock
        // driven, possibly through logic, by stages assumed constant never ticks either)
        EDIFNetlist opt = stoppedRippleCounter(invertedClocks, gated);
        EDIFTools.optimizeNetlist(opt, new EDIFOptOptions());
        Assertions.assertNull(opt.getTopCell().getCellInst("S2"));
        Assertions.assertTrue(opt.getTopCell().getInternalNet("o").isVCC());
        EDIFRandomSimEquivalence eq = new EDIFRandomSimEquivalence();
        Assertions.assertEquals(0, eq.check(stoppedRippleCounter(invertedClocks, gated), opt, 4),
                eq.getExamples().toString());
        Assertions.assertEquals(0, eq.getRemovedNonConstant());
    }

    private static String optimizeWithReport(EDIFNetlist netlist, EDIFOptOptions opts,
                                             EDIFOptStats[] stats) {
        java.io.StringWriter w = new java.io.StringWriter();
        stats[0] = EDIFTools.optimizeNetlist(netlist, opts.setReport(w));
        return w.toString();
    }

    private static List<String> reportLines(String report, String action) {
        List<String> lines = new java.util.ArrayList<>();
        for (String line : report.split("\n")) {
            if (line.startsWith(action + "\t")) {
                lines.add(line);
            }
        }
        return lines;
    }

    @ParameterizedTest
    @MethodSource("optProbeNames")
    public void testReportAccountsForEveryRemovedCell(String probe) {
        // Removed cells, resized LUTs (counted as removed and created) and removed hierarchy
        // (with nested instances) add up to the cells removed in the statistics
        EDIFOptStats[] stats = new EDIFOptStats[1];
        String report = optimizeWithReport(OptProbes.getProbes().get(probe).get(),
                new EDIFOptOptions(), stats);
        int removed = 0;
        for (EDIFOptStats.Phase phase : EDIFOptStats.Phase.values()) {
            removed += stats[0].getRemoved(phase);
        }
        int lines = 0;
        for (String action : new String[] {"REMOVED_CONSTANT", "REMOVED_BUFFER",
                "REMOVED_INVERTER", "REMOVED_UNLOADED", "SWEPT", "LUT_RESIZED"}) {
            lines += reportLines(report, action).size();
        }
        for (String line : reportLines(report, "HIERARCHY_REMOVED")) {
            java.util.regex.Matcher m = java.util.regex.Pattern.compile("with (\\d+) nested")
                    .matcher(line);
            lines += 1 + (m.find() ? Integer.parseInt(m.group(1)) : 0);
        }
        Assertions.assertEquals(removed, lines, report);
        // The summary matches the lines
        for (String line : report.split("\n")) {
            if (line.startsWith("# SUMMARY")) {
                String[] f = line.split("\t");
                Assertions.assertEquals(Integer.parseInt(f[2]), reportLines(report, f[1]).size());
            }
        }
    }

    @Test
    public void testReportExplainsDecisions() {
        OptProbes.B b = new OptProbes.B("report");
        b.in("clk", "a", "b", "c", "r");
        b.out("o1", "o2", "o3", "q1", "q2");
        // Constant: an AND with a ground input
        b.lut("K", "8", "o1", "a", "0");
        // Resized: I1 & (I0 ^ I3), with I1 constant and I2 unused by the function
        b.lut("S", "4488", "o2", "a", "1", "c", "b");
        // Buffer
        b.lut("U", "2", "m", "b");
        b.lut("V", "6", "o3", "m", "c");
        // Flip-flop that can only hold its INIT (CE low, no reset)
        b.fdre("F", "a", "0", "0", "q1", "0");
        // Inverter absorbed by a reset pin
        b.lut("IV", "1", "rn", "r");
        b.fdre("G", "a", "1", "rn", "q2", "0");
        // Dead logic
        b.lut("D", "6", "dead", "a", "b");
        EDIFOptStats[] stats = new EDIFOptStats[1];
        String report = optimizeWithReport(b.nl, new EDIFOptOptions(), stats);
        Assertions.assertTrue(report.contains("REMOVED_CONSTANT\tK\tLUT2\tO constant 0: constant"
                + " inputs I1=0 from "), report);
        Assertions.assertTrue(report.contains("LUT_RESIZED\tS\tLUT4\tLUT4 INIT=16'h4488 -> LUT2 "
                + "INIT=4'h6: I0->I0, I1 dropped (constant 1 from VCC/P (VCC)), I2 dropped "
                + "(function does not depend on it), I3->I1"), report);
        Assertions.assertTrue(report.contains("REMOVED_BUFFER\tU\tLUT1\tbuffer; O merged into b"
                + " (from I0)"), report);
        Assertions.assertTrue(report.contains("REMOVED_CONSTANT\tF\tFDRE\tQ constant 0: holds "
                + "INIT=0, R never active, CE constant 0"), report);
        Assertions.assertTrue(report.contains("REMOVED_INVERTER\tIV\tLUT1\tinverter absorbed by"
                + " all its 1 loads; O merged into r"), report);
        Assertions.assertTrue(report.contains("PIN_INVERTED\tG\tFDRE\tIS_R_INVERTED=1'b1, "
                + "absorbed inverter IV"), report);
        Assertions.assertTrue(report.contains("SWEPT\tD\tLUT2\tno path to a top-level output"),
                report);
        Assertions.assertTrue(report.contains("SOURCE_ADDED\t"), report);
        Assertions.assertTrue(report.contains("# SUMMARY\tSWEPT\t1"), report);
    }

    @Test
    public void testReportShowsDefinitionEdits() {
        // u0 gets a constant input, u1 does not: 'sub' is copied for u0
        EDIFOptStats[] stats = new EDIFOptStats[1];
        String report = optimizeWithReport(OptProbes.getProbes().get("hier_const_in_shared").get(),
                new EDIFOptOptions(), stats);
        Assertions.assertTrue(report.contains("DEFINITION_COPIED\tsub_HD1\tsub\tcopy of sub for "
                + "instances that need different edits, e.g. u0"), report);
        // No report is written unless requested
        Assertions.assertNull(new EDIFOptOptions().getReport());
    }

    /** Two I/O buffers driving INOUT ports; swapped connects each buffer to the other port. */
    private static EDIFNetlist ioBuffers(boolean swapped) {
        OptProbes.B b = new OptProbes.B("iobufs");
        b.in("a", "b");
        for (String io : new String[] {"io0", "io1"}) {
            EDIFPort port = b.top.createPort(io, EDIFDirection.INOUT, 1);
            b.net(b.top, io).createPortInst(port);
        }
        b.inst(Unisim.IOBUF, "IOB0", "I=a", "T=0", "IO=" + (swapped ? "io1" : "io0"));
        b.inst(Unisim.IOBUF, "IOB1", "I=b", "T=0", "IO=" + (swapped ? "io0" : "io1"));
        return b.nl;
    }

    @Test
    public void testLeafComparatorSeesBidirectionalConnections() {
        Assertions.assertEquals(0, new EDIFLeafNetlistComparator().compare(ioBuffers(false),
                ioBuffers(false)));
        Assertions.assertTrue(new EDIFLeafNetlistComparator().compare(ioBuffers(false),
                ioBuffers(true)) > 0);
    }

    /** A user-defined cell of the given library and name, with ports I0, I1 and O. */
    private static EDIFCell userCell(EDIFNetlist nl, String library, String name) {
        EDIFLibrary lib = nl.getLibrary(library);
        if (lib == null) {
            lib = nl.addLibrary(new EDIFLibrary(library));
        }
        EDIFCell cell = new EDIFCell(lib, name);
        cell.createPort("I0", EDIFDirection.INPUT, 1);
        cell.createPort("I1", EDIFDirection.INPUT, 1);
        cell.createPort("O", EDIFDirection.OUTPUT, 1);
        return cell;
    }

    private static EDIFNetlist userCellNetlist(String library, String name, boolean swapInputs) {
        OptProbes.B b = new OptProbes.B("user_cells");
        b.in("a", "b");
        b.out("o");
        b.inst(userCell(b.nl, library, name), "U", "I0=" + (swapInputs ? "b" : "a"),
                "I1=" + (swapInputs ? "a" : "b"), "O=o", "@INIT=4'h6");
        return b.nl;
    }

    @Test
    public void testLeafComparatorQualifiesUserCellTypes() {
        // Same cell name in another library: a different type
        Assertions.assertTrue(new EDIFLeafNetlistComparator().compare(
                userCellNetlist("work", "opaque", false),
                userCellNetlist("libB", "opaque", false)) > 0);
        // A black box named like a LUT, with its inputs swapped: not an equivalent LUT
        Assertions.assertTrue(new EDIFLeafNetlistComparator().compare(
                userCellNetlist("work", "LUT2", false),
                userCellNetlist("work", "LUT2", true)) > 0);
        Assertions.assertEquals(0, new EDIFLeafNetlistComparator().compare(
                userCellNetlist("work", "opaque", false),
                userCellNetlist("work", "opaque", false)));
    }

    @Test
    public void testLeafComparatorComparesPropertiesOfEquivalentLuts() {
        OptProbes.B g = new OptProbes.B("lut_props");
        g.in("a", "b");
        g.out("o");
        g.lut("L", "6", "o", "a", "b");
        // An equivalent LUT3 (I2 duplicates I0) that is DONT_TOUCH
        OptProbes.B t = new OptProbes.B("lut_props");
        t.in("a", "b");
        t.out("o");
        t.lut("L", "66", "o", "a", "b", "a").addProperty("DONT_TOUCH", "TRUE");
        EDIFLeafNetlistComparator cmp = new EDIFLeafNetlistComparator();
        Assertions.assertEquals(1, cmp.compare(g.nl, t.nl));
        Assertions.assertEquals(1, cmp.getDiffCount(EDIFLeafNetlistComparator.Category.PROPERTY));
        Assertions.assertEquals(1,
                cmp.getDiffCount(EDIFLeafNetlistComparator.Category.LUT_EQUIVALENT));
    }

    /**
     * An FDCE with INIT=1 and CE low, cleared by a NAND of six inputs (usually but not always
     * active), or by a tied-high clear.
     */
    private static EDIFNetlist clearedFlipFlop(String prefix, boolean tiedClear) {
        OptProbes.B b = new OptProbes.B("cleared_ff");
        b.in("clk", "d");
        b.out("q");
        String[] ins = new String[6];
        for (int i = 0; i < 6; i++) {
            ins[i] = prefix + i;
            b.in(ins[i]);
        }
        b.lut("N", "7FFFFFFFFFFFFFFF", "clr", ins);
        b.inst(Unisim.FDCE, "F", "C=clk", "CE=0", "D=d", "CLR=" + (tiedClear ? "1" : "clr"),
                "Q=q", "@INIT=1'b1");
        return b.nl;
    }

    private static EDIFNetlist groundedOutput(String prefix) {
        OptProbes.B b = new OptProbes.B("cleared_ff");
        b.in("clk", "d");
        b.out("q");
        for (int i = 0; i < 6; i++) {
            b.in(prefix + i);
        }
        b.inst(Unisim.GND, "G", "G=q");
        return b.nl;
    }

    @Test
    public void testRandomSimEquivalenceNeedsAlwaysActiveAsyncClear() {
        // The flip-flop is 1 until the clear first becomes active: replacing it by ground is
        // wrong, even when the clear happens to be active in all sampled patterns
        for (int i = 0; i < 20; i++) {
            String prefix = "s" + i + "_";
            EDIFRandomSimEquivalence eq = new EDIFRandomSimEquivalence();
            Assertions.assertTrue(eq.check(clearedFlipFlop(prefix, false), groundedOutput(prefix),
                    2) > 0, prefix);
        }
        // With the clear tied active, it is constant 0
        EDIFRandomSimEquivalence eq = new EDIFRandomSimEquivalence();
        Assertions.assertEquals(0, eq.check(clearedFlipFlop("s_", true), groundedOutput("s_"),
                2), eq.getExamples().toString());
    }

    private static EDIFNetlist dsp(String aluModeInverted) {
        OptProbes.B b = new OptProbes.B("dsp");
        b.in("clk", "a");
        EDIFCellInst d = b.inst(Unisim.DSP58, "D", "CLK=clk",
                "@IS_ALUMODE_INVERTED=" + aluModeInverted);
        b.net(b.top, "a").createPortInst("ALUMODE", 0, d);
        return b.nl;
    }

    @Test
    public void testRandomSimEquivalenceComparesBusInversionMasks() {
        // A bus pin's inversion mask is not simulated, so a change must be reported
        Assertions.assertEquals(0, new EDIFRandomSimEquivalence().check(dsp("4'b0000"),
                dsp("4'b0000"), 2));
        Assertions.assertTrue(new EDIFRandomSimEquivalence().check(dsp("4'b0000"),
                dsp("4'b0101"), 2) > 0);
    }

    /**
     * A preserved instance with an input connected outside but unused inside (driven by D) and
     * an output connected outside but undriven inside (loading E).
     */
    private static EDIFNetlist preservedUnusedPorts() {
        OptProbes.B b = new OptProbes.B("dt_port");
        b.in("a", "b", "c");
        b.out("o", "p");
        EDIFCell sub = b.cell("sub");
        b.in(sub, "y");
        b.out(sub, "z");
        sub.createPort("x", EDIFDirection.INPUT, 1);
        sub.createPort("w", EDIFDirection.OUTPUT, 1);
        b.lut(sub, "SL", "1", "z", "y");
        b.lut("D", "6", "dx", "a", "b");
        b.inst(sub, "u0", "x=dx", "y=c", "z=o", "w=wn").addProperty("DONT_TOUCH", "TRUE");
        b.lut("E", "6", "p", "wn", "a");
        return b.nl;
    }

    @Test
    public void testOptimizeKeepsUnusedPortsOfPreservedInstance() {
        // As in Vivado (2025.1): D, E and both connections of u0 are kept
        EDIFNetlist netlist = preservedUnusedPorts();
        EDIFOptStats stats = EDIFTools.optimizeNetlist(netlist, new EDIFOptOptions());
        EDIFCell top = netlist.getTopCell();
        Assertions.assertEquals(0, stats.getRemoved(EDIFOptStats.Phase.SWEEP));
        Assertions.assertNotNull(top.getCellInst("D"));
        EDIFCellInst u0 = top.getCellInst("u0");
        Assertions.assertSame(top.getCellInst("D").getPortInst("O").getNet(),
                u0.getPortInst("x").getNet());
        Assertions.assertSame(top.getCellInst("E").getPortInst("I0").getNet(),
                u0.getPortInst("w").getNet());
    }

    @Test
    public void testOrphanNetRemovalIsLinearInFanout() {
        // A preserved flip-flop survives while all its loads are swept: its net is removed
        OptProbes.B b = new OptProbes.B("orphan");
        b.in("clk", "a", "c");
        b.fdre("F", "a", "1", "0", "n", "0").addProperty("DONT_TOUCH", "TRUE");
        int loads = 400000;
        for (int i = 0; i < loads; i++) {
            b.lut("L" + i, "6", "d" + i, "n", "c");
        }
        EDIFOptStats stats = EDIFTools.optimizeNetlist(b.nl, new EDIFOptOptions());
        Assertions.assertEquals(loads, stats.getRemoved(EDIFOptStats.Phase.SWEEP));
        Assertions.assertNull(b.top.getCellInst("F").getPortInst("Q"));
        // Disconnecting the pins one at a time takes about 7s here (2s otherwise)
        Assertions.assertTrue(stats.getTimes().get("Apply edits") < 5_000_000_000L,
                stats.getTimes().toString());
    }

    @Test
    public void testComparatorsResetBetweenComparisons() {
        Supplier<EDIFNetlist> s = OptProbes.getProbes().get("lut_chain");
        EDIFNetlist bad = s.get();
        bad.getTopCell().getCellInst("C3").addProperty("INIT", "4'h9");
        EDIFRandomSimEquivalence eq = new EDIFRandomSimEquivalence();
        Assertions.assertTrue(eq.check(s.get(), bad, 2) > 0);
        Assertions.assertEquals(0, eq.check(s.get(), s.get(), 2));
        Assertions.assertTrue(eq.getExamples().isEmpty());
        EDIFLeafNetlistComparator cmp = new EDIFLeafNetlistComparator();
        Assertions.assertTrue(cmp.compare(s.get(), bad) > 0);
        Assertions.assertEquals(0, cmp.compare(s.get(), s.get()));
        Assertions.assertEquals(0, cmp.getTotalDiffs());
    }

    @Test
    public void testRandomSimEquivalenceChecksCutCells() {
        // A surviving flip-flop loses a connected input
        Supplier<EDIFNetlist> ff = () -> {
            OptProbes.B b = new OptProbes.B("ff_check");
            b.in("clk", "a", "r");
            b.out("q");
            b.fdre("F", "a", "1", "r", "q", "0");
            return b.nl;
        };
        EDIFNetlist noReset = ff.get();
        EDIFCellInst f = noReset.getTopCell().getCellInst("F");
        EDIFPortInst r = f.getPortInst("R");
        r.getNet().removePortInst(r);
        f.removePortInst(r);
        EDIFRandomSimEquivalence eq = new EDIFRandomSimEquivalence();
        Assertions.assertTrue(eq.check(ff.get(), noReset, 2) > 0);
        Assertions.assertEquals(0, new EDIFRandomSimEquivalence().check(ff.get(), ff.get(), 2));
        // A surviving flip-flop changes its initial value
        EDIFNetlist init1 = ff.get();
        init1.getTopCell().getCellInst("F").addProperty("INIT", "1'b1");
        Assertions.assertTrue(new EDIFRandomSimEquivalence().check(ff.get(), init1, 2) > 0);
        // A surviving flip-flop changes its type
        OptProbes.B fdse = new OptProbes.B("ff_check");
        fdse.in("clk", "a", "r");
        fdse.out("q");
        fdse.inst(Unisim.FDSE, "F", "C=clk", "D=a", "CE=1", "S=r", "Q=q", "@INIT=1'b0");
        Assertions.assertTrue(new EDIFRandomSimEquivalence().check(ff.get(), fdse.nl, 2) > 0);
        // A surviving memory reads another initialization file (paths are case-sensitive)
        Supplier<EDIFNetlist> ram = () -> {
            OptProbes.B b = new OptProbes.B("ram_check");
            b.in("clk");
            b.inst(Unisim.RAMB18E5, "M", "CLKARDCLK=clk", "@INIT_FILE=/tmp/a.mem");
            return b.nl;
        };
        EDIFNetlist otherFile = ram.get();
        otherFile.getTopCell().getCellInst("M").addProperty("INIT_FILE", "/tmp/A.mem");
        Assertions.assertTrue(new EDIFRandomSimEquivalence().check(ram.get(), otherFile, 2) > 0);
        Assertions.assertEquals(0, new EDIFRandomSimEquivalence().check(ram.get(), ram.get(), 2));
        // File names that look like booleans or numbers are still compared exactly
        for (String[] names : new String[][] {{"true", "TRUE"}, {"8'h1", "8'h01"}}) {
            EDIFNetlist x = ram.get();
            EDIFNetlist y = ram.get();
            x.getTopCell().getCellInst("M").addProperty("INIT_FILE", names[0]);
            y.getTopCell().getCellInst("M").addProperty("INIT_FILE", names[1]);
            Assertions.assertTrue(new EDIFRandomSimEquivalence().check(x, y, 2) > 0, names[0]);
        }
        // Declared numeric properties are compared by value
        EDIFNetlist hex = ram.get();
        hex.getTopCell().getCellInst("M").addProperty("SRVAL_A", "18'h00001");
        EDIFNetlist bin = ram.get();
        bin.getTopCell().getCellInst("M").addProperty("SRVAL_A", "18'b1");
        Assertions.assertEquals(0, new EDIFRandomSimEquivalence().check(hex, bin, 2));
        EDIFNetlist other = ram.get();
        other.getTopCell().getCellInst("M").addProperty("SRVAL_A", "18'h00002");
        Assertions.assertTrue(new EDIFRandomSimEquivalence().check(hex, other, 2) > 0);
        // A user-defined black box named like a primitive does not get its property types
        Supplier<EDIFNetlist> userFdre = () -> {
            OptProbes.B b = new OptProbes.B("user_fdre");
            b.in("a");
            b.out("q");
            EDIFCell bb = b.cell("FDRE");
            bb.createPort("D", EDIFDirection.INPUT, 1);
            bb.createPort("Q", EDIFDirection.OUTPUT, 1);
            b.inst(bb, "F", "D=a", "Q=q", "@INIT=0");
            return b.nl;
        };
        EDIFNetlist userInit = userFdre.get();
        userInit.getTopCell().getCellInst("F").addProperty("INIT", "1'b0");
        Assertions.assertTrue(new EDIFRandomSimEquivalence().check(userFdre.get(), userInit, 2) > 0);
        Assertions.assertEquals(0, new EDIFRandomSimEquivalence().check(userFdre.get(),
                userFdre.get(), 2));
        // A user-defined cell does not match the primitive of the same name
        EDIFNetlist primitive = ff.get();
        EDIFNetlist user = ff.get();
        EDIFCell bb = new EDIFCell(user.getWorkLibrary(), "FDRE");
        for (EDIFPort port : primitive.getHDIPrimitive(Unisim.FDRE).getPorts()) {
            bb.createPort(port.getName(), port.getDirection(), 1);
        }
        user.getTopCell().getCellInst("F").setCellType(bb);
        Assertions.assertTrue(new EDIFRandomSimEquivalence().check(primitive, user, 2) > 0);
        // An unknown part only disables declared property types
        Supplier<EDIFNetlist> unknownPart = () -> {
            EDIFNetlist n = ram.get();
            n.getDesign().addProperty(EDIFTools.EDIF_PART_PROP, "xcnosuchpart-ffvb1234-1-e");
            return n;
        };
        Assertions.assertEquals(0, new EDIFRandomSimEquivalence().check(unknownPart.get(),
                unknownPart.get(), 2));
        EDIFNetlist unknownOther = unknownPart.get();
        unknownOther.getTopCell().getCellInst("M").addProperty("INIT_FILE", "/tmp/A.mem");
        Assertions.assertTrue(new EDIFRandomSimEquivalence().check(unknownPart.get(),
                unknownOther, 2) > 0);
    }

    @Test
    public void testSweepDeadLogicOnly() {
        EDIFNetlist netlist = OptProbes.getProbes().get("sweep_unloaded").get();
        EDIFOptStats stats = EDIFTools.sweepDeadLogic(netlist);
        Assertions.assertEquals(4, stats.getRemoved(EDIFOptStats.Phase.SWEEP));
        Assertions.assertEquals(1, netlist.getTopCell().getCellInsts().stream()
                .filter(i -> !i.getCellType().isStaticSource()).count());
        EDIFNetlist loop = OptProbes.getProbes().get("sweep_loop").get();
        Assertions.assertEquals(2, EDIFTools.sweepDeadLogic(loop)
                .getRemoved(EDIFOptStats.Phase.SWEEP));
    }

    @Test
    public void testPropagateConstantsDoesNotSweep() {
        EDIFNetlist netlist = OptProbes.getProbes().get("sweep_unloaded").get();
        EDIFOptStats stats = EDIFTools.propagateConstants(netlist);
        Assertions.assertEquals(0, stats.getRemoved(EDIFOptStats.Phase.SWEEP));
        Assertions.assertNotNull(netlist.getTopCell().getCellInst("D2"));
    }

    @Test
    public void testOptimizePreservesDontTouch() {
        EDIFNetlist netlist = OptProbes.getProbes().get("dont_touch_cell").get();
        EDIFTools.optimizeNetlist(netlist, new EDIFOptOptions());
        EDIFCell top = netlist.getTopCell();
        Assertions.assertEquals("LUT2", top.getCellInst("DT").getCellType().getName());
        Assertions.assertNotNull(top.getCellInst("DU"));
        Assertions.assertNotNull(top.getCellInst("DK"));
    }

    @Test
    public void testOptimizeUniquifiesOnlyWhenNeeded() {
        // u0 gets a constant input and u1 does not: u0 needs its own copy of 'sub'
        EDIFNetlist shared = OptProbes.getProbes().get("hier_const_in_shared").get();
        EDIFOptStats stats = EDIFTools.optimizeNetlist(shared, new EDIFOptOptions());
        EDIFCell top = shared.getTopCell();
        EDIFCell u0 = top.getCellInst("u0").getCellType();
        EDIFCell u1 = top.getCellInst("u1").getCellType();
        Assertions.assertNotSame(u0, u1);
        Assertions.assertEquals("sub", u1.getName());
        Assertions.assertEquals("sub_HD1", u0.getName());
        Assertions.assertEquals(2, u1.getCellInsts().size());
        Assertions.assertEquals(1, stats.getCellsUniquified());
        // Both instances get the same edit: the definition stays shared
        EDIFNetlist same = OptProbes.getProbes().get("hier_const_in_same").get();
        stats = EDIFTools.optimizeNetlist(same, new EDIFOptOptions());
        Assertions.assertSame(same.getTopCell().getCellInst("u0").getCellType(),
                same.getTopCell().getCellInst("u1").getCellType());
        Assertions.assertEquals(0, stats.getCellsUniquified());
    }

    @Test
    public void testOptimizeUniquifiesNestedSharedDefinitions() {
        // top -> m0, m1 (both 'mid') -> l0 ('leafc'); only m0's leaf gets a constant input
        EDIFNetlist netlist = OptProbes.getProbes().get("hier_nested").get();
        EDIFNetlist orig = OptProbes.getProbes().get("hier_nested").get();
        EDIFTools.optimizeNetlist(netlist, new EDIFOptOptions());
        EDIFCell top = netlist.getTopCell();
        EDIFCell m1 = top.getCellInst("m1").getCellType();
        Assertions.assertEquals("mid", m1.getName());
        Assertions.assertEquals("leafc", m1.getCellInst("l0").getCellType().getName());
        Assertions.assertEquals("LUT2", m1.getCellInst("l0").getCellType().getCellInst("LL")
                .getCellType().getName());
        Assertions.assertEquals(0, new EDIFRandomSimEquivalence().check(orig, netlist, 2));
    }

    @Test
    public void testLeafIndexMatchesPhysicalNetPinMap() {
        EDIFNetlist netlist = OptProbes.getProbes().get("hier_nested").get();
        EDIFLeafIndex idx = new EDIFLeafIndex(netlist);
        Map<EDIFHierNet, List<EDIFHierPortInst>> map = netlist.getPhysicalNetPinMap();
        int leafPins = 0;
        for (List<EDIFHierPortInst> pins : map.values()) {
            leafPins += pins.size();
        }
        Assertions.assertEquals(leafPins, idx.getNumLeafPins());
        for (Map.Entry<EDIFHierNet, List<EDIFHierPortInst>> e : map.entrySet()) {
            int net = -1;
            for (EDIFHierPortInst hpi : e.getValue()) {
                String name = hpi.getFullHierarchicalInstName() + "/" + hpi.getPortInst().getName();
                int found = -1;
                for (int p = 0; p < idx.getNumLeafPins(); p++) {
                    if (idx.getPinName(p).equals(name)) {
                        found = idx.getPinNet(p);
                    }
                }
                Assertions.assertTrue(found >= 0, name);
                if (net < 0) {
                    net = found;
                }
                Assertions.assertEquals(net, found, name);
            }
        }
    }
}

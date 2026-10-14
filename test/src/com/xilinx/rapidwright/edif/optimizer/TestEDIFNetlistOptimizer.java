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
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import com.xilinx.rapidwright.design.Unisim;
import com.xilinx.rapidwright.edif.EDIFCell;
import com.xilinx.rapidwright.edif.EDIFCellInst;
import com.xilinx.rapidwright.edif.EDIFDirection;
import com.xilinx.rapidwright.edif.EDIFHierNet;
import com.xilinx.rapidwright.edif.EDIFHierPortInst;
import com.xilinx.rapidwright.edif.EDIFNet;
import com.xilinx.rapidwright.edif.EDIFNetlist;
import com.xilinx.rapidwright.edif.EDIFPort;
import com.xilinx.rapidwright.edif.EDIFPortInst;
import com.xilinx.rapidwright.edif.EDIFTools;
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
                TestEDIFNetlistOptimizer::lut62BufferedInvertedInput));
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
        Assertions.assertNull(VerilogLiterals.parseNumber("1'bx"));
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

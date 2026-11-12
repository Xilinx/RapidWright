/*
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

package com.xilinx.rapidwright.lec;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Path;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.xilinx.rapidwright.design.Unisim;
import com.xilinx.rapidwright.edif.EDIFCell;
import com.xilinx.rapidwright.edif.EDIFCellInst;
import com.xilinx.rapidwright.edif.EDIFDirection;
import com.xilinx.rapidwright.edif.EDIFNet;
import com.xilinx.rapidwright.edif.EDIFNetlist;
import com.xilinx.rapidwright.edif.EDIFTools;

/**
 * End to end acceptance cases on small in-memory netlists.
 *
 * A checker that never reports FAIL would pass every positive case, so the
 * negative cases here are the most important tests in
 * the suite. Each introduces exactly one deliberate change and asserts both the
 * verdict and that the affected pin is named.
 */
public class TestLecAcceptance {

    /**
     * Loads the solver's native libraries before any test runs, so that the
     * suite's check for leaked file descriptors is not tripped by a shared
     * library being loaded on first use inside a test.
     */
    @BeforeAll
    public static void loadSolver() {
        SatMiter.isAvailable();
    }

    /** The unmodified reference logic, used by every case below. */
    private static EDIFNetlist reference() {
        return LecNetlistFixtures.twoPartitionDesign("4'hE", false, true);
    }

    private static LecHarness check(EDIFNetlist ref, EDIFNetlist imp) {
        return LecHarness.run(ref, imp);
    }

    /**
     * A design against a copy of itself must pass with no
     * differences.
     *
     * The assertion on the escalation counters matters as much as the verdict:
     * this case must be settled entirely by structural hashing, which is what
     * demonstrates that both netlists really were built into one shared graph.
     */
    @Test
    public void testIdenticalCopyPasses() {
        LecHarness result = check(reference(), reference());
        result.assertPass();
        Assertions.assertEquals(0, result.getReport().getProvenDifferenceCount());
        Assertions.assertTrue(result.getReport().getConesChecked() > 0, "No cones were checked");
        Assertions.assertEquals(result.getReport().getConesChecked(),
                result.getReport().getConesEquivalent());
        Assertions.assertTrue(result.getEngine().getProvenByHash() > 0,
                "An identical copy should be settled by structural hashing");
        Assertions.assertEquals(0, result.getEngine().getProvenBySat(),
                "An identical copy should never need the solver");
    }

    /**
     * Moving a combinational cell to a different partition changes the
     * boundary ports and the hierarchy, but not the logic,
     * so it must pass.
     */
    @Test
    public void testRepartitioningPasses() {
        LecHarness result = check(reference(),
                LecNetlistFixtures.twoPartitionDesign("4'hE", true, true));
        result.assertPass();
        Assertions.assertEquals(0, result.getReport().getProvenDifferenceCount());
    }

    /**
     * One functionally wrong driver must fail, naming the
     * affected terminal pin and supplying a counter-example.
     */
    @Test
    public void testWrongLogicFails() {
        LecHarness result = check(reference(),
                LecNetlistFixtures.twoPartitionDesign("4'h6", false, true));
        result.assertFail();
        LecDifference difference = result.findDifference(LecDifferenceType.CONE_FUNCTION_DIFFERS);
        Assertions.assertNotNull(difference, "The differing cone was not reported");
        Assertions.assertEquals("PART_2/ff/D", difference.getSubject(),
                "The report must name the affected terminal pin");
        Assertions.assertFalse(difference.getVerdict().getCounterExample().isEmpty(),
                "A difference must come with a counter-example assignment");
    }

    /**
     * A cell in a different partition <i>and</i> a changed
     * function must fail: repartitioning alone is not a difference, but it must
     * not mask one either.
     */
    @Test
    public void testRepartitionedAndBrokenFails() {
        LecHarness result = check(reference(),
                LecNetlistFixtures.twoPartitionDesign("4'h6", true, true));
        result.assertFail();
        Assertions.assertNotNull(result.findDifference(LecDifferenceType.CONE_FUNCTION_DIFFERS));
    }

    /**
     * The counter-example must actually witness the difference. A tool that
     * reported an arbitrary assignment would look right and be useless, so the
     * assignment is evaluated against both functions here.
     */
    @Test
    public void testCounterExampleIsGenuine() {
        LecHarness result = check(reference(),
                LecNetlistFixtures.twoPartitionDesign("4'h6", false, true));
        LecDifference difference = result.findDifference(LecDifferenceType.CONE_FUNCTION_DIFFERS);
        Assertions.assertNotNull(difference);
        Assertions.assertTrue(result.witnessesDifference("PART_2/ff/D",
                difference.getVerdict().getCounterExample()),
                "The reported assignment does not actually separate the two functions");
    }

    /**
     * Renaming every combinational instance must change nothing, since
     * instance naming is not a difference. Terminal names are the
     * reference frame and are left alone.
     */
    @Test
    public void testCloudRenamingPasses() {
        EDIFNetlist imp = LecNetlistFixtures.twoPartitionDesign("4'hE", false, true, "_renamed");
        LecHarness result = check(reference(), imp);
        result.assertPass();
    }

    /**
     * A pin present in one netlist and absent in the other is a difference,
     * and must be reported as such rather than quietly skipped.
     */
    @Test
    public void testMissingTerminalFails() {
        EDIFNetlist imp = LecNetlistFixtures.twoPartitionDesign("4'hE", false, true);
        LecNetlistFixtures.removeTerminal(imp, "PART_2", "ff");
        LecHarness result = check(reference(), imp);
        result.assertFail();
        Assertions.assertTrue(
                result.getReport().getCount(LecDifferenceType.TERMINAL_MISSING_IN_IMP) > 0
                        || result.getReport()
                                .getCount(LecDifferenceType.CONE_PIN_MISSING_IN_IMP) > 0,
                "A terminal missing from IMP must be reported");
    }

    /**
     * Changing a flip flop's reset behaviour does not change any cone, so cone
     * comparison alone would pass. The terminal attribute comparison is what
     * catches it.
     */
    @Test
    public void testTerminalAttributeChangeFails() {
        EDIFNetlist imp = LecNetlistFixtures.twoPartitionDesign("4'hE", false, true);
        LecNetlistFixtures.setTerminalProperty(imp, "PART_2", "ff", "INIT", "1'b1");
        LecHarness result = check(reference(), imp);
        result.assertFail();
        Assertions.assertTrue(
                result.getReport().getCount(LecDifferenceType.TERMINAL_PROPERTY_DIFFERS) > 0,
                "A changed terminal property must be reported");
    }

    /**
     * A physical pin attribute, such as the Versal hold fix delay a router puts
     * on a flip flop input, changes timing but not logic, so it must not be
     * reported.
     */
    @Test
    public void testPinAttributeChangePasses() {
        EDIFNetlist imp = LecNetlistFixtures.twoPartitionDesign("4'hE", false, true);
        LecNetlistFixtures.setTerminalProperty(imp, "PART_2", "ff", "PinAttr:D:IMR_HOLD_FIX",
                "PART_2/ff/C");
        check(reference(), imp).assertPass();
    }

    /**
     * A feedthrough must be named by the input it comes from, not by the output
     * it drives, or rewiring it to a different input would go unseen.
     */
    @Test
    public void testFeedthroughFromADifferentInputFails() {
        LecHarness result = check(LecNetlistFixtures.feedthrough("z"),
                LecNetlistFixtures.feedthrough("w"));
        result.assertFail();
    }

    /**
     * What the design drives onto a bidirectional port is checked: swapping the
     * buffers on two such ports must fail.
     */
    @Test
    public void testSwappedInoutDriversFail() {
        LecHarness result = check(LecNetlistFixtures.ioBuffersOnInouts(false),
                LecNetlistFixtures.ioBuffersOnInouts(true));
        result.assertFail();
    }

    /**
     * Top level ports are terminals even where no cone reaches them: an input
     * that only one netlist has must fail.
     */
    @Test
    public void testExtraTopLevelPortFails() {
        EDIFNetlist imp = LecNetlistFixtures.feedthrough("z");
        LecNetlistFixtures.port(imp.getTopCell(), "unused", EDIFDirection.INPUT);
        LecHarness result = check(LecNetlistFixtures.feedthrough("z"), imp);
        result.assertFail();
        Assertions.assertTrue(
                result.getReport().getCount(LecDifferenceType.TERMINAL_MISSING_IN_REF) > 0,
                "The extra port must be reported as a terminal REF lacks");
    }

    /**
     * A net shorted between a terminal and cloud logic is matched by its
     * drivers, and the cloud driver is checked: the same short passes, and a
     * different function on the cloud side fails.
     */
    @Test
    public void testShortedNetIsMatchedByCheckedDrivers() {
        check(LecNetlistFixtures.shortedNet("4'h8"), LecNetlistFixtures.shortedNet("4'h8"))
                .assertPass();
        check(LecNetlistFixtures.shortedNet("4'h8"), LecNetlistFixtures.shortedNet("4'hE"))
                .assertFail();
    }

    /**
     * Clock pins are checked like any other pin: a flip flop moved to a
     * different clock must fail.
     */
    @Test
    public void testRewiredClockFails() {
        check(LecNetlistFixtures.clockedFlop("clk_a"), LecNetlistFixtures.clockedFlop("clk_a"))
                .assertPass();
        check(LecNetlistFixtures.clockedFlop("clk_a"), LecNetlistFixtures.clockedFlop("clk_b"))
                .assertFail();
    }

    /**
     * A top level port named like a terminal pin, here {@code ff/D} beside the
     * flip flop {@code ff}, would share that pin's key. The netlist is refused
     * rather than compared with the two aliased.
     */
    @Test
    public void testPortNamedLikeATerminalPinIsRefused() {
        EDIFNetlist imp = LecNetlistFixtures.clockedFlop("clk_a");
        LecNetlistFixtures.port(imp.getTopCell(), "ff/D", EDIFDirection.OUTPUT);
        Assertions.assertThrows(LecException.class,
                () -> check(LecNetlistFixtures.clockedFlop("clk_a"), imp));
    }

    /**
     * Endpoints are matched by name, so two different endpoints whose names join
     * to the same string would alias. Each such netlist is refused rather than
     * compared with one endpoint silently dropped.
     */
    @Test
    public void testCollidingEndpointNamesAreRefused() {
        // A top level instance renamed a/b beside instance b inside block a.
        EDIFNetlist nested = LecNetlistFixtures.clockedFlop("clk_a");
        EDIFCell top = nested.getTopCell();
        EDIFCell flop = top.getCellInst("ff").getCellType();
        EDIFCell block = new EDIFCell(top.getLibrary(), "block_type");
        LecNetlistFixtures.add(block, "b", flop);
        LecNetlistFixtures.add(top, "a", block);
        LecNetlistFixtures.add(top, "a/b", flop);
        // A register named like the metadata key of top level port d.
        EDIFNetlist portKey = LecNetlistFixtures.clockedFlop("clk_a");
        LecNetlistFixtures.add(portKey.getTopCell(), "top level port d",
                portKey.getTopCell().getCellInst("ff").getCellType());
        // A top level output named like the cloud driver of a shorted net.
        EDIFNetlist driverKey = LecNetlistFixtures.shortedNet("4'h8");
        LecNetlistFixtures.port(driverKey.getTopCell(), "lut/O", EDIFDirection.OUTPUT);
        LecNetlistFixtures.connect(driverKey.getTopCell(), "short", driverKey.getTopCell(),
                "lut/O");
        for (EDIFNetlist ambiguous : new EDIFNetlist[] { nested, portKey, driverKey }) {
            Assertions.assertThrows(LecException.class, () -> check(ambiguous, ambiguous));
        }
    }

    /**
     * A leaf cell outside the primitives library is a black box whatever it is
     * called, so replacing a flip flop with a leaf {@code work.FDRE} of the same
     * pins and properties is a terminal type difference, not a match.
     */
    @Test
    public void testLeafNamedLikeAPrimitiveFails() {
        EDIFNetlist imp = LecNetlistFixtures.clockedFlop("clk_a");
        EDIFCellInst ff = imp.getTopCell().getCellInst("ff");
        EDIFCell leaf = new EDIFCell(imp.getTopCell().getLibrary(), ff.getCellType(), "FDRE", false);
        ff.setCellType(leaf);
        LecHarness result = check(LecNetlistFixtures.clockedFlop("clk_a"), imp);
        result.assertFail();
        LecDifference difference =
                result.findDifference(LecDifferenceType.TERMINAL_CELL_TYPE_DIFFERS);
        Assertions.assertNotNull(difference, "The replaced flip flop was not reported");
        Assertions.assertEquals("ff", difference.getSubject());
    }

    /**
     * A key built from an instance path starting with the reserved unstable
     * prefix would be read as one of the checker's own variables (here that of
     * black box {@code bb}'s output Q in IMP), so such a netlist is refused.
     */
    @Test
    public void testInstanceNamedWithReservedPrefixIsRefused() {
        EDIFNetlist imp = LecNetlistFixtures.clockedFlop("clk_a");
        LecNetlistFixtures.add(imp.getTopCell(), TerminalKey.OPAQUE_PREFIX + "IMP:bb",
                imp.getTopCell().getCellInst("ff").getCellType());
        Assertions.assertThrows(LecException.class,
                () -> check(LecNetlistFixtures.clockedFlop("clk_a"), imp));
    }

    /**
     * The negative cases generalised: every destructive
     * change must produce a non-zero exit code.
     */
    @Test
    public void testEveryDestructiveChangeFails() {
        String[] brokenInits = { "4'h6", "4'h0", "4'hF", "4'h9" };
        for (String init : brokenInits) {
            LecHarness result = check(reference(),
                    LecNetlistFixtures.twoPartitionDesign(init, false, true));
            Assertions.assertNotEquals(LogicEquivalenceChecker.EXIT_PASS, result.getExitCode(),
                    "INIT " + init + " changes the logic but the checker passed");
        }
    }

    /**
     * RapidWright's readers expand the macro primitives of a netlist that names
     * its part (here an IBUF and a LUT6_2 on UltraScale+). The pins of an
     * expanded macro must still be those of an atom: if they were lost, both
     * netlists would lose them alike and a rewired macro input would pass.
     */
    @Test
    public void testExpandedMacrosAreChecked(@TempDir Path dir) {
        EDIFNetlist ref = readMacroDesign(dir, "ref", false);
        EDIFCell lut = ref.getHDIPrimitivesLibrary().getCell(Unisim.LUT6_2.name());
        Assertions.assertFalse(lut.isLeafCellOrBlackBox(), "LUT6_2 was not expanded when read");

        LecHarness result = check(ref, readMacroDesign(dir, "copy", false));
        result.assertPass();
        Assertions.assertTrue(result.getReport().getConesChecked() > 0, "No cones were checked");

        result = check(ref, readMacroDesign(dir, "rewired", true));
        result.assertFail();
        LecDifference difference = result.findDifference(LecDifferenceType.CONE_FUNCTION_DIFFERS);
        Assertions.assertNotNull(difference, "The differing cone was not reported");
    }

    /**
     * Writes and reads back a design whose output is its input {@code a}, through
     * an IBUF and a LUT6_2 (O6 = I0), or input {@code b} once the LUT6_2's I0 and
     * I1 are swapped.
     */
    private static EDIFNetlist readMacroDesign(Path dir, String name, boolean swapped) {
        EDIFNetlist netlist = EDIFTools.createNewNetlist("top");
        EDIFTools.ensureCorrectPartInEDIF(netlist, "xcvu3p-ffvc1517-2-e");
        EDIFCell top = netlist.getTopCell();
        LecNetlistFixtures.port(top, "a", EDIFDirection.INPUT);
        LecNetlistFixtures.port(top, "b", EDIFDirection.INPUT);
        LecNetlistFixtures.port(top, "o", EDIFDirection.OUTPUT);
        EDIFCellInst ibuf = top.createChildCellInst("ibuf", netlist.getHDIPrimitive(Unisim.IBUF));
        EDIFCellInst lut = top.createChildCellInst("lut", netlist.getHDIPrimitive(Unisim.LUT6_2));
        LecNetlistFixtures.setProperty(lut, "INIT", "64'hAAAAAAAAAAAAAAAA");
        LecNetlistFixtures.connect(top, "a", top, "a", ibuf, "I");
        LecNetlistFixtures.connect(top, "a_ibuf", ibuf, "O", lut, swapped ? "I1" : "I0",
                lut, "I2", lut, "I3", lut, "I4", lut, "I5");
        LecNetlistFixtures.connect(top, "b", top, "b", lut, swapped ? "I0" : "I1");
        LecNetlistFixtures.connect(top, "o", lut, "O6", top, "o");
        Path file = dir.resolve(name + ".edf");
        netlist.exportEDIF(file);
        return NetlistLoader.load(file, new LecConfig(),
                new LecLog(false, new PrintStream(new ByteArrayOutputStream())));
    }

    /**
     * An unconnected pin takes the default Vivado ties it to, which for the bits
     * of a bus pin is listed per bit: RAMB36E2's WEA[0] is tied high, so leaving
     * it unconnected differs from tying it low.
     */
    @Test
    public void testUnconnectedBusPinBitTakesItsDefault() {
        check(ramWithWriteEnable(false), ramWithWriteEnable(false)).assertPass();
        LecHarness result = check(ramWithWriteEnable(false), ramWithWriteEnable(true));
        result.assertFail();
        LecDifference difference = result.findDifference(LecDifferenceType.CONE_FUNCTION_DIFFERS);
        Assertions.assertNotNull(difference, "The tied write enable was not reported");
        Assertions.assertEquals("ram/WEA[0]", difference.getSubject());
    }

    /**
     * The defaults are those of device primitives: a black box named RAMB36E2
     * does not take them, so its unconnected WEA[0] differs from one tied high.
     */
    @Test
    public void testBlackBoxNamedLikeAPrimitiveTakesNoDefault() {
        check(ramWithWriteEnable(false, true, false), ramWithWriteEnable(false, true, false))
                .assertPass();
        check(ramWithWriteEnable(false, true, false), ramWithWriteEnable(true, true, true))
                .assertFail();
    }

    private static EDIFNetlist ramWithWriteEnable(boolean tiedLow) {
        return ramWithWriteEnable(tiedLow, false, false);
    }

    private static EDIFNetlist ramWithWriteEnable(boolean tied, boolean blackBox, boolean high) {
        LecNetlistFixtures fixture = new LecNetlistFixtures("top");
        EDIFCell top = fixture.getTop();
        EDIFCell ramType = blackBox ? fixture.newHierCell("RAMB36E2")
                : fixture.primitive("RAMB36E2", new String[0], new String[0]);
        ramType.createPort("WEA[3:0]", EDIFDirection.INPUT, 4);
        EDIFCellInst ram = LecNetlistFixtures.add(top, "ram", ramType);
        EDIFCellInst tie = high ? LecNetlistFixtures.add(top, "VCC", fixture.vcc())
                : LecNetlistFixtures.add(top, "GND", fixture.gnd());
        EDIFNet net = LecNetlistFixtures.connect(top, "tie", tie, high ? "P" : "G");
        if (tied) {
            net.createPortInst("WEA[0]", ram);
        }
        return fixture.getNetlist();
    }

    /**
     * A differential receiver's output follows I only while IB is its
     * complement, so its IB input is checked too: moving it to another input
     * must fail.
     */
    @Test
    public void testDifferentialReceiverInputIsChecked() {
        check(differentialInput("b"), differentialInput("b")).assertPass();
        check(differentialInput("b"), differentialInput("c")).assertFail();
    }

    private static EDIFNetlist differentialInput(String negative) {
        LecNetlistFixtures fixture = new LecNetlistFixtures("top");
        EDIFCell top = fixture.getTop();
        for (String name : new String[] { "a", "b", "c" }) {
            LecNetlistFixtures.port(top, name, EDIFDirection.INPUT);
        }
        LecNetlistFixtures.port(top, "o", EDIFDirection.OUTPUT);
        EDIFCellInst ibuf = LecNetlistFixtures.add(top, "ibuf",
                fixture.primitive("IBUFDS", new String[] { "I", "IB" }, new String[] { "O" }));
        LecNetlistFixtures.connect(top, "a", top, "a", ibuf, "I");
        LecNetlistFixtures.connect(top, negative, top, negative, ibuf, "IB");
        LecNetlistFixtures.connect(top, "o", ibuf, "O", top, "o");
        return fixture.getNetlist();
    }

    /**
     * What a bidirectional pin of a terminal receives is checked: an IOBUF
     * whose IO moves from input a to input b must fail, although its output
     * is the same variable in both netlists.
     */
    @Test
    public void testInoutPinOfTerminalIsChecked() {
        check(ioBufferOn("a"), ioBufferOn("a")).assertPass();
        LecHarness result = check(ioBufferOn("a"), ioBufferOn("b"));
        result.assertFail();
        LecDifference difference = result.findDifference(LecDifferenceType.CONE_FUNCTION_DIFFERS);
        Assertions.assertNotNull(difference, "The moved IO pin was not reported");
        Assertions.assertEquals("iob/IO", difference.getSubject());
    }

    private static EDIFNetlist ioBufferOn(String pad) {
        LecNetlistFixtures fixture = new LecNetlistFixtures("top");
        EDIFCell top = fixture.getTop();
        LecNetlistFixtures.port(top, "a", EDIFDirection.INPUT);
        LecNetlistFixtures.port(top, "b", EDIFDirection.INPUT);
        LecNetlistFixtures.port(top, "o", EDIFDirection.OUTPUT);
        EDIFCell iobufType = fixture.primitive("IOBUF", new String[] { "I", "T" },
                new String[] { "O" });
        iobufType.createPort("IO", EDIFDirection.INOUT, 1);
        EDIFCellInst iob = LecNetlistFixtures.add(top, "iob", iobufType);
        EDIFCellInst gnd = LecNetlistFixtures.add(top, "GND", fixture.gnd());
        EDIFCellInst vcc = LecNetlistFixtures.add(top, "VCC", fixture.vcc());
        LecNetlistFixtures.connect(top, "gnd", gnd, "G", iob, "I");
        LecNetlistFixtures.connect(top, "vcc", vcc, "P", iob, "T");
        LecNetlistFixtures.connect(top, pad, top, pad, iob, "IO");
        LecNetlistFixtures.connect(top, "o", iob, "O", top, "o");
        return fixture.getNetlist();
    }

    /**
     * A short is named after its leaf drivers, which leaves out a top level
     * input driving it too: one that only IMP connects to the short must not
     * pass.
     */
    @Test
    public void testShortWithTopLevelSourceDoesNotPass() {
        EDIFNetlist imp = LecNetlistFixtures.shortedNet("4'h8");
        LecNetlistFixtures.port(imp.getTopCell(), "c", EDIFDirection.INPUT);
        LecNetlistFixtures.connect(imp.getTopCell(), "short", imp.getTopCell(), "c");
        EDIFNetlist ref = LecNetlistFixtures.shortedNet("4'h8");
        LecNetlistFixtures.port(ref.getTopCell(), "c", EDIFDirection.INPUT);
        Assertions.assertNotEquals(LogicEquivalenceChecker.EXIT_PASS, check(ref, imp).getExitCode(),
                "A top level input added to a short passed");
    }

    /**
     * Only a primitive GND is a constant: a black box named GND driving an
     * output differs from a primitive GND driving it.
     */
    @Test
    public void testBlackBoxNamedGndIsNotAConstant() {
        check(groundedOutput(false), groundedOutput(false)).assertPass();
        check(groundedOutput(false), groundedOutput(true)).assertFail();
    }

    private static EDIFNetlist groundedOutput(boolean primitiveGround) {
        LecNetlistFixtures fixture = new LecNetlistFixtures("top");
        EDIFCell top = fixture.getTop();
        LecNetlistFixtures.port(top, "o", EDIFDirection.OUTPUT);
        EDIFCell blackBox = fixture.newHierCell("GND");
        LecNetlistFixtures.port(blackBox, "G", EDIFDirection.OUTPUT);
        EDIFCellInst bb = LecNetlistFixtures.add(top, "bb", blackBox);
        if (primitiveGround) {
            EDIFCellInst gnd = LecNetlistFixtures.add(top, "GND", fixture.gnd());
            LecNetlistFixtures.connect(top, "o", gnd, "G", top, "o");
        } else {
            LecNetlistFixtures.connect(top, "o", bb, "G", top, "o");
        }
        return fixture.getNetlist();
    }

    /**
     * A black box's IS_<pin>_INVERTED parameter has no known meaning, so it is
     * neither folded into the cone nor left out of the attribute comparison:
     * changing it and inverting the pin's driver must fail.
     */
    @Test
    public void testBlackBoxInversionParameterIsNotFolded() {
        check(blackBoxWithInversion(false), blackBoxWithInversion(false)).assertPass();
        check(blackBoxWithInversion(false), blackBoxWithInversion(true)).assertFail();
    }

    private static EDIFNetlist blackBoxWithInversion(boolean inverted) {
        LecNetlistFixtures fixture = new LecNetlistFixtures("top");
        EDIFCell top = fixture.getTop();
        LecNetlistFixtures.port(top, "a", EDIFDirection.INPUT);
        EDIFCell blackBox = fixture.newHierCell("opaque_ip");
        LecNetlistFixtures.port(blackBox, "I", EDIFDirection.INPUT);
        EDIFCellInst bb = LecNetlistFixtures.add(top, "bb", blackBox);
        if (inverted) {
            LecNetlistFixtures.setProperty(bb, "IS_I_INVERTED", "1'b1");
            EDIFCellInst inv = LecNetlistFixtures.add(top, "inv", fixture.lut(1));
            LecNetlistFixtures.setProperty(inv, "INIT", "2'h1");
            LecNetlistFixtures.connect(top, "a", top, "a", inv, "I0");
            LecNetlistFixtures.connect(top, "a_n", inv, "O", bb, "I");
        } else {
            LecNetlistFixtures.connect(top, "a", top, "a", bb, "I");
        }
        return fixture.getNetlist();
    }

    /** Two runs over the same inputs must produce byte identical reports. */
    @Test
    public void testReportIsReproducible() {
        String first = renderReport();
        String second = renderReport();
        Assertions.assertEquals(first, second, "The report is not reproducible between runs");
    }

    private static String renderReport() {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        PrintStream out = new PrintStream(buffer);
        LecHarness.run(reference(),
                LecNetlistFixtures.twoPartitionDesign("4'h6", false, true), out);
        out.flush();
        return buffer.toString();
    }
}

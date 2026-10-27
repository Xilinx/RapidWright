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

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import com.xilinx.rapidwright.edif.EDIFCell;
import com.xilinx.rapidwright.edif.EDIFDirection;
import com.xilinx.rapidwright.edif.EDIFNetlist;

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

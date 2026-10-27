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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import com.xilinx.rapidwright.device.Series;
import com.xilinx.rapidwright.edif.EDIFNetlist;

/**
 * Checks cone construction, and in particular the distinction between a
 * cluster that merely looks cyclic and a genuine
 * logic loop.
 *
 * Getting this wrong in either direction is serious. Reporting a carry chain as
 * a loop would make the tool useless on any design containing arithmetic;
 * failing to report a real loop would let an invalid netlist pass.
 */
public class TestConeBuilder {

    /**
     * Loads the solver's native libraries before any test runs, so that the
     * suite's check for leaked file descriptors is not tripped by a shared
     * library being loaded on first use inside a test.
     */
    @BeforeAll
    public static void loadSolver() {
        SatMiter.isAvailable();
    }

    private static NetlistSummary elaborate(EDIFNetlist netlist, LecLog log) {
        FlatNetlist flat = FlatNetlist.build(netlist, log);
        Connectivity conn = ConnectivityBuilder.build(flat, log);
        CellClassifier classifier = new CellClassifier(Series.UltraScalePlus, new LecConfig(), log);
        NetlistStats stats = NetlistStats.collect("REF", flat, conn, classifier, log);
        Aig aig = new Aig(1 << 12);
        return NetlistSummary.build("REF", flat, conn, classifier, aig, Series.UltraScalePlus,
                stats, log);
    }

    private static LecLog quietLog() {
        return new LecLog(false, new PrintStream(new ByteArrayOutputStream()));
    }

    /**
     * Two carry cells drive each other
     * through look up tables, so the cell graph has a cycle, but no bit depends
     * on itself. This must elaborate with no loop reported and no cone
     * abandoned.
     */
    @Test
    public void testCellCycleThatIsBitAcyclicIsNotALoop() {
        NetlistSummary summary = elaborate(LecNetlistFixtures.carryCompressionTree(), quietLog());
        Assertions.assertTrue(summary.getLoopReport().isEmpty(),
                "A carry cluster that is acyclic at bit level must not be reported as a loop, "
                        + "but " + summary.getLoopReport().getCount() + " were reported");
        Assertions.assertFalse(summary.getConeLiterals().isEmpty(), "No cones were built");
    }

    /** A real cycle at bit level must be reported rather than silently accepted. */
    @Test
    public void testGenuineLoopIsReported() {
        NetlistSummary summary = elaborate(LecNetlistFixtures.genuineCombinationalLoop(),
                quietLog());
        Assertions.assertEquals(1, summary.getLoopReport().getCount(),
                "A genuine combinational loop must be reported exactly once");
        String[] cycle = summary.getLoopReport().getCycles().get(0);
        Assertions.assertTrue(cycle.length >= 2, "A loop must name the pins involved");
    }

    /**
     * The look up table analogue of the carry cluster case.
     *
     * Two tables form a ring, but the returning edge lands on an input the
     * driving table's INIT ignores, so no bit depends on itself. Declaring a
     * table's support from its arity rather than its INIT reports this as a
     * loop; it is not one. Real designs hit it, because Vivado packs two
     * unrelated functions into one {@code LUT6_2} whose input pins are shared.
     */
    @Test
    public void testLutCycleThroughIgnoredInputIsNotALoop() {
        NetlistSummary summary = elaborate(
                LecNetlistFixtures.lutCycleThroughIgnoredInput(false), quietLog());
        Assertions.assertTrue(summary.getLoopReport().isEmpty(),
                "A ring closing on an input the INIT ignores is not a combinational loop, but "
                        + summary.getLoopReport().getCount() + " were reported");
    }

    /** The same ring, closed on an input the INIT does use, is a real loop. */
    @Test
    public void testLutCycleThroughUsedInputIsALoop() {
        NetlistSummary summary = elaborate(
                LecNetlistFixtures.lutCycleThroughIgnoredInput(true), quietLog());
        Assertions.assertEquals(1, summary.getLoopReport().getCount(),
                "Reducing support to what the INIT uses must not hide a genuine loop");
    }

    /**
     * The ignored input must not appear among the cone's source terminals
     * either, since the function provably cannot observe it.
     */
    @Test
    public void testIgnoredLutInputIsNotInTheCone() {
        LecHarness result = LecHarness.run(
                LecNetlistFixtures.lutCycleThroughIgnoredInput(false),
                LecNetlistFixtures.lutCycleThroughIgnoredInput(false));
        result.assertPass();
    }

    /**
     * A design containing a genuine loop must not pass, since a true logic loop
     * is an error.
     */
    @Test
    public void testLoopFailsTheRun() {
        LecHarness result = LecHarness.run(LecNetlistFixtures.genuineCombinationalLoop(),
                LecNetlistFixtures.genuineCombinationalLoop());
        result.assertFail();
        Assertions.assertTrue(
                result.getReport().getCount(LecDifferenceType.COMBINATIONAL_LOOP) > 0,
                "The run must report the combinational loop");
    }

    /**
     * A loop in both netlists is inherited from the input, not created by the
     * transform under test. It is still an error, but it must be
     * reported as the distinct thing it is, and it must be possible to accept
     * it: otherwise a design with a pre-existing loop could never produce a
     * clean run however correct the transform was.
     */
    @Test
    public void testPreexistingLoopIsDistinguishedAndCanBeAccepted() {
        LecHarness result = LecHarness.run(LecNetlistFixtures.genuineCombinationalLoop(true),
                LecNetlistFixtures.genuineCombinationalLoop(true));
        result.assertFail();
        Assertions.assertTrue(result.getReport().getPreexistingLoopCount() > 0,
                "A loop in both netlists must be reported as pre-existing");
        Assertions.assertEquals(0, result.getReport().getIntroducedLoopCount(),
                "Nothing introduced it, so it must not be reported as introduced");

        LecConfig tolerant = new LecConfig();
        tolerant.setAllowPreexistingLoops(true);
        LecResult accepted = LogicEquivalenceChecker.compare(
                LecNetlistFixtures.genuineCombinationalLoop(true),
                LecNetlistFixtures.genuineCombinationalLoop(true), tolerant,
                new PrintStream(new ByteArrayOutputStream()));
        Assertions.assertEquals(LogicEquivalenceChecker.EXIT_PASS, accepted.getExitCode(),
                "--allow-preexisting-loops should accept a loop inherited from the input");
    }

    /**
     * A loop the implementation has and the reference does not was created by
     * the transform. That is a regression, and no option may excuse it.
     */
    @Test
    public void testIntroducedLoopAlwaysFails() {
        LecConfig tolerant = new LecConfig();
        tolerant.setAllowPreexistingLoops(true);
        LecResult result = LogicEquivalenceChecker.compare(
                LecNetlistFixtures.genuineCombinationalLoop(false),
                LecNetlistFixtures.genuineCombinationalLoop(true), tolerant,
                new PrintStream(new ByteArrayOutputStream()));
        Assertions.assertTrue(result.getReport().getIntroducedLoopCount() > 0,
                "A loop only the implementation has must be reported as introduced");
        Assertions.assertEquals(LogicEquivalenceChecker.EXIT_FAIL, result.getExitCode(),
                "A loop created by the transform must fail even with "
                        + "--allow-preexisting-loops");
    }

    /**
     * Real designs must elaborate without spurious loops. These fixtures cover
     * all four series and include Versal carry look up tables, Series 7 four bit
     * carry chains and wide multiplexer trees.
     */
    @ParameterizedTest
    @ValueSource(strings = { "picoblaze_ooc_X10Y235.dcp", "bnn.dcp", "versal_cout_hq2.dcp",
            "verilog_ethernet.dcp", "microblazeAndILA_3pblocks.dcp", "routethru_luts.dcp" })
    public void testRealDesignsHaveNoLoops(String dcp) {
        LecLog log = quietLog();
        NetlistSummary summary = elaborate(TestConnectivityBuilder.loadLogical(dcp), log);
        Assertions.assertTrue(summary.getLoopReport().isEmpty(),
                dcp + " reported " + summary.getLoopReport().getCount()
                        + " combinational loops, the first being "
                        + (summary.getLoopReport().isEmpty() ? ""
                                : String.join(" -> ", summary.getLoopReport().getCycles().get(0))));
        Assertions.assertFalse(summary.getConeLiterals().isEmpty(), "No cones were built");
    }

    /**
     * Every terminal input pin, clocks included, becomes a cone, and every
     * cone resolves to a literal. A missing cone would silently skip a check.
     */
    @Test
    public void testEveryCheckedPinHasACone() {
        LecLog log = quietLog();
        EDIFNetlist netlist = TestConnectivityBuilder.loadLogical("picoblaze_ooc_X10Y235.dcp");
        NetlistSummary summary = elaborate(netlist, log);
        Assertions.assertEquals(summary.getStats().getCheckedPinCount(),
                summary.getConeLiterals().size(),
                "The number of cones must match the number of checked pins");
    }
}

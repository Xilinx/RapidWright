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

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Checks that each rung of the escalation ladder does its job.
 *
 * Without these, a serious bug would be invisible. Every other positive test in
 * the suite is settled by structural hashing, so a solver that wrongly returned
 * "equivalent" for everything, or that never ran at all, would leave the whole
 * suite green. These cases deliberately construct comparisons that only the
 * later rungs can settle, and assert that the expected rung is the one that
 * fired.
 */
public class TestEquivalenceEngine {

    /**
     * Loads the solver's native libraries before any test runs.
     *
     * The suite checks that a test leaves no file descriptors behind, and
     * loading a shared library legitimately opens some. Doing it once here,
     * outside the window the check covers, keeps that check meaningful for the
     * code actually under test.
     */
    @BeforeAll
    public static void loadSolver() {
        SatMiter.isAvailable();
    }

    /**
     * The same four input function built as one look up table in the reference
     * and as a multiplexer over two smaller tables in the implementation.
     *
     * Structural hashing cannot see that these agree, and simulation can never
     * prove agreement, so a pass here is only possible if the solver ran and
     * proved it.
     */
    @Test
    public void testResynthesisNeedsAndPassesTheSolver() {
        LecHarness result = LecHarness.run(LecNetlistFixtures.resynthesisedFunction(false),
                LecNetlistFixtures.resynthesisedFunction(true));
        result.assertPass();
        Assertions.assertTrue(result.getEngine().getProvenBySat() > 0,
                "Structurally different but equivalent logic must be proven by the solver, "
                        + "yet the solver settled nothing. Report:"
                        + System.lineSeparator() + result.getOutput());
    }

    /**
     * The same comparison with the reference's function perturbed. This must
     * fail, and it must fail through simulation rather than the solver, because
     * a wrong function is almost always separated by a random pattern.
     */
    @Test
    public void testPerturbedResynthesisIsRefuted() {
        LecHarness result = LecHarness.run(LecNetlistFixtures.resynthesisedFunction(false),
                LecNetlistFixtures.perturbedResynthesisedFunction());
        result.assertFail();
        Assertions.assertTrue(result.getEngine().getRefutedBySimulation() > 0,
                "A wrong function should be separated by simulation");
        LecDifference difference = result.findDifference(LecDifferenceType.CONE_FUNCTION_DIFFERS);
        Assertions.assertNotNull(difference);
        Assertions.assertTrue(result.witnessesDifference(difference.getSubject(),
                difference.getVerdict().getCounterExample()),
                "The counter-example must genuinely separate the two functions");
    }

    /**
     * Absorbing an inverter into a flip flop's data pin attribute is a
     * legitimate implementation of the same circuit. It must pass, and crucially
     * the attribute must not then be reported as a terminal difference: the cone
     * comparison already accounts for it, so comparing it again would report one
     * change twice.
     */
    @Test
    public void testAbsorbedInversionPasses() {
        LecHarness result = LecHarness.run(LecNetlistFixtures.absorbedInversion(false),
                LecNetlistFixtures.absorbedInversion(true));
        result.assertPass();
        Assertions.assertEquals(0,
                result.getReport().getCount(LecDifferenceType.TERMINAL_PROPERTY_DIFFERS),
                "An inversion folded into the cone must not also be reported as an attribute "
                        + "difference");
    }

    /**
     * An inversion absorbed on one side but simply missing on the other really
     * is a different circuit and must be caught.
     */
    @Test
    public void testMissingInversionFails() {
        LecHarness result = LecHarness.run(LecNetlistFixtures.absorbedInversion(false),
                LecNetlistFixtures.noInversion());
        result.assertFail();
    }

    /** Complementary cones are refuted in constant time, with no solver call. */
    @Test
    public void testComplementaryConesAreRefutedStructurally() {
        LecHarness result = LecHarness.run(LecNetlistFixtures.absorbedInversion(true),
                LecNetlistFixtures.noInversion());
        result.assertFail();
        Assertions.assertTrue(result.getEngine().getRefutedByHash() > 0,
                "Two cones that are exact complements should be refuted structurally");
        Assertions.assertEquals(0, result.getEngine().getSatCalls(),
                "Refuting a complement must not need the solver");
    }

    /**
     * A cone whose inputs cannot be matched between the two netlists must be
     * reported inconclusive rather than as a difference, because any
     * disagreement may be an artefact of the two sides having been handed
     * different variables for the same signal.
     */
    @Test
    public void testUnmatchableInputsYieldInconclusiveNotDifferent() {
        LecHarness result = LecHarness.run(LecNetlistFixtures.blackBoxDriven("bb_one"),
                LecNetlistFixtures.blackBoxDriven("bb_two"));
        Assertions.assertEquals(0,
                result.getReport().getCount(LecDifferenceType.CONE_FUNCTION_DIFFERS),
                "A cone fed by an unmatchable variable must not be reported as a proven "
                        + "difference");
        Assertions.assertTrue(result.getReport().getCount(LecDifferenceType.CONE_INCONCLUSIVE) > 0,
                "It must be reported as inconclusive instead");
        result.assertFail();
    }

    /**
     * A black box keeps its name but not its function: the same name in both
     * netlists says nothing about what reaches its inputs, so the cones it
     * feeds must still be inconclusive rather than proven by structural hash.
     */
    @Test
    public void testSameNamedBlackBoxIsNotProven() {
        LecHarness result = LecHarness.run(LecNetlistFixtures.blackBoxDriven("bb_one"),
                LecNetlistFixtures.blackBoxDriven("bb_one"));
        Assertions.assertTrue(result.getReport().getCount(LecDifferenceType.CONE_INCONCLUSIVE) > 0,
                "A cone through a black box must not be certified");
        result.assertFail();
    }

    /**
     * An undecided cone must fail by default, since presenting an unproven
     * result as a pass would be unsound, but the caller can choose otherwise.
     */
    @Test
    public void testAllowInconclusiveDowngradesToPass() {
        LecConfig config = new LecConfig();
        config.setAllowInconclusive(true);
        LecResult result = LogicEquivalenceChecker.compare(
                LecNetlistFixtures.blackBoxDriven("bb_one"),
                LecNetlistFixtures.blackBoxDriven("bb_two"), config,
                new java.io.PrintStream(new java.io.ByteArrayOutputStream()));
        Assertions.assertTrue(result.getReport().getCount(LecDifferenceType.CONE_INCONCLUSIVE) > 0);
        Assertions.assertEquals(LogicEquivalenceChecker.EXIT_PASS, result.getExitCode(),
                "--allow-inconclusive should downgrade an undecided cone to a warning");
    }
}

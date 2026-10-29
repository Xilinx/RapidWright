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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import com.xilinx.rapidwright.edif.EDIFNetlist;
import com.xilinx.rapidwright.support.LargeTest;

/**
 * Exercises the whole tool on real designs rather than constructed fixtures.
 *
 * The constructed fixtures prove the logic is right; these prove it survives
 * contact with netlists nobody designed for testing. Between them the six
 * designs used here cover all four architectures, Versal carry look up tables,
 * Series 7 four bit carry chains, block and distributed RAM, DSP slices, IO and
 * and clocking hard blocks.
 */
public class TestLecScale {

    /**
     * Loads the solver's native libraries before any test runs, so that the
     * suite's check for leaked file descriptors is not tripped by a shared
     * library being loaded on first use inside a test.
     */
    @BeforeAll
    public static void loadSolver() {
        SatMiter.isAvailable();
    }

    private static EDIFNetlist load(String dcp) {
        return TestConnectivityBuilder.loadLogical(dcp);
    }

    /**
     * Every real design must compare equal to itself, and must do so entirely
     * by structural hashing.
     *
     * The second half of that is the interesting part. If elaborating the same
     * netlist twice produced even slightly different graphs, the comparison
     * would still pass, but by a far more expensive route. Requiring that the
     * implementation adds no new nodes at all is a strong check that both
     * netlists really were built into one shared graph over shared terminals.
     */
    @ParameterizedTest
    @ValueSource(strings = { "picoblaze_ooc_X10Y235.dcp", "bnn.dcp", "versal_cout_hq2.dcp",
            "verilog_ethernet.dcp", "microblazeAndILA_3pblocks.dcp", "ramb18.dcp" })
    public void testRealDesignEqualsItself(String dcp) {
        LecHarness result = LecHarness.run(load(dcp), load(dcp));
        result.assertPass();
        Assertions.assertTrue(result.getReport().getConesChecked() > 0, "No cones were checked");
        Assertions.assertEquals(result.getReport().getConesChecked(),
                result.getReport().getConesEquivalent());
        Assertions.assertEquals(0, result.getResult().getImplementation().getAigNodesAdded(),
                "Elaborating the same netlist twice must reuse every node");
        Assertions.assertEquals(0, result.getEngine().getSatCalls(),
                "An identical netlist should never reach the solver");
    }

    /**
     * Corrupting look up table contents in a real design must be caught, and the
     * report must name an affected terminal pin.
     *
     * This is the acceptance tests' negative case applied to a real
     * netlist rather than a fixture.
     */
    @ParameterizedTest
    @ValueSource(strings = { "picoblaze_ooc_X10Y235.dcp", "bnn.dcp", "verilog_ethernet.dcp" })
    public void testCorruptedLutsAreCaught(String dcp) {
        EDIFNetlist imp = load(dcp);
        int mutated = LecNetlistFixtures.invertLutInits(imp);
        Assertions.assertTrue(mutated > 0, "No look up tables were found to corrupt in " + dcp);

        LecHarness result = LecHarness.run(load(dcp), imp);
        result.assertFail();
        LecDifference difference = result.findDifference(LecDifferenceType.CONE_FUNCTION_DIFFERS);
        Assertions.assertNotNull(difference,
                "Corrupting " + mutated + " look up tables produced no reported difference");
        Assertions.assertNotNull(difference.getSubject(), "The affected pin must be named");
        Assertions.assertTrue(result.witnessesDifference(difference.getSubject(),
                difference.getVerdict().getCounterExample()),
                "The counter-example must genuinely separate the two functions");
    }

    /**
     * The largest available design, run end to end and held to explicit
     * resource ceilings so that a regression in the scaling strategy shows up
     * as a failure rather than as a slow build.
     */
    @Test
    @LargeTest(max_memory_gb = 8)
    public void testLargeDesignRunsWithinBudget() {
        long start = System.nanoTime();
        LecHarness result = LecHarness.run(load("optical-flow.dcp"), load("optical-flow.dcp"));
        double seconds = (System.nanoTime() - start) / 1e9;

        result.assertPass();
        Assertions.assertTrue(result.getReport().getConesChecked() > 100000,
                "Expected a large number of cones, got "
                        + result.getReport().getConesChecked());
        Assertions.assertTrue(seconds < 300,
                "Comparing a 66k primitive design took " + seconds + " seconds");
    }
}

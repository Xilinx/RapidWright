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
import java.util.Map;

import org.junit.jupiter.api.Assertions;

import com.xilinx.rapidwright.edif.EDIFNetlist;

/**
 * Runs a comparison in process and helps assert on the outcome.
 *
 * Also provides an independent evaluator for the shared graph. Checking a
 * reported counter-example by re-evaluating both cone functions under it is
 * what distinguishes a genuine witness from a plausible looking assignment, and
 * it is deliberately written from the graph structure rather than by reusing
 * the simulator, so that a bug in the simulator cannot hide behind itself.
 */
public class LecHarness {

    private final LecResult result;

    private final String output;

    private LecHarness(LecResult result, String output) {
        this.result = result;
        this.output = output;
    }

    public static LecHarness run(EDIFNetlist ref, EDIFNetlist imp) {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        PrintStream out = new PrintStream(buffer);
        LecResult result = run(ref, imp, out);
        out.flush();
        return new LecHarness(result, buffer.toString());
    }

    public static LecResult run(EDIFNetlist ref, EDIFNetlist imp, PrintStream out) {
        return LogicEquivalenceChecker.compare(ref, imp, new LecConfig(), out);
    }

    public LecReport getReport() {
        return result.getReport();
    }

    public EquivalenceEngine getEngine() {
        return result.getEngine();
    }

    public LecResult getResult() {
        return result;
    }

    public int getExitCode() {
        return result.getExitCode();
    }

    /** @return The full rendered report, for assertions about what was printed. */
    public String getOutput() {
        return output;
    }

    public void assertPass() {
        Assertions.assertEquals(LogicEquivalenceChecker.EXIT_PASS, result.getExitCode(),
                "Expected PASS but the run failed. Report:" + System.lineSeparator() + output);
    }

    public void assertFail() {
        Assertions.assertEquals(LogicEquivalenceChecker.EXIT_FAIL, result.getExitCode(),
                "Expected FAIL but the run passed. Report:" + System.lineSeparator() + output);
    }

    /** @return The first reported finding of the given kind, or null. */
    public LecDifference findDifference(LecDifferenceType type) {
        for (LecDifference difference : result.getReport().getDifferences()) {
            if (difference.getType() == type) {
                return difference;
            }
        }
        return null;
    }

    /**
     * Re-evaluates both cone functions of a checked pin under an assignment, and
     * reports whether they really do disagree there.
     */
    public boolean witnessesDifference(String coneKey, Map<String, Boolean> assignment) {
        Integer refLit = result.getReference().getConeLiterals().get(coneKey);
        Integer impLit = result.getImplementation().getConeLiterals().get(coneKey);
        if (refLit == null || impLit == null) {
            return false;
        }
        return evaluate(refLit, assignment) != evaluate(impLit, assignment);
    }

    /**
     * Evaluates one literal of the shared graph under a named assignment.
     * Inputs the assignment does not mention are taken as zero, which is safe
     * because the reported assignment covers the union of both cones' inputs.
     */
    public boolean evaluate(int lit, Map<String, Boolean> assignment) {
        Aig aig = result.getAig();
        boolean[] values = new boolean[aig.getNodeCount()];
        for (int node = 1; node < aig.getNodeCount(); node++) {
            if (aig.isPi(node)) {
                Boolean value = assignment.get(aig.getPiName(aig.getPiIndex(node)));
                values[node] = value != null && value;
            } else {
                values[node] = literalValue(aig, values, aig.getFanin0(node))
                        && literalValue(aig, values, aig.getFanin1(node));
            }
        }
        return literalValue(aig, values, lit);
    }

    private static boolean literalValue(Aig aig, boolean[] values, int lit) {
        boolean value = Aig.nodeOf(lit) == 0 ? false : values[Aig.nodeOf(lit)];
        return Aig.isComplement(lit) != value;
    }
}

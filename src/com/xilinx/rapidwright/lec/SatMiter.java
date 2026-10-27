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

import java.util.HashMap;
import java.util.Map;
import java.util.TreeMap;

import com.google.ortools.Loader;
import com.google.ortools.sat.BoolVar;
import com.google.ortools.sat.CpModel;
import com.google.ortools.sat.CpSolver;
import com.google.ortools.sat.CpSolverStatus;
import com.google.ortools.sat.Literal;

/**
 * Decides whether two cones can ever disagree, by asking a solver.
 *
 * The question is posed as a miter: assert that the two cone roots take
 * different values and ask whether that is satisfiable. Unsatisfiable means the
 * two functions are equal for every input, which is a proof of equivalence.
 * Satisfiable yields an input assignment on which they differ, which is exactly
 * the counter-example reported with a difference.
 *
 * <h2>On the choice of solver</h2>
 *
 * This uses the CP-SAT solver already on RapidWright's classpath rather than
 * adding a dependency. It is adequate but not ideal for pure propositional
 * work: model construction crosses a protocol buffer and JNI boundary, and it
 * offers no incremental solving, so nothing is learned from one cone to the
 * next. Those costs are contained by never reaching this rung unless structural
 * hashing and simulation have both failed to settle the question, and by the
 * size guards and time budget in {@link EquivalenceEngine}. The class is kept
 * behind a narrow interface so an external solver could be substituted without
 * touching the engine.
 */
public class SatMiter {

    /** How a miter came out. */
    public enum Outcome {
        /** Proven equal on every input assignment. */
        EQUIVALENT,
        /** A differing input assignment was found. */
        DIFFERENT,
        /** The solver ran out of time; nothing was proven either way. */
        TIMEOUT
    }

    /** The verdict on one miter, with a counter-example when there is one. */
    public static class Result {

        private final Outcome outcome;

        private final Map<String, Boolean> counterExample;

        Result(Outcome outcome, Map<String, Boolean> counterExample) {
            this.outcome = outcome;
            this.counterExample = counterExample;
        }

        public Outcome getOutcome() {
            return outcome;
        }

        /** @return The differing assignment, by input name, or null. */
        public Map<String, Boolean> getCounterExample() {
            return counterExample;
        }
    }

    private static boolean nativeLibrariesLoaded;

    private static RuntimeException nativeLoadFailure;

    private final Aig aig;

    private final AigSupport support;

    private long solveCount;

    private double solveSeconds;

    public SatMiter(Aig aig, AigSupport support) {
        this.aig = aig;
        this.support = support;
    }

    /**
     * Loads the solver's native libraries once, turning a link failure into a
     * clear message rather than an error deep inside a comparison.
     */
    private static synchronized void ensureNativeLibraries() {
        if (nativeLoadFailure != null) {
            throw nativeLoadFailure;
        }
        if (nativeLibrariesLoaded) {
            return;
        }
        try {
            Loader.loadNativeLibraries();
            nativeLibrariesLoaded = true;
        } catch (RuntimeException | UnsatisfiedLinkError e) {
            nativeLoadFailure = new LecException("Could not load the CP-SAT native libraries, so "
                    + "cones that structural hashing and simulation cannot settle have to be "
                    + "reported inconclusive: " + e, e instanceof Throwable ? e : null);
            throw nativeLoadFailure;
        }
    }

    /** @return True if the solver is usable in this environment. */
    public static boolean isAvailable() {
        try {
            ensureNativeLibraries();
            return true;
        } catch (RuntimeException e) {
            return false;
        }
    }

    /**
     * Asks whether the two literals can differ.
     *
     * @param litRef     The reference cone root.
     * @param litImp     The implementation cone root.
     * @param timeoutSec Wall clock limit for this one question.
     */
    public Result solve(int litRef, int litImp, double timeoutSec) {
        ensureNativeLibraries();
        int[] nodes = support.coneNodes(new int[] { litRef, litImp });

        CpModel model = new CpModel();
        Map<Integer, Literal> vars = new HashMap<>(nodes.length * 2);
        Map<Integer, String> inputNames = new TreeMap<>();
        encode(model, nodes, vars, inputNames);

        Literal ref = literalOf(model, vars, litRef);
        Literal imp = literalOf(model, vars, litImp);
        model.addBoolXor(new Literal[] { ref, imp });

        CpSolver solver = new CpSolver();
        solver.getParameters().setMaxTimeInSeconds(timeoutSec);
        solver.getParameters().setNumSearchWorkers(1);
        solver.getParameters().setRandomSeed(1);
        long start = System.nanoTime();
        CpSolverStatus status = solver.solve(model);
        solveSeconds += (System.nanoTime() - start) / 1e9;
        solveCount++;

        if (status == CpSolverStatus.INFEASIBLE) {
            return new Result(Outcome.EQUIVALENT, null);
        }
        if (status == CpSolverStatus.OPTIMAL || status == CpSolverStatus.FEASIBLE) {
            return new Result(Outcome.DIFFERENT, readModel(solver, vars, inputNames));
        }
        return new Result(Outcome.TIMEOUT, null);
    }

    /**
     * Tseitin encodes every AND node of the cone. Each node contributes the
     * three clauses that make its variable equal the conjunction of its fanins.
     */
    private void encode(CpModel model, int[] nodes, Map<Integer, Literal> vars,
            Map<Integer, String> inputNames) {
        for (int node : nodes) {
            if (node == 0) {
                continue;
            }
            BoolVar var = model.newBoolVar("n" + node);
            vars.put(node, var);
            if (aig.isPi(node)) {
                inputNames.put(node, aig.getPiName(aig.getPiIndex(node)));
                continue;
            }
        }
        for (int node : nodes) {
            if (node == 0 || aig.isPi(node)) {
                continue;
            }
            Literal self = vars.get(node);
            Literal a = literalOf(model, vars, aig.getFanin0(node));
            Literal b = literalOf(model, vars, aig.getFanin1(node));
            model.addBoolOr(new Literal[] { self.not(), a });
            model.addBoolOr(new Literal[] { self.not(), b });
            model.addBoolOr(new Literal[] { self, a.not(), b.not() });
        }
    }

    /**
     * Maps a graph literal onto a solver literal, materialising the constant as
     * a fixed variable the first time it is needed.
     */
    private Literal literalOf(CpModel model, Map<Integer, Literal> vars, int lit) {
        int node = Aig.nodeOf(lit);
        Literal var = vars.get(node);
        if (var == null) {
            BoolVar constant = model.newBoolVar("const");
            model.addEquality(constant, 0);
            var = constant;
            vars.put(node, var);
        }
        return Aig.isComplement(lit) ? var.not() : var;
    }

    /** Reads the differing assignment back out of a satisfied model. */
    private static Map<String, Boolean> readModel(CpSolver solver, Map<Integer, Literal> vars,
            Map<Integer, String> inputNames) {
        Map<String, Boolean> assignment = new TreeMap<>();
        for (Map.Entry<Integer, String> e : inputNames.entrySet()) {
            Literal var = vars.get(e.getKey());
            assignment.put(e.getValue(), solver.booleanValue(var));
        }
        return assignment;
    }

    public long getSolveCount() {
        return solveCount;
    }

    public double getSolveSeconds() {
        return solveSeconds;
    }
}

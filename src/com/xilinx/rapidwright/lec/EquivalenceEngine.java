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

import java.util.ArrayList;
import java.util.Comparator;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Decides, for every checked pin, whether the two netlists compute the same
 * function.
 *
 * <h2>The escalation ladder</h2>
 *
 * <ol>
 * <li><b>Structural.</b> Both netlists were built into one graph over shared,
 * name keyed inputs, so two cones computed the same way are the <i>same
 * integer</i>. Equal literals prove equivalence and opposite literals refute it,
 * both in constant time. On a restructured but not resynthesised design this
 * settles the overwhelming majority of pins.</li>
 * <li><b>Simulation.</b> One pass over the graph evaluates sixty four patterns
 * for every node, hence for every cone at once, so refuting a thousand cones
 * costs the same as refuting one. A genuine bug such as a rewired pin or a
 * flipped INIT bit almost always falls out here, complete with a
 * counter-example.</li>
 * <li><b>Solver.</b> Only the pairs that neither hashing nor simulation could
 * settle reach the solver, which either proves them equal or produces a
 * differing assignment.</li>
 * </ol>
 *
 * Verdicts are cached on the literal pair, which matters more than it looks:
 * thousands of flip flops typically share one clock enable or reset cone, so
 * the number of distinct questions is far smaller than the number of pins.
 *
 * <h2>Why a difference can be downgraded</h2>
 *
 * A cone whose inputs include an unmatchable variable, from a broken
 * combinational loop, a multi-driver net or a black box, cannot be compared
 * honestly: the two netlists were handed different variables for the same
 * physical signal, so any disagreement may be an artefact. Such a cone is
 * reported inconclusive rather than differing. A structural or solver
 * <i>proof</i> of equality still stands, because that compares the functions
 * over whatever variables they share.
 */
public class EquivalenceEngine {

    /** One pin to be checked, and the two cone roots feeding it. */
    public static class Task {

        private final String key;

        private final int refLit;

        private final int impLit;

        private ConeVerdict verdict;

        public Task(String key, int refLit, int impLit) {
            this.key = key;
            this.refLit = refLit;
            this.impLit = impLit;
        }

        public String getKey() {
            return key;
        }

        public ConeVerdict getVerdict() {
            return verdict;
        }
    }

    private final Aig aig;

    private final AigSupport support;

    private final AigSimulator simulator;

    private final SatMiter sat;

    private final LecConfig config;

    private final LecLog log;

    private final Map<Long, ConeVerdict> cache = new HashMap<>();

    private int skippedTooLarge;

    private int skippedNoSolver;

    private double satSecondsUsed;

    public EquivalenceEngine(Aig aig, LecConfig config, LecLog log) {
        this.aig = aig;
        this.config = config;
        this.log = log;
        this.support = new AigSupport(aig);
        this.simulator = new AigSimulator(aig);
        this.sat = new SatMiter(aig, support);
    }

    /**
     * Runs the whole ladder over every task, filling in each one's verdict.
     */
    public void run(List<Task> tasks) {
        List<Task> pending = classifyStructurally(tasks);
        Map<Long, List<Task>> groups = groupByLiteralPair(pending);
        simulator.reset(0x5EEDL);
        refuteBySimulation(groups);
        resolveWithSolver(groups);
        applyCachedVerdicts(tasks);
    }

    /**
     * The constant time rung. Equal literals are the same function; opposite
     * literals are provably different.
     */
    private List<Task> classifyStructurally(List<Task> tasks) {
        List<Task> pending = new ArrayList<>();
        for (Task task : tasks) {
            long key = pairKey(task.refLit, task.impLit);
            ConeVerdict cached = cache.get(key);
            if (cached != null) {
                continue;
            }
            if (task.refLit == task.impLit) {
                cache.put(key, ConeVerdict.equivalent(ConeVerdict.Provenance.STRUCTURAL_HASH));
            } else if (task.refLit == Aig.not(task.impLit)) {
                cache.put(key, refuted(task, ConeVerdict.Provenance.STRUCTURAL_COMPLEMENT,
                        "the two cones are exact complements", null));
            } else {
                pending.add(task);
            }
        }
        return pending;
    }

    /** Collapses tasks that ask the same question, so it is answered once. */
    private Map<Long, List<Task>> groupByLiteralPair(List<Task> pending) {
        Map<Long, List<Task>> groups = new HashMap<>();
        for (Task task : pending) {
            long key = pairKey(task.refLit, task.impLit);
            List<Task> group = groups.get(key);
            if (group == null) {
                group = new ArrayList<>();
                groups.put(key, group);
            }
            group.add(task);
        }
        return groups;
    }

    /**
     * Runs rounds of patterns, retiring every pair a round separates. Because a
     * round evaluates the whole graph, the cost does not grow with the number of
     * pairs still outstanding.
     */
    private void refuteBySimulation(Map<Long, List<Task>> groups) {
        for (int round = 0; round < config.getSimRounds() && !groups.isEmpty(); round++) {
            simulator.runRound();
            List<Long> settled = new ArrayList<>();
            for (Map.Entry<Long, List<Task>> entry : groups.entrySet()) {
                Task representative = entry.getValue().get(0);
                int pattern = simulator.firstDifferingPattern(representative.refLit,
                        representative.impLit);
                if (pattern < 0) {
                    continue;
                }
                cache.put(entry.getKey(), refuted(representative,
                        ConeVerdict.Provenance.SIMULATION,
                        "simulation found a differing input assignment",
                        counterExampleAt(representative, pattern)));
                settled.add(entry.getKey());
            }
            for (Long key : settled) {
                groups.remove(key);
            }
        }
    }

    /**
     * Asks the solver about whatever remains, within the overall time budget.
     * Anything not settled is reported inconclusive rather than silently passed,
     * because a verdict that is not a proof must never read as a pass.
     *
     * The size guards order the work rather than exclude it. Cones within them
     * are solved first; cones beyond them are then attempted smallest first
     * with whatever budget is left. A large design can have a few hundred wide
     * cones the solver settles quickly, and refusing them outright would fail
     * the run for want of trying.
     */
    private void resolveWithSolver(Map<Long, List<Task>> groups) {
        boolean solverUsable = groups.isEmpty() || SatMiter.isAvailable();
        List<LargeCone> largeCones = new ArrayList<>();
        for (Map.Entry<Long, List<Task>> entry : groups.entrySet()) {
            Task task = entry.getValue().get(0);
            if (!solverUsable) {
                skippedNoSolver++;
                cache.put(entry.getKey(), inconclusive(task,
                        "the CP-SAT native library is unavailable"));
                continue;
            }
            int[] union = support.support(new int[] { task.refLit, task.impLit });
            int coneSize = support.coneSize(new int[] { task.refLit, task.impLit });
            if (union.length > config.getSatMaxSupport() || coneSize > config.getSatMaxNodes()) {
                largeCones.add(new LargeCone(entry.getKey(), task, union.length, coneSize));
                continue;
            }
            if (satSecondsUsed >= config.getSatBudgetSec()) {
                cache.put(entry.getKey(), inconclusive(task,
                        "the overall solver budget of " + config.getSatBudgetSec()
                                + " seconds was exhausted"));
                continue;
            }
            cache.put(entry.getKey(), solveOne(task));
        }
        largeCones.sort(Comparator.comparingInt((LargeCone c) -> c.inputs)
                .thenComparingInt(c -> c.nodes));
        for (LargeCone cone : largeCones) {
            if (satSecondsUsed >= config.getSatBudgetSec()) {
                skippedTooLarge++;
                cache.put(cone.key, inconclusive(cone.task, "the cone is large (" + cone.inputs
                        + " inputs, " + cone.nodes + " nodes) and the solver budget of "
                        + config.getSatBudgetSec() + " seconds ran out before it was "
                        + "attempted; consider raising --sat-budget"));
                continue;
            }
            cache.put(cone.key, solveOne(cone.task));
        }
    }

    /** A question beyond the size guards, deferred until the others are done. */
    private static class LargeCone {

        private final Long key;

        private final Task task;

        private final int inputs;

        private final int nodes;

        LargeCone(Long key, Task task, int inputs, int nodes) {
            this.key = key;
            this.task = task;
            this.inputs = inputs;
            this.nodes = nodes;
        }
    }

    private ConeVerdict solveOne(Task task) {
        double before = sat.getSolveSeconds();
        SatMiter.Result result;
        try {
            result = sat.solve(task.refLit, task.impLit, config.getSatTimeoutSec());
        } catch (LecException e) {
            return inconclusive(task, e.getMessage());
        } finally {
            satSecondsUsed += sat.getSolveSeconds() - before;
        }
        switch (result.getOutcome()) {
        case EQUIVALENT:
            return ConeVerdict.equivalent(ConeVerdict.Provenance.SAT_PROOF);
        case DIFFERENT:
            return refuted(task, ConeVerdict.Provenance.SAT_COUNTEREXAMPLE,
                    "the solver found a differing input assignment", result.getCounterExample());
        default:
            return inconclusive(task, "the solver reached its "
                    + config.getSatTimeoutSec() + " second limit without deciding");
        }
    }

    /**
     * Builds a refutation, downgrading it to inconclusive when the cone reaches
     * a variable the two netlists could not be made to share.
     */
    private ConeVerdict refuted(Task task, ConeVerdict.Provenance provenance, String reason,
            Map<String, Boolean> counterExample) {
        int[] refInputs = support.support(task.refLit);
        int[] impInputs = support.support(task.impLit);
        int[] union = merge(refInputs, impInputs);
        if (support.isTainted(union)) {
            return new ConeVerdict(ConeVerdict.Kind.INCONCLUSIVE, ConeVerdict.Provenance.ABANDONED,
                    "the cones differ, but the difference cannot be attributed because the cone "
                            + "reaches a variable that cannot be matched between the two netlists",
                    counterExample, only(refInputs, impInputs), only(impInputs, refInputs),
                    support.taintedNames(union));
        }
        return new ConeVerdict(ConeVerdict.Kind.DIFFERENT, provenance, reason, counterExample,
                only(refInputs, impInputs), only(impInputs, refInputs), null);
    }

    private ConeVerdict inconclusive(Task task, String reason) {
        int[] refInputs = support.support(task.refLit);
        int[] impInputs = support.support(task.impLit);
        int[] union = merge(refInputs, impInputs);
        return new ConeVerdict(ConeVerdict.Kind.INCONCLUSIVE, ConeVerdict.Provenance.ABANDONED,
                reason, null, only(refInputs, impInputs), only(impInputs, refInputs),
                support.taintedNames(union));
    }

    /**
     * Reads the assignment that separated a pair out of the simulator, keeping
     * only the inputs the two cones actually reach.
     */
    private Map<String, Boolean> counterExampleAt(Task task, int pattern) {
        int[] union = merge(support.support(task.refLit), support.support(task.impLit));
        Map<String, Boolean> assignment = new TreeMap<>();
        for (int pi : union) {
            assignment.put(aig.getPiName(pi), simulator.inputValue(pi, pattern));
        }
        return assignment;
    }

    private void applyCachedVerdicts(List<Task> tasks) {
        for (Task task : tasks) {
            task.verdict = cache.get(pairKey(task.refLit, task.impLit));
            if (task.verdict == null) {
                task.verdict = ConeVerdict.equivalent(ConeVerdict.Provenance.STRUCTURAL_HASH);
            }
        }
    }

    private static long pairKey(int refLit, int impLit) {
        return ((long) refLit << 32) | (impLit & 0xFFFFFFFFL);
    }

    private static int[] merge(int[] a, int[] b) {
        int[] result = Arrays.copyOf(a, a.length + b.length);
        System.arraycopy(b, 0, result, a.length, b.length);
        Arrays.sort(result);
        int count = 0;
        for (int i = 0; i < result.length; i++) {
            if (i == 0 || result[i] != result[i - 1]) {
                result[count++] = result[i];
            }
        }
        return Arrays.copyOf(result, count);
    }

    /** @return The names of inputs present in the first set but not the second. */
    private String[] only(int[] a, int[] b) {
        List<String> names = new ArrayList<>();
        for (int pi : a) {
            if (Arrays.binarySearch(b, pi) < 0) {
                names.add(aig.getPiName(pi));
            }
        }
        return names.toArray(new String[0]);
    }

    /**
     * The provenance counters below count <i>distinct questions</i> rather than
     * pins, because the answer to one question is shared by every pin whose
     * cones pose it. They are what the acceptance tests assert on: a positive
     * test that passes entirely by structural hashing proves nothing about the
     * solver path, so the tests check that the expected rung actually fired.
     */
    private int countProvenance(ConeVerdict.Provenance provenance) {
        int count = 0;
        for (ConeVerdict verdict : cache.values()) {
            if (verdict.getProvenance() == provenance) {
                count++;
            }
        }
        return count;
    }

    private int countKind(ConeVerdict.Kind kind) {
        int count = 0;
        for (ConeVerdict verdict : cache.values()) {
            if (verdict.getKind() == kind) {
                count++;
            }
        }
        return count;
    }

    public int getProvenByHash() {
        return countProvenance(ConeVerdict.Provenance.STRUCTURAL_HASH);
    }

    public int getRefutedByHash() {
        return countProvenance(ConeVerdict.Provenance.STRUCTURAL_COMPLEMENT);
    }

    public int getRefutedBySimulation() {
        return countProvenance(ConeVerdict.Provenance.SIMULATION);
    }

    public int getProvenBySat() {
        return countProvenance(ConeVerdict.Provenance.SAT_PROOF);
    }

    public int getRefutedBySat() {
        return countProvenance(ConeVerdict.Provenance.SAT_COUNTEREXAMPLE);
    }

    public int getInconclusiveQuestions() {
        return countKind(ConeVerdict.Kind.INCONCLUSIVE);
    }

    public int getSkippedTooLarge() {
        return skippedTooLarge;
    }

    public int getSkippedNoSolver() {
        return skippedNoSolver;
    }

    public double getSatSeconds() {
        return sat.getSolveSeconds();
    }

    public long getSatCalls() {
        return sat.getSolveCount();
    }

    public LecLog getLog() {
        return log;
    }
}

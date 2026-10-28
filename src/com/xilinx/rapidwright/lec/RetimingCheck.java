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

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Proves a retiming certificate and turns the comparison of two retimed
 * netlists back into a combinational one.
 *
 * <h2>The argument</h2>
 *
 * Each certificate entry defines a register of one netlist as a combinational
 * function of the other netlist's register outputs. The state variables the
 * two netlists share are: registers matched by name, and registers present in
 * only one netlist that a definition reads directly ("retimed away"). Every
 * defined register is then a function of those, by the substitution sigma.
 * Equivalence follows by induction over clock edges from three obligations,
 * each a combinational check over the shared variables:
 * <ol>
 * <li><b>Initial state.</b> Each definition holds with both netlists at their
 * {@code INIT} values. Every value is a constant, so this folds to a constant
 * with no solver.</li>
 * <li><b>Step.</b> Each definition is preserved by a clock edge:
 * {@code sigma(next(R)) == sigma(tau(def))}, where {@code next} is the
 * register's next-state function including clock enable and synchronous
 * reset or set, and {@code tau} advances the defining netlist's registers by
 * one edge.</li>
 * <li><b>Everything else.</b> Every check plain equivalence makes, with sigma
 * applied: matched terminals' input pins, top level outputs.</li>
 * </ol>
 * A definition and the registers it reads must share a clock, also checked.
 *
 * Every check holds for all values of the shared variables, so a retiming that
 * is only correct on the states a reset can reach fails, which is safe.
 *
 * <h2>Scope</h2>
 *
 * Defined registers, and the registers a definition reads, must be
 * {@code FDRE} or {@code FDSE}. A definition may not read a top level input,
 * since a register's value cannot depend on the current input, nor a black
 * box, memory or other terminal with no next-state model.
 */
class RetimingCheck {

    /** Subject prefix of the step obligation for one defined register. */
    static final String STEP = "retiming step ";

    /** Subject prefix of the clock obligation for one defined register. */
    static final String CLOCK = "retiming clock ";

    /** Subject prefix of the initial state obligation for one defined register. */
    static final String INIT = "retiming init ";

    private static final String FDRE = "FDRE";

    private static final String FDSE = "FDSE";

    /** One certificate entry, resolved against the elaborated netlists. */
    private static class Definition {

        private final RetimingMap.Entry entry;

        private final NetlistSummary owner;

        private final NetlistSummary definer;

        private final boolean set;

        private final int qPi;

        private final int defLit;

        private int[] supportPis;

        private String[] supportRegisters;

        private final List<Definition> dependsOn = new ArrayList<>();

        Definition(RetimingMap.Entry entry, NetlistSummary owner, NetlistSummary definer,
                boolean set, int qPi, int defLit) {
            this.entry = entry;
            this.owner = owner;
            this.definer = definer;
            this.set = set;
            this.qPi = qPi;
            this.defLit = defLit;
        }

        String register() {
            return entry.getRegister();
        }

        String subject() {
            return owner.getLabel() + " " + entry.getRegister();
        }
    }

    private final List<EquivalenceEngine.Task> tasks = new ArrayList<>();

    private final List<LecDifferenceType> taskTypes = new ArrayList<>();

    private final List<LecDifference> initDifferences = new ArrayList<>();

    private int mappedCount;

    private int retimedAwayCount;

    private int stepCount;

    private int clockCount;

    private RetimingCheck() {
    }

    /**
     * Resolves and applies a certificate. Builds every literal the check needs,
     * so it must run before the equivalence engine sizes itself to the graph.
     * Afterwards the summaries hold sigma-substituted cones and no longer list
     * the defined or retimed away registers.
     *
     * @throws LecException If the certificate is malformed for these netlists.
     */
    static RetimingCheck prepare(RetimingMap map, NetlistSummary ref, NetlistSummary imp,
            Aig aig) {
        RetimingCheck check = new RetimingCheck();
        List<String> problems = new ArrayList<>();
        List<Definition> defs = new ArrayList<>();
        Map<Integer, Definition> byQPi = new HashMap<>();
        for (RetimingMap.Entry e : map.getEntries()) {
            NetlistSummary owner = e.getRegisterSide() == RetimingMap.Side.REF ? ref : imp;
            NetlistSummary definer = owner == ref ? imp : ref;
            String type = owner.getTerminals().get(e.getRegister()).getCellTypeName();
            if (!type.equals(FDRE) && !type.equals(FDSE)) {
                problems.add(e.getSource() + ": " + owner.getLabel() + " " + e.getRegister()
                        + " is a " + type + "; only FDRE and FDSE registers can be retimed by a "
                        + "certificate (FDCE and FDPE clear and preset asynchronously)");
                continue;
            }
            int q = aig.newInput(TerminalKey.forRetimed(owner.getLabel(),
                    TerminalKey.forPin(e.getRegister(), "Q")), false);
            Definition d = new Definition(e, owner, definer, type.equals(FDSE),
                    aig.getPiIndex(Aig.nodeOf(q)),
                    definer.getDefinitionLiterals().get(e.getDefiningPinKey()));
            defs.add(d);
            byQPi.put(d.qPi, d);
        }
        failOn(problems);

        AigSupport support = new AigSupport(aig);
        for (Definition d : defs) {
            check.resolveSupport(d, byQPi, support, aig, problems);
        }
        failOn(problems);
        List<Definition> order = topologicalOrder(defs, problems);
        failOn(problems);

        AigSubstitution sigma = new AigSubstitution(aig, qPisOf(defs));
        for (Definition d : order) {
            sigma.setValue(d.qPi, sigma.apply(d.defLit));
        }
        check.checkInitialState(defs, ref, imp, aig);
        check.addStepAndClockTasks(defs, ref, imp, aig, sigma);

        Set<String> refAway = new LinkedHashSet<>();
        Set<String> impAway = new LinkedHashSet<>();
        check.findRetimedAway(defs, byQPi, ref, imp, refAway, impAway);
        for (Definition d : defs) {
            d.owner.removeRegister(d.register(), registerPins(d.set));
        }
        removeAll(ref, refAway);
        removeAll(imp, impAway);
        check.mappedCount = defs.size();
        check.retimedAwayCount = refAway.size() + impAway.size();

        ref.substituteConeLiterals(sigma::apply);
        imp.substituteConeLiterals(sigma::apply);
        return check;
    }

    /**
     * Finds the registers a definition reads, all of which must be FDRE or FDSE
     * outputs of the defining netlist.
     */
    private void resolveSupport(Definition d, Map<Integer, Definition> byQPi,
            AigSupport support, Aig aig, List<String> problems) {
        int[] pis = support.support(d.defLit);
        String[] registers = new String[pis.length];
        for (int i = 0; i < pis.length; i++) {
            Definition defined = byQPi.get(pis[i]);
            if (defined != null) {
                d.dependsOn.add(defined);
                registers[i] = defined.register();
                continue;
            }
            String name = aig.getPiName(pis[i]);
            String reason = null;
            String register = null;
            if (!aig.isPiStable(pis[i])) {
                reason = "a signal that cannot be matched between the netlists (a black box, "
                        + "loop or short)";
            } else if (!name.endsWith("/Q")) {
                reason = "a top level input or a pin that is not a register output";
            } else {
                register = name.substring(0, name.length() - 2);
                TerminalAttributes attrs = d.definer.getTerminals().get(register);
                if (attrs == null) {
                    reason = "a pin that is not a register output";
                } else if (!isRetimable(attrs)) {
                    reason = "the output of a " + attrs.getCellTypeName()
                            + ", which has no next-state model";
                }
            }
            if (reason != null) {
                problems.add(d.entry.getSource() + ": the definition of " + d.subject()
                        + " reads " + name + ", " + reason + "; a definition may only read "
                        + "FDRE and FDSE outputs");
            }
            registers[i] = register;
        }
        d.supportPis = pis;
        d.supportRegisters = registers;
    }

    /** Orders definitions so each comes after those it reads; refuses cycles. */
    private static List<Definition> topologicalOrder(List<Definition> defs,
            List<String> problems) {
        Map<Definition, Integer> pending = new HashMap<>();
        Map<Definition, List<Definition>> readers = new HashMap<>();
        Deque<Definition> ready = new ArrayDeque<>();
        for (Definition d : defs) {
            pending.put(d, d.dependsOn.size());
            for (Definition dep : d.dependsOn) {
                readers.computeIfAbsent(dep, k -> new ArrayList<>()).add(d);
            }
            if (d.dependsOn.isEmpty()) {
                ready.add(d);
            }
        }
        List<Definition> order = new ArrayList<>();
        while (!ready.isEmpty()) {
            Definition d = ready.poll();
            order.add(d);
            for (Definition reader : readers.getOrDefault(d, new ArrayList<>())) {
                if (pending.merge(reader, -1, Integer::sum) == 0) {
                    ready.add(reader);
                }
            }
        }
        if (order.size() < defs.size()) {
            List<String> cyclic = new ArrayList<>();
            for (Definition d : defs) {
                if (pending.get(d) > 0) {
                    cyclic.add(d.subject());
                }
            }
            problems.add("the retiming map is cyclic: these registers are defined in terms of "
                    + "each other: " + String.join(", ", cyclic));
        }
        return order;
    }

    /**
     * Obligation 1: every definition holds at the initial state. All register
     * values are constants there, so each definition folds to a constant.
     */
    private void checkInitialState(List<Definition> defs, NetlistSummary ref,
            NetlistSummary imp, Aig aig) {
        Map<NetlistSummary, AigSubstitution> initial = new HashMap<>();
        for (NetlistSummary definer : new NetlistSummary[] { ref, imp }) {
            AigSubstitution sub = new AigSubstitution(aig, supportPisDefinedBy(defs, definer));
            for (Definition d : defs) {
                if (d.definer != definer) {
                    continue;
                }
                for (int i = 0; i < d.supportPis.length; i++) {
                    TerminalAttributes attrs = definer.getTerminals().get(d.supportRegisters[i]);
                    sub.setValue(d.supportPis[i], initOf(attrs) ? Aig.TRUE : Aig.FALSE);
                }
            }
            initial.put(definer, sub);
        }
        for (Definition d : defs) {
            int value = initial.get(d.definer).apply(d.defLit);
            boolean expected = initOf(d.owner.getTerminals().get(d.register()));
            if (value != (expected ? Aig.TRUE : Aig.FALSE)) {
                initDifferences.add(new LecDifference(LecDifferenceType.RETIMING_INIT_DIFFERS,
                        INIT + d.subject(), "INIT is " + (expected ? 1 : 0) + " but "
                                + d.definer.getLabel() + " " + d.entry.getDefiningPinKey()
                                + " is " + (expected ? 0 : 1) + " in the initial state",
                        null, null));
            }
        }
    }

    /**
     * Obligation 2, and the clock condition, as engine tasks: each defined
     * register's next state against its definition advanced by one edge.
     */
    private void addStepAndClockTasks(List<Definition> defs, NetlistSummary ref,
            NetlistSummary imp, Aig aig, AigSubstitution sigma) {
        Map<NetlistSummary, AigSubstitution> advance = new HashMap<>();
        for (NetlistSummary definer : new NetlistSummary[] { ref, imp }) {
            AigSubstitution tau = new AigSubstitution(aig, supportPisDefinedBy(defs, definer));
            for (Definition d : defs) {
                if (d.definer != definer) {
                    continue;
                }
                for (int i = 0; i < d.supportPis.length; i++) {
                    TerminalAttributes attrs = definer.getTerminals().get(d.supportRegisters[i]);
                    int q = Aig.litOf(aig.getPiNode(d.supportPis[i]));
                    tau.setValue(d.supportPis[i], nextState(aig, definer, d.supportRegisters[i],
                            attrs.getCellTypeName().equals(FDSE), q));
                }
            }
            advance.put(definer, tau);
        }
        for (Definition d : defs) {
            int q = Aig.litOf(aig.getPiNode(d.qPi));
            int registerNext = sigma.apply(nextState(aig, d.owner, d.register(), d.set, q));
            int definitionNext = sigma.apply(advance.get(d.definer).apply(d.defLit));
            addTask(STEP + d.subject(), d.owner, registerNext, definitionNext,
                    LecDifferenceType.RETIMING_STEP_DIFFERS);
            stepCount++;

            int clock = sigma.apply(coneOf(d.owner, d.register(), "C"));
            Set<Integer> seen = new HashSet<>();
            for (String register : d.supportRegisters) {
                int other = sigma.apply(coneOf(d.definer, register, "C"));
                if (other != clock && seen.add(other)) {
                    addTask(CLOCK + d.subject() + " vs " + d.definer.getLabel() + " " + register,
                            d.owner, clock, other, LecDifferenceType.RETIMING_CLOCK_DIFFERS);
                }
            }
            clockCount++;
        }
    }

    /**
     * Registers in one netlist only that a definition reads directly. They are
     * the registers a retiming consumed, and are free variables of the check.
     */
    private void findRetimedAway(List<Definition> defs, Map<Integer, Definition> byQPi,
            NetlistSummary ref, NetlistSummary imp, Set<String> refAway, Set<String> impAway) {
        Map<NetlistSummary, Set<String>> defined = new HashMap<>();
        defined.put(ref, new HashSet<>());
        defined.put(imp, new HashSet<>());
        for (Definition d : defs) {
            defined.get(d.owner).add(d.register());
        }
        for (Definition d : defs) {
            NetlistSummary other = d.owner;
            for (int i = 0; i < d.supportPis.length; i++) {
                if (byQPi.containsKey(d.supportPis[i])) {
                    continue;
                }
                String register = d.supportRegisters[i];
                boolean matched = other.getTerminals().containsKey(register)
                        && !defined.get(other).contains(register);
                if (!matched) {
                    (d.definer == ref ? refAway : impAway).add(register);
                }
            }
        }
    }

    private void addTask(String key, NetlistSummary owner, int ownerLit, int otherLit,
            LecDifferenceType type) {
        boolean ownerIsImp = owner.getLabel().equals("IMP");
        tasks.add(new EquivalenceEngine.Task(key, ownerIsImp ? otherLit : ownerLit,
                ownerIsImp ? ownerLit : otherLit));
        taskTypes.add(type);
    }

    /** @return The register's next state: sync reset or set, then enable. */
    private static int nextState(Aig aig, NetlistSummary summary, String register, boolean set,
            int q) {
        int d = coneOf(summary, register, "D");
        int ce = coneOf(summary, register, "CE");
        int sr = coneOf(summary, register, set ? "S" : "R");
        int held = aig.mux(ce, d, q);
        return set ? aig.or(sr, held) : aig.and(Aig.not(sr), held);
    }

    private static int coneOf(NetlistSummary summary, String register, String pin) {
        Integer lit = summary.getConeLiterals().get(TerminalKey.forPin(register, pin));
        if (lit == null) {
            throw new LecException(summary.getLabel() + " " + register + " has no cone for pin "
                    + pin);
        }
        return lit;
    }

    private static boolean isRetimable(TerminalAttributes attrs) {
        String type = attrs.getCellTypeName();
        return type.equals(FDRE) || type.equals(FDSE);
    }

    /** @return The register's initial value; the unisim defaults are 0 and 1. */
    private static boolean initOf(TerminalAttributes attrs) {
        String raw = attrs.getProperties().get("INIT");
        if (raw == null) {
            return attrs.getCellTypeName().equals(FDSE);
        }
        return ModelUtils.parseBit(raw, "INIT");
    }

    private static String[] registerPins(boolean set) {
        return new String[] { "C", "CE", "D", set ? "S" : "R" };
    }

    private static void removeAll(NetlistSummary summary, Set<String> registers) {
        for (String register : registers) {
            boolean set = summary.getTerminals().get(register).getCellTypeName().equals(FDSE);
            summary.removeRegister(register, registerPins(set));
        }
    }

    private static int[] qPisOf(List<Definition> defs) {
        int[] pis = new int[defs.size()];
        for (int i = 0; i < pis.length; i++) {
            pis[i] = defs.get(i).qPi;
        }
        return pis;
    }

    private static int[] supportPisDefinedBy(List<Definition> defs, NetlistSummary definer) {
        Set<Integer> pis = new LinkedHashSet<>();
        for (Definition d : defs) {
            if (d.definer == definer) {
                for (int pi : d.supportPis) {
                    pis.add(pi);
                }
            }
        }
        return pis.stream().mapToInt(Integer::intValue).toArray();
    }

    private static void failOn(List<String> problems) {
        if (!problems.isEmpty()) {
            throw new LecException("Invalid retiming map:" + System.lineSeparator() + "  "
                    + String.join(System.lineSeparator() + "  ", problems));
        }
    }

    /** @return The step and clock obligations, to run with the other tasks. */
    List<EquivalenceEngine.Task> getTasks() {
        return tasks;
    }

    /** Adds the initial state findings and the verdicts of the tasks. */
    void record(LecReport report) {
        for (LecDifference difference : initDifferences) {
            report.add(difference);
        }
        for (int i = 0; i < tasks.size(); i++) {
            EquivalenceEngine.Task task = tasks.get(i);
            ConeVerdict verdict = task.getVerdict();
            report.countCone(verdict.getKind());
            if (verdict.getKind() == ConeVerdict.Kind.DIFFERENT) {
                report.add(new LecDifference(taskTypes.get(i), task.getKey(), null, verdict,
                        null));
            } else if (verdict.getKind() == ConeVerdict.Kind.INCONCLUSIVE) {
                report.add(new LecDifference(LecDifferenceType.CONE_INCONCLUSIVE, task.getKey(),
                        null, verdict, null));
            }
        }
        report.setRetimingCounts(mappedCount, retimedAwayCount, stepCount, clockCount);
    }
}

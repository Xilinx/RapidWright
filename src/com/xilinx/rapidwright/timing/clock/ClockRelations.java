/*
 *
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

package com.xilinx.rapidwright.timing.clock;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Set;

/**
 * The timing relations between the clocks of a design: for each pair of clock edges (launch,
 * capture), the setup and hold requirements by Vivado's edge alignment, and the pairs of clocks
 * that are not timed against each other.
 * <p>
 * A clock edge id is {@code 2*clock + (falling ? 1 : 0)}. For a launch edge and a capture edge, the
 * launch edges of the launch clock are enumerated over the common period of the two clocks (the
 * smallest number of launch periods, at most {@code MAX_CYCLES}, that is a multiple of the
 * capture period; {@code MAX_CYCLES} launch periods if there is none):
 * <ul>
 * <li>each launch edge tl is captured by the first capture edge after it, tc(tl) &gt; tl; the setup
 * requirement is the smallest tc(tl) - tl;</li>
 * <li>the hold requirement is the largest tp(tl) - tl, tp(tl) being the last capture edge at or
 * before tl (the capture edge before tc(tl)), i.e. data launched by tl must not be captured by
 * the edge before its setup capture edge.</li>
 * </ul>
 * A multicycle path of setup multiplier Ns moves the setup capture edge by Ns-1 capture periods
 * (or, with -start, the launch edge back by Ns-1 launch periods), the default hold check moving
 * with it; a hold multiplier Nh then moves the hold check back by Nh launch periods (or capture
 * periods with -end).
 * <p>
 * Pairs of clocks are false (not timed) when they are in different groups of a set_clock_groups,
 * when a set_false_path between them (clocks only) applies, and, unless
 * {@link #setUnrelatedFalse(boolean)} turns it off, when they are unrelated (no common primary
 * clock or source, see {@link ClockNetwork#areRelated(Clock, Clock)}). Times are in ps.
 */
public final class ClockRelations {

    /**
     * Maximum number of launch periods enumerated when looking for the common period of two clocks (a limit of
     * this analysis)
     */
    static final int MAX_CYCLES = 1000;

    /** Tolerance comparing edge times, in ps */
    private static final double EPS = 1e-3;

    private final List<Clock> clocks;
    private final int numClocks;
    /** Why a pair is false: 0 timed, else a REASON_* bit set; [setup/hold][launch][capture] */
    private final byte[][][] falseReason;
    /** Lazily computed {setup, hold} of each pair of clock edges */
    private final double[][] baseCache;

    /** False reason: different groups of a set_clock_groups */
    public static final byte REASON_GROUPS = 1;
    /** False reason: a set_false_path between the clocks */
    public static final byte REASON_FALSE_PATH = 2;
    /** False reason: unrelated clocks */
    public static final byte REASON_UNRELATED = 4;

    private final boolean[][] unrelated;
    private boolean unrelatedFalse = true;

    /**
     * Creates the relations of clocks, all timed against each other until pairs are made false.
     * @param clocks The clocks, by id.
     */
    public ClockRelations(List<Clock> clocks) {
        this.clocks = Collections.unmodifiableList(new ArrayList<>(clocks));
        this.numClocks = clocks.size();
        falseReason = new byte[2][numClocks][numClocks];
        unrelated = new boolean[numClocks][numClocks];
        baseCache = new double[4 * numClocks * numClocks][];
    }

    /**
     * Builds the relations of the clocks of a clock network, applying its constraints' clock
     * groups and clock-to-clock false paths, and marking unrelated clocks.
     * @param cn The clock network.
     * @return The relations.
     */
    public static ClockRelations build(ClockNetwork cn) {
        ClockRelations r = new ClockRelations(cn.getClocks());
        for (int a = 0; a < r.numClocks; a++) {
            for (int b = 0; b < r.numClocks; b++) {
                if (!cn.areRelated(cn.getClock(a), cn.getClock(b))) {
                    r.unrelated[a][b] = true;
                }
            }
        }
        for (TimingConstraints.ClockGroupsDef g : cn.getConstraints().getClockGroups()) {
            List<Set<Integer>> groups = new ArrayList<>();
            for (List<XdcObject> objs : g.groups) {
                groups.add(cn.resolveClocks(objs));
            }
            if (groups.size() == 1) {
                // A single group is exclusive with all other clocks
                Set<Integer> others = new java.util.LinkedHashSet<>();
                for (int c = 0; c < r.numClocks; c++) {
                    if (!groups.get(0).contains(c)) others.add(c);
                }
                groups.add(others);
            }
            for (int i = 0; i < groups.size(); i++) {
                for (int j = 0; j < groups.size(); j++) {
                    if (i == j) continue;
                    for (int a : groups.get(i)) {
                        for (int b : groups.get(j)) {
                            if (a == b) continue;
                            r.setFalse(a, b, true, true, REASON_GROUPS);
                        }
                    }
                }
            }
        }
        for (TimingConstraints.PathExceptionDef e : cn.getConstraints().getExceptions()) {
            if (e.kind != TimingConstraints.ExceptionKind.FALSE_PATH || !isClockOnly(e)) continue;
            Set<Integer> from = e.from == null ? null : cn.resolveClocks(e.from);
            Set<Integer> to = e.to == null ? null : cn.resolveClocks(e.to);
            for (int a = 0; a < r.numClocks; a++) {
                if (from != null && !from.contains(a)) continue;
                for (int b = 0; b < r.numClocks; b++) {
                    if (to != null && !to.contains(b)) continue;
                    r.setFalse(a, b, e.setup, e.hold, REASON_FALSE_PATH);
                }
            }
        }
        return r;
    }

    /** Whether an exception's -from and -to only name clocks (and at least one of them is given). */
    public static boolean isClockOnly(TimingConstraints.PathExceptionDef e) {
        if (e.from == null && e.to == null || e.through != null) {
            return false;
        }
        for (List<XdcObject> l : Arrays.asList(e.from, e.to)) {
            if (l == null) continue;
            for (XdcObject o : l) {
                if (o.getKind() != XdcObject.Kind.CLOCK) return false;
            }
        }
        return true;
    }

    /**
     * Makes a pair of clocks false (not timed).
     * @param launch Launch clock id.
     * @param capture Capture clock id.
     * @param setup For setup checks.
     * @param hold For hold checks.
     * @param reason A REASON_* bit.
     */
    public void setFalse(int launch, int capture, boolean setup, boolean hold, byte reason) {
        if (setup) falseReason[0][launch][capture] |= reason;
        if (hold) falseReason[1][launch][capture] |= reason;
    }

    /** Marks a pair of clocks as unrelated (false if {@link #isUnrelatedFalse()}). */
    public void setUnrelated(int a, int b, boolean value) {
        unrelated[a][b] = value;
    }

    /**
     * Sets whether unrelated clocks (no common primary clock or source) are false paths (true, the
     * default) or timed as Vivado does by default.
     */
    public void setUnrelatedFalse(boolean value) {
        unrelatedFalse = value;
    }

    public boolean isUnrelatedFalse() {
        return unrelatedFalse;
    }

    public boolean isUnrelated(int a, int b) {
        return unrelated[a][b];
    }

    public int getNumClocks() {
        return numClocks;
    }

    public List<Clock> getClocks() {
        return clocks;
    }

    /** Whether setup checks from a launch clock to a capture clock are not timed. */
    public boolean isFalseSetup(int launch, int capture) {
        return falseReason[0][launch][capture] != 0 || unrelatedFalse && unrelated[launch][capture];
    }

    /** Whether hold checks from a launch clock to a capture clock are not timed. */
    public boolean isFalseHold(int launch, int capture) {
        return falseReason[1][launch][capture] != 0 || unrelatedFalse && unrelated[launch][capture];
    }

    /** The REASON_* bits making a pair false for setup (0 if timed). */
    public byte getFalseReason(int launch, int capture) {
        byte r = falseReason[0][launch][capture];
        if (unrelatedFalse && unrelated[launch][capture]) {
            r |= REASON_UNRELATED;
        }
        return r;
    }

    /**
     * Gets the setup requirement of a pair of clock edges (no multicycle path).
     * @param launchEdge Launch clock edge id.
     * @param captureEdge Capture clock edge id.
     * @return The requirement in ps.
     */
    public double getSetup(int launchEdge, int captureEdge) {
        return base(launchEdge, captureEdge)[0];
    }

    /** Gets the hold requirement of a pair of clock edges (no multicycle path), in ps. */
    public double getHold(int launchEdge, int captureEdge) {
        return base(launchEdge, captureEdge)[1];
    }

    /**
     * Gets the setup requirement with a multicycle path.
     * @param launchEdge Launch clock edge id.
     * @param captureEdge Capture clock edge id.
     * @param setupMult The setup multiplier (1 for none).
     * @param setupStart Whether the multiplier counts launch periods (-start).
     * @return The requirement in ps.
     */
    public double getSetup(int launchEdge, int captureEdge, int setupMult, boolean setupStart) {
        double s = getSetup(launchEdge, captureEdge);
        if (setupMult != 1) {
            s += (setupMult - 1) * period(setupStart ? launchEdge : captureEdge);
        }
        return s;
    }

    /**
     * Gets the hold requirement with multicycle paths.
     * @param launchEdge Launch clock edge id.
     * @param captureEdge Capture clock edge id.
     * @param setupMult The setup multiplier (1 for none).
     * @param setupStart Whether the setup multiplier counts launch periods (-start).
     * @param holdMult The hold multiplier (0 for none).
     * @param holdEnd Whether the hold multiplier counts capture periods (-end).
     * @return The requirement in ps.
     */
    public double getHold(int launchEdge, int captureEdge, int setupMult, boolean setupStart, int holdMult,
                          boolean holdEnd) {
        double h = getHold(launchEdge, captureEdge);
        if (setupMult != 1) {
            h += (setupMult - 1) * period(setupStart ? launchEdge : captureEdge);
        }
        if (holdMult != 0) {
            h -= holdMult * period(holdEnd ? captureEdge : launchEdge);
        }
        return h;
    }

    private double period(int edge) {
        return clocks.get(edge >> 1).getPeriod();
    }

    private double[] base(int le, int ce) {
        int idx = le * 2 * numClocks + ce;
        double[] r = baseCache[idx];
        if (r == null) {
            r = compute(clocks.get(le >> 1), (le & 1) != 0, clocks.get(ce >> 1), (ce & 1) != 0);
            baseCache[idx] = r;
        }
        return r;
    }

    /** Sorted edge times within the first period of a clock, of one sense. */
    private static double[] edges(Clock c, boolean falling) {
        double[] w = c.waveform();
        double p = c.getPeriod();
        List<Double> l = new ArrayList<>();
        for (int i = falling ? 1 : 0; i < w.length; i += 2) {
            double t = w[i] % p;
            if (t < 0) t += p;
            if (p - t < EPS) t = 0;
            l.add(t);
        }
        if (l.isEmpty()) {
            l.add(0.0);
        }
        Collections.sort(l);
        double[] out = new double[l.size()];
        for (int i = 0; i < out.length; i++) out[i] = l.get(i);
        return out;
    }

    /**
     * Computes the {setup, hold} requirements of a launch and a capture clock edge (see the class
     * description).
     */
    static double[] compute(Clock launch, boolean launchFall, Clock capture, boolean captureFall) {
        double pl = launch.getPeriod();
        double pc = capture.getPeriod();
        double[] el = edges(launch, launchFall);
        double[] ec = edges(capture, captureFall);
        int n = MAX_CYCLES;
        for (int k = 1; k <= MAX_CYCLES; k++) {
            double ratio = k * pl / pc;
            if (Math.abs(ratio - Math.rint(ratio)) * pc <= Math.max(EPS, 1e-9 * k * pl)) {
                n = k;
                break;
            }
        }
        double setup = Double.POSITIVE_INFINITY;
        double hold = Double.NEGATIVE_INFINITY;
        for (int k = 0; k < n; k++) {
            for (double e : el) {
                double tl = e + k * pl;
                // First capture edge strictly after tl, and the last one at or before tl
                double next = Double.POSITIVE_INFINITY;
                double prev = Double.NEGATIVE_INFINITY;
                for (double c : ec) {
                    double j = Math.floor((tl - c) / pc);
                    double atOrBefore = c + j * pc;
                    if (atOrBefore > tl + EPS) {
                        atOrBefore -= pc;
                    } else if (atOrBefore + pc <= tl + EPS) {
                        atOrBefore += pc;
                    }
                    double after = atOrBefore + pc;
                    prev = Math.max(prev, atOrBefore);
                    next = Math.min(next, after);
                }
                setup = Math.min(setup, next - tl);
                hold = Math.max(hold, prev - tl);
            }
        }
        return new double[]{round(setup), round(hold)};
    }

    /**
     * Rounds to 0.001 ps if that only removes the noise of floating-point edge arithmetic, so that
     * exact values (e.g. the period of a clock relative to itself) are kept as they are.
     */
    private static double round(double ps) {
        double r = Math.rint(ps * 1000) / 1000;
        return Math.abs(r - ps) < 1e-6 ? r : ps;
    }
}

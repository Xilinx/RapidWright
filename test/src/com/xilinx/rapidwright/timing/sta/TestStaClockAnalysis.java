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

package com.xilinx.rapidwright.timing.sta;

import java.util.Arrays;
import java.util.BitSet;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ForkJoinPool;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import com.xilinx.rapidwright.timing.clock.Clock;
import com.xilinx.rapidwright.timing.clock.ClockModel;
import com.xilinx.rapidwright.timing.clock.ClockModelBuilder;
import com.xilinx.rapidwright.timing.clock.ClockRelations;
import com.xilinx.rapidwright.timing.clock.UncertaintyModel;
import com.xilinx.rapidwright.timing.family.Corner;

public class TestStaClockAnalysis {

    // ------------------------------------------------------------------------------------------
    // Single clock: the reference propagation's times and criticalities
    // ------------------------------------------------------------------------------------------

    @Test
    public void testSingleClockMatchesReference() {
        // (0 and -1: no requirement, the required time being the maximum arrival time)
        for (float requirement : new float[]{400f, 1234.5678f, 3000f, 50000f, 0f, -1f}) {
            StaGraph g = TestStaGraph.randomGraph(11, 20_000, 400, 4, new StaGraphAssembler());
            StaClockAnalysis an = new StaClockAnalysis(StaClockBinding.singleClock(g, requirement),
                    ForkJoinPool.commonPool());
            Assertions.assertEquals(1, an.getNumTags());
            Random r = new Random(3);
            for (int round = 0; round < 4; round++) {
                an.computeCriticality(0.99f, 3);
                float[][] ref = TestStaSingleClock.referenceTimes(g, requirement);
                for (int c = 0; c < g.getNumConnections(); c++) {
                    Assertions.assertEquals(Float.floatToIntBits(TestStaSingleClock.referenceCriticality(g, ref, c,
                            0.99f, 3)), Float.floatToIntBits(an.getCriticality(c)), "connection " + c + " T "
                            + requirement);
                }
                // The normalization: max(requirement, max arrival)
                Assertions.assertEquals(ref[2][0], an.getGroupNormalization(0));
                if (requirement <= 0 && round == 0) {
                    // A virtual requirement: some connection is critical
                    float max = 0;
                    for (int c = 0; c < g.getNumConnections(); c++) {
                        max = Math.max(max, an.getCriticality(c));
                    }
                    Assertions.assertTrue(max > 0.5f, "max criticality " + max);
                }
                for (int i = 0; i < 300; i++) {
                    int c = r.nextInt(g.getNumConnections());
                    float d = r.nextInt(800) + r.nextFloat();
                    an.setConnectionRouteDelay(c, d, d * 0.7f);
                }
            }
            // The later rounds were incremental
            Assertions.assertEquals(1, an.getNumFullPasses());
            Assertions.assertEquals(3, an.getNumIncrementalPasses());
        }
    }

    // ------------------------------------------------------------------------------------------
    // Multi-clock hand-built graph
    // ------------------------------------------------------------------------------------------

    /** Delays per corner: SLOW_MAX, SLOW_MIN, FAST_MIN, FAST_MAX */
    private static float[] d(float smax, float smin, float fmin, float fmax) {
        return new float[]{smax, smin, fmin, fmax};
    }

    /**
     * s1 (clock A, 10 ns) and s2 (clock B, A divided by 2) -> m -> e1 (A), e2 (B); s1 -> e2 through
     * connection 0. Clock tree: root, then one leaf per clock pin.
     */
    private static final class TwoClocks {
        final int[] id = new int[5];
        final StaGraph g;
        final List<Clock> clocks;
        final ClockModel model;
        final UncertaintyModel unc;
        ClockRelations rel;

        TwoClocks() {
            StaGraphAssembler asm = new StaGraphAssembler();
            int[] p = TestStaGraph.addPins(asm, 5);
            int c = asm.addConnection(null, 400, 350);
            TestStaGraph.addEdge(asm, p[0], p[2], 300);
            TestStaGraph.addEdge(asm, p[1], p[2], 200);
            TestStaGraph.addEdge(asm, p[2], p[3], 400);
            TestStaGraph.addEdge(asm, p[2], p[4], 100);
            asm.addEdge(p[0], p[4], StaGraph.NET, 100, 100, 0, c);
            asm.markEndpoint(p[3]);
            asm.markEndpoint(p[4]);
            g = asm.build();
            for (int i = 0; i < 5; i++) {
                id[i] = asm.getFinalPinId(p[i]);
            }
            Clock a = Clock.createPrimary(0, "A", 10000, new double[]{0, 5000}, "clkA");
            Clock b = Clock.createGenerated(1, "B", a, 20000, new double[]{0, 10000}, 0);
            clocks = Arrays.asList(a, b);
            rel = new ClockRelations(clocks);
            ClockModelBuilder t = new ClockModelBuilder();
            int root = t.addNode(ClockModelBuilder.NO_PARENT, d(100, 90, 50, 55));
            int l0 = t.addNode(root, d(20, 18, 10, 11));
            int l1 = t.addNode(root, d(40, 36, 20, 22));
            model = t.build(new int[]{l0, l1});
            unc = UncertaintyModel.none(2);
            unc.set(0, 0, 10, 5);
            unc.set(1, 0, 20, 6);
            unc.set(0, 1, 30, 7);
        }

        StaClockBinding binding() {
            StaClockBinding b = new StaClockBinding(g, clocks, rel, unc, model, new int[][]{{0}, {2}});
            b.setLaunchClockPin(id[0], 0);
            b.setLaunchClockPin(id[1], 1);
            b.setCaptureClockPin(id[3], 0, 50, 15);
            b.setCaptureClockPin(id[4], 1, 30, 25);
            return b;
        }
    }

    /**
     * Pins without fanout that are not endpoints constrain nothing, even with clocks above 32.8 ns:
     * s1 (clock A, 40 ns) -> m through connection 0, s2 (clock B, 80 ns) -> m, m -> e1 (A), e2 (B),
     * and m -> x, a pin without fanout. The fan-in cone of x is that of the endpoints: it keeps their
     * group and criticality.
     */
    @Test
    public void testDanglingPinsWithLongClocks() {
        StaGraphAssembler asm = new StaGraphAssembler();
        int[] p = TestStaGraph.addPins(asm, 6);
        int c = asm.addConnection(null, 400, 350);
        asm.addEdge(p[0], p[2], StaGraph.NET, 100, 100, 0, c);
        TestStaGraph.addEdge(asm, p[1], p[2], 200);
        TestStaGraph.addEdge(asm, p[2], p[3], 400);
        TestStaGraph.addEdge(asm, p[2], p[4], 100);
        TestStaGraph.addEdge(asm, p[2], p[5], 50);
        asm.markEndpoint(p[3]);
        asm.markEndpoint(p[4]);
        StaGraph g = asm.build();
        int[] id = new int[6];
        for (int i = 0; i < 6; i++) {
            id[i] = asm.getFinalPinId(p[i]);
        }
        Clock a = Clock.createPrimary(0, "A", 40000, new double[]{0, 20000}, "clkA");
        Clock b = Clock.createGenerated(1, "B", a, 80000, new double[]{0, 40000}, 0);
        List<Clock> clocks = Arrays.asList(a, b);
        ClockModelBuilder t = new ClockModelBuilder();
        int root = t.addNode(ClockModelBuilder.NO_PARENT, d(100, 90, 50, 55));
        int l0 = t.addNode(root, d(20, 18, 10, 11));
        int l1 = t.addNode(root, d(40, 36, 20, 22));
        StaClockBinding binding = new StaClockBinding(g, clocks, new ClockRelations(clocks), UncertaintyModel.none(2),
                t.build(new int[]{l0, l1}), new int[][]{{0}, {2}});
        binding.setLaunchClockPin(id[0], 0);
        binding.setLaunchClockPin(id[1], 1);
        binding.setCaptureClockPin(id[3], 0, 50, 15);
        binding.setCaptureClockPin(id[4], 1, 30, 25);
        StaClockAnalysis an = new StaClockAnalysis(binding, ForkJoinPool.commonPool());
        an.computeCriticality(0.99f, 1);
        // The connection s1 -> m reaches e1 with about 39.4 ns of slack out of a 40 ns relation
        float crit = an.getCriticality(0);
        Assertions.assertTrue(crit > 0, "criticality " + crit);
        StaClockAnalysis.Check e1 = check(an, id[3], 0, true);
        Assertions.assertTrue(crit >= 0.99f * (1 - e1.slack / an.getGroupNormalization(0)) - 1e-6f, "criticality " + crit);

        // The same with a single 40 ns clock
        StaClockAnalysis single = new StaClockAnalysis(StaClockBinding.singleClock(g, 40000), ForkJoinPool.commonPool());
        single.computeCriticality(0.99f, 1);
        Assertions.assertEquals(StaClockAnalysis.DANGLING_REQUIRED, single.getShiftedRequired(id[5], 0));
        // m's required time is that of e1 (40000 - 400), not the dangling pin's
        Assertions.assertEquals(39600f, single.getShiftedRequired(id[2], 0));
        // The connection's edge: 100 fixed + 400 route
        Assertions.assertEquals(0.99f * (1 - (39600f - 0 - 500) / 40000f), single.getCriticality(0), 1e-6f);
    }

    /**
     * Hold per group: e1 is captured by both clocks (its clock pin carries A and B), so each tag slot of
     * e1 has a check per capture clock, in different groups; each group gets the hold slack of its own
     * checks, not the slot's minimum.
     */
    @Test
    public void testHoldPerGroup() {
        TwoClocks tc = new TwoClocks();
        ClockModelBuilder t = new ClockModelBuilder();
        int root = t.addNode(ClockModelBuilder.NO_PARENT, d(100, 90, 50, 55));
        int l0 = t.addNode(root, d(20, 18, 10, 11));
        int l1 = t.addNode(root, d(40, 36, 20, 22));
        int l2 = t.addNode(root, d(30, 27, 15, 16));
        StaClockBinding b = new StaClockBinding(tc.g, tc.clocks, tc.rel, tc.unc, t.build(new int[]{l0, l1, l2}),
                new int[][]{{0}, {2}, {0, 2}});
        b.setLaunchClockPin(tc.id[0], 0);
        b.setLaunchClockPin(tc.id[1], 1);
        b.setCaptureClockPin(tc.id[3], 2, 50, 15);
        b.setCaptureClockPin(tc.id[4], 1, 30, 25);
        StaClockAnalysis an = new StaClockAnalysis(b, ForkJoinPool.commonPool());
        an.updateHold();
        Map<Long, Float> worst = new HashMap<>();
        for (int pin : new int[]{tc.id[3], tc.id[4]}) {
            for (StaClockAnalysis.Check c : an.getChecks(pin, false)) {
                worst.merge(((long) (c.launchEdge >> 1) << 32) | (c.captureEdge >> 1), c.slack, Math::min);
            }
        }
        Set<Float> distinct = new HashSet<>();
        for (StaClockAnalysis.GroupSummary s : an.getGroupSummaries()) {
            Float w = worst.get(((long) s.launchClock << 32) | s.captureClock);
            Assertions.assertNotNull(w);
            Assertions.assertEquals(w, s.whs, 1e-3f, s.launchClock + "->" + s.captureClock);
            distinct.add(s.whs);
        }
        // The groups of e1's slots do not all share one (minimum) hold slack
        Assertions.assertTrue(distinct.size() > 2, distinct.toString());
    }

    /** Hold queries refresh placeholder min delays through the refresher, or fail clearly without one */
    @Test
    public void testHoldDelayRefresh() {
        TwoClocks tc = new TwoClocks();
        StaClockAnalysis an = new StaClockAnalysis(tc.binding(), ForkJoinPool.commonPool());
        float before = an.getEndpointHoldSlacks()[0];
        // A placeholder min delay (the max one), marked stale
        an.setConnectionRouteDelay(0, 400, 400);
        an.markHoldDelaysStale();
        Assertions.assertThrows(IllegalStateException.class, an::getEndpointHoldSlacks);
        an.setHoldDelayRefresher(() -> an.setConnectionRouteDelay(0, 400, 350));
        an.markHoldDelaysStale();
        Assertions.assertEquals(before, an.getEndpointHoldSlacks()[0]);
        // The refreshed min delays also feed the group summaries
        an.setConnectionRouteDelay(0, 400, 400);
        an.markHoldDelaysStale();
        float whs = Float.POSITIVE_INFINITY;
        for (StaClockAnalysis.GroupSummary s : an.getGroupSummaries()) {
            whs = Math.min(whs, s.whs);
        }
        float[] slacks = an.getEndpointHoldSlacks();
        Assertions.assertEquals(Math.min(slacks[0], slacks[1]), whs, 1e-3f);
    }

    private static StaClockAnalysis.Check check(StaClockAnalysis an, int pin, int launchClock, boolean setup) {
        for (StaClockAnalysis.Check c : an.getChecks(pin, setup)) {
            if (c.launchEdge >> 1 == launchClock) {
                return c;
            }
        }
        return null;
    }

    @Test
    public void testMultiClockSetup() {
        TwoClocks tc = new TwoClocks();
        StaClockAnalysis an = new StaClockAnalysis(tc.binding(), ForkJoinPool.commonPool());
        an.update();
        Assertions.assertEquals(2, an.getNumTags());
        Assertions.assertEquals(2, an.getNumPinTags(tc.id[2]));
        // e1, launched by A: arrival 120 + 300 + 400; required 10000 + 108 - 50 - 10 + CRPR 12
        StaClockAnalysis.Check aa = check(an, tc.id[3], 0, true);
        Assertions.assertEquals(820f, aa.arrival);
        Assertions.assertEquals(12f, aa.crpr);
        Assertions.assertEquals(10060f, aa.required);
        Assertions.assertEquals(9240f, aa.slack);
        Assertions.assertEquals(10000f, aa.relation);
        Assertions.assertEquals(108f - 120f + 12f, aa.getSkew());
        // e1, launched by B: 140 + 200 + 400; 10000 + 108 - 50 - 20 + CRPR at the root 10
        StaClockAnalysis.Check ba = check(an, tc.id[3], 1, true);
        Assertions.assertEquals(740f, ba.arrival);
        Assertions.assertEquals(10048f, ba.required);
        // e2, launched by A through the connection: max(120 + 300 + 100, 120 + 100 + 400)
        StaClockAnalysis.Check ab = check(an, tc.id[4], 0, true);
        Assertions.assertEquals(620f, ab.arrival);
        Assertions.assertEquals(10000f + 126f - 30f - 30f + 10f, ab.required);
        StaClockAnalysis.Check bb = check(an, tc.id[4], 1, true);
        Assertions.assertEquals(20000f, bb.relation);
        Assertions.assertEquals(20000f + 126f - 30f + 14f, bb.required);
        Assertions.assertEquals(aa.slack, an.getWorstSetupCheck(tc.id[3]).slack);

        // The path of the A tag to e2 goes through the connection
        int tagA = aa.tag;
        int[] path = an.getPath(tc.id[4], tagA);
        Assertions.assertEquals(1, path.length);
        Assertions.assertEquals(0, tc.g.getEdgeConnection(path[0]));

        // Groups: no negative slack, D_g = T_g
        List<StaClockAnalysis.GroupSummary> groups = an.getGroupSummaries();
        Assertions.assertEquals(4, groups.size());
        for (StaClockAnalysis.GroupSummary s : groups) {
            Assertions.assertEquals(1, s.endpoints);
            Assertions.assertEquals(0f, s.tns);
            Assertions.assertEquals(s.launchClock == 1 && s.captureClock == 1 ? 20000f : 10000f, s.requirement);
        }

        // Criticality of the connection: its A tag, slack 9456 in group (A, B)
        an.computeCriticality(1f, 1);
        Assertions.assertEquals(1f - (10076f - 620f) / 10000f, an.getCriticality(0), 1e-6);

        // Make the connection slow: group (A, B) fails by 1000 ps, its shift normalizes it to 1
        an.setConnectionRouteDelay(0, 10856f, 350f);
        an.computeCriticality(1f, 1);
        Assertions.assertEquals(-1000f, an.getWorstSetupCheck(tc.id[4]).slack);
        Assertions.assertEquals(11000f, an.getGroupNormalization(1));
        Assertions.assertEquals(1f, an.getCriticality(0), 1e-6);
    }

    /**
     * A connection feeding two capture groups of different requirements takes the larger of their
     * normalized criticalities: s (clock A, 10 ns) -> m through connection 0, m -> e1 (A, slack 7 ns of
     * 10 ns: criticality 0.3) and m -> e2 (C, A multiplied by 2: relation 5 ns, slack 4 ns: criticality
     * 0.2). The smallest shifted required time is e2's, whose criticality is the smaller one.
     */
    @Test
    public void testCriticalityOfGroupsWithDifferentRequirements() {
        StaGraphAssembler asm = new StaGraphAssembler();
        int[] p = TestStaGraph.addPins(asm, 4);
        int c = asm.addConnection(null, 500, 500);
        asm.addEdge(p[0], p[1], StaGraph.NET, 0, 0, 0, c);
        TestStaGraph.addEdge(asm, p[1], p[2], 2500);
        TestStaGraph.addEdge(asm, p[1], p[3], 500);
        asm.markEndpoint(p[2]);
        asm.markEndpoint(p[3]);
        StaGraph g = asm.build();
        int s = asm.getFinalPinId(p[0]);
        int e1 = asm.getFinalPinId(p[2]);
        int e2 = asm.getFinalPinId(p[3]);
        Clock a = Clock.createPrimary(0, "A", 10000, new double[]{0, 5000}, "clkA");
        Clock cc = Clock.createGenerated(1, "C", a, 5000, new double[]{0, 2500}, 0);
        List<Clock> clocks = Arrays.asList(a, cc);
        StaClockBinding b = new StaClockBinding(g, clocks, new ClockRelations(clocks), UncertaintyModel.none(2),
                ClockModel.ideal(2), new int[][]{{0}, {2}});
        b.setLaunchClockPin(s, 0);
        b.setCaptureClockPin(e1, 0, 0, 0);
        b.setCaptureClockPin(e2, 1, 0, 0);
        StaClockAnalysis an = new StaClockAnalysis(b, ForkJoinPool.commonPool());
        an.computeCriticality(1f, 1);
        Assertions.assertEquals(7000f, check(an, e1, 0, true).slack);
        Assertions.assertEquals(4000f, check(an, e2, 0, true).slack);
        Assertions.assertEquals(0.3f, an.getCriticality(0), 1e-6);
    }

    /**
     * The launch insertion of a clock that reaches its clock pin by its own path (e.g. through another
     * input of a clock mux) is that of its path, as for the capture: s is clocked by A and B through one
     * clock pin, A's path with an insertion of 100 ps, B's with 300 ps.
     */
    @Test
    public void testLaunchInsertionOfClockPath() {
        StaGraphAssembler asm = new StaGraphAssembler();
        int[] p = TestStaGraph.addPins(asm, 2);
        TestStaGraph.addEdge(asm, p[0], p[1], 1000);
        asm.markEndpoint(p[1]);
        StaGraph g = asm.build();
        int s = asm.getFinalPinId(p[0]);
        int e = asm.getFinalPinId(p[1]);
        Clock a = Clock.createPrimary(0, "A", 10000, new double[]{0, 5000}, "clkA");
        Clock bc = Clock.createGenerated(1, "B", a, 20000, new double[]{0, 10000}, 0);
        List<Clock> clocks = Arrays.asList(a, bc);
        ClockModelBuilder t = new ClockModelBuilder();
        int root = t.addNode(ClockModelBuilder.NO_PARENT, d(0, 0, 0, 0));
        int viaA = t.addNode(root, d(100, 100, 100, 100));
        int viaB = t.addNode(root, d(300, 300, 300, 300));
        int cap = t.addNode(root, d(0, 0, 0, 0));
        ClockModel model = t.build(new int[]{viaA, cap});
        ClockModelBuilder.setClockPathNode(model, 0, 1, viaB);
        StaClockBinding b = new StaClockBinding(g, clocks, new ClockRelations(clocks), UncertaintyModel.none(2),
                model, new int[][]{{0, 2}, {2}});
        b.setLaunchClockPin(s, 0);
        b.setCaptureClockPin(e, 1, 0, 0);
        StaClockAnalysis an = new StaClockAnalysis(b, ForkJoinPool.commonPool());
        an.update();
        Assertions.assertEquals(1100f, check(an, e, 0, true).arrival);
        Assertions.assertEquals(1300f, check(an, e, 1, true).arrival);
    }

    @Test
    public void testFalsePathsAndMulticycle() {
        TwoClocks tc = new TwoClocks();
        // Clock pair false (e.g. clock groups): B -> A
        tc.rel.setFalse(1, 0, true, true, ClockRelations.REASON_GROUPS);
        StaClockBinding b = tc.binding();
        // set_multicycle_path 2 -setup -to e2; set_false_path -from s1 -to e1
        b.addException(new StaClockBinding.PathException(false, true, false, 2, null, null, null, null,
                new HashSet<>(Collections.singletonList(tc.id[4])), 0));
        b.addException(new StaClockBinding.PathException(true, true, true, 1, null, null,
                new HashSet<>(Collections.singletonList(tc.id[0])), null,
                new HashSet<>(Collections.singletonList(tc.id[3])), 1));
        StaClockAnalysis an = new StaClockAnalysis(b, ForkJoinPool.commonPool());
        an.update();
        an.updateHold();
        // e1: launched by A from s1 (false path), and by B (false pair): no check at all
        Assertions.assertTrue(an.getChecks(tc.id[3], true).isEmpty());
        Assertions.assertNull(an.getWorstSetupCheck(tc.id[3]));
        // e2: setup moved by one capture period (B, 20 ns), default hold too
        StaClockAnalysis.Check ab = check(an, tc.id[4], 0, true);
        Assertions.assertEquals(30000f, ab.relation);
        StaClockAnalysis.Check bb = check(an, tc.id[4], 1, true);
        Assertions.assertEquals(40000f, bb.relation);
        StaClockAnalysis.Check bbHold = check(an, tc.id[4], 1, false);
        Assertions.assertEquals(20000f, bbHold.relation);

        // A clock-only multicycle: -from A, 3 periods of B (start: launch periods of A)
        TwoClocks tc2 = new TwoClocks();
        StaClockBinding b2 = tc2.binding();
        BitSet fromA = new BitSet();
        fromA.set(0);
        b2.addException(new StaClockBinding.PathException(false, true, false, 3, Boolean.TRUE, fromA, null, null,
                null, 0));
        StaClockAnalysis an2 = new StaClockAnalysis(b2, ForkJoinPool.commonPool());
        an2.update();
        Assertions.assertEquals(10000f + 2 * 10000f, check(an2, tc2.id[3], 0, true).relation);
        Assertions.assertEquals(10000f + 2 * 10000f, check(an2, tc2.id[4], 0, true).relation);
        Assertions.assertEquals(20000f, check(an2, tc2.id[4], 1, true).relation);
    }

    @Test
    public void testUnrelatedClocksNotTimed() {
        // Unrelated clocks are false paths (unlike Vivado), and the report says so
        TwoClocks tc = new TwoClocks();
        tc.rel.setUnrelated(0, 1, true);
        tc.rel.setUnrelated(1, 0, true);
        StaClockAnalysis an = new StaClockAnalysis(tc.binding(), ForkJoinPool.commonPool());
        an.update();
        for (StaClockAnalysis.Check c : an.getChecks(tc.id[4], true)) {
            Assertions.assertEquals(c.launchEdge >> 1, c.captureEdge >> 1);
        }
        String unrelated = "Paths between unrelated clocks (no common primary clock) are false paths, not timed";
        Assertions.assertTrue(StaClockReport.format(an).contains(unrelated));
        // Timed as Vivado does: the A -> B check is back, and the note is gone
        TwoClocks tv = new TwoClocks();
        tv.rel.setUnrelated(0, 1, true);
        tv.rel.setUnrelatedFalse(false);
        StaClockAnalysis av = new StaClockAnalysis(tv.binding(), ForkJoinPool.commonPool());
        av.update();
        Assertions.assertNotNull(check(av, tv.id[4], 0, true));
        Assertions.assertFalse(StaClockReport.format(av).contains(unrelated));
    }

    @Test
    public void testHold() {
        TwoClocks tc = new TwoClocks();
        StaClockAnalysis an = new StaClockAnalysis(tc.binding(), ForkJoinPool.commonPool());
        an.update();
        Assertions.assertTrue(an.updateHold());
        Assertions.assertFalse(an.updateHold());
        // e1 from A: early arrival 60 + 300 + 400; required 0 + 66 (late capture) + 15 + 5 - CRPR (66 - 60)
        StaClockAnalysis.Check aa = check(an, tc.id[3], 0, false);
        Assertions.assertEquals(760f, aa.arrival);
        Assertions.assertEquals(6f, aa.crpr);
        Assertions.assertEquals(80f, aa.required);
        Assertions.assertEquals(680f, aa.slack);
        // e2 from A: min of 60 + 300 + 100 and 60 + 100 + 350 (min route delay)
        StaClockAnalysis.Check ab = check(an, tc.id[4], 0, false);
        Assertions.assertEquals(460f, ab.arrival);
        Assertions.assertEquals(0f + 77f + 25f + 7f - 5f, ab.required);
        float[] hs = an.getEndpointHoldSlacks();
        Assertions.assertEquals(2, hs.length);
        // A faster min route makes the hold check fail
        an.setConnectionRouteDelay(0, 400f, -400f);
        Assertions.assertTrue(an.updateHold());
        Assertions.assertTrue(an.getWorstHoldCheck(tc.id[4]).slack < 0);
        for (StaClockAnalysis.GroupSummary s : an.getGroupSummaries()) {
            if (s.launchClock == 0 && s.captureClock == 1) {
                Assertions.assertEquals(1, s.holdFailing);
                Assertions.assertTrue(s.whs < 0);
            }
        }
    }

    @Test
    public void testRefreshClockTiming() {
        TwoClocks tc = new TwoClocks();
        StaClockBinding b = tc.binding();
        StaClockAnalysis an = new StaClockAnalysis(b, ForkJoinPool.commonPool());
        an.update();
        float before = check(an, tc.id[3], 0, true).required;
        // Re-routed clocks: every capture insertion grows by 50 ps, the launch ones too
        ClockModelBuilder t = new ClockModelBuilder();
        int root = t.addNode(ClockModelBuilder.NO_PARENT, d(150, 140, 100, 105));
        int l0 = t.addNode(root, d(20, 18, 10, 11));
        int l1 = t.addNode(root, d(40, 36, 20, 22));
        b.setClockModel(t.build(new int[]{l0, l1}));
        an.refreshClockTiming();
        Assertions.assertTrue(an.update());
        StaClockAnalysis.Check aa = check(an, tc.id[3], 0, true);
        Assertions.assertEquals(before + 50f, aa.required);
        Assertions.assertEquals(870f, aa.arrival);
        Assertions.assertEquals(1, an.getNumFullPasses() - 1);
    }

    // ------------------------------------------------------------------------------------------
    // Launch candidates of distinct SLRs
    // ------------------------------------------------------------------------------------------

    /**
     * s1 (clock pin 0, SLR 0) and s2 (clock pin 1, SLR 1) -> e (captured by clock pin 0): s1 arrives
     * later, but s2 shares less of the clock tree with the capture pin (smaller CRPR credit).
     */
    private static StaClockAnalysis launchCandidates(boolean enabled, boolean refine, int[] pins) {
        StaGraphAssembler asm = new StaGraphAssembler();
        int[] p = TestStaGraph.addPins(asm, 3);
        asm.addEdge(p[0], p[2], StaGraph.CELL_COMB, 500, 500, 0f, StaGraph.NO_CONN);
        asm.addEdge(p[1], p[2], StaGraph.CELL_COMB, 490, 505, 0f, StaGraph.NO_CONN);
        asm.markEndpoint(p[2]);
        StaGraph g = asm.build();
        for (int i = 0; i < 3; i++) {
            pins[i] = asm.getFinalPinId(p[i]);
        }
        Clock a = Clock.createPrimary(0, "A", 10000, new double[]{0, 5000}, "clkA");
        List<Clock> clocks = Collections.singletonList(a);
        ClockModelBuilder t = new ClockModelBuilder();
        int root = t.addNode(ClockModelBuilder.NO_PARENT, d(100, 90, 50, 55));
        int spine = t.addNode(root, d(200, 180, 100, 110));
        int la = t.addNode(t.addNode(spine, d(300, 270, 150, 165)), d(10, 9, 5, 6));
        int lb = t.addNode(t.addNode(spine, d(300, 270, 150, 165)), d(10, 9, 5, 6));
        ClockModel model = t.build(new int[]{la, lb}, new int[]{0, 1});
        StaClockBinding b = new StaClockBinding(g, clocks, new ClockRelations(clocks), UncertaintyModel.none(1), model,
                new int[][]{{0}, {0}});
        b.setLaunchClockPin(pins[0], 0);
        b.setLaunchClockPin(pins[1], 1);
        b.setCaptureClockPin(pins[2], 0, 50, 15);
        StaClockAnalysis an = new StaClockAnalysis(b, ForkJoinPool.commonPool(), enabled);
        an.refineHold = refine;
        an.update();
        an.updateHold();
        return an;
    }

    /** The search of the hold refinement keeps pin ids exact, also above 2^24 pins (where a float rounds them) */
    @Test
    public void testHoldSearchPinIds() {
        int pin = (1 << 24) + 1;
        Assertions.assertNotEquals(pin, (int) (float) pin);
        Assertions.assertEquals(pin, new StaClockAnalysis.HoldSearchEntry(1f, pin, 0f).pin);
    }

    @Test
    public void testLaunchCandidates() {
        int[] pins = new int[3];
        StaClockAnalysis one = launchCandidates(false, false, pins);
        StaClockAnalysis two = launchCandidates(true, false, pins);
        // Setup: the arg-max s1 (1110 ps, CRPR 610 - 549 = 61) vs s2 (1100 ps, CRPR at the spine 300 - 270 = 30)
        StaClockAnalysis.Check s1 = one.getWorstSetupCheck(pins[2]);
        Assertions.assertFalse(s1.alternateLaunch);
        Assertions.assertEquals(0, s1.launchClockPin);
        Assertions.assertEquals(1110f, s1.arrival);
        Assertions.assertEquals(61f, s1.crpr);
        StaClockAnalysis.Check s2 = two.getWorstSetupCheck(pins[2]);
        Assertions.assertTrue(s2.alternateLaunch);
        Assertions.assertEquals(1, s2.launchClockPin);
        Assertions.assertEquals(1100f, s2.arrival);
        Assertions.assertEquals(30f, s2.crpr);
        Assertions.assertEquals(s1.slack - 21f, s2.slack, 1e-3);
        Assertions.assertEquals(s2.required - s2.arrival, s2.slack, 1e-3);
        Assertions.assertEquals(s2.slack, two.getEndpointSetupSlacks()[0], 1e-3);
        // The arrival times and the arg-max are unchanged; the path is the alternate's
        Assertions.assertEquals(one.getArrival(pins[2], 0), two.getArrival(pins[2], 0));
        Assertions.assertEquals(0, two.getLaunchClockPin(pins[2], 0));
        int[] path = two.getPath(s2);
        Assertions.assertEquals(1, path.length);
        Assertions.assertEquals(pins[1], two.getGraph().getEdgeFrom(path[0]));
        // Hold: the arg-min s1 (805 ps, CRPR 336 - 305 = 31) vs s2 (810 ps, CRPR at the spine 165 - 150 = 15)
        StaClockAnalysis.Check h1 = one.getWorstHoldCheck(pins[2]);
        Assertions.assertEquals(805f, h1.arrival);
        Assertions.assertEquals(31f, h1.crpr);
        StaClockAnalysis.Check h2 = two.getWorstHoldCheck(pins[2]);
        Assertions.assertTrue(h2.alternateLaunch);
        Assertions.assertEquals(810f, h2.arrival);
        Assertions.assertEquals(15f, h2.crpr);
        Assertions.assertEquals(h1.slack - 11f, h2.slack, 1e-3);
        Assertions.assertEquals(h2.slack, two.getEndpointHoldSlacks()[0], 1e-3);
    }

    @Test
    public void testHoldRefinement() {
        int[] pins = new int[3];
        StaClockAnalysis one = launchCandidates(false, false, pins);
        StaClockAnalysis refined = launchCandidates(false, true, pins);
        // Without the candidates, the refinement (slack 485 < 500) finds s2 (810 ps, credit 15) behind s1 (805, 31)
        StaClockAnalysis.Check h1 = one.getWorstHoldCheck(pins[2]);
        Assertions.assertEquals(0, h1.launchClockPin);
        Assertions.assertEquals(485f, h1.slack, 1e-3);
        StaClockAnalysis.Check h2 = refined.getWorstHoldCheck(pins[2]);
        Assertions.assertFalse(h2.alternateLaunch);
        Assertions.assertEquals(1, h2.launchClockPin);
        Assertions.assertEquals(810f, h2.arrival);
        Assertions.assertEquals(15f, h2.crpr);
        Assertions.assertEquals(h1.slack - 11f, h2.slack, 1e-3);
        Assertions.assertEquals(h2.required, h2.arrival - h2.slack, 1e-3);
        Assertions.assertEquals(h2.slack, refined.getEndpointHoldSlacks()[0], 1e-3);
        // Setup is not refined
        Assertions.assertEquals(one.getWorstSetupCheck(pins[2]).slack, refined.getWorstSetupCheck(pins[2]).slack);
    }

    /**
     * A fall-triggered clock pin (an IDDRE1's inverted clock CB) launches at the falling edge of its clock
     * edges: with CB on the falling edge of a 10 ns clock, at its rising edge, a full cycle before the capture.
     */
    @Test
    public void testFallTriggeredLaunch() {
        float[] slacks = new float[2];
        for (int k = 0; k < 2; k++) {
            StaGraphAssembler asm = new StaGraphAssembler();
            int[] p = TestStaGraph.addPins(asm, 2);
            asm.addEdge(p[0], p[1], StaGraph.CELL_COMB, 500, 500, 0f, StaGraph.NO_CONN);
            asm.markEndpoint(p[1]);
            StaGraph g = asm.build();
            Clock a = Clock.createPrimary(0, "A", 10000, new double[]{0, 5000}, "clkA");
            List<Clock> clocks = Collections.singletonList(a);
            ClockModelBuilder t = new ClockModelBuilder();
            int root = t.addNode(ClockModelBuilder.NO_PARENT, d(0, 0, 0, 0));
            // Clock pin 0 (the launch) on the falling edge (edge id 1), clock pin 1 (the capture) on the rising
            StaClockBinding b = new StaClockBinding(g, clocks, new ClockRelations(clocks), UncertaintyModel.none(1),
                    t.build(new int[]{root, root}), new int[][]{{1}, {0}});
            b.setLaunchClockPin(asm.getFinalPinId(p[0]), 0);
            b.setCaptureClockPin(asm.getFinalPinId(p[1]), 1, 0, 0);
            b.setFallLaunch(0, k == 1);
            StaClockAnalysis an = new StaClockAnalysis(b);
            an.update();
            slacks[k] = an.getWorstSetupCheck(asm.getFinalPinId(p[1])).slack;
        }
        // Falling edge at 5 ns -> rising at 10 ns: 5 ns; fall-triggered: rising at 0 -> rising at 10 ns
        Assertions.assertEquals(4500f, slacks[0], 1e-3);
        Assertions.assertEquals(9500f, slacks[1], 1e-3);
        Assertions.assertTrue(StaClockBinding.isFallTriggeredClockPin("IDDRE1", "CB"));
        Assertions.assertFalse(StaClockBinding.isFallTriggeredClockPin("IDDRE1", "C"));
    }

    // ------------------------------------------------------------------------------------------
    // Slow-corner hold
    // ------------------------------------------------------------------------------------------

    @Test
    public void testSlowCornerHold() {
        // s1 -> e (fast min 300, slow min 150) and s2 -> e through connection c (route min 350 at both)
        StaGraphAssembler asm = new StaGraphAssembler();
        asm.enableSecondMinCorner();
        int[] p = TestStaGraph.addPins(asm, 3);
        int c = asm.addConnection(null, 400, 350);
        asm.addEdge(p[0], p[2], StaGraph.CELL_COMB, 500, 300, 150, 0f, StaGraph.NO_CONN);
        asm.addEdge(p[1], p[2], StaGraph.NET, 0, 0, 0, 0f, c);
        asm.markEndpoint(p[2]);
        StaGraph g = asm.build();
        Assertions.assertTrue(g.hasSecondMinCorner());
        int[] id = new int[3];
        for (int i = 0; i < 3; i++) {
            id[i] = asm.getFinalPinId(p[i]);
        }
        Clock a = Clock.createPrimary(0, "A", 10000, new double[]{0, 5000}, "clkA");
        List<Clock> clocks = Collections.singletonList(a);
        ClockModelBuilder t = new ClockModelBuilder();
        int root = t.addNode(ClockModelBuilder.NO_PARENT, d(100, 90, 50, 55));
        int la = t.addNode(root, d(20, 18, 10, 11));
        int lc = t.addNode(root, d(40, 36, 20, 22));
        StaClockBinding b = new StaClockBinding(g, clocks, new ClockRelations(clocks), UncertaintyModel.none(1),
                t.build(new int[]{la, lc}), new int[][]{{0}, {0}});
        b.setLaunchClockPin(id[0], 0);
        b.setLaunchClockPin(id[1], 0);
        b.setCaptureClockPin(id[2], 1, 50, 15);
        StaClockAnalysis an = new StaClockAnalysis(b, ForkJoinPool.commonPool());
        Assertions.assertTrue(an.hasSlowHold());
        an.update();
        Assertions.assertTrue(an.updateHold());
        Assertions.assertFalse(an.updateHold());
        List<StaClockAnalysis.Check> checks = an.getChecks(id[2], false);
        Assertions.assertEquals(2, checks.size());
        StaClockAnalysis.Check fast = checks.get(0);
        StaClockAnalysis.Check slow = checks.get(1);
        // Fast: 50 + 10 + 300 against 55 + 22 + 15 - (55 - 50)
        Assertions.assertEquals(Corner.FAST_MIN, fast.corner);
        Assertions.assertEquals(360f, fast.arrival);
        Assertions.assertEquals(87f, fast.required);
        Assertions.assertEquals(5f, fast.crpr);
        // Slow: 90 + 18 + 150 against 100 + 40 + 15 - (100 - 90)
        Assertions.assertEquals(Corner.SLOW_MIN, slow.corner);
        Assertions.assertEquals(108f, slow.launchInsertion);
        Assertions.assertEquals(140f, slow.captureInsertion);
        Assertions.assertEquals(10f, slow.crpr);
        Assertions.assertEquals(258f, slow.arrival);
        Assertions.assertEquals(145f, slow.required);
        Assertions.assertEquals(113f, slow.slack);
        // The worst over both corners
        Assertions.assertEquals(Corner.SLOW_MIN, an.getWorstHoldCheck(id[2]).corner);
        Assertions.assertEquals(113f, an.getEndpointHoldSlacks()[0]);
        // A second-corner route change only re-times the slow corner
        an.setConnectionRouteDelayMin2(c, 100f);
        Assertions.assertTrue(an.updateHold());
        StaClockAnalysis.Check w = an.getWorstHoldCheck(id[2]);
        Assertions.assertEquals(208f, w.arrival);
        Assertions.assertEquals(63f, w.slack);
        Assertions.assertEquals(273f, an.getChecks(id[2], false).get(0).slack);
    }

    // ------------------------------------------------------------------------------------------
    // Incremental == full
    // ------------------------------------------------------------------------------------------

    private static StaClockBinding randomBinding(StaGraph g, long seed) {
        return randomBinding(g, seed, null);
    }

    private static StaClockBinding randomBinding(StaGraph g, long seed, int[] clockPinSlrs) {
        Clock a = Clock.createPrimary(0, "A", 1000, new double[]{0, 500}, "src");
        Clock b = Clock.createGenerated(1, "B", a, 1500, new double[]{0, 750}, 0);
        Clock c = Clock.createGenerated(2, "C", a, 2000, new double[]{500, 1500}, 100);
        List<Clock> clocks = Arrays.asList(a, b, c);
        ClockRelations rel = new ClockRelations(clocks);
        rel.setFalse(2, 1, true, true, ClockRelations.REASON_GROUPS);
        ClockModelBuilder t = new ClockModelBuilder();
        int root = t.addNode(ClockModelBuilder.NO_PARENT, d(300, 270, 150, 165));
        int[] nodes = new int[3];
        for (int i = 0; i < 3; i++) {
            nodes[i] = t.addNode(root, d(10 * i, 9 * i, 5 * i, 6 * i));
        }
        UncertaintyModel unc = UncertaintyModel.none(3);
        unc.set(0, 1, 25, 10);
        StaClockBinding bd = new StaClockBinding(g, clocks, rel, unc,
                clockPinSlrs == null ? t.build(nodes) : t.build(nodes, clockPinSlrs),
                new int[][]{{0}, {2}, {0, 5}});
        Random r = new Random(seed);
        for (int p = 0; p < g.getNumPins(); p++) {
            if (g.isPinKind(p, StaGraph.PIN_START)) {
                bd.setLaunchClockPin(p, r.nextInt(3));
            }
            if (g.isPinKind(p, StaGraph.PIN_END)) {
                bd.setCaptureClockPin(p, r.nextInt(3), r.nextInt(40), r.nextInt(20));
            }
        }
        return bd;
    }

    @Test
    public void testIncrementalEqualsFull() {
        incrementalEqualsFull(null, false);
    }

    @Test
    public void testIncrementalEqualsFullWithLaunchCandidates() {
        // Clock pins in two SLRs: the alternate launch candidates are kept and updated incrementally too
        incrementalEqualsFull(new int[]{0, 1, 0}, false);
    }

    /**
     * The fixed delays of edges change too (as those of the LUT arcs and intra-site delays of a connection that LUT
     * pin swapping routes to another pin of its LUT, see {@link StaClockAnalysis#setEdgeFixedDelays}): the
     * incremental passes start from the edges' sink pins and give the times, criticalities and hold slacks of a
     * full pass.
     */
    @Test
    public void testIncrementalEqualsFullWithFixedDelays() {
        incrementalEqualsFull(new int[]{0, 1, 0}, true);
    }

    private static void incrementalEqualsFull(int[] slrs, boolean fixedDelays) {
        StaGraph g = TestStaGraph.randomGraph(21, 30_000, 500, 4, new StaGraphAssembler());
        StaClockAnalysis inc = new StaClockAnalysis(randomBinding(g, 1, slrs), ForkJoinPool.commonPool());
        inc.update();
        Assertions.assertTrue(inc.getNumTags() > 1);
        Random r = new Random(9);
        for (int round = 0; round < 5; round++) {
            for (int i = 0; i < 200; i++) {
                int c = r.nextInt(g.getNumConnections());
                float dl = r.nextInt(900) + r.nextFloat();
                inc.setConnectionRouteDelay(c, dl, dl * 0.6f);
            }
            if (fixedDelays) {
                for (int i = 0; i < 200; i++) {
                    int e = r.nextInt(g.getNumEdges());
                    float dl = r.nextInt(300) + r.nextFloat();
                    inc.setEdgeFixedDelays(e, dl, dl * 0.7f, dl * 0.6f);
                }
            }
            Assertions.assertTrue(inc.update());
            Assertions.assertFalse(inc.update());
            inc.computeCriticality(0.99f, 3);
            StaClockAnalysis full = new StaClockAnalysis(randomBinding(g, 1, slrs), ForkJoinPool.commonPool());
            full.setParallel(round % 2 == 0);
            full.updateFull();
            full.computeCriticality(0.99f, 3);
            for (int p = 0; p < g.getNumPins(); p++) {
                for (int i = 0; i < inc.getNumPinTags(p); i++) {
                    Assertions.assertEquals(full.getArrival(p, i), inc.getArrival(p, i), "arrival " + p);
                    Assertions.assertEquals(full.getShiftedRequired(p, i), inc.getShiftedRequired(p, i), "required " + p);
                    Assertions.assertEquals(full.getLaunchClockPin(p, i), inc.getLaunchClockPin(p, i));
                }
            }
            for (int c = 0; c < g.getNumConnections(); c++) {
                Assertions.assertEquals(full.getCriticality(c), inc.getCriticality(c), "connection " + c);
            }
            Assertions.assertArrayEquals(full.getEndpointSetupSlacks(), inc.getEndpointSetupSlacks());
            if (fixedDelays) {
                Assertions.assertArrayEquals(full.getEndpointHoldSlacks(), inc.getEndpointHoldSlacks());
            }
        }
        Assertions.assertEquals(1, inc.getNumFullPasses());
        Assertions.assertEquals(5, inc.getNumIncrementalPasses());
    }

    /**
     * The launch keys of a clock model: the SLRs of its clock pins (-1 if unknown) when they are in two SLRs or
     * more, else none; the analysis of such a model keeps alternate launches (see testLaunchCandidates).
     */
    @Test
    public void testLaunchKeys() {
        ClockModelBuilder t = new ClockModelBuilder();
        int root = t.addNode(ClockModelBuilder.NO_PARENT, d(100, 90, 50, 55));
        int a = t.addNode(root, d(10, 9, 5, 6));
        int b = t.addNode(root, d(20, 18, 10, 12));
        ClockModel two = t.build(new int[]{a, b, a}, new int[]{0, 1, -1});
        int[] keys = StaClockAnalysis.getLaunchKeys(two);
        Assertions.assertArrayEquals(new int[]{0, 1, -1}, keys);
        for (int i = 0; i < two.getNumClockPins(); i++) {
            Assertions.assertEquals(two.getSlr(i), keys[i]);
        }
        Assertions.assertNotSame(keys, StaClockAnalysis.getLaunchKeys(two));
        Assertions.assertNull(StaClockAnalysis.getLaunchKeys(t.build(new int[]{a, b}, new int[]{1, 1})));
        Assertions.assertNull(StaClockAnalysis.getLaunchKeys(t.build(new int[]{a, b})));
    }

    /**
     * The hold refinement window: the refinement of testHoldRefinement applies to a hold slack below the
     * refinement slack, and finds a launch within the spread of the earliest.
     */
    @Test
    public void testHoldRefineWindow() {
        Assertions.assertEquals(StaClockAnalysis.HOLD_REFINE_SLACK, StaClockAnalysis.getHoldRefineSlack());
        Assertions.assertEquals(StaClockAnalysis.HOLD_REFINE_SPREAD, StaClockAnalysis.getHoldRefineSpread());
        int[] pins = new int[3];
        StaClockAnalysis.Check unrefined = launchCandidates(false, false, pins).getWorstHoldCheck(pins[2]);
        StaClockAnalysis.Check refined = launchCandidates(false, true, pins).getWorstHoldCheck(pins[2]);
        Assertions.assertTrue(unrefined.slack < StaClockAnalysis.getHoldRefineSlack());
        Assertions.assertTrue(refined.slack < unrefined.slack);
        Assertions.assertTrue(refined.arrival - unrefined.arrival < StaClockAnalysis.getHoldRefineSpread());
    }
}

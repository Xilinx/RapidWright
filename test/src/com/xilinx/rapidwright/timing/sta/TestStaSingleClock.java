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

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.util.Random;
import java.util.concurrent.ForkJoinPool;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

/**
 * Tests the analysis of a single ideal clock (see {@link StaClockBinding#singleClock(StaGraph, float)}), the
 * analysis of a design without clocks, and {@link TimingReport} over it, against a reference propagation of the
 * same arithmetic, one pin at a time.
 */
public class TestStaSingleClock {

    /**
     * 0 -(10)-> 2, 1 -(20)-> 2, 2 -(5)-> 3 (endpoint), 2 -(7)-> 4 (no fanout, not an endpoint),
     * 1 -(100)-> 5 (endpoint), the edge 1 -> 5 going through connection 0 (route 60 of its 100).
     */
    private static StaGraph smallGraph(int[] finalIds) {
        StaGraphAssembler asm = new StaGraphAssembler();
        int[] p = TestStaGraph.addPins(asm, 6);
        int c = asm.addConnection(null, 60, 30);
        TestStaGraph.addEdge(asm, p[0], p[2], 10);
        TestStaGraph.addEdge(asm, p[1], p[2], 20);
        TestStaGraph.addEdge(asm, p[2], p[3], 5);
        TestStaGraph.addEdge(asm, p[2], p[4], 7);
        asm.addEdge(p[1], p[5], StaGraph.NET, 40, 35, 40, c);
        asm.markEndpoint(p[3]);
        asm.markEndpoint(p[5]);
        StaGraph g = asm.build();
        for (int i = 0; i < 6; i++) {
            finalIds[i] = asm.getFinalPinId(p[i]);
        }
        return g;
    }

    private static StaClockAnalysis singleClock(StaGraph g, float requirement) {
        return new StaClockAnalysis(StaClockBinding.singleClock(g, requirement), ForkJoinPool.commonPool());
    }

    @Test
    public void testArrivalAndRequired() {
        int[] id = new int[6];
        StaGraph g = smallGraph(id);
        StaClockAnalysis an = singleClock(g, 50);
        Assertions.assertTrue(an.update());
        float[] arr = {0, 0, 20, 25, 27, 100};
        for (int i = 0; i < 6; i++) {
            Assertions.assertEquals(arr[i], an.getArrival(id[i], 0), "arrival " + i);
        }
        // The requirement is normalized to the maximum arrival time
        Assertions.assertEquals(100f, an.getGroupNormalization(0));
        float[] req = {85, 0, 95, 100, StaClockAnalysis.DANGLING_REQUIRED, 100};
        for (int i = 0; i < 6; i++) {
            Assertions.assertEquals(req[i], an.getShiftedRequired(id[i], 0), "required " + i);
        }
        TimingReport report = new TimingReport(an);
        Assertions.assertEquals(id[5], report.getWorstSetupCheck().getEndpoint());

        an = singleClock(g, 200);
        Assertions.assertTrue(an.update());
        Assertions.assertEquals(200f, an.getGroupNormalization(0));
        Assertions.assertEquals(100f, an.getShiftedRequired(id[1], 0));
        Assertions.assertEquals(185f, an.getShiftedRequired(id[0], 0));

        // Criticality of the connection: slack 100, (1 - 100/200)^3 * 0.99
        an.computeCriticality(0.99f, 3);
        Assertions.assertEquals((float) Math.pow(0.5f, 3) * 0.99f, an.getCriticality(0));

        report = new TimingReport(an);
        Assertions.assertArrayEquals(new int[]{Math.min(id[3], id[5]), Math.max(id[3], id[5])}, report.getEndpoints());
        Assertions.assertEquals(100f, report.getWNS());
        Assertions.assertEquals(0f, report.getTNS());
        report = new TimingReport(singleClock(g, 80));
        Assertions.assertEquals(-20f, report.getWNS());
        Assertions.assertEquals(-20f, report.getTNS());
        int[] path = report.getCriticalPath();
        Assertions.assertEquals(1, path.length);
        Assertions.assertEquals(id[5], g.getEdgeTo(path[0]));
        Assertions.assertEquals(100f, report.getPathDelay(path));
        int[] p03 = report.getMaxDelayPath(id[0], id[3]);
        Assertions.assertEquals(2, p03.length);
        Assertions.assertEquals(15f, report.getPathDelay(p03));
        Assertions.assertNull(report.getMaxDelayPath(id[0], id[5]));
        Assertions.assertEquals(25f, report.getPathDelay(report.getMaxDelayPath(id[1], id[3])));
        // The connection's edge: its fixed delay of 40 is intra-site, a net delay with its route of 60
        Assertions.assertEquals(0f, report.getLogicDelay(path[0]));
        Assertions.assertEquals(100f, report.getNetDelay(path[0]));

        // Without a requirement: the largest arrival time is the requirement of the criticalities, and the
        // slacks are those of a requirement of 0
        report = new TimingReport(singleClock(g, 0));
        Assertions.assertEquals(-100f, report.getWNS());
        Assertions.assertEquals(-125f, report.getTNS());
        Assertions.assertEquals(id[5], g.getEdgeTo(report.getCriticalPath()[0]));
        // The slacks are those of the requirement given, even below 0
        Assertions.assertEquals(-101f, new TimingReport(singleClock(g, -1)).getWNS());
        // Without endpoints, the WNS is the requirement
        Assertions.assertEquals(80f, new TimingReport(singleClock(new StaGraphAssembler().build(), 80)).getWNS());
    }

    /** The critical path printout of a single clock: the requirement less the path's delay */
    @Test
    public void testPrintCriticalPathInfo() {
        int[] id = new int[6];
        StaGraph g = smallGraph(id);
        for (float requirement : new float[]{80f, 0f}) {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            new TimingReport(singleClock(g, requirement)).printCriticalPathInfo(new PrintStream(bytes), 1.03f, 100f,
                    false, null);
            String out = bytes.toString();
            Assertions.assertTrue(out.contains(String.format("%-35s %10.2f\n", "Timing requirement (ps):",
                    requirement)), out);
            Assertions.assertTrue(out.contains(String.format("%-35s %10d\n", "Critical path delay (ps):", 100)), out);
            Assertions.assertTrue(out.contains(String.format("%-35s %10d\n", "Slack (ps):",
                    Math.round(requirement - 100))), out);
            Assertions.assertTrue(out.contains(String.format("%-35s %10d\n", "Critical path delay (ps):", 203)), out);
            Assertions.assertFalse(out.contains("Clock skew"), out);
        }
    }

    @Test
    public void testHoldArrival() {
        int[] id = new int[6];
        StaClockAnalysis an = singleClock(smallGraph(id), 1000);
        Assertions.assertTrue(an.updateHold());
        Assertions.assertFalse(an.updateHold());
        // The min arrival times, with the min delays: e.g. 1 -> 5, 35 fixed + 30 route
        assertHoldArrival(an, id[3], 15f);
        assertHoldArrival(an, id[5], 65f);
        an.setConnectionRouteDelay(0, 70, 5);
        Assertions.assertTrue(an.updateHold());
        assertHoldArrival(an, id[5], 40f);
    }

    /** Checks the hold arrival time of an endpoint */
    private static void assertHoldArrival(StaClockAnalysis an, int pin, float minArrival) {
        StaClockAnalysis.Check h = an.getWorstHoldCheck(pin);
        Assertions.assertEquals(minArrival, h.getArrival());
        Assertions.assertEquals(minArrival, h.getSlack());
    }

    @Test
    public void testDirtyTracking() {
        int[] id = new int[6];
        StaClockAnalysis an = singleClock(smallGraph(id), 50);
        Assertions.assertTrue(an.isDirty());
        Assertions.assertTrue(an.update());
        Assertions.assertFalse(an.update());
        Assertions.assertEquals(1, an.getNumFullPasses());
        // Same delay: still clean
        an.setConnectionRouteDelay(0, 60, 30);
        Assertions.assertFalse(an.isDirty());
        Assertions.assertFalse(an.update());
        an.setConnectionRouteDelay(0, 61, 30);
        Assertions.assertTrue(an.isDirty());
        Assertions.assertTrue(an.update());
        Assertions.assertFalse(an.isDirty());
        Assertions.assertEquals(101f, an.getArrival(id[5], 0));
        Assertions.assertEquals(1, an.getNumFullPasses());
        Assertions.assertEquals(1, an.getNumIncrementalPasses());
    }

    @Test
    public void testCriticalityFormula() {
        // (1 - max(slack, 0) / maxDelay)^exp * maxCrit
        Assertions.assertEquals(0.99f, StaClockAnalysis.getCriticality(0, 1000, 0.99f, 3));
        Assertions.assertEquals((float) Math.pow(0.75f, 3) * 0.99f,
                StaClockAnalysis.getCriticality(250, 1000, 0.99f, 3));
        Assertions.assertEquals(0f, StaClockAnalysis.getCriticality(1000, 1000, 0.99f, 3));
        // Rounding below 0 is taken as 0
        Assertions.assertEquals(0.99f, StaClockAnalysis.getCriticality(-0.005f, 1000, 0.99f, 3));
        // No edges: no criticality
        Assertions.assertEquals(0f, StaClockAnalysis.getCriticality(Float.MAX_VALUE, 1000, 0.99f, 3));
    }

    /**
     * Computes the arrival and required times of the pins of a graph with a single requirement, one pin at a time
     * in increasing id order (a topological order): the arrival time of a pin is the largest of the arrival time
     * plus delay of its in-edges (0 without any), and its required time the smallest of the required time less
     * delay of its out-edges and, for an endpoint, the requirement normalized to at least the largest arrival time
     * of the endpoints. A pin without fanout that is not an endpoint has an infinite required time.
     * @param g The graph.
     * @param requirement The requirement in ps.
     * @return The arrival times, the required times and, in a single-element array, the normalized requirement.
     */
    static float[][] referenceTimes(StaGraph g, float requirement) {
        int n = g.getNumPins();
        float[] arr = new float[n];
        float maxArrival = 0;
        for (int p = 0; p < n; p++) {
            for (int e = g.getInEdgeStart(p); e < g.getInEdgeEnd(p); e++) {
                Assertions.assertTrue(g.getEdgeFrom(e) < p);
                float a = arr[g.getEdgeFrom(e)] + g.getEdgeDelayMax(e);
                if (e == g.getInEdgeStart(p) || a > arr[p]) {
                    arr[p] = a;
                }
            }
            if (g.isPinKind(p, StaGraph.PIN_END)) {
                maxArrival = Math.max(maxArrival, arr[p]);
            }
        }
        float normalized = Math.max(maxArrival, requirement);
        float[] req = new float[n];
        for (int p = n - 1; p >= 0; p--) {
            req[p] = g.isPinKind(p, StaGraph.PIN_END) ? normalized : Float.POSITIVE_INFINITY;
            for (int i = 0; i < g.getOutDegree(p); i++) {
                int e = g.getOutEdge(p, i);
                req[p] = Math.min(req[p], req[g.getEdgeTo(e)] - g.getEdgeDelayMax(e));
            }
        }
        return new float[][] {arr, req, {normalized}};
    }

    /**
     * Computes the criticality of a connection from the times of {@link #referenceTimes(StaGraph, float)}: from the
     * smallest slack of the connection's edges (0 at least), (1 -
     * slack / normalized requirement)^exponent * maxCriticality, and 0 for a connection without edges.
     */
    static float referenceCriticality(StaGraph g, float[][] times, int conn, float maxCriticality,
                                      float criticalityExponent) {
        float minSlack = Float.MAX_VALUE;
        for (int i = 0; i < g.getConnectionNumEdges(conn); i++) {
            int e = g.getConnectionEdge(conn, i);
            float slack = times[1][g.getEdgeTo(e)] - times[0][g.getEdgeFrom(e)] - g.getEdgeDelayMax(e);
            minSlack = Float.min(minSlack, slack);
        }
        minSlack = Math.max(minSlack, 0);
        float criticality = Math.max(0f, 1 - minSlack / times[2][0]);
        return (float) Math.pow(criticality, criticalityExponent) * maxCriticality;
    }

    private static void assertSameAsReference(StaGraph g, StaClockAnalysis an, float requirement) {
        float[][] ref = referenceTimes(g, requirement);
        Assertions.assertEquals(ref[2][0], an.getGroupNormalization(0));
        for (int p = 0; p < g.getNumPins(); p++) {
            if (an.getNumPinTags(p) == 0) {
                // A pin that no path reaches (no in-edge, no out-edge)
                Assertions.assertEquals(0, g.getInDegree(p) + g.getOutDegree(p));
                continue;
            }
            Assertions.assertEquals(ref[0][p], an.getArrival(p, 0), "arrival of " + p);
            Assertions.assertEquals(ref[1][p], an.getShiftedRequired(p, 0), "required of " + p);
        }
        for (int c = 0; c < g.getNumConnections(); c++) {
            Assertions.assertEquals(referenceCriticality(g, ref, c, 0.95f, 3f), an.getCriticality(c),
                    "criticality of " + c);
        }
    }

    @Test
    public void testSameAsReference() {
        StaGraph g = TestStaGraph.randomGraph(11, 20_000, 300, 4, new StaGraphAssembler());

        for (float requirement : new float[]{0f, 3000f}) {
            StaClockAnalysis an = singleClock(g, requirement);
            an.computeCriticality(0.95f, 3f);
            assertSameAsReference(g, an, requirement);
        }

        // New route delays
        StaClockAnalysis an = singleClock(g, 1000f);
        Random r = new Random(5);
        for (int c = 0; c < g.getNumConnections(); c++) {
            float d = r.nextInt(800) + r.nextFloat();
            an.setConnectionRouteDelay(c, d, d);
        }
        an.computeCriticality(0.95f, 3f);
        assertSameAsReference(g, an, 1000f);
    }

    @Test
    public void testParallelPassIsDeterministic() {
        StaGraph g = TestStaGraph.randomGraph(13, 400_000, 20_000, 4, new StaGraphAssembler());
        // Wide enough levels to be split in chunks
        int maxLevel = 0;
        for (int l = 0; l < g.getNumLevels(); l++) {
            maxLevel = Math.max(maxLevel, g.getLevelStart(l + 1) - g.getLevelStart(l));
        }
        Assertions.assertTrue(maxLevel >= 4 * StaClockAnalysis.MIN_CHUNK);

        StaClockAnalysis serial = singleClock(g, 2000);
        serial.setParallel(false);
        serial.computeCriticality(0.99f, 3);
        float[] holdSlacks = serial.getEndpointHoldSlacks();
        float[] arr = new float[g.getNumPins()];
        float[] req = new float[g.getNumPins()];
        for (int p = 0; p < g.getNumPins(); p++) {
            arr[p] = serial.getArrival(p, 0);
            req[p] = serial.getShiftedRequired(p, 0);
        }
        float[] crit = new float[g.getNumConnections()];
        for (int c = 0; c < crit.length; c++) {
            crit[c] = serial.getCriticality(c);
        }

        ForkJoinPool pool = new ForkJoinPool(8);
        try {
            for (int run = 0; run < 3; run++) {
                StaClockAnalysis parallel = new StaClockAnalysis(StaClockBinding.singleClock(g, 2000), pool);
                parallel.setParallel(true);
                parallel.computeCriticality(0.99f, 3);
                for (int p = 0; p < g.getNumPins(); p++) {
                    Assertions.assertEquals(Float.floatToRawIntBits(arr[p]),
                            Float.floatToRawIntBits(parallel.getArrival(p, 0)));
                    Assertions.assertEquals(Float.floatToRawIntBits(req[p]),
                            Float.floatToRawIntBits(parallel.getShiftedRequired(p, 0)));
                }
                for (int c = 0; c < crit.length; c++) {
                    Assertions.assertEquals(Float.floatToRawIntBits(crit[c]),
                            Float.floatToRawIntBits(parallel.getCriticality(c)));
                }
                float[] h = parallel.getEndpointHoldSlacks();
                for (int k = 0; k < h.length; k++) {
                    Assertions.assertEquals(Float.floatToRawIntBits(holdSlacks[k]), Float.floatToRawIntBits(h[k]));
                }
            }
        } finally {
            pool.shutdown();
        }
    }
}

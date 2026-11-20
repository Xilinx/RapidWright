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

import java.util.Random;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

public class TestStaGraph {

    /** Adds n pins with keys 0..n-1 */
    static int[] addPins(StaGraphAssembler asm, int n) {
        int[] pins = new int[n];
        for (int i = 0; i < n; i++) {
            pins[i] = asm.addPin(StaGraph.makePinKey(0, i), (byte) 0);
        }
        return pins;
    }

    static void addEdge(StaGraphAssembler asm, int from, int to, float delay) {
        asm.addEdge(from, to, StaGraph.CELL_COMB, delay, delay, 0f, StaGraph.NO_CONN);
    }

    /** Finds the final pin of a key */
    static int pinOfKey(StaGraph g, int portIdx) {
        for (int p = 0; p < g.getNumPins(); p++) {
            if (StaGraph.getPortIndex(g.getPinKey(p)) == portIdx) return p;
        }
        return -1;
    }

    /** Checks that every edge goes from a lower level to a higher one, and the CSR arrays */
    static void checkLevelized(StaGraph g) {
        int[] level = new int[g.getNumPins()];
        for (int l = 0; l < g.getNumLevels(); l++) {
            Assertions.assertTrue(g.getLevelStart(l) < g.getLevelStart(l + 1), "empty level " + l);
            for (int p = g.getLevelStart(l); p < g.getLevelStart(l + 1); p++) {
                level[p] = l;
            }
        }
        Assertions.assertEquals(0, g.getLevelStart(0));
        Assertions.assertEquals(g.getNumPins(), g.getLevelStart(g.getNumLevels()));
        int numOut = 0;
        for (int p = 0; p < g.getNumPins(); p++) {
            for (int e = g.getInEdgeStart(p); e < g.getInEdgeEnd(p); e++) {
                Assertions.assertEquals(p, g.getEdgeTo(e));
                Assertions.assertTrue(level[g.getEdgeFrom(e)] < level[p]);
            }
            // A pin of level l > 0 has an in-edge from level l-1 (Kahn levels are longest paths)
            if (level[p] > 0) {
                boolean fromPrevious = false;
                for (int e = g.getInEdgeStart(p); e < g.getInEdgeEnd(p); e++) {
                    fromPrevious |= level[g.getEdgeFrom(e)] == level[p] - 1;
                }
                Assertions.assertTrue(fromPrevious);
            } else {
                Assertions.assertEquals(0, g.getInDegree(p));
            }
            for (int i = 0; i < g.getOutDegree(p); i++) {
                Assertions.assertEquals(p, g.getEdgeFrom(g.getOutEdge(p, i)));
                numOut++;
            }
        }
        Assertions.assertEquals(g.getNumEdges(), numOut);
    }

    @Test
    public void testLevelization() {
        StaGraphAssembler asm = new StaGraphAssembler();
        //  0 -> 1 -> 2 -> 3
        //  0 ------------> 3
        //  4 -> 2
        //  5 (isolated)
        int[] p = addPins(asm, 6);
        addEdge(asm, p[2], p[3], 1);
        addEdge(asm, p[0], p[1], 1);
        addEdge(asm, p[1], p[2], 1);
        addEdge(asm, p[0], p[3], 1);
        addEdge(asm, p[4], p[2], 1);
        StaGraph g = asm.build();
        checkLevelized(g);
        Assertions.assertEquals(6, g.getNumPins());
        Assertions.assertEquals(5, g.getNumEdges());
        Assertions.assertEquals(4, g.getNumLevels());
        Assertions.assertEquals(0, g.getNumCutEdges());
        // Level 0: 0, 4, 5; level 1: 1; level 2: 2; level 3: 3
        Assertions.assertEquals(3, g.getLevelStart(1));
        Assertions.assertEquals(3, asm.getFinalPinId(p[1]));
        Assertions.assertEquals(4, asm.getFinalPinId(p[2]));
        Assertions.assertEquals(5, asm.getFinalPinId(p[3]));
        Assertions.assertEquals(2, g.getInDegree(asm.getFinalPinId(p[3])));
        Assertions.assertTrue(g.isPinKind(asm.getFinalPinId(p[0]), StaGraph.PIN_START));
        Assertions.assertTrue(g.isPinKind(asm.getFinalPinId(p[4]), StaGraph.PIN_START));
        // An isolated pin is no startpoint
        Assertions.assertFalse(g.isPinKind(asm.getFinalPinId(p[5]), StaGraph.PIN_START));
    }

    @Test
    public void testDuplicateEdgesMerged() {
        // Duplicate edges (e.g. a LUT RAM address mapped to A1 and WA1): the max of the max delays and
        // the min of the min delays, whatever their order
        for (boolean reversed : new boolean[]{false, true}) {
            StaGraphAssembler asm = new StaGraphAssembler();
            int[] p = addPins(asm, 2);
            float[][] d = {{10, 4}, {20, 9}, {15, 2}};
            for (int i = 0; i < d.length; i++) {
                float[] x = d[reversed ? d.length - 1 - i : i];
                asm.addEdge(p[0], p[1], StaGraph.CELL_COMB, x[0], x[1], 0f, StaGraph.NO_CONN);
            }
            StaGraph g = asm.build();
            Assertions.assertEquals(1, g.getNumEdges());
            Assertions.assertEquals(20f, g.getEdgeDelayMax(0));
            Assertions.assertEquals(2f, g.getEdgeDelayMin(0));
        }
    }

    @Test
    public void testCycleCutting() {
        StaGraphAssembler asm = new StaGraphAssembler();
        // 0 -> 1 -> 2 -> 0 (cycle closed by 2 -> 0), 2 -> 3, 3 -> 3 (self loop), 4 -> 1
        int[] p = addPins(asm, 5);
        addEdge(asm, p[0], p[1], 1);
        addEdge(asm, p[1], p[2], 1);
        addEdge(asm, p[2], p[0], 1);
        addEdge(asm, p[2], p[3], 1);
        addEdge(asm, p[3], p[3], 1);
        addEdge(asm, p[4], p[1], 1);
        StaGraph g = asm.build();
        checkLevelized(g);
        Assertions.assertEquals(2, g.getNumCutEdges());
        Assertions.assertEquals(4, g.getNumEdges());
        // The DFS from pin 0 finds 2 -> 0 first, then 3 -> 3
        Assertions.assertEquals(asm.getFinalPinId(p[2]), g.getCutEdgeFrom(0));
        Assertions.assertEquals(asm.getFinalPinId(p[0]), g.getCutEdgeTo(0));
        Assertions.assertEquals(asm.getFinalPinId(p[3]), g.getCutEdgeFrom(1));
        Assertions.assertEquals(asm.getFinalPinId(p[3]), g.getCutEdgeTo(1));
    }

    @Test
    public void testLongCycleCutting() {
        // A long chain closed into a cycle exercises the iterative DFS's stack
        int n = 100_000;
        StaGraphAssembler asm = new StaGraphAssembler();
        int[] p = addPins(asm, n);
        for (int i = 0; i < n; i++) {
            addEdge(asm, p[i], p[(i + 1) % n], 1);
        }
        StaGraph g = asm.build();
        checkLevelized(g);
        Assertions.assertEquals(1, g.getNumCutEdges());
        Assertions.assertEquals(n, g.getNumLevels());
    }

    @Test
    public void testEndpoints() {
        StaGraphAssembler asm = new StaGraphAssembler();
        // 0 -> 1 -> 2 -> 3 -> 1 (cycle closed by 3 -> 1); 0 -> 4; 0 -> 5 -> 6; 7 (isolated)
        int[] p = addPins(asm, 8);
        addEdge(asm, p[0], p[1], 1);
        addEdge(asm, p[1], p[2], 1);
        addEdge(asm, p[2], p[3], 1);
        addEdge(asm, p[3], p[1], 1);
        addEdge(asm, p[0], p[4], 1);
        addEdge(asm, p[0], p[5], 1);
        addEdge(asm, p[5], p[6], 1);
        // 3 has no fanout once the cycle is cut: an endpoint if marked so
        asm.markEndpoint(p[3]);
        asm.markEndpointIfNoFanout(p[4]);
        // An endpoint with fanout only when forced
        asm.markEndpoint(p[5]);
        asm.forceEndpoint(p[2]);
        // Startpoints are not endpoints, even when forced
        asm.forceEndpoint(p[0]);
        asm.forceEndpoint(p[7]);
        StaGraph g = asm.build();
        Assertions.assertEquals(1, g.getNumCutEdges());
        boolean[] end = new boolean[8];
        for (int i = 0; i < 8; i++) {
            end[i] = g.isPinKind(asm.getFinalPinId(p[i]), StaGraph.PIN_END);
        }
        Assertions.assertArrayEquals(new boolean[]{false, false, true, true, true, false, false, true}, end);

        StaGraphAssembler asm2 = new StaGraphAssembler();
        int[] q = addPins(asm2, 4);
        addEdge(asm2, q[0], q[1], 1);
        addEdge(asm2, q[1], q[2], 1);
        addEdge(asm2, q[2], q[1], 1);
        // Pin 2 had fanout before the cut of 2 -> 1
        asm2.markEndpointIfNoFanout(q[2]);
        StaGraph g2 = asm2.build();
        Assertions.assertFalse(g2.isPinKind(asm2.getFinalPinId(q[2]), StaGraph.PIN_END));
    }

    @Test
    public void testConnections() {
        StaGraphAssembler asm = new StaGraphAssembler();
        int[] p = addPins(asm, 4);
        int c0 = asm.addConnection(null, 100, 90);
        int c1 = asm.addConnection(null, 200, 150);
        asm.addEdge(p[0], p[1], StaGraph.NET, 10, 8, 10, c0);
        asm.addEdge(p[0], p[2], StaGraph.NET, 20, 15, 20, c0);
        asm.addEdge(p[0], p[3], StaGraph.NET, 30, 25, 30, c1);
        StaGraph g = asm.build();
        Assertions.assertEquals(2, g.getNumConnections());
        Assertions.assertEquals(2, g.getConnectionNumEdges(c0));
        Assertions.assertEquals(1, g.getConnectionNumEdges(c1));
        int e = g.getConnectionEdge(c1, 0);
        Assertions.assertEquals(asm.getFinalPinId(p[3]), g.getEdgeTo(e));
        Assertions.assertEquals(230f, g.getEdgeDelayMax(e));
        Assertions.assertEquals(175f, g.getEdgeDelayMin(e));
        for (int i = 0; i < 2; i++) {
            Assertions.assertEquals(c0, g.getEdgeConnection(g.getConnectionEdge(c0, i)));
        }
    }

    /**
     * Builds a random DAG of pins in layers, with random edges between them, some going through
     * shared connections, its sinks being endpoints.
     */
    static StaGraph randomGraph(long seed, int numPins, int width, int fanin, StaGraphAssembler asm) {
        Random r = new Random(seed);
        int[] p = addPins(asm, numPins);
        int numConns = numPins / 4;
        for (int c = 0; c < numConns; c++) {
            float d = r.nextInt(500) + r.nextFloat();
            asm.addConnection(null, d, d * 0.7f);
        }
        for (int i = width; i < numPins; i++) {
            int k = 1 + r.nextInt(fanin);
            for (int j = 0; j < k; j++) {
                int lo = Math.max(0, i - 3 * width);
                int from = lo + r.nextInt(i - lo);
                float d = r.nextInt(300) + r.nextFloat();
                int conn = r.nextInt(3) == 0 ? r.nextInt(numConns) : StaGraph.NO_CONN;
                asm.addEdge(p[from], p[i], conn < 0 ? StaGraph.CELL_COMB : StaGraph.NET, d, d * 0.8f, 0f, conn);
            }
            asm.markEndpoint(p[i]);
            if (r.nextInt(50) == 0) {
                asm.forceEndpoint(p[i]);
            }
        }
        return asm.build();
    }

    @Test
    public void testRandomGraph() {
        StaGraph g = randomGraph(7, 50_000, 1000, 4, new StaGraphAssembler());
        checkLevelized(g);
        Assertions.assertEquals(0, g.getNumCutEdges());
    }

    @Test
    public void testMemoryPerPinAndEdge() {
        StaGraph g = randomGraph(3, 200_000, 2000, 3, new StaGraphAssembler());
        double perPin = (g.getPinArrayBytes() + g.getCellArrayBytes()) / (double) g.getNumPins();
        double perEdge = (g.getEdgeArrayBytes() + g.getConnectionArrayBytes()) / (double) g.getNumEdges();
        Assertions.assertTrue(perPin <= 32, "bytes per pin " + perPin);
        Assertions.assertTrue(perEdge <= 48, "bytes per edge " + perEdge);
        // The analysis of a single clock, setup and hold: one tag per pin
        StaClockAnalysis an = new StaClockAnalysis(StaClockBinding.singleClock(g, 1000));
        an.computeCriticality(0.99f, 3);
        an.updateHold();
        double analysisPerPin = an.getMemoryBytes() / (double) g.getNumPins();
        Assertions.assertTrue(analysisPerPin <= 64, "analysis bytes per pin " + analysisPerPin);
    }
}

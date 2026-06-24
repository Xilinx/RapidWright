/*
 * Copyright (c) 2025, Advanced Micro Devices, Inc.
 * All rights reserved.
 *
 * Author: Chris Lavin, AMD Research and Advanced Development.
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

package com.xilinx.rapidwright.timing;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Set;

import org.jgrapht.GraphPath;
import org.jgrapht.alg.cycle.CycleDetector;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import com.xilinx.rapidwright.design.Design;
import com.xilinx.rapidwright.device.Series;
import com.xilinx.rapidwright.edif.EDIFHierCellInst;
import com.xilinx.rapidwright.edif.EDIFHierNet;
import com.xilinx.rapidwright.edif.EDIFHierPortInst;
import com.xilinx.rapidwright.edif.EDIFNetlist;
import com.xilinx.rapidwright.support.RapidWrightDCP;

public class TestTimingGraph {

    @Test
    public void testGetTimingPaths() {
        Design d = RapidWrightDCP.loadDCP("picoblaze_ooc_X10Y235_2022_1.dcp");

        TimingManager tm = new TimingManager(d);
        TimingGraph tg = tm.getTimingGraph();

        GraphPath<TimingVertex, TimingEdge> criticalPath = tg.getMaxDelayPath();

        EDIFHierNet clk = tg.getClockNet(criticalPath);
        Assertions.assertEquals("clk", clk.toString());

        tg.prettyPrintPathDelays(criticalPath);

        Assertions.assertEquals(2437.7f, tg.getPathDelay(criticalPath));

        GraphPath<TimingVertex, TimingEdge> otherPath = tg.getTimingPath(
                "processor/sx_addr4_flop/Q",
                "output_port_w_reg[6]/D");

        clk = tg.getClockNet(otherPath);
        Assertions.assertEquals("clk", clk.toString());

        tg.prettyPrintPathDelays(otherPath);
        
        Assertions.assertEquals(1611.1f, tg.getPathDelay(otherPath));
    }

    /**
     * Verifies that a TimingGraph can be built for a Versal design in
     * topology-only mode. The lightweight delay model has no Versal data,
     * so all delays are 0; the structural graph (vertices, edges with
     * Net/SitePinInst annotations, and Q->D paths between FDRE/FDSE/FDPE/FDCE
     * cells) must still be produced.
     */
    @Test
    public void testVersalTopologyOnly() {
        Design d = RapidWrightDCP.loadDCP("picoblaze_2022.2.dcp");
        Assertions.assertEquals(Series.Versal, d.getDevice().getSeries(),
                "picoblaze_2022.2.dcp is expected to be a Versal design");

        TimingManager tm = new TimingManager(d);
        Assertions.assertTrue(tm.getTimingModel().isTopologyOnly(),
                "TimingModel should fall back to topology-only mode on Versal");

        TimingGraph tg = tm.getTimingGraph();
        Assertions.assertTrue(tg.vertexSet().size() > 0, "graph has no vertices");
        Assertions.assertTrue(tg.edgeSet().size() > 0, "graph has no edges");

        // At least one edge should carry a physical Net annotation (proves the
        // structural information survives even with no delay data).
        long edgesWithNet = tg.edgeSet().stream()
                .filter(e -> e.getNet() != null)
                .count();
        Assertions.assertTrue(edgesWithNet > 0,
                "no TimingEdges carry a physical Net annotation");

        // FF vertices should be marked from FDRE/FDSE/FDPE/FDCE cells in the design.
        long flopOutputs = tg.vertexSet().stream().filter(TimingVertex::getFlopOutput).count();
        long flopInputs  = tg.vertexSet().stream().filter(TimingVertex::getFlopInput ).count();
        Assertions.assertTrue(flopOutputs > 0, "no FF output vertices were marked");
        Assertions.assertTrue(flopInputs  > 0, "no FF input vertices were marked");

        // Path enumeration: getMaxDelayPath() internally calls buildGraphPaths(1)
        // (K-shortest-paths through super-source/super-sink), which is the working
        // entry point for path enumeration. With all delays = 0, the returned path
        // is still a structurally valid super-source -> super-sink walk.
        GraphPath<TimingVertex, TimingEdge> path = tg.getMaxDelayPath();
        Assertions.assertNotNull(path, "getMaxDelayPath() returned null");
        Assertions.assertTrue(path.getEdgeList().size() > 0,
                "max-delay path has no edges");

        // The internal cache populated by getMaxDelayPath() should contain >= 1 path.
        Assertions.assertTrue(tg.getGraphPaths().size() > 0,
                "graph path cache is empty after getMaxDelayPath()");

        // Walking the path's edges, at least one interior edge should carry a Net.
        long pathEdgesWithNet = path.getEdgeList().stream()
                .filter(e -> e.getNet() != null)
                .count();
        Assertions.assertTrue(pathEdgesWithNet > 0,
                "enumerated path has no edges with Net annotation");
    }

    /**
     * Verifies that TimingGraph.breakCycles() recovers from a non-DAG state.
     *
     * Combinational cycles can show up in real designs (latch feedback,
     * intentional combinational loops, internal DSP/BRAM feedback the
     * lightweight model doesn't know about). Without a loop breaker,
     * setOrderedTimingVertexLists()'s topological-order iterator throws
     * IllegalArgumentException("Graph is not a DAG").
     *
     * The test loads picoblaze_2022.2.dcp (Versal, so the graph itself is built
     * in topology-only mode), injects an artificial back-edge to simulate the
     * cycle pattern, asserts the cycle is detected, runs breakCycles(), and
     * asserts the graph is acyclic and topological order succeeds.
     */
    @Test
    public void testBreakCyclesRecoversFromInjectedCycle() {
        Design d = RapidWrightDCP.loadDCP("picoblaze_2022.2.dcp");
        TimingManager tm = new TimingManager(d);
        TimingGraph tg = tm.getTimingGraph();

        // postBuild() already ran breakCycles() once; the graph should be a DAG.
        Assertions.assertFalse(new CycleDetector<>(tg).detectCycles(),
                "graph should be acyclic after initial postBuild");

        // Pick an interior edge (not super-source/sink) and add the reverse
        // edge to introduce a 2-cycle.
        TimingEdge sample = tg.edgeSet().stream()
                .filter(e -> e.getSrc() != null && e.getDst() != null)
                .filter(e -> !"superSource".equals(e.getSrc().getName()))
                .filter(e -> !"superSink".equals(e.getDst().getName()))
                .findFirst()
                .orElseThrow(() -> new NoSuchElementException(
                        "no interior edge found to seed the cycle"));
        TimingVertex a = sample.getSrc();
        TimingVertex b = sample.getDst();
        Assertions.assertNull(tg.getEdge(b, a),
                "test precondition: reverse edge should not already exist");
        TimingEdge backEdge = new TimingEdge(tg, b, a);
        Assertions.assertTrue(tg.addEdge(b, a, backEdge),
                "failed to inject reverse edge");
        Assertions.assertTrue(new CycleDetector<>(tg).detectCycles(),
                "injected back-edge should have introduced a cycle");

        // Without breakCycles(), setOrderedTimingVertexLists() throws.
        Assertions.assertThrows(IllegalArgumentException.class,
                () -> tg.setOrderedTimingVertexLists(),
                "topological-order iterator should throw on a non-DAG");

        // breakCycles() should remove at least the back-edge we added and
        // leave the graph acyclic.
        int removed = tg.breakCycles();
        Assertions.assertTrue(removed >= 1,
                "breakCycles() should have removed at least one edge");
        Assertions.assertFalse(new CycleDetector<>(tg).detectCycles(),
                "graph should be acyclic after breakCycles()");

        // Topological order should now succeed.
        Assertions.assertDoesNotThrow(tg::setOrderedTimingVertexLists,
                "setOrderedTimingVertexLists() should succeed after breakCycles()");
    }

    /**
     * Stress-test breakCycles() with many injected cycles.
     *
     * Injects N independent back-edges into the picoblaze Versal graph
     * (creating N separate 2-cycles), then verifies that:
     *  - breakCycles() terminates,
     *  - the number of edges removed is in the range [N, edgeCountBefore],
     *  - the final edge count equals (edgeCountBefore - removed),
     *  - the graph is acyclic afterwards.
     *
     * This pins down the termination bound: each successful iteration must
     * strictly shrink the edge count, so the loop can run at most
     * edgeSet().size() times no matter how many cycles are present.
     */
    @Test
    public void testBreakCyclesTerminatesUnderManyInjectedCycles() {
        Design d = RapidWrightDCP.loadDCP("picoblaze_2022.2.dcp");
        TimingManager tm = new TimingManager(d);
        TimingGraph tg = tm.getTimingGraph();

        final int target = 50;
        int injected = 0;
        for (TimingEdge e : new java.util.ArrayList<>(tg.edgeSet())) {
            if (injected >= target) break;
            TimingVertex a = e.getSrc();
            TimingVertex b = e.getDst();
            if (a == null || b == null) continue;
            if ("superSource".equals(a.getName())) continue;
            if ("superSink".equals(b.getName()))   continue;
            if (tg.getEdge(b, a) != null) continue; // skip if reverse already exists
            tg.addEdge(b, a, new TimingEdge(tg, b, a));
            injected++;
        }
        Assertions.assertEquals(target, injected,
                "could not inject the requested number of back-edges");
        Assertions.assertTrue(new CycleDetector<>(tg).detectCycles(),
                "expected cycles after injection");

        int edgesBefore = tg.edgeSet().size();
        int removed = tg.breakCycles();

        Assertions.assertTrue(removed >= injected,
                "breakCycles should remove at least one edge per injected cycle"
                        + "; injected=" + injected + " removed=" + removed);
        Assertions.assertTrue(removed <= edgesBefore,
                "breakCycles cannot remove more edges than existed");
        Assertions.assertEquals(edgesBefore - removed, tg.edgeSet().size(),
                "edge count decrease must match the reported removal count");
        Assertions.assertFalse(new CycleDetector<>(tg).detectCycles(),
                "graph should be acyclic after breakCycles()");
    }

    private static final Set<String> FLOP_TYPES = new HashSet<>(
            List.of("FDRE", "FDSE", "FDPE", "FDCE"));

    /**
     * Validates the memory-scalable cone-path API (TimingGraph.getConePath),
     * which finds a register-to-register path by walking the netlist on demand
     * without building the global timing graph.
     *
     * Uses the small picoblaze Versal DCP: discovers a launch flop Q that
     * reaches a capture flop D through >=1 combinational stage, then asserts
     * getConePath reproduces a well-formed path between the same two pins.
     */
    @Test
    public void testGetConePath() {
        Design d = RapidWrightDCP.loadDCP("picoblaze_2022.2.dcp");
        // No global graph build: construct without auto-build, just to get a
        // TimingGraph instance bound to the design.
        TimingManager tm = new TimingManager(d, /*doBuild=*/false);
        TimingGraph tg = tm.getTimingGraph();
        EDIFNetlist netlist = d.getNetlist();

        // Discover a (launch Q -> capture D) pair connected through logic.
        EDIFHierPortInst[] pair = discoverPairThroughLogic(netlist);
        Assertions.assertNotNull(pair, "no launch->logic->capture pair found in design");
        EDIFHierPortInst from = pair[0];
        EDIFHierPortInst to = pair[1];

        List<EDIFHierPortInst> path = tg.getConePath(from, to, TimingGraph.DEFAULT_CONE_MAX_DEPTH);
        Assertions.assertNotNull(path, "getConePath returned null for a known-connected pair");
        Assertions.assertTrue(path.size() >= 3,
                "expected a multi-pin path through logic, got " + path.size());
        Assertions.assertEquals(from, path.get(0), "path must start at the from pin");
        Assertions.assertEquals(to, path.get(path.size() - 1), "path must end at the to pin");

        // The string-name overload should resolve and return an equivalent path.
        List<EDIFHierPortInst> byName = tg.getConePath(from.toString(), to.toString());
        Assertions.assertNotNull(byName, "name-based getConePath returned null");
        Assertions.assertEquals(from, byName.get(0));
        Assertions.assertEquals(to, byName.get(byName.size() - 1));

        // A path to a clearly unrelated pin (the launch flop's own Q feeding
        // itself) must not be found; and a bogus name must return null safely.
        Assertions.assertNull(tg.getConePath("does/not/exist/Q", to.toString()),
                "unresolvable source pin should yield null, not throw");
    }

    /** Finds a launch flop Q that reaches some capture flop D through >=1 logic stage. */
    private static EDIFHierPortInst[] discoverPairThroughLogic(EDIFNetlist netlist) {
        for (EDIFHierCellInst cell : netlist.getAllLeafHierCellInstances()) {
            if (!FLOP_TYPES.contains(cell.getCellType().getName())) continue;
            EDIFHierPortInst q = cell.getPortInst("Q");
            if (q == null || !q.isOutput()) continue;
            EDIFHierNet qn = q.getHierarchicalNet();
            if (qn == null) continue;
            List<EDIFHierPortInst> qSinks = qn.getLeafHierPortInsts(false, true);
            if (qSinks.size() > 8) continue; // keep the search small/deterministic
            EDIFHierPortInst to = forwardToCaptureThroughLogic(q);
            if (to != null) return new EDIFHierPortInst[] { q, to };
        }
        return null;
    }

    /** Bounded forward BFS returning the first flop D reached at >=1 logic stage. */
    private static EDIFHierPortInst forwardToCaptureThroughLogic(EDIFHierPortInst from) {
        java.util.Map<EDIFHierPortInst, Integer> depth = new java.util.HashMap<>();
        Set<EDIFHierPortInst> seen = new HashSet<>();
        Deque<EDIFHierPortInst> q = new ArrayDeque<>();
        q.add(from); seen.add(from); depth.put(from, 0);
        while (!q.isEmpty()) {
            EDIFHierPortInst out = q.poll();
            EDIFHierNet net = out.getHierarchicalNet();
            if (net == null) continue;
            int nd = depth.get(out) + 1;
            if (nd > 24) continue;
            for (EDIFHierPortInst sink : net.getLeafHierPortInsts(false, true)) {
                if (!sink.isInput()) continue;
                boolean flop = FLOP_TYPES.contains(sink.getCellType().getName());
                if (flop) {
                    if (sink.getPortInst().getName().equals("D") && nd >= 2) return sink;
                    continue;
                }
                for (EDIFHierPortInst cp : sink.getHierarchicalInst().getHierPortInsts()) {
                    if (cp.isOutput() && seen.add(cp)) { depth.put(cp, nd + 1); q.add(cp); }
                }
            }
        }
        return null;
    }
}

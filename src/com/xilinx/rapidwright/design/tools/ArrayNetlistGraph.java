/*
 *
 * Copyright (c) 2025, Advanced Micro Devices, Inc.
 * All rights reserved.
 *
 * Author: Andrew Butt, AMD Advanced Research and Development.
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

package com.xilinx.rapidwright.design.tools;

import com.google.ortools.Loader;
import com.google.ortools.sat.CpModel;
import com.google.ortools.sat.CpSolver;
import com.google.ortools.sat.CpSolverStatus;
import com.google.ortools.sat.IntVar;
import com.google.ortools.sat.LinearExpr;
import com.google.ortools.sat.LinearExprBuilder;
import com.google.ortools.sat.Literal;
import com.xilinx.rapidwright.design.Design;
import com.xilinx.rapidwright.design.blocks.PBlockSide;
import com.xilinx.rapidwright.edif.EDIFHierCellInst;
import com.xilinx.rapidwright.edif.EDIFHierPortInst;
import com.xilinx.rapidwright.edif.EDIFPort;
import com.xilinx.rapidwright.util.Pair;
import jnr.ffi.annotations.In;
import org.jgrapht.Graph;
import org.jgrapht.GraphPath;
import org.jgrapht.alg.cycle.CycleDetector;
import org.jgrapht.alg.shortestpath.DijkstraShortestPath;
import org.jgrapht.graph.DefaultDirectedGraph;
import org.jgrapht.graph.DefaultEdge;
import org.jgrapht.traverse.TopologicalOrderIterator;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

public class ArrayNetlistGraph {
    private static class NetlistEdge extends DefaultEdge {
        private final PBlockSide direction;

        NetlistEdge(PBlockSide direction) {
            this.direction = direction;
        }

        public boolean noDirectionSpecified() {
            return direction == null;
        }

        public boolean isRight() {
            return direction == PBlockSide.RIGHT;
        }

        public boolean isLeft() {
            return direction == PBlockSide.LEFT;
        }

        public boolean isBelow() {
            return direction == PBlockSide.BOTTOM;
        }

        public boolean isAbove() {
            return direction == PBlockSide.TOP;
        }

        @Override
        public String toString() {
            return "(" + getSource() + " : " + getTarget() + ", " + this.direction + ")";
        }
    }
    private Graph<String, NetlistEdge> graph;
    private Map<Pair<String, String>, Boolean> directionMap;

    public ArrayNetlistGraph() {
        graph = new DefaultDirectedGraph<>(NetlistEdge.class);
    }

    public ArrayNetlistGraph(Design array, List<String> modules) {
        this(array, modules, null);
    }

    public ArrayNetlistGraph(Design array, List<String> modules, Map<EDIFPort, PBlockSide> sideMap) {
        this();
        EDIFHierCellInst top = array.getNetlist().getTopHierCellInst();
        for (String module : modules) {
            addVertex(module);
        }

        for (String module : modules) {
            EDIFHierCellInst cellInst = array.getNetlist().getHierCellInstFromName(module);
            for (EDIFHierPortInst portInst : cellInst.getHierPortInsts()) {
                if (portInst.isOutput()) {
                    for (EDIFHierPortInst netPortInst : portInst.getHierarchicalNet().getPortInsts()) {
                        if (!netPortInst.equals(portInst) && netPortInst.getCellType() != null) {
                            EDIFHierCellInst destCellInst = netPortInst.getFullHierarchicalInst();
                            if (destCellInst != null && containsNode(destCellInst.getFullHierarchicalInstName())) {
                                PBlockSide pBlockSide = sideMap == null ? null : sideMap.get(portInst.getPortInst().getPort());

                                addEdge(cellInst.getFullHierarchicalInstName(),
                                        destCellInst.getFullHierarchicalInstName(),
                                        pBlockSide);
                            }
                        }
                    }
                }
            }
        }
    }

    public void addVertex(String name) {
        graph.addVertex(name);
    }

    public boolean containsNode(String name) {
        return graph.containsVertex(name);
    }

    public void addEdge(String from, String to, PBlockSide direction) {
        graph.addEdge(from, to, new NetlistEdge(direction));
    }

    public boolean isAcyclic() {
        CycleDetector<String, NetlistEdge> cycleDetector = new CycleDetector<>(graph);
        return !cycleDetector.detectCycles();
    }

    public Iterator<String> getTopologicalOrderIterator() {
        return new TopologicalOrderIterator<>(graph);
    }

    public Map<Pair<Integer, Integer>, String> getGreedyPlacementGrid() {
        Map<Pair<Integer, Integer>, String> placementMap = new HashMap<>();
        Map<String, Pair<Integer, Integer>> reversePlacementMap = new HashMap<>();
        Map<String, Integer> candidateMap = new HashMap<>();
        Iterator<String> iterator = getTopologicalOrderIterator();
        DijkstraShortestPath<String, NetlistEdge> dsp = new DijkstraShortestPath<>(graph);
        String topLeftNode = iterator.next();
        placementMap.put(new Pair<>(0, 0), topLeftNode);
        reversePlacementMap.put(topLeftNode, new Pair<>(0, 0));
        for (NetlistEdge edge : graph.outgoingEdgesOf(topLeftNode)) {
            String node = graph.getEdgeTarget(edge);
            candidateMap.put(node, 1);
        }

        NetlistEdge extraConstraintEdge = graph.outgoingEdgesOf(topLeftNode).iterator().next();
        if (extraConstraintEdge.isRight() || extraConstraintEdge.isBelow()) {
            // Add additional constraint based on the sideMap
            String extraConstraintNode = graph.getEdgeTarget(extraConstraintEdge);
            candidateMap.remove(extraConstraintNode);
            Pair<Integer, Integer> extraConstraintPlacement;
            if (extraConstraintEdge.isRight()) {
                extraConstraintPlacement = new Pair<>(1, 0);
            } else {
                extraConstraintPlacement = new Pair<>(0, 1);
            }
            placementMap.put(extraConstraintPlacement, extraConstraintNode);
            reversePlacementMap.put(extraConstraintNode, extraConstraintPlacement);
            for (NetlistEdge edge : graph.outgoingEdgesOf(extraConstraintNode)) {
                String targetNode = graph.getEdgeTarget(edge);
                int count = candidateMap.computeIfAbsent(targetNode, (n) -> 0);
                candidateMap.put(targetNode, count + 1);
            }
        }
        while (!candidateMap.isEmpty()) {
            List<String> sortedCandidates = candidateMap.entrySet().stream()
                    .sorted((e1, e2) -> {
                      if (e1.getValue() == e2.getValue()) {
                          // Tie-break of shorted path distance
                          GraphPath<String, NetlistEdge> shortestPathE1 = dsp.getPath(topLeftNode, e1.getKey());
                          GraphPath<String, NetlistEdge> shortestPathE2 = dsp.getPath(topLeftNode, e2.getKey());
                          return shortestPathE1.getLength() - shortestPathE2.getLength();
                      }
                      return e2.getValue().compareTo(e1.getValue());
                    })
                    .map(Map.Entry::getKey).collect(Collectors.toList());
            String node = sortedCandidates.get(0);
            candidateMap.remove(node);
            for (NetlistEdge edge : graph.outgoingEdgesOf(node)) {
                String targetNode = graph.getEdgeTarget(edge);
                int count = candidateMap.computeIfAbsent(targetNode, (n) -> 0);
                candidateMap.put(targetNode, count + 1);
            }
            Set<NetlistEdge> inEdges = graph.incomingEdgesOf(node);
            List<String> inNeighbors = new ArrayList<>();
            for (NetlistEdge e : inEdges) {
                inNeighbors.add(graph.getEdgeSource(e));
            }
            if (inNeighbors.size() > 3) {
                throw new RuntimeException("Greedy placement does not work for given netlist");
            }
            List<Pair<Integer, Integer>> inNeighborPlacements = new ArrayList<>();
            for (String inNeighbor : inNeighbors) {
                inNeighborPlacements.add(reversePlacementMap.get(inNeighbor));
            }
            inNeighborPlacements = inNeighborPlacements.stream().sorted(
                    (p1, p2) -> {
                        if (p1.getSecond().equals(p2.getSecond())) {
                            return p1.getFirst() - p2.getFirst();
                        }
                        return p1.getSecond() - p2.getSecond();
                    }).collect(Collectors.toList());
            List<Pair<Integer, Integer>> validPlacements = new ArrayList<>();
            if (inNeighbors.size() == 1) {
                Pair<Integer, Integer> neighborPlacement = inNeighborPlacements.get(0);
                validPlacements.add(new Pair<>(neighborPlacement.getFirst() + 1, neighborPlacement.getSecond()));
                validPlacements.add(new Pair<>(neighborPlacement.getFirst(), neighborPlacement.getSecond() + 1));
            } else if (inNeighbors.size() == 2) {
                int x = inNeighborPlacements.get(0).getFirst();
                int y = inNeighborPlacements.get(1).getSecond();
                validPlacements.add(new Pair<>(x, y));
            } else {
                throw new RuntimeException("Not yet implemented, try using OR-tools based placement");
            }
            Pair<Integer, Integer> placement = null;
            for (Pair<Integer, Integer> location : validPlacements) {
                if (!placementMap.containsKey(location)) {
                    placement = location;
                }
            }
            if (placement == null) {
                throw new RuntimeException("Could not find valid greedy placement for cell: " + node);
            }
            placementMap.put(placement, node);
            reversePlacementMap.put(node, placement);
        }

        for (int y = 0; y < graph.vertexSet().size(); y++) {
            for (int x = 0; x < graph.vertexSet().size(); x++) {
                if (placementMap.containsKey(new Pair<>(x, y))) {
                    System.out.println("Placed " + placementMap.get(new Pair<>(x, y)) + " at (" + x + ", " + y + ")");
                }
            }
        }

        return placementMap;
    }

    public Map<Pair<Integer, Integer>, String> getOptimalPlacementGrid(int width, int height) {
        Map<Pair<Integer, Integer>, String> placementMap = new HashMap<>();
        int numNodes = graph.vertexSet().size();
        Map<Integer, String> numToNameMap = new HashMap<>();
        Map<String, Integer> nameToNumMap = new HashMap<>();

        int i = 0;
        for (String v : graph.vertexSet()) {
            numToNameMap.put(i, v);
            nameToNumMap.put(v, i);
            i++;
        }

        Loader.loadNativeLibraries();
        CpModel model = new CpModel();
        Literal[][][] placements = new Literal[numNodes][width][height];
        for (int n = 0; n < numNodes; n++) {
            for (int x = 0; x < width; x++) {
                for (int y = 0; y < height; y++) {
                    placements[n][x][y] = model.newBoolVar("placement_n" + n + "x" + x + "y" + y);
                }
            }
        }

        // At most one node can be placed at each grid location
        for (int x = 0; x < width; x++) {
            for (int y = 0; y < height; y++) {
                List<Literal> nodes = new ArrayList<>();
                for (int n = 0; n < numNodes; n++) {
                    nodes.add(placements[n][x][y]);
                }
                model.addAtMostOne(nodes);
            }
        }

        // Every node must be placed at exactly one location
        for (int n = 0; n < numNodes; n++) {
            List<Literal> locations = new ArrayList<>();
            for (int x = 0; x < width; x++) {
                locations.addAll(Arrays.asList(placements[n][x]).subList(0, height));
            }
            model.addExactlyOne(locations);
        }

        // Add auxiliary variables for x and y placement
        IntVar[] xPlacement = new IntVar[numNodes];
        IntVar[] yPlacement = new IntVar[numNodes];
        for (int n = 0; n < numNodes; n++) {
            xPlacement[n] = model.newIntVar(0, width, "x_loc_n" + n);
            yPlacement[n] = model.newIntVar(0, height, "y_loc_n" + n);
            LinearExprBuilder xExpr = LinearExpr.newBuilder();
            LinearExprBuilder yExpr = LinearExpr.newBuilder();
            for (int x = 0; x < width; x++) {
                for (int y = 0; y < height; y++) {
                    xExpr.addTerm(placements[n][x][y], x);
                    yExpr.addTerm(placements[n][x][y], y);
                }
            }
            model.addEquality(xPlacement[n], xExpr);
            model.addEquality(yPlacement[n], yExpr);
        }

        // Add auxiliary variables for x and y distance between connected nodes
        List<IntVar> xDistVars = new ArrayList<>();
        List<IntVar> yDistVars = new ArrayList<>();
        for (String v : graph.vertexSet()) {
            Set<NetlistEdge> outEdges = graph.outgoingEdgesOf(v);
            int sourceNum = nameToNumMap.get(v);
            for (NetlistEdge e : outEdges) {
                String edgeTarget = graph.getEdgeTarget(e);
                int targetNum = nameToNumMap.get(edgeTarget);

                // x distance variable
                IntVar xDistVar = model.newIntVar(0, width, "x_dist_" + v + "_n" + sourceNum + "_to_" + edgeTarget + "_n" + targetNum);
                xDistVars.add(xDistVar);
                IntVar sourceXVar = xPlacement[sourceNum];
                IntVar targetXVar = xPlacement[targetNum];

                // Adding both of these constraints is equivalent to xDistVar = abs(sourceX - targetX)
                LinearExprBuilder sourceMinusTargetX = LinearExpr.newBuilder();
                sourceMinusTargetX.addTerm(sourceXVar, 1);
                sourceMinusTargetX.addTerm(targetXVar, -1);
                model.addGreaterOrEqual(xDistVar, sourceMinusTargetX);

                LinearExprBuilder targetMinusSourceX = LinearExpr.newBuilder();
                targetMinusSourceX.addTerm(targetXVar, 1);
                targetMinusSourceX.addTerm(sourceXVar, -1);
                model.addGreaterOrEqual(xDistVar, targetMinusSourceX);

                // y distance variable
                IntVar yDistVar = model.newIntVar(0, width, "y_dist_" + v + "_n" + sourceNum + "_to_" + edgeTarget + "_n" + targetNum);
                yDistVars.add(yDistVar);
                IntVar sourceYVar = yPlacement[sourceNum];
                IntVar targetYVar = yPlacement[targetNum];

                // Adding both of these constraints is equivalent to xDistVar = abs(sourceX - targetX)
                LinearExprBuilder sourceMinusTargetY = LinearExpr.newBuilder();
                sourceMinusTargetY.addTerm(sourceYVar, 1);
                sourceMinusTargetY.addTerm(targetYVar, -1);
                model.addGreaterOrEqual(yDistVar, sourceMinusTargetY);

                LinearExprBuilder targetMinusSourceY = LinearExpr.newBuilder();
                targetMinusSourceY.addTerm(targetYVar, 1);
                targetMinusSourceY.addTerm(sourceYVar, -1);
                model.addGreaterOrEqual(yDistVar, targetMinusSourceY);

                // Neighbors must be adjacent
                LinearExprBuilder xDistPlusYDist = LinearExpr.newBuilder();
                xDistPlusYDist.addSum(new IntVar[]{xDistVar, yDistVar});
                model.addLessOrEqual(xDistPlusYDist, 1);
                model.addLessOrEqual(xDistVar, 3);
                model.addLessOrEqual(yDistVar, 3);
            }
        }

        // Place the anchor in the top left corner
        String anchor = getTopologicalOrderIterator().next();
        int anchorNum = nameToNumMap.get(anchor);
        model.addAssumption(placements[anchorNum][0][0]);

        NetlistEdge extraConstraintEdge = graph.outgoingEdgesOf(anchor).iterator().next();
        if (extraConstraintEdge.isRight() || extraConstraintEdge.isBelow()) {
            // Add additional constraint based on the sideMap
            String extraConstraintNode = graph.getEdgeTarget(extraConstraintEdge);
            int extraConstraintNum = nameToNumMap.get(extraConstraintNode);
            if (extraConstraintEdge.isRight()) {
                model.addAssumption(placements[extraConstraintNum][1][0]);
            } else {
                model.addAssumption(placements[extraConstraintNum][0][1]);
            }
        }

        IntVar maxXDistVar = model.newIntVar(0, width, "max_x_dist");
        for (IntVar xDistVar : xDistVars) {
            model.addGreaterOrEqual(maxXDistVar, xDistVar);
        }
        IntVar maxYDistVar = model.newIntVar(0, width, "max_y_dist");
        for (IntVar yDistVar : yDistVars) {
            model.addGreaterOrEqual(maxYDistVar, yDistVar);
        }
        LinearExprBuilder obj = LinearExpr.newBuilder();
        obj.add(maxXDistVar);
        obj.add(maxYDistVar);
        model.minimize(obj);

        CpSolver solver = new CpSolver();
        CpSolverStatus status = solver.solve(model);

        if (status == CpSolverStatus.FEASIBLE || status == CpSolverStatus.OPTIMAL) {
            System.out.println("Solution: " + status);
            for (int x = 0; x < width; x++) {
                for (int y = 0; y < height; y++) {
                    for (int n = 0; n < numNodes; n++) {
                        if (solver.booleanValue(placements[n][x][y])) {
                            System.out.println("Placed " + numToNameMap.get(n) + " at (" + x + ", " + y + ")");
                            placementMap.put(new Pair<>(x, y), numToNameMap.get(n));
                            break;
                        }
                    }
                }
            }
        } else {
            throw new RuntimeException("Failed to find optimal placement grid, solver returned status: " + status);
        }

        return placementMap;
    }

    @Override
    public String toString() {
        return graph.toString();
    }
}

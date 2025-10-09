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
import com.google.ortools.sat.BoolVar;
import com.google.ortools.sat.CpModel;
import com.google.ortools.sat.CpSolver;
import com.google.ortools.sat.CpSolverSolutionCallback;
import com.google.ortools.sat.CpSolverStatus;
import com.google.ortools.sat.IntVar;
import com.google.ortools.sat.LinearExpr;
import com.google.ortools.sat.LinearExprBuilder;
import com.google.ortools.sat.Literal;
import com.xilinx.rapidwright.design.Design;
import com.xilinx.rapidwright.edif.EDIFHierCellInst;
import com.xilinx.rapidwright.edif.EDIFHierPortInst;
import com.xilinx.rapidwright.util.Pair;
import org.jgrapht.Graph;
import org.jgrapht.alg.cycle.CycleDetector;
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

public class ArrayNetlistGraph {
    Graph<String, DefaultEdge> graph;

    public ArrayNetlistGraph() {
        graph = new DefaultDirectedGraph<>(DefaultEdge.class);
    }

    public ArrayNetlistGraph(Design array, List<String> modules) {
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
                                addEdge(cellInst.getFullHierarchicalInstName(),
                                        destCellInst.getFullHierarchicalInstName());
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

    public void addEdge(String from, String to) {
        graph.addEdge(from, to);
    }

    public boolean isAcyclic() {
        CycleDetector<String, DefaultEdge> cycleDetector = new CycleDetector<>(graph);
        return !cycleDetector.detectCycles();
    }

    public Iterator<String> getTopologicalOrderIterator() {
        return new TopologicalOrderIterator<>(graph);
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
            Set<DefaultEdge> outEdges = graph.outgoingEdgesOf(v);
            int sourceNum = nameToNumMap.get(v);
            for (DefaultEdge e : outEdges) {
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
//                model.addLessOrEqual(xDistPlusYDist, 1);
//                model.addLessOrEqual(xDistVar, 3);
//                model.addLessOrEqual(yDistVar, 3);
            }
        }

        // Place the anchor in the top left corner
        String anchor = getTopologicalOrderIterator().next();
        int anchorNum = nameToNumMap.get(anchor);
        model.addAssumption(placements[anchorNum][0][0]);

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

        // Add objective to minimize manhattan distance
//        LinearExprBuilder obj = LinearExpr.newBuilder();
//        for (IntVar xDistVar : xDistVars) {
//            obj.addTerm(xDistVar, 1);
//        }
//        for (IntVar yDistVar : yDistVars) {
//            obj.add(yDistVar);
//        }
//        model.minimize(obj);

        CpSolver solver = new CpSolver();
        solver.getParameters().setMaxTimeInSeconds(10.0);
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
        }

        return placementMap;
    }

    @Override
    public String toString() {
        return graph.toString();
    }
}

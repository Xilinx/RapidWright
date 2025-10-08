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

import com.xilinx.rapidwright.design.Design;
import com.xilinx.rapidwright.edif.EDIFHierCellInst;
import com.xilinx.rapidwright.edif.EDIFHierPortInst;
import org.jgrapht.Graph;
import org.jgrapht.alg.cycle.CycleDetector;
import org.jgrapht.graph.DefaultDirectedGraph;
import org.jgrapht.graph.DefaultEdge;
import org.jgrapht.traverse.TopologicalOrderIterator;

import java.util.Iterator;
import java.util.List;

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

    @Override
    public String toString() {
        return graph.toString();
    }
}

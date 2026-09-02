/*
 * Copyright (c) 2026, Advanced Micro Devices, Inc.
 * All rights reserved.
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
 */

package com.xilinx.rapidwright.examples;

import java.util.List;

import org.jgrapht.GraphPath;
import org.jgrapht.alg.shortestpath.KShortestSimplePaths;

import com.xilinx.rapidwright.design.Design;
import com.xilinx.rapidwright.design.Net;
import com.xilinx.rapidwright.device.Node;
import com.xilinx.rapidwright.device.PIP;
import com.xilinx.rapidwright.timing.TimingEdge;
import com.xilinx.rapidwright.timing.TimingGraph;
import com.xilinx.rapidwright.timing.TimingManager;
import com.xilinx.rapidwright.timing.TimingVertex;

/**
 * Loads a placed/routed DCP, builds a TimingGraph (topology-only mode on Versal),
 * enumerates K simple paths between the super-source and super-sink, and prints
 * each path's hierarchical pin sequence along with the carrying net's PIPs.
 *
 * Usage: PrintVersalTimingPaths [dcp] [numPaths]
 *   default dcp:      test/RapidWrightDCP/picoblaze_2022.2.dcp
 *   default numPaths: 100
 */
public class PrintVersalTimingPaths {

    public static void main(String[] args) {
        String dcp = args.length > 0
                ? args[0]
                : "test/RapidWrightDCP/picoblaze_2022.2.dcp";
        int k = args.length > 1 ? Integer.parseInt(args[1]) : 100;

        Design design = Design.readCheckpoint(dcp);

        // On Versal this drops into topology-only mode automatically (delays = 0,
        // structure intact). TimingManager.postBuild() has already wired
        // superSource -> FF outputs and FF inputs -> superSink.
        TimingManager tm = new TimingManager(design);
        TimingGraph tg = tm.getTimingGraph();

        // Enumerate K simple paths between the super-source and super-sink.
        // (TimingGraph.buildGraphPaths(N) currently only returns one Bellman-Ford
        // path regardless of N; calling KShortestSimplePaths directly is the
        // clean way to get many paths.)
        KShortestSimplePaths<TimingVertex, TimingEdge> kSP = new KShortestSimplePaths<>(tg);
        List<GraphPath<TimingVertex, TimingEdge>> paths =
                kSP.getPaths(tg.superSource, tg.superSink, k);

        System.out.println("Enumerated " + paths.size() + " path(s).\n");

        int idx = 0;
        for (GraphPath<TimingVertex, TimingEdge> path : paths) {
            List<TimingEdge> edges = path.getEdgeList();
            System.out.println("=== Path #" + (idx++) + "  (" + edges.size() + " edges) ===");

            for (TimingEdge e : edges) {
                TimingVertex src = e.getSrc();
                TimingVertex dst = e.getDst();

                // Skip the super-source / super-sink connector edges.
                if ("superSource".equals(src.getName())) continue;
                if ("superSink".equals(dst.getName()))   continue;

                System.out.println("  " + src.getName() + "  ->  " + dst.getName());

                if (e.getFirstPin() != null) {
                    System.out.println("      src SitePin: " + e.getFirstPin()
                            + "   tile=" + e.getFirstPin().getTile());
                }
                if (e.getSecondPin() != null) {
                    System.out.println("      dst SitePin: " + e.getSecondPin()
                            + "   tile=" + e.getSecondPin().getTile());
                }

                Net net = e.getNet();
                if (net != null && net.hasPIPs()) {
                    System.out.println("      net: " + net.getName()
                            + "   (" + net.getPIPs().size() + " PIPs)");
                    for (PIP pip : net.getPIPs()) {
                        Node s = pip.getStartNode();
                        Node t = pip.getEndNode();
                        System.out.println("        " + pip.getTile()
                                + ":  " + s + " [" + s.getIntentCode() + "]"
                                + "  ->  " + t + " [" + t.getIntentCode() + "]");
                    }
                }
            }
            System.out.println();
        }
    }
}

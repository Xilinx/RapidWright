/*
 * Copyright (c) 2019-2022, Xilinx, Inc.
 * Copyright (c) 2022-2023, Advanced Micro Devices, Inc.
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
 *
 */

package com.xilinx.rapidwright.timing;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.xilinx.rapidwright.design.ConstraintGroup;
import com.xilinx.rapidwright.design.Design;
import com.xilinx.rapidwright.design.Net;
import com.xilinx.rapidwright.device.Device;
import com.xilinx.rapidwright.device.Node;
import com.xilinx.rapidwright.rwroute.Connection;
import com.xilinx.rapidwright.rwroute.NetWrapper;
import com.xilinx.rapidwright.rwroute.RWRouteConfig;
import com.xilinx.rapidwright.rwroute.RouteNode;
import com.xilinx.rapidwright.rwroute.RouteNodeGraph;
import com.xilinx.rapidwright.timing.delayestimator.DelayEstimatorBase;
import com.xilinx.rapidwright.util.MessageGenerator;
import com.xilinx.rapidwright.util.Pair;
import com.xilinx.rapidwright.util.RuntimeTrackerTree;


/**
 * A TimingManager sets up and creates an example TimingModel and an example TimingGraph for a given
 * Design.
 */
public class TimingManager {
    private TimingModel timingModel;
    private TimingGraph timingGraph;
    private Design design;
    private Device device;

    public static final int BUILD_GRAPH_PATHS_DEFAULT_PARAM = 1; // use 0 instead for all paths
    
    public RuntimeTrackerTree routerTimer;
    private boolean verbose;
    
    private float timingRequirement;
    private float pessimismA = (float) 1.03;
    private float pessimismB = 100;
    
    /**
     * Default constructor: creates the TimingManager object, which the user needs to create for 
     * using our TimingModel, and then it builds the model.
     * @param design RapidWright Design object.
     */
    public TimingManager(Design design) {
        this(design, true);
    }

    /**
     * Alternate constructor for creating the objects for the TimingModel, but with the choice to 
     * not build the model yet.
     * @param design RapidWright Design object.
     * @param doBuild Whether to go ahead and build the model now.  For example, a user might not 
     * want to build the TimingGraph yet.
     */
    public TimingManager(Design design, boolean doBuild) {
        this.design = design;
        timingModel = new TimingModel(design.getDevice());
        timingGraph = new TimingGraph(design);
        timingModel.setTimingManager(this);
        timingGraph.setTimingManager(this);
        timingGraph.setTimingModel(timingModel);
        device = design.getDevice();
        if (doBuild)
            build(false, design.getNets());
    }
    
    public TimingManager(Design design, RuntimeTrackerTree timer, RWRouteConfig config, ClkRouteTiming clkTiming, Collection<Net> targetNets, boolean isPartialRouting) {
        this.design = design;
        setTimingRequirement();
        verbose = config.isVerbose();
        setPessimismFactors(config.getPessimismA(), config.getPessimismB());
        routerTimer = timer;
        timingModel = new TimingModel(design.getDevice());
        timingGraph = new TimingGraph(design, routerTimer, clkTiming, config.getDspTimingDataFolder());
        timingModel.setTimingManager(this);
        timingGraph.setTimingManager(this);
        timingGraph.setTimingModel(timingModel);
        device = design.getDevice();
        build(isPartialRouting, targetNets);
    }
    
    /**
     * Updates the delay of nets after the cycle removal and delay-aware path merging.
     * @param illegalNets {@link NetWrapper} instances in question.
     * @param nodesDelays Stored nodes and their delay values.
     */
    public void updateIllegalNetsDelays(Set<NetWrapper> illegalNets, Map<Node, Float> nodesDelays) {
         for (NetWrapper netWrapper:illegalNets) {
             for (Connection connection:netWrapper.getConnections()) {
                 float netDelay = 0;
                 if (connection.isDirect()) continue;
                 for (int i = connection.getNodes().size() - 2; i >= 0; i--) {
                     Node child = connection.getNodes().get(i);
                     Node parent = connection.getNodes().get(i+1);
                     netDelay += nodesDelays.getOrDefault(child, 0f)
                             + DelayEstimatorBase.getExtraDelay(child, DelayEstimatorBase.isLong(parent));
                 }
                 connection.setTimingEdgesDelay(netDelay);
                 connection.setDlyPatched(true);
             }
         }
    }
    
    /**
     * Patches up the delay of consecutive Long nodes for connections.
     * @param connections Connections in question.
     */
    public void patchUpDelayOfConnections(List<Connection> connections) {
        for (Connection connection : connections) {
            if (connection.isDirect()) continue;
            if (connection.isDlyPatched()) continue;
            float netDelay = 0;
            for (int i = connection.getRnodes().size() - 2; i >= 0; i--) {
                RouteNode child = connection.getRnodes().get(i);
                RouteNode parent = connection.getRnodes().get(i+1);
                netDelay += child.getDelay() + DelayEstimatorBase.getExtraDelay(child, DelayEstimatorBase.isLong(parent));
            }
            connection.setTimingEdgesDelay(netDelay);
            connection.setDlyPatched(true);
        }
    }
    
    /**
     * Calculates and returns the maximum arrival time and the associated TimingVertex
     */
    public Pair<Float,TimingVertex> calculateArrivalRequiredTimes() {
        Pair<Float, TimingVertex> maxs;

        timingGraph.resetRequiredAndArrivalTime();
        timingGraph.computeArrivalTimesTopologicalOrder();

        maxs = timingGraph.getMaxDelay();
        float maxArrival = maxs.getFirst();
        // Negative slacks are not supported. Normalize the required time
        // to be the maximum of the latest arrival time and the timing requirement.
        // If maxArrival > timingRequirement, setting it to timingRequirement would mean
        // negative slack.
        // If timingRequirement > maxArrival, setting it to maxArrival would mean that
        // minimum slack is zero leading to unnecessary router effort.
        float normalizedRequired = Float.max(maxArrival, timingRequirement);
        timingGraph.setTimingRequirementTopologicalOrder(normalizedRequired);
        
        return maxs;
    }
    
    /**
     * Sets critical path delay pessimism factors.
     */
    private void setPessimismFactors(float a, short b) {
        if (a > 1) {
            pessimismA = a;
        }
        if (b > 0) {
            pessimismB = b;
        }
    }
    
    public void getCriticalPathInfo(Pair<Float, TimingVertex> maxDelayTimingVertex, boolean useRoutable, RouteNodeGraph routingGraph) {
        TimingVertex maxV = maxDelayTimingVertex.getSecond();
        float maxDelay = maxDelayTimingVertex.getFirst();
        System.out.printf(MessageGenerator.formatString("Timing requirement (ps):", timingRequirement));
        List<TimingEdge> criticalEdges = timingGraph.getCriticalTimingEdgesInOrder(maxV);
        short arr = 0;
        short clkskew = 0;
        for (TimingEdge e : criticalEdges) {
            arr += e.getDelay();
        }
        System.out.printf(MessageGenerator.formatString("Critical path delay (ps):", (int)(arr - criticalEdges.get(0).getDelay() - clkskew)));
        System.out.printf(MessageGenerator.formatString("Slack (ps):", (int)(timingRequirement - maxDelay)));
        System.out.printf(MessageGenerator.formatString("With timing closure guarantee:"));
        int adjusted = (int) (pessimismA * (arr - criticalEdges.get(0).getDelay() - clkskew) + pessimismB);
        System.out.printf(MessageGenerator.formatString("Critical path delay (ps):", adjusted));
        System.out.printf(MessageGenerator.formatString("Slack (ps):", (int)(timingRequirement - adjusted)));
        
        printPathDelayBreakDown(arr, criticalEdges, timingGraph.getTimingEdgeConnectionMap(), useRoutable, routingGraph);
    }
    
    /**
     * Gets and prints the given path from the TimingGraph
     */
    public void getSamplePathDelayInfo(List<String> verticesOfVivadoPath, Map<TimingEdge, Connection> timingEdgeConnctionMap, boolean routableBased, RouteNodeGraph routingGraph) {
        List<TimingEdge> edges = timingGraph.getTimingEdgeOfPath(verticesOfVivadoPath);
        short totalDelay = 0;
        for (TimingEdge edge : edges) {
            totalDelay += edge.getDelay();
        }
        System.out.println("Total delay: " + totalDelay);
        printPathDelayBreakDown(totalDelay, edges, timingEdgeConnctionMap, routableBased, routingGraph);
    }
    
    private void printPathDelayBreakDown(short arr, List<TimingEdge> criticalEdges, Map<TimingEdge, Connection> timingEdgeConnctionMap, boolean useRoutable, RouteNodeGraph routingGraph) {
        if (verbose) {
            System.out.println("\nTimingEdges:");
            int id = 0;
            for (TimingEdge e : criticalEdges) {
                System.out.println(String.format("%5d", id++) + "  " + e);
            }
        }
        printTimingPathInTable(criticalEdges, arr);
        if (routingGraph == null) return;
        if (!verbose) return;
        for (TimingEdge edge : criticalEdges) {
            if (timingEdgeConnctionMap.containsKey(edge)) {
                System.out.println(timingEdgeConnctionMap.get(edge));
                if (useRoutable) {
                    List<RouteNode> groups = timingEdgeConnctionMap.get(edge).getRnodes();
                    for (int iGroup = groups.size() -1; iGroup >= 0; iGroup--) {
                        System.out.println("\t " + groups.get(iGroup));
                    }
                } else {
                    List<Node> nodes = timingEdgeConnctionMap.get(edge).getNodes();
                    for (int iGroup = nodes.size() -1; iGroup >= 0; iGroup--) {
                        RouteNode rnode = routingGraph.getNode(nodes.get(iGroup));
                        if (rnode != null) {
                            System.out.println("\t " + rnode.getNode() + ", " + rnode.getIntentCode() + ", delay = " + (short) rnode.getDelay());
                        } else {
                            System.out.println("\t " + nodes.get(iGroup) + ", " + nodes.get(iGroup).getIntentCode() + ", delay = " + 0);
                        }
                    }
                }
            }
            System.out.println();
        }
    }
    
    private void printTimingPathInTable(List<TimingEdge> path, short arr) {
        System.out.println("\nDetail delays:");
        System.out.println("------------------------------------------------------------------------------");
        System.out.printf("%10s  %8s  %16s  %10s    %-25s\n",
                "Logic (ps)",
                "Net (ps)",
                "(intrasite (ps))",
                "Total (ps)",
                "Netlist Resource(s)"
                );
        System.out.printf("----------  --------------------------  ----------    ------------------------\n");
        for (TimingEdge e : path) {
            System.out.printf("%10d  %8d  %16d  %10d    %-25s\n",
                    (short) e.getLogicDelay(),
                    (short) e.getNetDelay(),
                    (short) e.getIntraSiteDelay(),
                    (short) e.getDelay(),
                    e.getSrc());
            if (e.getNet() != null && e.getNet().getName() != null) {
                System.out.printf("%50s  %-25s\n", "", "  net: " + e.getNet().getName());
            }
        }
        System.out.printf("----------  --------------------------  ----------    ------------------------\n");
        System.out.printf("%-38s  %10d\n", "Arrival time:", arr);
        System.out.println("------------------------------------------------------------------------------");
    }
    
    
    /**
     * Set the timing requirement of the design
     */
    public void setTimingRequirement() {
        setTimingRequirementPs(getDesignTimingRequirement(design) * 1000);
    }

    public void setTimingRequirementPs(float ps) {
        timingRequirement = ps;
    }

    public float getTimingRequirementPs() {
        return timingRequirement;
    }

    public static float getDesignTimingRequirement(Design design) {
        float treq = 0;
        
        ConstraintGroup[] constraintGroups = {ConstraintGroup.NORMAL, ConstraintGroup.LATE};
        //TODO CHECK which constraint to use. The maximum one as default?
        for (ConstraintGroup group : constraintGroups) {
            List<String> constraints = design.getXDCConstraints(group);
            for (String constraint : constraints) {
                if (constraint.contains("#")) {
                    constraint = constraint.substring(0, constraint.indexOf('#'));
                }
                if (constraint.contains("-period")) {
                    int startIndex = constraint.indexOf("-period");
                    treq = Math.max(treq, Float.parseFloat(constraint.substring(startIndex+7, startIndex+13)));
                }
            }
        }
        
        return treq;
    }
    
    /**
     * Calculates criticality for each connection.
     * @param connections Connections in question.
     * @param maxCriticality The maximum criticality value.
     * @param criticalityExponent The criticality exponent to use. For more information, please refer to the {@link RWRouteConfig} class file.
     */
    public void calculateCriticality(List<Connection> connections, float maxCriticality, float criticalityExponent) {
        for (Connection connection:connections) {
            connection.resetCriticality();
        }
        float maxRequired = timingGraph.superSink.getRequiredTime();
        for (Connection connection : connections) {
            connection.calculateCriticality(maxRequired, maxCriticality, criticalityExponent);
        }
    }

    /**
     * Builds the TimingModel and TimingGraph.
     * @return Indication of successful completion.
     */
    private boolean build(boolean isPartialRouting, Collection<Net> targetNets) {
        if (routerTimer != null) routerTimer.createRuntimeTracker("build timing model", "Initialization").start();
        timingModel.build();
        if (routerTimer != null) routerTimer.getRuntimeTracker("build timing model").stop();
        
        if (routerTimer != null) routerTimer.createRuntimeTracker("build timing graph", "Initialization").start();
        timingGraph.build(isPartialRouting, targetNets);
        if (routerTimer != null) routerTimer.getRuntimeTracker("build timing graph").stop();
        
        return postBuild();
    }

    private boolean postBuild() {
        if (routerTimer != null) routerTimer.createRuntimeTracker("post graph build", "Initialization").start();
        timingGraph.removeClockCrossingPaths();
        timingGraph.buildSuperGraphPaths();
        timingGraph.setOrderedTimingVertexLists();
        if (routerTimer != null) routerTimer.getRuntimeTracker("post graph build").stop();
        return true;
    }

    /**
     * Gets the TimingGraph object.
     * @return TimingGraph
     */
    public TimingGraph getTimingGraph() {
        return timingGraph;
    }

    /**
     * Gets the TimingModel object.
     * @return TimingModel
     */
    public TimingModel getTimingModel() {
        return timingModel;
    }

    /**
     * Gets the corresponding design used in creating this TimingManager.
     * @return Corresponding design used in creating this TimingManager.
     */
    public Design getDesign() {
        return design;
    }
    
    /**
     * Gets the corresponding device used in creating this TimingManager.
     * @return Corresponding device used in creating this TimingManager.
     */
    public Device getDevice() {
        return device;
    }
    
    public void setTimingEdgesOfConnections(List<Connection> connections) {
        timingGraph.setTimingEdgesOfConnections(connections);
    }
    
    
}

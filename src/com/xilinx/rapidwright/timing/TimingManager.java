/*
 * Copyright (c) 2019-2022, Xilinx, Inc.
 * Copyright (c) 2022-2024, Advanced Micro Devices, Inc.
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

import com.xilinx.rapidwright.design.ConstraintGroup;
import com.xilinx.rapidwright.design.Design;
import com.xilinx.rapidwright.design.Net;
import com.xilinx.rapidwright.design.NetTools;
import com.xilinx.rapidwright.design.SiteInst;
import com.xilinx.rapidwright.design.SitePinInst;
import com.xilinx.rapidwright.device.BELPin;
import com.xilinx.rapidwright.device.Device;
import com.xilinx.rapidwright.device.Node;
import com.xilinx.rapidwright.edif.EDIFHierPortInst;
import com.xilinx.rapidwright.rwroute.Connection;
import com.xilinx.rapidwright.rwroute.NetWrapper;
import com.xilinx.rapidwright.rwroute.RWRouteConfig;
import com.xilinx.rapidwright.rwroute.RouteNode;
import com.xilinx.rapidwright.rwroute.RouterHelper;
import com.xilinx.rapidwright.timing.delayestimator.DelayEstimatorBase;
import com.xilinx.rapidwright.timing.delayestimator.InterconnectInfo;
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

    /** Used to break the delay of the critical path down per node; null when no breakdown is wanted */
    private final DelayEstimatorBase<InterconnectInfo> estimator;

    /** Format of one line of that breakdown: its delay, what kind of hop it is, and the hop itself */
    private static final String DELAY_LINE_FORMAT = "\tdelay = %4d, %-18s, %s\n";

    /**
     * Default constructor: creates the TimingManager object, which the user needs to create for 
     * using our TimingModel, and then it builds the model.
     * @param design RapidWright Design object.
     */
    public TimingManager(Design design) {
        this.design = design;
        timingModel = new TimingModel(design.getDevice());
        timingGraph = new TimingGraph(design);
        timingModel.setTimingManager(this);
        timingGraph.setTimingManager(this);
        timingGraph.setTimingModel(timingModel);
        device = design.getDevice();
        // No critical path breakdown is printed through this constructor, since it leaves verbose off
        estimator = null;
        build(false, design.getNets());
    }
    
    public TimingManager(Design design,
                         RuntimeTrackerTree timer,
                         RWRouteConfig config,
                         ClkRouteTiming clkTiming,
                         Collection<Net> targetNets,
                         boolean isPartialRouting,
                         DelayEstimatorBase<InterconnectInfo> estimator) {
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
        this.estimator = estimator;
        build(isPartialRouting, targetNets);
    }
    
    /**
     * Updates the delay of nets after the cycle removal and delay-aware path merging.
     * @param illegalNets {@link NetWrapper} instances in question.
     * @param nodesDelays Stored nodes and their delay values.
     */
    public void updateIllegalNetsDelays(List<NetWrapper> illegalNets, Map<Node, Float> nodesDelays) {
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
    
    public void getCriticalPathInfo(Pair<Float, TimingVertex> maxDelayTimingVertex) {
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
        
        printPathDelayBreakDown(arr, criticalEdges);
    }

    private void printPathDelayBreakDown(short arr, List<TimingEdge> criticalEdges) {
        if (verbose) {
            System.out.println("\nTimingEdges:");
            int id = 0;
            for (TimingEdge e : criticalEdges) {
                System.out.println(String.format("%5d", id++) + "  " + e);
            }
        }
        printTimingPathInTable(criticalEdges, arr);
        if (!verbose) {
            return;
        }

        System.out.println();
        Map<TimingEdge, Connection> timingEdgeConnectionMap = timingGraph.getTimingEdgeConnectionMap();
        for (TimingEdge edge : criticalEdges) {
            Connection connection = timingEdgeConnectionMap.get(edge);
            if (connection != null) {
                System.out.println(connection);
                // Walk the path in source-to-sink order: the hop taken inside the source site,
                // then the inter-site routing, then the hop taken inside the sink site.
                // The intra-site hops are not held onto by the timing graph, so recover them here
                // rather than describe every edge of it just to print this one path.
                printIntraSiteDelayTerm(timingModel.getSourceIntraSiteDelayTerm(connection.getSource()));
                // Nodes are ordered sink-first, so the driver of nodes[i] is nodes[i + 1]
                List<Node> nodes = connection.getNodes();
                if (nodes.isEmpty()) {
                    if (connection.getNet().hasPIPs()) {
                        // A connection only carries its nodes when a router assigned them to it; walk
                        // the PIPs of its net to recover them otherwise. Only the connections of this
                        // one path are worth paying that for, hence not doing so for every connection.
                        nodes = NetTools.getNodesToSink(connection.getSink());
                    } else {
                        System.out.println("\t(no intersite routing)");
                    }
                }
                for (int iGroup = nodes.size() -1; iGroup >= 0; iGroup--) {
                    Node node = nodes.get(iGroup);
                    short delay = RouterHelper.computeNodeDelay(estimator, node);
                    if (iGroup + 1 < nodes.size()) {
                        // Account for the extra delay this node incurs from its driver, as the
                        // accumulated route delay of the connection does
                        delay += DelayEstimatorBase.getExtraDelay(node, DelayEstimatorBase.isLong(nodes.get(iGroup + 1)));
                    }
                    System.out.printf(DELAY_LINE_FORMAT, delay, node.getIntentCode(), node);
                }
                printIntraSiteDelayTerm(timingModel.getSinkIntraSiteDelayTerm(connection.getSink()));
                System.out.println();
            } else if (edge.getNet() != null) {
                // No Connection means RWRoute never routed this edge: it must be an intra-site
                // connection (e.g. ALUT6/O -> CARRY8/S[0]).
                short intraSiteDelay = (short) edge.getIntraSiteDelay();
                assert(edge.getNetDelay() == intraSiteDelay);
                // A direct connection crosses a site pin at each end, so describe those hops
                Pair<String,Short> sourceTerm = (edge.getFirstPin() != null) ?
                        timingModel.getSourceIntraSiteDelayTerm(edge.getFirstPin()) : null;
                Pair<String,Short> sinkTerm = (edge.getSecondPin() != null) ?
                        timingModel.getSinkIntraSiteDelayTerm(edge.getSecondPin()) : null;
                System.out.printf("net = %s, %s\n", edge.getNet(), edge);
                int recovered = (sourceTerm != null ? sourceTerm.getSecond() : 0)
                              + (sinkTerm != null ? sinkTerm.getSecond() : 0);
                if (recovered == intraSiteDelay) {
                    printIntraSiteDelayTerm(sourceTerm);
                    printIntraSiteDelayTerm(sinkTerm);
                } else {
                    // Otherwise the site pins do not describe the delay, which is then the single
                    // hop between the two cells the edge joins inside their site
                    printIntraSiteDelayTerm(getIntraSiteDelayTerm(edge, intraSiteDelay));
                }
                System.out.println();
            }
        }
    }
    
    /**
     * Prints one intra-site hop of the critical path, in the same format used for its nodes.
     * @param term The hop and its delay, as returned by
     *             {@link TimingModel#getSourceIntraSiteDelayTerm(SitePinInst)};
     *             nothing is printed when null.
     */
    private static void printIntraSiteDelayTerm(Pair<String,Short> term) {
        if (term == null) {
            return;
        }
        System.out.printf(DELAY_LINE_FORMAT, term.getSecond(), "(intrasite)", term.getFirst());
    }

    /**
     * Recovers the hop that the intra-site delay of an edge accounts for from the two cells the
     * edge joins, for the edges whose site pins do not describe it: those of a net that never
     * leaves its site, or that has no sink site pin to be described by.
     * @param edge Edge to describe.
     * @param intraSiteDelay Intra-site delay of that edge, in picoseconds.
     * @return The BEL pins the hop is between paired with that delay, falling back to a
     *         placeholder description when they cannot be recovered.
     */
    private Pair<String,Short> getIntraSiteDelayTerm(TimingEdge edge, short intraSiteDelay) {
        Pair<SiteInst,BELPin> source = getBELPinOfVertex(edge.getSrc());
        Pair<SiteInst,BELPin> sink = getBELPinOfVertex(edge.getDst());
        if (source != null && sink != null && source.getFirst() == sink.getFirst()) {
            String fromBelPin = describeBELPin(source.getSecond());
            String toBelPin = describeBELPin(sink.getSecond());
            // Only describe the hop once it is confirmed to be the one charged for
            Short delay = timingModel.lookupIntraSiteDelay(
                    source.getFirst().getSiteTypeEnum(), fromBelPin, toBelPin);
            if (delay != null && delay == intraSiteDelay) {
                return new Pair<>(fromBelPin + " -> " + toBelPin, intraSiteDelay);
            }
        }
        return new Pair<>("(BEL pins not recoverable)", intraSiteDelay);
    }

    /**
     * Finds where the cell pin that a vertex of the timing graph stands for has been placed.
     * @param vertex Vertex to look up.
     * @return The site instance and BEL pin it is placed onto, or null if the netlist does not
     *         name such a cell pin, or it is not placed onto a BEL pin.
     */
    private Pair<SiteInst,BELPin> getBELPinOfVertex(TimingVertex vertex) {
        // Vertices are named after the cell pin they stand for, so let the netlist resolve it
        EDIFHierPortInst portInst = design.getNetlist().getHierPortInstFromName(vertex.getName());
        if (portInst == null) {
            return null;
        }
        Pair<SiteInst,BELPin> belPin = portInst.getRoutedBELPin(design);
        return (belPin == null || belPin.getSecond() == null) ? null : belPin;
    }

    /**
     * Names a BEL pin the way the intra-site delay model does.
     * @param belPin BEL pin to name.
     * @return That BEL pin named "&lt;BEL&gt;/&lt;pin&gt;".
     */
    private static String describeBELPin(BELPin belPin) {
        return belPin.getBELName() + "/" + belPin.getName();
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
        // setOrderedTimingVertexLists() uses a topological-order iterator which
        // throws on cycles. Break any cycles first (analog of Vivado's automatic
        // arc disabling for latch feedback / combinational loops / etc.).
        timingGraph.breakCycles();
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

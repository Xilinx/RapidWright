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

import java.io.FileNotFoundException;
import java.io.PrintStream;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;

import com.xilinx.rapidwright.design.Cell;
import com.xilinx.rapidwright.design.Design;
import com.xilinx.rapidwright.design.DesignTools;
import com.xilinx.rapidwright.design.Net;
import com.xilinx.rapidwright.design.SiteInst;
import com.xilinx.rapidwright.design.SitePinInst;
import com.xilinx.rapidwright.design.tools.LUTTools;
import com.xilinx.rapidwright.device.BEL;
import com.xilinx.rapidwright.device.BELPin;
import com.xilinx.rapidwright.edif.EDIFCell;
import com.xilinx.rapidwright.edif.EDIFCellInst;
import com.xilinx.rapidwright.edif.EDIFHierCellInst;
import com.xilinx.rapidwright.edif.EDIFHierPortInst;
import com.xilinx.rapidwright.edif.EDIFNet;
import com.xilinx.rapidwright.edif.EDIFPortInst;
import com.xilinx.rapidwright.edif.EDIFPropertyValue;
import com.xilinx.rapidwright.edif.EDIFTools;
import com.xilinx.rapidwright.rwroute.Connection;
import com.xilinx.rapidwright.rwroute.RouterHelper;
import com.xilinx.rapidwright.util.Pair;
import com.xilinx.rapidwright.util.RuntimeTrackerTree;

import org.jgrapht.GraphPath;
import org.jgrapht.alg.shortestpath.AllDirectedPaths;
import org.jgrapht.alg.shortestpath.BellmanFordShortestPath;
import org.jgrapht.alg.shortestpath.KShortestSimplePaths;
import org.jgrapht.graph.DefaultDirectedWeightedGraph;
import org.jgrapht.graph.GraphWalk;
import org.jgrapht.traverse.TopologicalOrderIterator;

/**
 * A TimingGraph is an acyclic weighted-directed graph representing logic delays and physical net 
 * delays based on analyzing the circuits within {@link Design} objects.
 */
public class TimingGraph extends DefaultDirectedWeightedGraph<TimingVertex, TimingEdge> {

    private static final long serialVersionUID = 7072333598844760465L;
    public boolean debug = false;
    public boolean verbose = false;
    private TimingModel timingModel;
    private TimingManager timingManager;
    private HashSet<GraphPath<TimingVertex, TimingEdge>> graphPathHashSet;
    HashMap<EDIFCellInst, String> hierCellInstMap;
    DelayModel intrasiteAndLogicDelayModel;
    PrintStream graphVizPrintStream;
    HashMap<String, EDIFCellInst> myCellMap;
    Design design;
    ArrayList<EDIFHierCellInst> set;
    private HashMap<String, TimingVertex> safeVertexCheck = new HashMap<>();
    static HashSet<String> unisimFlipFlopTypes;
    static HashSet<String> ramTypes;

    /** A map from TimingEdges to connections */
    private Map<TimingEdge, Connection> timingEdgeConnectionMap = new HashMap<>();
    /** Mapping between each sink {@link SitePinInst} instance and its associated {@link TimingEdge} instances */
    private Map<SitePinInst, List<TimingEdge>> sinkSitePinInstTimingEdges = new HashMap<>();
    /** Mapping between a logic pin and a physical pin recognized by the timing graph builder */
    private Map<EDIFHierPortInst, SitePinInst> edifHPortMap = new HashMap<>();
    private List<TimingVertex> orderedTimingVertices = new ArrayList<>();
    private List<TimingVertex> reversedOrderedTimingVertices = new ArrayList<>();
    private ClkRouteTiming clkRouteTiming = null;
    private RuntimeTrackerTree routerTimer;
    
    /** DSP timing data related variables */
    private String dspTimingDataFolder;
    private boolean dspTimingDataFolderWarning;
    private boolean dspTimingFileExistenceWarning;
    private Map<String, DSPTimingData> dspNameDataMapping = new HashMap<>();
    private Set<DSPTimingData> dspTimingDataSet = new HashSet<>();
    
    static {
        
        unisimFlipFlopTypes = new HashSet<>();
        // build a static set containing the names of Flops collection for the method: 
        // "stringContainsNameOfFlipFlop"
        unisimFlipFlopTypes.add("FDSE");
        unisimFlipFlopTypes.add("FDPE");
        unisimFlipFlopTypes.add("FDRE");
        unisimFlipFlopTypes.add("FDCE");
    }
    
    static {
        ramTypes = new HashSet<>();
        ramTypes.add("RAMB18E2");
        ramTypes.add("RAMB36E2");
    }
    
    /**
     * Creates a TimingGraph for the purpose of report_timing based on analyzing nets within a 
     * {@link Design} object.
     * @param design The RW {@link Design} object
     *
     */
    public TimingGraph(Design design) {
        super(TimingEdge.class);
        this.design = design;
    }
    
    
    public TimingGraph(Design design, RuntimeTrackerTree timer, ClkRouteTiming clkTiming, String dspTimingDataFolder) {
        this(design);
        routerTimer = timer;
        clkRouteTiming = clkTiming;
        dspTimingDataFolder = dspTimingDataFolder;
    }

    /**
     * Builds the TimingGraph based on analyzing nets within a {@link Design} object.
     */
    public void build(boolean isPartialRouting, Collection<Net> targetNets) {
        if (timingModel == null) {
            throw new RuntimeException("Error: The TimingModel is not properly set for the "
                    + "TimingGraph prior to building.");
        }
        String seriesName = design.getDevice().getSeries().name().toLowerCase();
        intrasiteAndLogicDelayModel = DelayModelBuilder.getDelayModel(seriesName);

        if (routerTimer != null) routerTimer.createRuntimeTracker("determine logic dly", "build timing graph").start();
        myCellMap = design.getNetlist().generateCellInstMap();
        if (!isPartialRouting) {
            determineLogicDelaysFromEDIFCellInsts(myCellMap);
        } else {
            determineLogicDelaysFromEDIFCellInsts(generateCellMapOfNets(targetNets));
        }
        if (routerTimer != null) routerTimer.getRuntimeTracker("determine logic dly").stop();
        
        if (routerTimer != null) routerTimer.createRuntimeTracker("add net dly edges", "build timing graph").start();
        // for (Net net : design.getNets()) {
        for (Net net : targetNets) {
            if (net.isClockNet()) continue;//this is for getting rid of the problem in addNetDelayEdges() of clock net
            if (net.isStaticNet()) continue;
            addNetDelayEdges(net);
        }
        
        addTimingEdgesOfNets(isPartialRouting, targetNets);
        
        if (routerTimer != null) routerTimer.getRuntimeTracker("add net dly edges").stop();
    }
    
    private void addTimingEdgesOfNets(boolean isPartialRouting, Collection<Net> assignedNets) {
        for (Net net : assignedNets) {
            if (net.isClockNet()) continue;//this is for getting rid of the problem in addNetDelayEdges() of clock net
            if (net.isStaticNet()) continue;
            if (!isPartialRouting || !net.hasPIPs()) {
                addNetDelayEdges(net);
            }
        }
    }
    
    public void populateHierCellInstMap() {
        hierCellInstMap = new LinkedHashMap<>();
        EDIFCellInst top = design.getNetlist().getTopCellInst();
        hierCellInstMap.put(top, top.getName());
        Queue<EDIFHierCellInst> q = new LinkedList<>();
        q.add(design.getNetlist().getTopHierCellInst());
        while (!q.isEmpty()) {
            EDIFHierCellInst i = q.poll();
            for (EDIFCellInst child : i.getInst().getCellType().getCellInsts()) {
                String fullName = "";
                if (!i.isTopLevelInst()) {
                    fullName = i.getFullHierarchicalInstName();// + EDIFTools.EDIF_HIER_SEP + child.getName();
                }
                EDIFHierCellInst newCell = i.getChild(child);
                if (newCell.getInst().getCellType().isPrimitive()) {
                    set.add(newCell);
                } else {
                    if (!set.contains(newCell))
                        set.add(newCell);
                    q.add(newCell);
                }
                hierCellInstMap.put(child, fullName);
            }
        }
    }
    
    /**
     * Gets a map of hierarchical names to EDIFCellInsts of target nets.
     * @param nets Nets in question.
     * @return A map of hierarchical names to EdifCellInstances that use primitives in the library.
     */
    private Map<String, EDIFCellInst> generateCellMapOfNets(Collection<Net> nets) {
        Map<String, EDIFCellInst> partialCellMap = new HashMap<>();
        Set<String> keys = new HashSet<>();
        for (Net n : nets) {
            if (n.isClockNet() || n.isStaticNet() || n.hasPIPs()) continue;
            if (!RouterHelper.isRoutableNetWithSourceSinks(n)) continue;
            List<EDIFHierPortInst> ehportInsts = design.getNetlist().getPhysicalPins(n.getName());
            for (EDIFHierPortInst eportInst : ehportInsts) {
                keys.add(eportInst.getFullHierarchicalInstName());
            }
        }
        
        for (String fullHierInstName : keys) {
            EDIFCellInst edifCellInst = myCellMap.get(fullHierInstName);
            if (edifCellInst == null) {
                System.out.println("WARNING: Null EDIFCellInst under name " + fullHierInstName);
                continue;
            }
            partialCellMap.put(fullHierInstName, edifCellInst);
        }
        return partialCellMap;
    }

    /**
     * Gets the delay/weight of a GraphPath
     * @param graphPath A timing path within the timingGraph between a source flop and sink flop.
     * @return The weight of the GraphPath, which is the delay of the path.
     */
    public float getDelay(GraphPath<TimingVertex, TimingEdge> graphPath) {
        return (float)graphPath.getWeight();
    }

    /**
     * Sets the same specified timing requirement on the TimingGraph on GraphPaths that have been 
     * predetermined.
     * @param requirement The required time in picoseconds at the sink of the path.
     */
    public void setTimingRequirement(float requirement) {
        for (GraphPath<TimingVertex, TimingEdge> path : getGraphPaths()) {
            setTimingRequirement(requirement, path);
        }
        computeArrivalTimes();
    }
    
    /**
     * Creates and Sets the lists of ordered TimingVertices
     */
    public void setOrderedTimingVertexLists() {
        TopologicalOrderIterator<TimingVertex, TimingEdge> orderIterator = new TopologicalOrderIterator<>(this);
        while (orderIterator.hasNext()) {
            TimingVertex v = orderIterator.next();
            orderedTimingVertices.add(v);
        }
        reversedOrderedTimingVertices = getReversedOrder();
    }
    
    /**
     * Computes/recomputes the arrival time stored at each vertex of the graph using TopologicalOrderIterator
     */
    public void computeArrivalTimesTopologicalOrder() {
        if (orderedTimingVertices.isEmpty()) {
            setOrderedTimingVertexLists();
        }
        for (TimingVertex v : orderedTimingVertices) {
            Set<TimingEdge> outgoings = outgoingEdgesOf(v);
            if (inDegreeOf(v) == 0) v.setArrivalTime(0);
            for (TimingEdge e : outgoings) {
                float arrival = e.getSrc().getArrivalTime() + e.getDelay();
                e.getDst().setMaxArrivalTime(arrival, v);
            }
        }
    }

    /**
     * Get the clock region that the cell pin resides in
     * @param cellPinName, the name of the cell pin
     * @param design
     * @return clock region name
     */
    public static String getClockRegionOfCellPin(String cellPinName, Design design) {
        int indexOfLastSlash = cellPinName.lastIndexOf("/");
        String cellName = cellPinName.substring(0, indexOfLastSlash);
        Cell cell = design.getCell(cellName);
        if (cell == null) {
            System.out.println("NULL CELL FOUND FOR " + cellPinName);
            return null;
        }
        return cell.getTile().getClockRegion().getName();
    }
    
    /**
     * Set the required time of each timing vertex in the graph
     * @param requirement, the required time of the design
     */
    public void setTimingRequirementTopologicalOrder(float requirement) {
        if (reversedOrderedTimingVertices.isEmpty()) {
            reversedOrderedTimingVertices = getReversedOrder();
        }
        for (TimingVertex v : reversedOrderedTimingVertices) {
            Set<TimingEdge> incomings = incomingEdgesOf(v);
            if (outDegreeOf(v) == 0) {
                if (v.equals(superSink)) {
                    v.setMinRequiredTime(requirement);
                } else {
                    v.setMinRequiredTime(Short.MAX_VALUE);//NOTE: there are dangling timing vertices not connected to super sink
                }
            }
            
            for (TimingEdge e : incomings) {
                float remainingRequiredTime = e.getDst().getRequiredTime() - e.getDelay();
                e.getSrc().setMinRequiredTime(remainingRequiredTime);
            }
        }
    }
    
    /**
     * Reset the required and arrival time to be null
     */
    public void resetRequiredAndArrivalTime() {
        for (TimingVertex v : vertexSet()) {
            v.resetArrivalTime();
            v.resetRequiredTime();
            v.setPrev(null);
        }
    }
    
    /**
     * Get the maximum delay, i.e., the maximum arrival time, and corresponding timing path sink of the design
     */
    public Pair<Float, TimingVertex> getMaxDelay() {
        return new Pair<>(superSink.getArrivalTime(), superSink);
    }
    
    private List<TimingVertex> getReversedOrder() {
        List<TimingVertex> reversedOrderedTimingVertices = new ArrayList<>();
        reversedOrderedTimingVertices.addAll(orderedTimingVertices);

        Collections.reverse(reversedOrderedTimingVertices);
        return reversedOrderedTimingVertices;
    }
    
    /**
     * Get a list of timing edges consisting of the critical path
     * @param maxV The timing vertex with the maximum arrival time
     * @return A list of timing edges consisting of the critical path
     */
    public List<TimingEdge> getCriticalTimingEdgesInOrder(TimingVertex maxV) {
        List<TimingEdge> criticalTimingEdges = new ArrayList<>();
        TimingVertex timingVertex = maxV;
        
        while (incomingEdgesOf(timingVertex).size() != 0) {
            TimingEdge e = getCriticalSourceTimingVertex(timingVertex);
            if (e == null) break;
            timingVertex = e.getSrc();
            criticalTimingEdges.add(e);
        }
        
        Collections.reverse(criticalTimingEdges);
        return criticalTimingEdges;
    }
    
    private TimingEdge getCriticalSourceTimingVertex(TimingVertex sinkV) {
        Set<TimingEdge> incomingEdges = incomingEdgesOf(sinkV);
        
        for (TimingEdge e : incomingEdges) {
            if (e.getSrc().equals(sinkV.getPrev())) {
                return e;
            }
        }
        return null;
    }
    
    /**
     * Finds the given critical path in the timing graph and reports the delay detail
     * @param verticesNames, the given TimingVertices
     * @return A list of TimingEdges associated with the given TimingVertices
     */
    // output vertices only
    // return null if path not found in the graph
    public List<TimingEdge> getTimingEdgeOfPath(List<String> verticesNames) {
        boolean verbose = true;
        
        if (verbose) System.out.println("\nGET DELAY OF GIVEN PATH:\n");
        List<TimingVertex> vertices = new ArrayList<>();
        for (String str : verticesNames) {
            TimingVertex v = safeVertexCheck.get(str);
            if (v != null) {
                vertices.add(v);
            } else {
                System.err.println("graph does not contain: " + str);
            }
        }
        if (verbose) System.out.println(vertices.size() + " / " + verticesNames.size() + " vertices from the path found in TimingGraph");
        List<TimingEdge> edges = new ArrayList<>();
        // Q -> O -> O -> --- -> D
        for (int i = 0; i < vertices.size() - 1; i++) {
            if (verbose) {
                if (i > 0) {//skip superSource outgoing timing edges printout as there are too many
                    System.out.println(vertices.get(i) + " outgoing timing eges:\n " + outgoingEdgesOf(vertices.get(i)));
                }
            }
            boolean found = false;
            for (TimingEdge e : outgoingEdgesOf(vertices.get(i))) {
                if (found) {
                    break;
                }
                if (outgoingEdgesOf(e.getDst()).size() == 0)
                    System.out.println(e.getDst() + " no outgoing edges, delay =  " + e.getDelay());
                for (TimingEdge nexte : outgoingEdgesOf(e.getDst())) {
                    // this means the hops between adjacent pins could be more than two
                    // otherwise, it will report as NULL TimingEdge found
                    if (nexte.getDst().equals(vertices.get(i+1))) {
                        if (verbose) System.out.println("TimingEdge found between: \n  " + vertices.get(i) + ", " + vertices.get(i+1));
                        edges.add(e);
                        edges.add(nexte);
                        found = true;
                        break;
                    }
                }
                if (e.getDst().equals(vertices.get(i+1))) {
                    edges.add(e);
                    found = true;
                    break;
                }
            }
            if (!found) {
                System.out.println("NULL TimingEdge found between: \n  " + vertices.get(i) + ", " + vertices.get(i+1));
            }
            if (verbose) System.out.println();
        }
        return edges;
    }
        
    /**
     * Sets the same specified timing requirement on a specified GraphPath.
     * @param requirement The required time in picoseconds at the sink of the path.
     * @param graphPath The GraphPath receiving this required time in picoseconds at the sink of the
     * path.
     */
    public void setTimingRequirement(float requirement, GraphPath<TimingVertex, TimingEdge> graphPath) {
        List<TimingEdge> edgeList = (List<TimingEdge>)graphPath.getEdgeList();
        float remainingRequiredTime = requirement;
        int sz = edgeList.size();
        for (int i=sz-1; i>=0; i-- ) {
            TimingEdge e = edgeList.get(i);
            e.getDst().setMinRequiredTime(remainingRequiredTime);
            remainingRequiredTime = remainingRequiredTime - e.getDelay();
            if (inDegreeOf(e.getSrc()) ==0) {
                e.getSrc().setMinRequiredTime(remainingRequiredTime);
            }
        }
    }

    /**
     * Gets the slack from a specified GraphPath at its source.
     * @param graphPath The GraphPath that is being checked for the slack.
     * @return The slack as a Float, which can be null if not yet set.
     */
    public Float getSlack(GraphPath<TimingVertex, TimingEdge> graphPath) {
        Float result = null;
        for (TimingEdge timingEdge: (List<TimingEdge>)graphPath.getEdgeList()) {
            if (result == null) {
                result = timingEdge.getSrc().getSlack();
            } else {
                result += timingEdge.getSrc().getSlack();
            }
        }
        return result;
    }

    /**
     * Gets the required time from a specified GraphPath at its source.
     * @param graphPath The GraphPath that is being checked for the required time.
     * @return The required time as a Float, which can be null if not yet set.
     */
    public float getRequiredTime(GraphPath<TimingVertex, TimingEdge> graphPath) {
        float result = 0;
        List<TimingEdge> eList = (List<TimingEdge>)graphPath.getEdgeList();
        result = eList.get(eList.size()-1).getDst().getRequiredTime();
        return result;
    }

    /**
     * Inserts a GraphPath into the TimingGraph.
     * @param path The GraphPath that is being inserted.
     * @return Boolean indication of success.
     */
    public boolean addTimingPath(GraphPath<TimingVertex, TimingEdge> path) {
        boolean result = true;
        List<TimingEdge> edges = path.getEdgeList();
        for (TimingEdge e : edges) {
            if (!containsEdge(e)) {
                result &= safeAddEdge(e.getSrc(), e.getDst(), e);
                setEdgeWeight(e, e.getDelay());
            }
        }
        return result;
    }

    /**
     * Removes a GraphPath from the TimingGraph.
     * @param path The GraphPath that is being removed.
     * @return Boolean indication of success.
     */
    public boolean removeTimingPath(GraphPath<TimingVertex, TimingEdge> path) {
        boolean result = false;
        List<TimingEdge> edges = path.getEdgeList();
        boolean nofanout = true;
        for (TimingEdge e : edges) {
            if (outDegreeOf(getEdgeSource(e)) != 1 || inDegreeOf(getEdgeTarget(e)) != 1 )
                nofanout = false;
        }
        if (nofanout) {
            for ( TimingEdge e : edges) {
                removeEdge(e);
            }
            result = true;
        }
        if (result)
            graphPathHashSet.remove(path);
        return result;
    }

    /**
     * Finds and returns the value of the worst slack from the TimingGraph.
     * @return The value of the worst slack found in the TimingGraph, which might be null if slack 
     * hasn't been pre-computed.
     */
    public Float getWorstSlack() {
        Float result = Float.valueOf(1<<20);

        for (TimingVertex v : vertexSet()) {
            Float slack = v.getSlack();
            if (slack != null &&
                    outDegreeOf(v) == 0 &&
                    v.getSlack() < result)
                result = v.getSlack();
        }
        return result;
    }

    /**
     * Finds and returns the path from the TimingGraph having maximum delay.
     * @return The GraphPath that is the critical path found in the TimingGraph, which might be null
     * if the GraphPaths haven't been pre-computed by calling {@link #buildGraphPaths()}.
     */
    public GraphPath<TimingVertex, TimingEdge> getMaxDelayPath() {
        GraphPath<TimingVertex, TimingEdge> result = null;
        float maxWeight = 0;
        computeArrivalTimesTopologicalOrder();
        if (graphPathHashSet == null) {
            buildGraphPaths(1);
        }
        for (GraphPath<TimingVertex, TimingEdge> p : graphPathHashSet) {
            float w = (float)p.getWeight();
            if (Math.abs(w) > maxWeight) {
                result = p;
                maxWeight = Math.abs(w);
            }
        }
        return result;
    }

    /**
     * This creates a GraphViz library dot file representation of the TimingGraph.  Might be useful 
     * for visualizing tiny designs.  The resulting digraph() might be too large to render depending
     * on the size of design.
     * @param dotFileName The output filename for the writing the .dot file.
     */
    public void generateGraphvizDotVisualization(String dotFileName) {
        graphVizPrintStream = null;
        //logFOS = new FileOutputStream(logFile);
        try {
            graphVizPrintStream = new PrintStream(dotFileName);
        } catch (FileNotFoundException e1) {
            e1.printStackTrace();
        }
        
        computeArrivalTimes();
        computeSlacks();
        graphVizPrintStream.println("digraph {");
        graphVizPrintStream.println("rankdir=LR;");
        for (TimingEdge e : edgeSet()) {
            if (e != null) {
                setEdgeWeight(e, e.getDelay());
            }
            graphVizPrintStream.println(e.toGraphvizDotString() + ";");
        }
        graphVizPrintStream.println("}");
        graphVizPrintStream.close();
    }

    /** Returns a set of built GraphPaths.
     * @return The HashSet of current set of GraphPaths that were prebuilt by running buildGraphPaths()
     */
    public HashSet<GraphPath<TimingVertex, TimingEdge>> getGraphPaths() {
        if (graphPathHashSet == null)
            buildGraphPaths();
        return graphPathHashSet;
    }


    /** Builds and returns a set of GraphPaths.
     * @return A List of GraphPaths that were just built by this command
     */
    public List<GraphPath<TimingVertex, TimingEdge>> buildGraphPaths() {
        return buildGraphPaths(0);
    }

    static Set<String> bramPinsToSuperSink;
    static {
        bramPinsToSuperSink = new HashSet<>();
        bramPinsToSuperSink.add("ADDRARDADDR");//EN, ADDR, WE, DIN
        bramPinsToSuperSink.add("ADDRBWRADDR");
        bramPinsToSuperSink.add("ADDRENA");
        bramPinsToSuperSink.add("ADDRENB");
        bramPinsToSuperSink.add("CASDOMUXA");
        bramPinsToSuperSink.add("CASDOMUXB");
        bramPinsToSuperSink.add("CASDOMUXEN_A");
        bramPinsToSuperSink.add("CASDOMUXEN_B");
        bramPinsToSuperSink.add("CASOREGIMUXA");
        bramPinsToSuperSink.add("CASOREGIMUXB");
        bramPinsToSuperSink.add("CASOREGIMUXEN_A");
        bramPinsToSuperSink.add("CASOREGIMUXEN_B");
        bramPinsToSuperSink.add("DINADIN");
        bramPinsToSuperSink.add("DINBDIN");
        bramPinsToSuperSink.add("ENARDEN");
        bramPinsToSuperSink.add("ENBWREN");
        bramPinsToSuperSink.add("WEA");
        bramPinsToSuperSink.add("WEBWE");
        // that CASDIN goes to FF or not depends on the MUX
        // CASOREGIMUX == 1 (vcc) { CASDOMUX == 0 (gnd), do not connect, otherwise connect to superSink}
        // CASOREGIMUX && CASDOMUX from signals (not vcc/gnd), connect to superSink
    }
    
    private boolean shouldBRAMInputConnectToSuperSink(Cell cell, String cellPinName) {
        boolean shouldConnect = false;
        boolean debug = false;
        
        int indexOfLastSlash = cellPinName.lastIndexOf("/");
        int length = cellPinName.length();
        String pinName = cellPinName.substring(indexOfLastSlash + 1, length);
        String portString =pinName;
        if (pinName.contains("[")) {
            portString = pinName.substring(0, pinName.lastIndexOf("["));
        }
        
        if (bramPinsToSuperSink.contains(portString)) {
            shouldConnect = true;
        } else if (pinName.startsWith("CASDINA") || pinName.startsWith("CASDINPA")) {
            // check CASOREGIMUXA and CASDOMUXA
            shouldConnect = shouldCASCADINConnectToSuperSink(cell, "CASOREGIMUXA", "CASDOMUXA");
        } else if (pinName.startsWith("CASDINB") || pinName.startsWith("CASDINPB")) {
            // check CASOREGIMUXB and CASDOMUXB
            shouldConnect = shouldCASCADINConnectToSuperSink(cell, "CASOREGIMUXB", "CASDOMUXB");
        }
        
        if (debug && shouldConnect) System.out.println(cellPinName + ", should connect? " + shouldConnect);
        //TODO add setup time of BRAM
        return shouldConnect;
    }
    
    private boolean shouldCASCADINConnectToSuperSink(Cell cell, String oregimux, String domux) {
        boolean shouldConnect = false;
        String siteWireI = cell.getSiteWireNameFromLogicalPin(oregimux);
        Net netIMUX = cell.getSiteInst().getNetFromSiteWire(siteWireI);
        String siteWireO = cell.getSiteWireNameFromLogicalPin(domux);
        Net netOMUX = cell.getSiteInst().getNetFromSiteWire(siteWireO);
        if (netIMUX.equals(design.getVccNet())) {
            if (!netOMUX.equals(design.getGndNet())) {
                shouldConnect = true;
            }
        }
        if (!netIMUX.isStaticNet() && !netOMUX.isStaticNet()) {
            shouldConnect = true;
        }
        return shouldConnect;
    }


    /**
     * The superSource and superSink are used to consolidate all timing start and end points, respectively.
     * They simplify timing computations and data to compute clock skew will be annotated on the edges from superSource and to superSink.
     * For example, a FF will be represented both as a start and end points using two vertices, says source and sink.
     * The superSource will have no input, but fanout to all the start points nodes.
     * The superSink will have fanin from all the end point nodes and have no output.
     */
    public TimingVertex superSource = null;
    public TimingVertex superSink = null;
    
    /**
     * Connects the sources and sinks of timing paths to a superSource and a superSink, respectively
     */
    public void buildSuperGraphPaths() {
        Set<TimingVertex> sources = new LinkedHashSet<>();
        Set<TimingVertex> sinks = new LinkedHashSet<>();  
        for (TimingVertex s1 : vertexSet()) {
            if (inDegreeOf(s1) == 0 && outDegreeOf(s1) > 0 ) {
                sources.add(s1);
            } else if (s1.getFlopInput() && outDegreeOf(s1) == 0 && inDegreeOf(s1) > 0) {
                sinks.add(s1);
            } else if (s1.getName().endsWith("VCLK")) {// for DSP
                sinks.add(s1);
            } else {
                // All pins to "D" of BRAM must go to super sink, if it goes somewhere else, something is wrong
                String cellPinName = s1.getName();
                int indexOfLastSlash = cellPinName.lastIndexOf("/");
                String cellName = cellPinName.substring(0, indexOfLastSlash);
                EDIFCellInst mycellInst = myCellMap.get(cellName);
                Cell cell = design.getCell(cellName);
                if (cell != null && mycellInst.getCellType() != null) {
                    if (mycellInst.getCellType().getName().startsWith("RAMB")) {
                        if (shouldBRAMInputConnectToSuperSink(cell, cellPinName)) {
                            sinks.add(s1);
                        }
                    }
                }
            }
        }      
        if (superSource == null) {
            superSource = new TimingVertex("superSource");
            superSink = new TimingVertex("superSink");
        }
        if (!vertexSet().contains(superSource))
            safeAddVertex(superSource);
        if (!vertexSet().contains(superSink))
            safeAddVertex(superSink);
        
        // superSource has initial arrival times as zero, do not need to be set again
        // add clk skew here
        for (TimingVertex s : sources) {
            TimingEdge e = new TimingEdge(this, superSource, s);
            addEdge(superSource, s, e);
        }
        for (TimingVertex s : sinks) {
            TimingEdge e = new TimingEdge(this, s, superSink);
            addEdge(s, superSink, e);
        }
    }
    
    private List<GraphPath<TimingVertex, TimingEdge>> buildGraphPaths(int n) {
        graphPathHashSet = new LinkedHashSet<>();
        Set<TimingVertex> sources = new LinkedHashSet<>();
        Set<TimingVertex> sinks = new LinkedHashSet<>();
        List<GraphPath<TimingVertex, TimingEdge>> result = new ArrayList<>();
        for (TimingVertex s1 : vertexSet()) {
            if (inDegreeOf(s1) == 0 && outDegreeOf(s1) > 0 ) {
                //if (inDegreeOf(s1) == 0 && outDegreeOf(s1) > 0 || s1.getFlopOutput()) {
                //if (s1.getFlopOutput()) {
                sources.add(s1);
                //} else if (outDegreeOf(s1) == 0 && inDegreeOf(s1) > 0 ) {
            } else if (s1.getFlopInput() && outDegreeOf(s1) == 0 && inDegreeOf(s1) > 0) {
                sinks.add(s1);
            }
        }
        List<GraphPath<TimingVertex, TimingEdge>> paths = new LinkedList<>();

        boolean getAllPaths = false;
        if (n == 0)
            getAllPaths = true;

        if (getAllPaths) {
            AllDirectedPaths<TimingVertex, TimingEdge> allAlg = new AllDirectedPaths<>(this);
            Integer maxPathLen = 1000;
            paths = allAlg.getAllPaths(sources, sinks, true, maxPathLen);
        } else {
            
            for (TimingEdge e : edgeSet()) {
                setEdgeWeight(e,-1*e.getDelay());
            }
            
            if (superSource == null) {
                superSource = new TimingVertex("superSource");
                superSink = new TimingVertex("superSink");
            }
            if (!vertexSet().contains(superSource))
                safeAddVertex(superSource);
            if (!vertexSet().contains(superSink))
                safeAddVertex(superSink);
            
            for (TimingVertex s : sinks) {
                TimingEdge e = new TimingEdge(this, s, superSink);
                addEdge(s, superSink, e);
            }
            boolean bellmanFord = true;
            if (bellmanFord) {
                BellmanFordShortestPath<TimingVertex, TimingEdge> bellmanFordShortestPath =
                        new BellmanFordShortestPath<TimingVertex, TimingEdge>(this);
                GraphPath<TimingVertex, TimingEdge> path = bellmanFordShortestPath.getPath(superSource, superSink);
                for (TimingEdge e : edgeSet()) {
                    setEdgeWeight(e, e.getDelay());
                }
                double weight = 0;
                if (path != null) {
                    for (TimingEdge e : path.getEdgeList()) {
                        weight += e.getDelay();
                    }
                    ((GraphWalk<TimingVertex, TimingEdge>) path).setWeight(weight);
                    paths.add(path);
                }
            } else {
                KShortestSimplePaths<TimingVertex, TimingEdge> kShortestSimplePaths =
                        new KShortestSimplePaths<>(this);
                List<GraphPath<TimingVertex, TimingEdge>> shortest = kShortestSimplePaths.getPaths(superSource, superSink, n);
                for (GraphPath<TimingVertex, TimingEdge> path : shortest) {
                    for (TimingEdge e : path.getEdgeList()) {
                        setEdgeWeight(e, -1 * e.getDelay());
                    }
                    double weight = path.getWeight();
                    ((GraphWalk<TimingVertex, TimingEdge>)path).setWeight(-1*weight);
                }
                paths.addAll(shortest);
            }
        }
        
        for (GraphPath<TimingVertex, TimingEdge> path : paths) {
            //System.out.println("Path between: src:" + s1 + " and sink:" + s2 + " is: " + path + " w:" + path.getWeight());
            result.add(path);
            graphPathHashSet.add(path);
        }
        
        return result;
    }

    /**
     * Computes/recomputes the arrival times stored at the vertices of the graph based on the edges
     */
    public void computeArrivalTimes() {
        for (GraphPath<TimingVertex, TimingEdge> p : graphPathHashSet) {
            float arrival = 0;
            for (TimingEdge e : (List<TimingEdge>) p.getEdgeList()) {
                arrival += e.getDelay();
                e.getDst().setMaxArrivalTime(arrival);// should have a check on arrival time to set the max one
                if (inDegreeOf(e.getSrc())==0) {
                    e.getSrc().setMaxArrivalTime(0);
                }
            }
        }
    }
    
    /**
     * Computes/recomputes the slack stored at vertices of the graph based on comparing required 
     * times and arrival times.
     */
    public void computeSlacks() {
        for (TimingVertex v : vertexSet()) {
            v.setSlack(v.getRequiredTime() - v.getArrivalTime());
        }
    }
    
    /**
     * This helper function is used to avoid duplicate insertions of vertices within the TimingGraph.  
     * To avoid duplicates, the helper function first checks if a vertex with the same name already 
     * exists within the TimingGraph.  If so, it will return a reference to the existing vertex.  If
     * not, it will insert the specified TimingVertex v and return a reference to v.
     * @param v TimingVertex to be inserted into the TimingGraph.
     * @return A reference to TimingVertex v if there is not a vertex with the same name already 
     * inserted, otherwise, it returns a reference to the existing TimingVertex with same name as v.
     */
    TimingVertex safeAddVertex(TimingVertex v) {
        TimingVertex result = v;
        TimingVertex test = safeVertexCheck.get(v.getName());
        if  (v != null &&  v.getName() != null && test == null) {
            addVertex(v);
            safeVertexCheck.put(v.getName(), v);
            result = v;
        } else
            result = test;

        return  result;
    }

    /**
     * This helper function is used to avoid duplicate insertions of edges within the TimingGraph.  
     * To avoid duplicates, the helper function first checks if an edge with the same first vertex 
     * and same second vertex already exist within the TimingGraph.  If so, it will return a 
     * reference to the existing edge.  If not, it will insert the specified TimingEdge e and return
     *  a reference to e.
     * @param vs First vertex as a TimingVertex.
     * @param vd Second vertex as a TimingVertex.
     * @param e TimingEdge to be inserted.
     * @return A reference to TimingEdge e if there is not an edge already inserted, otherwise, it 
     * returns a reference to the existing TimingEdge having the same vertices.
     */
    boolean safeAddEdge(TimingVertex vs, TimingVertex vd, TimingEdge e) {
        if (vs == null || vs.getName() == null || vd == null || vd.getName() == null) {
            System.err.println("Error: vs is null:" + vs + " or vd is null:" + vd);
            Exception newException = new Exception();
            newException.printStackTrace();
            return false;
        }
        TimingEdge prev = getEdge(vs, vd);
        boolean tmp = (prev != null && prev.getNet() != null);
        if (tmp) {
            if (verbose)
                System.out.println("replacing edge:"+e);
            else {
                removeEdge(vs, vd);
            }
        }
        return addEdge(vs, vd, e);
    }

    /**
     * For helping to avoid duplicates, this helper function calls safeVertexCheck to see if a 
     * Vertex with the name s already exists.
     * @param s The name/id for the new TimingVertex.  Typically this is set to a hierarchical name 
     * of the pin/EDIFPortInst.
     * @return If no vertex exists with this name, then a new vertex is created and a reference to 
     * it is returned.  Otherwise, it returns a reference to the vertex that exists having the same 
     * name.
     */
    protected TimingVertex newTimingVertex(String s) {
        TimingVertex v1 = safeVertexCheck.get(s);
        if (v1 == null) {
            if (s.startsWith("/"))
                s = s.substring(1, s.length());
            v1 = new TimingVertex(s);
            v1 = safeAddVertex(v1);
        }
        return v1;
    }

    /**
     * TODO
     * This method is planned for helping to remove edges in the graph between flops connected to 
     * different clocks, however, this has not been implemented in the current release.
     * @return Boolean indication of whether any paths were removed.
     */
    protected boolean removeClockCrossingPaths() {
        boolean result = false;
        return result;
    }

    /**
     * Checks if the provided string is a supported unisim flop flop type.
     * @param cellType The cell type name to query.
     * @return True if cell type is a supported unisim flip flop type (FDRE, FDCE,...).
     */
    private boolean isUnisimFlipFlopType(String cellType) {
        return unisimFlipFlopTypes.contains(cellType);
    }
    
    private boolean isRamType(String cellType) {
        return ramTypes.contains(cellType);
    }
    
    static Set<String> bramOutPortsA = new HashSet<>();
    static {
        bramOutPortsA.add("CASDOUTA");
        bramOutPortsA.add("DOUTADOUT");
        bramOutPortsA.add("CASDOUTPA");
        bramOutPortsA.add("DOUTPADOUTP");
    }
    
    static Set<String> bramOutPortsB = new HashSet<>();
    static {
        bramOutPortsB.add("CASDOUTB");
        bramOutPortsB.add("DOUTBDOUT");
        bramOutPortsB.add("CASDOUTPB");
        bramOutPortsB.add("DOUTPBDOUTP");
    }
    
    private boolean isBramOutPortA(String portName) {
        for (String s : bramOutPortsA) {
            if (portName.startsWith(s)) return true;
        }
        return false;
    }
    
    private boolean isBramOutPortB(String portName) {
        for (String s : bramOutPortsB) {
            if (portName.startsWith(s)) return true;
        }
        return false;
    }
    
    /**
     * Steps through the Physical "Cells" within the design and effectively adds TimingEdges to the 
     * TimingGraph representing logic delays from input pins to corresponding output pins.
     */
    void determineLogicDelaysFromEDIFCellInsts(Map<String, EDIFCellInst> myCellMap) {
        for (String cellName : myCellMap.keySet()) {
            Cell c = design.getCell(cellName);
            if (c == null) continue;

            EDIFCellInst mycellInst = myCellMap.get(cellName);
            EDIFCell mycellType = mycellInst.getCellType();
            String myCellName = mycellType.getName();
            Collection<EDIFPortInst> portInstList = mycellInst.getPortInsts();
            
            if (myCellName.startsWith("RAMB")) {
                int encodedConfig = 0;
                encodedConfig |= intrasiteAndLogicDelayModel.getEncodedConfigCode("RAMB36E2:RTL_RAM_TYPE:RAM_TDP");
                for (Map.Entry<String, EDIFPropertyValue> entry : mycellInst.getPropertiesMap().entrySet()) {
                    encodedConfig |= intrasiteAndLogicDelayModel.getEncodedConfigCode("RAMB36E2:"+ entry.getKey() + ":" + entry.getValue().getValue().toString());
                }
                short belIdx = intrasiteAndLogicDelayModel.getBELIndex("RAMB36E2");
                
                // TODO this loop should be consolidated with that of CARRY8.
                for (EDIFPortInst ep1 : portInstList) {
                    if (!ep1.isInput()) {
                        continue;
                    }
                    String s1 = ep1.getName();
                    for (EDIFPortInst ep2 : portInstList) {
                        if (!ep2.isOutput()) {
                            continue;
                        }
                        String s2 = ep2.getName();

                        // RAMB36E2 and RAMB18E2 have the same delay, only that RAMB18E2 will have less pins
                        short delay = 0;
                        if (s1.startsWith("CLKA")) { // for DSP, we need to look up in the text file
                            // check order_a for A pin, order_b for B pin
                            if (isBramOutPortA(s2)) {
                                String property = mycellInst.getProperty("CASCADE_ORDER_A").getValue();
                                int DOA_REG = Integer.parseInt(mycellInst.getProperty("DOA_REG").getValue());
                                if (property.equals("FIRST") || property.equals("NONE") || (property.equals("LAST") && DOA_REG == 1)) {
                                    delay = (short) getCLKtoOutputDelay(s2, encodedConfig);
                                }
                            }
                        } else if (s1.startsWith("CLKB")) {
                                if (isBramOutPortB(s2)) {
                                    String property = mycellInst.getProperty("CASCADE_ORDER_B").getValue();
                                    int DOB_REG = Integer.parseInt(mycellInst.getProperty("DOB_REG").getValue());
                                    if (property.equals("FIRST") || property.equals("NONE") || (property.equals("LAST") && DOB_REG == 1)) {
                                        delay = (short) getCLKtoOutputDelay(s2, encodedConfig);
                                    }
                                    
                                }
                        } else {
                            delay = intrasiteAndLogicDelayModel.getLogicDelay(belIdx, s1, s2, encodedConfig);
                        }
                        
                        if (delay < 0) {
                            continue;
                        }
                        
                        TimingVertex v1 = newTimingVertex(cellName+"/"+s1);
                        TimingVertex v2 = newTimingVertex(cellName+"/"+s2);
                        TimingEdge e = new TimingEdge(this, v1, v2, null, new Net());
                        
                        safeAddEdge(e.getSrc(), e.getDst(), e);
                        e.setLogicDelay(delay);
                        setEdgeWeight(e, e.getDelay());
                    }
                }
            }
            else if (myCellName.startsWith("LUT") || myCellName.startsWith("RAM") || myCellName.startsWith("SRL")) {
                EDIFCell parent = c.getParentCell();
                boolean excludeSomeEdges = false;
                boolean eqHasI0 = false;
                boolean eqHasI1 = false;
                boolean eqHasI2 = false;
                boolean eqHasI3 = false;
                boolean eqHasI4 = false;
                boolean eqHasI5 = false;
                short belIdx = intrasiteAndLogicDelayModel.getBELIndex(c.getBELName());

                String thisCellEquation = "";
                // in the case of LUT6_2, we found that we need to check the LUT equation in order to decide whether
                // or not to add edges representing individual logic delays to the timing graph
                if (parent != null && parent.getName().startsWith("LUT6_2")) {
                    String [] parts = cellName.split("/");
                    String parentCell = parts[0];
                    for (int i =1; i < parts.length-1; i++) {
                        parentCell += "/"+parts[i];
                    }
                    EDIFCellInst eciParent = design.getNetlist().getCellInstFromHierName(parentCell);
                    EDIFPortInst epiForI5 =  eciParent.getPortInst("I5");
                    EDIFNet enForI5 = epiForI5.getNet();

                    boolean pinI5ConnectedToConst0 = enForI5.getName().equals(EDIFTools.LOGICAL_GND_NET_NAME);
                    boolean pinI5ConnectedToConst1 = enForI5.getName().equals(EDIFTools.LOGICAL_VCC_NET_NAME);
                    boolean thisCellIsLUT5 = c.getType().equals("LUT5");

                    thisCellEquation =  LUTTools.getLUTEquation(eciParent);
                    String lutInit = LUTTools.getLUTInitFromEquation(thisCellEquation,6);
                    //String truthTable = LUTTools.returnTruthTable(eci);
                    //System.out.println(truthTable+"\n");

                    long lutInitValue = LUTTools.getInitValue(lutInit);
                    int tableEntries = thisCellIsLUT5 ? 32 :
                            (pinI5ConnectedToConst0 || pinI5ConnectedToConst1)? 32 : 64;

                    int startingPoint = thisCellIsLUT5 ||
                            (!pinI5ConnectedToConst0 && !pinI5ConnectedToConst1) ||
                            pinI5ConnectedToConst0 ? 0 : 32;

                    int[][] tempTableIx = new int[tableEntries/2][2];
                    int zeroCntr = 0;
                    int oneCntr = 0;
                    for (int i=startingPoint; i<startingPoint+tableEntries; i++) {
                        int resultBit = getBit(lutInitValue, i);
                        int ix = getBit(i, 0);
                        if (ix == 0) {
                            tempTableIx[zeroCntr][0] = resultBit;
                            zeroCntr++;
                        } else if (ix == 1) {
                            tempTableIx[oneCntr][1] = resultBit;
                            oneCntr++;
                        }
                    }
                    for (int i=0; i<tableEntries/2; i++) {
                        if (tempTableIx[i][0] != tempTableIx[i][1]) {
                            eqHasI0 = true;
                            break;
                        }
                    }

                    zeroCntr = 0;
                    oneCntr = 0;
                    for (int i=startingPoint; i<startingPoint+tableEntries; i++) {
                        int resultBit = getBit(lutInitValue, i);
                        int ix = getBit(i, 1);
                        if (ix == 0) {
                            tempTableIx[zeroCntr][0] = resultBit;
                            zeroCntr++;
                        } else if (ix == 1) {
                            tempTableIx[oneCntr][1] = resultBit;
                            oneCntr++;
                        }
                    }
                    for (int i=0; i<tableEntries/2; i++) {
                        if (tempTableIx[i][0] != tempTableIx[i][1]) {
                            eqHasI1 = true;
                            break;
                        }
                    }

                    zeroCntr = 0;
                    oneCntr = 0;
                    for (int i=startingPoint; i<startingPoint+tableEntries; i++) {
                        int resultBit = getBit(lutInitValue, i);
                        int ix = getBit(i, 2);
                        if (ix == 0) {
                            tempTableIx[zeroCntr][0] = resultBit;
                            zeroCntr++;
                        } else if (ix == 1) {
                            tempTableIx[oneCntr][1] = resultBit;
                            oneCntr++;
                        }
                    }
                    for (int i=0; i<tableEntries/2; i++) {
                        if (tempTableIx[i][0] != tempTableIx[i][1]) {
                            eqHasI2 = true;
                            break;
                        }
                    }

                    zeroCntr = 0;
                    oneCntr = 0;
                    for (int i=startingPoint; i<startingPoint+tableEntries; i++) {
                        int resultBit = getBit(lutInitValue, i);
                        int ix = getBit(i, 3);
                        if (ix == 0) {
                            tempTableIx[zeroCntr][0] = resultBit;
                            zeroCntr++;
                        } else if (ix == 1) {
                            tempTableIx[oneCntr][1] = resultBit;
                            oneCntr++;
                        }
                    }
                    for (int i=0; i<tableEntries/2; i++) {
                        if (tempTableIx[i][0] != tempTableIx[i][1]) {
                            eqHasI3 = true;
                            break;
                        }
                    }

                    zeroCntr = 0;
                    oneCntr = 0;
                    for (int i=startingPoint; i<startingPoint+tableEntries; i++) {
                        int resultBit = getBit(lutInitValue, i);
                        int ix = getBit(i, 4);
                        if (ix == 0) {
                            tempTableIx[zeroCntr][0] = resultBit;
                            zeroCntr++;
                        } else if (ix == 1) {
                            tempTableIx[oneCntr][1] = resultBit;
                            oneCntr++;
                        }
                    }
                    for (int i=0; i<tableEntries/2; i++) {
                        if (tempTableIx[i][0] != tempTableIx[i][1]) {
                            eqHasI4 = true;
                            break;
                        }
                    }

                    if (!pinI5ConnectedToConst0 && !pinI5ConnectedToConst1 && !thisCellIsLUT5) {
                        zeroCntr = 0;
                        oneCntr = 0;
                        for (int i = 0; i < startingPoint+tableEntries; i++) {
                            int resultBit = getBit(lutInitValue, i);
                            int ix = getBit(i, 5);
                            if (ix == 0) {
                                tempTableIx[zeroCntr][0] = resultBit;
                                zeroCntr++;
                            } else if (ix == 1) {
                                tempTableIx[oneCntr][1] = resultBit;
                                oneCntr++;
                            }
                        }
                        for (int i = 0; i < tableEntries / 2; i++) {
                            if (tempTableIx[i][0] != tempTableIx[i][1]) {
                                eqHasI5 = true;
                                break;
                            }
                        }
                    }

                    excludeSomeEdges = !eqHasI0 || !eqHasI1 || !eqHasI2 || !eqHasI3 || !eqHasI4 || !eqHasI5;
                }

                for (EDIFPortInst ep1 : portInstList) {
                    if (excludeSomeEdges) {
                        if (ep1.getName().endsWith("I0") && !eqHasI0)
                            continue;
                        if (ep1.getName().endsWith("I1") && !eqHasI1)
                            continue;
                        if (ep1.getName().endsWith("I2") && !eqHasI2)
                            continue;
                        if (ep1.getName().endsWith("I3") && !eqHasI3)
                            continue;
                        if (ep1.getName().endsWith("I4") && !eqHasI4)
                            continue;
                        if (ep1.getName().endsWith("I5") && !eqHasI5)
                            continue;
                    }
                    String s1 = cellName + "/" + ep1.getName();

                    for (EDIFPortInst ep2 : portInstList) {
                        String s2 = cellName + "/" + ep2.getName();

                        float logicDelay = 0.0f;
                        if (ep1 != ep2 && ep1.isInput() && ep2.isOutput()) {

                            String physPin = c.getPhysicalPinMapping(ep1.getName());
                            String outputPhysPin = c.getPhysicalPinMapping(ep2.getName());


                            float myLogicDelay;
                            try {
                                myLogicDelay = intrasiteAndLogicDelayModel.getLogicDelay(belIdx, physPin, outputPhysPin);
                            } catch (IllegalArgumentException e) {
                                continue;
                            }
                            if (myLogicDelay < 0) {
                                continue;
                            }
                            float LOGIC_DELAY = 0.0f;

                            LOGIC_DELAY = myLogicDelay;

                            if (ep2.getName().startsWith("O")) {
                                logicDelay = LOGIC_DELAY;
                                //break;
                            }

                            TimingVertex v1 = newTimingVertex(s1);
                            TimingVertex v2 = newTimingVertex(s2);
                            TimingEdge e = new TimingEdge(this, v1, v2, null, new Net());
                            safeAddEdge(e.getSrc(), e.getDst(), e);
                            e.setLogicDelay(logicDelay);
                            setEdgeWeight(e, e.getDelay());
                            if (debug) {
                                System.out.println("Adding v1:" + s1 + " and v2:" + s2 + 
                                                   " with edge:" + e + " to SG2, logic delay: " + logicDelay);
                            }

                        }
                    }
                }

            }
            else if (myCellName.startsWith("CARRY")) {
                int encodedConfig = 0;
                if (c.getPhysicalPinMapping("CI") == null) {
                    encodedConfig |= intrasiteAndLogicDelayModel.getEncodedConfigCode("CARRY8:CYINIT_BOT:GND");
                } else if (c.getPhysicalPinMapping("CI_TOP") == null) {
                    encodedConfig |= intrasiteAndLogicDelayModel.getEncodedConfigCode("CARRY8:CYINIT_TOP:GND");
                } else {
                    encodedConfig |= intrasiteAndLogicDelayModel.getEncodedConfigCode("CARRY8:CYINIT_BOT:CIN"); 
                }
                encodedConfig |= intrasiteAndLogicDelayModel.getEncodedConfigCode("CARRY8:CARRY_TYPE:SINGLE_CY8");                
                short belIdx = intrasiteAndLogicDelayModel.getBELIndex("CARRY8");
                
                for (EDIFPortInst ep1 : portInstList) {
                    if (!ep1.isInput()) {
                        continue;
                    }

                    String s1 = cellName + "/" + ep1.getName();
                    for (EDIFPortInst ep2 : portInstList) {

                        if (!ep2.isOutput()) {
                            continue;
                        }
                        String s2 = cellName + "/" + ep2.getName();
                        float logicDelay = 0.0f;
                        if (ep1 != ep2 && ep1.isInput() && ep2.isOutput()) {
                            String physPin = c.getPhysicalPinMapping(ep1.getName());
                            String outputPhysPin = c.getPhysicalPinMapping(ep2.getName());

                            if (physPin == null || physPin.equals("null")) {
                                // TODO - This is suspected to be buggy behavior
                                encodedConfig = 0; 
                            }
                            
                            float myLogicDelay = intrasiteAndLogicDelayModel.getLogicDelay(
                                     belIdx, physPin, outputPhysPin, encodedConfig);
                            if (myLogicDelay < 0) {
                                continue;
                            }

                            logicDelay = myLogicDelay;

                            boolean ep1ContainsRange = ep1.getName().endsWith("I[7:0]");
                            boolean ep2ContainsRange = ep2.getName().endsWith("O[7:0]");
                            String ep1FirstLetter = ep1.getName().substring(0, 1);
                            String ep2FirstLetter = ep2.getName().substring(0, 1);
                            if (ep2ContainsRange) {
                                if (ep2FirstLetter.equals("O"))
                                    ep2FirstLetter = "";
                                s2 = s2.replace(ep2FirstLetter + "O[7:0]", ep2FirstLetter + "O");
                                if (ep1ContainsRange) {
                                    for (int j = 0; j < 8; j++) {
                                        for (int i = 0; i < 8; i++) {
                                            TimingVertex v1 = newTimingVertex(cellName + "/" + ep1FirstLetter + j);
                                            TimingVertex v2 = newTimingVertex(cellName + "/" + s2 + i);
                                            TimingEdge e = new TimingEdge(this, v1, v2, null, new Net());
                                            safeAddEdge(e.getSrc(), e.getDst(), e);
                                            e.setLogicDelay(logicDelay);
                                            setEdgeWeight(e, e.getDelay());
                                            if (debug)
                                                System.out.println("Adding v1:" + s1 + " and v2:" + s2 + " with edge:" + e + " to SG2");

                                        }
                                        TimingVertex v1 = newTimingVertex(cellName + "/" + ep1FirstLetter + j);
                                        TimingVertex v2 = newTimingVertex(cellName + "/" + "OUT1");
                                        TimingEdge e = new TimingEdge(this, v1, v2, null, new Net());
                                        safeAddEdge(e.getSrc(), e.getDst(), e);
                                        e.setLogicDelay(logicDelay);
                                        setEdgeWeight(e, e.getDelay());
                                        if (debug)
                                            System.out.println("Adding v1:" + s1 + " and v2:" + s2 + " with edge:" + e + " to SG2");

                                    }
                                } else {
                                    for (int i = 0; i < 8; i++) {
                                        TimingVertex v1 = newTimingVertex(s1);
                                        TimingVertex v2 = newTimingVertex(s2 + i);
                                        TimingEdge e = new TimingEdge(this, v1, v2, null, new Net());
                                        safeAddEdge(e.getSrc(), e.getDst(), e);
                                        e.setLogicDelay(logicDelay);
                                        setEdgeWeight(e, e.getDelay());
                                        if (debug)
                                            System.out.println("Adding v1:" + s1 + " and v2:" + s2 + " with edge:" + e + " to SG2");

                                    }
                                    TimingVertex v1 = newTimingVertex(s1);
                                    TimingVertex v2 = newTimingVertex(cellName + "/" + "OUT1");
                                    TimingEdge e = new TimingEdge(this, v1, v2, null, new Net());
                                    safeAddEdge(e.getSrc(), e.getDst(), e);
                                    e.setLogicDelay(logicDelay);
                                    setEdgeWeight(e, e.getDelay());
                                    if (debug)
                                        System.out.println("Adding v1:" + s1 + " and v2:" + s2 + " with edge:" + e + " to SG2");
                                }

                            } else {
                                TimingVertex v1 = newTimingVertex(s1);
                                TimingVertex v2 = newTimingVertex(s2);
                                TimingEdge e = new TimingEdge(this, v1, v2, null, new Net());
                                safeAddEdge(e.getSrc(), e.getDst(), e);
                                e.setLogicDelay(logicDelay);
                                setEdgeWeight(e, e.getDelay());
                                if (debug)
                                    System.out.println("Adding v1:" + s1 + " and v2:" + s2 + " with edge:" + e + " to SG2");
                            }
                        }
                    }
                }
            } else if (mycellInst.getCellType().toString().contains("DSP_")) {//contains DSP_, and FD, VCC
                dspTimingDataPathCheck();
                String dspBlockFullHierName = c.getParentHierarchicalInstName();
                DSPTimingData dspTimingData = dspNameDataMapping.get(dspBlockFullHierName);
                if (dspTimingData == null) {
                    dspTimingData = new DSPTimingData(dspBlockFullHierName, dspTimingDataFolder);//check if data processed previously
                    if (dspTimingData.isValid()) {
                        dspNameDataMapping.put(dspBlockFullHierName, dspTimingData);
                    } else {
                        dspTimingFileExistenceWarning(dspBlockFullHierName);
                    }
                }
                for (EDIFPortInst portInst : portInstList) {
                    String s1 = portInst.getName();
                    if (s1.endsWith(("CLK"))) {
                        if (dspTimingData.containsPortInst(portInst.getName())) {
                            dspTimingData.addPinMapping("CLK", portInst.getName());
                            dspTimingDataSet.add(dspTimingData);
                        }
                    }
                   
                    EDIFNet en = portInst.getNet();
                    for (EDIFPortInst portInstOfNet : en.getPortInsts()) {
                        if (portInstOfNet.isTopLevelPort()) {
                            if (dspTimingData.containsPortInst(portInstOfNet.getName())) {
                                dspTimingData.addPinMapping(portInst.getFullName(), portInstOfNet.getName());
                                dspTimingDataSet.add(dspTimingData);//saved for adding timing edges with logic delay
                            }
                        }
                    }
                }
            }
            else if (myCellName.startsWith("BUFGCE")) {//BUFGCE as mycellname, portInsts: [BUFGCE_inst/CE, BUFGCE_inst/I, BUFGCE_inst/O]
                String s1 = cellName + "/" + "I";
                String s2 = cellName + "/" + "O";
                TimingVertex v1 = newTimingVertex(s1);
                TimingVertex v2 = newTimingVertex(s2);
                TimingEdge e = new TimingEdge(this, v1, v2, null, new Net());
                safeAddEdge(e.getSrc(), e.getDst(), e);
                e.setLogicDelay(0);
                setEdgeWeight(e, e.getDelay());
            }
        }
        
        // add dsp timing edges here, because the above for loop deals with one cell a time
        // the overall info of top level inputs and outputs of DSP blocks available after the loop
        // DSP delays CLK to Q, IN to CLK, IN to OUT are handled here
        for (DSPTimingData dspTimingData : dspTimingDataSet) {
            for (Pair<String, String> inOut : dspTimingData.getInputOutputDelays().keySet()) {
                TimingVertex v1 = newTimingVertex(dspTimingData.getBlockName() + "/" + inOut.getFirst());
                TimingVertex v2 = newTimingVertex(dspTimingData.getBlockName() + "/" + inOut.getSecond());
                TimingEdge e = new TimingEdge(this, v1, v2, null, new Net());
               
                safeAddEdge(e.getSrc(), e.getDst(), e);
                e.setLogicDelay(dspTimingData.getInputOutputDelays().get(inOut));
                setEdgeWeight(e, e.getDelay());
            }
        }   
    }
    
    private void dspTimingDataPathCheck() {
        if (dspTimingDataFolder == null && !dspTimingDataFolderWarning) {
            System.out.println("CRITICAL WARNING: The design contains DSP blocks, but the DSP logic delay file path has not been set.");
            DSPTimingData.generateWarningInfo();
            dspTimingDataFolderWarning = true;
        } else if (dspTimingDataFolder != null) {
            if (!dspTimingDataFolder.endsWith("/")) dspTimingDataFolder += "/";
            if (!dspTimingDataFolderWarning) {
                System.out.println("INFO: DSP timing data folder set as: " + dspTimingDataFolder);
                dspTimingDataFolderWarning = true;
            }
        }
    }
    
    private void dspTimingFileExistenceWarning(String dspBlockFullHierName) {
        if (!dspTimingFileExistenceWarning) {
            System.out.println("CRITICAL WARNING: logic delay file does not exist: " + dspBlockFullHierName.replace("/", "-"));
            DSPTimingData.generateWarningInfo();
            dspTimingFileExistenceWarning = true;
        }
    }
    
    private Cell srcCell;
    private Cell dstCell;
    private BELPin source;
    private BELPin sink;
    private SiteInst si;
    
    private float intraSiteDelay = 0.0f;

    /**
     * This method is called per physical "Net" object for adding TimingEdges into the TimingGraph 
     * representing the net delays.
     * @param n Physical "Net" to be analyzed.
     * @return Returns -1 or 0 on failure.  Returns 1 on success.
     */
    
    static List<String> bramCLKPins;
    static {
        bramCLKPins = new ArrayList<>();
        bramCLKPins.add("CLKARDCLK");
        bramCLKPins.add("CLKBWRCLK");
    }
    
    float getCLKtoOutputDelay(String portName, int encodedConfig) {
        float delay = 0;
        short belIdx = intrasiteAndLogicDelayModel.getBELIndex("RAMB36E2");
        for (String clk : bramCLKPins) {
            delay = Math.max(delay, intrasiteAndLogicDelayModel.getLogicDelay(belIdx, clk, portName, encodedConfig));
        }
        return delay;
    }
    
    public boolean overwriteBUGCEDelay = false;
    public int addNetDelayEdges(Net net) {
        EDIFNet edifNet = net.getLogicalNet();
        boolean haveIntrasiteNet = (net.getSinkPins().size() == 0);
        SitePinInst spi_source = net.getSource();
        float logicDelay;
        SitePinInst local_spi_source = null;
        List<SitePinInst> spi_sources = new ArrayList<>();
        
        List<EDIFHierPortInst> hports = null;
        hports = design.getNetlist().getPhysicalPins(net);

        if (hports == null) {
            return 0;
        }

        HashMap<String, SitePinInst> stringSources = new HashMap<>();
        HashMap<String, SitePinInst> stringSinks = new HashMap<>();
        HashMap<String, Cell> testDestCells = new HashMap<>();
        HashMap<String, BELPin> sink_belpins = new HashMap<>();

        Cell testSourceCell = null;
        logicDelay = 0f;
        boolean updateLogicDelay = true;
        
        if (clkRouteTiming == null) {
            overwriteBUGCEDelay = false;
        } else {
            if (spi_source != null && spi_source.getName().equals("CLK_OUT") && spi_source.toString().contains(clkRouteTiming.getBufgce())) {
                overwriteBUGCEDelay = true;
            } else {
                overwriteBUGCEDelay = false;
            }
        }
        
        for (EDIFHierPortInst hport : hports) {
            String portName = hport.getPortInst().getName();
            String cellName = hport.getFullHierarchicalInstName();
            Cell cell = design.getCell(cellName);
            String fullName = cellName+"/"+portName; // YZhou: CellPin Name, same as hport.toString()
            
            SitePinInst spi5 = null;
            String physPinName = null;
            if (cell == null) {
                continue;
            }
            if (cell.isRoutethru()) {
                String b = cell.getType();
                if (b.startsWith("CARRY")) {
                    cell = cell.getSiteInst().getCell(b);
                    physPinName = cell.getPhysicalPinMapping(portName);
                } else {
                    BEL lut = cell.getBEL();
                    for (String pin : cell.getPinMappingsP2L().keySet()) {
                        BELPin belPin = lut.getPin(pin);
                        if (belPin.isInput()) {
                            physPinName = belPin.getConnectedSitePinName();
                            String spiName = belPin.getConnectedSitePinName();
                            if (spiName != null)
                                spi5 = cell.getSiteInst().getSitePinInst(spiName);
                        }
                    }
                }
            } else {
                physPinName = cell.getPhysicalPinMapping(portName);
                // spi5 = cell.getSitePinFromLogicalPin(hport.getPortInst().getName(), null);
                spi5 = cell.getSiteInst().getSitePinInst(DesignTools.getRoutedSitePin(cell, net, portName)); // use the new method to get over unmatched SitePinInst issue
            }
           
           // if cell is dsp, and port name included in DSP pin mapping, override the fullName that is used to build timing edges
           if (cell.getType().startsWith("DSP_")) {
               String dspBlockFullHierName = cell.getParentHierarchicalInstName();
               
               DSPTimingData dspTimingData = dspNameDataMapping.get(dspBlockFullHierName);
               if (dspTimingData != null) {
                   if (dspTimingData.getPinMapping() != null) { // null due to files that (mul_ln1371_fu_88_p2.txt) contains clk only, not processed yet -> fixed
                    
                       String mappedfullName = dspTimingData.getPinMapping().get(fullName);
                       if (mappedfullName != null) {
                           fullName = mappedfullName;
                       }
                   }
               }
           }
            
           si = cell.getSiteInst();
           BEL bel = si.getBEL(cell.getBELName());
           BELPin belpin =  null;

           if (bel != null  && physPinName != null)
               belpin = bel.getPin(physPinName.replace("[", "").replace("]", ""));
            
           SitePinInst mypin = spi5;
           if (mypin == null) {
               if (hport.isOutput()) {
                   stringSources.put(fullName, null);
                   testSourceCell = cell;
                   if (isUnisimFlipFlopType(cell.getType())) {
                       logicDelay = timingModel.LOGIC_FF_DELAY;
                   } else if (isRamType(cell.getType())) {
                       updateLogicDelay = false;
                   }
                   source = cell.getBEL().getPin(physPinName);
               } else {
                   stringSinks.put(fullName, null);
                   testDestCells.put(fullName, cell);
                   sink_belpins.put(fullName, belpin);
               }
               continue;
           }

            if (hport.getPortInst().isOutput() || mypin.isOutPin()) {
                spi_sources.add(mypin);
                stringSources.put(fullName, mypin);
                testSourceCell = cell;
                source = cell.getBEL().getPin(physPinName);
                if (isUnisimFlipFlopType(cell.getType())) {
                    logicDelay = timingModel.LOGIC_FF_DELAY;
                } else if (isRamType(cell.getType())) {
                    updateLogicDelay = false;
                }
            } else {
                mypin = spi5;
                testDestCells.put(fullName, cell);
                stringSinks.put(fullName, mypin);
                sink_belpins.put(fullName, belpin);
            }
            edifHPortMap.put(hport, mypin);// added to get corresponding timing edges of connections
        }
        
        if (stringSinks.size() == 0 || stringSources.size() == 0) {
            int nPins = net.getPins().size();
            if (hports.size() != nPins) {
                return 0;
            } else
                return -1;
        }
        String S = stringSources.keySet().iterator().next();
        
        local_spi_source = spi_sources.size() > 0? spi_sources.get(0) : net.getSource() != null ? net.getSource() : local_spi_source;

        for (String D : stringSinks.keySet()) {
            SitePinInst spi_sink = stringSinks.get(D);
            srcCell = testSourceCell;
            dstCell = testDestCells.get(D);
            sink = sink_belpins.get(D);
            TimingVertex vS = safeVertexCheck.get(S);
            if (vS == null)
                vS = new TimingVertex(S);

            String vs_type = (srcCell != null) ? srcCell.getType() : null;
            if (vs_type != null && isUnisimFlipFlopType(vs_type)) {
                vS.setFlopOutput();
            }

            TimingVertex vD = safeVertexCheck.get(D);
            if (vD == null)
                vD = new TimingVertex(D);

            String vd_type = (dstCell != null) ? dstCell.getType() : null;
            if (vd_type != null && isUnisimFlipFlopType(vd_type)) {
                //String destClk = "";
                vD.setFlopInput();
            }
            vS = safeAddVertex(vS);
            vD = safeAddVertex(vD);
            TimingEdge e;
            e = getEdge(vS, vD);
            if (e == null)
                e = new TimingEdge(this, vS, vD, edifNet, net);

            boolean forceUpdateEdge = false;
            float netDelay = 0f;
            if (haveIntrasiteNet) {//LUT driving a FF is here
                String param2 = srcCell.getBELName()+"/"+ source.getName();
                String param3 = null;
                if (sink_belpins.get(D) == null) {
                    param3 =  dstCell.getBELName() +"/" + stringSinks.get(D).getName();
                } else {
                    param3 =  dstCell.getBELName() +"/" +sink_belpins.get(D).getName();
                }
                float tmpNetDelay;
                Short returnValue = intrasiteAndLogicDelayModel.getIntraSiteDelay(
                            si.getSiteTypeEnum(),
                            param2,
                            param3);
                if (returnValue == null) {
                    continue;
                }
                tmpNetDelay = (float) returnValue;
                
                intraSiteDelay = Math.max(0f, tmpNetDelay);// YZhou: for intrasite net, its intrasite delay is equal to net delay
                netDelay = Math.max(0f, tmpNetDelay);
                forceUpdateEdge = true;
                
            } else {
                if (srcCell == null)
                    continue;
                if (dstCell == null)
                    continue;
                if (local_spi_source == null || spi_sink == null) {
                    if (local_spi_source == null && spi_sink == null) {//source and sink are null
                        String param2 = srcCell.getBELName()+"/"+ source.getName();
                        String param3 =  dstCell.getBELName() +"/" +sink_belpins.get(D).getName();
                        float tmpNetDelay = intrasiteAndLogicDelayModel.getIntraSiteDelay(
                                si.getSiteTypeEnum(),
                                param2,
                                param3);
                        netDelay = tmpNetDelay;
                        intraSiteDelay = tmpNetDelay;
                        forceUpdateEdge = true;
                    } else {
                        netDelay = timingModel.calcDelay(local_spi_source, spi_sink, source, sink, net); 
                        intraSiteDelay = timingModel.getIntraSiteDelay();
                        forceUpdateEdge = true;
                    }
                } else {                    
                    netDelay = timingModel.calcDelay(local_spi_source, spi_sink, source, sink, net);
                    intraSiteDelay = timingModel.getIntraSiteDelay();
                    forceUpdateEdge = true;
                    if (clkRouteTiming == null) {
                        overwriteBUGCEDelay = false;
                    } else {
                        if (spi_sink.getName().equals("CLK_IN") && spi_sink.toString().contains(clkRouteTiming.getBufgce())) {
                            overwriteBUGCEDelay = true;
                        } else {
                            overwriteBUGCEDelay = false;
                        }
                    }
                }
            }
            
            if (e.getNetDelay() != 0f || forceUpdateEdge) {
                 if (overwriteBUGCEDelay) {
                     if (spi_sink.getName().equals("CLK_IN")) {
                         logicDelay += getRouteDelayToSinkINTTile(RouterHelper.getUpstreamINTTileOfClkIn(spi_sink).getName());
                     } else {
                         netDelay = getRouteDelayToSinkINTTile(spi_sink.getConnectedNode().getTile().getName());
                         logicDelay = 0;
                         intraSiteDelay = 0;
                     }
                 }
                e.setNetDelay(netDelay);
                if (updateLogicDelay) e.setLogicDelay(logicDelay);
                e.setIntraSiteDelay(intraSiteDelay);
            }
            e.setFirstSitePinInst(local_spi_source);
            e.setSecondSitePinInst(spi_sink);
            safeAddEdge(vS, vD, e);
            setEdgeWeight(e, e.getDelay());
            
            if (spi_sink != null) {
                List<TimingEdge> connectionEdges = sinkSitePinInstTimingEdges.get(spi_sink);
                if (connectionEdges == null) {
                    connectionEdges = new ArrayList<>();
                }
                connectionEdges.add(e);
                sinkSitePinInstTimingEdges.put(spi_sink, connectionEdges);
            }
        }

        // Clear the topological order so that it will be recomputed
        orderedTimingVertices.clear();
        return 1;
    }
    
    private short getRouteDelayToSinkINTTile(String intTile) {
        short delay = clkRouteTiming.getRouteDelaysToSinkINTTiles().getOrDefault(intTile, (short) 0);
        if (delay == 0) {
            System.out.println("WARNING: No delay data for the sink INT tile: " + intTile);
        }
        return delay;
    }
    
    public DelayModel getintraSiteAndLogicDelayModel() {
        return intrasiteAndLogicDelayModel;
    }

    /**
     * Returns a reference to the associated TimingModel.
     * @return A reference to the TimingModel created by the TimingManager.
     */
    public TimingModel getTimingModel() {
        return timingModel;
    }


    /**
     * If a TimingMangager is used to create the TimingGraph indirectly from the user, the 
     * TimingManager will call this method to set the TimingModel.
     * @param tModel The TimingManager will set this to the TimingModel that it creates.
     */
    public void setTimingModel(TimingModel tModel) {
        timingModel = tModel;
    }

    /**
     * Returns a reference to the associated TimingManager.
     * @return A reference to the TimingManager that created the TimingGraph.
     */
    public TimingManager getTimingManager() {
        return timingManager;
    }

    /**
     * If a TimingMangager is used to create the TimingGraph indirectly from the user, the 
     * TimingManager will call this method.
     * @param tManager The TimingManager will set this to itself.
     */
    public void setTimingManager(TimingManager tManager) {
        timingManager = tManager;
    }

    /**
     * Copied from LUTTools.java. Gets a bit at the specified index from within an int.
     * @param value
     * @param bitIndex
     * @return Single bit from indexed location will be zero or one.
     */
    protected static int getBit(int value, int bitIndex) {
        return (value >> bitIndex) & 0x1;
    }

    /**
     * Copied from LUTTools.java. Gets a bit at the specified index from within a long.
     * @param value
     * @param bitIndex
     * @return Single bit from indexed location will be zero or one.
     */
    protected static int getBit(long value, int bitIndex) {
        return (int)(value >> bitIndex) & 0x1;
    }
    
    public Map<TimingEdge, Connection> getTimingEdgeConnectionMap() {
        return timingEdgeConnectionMap;
    }
    
    /**
     * Assigns {@link TimingEdge} instances to each connection in the list.
     * @param connections A list of connections that should be associated with {@link TimingEdge} instances.
     */
    public void setTimingEdgesOfConnections(List<Connection> connections) {
        for (Connection connection : connections) {
            if (connection.isDirect()) continue;
            List<EDIFHierPortInst> hportsFromSitePinInsts = DesignTools.getPortInstsFromSitePinInst(connection.getSink());
            if (hportsFromSitePinInsts.isEmpty()) {
                throw new RuntimeException("ERROR: Unable to find hierarchical logical cell pins from: " + connection.getSink());
            }
            EDIFHierPortInst hportSink = hportsFromSitePinInsts.get(0);
            SitePinInst mappedSink = edifHPortMap.get(hportSink);
            
            List<TimingEdge> timingEdges = sinkSitePinInstTimingEdges.get(mappedSink);
            if (timingEdges == null) {
                throw new RuntimeException("ERROR: No timing edges for connection from: " + connection.getSource() + " to " + connection.getSink());
            }
            connection.setTimingEdges(timingEdges);
            for (TimingEdge edge : connection.getTimingEdges()) {
                timingEdgeConnectionMap.put(edge, connection); // for getting critical path delay breakdown in the timing report
            }
        }
    }
}

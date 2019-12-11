/*
 * Copyright (c) 2019 Xilinx, Inc.
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

import com.xilinx.rapidwright.design.Cell;
import com.xilinx.rapidwright.design.Design;
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

import org.jgrapht.GraphPath;
import org.jgrapht.alg.shortestpath.AllDirectedPaths;
import org.jgrapht.alg.shortestpath.BellmanFordShortestPath;
import org.jgrapht.alg.shortestpath.KShortestSimplePaths;
import org.jgrapht.graph.DefaultDirectedWeightedGraph;
import org.jgrapht.graph.GraphWalk;

import java.io.FileNotFoundException;
import java.io.PrintStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Queue;
import java.util.LinkedList;
import java.util.List;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.Collection;


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
    String netName;
    Net net;
    String prevNet;
    EDIFNet edifNet;
    boolean haveIntrasiteNet;
    SitePinInst spi_source;
    Design design;
    ArrayList<EDIFHierCellInst> set;
    private HashMap<String, TimingVertex> safeVertexCheck = new HashMap<>();
    static HashSet<String> unisimFlipFlopTypes;

    static {
        
        unisimFlipFlopTypes = new HashSet<>();
        // build a static set containing the names of Flops collection for the method: 
        // "stringContainsNameOfFlipFlop"
        unisimFlipFlopTypes.add("FDSE");
        unisimFlipFlopTypes.add("FDPE");
        unisimFlipFlopTypes.add("FDRE");
        unisimFlipFlopTypes.add("FDCE");
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

    /**
     * Builds the TimingGraph based on analyzing nets within a {@link Design} object.
     */
    public void build() {
        if (timingModel == null) {
            throw new RuntimeException("Error: The TimingModel is not properly set for the "
                    + "TimingGraph prior to building.");
        }
        String seriesName = design.getDevice().getSeries().name().toLowerCase();
        intrasiteAndLogicDelayModel = DelayModelBuilder.getDelayModel(seriesName);
        HashMap<String, Net> netsByName = new LinkedHashMap<>();
        for (Net n : design.getNets()) {
            netsByName.put(n.getName(), n);
        }        String[] netsArray = netsByName.keySet().toArray(new String[netsByName.size()]);
        //Arrays.sort(netsArray);
        for (Net n : design.getNets()) {
            netsByName.put(n.getName(), n);
        }
        hierCellInstMap = new LinkedHashMap<>();
        EDIFCellInst top = design.getNetlist().getTopCellInst();
        hierCellInstMap.put(top, top.getName());
        myCellMap = design.getNetlist().generateCellInstMap();
        determineLogicDelaysFromEDIFCellInsts();
        set = new ArrayList<>();
        Queue<EDIFHierCellInst> q = new LinkedList<>();
        q.add(new EDIFHierCellInst("", top));
        while (!q.isEmpty()) {
            EDIFHierCellInst i = q.poll();
            for (EDIFCellInst child : i.getInst().getCellType().getCellInsts()) {
                String fullName = "";
                if (!i.isTopLevelInst()) {
                    fullName = i.getFullHierarchicalInstName();// + EDIFTools.EDIF_HIER_SEP + child.getName();
                }
                EDIFHierCellInst newCell = new EDIFHierCellInst(fullName, child);
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
        prevNet = "";
        for (String netName : netsArray) {
            this.netName = netName;
            this.net = netsByName.get(netName);
            this.edifNet = net.getLogicalNet();
            this.haveIntrasiteNet = (net.getSinkPins().size() == 0);
            this.spi_source = net.getSource();
            addNetDelayEdges(net);
        }
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
     * Sets the same specified timing requirement on a specified GraphPath.
     * @param requirement The required time in picoseconds at the sink of the path.
     * @param graphPath The GraphPath receiving this required time in picoseconds at the sink of the
     * path.
     */
    public void setTimingRequirement(float requirement, GraphPath<TimingVertex, TimingEdge> graphPath) {
        List<TimingEdge> edgeList = (List<TimingEdge>)graphPath.getEdgeList();
        float remainingRequiredTime = requirement;
        int sz = edgeList.size();
        for(int i=sz-1; i>=0; i-- ) {
            TimingEdge e = edgeList.get(i);
            e.getDst().setRequiredTime(remainingRequiredTime);
            remainingRequiredTime = remainingRequiredTime - e.getDelay();
            if (inDegreeOf(e.getSrc()) ==0) {
                e.getSrc().setRequiredTime(remainingRequiredTime);
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
        for(TimingEdge e : edges) {
            if (!this.containsEdge(e)) {
                result &= safeAddEdge(e.getSrc(), e.getDst(), e);
                this.setEdgeWeight(e, e.getDelay());
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
        for(TimingEdge e : edges) {
            if (outDegreeOf(getEdgeSource(e)) != 1 || inDegreeOf(getEdgeTarget(e)) != 1 )
                nofanout = false;
        }
        if (nofanout) {
            for( TimingEdge e : edges) {
                removeEdge(e);
            }
            result = true;
        }
        if (result)
            graphPathHashSet.remove(path);
        return result;
    }

/*
    private TimingEdge getEdgeIntoCurrent(TimingVertex node) {
        TimingEdge result = null;

        for(TimingEdge e : edgesOf(node)) {
            if (e.getDst().equals(node)) {
                result = e;
                break;
            }
        }
        return result;
    }


    public boolean removePartialTimingPath(GraphPath<TimingVertex, TimingEdge> path) {
        boolean result = false;
        List<TimingEdge> edges = path.getEdgeList();
        boolean nofanout = true;
        TimingVertex sink = path.getEndVertex();
        TimingVertex cur = sink;
        TimingEdge intoCurrent = getEdgeIntoCurrent(cur);
        int timer =100;
        while (cur != null && inDegreeOf(cur) == 1 &&
                (outDegreeOf(cur)==0 || outDegreeOf(cur)==1)) {
            cur = intoCurrent.getSrc();
            removeEdge(intoCurrent);
            result = true;
            timer--;
            if (timer == 0)
                break;
        }
        if (result)
            graphPathHashSet.remove(path);
        return result;
    }
*/

    /**
     * Finds and returns the value of the worst slack from the TimingGraph.
     * @return The value of the worst slack found in the TimingGraph, which might be null if slack 
     * hasn't been pre-computed.
     */
    public Float getWorstSlack() {
        Float result = Float.valueOf(1<<20);

        for (TimingVertex v : this.vertexSet()) {
            Float slack = v.getSlack();
            if (slack != null &&
                    this.outDegreeOf(v) == 0 &&
                    v.getSlack() < result)
                result = v.getSlack();
        }
        return result;
    }

/*
    public float getMaxDelay() {
        float result = 0;
        for (GraphPath p : graphPathHashSet) {
            float w = (float)p.getWeight();
            if (w > result)
                result = w;
        }
        return result;
    }


    public float getAvgDelay() {
        float result = 0;
        for (GraphPath p : graphPathHashSet) {
            float w = (float)p.getWeight();
            result += w;
        }
        return result/ graphPathHashSet.size();
    }
*/
    /**
     * Finds and returns the path from the TimingGraph having maximum delay.
     * @return The GraphPath that is the critical path found in the TimingGraph, which might be null
     * if the GraphPaths haven't been pre-computed by calling {@link #buildGraphPaths()}.
     */
    public GraphPath<TimingVertex, TimingEdge> getMaxDelayPath() {
        GraphPath<TimingVertex, TimingEdge> result = null;
        float maxWeight = 0;
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
        for (TimingEdge e : this.edgeSet()) {
            if (e != null) {
                this.setEdgeWeight(e, e.getDelay());
            }
            graphVizPrintStream.println(e.toGraphvizDotString() + ";");
        }
        graphVizPrintStream.println("}");
        graphVizPrintStream.close();
    }

    /*
    List<String> getChildStrings(EDIFCellInst eci, List<String> workingSet) {
        List<String> result = new ArrayList<>();
        List<String> tmpList = new ArrayList<>();
        for (String s : workingSet) {
            //String tmp = s + "/" + eci.getName();
            String tmp = s;
            tmpList.add(tmp);
        }
        result.addAll(tmpList);
        if (eci.getCellType().getCellInsts().size() == 0) {
        }
        else {
            Collection<EDIFCellInst> children = eci.getCellType().getCellInsts();
            for (EDIFCellInst child : children) {
                List<String> tmpList2 = new ArrayList<>();
                for (String s : workingSet) {
                    String tmp = s + "/" + child.getName() ;
                    tmpList2.add(tmp);
                }
                List<String> tmp2 = getChildStrings(child, tmpList2);
                result.addAll(tmp2);
            }
        }
        return result;
    }
*/

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

    /** Builds and returns a set of GraphPaths.
     * @param n 0 will return all paths; 1 will return 1 path by using Bellman Ford algorithm with 
     * negating the edges prior to running the shortest path algorithm.
     * @return A List of GraphPaths that were just built by this command
     */
    public List<GraphPath<TimingVertex, TimingEdge>> buildGraphPaths(int n) {
        graphPathHashSet = new LinkedHashSet<>();
        Set<TimingVertex> sources = new LinkedHashSet<>();
        Set<TimingVertex> sinks = new LinkedHashSet<>();
        List<GraphPath<TimingVertex, TimingEdge>> result = new ArrayList<>();
        for (TimingVertex s1 : this.vertexSet()) {
            if (this.inDegreeOf(s1) == 0 && this.outDegreeOf(s1) > 0 ) {
                //if (this.inDegreeOf(s1) == 0 && this.outDegreeOf(s1) > 0 || s1.getFlopOutput()) {
                //if (s1.getFlopOutput()) {
                sources.add(s1);
                //} else if (this.outDegreeOf(s1) == 0 && this.inDegreeOf(s1) > 0 ) {
            } else if (s1.getFlopInput() && this.outDegreeOf(s1) == 0 && this.inDegreeOf(s1) > 0) {
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
            for(TimingEdge e : edgeSet()) {
                setEdgeWeight(e,-1*e.getDelay());
            }
            TimingVertex superSource = null;
            TimingVertex superSink = null;
            if (superSource == null) {
                superSource = new TimingVertex("superSource");
                superSink = new TimingVertex("superSink");
            }
            if (!vertexSet().contains(superSource))
                addVertex(superSource);
            if (!vertexSet().contains(superSink))
                addVertex(superSink);
            for (TimingVertex s : sources) {
                addEdge(superSource, s, new TimingEdge(superSource, s));
            }
            for (TimingVertex s : sinks) {
                addEdge(s, superSink, new TimingEdge(s, superSink));
            }
            boolean bellmanFord = true;
            if (bellmanFord) {
                BellmanFordShortestPath<TimingVertex, TimingEdge> bellmanFordShortestPath =
                        new BellmanFordShortestPath<TimingVertex, TimingEdge>(this);
                GraphPath<TimingVertex, TimingEdge> path = bellmanFordShortestPath.getPath(superSource, superSink);
                for(TimingEdge e : edgeSet()) {
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
        paths.size();
        for (GraphPath<TimingVertex, TimingEdge> path : paths) {
            //System.out.println("Path between: src:" + s1 + " and sink:" + s2 + " is: " + path + " w:" + path.getWeight());
            result.add(path);
            graphPathHashSet.add(path);
        }
        return result;
    }

    /**
     * Computes/recomputes the arrival times stored at the vertices of the graph based on the edge 
     * delays.
     */
    public void computeArrivalTimes() {
        for (GraphPath<TimingVertex, TimingEdge> p : graphPathHashSet) {
            float arrival = 0;
            for (TimingEdge e : (List<TimingEdge>) p.getEdgeList()) {
                arrival += e.getDelay();
                e.getDst().setArrivalTime(arrival);
                if (inDegreeOf(e.getSrc())==0) {
                    e.getSrc().setArrivalTime(0);
                }
            }
        }
    }

    /**
     * Computes/recomputes the slack stored at vertices of the graph based on comparing required 
     * times and arrival times.
     */
    public void computeSlacks() {
        for (TimingVertex v : this.vertexSet()) {
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
/*
        boolean nullSourceClock = false;
        boolean nullDestClock = false;
        List<GraphPath> paths = buildGraphPaths();

        List<TimingVertex> pathEndVertices = new LinkedList();
        for (GraphPath<TimingVertex, TimingEdge> path : paths) {
            List<TimingVertex> pathVertices = new LinkedList<>();
            if (!path.getEndVertex().getFlopInput()) {
                removePartialTimingPath(path);
                result = true;
            }
        }
/*
        for (GraphPath<TimingVertex, TimingEdge> path : buildGraphPaths()) {
            if (path.getStartVertex().getClockName() == null) {
                nullSourceClock = true;
                System.err.println("Graph path has null StartVertex clock:"+path);
            }
            else if (path.getEndVertex().getClockName() == null) {
                nullDestClock = true;
                System.err.println("Graph path has null EndVertex clock:"+path);
            }
            if ( !nullDestClock &&  !nullSourceClock && !path.getStartVertex().getClockName().equals(path.getEndVertex().getClockName())) {
                System.out.println("removing glock crossing graph path "+(++clockCrossingGraphPathCntr)+": "+path);
                removePartialTimingPath(path);
                graphPathHashMap.remove(path.toString());
                result = true;
            }
        } */
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

    /**
     * Steps through the Physical "Cells" within the design and effectively adds TimingEdges to the 
     * TimingGraph representing logic delays from input pins to corresponding output pins.
     */
    void determineLogicDelaysFromEDIFCellInsts() {
        for (String k : myCellMap.keySet()) {
            EDIFCellInst mycellInst = myCellMap.get(k);
            EDIFCell mycellType = mycellInst.getCellType();
            String mycellname = mycellType.getName();
            Collection<EDIFPortInst> portInstList = mycellInst.getPortInsts();


            if (mycellname.startsWith("LUT") || mycellname.startsWith("RAM") || mycellname.startsWith("SRL")) {
                Cell c = design.getCell(k);
                if (c == null)
                    continue;
                EDIFCell parent = c.getParentCell();
                boolean excludeSomeEdges = false;
                boolean eqHasI0 = false;
                boolean eqHasI1 = false;
                boolean eqHasI2 = false;
                boolean eqHasI3 = false;
                boolean eqHasI4 = false;
                boolean eqHasI5 = false;


                String thisCellEquation = "";
                // in the case of LUT6_2, we found that we need to check the LUT equation in order to decide whether
                // or not to add edges representing individual logic delays to the timing graph
                if (parent != null && parent.getName().startsWith("LUT6_2")) {
                    String [] parts = k.split("/");
                    String parentCell = parts[0];
                    for (int i =1; i < parts.length-1; i++) {
                        parentCell += "/"+parts[i];
                    }
                    EDIFCellInst eciParent = design.getNetlist().getCellInstFromHierName(parentCell);
                    EDIFPortInst epiForI5 =  eciParent.getPortInst("I5");
                    EDIFNet enForI5 = epiForI5.getNet();

                    boolean pinI5ConnectedToConst0 = enForI5.getName().equals("<const0>");
                    boolean pinI5ConnectedToConst1 = enForI5.getName().equals("<const1>");
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
                    String s1 = k + "/" + ep1.getName();

                    for (EDIFPortInst ep2 : portInstList) {
                        String s2 = k + "/" + ep2.getName();

                        float logicDelay = 0.0f;
                        if (ep1 != ep2 && ep1.isInput() && ep2.isOutput()) {
                            if (c == null)
                                continue;

                            String physPin = c.getPhysicalPinMapping(ep1.getName());
                            String outputPhysPin = c.getPhysicalPinMapping(ep2.getName());

                            BEL mybel = c.getBEL();
                            float myLogicDelay;
                            try {
                                myLogicDelay = intrasiteAndLogicDelayModel.getLogicDelay(mybel.getName(), physPin, outputPhysPin);
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
                                                   " with edge:" + e + " to SG2");
                            }

                        }
                    }
                }

            }
            else if (mycellname.startsWith("CARRY")) {
                List<String> config = new ArrayList<String>();


                Cell c = design.getCell(k);
                if (c == null) {
                    continue;
                }

                if (c.getPhysicalPinMapping("CI") == null) {
                    config.add("CYINIT_BOT:GND");
                    config.add("CARRY_TYPE:SINGLE_CY8");
                }
                else if (c.getPhysicalPinMapping("CI_TOP") == null) {
                    config.add("CYINIT_TOP:GND");
                    config.add("CARRY_TYPE:SINGLE_CY8");
                } else {
                    config.add("CYINIT_BOT:CIN"); config.add("CARRY_TYPE:SINGLE_CY8");
                }

                for (EDIFPortInst ep1 : portInstList) {
                    if (!ep1.isInput()) {
                        continue;
                    }
                    String s1 = k + "/" + ep1.getName();
                    for (EDIFPortInst ep2 : portInstList) {

                        if (!ep2.isOutput()) {
                            continue;
                        }
                        String s2 = k + "/" + ep2.getName();
                        float logicDelay = 0.0f;
                        if (ep1 != ep2 && ep1.isInput() && ep2.isOutput()) {

                            BEL mybel = c.getSiteInst().getBEL(mycellType.toString());
                            c = c.getSiteInst().getCell(mybel);

                            String physPin = c.getPhysicalPinMapping(ep1.getName());
                            String outputPhysPin = c.getPhysicalPinMapping(ep2.getName());

                            if (physPin == null || physPin.equals("null")) {
                                config = new ArrayList<>();
                            }

                            float myLogicDelay = intrasiteAndLogicDelayModel.getLogicDelay(
                                     mybel.getName(), physPin, outputPhysPin, config);
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
                                            TimingVertex v1 = newTimingVertex(k + "/" + ep1FirstLetter + j);
                                            TimingVertex v2 = newTimingVertex(k + "/" + s2 + i);
                                            TimingEdge e = new TimingEdge(this, v1, v2, null, new Net());
                                            safeAddEdge(e.getSrc(), e.getDst(), e);
                                            e.setLogicDelay(logicDelay);
                                            setEdgeWeight(e, e.getDelay());
                                            if (debug)
                                                System.out.println("Adding v1:" + s1 + " and v2:" + s2 + " with edge:" + e + " to SG2");

                                        }
                                        TimingVertex v1 = newTimingVertex(k + "/" + ep1FirstLetter + j);
                                        TimingVertex v2 = newTimingVertex(k + "/" + "OUT1");
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
                                    TimingVertex v2 = newTimingVertex(k + "/" + "OUT1");
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
            }
        }
    }


    private Cell srcCell;
    private Cell dstCell;
    private BELPin source;
    private BELPin sink;
    private SitePinInst spi_sink;
    private SitePinInst local_spi_source;
    private List<SitePinInst> spi_sources;
    private List<SitePinInst> spi_sinks;
    private SiteInst si;


    /**
     * This method is called per physical "Net" object for adding TimingEdges into the TimingGraph 
     * representing the net delays.
     * @param n Physical "Net" to be analyzed.
     * @return Returns -1 or 0 on failure.  Returns 1 on success.
     */
    int addNetDelayEdges(Net n) {
        float logicDelay;
        spi_sinks = new ArrayList<>();
        local_spi_source = null;
        spi_sources = new ArrayList<>();


        String netName = n.getName();

        if (netName.startsWith("GLOBAL_LOGIC") || netName.startsWith("GLOBAL_USED")) {
            return -1;
        }
        List<EDIFHierPortInst> hports = null;
        hports = design.getNetlist().getPhysicalPins(netName);

        if (hports == null) {
            return 0;
        }

        HashMap<String, SitePinInst> stringSources = new HashMap<>();
        HashMap<String, SitePinInst> stringSinks = new HashMap<>();
        HashMap<String, Cell> testDestCells = new HashMap<>();
        HashMap<String, BELPin> sink_belpins = new HashMap<>();

        Cell testSourceCell = null;

        logicDelay = 0f;


        for (EDIFHierPortInst hport : hports) {
            String portName = hport.getPortInst().getName();
            String cellName = hport.getFullHierarchicalInstName();
            Cell cell = design.getCell(cellName);
            String fullName = cellName+"/"+portName;
            SitePinInst spi5 = null;
            String physPinName = null;
            if (cell == null) {
                continue;
            }
            if(cell.isRoutethru()) {
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
                spi5 = cell.getSitePinFromLogicalPin(hport.getPortInst().getName(), null);
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
                }
            } else {
                mypin = spi5;
                if (mypin != null)
                    spi_sinks.add(mypin);
                testDestCells.put(fullName, cell);
                stringSinks.put(fullName, mypin);
                sink_belpins.put(fullName, belpin);
            }
        }
        if (stringSinks.size() == 0 || stringSources.size() == 0) {
            int nPins = n.getPins().size();
            if (hports.size() != nPins) {
                return 0;
            } else
                return -1;
        }
        String S = stringSources.keySet().iterator().next();
        local_spi_source = spi_sources.size() > 0? spi_sources.get(0) : net.getSource() != null ? net.getSource() : local_spi_source;
        for (String D : stringSinks.keySet()) {
            spi_sink = stringSinks.get(D);
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

            if (haveIntrasiteNet) {
                String param2 = srcCell.getBELName()+"/"+ source.getName();
                String param3 = null;
                if (sink_belpins.get(D) == null) {
                    param3 =  dstCell.getBELName() +"/" + stringSinks.get(D).getName();
                } else {
                    param3 =  dstCell.getBELName() +"/" +sink_belpins.get(D).getName();
                }

                float tmpNetDelay;
                try {
                    tmpNetDelay = intrasiteAndLogicDelayModel.getIntraSiteDelay(
                            si.getSiteTypeEnum().name(),
                            param2,
                            param3);
                } catch (IllegalArgumentException iae) {
                    continue;
                }
                netDelay = Math.max(0f, tmpNetDelay);
                forceUpdateEdge = true;
            } else {
                if (srcCell == null)
                    continue;
                if (dstCell == null)
                    continue;
                if (local_spi_source == null || spi_sink == null) {
                    if (local_spi_source == null && spi_sink == null) {
                        String param2 = srcCell.getBELName()+"/"+ source.getName();
                        String param3 =  dstCell.getBELName() +"/" +sink_belpins.get(D).getName();
                        float tmpNetDelay = intrasiteAndLogicDelayModel.getIntraSiteDelay(
                                si.getSiteTypeEnum().name(),
                                param2,
                                param3);
                        netDelay = tmpNetDelay;
                        forceUpdateEdge = true;
                    }
                    else {
                        netDelay = timingModel.calcDelay(local_spi_source, spi_sink, source, sink, 
                                local_spi_source.getSite(), null, net);
                        forceUpdateEdge = true;
                    }
                }
                else {
                    netDelay = timingModel.calcDelay(local_spi_source, spi_sink, source, sink, 
                            local_spi_source.getSite(), spi_sink.getSite(), net);
                    forceUpdateEdge = true;
                }
            }
            if (e.getNetDelay() != 0f || forceUpdateEdge) {
                e.setNetDelay(netDelay);
                e.setLogicDelay(logicDelay);
            }
            e.setFirstSitePinInst(local_spi_source);
            e.setSecondSitePinInst(spi_sink);
            safeAddEdge(vS, vD, e);
            setEdgeWeight(e, e.getDelay());
        }
        return 1;
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
        this.timingModel = tModel;
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
        this.timingManager = tManager;
    }

    /**
     * Copied from LUTTools.java. Gets a bit at the specified index from within an int.
     * @param value
     * @param bitIndex
     * @return Single bit from indexed location will be zero or one.
     */
    protected static int getBit(int value, int bitIndex){
        return (value >> bitIndex) & 0x1;
    }

    /**
     * Copied from LUTTools.java. Gets a bit at the specified index from within a long.
     * @param value
     * @param bitIndex
     * @return Single bit from indexed location will be zero or one.
     */
    protected static int getBit(long value, int bitIndex){
        return (int)(value >> bitIndex) & 0x1;
    }

}

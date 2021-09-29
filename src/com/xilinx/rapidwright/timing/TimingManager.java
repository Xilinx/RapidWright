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

import java.util.List;
import java.util.Map;
import java.util.Set;

import com.xilinx.rapidwright.design.ConstraintGroup;
import com.xilinx.rapidwright.design.Design;
import com.xilinx.rapidwright.design.SitePinInst;
import com.xilinx.rapidwright.device.Device;
import com.xilinx.rapidwright.device.Node;
import com.xilinx.rapidwright.edif.EDIFHierPortInst;
import com.xilinx.rapidwright.rwroute.Configuration;
import com.xilinx.rapidwright.rwroute.Connection;
import com.xilinx.rapidwright.rwroute.NetWrapper;
import com.xilinx.rapidwright.rwroute.Routable;
import com.xilinx.rapidwright.timing.delayestimator.DelayEstimatorBase;
import com.xilinx.rapidwright.util.Pair;
import com.xilinx.rapidwright.util.TimerTree;


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
    
    public TimerTree routerTimer;
    private boolean verbose;
    
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
        timingModel = new TimingModel(this.design.getDevice());
        timingGraph = new TimingGraph(this.design);
        timingModel.setTimingManager(this);
        timingGraph.setTimingManager(this);
        timingGraph.setTimingModel(timingModel);
        this.device = this.design.getDevice();
        if (doBuild)
            build(false);
    }
    
    public TimingManager(Design design, boolean doBuild, TimerTree timer, Configuration config) {
    	this.design = design;
    	this.setTreq();
    	this.verbose = config.isVerbose();
    	setPessimismFactors(config.getPessimismA(), config.getPessimismB());
    	this.routerTimer = timer;
        timingModel = new TimingModel(this.design.getDevice());
        timingGraph = new TimingGraph(this.design, this.routerTimer);
        timingModel.setTimingManager(this);
        timingGraph.setTimingManager(this);
        timingGraph.setTimingModel(timingModel);
        this.device = this.design.getDevice();
        if (doBuild)
            build(config.isPartialRouting());
    }
    
    /**
     * Gets the map between each sink {@link SitePinInst} instance and its associated {@link TimingEdge} instances
     * from the {@link TimingGraph} instance.
     * @return Mapping between each sink {@link SitePinInst} instance and its associated {@link TimingEdge} instances.
     */
    public Map<SitePinInst, List<TimingEdge>> getSinkSitePinInstAndTimingEdgesMap(){
    	return this.timingGraph.getSinkSitePinInstTimingEdges();
    }
    
    /**
     * Gets the map between each {@link EDIFHierPortInst} instance (logical pin) and {@link SitePinInst} instance (physical pin)
     * from the {@link TimingGraph} instance.
     * @return Mapping between each {@link EDIFHierPortInst} instance (logical pin) and {@link SitePinInst} instance
     */
    public Map<EDIFHierPortInst, SitePinInst> getEdifHPortMap(){
    	return this.timingGraph.getEdifHPortMap();
    }
    /**
     * Updates the delay of nets after the cycle removal and delay-aware path merging
     */
    public void updateIllegalNetsDelays(Set<NetWrapper> illegalNets, Map<Node, Float> nodesDelays, List<Connection> cons){
    	 for(NetWrapper n:illegalNets){
    		 for(Connection c:n.getConnection()){
    			 float netDelay = 0;
    			 if(c.isDirect()) continue;
    			 for(int i = c.getNodes().size() - 2; i >= 0; i--) {
    				 Node child = c.getNodes().get(i);
    				 Node parent = c.getNodes().get(i+1);
    				 netDelay += nodesDelays.getOrDefault(child, 0f)
							 + DelayEstimatorBase.getExtraDelay(child, DelayEstimatorBase.isLong(parent));
    			 }
    			 c.setTimingEdgesDelay(netDelay);
    			 c.setDlyPatched(true);
    		 }
    	 }
    }
    
    /**
     * Patches up the delay of consecutive Long nodes
     */
    public void patchUpDelayOfConnections(List<Connection> cons) {
    	for(Connection c : cons) {
    		if(c.isDirect()) continue;
    		if(c.isDlyPatched()) continue;
    		float netDelay = 0;
    		for(int i = c.getRnodes().size() - 2; i >= 0; i--) {
    			Routable child = c.getRnodes().get(i);
				Routable parent = c.getRnodes().get(i+1);
				netDelay += child.getDelay() + DelayEstimatorBase.getExtraDelay(child.getNode(), DelayEstimatorBase.isLong(parent.getNode()));
    		}
    		c.setTimingEdgesDelay(netDelay);
			c.setDlyPatched(true);
    	}
    }
    
    private short index = -1;
    /**
     * Calculates and returns the maximum arrival time and the associated TimingVertex
     */
    public Pair<Float, TimingVertex> calculateArrivalRequireTimes(){
    	Pair<Float, TimingVertex> maxs;
    	
    	if(this.validClkSkew()) {
    		this.timingGraph.resetRequiredAndArrivalTimeVectors();
        	this.timingGraph.computeArrivalTimeVectorsTopologicalOrder();
        	Pair<Pair<Short, Short>, TimingVertex> maxIdTimingVertex = this.timingGraph.getMaxArrivalTimeFromVector();
        	float maxArrival = maxIdTimingVertex.getFirst().getFirst();
        	this.index = maxIdTimingVertex.getFirst().getSecond();
        	maxs = new Pair<>(maxArrival, maxIdTimingVertex.getSecond());
        	this.timingGraph.setTimingRequiredTimesVecotrTopologicalOrder(maxArrival);
    	}else {
    		this.timingGraph.resetRequiredAndArrivalTime();//0.01s for 20k design
    		this.timingGraph.computeArrivalTimesTopologicalOrder();//0.7s for 20k design
        	maxs = this.timingGraph.getMaxDelay();
        	this.timingGraph.setTimingRequirementTopologicalOrder(maxs.getFirst());//(Math.max(maxs.getFirst(), Treq - 500)); //0.8s for 20k design	
    	}
    	return maxs;
    }
    
    private boolean validClkSkew() {
    	return TimingGraph.validClkSkew();
    }
    
    /**
     * Gets and prints the critical path
     */
    static float pessimismA = (float) 1.03;
    static float pessimismB = 100;
    public static void setPessimismFactors(float a, short b) {
    	if(a > 1) {
    		pessimismA = a;
    	}
    	if(b > 0) {
    		pessimismB = b;
    	}
    }
    
    public void getCriticalPathInfo(Pair<Float, TimingVertex> maxDelayTimingVertex, Map<TimingEdge, Connection> timingEdgeConnctionMap,
    		boolean useRoutable, Map<Node, Routable> rnodesCreated){
    	TimingVertex maxV = maxDelayTimingVertex.getSecond();
    	float maxDelay = maxDelayTimingVertex.getFirst();
    	System.out.printf("%-30s %10d\n", "Timing requirement (ps):", Treq);
    	List<TimingEdge> criticalEdges = this.timingGraph.getCriticalTimingEdgesInOrder(maxV);
    	
    	short arr = 0;
    	short clkskew = 0;
    	for(TimingEdge e : criticalEdges) {
    		arr += e.getDelay();
    	}
    	
    	if(this.index >= 0) {// when there is valid clk skew data
    		String srcCR = this.getSrcClockRegion();
        	String dstCR = TimingGraph.getClockRegionOfCellPin(maxV.getName(), this.timingGraph.design);
        	List<Short> skewData = TimingGraph.clkSkewRouteDelay.getSkew().get(new Pair<>(srcCR, dstCR));
        	clkskew = (short) (skewData.get(2) - skewData.get(1) + skewData.get(3));
        	
        	System.out.printf("%-30s %10d %s\n\n", "Clock path skew (ps):", clkskew, "(" + srcCR + " -> " + dstCR + ")");
        	System.out.printf("%-30s %10d\n", "Critical path delay (ps):", (short) (arr - criticalEdges.get(0).getDst().getArrivalTimes()[this.index] - clkskew));
    	}else {
    		System.out.printf("%-30s %10d\n", "Critical path delay (ps):", (short) (arr - criticalEdges.get(0).getDelay() - clkskew));
    	}
    	
    	
    	System.out.printf("%-30s %10d\n\n", "Slack (ps):", (short) (Treq - maxDelay));
    	
    	
    	System.out.printf("%-30s\n", "With timing closure guarantee:");
    	short adjusted = (short) (pessimismA * (arr - criticalEdges.get(0).getDelay() - clkskew) + pessimismB);
    	System.out.printf("%-30s %10d\n", "Critical path delay (ps):", (short)adjusted);
    	System.out.printf("%-30s %10d\n\n", "Slack (ps):", (short) (Treq - adjusted));
    	
    	this.printPathDelayBreakDown(arr, criticalEdges, timingEdgeConnctionMap, useRoutable, rnodesCreated);
    	
    }
    
    private String getSrcClockRegion() {
    	if(this.index == 0) {
    		return "X2Y2";
    	}
    	if(this.index == 1) {
    		return "X2Y32";
    	}
    	if(this.index == 2) {
    		return "X3Y2";
    	}
    	if(this.index == 3) {
    		return "X3Y3";
    	}
    	return null;
    }
    
    /**
     * Gets and prints the given path from the TimingGraph
     */
    public void getSamplePathDelayInfo(List<String> verticesOfVivadoPath, Map<TimingEdge, Connection> timingEdgeConnctionMap, boolean routableBased, Map<Node, Routable> rnodesCreated) {
    	List<TimingEdge> edges = this.timingGraph.getTimingEdgeOfPath(verticesOfVivadoPath);
    	short totalDelay = 0;
    	for(TimingEdge e : edges) {
    		totalDelay += e.getDelay();
    	}
    	System.out.println("Total delay: " + totalDelay);
    	this.printPathDelayBreakDown(totalDelay, edges, timingEdgeConnctionMap, routableBased, rnodesCreated);
    }
    
    private void printPathDelayBreakDown(short arr, List<TimingEdge> criticalEdges, Map<TimingEdge, Connection> timingEdgeConnctionMap, boolean useRoutable, Map<Node, Routable> rnodesCreated) {
    	if(verbose) {
    		System.out.println("\nTimingEdges:");
        	int id = 0;
        	for(TimingEdge e : criticalEdges) {
        		System.out.println(String.format("%5d", id++) + "  " + e);
        	}
    	}
    	this.printTimingPathInTable(criticalEdges, arr);
    	if(rnodesCreated == null) return;
    	if(!verbose) return;
    	for(TimingEdge e : criticalEdges) {
    		if(timingEdgeConnctionMap.containsKey(e)){
    			System.out.println(timingEdgeConnctionMap.get(e));
    			if(useRoutable) {
    				List<Routable> groups = timingEdgeConnctionMap.get(e).getRnodes();
        			for(int iGroup = groups.size() -1; iGroup >= 0; iGroup--) {
        				System.out.println("\t " + groups.get(iGroup));
        			}
    			}else {
    				List<Node> nodes = timingEdgeConnctionMap.get(e).getNodes();
        			for(int iGroup = nodes.size() -1; iGroup >= 0; iGroup--) {
        				Routable rnode = rnodesCreated.get(nodes.get(iGroup));
        				if(rnode != null) {
        					System.out.println("\t " + rnode.getNode() + ", " + rnode.getNode().getIntentCode() + ", delay = " + (short) rnode.getDelay());
        				}else {
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
    	for(TimingEdge e : path){
	    	System.out.printf("%10d  %8d  %16d  %10d    %-25s\n",
					(short) e.getLogicDelay(),
					(short) e.getNetDelay(),
					(short) e.getIntraSiteDelay(),
					(short) e.getDelay(),
					e.getSrc());
			if(e.getNet() != null && e.getNet().getName() != null) {
				System.out.printf("%50s  %-25s\n", "", "  net: " + e.getNet().getName());
			}
    	}
    	System.out.printf("----------  --------------------------  ----------    ------------------------\n");
    	System.out.printf("%-38s  %10d\n", "Arrival time:", arr);
    	System.out.println("------------------------------------------------------------------------------");
    }
    
    static short Treq;
    /**
     * Set the timing requirement of the design
     */
    public void setTreq() {
    	Treq = (short) (getDesignTimingReq(this.design) * 1000);
    }
    
    public static float getDesignTimingReq(Design design) {
		float treq = 0;
		String timingConstraint = null;
		ConstraintGroup[] constraintGroups = {ConstraintGroup.NORMAL, ConstraintGroup.LATE};
		//TODO CHECK which constraint to use. The maximum one as default?
		for(ConstraintGroup group : constraintGroups) {
			List<String> constraints = design.getXDCConstraints(group);
			for(String s : constraints) {
				if(s.contains("-period")) {
					timingConstraint = s;
					break;
				}
			}
		}
		
		int startIndex = timingConstraint.indexOf("-period");
		treq = Float.valueOf(timingConstraint.substring(startIndex+7, startIndex+13));
		return treq;
	}
    
    /**
     * Calculates criticality for each connection
     */
    public float calculateCriticality(List<Connection> cons, float maxCriticality, float criticalityExponent, float maxDelay){
    	for(Connection c:cons){
    		c.resetCriticality();
    	}
    	float maxCriti = 0;
		if(this.index >= 0) {
			for(Connection c : cons){
	    		c.calculateCriticalityFromVector(maxDelay, maxCriticality, criticalityExponent, this.index);
	    		if(c.getCriticality() > maxCriti)
	    			maxCriti = c.getCriticality();
	    	}
		}else {
			for(Connection c : cons){
	    		c.calculateCriticality(maxDelay, maxCriticality, criticalityExponent);
	    		if(c.getCriticality() > maxCriti)
	    			maxCriti = c.getCriticality();
	    	}
		}
    	return maxCriti;
    }
    
    public boolean comparableFloat(Float a, float b){
    	return Math.abs(a - b) < Math.pow(10, -9);
    }

    /**
     * Builds the TimingModel and TimingGraph.
     * @return Indication of successful completion.
     */
    private boolean build(boolean isPartialRouting) {
    	if(this.routerTimer != null) this.routerTimer.createTimer("build timing model", "Initialization").start();
        timingModel.build();
        if(this.routerTimer != null) this.routerTimer.getTimer("build timing model").stop();
        
        if(this.routerTimer != null) this.routerTimer.createTimer("build timing graph", "Initialization").start();
        timingGraph.build(isPartialRouting);
        if(this.routerTimer != null) this.routerTimer.getTimer("build timing graph").stop();
        
        return postBuild();
    }

    private boolean postBuild() {
    	if(this.routerTimer != null) this.routerTimer.createTimer("post graph build", "Initialization").start();
        timingGraph.removeClockCrossingPaths();
        timingGraph.buildSuperGraphPaths();
        timingGraph.setOrderedTimingVertexLists();
        if(this.routerTimer != null) this.routerTimer.getTimer("post graph build").stop();
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
}

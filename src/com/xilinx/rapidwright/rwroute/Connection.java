/*
 * 
 * Copyright (c) 2021 Ghent University. 
 * All rights reserved.
 *
 * Author: Yun Zhou, Ghent University.
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

package com.xilinx.rapidwright.rwroute;

import java.util.ArrayList;
import java.util.List;

import com.xilinx.rapidwright.design.SitePinInst;
import com.xilinx.rapidwright.device.Node;
import com.xilinx.rapidwright.timing.TimingEdge;
import com.xilinx.rapidwright.timing.delayestimator.DelayEstimatorBase;

/**
 * A Connection instance represents a pair of source-sink {@link SitePinInst} instances of a {@link Net} instance.
 */
public class Connection implements Comparable<Connection>{
	/** A unique index of a connection */
	private final int id;
	/** The source and sink {@link SitePinInst}s of a connection */
	private SitePinInst source;
	private final SitePinInst sink;
	/** 
	 * The source and sink {@link RoutableNode} instances (rnodes) of a connection.
	 * They are created based on the INT tile nodes the source and sink SitePinInsts connect to, respectively.
	 */
	private Routable sourceRnode;
	private Routable sinkRnode;
	/** 
	 * true to indicate the source and the sink are connected through dedicated resources, 
	 * such as the carry chain connections and connections between cascaded BRAMs.
	 * These connections only need to be routed once after the iterative routing of other connections.
	 */
	private boolean direct;
	/** The {@link NetWrapper} instance indicating the net a connection belongs to */
	private NetWrapper netWrapper;
	/** 
	 * The half-perimeter wirelength of a connection based on the source and sink rnodes, 
	 * used for sorting connection and statistics of connection span.
	 */
	private short hpwl;
	/** Boundary coordinates of a connection's bounding box (BB), based on INT tile X and Y coordinates */
	private short xMinBB;
	private short xMaxBB;
	private short yMinBB;
	private short yMaxBB;	
	/** 
	 * TimingEdges associated to a connection.
	 * For LUT_6_2_* pins, there will be two timing edges mapped to the same pair of SitePinInsts.
	 */
	private List<TimingEdge> timingEdges;
	/** The criticality factor to indicate how timing-critical a connection is */
	private float criticality;
	/** List of Routable instances that make up of the route of a connection */
	private List<Routable> rnodes;
	
	/** To indicate if the route delay of a connection has been patched up, when there are consecutive long nodes */
	private boolean dlyPatched;
	/** true to indicate that a connection cross SLRs */
	private boolean crossSLR;
	/** List of nodes assigned to a connection to form the path for generating PIPs */
	private List<Node> nodes;
	
	public Connection(int id, SitePinInst source, SitePinInst sink, NetWrapper netWrapper){
		this.id = id;
		this.source = source;
		this.sink = sink;
		this.criticality = 0f;
		this.rnodes = new ArrayList<>();
		this.netWrapper = netWrapper;
		this.netWrapper.addCons(this);
	}
	
	/**
	 * Computes the half-perimeter wirelength of connection based on the source and sink rnodes.
	 */
	public void computeHpwl() {
		this.hpwl = (short) (Math.abs(this.sourceRnode.getEndTileXCoordinate() - this.sinkRnode.getEndTileXCoordinate()) + 1 
				+ Math.abs(this.sourceRnode.getEndTileYCoordinate() - this.sinkRnode.getEndTileYCoordinate()) + 1);
	}
	
	/**
	 * Computes the connection bounding box based on the geometric center of the net, source and sink rnodes.
	 * @param boundingBoxExtensionX To indicate the extension on top of the minimum bounding box in the horizontal direction.
	 *  @param boundingBoxExtensionY To indicate the extension on top of the minimum bounding box in the vertical direction.
	 * that contains the source rnode, sink rnode and the center of its {@link NetWrapper} Object.
	 * @param checkSLRCrossing A flag to indicate if SLR-crossing check is needed.
	 */
	public void computeConnectionBoundingBox(short boundingBoxExtensionX, short boundingBoxExtensionY, boolean checkSLRCrossing) {
		short xMin, xMax, yMin, yMax;
		short xNetCenter = (short) Math.ceil(this.netWrapper.getXCenter());
		short yNetCenter = (short) Math.ceil(this.netWrapper.getYCenter());
		xMax = this.maxOfThree(this.sourceRnode.getEndTileXCoordinate(), this.sinkRnode.getEndTileXCoordinate(), xNetCenter);
		xMin = this.minOfThree(this.sourceRnode.getEndTileXCoordinate(), this.sinkRnode.getEndTileXCoordinate(), xNetCenter);
		yMax = this.maxOfThree(this.sourceRnode.getEndTileYCoordinate(), this.sinkRnode.getEndTileYCoordinate(), yNetCenter);
		yMin = this.minOfThree(this.sourceRnode.getEndTileYCoordinate(), this.sinkRnode.getEndTileYCoordinate(), yNetCenter);
		this.xMaxBB = (short) (xMax + boundingBoxExtensionX);
		this.xMinBB = (short) (xMin - boundingBoxExtensionX);
		this.yMaxBB = (short) (yMax + boundingBoxExtensionY);
		this.yMinBB = (short) (yMin - boundingBoxExtensionY);
		
		/** allow more space for resource expansion of SLR-crossing connections */
		if(checkSLRCrossing) {		
			if(this.crossSLR()) {
				this.yMaxBB += 2 * boundingBoxExtensionY;
				this.yMinBB -= 2 * boundingBoxExtensionY;
			}
		}
		this.xMinBB = this.xMinBB < 0? -1:this.xMinBB;
		this.yMinBB = this.yMinBB < 0? -1:this.yMinBB;
	}
	
	private boolean crossSLR() {
		if(this.getSource().getTile().getSLR().equals(this.sink.getTile().getSLR())) {
			return false;
		}
		this.crossSLR = true;
		return true;
	}
	
	private short maxOfThree(short var1, short var2, short var3) {
		if(var1 >= var2 && var1 >= var3) {
			return var1;
		}else if(var2 >= var1 && var2 >= var3) {
			return var2;
		}else {
			return var3;
		}
	}
	
	private short minOfThree(short var1, short var2, short var3) {
		if(var1 <= var2 && var1 <= var3) {
			return var1;
		}else if(var2 <= var1 && var2 <= var3) {
			return var2;
		}else {
			return var3;
		}
	}
	
	/**
	 * Computes criticality of a connection.
	 * @param maxDelay The maximum delay to normalize the slack of a connection.
	 * @param maxCriticality The maximum criticality.
	 * @param criticalityExponent The exponent to separate critical connections and non-critical connections.
	 */
	public void calculateCriticality(float maxDelay, float maxCriticality, float criticalityExponent){
		float slackCon = Float.MAX_VALUE;
		for(TimingEdge e : this.getTimingEdges()) {
			float tmpslackCon = e.getDst().getRequiredTime() - e.getSrc().getArrivalTime() - e.getDelay();
			if(tmpslackCon < slackCon)
				slackCon = tmpslackCon;
		}
		
		float tempCriticality  = (1 - slackCon / maxDelay);
		
		tempCriticality = (float) Math.pow(tempCriticality, criticalityExponent) * maxCriticality;
    	
		if(tempCriticality > this.criticality)
			this.setCriticality(tempCriticality);
	}
	
	/**
	 * Computes criticality of a connection, used when doing timing-driven routing with clock skew data supplied.
	 * @param maxDelay The maximum delay to normalize the slack of a connection
	 * @param maxCriticality The maximum criticality.
	 * @param criticalityExponent The exponent to separate critical connections and non-critical connections.
	 * @param index The clock region index to indicate which bit of the required time and arrival time arrays should be used.
	 */
	public void calculateCriticalityFromVector(float maxDelay, float maxCriticality, float criticalityExponent, short index){
		float slackCon = Float.MAX_VALUE;
		for(TimingEdge e : this.getTimingEdges()) {
			float tmpslackCon = e.getDst().getRequiredTimes()[index] - e.getSrc().getArrivalTimes()[index] - e.getDelay();
			if(tmpslackCon < slackCon)
				slackCon = tmpslackCon;
		}
		
		float tempCriticality  = Math.min((1 - slackCon / maxDelay), 1);
		
		tempCriticality = (float) Math.pow(tempCriticality, criticalityExponent) * maxCriticality;
    	
		if(tempCriticality > this.criticality)
			this.setCriticality(tempCriticality);
	}
	
	/**
	 * Checks if a connection has any overused rnodes to indicate the congestion status.
	 * @return
	 */
	public boolean congested() {
		for(Routable rn : this.getRnodes()){
			if(rn.overUsed()) {
				return true;
			}
		}
		return false;
	}
	
	/**
	 * Checks if a connection is routed through any rnodes that have multiple drivers.
	 * @return
	 */
	public boolean useRnodesWithMultiDrivers() {
		for(Routable rn : this.getRnodes()){
			if(rn.hasMultiDrivers()) {
				return true;
			}
		}
		return false;
	}
	
	public void addRnode(Routable rn) {
		this.rnodes.add(rn);	
	}
	
	public void updateRouteDelay(){	
		this.setTimingEdgesDelay(this.getRouteDelay());
	}
	
	public void setTimingEdgesDelay(float routeDelay){
		for(TimingEdge e : this.getTimingEdges()){
			e.setRouteDelay(routeDelay);
		}
	}
	
	private float getRouteDelay() {
		float routeDelay = this.getRnodes().get(this.getRnodes().size() - 1).getDelay();
		for(int i = this.getRnodes().size() - 2; i >= 0; i--) {
			Routable rnode = this.getRnodes().get(i);
			Routable parent = this.getRnodes().get(i+1);
			routeDelay += rnode.getDelay() +
					DelayEstimatorBase.getExtraDelay(rnode.getNode(), DelayEstimatorBase.isLong(parent.getNode()));
		}
		return routeDelay;
	}
	
	public void setCriticality(float criticality){
		this.criticality = criticality;
	}
	
	public void resetCriticality(){
		this.criticality = 0;
	}
	
	public float getCriticality(){
		return this.criticality;
	}
	
	public void resetRoute(){
		this.getRnodes().clear();
		this.sink.setRouted(false);
	}
	
	public Routable getSourceRnode() {
		return sourceRnode;
	}

	public void setSourceRnode(Routable sourceNode) {
		this.sourceRnode = sourceNode;
	}

	public Routable getSinkRnode() {
		return sinkRnode;
	}

	public void setSinkRnode(Routable childRnode) {
		this.sinkRnode = childRnode;
	}
	
	public short getXMinBB() {
		return xMinBB;
	}

	public void setXMinBB(short xMinBB) {
		this.xMinBB = xMinBB;
	}

	public short getXMaxBB() {
		return xMaxBB;
	}

	public void setXMaxBB(short xMaxBB) {
		this.xMaxBB = xMaxBB;
	}

	public short getYMinBB() {
		return yMinBB;
	}

	public void setYMinBB(short yMinBB) {
		this.yMinBB = yMinBB;
	}

	public short getYMaxBB() {
		return yMaxBB;
	}

	public void setYMaxBB(short yMaxBB) {
		this.yMaxBB = yMaxBB;
	}

	public void setNetWrapper(NetWrapper netWrapper){
		this.netWrapper = netWrapper;
	}
	
	public NetWrapper getNetWrapper(){
		return this.netWrapper;
	}
	
	public SitePinInst getSource() {
		return source;
	}

	public void setSource(SitePinInst source) {
		this.source = source;
	}

	public boolean isDirect() {
		return direct;
	}

	public void setDirect(boolean direct) {
		this.direct = direct;
	}

	public SitePinInst getSink() {
		return sink;
	}

	public List<TimingEdge> getTimingEdges() {
		return timingEdges;
	}

	public void setTimingEdges(List<TimingEdge> timingEdges) {
		this.timingEdges = timingEdges;
	}

	public short getHpwl() {
		return hpwl;
	}

	public void setHpwl(short conHpwl) {
		this.hpwl = conHpwl;
	}

	public List<Routable> getRnodes() {
		return rnodes;
	}

	public void setRnodes(List<Routable> rnodes) {
		this.rnodes = rnodes;
	}

	public boolean isDlyPatched() {
		return dlyPatched;
	}

	public void setDlyPatched(boolean dlyPatched) {
		this.dlyPatched = dlyPatched;
	}

	public boolean isCrossSLR() {
		return crossSLR;
	}

	public void setCrossSLR(boolean crossSLR) {
		this.crossSLR = crossSLR;
	}
	
	public void newNodes(){
		this.setNodes(new ArrayList<>());
	}
	
	public void addNode(Node node){
		this.getNodes().add(node);
	}
	
	public List<Node> getNodes() {
		return nodes;
	}

	public void setNodes(List<Node> nodes) {
		this.nodes = nodes;
	}
	
	public void enlargeBoundingBox(int horizontalIncrement, int verticalIncrement) {
		this.xMinBB -= horizontalIncrement;
		this.xMaxBB += horizontalIncrement;
		this.yMinBB -= verticalIncrement;
		this.yMaxBB += verticalIncrement;
		this.xMinBB = this.xMinBB < 0? -1:this.xMinBB;
		this.yMinBB = this.yMinBB < 0? -1:this.yMinBB;
	}
	
	@Override
	public int hashCode(){
		return this.id;
	}
	
	@Override
	public int compareTo(Connection arg0) {
		if(this.netWrapper.getConnection().size() > arg0.getNetWrapper().getConnection().size()) {
			return 1;
		}else if(this.netWrapper.getConnection().size() == arg0.getNetWrapper().getConnection().size()) {
			if(this.getHpwl() > arg0.getHpwl()) {
				return 1;
			}else if(this.getHpwl() == arg0.getHpwl()) {
				if(this.hashCode() > arg0.hashCode()) {
					return -1;
				}
			}
		}else {
			return -1;
		}
		return -1;
	}
	
	public String bbRectangleString() {
		return "[( " + this.xMinBB + ", " + this.yMinBB + " ), -> ( " + this.xMaxBB + ", " + this.yMaxBB + " )]";
	}
	
	@Override
	public String toString() {
		StringBuilder s = new StringBuilder();
		s.append("Con ");
		s.append(String.format("%6s", this.id));
		s.append(", ");
		s.append("boundbox = " + this.bbRectangleString());
		s.append(", ");
		s.append("net = " + this.netWrapper.getNet().getName());
		s.append(", ");
		s.append(String.format("net fanout = %3s", this.netWrapper.getConnection().size()));
		s.append(", ");
		s.append(String.format("source = %s", this.getSource().getName()));
		s.append(", ");
		s.append("sink = " + this.getSink().getName());
		s.append(", ");
		s.append(String.format("delay = %4d ", (short)(this.getTimingEdges() == null? 0:this.getTimingEdges().get(0).getNetDelay())));
		s.append(", ");
		s.append(String.format("criticality = %4.3f ", this.getCriticality()));
		
		return s.toString();
	}
}

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

import com.xilinx.rapidwright.design.Net;
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
	/** The source and sink {@link SitePinInst} instances of a connection */
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
	 * {@link TimingEdge} instances associated to a connection.
	 * For LUT_6_2_* pins, there will be two timing edges mapped to the same connection.
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
		criticality = 0f;
		rnodes = new ArrayList<>();
		this.netWrapper = netWrapper;
		this.netWrapper.addConnection(this);
	}
	
	/**
	 * Computes the half-perimeter wirelength of connection based on the source and sink rnodes.
	 */
	public void computeHpwl() {
		hpwl = (short) (Math.abs(sourceRnode.getEndTileXCoordinate() - sinkRnode.getEndTileXCoordinate()) + 1 
				+ Math.abs(sourceRnode.getEndTileYCoordinate() - sinkRnode.getEndTileYCoordinate()) + 1);
	}
	
	/**
	 * Computes the connection bounding box based on the geometric center of the net, source and sink rnodes.
	 * @param boundingBoxExtensionX To indicate the extension on top of the minimum bounding box in the horizontal direction.
	 * @param boundingBoxExtensionY To indicate the extension on top of the minimum bounding box in the vertical direction.
	 * that contains the source rnode, sink rnode and the center of its {@link NetWrapper} Object.
	 * @param checkSLRCrossing A flag to indicate if SLR-crossing check is needed.
	 */
	public void computeConnectionBoundingBox(short boundingBoxExtensionX, short boundingBoxExtensionY, boolean checkSLRCrossing) {
		short xMin, xMax, yMin, yMax;
		short xNetCenter = (short) Math.ceil(netWrapper.getXCenter());
		short yNetCenter = (short) Math.ceil(netWrapper.getYCenter());
		xMax = maxOfThree(sourceRnode.getEndTileXCoordinate(), sinkRnode.getEndTileXCoordinate(), xNetCenter);
		xMin = minOfThree(sourceRnode.getEndTileXCoordinate(), sinkRnode.getEndTileXCoordinate(), xNetCenter);
		yMax = maxOfThree(sourceRnode.getEndTileYCoordinate(), sinkRnode.getEndTileYCoordinate(), yNetCenter);
		yMin = minOfThree(sourceRnode.getEndTileYCoordinate(), sinkRnode.getEndTileYCoordinate(), yNetCenter);
		xMaxBB = (short) (xMax + boundingBoxExtensionX);
		xMinBB = (short) (xMin - boundingBoxExtensionX);
		yMaxBB = (short) (yMax + boundingBoxExtensionY);
		yMinBB = (short) (yMin - boundingBoxExtensionY);
		
		// allow more space for resource expansion of SLR-crossing connections
		if(checkSLRCrossing) {		
			if(crossSLR()) {
				yMaxBB += 2 * boundingBoxExtensionY;
				yMinBB -= 2 * boundingBoxExtensionY;
			}
		}
		xMinBB = xMinBB < 0? -1:xMinBB;
		yMinBB = yMinBB < 0? -1:yMinBB;
	}
	
	private boolean crossSLR() {
		if(getSource().getTile().getSLR().equals(sink.getTile().getSLR())) {
			return false;
		}
		crossSLR = true;
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
		for(TimingEdge e : getTimingEdges()) {
			float tmpslackCon = e.getDst().getRequiredTime() - e.getSrc().getArrivalTime() - e.getDelay();
			if(tmpslackCon < slackCon)
				slackCon = tmpslackCon;
		}
		
		float tempCriticality  = (1 - slackCon / maxDelay);
		
		tempCriticality = (float) Math.pow(tempCriticality, criticalityExponent) * maxCriticality;
    	
		if(tempCriticality > criticality)
			setCriticality(tempCriticality);
	}
	
	/**
	 * Checks if a connection has any overused rnodes to indicate the congestion status.
	 * @return
	 */
	public boolean isCongested() {
		for(Routable rn : getRnodes()){
			if(rn.isOverUsed()) {
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
		for(Routable rn : getRnodes()){
			if(rn.hasMultiDrivers()) {
				return true;
			}
		}
		return false;
	}
	
	public void addRnode(Routable rn) {
		rnodes.add(rn);	
	}
	
	public void updateRouteDelay(){	
		setTimingEdgesDelay(getRouteDelay());
	}
	
	public void setTimingEdgesDelay(float routeDelay){
		for(TimingEdge e : getTimingEdges()){
			e.setRouteDelay(routeDelay);
		}
	}
	
	private float getRouteDelay() {
		float routeDelay = getRnodes().get(getRnodes().size() - 1).getDelay();
		for(int i = getRnodes().size() - 2; i >= 0; i--) {
			Routable rnode = getRnodes().get(i);
			Routable parent = getRnodes().get(i+1);
			routeDelay += rnode.getDelay() +
					DelayEstimatorBase.getExtraDelay(rnode.getNode(), DelayEstimatorBase.isLong(parent.getNode()));
		}
		return routeDelay;
	}
	
	public void setCriticality(float criticality){
		this.criticality = criticality;
	}
	
	public void resetCriticality(){
		criticality = 0;
	}
	
	public float getCriticality(){
		return criticality;
	}
	
	public void resetRoute(){
		getRnodes().clear();
		sink.setRouted(false);
	}
	
	public Routable getSourceRnode() {
		return sourceRnode;
	}

	public void setSourceRnode(Routable sourceNode) {
		sourceRnode = sourceNode;
	}

	public Routable getSinkRnode() {
		return sinkRnode;
	}

	public void setSinkRnode(Routable childRnode) {
		sinkRnode = childRnode;
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
		hpwl = conHpwl;
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
		setNodes(new ArrayList<>());
	}
	
	public void addNode(Node node){
		getNodes().add(node);
	}
	
	public List<Node> getNodes() {
		return nodes;
	}

	public void setNodes(List<Node> nodes) {
		this.nodes = nodes;
	}
	
	public void enlargeBoundingBox(int horizontalIncrement, int verticalIncrement) {
		xMinBB -= horizontalIncrement;
		xMaxBB += horizontalIncrement;
		yMinBB -= verticalIncrement;
		yMaxBB += verticalIncrement;
		xMinBB = xMinBB < 0? -1:xMinBB;
		yMinBB = yMinBB < 0? -1:yMinBB;
	}
	
	@Override
	public int hashCode(){
		return id;
	}
	
	@Override
	public int compareTo(Connection arg0) {
		if(netWrapper.getConnections().size() > arg0.getNetWrapper().getConnections().size()) {
			return 1;
		}else if(netWrapper.getConnections().size() == arg0.getNetWrapper().getConnections().size()) {
			if(this.getHpwl() > arg0.getHpwl()) {
				return 1;
			}else if(getHpwl() == arg0.getHpwl()) {
				if(hashCode() > arg0.hashCode()) {
					return -1;
				}
			}
		}else {
			return -1;
		}
		return -1;
	}
	
	public String bbRectangleString() {
		return "[( " + xMinBB + ", " + yMinBB + " ), -> ( " + xMaxBB + ", " + yMaxBB + " )]";
	}
	
	@Override
	public String toString() {
		StringBuilder s = new StringBuilder();
		s.append("Con ");
		s.append(String.format("%6s", id));
		s.append(", ");
		s.append("boundbox = " + bbRectangleString());
		s.append(", ");
		s.append("net = " + netWrapper.getNet().getName());
		s.append(", ");
		s.append(String.format("net fanout = %3s", netWrapper.getConnections().size()));
		s.append(", ");
		s.append(String.format("source = %s", getSource().getName()));
		s.append(", ");
		s.append("sink = " + getSink().getName());
		s.append(", ");
		s.append(String.format("delay = %4d ", (short)(getTimingEdges() == null? 0:getTimingEdges().get(0).getNetDelay())));
		s.append(", ");
		s.append(String.format("criticality = %4.3f ", getCriticality()));
		
		return s.toString();
	}
	
}

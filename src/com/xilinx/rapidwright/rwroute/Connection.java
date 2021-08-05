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
 * A connection represents a pair of source-sink SitePinInsts within a net
 *
 */
public class Connection implements Comparable<Connection>{
	/** A unique index of this connection */
	private final int id;
	/** The source and sink SitePinInsts of this connection */
	private SitePinInst source;
	private final SitePinInst sink;
	/** The source and sink RoutableNodes (rnodes) of this connection 
	 * They are created based on the INT tile nodes the source and sink SitePinInsts connect to, respectively*/
	private Routable sourceRnode;
	private Routable sinkRnode;
	/** true to indicate the source and the sink are connected through dedicated resources 
	 * such as the carry chain connections and connections between cascaded BRAMs 
	 * these connections only need to be routed once at the end of routing*/
	private boolean direct;
	/** The net that this connection belongs to */
	private NetWrapper net;
	/** The half-perimeter wirelength of connection based on the source and sink rnodes, used for sorting connection */
	private short hpwl;
	/** Boundary coordinates of this connection's bounding box, based on INT tile X and Y coordinates */
    private short x_min_b;
    private short x_max_b;
    private short y_min_b;
    private short y_max_b;
	
    /** TimingEdges associated to this connection 
     * FOR LUT_6_2_* SITEPININSTS, there will be two timing edges mapped to the same pair of SitePinInsts*/
    private List<TimingEdge> timingEdges;
    /** The criticality factor to indicate how timing-critical this connection is */
    private float criticality;
    /** List of Routable that make up of the route of this connection */
	private List<Routable> rnodes;
	
	/** To indicate if the route delay of this connection has been patched up, when there are consecutive long nodes */
	private boolean dlyPatched;
	/** true to indicate that this connection cross SLRs */
	private boolean crossSLR;
	/** List of Nodes assigned to this connection to form the path for gennerating PIPs */
	private List<Node> nodes;
	
	public Connection(int id, SitePinInst source, SitePinInst sink){
		this.id = id;
		this.source = source;
		this.sink = sink;
		this.criticality = 0f;
		this.rnodes = new ArrayList<>();
	}
	
	/**
	 * Computes the half-perimeter wirelength of connection based on the source and sink rnodes
	 */
	public void computeHpwl() {
		this.hpwl = (short) (Math.abs(this.sourceRnode.getX() - this.sinkRnode.getX()) + 1 
				+ Math.abs(this.sourceRnode.getY() - this.sinkRnode.getY()) + 1);
	}
	
	/**
	 * Computes the connection bounding box based on the geometric center of the net, source and sink rnodes
	 * @param boundingBoxExtension To indicate the extension on top of the minimum bounding box
	 * @param checkSLRCrossing A flag to indicate if SLR-crossing check is needed
	 */
	public void computeConnectionBoundingBox(short boundingBoxExtension, boolean checkSLRCrossing) {
		short x_min, x_max, y_min, y_max;
		short x_geo = (short) Math.ceil(this.net.getX_geo());
		short y_geo = (short) Math.ceil(this.net.getY_geo());
		x_max = this.maxOfThree(this.sourceRnode.getX(), this.sinkRnode.getX(), x_geo);
		x_min = this.minOfThree(this.sourceRnode.getX(), this.sinkRnode.getX(), x_geo);
		y_max = this.maxOfThree(this.sourceRnode.getY(), this.sinkRnode.getY(), y_geo);
		y_min = this.minOfThree(this.sourceRnode.getY(), this.sinkRnode.getY(), y_geo);
		this.x_max_b = (short) (x_max + boundingBoxExtension);
		this.x_min_b = (short) (x_min - boundingBoxExtension);
		this.y_max_b = (short) (y_max + 5 * boundingBoxExtension);
		this.y_min_b = (short) (y_min - 5 * boundingBoxExtension);
		
		/** allow more space for resource expansion of SLR-crossing connections */
		if(checkSLRCrossing) {		
			if(this.crossSLR()) {
				this.y_max_b += 10 * boundingBoxExtension;
				this.y_min_b -= 10 * boundingBoxExtension;
			}
		}
		this.x_min_b = this.x_min_b < 0? -1:this.x_min_b;
		this.y_min_b = this.y_min_b < 0? -1:this.y_min_b;
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
	 * Computes criticality of this connection
	 * @param maxDelay The maximum delay to normalize the slack of this connection
	 * @param maxCriticality The maximum criticality
	 * @param criticalityExponent The exponent to separate critical connections and non-critical connections
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
	 * Computes criticality of this connection, used when doing timing-driven routing with clock skew data supplied
	 * @param maxDelay The maximum delay to normalize the slack of this connection
	 * @param maxCriticality The maximum criticality
	 * @param criticalityExponent The exponent to separate critical connections and non-critical connections
	 * @param index The clock region index to indicate which bit of the required time and arrival time arrays should be used
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
	 * Checks if this connection has any overused rnodes to indicate the congestion status
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
	 * Checks if this connection has any rnode driven by mutiple fanin rnodes
	 * @return
	 */
	public boolean hasMultiFaninNode() {
		for(Routable rn : this.getRnodes()){
			if(rn.hasMultiFanin()) {
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
	
	public short getX_min_b() {
		return x_min_b;
	}

	public void setX_min_b(short x_min_b) {
		this.x_min_b = x_min_b;
	}

	public short getX_max_b() {
		return x_max_b;
	}

	public void setX_max_b(short x_max_b) {
		this.x_max_b = x_max_b;
	}

	public short getY_min_b() {
		return y_min_b;
	}

	public void setY_min_b(short y_min_b) {
		this.y_min_b = y_min_b;
	}

	public short getY_max_b() {
		return y_max_b;
	}

	public void setY_max_b(short y_max_b) {
		this.y_max_b = y_max_b;
	}

	public void setNet(NetWrapper net){
		this.net = net;
	}
	
	public NetWrapper getNet(){
		return this.net;
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
		this.x_min_b -= horizontalIncrement;
		this.x_max_b += horizontalIncrement;
		this.y_min_b -= verticalIncrement;
		this.y_max_b += verticalIncrement;
		this.x_min_b = this.x_min_b < 0? -1:this.x_min_b;
		this.y_min_b = this.y_min_b < 0? -1:this.y_min_b;
	}
	
	@Override
	public int hashCode(){
		return this.id;
	}
	
	@Override
	public int compareTo(Connection arg0) {
		if(this.net.getConnection().size() > arg0.getNet().getConnection().size()) {
			return 1;
		}else if(this.net.getConnection().size() == arg0.getNet().getConnection().size()) {
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
		return "[( " + this.x_min_b + ", " + this.y_min_b + " ), -> ( " + this.x_max_b + ", " + this.y_max_b + " )]";
	}
	
	@Override
	public String toString() {
		StringBuilder s = new StringBuilder();
		s.append("Con ");
		s.append(String.format("%6s", this.id));
		s.append(", ");
		s.append("boundbox = " + this.bbRectangleString());
		s.append(", ");
		s.append("net = " + this.net.getNet().getName());
		s.append(", ");
		s.append(String.format("net fanout = %3s", this.net.getConnection().size()));
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

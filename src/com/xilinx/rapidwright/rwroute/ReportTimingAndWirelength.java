package com.xilinx.rapidwright.rwroute;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.xilinx.rapidwright.design.Design;
import com.xilinx.rapidwright.design.Net;
import com.xilinx.rapidwright.design.NetType;
import com.xilinx.rapidwright.device.IntentCode;
import com.xilinx.rapidwright.device.Node;
import com.xilinx.rapidwright.device.PIP;
import com.xilinx.rapidwright.device.TileTypeEnum;
import com.xilinx.rapidwright.rwroute.GlobalSignalRouting.RoutingNode;
import com.xilinx.rapidwright.timing.delayestimator.DelayEstimatorBase;

public class ReportTimingAndWirelength extends PartialRouter{
	//TODO do not extend from the router, make the methods stand-alone
	public Design design;
	public int rnodeId;
	public long wirelength = 0;
	public long usedNodes = 0;
	public int totalRoutableNets = 0;
	
	public Map<IntentCode, Long> typeUsage = new HashMap<>();
	public Map<IntentCode, Long> typeLength = new HashMap<>();
	
	public boolean timingDriven;
	
	public ReportTimingAndWirelength(Design design, Configuration config) {	
		super(design, config);
		this.timingDriven = config.isTimingDriven();
		this.design = design;
	}
	
	public static void main(String[] args) {
		if(args.length < 2){
			System.out.println("USAGE:\n <input.dcp> <string to indicate redirecting of timing model data path>");
		}
		
		Design design = Design.readCheckpoint(args[0]);
		
		Configuration config = new Configuration();
		config.parseArguments(1, args);
		config.setPartialRouting(true);
		config.setTimingDriven(true);
			
		ReportTimingAndWirelength reporter = new ReportTimingAndWirelength(design, config);
		
		reporter.computeStatisticsToReport();
		
		reporter.printStatistics(reporter);
		
	}
	
	private void printStatistics(ReportTimingAndWirelength reporter) {
		System.out.println("\n");
		System.out.println("Total nodes: " + reporter.usedNodes);
		System.out.println("Routable nets: " + reporter.totalRoutableNets);
		System.out.println("Total wirelength: " + reporter.wirelength);
		
		System.out.println("\n INT_Nodes" + "\tUsage " + "\tLength ");
		for(IntentCode ic : nodeTypes) {
			long usage = reporter.typeUsage.getOrDefault(ic, (long)0);
			long length = reporter.typeLength.getOrDefault(ic, (long)0);
			System.out.println(" " + ic + "\t" + usage + "\t" + length);
		}
	}
	
	static List<IntentCode> nodeTypes = new ArrayList<>();
	static {
		nodeTypes.add(IntentCode.NODE_SINGLE);
		nodeTypes.add(IntentCode.NODE_DOUBLE);
		nodeTypes.add(IntentCode.NODE_VQUAD);
		nodeTypes.add(IntentCode.NODE_HQUAD);
		nodeTypes.add(IntentCode.NODE_VLONG);
		nodeTypes.add(IntentCode.NODE_HLONG);
		nodeTypes.add(IntentCode.NODE_LOCAL);
		nodeTypes.add(IntentCode.NODE_PINBOUNCE);
		nodeTypes.add(IntentCode.NODE_PINFEED);
		
	}
	
	private void computeStatisticsToReport() {	
		this.computeWLDlyStatisticsForEachNet();
		
		if(this.timingDriven) {
			this.maxDelayAndTimingVertex = this.timingManager.calculateArrivalRequireTimes();
			System.out.println();
			this.timingManager.getCriticalPathInfo(this.maxDelayAndTimingVertex, this.timingEdgeConnectionMap, false, null);
			
		}
	}
	
	private void computeWLDlyStatisticsForEachNet() {		
		for(Net n : this.design.getNets()) {
			if (!n.getType().equals(NetType.WIRE)) continue;
			if(!RouterHelper.isRoutableNetWithSourceSinks(n)) continue;
			if(n.getSource().toString().contains("CLK")) continue;
				
			NetWrapper netplus = this.initializeNetAndCons(n, this.config.getBoundingBoxExtension(), false); 
			
			List<Node> netNodes = RouterHelper.getNodesOfNet(n);
			
			for(Node node:netNodes){
				
				if(node.getTile().getTileTypeEnum() != TileTypeEnum.INT) continue;
				usedNodes++;
				
				this.putWireLengthInfo(node, this.typeUsage, this.typeLength);
				
			}
			
			this.totalRoutableNets++;
			
			if(this.timingDriven) {
				this.setTimingEdgesOfCons(netplus.getConnection());
				this.setAccumulativeDelayOfNetNodes(netplus);
			}
			
		}
	}
	
	private void setAccumulativeDelayOfNetNodes(NetWrapper netplus) {
		List<PIP> pips = netplus.getNet().getPIPs();
		
		Map<Node, RoutingNode> nodeRoutingNodeMap = new HashMap<>();
		boolean firstPIP = true;
		for(PIP pip : pips) {
			Node startNode = pip.getStartNode();
			RoutingNode startrn = RouterHelper.createRoutingNode(startNode, nodeRoutingNodeMap);
			if(firstPIP) startrn.setDelayFromSource(0);
			firstPIP = false;
			
			Node endNode = pip.getEndNode();
			RoutingNode endrn = RouterHelper.createRoutingNode(endNode, nodeRoutingNodeMap);
			endrn.setPrev(startrn);
			float delay = 0;
			if(endNode.getTile().getTileTypeEnum() == TileTypeEnum.INT) {
				delay = this.computeRoutingNodeDelay(endrn)
						+ DelayEstimatorBase.getExtraDelay(endNode, DelayEstimatorBase.isLong(startNode));
			}
			
			endrn.setDelayFromSource(startrn.getDelayFromSource() + delay);
			
		}
		
		for(Connection c : netplus.getConnection()) {
			if(c.isDirect()) continue;
			Node sinkNode = c.getSinkRnode().getNode();
			RoutingNode sinkrn = nodeRoutingNodeMap.get(sinkNode);
			if(sinkrn == null) continue;
			float cdelay = sinkrn.getDelayFromSource();
			if(c.getTimingEdges() == null) continue;
			c.setTimingEdgesDelay(cdelay);	
		}	
	}
	
	private float computeRoutingNodeDelay(RoutingNode routingNode) {
		return RouterHelper.computeNodeDelay(estimator, routingNode.getNode());	
	}
	
	
	private void putWireLengthInfo(Node node, Map<IntentCode, Long> typeUsage, Map<IntentCode, Long> typeLength) {
		int wl = RouterHelper.getLengthOfNode(node);
		
		this.wirelength += wl;
		
		RouterHelper.putNodeTypeLength(node, wl, this.typeUsage, this.typeLength);
	}
	
}

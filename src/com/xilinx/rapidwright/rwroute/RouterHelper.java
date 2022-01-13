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

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;

import com.xilinx.rapidwright.design.Design;
import com.xilinx.rapidwright.design.Net;
import com.xilinx.rapidwright.design.SitePinInst;
import com.xilinx.rapidwright.device.BELPin;
import com.xilinx.rapidwright.device.IntentCode;
import com.xilinx.rapidwright.device.Node;
import com.xilinx.rapidwright.device.PIP;
import com.xilinx.rapidwright.device.Tile;
import com.xilinx.rapidwright.device.TileTypeEnum;
import com.xilinx.rapidwright.device.Wire;
import com.xilinx.rapidwright.timing.TimingEdge;
import com.xilinx.rapidwright.timing.TimingManager;
import com.xilinx.rapidwright.timing.delayestimator.DelayEstimatorBase;
import com.xilinx.rapidwright.util.Pair;

/**
 * A collection of supportive methods for the router.
 */
public class RouterHelper {
	/**
	 * Checks if a {@link Net} instance has source and sink {@link SitePinInst} instances to be routable.
	 * @param net The net to be checked.
	 * @return true, if the net has source and sink pins.
	 */
	public static boolean isRoutableNetWithSourceSinks(Net net){
		return net.getSource() != null && net.getSinkPins().size() > 0;
	}
	
	/**
	 * Checks if a {@link Net} instance is driver-less or load-less.
	 * @param net The net to be checked.
	 * @return true, if the nets is driver-less or load-less.
	 */
	public static boolean isDriverLessOrLoadLessNet(Net net){
		return (isDriverLessNet(net) || isLoadLessNet(net));
	}
	
	/**
	 * Checks if a {@link Net} instance is driver-less.
	 * @param net The net to be checked.
	 * @return true, if the net does not have a source pin.
	 */
	public static boolean isDriverLessNet(Net net) {
		return (net.getSource() == null && net.getSinkPins().size() > 0);
	}
	
	/**
	 * Checks if a {@link Net} instance is load-less.
	 * @param net The net to be checked.
	 * @return true, if the net does not have sink pins.
	 */
	public static boolean isLoadLessNet(Net net) {
		return (net.getSource() != null && net.getSinkPins().size() == 0);
	}
	
	/**
	 * Checks if a {@link Net} instance is internally routed net.
	 * @param net The net to be checked.
	 * @return true, if the net does not have pins.
	 */
	public static boolean isInternallyRoutedNet(Net net){
		return net.getPins().size() == 0;
	}
	
	/**
	 * Checks if the source-sink connection is an external connection driven by COUT.
	 * If true, the source pin swapped to the alternative pin of the {@link Net} instance.
	 * Because COUT only connects to CIN.
	 * @param source The source SitePinInst of this connection.
	 * @param sink The sink SitePinInst of this connection.
	 * @return true, if the source is a COUT while the sink is not CIN.
	 */
	public static boolean isExternalConnectionToCout(SitePinInst source, SitePinInst sink){
		return source.getName().equals("COUT") && (!sink.getName().equals("CIN"));
	}
	
	/**
	 * Gets a {@link Node} instance that connects to an INT {@link Tile} instance from an output {@link SitePinInst} instance.
	 * @param output The output pin.
	 * @return A node that connects to an INT tile from an output pin.
	 */
	public static Node projectOutputPinToINTNode(SitePinInst output) {
		Node intNode = output.getConnectedNode();
		int watchdog = 5;
		
		while(intNode.getAllDownhillNodes().get(0).getTile().getTileTypeEnum() != TileTypeEnum.INT) {
			List<Node> downhills = intNode.getAllDownhillNodes();
			intNode = downhills.get(0);
			if(downhills.size() > 1) {
				int i = 1;
				while(intNode.getAllDownhillNodes().size() == 0) {
					intNode = downhills.get(i);
					i++;
				}
			}
			watchdog--;
			if(intNode.getAllDownhillNodes().size() == 0 || watchdog < 0) {
				intNode = null;
				break;
			}
		}
		return intNode;
	}
	
	/**
	 * Gets a list of {@link Node} instances that connect an input {@link SitePinInst} instance to an INT {@link Tile} instance.
	 * @param input The input pin.
	 * @return A list of nodes from the input SitePinInst to an INT tile.
	 */
	public static List<Node> projectInputPinToINTNode(SitePinInst input) {
		List<Node> sinkToSwitchBoxPath = new ArrayList<>();		
		RoutingNode sink = new RoutingNode(input.getConnectedNode());
		sink.setPrev(null);
		Queue<RoutingNode> q = new LinkedList<>();
		q.add(sink);
		int watchdog = 1000;		
		while(!q.isEmpty()) {
			RoutingNode n = q.poll();
			if(n.getNode().getTile().getTileTypeEnum() == TileTypeEnum.INT) {
				while(n != null) {
					sinkToSwitchBoxPath.add(n.getNode());
					n = n.getPrev();
				}
				return sinkToSwitchBoxPath;
			}
			for(Node uphill : n.getNode().getAllUphillNodes()) {
				if(uphill.getAllUphillNodes().size() == 0) continue;
				RoutingNode prev = new RoutingNode(uphill);
				prev.setPrev(n);
				q.add(prev);
			}
			watchdog--;
			if(watchdog < 0) {
				break;
			}		
		}
		
		return sinkToSwitchBoxPath;
	}
	
	public static Tile getUpstreamINTTileOfClkIn(SitePinInst clkIn) {
		List<Node> pathToINTTile = projectInputPinToINTNode(clkIn);
		if(pathToINTTile.isEmpty()) {
			throw new RuntimeException("ERROR: CLK_IN does not connet to INT Tile directly");
		}
		
		return pathToINTTile.get(0).getTile();
	}
	
	/**
	 * Gets a list of {@link PIP} instances for routing a connection.
	 * @param connection The {@link Connection} instance that has been routed with a list of {@link Node} instances.
	 * @return A list of PIPs for the connection.
	 */
	public static List<PIP> getConnectionPIPs(Connection connection){
		return getPIPsFromListOfReversedNodes(connection.getNodes());
	}
	
	/**
	 * Gets a list of {@link PIP} instances from a list of {@link Node} instances in a reversed order.
	 * @param connectionNodes The list of nodes of a routed {@link Connection} instance.
	 * @return A list of PIPs generated from the list of nodes.
	 */
	public static List<PIP> getPIPsFromListOfReversedNodes(List<Node> connectionNodes){
		List<PIP> connectionPIPs = new ArrayList<>();
		if(connectionNodes == null) return connectionPIPs;
		// Nodes of a connection are added to the list starting from its sink to its source
		for(int i = connectionNodes.size() -1; i > 0; i--){
			Node driver = connectionNodes.get(i);
			Node load = connectionNodes.get(i-1);		
			PIP pip = findPIPbetweenNodes(driver, load);	
			if(pip != null){
				connectionPIPs.add(pip);
			}else{
				System.err.println("ERROR: Null PIP connecting these two nodes: " + driver.toString() + ", " + load.toString());
			}
		}
		return connectionPIPs;
	}
	
	/**
	 * Finds the {@link PIP} instance that connects two {@link Node} instances.
	 * @param driver The driver node.
	 * @param load The load node.
	 * @return The PIP connecting the two nodes.
	 */
	public static PIP findPIPbetweenNodes(Node driver, Node load){
		PIP pip = getPIP(load.getTile(), driver.getAllWiresInNode(), load.getWire());
		if(pip == null) {
			// for other scenarios regarding bidirectional nodes, such as LAG tile nodes, LAG_LAG_X12Y250/LAG_MUX_ATOM_0_TXOUT to node LAG_LAG_X12Y310/UBUMP0 
			pip = getPIP(driver, load);
		}
		
		return pip;
	}
	
	/**
	 * Gets the {@link PIP} instance based on the {@link Tile} instance of a node, its driver node wires and its base {@link Wire} instance.
	 * @param loadTile The base tile of the load node.
	 * @param driverWires All wires in the driver node.
	 * @param loadWire The wire of the load node.
	 * @return The PIP that connects one of the wires in the driver node and the wire of the load node.
	 */
	public static PIP getPIP(Tile loadTile, Wire[] driverWires, int loadWire) {
		PIP pip = null;
		for(Wire wire : driverWires){
			if(wire.getTile().equals(loadTile)){
				pip = loadTile.getPIP(wire.getWireIndex(), loadWire);
				if(pip != null){
					break;
				}
			}
		}
		return pip;
	}
	
	/**
	 * Gets the {@link PIP} instance from a driver {@link Node} instance to a load {@link Node} instance.
	 * @param driver The driver node.
	 * @param load The load node.
	 * @return The PIP from the driver node to the load node.
	 */
	public static PIP getPIP(Node driver, Node load) {
		PIP pip = null;
		for(PIP p : driver.getAllDownhillPIPs()) {
			if(p.getEndNode().equals(load))
				return p;
		}
		for(PIP p : driver.getAllUphillPIPs()) {
			if(p.getStartNode().equals(load))
				return p;
		}
		return pip;
	}
	
	/**
	 * Gets {@link Node} instances of a {@link Net} instance from its {@link PIP} instances, 
	 * in the order of source pin node, sink pin nodes, and other intermediate nodes.
	 * @param net The target net.
	 * @return All nodes used by the net.
	 */
	public static List<Node> getNodesOfNet(Net net){
		List<Node> nodes = new ArrayList<>();
		if(net.getSource() != null) nodes.add(net.getSource().getConnectedNode());
		for(SitePinInst pin : net.getSinkPins()) {
			Node pinNode = pin.getConnectedNode();
			if(pinNode != null) {
				nodes.add(pinNode);
			}else {
				System.err.println("ERROR: No node connects to pin " + pin + ", net " + net);
			}
		}
		
		for(PIP pip : net.getPIPs()) {
			Node end = pip.getEndNode();
			Node start = pip.getStartNode();
			if(!nodes.contains(end)) nodes.add(end);
			if(!nodes.contains(start)) nodes.add(start);
		}	
		return nodes;
	}
	
	/**
	 * Gets a set of {@link Node} instances used by a {@link Net} instance.
	 * @param net The target net.
	 * @return A set of nodes used by a net.
	 */
	public static Set<Node> getUsedNodesOfNet(Net net){
		Set<Node> nodes = new HashSet<>();
		if(net.getSource() != null) nodes.add(net.getSource().getConnectedNode());
		for(SitePinInst pin : net.getSinkPins()) {
			Node pinNode = pin.getConnectedNode();
			if(pinNode != null) {
				nodes.add(pinNode);
			}else {
				System.err.println("ERROR: No node connects to pin " + pin + ", net " + net);
			}
		}
		
		for(PIP pip : net.getPIPs()) {
			Node end = pip.getEndNode();
			Node start = pip.getStartNode();
			nodes.add(end);
			nodes.add(start);
		}
		
		return nodes;
	}
	
	/**
	 * Checks if a DSP {@link BELPin} instance is invertible.
	 * @param belPin The bel pin in question.
	 * @return true, if the bel pin is invertible.
	 */
	private static boolean isInvertibleDSPBELPin(BELPin belPin) {
		if(belPin.getBELName().equals("CLKINV")) {
			//NEED TO BE INVERTED when BEL.canInvert returns false
			return true;
		}
		return belPin.getBEL().canInvert();
	}
	
	/**
	 * Inverts all possible GND sink pins to VCC pins.
	 * @param design The target design.
	 * @param staticNet The static net, should be VCC only.
	 */
	public static void invertPossibleGndPinsToVccPins(Design design, Net staticNet) {
		if(!staticNet.getName().equals(Net.GND_NET)) return;
		List<SitePinInst> toInvertPins = new ArrayList<>();
		for(SitePinInst currSitePinInst:staticNet.getPins()) {
			BELPin[] belPins = currSitePinInst.getSiteInst().getSiteWirePins(currSitePinInst.getName());
			// DSP or BRAM
			if(belPins.length == 2) {
				for(BELPin belPin : belPins){
					if(belPin.isSitePort())	continue;
					// DO NOT invert CLK_OPTINV_CLKB_L and CLK_OPTINV_CLKB_U
					if(belPin.getBEL().getName().contains("CLKB")) continue;
					if(currSitePinInst.toString().contains("RAM")) {
						if(belPin.getBEL().canInvert()) {
							// SRST2 of SLICE also has an inverter, but should not be invertible
							toInvertPins.add(currSitePinInst);
		                }
					}else if (currSitePinInst.toString().contains("DSP")) {
						if(isInvertibleDSPBELPin(belPin)) {
							toInvertPins.add(currSitePinInst);
						}
					}
	           }
			}
		}
		
		for(SitePinInst toinvert:toInvertPins) {
			boolean success = staticNet.removePin(toinvert, true);
            success |= design.getVccNet().addPin(toinvert);
            if(!success) {
                  throw new RuntimeException("ERROR: Couldn't invert site pin " +
                		  toinvert);
            }
		}
	}
	
	/**
	 * Gets the wirelength of a node.
	 * @param node The target node.
	 * @return The wirelength of the node.
	 */
	public static int getLengthOfNode(Node node) {
		Tile entry = node.getTile();	
		Tile exit = null;
		List<Tile> intTiles = new ArrayList<>();
		for(Wire w : node.getAllWiresInNode()) {
			Tile wireTile = w.getTile();
			if(wireTile.getTileTypeEnum() == TileTypeEnum.INT) {
				if(!intTiles.contains(wireTile)) {
					intTiles.add(wireTile);
				}
			}
		}	
		if(intTiles.size() > 1) {
			exit = intTiles.get(1);
		}else if(intTiles.size() == 1) {
			exit = entry;
		}		
		return Math.abs(entry.getTileXCoordinate()- exit.getTileXCoordinate())
				+ Math.abs(entry.getTileYCoordinate() - exit.getTileYCoordinate());
	}
	
	/**
	 * Adds the {@link IntentCode} and wirelength of an used node to the map.
	 * @param node The target node.
	 * @param wlNode The wirelength of the node.
	 * @param typeUsage The map between each node type and the number of used nodes for the node type.
	 * @param typeLength The map between each node type and the total wirelength of used nodes for the node type.
	 */
	public static void addNodeTypeLengthToMap(Node node, int wlNode, Map<IntentCode, Long> typeUsage, Map<IntentCode, Long> typeLength) {
		IntentCode ic = node.getIntentCode();
		Long counter = typeUsage.get(ic);
		if(counter == null) {
			counter = (long) 1;
		}else {
			counter++;
		}
		typeUsage.put(ic, counter);
		
		Long length = typeLength.get(ic);
		if(length == null) {
			length = (long) wlNode;
		}else {
			length += wlNode;
		}
		typeLength.put(ic, length);
	}	
	
	/**
	 * Gets a map containing net delay for each sink pin paired with an INT tile node of a routed net.
	 * @param net The target routed net.
	 * @param estimator An instantiation of DelayEstimatorBase.
	 * @return The map containing net delay for each sink pin paired with an INT tile node of a routed net.
	 */
	public static Map<Pair<SitePinInst, Node>, Short> getSourceToSinkINTNodeDelays(Net net, DelayEstimatorBase estimator) {
		List<PIP> pips = net.getPIPs();
		Map<Node, RoutingNode> nodeRoutingNodeMap = new HashMap<>();
		boolean firstPIP = true;
		for(PIP pip : pips) {
			Node startNode = pip.getStartNode();
			RoutingNode startrn = createRoutingNode(pip.getStartNode(), nodeRoutingNodeMap);
			
			if(firstPIP) {
				startrn.setDelayFromSource(0);
			}
			firstPIP = false;
			
			Node endNode = pip.getEndNode();
			RoutingNode endrn = createRoutingNode(endNode, nodeRoutingNodeMap);
			endrn.setPrev(startrn);
			int delay = 0;
			if(endNode.getTile().getTileTypeEnum() == TileTypeEnum.INT) {//device independent?
				delay = computeNodeDelay(estimator, endNode)
						+ DelayEstimatorBase.getExtraDelay(endNode, DelayEstimatorBase.isLong(startNode));
			}
			
			endrn.setDelayFromSource(startrn.getDelayFromSource() + delay);		
		}
		
		Map<Pair<SitePinInst, Node>, Short> sinkNodeDelays = new HashMap<>();
		for(SitePinInst sink : net.getSinkPins()) {
			Node sinkNode = sink.getConnectedNode();
			if(!(sinkNode.getTile().getTileTypeEnum() == TileTypeEnum.INT)) {
				sinkNode = projectInputPinToINTNode(sink).get(0);
			}
			
			short routeDelay = (short) nodeRoutingNodeMap.get(sinkNode).getDelayFromSource();
			sinkNodeDelays.put(new Pair<>(sink, sinkNode), routeDelay);
		}
		
		return sinkNodeDelays;
	}
	
	/**
	 * Creates a {@link RoutingNode} Object based on a {@link Node} Object, avoiding duplicates.
	 * @param node The {@link Node} instance that is used to create a RoutingNode object.
	 * @param createdRoutingNodes A map storing created {@link RoutingNode} instances and corresponding {@link Node} instances.
	 * @return A created RoutingNode instance based on a node
	 */
	public static RoutingNode createRoutingNode(Node node, Map<Node, RoutingNode> createdRoutingNodes) {
		RoutingNode resourceNode = createdRoutingNodes.get(node);
		if(resourceNode == null) {
			resourceNode = new RoutingNode(node);
			createdRoutingNodes.put(node, resourceNode);
		}
		return resourceNode;
	}
	
	/**
	 * Computes the delay of a node.
	 * @param estimator An instantiation of the DelayEstimatorBase.
	 * @param node The node in question.
	 * @return The delay of the node.
	 */
	public static short computeNodeDelay(DelayEstimatorBase estimator, Node node) {
		if(RoutableNode.isExitNode(node)) {
			return estimator.getDelayOf(node);
		}
		return 0;
	}
	
	/**
	 * Routes and assigns nodes to a direct connection, e.g. carry chain connections and connections between cascaded BRAMs.
	 * @param directConnection The target direct connection.
	 * @return true, if the connection is successfully routed.
	 */
	public static boolean routeDirectConnection(Connection directConnection){
		directConnection.newNodes();
		directConnection.setNodes(findPathBetweenNodes(directConnection.getSource().getConnectedNode(), directConnection.getSink().getConnectedNode()));
		return directConnection.getNodes() != null? true : false;
	}
	
	/**
	 * Find a path from a source node to a sink node.
	 * @param source The source node.
	 * @param sink The sink node.
	 * @return A list of nodes making up the path.
	 */
	public static List<Node> findPathBetweenNodes(Node source, Node sink){
		List<Node> path = new ArrayList<>();		
		if(source.equals(sink)) {
			return path; // for pins without additional projected int_node	
		}
		if(source.getAllDownhillNodes().contains(sink)) {
			path.add(sink);
			path.add(source);
			return path;
		}		
		RoutingNode sourcer = new RoutingNode(source);
		sourcer.setPrev(null);
		Queue<RoutingNode> queue = new LinkedList<>();
		queue.add(sourcer);
		
		int watchdog = 10000;
		boolean success = false;
		while(!queue.isEmpty()) {
			RoutingNode curr = queue.poll();		
			if(curr.getNode().equals(sink)) {
				while(curr != null) {
					path.add(curr.getNode());
					curr = curr.getPrev();
				}
				success = true;
				break;
			}	
			for(Node n : curr.getNode().getAllDownhillNodes()) {
				RoutingNode child = new RoutingNode(n);
				child.setPrev(curr);
				queue.add(child);	
			}
			watchdog--;
			if(watchdog < 0) {
				success = false;
				break;
			}	
		}
		
		if(!success) {
			System.err.println("ERROR: Failed to find a path between two nodes: " + source + ", " + sink);
		}
		return path;
	}
	
	/**
	 *  Gets the delay of a given path, using output pin only.
	 *  The path format: 
	 *  {@code superSource -> Q -> O -> --- -> D.}
	 */
	public static void getSamplePathDelay(String filePath, TimingManager timingManager,
			Map<TimingEdge, Connection> timingEdgeConnectionMap, Map<Node, Routable> rnodesCreated) {
		List<String> verticesOfVivadoPath = new ArrayList<>();
		// Include CLK if the first in the path is BRAM or DSP to check the logic delay
		// NOTE: remember to change the pin names of DSPs from subblock to top-level block that we use
		verticesOfVivadoPath.add("superSource");	
		File vivadoReport = new File(filePath);	
		if(!vivadoReport.exists()) {
			System.err.println("ERROR: Target file does not exist for getting the sample path delay");
			return;
		}
		try {
			List<String> path = parseVivadoPathToStringList(vivadoReport);
			System.out.println("INFO: Given path: " + path);
			verticesOfVivadoPath.addAll(path);
		} catch (IOException e) {
			
			e.printStackTrace();
		}
		System.out.println(verticesOfVivadoPath);
		timingManager.getSamplePathDelayInfo(verticesOfVivadoPath, timingEdgeConnectionMap, true, rnodesCreated);
	}

	/**
	 * Parses the data path from an input file indicating data path of a Vivado timing report.
	 * @param file The file contains a data path of a Vivado timing report.
	 * @return The data path.
	 * @throws IOException
	 */
	public static List<String> parseVivadoPathToStringList(File file) throws IOException{
		List<String> path = new ArrayList<>();
		BufferedReader reader = new BufferedReader(new FileReader(file));
		String line;
		while ((line = reader.readLine()) != null) {
			if (line.length() == 0) {
				break;
			}
			
			if(!line.contains(" r  ") && !line.contains(" f  ")) continue;
			
			String[] dataStrings = line.split("\\s+");
			path.add(dataStrings[dataStrings.length - 1]);
		}
		reader.close();
		return path;
	}
	
}

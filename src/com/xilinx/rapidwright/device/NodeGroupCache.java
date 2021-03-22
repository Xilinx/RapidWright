/* 
 * Copyright (c) 2021 Xilinx, Inc. 
 * All rights reserved.
 *
 * Author: Chris Lavin, Xilinx Research Labs.
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
/**
 * 
 */
package com.xilinx.rapidwright.device;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import com.xilinx.rapidwright.util.Pair;

public class NodeGroupCache {
	
	/**
	 * Checks if the provided node is an exit node of a NodeGroup.
	 * @param node The node to check
	 * @return True if this is an exit node, false otherwise
	 */
	public static boolean isExitNode(Node node) {
		switch(node.getIntentCode()) {
			case NODE_SINGLE:
			case NODE_DOUBLE:
			case NODE_HQUAD:
			case NODE_VQUAD:
			case NODE_VLONG:
			case NODE_HLONG:
			case NODE_PINBOUNCE:
			case NODE_PINFEED:
				return true;
			case NODE_LOCAL:
				String wireName = node.getWireName();
				if(wireName.contains("GLOBAL") || wireName.contains("CTRL")) {
					return true;
				}
			default:
		}
		return false;
	}
	
	/**
	 * Checks if this node is an entry node of a NodeGroup.
	 * @param node The node to check
	 * @return True if this is an entry node, false otherwise.
	 */
	public static boolean isEntryNode(Node node) {
		return !isExitNode(node);
	}
	
	private static boolean isSingleNodeGroup(Node node) {
		SitePin pin = node.getSitePin();
		if(pin != null && !pin.isInput()) {
			return true;
		}
		IntentCode intentCode = node.getIntentCode();
		if(intentCode == IntentCode.VLONG || intentCode == IntentCode.HLONG) {
			return true;
		}
		if(node.getWireName().contains("GLOBAL")) {
			return true;
		}
		
		return false;
	}
	
	/**
	 * Gets the cluster of NodeGroups that have a common exit node/
	 * @param exitNode The exit node in question
	 * @return The cluster of NodeGroups that share the given exit node.
	 */
	public static CommonExitCluster getCommonExitCluster(Node exitNode){
		CommonExitCluster nodeGroups = new CommonExitCluster();
		if(isSingleNodeGroup(exitNode)) {
			nodeGroups.add(new NodeGroup(null, exitNode));
			return nodeGroups;
		}
		for(Node uphillNode : exitNode.getAllUphillNodes()) {
			if(uphillNode.getTile().getTileTypeEnum() != TileTypeEnum.INT) {
				continue;
			}
			if(isEntryNode(uphillNode)) {
				nodeGroups.add(new NodeGroup(uphillNode, exitNode));
			}
		}
		
		return nodeGroups;
	}
	
	public static List<CommonExitCluster> getDownstreamClusters(CommonExitCluster sourceCluster) {
		return getDownstreamClusters(sourceCluster.get(0).getExit());
	}
	
	public static List<CommonExitCluster> getDownstreamClusters(Node commonExitNode) {
		List<CommonExitCluster> downstreamClusters = new ArrayList<CommonExitCluster>();
		for(Node node : commonExitNode.getAllDownhillNodes()) {
			if(node.getTile().getTileTypeEnum() != TileTypeEnum.INT) {
				continue;
			}
			if(isExitNode(node)) {
				downstreamClusters.add(new CommonExitCluster(node));
			} else { // Entry Node
				for(Node downhillNode : node.getAllDownhillNodes()) {
					if(!isExitNode(downhillNode)) {
						throw new RuntimeException("ERROR: Bad Assumption: " + commonExitNode + " " + node + " -> " + downhillNode);
					}
					downstreamClusters.add(getCommonExitCluster(downhillNode));
				}
			}
		}
		return downstreamClusters;
	}
	
	
	
	public static void main(String[] args) {
		Device device = Device.getDevice("xcvu3p");
		HashSet<Node> visited = new HashSet<>();
		// Iterate to find all nodes in the device
		for(Tile tile : device.getAllTiles()) {
			for(PIP pip : tile.getPIPs()) {
				Node start = pip.getStartNode();
				if(start != null && !visited.contains(start) && isExitNode(start)) {
					visited.add(start);
					CommonExitCluster startCluster = getCommonExitCluster(start);
					//System.out.println(start + ":");
					//System.out.println("\t" + startCluster);
					if(startCluster.size() > 0) getDownstreamClusters(startCluster);
				}
				Node end = pip.getEndNode();
				if(end != null && !visited.contains(end) && isExitNode(end)) {
					visited.add(end);
					CommonExitCluster endCluster = getCommonExitCluster(end);
					//System.out.println(end + ":");
					//System.out.println("\t" + endCluster);
					if(endCluster.size() > 0) getDownstreamClusters(endCluster);
				}
			}
		}
	}
}

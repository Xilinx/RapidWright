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

import java.io.BufferedWriter;
import java.io.FileWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;

import com.esotericsoftware.kryo.unsafe.UnsafeInput;
import com.esotericsoftware.kryo.unsafe.UnsafeOutput;
import com.xilinx.rapidwright.util.FileTools;
import com.xilinx.rapidwright.util.MessageGenerator;
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
	
	private static int getNodeTileOffset(Node entry, Node exit) {
		if(entry == null) return -1;
		Tile tile = exit.getTile();
		int xOffset = entry.getTile().getColumn() - tile.getColumn();
		int yOffset = entry.getTile().getRow() - tile.getRow();
		return xOffset << 16 | (yOffset & 0xffff);
	}
	
	private static String getOffsetString(int offset) {
		int x = offset >>> 16;
		int y = (offset << 16) >> 16;
		return "("+x+","+y+")";
	}
	

	private static Long getCompactCluster(int wireIdx, int dx, int dy) {
		return (((long)wireIdx) << 32) | (0xffff0000L & ((long)dx << 16)) | (dy & 0x000000000000ffffL);
	}
	
	private static int getWireIndex(long compactCluster) {
		return 0xffffffff & (int)(compactCluster >>> 32);
	}
	
	private static int getDx(long compactCluster) {
		int dx = 0xffff & (int)(compactCluster >>> 16);
		return (dx << 16) >> 16;
	}
	
	private static int getDy(long compactCluster) {
		int dy = (int)(0xffff & compactCluster);
		return (dy << 16) >> 16;
	}
	

	
	public static void main(String[] args) throws IOException {
		Device device = Device.getDevice("xczu3eg");
		HashSet<Node> visited = new HashSet<>();
		HashMap<Node,CompactCluster> compactDownstreamClusters = new HashMap<>();
		HashMap<CompactCluster,Integer> uniqueClusters = new HashMap<>();
		List<CompactCluster> uniqueClusterList = new ArrayList<>(); 
		// Iterate to find all nodes in the device
		System.out.println(System.getProperty("user.dir"));
		BufferedWriter bw = new BufferedWriter(new FileWriter(device.getName() +"_nodes.txt"));
		for(Tile tile : device.getAllTiles()) {
			for(PIP pip : tile.getPIPs()) {
				for(Node node : new Node[] {pip.getStartNode(), pip.getEndNode()}) {
					if(node != null && !visited.contains(node) && isExitNode(node)) {
						//int templateIdx = node.getNodeTemplateIndex();
						visited.add(node);
						CommonExitCluster startCluster = getCommonExitCluster(node);
						if(startCluster.size() > 0) {
							bw.write("NODE " + node.getWireName());
							List<CommonExitCluster> downstreamClusters = getDownstreamClusters(startCluster);
							CompactCluster clusters = new CompactCluster(new long[downstreamClusters.size()]);
							int i=0;
							for(CommonExitCluster cluster : downstreamClusters) {
								Node downstreamNode = cluster.get(0).getExit();
								int dx = node.getTile().getTileXCoordinate() - downstreamNode.getTile().getTileXCoordinate();
								int dy = node.getTile().getTileYCoordinate() - downstreamNode.getTile().getTileYCoordinate();
								clusters.clusters[i] = getCompactCluster(downstreamNode.getWire(), dx, dy);
								if(getWireIndex(clusters.clusters[i]) != downstreamNode.getWire()) {
									throw new RuntimeException("ERROR: Bad Wire: " + getWireIndex(clusters.clusters[i]) + " " + downstreamNode.getWire());
								}
								if(getDx(clusters.clusters[i]) != dx) {
									throw new RuntimeException("ERROR: Bad Dx: " + getDx(clusters.clusters[i])+  " "+ dx);
								}
								if(getDy(clusters.clusters[i]) != dy) {
									throw new RuntimeException("ERROR: Bad Dy: " + getDy(clusters.clusters[i])+  " "+ dy);
								}

								bw.write(" " + downstreamNode.getWireName() + "-" + dx + "," + dy);
								i++;
							}
							
							// De-duplicate copies
							Integer existingClusterIdx = uniqueClusters.get(clusters);
							if(existingClusterIdx != null) {
								clusters = uniqueClusterList.get(existingClusterIdx);
							} else {
								uniqueClusters.put(clusters, uniqueClusterList.size());
								uniqueClusterList.add(clusters);
							}
							compactDownstreamClusters.put(node, clusters);
						}
						bw.write("\n");
					}					
				}
			}
		}
		bw.close();
		
		String fileName = "nodeGroupCache.dat";
		String cacheVer = "0.1.0";
		UnsafeOutput uos = FileTools.getUnsafeOutputStream(fileName);
		uos.writeString(Device.DEVICE_FILE_VERSION);
		uos.writeString(cacheVer);
		uos.writeInt(uniqueClusterList.size());
		for(CompactCluster c : uniqueClusterList) {
			uos.writeInt(c.clusters.length);
			for(long value : c.clusters) {
				uos.writeLong(value);
			}
		}
		uos.writeInt(compactDownstreamClusters.size());
		for(Entry<Node, CompactCluster> e : compactDownstreamClusters.entrySet() ) {
			Tile tile = e.getKey().getTile();
			int xy = (0xffff0000 & (tile.getColumn() << 16)) | (0x0000ffff & tile.getRow());
			int wire = e.getKey().getWire();
			int idx = uniqueClusters.get(e.getValue());
			uos.writeInt(xy);
			uos.writeInt(wire);
			uos.writeInt(idx);
		}
		uos.close();
		
		
		UnsafeInput uis = FileTools.getUnsafeInputStream(fileName);
		if(!Device.DEVICE_FILE_VERSION.equals(uis.readString())){
			// TODO - Rebuild
			throw new RuntimeException("ERROR: Bad Device Version");
		}
		if(!cacheVer.equals(uis.readString())){
			// TODO - Rebuild
			throw new RuntimeException("ERROR: Bad Cache Version");
		}
		CompactCluster[] clusters = new CompactCluster[uis.readInt()];
		for(int i=0; i < clusters.length; i++) {
			int len = uis.readInt();
			clusters[i] = new CompactCluster(new long[len]);
			for(int j=0; j < len; j++) {
				clusters[i].clusters[j] = uis.readLong();
			}
		}
		int nodeCount = uis.readInt();
		Map<Node, CompactCluster> restoredMap = new HashMap<Node, CompactCluster>(nodeCount);
		for(int i=0; i < nodeCount; i++) {
			int xy = uis.readInt();
			int wire = uis.readInt();
			int idx = uis.readInt();
			Tile tile = device.getTile(0xffff & xy, xy >>> 16);
			Node node = Node.getNode(tile, wire);
			CompactCluster cluster = clusters[idx];
			if(node == null || cluster == null) {
				throw new RuntimeException("ERROR: Bad read on cache");
			}
			restoredMap.put(node, cluster);
		}
		uis.close();
		
		// Verify
		if(restoredMap.size() != compactDownstreamClusters.size()) {
			throw new RuntimeException("ERROR: Bad match");
		}
		for(Entry<Node, CompactCluster> e : compactDownstreamClusters.entrySet()) {
			CompactCluster cluster = restoredMap.get(e.getKey());
			if(cluster == null) {
				throw new RuntimeException("ERROR: Bad Match");
			}
			if(!e.getValue().equals(cluster)) {
				throw new RuntimeException("ERROR: Bad Match");
			}
		}
		
		
		System.out.println("Node Count: " + compactDownstreamClusters.size());
		System.out.println("Cluster Count: " + uniqueClusters.size());
	}
}

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

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;

import com.esotericsoftware.kryo.unsafe.UnsafeInput;
import com.esotericsoftware.kryo.unsafe.UnsafeOutput;
import com.xilinx.rapidwright.util.FileTools;

public class NodeGroupCache {

	public static final String GROUP_NODE_CACHE_VERSION = "0.1.0";

	private static HashMap<Device,Map<Node,CompactCluster>> singletonMap = 
						new HashMap<Device, Map<Node,CompactCluster>>(); 
	
	
	/**
	 * Gets the nodes that are exit nodes of downstream clusters of the provided node.
	 * @param node An exit node of an existing node cluster.
	 * @return The list of exit nodes of downstream clusters of the provided node, or an empty list
	 * if none could be found.
	 */
	public static List<Node> getDownstreamNodes(Node node) {
		if(node == null) return Collections.emptyList();
		Device device = node.getTile().getDevice();
		Map<Node,CompactCluster> cache = getCache(device);
		CompactCluster cluster = cache.get(node);
		if(cluster == null) return Collections.emptyList();
		return cluster.getDownstreamNodes(node);
	}
	
	/**
	 * For a given exit node, this finds and returns a list of downstream common exit clusters
	 * @param node The exit node of interest
	 * @return A list of downstream common exit clusters 
	 */
	public static List<CommonExitCluster> getDownstreamClusters(Node node) {
		List<Node> nodes = getDownstreamNodes(node);
		List<CommonExitCluster> downstreamClusters = new ArrayList<CommonExitCluster>();
		for(Node n : nodes) {
			downstreamClusters.add(getCommonExitCluster(n));
		}
		return downstreamClusters;
	}
	
	/**
	 * Gets the downstream clusters of the provided source cluster.  
	 * @param sourceCluster The target source cluster.
	 * @return A list of downstream clusters of the provided source cluster. 
	 */
	public static List<CommonExitCluster> getDownstreamClusters(CommonExitCluster sourceCluster) {
		Node exitNode = sourceCluster.get(0).getExit();
		return getDownstreamClusters(exitNode);
	}
	
	private static Map<Node,CompactCluster> getCache(Device device) {
		Map<Node,CompactCluster> cache = singletonMap.get(device);
		if(cache == null) {
			String cacheFileName = FileTools.getRapidWrightPath() + 
					File.separator + FileTools.getNodeGroupCacheName(device);
			cache = readCacheFile(cacheFileName, device);
			if(cache == null) {
				// First time, or cache file is invalid
				if(!Device.QUIET_MESSAGE) {
					 System.err.println("INFO: Building NodeGroupCache cache for "+device+"..."
							 	+ "\n      This might take a few seconds for large devices on the first call.  "
								+ "\n      It is triggered by calls to NodeGroupCache.getCache(). "
								+ "\n      To avoid printing this message, set Device.QUIET_MESSAGE=true or set "
								+ "the ENVIRONMENT variable "
								+ "RW_QUIET_MESSAGE" +"=1.");
				}
				HashMap<Node,CompactCluster> compactDownstreamClusters = new HashMap<>();
				HashMap<CompactCluster,Integer> uniqueClusters = new HashMap<>();
				List<CompactCluster> uniqueClusterList = new ArrayList<>(); 
				createCache(device, compactDownstreamClusters, uniqueClusters, uniqueClusterList);
				writeCacheFile(cacheFileName, compactDownstreamClusters, uniqueClusters, uniqueClusterList);
				if(!Device.QUIET_MESSAGE) {
					System.err.println("INFO: Finished NodeGroupCache, wrote to " + cacheFileName);
				}
				cache = compactDownstreamClusters;
			}
			singletonMap.put(device, cache);
		}
		return cache;
	}
	
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
	
	private static List<CommonExitCluster> _getDownstreamClusters(CommonExitCluster sourceCluster) {
		return _getDownstreamClusters(sourceCluster.get(0).getExit());
	}
	
	private static List<CommonExitCluster> _getDownstreamClusters(Node commonExitNode) {
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
						throw new RuntimeException("ERROR: Bad Assumption: " 
								+ commonExitNode + " " + node + " -> " + downhillNode);
					}
					downstreamClusters.add(getCommonExitCluster(downhillNode));
				}
			}
		}
		return downstreamClusters;
	}	
	
	private static void writeCacheFile(String fileName, 
			Map<Node,CompactCluster> compactDownstreamClusters, 
			Map<CompactCluster, Integer> uniqueClusters,
			List<CompactCluster> uniqueClusterList) {
		UnsafeOutput uos = FileTools.getUnsafeOutputStream(fileName);
		uos.writeString(Device.DEVICE_FILE_VERSION);
		uos.writeString(GROUP_NODE_CACHE_VERSION);
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
	}
	
	private static Map<Node, CompactCluster> readCacheFile(String fileName, Device device) {
		if(!new File(fileName).exists()) {
			return null;
		}
		UnsafeInput uis = FileTools.getUnsafeInputStream(fileName);
		if(!Device.DEVICE_FILE_VERSION.equals(uis.readString())){
			return null;
		}
		if(!GROUP_NODE_CACHE_VERSION.equals(uis.readString())){
			return null;
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
				return null;
			}
			restoredMap.put(node, cluster);
		}
		uis.close();

		return restoredMap;
	}
	
	/**
	 * Populates a node to downstream cluster cache of connections
	 * @param device The target device
	 * @param compactDownstreamClusters An empty map to be populated with the connections
	 * @param uniqueClusters An empty map to be populated with unique cluster patterns
	 * @param uniqueClusterList An empty list to be populated with the list of unique cluster 
	 * patterns
	 */
	private static void createCache(Device device, 
			Map<Node,CompactCluster> compactDownstreamClusters, 
			Map<CompactCluster, Integer> uniqueClusters,
			List<CompactCluster> uniqueClusterList) {
		
		HashSet<Node> visited = new HashSet<>();
		// Iterate to find all nodes in the device
		for(Tile tile : device.getAllTiles()) {
			for(PIP pip : tile.getPIPs()) {
				for(Node node : new Node[] {pip.getStartNode(), pip.getEndNode()}) {
					boolean checkNode = node != null && !visited.contains(node) && isExitNode(node); 
					if(!checkNode) continue;

					visited.add(node);
					CommonExitCluster startCluster = getCommonExitCluster(node);
					if(startCluster.size() < 1) continue;
					
					List<CommonExitCluster> downstreamClusters = _getDownstreamClusters(startCluster);
					CompactCluster clusters = new CompactCluster(new long[downstreamClusters.size()]);
					int i=0;
					for(CommonExitCluster cluster : downstreamClusters) {
						Node downstreamNode = cluster.get(0).getExit();
						int dx = node.getTile().getTileXCoordinate() 
								- downstreamNode.getTile().getTileXCoordinate();
						int dy = node.getTile().getTileYCoordinate() 
								- downstreamNode.getTile().getTileYCoordinate();
						clusters.clusters[i] = CompactCluster.getCompactCluster(downstreamNode.getWire(), dx, dy);
						if(CompactCluster.getWireIndex(clusters.clusters[i]) != downstreamNode.getWire()) {
							throw new RuntimeException("ERROR: Bad Wire: " 
									+ CompactCluster.getWireIndex(clusters.clusters[i]) 
									+ " " + downstreamNode.getWire());
						}
						if(CompactCluster.getDx(clusters.clusters[i]) != dx) {
							throw new RuntimeException("ERROR: Bad Dx: " 
									+ CompactCluster.getDx(clusters.clusters[i])+  " "+ dx);
						}
						if(CompactCluster.getDy(clusters.clusters[i]) != dy) {
							throw new RuntimeException("ERROR: Bad Dy: " 
									+ CompactCluster.getDy(clusters.clusters[i])+  " "+ dy);
						}
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
			}
		}
	}
	
	/**
	 * Clears the singleton map, thus deleting any references to the cache
	 * objects.  This will enable the garbage collector to reclaim cache objects without any
	 * other references. 
	 */
	public static void releaseCacheReferences(){
		singletonMap.clear();
	}
	
	/**
	 * Creates all device node group caches up front.  This will take a long time.
	 * @param device Specifies which devices to create the node group caches for.
	 */
	public static void createNodeGroupCaches(String ...devices) {
		for(String deviceName : devices) {
			getCache(Device.getDevice(deviceName));
			releaseCacheReferences();
		}
	}
	
	public static void main(String[] args) throws IOException {
		Device device = Device.getDevice("xczu3eg");

		Map<Node,CompactCluster> cache = getCache(device);
		
		for(Entry<Node,CompactCluster> e : cache.entrySet()) {
			Node node = e.getKey();
			List<Node> downstreamNodes = cache.get(node).getDownstreamNodes(node);
			
			List<CommonExitCluster> downstreamClusters = _getDownstreamClusters(node);
			
			if(downstreamNodes.size() != downstreamClusters.size()) {
				throw new RuntimeException("ERROR: Mismatch number of downstream nodes/clusters");
			}
			for(int i=0; i < downstreamNodes.size(); i++) {
				Node foundNode = downstreamClusters.get(i).get(0).getExit();
				Node cachedNode = downstreamNodes.get(i);
				if(!foundNode.equals(cachedNode)) {
					throw new RuntimeException("ERROR: Mismatch for exit node " + node);
				}
			}
		}
	}
}

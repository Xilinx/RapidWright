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

import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;

import com.xilinx.rapidwright.device.Node;
import com.xilinx.rapidwright.timing.delayestimator.DelayEstimatorBase;

/**
 * A graph-based tool based on Depth-first Search to fix illegal routes, 
 * i.e. routed nets with path cycles or multi-driver nodes.
 */
public class RouteFixer{
	private NetWrapper netp;
	private Map<Node, NodeWithDelay> nodeMap;
	private NodeWithDelay source;
	private int vertexId;
	
	public RouteFixer(NetWrapper netp, Map<Node, Routable> rnodesCreated){
		this.netp = netp;
		nodeMap = new HashMap<>();
		source = null;
		vertexId = 0;
		buildGraph(netp, rnodesCreated);
	}
	
	private void buildGraph(NetWrapper netWrapper, Map<Node, Routable> rnodesCreated){
		for(Connection connection:netWrapper.getConnections()){
			// nodes of connections are in the order from sink to source
			int vertexSize = connection.getNodes().size();
			for(int i = vertexSize - 1; i > 0; i--){
				Node cur = connection.getNodes().get(i);
				Node next = connection.getNodes().get(i - 1);
				
				Routable currRnode = rnodesCreated.get(cur);
				Routable nextRnode = rnodesCreated.get(next);
				float currDly = currRnode == null? 0f : currRnode.getDelay();
				float nextDly = nextRnode == null? 0f : nextRnode.getDelay();
				
				NodeWithDelay newCur = nodeMap.containsKey(cur) ? nodeMap.get(cur) : new NodeWithDelay(vertexId++, cur, currDly);
				NodeWithDelay newNext = nodeMap.containsKey(next) ? nodeMap.get(next) : new NodeWithDelay(vertexId++, next, nextDly);
				nodeMap.put(cur, newCur);
				nodeMap.put(next, newNext);
				
				if(i == 1) {
					newNext.setSink(true);
				}
				if(i == vertexSize - 1) source = newCur;
				newCur.addChildren(newNext);
			}
		}
	}
	
	/**
	 * Finalizes the route of each connection based on the delay-aware path merging.
	 */
	public void finalizeRoutesOfConnections(){
		setShortestPathToEachVertex();
		
		for(Connection connection : netp.getConnections()) {
			NodeWithDelay csink = nodeMap.get(connection.getNodes().get(0));
			connection.getNodes().clear();
			connection.getNodes().add(csink.getNode());
			NodeWithDelay prev = csink.getPrev();
			while(prev != null) {
				connection.getNodes().add(prev.getNode());
				prev = prev.getPrev();
			}
		}
	}
	
	private void setShortestPathToEachVertex() {
		PriorityQueue<NodeWithDelay> queue = new PriorityQueue<NodeWithDelay>(NodeWithDelayComparator);

		queue.clear();
		source.cost = source.delay;
		source.setPrev(null);
		queue.add(source);
		
		while(!queue.isEmpty()) {
			NodeWithDelay cur = queue.poll();
			Set<NodeWithDelay> nexts = cur.children;
			if(nexts == null || nexts.isEmpty()) continue;
			for(NodeWithDelay next : nexts) {
				float newCost = cur.cost + next.getDelay()
						+ DelayEstimatorBase.getExtraDelay(next.getNode(), DelayEstimatorBase.isLong(cur.getNode()));
				if(!next.isVisited() || (next.isVisited() && newCost < next.cost)) {
					// The second condition is necessary, 
					// because a smaller path delay from the source to the current "next" could be achieved later.	
					next.cost = newCost;
					next.setPrev(cur);
					next.setVisited(true);
					queue.add(next);
				}
			}
		}
	}
	
	private static Comparator<NodeWithDelay> NodeWithDelayComparator = new Comparator<NodeWithDelay>() {
    	@Override
    	public int compare(NodeWithDelay a, NodeWithDelay b) {
    		if(a.getDelay() < b.getDelay()){
    			return -1;
    		}else {
    			return 1;
    		}
    	}
    };
	
	class NodeWithDelay{
		private int id;
		private Node node;
		private float delay;
		private boolean isSink;
		private NodeWithDelay prev;
		private float cost;
		private boolean visited;
		private Set<NodeWithDelay> children;
		 
		public NodeWithDelay(int id, Node node, float delay){
			this.id = id;
			this.node = node;
			this.delay = delay;
			isSink = false;
			prev = null;
			cost = Short.MAX_VALUE;
			visited = false;
			children = new HashSet<>();
		}
		
		public void addChildren(NodeWithDelay child) {
			children.add(child);
		}
		
		public boolean isVisited() {
			return visited;
		}

		public void setVisited(boolean visited) {
			this.visited = visited;
		}

		public NodeWithDelay getPrev() {
			return prev;
		}
		
		public void setPrev(NodeWithDelay driver) {
			if(prev == null) {
				prev = driver;
			}else if(driver.cost < prev.cost) {
				prev = driver;
			}
		}
		
		public boolean isSink() {
			return isSink;
		}

		public void setSink(boolean isSink) {
			this.isSink = isSink;
		}

		public float getDelay() {
			return delay;
		}
		
		public Node getNode(){
			return node;
		}
		
		public void setDelay(float f) {
			delay = f;
		}
		
		@Override
		public int hashCode(){
			return node.hashCode();
		}
		
		@Override
		public String toString(){
			return id + ", " + node.toString() + ", delay = " + delay + ", sink? " + isSink;
		}
	}
	
}

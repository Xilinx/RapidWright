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

import com.xilinx.rapidwright.device.Node;

/**
 * A lightweight useful class for different simple routing-related scenarios, 
 * each {@link RoutingNode} Object is associated to a {@link Node} Object.
 */
public class RoutingNode{
	private Node node;
	private RoutingNode prev;
	private boolean isTarget;
	/** Accumulative delay from a source to a {@link RoutingNode} instance */
	private float delayFromSource;
	
	RoutingNode (Node node){
		this.node = node;
		prev = null;
		isTarget = false;
		delayFromSource = 0;
	}
	
	/**
	 * Gets the accumulative delay from a source to a RoutingNode instance if it is an used resource of a routing path.
	 * @return The accumulative delay.
	 */
	public float getDelayFromSource() {
		return delayFromSource;
	}

	/**
	 * Sets the accumulative delay from a source to a RoutingNode instance if it is an used resource of a routing path.
	 */
	public void setDelayFromSource(float delayFromSource) {
		this.delayFromSource = delayFromSource;
	}

	public void setPrev(RoutingNode prev) {
		this.prev = prev;
	}
	
	public RoutingNode getPrev() {
		return prev;
	}
	
	public Node getNode() {
		return node;
	}

	public boolean isTarget() {
		return isTarget;
	}

	public void setTarget(boolean isTarget) {
		this.isTarget = isTarget;
	}
	
	public int hashCode() {
		return node.hashCode();
	}
	
	public String toString() {
		return node.toString() + ", accDly = " + delayFromSource;
	}
	
}

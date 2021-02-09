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
package com.xilinx.rapidwright.device;

/**
 * A NodeGroup is a group of one or two Nodes that represent a routing resource unit.
 *
 */
public class NodeGroup {

	private Node entry;
	
	private Node exit;

	public NodeGroup(Node entry, Node exit) {
		super();
		this.entry = entry;
		this.exit = exit;
	}

	/**
	 * @return the entry
	 */
	public Node getEntry() {
		return entry;
	}

	/**
	 * @param entry the entry to set
	 */
	public void setEntry(Node entry) {
		this.entry = entry;
	}

	/**
	 * @return the exit
	 */
	public Node getExit() {
		return exit;
	}

	/**
	 * @param exit the exit to set
	 */
	public void setExit(Node exit) {
		this.exit = exit;
	}

	@Override
	public int hashCode() {
		final int prime = 31;
		int result = 1;
		result = prime * result + ((entry == null) ? 0 : entry.hashCode());
		result = prime * result + ((exit == null) ? 0 : exit.hashCode());
		return result;
	}

	@Override
	public boolean equals(Object obj) {
		if (this == obj)
			return true;
		if (obj == null)
			return false;
		if (getClass() != obj.getClass())
			return false;
		NodeGroup other = (NodeGroup) obj;
		if (entry == null) {
			if (other.entry != null)
				return false;
		} else if (!entry.equals(other.entry))
			return false;
		if (exit == null) {
			if (other.exit != null)
				return false;
		} else if (!exit.equals(other.exit))
			return false;
		return true;
	}

	@Override
	public String toString() {
		return "<" + entry + ", " + exit + ">";
	}
	
}

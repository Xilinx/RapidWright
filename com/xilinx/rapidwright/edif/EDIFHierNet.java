/*
 * 
 * Copyright (c) 2017 Xilinx, Inc. 
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
package com.xilinx.rapidwright.edif;

/**
 * Combines an {@link EDIFNet} with a full hierarchical
 * instance name to uniquely identify a net in a netlist.
 * 
 * Created on: Sep 13, 2017
 */
public class EDIFHierNet {

	private String hierarchicalInstName;
	
	private EDIFNet net;

	/**
	 * Constructor 
	 * @param hierarchicalInstName Parent instance cell that contains this net
	 * @param net The actual net object
	 */
	public EDIFHierNet(String hierarchicalInstName, EDIFNet net) {
		super();
		this.hierarchicalInstName = hierarchicalInstName;
		this.net = net;
	}

	/**
	 * @return the hierarchicalInstName
	 */
	public String getHierarchicalInstName() {
		return hierarchicalInstName;
	}

	/**
	 * @param hierarchicalInstName the hierarchicalInstName to set
	 */
	public void setHierarchicalInstName(String hierarchicalInstName) {
		this.hierarchicalInstName = hierarchicalInstName;
	}

	/**
	 * @return the net
	 */
	public EDIFNet getNet() {
		return net;
	}

	/**
	 * @param net the net to set
	 */
	public void setNet(EDIFNet net) {
		this.net = net;
	}
	
	/**
	 * Given a port on the net, gives the full hierarchical name of the instance
	 * attached to the port.
	 * @param port The reference port of the instance.
	 * @return Full hierarchical name of the instance attached to the port.
	 */
	public String getHierarchicalInstName(EDIFPortInst port){
		if(this.hierarchicalInstName.equals("")){
			return port.getCellInst().getName();
		}
		return this.hierarchicalInstName + EDIFTools.EDIF_HIER_SEP + port.getCellInst().getName();
	}
	
	public String getHierarchicalNetName(){
		if(this.hierarchicalInstName.length() == 0){
			return net.getName();
		}
		return this.hierarchicalInstName + EDIFTools.EDIF_HIER_SEP + net.getName();
	}

	/* (non-Javadoc)
	 * @see java.lang.Object#hashCode()
	 */
	@Override
	public int hashCode() {
		final int prime = 31;
		int result = 1;
		result = prime * result + ((hierarchicalInstName == null) ? 0 : hierarchicalInstName.hashCode());
		result = prime * result + ((net == null) ? 0 : net.hashCode());
		return result;
	}

	/* (non-Javadoc)
	 * @see java.lang.Object#equals(java.lang.Object)
	 */
	@Override
	public boolean equals(Object obj) {
		if (this == obj)
			return true;
		if (obj == null)
			return false;
		if (getClass() != obj.getClass())
			return false;
		EDIFHierNet other = (EDIFHierNet) obj;
		if (hierarchicalInstName == null) {
			if (other.hierarchicalInstName != null)
				return false;
		} else if (!hierarchicalInstName.equals(other.hierarchicalInstName))
			return false;
		if (net == null) {
			if (other.net != null)
				return false;
		} else if (!net.equals(other.net))
			return false;
		return true;
	}

	/* (non-Javadoc)
	 * @see java.lang.Object#toString()
	 */
	@Override
	public String toString() {
		return "EDIFHierNet [hierarchicalInstName=" + hierarchicalInstName + ", net=" + net + "]";
	}
	
	
}

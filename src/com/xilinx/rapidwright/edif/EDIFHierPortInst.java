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
 * Combines an {@link EDIFHierPortInst} with a full hierarchical
 * instance name to uniquely identify a port instance in a netlist.
 * 
 * Created on: Sep 12, 2017
 */
public class EDIFHierPortInst {

	private String hierarchicalInstName;
	
	private EDIFPortInst portInst;

	/**
	 * Constructor
	 * @param hierarchicalInstName The hierarchical parent instance cell name of the port 
	 * @param portInst The actual port ref object
	 */
	public EDIFHierPortInst(String hierarchicalInstName, EDIFPortInst portInst) {
		super();
		this.hierarchicalInstName = hierarchicalInstName;
		this.portInst = portInst;
	}

	/**
	 * The name of the parent instance cell that contains the instance
	 * cell pin.
	 * @return the hierarchicalInstanceName
	 */
	public String getHierarchicalInstName() {
		return hierarchicalInstName;
		
	}

	/**
	 * Returns the full hierarchical name of the instance on which this port resides.
	 * @return The full hierarchical name.
	 */
	public String getFullHierarchicalInstName(){
		if(portInst.getCellInst() == null){
			// Internal (inward-facing) side of a cell port
			return hierarchicalInstName;
		}
		EDIFCellInst topCellInst = portInst.getCellInst().getCellType().getLibrary().getNetlist().getTopCellInst();
		if(hierarchicalInstName.equals(""))
			return portInst.getCellInst().equals(topCellInst) ? "" : portInst.getCellInst().getName(); 
		return hierarchicalInstName + EDIFTools.EDIF_HIER_SEP + portInst.getCellInst().getName();
	}
	
	/**
	 * @param hierarchicalInstanceName the hierarchicalInstanceName to set
	 */
	public void setHierarchicalInstName(String hierarchicalInstanceName) {
		this.hierarchicalInstName = hierarchicalInstanceName;
	}

	/**
	 * @return the portInst
	 */
	public EDIFPortInst getPortInst() {
		return portInst;
	}

	/**
	 * @param portInst the port instance to set
	 */
	public void setPortInst(EDIFPortInst portInst) {
		this.portInst = portInst;
	}

	/* (non-Javadoc)
	 * @see java.lang.Object#hashCode()
	 */
	@Override
	public int hashCode() {
		final int prime = 31;
		int result = 1;
		result = prime * result + ((hierarchicalInstName == null) ? 0 : hierarchicalInstName.hashCode());
		result = prime * result + ((portInst == null) ? 0 : portInst.hashCode());
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
		EDIFHierPortInst other = (EDIFHierPortInst) obj;
		if (hierarchicalInstName == null) {
			if (other.hierarchicalInstName != null)
				return false;
		} else if (!hierarchicalInstName.equals(other.hierarchicalInstName))
			return false;
		if (portInst == null) {
			if (other.portInst != null)
				return false;
		} else if (!portInst.equals(other.portInst))
			return false;
		return true;
	}

	/* (non-Javadoc)
	 * @see java.lang.Object#toString()
	 */
	@Override
	public String toString() {
		if(hierarchicalInstName.equals("")) return portInst.getFullName();
		return hierarchicalInstName + "/" + portInst.getFullName();
	}

	public String getHierarchicalNetName(){
		if(hierarchicalInstName.equals("")) return portInst.getNet().getName();
		return hierarchicalInstName + EDIFTools.EDIF_HIER_SEP + portInst.getNet();
	}
	
	public String getTransformedNetName(){
		String portName = null;
		if(portInst.getPort().getWidth() > 1){
			EDIFCellInst eci = portInst.getCellInst();
			int idx = portInst.getIndex();
			if(portInst.getPort().isLittleEndian()){
				idx = (portInst.getPort().getWidth()-1) - idx;
			}
			portName = portInst.getPort().getBusName() + idx;
			if(eci != null) 
				portName = portInst.getCellInst().getName() + EDIFTools.EDIF_HIER_SEP + portName;  
		}else{
			portName = portInst.getFullName();
		}
		if(hierarchicalInstName.equals("")) return portName;
		return hierarchicalInstName + "/" + portName;
	}
	
	public boolean isOutput(){
		return portInst.getPort().isOutput();
	}
	
	public boolean isInput(){
		return portInst.getPort().isInput();
	}
}

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
 * Combines an {@link EDIFCellInst} with a full hierarchical
 * instance name to uniquely identify an instance in a netlist.
 * 
 * Created on: Oct 30, 2017
 */
public class EDIFHierCellInst {

	private String hierarchicalInstName;
	
	private EDIFCellInst instance;

	/**
	 * Constructor
	 * @param hierarchicalInstanceName Full hierarchical name of the parent instance
	 * @param instance The actual instance object
	 */
	public EDIFHierCellInst(String hierarchicalInstanceName, EDIFCellInst instance) {
		super();
		this.hierarchicalInstName = hierarchicalInstanceName;
		this.instance = instance;
	}

	/**
	 * @return the hierarchical name of this parent instance
	 */
	public String getHierarchicalInstName() {
		return hierarchicalInstName;
	}

	/**
	 * @param hierarchicalInstanceName the hierarchical name of the parent instance
	 */
	public void setHierarchicalInstName(String hierarchicalInstanceName) {
		this.hierarchicalInstName = hierarchicalInstanceName;
	}

	/**
	 * @return the instance
	 */
	public EDIFCellInst getInst() {
		return instance;
	}

	/**
	 * @param instance the instance to set
	 */
	public void setInst(EDIFCellInst instance) {
		this.instance = instance;
	}
	
	public String getFullHierarchicalInstName(){
		if(isTopLevelInst()) return "";
		if(hierarchicalInstName.equals("")) return instance.getName();
		return hierarchicalInstName + EDIFTools.EDIF_HIER_SEP + instance.getName();
	}

	/**
	 * Checks if this instance is the top level instance of the netlist.
	 * @return True if this is the top instance, false otherwise.
	 */
	public boolean isTopLevelInst() {
		return instance.getCellType().getLibrary().getNetlist().getTopCellInst().equals(instance);
	}
	
	public String toString(){
		return getFullHierarchicalInstName();
	}
	
	public EDIFCell getCellType(){
		return instance.getCellType();
	}
	
	public String getCellName(){
		return instance.getCellName();
	}
}

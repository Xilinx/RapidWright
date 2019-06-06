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
package com.xilinx.rapidwright.design;

/**
 * Class specifically created to manage pin swaps on the same site (such as LUTs)
 * Created on: Nov 22, 2017
 */
public class PinSwap {

	private Cell cell;
	
	private Cell companionCell; 
	
	private String companionLogicalName;
	
	private String logicalName;
	
	private String oldPhysicalName;
	
	private String newPhysicalName;
	
	private String depopulatedLogicalName;

	private String newNetPinName;

	/**
	 * @param logicalName
	 * @param oldPhysicalName
	 * @param newPhysicalName
	 * @param depopulatedLogicalName
	 * @param net
	 * @param newNetPinName
	 */
	public PinSwap(Cell c, String logicalName, String oldPhysicalName, String newPhysicalName, String depopulatedLogicalName,
			String newNetPinName) {
		super();
		this.cell = c;
		this.logicalName = logicalName;
		this.oldPhysicalName = oldPhysicalName;
		this.newPhysicalName = newPhysicalName;
		this.depopulatedLogicalName = depopulatedLogicalName;
		this.newNetPinName = newNetPinName;
	}

	/**
	 * @return the logicalName
	 */
	public String getLogicalName() {
		return logicalName;
	}

	/**
	 * @param logicalName the logicalName to set
	 */
	public void setLogicalName(String logicalName) {
		this.logicalName = logicalName;
	}

	/**
	 * @return the oldPhysicalName
	 */
	public String getOldPhysicalName() {
		return oldPhysicalName;
	}

	/**
	 * @param oldPhysicalName the oldPhysicalName to set
	 */
	public void setOldPhysicalName(String oldPhysicalName) {
		this.oldPhysicalName = oldPhysicalName;
	}

	/**
	 * @return the newPhysicalName
	 */
	public String getNewPhysicalName() {
		return newPhysicalName;
	}

	/**
	 * @param newPhysicalName the newPhysicalName to set
	 */
	public void setNewPhysicalName(String newPhysicalName) {
		this.newPhysicalName = newPhysicalName;
	}

	/**
	 * @return the newNetPinName
	 */
	public String getNewNetPinName() {
		return newNetPinName;
	}

	/**
	 * @param newNetPinName the newNetPinName to set
	 */
	public void setNewNetPinName(String newNetPinName) {
		this.newNetPinName = newNetPinName;
	}

	/**
	 * This gets the previous logical pin mapping of the new physical pin name being moved. 
	 * @return the depopulatedLogicalName
	 */
	public String getDepopulatedLogicalName() {
		return depopulatedLogicalName;
	}

	/**
	 * @param depopulatedLogicalName the depopulatedLogicalName to set
	 */
	public void setDepopulatedLogicalName(String depopulatedLogicalName) {
		this.depopulatedLogicalName = depopulatedLogicalName;
	}

	/**
	 * @return the cell
	 */
	public Cell getCell() {
		return cell;
	}

	/**
	 * @return the companionCell
	 */
	public Cell getCompanionCell() {
		return companionCell;
	}
	
	/**
	 * If this pin swap does not directly involve two cells, it may
	 * involve another indirectly.  If this pin swap is a LUT6 it will
	 * check for a LUT5 and vice versa.  
	 * @return Cell in the overlapping LUT BEL site.
	 */
	public Cell checkForCompanionCell(){
		if(companionCell != null) return companionCell;
		String otherBEL = cell.getBELName().charAt(1) == '5' ? cell.getBELName().replace('5', '6') : cell.getBELName().replace('6', '5');
		return cell.getSiteInst().getCell(otherBEL);
	}

	/**
	 * @param companionCell the companionCell to set
	 */
	public void setCompanionCell(Cell companionCell, String companionLogicalName) {
		this.companionCell = companionCell;
		this.companionLogicalName = companionLogicalName;
	}

	public String getCompanionLogicalName(){
		return this.companionLogicalName;
	}
	
	/* (non-Javadoc)
	 * @see java.lang.Object#hashCode()
	 */
	@Override
	public int hashCode() {
		final int prime = 31;
		int result = 1;
		result = prime * result + ((logicalName == null) ? 0 : logicalName.hashCode());
		result = prime * result + ((newPhysicalName == null) ? 0 : newPhysicalName.hashCode());
		result = prime * result + ((oldPhysicalName == null) ? 0 : oldPhysicalName.hashCode());
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
		PinSwap other = (PinSwap) obj;
		if (logicalName == null) {
			if (other.logicalName != null)
				return false;
		} else if (!logicalName.equals(other.logicalName))
			return false;
		if (newPhysicalName == null) {
			if (other.newPhysicalName != null)
				return false;
		} else if (!newPhysicalName.equals(other.newPhysicalName))
			return false;
		if (oldPhysicalName == null) {
			if (other.oldPhysicalName != null)
				return false;
		} else if (!oldPhysicalName.equals(other.oldPhysicalName))
			return false;
		return true;
	}
	
	public String toString(){
		return cell.getBELName().charAt(1) + "LUT/" + logicalName + ":" + oldPhysicalName +"->" + newPhysicalName; 
	}
}

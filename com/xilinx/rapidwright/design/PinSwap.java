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
	public PinSwap(String logicalName, String oldPhysicalName, String newPhysicalName, String depopulatedLogicalName,
			String newNetPinName) {
		super();
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
		return logicalName + ":" + oldPhysicalName +"->" + newPhysicalName; 
	}
}

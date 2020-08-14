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
 * Created on: Jun 21, 2017
 */
public class AltPinMapping {

	private String logicalName;
	
	private String altCellName;
	
	private String altCellType;

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
	 * @return the altCellName
	 */
	public String getAltCellName() {
		return altCellName;
	}

	/**
	 * @param altCellName the altCellName to set
	 */
	public void setAltCellName(String altCellName) {
		this.altCellName = altCellName;
	}

	/**
	 * @return the altCellType
	 */
	public String getAltCellType() {
		return altCellType;
	}

	/**
	 * @param altCellType the altCellType to set
	 */
	public void setAltCellType(String altCellType) {
		this.altCellType = altCellType;
	}

	/* (non-Javadoc)
	 * @see java.lang.Object#hashCode()
	 */
	@Override
	public int hashCode() {
		final int prime = 31;
		int result = 1;
		result = prime * result + ((altCellName == null) ? 0 : altCellName.hashCode());
		result = prime * result + ((altCellType == null) ? 0 : altCellType.hashCode());
		result = prime * result + ((logicalName == null) ? 0 : logicalName.hashCode());
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
		AltPinMapping other = (AltPinMapping) obj;
		if (altCellName == null) {
			if (other.altCellName != null)
				return false;
		} else if (!altCellName.equals(other.altCellName))
			return false;
		if (altCellType == null) {
			if (other.altCellType != null)
				return false;
		} else if (!altCellType.equals(other.altCellType))
			return false;
		if (logicalName == null) {
			if (other.logicalName != null)
				return false;
		} else if (!logicalName.equals(other.logicalName))
			return false;
		return true;
	}

	/**
	 * @param logicalName
	 * @param altCellName
	 * @param altCellType
	 */
	public AltPinMapping(String logicalName, String altCellName, String altCellType) {
		super();
		this.logicalName = logicalName;
		this.altCellName = altCellName;
		this.altCellType = altCellType;
	}
	
	public AltPinMapping(){
		
	}
	
	public String toString() {
		return altCellName + "[" + altCellType + "]/" + logicalName;
	}
}

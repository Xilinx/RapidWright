/*
 * 
 * Copyright (c) 2018 Xilinx, Inc. 
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
package com.xilinx.rapidwright.ipi;

/**
 * Annotates a package pin name with an IO standard
 * Created on: Jan 25, 2018
 */
public class PackagePinConstraint {

	private String name;
	
	private String ioStandard;

	/**
	 * @return the name
	 */
	public String getName() {
		return name;
	}

	/**
	 * @param name the name to set
	 */
	public void setName(String name) {
		this.name = name;
	}

	/**
	 * @return the ioStandard
	 */
	public String getIoStandard() {
		return ioStandard;
	}

	/**
	 * @param ioStandard the ioStandard to set
	 */
	public void setIOStandard(String ioStandard) {
		this.ioStandard = ioStandard;
	}
	
	public String toString(){
		return name + ":" + ioStandard;
	}
}

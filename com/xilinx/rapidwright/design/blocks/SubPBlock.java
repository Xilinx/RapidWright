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
package com.xilinx.rapidwright.design.blocks;

import com.xilinx.rapidwright.device.Device;

/**
 * Keeps track of a constrained pblock within a pblock.
 * 
 * Created on: Jul 12, 2017
 */
public class SubPBlock extends PBlock {

	/**
	 * 
	 */
	private static final long serialVersionUID = -5272887799353790281L;
	private String getCellsArgs;

	/**
	 * @param device
	 * @param pblock
	 */
	public SubPBlock(Device device, String pblock) {
		super(device, pblock);
	}

	/**
	 * @return the getCellsArgs
	 */
	public String getGetCellsArgs() {
		return getCellsArgs;
	}

	/**
	 * @param getCellsArgs the getCellsArgs to set
	 */
	public void setGetCellsArgs(String getCellsArgs) {
		this.getCellsArgs = getCellsArgs;
	}
	
	
}

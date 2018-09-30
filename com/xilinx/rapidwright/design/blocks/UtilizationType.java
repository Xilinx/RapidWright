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

/**
 * Different categories of fabric utilization.
 * 
 * Created on: Apr 21, 2017
 */
public enum UtilizationType {
	CLB_LUTS("CLB LUTs"),
	LUTS_AS_LOGIC("LUTs as Logic"),
	LUTS_AS_MEMORY("LUTs as Memory"),
	CLB_REGS("CLB Regs"),
	REGS_AS_FFS("Regs as FF"),
	REGS_AS_LATCHES("Regs as Latch"),
	CARRY8S("CARRY8s"),
	//F7_MUXES("F7 Muxes"),
	//F8_MUXES("F8 Muxes"),
	//F9_MUXES("F9 Muxes"),
	CLBS("CLBs"),
	CLBLS("CLBLs"),
	CLBMS("CLBMs"),
	//LUT_FF_PAIRS("Lut/FF Pairs"),
	RAMB36S_FIFOS("RAMB36s/FIFOs"),
	RAMB18S("RAMB18s"),
	URAMS("URAMs"),
	DSPS("DSPs");

	private String name;
	
	private UtilizationType(String name){
		this.name = name;
	}
	
	public String getString(){
		return name;
	}

}

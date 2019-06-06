/* 
 * Original work: Copyright (c) 2010-2011 Brigham Young University
 * Modified work: Copyright (c) 2017 Xilinx, Inc. 
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
package com.xilinx.rapidwright.design;

import com.xilinx.rapidwright.edif.EDIFTools;

/**
 * This enum is simply a way to check net types easier than using Strings.
 * @author Chris Lavin
 * Created on: Jun 22, 2010
 */
public enum NetType{
	WIRE,
	GND,
	VCC,
	UNKNOWN;
	
	public static NetType getNetTypeFromNetName(String name){
		if(name == null || name.equals("")) return UNKNOWN;
		if(name.equals(Net.GND_NET)) return GND;
		if(name.equals(Net.VCC_NET)) return VCC;
		if(name.endsWith(EDIFTools.LOGICAL_GND_NET_NAME)) return GND;
		if(name.endsWith(EDIFTools.LOGICAL_VCC_NET_NAME)) return VCC;
		return WIRE;
	}
	
	public boolean isStaticNetType(){
		return this == GND || this == VCC; 
	}
}

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

import com.xilinx.rapidwright.design.PinType;
import com.xilinx.rapidwright.device.BELPin.Direction;

/**
 * Provides basic directional options for ports.
 * 
 * Created on: May 11, 2017
 */
public enum EDIFDirection {
	INPUT,
	OUTPUT,
	INOUT;
	
	public static EDIFDirection getEnum(String s){
		s = s.toUpperCase();
		if(s.equals("BIDIR")) return INOUT;
		return valueOf(s);
	}
	
	public static EDIFDirection getDir(PinType p){
		if(p == PinType.IN) return INPUT;
		if(p == PinType.OUT) return OUTPUT;
		return INOUT;
	}
	
	public static EDIFDirection getDir(Direction p){
		if(p == Direction.INPUT) return INPUT;
		if(p == Direction.OUTPUT) return OUTPUT;
		return INOUT;
	}
}

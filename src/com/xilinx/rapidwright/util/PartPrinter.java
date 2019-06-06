/* 
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
package com.xilinx.rapidwright.util;

import com.xilinx.rapidwright.device.Part;
import com.xilinx.rapidwright.device.PartNameTools;

/**
 * Prints all the installed parts in RapidWright
 * 
 * Created on: Jan 9, 2017
 */
public class PartPrinter {
	public static void main(String[] args) {
		for(Part p : PartNameTools.getParts()){
			System.out.println(p);
		}
	}
}

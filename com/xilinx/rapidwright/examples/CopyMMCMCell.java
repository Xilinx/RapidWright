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
package com.xilinx.rapidwright.examples;

import com.xilinx.rapidwright.design.Cell;
import com.xilinx.rapidwright.design.Design;
import com.xilinx.rapidwright.util.MessageGenerator;

/**
 * Takes as input two DCP files, one with an MMCM in one DCP that is to be copied into another DCP.
 * Created on: Apr 18, 2017
 */
public class CopyMMCMCell {

	
	public static void main(String[] args) {
		if(args.length != 3){
			MessageGenerator.briefMessageAndExit("USAGE: <source MMCM DCP> <input DCP> <output DCP>");
		}
		Design clkPath = Design.readCheckpoint(args[0], args[0].replace(".dcp", ".edf"));
		Design input = Design.readCheckpoint(args[1], args[1].replace(".dcp", ".edf"));
		
		Cell mmcm = null;
		for(Cell c : clkPath.getCells()){
			if(c.getBEL().getName().contains("MMCM")){
				mmcm = c;
				break;
			}
		}
		if(mmcm == null) throw new RuntimeException("ERROR: Couldn't find an MMCM instance in source design.");
		
		input.copyCell(mmcm, mmcm.getName().substring(mmcm.getName().lastIndexOf('/')+1));
		
		input.writeCheckpoint(args[2]);
	}
}

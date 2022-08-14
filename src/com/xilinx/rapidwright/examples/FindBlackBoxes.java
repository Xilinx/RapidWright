/* 
 * Copyright (c) 2021 Xilinx, Inc. 
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
 
package com.xilinx.rapidwright.examples;

import java.util.ArrayList;
import java.util.List;

import com.xilinx.rapidwright.design.Design;
import com.xilinx.rapidwright.edif.EDIFHierCellInst;

public class FindBlackBoxes {

	public static List<EDIFHierCellInst> getBlackBoxes(Design design){
		ArrayList<EDIFHierCellInst> blackBoxInsts = new ArrayList<EDIFHierCellInst>(); 
		for(EDIFHierCellInst inst : design.getNetlist().getAllDescendants("", "*", false)) {
			if(inst.getInst().isBlackBox()) {
				blackBoxInsts.add(inst);
			}
		}
		return blackBoxInsts;
	}
	
	/**
	 * Reads a DCP and prints out hierarchical path to each black box cell instance found
	 * @param args
	 */
	public static void main(String[] args) {
		if(args.length < 1 || args.length > 2) {
			System.out.println("USAGE: <input.dcp> [input.edf]");
			return;
		}
		Design designWithBB = args.length == 1 ? 
				Design.readCheckpoint(args[0]) : Design.readCheckpoint(args[0], args[1]);
		
		for(EDIFHierCellInst inst : getBlackBoxes(designWithBB)) {
			System.out.println(inst.getFullHierarchicalInstName());
		}
	}
}

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

import java.io.FileNotFoundException;
import java.io.PrintWriter;
import java.util.LinkedList;
import java.util.Queue;

import com.xilinx.rapidwright.edif.EDIFCellInst;
import com.xilinx.rapidwright.edif.EDIFHierCellInst;
import com.xilinx.rapidwright.edif.EDIFNetlist;
import com.xilinx.rapidwright.edif.EDIFTools;
import com.xilinx.rapidwright.util.MessageGenerator;

/**
 * This example enumerates all cell instances in an EDIF netlist
 * and writes them to a file.  
 * Created on: Aug 3, 2015
 */
public class PrintEDIFInstances {

	
	public static void printEDIFInstancesToFile(EDIFNetlist ee, String fileName){
		try {
			PrintWriter pw = new PrintWriter(fileName);
			pw.println("DESIGN NAME: " + ee.getName());
			Queue<EDIFHierCellInst> queue = new LinkedList<EDIFHierCellInst>();
			queue.add(new EDIFHierCellInst("", ee.getTopCellInst()));
			while(!queue.isEmpty()){
				EDIFHierCellInst p = queue.poll();
				EDIFCellInst i = p.getInst();
				String path = p.getHierarchicalInstName();
				String curr = path + "/" + i.getName();
				pw.println(curr + " (" + i.getCellType() + ") from library " + i.getCellType().getLibrary());
				for(EDIFCellInst i2 : i.getCellType().getCellInsts()){
					queue.add(new EDIFHierCellInst(curr, i2));
				}
			}
			
			
			pw.close();
		} catch (FileNotFoundException e) {
			e.printStackTrace();
		}
	}
	
	public static void main(String[] args) {
		if(args.length != 2){
			MessageGenerator.briefMessageAndExit("USAGE: <input.edf> <instnames.txt>");
		}
		EDIFNetlist ee = EDIFTools.readEdifFile(args[0]);
		printEDIFInstancesToFile(ee, args[1]);
	}
}

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

import java.util.ArrayList;
import java.util.Iterator;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Tool for comparing reports from the Vivado Tcl command report_route_status.
 * Created on: Jan 22, 2016
 */
public class CompareRouteStatusReports {

	TreeMap<String,RouteStatus> tree1;
	
	TreeMap<String,RouteStatus> tree2;

	public TreeMap<String,RouteStatus> loadRouteStatusReport(String fileName){
		ArrayList<String> lines = FileTools.getLinesFromTextFile(fileName);
		TreeMap<String,RouteStatus> tree = new TreeMap<String,RouteStatus>();
		boolean pastHeader = false;
		String currNetName = null;
		String currStatus = null;
		ArrayList<String> currSubTree = null;
		ArrayList<ArrayList<String>> currSubTrees = null;
		for(int i=0; i < lines.size(); i++){
			String curr = lines.get(i);
			if(pastHeader){
				if(curr.length() > 0 && Character.isWhitespace(curr.charAt(0))){
					if(curr.contains("Route Tree:")){
						continue;
					}
					else if(curr.contains("Routing status:")){
						String[] parts = curr.split(" ");
						currStatus = parts[4];
					}else if(curr.contains("-----------") && !lines.get(i-1).contains("Route Tree:")){
						// create new RouteStatus
						RouteStatus rs = new RouteStatus();
						rs.setName(currNetName);
						rs.setStatus(currStatus);
						rs.setSubTrees(currSubTrees);
						tree.put(rs.getName(),rs);
						currSubTree = null;
						currSubTrees = null;
					}else if(curr.contains("/")){
						String wire = curr.replace("[", " ").replace("{", " ").replace("}", " ").replace("]", " ");
						wire = wire.trim();
						currSubTree.add(wire);
					}else if(curr.contains("Subtree:")){
						currSubTree = new ArrayList<String>();
						if(currSubTrees == null){
							currSubTrees = new ArrayList<ArrayList<String>>();
						}
						currSubTrees.add(currSubTree);
					}
				}else{
					currNetName = curr.trim();
				}
			}
			else if(curr.contains("Logical Net Detailed Routing:")){
				pastHeader = true;
			}
		}
		
		return tree;
	}
	
	
	public void compare(String fileName1, String fileName2){
		tree1 = loadRouteStatusReport(fileName1);
		tree2 = loadRouteStatusReport(fileName2);
		if(tree1.keySet().size() != tree2.keySet().size()){
			System.out.println("Error: Differing number of nets in files!");
		}
		
		for(String net : tree1.keySet()){
			RouteStatus r1 = tree1.get(net);
			RouteStatus r2 = tree2.get(net);
			r1.reportDifferences(r2);
		}
	}
	
	
	public static void main(String[] args) {
		if(args.length != 2){
			MessageGenerator.briefMessageAndExit("USAGE: report1.txt report2.txt");
		}
		String file1 = args[0];
		String file2 = args[1];
		
		CompareRouteStatusReports r = new CompareRouteStatusReports();
		r.compare(file1,file2);
	}
}

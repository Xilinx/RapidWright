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
package com.xilinx.rapidwright.util;

import java.util.HashSet;

import com.xilinx.rapidwright.design.Cell;
import com.xilinx.rapidwright.design.Design;
import com.xilinx.rapidwright.design.Net;
import com.xilinx.rapidwright.device.BEL;
import com.xilinx.rapidwright.device.PIP;
import com.xilinx.rapidwright.device.Site;

/**
 * This class takes as input two DCPs, one which is derived from the other and
 * reports the number of changes to the original implementation.  Specifically,
 * the number of cells that have moved from their original placement, and the
 * number of nets that have had routing PIPs changed beyond the original routing.
 * Created on: May 9, 2017
 */
public class DesignImplementationDiff {

	public static void main(String[] args) {
		if(args.length != 2){
			MessageGenerator.briefMessageAndExit("USAGE: <original.dcp> <superset.dcp>");
		}
		Design original = Design.readCheckpoint(args[0]);
		Design superset = Design.readCheckpoint(args[1]);
		int cellMovements = 0;
		int netRoutingChanges = 0;
		for(Cell c : original.getCells()){
			BEL e = c.getBEL();
			Site s = c.getSite();
			boolean placementChange = false;
			Cell cc = superset.getCell(c.getName());
			if(cc == null){
				System.out.println("Cell " + c.getName() + " is missing");
				continue;
			}
			if(!cc.getSite().equals(s)){
				System.out.println("Cell " + c.getName() + " has moved to " + cc.getSite());
				placementChange = true;
			}
			
			if(!cc.getBEL().equals(e)){
				System.out.println("Cell " + c.getName() + " has moved to " + cc.getBEL());
				placementChange = true;
			}
			cellMovements += placementChange ? 1 : 0;
		}
		
		for(Net n : original.getNets()){
			Net nn = superset.getNet(n.getName());
			if(nn == null){
				System.out.println("Net " + nn + " is missing");
				continue;
			}
			if(nn.isStaticNet()) continue;
			boolean netChange = false;
			HashSet<PIP> pips = new HashSet<>(nn.getPIPs());	
			for(PIP p : n.getPIPs()){
				if(!pips.contains(p)){
					System.out.println("Missing PIP " + p.toString() + " from net " + n.getName());
					netChange = true;
				}
			}
			netRoutingChanges += netChange ? 1 : 0;
		}
		
		int cellCount = original.getCells().size();
		int netCount = original.getNets().size();
		
		float placementPercent = ((float)cellMovements/(float)cellCount) * 100.0f;
		float routingPercent = ((float)netRoutingChanges/(float)netCount) * 100.0f;
		System.out.printf("Placement changes: %d/%d %3.2f%%\n", cellMovements, cellCount, placementPercent);
		System.out.printf("Net routing changes: %d/%d %3.2f%%\n", netRoutingChanges, netCount, routingPercent);
		
	}
}

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
package com.xilinx.rapidwright.util;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.List;

import com.xilinx.rapidwright.device.Device;
import com.xilinx.rapidwright.device.Site;
import com.xilinx.rapidwright.device.Tile;
import com.xilinx.rapidwright.device.Wire;

/**
 * This class is a simple method to browse device information by tile.
 * @author Chris Lavin
 * Created on: Jul 12, 2010
 */
public class BrowseDevice{

	public static void run(Device dev){
		BufferedReader br = new BufferedReader(new InputStreamReader(System.in));
		Tile t = null;
		while(true){
			System.out.println("Commands: ");
			System.out.println(" 1: Get wire connections in tile");
			System.out.println(" 2: Check if wire is a PIP wire");
			System.out.println(" 3: List RouteThrough wires");
			System.out.println(" 4: Follow wire connections");
			System.out.println(" 5: List primitives of a tile");
			System.out.println(" 6: Get tile of a primitive site");
			System.out.println(" 7: Exit");
			try {
				Integer cmd = Integer.parseInt(br.readLine().trim());
				switch(cmd){
					case 1:
						System.out.println("Enter tile name: ");
						t = dev.getTile(br.readLine().trim());
						System.out.println("Choosen Tile: " + t.getName());

						System.out.println("Enter wire name: ");
						String wire = br.readLine().trim();
						List<Wire> wires = t.getWireConnections(t.getWireIndex(wire));
						for(Wire w : wires){
							System.out.println("  " + w.toString());
						}
						break;
					case 2:
						System.out.println("Enter wire name:");
						String wire1 = br.readLine().trim();
						boolean isPIPWire = t.getPIPs(wire1).size() > 0;
						System.out.println("isPIP? " + isPIPWire);
						break;
					case 3:
						System.out.println("PIPRouteThroughs");
						/*for(WireConnection w : dev.getRouteThroughMap().keySet()){
							System.out.println("  " + w.toString(we) + " " + dev.getRouteThroughMap().get(w).toString(we));
						}*/
						break;
					case 4:
						System.out.println("Enter start tile name: ");
						t = dev.getTile(br.readLine().trim());
						System.out.println("Choosen start tile: " + t.getName());

						System.out.println("Enter start wire name: ");
						String startWire = br.readLine().trim();
						
						while(true){
							if(t.getWireConnections(t.getWireIndex(startWire)) == null){
								System.out.println("This wire has no connections, it may be a sink");
								break;
							}
							List<Wire> wireConnections = t.getWireConnections(t.getWireIndex(startWire));
							System.out.println(t.getName() + " " + startWire + ":");
							for (int i = 0; i < wireConnections.size(); i++) {
								System.out.println("  " + i + ". " + wireConnections.get(i).getTile() +" " + t.getWireName(wireConnections.get(i).getWireIndex()) + " ("+wireConnections.get(i).getIntentCode()+")");
								if(true){
									// print next hop
									Tile tmpTile = wireConnections.get(i).getTile();
									String tmpWire = tmpTile.getWireName(wireConnections.get(i).getWireIndex());
									for(Wire w : tmpTile.getWireConnections(t.getWireIndex(tmpWire))){
										System.out.println("     ->  " + w.getTile() +" " + w.getWireName() + " ("+w.getIntentCode()+")");
									}
								}
							}
							System.out.print("Choose a wire: ");
							int ndx;
							try{
								ndx = Integer.parseInt(br.readLine().trim());
								t = wireConnections.get(ndx).getTile();
								startWire = t.getWireName(wireConnections.get(ndx).getWireIndex());
							}
							catch(Exception e){
								System.out.println("Did not understand, try again.");
								continue;
							}
							
						}
						break;
					case 5:
						System.out.println("Enter tile name: ");
						t = dev.getTile(br.readLine().trim());
						System.out.println("Choosen Tile: " + t.getName());

						if(t.getSites().length == 0){
							System.out.println(t.getName() + " has no primitive sites.");
						}
						else{
							for(Site p : t.getSites()){
								System.out.println("  " + p.getName());
							}
						}
					
						break;
					case 6:
						System.out.println("Enter tile name: ");
						String siteName = br.readLine().trim();
						Site site = dev.getSite(siteName);
						if(site == null){
							System.out.println("No primitive site called \"" + siteName +  "\" exists.");
						}
						else {
							System.out.println(site.getTile());
						}
						break;
					case 7:
						return;
						
				}
			} catch (Exception e) {
				System.out.println("Bad input, try again.");
			}
		}
	}
	public static void main(String[] args){
		MessageGenerator.printHeader(" RapidWright Device Browser");		
		if(args.length != 1){
			MessageGenerator.briefMessageAndExit("USAGE: <device part name, ex: xc4vfx12ff668 >");
		}
		Device dev = (Device) Device.getDevice(args[0]);	
		
		run(dev);
	}
}

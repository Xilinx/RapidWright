/*
 * 
 * Copyright (c) 2021 Ghent University. 
 * All rights reserved.
 *
 * Author: Yun Zhou, Ghent University.
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
package com.xilinx.rapidwright.timing;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Clock enable net timing data
 */
public class CERouteTiming {
	/** Name of the BUFGCE */
	private String bufgce;
	/** Destination INT tiles of the BUFGE output and the routes */
	private Map<String, List<String>> dstINTTilesRoutes;
	/** Destination INT tiles of the BUFGE output and the delays */
	private Map<String, Short> dstINTTilesDelays;
	/** INT tile associated with the BUFGCE_CLK_IN and the delay from the INT tile to the CLK_IN */
	private Map<String, Short> intTileToBufgInDelay;
	/** INT tile associated with the BUFGCE_CLK_IN and the route from the INT tile to the CLK_IN */
	private List<String> intTileToBufgInRoute;
	
	static String ceRouteTiming = null;
	public static void setCERouteTiming(String fileName) {
		if(fileName != null) {
			ceRouteTiming = fileName;
			System.out.println("CE ROUTE TIMING IN FILE: " + ceRouteTiming);
		}else {
			System.out.println("NULL CE ROUTE TIMING");
		}
	}
	
	public CERouteTiming(String bufg) {
		String[] ss = bufg.split("/");
		this.bufgce = ss[ss.length - 1].replace(".txt", "");
		
		this.dstINTTilesRoutes = new HashMap<>();
		this.dstINTTilesDelays = new HashMap<>();
		this.intTileToBufgInDelay = new HashMap<>();
		this.intTileToBufgInRoute = new ArrayList<>();
			
		try {
			this.parseDataFromFile();
		} catch (IOException e) {
			e.printStackTrace();
		}
	}	
	
	private void parseDataFromFile() throws IOException {
		File clkTimingFile = new File(ceRouteTiming);
		if(!clkTimingFile.exists()) {
        	System.err.println("CLK TIMING FILE NOT FOUND : " + this.bufgce);
        	return;
        }
		BufferedReader reader = new BufferedReader(new FileReader(clkTimingFile));
		// NOTE: DATA TYPE (int_bufg, bufg_int, etc) MUST BE READ IN THE SAME ORDER AS IN THE FILE
		this.parseDataSection(reader, "int_bufg");
		this.parseDataSection(reader, "bufg_int");
		
		reader.close();
	}
	
	private void parseDataSection(BufferedReader reader, String section) throws IOException {
		boolean dataFound = false;
		String startLineToFind = section;
        String line;
        try {
			while ((line = reader.readLine()) != null) {
			    if (line.equals(startLineToFind)) {
			        dataFound = true;
			        break;
			    }
			}
		} catch (IOException e) {
			e.printStackTrace();
		}
        
        if(!dataFound) {
        	System.err.println("ERROR: No section header found in the file for " + section);
        	return;
        }
        if(section.equals("int_bufg")) {
        	this.readINTToBufgDelay(reader);
        }else if(section.equals("bufg_int")) {
        	this.readRouteAndDelay(reader);
        }
        
	}
	
	private void readINTToBufgDelay(BufferedReader reader) throws NumberFormatException, IOException {
		String line;
		while ((line = reader.readLine()) != null) {
			if(line.startsWith("#")) continue;
			if (line.length() == 0) {
				// the end of the current section
				return;
			 }
			
			line = line.replace("{", "").replace("}", "");
			String[] dataStrings = line.split("\\s+");
			if(dataStrings.length < 4) {
				System.out.println("CRITICAL WARNING: Incomplete data of line " + line);
				continue;
			}
			this.dstINTTilesDelays.put(dataStrings[0], Short.parseShort(dataStrings[2]));
			this.intTileToBufgInDelay.put(dataStrings[0], Short.parseShort(dataStrings[2]));// check the index of INT tile and delay
			
			for(int id = 3; id < dataStrings.length; id++) {
				this.intTileToBufgInRoute.add(dataStrings[id]);
			}
			
		}
	}
	
	private void readRouteAndDelay(BufferedReader reader) throws NumberFormatException, IOException {
		String line;
		while ((line = reader.readLine()) != null) {
			if (line.length() == 0) {
				return;
			}
			
			if(line.startsWith("#")) continue;
			line = line.replace("{", "").replace("}", "");
			String[] dataStrings = line.split("\\s+");
			if(dataStrings.length < 3 || dataStrings.length < 4) {
				System.out.println("CRITICAL WARNING: Incomplete data of line " + line);
				continue;
			}
			String intTile = dataStrings[1];
			this.dstINTTilesDelays.put(intTile, Short.parseShort(dataStrings[2]));
			
			List<String> nodes = new ArrayList<>();
			for(int i = 3; i < dataStrings.length; i++) {
				nodes.add(dataStrings[i]);
			}
			
			this.dstINTTilesRoutes.put(intTile, nodes);
		}
	}
	
	public String getBufgce() {
		return this.bufgce;
	}

	public Map<String, List<String>> getDstINTtileRoute() {
		return this.dstINTTilesRoutes;
	}

	public Map<String, Short> getDstINTtileDelay() {
		return this.dstINTTilesDelays;
	}

	public Map<String, Short> getIntTileToBufgInDelay() {
		return this.intTileToBufgInDelay;
	}

	public static String getCeRouteTiming() {
		return ceRouteTiming;
	}

	public List<String> getIntTileToBufgInRoute() {
		return this.intTileToBufgInRoute;
	}
	
	@Override
	public int hashCode() {
		return this.bufgce.hashCode();
	}
}

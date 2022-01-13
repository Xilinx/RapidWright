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

import com.xilinx.rapidwright.rwroute.RWRouteConfig;

/**
 * A {@link ClkRouteTiming} instance stores the clock route and timing template data.
 * To obtain a template file, please refer to find_clock_route_template.tcl under $RAPIDWRIGHT_PATH/tcl/rwroute.
 * When a clock route timing template file is ready, please use "--clkRouteTiming" option (see {@link RWRouteConfig})
 * to enable RWRoute to use the file for timing-driven clock routing.
 */
public class ClkRouteTiming {
	/** Name of the BUFGCE, or the name of the timing data file */
	private String bufgce;
	/** A map storing routes from CLK_OUT to different INT tiles that connect to sink pins of a global clock net */
	private Map<String, List<String>> routesToSinkINTTiles;
	/** A map storing route delays from CLK_OUT to different INT tiles that connect to sink pins of a global clock net */
	private Map<String, Short> routeDelaysToSinkINTTiles;
	/** INT tile associated with the BUFGCE_CLK_IN and the delay from the INT tile to the CLK_IN */
	private Map<String, Short> intTileToBufgInDelay;
	/** INT tile associated with the BUFGCE_CLK_IN and the route from the INT tile to the CLK_IN */
	private List<String> intTileToBufgInRoute;
	
	private String clkRouteTiming = null;
	
	public ClkRouteTiming(String fileName) {
		this.bufgce = fileName;
		this.routesToSinkINTTiles = new HashMap<>();
		this.routeDelaysToSinkINTTiles = new HashMap<>();
		this.intTileToBufgInDelay = new HashMap<>();
		this.intTileToBufgInRoute = new ArrayList<>();
		
		if(fileName != null) {
			this.clkRouteTiming = fileName;
			System.out.println("INFO: Clock route timing file set as: " + clkRouteTiming);
		}
		
		try {
			this.parseDataFromFile();
		} catch (IOException e) {
			e.printStackTrace();
		}
	}	
	
	private void parseDataFromFile() throws IOException {
		File clkTimingFile = new File(this.clkRouteTiming);
		if(!clkTimingFile.exists()) {
			throw new IllegalArgumentException("ERROR: Specified clock route timing file does not exist.");
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
        	throw new IllegalArgumentException("ERROR: No section header found in the file for " + section);
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
				throw new IllegalArgumentException("ERROR: Incomplete data of line " + line);
			}
			this.routeDelaysToSinkINTTiles.put(dataStrings[0], Short.parseShort(dataStrings[2]));
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
				throw new IllegalArgumentException("ERROR: Incomplete data of line " + line);
			}
			String intTile = dataStrings[1];
			this.routeDelaysToSinkINTTiles.put(intTile, Short.parseShort(dataStrings[2]));
			
			List<String> nodes = new ArrayList<>();
			for(int i = 3; i < dataStrings.length; i++) {
				nodes.add(dataStrings[i]);
			}
			
			this.routesToSinkINTTiles.put(intTile, nodes);
		}
	}
	
	public String getBufgce() {
		return this.bufgce;
	}

	public Map<String, List<String>> getRoutesToSinkINTTiles() {
		return this.routesToSinkINTTiles;
	}

	public Map<String, Short> getRouteDelaysToSinkINTTiles() {
		return this.routeDelaysToSinkINTTiles;
	}

	public Map<String, Short> getIntTileToBufgInDelay() {
		return this.intTileToBufgInDelay;
	}

	public String getClkRouteTiming() {
		return this.clkRouteTiming;
	}

	public List<String> getIntTileToBufgInRoute() {
		return this.intTileToBufgInRoute;
	}
	
	@Override
	public int hashCode() {
		return this.bufgce.hashCode();
	}
	
}

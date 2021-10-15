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

package com.xilinx.rapidwright.util.rwroute;

import java.io.BufferedReader;
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.Map.Entry;

import com.xilinx.rapidwright.design.Design;
import com.xilinx.rapidwright.design.Net;
import com.xilinx.rapidwright.device.Device;
import com.xilinx.rapidwright.device.Site;

/**
 * A helper class to read and set clock buffer tap levels.
 * To read: input.dcp --read input_file output_file_directory 
 * "input_file" is the file that contains buffers sites.
 * "output_file_directory" is the path to store the output file that contains the buffer sites and tap levels.
 * To set: input.dcp --set input_file output_file_directory
 * "input_file" is the file that contains buffers sites and tap levels.
 * "output_file_directory" is the path to store the output DCP file with tap levels of the buffer sites set.
 */
public class BufferTapTool {
	public static void main(String[] args) {
		if(args.length < 2){
			System.out.println("BASIC USAGE:\n <input.dcp> <--read <file directory> or --set <file>> <output directory>\n");
			return;
		}
		boolean read = args[1].equals("--read");
		if(!read && args.length < 4) {
			System.out.println("BASIC USAGE:\n <input.dcp> --set <file path> <output directory>\n");
			return;
		}
		if(read && args.length < 4) {
			System.out.println("BASIC USAGE:\n <input.dcp> --read <input file> <output file path>\n");
			return;
		}
		
		String inputDcpName = args[0].substring(args[0].lastIndexOf("/")+1);
		Design design = Design.readCheckpoint(args[0]);
		Device dev = design.getDevice();
		
		Net clock = getClockNet(design);
		if(clock == null) {
			System.err.println("ERROR: No clock net found");
		}
		
		Map<String, Integer> siteTaps = getBufferSiteTapsFromFile(args[2]);
		if(siteTaps.isEmpty()) {
			System.err.println("ERROR: No valid content found in " + args[2]);
			return;
		}
		
		if(read) {
			String filePath = args[3].endsWith("/")? args[3] : args[3] + "/";
			readBufferTapsToFile(inputDcpName, clock, siteTaps, dev, filePath);
			return;
		}
		
		// set	
		setBufferTaps(siteTaps, clock, dev);
		String outputDCP = args[3].endsWith("/")? args[3] : args[3] + "/";
		outputDCP += inputDcpName.replace(".dcp", "_buffer_set.dcp");
		design.writeCheckpoint(outputDCP);
	}
	
	/**
	 * Gets the clock net of a design.
	 * @param design The design in question.
	 * @return The clock net of the design.
	 */
	private static Net getClockNet(Design design) {
		for(Net n : design.getNets()) {
			if(n.isClockNet()) {
				return n;
			}
		}
		return null;
	}
	
	/**
	 * Gets buffer sites info from the input file.
	 * @param file The file to use that contains buffer sites (and buffer taps in the set mode).
	 * @return A map contains buffer site names and corresponding taps when taps provided.
	 */
	private static Map<String, Integer> getBufferSiteTapsFromFile(String file) {
		Map<String, Integer> siteTaps = new HashMap<>();	
		try {
			BufferedReader myReader = new BufferedReader(new FileReader(file));
			String line;
			try {
				while((line = myReader.readLine()) != null) {
					if (line.length() == 0 || line.startsWith("#") || !line.startsWith("BUFCE_")) {
						break;
					}
					String[] siteTapLevels = line.split("\\s+");
					if(siteTapLevels.length == 1) {
						siteTaps.put(siteTapLevels[0], null);
					}else {
						siteTaps.put(siteTapLevels[0], Integer.parseInt(siteTapLevels[1]));
					}		
				}
			} catch (IOException e) {
				e.printStackTrace();
			}
		} catch (FileNotFoundException e) {
			e.printStackTrace();
		}
		
		return siteTaps;
	}
	
	/**
	 * Reads buffer taps of a clock net from the design checkpoint and write taps to a file.
	 * @param inputDCPName The input design checkpoint file name.
	 * @param clock The clock net in question.
	 * @param siteTaps A map contains buffer site names.
	 * @param dev The device instance to use.
	 * @param filePath The file path of the output file.
	 */
	private static void readBufferTapsToFile(String inputDCPName, Net clock, Map<String, Integer> siteTaps, Device dev, String filePath) {
		String readToFile = inputDCPName.replace(".dcp", "_buffer_tap.txt");
		try {
			FileWriter myWriter = new FileWriter(filePath + readToFile);
			
			for(String s : siteTaps.keySet()) {
				Site site = dev.getSite(s);
				if(site == null) {
					System.err.println("ERROR: No site found under name " + s);
					continue;
				}
				int tap = clock.getBufferDelay(site);
				myWriter.write(site + " \t\t" + tap + "\n");
				System.out.println(site + " \t\t" + tap);
			}
			myWriter.close();
			
		} catch (IOException e) {
			e.printStackTrace();
		}
	}
	
	/**
	 * Sets buffer taps of the clock net.
	 * @param siteTaps A map contains buffer site names and buffer taps.
	 * @param clock The clock net in question.
	 * @param dev The device instance to use.
	 */
	private static void setBufferTaps(Map<String, Integer> siteTaps, Net clock, Device dev) {
		for(Entry<String, Integer> siteTap : siteTaps.entrySet()) {
			String siteName = siteTap.getKey();
			Site site = dev.getSite(siteName);
			int tap = siteTap.getValue();
			if(site == null) {
				System.err.println("ERROR: No site found under name " + siteName);
				continue;
			}
			clock.setBufferDelay(site, tap);
			System.out.println("INFO: Set " + site + " tap = " + tap);
			System.out.println("INFO: Tap after setting: " + clock.getBufferDelay(site));
		}
	}
}

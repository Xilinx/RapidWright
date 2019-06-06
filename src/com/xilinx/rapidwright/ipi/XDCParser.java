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
package com.xilinx.rapidwright.ipi;

import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

import com.xilinx.rapidwright.device.Device;
import com.xilinx.rapidwright.util.FileTools;


/**
 * Parses an XDC file for package constraints only.  Does not
 * perform full XDC parsing.
 * 
 * Created on: Jul 27, 2015
 */
public class XDCParser {

	private static boolean expect(String expected, String found, int lineNum, String line){
		if(!expected.equals(found)){
			throw new RuntimeException("\nERROR: While parsing line:\n   '" +
				line + "' (line number " + lineNum + ")\n" + "   Expected: '" +
					expected + "'\n      Found: '" + found + "'\nStack Trace:");
		}
		return true;
	}

	/**
	 * Very rudimentary parsing to extract IO placements and IO standards.  This
	 * does not support many Tcl constructs.
	 * @param fileName Name of the XDC file to parse
	 * @param dev The device associated with the design.
	 * @return A map of port names to package pin information.
	 */
	public static HashMap<String,PackagePinConstraint> parseXDC(String fileName, Device dev){
		HashMap<String,PackagePinConstraint> constraints = new HashMap<>();
		int lineNum = 1;
		for(String line : FileTools.getLinesFromTextFile(fileName)){
			if(line.trim().startsWith("#")) continue;
			if(line.contains("set_property") && line.contains("PACKAGE_PIN")){
				String[] parts = line.split("\\s+");
				expect("set_property", parts[0], lineNum, line);
				expect("PACKAGE_PIN", parts[1], lineNum, line);
				String pinLoc = parts[2];
				if(!dev.getActivePackage().getPackagePinMap().containsKey(pinLoc)){
					expect("<VALID_PKG_PIN>", pinLoc,lineNum,line);
				}
				expect("[get_ports", parts[3], lineNum, line);
				String key = parts[4].substring(0, parts[4].lastIndexOf(']'));
				key = key.replace("}", "");
				key = key.replace("{", "");
				PackagePinConstraint pkgPin = constraints.get(key);
				if(pkgPin == null){
					pkgPin = new PackagePinConstraint();
					constraints.put(key, pkgPin);
				}
				pkgPin.setName(pinLoc);
			}else if(line.contains("set_property") && line.contains("IOSTANDARD")){
				String[] parts = line.split("\\s+");
				expect("set_property", parts[0], lineNum, line);
				expect("IOSTANDARD", parts[1], lineNum, line);
				String ioStandard = parts[2];
				expect("[get_ports", parts[3], lineNum, line);
				String key = parts[4].substring(0, parts[4].lastIndexOf(']'));
				key = key.replace("}", "");
				key = key.replace("{", "");
				PackagePinConstraint pkgPin = constraints.get(key);
				if(pkgPin == null){
					pkgPin = new PackagePinConstraint();
					constraints.put(key, pkgPin);
				}
				pkgPin.setIOStandard(ioStandard);
			}
			lineNum++;
		}
		
		
		return constraints;
	}
	
	public static void writeXDC(List<String> constraints, OutputStream out){
		if(constraints == null) return;
		try {
			for(String s : constraints){
				out.write(s.getBytes());
				out.write('\n');
			}
		} catch (IOException e) {
			e.printStackTrace();
		}
	}
}

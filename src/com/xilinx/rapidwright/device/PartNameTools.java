/* 
 * Copyright (c) 2019 Xilinx, Inc. 
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
package com.xilinx.rapidwright.device;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import com.esotericsoftware.kryo.io.UnsafeInput;
import com.xilinx.rapidwright.device.FamilyType;
import com.xilinx.rapidwright.device.Part;
import com.xilinx.rapidwright.device.Series;
import com.xilinx.rapidwright.util.FileTools;

/**
 * Generated on: Wed Sep 25 17:39:08 2019
 * by: com.xilinx.rapidwright.release.PartNamePopulator
 * 
 * Class to hold utility APIs dealing with Parts and device names.
 */
public class PartNameTools {
	public static HashMap<String,Part> partMap;
	static {
		partMap = new HashMap<String,Part>();
		UnsafeInput his = FileTools.getUnsafeInputStream(FileTools.getRapidWrightResourceInputStream(FileTools.PART_DB_PATH));
		String[] strings = FileTools.readStringArray(his);
		int partCount = 0;
		partCount = his.readInt();
		for(int i=0; i < partCount; i++){
			int[] part = FileTools.readIntArray(his);
			Part tmpPart = new Part(
				strings[part[0]],
				FamilyType.valueOf(strings[part[1]].toUpperCase()),
				strings[part[2]],
				FamilyType.valueOf(strings[part[3]].toUpperCase()),
				strings[part[4]],
				strings[part[5]],
				strings[part[6]],
				strings[part[7]],
				strings[part[8]],
				Series.valueOf(strings[part[9]])
				);
			partMap.put(strings[part[0]], tmpPart);
			if(!strings[part[8]].equals("")) {
				partMap.put(strings[part[4]]+"-"+strings[part[8]], tmpPart);
			} else {
				partMap.put(strings[part[4]], tmpPart);
				partMap.put(strings[part[4]]+strings[part[5]], tmpPart);
				partMap.put(strings[part[4]]+strings[part[5]]+strings[part[6]], tmpPart);
				partMap.put(strings[part[4]]+"-"+strings[part[5]], tmpPart);
				partMap.put(strings[part[4]]+"-"+strings[part[5]]+strings[part[6]], tmpPart);
			}
		}
	}
	public static Part getPart(String partName) {
		Part p = partMap.get(partName);
		if(p == null && !partName.startsWith("xc")){
			p = partMap.get("xc" + partName);
		}
		if(p == null){
			throw new RuntimeException("\n\n\tERROR: Couldn't identify " + partName + " in RapidWright part database. ");
		}
		return p;
	}
	/**
	 * Returns a collection of all known parts
	 * @return
	 */
	public static Collection<Part> getParts(){
		return partMap.values();
	}
	/**
	 * This function returns the architecture (as a family type)
	 * @param type The given family type.
	 * @return The base architecture as a family type
	 */
	public static FamilyType getArchitectureFromFamilyType(FamilyType type) {
		switch(type){
			case AARTIX7: return FamilyType.ARTIX7;
			case AKINTEX7: return FamilyType.KINTEX7;
			case ARTIX7: return FamilyType.ARTIX7;
			case ARTIX7L: return FamilyType.ARTIX7;
			case ASPARTAN7: return FamilyType.SPARTAN7;
			case AZYNQ: return FamilyType.ZYNQ;
			case AZYNQUPLUS: return FamilyType.ZYNQUPLUS;
			case KINTEX7: return FamilyType.KINTEX7;
			case KINTEX7L: return FamilyType.KINTEX7;
			case KINTEXU: return FamilyType.KINTEXU;
			case KINTEXUPLUS: return FamilyType.KINTEXUPLUS;
			case QARTIX7: return FamilyType.ARTIX7;
			case QKINTEX7: return FamilyType.KINTEX7;
			case QKINTEX7L: return FamilyType.KINTEX7;
			case QKINTEXU: return FamilyType.KINTEXU;
			case QKINTEXUPLUS: return FamilyType.KINTEXUPLUS;
			case QRKINTEXU: return FamilyType.KINTEXU;
			case QVIRTEX7: return FamilyType.VIRTEX7;
			case QVIRTEXUPLUS: return FamilyType.VIRTEXUPLUS;
			case QZYNQ: return FamilyType.ZYNQ;
			case QZYNQUPLUS: return FamilyType.ZYNQUPLUS;
			case QZYNQUPLUSRFSOC: return FamilyType.ZYNQUPLUSRFSOC;
			case SPARTAN7: return FamilyType.SPARTAN7;
			case VIRTEX7: return FamilyType.VIRTEX7;
			case VIRTEXU: return FamilyType.VIRTEXU;
			case VIRTEXUPLUS: return FamilyType.VIRTEXUPLUS;
			case VIRTEXUPLUS58GES1: return FamilyType.VIRTEXUPLUS58G;
			case VIRTEXUPLUSHBM: return FamilyType.VIRTEXUPLUSHBM;
			case VIRTEXUPLUSHBMES1: return FamilyType.VIRTEXUPLUSHBM;
			case ZYNQ: return FamilyType.ZYNQ;
			case ZYNQUPLUS: return FamilyType.ZYNQUPLUS;
			case ZYNQUPLUSRFSOC: return FamilyType.ZYNQUPLUSRFSOC;
			default: return null;
		}
	}
	/**
	 * This method will return a full architecture name as stored in Vivado.
	 * @param type Type of family to get formal name.
	 * @return The formal family name or null if none exists.
	 */
	public static String getFullArchitectureName(FamilyType type) {
		switch(type){
			case AARTIX7: return "Artix-7";
			case AKINTEX7: return "Kintex-7";
			case ARTIX7: return "Artix-7";
			case ARTIX7L: return "Artix-7";
			case ASPARTAN7: return "Spartan-7";
			case AZYNQ: return "Zynq-7000";
			case AZYNQUPLUS: return "Zynq UltraScale+";
			case KINTEX7: return "Kintex-7";
			case KINTEX7L: return "Kintex-7";
			case KINTEXU: return "Kintex UltraScale";
			case KINTEXUPLUS: return "Kintex UltraScale+";
			case QARTIX7: return "Artix-7";
			case QKINTEX7: return "Kintex-7";
			case QKINTEX7L: return "Kintex-7";
			case QKINTEXU: return "Kintex UltraScale";
			case QKINTEXUPLUS: return "Kintex UltraScale+";
			case QRKINTEXU: return "Kintex UltraScale";
			case QVIRTEX7: return "Virtex-7";
			case QVIRTEXUPLUS: return "Virtex UltraScale+";
			case QZYNQ: return "Zynq-7000";
			case QZYNQUPLUS: return "Zynq UltraScale+";
			case QZYNQUPLUSRFSOC: return "Zynq UltraScale+ RFSOC";
			case SPARTAN7: return "Spartan-7";
			case VIRTEX7: return "Virtex-7";
			case VIRTEXU: return "Virtex UltraScale";
			case VIRTEXUPLUS: return "Virtex UltraScale+";
			case VIRTEXUPLUS58G: return "Virtex UltraScale+";
			case VIRTEXUPLUS58GES1: return "Virtex UltraScale+";
			case VIRTEXUPLUSHBM: return "Virtex UltraScale+";
			case VIRTEXUPLUSHBMES1: return "Virtex UltraScale+";
			case ZYNQ: return "Zynq-7000";
			case ZYNQUPLUS: return "Zynq UltraScale+";
			case ZYNQUPLUSRFSOC: return "Zynq UltraScale+ RFSOC";
			default: return null;
		}
	}
	/**
	 * Gets the series to which the provided family belongs.
	 * @param type Type of family to get formal name.
	 * @return The series or technology generation or null if unknown.
	 */
	public static Series getSeriesFromFamilyType(FamilyType type) {
		switch(type){
			case AARTIX7: return Series.Series7;
			case AKINTEX7: return Series.Series7;
			case ARTIX7: return Series.Series7;
			case ARTIX7L: return Series.Series7;
			case ASPARTAN7: return Series.Series7;
			case AZYNQ: return Series.Series7;
			case AZYNQUPLUS: return Series.UltraScalePlus;
			case KINTEX7: return Series.Series7;
			case KINTEX7L: return Series.Series7;
			case KINTEXU: return Series.UltraScale;
			case KINTEXUPLUS: return Series.UltraScalePlus;
			case QARTIX7: return Series.Series7;
			case QKINTEX7: return Series.Series7;
			case QKINTEX7L: return Series.Series7;
			case QKINTEXU: return Series.UltraScale;
			case QKINTEXUPLUS: return Series.UltraScalePlus;
			case QRKINTEXU: return Series.UltraScale;
			case QVIRTEX7: return Series.Series7;
			case QVIRTEXUPLUS: return Series.UltraScalePlus;
			case QZYNQ: return Series.Series7;
			case QZYNQUPLUS: return Series.UltraScalePlus;
			case QZYNQUPLUSRFSOC: return Series.UltraScalePlus;
			case SPARTAN7: return Series.Series7;
			case VIRTEX7: return Series.Series7;
			case VIRTEXU: return Series.UltraScale;
			case VIRTEXUPLUS: return Series.UltraScalePlus;
			case VIRTEXUPLUS58G: return Series.UltraScalePlus;
			case VIRTEXUPLUS58GES1: return Series.UltraScalePlus;
			case VIRTEXUPLUSHBM: return Series.UltraScalePlus;
			case VIRTEXUPLUSHBMES1: return Series.UltraScalePlus;
			case ZYNQ: return Series.Series7;
			case ZYNQUPLUS: return Series.UltraScalePlus;
			case ZYNQUPLUSRFSOC: return Series.UltraScalePlus;
			default: return null;
		}
	}
	/**
	 * Gets the canonical device name and package combination.  Some
	 * parts have '-' between them, but Series 7 parts do not. This method
	 * attempts to handle the case properly.
	 */
	public static String getDeviceAndPackage(Part p) {
		if(p.getArchitectureFullName().contains("UltraScale")) {
			return p.getDevice()+"-"+p.getPkg();
		}
		return p.getDevice()+p.getPkg();
	}
	/**
	 * Gets all parts that are available for the provided series.
	 * @param series The series of interest
	 * @return A list of all parts that are available for
	 * the provided series.
	 */
	public static List<Part> getAllParts(Series series){
		ArrayList<Part> parts = new ArrayList<>();
		for(Part p : getParts()){
			if(p.getSeries() == series) parts.add(p);
		}
		return parts;
	}
}

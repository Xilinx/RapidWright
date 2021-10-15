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
package com.xilinx.rapidwright.device.helper;

import com.xilinx.rapidwright.device.Device;
import com.xilinx.rapidwright.device.FamilyType;
import com.xilinx.rapidwright.device.PartNameTools;
import com.xilinx.rapidwright.device.Tile;
import com.xilinx.rapidwright.device.TileTypeEnum;
import com.xilinx.rapidwright.util.Utils;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map.Entry;
import java.util.TreeSet;

/**
 * Represents a pattern of tile columns found on a Xilinx device.
 * Created on: Jun 2, 2016
 */
public class TileColumnPattern extends ArrayList<TileTypeEnum> implements Comparable<TileColumnPattern>{

	private enum TypesOfInterest {
		SLICEL,
		SLICEM,
		BRAM,
		DSP,
		URAM
	}

	private static final long serialVersionUID = 3453628493683591805L;

	public static int MAX_PATTERN_LENGTH = 26;
	/** The number of consecutive NULL columns for a pattern not to cross */
	public static final int NULL_COLUMN_BREAK_SIZE = 3;
	/** Packs a number of has___() flags for quick answers */
	private EnumSet<TypesOfInterest> flags = EnumSet.noneOf(TypesOfInterest.class);
	/** Place holder for the number of occurrences of this tile column pattern in a particular device */
	private int numInstances = 0;

	
	/** Contains the tile types to create patterns from (all other tile types are ignored) */
	private static HashSet<TileTypeEnum> typesOfInterest;
	static{
		typesOfInterest = new HashSet<TileTypeEnum>();
		typesOfInterest.addAll(Utils.getCLBTileTypes());
		typesOfInterest.addAll(Utils.getDSPTileTypes());
		typesOfInterest.addAll(Utils.getBRAMTileTypes());
		typesOfInterest.addAll(Utils.getURAMTileTypes());
	}
	
	public TileColumnPattern(){
		updateFlags();
	}
	
	public static TileColumnPattern createTileColumnPattern(List<TileTypeEnum> types) {
		return createTileColumnPattern(types,0, types.size());
	}
	
	/**
	 * Creates a TileColumnPattern from an existing list of tile types and uses the start and end 
	 * as indicies to get a sublist of filteredTypes.
	 * @param filteredTypes The list of tile types to turn into a pattern
	 * @param start The start index to use to build a subList of filteredTypes
	 * @param end The end index to use to build a subList of filteredTypes
	 * @return A tile column pattern or null if the list contained a NULL (break) tileType.
	 */
	public static TileColumnPattern createTileColumnPattern(List<TileTypeEnum> filteredTypes, int start, int end) {
		TileColumnPattern p = new TileColumnPattern();
		for(TileTypeEnum t : filteredTypes.subList(start, end)){
			if(t == TileTypeEnum.NULL) return null;
			p.add(t);
		}
		p.updateFlags();
		return p;
	}

	private void updateFlags(){
		flags = EnumSet.noneOf(TypesOfInterest.class);
		for(TileTypeEnum t : this){
			if(Utils.isCLBM(t)){
				flags.add(TypesOfInterest.SLICEM);
			}else if(Utils.isCLB(t)){
				flags.add(TypesOfInterest.SLICEL);
			}else if(Utils.isDSP(t)){
				flags.add(TypesOfInterest.DSP);
			}else if(Utils.isBRAM(t)){
				flags.add(TypesOfInterest.BRAM);
			}else if(Utils.isURAM(t)){
				flags.add(TypesOfInterest.URAM);
			}else {
				throw new RuntimeException("ERROR: Unexpected TileTypeEnum, please re-examine source code to properly handle " + t);
			}
		}
	}
	
	public boolean hasSLICEL(){
		return flags.contains(TypesOfInterest.SLICEL);
	}
	
	public boolean hasSLICEM(){
		return flags.contains(TypesOfInterest.SLICEM);
	}
	
	public boolean hasBRAM(){
		return flags.contains(TypesOfInterest.BRAM);
	}
	
	public boolean hasDSP(){
		return flags.contains(TypesOfInterest.DSP);
	}
	public boolean hasURAM(){
		return flags.contains(TypesOfInterest.URAM);
	}
	
	/**
	 * @return the numInstances
	 */
	public int getNumInstances() {
		return numInstances;
	}

	/**
	 * @param numInstances the numInstances to set
	 */
	public void setNumInstances(int numInstances) {
		this.numInstances = numInstances;
	}

	/**
	 * Finds the first row that has populated BRAM, DSP, URAM, and CLB tiles to help identify
	 * tile column types.  Can reliably iterate over this row and identify column types.
	 * @param dev The device of interest
	 * @param wantLaguna true if we are interested in a row with laguna tiles, false if interested in one without
	 * @return The row index that contains populated BRAM and DSP tiles.
	 */
	public static int getCommonRow(Device dev, boolean wantLaguna){
		boolean devHasUram = (PartNameTools.getPart(dev.getName()).getUltraRams() != 0);
		for(int row=0; row < dev.getRows(); row++){
			boolean hasDSP = false;
			boolean hasBRAM = false;
			boolean hasCLB = false;
			boolean hasURAM = false;
			boolean hasLaguna = false;
			for(int col=0; col < dev.getColumns(); col++){
				Tile t = dev.getTile(row, col);
				TileTypeEnum tt = t.getTileTypeEnum();
				if(Utils.isDSP(tt)) hasDSP = true;
				if(Utils.isBRAM(tt)) hasBRAM = true;
				if(Utils.isCLB(tt)) hasCLB = true;
				if (Utils.isURAM(tt)) hasURAM = true;
				if (tt == TileTypeEnum.LAG_LAG) {
					hasLaguna = true;
					if (!wantLaguna) {
						break;
					}
				}
			}
			//Cannot test inside loop since we may hit the first laguna tile after having satisfied all other conditions
			if(hasDSP && hasBRAM && hasCLB && (!devHasUram || hasURAM) && (hasLaguna==wantLaguna)) {
				return row;
			}
		}
		throw new RuntimeException("Did not find any row that matched all conditions");
	}


	/**
	 * Finds the first row that has populated BRAM, DSP, URAM, and CLB tiles but no Laguna tiles to help identify
	 * tile column types.  Can reliably iterate over this row and identify column types.
	 * @param dev The device of interest
	 * @return The row index that contains populated BRAM and DSP tiles.
	 */
	public static int getCommonRow(Device dev) {
		return getCommonRow(dev, false);
	}
	
	/**
	 * Creates a map where the keys are all tile column patterns for the given device and 
	 * the values are sets of occurrences/instances of those tile column patterns (represented
	 * by the the column index of the start of the pattern).  
	 * @param dev The device of interest
	 * @param wantLaguna true if we are interested in a row with laguna tiles, false if interested in one without
	 * @return The map between tile column patterns and their respective instances.
	 */
	public static HashMap<TileColumnPattern,TreeSet<Integer>> genColumnPatternMap(Device dev, boolean wantLaguna){
		HashMap<TileColumnPattern,TreeSet<Integer>> colPatternMap = new HashMap<TileColumnPattern, TreeSet<Integer>>();

		// We need to know what row index to start from to avoid missing DSP/BRAM tiles with nulls
		int rowIdx = getCommonRow(dev, wantLaguna);
		
		// First create a filtered list of columns of only the CLB/BRAM/DSP types of interest
		ArrayList<TileTypeEnum> filteredTypes = new ArrayList<TileTypeEnum>();
		ArrayList<Integer> columnIdxs = new ArrayList<Integer>();
		int nullCtr = 0;
		int longestRunWithoutNull = 1;
		int currRunWithoutNull = 0;
		for(int col=0; col < dev.getColumns(); col++){
			Tile tile = dev.getTile(rowIdx, col);
			if(typesOfInterest.contains(tile.getTileTypeEnum())){
				filteredTypes.add(tile.getTileTypeEnum());
				columnIdxs.add(tile.getColumn());
				nullCtr = 0;
				currRunWithoutNull++;
			}else if(tile.getTileTypeEnum() == TileTypeEnum.NULL){
				nullCtr++;
				if(NULL_COLUMN_BREAK_SIZE == nullCtr){
					filteredTypes.add(tile.getTileTypeEnum());
					columnIdxs.add(tile.getColumn());
					if(currRunWithoutNull > longestRunWithoutNull){
						longestRunWithoutNull = currRunWithoutNull;
					}
					currRunWithoutNull = 0;
				}
			}else{
				nullCtr = 0;
			}
		}
		MAX_PATTERN_LENGTH = longestRunWithoutNull;
		if(longestRunWithoutNull==1 && (currRunWithoutNull>0)) {
			MAX_PATTERN_LENGTH = currRunWithoutNull;
		}
		// Generate all possible patterns and store them in the map keeping track of each
		// instance of each pattern
		for(int i=1; i < MAX_PATTERN_LENGTH; i++){
			for(int j=0; j < filteredTypes.size(); j++){
				if(j+i > filteredTypes.size()) continue;
				TileColumnPattern curr = createTileColumnPattern(filteredTypes, j, j+i);
				if(curr == null) continue;
				TreeSet<Integer> matches = colPatternMap.get(curr);
				if(matches == null){
					matches = new TreeSet<Integer>();
					colPatternMap.put(curr,matches);
				}
				matches.add(columnIdxs.get(j));
			}
		}
		
		
		return colPatternMap;
	}


	/**
	 * Creates a map where the keys are all tile column patterns for the given device and
	 * the values are sets of occurrences/instances of those tile column patterns (represented
	 * by the the column index of the start of the pattern).
	 * @param dev The device of interest
	 * @return The map between tile column patterns and their respective instances.
	 */
	public static HashMap<TileColumnPattern,TreeSet<Integer>> genColumnPatternMap(Device dev){
		return genColumnPatternMap(dev, false);
	}
	
	/**
	 * Gets an array of all tile column pattersn sorted by those with the most number of instances.
	 * @param map A tile column pattern map such as one generated in genColumnPatternMap().
	 * @return An array of tile column patterns sorted by the patterns with the most number of instances.
	 */
	public static TileColumnPattern[] getSortedMostCommonPatterns(HashMap<TileColumnPattern,TreeSet<Integer>> map){
		int size = map.size();
		TileColumnPattern[] sorted = new TileColumnPattern[size];
		int i = 0; 
		for(Entry<TileColumnPattern,TreeSet<Integer>> e : map.entrySet()){
			e.getKey().setNumInstances(e.getValue().size());
			sorted[i++] = e.getKey();
		}
		
		Arrays.sort(sorted);
		return sorted;
	}
	
	/**
	 * Helper function to help visualize tile column patterns by assigning an ASCII charcter
	 * to certain tile types.
	 * @param type The tile type of interest.
	 * @param arch The device family
	 * @return A character to represent the tile type or '0' if not of interest.
	 */
	@SuppressWarnings("incomplete-switch")
	public static char getTileCharacter(TileTypeEnum type, FamilyType arch){
		if(arch == FamilyType.KINTEXU || arch == FamilyType.VIRTEXU){
			switch (type){
				case INT: return 'I'; 
				case CLEL_L:return 'L';
				case CLEL_R: return 'l';
				case CLE_M: return 'M';
				case CLE_M_R:return 'm';
				case DSP: return 'D';
				case BRAM: return 'B';
			}
		}
		return '0';
	}
	
	/**
	 * Gets the short-hand ASCII tile column pattern.
	 * @param arch Family of the device of interest. 
	 * @return A string of the short-hand ASCII tile column type representation.
	 */
	public String getTilePatternString(FamilyType arch){
		StringBuilder sb = new StringBuilder();
		for(TileTypeEnum t : this){
			sb.append(getTileCharacter(t, arch));
		}
		return sb.toString();
	}
	
	public static void main(String[] args) {
		String part = "xcku040-ffva1156-2-e";
		
		Device dev = Device.getDevice(part);
		FamilyType arch = dev.getArchitecture();
		HashMap<TileColumnPattern,TreeSet<Integer>> map = genColumnPatternMap(dev);
		for(TileColumnPattern p : getSortedMostCommonPatterns(map)){
			TreeSet<Integer> set = map.get(p);
			System.out.print(p.getTilePatternString(arch) + " " + p.getNumInstances() + " {");
			for(Integer i : set){
				System.out.print(i + ",");
			}
			System.out.println("}");
		}
	}

	@Override
	public int compareTo(TileColumnPattern o) {
		int numInstsDiff = o.getNumInstances() - getNumInstances();
		if(numInstsDiff == 0){
			return size() - o.size();
		}
		return numInstsDiff;
	}
}

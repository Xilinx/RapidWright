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

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map.Entry;

import com.xilinx.rapidwright.device.Device;
import com.xilinx.rapidwright.device.FamilyType;
import com.xilinx.rapidwright.device.Tile;
import com.xilinx.rapidwright.device.TileTypeEnum;
import com.xilinx.rapidwright.util.Utils;

import java.util.TreeSet;

/**
 * Represents a pattern of tile columns found on a Xilinx device.
 * Created on: Jun 2, 2016
 */
public class TileColumnPattern extends ArrayList<TileTypeEnum> implements Comparable<TileColumnPattern>{

	private static final long serialVersionUID = 3453628493683591805L;

	public static int MAX_PATTERN_LENGTH = 26;
	/** The number of consecutive NULL columns for a pattern not to cross */
	public static final int NULL_COLUMN_BREAK_SIZE = 3;
	/** Packs a number of has___() flags for quick answers */
	private int flags = 0;
	/** Place holder for the number of occurrences of this tile column pattern in a particular device */
	private int numInstances = 0;
	
	private static final int HAS_SLICEL = 0x1;
	private static final int HAS_SLICEM = 0x2;
	private static final int HAS_BRAM = 0x4;
	private static final int HAS_DSP = 0x8;
	
	/** Contains the tile types to create patterns from (all other tile types are ignored) */
	private static HashSet<TileTypeEnum> typesOfInterest;
	static{
		typesOfInterest = new HashSet<TileTypeEnum>();
		for(TileTypeEnum t : Utils.getCLBTileTypes()){
			typesOfInterest.add(t);
		}
		for(TileTypeEnum t : Utils.getDSPTileTypes()){
			typesOfInterest.add(t);
		}
		for(TileTypeEnum t : Utils.getBRAMTileTypes()){
			typesOfInterest.add(t);
		}
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
		flags = 0;
		for(TileTypeEnum t : this){
			if(Utils.isCLBM(t)){
				flags |= HAS_SLICEM;
			}else if(Utils.isCLB(t)){
				flags |= HAS_SLICEL;
			}else if(Utils.isDSP(t)){
				flags |= HAS_DSP;
			}else if(Utils.isBRAM(t)){
				flags |= HAS_BRAM;
			}else {
				throw new RuntimeException("ERROR: Unexpected TileTypeEnum, please re-examine source code to properly handle " + t);
			}
		}
	}
	
	public boolean hasSLICEL(){
		return (flags & HAS_SLICEL) != 0;
	}
	
	public boolean hasSLICEM(){
		return (flags & HAS_SLICEM) != 0;
	}
	
	public boolean hasBRAM(){
		return (flags & HAS_BRAM) != 0;
	}
	
	public boolean hasDSP(){
		return (flags & HAS_DSP) != 0;
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
	 * Finds the first row that has a populated BRAM or DSP tile to help identify
	 * tile column types.  Can reliably iterate over this row and identify column types.
	 * @param dev The device of interest
	 * @return The row index that contains populated BRAM or DSP tiles.
	 */
	public static int getCommonRow(Device dev){
		int rowIdx = 0;
		outer: for(int row=0; row < dev.getRows(); row++){
			for(int col=0; col < dev.getColumns(); col++){
				Tile t = dev.getTile(row, col);
				TileTypeEnum tt = t.getTileTypeEnum();
				if(Utils.isDSP(tt) || Utils.isBRAM(tt)){
					rowIdx = row;
					break outer;
				}
			}
		}
		return rowIdx;
	}
	
	/**
	 * Creates a map where the keys are all tile column patterns for the given device and 
	 * the values are sets of occurrences/instances of those tile column patterns (represented
	 * by the the column index of the start of the pattern).  
	 * @param dev The device of interest
	 * @return The map between tile column patterns and their respective instances.
	 */
	public static HashMap<TileColumnPattern,TreeSet<Integer>> genColumnPatternMap(Device dev){
		HashMap<TileColumnPattern,TreeSet<Integer>> colPatternMap = new HashMap<TileColumnPattern, TreeSet<Integer>>();

		// We need to know what row index to start from to avoid missing DSP/BRAM tiles with nulls
		int rowIdx = getCommonRow(dev);
		
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

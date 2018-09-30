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
package com.xilinx.rapidwright.placer.blockplacer;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;

import com.xilinx.rapidwright.design.ModuleInst;
//import com.xilinx.rapidwright.design.ModuleInst;
import com.xilinx.rapidwright.design.Net;
import com.xilinx.rapidwright.design.SiteInst;
import com.xilinx.rapidwright.device.PIP;
import com.xilinx.rapidwright.device.Site;
import com.xilinx.rapidwright.device.Tile;

/**
 * This extends {@link ModuleInst} and is used by {@link BlockPlacer} and {@link BlockPlacer2}
 * to help calculate system cost.
 * @author clavin
 *
 */
public class HardMacro extends ModuleInst implements Comparable<Object> {
	
	private HashSet<Site> validSiteSet;
	
	private ArrayList<PortWire> connectedPortWires;
	
	private HashSet<Path> connectedPaths;
	
	private Site tempAnchorSite;
	
	protected int topReference = Integer.MIN_VALUE;
	protected int bottomReference = Integer.MIN_VALUE;
	protected int leftReference = Integer.MIN_VALUE;
	protected int rightReference = Integer.MIN_VALUE;
	
	private int tileSize = 0;
	
	protected int top;
	protected int bottom;
	protected int left;
	protected int right;
	
	public HardMacro(ModuleInst moduleInst) {
		super(moduleInst);
		setConnectedPortWires(new ArrayList<PortWire>());
		connectedPaths = new HashSet<Path>();
	}

	/**
	 * Updates the total number of tiles used in the hard macro.
	 */
	public void calculateTileSize(){
		HashSet<Tile> tileSet = new HashSet<Tile>(); 
		for(SiteInst i : getInsts()){
			tileSet.add(i.getTile());
		}
		for(Net n : getNets()){
			for(PIP p : n.getPIPs()){
				tileSet.add(p.getTile());
			}
		}
		this.setTileSize(tileSet.size());
	}
	
	/**
	 * @return the validPlacements
	 */
	public ArrayList<Site> getValidPlacements() {
		return getModule().getAllValidPlacements();
	}

	public boolean isValidPlacement(){
		return validSiteSet.contains(tempAnchorSite);
	}
	
	public void setValidPlacements() {
		validSiteSet = new HashSet<Site>(getValidPlacements());
	}
	
	public void unsetTempAnchorSite(){
		this.tempAnchorSite = null;
	}
	
	/**
	 * @return the tempAnchorSite
	 */
	public Site getTempAnchorSite() {
		return tempAnchorSite;
	}

	/**
	 * @param tempAnchorSite the tempAnchorSite to set
	 */
	public void setTempAnchorSite(Site tempAnchorSite, HashMap<Site, HardMacro> currentPlacements) {
		Tile t = null;
		
		// calculate the bounding box for the module relative to the anchor
		if(this.tempAnchorSite == null && topReference == Integer.MIN_VALUE){
			t = getModule().getAnchor().getTile();
			int topIndex = t.getRow();
			int bottomIndex = topIndex;
			int leftIndex = t.getColumn();
			int rightIndex = leftIndex;
			
			for(SiteInst instance : getModule().getSiteInsts()){
				t = instance.getTile();
				if(topIndex > t.getRow()) topIndex = t.getRow();
				if(bottomIndex < t.getRow()) bottomIndex = t.getRow();
				if(leftIndex > t.getColumn()) leftIndex = t.getColumn();
				if(rightIndex < t.getColumn()) rightIndex = t.getColumn();
			}
			for(Net net : getModule().getNets()){
				for(PIP pip : net.getPIPs()){
					t = pip.getTile();
					if(topIndex > t.getRow()) topIndex = t.getRow();
					if(bottomIndex < t.getRow()) bottomIndex = t.getRow();
					if(leftIndex > t.getColumn()) leftIndex = t.getColumn();
					if(rightIndex < t.getColumn()) rightIndex = t.getColumn();
				}
			}
			t = getModule().getAnchor().getTile();
			topReference = (t.getRow() - topIndex) + 0;
			bottomReference = (t.getRow() - bottomIndex) - 0;
			leftReference = (t.getColumn() - leftIndex) + 0;
			rightReference = (t.getColumn() - rightIndex) + 0;
		}

		// perform the move
		currentPlacements.remove(this.tempAnchorSite);
		currentPlacements.put(tempAnchorSite, this);
		
		// update to the new absolute bounding box for the hard macro
		t = tempAnchorSite.getTile();
		top = t.getRow() - topReference;
		bottom = t.getRow() - bottomReference;
		left = t.getColumn() - leftReference;
		right = t.getColumn() - rightReference;
		this.tempAnchorSite = tempAnchorSite;
	}
	
	/**
	 * @param connectedPortWires the connectedPortWires to set
	 */
	public void setConnectedPortWires(ArrayList<PortWire> connectedPortWires) {
		this.connectedPortWires = connectedPortWires;
	}

	/**
	 * @return the connectedPortWires
	 */
	public ArrayList<PortWire> getConnectedPortWires() {
		return connectedPortWires;
	}

	public void addConnectedPortWire(PortWire wire){
		connectedPortWires.add(wire);
	}
	
	public void addConnectedPath(Path path){
		connectedPaths.add(path);
	}
	
	public HashSet<Path> getConnectedPaths(){
		return connectedPaths;
	}
	
	public static final int HALO = 1;
	
	/**
	 * Determines if the hard macros overlap.  Hard macros are considered 
	 * overlapping if their bounding boxes overlap.
	 * @param hm Hard macro to check against.
	 * @return
	 */
	public boolean overlaps(HardMacro hm){
		if(hm.getTempAnchorSite() == null){
			return false;
		}
		if(left > hm.right+HALO){
			return false;
		} 
		else if(right < hm.left+HALO){
			return false;
		}
		else if(bottom < hm.top-HALO){
			return false;
		}
		else if(top > hm.bottom+HALO){
			return false;
		}		
		return true;
	}

	@Override
	public int compareTo(Object other){
		return ((HardMacro)other).getTileSize() - getTileSize();
	}
	
	public String toString(){
		return getName();
	}

	/**
	 * @return the tileSize
	 */
	public int getTileSize() {
		return tileSize;
	}

	/**
	 * @param tileSize the tileSize to set
	 */
	public void setTileSize(int tileSize) {
		this.tileSize = tileSize;
	}
}

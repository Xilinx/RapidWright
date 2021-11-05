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
import com.xilinx.rapidwright.design.RelocatableTileRectangle;
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
	private RelocatableTileRectangle tempAnchorBoundingBox;

	
	private int tileSize = 0;

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
		for(SiteInst i : getSiteInsts()){
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
	
	public void lockPlacement(Site anchorLocation) {
	    validSiteSet = new HashSet<>();
        validSiteSet.add(anchorLocation);
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

		// perform the move
		currentPlacements.remove(this.tempAnchorSite);
		currentPlacements.put(tempAnchorSite, this);

		this.tempAnchorSite = tempAnchorSite;
		this.tempAnchorBoundingBox = getModule().getBoundingBox().getCorresponding(tempAnchorSite.getTile(), getModule().getAnchor().getTile());
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

		return tempAnchorBoundingBox.overlaps(hm.tempAnchorBoundingBox);
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

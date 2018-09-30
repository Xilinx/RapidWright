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

import java.util.HashMap;

import com.xilinx.rapidwright.device.Site;

/**
 * Represents a move within the {@link BlockPlacer}.
 * @author clavin
 *
 */
public class Move {

	Site site0;
	
	Site site1;
	
	private HardMacro block0;
	
	private HardMacro block1;

	
	public Move(){
		
	}
	
	public Move(Site site0, Site site1, HardMacro block0, HardMacro block1) {
		this.site0 = site0;
		this.site1 = site1;
		this.setBlock0(block0);
		this.setBlock1(block1);
	}
	public void setMove(Site site0, Site site1, HardMacro block0, HardMacro block1) {
		this.site0 = site0;
		this.site1 = site1;
		this.setBlock0(block0);
		this.setBlock1(block1);
	}
	
	public void undoMove(HashMap<Site, HardMacro> currentPlacements){
		if(getBlock0() != null) getBlock0().setTempAnchorSite(site0, currentPlacements);
		if(getBlock1() != null) getBlock1().setTempAnchorSite(site1, currentPlacements);
	}

	/* (non-Javadoc)
	 * @see java.lang.Object#toString()
	 */
	@Override
	public String toString() {
		return "Move " + (getBlock0() == null ? "null" : getBlock0().getName()) + " -> " + site1.getTile().getName() + "("+site1.getName()+"), " + (getBlock1() == null ? "null" : getBlock1().getName()) + " -> " + site0.getTile().getName() + "("+site0.getName()+")";
	}

	/**
	 * @return the block0
	 */
	public HardMacro getBlock0() {
		return block0;
	}

	/**
	 * @param block0 the block0 to set
	 */
	public void setBlock0(HardMacro block0) {
		this.block0 = block0;
	}

	/**
	 * @return the block1
	 */
	public HardMacro getBlock1() {
		return block1;
	}

	/**
	 * @param block1 the block1 to set
	 */
	public void setBlock1(HardMacro block1) {
		this.block1 = block1;
	}
	
	
}

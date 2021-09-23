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

import com.xilinx.rapidwright.design.AbstractModuleInst;

/**
 * Represents a move within the {@link BlockPlacer}.
 * @author clavin
 *
 */
public class Move<ModuleInstT extends AbstractModuleInst<?,?>, PlacementT> {

	private final AbstractBlockPlacer<ModuleInstT, PlacementT> placer;
	PlacementT site0;

	PlacementT site1;
	PlacementT site1Previous;
	
	private ModuleInstT block0;
	
	private ModuleInstT block1;
	private int deltaCost;


	public Move(AbstractBlockPlacer<ModuleInstT, PlacementT> placer){

		this.placer = placer;
	}
	
	public Move(PlacementT site0, PlacementT site1, ModuleInstT block0, ModuleInstT block1, AbstractBlockPlacer<ModuleInstT, PlacementT> placer) {
		this.placer = placer;
		this.site0 = site0;
		this.site1 = site1;
		this.setBlock0(block0);
		this.setBlock1(block1);
	}
	public void setMove(PlacementT site0, PlacementT site1, ModuleInstT block0, ModuleInstT block1, PlacementT site1Previous) {
		this.site0 = site0;
		this.site1 = site1;
		this.site1Previous = site1Previous;
		this.setBlock0(block0);
		this.setBlock1(block1);
	}

	public void setMove(PlacementT site0, PlacementT site1, ModuleInstT block0, ModuleInstT block1) {
		setMove(site0, site1, block0, block1, site1);
	}
	
	public void undoMove(){
		if(getBlock0() != null) placer.setTempAnchorSite(getBlock0(), site0);
		if(getBlock1() != null) placer.setTempAnchorSite(getBlock1(), site1Previous != null ? site1Previous : site1);
	}

	/* (non-Javadoc)
	 * @see java.lang.Object#toString()
	 */
	@Override
	public String toString() {
		return "Move " + (getBlock0() == null ? "null" : getBlock0().getName()) + " -> " + site1 + ", " + (getBlock1() == null ? "null" : getBlock1().getName()) + " -> " + site0;
	}

	/**
	 * @return the block0
	 */
	public ModuleInstT getBlock0() {
		return block0;
	}

	/**
	 * @param block0 the block0 to set
	 */
	public void setBlock0(ModuleInstT block0) {
		this.block0 = block0;
	}

	/**
	 * @return the block1
	 */
	public ModuleInstT getBlock1() {
		return block1;
	}

	/**
	 * @param block1 the block1 to set
	 */
	public void setBlock1(ModuleInstT block1) {
		this.block1 = block1;
	}


	public void setDeltaCost(int deltaCost) {
		this.deltaCost = deltaCost;
	}

	public int getDeltaCost() {
		return deltaCost;
	}
}

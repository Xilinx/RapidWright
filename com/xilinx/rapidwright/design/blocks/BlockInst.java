/*
 * 
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
package com.xilinx.rapidwright.design.blocks;

import com.xilinx.rapidwright.device.Site;

/**
 * A pre-implemented block creation instance.  Part of the 
 * constructs needed to parse an implementation guide file.
 * 
 * Created on: Apr 25, 2017
 */
public class BlockInst {
	/** A reference to the block prototype for this block instance */
	private BlockGuide parent;
	/** Name of the block instance */
	private String name;
	/** Index of the implementation (pblock) within the block parent */
	private Integer impl = null;
	/** The anchor site placement for this instance */
	private Site placement = null;
	/**
	 * @return the parent
	 */
	public BlockGuide getParent() {
		return parent;
	}
	/**
	 * @param parent the parent to set
	 */
	public void setParent(BlockGuide parent) {
		this.parent = parent;
	}
	/**
	 * @return the name
	 */
	public String getName() {
		return name;
	}
	/**
	 * @param name the name to set
	 */
	public void setName(String name) {
		this.name = name;
	}
	/**
	 * @return the impl
	 */
	public Integer getImplIndex() {
		return impl;
	}
	/**
	 * @param impl the impl to set
	 */
	public void setImpl(Integer impl) {
		this.impl = impl;
	}
	/**
	 * @return the placement
	 */
	public Site getPlacement() {
		return placement;
	}
	/**
	 * @param placement the placement to set
	 */
	public void setPlacement(Site placement) {
		this.placement = placement;
	}
	
	
}

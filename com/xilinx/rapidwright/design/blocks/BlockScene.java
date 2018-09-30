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

import java.util.ArrayList;

import com.xilinx.rapidwright.gui.TileScene;

/**
 * WIP. Represents a to be created pre-implemented module for 
 * use in a GUI context.
 * 
 * Created on: Apr 26, 2017
 */
public class BlockScene extends TileScene {
	
	private ImplGuide implGuide;
	
	private ArrayList<GUIPBlock> guiPBlocks;
	
	public BlockScene(){
		super();
		guiPBlocks = new ArrayList<>();
	}

	/**
	 * @return the implGuide
	 */
	public ImplGuide getImplGuide() {
		return implGuide;
	}

	/**
	 * @param implGuide the implGuide to set
	 */
	public void setImplGuide(ImplGuide implGuide) {
		this.implGuide = implGuide;
		
		// Update list of PBlocks
		guiPBlocks.clear();
		for(BlockGuide b : implGuide.getBlocks()){
			for(PBlock pb : b.getImplementations()){
				GUIPBlock guiPb = new GUIPBlock(pb, this);
				guiPBlocks.add(guiPb);
				addItem(guiPb);
				guiPb.show();
			}
		}
		
	}
	
}

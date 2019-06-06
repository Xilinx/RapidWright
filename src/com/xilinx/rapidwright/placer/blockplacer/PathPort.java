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

import com.xilinx.rapidwright.design.SitePinInst;
import com.xilinx.rapidwright.device.Tile;

/**
 * Represents endpoints on a {@link Path}.
 * @author clavin
 *
 */
public class PathPort {
	
	private SitePinInst sitePinInst;
	private HardMacro block;
	private int rowOffset;
	private int columnOffset;
	
	public Tile getPortTile(){
		if(block == null){
			return sitePinInst.getTile();
		}
		Tile anchor = block.getTempAnchorSite().getTile();
		return anchor.getDevice().getTile(anchor.getRow()-rowOffset, anchor.getColumn()-columnOffset);
	}
	
	public Tile getMovedTile(){
		if(block == null){
			return sitePinInst.getTile();
		}
		Tile pinTile = sitePinInst.getTile();
		Tile anchor = block.getAnchor().getTile();
		Tile newAnchorTile = block.getTempAnchorSite().getTile();
		int tileXOffset = pinTile.getTileXCoordinate() - anchor.getTileXCoordinate();
		int tileYOffset = pinTile.getTileYCoordinate() - anchor.getTileYCoordinate();
		int newTileX = newAnchorTile.getTileXCoordinate() + tileXOffset;
		int newTileY = newAnchorTile.getTileYCoordinate() + tileYOffset;
		String oldName = pinTile.getName();
		String newName = oldName.substring(0, oldName.lastIndexOf('X')+1) + newTileX + "Y" + newTileY;
		Tile correspondingTile = block.getDesign().getDevice().getTile(newName); 
		return correspondingTile;
		//return anchor.getDevice().getTile(anchor.getRow()-rowOffset, anchor.getColumn()-columnOffset);
	}
	
	/**
	 * @return the pin
	 */
	public SitePinInst getSitePinInst() {
		return sitePinInst;
	}

	/**
	 * @param sitePinInst the pin to set
	 */
	public void setSitePinInst(SitePinInst sitePinInst) {
		this.sitePinInst = sitePinInst;
	}

	/**
	 * @return the block
	 */
	public HardMacro getBlock() {
		return block;
	}

	/**
	 * @param block the block to set
	 */
	public void setBlock(HardMacro block) {
		this.block = block;
	}
	
	/**
	 * @return the rowOffset
	 */
	public int getRowOffset() {
		return rowOffset;
	}

	/**
	 * @param rowOffset the rowOffset to set
	 */
	public void setRowOffset(int rowOffset) {
		this.rowOffset = rowOffset;
	}

	/**
	 * @return the columnOffset
	 */
	public int getColumnOffset() {
		return columnOffset;
	}

	/**
	 * @param columnOffset the columnOffset to set
	 */
	public void setColumnOffset(int columnOffset) {
		this.columnOffset = columnOffset;
	}

	/* (non-Javadoc)
	 * @see java.lang.Object#hashCode()
	 */
	@Override
	public int hashCode() {
		final int prime = 31;
		int result = 1;
		result = prime * result + ((sitePinInst == null) ? 0 : sitePinInst.hashCode());
		return result;
	}

	/* (non-Javadoc)
	 * @see java.lang.Object#equals(java.lang.Object)
	 */
	@Override
	public boolean equals(Object obj) {
		if (this == obj)
			return true;
		if (obj == null)
			return false;
		if (getClass() != obj.getClass())
			return false;
		PathPort other = (PathPort) obj;
		if (sitePinInst == null) {
			if (other.sitePinInst != null)
				return false;
		} else if (!sitePinInst.equals(other.sitePinInst))
			return false;
		return true;
	}
	
	
}

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
package com.xilinx.rapidwright.design.blocks;

import java.util.HashSet;
import java.util.Set;

import com.xilinx.rapidwright.design.PBlockCorner;
import com.xilinx.rapidwright.device.ClockRegion;
import com.xilinx.rapidwright.device.Device;
import com.xilinx.rapidwright.device.Site;
import com.xilinx.rapidwright.device.Tile;

/**
 * Represents a range of a particular type for a pblock
 * Created on: Sep 16, 2016
 */
public class PBlockRange {

	private PBlockCorner lowerLeft;

	private PBlockCorner upperRight;
	
	
	public PBlockRange(Device dev, String range){
		int colonIndex = range.indexOf(':');
		if(colonIndex < 0) throw new RuntimeException("ERROR: Invalid pblock string '" + range + "'");
		String lowerLeftName = range.substring(0, colonIndex);
		String upperRightName = range.substring(colonIndex+1);
		if(lowerLeftName.startsWith(PBlockCorner.CLOCK_REGION) && upperRightName.startsWith(PBlockCorner.CLOCK_REGION)){
			ClockRegion lowerLeftCR = dev.getClockRegion(lowerLeftName);
			ClockRegion upperRightCR = dev.getClockRegion(upperRightName);
			if(lowerLeftCR == null || upperRightCR == null){
				throw new RuntimeException("ERROR: Invalid pblock range: " + range);
			}
			setLowerLeft(lowerLeftCR);
			setUpperRight(upperRightCR);
		}else {
			Site ll = dev.getSite(lowerLeftName);
			Site ur = dev.getSite(upperRightName);
			if(ll == null || ur == null){
				throw new RuntimeException("ERROR: Invalid pblock range: " + range);
			}
			setLowerLeft(ll);
			setUpperRight(ur);			
		}
	}
	
	public PBlockRange(Site lowerLeft, Site upperRight){
		setLowerLeft(lowerLeft);
		setUpperRight(upperRight);
	}
	
	/**
	 * @return the lowerLeft
	 */
	public Site getLowerLeftSite() {
		return (Site) lowerLeft;
	}

	/**
	 * @param lowerLeft the lowerLeft to set
	 */
	public void setLowerLeft(PBlockCorner lowerLeft) {
		this.lowerLeft = lowerLeft;
	}
	
	/**
	 * @return the upperRight
	 */
	public Site getUpperRightSite() {
		return (Site) upperRight;
	}

	/**
	 * @param upperRight the upperRight to set
	 */
	public void setUpperRight(PBlockCorner upperRight) {
		this.upperRight = upperRight;
	}
	
	public String toString(){
		return lowerLeft.getName() + ":" + upperRight.getName();
	}
	
	/**
	 * Moves the pblock by the specified offset in units of the pblock range type.
	 * For example, if the pblock is SLICE_X0Y0:SLICE_X2Y2 with an offset of (0,2), 
	 * the result would be SLICE_X0Y2:SLICE_X2Y4.  However, if the pblock range
	 * is using clock regions, the offset unit would be two clock regions away.
	 * @param xOffset Offset of the pblock range type in the X (column) direction
	 * @param yOffset Offset of the pblock range type in the Y (row) direction
	 * @return True if the move was successful, false otherwise;
	 */
	public boolean move(int xOffset, int yOffset){
		Device d = getDevice();
		String newSiteName = replaceXY(lowerLeft.getName(),lowerLeft.getInstanceX()+xOffset, lowerLeft.getInstanceY()+yOffset);
		PBlockCorner newLowerLeft = isClockRegionRange() ? d.getClockRegion(newSiteName) : d.getSite(newSiteName);
		if(newLowerLeft == null) return false;
		newSiteName = replaceXY(upperRight.getName(),upperRight.getInstanceX()+xOffset, upperRight.getInstanceY()+yOffset);
		PBlockCorner newUpperRight = isClockRegionRange() ? d.getClockRegion(newSiteName) : d.getSite(newSiteName);
		if(newUpperRight == null) return false;
		setLowerLeft(newLowerLeft);
		setUpperRight(newUpperRight);
		return true;
		
	}
	
	public static String replaceXY(String name, int x, int y){
		return name.substring(0, name.lastIndexOf('X')+1) + x + "Y" + y;
	}
	
	public Tile getTopLeftTile(){
		int col = -1;
		int row = -1;
		if(isClockRegionRange()){
			col = ((ClockRegion)lowerLeft).getUpperLeft().getColumn();
			row = ((ClockRegion)upperRight).getUpperLeft().getRow();
		}else{
			col = getLowerLeftSite().getTile().getColumn();
			row = getUpperRightSite().getTile().getRow();			
		}
			
		return getDevice().getTile(row, col);
	}
	
	public Tile getBottomRightTile(){
		int col = -1;
		int row = -1;
		if(isClockRegionRange()){
			col = ((ClockRegion)upperRight).getLowerRight().getColumn();
			row = ((ClockRegion)lowerLeft).getLowerRight().getRow();
		} else {
			row = getLowerLeftSite().getTile().getRow();
			col = getUpperRightSite().getTile().getColumn();
		}
			
		return getDevice().getTile(row, col);		
	}
	
	public Tile getBottomLeftTile(){
		int col = -1;
		int row = -1;
		if(isClockRegionRange()){
			col = ((ClockRegion)lowerLeft).getUpperLeft().getColumn();
			row = ((ClockRegion)lowerLeft).getLowerRight().getRow();
		} else {
			col = getLowerLeftSite().getTile().getColumn();
			row = getLowerLeftSite().getTile().getRow();
		}
		return getDevice().getTile(row, col);
	}
	
	public Tile getTopRightTile(){
		int col = -1;
		int row = -1;
		if(isClockRegionRange()){
			col = ((ClockRegion)upperRight).getLowerRight().getColumn();
			row = ((ClockRegion)upperRight).getUpperLeft().getRow();
		} else {
			row = getUpperRightSite().getTile().getRow();
			col = getUpperRightSite().getTile().getColumn();
		}	
		return getDevice().getTile(row, col);		
	}
	
	public Device getDevice(){
		return lowerLeft.getDevice();
	}
	
	/**
	 * Iterates over rectangular region imposed by pblock and returns the set of all
	 * tiles inclusive of those sites.
	 * @return A set of all tiles inclusive of the pblock range.
	 */
	public Set<Tile> getAllTiles(){
		Set<Tile> tiles = new HashSet<>();
		
		int colMin = getBottomLeftTile().getColumn();
		int rowMin = getTopRightTile().getRow();
		int colMax = getTopRightTile().getColumn();
		int rowMax = getBottomLeftTile().getRow();
		
		// We may need to expand column to include outward facing CLB/DSP/BRAM to INT tiles
		if(isSiteRange()){
			Tile t = getLowerLeftSite().getIntTile();
			if(t.getColumn() < colMin) colMin = t.getColumn();
			t = getUpperRightSite().getIntTile();
			if(t.getColumn() > colMax) colMax = t.getColumn();			
		}
					
		for(int col=colMin; col <= colMax; col++){
			for(int row=rowMin; row <= rowMax; row++){
				tiles.add(getDevice().getTile(row, col));
			}
		}
		
		return tiles;
	}
	
	public boolean isClockRegionRange(){
		return lowerLeft instanceof ClockRegion;
	}
	
	public boolean isSiteRange(){
		return lowerLeft instanceof Site;
	}
}

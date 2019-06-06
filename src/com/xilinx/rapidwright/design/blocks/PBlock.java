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

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;

import com.xilinx.rapidwright.device.Device;
import com.xilinx.rapidwright.device.Site;
import com.xilinx.rapidwright.device.SiteTypeEnum;
import com.xilinx.rapidwright.device.Tile;

/**
 * Represents a collection of one or more pblock ranges that describe a complete pblock
 * Created on: Sep 16, 2016
 */
public class PBlock extends ArrayList<PBlockRange> {

	private static final long serialVersionUID = -8009759451075978785L;

	private ArrayList<SubPBlock> subPBlocks;
	
	private Set<Tile> tileSet;
	
	private String name;
	
	private PBlock parent;
	
	private boolean containRouting;
	/** Set of all basic sites that can be referenced in a PBlock */
	private static HashSet<SiteTypeEnum> pblockTypes;
	
	static{
		pblockTypes = new HashSet<>();
		pblockTypes.add(SiteTypeEnum.SLICEL);
		pblockTypes.add(SiteTypeEnum.SLICEM);
		pblockTypes.add(SiteTypeEnum.DSP48E1);
		pblockTypes.add(SiteTypeEnum.DSP48E2);
		pblockTypes.add(SiteTypeEnum.RAMB180);
		pblockTypes.add(SiteTypeEnum.RAMB181);
		pblockTypes.add(SiteTypeEnum.RAMB18E1);
		pblockTypes.add(SiteTypeEnum.RAMBFIFO18);
		pblockTypes.add(SiteTypeEnum.RAMBFIFO36);
		pblockTypes.add(SiteTypeEnum.RAMBFIFO36E1);
		pblockTypes.add(SiteTypeEnum.URAM288);
		pblockTypes.add(SiteTypeEnum.LAGUNA);
	}
	
	public PBlock(){
		
	}
	
	/**
	 * Creates a new pblock object from a Vivado-style PBlock string. 
	 * @param dev The device for which to create the pblock.
	 * @param pblock The pblock string (SLICE_X0Y0:SLICE_X1Y1 ...)
	 */
	public PBlock(Device dev, String pblock){
		super();
		String[] pblockRanges = pblock.trim().split("\\s+");
		for(String pblockRange : pblockRanges){
			add(new PBlockRange(dev, pblockRange));
		}
	}
	
	/**
	 * Creates a new pblock object from a set of sites.  Only
	 * supports used sites of type SLICE
	 * @param dev The device for which to create the pblock
	 * @param sites Set of SLICE sites to be included in the pblock and
	 * create a minimum rectangle size from those provided.
	 */
	public PBlock(Device dev, Set<Site> sites){
		super();
		
		Map<SiteTypeEnum,ArrayList<Site>> typeSets = new HashMap<>();
		for(Site s : sites){
			ArrayList<Site> sameTypes = typeSets.get(s.getSiteTypeEnum());
			if(sameTypes == null){
				sameTypes = new ArrayList<>();
				typeSets.put(s.getSiteTypeEnum(), sameTypes);
			}
			sameTypes.add(s);
		}

		// SLICEs are a special case
		List<Site> slices = typeSets.remove(SiteTypeEnum.SLICEL);
		List<Site> slicems = typeSets.remove(SiteTypeEnum.SLICEM);
		if(slices == null) slices = slicems == null ? Collections.emptyList() : slicems;
		else slices.addAll(slicems == null ? Collections.emptyList() : slicems);
		PBlockRange sliceRange = createPBlockRange(dev, slices);
		if(sliceRange != null) add(sliceRange);
		// Rest of site types
		for(Entry<SiteTypeEnum,ArrayList<Site>> e : typeSets.entrySet()){
			add(createPBlockRange(dev, e.getValue()));
		}
	}
	
	/**
	 * Creates a pblock range for a specific site type namespace 
	 * (all must be SLICE or DSP, no mixing).
	 * @param dev The device onto which the pblock range should be created
	 * @param sites The list of homogeneous sites (all must be same type namespace).
	 * @return The minimum rectangle pblock range for the provided sites
	 * or null if unable to generate a correct range.
	 */
	public static PBlockRange createPBlockRange(Device dev, List<Site> sites){
		if(sites == null || sites.isEmpty()) return null;
		
		int xMin = Integer.MAX_VALUE;
		int xMax = 0;
		int yMin = Integer.MAX_VALUE;
		int yMax = 0;
		String namespace = null;
		for(Site s : sites){
			if(namespace == null){
				namespace = s.getNameSpacePrefix();
			}else if(!namespace.equals(s.getNameSpacePrefix())){
				throw new RuntimeException("ERROR: Found multiple types for "
						+ "PBlockRange creation request: " + namespace  + " " 
						+ s.getNameSpacePrefix());
			}
			int x = s.getInstanceX();
			int y = s.getInstanceY();
			if(x > xMax) xMax = x;
			if(x < xMin) xMin = x;			
			if(y > yMax) yMax = y;
			if(y < yMin) yMin = y;
		}
		
		Site lowerLeft = dev.getSite(namespace + "X" + xMin +"Y" + yMin);
		Site upperRight = dev.getSite(namespace + "X" + xMax +"Y" + yMax);
		PBlockRange range = new PBlockRange(lowerLeft,upperRight);
		return range;
	}
	
	
	public ArrayList<String> getTclConstraints(){
		if(name == null) 
			throw new RuntimeException("ERROR: Must give pblock a name!");
		if(parent != null && parent.getName() == null) 
			throw new RuntimeException("ERROR: Parent of pblock " + name + " does not have a name");
		ArrayList<String> tcl = new ArrayList<>();
		tcl.add("create_pblock " + name + (parent != null ? " -parent " + parent.getName() : ""));
		for(PBlockRange p : this){
			tcl.add("resize_pblock "+ name +" -add " + p.toString());
		}
		if(containRouting()){
			tcl.add("set_property CONTAIN_ROUTING 1 [get_pblocks "+name+"]");
		}
		
		return tcl;
	}
	
	public String toString(){
		StringBuilder sb = new StringBuilder();
		for(int i=0; i < size(); i++){
			sb.append(get(i).toString());
			if(i != size() -1) sb.append(" ");
		}
		return sb.toString();
	}

	public Tile getTopLeftTile(){
		int leftMostColumn = Integer.MAX_VALUE;
		int topMostRow = Integer.MAX_VALUE;
		for(PBlockRange range : this){
			Tile tl = range.getTopLeftTile();
			if(leftMostColumn > tl.getColumn()) leftMostColumn = tl.getColumn();
			if(topMostRow > tl.getRow()) topMostRow = tl.getRow();
		}
		return getDevice().getTile(topMostRow,leftMostColumn);
	}
	
	public Tile getBottomLeftTile(){
		int leftMostColumn = Integer.MAX_VALUE;
		int bottomMostRow = 0;
		for(PBlockRange range : this){
			Tile tl = range.getBottomLeftTile();
			if(leftMostColumn > tl.getColumn()) leftMostColumn = tl.getColumn();
			if(bottomMostRow < tl.getRow()) bottomMostRow = tl.getRow();
		}
		return getDevice().getTile(bottomMostRow,leftMostColumn);
	}
	
	public Tile getBottomRightTile(){
		int rightMostColumn = 0;
		int bottomMostRow = 0;
		for(PBlockRange range : this){
			Tile br = range.getBottomRightTile();
			if(rightMostColumn < br.getColumn()) rightMostColumn = br.getColumn();
			if(bottomMostRow < br.getRow()) bottomMostRow = br.getRow();
		}
		return getDevice().getTile(bottomMostRow,rightMostColumn);
	}

	public Tile getTopRightTile(){
		int rightMostColumn = 0;
		int topMostRow = Integer.MAX_VALUE;
		for(PBlockRange range : this){
			Tile br = range.getTopRightTile();
			if(rightMostColumn < br.getColumn()) rightMostColumn = br.getColumn();
			if(topMostRow > br.getRow()) topMostRow = br.getRow();
		}
		return getDevice().getTile(topMostRow,rightMostColumn);
	}

	/**
	 * Iterates over all Pblock ranges and returns the set of all tiles
	 * covered by the pblock. The set of tiles is cached, so if
	 * underlying PBlockRange objects change since last called, 
	 * this data will be stale.  
	 * @return The set of all tiles in the pblock 
	 */
	public Set<Tile> getAllTiles(){
		if(tileSet == null){
			tileSet = new HashSet<>();
			for(PBlockRange range : this){
				tileSet.addAll(range.getAllTiles());
			}			
		}
		return tileSet;
	}
	
	/**
	 * Gets all sites inside the pblock 
	 * @param prefix Starting name of site type desired ("SLICE"). If null, returns all site types.
	 * @return All sites of a particular type
	 */
	public Set<Site> getAllSites(String prefix){
		Set<Site> sites = new HashSet<>();
		for(Tile t : getAllTiles()){
			if(t.getSites() == null) continue;
			for(Site s : t.getSites()){
				if(prefix != null){
					if(!s.getName().startsWith(prefix)) continue;
				}
				sites.add(s);
			}
		}
		return sites;
	}
	
	/**
	 * Checks if this pblock includes the tile provided within its boundaries.
	 * @param tile The tile in question.
	 * @return True if the tile falls within the boundaries of the PBlock, false otherwise.
	 */
	public boolean containsTile(Tile tile){
		return getAllTiles().contains(tile);
	}
	
	
	public Device getDevice(){
		if(size() == 0) return null;
		return get(0).getDevice();
	}
	
	public void addSubPBlock(SubPBlock subPBlock){
		if(subPBlocks == null) subPBlocks = new ArrayList<>();
		subPBlocks.add(subPBlock);
	}
	
	public List<SubPBlock> getSubPBlocks(){
		if(subPBlocks == null) return Collections.emptyList();
		return subPBlocks;
	}
	
	/**
	 * @param placement
	 * @return
	 */
	public PBlock createNewPblockAt(Site placement) {
		PBlockRange slices = null;
		PBlockRange dsps = null;
		PBlockRange brams = null;
		
		for(PBlockRange range : this){
			if(range.getLowerLeftSite().getName().startsWith("SLICE")){
				slices = range;
			}else if(range.getLowerLeftSite().getName().startsWith("DSP")){
				dsps = range;
			}else if(range.getLowerLeftSite().getName().startsWith("RAMB")){
				brams = range;
			}
		}
		
		if(placement.getName().startsWith("SLICE")){
			int xOffset = slices.getLowerLeftSite().getInstanceX() - placement.getInstanceX();
			int yOffset = slices.getLowerLeftSite().getInstanceY() - placement.getInstanceY();
			int newUpperX = slices.getUpperRightSite().getInstanceX() - xOffset;
			int newUpperY = slices.getUpperRightSite().getInstanceY() - yOffset;
			Site newUpperRight = placement.getTile().getDevice().getSite("SLICE_X" + newUpperX + "Y" + newUpperY);
			slices = new PBlockRange(placement, newUpperRight);			
		}else if(placement.getName().startsWith("DSP")){
			int xOffset = dsps.getLowerLeftSite().getInstanceX() - placement.getInstanceX();
			int yOffset = dsps.getLowerLeftSite().getInstanceY() - placement.getInstanceY();
			int newUpperX = dsps.getUpperRightSite().getInstanceX() - xOffset;
			int newUpperY = dsps.getUpperRightSite().getInstanceY() - yOffset;
			Site newUpperRight = placement.getTile().getDevice().getSite("DSP48E2_X" + newUpperX + "Y" + newUpperY);
						
			if(slices != null){
				// SLICE X OFFSET: How many slice columns between old and new DSP columns? 
				Tile startTile = dsps.getLowerLeftSite().getTile();
				Tile newDSPTile = placement.getTile();
				//int incr = startTile.getColumn() > newDSPTile.getColumn() ? /*LEFT*/ -1 : /*RIGHT*/ 1;
				int incr = startTile.getColumn() == newDSPTile.getColumn() ? 0 : (startTile.getColumn() > newDSPTile.getColumn() ? /*LEFT*/ -1 : /*RIGHT*/ 1);
				int sliceXOffset = 0;
				while(incr != 0 && startTile.getColumn() != newDSPTile.getColumn()){
					startTile = startTile.getDevice().getTile(newDSPTile.getRow(), startTile.getColumn()+incr);
					if(startTile.getSites().length > 0){
						if(startTile.getSites()[0].getName().startsWith("SLICE")){
							sliceXOffset -= incr;
						}
					}
				}
				
				// SLICE Y OFFSET: DSP Offset * 2.5
				int sliceYOffset = (int)((double)yOffset * 2.5);
				
				Site newLowerLeftSlice = startTile.getDevice().getSite("SLICE_X" + (slices.getLowerLeftSite().getInstanceX()-sliceXOffset) + "Y" + (slices.getLowerLeftSite().getInstanceY()-sliceYOffset));
				Site newUpperRightSlice = startTile.getDevice().getSite("SLICE_X" + (slices.getUpperRightSite().getInstanceX()-sliceXOffset)+ "Y" + (slices.getUpperRightSite().getInstanceY()-sliceYOffset));
				slices = new PBlockRange(newLowerLeftSlice, newUpperRightSlice);
			}			
			dsps = new PBlockRange(placement, newUpperRight);
			
		}else if(placement.getName().startsWith("RAMB")){
			int xOffset = brams.getLowerLeftSite().getInstanceX() - placement.getInstanceX();
			int yOffset = brams.getLowerLeftSite().getInstanceY() - placement.getInstanceY();
			int newUpperX = brams.getUpperRightSite().getInstanceX() - xOffset;
			int newUpperY = brams.getUpperRightSite().getInstanceY() - yOffset;
			Site newUpperRight = placement.getTile().getDevice().getSite("RAMB36_X" + newUpperX + "Y" + newUpperY);
						
			if(slices != null){
				// SLICE X OFFSET: How many slice columns between old and new BRAM columns? 
				Tile startTile = brams.getLowerLeftSite().getTile();
				Tile newBRAMTile = placement.getTile();
				int incr = startTile.getColumn() == newBRAMTile.getColumn() ? 0 : (startTile.getColumn() > newBRAMTile.getColumn() ? /*LEFT*/ -1 : /*RIGHT*/ 1);
				int sliceXOffset = 0;
				while(incr != 0 && startTile != newBRAMTile){
					startTile = startTile.getDevice().getTile(newBRAMTile.getRow(), startTile.getColumn()+incr);
					if(startTile == null){
						throw new RuntimeException("ERROR: Couldn't create new pblock at placement " + 
								placement.getName() + " for pblock " + this.toString());
					}
					if(startTile.getSites() != null && startTile.getSites().length > 0){
						if(startTile.getSites()[0].getName().startsWith("SLICE")){
							sliceXOffset -= incr;
						}
					}
				}
				
				// SLICE Y OFFSET: DSP Offset * 5
				int sliceYOffset = yOffset * 5;
				
				Site newLowerLeftSlice = startTile.getDevice().getSite("SLICE_X" + (slices.getLowerLeftSite().getInstanceX()-sliceXOffset) + "Y" + (slices.getLowerLeftSite().getInstanceY()-sliceYOffset));
				Site newUpperRightSlice = startTile.getDevice().getSite("SLICE_X" + (slices.getUpperRightSite().getInstanceX()-sliceXOffset)+ "Y" + (slices.getUpperRightSite().getInstanceY()-sliceYOffset));
				slices = new PBlockRange(newLowerLeftSlice, newUpperRightSlice);
			}			
			brams = new PBlockRange(placement, newUpperRight);			
		}
		
		PBlock newPBlock = new PBlock();
		if(slices != null) newPBlock.add(slices);
		if(dsps != null) newPBlock.add(dsps);
		if(brams != null) newPBlock.add(brams);
		
		return newPBlock;
	}
	
	/**
	 * Attempts to move the pblock by an offset of tiles in the x and y directions.
	 * @param dx The number of tiles to move the pblock in the x direction.
	 * @param dy The number of tiles to mvoe the pblock in the y direction.
	 * @return True if the pblock ranges changed, false if no move was made.
	 */
	public boolean movePBlock(int dx, int dy){
		if(dx == 0 && dy == 0) return false;
		Tile bl = getBottomLeftTile();
		Tile tr = getTopRightTile();
		Device d = tr.getDevice();
		boolean hasMoved = false;
		if(dx != 0){
			if(dx > 0){
				// moving to the right, check the columns to the right most tile
				for(PBlockRange pbr : this){
					int x = 0;
					Site right = pbr.getUpperRightSite().getNeighborSite(x, 0);
					int target = right.getTile().getColumn() + dx;
					while(right.getTile().getColumn() < target){
						x++;
						right = pbr.getUpperRightSite().getNeighborSite(x, 0);
						if(right.getTile().getColumn() <= target){
							hasMoved = true;
						}
					}
					if(hasMoved){
						pbr.setUpperRight(right);
						Site otherCorner = pbr.getLowerLeftSite().getNeighborSite(x, 0);
						pbr.setLowerLeft(otherCorner);						
					}
				}
			}else{
				// moving to the left, check the columns to the left most tile
				for(PBlockRange pbr : this){
					int x = 0;
					Site left = pbr.getLowerLeftSite().getNeighborSite(x, 0);
					int target = left.getTile().getColumn() + dx;
					while(left.getTile().getColumn() > target){
						x--;
						left = pbr.getLowerLeftSite().getNeighborSite(x, 0);
						if(left.getTile().getColumn() >= target){
							hasMoved = true;
						}
					}
					if(hasMoved){
						pbr.setLowerLeft(left);
						Site otherCorner = pbr.getUpperRightSite().getNeighborSite(x, 0);
						pbr.setUpperRight(otherCorner);						
					}
				}

			}			
		}
		if(dy != 0){
			if(dy > 0){
				// moving down, check tiles below
				for(PBlockRange pbr : this){
					int y = 0;
					Site left = pbr.getLowerLeftSite().getNeighborSite(0, y);
					int target = left.getTile().getRow() + dy;
					while(left.getTile().getRow() < target){
						y--;
						left = pbr.getLowerLeftSite().getNeighborSite(0, y);
						if(left.getTile().getRow() <= target){
							hasMoved = true;
						}
					}
					if(hasMoved){
						pbr.setLowerLeft(left);
						Site otherCorner = pbr.getUpperRightSite().getNeighborSite(0, y);
						pbr.setUpperRight(otherCorner);											
					}
				}
			}else{
				// moving up, check the rows above
				for(PBlockRange pbr : this){
					int y = 0;
					Site right = pbr.getUpperRightSite().getNeighborSite(0, y);
					int target = right.getTile().getRow() + dy;
					while(right.getTile().getRow() > target){
						y++;
						right = pbr.getUpperRightSite().getNeighborSite(0, y);
						if(right.getTile().getRow() >= target){
							hasMoved = true;
						}
					}
					if(hasMoved){
						pbr.setUpperRight(right);
						Site otherCorner = pbr.getLowerLeftSite().getNeighborSite(0, y);
						pbr.setLowerLeft(otherCorner);						
					}
				}

			}			
		}
		return hasMoved;
	}
	
	public static void main(String[] args) {
		Device d = Device.getDevice("xcku035-fbva900-2-e");
		String initRange = "SLICE_X5Y5:SLICE_X7Y7";
		PBlock p = new PBlock(d, initRange);
		System.out.println(p);
		for(int i : new int[]{-2,-1,0,1,2}){
			for(int j : new int[]{-2,-1,0,1,2}){
				p.movePBlock(i, j);
				System.out.println("(" + i +", " + j + ") " + p);
				p.set(0, new PBlockRange(d,initRange));
			}
		}
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
	 * @return the parent
	 */
	public PBlock getParent() {
		return parent;
	}

	/**
	 * @param parent the parent to set
	 */
	public void setParent(PBlock parent) {
		this.parent = parent;
	}
	
	public boolean containRouting() {
		return containRouting;
	}

	public void setContainRouting(boolean containRouting) {
		this.containRouting = containRouting;
	}
	
	/**
	 * Returns true if the provided site type is referenced in a pblock corner.
	 * @param type The site type in question.
	 * @return True if it can be used as a pblock reference point, false otherwise.
	 */
	public static boolean isPBlockCornerSiteType(SiteTypeEnum type){
		return pblockTypes.contains(type);
	}
}

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

import com.xilinx.rapidwright.design.ModuleInst;
import com.xilinx.rapidwright.design.SitePinInst;
import com.xilinx.rapidwright.design.SiteInst;
import com.xilinx.rapidwright.device.Tile;

/**
 * Represents a delay path between pre-implemented modules.
 * @author clavin
 *
 */
public class Path extends ArrayList<PathPort>{
	private int length;
	// Half Perimeter Wire Length
	private int hpwl;
	// TODO - we should fix this to cover all the boards, UltraScla HRIO column is 175
	private int crossingColumn = 175;
	private ArrayList<Integer> delay;
	private int maxDelay;
	
	/**
	 * 
	 */
	private static final long serialVersionUID = 4016705713685431809L;

	public int getLength(){
		return length;
	}
	
	public int getHPWL(){
		return hpwl;
	}
	
	public ArrayList<Integer> getDelay(){
		return delay;
	}
	
	public int getMaxDelay(){
		return maxDelay;
	}
	
	
	public void setDelay(ArrayList<Integer> estimatedDelay){
		delay = estimatedDelay;
	}
	
	public void setMaxDelay(int pathMaxDelay){
		maxDelay = pathMaxDelay;
	}
	
	public int getSize(){
		return size();
	}
	
	public void calculateLength(){
		length = 0;
		int tmpLen = 0;
		int size = size();
		Tile tmp = get(0).getPortTile();
		for (int i = 1; i < size; i++) {
			Tile next = get(i).getPortTile();
			if ((next.getColumn() > crossingColumn && tmp.getColumn() < crossingColumn) ||
				(next.getColumn() < crossingColumn && tmp.getColumn() > crossingColumn)){
				tmpLen = tmp.getTileManhattanDistance(next);
				length += 10 * tmpLen;
			} else{
				length += tmp.getTileManhattanDistance(next);// * (get(i).getPin().getNet().getFanOut())/2;
			}
			tmp = next;
		}
	}
	
	public void calculateHPWL(){
		hpwl = 0;
		int crossingPenalty = 1;
		int fanOutPenalty = 1;
		int size = size();
		Tile tmp = get(0).getPortTile();
		int xMin = tmp.getColumn();
		int xMax = tmp.getColumn();
		int yMin = tmp.getRow();
		int yMax = tmp.getRow();
		for (int i = 1; i < size; i++) {
			Tile next = get(i).getPortTile();
			int tmpX = next.getColumn();
			int tmpY = next.getRow();
			if ((next.getColumn() > crossingColumn && tmp.getColumn() < crossingColumn) ||
				(next.getColumn() < crossingColumn && tmp.getColumn() > crossingColumn)){
				crossingPenalty = 20;
			} 
			if(tmpX < xMin){
				xMin = tmpX;
			} else if(tmpX > xMax){
				xMax = tmpX;
			}
			if(tmpY < yMin){
				yMin = tmpY;
			} else if(tmpY > yMax){
				yMax = tmpY;
			}
		}
		if (size > 30){
			fanOutPenalty = 3;
		}
		hpwl = (Math.abs(xMin - xMax) + Math.abs(yMin - yMax)) * crossingPenalty * fanOutPenalty;
	}
	
	/**
	 * Adds a pin the to path.
	 * @param map Map of module instance to hard macros
	 */
	public void addPin(SitePinInst p, HashMap<ModuleInst, HardMacro> map){
		PathPort pp = new PathPort();
		pp.setSitePinInst(p);
		pp.setBlock(map.get(p.getSiteInst().getModuleInst()));
		if(pp.getBlock() != null){
			Tile anchorTile = pp.getBlock().getModule().getAnchor().getTile();
			SiteInst templateInst = pp.getSitePinInst().getSiteInst().getModuleTemplateInst();
			if(templateInst == null){
				System.out.println(pp.getSitePinInst().toString() + " on " + pp.getSitePinInst().getSiteInst().toString() + " has no template instance");
			}
			Tile sourceTile = templateInst.getTile();
			pp.setRowOffset(anchorTile.getRow() - sourceTile.getRow());
			pp.setColumnOffset(anchorTile.getColumn() - sourceTile.getColumn());
		}
		this.add(pp);
	}
	
	@Override
	public String toString(){
		StringBuilder sb = new StringBuilder();
		for(PathPort pp : this){
			sb.append(pp.getSitePinInst().getSitePinName() +"->");
		}
		return sb.toString();
	}

	/* (non-Javadoc)
	 * @see java.lang.Object#hashCode()
	 */
	@Override
	public int hashCode() {
		StringBuilder sb = new StringBuilder();
		for(PathPort p : this){
			sb.append(p.getSitePinInst().getSitePinName());
		}
		
		return sb.toString().hashCode();
	}

	/* (non-Javadoc)
	 * @see java.lang.Object#equals(java.lang.Object)
	 */
	@Override
	public boolean equals(Object obj) {
		if (this == obj)
			return true;
		if (!super.equals(obj))
			return false;
		if (getClass() != obj.getClass())
			return false;
		Path other = (Path) obj;
		if (size() != other.size())
			return false;
		int size = size();
		for (int i = 0; i < size; i++) {
			if(!get(i).equals(other.get(i))){
				return false;
			}
		}
		
		return true;
	}
	
}

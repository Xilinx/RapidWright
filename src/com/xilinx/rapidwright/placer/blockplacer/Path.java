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
import java.util.List;
import java.util.Map;

import com.xilinx.rapidwright.design.ModuleInst;
import com.xilinx.rapidwright.design.SimpleTileRectangle;
import com.xilinx.rapidwright.design.SitePinInst;
import com.xilinx.rapidwright.device.Tile;

/**
 * Represents a delay path between pre-implemented modules.
 * @author clavin
 *
 */
public class Path extends AbstractPath<PathPort, HardMacro>{
	private final String name;

	// Half Perimeter Wire Length
	protected int hpwl;
	protected ArrayList<Integer> delay;
	protected int maxDelay;

	public Path(String name) {
		this.name = name;
	}

	public Path() {
		this.name = null;
	}

	public int getLength(){
		return hpwl;
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

	public void calculateLength(){
		calculateHPWL();
	}

	@Override
	public String getName() {
		return name;
	}

	public void calculateHPWL(){

		SimpleTileRectangle rect = new SimpleTileRectangle();
		for (PathPort port : ports) {
			rect.extendTo(port.getPortTile());
		}

		int fanOutPenalty  = 1;
		if (getSize() > 30){
			fanOutPenalty = 3;
		}
		hpwl = rect.hpwl()*fanOutPenalty;
	}


	@Override
	public String toString(){
		StringBuilder sb = new StringBuilder();
		for(PathPort pp : ports){
			sb.append(pp.getSitePinInst().getSitePinName()).append("->");
		}
		return sb.toString();
	}

	/* (non-Javadoc)
	 * @see java.lang.Object#hashCode()
	 */
	@Override
	public int hashCode() {
		StringBuilder sb = new StringBuilder();
		for(PathPort p : ports){
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
		return ports.equals(other.ports);
	}
	/**
	 * Adds a pin the to path.
	 * @param p The pin to add
	 * @param map Map of module instance to hard macros
	 */
	public void addPin(SitePinInst p, Map<ModuleInst, HardMacro> map){
		final HardMacro block = map.get(p.getSiteInst().getModuleInst());
		Tile tile = p.getTile();
		if(block != null) {
			tile = p.getSiteInst().getModuleTemplateInst().getTile();

			moduleInsts.add(block);
		}
		ports.add(new PathPort(p, block, tile));
	}

	public PathPort get(int index) {
		return ports.get(index);
	}

	public List<PathPort> getPorts() {
		return ports;
	}
}

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

public class PortWire {
	
	private SitePinInst source;
	private SitePinInst sink;
	private HardMacro sourceBlock;
	private HardMacro sinkBlock;

	private int sourceRowOffset;
	
	private int sourceColumnOffset;
	
	private int sinkRowOffset;
	
	private int sinkColumnOffset;
	
	private int length;
	
	/**
	 * @param source
	 * @param sink
	 */
	public PortWire(SitePinInst source, SitePinInst sink) {
		setSource(source);
		setSink(sink);
		this.sourceBlock = null;
		this.sinkBlock = null;
		length = -1;
	}

	/**
	 * @return the sourceBlock
	 */
	public HardMacro getSourceBlock() {
		return sourceBlock;
	}


	/**
	 * @param sourceBlock the sourceBlock to set
	 */
	public void setSourceBlock(HardMacro sourceBlock) {
		this.sourceBlock = sourceBlock;
		Tile anchorTile = sourceBlock.getModule().getAnchor().getTile();
		Tile sourceTile = source.getSiteInst().getModuleTemplateInst().getTile();
		this.sourceRowOffset = anchorTile.getRow() - sourceTile.getRow();
		this.sourceColumnOffset = anchorTile.getColumn() - sourceTile.getColumn();
	}

	/**
	 * @return the sinkBlock
	 */
	public HardMacro getSinkBlock() {
		return sinkBlock;
	}

	/**
	 * @param sinkBlock the sinkBlock to set
	 */
	public void setSinkBlock(HardMacro sinkBlock) {
		this.sinkBlock = sinkBlock;
		Tile anchorTile = sinkBlock.getModule().getAnchor().getTile();
		Tile sinkTile = sink.getSiteInst().getModuleTemplateInst().getTile();
		this.sinkRowOffset = anchorTile.getRow() - sinkTile.getRow();
		this.sinkColumnOffset = anchorTile.getColumn() - sinkTile.getColumn();
	}

	/**
	 * @return the source
	 */
	public SitePinInst getSource() {
		return source;
	}

	/**
	 * @param source the source to set
	 */
	public void setSource(SitePinInst source) {
		this.source = source;
	}

	/**
	 * @return the sink
	 */
	public SitePinInst getSink() {
		return sink;
	}

	/**
	 * @param sink the sink to set
	 */
	public void setSink(SitePinInst sink) {
		this.sink = sink;
	}
	
	private Tile getSourceBlockTile(){
		Tile anchor = sourceBlock.getTempAnchorSite().getTile();
		return sourceBlock.getDesign().getDevice().getTile(anchor.getRow()-sourceRowOffset, anchor.getColumn()-sourceColumnOffset);
	}
	
	private Tile getSinkBlockTile(){
		Tile anchor = sinkBlock.getTempAnchorSite().getTile();
		return sinkBlock.getDesign().getDevice().getTile(anchor.getRow()-sinkRowOffset, anchor.getColumn()-sinkColumnOffset);
		
	}
	
	public void calculateLength(){
		Tile src = sourceBlock == null ? source.getTile() : getSourceBlockTile();
		Tile snk = sinkBlock == null ? sink.getTile() : getSinkBlockTile();		
		length = src.getManhattanDistance(snk) + 4*(source.getNet().getFanOut());			
	}
	
	public int getLength(){
		return length;
	}

	/* (non-Javadoc)
	 * @see java.lang.Object#toString()
	 */
	@Override
	public String toString() {
		return "PortWire [source=" + (source==null? "null" : source.getName()) + ", sink=" + (sink==null? "null" : sink.getName() + " " + sink.getSiteInstName())
				+ " sourceBlock=" + (sourceBlock==null? "null" : sourceBlock.getName()) + ", sinkBlock=" + (sinkBlock==null? "null" : sinkBlock.getName())
				//+ " sourceRowOffset=" + sourceRowOffset
				//+ " sourceColumnOffset=" + sourceColumnOffset
				//+ " sinkRowOffset=" + sinkRowOffset 
				//+ " sinkColumnOffset=" + sinkColumnOffset 
				+ " length=" + length +"]";
	}
	
	
}

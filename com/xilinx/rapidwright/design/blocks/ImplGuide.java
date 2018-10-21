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

import java.io.BufferedWriter;
import java.io.FileWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import com.xilinx.rapidwright.device.Device;
import com.xilinx.rapidwright.device.Part;
import com.xilinx.rapidwright.device.PartNameTools;
import com.xilinx.rapidwright.device.Site;
import com.xilinx.rapidwright.util.FileTools;
import com.xilinx.rapidwright.util.MessageGenerator;

/**
 * Parser for impl guide files.
 * 
 * Created on: Apr 25, 2017
 */
public class ImplGuide {

	public static final String PART = "PART";
	public static final String BLOCK = "BLOCK";
	public static final String IMPL = "IMPL";
	public static final String SUB_IMPL = "SUB_IMPL";
	public static final String INST = "INST";
	public static final String CLOCK = "CLOCK";
	public static final String TCL = "TCL";
	public static final String END_BLOCK = "END_BLOCK";
	public static final String END_BLOCKS = "END_BLOCKS";
	
	/** The target part for this design */
	private Part part;
	/** The device corresponding to the part */
	private Device device;
	/** A map to keep track of all the blockGuides */
	private Map<String, BlockGuide> blockGuides;
	
	public ImplGuide(){
		blockGuides = new LinkedHashMap<String,BlockGuide>();
	}
	
	private static String checkPblockValid(ImplGuide ig, int lineNumber, String pblock){
		// Check for valid sites
		int colon = pblock.indexOf(':');
		Site start = ig.getDevice().getSite(pblock.substring(0, colon));
		if(start == null){
			throw new RuntimeException("ERROR: Site " + pblock.substring(0, colon) 
				+" doesn't exist in part " + ig.getPart().getName() 
				+ " found in pblock on line " + lineNumber );
		}
		Site end = ig.getDevice().getSite(pblock.substring(colon+1, pblock.length()));
		if(end == null){
			throw new RuntimeException("ERROR: Site " + pblock.substring(colon+1, pblock.length()) 
				+" doesn't exist in part " + ig.getPart().getName() 
				+ " found in pblock on line " + lineNumber );
		}
		return pblock;
	}
	
	public static ImplGuide readImplGuide(String fileName){
		ImplGuide ig = new ImplGuide();
		
		int lineNumber = 0;
		BlockGuide currBlock = null;
		PBlock currImpl = null;
		int implCount = 0;
		int instCount = 0;
		outer: for(String line : FileTools.getLinesFromTextFile(fileName)){
			lineNumber++;
			line = line.trim();
			if(line.equals("")) continue;
			if(line.startsWith("#")) continue;
			String[] tokens = line.split("\\s+");
			
			switch(tokens[0]){
				case PART:{
					ig.setPart(PartNameTools.getPart(tokens[1]));
					ig.setDevice(Device.getDevice(ig.getPart()));
					break;
				}
				case BLOCK:{
					currBlock = new BlockGuide();
					currBlock.setCacheID(tokens[1]);
					ig.addBlock(currBlock);
					implCount = Integer.parseInt(tokens[2]);
					instCount = Integer.parseInt(tokens[3]);
					break;
				}
				case IMPL:{
					int index = Integer.parseInt(tokens[1]);
					int subImplCount = 0;
					int tokenIdx = 0;
					try{
						subImplCount = Integer.parseInt(tokens[2]);
					}catch (NumberFormatException e){
						subImplCount = 0;
						tokenIdx = -1;
					}
					
					StringBuilder sb = new StringBuilder(checkPblockValid(ig, lineNumber, tokens[3+tokenIdx]));
					
					for(int i=4+tokenIdx; i < tokens.length; i++){
						sb.append(" ");
						sb.append(checkPblockValid(ig, lineNumber, tokens[i]));
					}
					currImpl = new PBlock(ig.getDevice(),sb.toString());
					currBlock.addImplementation(index, currImpl);
					break;
				}
				case SUB_IMPL:{
					int index = Integer.parseInt(tokens[1]);
					String getCellsParam = line.substring(line.indexOf('\'')+1, line.lastIndexOf('\''));
					int lastTokenWithQuote = 0;
					for(int i=0; i < tokens.length; i++){
						if(tokens[i].indexOf('\'') != -1) lastTokenWithQuote = i;
					}
					lastTokenWithQuote++;
					StringBuilder sb = new StringBuilder(checkPblockValid(ig, lineNumber, tokens[lastTokenWithQuote]));
					for(int i=lastTokenWithQuote+1; i < tokens.length; i++){
						sb.append(" ");
						sb.append(checkPblockValid(ig, lineNumber, tokens[i]));
					}
					SubPBlock subImpl = new SubPBlock(ig.getDevice(),sb.toString());
					subImpl.setGetCellsArgs(getCellsParam);
					currImpl.addSubPBlock(subImpl);
					break;
				}
				case INST:{
					BlockInst bi = new BlockInst();
					bi.setName(tokens[1]);
					currBlock.addBlockInst(bi);
					if(tokens.length > 2){
						bi.setImpl(Integer.parseInt(tokens[2]));
						if(currBlock.getImplementations().get(bi.getImplIndex()) == null){
							throw new RuntimeException("ERROR: Inconsistent implement"
								+ "ation guide for instance " + bi.getName() + ".  The"
								+ " block " + currBlock.getCacheID() + " doesn't have "
								+ "an implementation " + bi.getImplIndex() + ".");
						}
					}
					if(tokens.length > 3){
						Site s = ig.getDevice().getSite(tokens[3]);
						if(s == null){
							throw new RuntimeException("ERROR: " + tokens[3] + " could " 
								+ "not be found in the device " 
								+ ig.getDevice().getDeviceName() + ".");
						}
						bi.setPlacement(s);
					}
					break;
				}
				case CLOCK:{
					String clkPortName = tokens[1];
					Float period = Float.parseFloat(tokens[2]);
					if(period < 0) throw new RuntimeException("ERROR: Parsing clock period constraint '" 
							+ tokens[2] +"' is invalid on line " + lineNumber );
					currBlock.addClock(clkPortName,period);
					if(tokens.length == 4){
						Site clkBuffer = ig.getDevice().getSite(tokens[3]);
						if(clkBuffer == null){
							throw new RuntimeException("ERROR: Invalid clk buffer site '" 
									+ tokens[3] +"' on line " + lineNumber );
						}
						currBlock.addClockBuffer(clkPortName,clkBuffer);
					}
					break;
				}
				case TCL:{
					String tclCommand = line.substring(4);
					currBlock.addXDCCommand(tclCommand);
					break;
				}
				case END_BLOCK:{
					currBlock = null;
					break;
				}
				case END_BLOCKS:{
					break outer;
				}
				default:
					throw new RuntimeException("ERROR while parsing " + 
					fileName + " unexpected token " + tokens[0] +
					" on line " + lineNumber);
			}
			
		}
		
		return ig;
	}

	public void writeImplGuide(String fileName){
		String indent = "  ";
		String nl = "\n";
		try {
			BufferedWriter bw = new BufferedWriter(new FileWriter(fileName));
			bw.write(PART + " " + getPart().toString() + nl);
			for(BlockGuide b : getBlocks()){
				bw.write(BLOCK + " " + b.getCacheID() + " " + b.getImplementations().size() + " " + b.getInsts().size() + nl);
				int i = 0;
				for(PBlock pb : b.getImplementations()){
					bw.write(indent + IMPL + " " + i +  " "+ pb.toString() + nl);
					i++;
				} 
				for(BlockInst bi : b.getInsts()){
					bw.write(indent + INST + " " + bi.getName());
					if(bi.getImplIndex() != null){
						bw.write(" " + bi.getImplIndex()); 
						if(bi.getPlacement() != null){
							bw.write(" " + bi.getPlacement().getName());
						}
					}
					bw.write(nl);
				}
				for(String clock : b.getClocks()){
					bw.write(indent + CLOCK + " " + clock);
					float f = b.getClockPeriod(clock);
					bw.write(" " + f);
					Site clkBuffer = b.getClockBuffer(clock);
					if(clkBuffer != null){
						bw.write(" " + clkBuffer.getName());
					}
					bw.write(nl);
				}
				bw.write(END_BLOCK + nl);
			}
			bw.write(END_BLOCKS + nl);
			bw.close();
		} catch (IOException e) {
			e.printStackTrace();
		}
	}

	/**
	 * @return the part
	 */
	public Part getPart() {
		return part;
	}


	/**
	 * @param part the part to set
	 */
	public void setPart(Part part) {
		this.part = part;
	}


	/**
	 * @return the device
	 */
	public Device getDevice() {
		return device;
	}


	/**
	 * @param device the device to set
	 */
	public void setDevice(Device device) {
		this.device = device;
	}


	/**
	 * @return the blockGuides
	 */
	public Collection<BlockGuide> getBlocks() {
		return blockGuides.values();
	}
	
	/**
	 * Adds a block to this implementation guide
	 * @param blockGuide The block to add.
	 * @return Previous block guide with the same cache ID.
	 */
	public BlockGuide addBlock(BlockGuide blockGuide){
		return blockGuides.put(blockGuide.getCacheID(), blockGuide);
	}
	
	/**
	 * Creates a new block guide with the associated cache ID and
	 * adds it to the impl guide.
	 * @param cacheID The Vivado cache ID for the block to be created
	 * @return The newly created block guide
	 */
	public BlockGuide createBlockGuide(String cacheID){
		BlockGuide bg = new BlockGuide();
		bg.setCacheID(cacheID);
		addBlock(bg);
		return bg;
	}
	
	public Set<String> getBlockNames(){
		return blockGuides.keySet();
	}
	
	public BlockGuide getBlock(String id){
		return blockGuides.get(id);
	}
	
	public BlockGuide removeBlock(String id){
		return blockGuides.remove(id);
	}
	
	public void removeBlocksWithoutPBlocks(){
		ArrayList<BlockGuide> toRemove = new ArrayList<>();
		for(BlockGuide bg : getBlocks()){
			boolean isNull = false; 
			for(PBlock pb : bg.getImplementations()){
				if(pb == null) isNull = true;
			}
			if(isNull) toRemove.add(bg);
		}
		for(BlockGuide bg : toRemove){
			removeBlock(bg.getCacheID());
		}
	}
	
	public boolean hasBlock(String id){
		return blockGuides.containsKey(id);
	}
	
	public static void main(String[] args) {
		if(args.length != 2){
			MessageGenerator.briefMessageAndExit("USAGE: <input.igf> <output.igf>");
		}
		ImplGuide ig = readImplGuide(args[0]);
		ig.writeImplGuide(args[1]);
	}
}

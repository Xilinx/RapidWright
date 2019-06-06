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

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.TreeMap;

import com.xilinx.rapidwright.device.Site;

/**
 * An object that captures pre-implemented block creation guidance
 * from an implementation guide file.
 * 
 * Created on: Apr 25, 2017
 */
public class BlockGuide {
	/** String representation of the Vivado IP cache ID */
	private String cacheID;
	/** Indexed list of pblock implementations available to block instances */
	private ArrayList<PBlock> implementations;
	/** A map of all the instances for this block */
	private Map<String,BlockInst> insts;
	/** A map of all the clock ports and their constraints */
	private Map<String,Float> clocks;
	/** A map of any clocks that have estimation clock buffers to use (est. clock skew) */
	private Map<String,Site> clockBuffers;
	/** Finalizing XDC commands to be used (multicycle_paths, etc) */
	private List<String> xdcCommands;
	/** Cached copy of MD5 hash representing collection of settings */
	private String md5Hash;
	
	public BlockGuide(){
		implementations = new ArrayList<>();
		insts = new LinkedHashMap<>();
		clocks = new LinkedHashMap<>();
		clockBuffers = new LinkedHashMap<>();
	}
	
	/**
	 * @return the cacheID
	 */
	public String getCacheID() {
		return cacheID;
	}
	/**
	 * @param cacheID the cacheID to set
	 */
	public void setCacheID(String cacheID) {
		this.cacheID = cacheID;
	}
	/**
	 * @return the implementations
	 */
	public ArrayList<PBlock> getImplementations() {
		return implementations;
	}
	/**
	 * @param implementations the implementations to set
	 */
	public void setImplementations(ArrayList<PBlock> implementations) {
		this.implementations = implementations;
	}
	/**
	 * @return the instances
	 */
	public Collection<BlockInst> getInsts() {
		return insts.values();
	}
	/**
	 * @param insts the instances to set
	 */
	public void setInsts(TreeMap<String, BlockInst> insts) {
		this.insts = insts;
	}
	
	public void addImplementation(int index, PBlock pblock){
		implementations.add(index, pblock);
	}
	
	public BlockInst getInst(String name){
		return insts.get(name);
	}
	
	/**
	 * @param bi
	 */
	public void addBlockInst(BlockInst bi) {
		insts.put(bi.getName(), bi);
	}
	
	public void addClock(String clockName, Float constraint){
		clocks.put(clockName, constraint);
	}
	
	public void addClockBuffer(String clockName, Site clkBuffer){
		clockBuffers.put(clockName, clkBuffer);
	}
	
	public Set<String> getClocks(){
		return clocks.keySet();
	}
	
	public Float getClockPeriod(String clkPortName){
		return clocks.get(clkPortName);
	}
	
	public Site getClockBuffer(String clkPortName){
		return clockBuffers.get(clkPortName);
	}
	
	public Set<String> getClocksWithBuffers(){
		return clockBuffers.keySet();
	}
	
	public void addXDCCommand(String xdc){
		if(xdcCommands == null) xdcCommands = new ArrayList<>();
		xdcCommands.add(xdc);
	}
	
	public List<String> getXDCCommands(){
		if(xdcCommands == null) return Collections.emptyList();
		return xdcCommands;
	}
	
	/**
	 * The MD5 hash intends to include anything that can affect any of the 
	 * implementations from the Impl Guide.  Therefore, if the user changes
	 * anything about the impl guide that affects the implementation build, 
	 * it should restart.  
	 * @return
	 */
	public String getMD5Hash(){
		if(md5Hash == null){
			MessageDigest md5 = null;
			
			try {
				md5 = MessageDigest.getInstance("MD5");
			} catch (NoSuchAlgorithmException e1) {
				e1.printStackTrace();
			}

			// Add all data to md5sum
			md5.update(cacheID.getBytes());
			for(PBlock pb : implementations){
				md5.update(pb.toString().getBytes());
				if(pb.getSubPBlocks() != null){
					for(SubPBlock sub : pb.getSubPBlocks()){
						md5.update(sub.getGetCellsArgs().getBytes());
						md5.update(sub.toString().getBytes());
					}
				}
			}
			for(Entry<String,Float> e : clocks.entrySet()){
				md5.update(e.getKey().getBytes());
				md5.update(e.getValue().toString().getBytes());
			}
			for(Entry<String,Site> e : clockBuffers.entrySet()){
				md5.update(e.getKey().getBytes());
				md5.update(e.getValue().toString().getBytes());
			}
			for(String xdc : getXDCCommands()){
				md5.update(xdc.getBytes());
			}
			
			// Convert bytes to alpha-numeric string
			byte[] b = md5.digest();
			StringBuilder result = new StringBuilder(32);
			for (int i=0; i < b.length; i++) {
				result.append(Integer.toString( ( b[i] & 0xff ) + 0x100, 16).substring( 1 ));
			}
			md5Hash = result.toString();
		}

		return md5Hash;
	}
}

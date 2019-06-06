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
package com.xilinx.rapidwright.design;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import com.xilinx.rapidwright.device.Tile;

/**
 * This class represents the ports used to define the interfaces of modules.  
 * They consist of a unique name and the {@link SitePinInst} to which they are
 * connected.
 * @author Chris Lavin
 * Created on: Jun 22, 2010
 */
public class Port implements Serializable, Cloneable{

	private static final long serialVersionUID = -8961782654770650827L;
	/** Name of the Port of the current module, this is the port of an instance in the module. */
	private String name;
	/** This is the pin that the port references. */
	private SitePinInst sitePinInst;
	/** Port type (provided by metadata) */
	private PortType type;
	/** List of port names this port connects directly to (pass-thru connections) */
	private ArrayList<String> passThruPortNames;
	/** Specifies the tile where the partition pin should go */
	private Tile partitionPinLoc;
	/** Flag to be used when no pin is available denoting direction of port */
	private boolean isOutputPort = false;
	/** Worst case delay in or out of the port in nanoseconds */
	private float worstCasePortDelay = 0.0f;
	
	/**
	 * Default constructor, everything is null.
	 */
	public Port(){
		name = null;
		setSitePinInst(null);
	}
	

	/**
	 * @param name Name of the port.
	 * @param sitePinInst Pin which the port references
	 */
	public Port(String name, SitePinInst sitePinInst){
		this.name = name;
		this.setSitePinInst(sitePinInst);
		if(sitePinInst != null){
			setOutputPort(sitePinInst.isOutPin());
			sitePinInst.setPort(this);
		}
	}
	
	/**
	 * Special constructor when creating a port that has a pass-thru connection
	 * @param name Name of the port on the module
	 * @param isOutputPin Flag denoting if this port is an output or input
	 * @param initialPassThruPinName The name of the pass-thru port this port connects to
	 */
	public Port(String name, boolean isOutputPort, String initialPassThruPinName){
		this(name, null);
		setOutputPort(isOutputPort);
		addPassThruPortName(initialPassThruPinName);
	}
	
	/**
	 * Gets and returns the name of the port.
	 * @return The name of the port.
	 */
	public String getName(){
		return name;
	}
	
	/**
	 * Sets the name of the port.
	 * @param name The new name of the port.
	 */
	public void setName(String name){
		this.name = name;
	}
	
	/**
	 * Gets and returns the instance name.
	 * @return The name of the instance where this port resides.
	 */
	public String getSiteInstName(){
		return sitePinInst == null ? "null" : (sitePinInst.getSiteInst() == null ? "null" : sitePinInst.getSiteInstName());
	}
	
	/**
	 *  Gets the pin name of the instance where the port resides.
	 * @return The pin name of the port.
	 */
	public String getSitePinInstName(){
		return sitePinInst == null ? "null" : sitePinInst.getName();
	}
	

	/**
	 * @param sitePinInst the pin to set
	 */
	public void setSitePinInst(SitePinInst sitePinInst) {
		this.sitePinInst = sitePinInst;
		if(sitePinInst != null) {
			sitePinInst.setPort(this);
			sitePinInst.setIsOutputPin(sitePinInst.isOutPin());
		}
	}

	/**
	 * @return the pin
	 */
	public SitePinInst getSitePinInst() {
		return sitePinInst;
	}

	/**
	 * @return the instance
	 */
	public SiteInst getSiteInst() {
		return sitePinInst.getSiteInst();
	}

	/**
	 * Simply looks at the pin of the port to determine
	 * its direction.
	 * @return True if this port is an output, false otherwise.
	 */
	public boolean isOutPort(){
		return sitePinInst == null ? isOutputPort : sitePinInst.isOutPin();
	}


	/**
	 * @param isOutputPort the isOutputPort to set
	 */
	public void setOutputPort(boolean isOutputPort) {
		this.isOutputPort = isOutputPort;
	}


	/**
	 * @return the passThruPortNames
	 */
	public List<String> getPassThruPortNames() {
		return passThruPortNames == null ? Collections.emptyList() : passThruPortNames;
	}


	/**
	 * @param passThruPinNames the passThruPinNames to set
	 */
	public void setPassThruPortNames(ArrayList<String> passThruPinNames) {
		this.passThruPortNames = passThruPinNames;
	}

	public void addPassThruPortName(String portName){
		if(passThruPortNames == null) passThruPortNames = new ArrayList<String>();
		passThruPortNames.add(portName);
	}

	public Tile getPartitionPinLoc() {
		return partitionPinLoc;
	}


	public void setPartitionPinLoc(Tile partitionPinLoc) {
		this.partitionPinLoc = partitionPinLoc;
	}


	/**
	 * @return the worstCasePortDelay
	 */
	public float getWorstCasePortDelay() {
		return worstCasePortDelay;
	}


	/**
	 * @param worstCasePortDelay the worstCasePortDelay to set
	 */
	public void setWorstCasePortDelay(float worstCasePortDelay) {
		this.worstCasePortDelay = worstCasePortDelay;
	}


	/**
	 * Generates hashCode for this port based on instance name, port name, and pin name.
	 */
	@Override
	public int hashCode(){
		final int prime = 31;
		int result = 1;
		result = prime * result + ((getSiteInstName() == null) ? 0 : getSiteInstName().hashCode());
		result = prime * result + ((name == null) ? 0 : name.hashCode());
		result = prime * result + ((getSitePinInstName() == null) ? 0 : getSitePinInstName().hashCode());
		return result;
	}

	/**
	 * Checks if this and obj are equal ports by comparing port name,
	 * instance name and pin name.
	 */
	@Override
	public boolean equals(Object obj){
		if(this == obj)
			return true;
		if(obj == null)
			return false;
		if(getClass() != obj.getClass())
			return false;
		Port other = (Port) obj;
		if(getSiteInstName() == null){
			if(other.getSiteInstName() != null)
				return false;
		}
		else if(!getSiteInstName().equals(other.getSiteInstName()))
			return false;
		if(name == null){
			if(other.name != null)
				return false;
		}
		else if(!name.equals(other.name))
			return false;
		if(getSitePinInstName() == null){
			if(other.getSitePinInstName() != null)
				return false;
		}
		else if(!getSitePinInstName().equals(other.getSitePinInstName()))
			return false;
		return true;
	}
	
	@Override
	public String toString(){
		StringBuilder sb = new StringBuilder();
		sb.append("port ");
		sb.append(name);
		sb.append(sitePinInst == null ? " null" : " " + sitePinInst.getSitePinName());
		if(passThruPortNames != null && passThruPortNames.size() > 0)
			sb.append(" [PASSTHRU: " + passThruPortNames + "]");
		return sb.toString();
		//return "  port \"" + name + "\" \"" + pin.getSiteInstName() + "\" \"" + pin.getName() +"\";";
	}


	/**
	 * @return the type
	 */
	public PortType getType() {
		return type;
	}


	/**
	 * @param type the type to set
	 */
	public void setType(PortType type) {
		this.type = type;
	}
}

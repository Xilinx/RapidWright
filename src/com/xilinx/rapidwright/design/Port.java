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
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

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
	private Set<SitePinInst> sitePinInsts = new HashSet<>();
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
	}
	

	/**
	 * @param name Name of the port.
	 * @param sitePinInst Pin which the port references
	 */
	public Port(String name, SitePinInst sitePinInst){
		this.name = name;
		addSitePinInst(sitePinInst);
	}

	/**
	 * @param name Name of the port.
	 * @param sitePinInsts Pins which the port references
	 */
	public Port(String name, Collection<SitePinInst> sitePinInsts){
		this.name = name;
		for (SitePinInst sitePinInst : sitePinInsts) {
			addSitePinInst(sitePinInst);
		}
	}


	/**
	 * Special constructor when creating a port that has a pass-thru connection
	 * @param name Name of the port on the module
	 * @param isOutputPort Flag denoting if this port is an output or input
	 * @param initialPassThruPinName The name of the pass-thru port this port connects to
	 */
	public Port(String name, boolean isOutputPort, String initialPassThruPinName){
		this.name = name;
		setOutputPort(isOutputPort);
		addPassThruPortName(initialPassThruPinName);
	}

	/**
	 * @param name Name of the port.
	 */
	public Port(String name) {
		this.name = name;
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
	public String getSingleSiteInstName(){
		SitePinInst singleSitePinInst = getSingleSitePinInst();
		return singleSitePinInst == null ? "null" : (singleSitePinInst.getSiteInst() == null ? "null" : singleSitePinInst.getSiteInstName());
	}
	
	/**
	 *  Gets the pin name of the instance where the port resides.
	 * @return The pin name of the port.
	 */
	public String getSingleSitePinInstName(){
		SitePinInst singleSitePinInst = getSingleSitePinInst();
		return singleSitePinInst == null ? "null" : singleSitePinInst.getName();
	}

	private void setInternalDirectionFromPins() {
		if (sitePinInsts.isEmpty()) {
			return;
		}
		boolean hasOutputPin = false;
		boolean hasInputPin = false;
		for (SitePinInst sitePinInst : sitePinInsts) {
			if (sitePinInst.isOutPin()) {
				hasOutputPin = true;
			} else {
				hasInputPin = true;
			}
		}
		if (hasOutputPin && hasInputPin) {
			throw new IllegalStateException("Port "+getName()+" is a mix of input and output pins");
		}
		isOutputPort = hasOutputPin;
	}
	

	/**
	 * @param sitePinInst the pin to add
	 */
	public void addSitePinInst(SitePinInst sitePinInst) {
		Objects.requireNonNull(sitePinInst);
		sitePinInsts.add(sitePinInst);
		sitePinInst.setPort(this);
		setInternalDirectionFromPins();
		boundingBox = null;
	}

	/**
	 * Convenience method for ports with at most one SitePinInst. Get the single SitePinInst associated with this
	 * Port or null if there are none. Throws if there are multiple.
	 * @return the pin
	 */
	public SitePinInst getSingleSitePinInst() {
		if (sitePinInsts.isEmpty()) {
			return null;
		}
		if (sitePinInsts.size() > 1) {
			throw new IllegalStateException("Tried getting single SitePinInst in port "+getName()+", but there are "+sitePinInsts.size()+" pins");
		}
		return sitePinInsts.iterator().next();
	}

	public Set<SitePinInst> getSitePinInsts() {
		return sitePinInsts;
	}

	/**
	 * @return the instance
	 */
	public SiteInst getSingleSiteInst() {
		return getSingleSitePinInst().getSiteInst();
	}

	/**
	 * Simply looks at the pin of the port to determine
	 * its direction.
	 * @return True if this port is an output, false otherwise.
	 */
	public boolean isOutPort(){
		setInternalDirectionFromPins();
		return isOutputPort;
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
		result = prime * result + sitePinInsts.hashCode();
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
		if (!sitePinInsts.equals(other.sitePinInsts))
			return false;
		if(name == null){
			if(other.name != null)
				return false;
		}
		else if(!name.equals(other.name))
			return false;
		return true;
	}
	
	@Override
	public String toString(){
		StringBuilder sb = new StringBuilder();
		sb.append("port ");
		sb.append(name);
		sb.append(sitePinInsts);
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

	public Net getNet() {
		//All SitePinInsts in the port are required to have the same net, so just grab any pin's net
		if (sitePinInsts.isEmpty()) {
			return null;
		}
		return sitePinInsts.iterator().next().getNet();
	}

	public void removeSitePinInst(SitePinInst p) {
		sitePinInsts.remove(p);
		boundingBox = null;
	}

	RelocatableTileRectangle boundingBox = null;
	public RelocatableTileRectangle getBoundingBox() {
		if (boundingBox == null) {
			boundingBox = new RelocatableTileRectangle();
			for (SitePinInst sitePinInst : getSitePinInsts()) {
				boundingBox.extendTo(sitePinInst.getTile());
			}
		}
		return boundingBox;
	}
}

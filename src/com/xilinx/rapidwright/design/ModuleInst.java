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

import java.util.ArrayList;
import java.util.HashMap;

import com.xilinx.rapidwright.design.blocks.PBlock;
import com.xilinx.rapidwright.design.blocks.PBlockRange;
import com.xilinx.rapidwright.device.Device;
import com.xilinx.rapidwright.device.PIP;
import com.xilinx.rapidwright.device.Site;
import com.xilinx.rapidwright.device.SiteTypeEnum;
import com.xilinx.rapidwright.device.Tile;
import com.xilinx.rapidwright.edif.EDIFNet;
import com.xilinx.rapidwright.edif.EDIFPort;
import com.xilinx.rapidwright.edif.EDIFCell;
import com.xilinx.rapidwright.edif.EDIFCellInst;
import com.xilinx.rapidwright.util.MessageGenerator;
import com.xilinx.rapidwright.util.Utils;

/**
 * There is no direct representation of a module instance in Vivado. Each member of
 * a module instance is referenced in a particular way back to the module
 * instance. This class attempts to collect all the module instance information
 * into a single class.
 * 
 * @author Chris Lavin Created on: Jun 22, 2010
 */
public class ModuleInst{

	/** Name of the module instance */
	private String name;
	/** The design which contains this module instance */
	private transient Design design;
	/** The module of which this object is an instance of */
	private Module module;
	/** The anchor instance of the module instance */
	private SiteInst anchor;
	/** A list of all primitive instances which make up this module instance */
	private ArrayList<SiteInst> instances;
	/** A list of all nets internal to this module instance */
	private ArrayList<Net> nets;
	/** Reference to the logical cell instance in the netlist */
	private EDIFCellInst cellInst;
	
	/**
	 * Constructor initializing instance module name
	 * @param name Name of the module instance
	 */
	public ModuleInst(String name, Design design){
		this.name = name;
		this.setDesign(design);
		this.module = null;
		this.setAnchor(null);
		instances = new ArrayList<SiteInst>();
		nets = new ArrayList<Net>();
	}

	/**
	 * This will initialize this module instance to the same attributes
	 * as the module instance passed in.  This is primarily used for classes
	 * which extend {@link ModuleInst}.
	 * @param moduleInst The module instance to mimic.
	 */
	public ModuleInst(ModuleInst moduleInst){
		this.name = moduleInst.name;
		this.setDesign(moduleInst.design);
		this.module = moduleInst.module;
		this.setAnchor(moduleInst.anchor);
		instances =  moduleInst.instances;
		nets = moduleInst.nets;	
	}
	
	/**
	 * Adds the instance inst to the instances list that are members of the
	 * module instance.
	 * @param inst The instance to add.
	 */
	public void addInst(SiteInst inst){
		instances.add(inst);
	}

	public void removeInst(SiteInst inst){
		instances.remove(inst);
	}
	
	/**
	 * Adds the net to the net list that are members of the module instance.
	 * @param net The net to add.
	 */
	public void addNet(Net net){
		nets.add(net);
	}

	/**
	 * @return the name of this module instance
	 */
	public String getName(){
		return name;
	}

	/**
	 * @param name the name to set
	 */
	public void setName(String name){
		this.name = name;
	}

	/**
	 * @param design the design to set
	 */
	public void setDesign(Design design){
		this.design = design;
	}

	/**
	 * @return the design
	 */
	public Design getDesign(){
		return design;
	}

	/**
	 * @return the moduleType
	 */
	public Module getModule(){
		return module;
	}

	/**
	 * @param module the module to set.
	 */
	public void setModule(Module module){
		this.module = module;
	}

	/**
	 * @return the instances
	 */
	public ArrayList<SiteInst> getInsts(){
		return instances;
	}

	/**
	 * @param instances the instances to set
	 */
	public void setInsts(ArrayList<SiteInst> instances){
		this.instances = instances;
	}

	public EDIFCellInst getCellInst() {
		return cellInst;
	}

	public void setCellInst(EDIFCellInst cellInst) {
		this.cellInst = cellInst;
	}

	/**
	 * Gets the list of physical nets in the module instance.
	 * @return Full list of physical nets in this module instance.
	 */
	public ArrayList<Net> getNets(){
		return nets;
	}

	/**
	 * Sets the anchor instance for this module instance.
	 * @param anchor The new anchor instance for this module instance.
	 */
	public void setAnchor(SiteInst anchor){
		this.anchor = anchor;
	}

	/**
	 * Gets and returns the anchor instance for this module instance.
	 * @return The anchor instance for this module instance.
	 */
	public SiteInst getAnchor(){
		return anchor;
	}
	
	public boolean isPlaced(){
		return anchor.isPlaced();
	}
	
	/**
	 * Does a brute force search to find all valid locations of where this module
	 * instance can be placed.  It returns the module instance to its original
	 * location.
	 * @return A list of valid anchor sites for the module instance to be placed.
	 */
	public ArrayList<Site> getAllValidPlacements(){
		ArrayList<Site> validSites = new ArrayList<Site>();
		if(getAnchor() == null) return validSites;
		Site originalSite = getAnchor().getSite();
		Design design = getDesign();
		Site[] sites = design.getDevice().getAllCompatibleSites(getAnchor().getSiteTypeEnum());
		for(Site newAnchorSite : sites){
			if(place(newAnchorSite)){
				validSites.add(newAnchorSite);
				unplace();
			}
		}
		
		// Put hard macro back
		if(originalSite != null) place(originalSite);
		
		return validSites;
	}

	
	/**
	 * Places the module instance anchor at the newAnchorSite as well as all other 
	 * instances and nets within the module instance at their relative offsets of the new site.
	 * @param newAnchorSite The new site for the anchor of the module instance.
	 * @param dev The device on which the module instance is being placed.
	 * @return True if placement was successful, false otherwise.
	 */
	public boolean place(Site newAnchorSite){	
		// Check if parameters are null
		if(newAnchorSite == null){
			return false;
		}
		Device dev = newAnchorSite.getDevice();
		
		// Do some error checking on the newAnchorSite
		if(module.getAnchor() == null) return false;
		Site p = module.getAnchor().getSite();
		Tile t = newAnchorSite.getTile();
		Site newValidSite = p.getCorrespondingSite(module.getAnchor().getSiteTypeEnum(), t);
		if(!newAnchorSite.equals(newValidSite)){
			//MessageGenerator.briefError("New anchor site (" + newAnchorSite.getName() +
			//		") is incorrect.  Should be " + newValidSite.getName());
			//this.unplace();
			return false;
		}
		
		// save original placement in case new placement is invalid
		HashMap<SiteInst, Site> originalSites;
		originalSites = isPlaced() ? new HashMap<SiteInst, Site>() : null;

		//=======================================================//
		/* Place instances at new location                       */
		//=======================================================//
		for(SiteInst inst : instances){
			// Certain site types cannot move, and will have to remain
			if(Utils.isLockedSiteType(inst.getSiteTypeEnum())){
				inst.place(inst.getModuleTemplateInst().getSite());
				continue;
			}
			
			Site templateSite = inst.getModuleTemplateInst().getSite();
			Tile newTile = module.getCorrespondingTile(templateSite.getTile(), newAnchorSite.getTile(), dev);
			Site newSite = templateSite.getCorrespondingSite(inst.getSiteTypeEnum(), newTile);

			if(newSite == null){
				//MessageGenerator.briefError("ERROR: No matching site found." +
				//	" (Template Site:"	+ templateSite.getName() + 
				//	", Template Tile:" + templateSite.getTile() +
				//	" => New Site:" + newSite + ", New Tile:" + newTile+")");
				
				// revert placement to original placement before method call
				if(originalSites == null){
					unplace();
					return false;
				}
				for(SiteInst i : originalSites.keySet()){
					design.getSiteInst(i.getName()).place(originalSites.get(i));
				}
				return false;
			}
			if(newSite.getSiteTypeEnum() == SiteTypeEnum.BUFGCE && design.isSiteUsed(newSite)){
				// Choose a different buffer if the specific one in the block is already used
				for(Site s : newSite.getTile().getSites()){
					if(s.isCompatibleSiteType(newSite.getSiteTypeEnum()) && !design.isSiteUsed(s)){
						newSite = s;
						break;
					}
				}
				if(design.isSiteUsed(newSite)){
					throw new RuntimeException("ERROR: BlockGuide ("+ name +") contains a BUFGCE that is already fully occupied in the tile specified.");					
				}
			}
			
			if(originalSites != null){ 
				originalSites.put(inst, inst.getSite());
			}
			inst.place(newSite);
		}
		
		//=======================================================//
		/* Place net at new location                             */
		//=======================================================//
		for(Net net : nets){
			net.getPIPs().clear();
			Net templateNet = net.getModuleTemplateNet();
			for(PIP pip : templateNet.getPIPs()){
				Tile templatePipTile = pip.getTile();
				Tile newPipTile = module.getCorrespondingTile(templatePipTile, newAnchorSite.getTile(), dev);
				if(newPipTile == null){
					unplace();
					MessageGenerator.briefError("Warning: Unable to return module instance "+ name +" back to original placement.");
					return false;
				}
				PIP newPip = new PIP(pip);///new PIP(newPipTile, pip.getStartWire(), pip.getEndWire(), pip.getPIPType());
				newPip.setTile(newPipTile);
				//if(!newPipTile.hasPIP(newPip)){
				//	return false;
				//}
				net.addPIP(newPip);
			}
		}
		return true;
	}
	
	/**
	 * Removes all placement information and unroutes all nets of the module instance.
	 */
	public void unplace(){
		//unplace instances
		for(SiteInst inst : instances){
			inst.unPlace();
		}
		//unplace nets (remove pips)
		for(Net net : nets){
			net.getPIPs().clear();
		}
	}

	/**
	 * This method will calculate and return the corresponding tile of a module instance.
	 * for a new anchor location.
	 * @param templateTile The tile in the module which acts as a template.
	 * @param newAnchorTile This is the tile of the new anchor instance of the module instance.
	 * @param dev The device which corresponds to this module instance.
	 * @return The new tile of the module instance which corresponds to the templateTile, or null
	 * if none exists.
	 */
	public Tile getCorrespondingTile(Tile templateTile, Tile newAnchorTile, Device dev){
		return module.getCorrespondingTile(templateTile, newAnchorTile, dev);
	}
	
	/**
	 * Gets the corresponding port pin (SitePinInst) on this module instance
	 * that corresponds to the module's port.  
	 * @param port The port on the prototype module.
	 * @return The corresponding port pin on this module instance, or null if could not be found.
	 */
	public SitePinInst getCorrespondingPin(Port port){
		SitePinInst modulePin = port.getSitePinInst();
		if(modulePin == null) return null;
		
		Tile anchorTile = getAnchor().getTile();
		Tile newPinTile = getCorrespondingTile(modulePin.getTile(), anchorTile, anchorTile.getDevice());
		
		if(newPinTile == null) return null;
		
		Site newSite = newPinTile.getSites()[modulePin.getSite().getSiteIndexInTile()];
		SiteInst newSiteInst = getDesign().getSiteInstFromSite(newSite);
		return newSiteInst.getSitePinInst(modulePin.getName());
	}
	
	/**
	 * Gets (if it exists), the corresponding net within the module instance of the port.
	 * @param p The port on the module of interest
	 * @return The corresponding net on the module instance.
	 */
	public Net getCorrespondingNet(Port p){
		if(p.getSitePinInst() != null && p.getSitePinInst().getNet() != null){
			String name = getName() + "/" + p.getSitePinInst().getNet().getName();
			return design.getNet(name);
		}
		// Get net of input port pass-thru
		if(p.isOutPort() && p.getPassThruPortNames().size() > 0){
			Port input = getModule().getPort(p.getPassThruPortNames().get(0));
			return getCorrespondingNet(input);
		}
		return null;
	}
	
	/* (non-Javadoc)
	 * @see java.lang.Object#hashCode()
	 */
	@Override
	public int hashCode(){
		final int prime = 31;
		int result = 1;
		result = prime * result + ((name == null) ? 0 : name.hashCode());
		return result;
	}

	/* (non-Javadoc)
	 * @see java.lang.Object#equals(java.lang.Object)
	 */
	@Override
	public boolean equals(Object obj){
		if(this == obj)
			return true;
		if(obj == null)
			return false;
		if(getClass() != obj.getClass())
			return false;
		ModuleInst other = (ModuleInst) obj;
		if(name == null){
			if(other.name != null)
				return false;
		}
		else if(!name.equals(other.name))
			return false;
		return true;
	}
	
	/**
	 * Get's the corresponding port on the module by name.
	 * @param name
	 * @return
	 */
	public Port getPort(String name){
		return module.getPort(name);
	}
	
	public String toString(){
		return name;
	}
	
	/**
	 * Get's the current lower left site as used for a placement directive 
	 * for an implementation guide.  If a pblock was used and is presently annotated
	 * on the Module, it will use the lower left most corner of the pblock.
	 * @return The current lower left site used for placement.
	 */
	public Site getLowerLeftPlacement(){
		return getLowerLeftPlacement(null);
	}
	
	/**
	 * Get's the current lower left site as used for a placement directive 
	 * for an implementation guide.  If a pblock was used and is presently annotated
	 * on the Module, it will use the lower left most corner of the pblock.
	 * @param type If a desired type is requested for the corner, otherwise it will search 
	 * for the most lower left corner site 
	 * @return The current lower left site used for placement.
	 */
	public Site getLowerLeftPlacement(SiteTypeEnum type){
		// Calculate anchor offset
		SiteInst anchor = getModule().getAnchor();
		if(anchor == null) return null;
		
		Tile origAnchor = anchor.getSite().getTile();
		Tile currAnchor = getAnchor().getSite().getTile();
		int dx = currAnchor.getTileXCoordinate() - origAnchor.getTileXCoordinate();
		int dy = currAnchor.getTileYCoordinate() - origAnchor.getTileYCoordinate();
		
		// Get original lower left placement 
		Tile origLowerLeft = getLowerLeftTile(type);
		
		String origTilePrefix = origLowerLeft.getTileNamePrefix();
		String newSuffix = "X" + (origLowerLeft.getTileXCoordinate() + dx) + "Y" + (origLowerLeft.getTileYCoordinate() + dy);
		
		Tile newTile = origLowerLeft.getDevice().getTile(origTilePrefix + newSuffix);
		if(type == null){
			return newTile.getSites()[0];
		}
		for(Site s : newTile.getSites()){
			if(s.getSiteTypeEnum() == type) return s;
		}
		return null;
	}
	
	/**
	 * Chooses a lower left reference tile in a module instance for the purpose
	 * of placement. The tile chosen is from the context of the original module, not 
	 * the module instance's current location.
	 * @param type The site type space in which to reference.
	 * @return A lower left tile from the module's original footprint.
	 */
	public Tile getLowerLeftTile(SiteTypeEnum type){
		PBlock pb = getModule().getPBlock();
		if(pb != null){
			if(type == null) 
				return pb.getBottomLeftTile();
			
			for(PBlockRange range : pb){
				if(range.getLowerLeftSite().getSiteTypeEnum() == type){
					return range.getLowerLeftSite().getTile();
				}
			}
		}
		
		
		SiteInst lowerLeftIP = null;
		int x = Integer.MAX_VALUE;
		int y = Integer.MAX_VALUE;
		for(SiteInst s : getModule().getSiteInsts()){
			boolean isSiteCompatible = type == null ? PBlock.isPBlockCornerSiteType(s.getSiteTypeEnum()) : 
				(s.getSite().isCompatibleSiteType(type) || (Utils.isSLICE(s) && Utils.isSLICE(type))); 
			if(isSiteCompatible){
				if(lowerLeftIP == null){
					lowerLeftIP = s;
				}else if(s.getSite().getInstanceY() < lowerLeftIP.getSite().getInstanceY()){
					lowerLeftIP = s;
				}
				if(s.getSite().getInstanceX() < x){
					x = s.getSite().getInstanceX();
				}
				if(s.getSite().getInstanceY() < y){
					y = s.getSite().getInstanceY();
				}
			}
		}
		
		Device dev = getDesign().getDevice();
		String prefix = lowerLeftIP.getSite().getName().substring(0, lowerLeftIP.getSite().getName().lastIndexOf('_')+1);
		Site target = dev.getSite(prefix + "X" + x + "Y" + y);
		
		return target.getTile();
	}
	
	/**
	 * Attempts to place the module instance such that it's lower left tile falls
	 * on the specified IP tile (CLB,DSP,BRAM,...)
	 * @param ipTile The specific tile onto which the lower left tile of the {@link ModuleInst} should be placed.
	 * @param type The specific site type in the tile.
	 * @return True if the placement succeeded, false otherwise.
	 */
	public boolean placeMINearTile(Tile ipTile, SiteTypeEnum type){
		Tile targetTile = getLowerLeftTile(type);
		Device dev = targetTile.getDevice();
		
		Tile newAnchorTile = getModule().getCorrespondingAnchorTile(targetTile, ipTile, dev);
		if(newAnchorTile == null) return false;
		
		SiteInst anchor = getModule().getAnchor();
		if(anchor == null) return false;
		Site moduleAnchor = anchor.getSite();
		boolean success = place(newAnchorTile.getSites()[moduleAnchor.getTile().getSiteIndex(moduleAnchor)]);
		
		if(!success) System.out.println("Failed placement attempt, TargetTile="+targetTile.getName()+" ipTile="+ipTile.getName());
		return success;  
	}		

	/**
	 * Connects two signals by port name between this module instance and a top-level port. 
	 * This method will create a new net for the connection handling adding both
	 * a logical net (EDIFNet) and physical net (Net).
	 * @param portName This module instance's port name to connect.
	 * @param otherPortName The top-level port of the the cell instance.
	 * 
	 */
	public void connect(String portName, String otherPortName){
		connect(portName, null, otherPortName, -1);
	}
	
	/**
	 * Connects two signals by port name between this module instance and a top-level port. 
	 * This method will create a new net for the connection handling adding both
	 * a logical net (EDIFNet) and physical net (Net).
	 * @param portName This module instance's port name to connect.
	 * @param otherPortName The top-level port of the the cell instance.
	 * @param busIndex If the port is multi-bit, specify the index to connect or -1 if single bit bus.
	 */
	public void connect(String portName, String otherPortName, int busIndex){
		connect(portName, null, otherPortName, busIndex);
	}
	
	/**
	 * Connects two signals by port name between this module instance and another. 
	 * This method will create a new net for the connection handling adding both
	 * a logical net (EDIFNet) and physical net (Net).
	 * @param portName This module instance's port name to connect.
	 * @param other The other module instance to connect to. If this is null, it will 
	 * connect it to an existing parent cell port named otherPortName
	 * @param otherPortName The port name on the other module instance to connect to or
	 * the top-level port of the the cell instance.
	 */
	public void connect(String portName, ModuleInst other, String otherPortName){
		connect(portName, other, otherPortName, -1);
	}
	
	/**
	 * Connects two signals by port name between this module instance and another. 
	 * This method will create a new net for the connection handling adding both
	 * a logical net (EDIFNet) and physical net (Net).
	 * @param portName This module instance's port name to connect.
	 * @param other The other module instance to connect to. If this is null, it will 
	 * connect it to an existing parent cell port named otherPortName
	 * @param otherPortName The port name on the other module instance to connect to or
	 * the top-level port of the the cell instance.
	 * @param busIndex If the port is multi-bit, specify the index to connect or -1 if single bit bus.
	 */
	public void connect(String portName, ModuleInst other, String otherPortName, int busIndex){
		connect(portName, busIndex, other, otherPortName, busIndex);
	}

	/**
	 * Connects two signals by port name between this module instance and another.
	 * This method will create a new net for the connection handling adding both
	 * a logical net (EDIFNet) and physical net (Net).
	 * @param portName This module instance's port name to connect.
	 * @param busIndex0 If the assigned port of this module instance is multi-bit,
	 * specify the index to connect or -1 if single bit bus.
	 * @param other The other module instance to connect to. If this is null, it will
	 * connect it to an existing parent cell port named otherPortName
	 * @param otherPortName The port name on the other module instance to connect to or
	 * the top-level port of the the cell instance.
	 * @param busIndex1 If the port (of the other module instance or the existing parent cell) is multi-bit,
	 * specify the index to connect or -1 if single bit bus.
	 */

	public void connect(String portName, int busIndex0, ModuleInst other, String otherPortName, int busIndex1){
		EDIFCell top = design.getTopEDIFCell();
		EDIFCellInst eci0 = top.getCellInst(getName());
		if(eci0 == null) throw new RuntimeException("ERROR: Couldn't find logical cell instance for " + getName());
		if(other == null) {
			// Connect to a top-level port
			EDIFPort port = top.getPort(otherPortName);

			String netName = busIndex1 == -1 ? otherPortName : port.getBusName() + "[" + busIndex1 + "]";
			EDIFNet net = top.getNet(netName);
			if(net == null){
				net = top.createNet(netName);
			}
			if(net.getPortInst(netName) == null){
				net.createPortInst(port, busIndex1);
			}
			net.createPortInst(portName, busIndex0, eci0);
			return;
		}
		EDIFCellInst eci1 = top.getCellInst(other.getName());
		if(eci1 == null) throw new RuntimeException("ERROR: Couldn't find logical cell instance for " + getName());

		String netName = busIndex0 == -1 ? getName() + "_" + portName : getName() + "_" + portName + "["+busIndex0+"]";
		EDIFNet net = top.createNet(netName);
		net.createPortInst(portName, busIndex0, eci0);
		net.createPortInst(otherPortName, busIndex1, eci1);

		// Connect physical pins
		Port p0 = getPort(busIndex0 == -1 ? portName : portName + "[" + busIndex0 + "]");
		Port p1 = other.getPort(busIndex1 == -1 ? otherPortName : otherPortName + "[" + busIndex1 + "]");

		SitePinInst pin0 = getCorrespondingPin(p0);
		SitePinInst pin1 = other.getCorrespondingPin(p1);

		if(pin0.isOutPin()){
			pin0.getNet().addPin(pin1);
		}else{
			pin1.getNet().addPin(pin0);
		}
	}
}

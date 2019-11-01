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
package com.xilinx.rapidwright.edif;

import java.io.IOException;
import java.io.Writer;
import java.util.Collection;
import java.util.Collections;
import java.util.Map;

/**
 * Represent a logical cell in an EDIF netlist.  Can 
 * be both a leaf cell or a hierarchical cell.
 * 
 * Created on: May 11, 2017
 */
public class EDIFCell extends EDIFPropertyObject {

	public static final String DEFAULT_VIEW = "netlist";
	
	private EDIFLibrary library;
	
	private Map<String,EDIFCellInst> instances;
	
	private Map<String,EDIFNet> nets;
	
	private Map<String,EDIFPort> ports;
	
	private Map<String,EDIFNet> internalPortMap;
	
	private String view = DEFAULT_VIEW;
	
	public EDIFCell(EDIFLibrary lib, String name){
		super(name);
		if(lib != null) lib.addCell(this);
	}
	
	protected EDIFCell(){
		
	}
	
	public EDIFCellInst createChildCellInst(String name, EDIFCell reference){
		return new EDIFCellInst(name, reference, this);
	}
	
	public EDIFCellInst addNewCellInstUniqueName(String suggestedName, EDIFCell reference){
		EDIFCellInst i = new EDIFCellInst();
		i.setName(suggestedName);
		i.setCellType(reference);
		return addCellInstUniqueName(i);
	}
	
	/**
	 * Adds the instance to this cell.  Checks for a name collision.
	 * @param instance The instance to add to this cell.
	 * @return The instance added to the cell.
	 */
	public EDIFCellInst addCellInst(EDIFCellInst instance){
		if(instances == null) instances = getNewMap();
		instance.setParentCell(this);
		EDIFCellInst collision = instances.put(instance.getName(), instance);
		if(collision != null && instance != collision){
			throw new RuntimeException("ERROR: Name collsion inside EDIFCell " + 
				getName() + ", trying to add instance " + instance.getName() +
				" which already exists inside this cell.");
		}
		return instance;
	}
	
	/**
	 * Ensures that the provided cell instance is added to this cell by renaming it
	 * to something unique.
	 * @param instance The instance to be added to this cell.
	 * @return The instance added to the cell.
	 */
	public EDIFCellInst addCellInstUniqueName(EDIFCellInst instance){
		if(instances == null) instances = getNewMap();
		instance.setParentCell(this);
		while(instances.containsKey(instance.getName())){
			instance.setName(instance.getName() + "_" + getLibrary().getNetlist().nameSpaceUniqueCount++);
		}
		instance.updateEDIFRename();
		instances.put(instance.getName(), instance);
		return instance;
	}
	
	public EDIFCellInst getCellInst(String name){
		if(instances == null) return null;;
		return instances.get(name);
	}

	/**
	 * Adds a net to the cell. Checks for name collisions.
	 * @param net The net to add
	 * @return The net that was added.
	 */
	public EDIFNet addNet(EDIFNet net){
		if(nets == null) nets = getNewMap();
		net.setParentCell(this);
		EDIFNet collision = nets.put(net.getName(), net);
		if(collision != null && net != collision){
			throw new RuntimeException("ERROR: Name collision inside EDIFCell " + 
				getName() + ", trying to add net " + net.getName() +
				" which already exists inside this cell.");
		}
		return net;
	}
	
	public EDIFNet getNet(EDIFNet net){
		return getNet(net.getName());
	}
	
	public EDIFNet getNet(String name){
		if(nets == null) return null;
		return nets.get(name);
	}
	
	public EDIFNet removeNet(EDIFNet net){
		return removeNet(net.getName());
	}
	
	public EDIFNet removeNet(String name){
		if(nets == null) return null;;
		return nets.remove(name);
	}
	/**
	 * Adds a port to the cell.  Checks for naming collisions and throws
	 * RuntimeException if it occurs. Note that ports are usually keyed by
	 * bus name (see {@link EDIFPort#getBusName()}) to enable getPort() to 
	 * only require the bus name for getting a port.  However, is situations
	 * where the bus name collides with a single bit bus name, the range
	 * is included for the multi-bit bus and getPort() requires the range.  
	 * This is only in the case where a single bit bus collides by having the 
	 * same name.  For example single bit port 'my_port[0]' and multi-bit port 
	 * 'my_port[0][3:0]' being added will require that requesting the multi-bit
	 * port through getPort() will require the entire name 'my_port[0][3:0]'.  
	 * Ultimately this naming scheme is discouraged. 
	 * @param port The port to add.
	 * @return The port that was added.
	 */
	public EDIFPort addPort(EDIFPort port){
		if(ports == null) ports = getNewMap();
		port.setParentCell(this);
		EDIFPort collision = ports.put(port.getBusName(),port);
		if(collision != null && port != collision){
			if(collision.getWidth() != port.getWidth()) {
				// We have a situation where two ports have the same root name,
				// For example:
				//   my_port[0]
				//   my_port[0][3:0]
				//
				if(collision.getWidth() > 1) {
					ports.put(collision.getName(), collision);
				}else {
					ports.put(collision.getBusName(), collision);
					ports.put(port.getName(), port);
				}				
			}else {
				throw new RuntimeException("ERROR: Name collsion inside EDIFCell " +
					getName() + ", trying to add port " + port.getName() +
					" which already exists inside this cell.");
			}
			 
		}
		return port;
	}
	
	/**
	 * Gets a port by bus name (see {@link EDIFPort#getBusName()}).  Multi-bit ports need to 
	 * have brackets removed unless the {@link EDIFCell} already has a port
	 * with the same name as the bus name of the multi-bit port.  In only this case,
	 * the range would be required in order to distinguish the ambiguity.  
	 * See {@link EDIFCell#addPort(EDIFPort)} for more information.	
	 * @param name Bus name of the port to get.
	 * @return The port or null if none exists.
	 */
	public EDIFPort getPort(String name){
		if(ports == null) return null;
		return ports.get(name);
	}
	
	public EDIFCellInst createCellInst(String name, EDIFCell parent){
		return new EDIFCellInst(name, this, parent);
	}
	
	public EDIFCellInst removeCellInst(EDIFCellInst cellInstance){
		return removeCellInst(cellInstance.getName());
	}
	
	public EDIFCellInst removeCellInst(String name){
		if(instances == null) return null;
		return instances.remove(name);
	}
	
	public EDIFNet createNet(String name){
		EDIFNet net = new EDIFNet(name, this);
		return net;
	}
	
	public EDIFPort createPort(String name, EDIFDirection direction, int width){
		EDIFPort p = new EDIFPort(name, direction, width);
		addPort(p);
		return p;
	}
	
	public EDIFPort createPort(EDIFPort port) {
		return createPort(port.getName(), port.getDirection(), port.getWidth());
	}
	
	public void rename(String newName){
		setName(newName);
	}
	
	/**
	 * Renames the provided instance i with newName. 
	 * @param i Current instance in the cell.
	 * @param newName New name for instance i
	 * @return The newly renamed instance
	 */
	public EDIFCellInst renameCellInst(EDIFCellInst i, String newName){
		EDIFCellInst inst = getCellInst(i.getName());
		if(inst == null) {
			throw new RuntimeException("ERROR: " +
				"Couldn't find instance " + i.getName() + " in cell " + getName() +
				" when trying to rename to " + newName);
		}
		removeCellInst(inst);
		inst.setName(newName);
		inst.updateEDIFRename();
		addCellInst(inst);
		return inst;
	}
	
	public void moveToLibrary(EDIFLibrary newLibrary){
		if(library != null) library.removeCell(this);
		newLibrary.addCell(this);		
	}
	
	/**
	 * @return the view
	 */
	public String getView() {
		return view;
	}

	/**
	 * @param view the view to set
	 */
	public void setView(String view) {
		this.view = view;
	}
	
	public Collection<EDIFPort> getPorts(){
		if(ports == null) return Collections.emptyList();
		return ports.values();
	}
	
	public Map<String,EDIFPort> getPortMap(){
		return ports == null ? Collections.emptyMap() : ports;
	}
	
	public Collection<EDIFCellInst> getCellInsts(){
		if(instances == null) return Collections.emptyList();
		return instances.values();
	}
	
	public Collection<EDIFNet> getNets(){
		if(nets == null) return Collections.emptyList();
		return nets.values();
	}
	
	/**
	 * Populates an internal map between port-based port ref name,  'bus[3]' or 'clk'.  
	 * @param portInstName Name from a port ref as generated in @link {@link EDIFPortInst#getPortInstNameFromPort()} 
	 * @param internalNet The net inside this cell to match with the port ref name.
	 */
	protected void addInternalPortMapEntry(String portInstName, EDIFNet internalNet){
		if(internalPortMap == null) internalPortMap = getNewMap();
		internalPortMap.put(portInstName, internalNet);
	}
	
	/**
	 * Removes the entry within the cell internal net map (when removing a port on a cell).  
	 * @param portInstName Name of the port ref to remove 
	 * @return The net to which the removed port ref belongs, or null if none could be found.
	 */
	protected EDIFNet removeInternalPortMapEntry(String portInstName){
		if(internalPortMap == null) return null;
		return internalPortMap.remove(portInstName);
	}
	
	public Map<String,EDIFNet> getInternalNetMap(){
		if(internalPortMap == null) return Collections.emptyMap();
		return internalPortMap;
	}
	
	/**
	 * Takes an external (or internal) port ref and returns the corresponding
	 * EDIFNet connected inside the cell.
	 * @param portInst The external port ref to get the internal net.
	 * @return The internal connected net or null if none exists.
	 */
	public EDIFNet getInternalNet(EDIFPortInst portInst){
		return getInternalNet(portInst.getPortInstNameFromPort());
	}

	protected EDIFNet getInternalNet(String portInstName){
		if(internalPortMap == null) return null;
		return internalPortMap.get(portInstName);
	}
	
	/**
	 * @return the library
	 */
	public EDIFLibrary getLibrary() {
		return library;
	}

	/**
	 * @param library the library to set
	 */
	public void setLibrary(EDIFLibrary library) {
		this.library = library;
	}

	public boolean hasContents(){
		return instances != null || nets != null; 
	}
	
	public boolean isPrimitive(){
		return getLibrary().getName().equals(EDIFTools.EDIF_LIBRARY_HDI_PRIMITIVES_NAME) && (instances == null || instances.size() == 0) && (nets == null || nets.size() == 0);
	}
	
	public boolean isLeafCellOrBlackBox() {
		return (instances == null || instances.size() == 0) && (nets == null || nets.size() == 0);
	}
	
	/**
	 * Deletes internal representation.  
	 */
	protected void makePrimitive() { 
		instances = null;
		nets = null;
		internalPortMap = null;
	}
	
	public void exportEDIF(Writer wr) throws IOException{
		wr.write("   (cell ");
		exportEDIFName(wr);
		wr.write(" (celltype GENERIC)\n");
		wr.write("     (view "+getView()+" (viewtype NETLIST)\n");
		wr.write("       (interface \n");
		for(EDIFPort port : getPorts()){
			port.exportEDIF(wr, "        ");
		}
		wr.write("       )\n"); // Interface end
		if(hasContents()){
			wr.write("       (contents\n");
			for(EDIFCellInst i : getCellInsts()){
				i.exportEDIF(wr);						
			}
			for(EDIFNet n : getNets()){
				n.exportEDIF(wr);
			}
			wr.write("       )\n"); // Contents end
		}
		if(getProperties().size() > 0){
			wr.write("\n");
			exportEDIFProperties(wr, "           ");
		}
		wr.write("     )\n"); // View end
		wr.write("   )\n"); // Cell end
	}
}
 
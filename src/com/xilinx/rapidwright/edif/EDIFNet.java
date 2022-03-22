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
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.xilinx.rapidwright.design.Cell;

/**
 * Represents a net within an EDIF netlist.
 * 
 * Created on: May 11, 2017
 */
public class EDIFNet extends EDIFPropertyObject {
	
	private EDIFCell parentCell;
	
	private EDIFPortInstList portInsts;
	
	public EDIFNet(String name, EDIFCell parentCell){
		super(name);
		if(parentCell != null) parentCell.addNet(this);
	}
	
	/**
	 * Copy constructor, does not copy portInsts
	 * @param net
	 */
	public EDIFNet(EDIFNet net) {
		super((EDIFPropertyObject) net);
	}
	
	protected EDIFNet(){
		
	}
	
	/**
	 * Adds the EDIFPortInst to this logical net.  The net stores the port instances using a sorted
	 * ArrayList (@link EDIFPortInstList).  Worst case O(n) to add. 
	 * @param portInst The port instance to add to this net.
	 */
	public void addPortInst(EDIFPortInst portInst){
		if(portInsts == null) portInsts = new EDIFPortInstList();
		if(parentCell != null && portInst.getCellInst() == null) {
			parentCell.addInternalPortMapEntry(portInst.getName(), this);
		}
		portInst.setParentNet(this);
		portInsts.add(portInst);
	}
	
	public EDIFPortInst createPortInst(EDIFPort port){
		return new EDIFPortInst(port, this, null);
	}
	
	public EDIFPortInst createPortInst(EDIFPort port, int index){
		return new EDIFPortInst(port, this, index, null);
	}
	
	public EDIFPortInst createPortInst(String portName, EDIFCellInst cellInst){
		EDIFPort port = cellInst.getPort(portName);
		if(port == null) {
			// check if it is a bussed port
			int lengthRootName = portName.lastIndexOf('[');
			if(lengthRootName == -1) return null;
			String name = portName.substring(0, lengthRootName);
			int idx = Integer.parseInt(portName.substring(lengthRootName+1, portName.lastIndexOf(']')));
			String portRootName = portName.substring(0,lengthRootName);
			port = cellInst.getPort(portRootName);
			if(port == null) {
				return null;
			}
			return createPortInst(name, port.getWidth()-idx-1, cellInst);
		}
		return new EDIFPortInst(port, this, cellInst);
	}
	
	public EDIFPortInst createPortInst(String portName, int index, EDIFCellInst cellInst){
		EDIFPort port = cellInst.getPort(portName);
		return new EDIFPortInst(port, this, index, cellInst);
	}
	
	public EDIFPortInst createPortInst(String portName, Cell cell){
		EDIFCellInst cellInst = cell.getEDIFCellInst();
		return createPortInst(portName,cellInst);
	}
	
	public EDIFPortInst createPortInst(String portName, int index, Cell cell){
		EDIFCellInst cellInst = cell.getEDIFCellInst();
		return createPortInst(portName,index,cellInst);
	}

	
	public EDIFPortInst createPortInst(EDIFPort port, EDIFCellInst cellInst){
		return new EDIFPortInst(port, this, cellInst);
	}
		
	public EDIFPortInst createPortInst(EDIFPort port, int index, EDIFCellInst cellInst){
		return new EDIFPortInst(port, this, index, cellInst);
	}
	
	/**
	 * Creates a new map of all the EDIFPortInst objects stored on this net.  The new map
	 * contains a copy of EDIFPortInsts available at the time of invocation as returned from 
	 * {@link #getPortInstList()}.      
	 * @return A map of EDIFPortInst names ({@link EDIFPortInst#getName()} to the corresponding objects.
	 * @deprecated
	 */
	public Map<String, EDIFPortInst> getPortInstMap(){
	    if(portInsts == null) return Collections.emptyMap();
	    HashMap<String, EDIFPortInst> map = new HashMap<>();
	    for(EDIFPortInst e : getPortInsts()) {
	        map.put(e.getFullName(), e);
	    }
	    return map;
	}
	
	/**
	 * Gets the sorted ArrayList of EDIFPortInsts on this net as a collection.
	 * @return The collection of EDIFPortInsts on this net.
	 */
	public Collection<EDIFPortInst> getPortInsts(){
		return portInsts == null ? Collections.emptyList() : portInsts;
	}
	
	public void rename(String newName) {
	    this.parentCell.removeNet(this);
	    setName(newName);
	    updateEDIFRename();
	    this.parentCell.addNet(this);
	}
	
	/**
	 * This returns all sources on the net, either output ports of the 
	 * cell instances in the cell or the top level input ports.
	 * @return A list of port ref sources. 
	 */
	public List<EDIFPortInst> getSourcePortInsts(boolean includeTopLevelPorts){
		List<EDIFPortInst> srcs = new ArrayList<>();
		for(EDIFPortInst portInst : getPortInsts()){
			boolean includePort =
				(portInst.isOutput() && !portInst.isTopLevelPort()) ||
				(portInst.isInput() && portInst.isTopLevelPort() && includeTopLevelPorts);
			if(includePort) srcs.add(portInst);
		}
		return srcs;
	}
	
	/**
	 * @deprecated
	 * Poor performance, please use {@link #getPortInst(EDIFCellInst, String)}.
	 * @param fullName Full name of the port instance {@link EDIFPortInst#getFullName()}
	 * @return The port instance connected to this net, or null if none exists. 
	 */
	public EDIFPortInst getPortInst(String fullName) {
	    return getPortInstMap().get(fullName);
	}
	
	/**
	 * Gets the port instance specified by the cell instance and name of the port instance.  If the
	 * specified cell instance is null, this looks for a top level port instance on the parent cell.
	 * The net stores the port instances using a sorted ArrayList (@link EDIFPortInstList).  Worst 
	 * case O(log n) to get.
	 * @param inst The cell instance where the EDIFPortInst resides.  If this is null, it gets the
	 * top level port instance connected to the parent cell port.
	 * @param portInstName Name of the port instance ({@link EDIFPortInst#getName()} to get
	 * @return The port instance connected to this net, or null if none exists.
	 */
	public EDIFPortInst getPortInst(EDIFCellInst inst, String portInstName){
	    if (portInsts == null) return null;
	    return portInsts.get(inst, portInstName);
	}

	/**
	 * Gets the first top level port instance from the stored list in the net.  If multiple top level
	 * port instances exist on the net, this only returns the first found. For a comprehensive list
	 * call {@link #getAllTopLevelPortInsts()}.
	 * @return The first top level port instance found in the net, or null if none exists.
	 */
	public EDIFPortInst getTopLevelPortInst() {
	    for(EDIFPortInst portInst : getPortInsts()) {
	        if(portInst.isTopLevelPort()) {
	            return portInst;
	        }
	    }
	    return null;
	}
	
	/**
	 * Gets all top level port instances connected to this net.  
	 * @return A list of all top level port instances connected to this net.
	 */
	public List<EDIFPortInst> getAllTopLevelPortInsts() {
	    List<EDIFPortInst> topPortInsts = new ArrayList<>();
	    for(EDIFPortInst portInst : getPortInsts()) {
            if(portInst.isTopLevelPort()) {
                topPortInsts.add(portInst);
            }
        }
	    return topPortInsts;
	}
	
	/**
	 * Removes the port instance provided from the net. The net stores the port instances using a 
	 * sorted ArrayList (@link EDIFPortInstList).  Worst case O(n) to remove.
	 * @param portInst The port instance to remove from the net.
	 * @return The port instance object that was removed or null if no changes were made.
	 */
	public EDIFPortInst removePortInst(EDIFPortInst portInst){
		return removePortInst(portInst.getCellInst(), portInst.getName()); 
	}
	
	/**
	 * Removes the port instance by full name.  
	 * @param portInstName Full name of the port instance (if its on a cell instance, it includes 
	 * the instance name suffixed with '/' followed by bit-wise port name.
	 * @return The removed port instance, or null if none removed.
	 * @deprecated
	 */
	public EDIFPortInst removePortInst(String portInstName) {
		int hierIdx = portInstName.lastIndexOf('/');
		if(hierIdx == -1) {
			return removePortInst(null, portInstName);
		}
		String instName = portInstName.substring(0, hierIdx);
		EDIFCellInst inst = getParentCell().getCellInst(instName);
		String pinName = portInstName.substring(hierIdx+1);
		return removePortInst(inst,pinName);
	}
	
	/**
	 * Removes the port instance specified from the net. The net stores the port instances using a
	 * sorted ArrayList (@link EDIFPortInstList).  Worst case O(n) to remove. 
	 * @param inst The cell instance where the EDIFPortInst resides.  If this is null, it removes 
	 * the top level port instance connected to the parent cell port.
	 * @param portInstName Name of the port instance ({@link EDIFPortInst#getName()} to remove
	 * @return The port instance object that was removed or null if no changes were made.
	 */
	public EDIFPortInst removePortInst(EDIFCellInst inst, String portInstName){
        if (portInsts == null) return null;
        EDIFPortInst tmp = portInsts.remove(inst, portInstName);
		if(tmp != null) tmp.setParentNet(null);
		return tmp;
	}
	
	/**
	 * @return the parentCell
	 */
	public EDIFCell getParentCell() {
		return parentCell;
	}

	/**
	 * @param parentCell the parentCell to set
	 */
	public void setParentCell(EDIFCell parentCell) {
		this.parentCell = parentCell;
	}
	
	public void exportEDIF(Writer wr) throws IOException {
		wr.write("         (net ");
		exportEDIFName(wr);
		wr.write(" (joined\n");
		for(EDIFPortInst p : getPortInsts()){
			p.writeEDIFExport(wr, "          ");
		}							
		wr.write("          )\n"); // joined end
		if(getProperties().size() > 0){
			wr.write("\n");
			exportEDIFProperties(wr, "           ");
		}
		wr.write("         )\n"); // Nets end

	}
}

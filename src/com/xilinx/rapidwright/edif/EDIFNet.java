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
	
	private Map<String,EDIFPortInst> portInsts;
	
	public EDIFNet(String name, EDIFCell parentCell){
		super(name);
		if(parentCell != null) parentCell.addNet(this);
	}
	
	protected EDIFNet(){
		
	}
	
	public EDIFPortInst addPortInst(EDIFPortInst portInst){
		if(portInsts == null) portInsts = getNewMap();
		portInst.setParentNet(this);
		if(parentCell != null && portInst.getCellInst() == null) {
			parentCell.addInternalPortMapEntry(portInst.getName(), this);
		}
		return portInsts.put(portInst.getFullName(), portInst);
	}
	
	public EDIFPortInst createPortInst(EDIFPort port){
		return new EDIFPortInst(port, this, null);
	}
	
	public EDIFPortInst createPortInst(EDIFPort port, int index){
		return new EDIFPortInst(port, this, index, null);
	}
	
	public EDIFPortInst createPortInst(String portName, EDIFCellInst cellInst){
		EDIFPort port = cellInst.getPort(portName);
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
	
	public Map<String,EDIFPortInst> getPortInstMap(){
		return portInsts == null ? Collections.emptyMap() : portInsts;
	}
	
	public Collection<EDIFPortInst> getPortInsts(){
		return portInsts == null ? Collections.emptyList() : portInsts.values();
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
	
	public EDIFPortInst getPortInst(String fullName){
		return portInsts == null ? null : portInsts.get(fullName);
	}

	public EDIFPortInst removePortInst(EDIFPortInst portInst){
		return removePortInst(portInst.getFullName()); 
	}
	
	public EDIFPortInst removePortInst(String portInstFullName){
		EDIFPortInst tmp = portInsts.remove(portInstFullName);
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

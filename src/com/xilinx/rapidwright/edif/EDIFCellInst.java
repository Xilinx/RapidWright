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

/**
 * A cell instance in a logical (EDIF) netlist.  Instantiates
 * an {@link EDIFCell}.
 * 
 * Created on: May 11, 2017
 */
public class EDIFCellInst extends EDIFPropertyObject implements EDIFEnumerable {
    
    private EDIFCell parentCell;
    
    private EDIFCell cellType;
    
    private EDIFName viewref;
    
    public static final EDIFName DEFAULT_VIEWREF = EDIFCell.DEFAULT_VIEW;

	public static final String BLACK_BOX_PROP = "IS_IMPORTED";
	public static final String BLACK_BOX_PROP_VERSAL = "black_box";
	
	private List<EDIFPortInst> portInsts;

    protected EDIFCellInst(){
        
    }
    
    public EDIFCellInst(String name, EDIFCell cellType, EDIFCell parentCell){
        super(name);
        setCellType(cellType);
        if(parentCell != null) parentCell.addCellInst(this);
        viewref = cellType != null ? cellType.getEDIFView() : DEFAULT_VIEWREF;
    }
    
    /**
     * Copy constructor.  Creates new objects except portInsts. 
     * @param inst Prototype instance to copy 
     */
    public EDIFCellInst(EDIFCellInst inst, EDIFCell parentCell) {
        super((EDIFPropertyObject)inst);
        this.parentCell = parentCell;
        this.cellType = inst.cellType;
        this.viewref = new EDIFName(inst.viewref);
    }
    
    /**
     * @return the viewref
     */
    public EDIFName getViewref() {
        return viewref;
    }

    /**
     * @param viewref the viewref to set
     */
    public void setViewref(EDIFName viewref) {
        this.viewref = viewref;
    }
    
    /**
     * Helper method to help maintain port ref map.  Adds a new
     * port ref for this instance.
     * @param epr The port ref to add
     * @return Any previous port ref of the same name, null if none already exists.
     */
    protected void addPortInst(EDIFPortInst epr) {
        if(portInsts == null) portInsts = new ArrayList<>();
        if(!epr.getCellInst().equals(this)) 
            throw new RuntimeException("ERROR: Incorrect EDIFPortInst '"+
                epr.getFullName()+"' being added to EDIFCellInst " + toString());
        portInsts.add(epr);
    }
    
    /**
     * Removes the provided port ref, if it exists.
     * @param epr The port ref to remove.
     * @return The removed port ref, or null if none exists.
     */
    protected EDIFPortInst removePortInst(EDIFPortInst epr){
        if(portInsts == null) return null;
        for(int i=0; i < portInsts.size(); i++) {
            if(portInsts.get(i).equals(epr)) {
                return portInsts.remove(i);
            }
        }
        return null;
    }
    
    /**
     * Gets the port ref on this cell by pin name (not full
     * port ref name).  
     * @param name Name of the pin in the port ref to get. 
     * @return A port ref by pin name.
     */
    public EDIFPortInst getPortInst(String name){
        for(int i=0; i < portInsts.size(); i++) {
            EDIFPortInst pi = portInsts.get(i);
            if(pi.getName().equals(name)) {
                return pi;
            }
        }
        return null;
    }
    
    /**
     * Gets the port on the underlying cell type.  It is the same as 
     * calling getCellType().getPort(name).
     * @param name Name of the port to get.
     * @return The port on the underlying cell type.
     */
    public EDIFPort getPort(String name){
        return getCellType().getPort(name);
    }
    
    public Collection<EDIFPortInst> getPortInsts(){
        return portInsts;
    }
    
    /**
     * @return the parentCell
     */
    public EDIFCell getParentCell() {
        return parentCell;
    }

    /**
     * @param parent the parentCell to set
     */
    public void setParentCell(EDIFCell parent) {
        this.parentCell = parent;
    }

    /**
     * @return the cellType
     */
    public EDIFCell getCellType() {
        return cellType;
    }

    public Collection<EDIFPort> getCellPorts(){
        return cellType.getPorts();
    }
    
    public String getCellName(){
        return cellType.getName();
    }

    /**
     * @param cellType the cellType to set
     */
    public void setCellType(EDIFCell cellType) {
        this.cellType = cellType;
        this.viewref = cellType != null ? cellType.getEDIFView() : null;
    }
    
    public void updateCellType(EDIFCell cellType) {
        setCellType(cellType);
        for(EDIFPortInst portInst : getPortInsts()) {
            EDIFPort origPort = portInst.getPort();
            EDIFPort port = cellType.getPort(origPort.getBusName());
            if(port == null || port.getWidth() != origPort.getWidth()) {
                port = cellType.getPort(origPort.getName());
            }
            portInst.setPort(port);
        }
    }
	
	public boolean isBlackBox(){
		EDIFPropertyValue val = getProperty(BLACK_BOX_PROP);
		if(val != null && val.getValue().toLowerCase().equals("true")) 
			return true;
		val = getProperty(BLACK_BOX_PROP_VERSAL);
		if(val != null && val.getValue().toLowerCase().equals("1")) 
			return true;
        return false;
    }

    public void exportEDIF(Writer wr) throws IOException{
        wr.write("         (instance ");
        exportEDIFName(wr);
        wr.write(" (viewref ");
        wr.write( getViewref().getLegalEDIFName());
        wr.write(" (cellref ");
        wr.write(cellType.getLegalEDIFName());
        wr.write(" (libraryref ");
        wr.write(cellType.getLibrary().getLegalEDIFName());
        if(getProperties().size() > 0){
            wr.write(")))\n");
            exportEDIFProperties(wr, "           ");
            wr.write("         )\n");               
        }else{
            wr.write("))))\n");
        }
    }

    @Override
    public String getUniqueKey() {
        return getCellType().getUniqueKey() + "_" + getParentCell().getUniqueKey() + "_" + getName();
    }
}

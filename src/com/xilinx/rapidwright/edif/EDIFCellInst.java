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
import java.util.HashMap;
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
	
	private EDIFPortInstList portInsts;

    protected EDIFCellInst(){
        
    }
    
    public EDIFCellInst(String name, EDIFCell cellType, EDIFCell parentCell){
        super(name);
        setCellType(cellType);
        if(parentCell != null) parentCell.addCellInst(this);
        setViewref(cellType != null ? cellType.getEDIFView() : DEFAULT_VIEWREF);
    }
    
    /**
     * Copy constructor.  Creates new objects except portInsts. 
     * @param inst Prototype instance to copy 
     */
    public EDIFCellInst(EDIFCellInst inst, EDIFCell parentCell) {
        super((EDIFPropertyObject)inst);
        this.parentCell = parentCell;
        this.cellType = inst.cellType;
        setViewref(new EDIFName(inst.viewref));
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
        this.viewref = EDIFCell.DEFAULT_VIEW.equals(viewref) ? EDIFCell.DEFAULT_VIEW : viewref;
    }
    
    /**
     * Creates a new map of all the EDIFPortInst objects stored on this EDIFCellInst.  The new map
     * contains a copy of EDIFPortInsts available at the time of invocation as returned from 
     * {@link #getPortInstList()}.      
     * @return A map of EDIFPortInst names ({@link EDIFPortInst#getName()} to the corresponding objects.
     * @deprecated
     */
    public Map<String, EDIFPortInst> getPortInstMap(){
        if(portInsts == null) return Collections.emptyMap();
        HashMap<String, EDIFPortInst> map = new HashMap<>();
        for(EDIFPortInst e : getPortInsts()) {
            map.put(e.getName(), e);
        }
        return map;
    }
    
    /**
     * Adds a new EDIFPortInst to this cell instance. The port instances
     * are stored in a sorted ArrayList, so worst case is O(n).
     * @param epr The port instance to add
     */
    protected void addPortInst(EDIFPortInst epr) {
        if(portInsts == null) portInsts = new EDIFPortInstList();
        if(!epr.getCellInst().equals(this)) 
            throw new RuntimeException("ERROR: Incorrect EDIFPortInst '"+
                epr.getFullName()+"' being added to EDIFCellInst " + toString());
        portInsts.add(epr);
    }
    
    /**
     * Removes the provided port instance from the cell instance, if it exists.  The port instances
     * are stored in a sorted ArrayList, so worst case is O(n).
     * @param epr The port instance object to remove
     * @return The removed port instance, or null if it was not found.
     */
    protected EDIFPortInst removePortInst(EDIFPortInst epr){
        if(portInsts == null) return null;
        return portInsts.remove(epr);
    }
    
    /**
     * Removes the named port instance from the cell instance, if it exists. The port instances
     * are stored in a sorted ArrayList, so worst case is O(n).
     * @param portName Name of the port ref to remove ({@link EDIFPortInst#getName()})
     * @return The removed port instance, or null if none found by that name.
     */
    protected EDIFPortInst removePortInst(String portName){
        if(portInsts == null) return null;
        return portInsts.remove(this, portName);
    }
    
    /**
     * Gets the port instance on this cell by pin name ({@link EDIFPortInst#getName()}). The port 
     * instances are stored in a sorted ArrayList, so worst case is O(log n).
     * @param name Name of the port instance to get. 
     * @return The named port instance, or null if none found by that name. 
     */
    public EDIFPortInst getPortInst(String name){
        if(portInsts == null) return null;
        return portInsts.get(this, name);
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
    
    /**
     * Gets the sorted ArrayList of EDIFPortInsts on this cell instance as a collection.
     * @return The collection of EDIFPortInsts on this cell.
     */
    public Collection<EDIFPortInst> getPortInsts(){
        return portInsts == null ? Collections.emptyList() : portInsts;
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
     * Forcibly modify the cell being instantiated without updating portInsts
     * @param cellType the cellType to set
     */
    public void setCellTypeRaw(EDIFCell cellType) {
        this.cellType = cellType;
        setViewref(cellType != null ? cellType.getEDIFView() : null);
    }

    /**
     * Modify the cell being instantiated and update port refs on portInsts
     * @param cellType the cellType to set
     */
    public void setCellType(EDIFCell cellType) {
        setCellTypeRaw(cellType);
        for(EDIFPortInst portInst : getPortInsts()) {
            EDIFPort origPort = portInst.getPort();
            EDIFPort port = cellType.getPort(origPort.getBusName());
            if(port == null || port.getWidth() != origPort.getWidth()) {
                port = cellType.getPort(origPort.getName());
            }
            portInst.setPort(port);
        }
    }

    /**
     * @deprecated
     */
    public void updateCellType(EDIFCell cellType) {
        setCellType(cellType);
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

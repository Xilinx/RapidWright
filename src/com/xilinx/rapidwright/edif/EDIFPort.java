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
import java.util.List;

/**
 * Represents a port on an {@link EDIFCell} within an EDIF netlist.
 * Created on: May 11, 2017
 */
public class EDIFPort extends EDIFPropertyObject implements EDIFEnumerable {

	private EDIFCell parentCell;
	
	private EDIFDirection direction;
	
	private int width = 1;

	private boolean isLittleEndian = true;
	
	private String busName;
	
	public EDIFPort(String name, EDIFDirection direction, int width){
		super(name);
		setDirection(direction);
		setWidth(width);
		setIsLittleEndian();
	}
	
	/**
     * Copy Constructor
     * @param port
     */
    public EDIFPort(EDIFPort port) {
        super((EDIFPropertyObject)port);
        this.direction = port.direction;
        this.width = port.width;
        this.isLittleEndian = port.isLittleEndian;
        this.busName = port.busName;
    }
	
	protected EDIFPort(){
		
	}
	
	/**
	 * @return the direction
	 */
	public EDIFDirection getDirection() {
		return direction;
	}

	/**
	 * If this port is a bus, it describes the endian-ness of
	 * how the bits in the bus vector are arranged.  If the bus is
	 * little endian, LSB (least significant bit) is the rightmost bit 
	 * (bus[7:0]).  If the bus is not little endian (big endian), LSB is the
	 * left most bit (bus[0:7]).
	 * @return True if this bus is little endian, false otherwise. Not 
	 * applicable for single bit ports.   
	 */
	public boolean isLittleEndian(){
		return isLittleEndian;
	}
	
	protected void setIsLittleEndian(){
		if(width == 1) return;
		String name = getName();
		if(name.charAt(name.length()-1) != ']' || !Character.isDigit(name.charAt(name.length()-2))){
			throw new RuntimeException("ERROR: Port " + getName() + " does not have proper bus suffix");
		}
		int colonIdx = -1;
		int leftBracket = -1;
		for(int i=name.length()-3; i >= 0; i--){
			char c = name.charAt(i);
			if(c == ':') colonIdx = i;
			else if(c == '[') {
				leftBracket = i;
				break;
			}
		}
		if(colonIdx == -1 || leftBracket == -1){
			throw new RuntimeException("ERROR: Interpreting port " + getName() + ", couldn't identify indicies.");
		}
		
		int left = Integer.parseInt(name.substring(leftBracket+1, colonIdx));
		int right = Integer.parseInt(name.substring(colonIdx+1, name.length()-1));
		isLittleEndian = left > right;
	}
	
	public boolean isOutput(){
		return direction == EDIFDirection.OUTPUT;
	}
	
	public boolean isInput(){
		return direction == EDIFDirection.INPUT;
	}
	
	/**
	 * @param direction the direction to set
	 */
	public void setDirection(EDIFDirection direction) {
		this.direction = direction;
	}

	/**
	 * @return the width
	 */
	public int getWidth() {
		return width;
	}
	
	/**
	 * @param width the width to set
	 */
	public void setWidth(int width) {
		this.width = width;
	}
	
	public String getBusName(){
		if(busName == null){
			int idx = EDIFTools.lengthOfNameWithoutBus(getName().toCharArray());
			busName = getName().substring(0, idx);						
		}
		return busName;
	}
	
	public String getStemName(){
		int leftBracket = getName().indexOf('[');
		return leftBracket == -1 ? getName() : getName().substring(0, leftBracket); 
	}
	
	public Integer getLeft(){
		if(!isBus()) return null;
		int leftBracket = getName().lastIndexOf('[');
		int colon = getName().lastIndexOf(':');
		int value = Integer.parseInt(getName().substring(leftBracket+1,colon)); 
		return value;
	}
	

	public Integer getRight(){
		if(!isBus()) return null;
		int rightBracket = getName().lastIndexOf(']');
		int colon = getName().lastIndexOf(':');
		int value = Integer.parseInt(getName().substring(colon+1, rightBracket)); 
		return value;
	}
	
	/**
	 * Gets the list of internal nets connected to this port, indexed in the same way as the port.
	 * A single bit port will only have one entry in the list, for example. 
	 * @return The list of internal nets connected to this port.  If one or more bits is not 
	 * connected (such as in a primitive cell), a null entry will be present at the corresponding 
	 * index.
	 */
	public List<EDIFNet> getInternalNets(){
	    List<EDIFNet> nets = new ArrayList<>(width);
	    for(int i=0; i < width; i++) {
	        nets.add(getInternalNet(i));
	    }
	    return nets;
	}
	
	/**
	 * Gets the internal net connected to this port at specified index of the port,
	 * connected to the inside of the cell.  
	 * @param index Index of port (0 if it is a single bit) 
	 * @return The internal net connect to this port or null if not connect (such as a primitive).
	 */
	public EDIFNet getInternalNet(int index) {
	    return parentCell.getInternalNet(getPortInstNameFromPort(index));
	}
	
	/**
	 * If the port is only one bit wide or the user only needs the 0th indexed net, this is
	 * a convenience method to getting the internal net (inside the cell) connected to this port.  
	 * @return The internal net connected to this port (single bit or 0th index only), 
	 * or null if not connected (such as a primitive).
	 */
	public EDIFNet getInternalNet() {
	    return getInternalNet(0);
	}
	
	/**
	 * Gets the PortInst name from this port and the specified index
	 * @param index The index to get from the port 
	 * @return The name of the PortInst for the specified index
	 */
	public String getPortInstNameFromPort(int index) {
	    if(!isBus()) return getBusName();
	    if(isLittleEndian()){
	        index = (getWidth()-1) - index;
	    }
	    return getBusName() + "[" + index + "]";     
	}
	
	public void exportEDIF(Writer wr, String indent) throws IOException{
		wr.write(indent);
		wr.write("(port ");
		if(width > 1) wr.write("(array ");
		exportEDIFName(wr);
		if(width > 1) wr.write(" " + width + ")");
		wr.write(" (direction ");
		wr.write(direction.toString());
		wr.write(")");
		if(getProperties().size() > 0){
			wr.write("\n");
			exportEDIFProperties(wr, indent+"   ");
			wr.write(indent);
		}
		wr.write(")\n");
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
		parentCell.trackChange(EDIFChangeType.PORT_ADD, getName());
	}

	/**
	 * @return
	 */
	public boolean isBus() {
        return width > 1 || !getName().equals(getBusName());
	}
	
	public int[] getBitBlastedIndicies(){
		int lastLeftBracket = getName().lastIndexOf('[');
		if(getName().contains(":")) 
			return EDIFTools.bitBlastBus(getName().substring(lastLeftBracket));
		if(getName().contains("["))
			return new int[] {Integer.parseInt(getName().substring(lastLeftBracket,getName().length()-1))};
		return null;
	}

    @Override
    public String getUniqueKey() {
        return parentCell.getUniqueKey() + "_" + getName();
    }
    
    public boolean isBusRangeEqual(EDIFPort otherPort) {
        String name = getName();
        int leftBracket = name.lastIndexOf('[');
        int len = name.length()-leftBracket;
        String otherName = otherPort.getName();
        int otherLeftBracket = otherName.lastIndexOf('[');
        if(leftBracket == -1 && otherLeftBracket == -1) return true;
        return name.regionMatches(leftBracket, otherName, otherLeftBracket, len);
    }
}


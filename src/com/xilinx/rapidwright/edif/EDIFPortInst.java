/*
 *
 * Copyright (c) 2017-2022, Xilinx, Inc.
 * Copyright (c) 2022-2023, Advanced Micro Devices, Inc.
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
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

/**
 * Represents an instance of a port on an {@link EDIFCellInst}.
 * Created on: May 11, 2017
 */
public class EDIFPortInst implements Comparable<EDIFPortInst> {

    private String name;

    private EDIFPort port;

    private EDIFNet parentNet;

    private int index = -1;

    private EDIFCellInst cellInst;

    public EDIFPortInst(EDIFPort port, EDIFNet parentNet) {
        this(port, parentNet, -1, null);
    }

    /**
     * Copy constructor
     * @param portInst
     */
    public EDIFPortInst(EDIFPortInst portInst) {
        this.name = portInst.name;
        this.port = null;
        this.parentNet = null;
        this.index = portInst.index;
        this.cellInst = null;
    }

    public EDIFPortInst(EDIFPort port, EDIFNet parentNet, int index) {
        this(port,parentNet,index,null);
    }

    /**
     * Constructor to create a single bit port ref and associate it with its
     * net and instance.
     * @param port The port on the cell this port ref uses
     * @param parentNet The net this port ref should belong to
     * @param cellInst The instance this port ref belongs to
     */
    public EDIFPortInst(EDIFPort port, EDIFNet parentNet, EDIFCellInst cellInst) {
        this(port, parentNet, -1, cellInst, false);
    }

    /**
     * Constructor to create a single bit port ref and associate it with its
     * net and instance.
     * @param port The port on the cell this port ref uses
     * @param parentNet The net this port ref should belong to
     * @param cellInst The instance this port ref belongs to
     * @param deferSort The EDIFPortInstList maintains a sorted list of EDIFPortInst
     * objects and sorts them upon insertion.  Setting this flag to true will skip a sort addition
     * but the caller is responsible to conclude a batch of additions with a call to
     * {@link EDIFPortInstList#reSortList()}.  This is useful when a large number of EDIFPortInsts
     * will be added consecutively (such as parsing a netlist).
     */
    public EDIFPortInst(EDIFPort port, EDIFNet parentNet, EDIFCellInst cellInst, boolean deferSort) {
        this(port, parentNet, -1, cellInst, deferSort);
    }

    /**
     * Constructor to create a new port ref on the provided instance and connect
     * it to the provided net
     * @param port The port on the cell this port ref uses
     * @param parentNet The net this port ref should be added to
     * @param index For port refs part of a bussed port, this is the index into
     * the bussed array, for single bit ports, it should be -1.
     * @param cellInst This instance on which this port ref corresponds.
     */
    public EDIFPortInst(EDIFPort port, EDIFNet parentNet, int index, EDIFCellInst cellInst) {
        this(port, parentNet, index, cellInst, false);
    }

    /**
     * Constructor to create a new port ref on the provided instance and connect
     * it to the provided net
     * @param port The port on the cell this port ref uses
     * @param parentNet The net this port ref should be added to
     * @param index For port refs part of a bussed port, this is the index into
     * the bussed array, for single bit ports, it should be -1.
     * @param cellInst This instance on which this port ref corresponds.
     * @param deferSort The EDIFPortInstList maintains a sorted list of EDIFPortInst 
     * objects and sorts them upon insertion.  Setting this flag to true will skip a sort addition
     * but the caller is responsible to conclude a batch of additions with a call to 
     * {@link EDIFPortInstList#reSortList()}.  This is useful when a large number of EDIFPortInsts 
     * will be added consecutively (such as parsing a netlist).
     */
    public EDIFPortInst(EDIFPort port, EDIFNet parentNet, int index, EDIFCellInst cellInst,
            boolean deferSort) {
        if (index == -1 && port.isBus()) {
            throw new RuntimeException("ERROR: Use a different constructor, "
                    + "need index for bussed port " + port.getName());
        }
        if (cellInst != null) {
            if (!port.equals(cellInst.getPort(port.getBusName(true)))) {
                // check for name collision
                if (!port.equals(cellInst.getPort(port.getName()))) {
                    throw new RuntimeException("ERROR: Provided port '"+
                            port.getName() + "' does not exist on EDIFCell type '" +
                            cellInst.getCellType().getName() + "' when adding port "
                            + "ref to instance '" + cellInst.getName() + "'.");
                }
            }
        }
        this.index = index;
        this.port = port;
        this.name = getPortInstNameFromPort();
        setCellInst(cellInst, deferSort);
        if (parentNet != null)
            parentNet.addPortInst(this, deferSort);
    }

    protected EDIFPortInst() {

    }

    /**
     * Creates a new port instance without connecting it to a net.
     * 
     * @param portInstName Name of the port instance
     * @param inst         The instance on which to create the new port instance
     * @return The new port instance.
     */
    public static EDIFPortInst createPortInstFromPortInstName(String portInstName, EDIFCellInst inst) {
        EDIFPort port = inst.getCellType().getPortByPortInstName(portInstName);
        if (port == null)
            return null;
        int portIdx = -1;
        if (port.isBus()) {
            int idx = EDIFTools.getPortIndexFromName(portInstName);
            portIdx = port.getPortIndexFromNameIndex(idx);
        }
        return new EDIFPortInst(port, null, portIdx, inst, false);
    }

    public String getPortInstNameFromPort() {
        return port.getPortInstNameFromPort(index);
    }

    public String getName() {
        return name;
    }

    protected void setName(String name) {
        this.name = name;
    }

    /**
     * @return the index
     */
    public int getIndex() {
        return index;
    }

    /**
     * @param index the index to set
     */
    public void setIndex(int index) {
        this.index = index;
    }

    public EDIFCell getParentCell() {
        return parentNet.getParentCell();
    }


    /**
     * @return the cellInst
     */
    public EDIFCellInst getCellInst() {
        return cellInst;
    }

    /**
     * @param cellInst the cellInst to set
     */
    public void setCellInst(EDIFCellInst cellInst) {
        setCellInst(cellInst, false);
    }

    /**
     * Sets the corresponding cell instance for this port instance.
     * @param cellInst the cellInst to set
     * @param deferSort The EDIFPortInstList maintains a sorted list of EDIFPortInst 
     * objects and sorts them upon insertion.  Setting this flag to true will skip a sort addition
     * but the caller is responsible for conclude a batch of additions with a call to 
     * {@link EDIFPortInstList#reSortList()}.  This is useful when a large number of EDIFPortInsts 
     * will be added consecutively (such as parsing a netlist).
     */
    public void setCellInst(EDIFCellInst cellInst, boolean deferSort) {
        if (this.cellInst != null && !deferSort) {
            this.cellInst.removePortInst(this);
        }
        this.cellInst = cellInst;
        if (cellInst != null) {
            cellInst.addPortInst(this, deferSort);
        }
    }

    protected void setCellInstRaw(EDIFCellInst cellInst) {
        this.cellInst = cellInst;
    }

    /**
     * Checks if this is an output of a GND or VCC primitive cell.
     * @return True if the underlying cell is a static output from GND or VCC, false otherwise.
     */
    public boolean isPrimitiveStaticSource() {
        if (cellInst == null) return false;
        String name = cellInst.getCellType().getName();
        if (name.equals("GND") || name.equals("VCC")) return true;
        return false;
    }

    public String getFullName() {
        String fullName = getName();
        if (port == null && index != -1) {
            // This is a special case only during parsing that needs to be
            // added to avoid name collisions in the EDIFNet portInsts map.
            fullName = fullName + "[" + index + "]";
        }
        if (getCellInst() == null) return fullName;
        return getCellInst().getName() + EDIFTools.EDIF_HIER_SEP + fullName;
    }

    public EDIFPort getPort() {
        return port;
    }

    public void setPort(EDIFPort port) {
        this.port = port;
    }

    public EDIFDirection getDirection() {
        return getPort().getDirection();
    }

    public boolean isOutput() {
        return getDirection() == EDIFDirection.OUTPUT;
    }

    public boolean isInput() {
        return getDirection() == EDIFDirection.INPUT;
    }

    public boolean isTopLevelPort() {
        return getCellInst() == null;
    }

    /**
     * @return the parentNet
     */
    public EDIFNet getNet() {
        return parentNet;
    }

    public EDIFNet getInternalNet() {
        if (cellInst == null) return null;
        return cellInst.getCellType().getInternalNet(this);
    }

    /**
     * @param parentNet the parentNet to set
     */
    public void setParentNet(EDIFNet parentNet) {
        this.parentNet = parentNet;
    }

    public static final byte[] EXPORT_CONST_PORTREF = "(portref ".getBytes(StandardCharsets.UTF_8);
    public static final byte[] EXPORT_CONST_MEMBER = "(member ".getBytes(StandardCharsets.UTF_8);
    public static final byte[] EXPORT_CONST_INSTANCEREF = " (instanceref ".getBytes(StandardCharsets.UTF_8);
    public static final byte[] EXPORT_CONST_CLOSE_PORT_INST = ")\n".getBytes(StandardCharsets.UTF_8);

    public void writeEDIFExport(OutputStream os, byte[] indent, EDIFWriteLegalNameCache<?> cache) throws IOException{
        os.write(indent);
        os.write(EXPORT_CONST_PORTREF);
        if (index == -1) {
             os.write(cache.getLegalEDIFName(getPort().getName()));
        }
        else {
            os.write(EXPORT_CONST_MEMBER);
            os.write(getPort().getBusEDIFRename(cache));
            os.write(' ');
            os.write(Integer.toString(index).getBytes(StandardCharsets.UTF_8));
            os.write(')');
        }
        if (getCellInst() != null) {
            os.write(EXPORT_CONST_INSTANCEREF);
            os.write(cache.getLegalEDIFName(getCellInst().getName()));
            os.write(')');
        }
        os.write(EXPORT_CONST_CLOSE_PORT_INST);
    }

    /* (non-Javadoc)
     * @see java.lang.Object#hashCode()
     */
    @Override
    public int hashCode() {
        final int prime = 31;
        int result = 1;
        result = prime * result + ((cellInst == null) ? 0 : cellInst.hashCode());
        result = prime * result + index;
        result = prime * result + ((name == null) ? 0 : name.hashCode());
        return result;
    }

    /* (non-Javadoc)
     * @see java.lang.Object#equals(java.lang.Object)
     */
    @Override
    public boolean equals(Object obj) {
        if (this == obj)
            return true;
        if (obj == null)
            return false;
        if (getClass() != obj.getClass())
            return false;
        EDIFPortInst other = (EDIFPortInst) obj;
        if (cellInst == null) {
            if (other.cellInst != null)
                return false;
        } else if (!cellInst.equals(other.cellInst))
            return false;
        if (index != other.index)
            return false;
        if (name == null) {
            if (other.name != null)
                return false;
        } else if (!name.equals(other.name))
            return false;
        return true;
    }

    public String toString() {
        if (cellInst == null) return name;
        return cellInst.getName() + EDIFTools.EDIF_HIER_SEP + name;
    }

    @Override
    public int compareTo(EDIFPortInst o) {
        EDIFCellInst cellInst = o.getCellInst();
        String cellInstName = cellInst == null ? null : cellInst.getName();
        String portInstName = o.getName();
        return EDIFPortInstList.compare(this, cellInstName, portInstName);
    }
}

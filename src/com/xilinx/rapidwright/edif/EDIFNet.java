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
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

import com.xilinx.rapidwright.design.Cell;
import com.xilinx.rapidwright.design.Unisim;

/**
 * Represents a net within an EDIF netlist.
 *
 * Created on: May 11, 2017
 */
public class EDIFNet extends EDIFPropertyObject {

    private EDIFCell parentCell;

    private EDIFPortInstList portInsts;

    public EDIFNet(String name, EDIFCell parentCell) {
        super(name);
        if (parentCell != null) parentCell.addNet(this);
    }

    /**
     * Copy constructor, does not copy portInsts
     * @param net
     */
    public EDIFNet(EDIFNet net) {
        super((EDIFPropertyObject) net);
    }

    protected EDIFNet() {

    }

    /**
     * Adds the EDIFPortInst to this logical net.  The net stores the port instances using a sorted
     * ArrayList (@link EDIFPortInstList).  Worst case O(n) to add.
     * @param portInst The port instance to add to this net.
     */
    public void addPortInst(EDIFPortInst portInst) {
        addPortInst(portInst, false);
    }

    /**
     * Adds the EDIFPortInst to this logical net. The net stores the port instances
     * using a sorted ArrayList (@link EDIFPortInstList). Worst case O(n) to add.
     * 
     * @param portInst The port instance to add to this net.
     * @param deferSort The EDIFPortInstList maintains a sorted list of EDIFPortInst 
     * objects and sorts them upon insertion.  Setting this flag to true will skip a sort addition
     * but the caller is responsible to conclude a batch of additions with a call to 
     * {@link EDIFPortInstList#reSortList()}.  This is useful when a large number of EDIFPortInsts 
     * will be added consecutively (such as parsing a netlist).
     */
    public void addPortInst(EDIFPortInst portInst, boolean deferSort) {
        if (portInsts == null) portInsts = new EDIFPortInstList();
        boolean isParentCellNonNull = parentCell != null;
        EDIFCellInst inst = portInst.getCellInst();
        if (isParentCellNonNull && inst == null) {
            parentCell.addInternalPortMapEntry(portInst.getName(), this);
        }
        portInst.setParentNet(this);
        if (isParentCellNonNull) {
            // This does not explicitly track the port instance index, in most cases the name should be sufficient.
            trackChanges(EDIFChangeType.PORT_INST_ADD, inst, portInst.getName());
        }
        if (deferSort) {
            portInsts.deferSortAdd(portInst);
        } else {
            boolean added = portInsts.add(portInst);
            assert(added);
        }
    }

    public void trackChanges(EDIFChangeType type, EDIFCellInst inst, String portInstName) {
        EDIFNetlist netlist = parentCell.getNetlist();
        if (netlist != null && netlist.isTrackingCellChanges()) {
            String instName = inst == null ? null : inst.getName();
            EDIFChangeNet change = new EDIFChangeNet(type, portInstName, getName(), instName);
            netlist.addTrackingChange(parentCell, change);
        }
    }

    public EDIFPortInst createPortInst(EDIFPort port) {
        return new EDIFPortInst(port, this, null);
    }

    public EDIFPortInst createPortInst(EDIFPort port, int index) {
        return new EDIFPortInst(port, this, index, null);
    }

    /**
     * Creates a new port instance from a name on the external port of the provided
     * cell instance. It looks up the appropriate port name from the portInstName
     * and identifies the index if any.
     * 
     * @param portInstName The name of the new port instance, including indexed bit
     *                     if it belongs on a bussed port.
     * @param cellInst     The destination cell instance to receive the port
     *                     instance
     * @return The newly created port instance or null if none could be created on
     *         the instance.
     */
    public EDIFPortInst createPortInst(String portInstName, EDIFCellInst cellInst) {
        return createPortInstFromPortInstName(portInstName, cellInst.getCellType(), cellInst);
    }

    public EDIFPortInst createPortInst(String portInstName, EDIFCellInst cellInst, boolean deferSort) {
        return createPortInstFromPortInstName(portInstName, cellInst.getCellType(), cellInst, deferSort);
    }
    
    /**
     * Creates a new port instance from a name on the internal port of the provided
     * cell. It looks up the appropriate port name from the portInstName and
     * identifies the index if any.
     * 
     * @param portInstName The name of the new port instance, including indexed bit
     *                     if it belongs on a bussed port.
     * @param cell         The destination cell to receive the port instance (on an
     *                     inward facing port).
     * @return The newly created port instance or null if none could be created on
     *         the cell.
     */
    public EDIFPortInst createPortInst(String portInstName, EDIFCell cell) {
        return createPortInstFromPortInstName(portInstName, cell, null);
    }

    /**
     * Creates a port instance from a name. Navigates port naming issues when bussed
     * names can collide with single bit port names.
     * 
     * @param portInstName Proposed name of the new port instance
     * @param cell         The cell from which to draw the port
     * @param inst         If this is not null, the port instance is added to the
     *                     external facing port connection. If this is null, it will
     *                     add it to the inward facing port connection.
     * @return The newly created port instance or null if none could be created on
     *         the cell or cell instance.
     */
    public EDIFPortInst createPortInstFromPortInstName(String portInstName, EDIFCell cell, EDIFCellInst inst) {
        return createPortInstFromPortInstName(portInstName, cell, inst, false);
    }

    /**
     * Creates a port instance from a name. Navigates port naming issues when bussed
     * names can collide with single bit port names.
     * 
     * @param portInstName Proposed name of the new port instance
     * @param cell         The cell from which to draw the port
     * @param inst         If this is not null, the port instance is added to the
     *                     external facing port connection. If this is null, it will
     *                     add it to the inward facing port connection.
     * @param deferSort    The EDIFPortInstList maintains a sorted list of EDIFPortInst 
     *                     objects and sorts them upon insertion.  Setting this flag to 
     *                     true will skip a sort addition but the caller is responsible 
     *                     to conclude a batch of additions with a call to 
     *                     {@link EDIFPortInstList#reSortList()}.  This is useful when 
     *                     a large number of EDIFPortInsts.
     * @return The newly created port instance or null if none could be created on
     *         the cell or cell instance.
     */
    public EDIFPortInst createPortInstFromPortInstName(String portInstName, EDIFCell cell,
            EDIFCellInst inst, boolean deferSort) {
        EDIFPort port = cell.getPortByPortInstName(portInstName);
        if (port == null) return null;
        int portIdx = -1;
        if (port.isBus()) {
            int idx = EDIFTools.getPortIndexFromName(portInstName);
            portIdx = port.getPortIndexFromNameIndex(idx);
        }
        return new EDIFPortInst(port, this, portIdx, inst, deferSort);
    }

    public EDIFPortInst createPortInst(String portName, int index, EDIFCellInst cellInst) {
        EDIFPort port = cellInst.getPort(portName);
        return new EDIFPortInst(port, this, index, cellInst);
    }

    public EDIFPortInst createPortInst(String portName, Cell cell) {
        EDIFCellInst cellInst = cell.getEDIFCellInst();
        return createPortInst(portName,cellInst);
    }

    public EDIFPortInst createPortInst(String portName, int index, Cell cell) {
        EDIFCellInst cellInst = cell.getEDIFCellInst();
        return createPortInst(portName,index,cellInst);
    }


    public EDIFPortInst createPortInst(EDIFPort port, EDIFCellInst cellInst) {
        return new EDIFPortInst(port, this, cellInst);
    }

    public EDIFPortInst createPortInst(EDIFPort port, EDIFCellInst cellInst, boolean deferSort) {
        return new EDIFPortInst(port, this, cellInst, deferSort);
    }

    public EDIFPortInst createPortInst(EDIFPort port, int index, EDIFCellInst cellInst) {
        return new EDIFPortInst(port, this, index, cellInst);
    }
    
    public EDIFPortInst createPortInst(EDIFPort port, int index, EDIFCellInst cellInst, boolean deferSort) {
        return new EDIFPortInst(port, this, index, cellInst, deferSort);
    }

    /**
     * Gets the sorted ArrayList of EDIFPortInsts on this net as a collection.
     * @return The collection of EDIFPortInsts on this net.
     */
    public Collection<EDIFPortInst> getPortInsts() {
        return portInsts == null ? Collections.emptyList() : portInsts;
    }

    public void rename(String newName) {
        this.parentCell.removeNet(this);
        setName(newName);
        this.parentCell.addNet(this);
    }

    /**
     * This returns all sources on the net, either output ports of the
     * cell instances in the cell or the top level input ports.
     * @return A list of port ref sources.
     */
    public List<EDIFPortInst> getSourcePortInsts(boolean includeTopLevelPorts) {
        List<EDIFPortInst> srcs = new ArrayList<>();
        for (EDIFPortInst portInst : getPortInsts()) {
            boolean includePort =
                (portInst.isOutput() && !portInst.isTopLevelPort()) ||
                (portInst.isInput() && portInst.isTopLevelPort() && includeTopLevelPorts);
            if (includePort) srcs.add(portInst);
        }
        return srcs;
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
    public EDIFPortInst getPortInst(EDIFCellInst inst, String portInstName) {
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
        for (EDIFPortInst portInst : getPortInsts()) {
            if (portInst.isTopLevelPort()) {
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
        for (EDIFPortInst portInst : getPortInsts()) {
            if (portInst.isTopLevelPort()) {
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
    public EDIFPortInst removePortInst(EDIFPortInst portInst) {
        return removePortInst(portInst.getCellInst(), portInst.getName());
    }

    /**
     * Removes the port instance specified from the net. The net stores the port instances using a
     * sorted ArrayList (@link EDIFPortInstList).  Worst case O(n) to remove.
     * @param inst The cell instance where the EDIFPortInst resides.  If this is null, it removes
     * the top level port instance connected to the parent cell port.
     * @param portInstName Name of the port instance ({@link EDIFPortInst#getName()} to remove
     * @return The port instance object that was removed or null if no changes were made.
     */
    public EDIFPortInst removePortInst(EDIFCellInst inst, String portInstName) {
        if (portInsts == null) return null;
        if (parentCell != null) {
            // This does not explicitly track the port instance index, in most cases the name should be sufficient.
            trackChanges(EDIFChangeType.PORT_INST_REMOVE, inst, portInstName);
        }
        EDIFPortInst tmp = portInsts.remove(inst, portInstName);
        if (tmp != null) tmp.setParentNet(null);
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
        parentCell.trackChange(EDIFChangeType.NET_ADD, getName());
    }

    protected EDIFPortInstList getEDIFPortInstList() {
        return portInsts;
    }

    /**
     * Checks if this net is a logical VCC net. It first checks if the net matches
     * the defacto name for VCC in Vivado synthesis, "\<const1\>"
     * ({@link EDIFTools#LOGICAL_VCC_NET_NAME}). If there is not match, it will also
     * check the source of the net to see if it is a VCC primitive.
     * 
     * @return True if this is a logical VCC net, false otherwise.
     */
    public boolean isVCC() {
        return checkIsStaticSource(EDIFTools.LOGICAL_VCC_NET_NAME, Unisim.VCC.name());
    }

    /**
     * Checks if this net is a logical GND net. It first checks if the net matches
     * the defacto name for GND in Vivado synthesis, "\<const0\>"
     * ({@link EDIFTools#LOGICAL_GND_NET_NAME}). If there is not match, it will also
     * check the source of the net to see if it is a GND primitive.
     * 
     * @return True if this is a logical GND net, false otherwise.
     */
    public boolean isGND() {
        return checkIsStaticSource(EDIFTools.LOGICAL_GND_NET_NAME, Unisim.GND.name());
    }

    /**
     * Helper method to {@link #isVCC()} and {@link #isGND()} by checking the net
     * name and source if it is the queried static source net.
     * 
     * @param name     Defacto Vivado static source net name (see
     *                 ({@link EDIFTools#LOGICAL_VCC_NET_NAME}) or
     *                 ({@link EDIFTools#LOGICAL_GND_NET_NAME})).
     * @param cellType This would be either 'VCC' or 'GND'.
     * @return True if it was determined that the static net query matched, false
     *         otherwise.
     */
    private boolean checkIsStaticSource(String name, String cellType) {
        // Rely on Vivado-standardized logical names
        if (!getName().equals(name)) {
            // Check source
            for (EDIFPortInst portInst : getSourcePortInsts(false)) {
                if (portInst.getCellInst().getCellType().getName().equals(cellType)) {
                    return true;
                }
            }
            return false;
        }
        return true;
    }

    /**
     * Checks if the net has all port instances (terminals) within the parent cell.
     * 
     * @return True if all port instances are internal to this net's parent cell,
     *         false otherwise. If the net has not port instances, it returns true.
     */
    public boolean isInternalToParent() {
        for (EDIFPortInst pi : getPortInsts()) {
            if (pi.isTopLevelPort()) return false;
        }
        return true;
    }
    

    public static final byte[] EXPORT_CONST_NET_START = "         (net ".getBytes(StandardCharsets.UTF_8);
    public static final byte[] EXPORT_CONST_JOINED = " (joined\n".getBytes(StandardCharsets.UTF_8);
    public static final byte[] EXPORT_CONST_PORT_INDENT = "          ".getBytes(StandardCharsets.UTF_8);
    public static final byte[] EXPORT_CONST_PROP_INDENT = "           ".getBytes(StandardCharsets.UTF_8);
    public static final byte[] EXPORT_CONST_JOINED_END = "          )\n".getBytes(StandardCharsets.UTF_8);
    public static final byte[] EXPORT_CONST_NET_END = "         )\n".getBytes(StandardCharsets.UTF_8);

    private static final Comparator<EDIFPortInst> edifPortInstComparator =
            Comparator.comparing((EDIFPortInst e)->e.getCellInst()!=null?e.getCellInst().getName():"")
                    .thenComparing(EDIFPortInst::getName);

    public void exportEDIF(OutputStream os, EDIFWriteLegalNameCache<?> cache, boolean stable) throws IOException {
        os.write(EXPORT_CONST_NET_START);
        exportEDIFName(os, cache);
        os.write(EXPORT_CONST_JOINED);
        for (EDIFPortInst p : EDIFTools.sortIfStable(getPortInsts(), edifPortInstComparator, stable)) {
            p.writeEDIFExport(os, EXPORT_CONST_PORT_INDENT, cache);
        }
        os.write(EXPORT_CONST_JOINED_END); // joined end
        if (getPropertiesMap().size() > 0) {
            os.write('\n');
            exportEDIFProperties(os, EXPORT_CONST_PROP_INDENT, cache, stable);
        }
        os.write(EXPORT_CONST_NET_END); // Nets end

    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj)
            return true;
        if (obj == null)
            return false;
        if (getClass() != obj.getClass())
            return false;
        EDIFNet other = (EDIFNet) obj;
        if (!parentCell.equals(other.parentCell))
            return false;
        return super.equals(obj);
    }
}

/*
 *
 * Copyright (c) 2017-2022, Xilinx, Inc.
 * Copyright (c) 2022, Advanced Micro Devices, Inc.
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

import java.util.List;
import java.util.Objects;

import com.xilinx.rapidwright.design.Cell;
import com.xilinx.rapidwright.design.Design;
import com.xilinx.rapidwright.design.SiteInst;
import com.xilinx.rapidwright.design.SitePinInst;
import com.xilinx.rapidwright.device.BELPin;
import com.xilinx.rapidwright.util.Pair;

import org.jetbrains.annotations.NotNull;

/**
 * Combines an {@link EDIFHierPortInst} with a full hierarchical
 * instance name to uniquely identify a port instance in a netlist.
 *
 * Created on: Sep 12, 2017
 */
public class EDIFHierPortInst {

    @NotNull
    private final EDIFHierCellInst hierarchicalInst;

    @NotNull
    private final EDIFPortInst portInst;

    /**
     * Constructor
     * @param hierarchicalInst The hierarchical parent instance cell of the port
     * @param portInst The actual port ref object
     */
    public EDIFHierPortInst(@NotNull EDIFHierCellInst hierarchicalInst, @NotNull EDIFPortInst portInst) {
        this.hierarchicalInst = Objects.requireNonNull(hierarchicalInst);
        this.portInst = Objects.requireNonNull(portInst);
    }

    /**
     * The name of the parent instance cell that contains the instance
     * cell pin.
     * @return the hierarchicalInstanceName
     */
    public String getHierarchicalInstName() {
        return hierarchicalInst.getFullHierarchicalInstName();

    }

    /**
     * Gets the net on the port inst
     * @return The net on the port inst
     */
    public EDIFNet getNet() {
        return portInst.getNet();
    }

    /**
     * Returns the full hierarchical name of the instance on which this port resides.
     * @return The full hierarchical name.
     */
    public String getFullHierarchicalInstName() {
        if (portInst.getCellInst() == null) {
            // Internal (inward-facing) side of a cell port
            return hierarchicalInst.getFullHierarchicalInstName();
        }

        EDIFCellInst topCellInst = portInst.getCellInst().getCellType().getLibrary().getNetlist().getTopCellInst();
        if (portInst.getCellInst() == topCellInst) {
            //Outward side of top
            return "";
        }

        StringBuilder sb = new StringBuilder();
        if (hierarchicalInst.enterHierarchicalName(sb)) {
            sb.append(EDIFTools.EDIF_HIER_SEP);
        }
        sb.append(portInst.getCellInst().getName());
        return sb.toString();
    }

    /**
     * @return the portInst
     */
    public @NotNull EDIFPortInst getPortInst() {
        return portInst;
    }

    public EDIFCell getCellType() {
        if (portInst.getCellInst() == null) return null;
        return portInst.getCellInst().getCellType();
    }


    /* (non-Javadoc)
     * @see java.lang.Object#hashCode()
     */
    @Override
    public int hashCode() {
        final int prime = 31;
        int result = 1;
        result = prime * result + ((hierarchicalInst == null) ? 0 : hierarchicalInst.hashCode());
        result = prime * result + ((portInst == null) ? 0 : portInst.hashCode());
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
        EDIFHierPortInst other = (EDIFHierPortInst) obj;
        if (hierarchicalInst == null) {
            if (other.hierarchicalInst != null)
                return false;
        } else if (!hierarchicalInst.equals(other.hierarchicalInst))
            return false;
        if (portInst == null) {
            if (other.portInst != null)
                return false;
        } else if (!portInst.equals(other.portInst))
            return false;
        return true;
    }

    /* (non-Javadoc)
     * @see java.lang.Object#toString()
     */
    @Override
    public String toString() {
        if (hierarchicalInst.isTopLevelInst()) return portInst.getFullName();
        return hierarchicalInst + "/" + portInst.getFullName();
    }

    public String getHierarchicalNetName() {
        StringBuilder sb = new StringBuilder();
        if (hierarchicalInst.enterHierarchicalName(sb)) {
            sb.append(EDIFTools.EDIF_HIER_SEP);
        }
        sb.append(portInst.getNet().getName());
        return sb.toString();
    }

    public EDIFHierNet getHierarchicalNet() {
        EDIFNet net = getNet();
        return (net != null) ? new EDIFHierNet(hierarchicalInst, net) : null;
    }

    public boolean isOutput() {
        return portInst.getPort().isOutput();
    }

    public boolean isInput() {
        return portInst.getPort().isInput();
    }

    /**
     * Gets the routed site pin if this port is on a placed leaf cell and its' site is routed
     * @param design The current design
     * @return The connected site pin to the connected to this cell pin.
     */
    public SitePinInst getRoutedSitePinInst(Design design) {
        Cell cell = getPhysicalCell(design);
        if (cell == null) return null;
        return cell.getSitePinFromPortInst(getPortInst(), null);
    }

    /**
     * Gets the physical cell to which this port instance has been placed
     * @param design The design corresponding to the implementation of this port instance's netlist
     * @return The placed physical cell mapped for this port instance or null if none could be found
     */
    public Cell getPhysicalCell(Design design) {
        String cellName = getFullHierarchicalInstName();
        Cell cell = design.getCell(cellName);
        return cell;
    }

    /**
     * Gets the physical site instance and BEL pin location where this port instance has been placed
     * @param design The design corresponding to the implementation of this port instance's netlist
     * @return The site instance and BELPin for this port instance
     */
    public Pair<SiteInst, BELPin> getRoutedBELPin(Design design) {
        Cell cell = getPhysicalCell(design);
        if (cell == null) return null;
        BELPin belPin = cell.getBELPin(this);
        return new Pair<>(cell.getSiteInst(), belPin);
    }

    /**
     * Gets the list of site pins if this port is on a placed leaf cell and its' site is routed
     * @param design The current design
     * @return The list of connected site pins to the connected to this cell pin.
     */
    public List<SitePinInst> getAllRoutedSitePinInsts(Design design) {
        String cellName = getFullHierarchicalInstName();
        Cell cell = design.getCell(cellName);
        if (cell == null) return null;
        return cell.getAllSitePinsFromPortInst(getPortInst(), null);
    }

    public EDIFHierCellInst getHierarchicalInst() {
        return hierarchicalInst;
    }

    /**
     * For Ports that represent connections to the parent (portInst.getCellInst()==null), get the parent's portInst
     * that connects to this port.
     */
    public EDIFHierPortInst getPortInParent() {
        if (portInst.getCellInst() != null) {
            throw new IllegalStateException("This method is only valid for PortInsts that represent connections to the parent");
        }
        if (hierarchicalInst.isTopLevelInst()) {
            throw new IllegalStateException("Cannot get Port in Parent of Root Port "+this);
        }
        final EDIFPortInst portInst = hierarchicalInst.getInst().getPortInst(this.portInst.getPortInstNameFromPort());
        if (portInst == null) {
            //throw new IllegalStateException("Trying to find port "+this+" in parent but got null!");
            return null;
        }
        return new EDIFHierPortInst(hierarchicalInst.getParent(), portInst);
    }

    /**
     * FOr Ports that represent connections to inner cells, get the connected Net that is connected within the cell
     */
    public EDIFHierNet getInternalNet() {

        final EDIFNet internalNet = portInst.getInternalNet();
        if (internalNet == null) {
            return null;
        }
        return new EDIFHierNet(hierarchicalInst.getChild(portInst.getCellInst()), internalNet);

    }
}

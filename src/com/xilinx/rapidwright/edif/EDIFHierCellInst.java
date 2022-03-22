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
package com.xilinx.rapidwright.edif;

import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

/**
 * An {@link EDIFCellInst} with its hierarchy, described by all the {@link EDIFCellInst}s that sit above it within
 * the netlist.
 *
 * Instances of this class do not necessarily describe a complete hierarchy from the top level cell to a leaf instance.
 * They may also be used to describe a partial hierarchy starting at an arbitrary point in the design.
 *
 * Instances of this class are immutable: Once created, it cannot be changed.
 *
 * Created on: Oct 30, 2017
 */
public class EDIFHierCellInst {
    private final EDIFCellInst[] cellInsts;

    private EDIFHierCellInst(EDIFCellInst[] cellInsts, int newLength, EDIFCellInst relativeChild) {
        if (newLength == 0) {
            throw new IllegalStateException("Cannot have a hierCellInst without any names");
        }
        this.cellInsts = Arrays.copyOf(cellInsts, newLength);
        if (relativeChild != null) {
            this.cellInsts[this.cellInsts.length-1] = relativeChild;
        }
    }

    private static boolean isToplevelInst(EDIFCellInst eci) {
        EDIFCell cellType = eci.getCellType();
        EDIFLibrary library = cellType.getLibrary();
        EDIFNetlist netlist = library.getNetlist();
        EDIFCellInst topCellInst = null;
        if(netlist != null) {
            topCellInst = netlist.getTopCellInst();
        }
        if(topCellInst != null) {
            return topCellInst==eci;
        } else {
            return false;
        }
    }

    /**
     * Create an absolute EDIFHierCellInst.
     * The first cellInst is required to be the top cell inst as returned by {@link EDIFNetlist#getTopCellInst()}
     * @param cellInsts the hierarchy of cell insts
     * @return a new instance
     */
    public static EDIFHierCellInst create(EDIFCellInst... cellInsts) {
        if (cellInsts.length == 0) {
            throw new RuntimeException("Cannot create empty EDIFHierCellInst");
        }
        if (!isToplevelInst(cellInsts[0])) {
            throw new RuntimeException("Tried to create absolute EDIFHierCellInst, but is not rooted at top instance: "+ Arrays.toString(cellInsts));
        }
        return createRelative(cellInsts);
    }

    public static EDIFHierCellInst createTopInst(EDIFCellInst topCell) {
        return new EDIFHierCellInst(new EDIFCellInst[]{topCell});
    }

    /**
     * Create an EDIFHierCellInst. This may be absolute (first item is the top cell inst) or relative (starting at an arbitrary point in the hierarchy).
     * @param cellInsts the hierarchy of cell insts
     * @return a new top instance
     */
    public static EDIFHierCellInst createRelative(EDIFCellInst... cellInsts) {
        return new EDIFHierCellInst(cellInsts);
    }

    private boolean hasParent() {
        return cellInsts.length > 1;
    }

    /**
     * Create a new Instance.
     * To guarantee Immutability, the provided array MUST NOT be changed.
     * @param cellInsts the cells to use
     */
    private EDIFHierCellInst(EDIFCellInst[] cellInsts) {
        if (cellInsts.length == 0) {
            throw new RuntimeException("cannot have empty cell insts");
        }
        this.cellInsts = cellInsts;
    }

    public EDIFCellInst getInst() {
        return cellInsts[cellInsts.length-1];
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        EDIFHierCellInst that = (EDIFHierCellInst) o;
        return Arrays.equals(cellInsts, that.cellInsts);
    }

    @Override
    public int hashCode() {
        return Arrays.hashCode(cellInsts);
    }

    public EDIFHierCellInst getParent() {
        if (cellInsts.length == 1) {
            return null;
        }
        return new EDIFHierCellInst(cellInsts, cellInsts.length-1, null);
    }

    public EDIFHierCellInst getChild(EDIFCellInst relativeChild) {
        return new EDIFHierCellInst(cellInsts, cellInsts.length+1, relativeChild);
    }

    public EDIFHierCellInst getSibling(EDIFCellInst relativeSibling) {
        return new EDIFHierCellInst(cellInsts, cellInsts.length, relativeSibling);
    }

    /**
     * Checks if the provided instance is an ancestor (hierarchical parent) of this instance. For 
     * example, if this="disneyland/tomorrow_land/space_mountain" and 
     * potentialAncestor="disneyland/tomorrow_land", this method would return true.  however, if 
     * potentialAncestor="disneyland/adventure_land", it would return false. 
     *  
     * @param potentialAncestor The hierarchical instance in question to check if it is  
     * @return True if the provided instance is a hierarchical ancestor of this instance.
     */
    public boolean isDescendantOf(EDIFHierCellInst potentialAncestor) {
        EDIFCellInst[] other = potentialAncestor.cellInsts;
        if(other.length >= cellInsts.length) return false;
        for(int i=0; i < other.length; i++) {
            if(cellInsts.length > i) {
                if(!cellInsts[i].getName().equals(other[i].getName())) {
                    return false;
                }
            }
        }
        return true;
    }
    
    /**
     * Given this instance and the provided instance o, it determines the closest common ancestor
     * between the two instances.  In some cases, this can default to the top cell design instance.
     * For example, if this instance = "a/b/c/d" and o = "a/b/e/f", the method would return "a/b".  
     * @param o The other instance to check for a common ancestor.
     * @return The closest common ancestor between this instance and the provided instance.
     */
    public EDIFHierCellInst getCommonAncestor(EDIFHierCellInst o) {
        if(!isAbsolute() || !o.isAbsolute()) {
            throw new RuntimeException("ERROR: Can only get a common ancestor of absolute "
                    + "EDIFHierCellInsts. this.isAbsolute()=" + this.isAbsolute() 
                    + ", o.isAbsolute()=" + o.isAbsolute());
        }
        EDIFCellInst[] oCellInsts = o.cellInsts;
        int min = Integer.min(cellInsts.length, oCellInsts.length);
        int idx = 0;
        for(int i=0; i< min; i++) { 
            if(cellInsts[i] == oCellInsts[i]) {
                idx++;
            } else {
                break;
            }
        }
        return new EDIFHierCellInst(oCellInsts, idx, null);
    }
    
    public boolean isAbsolute() {
        return isToplevelInst(cellInsts[0]);
    }

    public List<EDIFCellInst> getFullHierarchy() {
        //We can't just return the internal array, as users could then change it.
        //Return an unmodifiable list that uses the same backing array instead
        return Collections.unmodifiableList(Arrays.asList(cellInsts));
    }

    public int getDepth() {
        return cellInsts.length;
    }

    public boolean enterHierarchicalName(StringBuilder sb) {
        int start = isAbsolute() ? 1 : 0;
        for (int i = start; i < cellInsts.length; i++) {
            if (i>start) {
                sb.append(EDIFTools.EDIF_HIER_SEP);
            }
            sb.append(cellInsts[i].getName());
        }
        return start < cellInsts.length;
    }

    public String getFullHierarchicalInstName() {
        StringBuilder sb = new StringBuilder();
        enterHierarchicalName(sb);
        return sb.toString();
    }

    /**
     * Checks if this instance is the top level instance of the netlist.
     *
     * @return True if this is the top instance, false otherwise.
     */
    public boolean isTopLevelInst() {
        //Has multiple levels?
        if (hasParent()) {
            return false;
        }
        //HierCellInsts need not be absolute, so check the cell
        return isToplevelInst(getInst());

    }

    public EDIFCell getCellType() {
        return getInst().getCellType();
    }

    public String getCellName() {
        return getInst().getCellName();
    }

    /**
     * Get a direct child
     * @param relativeChildName the name of the direct child
     * @return the hierarchical child
     */
    public EDIFHierCellInst getChild(String relativeChildName) {
        final EDIFCellInst cellInst = getCellType().getCellInst(relativeChildName);
        if (cellInst == null) {
            //throw new IllegalStateException(this + " does not have a child named "+relativeChildName);
            return null;
        }
        return getChild(cellInst);
    }

    public EDIFHierNet getNet(String netName) {
        final EDIFNet net = getInst().getCellType().getNet(netName);
        if (net == null) {
            return null;
        }
        return new EDIFHierNet(this, net);
    }

    /**
     * Get a hierarchical port inst, viewed from external to this cell inst.
     * @param portName name of the port to get.
     * @throws IllegalStateException if the port does not exist
     */
    public EDIFHierPortInst getPortInst(String portName) {
        final EDIFPortInst port = getInst().getPortInst(portName);
        if (port == null) {
            return null;
        }
        return new EDIFHierPortInst(getParent(), port);
    }

    /**
     * Add this instance's children to some collection
     * @param collection the collection to add to
     */
    public void addChildren(Collection<EDIFHierCellInst> collection) {
        for (EDIFCellInst cellInst : getInst().getCellType().getCellInsts()) {
            collection.add(getChild(cellInst));
        }
    }

    @Override
    public String toString() {
        return getFullHierarchicalInstName();
    }
}

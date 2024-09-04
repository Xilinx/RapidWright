/*
 *
 * Copyright (c) 2017-2022, Xilinx, Inc.
 * Copyright (c) 2022-2024, Advanced Micro Devices, Inc.
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
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Objects;
import java.util.Queue;
import java.util.concurrent.atomic.AtomicIntegerFieldUpdater;

/**
 * Represent a logical cell in an EDIF netlist.  Can
 * be both a leaf cell or a hierarchical cell.
 *
 * Created on: May 11, 2017
 */
public class EDIFCell extends EDIFPropertyObject {

    public static final EDIFName DEFAULT_VIEW = new EDIFName("netlist");

    private EDIFLibrary library;

    private Map<String, EDIFCellInst> instances;

    private Map<String, EDIFNet> nets;

    private Map<String, EDIFPort> ports;

    private Map<String, EDIFNet> internalPortMap;

    private EDIFName view = DEFAULT_VIEW;

    /**
     * An atomically updated variable to track the number of `EDIFCellInst`
     * objects (attached to a parent cell) that instantiate this cell.
     * Note: this does not track the number of `EDIFHierCellInst` objects
     *       that would exist if the netlist was traversed from the top cell.
     */
    private volatile int nonHierInstantiationCount = 0;
    private static final AtomicIntegerFieldUpdater<EDIFCell> nonHierInstantiationCountUpdater =
            AtomicIntegerFieldUpdater.newUpdater(EDIFCell.class, "nonHierInstantiationCount");

    public EDIFCell(EDIFLibrary lib, String name) {
        super(name);
        if (lib != null) lib.addCell(this);
    }

    /**
     * Shallow Copy constructor - Creates a new EDIFCell object, EDIFCell
     * contents point to orig.
     *
     * @param orig The original cell
     */
    public EDIFCell(EDIFCell orig) {
        this(null, orig);
    }

    /**
     * Shallow Copy constructor - Creates a new EDIFCell object, EDIFCell
     * contents point to orig.
     *
     * @param lib  Destination library of the copied cell.
     * @param orig The original cell
     */
    public EDIFCell(EDIFLibrary lib, EDIFCell orig) {
        super(orig.getName());
        if (lib != null) lib.addCell(this);
        instances = orig.instances;
        nets = orig.nets;
        ports = orig.ports;
        internalPortMap = orig.internalPortMap;
        view = orig.view;
    }

    /**
     * Full Deep Copy Constructor with rename
     *
     * @param lib         Destination library of the new cell
     * @param orig        Prototype of the original cell
     * @param newCellName Name of the new cell copy
     */
    public EDIFCell(EDIFLibrary lib, EDIFCell orig, String newCellName) {
        super(newCellName);
        if (lib != null) lib.addCell(this);
        if (orig.instances != null) {
            for (Entry<String, EDIFCellInst> e : orig.instances.entrySet()) {
                addCellInst(new EDIFCellInst(e.getValue(), this));
            }
        }
        if (orig.ports != null) {
            for (Entry<String, EDIFPort> e : orig.ports.entrySet()) {
                addPort(new EDIFPort(e.getValue()));
            }
        }
        if (orig.nets != null) {
            for (Entry<String, EDIFNet> e : orig.nets.entrySet()) {
                EDIFNet net = addNet(new EDIFNet(e.getValue()));
                for (EDIFPortInst prototype : e.getValue().getPortInsts()) {
                    EDIFPortInst newPortInst = new EDIFPortInst(prototype);
                    EDIFPort newPort = null;
                    if (prototype.getCellInst() != null) {
                        newPortInst.setCellInst(getCellInst(prototype.getCellInst().getName()));
                        newPort = newPortInst.getCellInst().getCellType().getPort(prototype.getPort().getBusName(true));
                        if (newPort == null || newPort.getWidth() != prototype.getPort().getWidth()) {
                            newPort = newPortInst.getCellInst().getCellType().getPort(prototype.getPort().getName());
                        }
                    } else {
                        newPort = getPort(prototype.getPort().getBusName(true));
                        if (newPort == null || newPort.getWidth() != prototype.getPort().getWidth()) {
                            newPort = getPort(prototype.getPort().getName());
                        }
                    }

                    newPortInst.setPort(newPort);
                    net.addPortInst(newPortInst);
                }
            }
        }
        view = orig.view;
        setPropertiesMap(orig.createDuplicatePropertiesMap());
    }

    protected EDIFCell() {

    }

    public EDIFCellInst createChildCellInst(String name, EDIFCell reference) {
        return new EDIFCellInst(name, reference, this);
    }

    public EDIFCellInst addNewCellInstUniqueName(String suggestedName, EDIFCell reference) {
        EDIFCellInst i = new EDIFCellInst();
        i.setName(suggestedName);
        i.setCellType(reference);
        return addCellInstUniqueName(i);
    }

    /**
     * Adds the instance to this cell.  Checks for a name collision.
     *
     * @param instance The instance to add to this cell.
     * @return The instance added to the cell.
     */
    public EDIFCellInst addCellInst(EDIFCellInst instance) {
        if (instances == null) instances = getNewMap();
        instance.setParentCell(this);
        EDIFCellInst collision = instances.put(instance.getName(), instance);
        if (collision != null && instance != collision) {
            throw new RuntimeException("ERROR: Name collsion inside EDIFCell " +
                    getName() + ", trying to add instance " + instance.getName() +
                    " which already exists inside this cell.");
        }
        return instance;
    }

    /**
     * Ensures that the provided cell instance is added to this cell by renaming it
     * to something unique.
     *
     * @param instance The instance to be added to this cell.
     * @return The instance added to the cell.
     */
    public EDIFCellInst addCellInstUniqueName(EDIFCellInst instance) {
        if (instances == null) instances = getNewMap();
        instance.setParentCell(this);
        while (instances.containsKey(instance.getName())) {
            instance.setName(instance.getName() + "_" + getLibrary().getNetlist().nameSpaceUniqueCount++);
        }
        instances.put(instance.getName(), instance);
        return instance;
    }

    public EDIFCellInst getCellInst(String name) {
        if (instances == null) return null;
        return instances.get(name);
    }

    /**
     * Adds a net to the cell. Checks for name collisions.
     *
     * @param net The net to add
     * @return The net that was added.
     */
    public EDIFNet addNet(EDIFNet net) {
        if (nets == null) nets = getNewMap();
        net.setParentCell(this);
        EDIFNet collision = nets.put(net.getName(), net);
        if (collision != null && net != collision) {
            throw new RuntimeException("ERROR: Name collision inside EDIFCell " +
                    getName() + ", trying to add net " + net.getName() +
                    " which already exists inside this cell.");
        }
        return net;
    }

    public EDIFNet getNet(EDIFNet net) {
        return getNet(net.getName());
    }

    public EDIFNet getNet(String name) {
        if (nets == null) return null;
        return nets.get(name);
    }

    public EDIFNet removeNet(EDIFNet net) {
        return removeNet(net.getName());
    }

    public EDIFNet removeNet(String name) {
        if (nets == null) return null;
        trackChange(EDIFChangeType.NET_REMOVE, name);
        return nets.remove(name);
    }

    /**
     * Adds a port to the cell. Checks for naming collisions and throws
     * RuntimeException if it occurs. Single bit ports that are not bussed, are
     * keyed by their full name. Bussed ports, however, have their range truncated
     * to just the opening square bracket so that the port can be retrieved from a
     * valid EDIFPortInst name (range being unknown). For example, bus[3:0] would be
     * keyed by "bus[".
     *
     * @param port The port to add.
     * @return The port that was added.
     */
    public EDIFPort addPort(EDIFPort port) {
        if (ports == null) ports = getNewMap();
        port.setParentCell(this);
        EDIFPort collision = ports.put(port.getBusName(true), port);
        if (collision != null && port != collision) {
            throw new RuntimeException("ERROR: Port name collision on EDIFCell " + getName()
                    + ", trying to add port " + port
                    + ", but the cell already contains ports with the " + "same name: " + collision);
        }
        return port;
    }

    /**
     * Gets a port by bus name (see {@link EDIFPort#getBusName()}). Multi-bit ports
     * need to have closing square bracket and range removed (for example:
     * {@code "bus[3:0]" -> "bus["}). See {@link EDIFCell#addPort(EDIFPort)} for more information.
     *
     * @param name Bus name (ends with '[' to represent a bussed port) of the
     *                port to get. Single bit ports use their entire name.
     * @return The port or null if none exists.
     */
    public EDIFPort getPort(String name) {
        if (ports == null) return null;
        EDIFPort port = ports.get(name);
        // For callers who have a port name and its unknown if its a bus, attempt a check with adding the '[' suffix
        if (port == null && name.charAt(name.length() - 1) != '[') {
            port = ports.get(name + "[");
        }
        return port;
    }

    /**
     * Given a port instance name (not including the name of the cell instance),
     * gets the associated port.
     * 
     * @param portInstName
     * @return
     */
    public EDIFPort getPortByPortInstName(String portInstName) {
        if (ports == null) return null;
        EDIFPort port = ports.get(portInstName);
        if (port == null && portInstName.charAt(portInstName.length() - 1) == ']') {
            port = ports.get(portInstName.substring(0, portInstName.lastIndexOf('[') + 1));
        }
        return port;
    }

    public EDIFCellInst createCellInst(String name, EDIFCell parent) {
        return new EDIFCellInst(name, this, parent);
    }

    public EDIFCellInst removeCellInst(EDIFCellInst cellInstance) {
        return removeCellInst(cellInstance.getName());
    }

    public EDIFCellInst removeCellInst(String name) {
        if (instances == null) return null;
        EDIFCellInst removedInstance = instances.remove(name);
        if (removedInstance != null) {
            assert(removedInstance.getParentCell() == this);
            removedInstance.setParentCell(null);
        }
        return removedInstance;
    }

    public EDIFNet createNet(String name) {
        EDIFNet net = new EDIFNet(name, this);
        return net;
    }

    public EDIFPort createPort(String name, EDIFDirection direction, int width) {
        EDIFPort p = new EDIFPort(name, direction, width);
        addPort(p);
        return p;
    }

    public EDIFPort createPort(EDIFPort port) {
        return createPort(port.getName(), port.getDirection(), port.getWidth());
    }

    public void rename(String newName) {
        setName(newName);
    }

    /**
     * Renames the provided instance i with newName.
     *
     * @param i       Current instance in the cell.
     * @param newName New name for instance i
     * @return The newly renamed instance
     */
    public EDIFCellInst renameCellInst(EDIFCellInst i, String newName) {
        EDIFCellInst inst = getCellInst(i.getName());
        if (inst == null) {
            throw new RuntimeException("ERROR: " +
                    "Couldn't find instance " + i.getName() + " in cell " + getName() +
                    " when trying to rename to " + newName);
        }
        removeCellInst(inst);
        inst.setName(newName);
        addCellInst(inst);
        return inst;
    }

    public void removePort(EDIFPort port) {
        List<String> portObjectsToRemove = new ArrayList<>();
        for (Entry<String, EDIFPort> p : getPortMap().entrySet()) {
            if (p.getValue() == port || p.getValue().getName().equals(port.getName())) {
                portObjectsToRemove.add(p.getKey());
            }
        }
        for (String s : portObjectsToRemove) {
            getPortMap().remove(s);
        }
        trackChange(EDIFChangeType.PORT_REMOVE, port.getName());
    }

    public void moveToLibrary(EDIFLibrary newLibrary) {
        if (library != null) library.removeCell(this);
        newLibrary.addCell(this);
    }

    /**
     * Gets the original view name
     *
     * @return the view
     */
    public String getView() {
        return view.getName();
    }

    /**
     * Gets the EDIFName object representation of the view name
     *
     * @return Gets the EDIFName object storing the view name
     */
    public EDIFName getEDIFView() {
        return view;
    }

    /**
     * @param view the view to set
     */
    public void setView(String view) {
        setView(new EDIFName(view));
    }

    public void setView(EDIFName view) {
        this.view = DEFAULT_VIEW.equals(view) ? DEFAULT_VIEW : view;
    }

    public Collection<EDIFPort> getPorts() {
        if (ports == null) return Collections.emptyList();
        return ports.values();
    }

    public Map<String, EDIFPort> getPortMap() {
        return ports == null ? Collections.emptyMap() : ports;
    }

    public Collection<EDIFCellInst> getCellInsts() {
        if (instances == null) return Collections.emptyList();
        return instances.values();
    }

    public Collection<EDIFNet> getNets() {
        if (nets == null) return Collections.emptyList();
        return nets.values();
    }

    /**
     * Populates an internal map between port-based port ref name,  'bus[3]' or 'clk'.
     *
     * @param portInstName Name from a port ref as generated in @link {@link EDIFPortInst#getPortInstNameFromPort()}
     * @param internalNet  The net inside this cell to match with the port ref name.
     */
    public void addInternalPortMapEntry(String portInstName, EDIFNet internalNet) {
        if (internalPortMap == null) internalPortMap = getNewMap();
        internalPortMap.put(portInstName, internalNet);
    }

    /**
     * Removes the entry within the cell internal net map (when removing a port on a cell).
     *
     * @param portInstName Name of the port ref to remove
     * @return The net to which the removed port ref belongs, or null if none could be found.
     */
    public EDIFNet removeInternalPortMapEntry(String portInstName) {
        if (internalPortMap == null) return null;
        return internalPortMap.remove(portInstName);
    }

    public Map<String, EDIFNet> getInternalNetMap() {
        if (internalPortMap == null) return Collections.emptyMap();
        return internalPortMap;
    }

    /**
     * Takes an external (or internal) port ref and returns the corresponding
     * EDIFNet connected inside the cell.
     *
     * @param portInst The external port ref to get the internal net.
     * @return The internal connected net or null if none exists.
     */
    public EDIFNet getInternalNet(EDIFPortInst portInst) {
        return getInternalNet(portInst.getPortInstNameFromPort());
    }

    /**
     * Takes an external (or internal) port name and returns the corresponding
     * EDIFNet connected inside the cell.
     *
     * @param portInstName The external port name to get the internal net.
     * @return The internal connected net or null if none exists.
     */
    public EDIFNet getInternalNet(String portInstName) {
        if (internalPortMap == null) return null;
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
        if (library == null) {
            throw new RuntimeException("ERROR: library argument cannot be null.");
        }
        if (this.library != null && this.library != library) {
            throw new RuntimeException("ERROR: EDIFCell is already attached to a library. Call EDIFLibrary.removeCell() first.");
        }

        this.library = library;
    }

    protected void clearLibrary() {
        this.library = null;
    }

    public boolean hasContents() {
        return instances != null || nets != null;
    }

    public boolean isPrimitive() {
        return getLibrary().getName().equals(EDIFTools.EDIF_LIBRARY_HDI_PRIMITIVES_NAME) && isLeafCellOrBlackBox();
    }

    public boolean isStaticSource() {
        return isPrimitive() && (isVCCSource() || isGNDSource());
    }

    public boolean isVCCSource() {
        return getName().equals("VCC");
    }

    public boolean isGNDSource() {
        return getName().equals("GND");
    }

    public boolean isLeafCellOrBlackBox() {
        return (instances == null || instances.size() == 0) && (nets == null || nets.size() == 0);
    }

    /**
     * Checks if all the port on the provided cell match and are equal to the ports on this cell.
     * @param cell The other cell to check against.
     * @return True if the ports on each cell match each other, false otherwise.
     */
    public boolean hasCompatibleInterface(EDIFCell cell) {
        Map<String,EDIFPort> portMap = new HashMap<>(ports);
        if (portMap.size() != cell.getPortMap().size()) return false;

        for (EDIFPort port : cell.getPorts()) {
            EDIFPort match = portMap.remove(port.getBusName(true));
            if (match == null) {
                match = portMap.remove(port.getName());
                if (match == null) {
                    return false;
                }
            }
            if (!Objects.equals(port.getName(), match.getName())) return false;
            if (!Objects.equals(port.getWidth(), match.getWidth())) return false;
            if (!Objects.equals(port.getDirection(), match.getDirection())) return false;
        }
        return portMap.isEmpty();
    }

    /**
     * Deletes internal representation.
     */
    public void makePrimitive() {
        EDIFNetlist netlist = getNetlist();
        if (netlist != null && netlist.isTrackingCellChanges()) {
            for (EDIFCellInst inst : getCellInsts()) {
                netlist.trackChange(this, EDIFChangeType.CELL_INST_REMOVE, inst.getName());
            }
            for (EDIFNet net : getNets()) {
                netlist.trackChange(this, EDIFChangeType.NET_REMOVE, net.getName());
            }
        }
        for (EDIFCellInst instance : getCellInsts()) {
            instance.getCellType().decrementNonHierInstantiationCount();
        }
        instances = null;
        nets = null;
        internalPortMap = null;
    }


    public static final byte[] EXPORT_CONST_CELL_BEGIN = "   (cell ".getBytes(StandardCharsets.UTF_8);
    public static final byte[] EXPORT_CONST_CELLTYPE = " (celltype GENERIC)\n     (view ".getBytes(StandardCharsets.UTF_8);
    public static final byte[] EXPORT_CONST_VIEWTYPE = " (viewtype NETLIST)\n       (interface \n".getBytes(StandardCharsets.UTF_8);
    public static final byte[] EXPORT_CONST_INTERFACE_END = "       )\n".getBytes(StandardCharsets.UTF_8);
    public static final byte[] EXPORT_CONST_CONTENTS = "       (contents\n".getBytes(StandardCharsets.UTF_8);
    public static final byte[] EXPORT_CONST_CONTENTS_END = "       )\n".getBytes(StandardCharsets.UTF_8);
    public static final byte[] EXPORT_CONST_VIEW_END = "     )\n".getBytes(StandardCharsets.UTF_8);
    public static final byte[] EXPORT_CONST_CELL_END = "   )\n".getBytes(StandardCharsets.UTF_8);
    public static final byte[] EXPORT_CONST_PROP_INDENT = "           ".getBytes(StandardCharsets.UTF_8);

    public void exportEDIF(OutputStream os, EDIFWriteLegalNameCache<?> cache, boolean stable) throws IOException {
        os.write(EXPORT_CONST_CELL_BEGIN);
        exportEDIFName(os, cache);
        os.write(EXPORT_CONST_CELLTYPE);
        view.exportEDIFName(os, cache);
        os.write(EXPORT_CONST_VIEWTYPE);
        for (EDIFPort port : EDIFTools.sortIfStable(getPorts(), stable)) {
            port.exportEDIF(os, cache, stable);
        }
        os.write(EXPORT_CONST_INTERFACE_END); // Interface end
        if (hasContents()) {
            os.write(EXPORT_CONST_CONTENTS);
            for (EDIFCellInst i : EDIFTools.sortIfStable(getCellInsts(), stable)) {
                i.exportEDIF(os, cache, stable);
            }
            for (EDIFNet n : EDIFTools.sortIfStable(getNets(), stable)) {
                n.exportEDIF(os, cache, stable);
            }
            os.write(EXPORT_CONST_CONTENTS_END); // Contents end
        }
        if (getPropertiesMap().size() > 0) {
            os.write('\n');
            exportEDIFProperties(os, EXPORT_CONST_PROP_INDENT, cache, stable);
        }
        os.write(EXPORT_CONST_VIEW_END); // View end
        os.write(EXPORT_CONST_CELL_END); // Cell end
    }

    public void exportEDIF(OutputStream os, EDIFWriteLegalNameCache<?> cache) throws IOException{
        exportEDIF(os, cache, false);
    }

    /**
     * Recursively finds all leaf cell descendants of this cell
     *
     * The returned EDIFHierCellInsts are relative to this cell.
     *
     * @return A list of all leaf cell descendants of this cell
     */
    public List<EDIFHierCellInst> getAllLeafDescendants() {
        return getAllLeafDescendants(null);
    }

    /**
     * Recursively finds all leaf cell descendants of this cell
     *
     * @param parentInstance Parent name or prefix name for all leaf cell descendants to be
     *                       added.  Is not error checked against netlist because the context is not available.
     * @return A list of all leaf cell descendants of this cell
     */
    public List<EDIFHierCellInst> getAllLeafDescendants(EDIFHierCellInst parentInstance) {
        List<EDIFHierCellInst> leafCells = new ArrayList<>();

        if (!hasContents()) return leafCells;

        Queue<EDIFHierCellInst> toProcess = new LinkedList<EDIFHierCellInst>();
        for (EDIFCellInst inst : getCellInsts()) {
            if (parentInstance == null) {
                toProcess.add(EDIFHierCellInst.createRelative(inst));
            } else {
                toProcess.add(parentInstance.getChild(inst));
            }
        }

        while (!toProcess.isEmpty()) {
            EDIFHierCellInst curr = toProcess.poll();
            if (curr.getCellType().isPrimitive()) {
                leafCells.add(curr);
            } else {
                curr.addChildren(toProcess);
            }
        }
        return leafCells;
    }

    public EDIFNetlist getNetlist() {
        EDIFLibrary lib = getLibrary();
        return lib != null ? lib.getNetlist() : null;
    }

    public void trackChange(EDIFChangeType type, String name) {
        EDIFNetlist netlist = getNetlist();
        if (netlist != null) {
            netlist.trackChange(this, type, name);
        }
    }

    public EDIFPort getPortByLegalName(String name, EDIFReadLegalNameCache cache) {
        EDIFPort port = getPort(name);
        if (port != null) {
            return port;
        }
        // Finding by EDIFName is O(n), but n is generally small and alternative to building
        // a map for this single search ends up taking longer
        for (Map.Entry<String,EDIFPort> e : getPortMap().entrySet()) {
            if (cache.getLegalEDIFName(e.getValue()).equals(name)) {
                return e.getValue();
            }
        }
        return null;
    }

    public void sortEDIFPortInstLists() {
        for (EDIFNet net : getNets()) {
            EDIFPortInstList list = net.getEDIFPortInstList();
            if (list != null) list.reSortList();
        }
        for (EDIFCellInst inst : getCellInsts()) {
            EDIFPortInstList list = inst.getEDIFPortInstList();
            if (list != null) list.reSortList();
        }
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        if (!super.equals(o)) return false;
        EDIFCell edifCell = (EDIFCell) o;
        return Objects.equals(library, edifCell.library);
    }

    @Override
    public int hashCode() {
        return Objects.hash(super.hashCode(), library);
    }

    /**
     * Atomically increment instance count of this cell.
     */
    public void incrementNonHierInstantiationCount() {
        nonHierInstantiationCountUpdater.incrementAndGet(this);
    }

    /**
     * Atomically decrement instance count of this cell.
     */
    public void decrementNonHierInstantiationCount() {
        nonHierInstantiationCountUpdater.getAndDecrement(this);
    }

    /**
     * @return The number of times this cell has been instantiated.
     */
    public int getNonHierInstantiationCount() {
        return nonHierInstantiationCountUpdater.get(this);
    }

    /**
     * True if this cell is instantiated no more than once.
     */
    public boolean isUniquified() {
        return getNonHierInstantiationCount() <= 1;
    }
}


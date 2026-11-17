/*
 * Copyright (c) 2026, Advanced Micro Devices, Inc.
 * All rights reserved.
 *
 * Author: Chris Lavin, AMD Research and Advanced Development.
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

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReferenceFieldUpdater;

/**
 * What a group of nets of one cell connects to, in that cell and in the cells instantiated below
 * it.
 * <p>
 * A group is a net of a cell together with the other nets of that cell it is connected to through
 * the cells instantiated in it: a net of an instantiated cell that is connected to more than one
 * of its ports connects the nets on those ports of each of its instances. Most groups are a single
 * net. For its group, this records the ports of the cell it is connected to, and the sources (leaf
 * cell output pins) it has in the cell and below. None of this depends on where the cell is
 * instantiated, so it is kept once per net ({@link EDIFNet#connectivity}), however many times the
 * cell is instantiated. {@link EDIFParentNets} finds the parent net of a hierarchical net from it:
 * up the instances of the net through the ports of its groups, then down to the source.
 * <p>
 * It is computed when first asked for, and kept until something it was computed from changes. The
 * methods that change a netlist tell the nets they change ({@link EDIFNet#connectivityChanged()}),
 * which drops what is recorded for them and for the nets of other cells that it was used for.
 * Looking it up from several threads at the same time is safe, as long as no thread changes the
 * netlist meanwhile.
 */
final class EDIFNetConnectivity {

    /**
     * Set once a parent net is looked up in any netlist. Until then no net has its connectivity
     * recorded and no netlist keeps results, so that a change to a netlist has nothing to drop.
     */
    static volatile boolean active;

    private static final AtomicInteger LAST_EPOCH = new AtomicInteger();

    /**
     * What is recorded for a net is replaced or removed in one step, so that threads recording at
     * the same time cannot leave a record in use that is no longer the one of its nets: such a
     * record would not be dropped when its nets change.
     */
    private static final AtomicReferenceFieldUpdater<EDIFNet, EDIFNetConnectivity> RECORDED =
            AtomicReferenceFieldUpdater.newUpdater(EDIFNet.class, EDIFNetConnectivity.class, "connectivity");

    private static final Comparator<EDIFPortInst> BY_NAME = Comparator.comparing(EDIFPortInst::getName);

    /** What is recorded in an epoch before this one is no longer used by any netlist */
    private static volatile int minEpoch;

    /**
     * Marks a cell that connectivity was computed from as a leaf cell, or for the direction of
     * its ports, see {@link EDIFCell}
     */
    static final Object LEAF_CELL_USED = new Object();

    private static final int MANY_NETS = 8;

    /** The nets of the group: an EDIFNet, or an EDIFNet[] if more than one */
    private final Object nets;

    /**
     * The port insts on these nets that connect them to ports of their cell (those without a cell
     * inst): null if none, an EDIFPortInst, or an EDIFPortInst[] if more than one, in the order
     * of their names (so that the first one that is connected on an instance is the same one
     * whichever net of the group this was computed from)
     */
    private final Object ports;

    /**
     * If the group has sources and the first one is a leaf cell output pin on one of the nets,
     * the net of that pin, else null
     */
    private final EDIFNet sourceNet;

    /**
     * If the group has sources and the first one is not on one of the nets, the cell instance on
     * one of the nets below which it is found, else null
     */
    private final EDIFCellInst sourceInst;

    /** The connectivity of the net inside {@link #sourceInst} that has the source, else null */
    private final EDIFNetConnectivity sourceBelow;

    /** Number of sources in the cell and below: 0, 1, or 2 for more than one */
    private final byte sourceCount;

    /** The epoch of the netlist this was computed for, see {@link #newEpoch()} */
    private final int epoch;

    /** False once something this was computed from has changed */
    private volatile boolean valid;

    /**
     * The connectivity (of nets of cells instantiating this one) computed from this: null, an
     * EDIFNetConnectivity or an ArrayList of them. Guarded by this object's lock.
     */
    private Object dependents;

    /**
     * The instance of the cell that this was last looked up from, with the connectivity of the
     * net outside it: this saves finding the port inst of the instance by name each time. It is
     * only used while that connectivity is valid, as it is dropped when a port inst is added to
     * or removed from its nets.
     */
    private Above above;

    /** The connectivity of the net outside an instance. */
    private static final class Above {
        final EDIFCellInst inst;
        final EDIFNetConnectivity connectivity;

        Above(EDIFCellInst inst, EDIFNetConnectivity connectivity) {
            this.inst = inst;
            this.connectivity = connectivity;
        }
    }

    private EDIFNetConnectivity(Object nets, Object ports, EDIFNet sourceNet, EDIFCellInst sourceInst,
            EDIFNetConnectivity sourceBelow, int sourceCount, int epoch) {
        this.nets = nets;
        this.ports = ports;
        this.sourceNet = sourceNet;
        this.sourceInst = sourceInst;
        this.sourceBelow = sourceBelow;
        this.sourceCount = (byte) sourceCount;
        this.epoch = epoch;
        this.valid = true;
    }

    /**
     * Gets a new epoch. Connectivity is only used in the epoch it was computed in, so a netlist
     * drops all of it by moving to a new epoch.
     */
    static int newEpoch() {
        return LAST_EPOCH.incrementAndGet();
    }

    /** Gets the first epoch that is still valid: a netlist in an earlier one must get a new one. */
    static int getMinEpoch() {
        return minEpoch;
    }

    /** Drops the connectivity recorded in all netlists. */
    static void resetAll() {
        minEpoch = newEpoch();
    }

    /**
     * Gets the connectivity of the group of a net, computing it if it is not recorded.
     * @param net The net.
     * @param epoch The epoch of the netlist asking.
     * @return The connectivity of the net and of the nets of its cell connected to it.
     */
    static EDIFNetConnectivity get(EDIFNet net, int epoch) {
        EDIFNetConnectivity c = net.connectivity;
        if (c != null && c.epoch == epoch && c.valid) {
            return c;
        }
        return compute(net, epoch);
    }

    /** Number of sources this group has in its cell and below: 0, 1, or 2 for more than one. */
    int getSourceCount() {
        return sourceCount;
    }

    /**
     * Gets the net of the first source of this group, if that is a leaf cell output pin on one of
     * its nets, which it is if {@link #getSourceBelow()} is null.
     */
    EDIFNet getSourceNet() {
        return sourceNet;
    }

    /**
     * Gets the cell instance below which the first source of this group is, if it is not on one
     * of its nets ({@link #getSourceBelow()} is not null).
     */
    EDIFCellInst getSourceInst() {
        return sourceInst;
    }

    /**
     * Gets the connectivity, inside {@link #getSourceInst()}, that has the first source of this
     * group, or null if the source is on one of the nets of this group.
     */
    EDIFNetConnectivity getSourceBelow() {
        return sourceBelow;
    }

    /** Checks if this group is connected to the port of its cell with the given port inst name. */
    private boolean hasPort(String portInstName) {
        Object ports = this.ports;
        if (ports instanceof EDIFPortInst) {
            return ((EDIFPortInst) ports).getName().equals(portInstName);
        }
        if (ports != null) {
            for (EDIFPortInst port : (EDIFPortInst[]) ports) {
                if (port.getName().equals(portInstName)) {
                    return true;
                }
            }
        }
        return false;
    }

    /** Number of ports of the cell that this group is connected to. */
    int getPortCount() {
        return ports == null ? 0 : (ports instanceof EDIFPortInst) ? 1 : ((EDIFPortInst[]) ports).length;
    }

    /** Gets the port inst (without a cell inst) connecting this group to a port of the cell. */
    EDIFPortInst getPort(int i) {
        return (ports instanceof EDIFPortInst) ? (EDIFPortInst) ports : ((EDIFPortInst[]) ports)[i];
    }

    /**
     * Gets the connectivity of the net that this group is connected to outside of an instance of
     * its cell.
     * @param inst An instance of the cell of this group.
     * @param epoch The epoch of the netlist asking.
     * @return The connectivity of the net (of the cell that the instance is in) on the first port
     * of this group that is connected on the instance, or null if none is.
     */
    EDIFNetConnectivity getAbove(EDIFCellInst inst, int epoch) {
        Above a = above;
        if (a != null && a.inst == inst) {
            EDIFNetConnectivity c = a.connectivity;
            if (c.epoch == epoch && c.valid) {
                return c;
            }
        }
        EDIFNet net = getNetAbove(inst);
        if (net == null) {
            return null;
        }
        EDIFNetConnectivity c = get(net, epoch);
        above = new Above(inst, c);
        return c;
    }

    /**
     * Gets the net that this group is connected to outside of an instance of its cell.
     * @param inst An instance of the cell of this group.
     * @return The net (of the cell that the instance is in) on the first port of this group that
     * is connected on the instance, or null if none is.
     */
    private EDIFNet getNetAbove(EDIFCellInst inst) {
        Object ports = this.ports;
        if (ports == null) {
            return null;
        }
        if (ports instanceof EDIFPortInst) {
            return getNetOn(inst, (EDIFPortInst) ports);
        }
        for (EDIFPortInst port : (EDIFPortInst[]) ports) {
            EDIFNet net = getNetOn(inst, port);
            if (net != null) {
                return net;
            }
        }
        return null;
    }

    private static EDIFNet getNetOn(EDIFCellInst inst, EDIFPortInst port) {
        EDIFPortInst outer = inst.getPortInst(port.getName());
        return outer == null ? null : outer.getNet();
    }

    /**
     * Gets the net inside a cell that is connected to one of its ports: the net of the cell on
     * that port which has the port inst for it. (A port inst that was removed from its net may
     * leave the net as the one of the port.)
     * @param cell The cell.
     * @param portInstName Name of the port inst, which is that of the port and, for a bus, its bit.
     * @return The net inside the cell, or null if none is connected to the port.
     */
    static EDIFNet getConnectedNet(EDIFCell cell, String portInstName) {
        EDIFNet net = cell.getInternalNet(portInstName);
        if (net == null || net.getPortInst(null, portInstName) != null) {
            return net;
        }
        // Not found by name: make sure, as the port insts of a net are no longer sorted once a
        // cell instance on it is renamed
        EDIFPortInst[] portInsts = net.getPortInstArray();
        if (portInsts != null) {
            for (EDIFPortInst portInst : portInsts) {
                if (portInst == null) {
                    break;
                }
                if (portInst.getCellInst() == null && portInstName.equals(portInst.getName())) {
                    return net;
                }
            }
        }
        return null;
    }

    /**
     * Checks if a net with a port inst for a port of its cell is connected to that port.
     * @param cell The cell.
     * @param portInstName Name of the port inst (without a cell inst) on the net.
     * @param net The net.
     * @return True if the net is the one of the cell on that port.
     */
    static boolean isConnected(EDIFCell cell, String portInstName, EDIFNet net) {
        return cell.getInternalNet(portInstName) == net;
    }

    private static EDIFNetConnectivity compute(EDIFNet first, int epoch) {
        EDIFNetConnectivity c;
        do {
            c = computeOnce(first, epoch);
        } while (c == null);
        return c;
    }

    /**
     * @return The connectivity of the group of the net, or null if another thread replaced, while
     * it was computed, some of what it was computed from, in which case it is to be computed again.
     */
    private static EDIFNetConnectivity computeOnce(EDIFNet first, int epoch) {
        Builder b = new Builder(first);
        for (int i = 0; i < b.netCount; i++) {
            EDIFNet net = b.getNet(i);
            EDIFPortInst[] portInsts = net.getPortInstArray();
            if (portInsts == null) {
                continue;
            }
            EDIFCell cell = net.getParentCell();
            for (EDIFPortInst portInst : portInsts) {
                if (portInst == null) {
                    // The rest of the array is unused capacity
                    break;
                }
                EDIFCellInst inst = portInst.getCellInst();
                if (inst == null) {
                    if (cell == null || isConnected(cell, portInst.getName(), net)) {
                        b.addPort(portInst);
                    }
                    continue;
                }
                EDIFCell type = inst.getCellType();
                boolean isLeaf = type.isLeafCellOrBlackBox();
                if (isLeaf) {
                    // Whether the pin is a source depends on the cell being a leaf cell, and on
                    // the direction of the port, which is that of another cell if the port insts
                    // of the instance were not updated when it was given its cell
                    type.setConnectivityUsed();
                    EDIFCell portCell = portInst.getPort().getParentCell();
                    if (portCell != type && portCell != null) {
                        portCell.setConnectivityUsed();
                    }
                    if (portInst.isOutput()) {
                        b.addSources(portInst, null, 1);
                    }
                }
                EDIFNet inner = type.getInternalNet(portInst.getName());
                EDIFNetConnectivity below = (inner != null) ? get(inner, epoch) : null;
                if (below == null || !below.hasPort(portInst.getName())) {
                    // No net inside the instance is connected to the port (see getConnectedNet())
                    if (!isLeaf) {
                        b.addUnconnected(type);
                    }
                    continue;
                }
                if (b.connect(inst, portInst, below)) {
                    b.addBelow(inst, below);
                    b.addSources(portInst, below, below.sourceCount);
                }
            }
        }
        return b.build(epoch);
    }

    /** Collects the nets of a group and what they connect to. */
    private static final class Builder {
        private final EDIFNet first;
        /** The nets after the first */
        private ArrayList<EDIFNet> moreNets;
        /** All the nets, once there are many */
        private Set<EDIFNet> netSet;
        private int netCount = 1;
        private EDIFPortInst port;
        private ArrayList<EDIFPortInst> morePorts;
        private EDIFNet sourceNet;
        private EDIFCellInst sourceInst;
        private EDIFNetConnectivity sourceBelow;
        private int sourceCount;
        /** The connectivity of the nets inside instances that this is computed from */
        private ArrayList<EDIFNetConnectivity> below;
        /** The instance of each of these */
        private ArrayList<EDIFCellInst> belowInsts;
        /** Cells with a port that has no net inside, and a net of this group outside */
        private ArrayList<EDIFCell> unconnected;

        Builder(EDIFNet first) {
            this.first = first;
        }

        EDIFNet getNet(int i) {
            return i == 0 ? first : moreNets.get(i - 1);
        }

        void addNet(EDIFNet net) {
            if (net == first) {
                return;
            }
            if (moreNets == null) {
                moreNets = new ArrayList<>(2);
            } else if (netSet != null) {
                if (!netSet.add(net)) {
                    return;
                }
            } else {
                for (int i = 0; i < moreNets.size(); i++) {
                    if (moreNets.get(i) == net) {
                        return;
                    }
                }
                if (netCount == MANY_NETS) {
                    netSet = Collections.newSetFromMap(new IdentityHashMap<>());
                    netSet.addAll(moreNets);
                    netSet.add(net);
                }
            }
            moreNets.add(net);
            netCount++;
        }

        void addPort(EDIFPortInst portInst) {
            if (port == null) {
                port = portInst;
            } else {
                if (morePorts == null) {
                    morePorts = new ArrayList<>(2);
                }
                morePorts.add(portInst);
            }
        }

        void addSources(EDIFPortInst portInst, EDIFNetConnectivity portInstBelow, int count) {
            if (count == 0) {
                return;
            }
            if (sourceCount == 0) {
                if (portInstBelow == null) {
                    sourceNet = portInst.getNet();
                } else {
                    sourceInst = portInst.getCellInst();
                    sourceBelow = portInstBelow;
                }
            }
            sourceCount = Math.min(2, sourceCount + count);
        }

        void addBelow(EDIFCellInst inst, EDIFNetConnectivity c) {
            if (below == null) {
                below = new ArrayList<>(2);
                belowInsts = new ArrayList<>(2);
            }
            below.add(c);
            belowInsts.add(inst);
        }

        void addUnconnected(EDIFCell cell) {
            if (unconnected == null) {
                unconnected = new ArrayList<>(2);
            } else if (unconnected.get(unconnected.size() - 1) == cell) {
                return;
            }
            unconnected.add(cell);
        }

        /**
         * Adds the nets that a net of this group is connected to through an instance: those on
         * the other ports (of the instance) of the group that the net is connected to inside it.
         * As several nets of this group may be connected to that group, it is only to be counted
         * for one of them: the one on its first port that is connected on the instance.
         * @param inst The instance.
         * @param portInst The port inst of the instance on a net of this group.
         * @param below The connectivity of the net inside the instance on that port.
         * @return True if this is the port inst to count the group below for.
         */
        boolean connect(EDIFCellInst inst, EDIFPortInst portInst, EDIFNetConnectivity below) {
            Object belowPorts = below.ports;
            if (belowPorts instanceof EDIFPortInst) {
                // The port of the port inst
                return true;
            }
            String name = portInst.getName();
            boolean isFirst = true;
            for (EDIFPortInst belowPort : (EDIFPortInst[]) belowPorts) {
                EDIFNet net = getNetOn(inst, belowPort);
                if (net == null) {
                    continue;
                }
                addNet(net);
                if (isFirst && !belowPort.getName().equals(name)) {
                    // Counted from that net, which adds the nets on the other ports
                    return false;
                }
                isFirst = false;
            }
            return true;
        }

        EDIFNetConnectivity build(int epoch) {
            EDIFNetConnectivity existing = first.connectivity;
            if (existing != null && existing.epoch == epoch && existing.valid) {
                // Another thread computed it meanwhile
                return existing;
            }
            Object nets = first;
            if (moreNets != null) {
                EDIFNet[] array = new EDIFNet[netCount];
                for (int i = 0; i < netCount; i++) {
                    array[i] = getNet(i);
                }
                nets = array;
            }
            Object ports = port;
            if (morePorts != null) {
                EDIFPortInst[] array = new EDIFPortInst[morePorts.size() + 1];
                array[0] = port;
                for (int i = 1; i < array.length; i++) {
                    array[i] = morePorts.get(i - 1);
                }
                Arrays.sort(array, BY_NAME);
                ports = array;
            }
            EDIFNetConnectivity c = new EDIFNetConnectivity(nets, ports, sourceNet, sourceInst, sourceBelow,
                    sourceCount, epoch);
            // It must be dropped when what it was computed from changes
            if (below != null) {
                boolean isCurrent = true;
                for (EDIFNetConnectivity b : below) {
                    b.addDependent(c);
                    // Checked once it is a dependent: if dropped before, it does not know of this
                    isCurrent &= b.valid;
                }
                if (!isCurrent) {
                    // Another thread recorded one of these again meanwhile
                    c.valid = false;
                    return null;
                }
                for (int i = 0; i < below.size(); i++) {
                    // Saves finding this from there
                    below.get(i).above = new Above(belowInsts.get(i), c);
                }
            }
            if (unconnected != null) {
                for (EDIFCell cell : unconnected) {
                    cell.addConnectivityWatcher(c);
                }
            }
            for (int i = 0; i < netCount; i++) {
                EDIFNetConnectivity old = RECORDED.getAndSet(getNet(i), c);
                if (old != null && old.valid) {
                    // Nothing can be told to drop what was computed from it once it is replaced
                    invalidate(old);
                }
            }
            return c;
        }
    }

    private void addDependent(EDIFNetConnectivity dependent) {
        synchronized (this) {
            dependents = addTo(dependents, dependent);
        }
    }

    /**
     * Adds to a set of connectivity objects that is kept as null if empty, as the object if there
     * is one, or else as an ArrayList. Objects that are no longer valid are removed from time to
     * time.
     * @return The set with the object added.
     */
    @SuppressWarnings("unchecked")
    static Object addTo(Object set, EDIFNetConnectivity c) {
        if (set == null || set == c) {
            return c;
        }
        ArrayList<EDIFNetConnectivity> list;
        if (set instanceof EDIFNetConnectivity) {
            if (!((EDIFNetConnectivity) set).valid) {
                return c;
            }
            list = new ArrayList<>(2);
            list.add((EDIFNetConnectivity) set);
        } else {
            list = (ArrayList<EDIFNetConnectivity>) set;
            int size = list.size();
            if (list.get(size - 1) == c) {
                return list;
            }
            if (size >= 16 && (size & (size - 1)) == 0) {
                list.removeIf(d -> !d.valid);
            }
        }
        list.add(c);
        return list;
    }

    /**
     * Drops what is recorded for a net that changed, and what the netlist of the net keeps.
     * @param net A net that had a port inst added, removed or changed.
     */
    static void netChanged(EDIFNet net) {
        EDIFNetConnectivity c = RECORDED.getAndSet(net, null);
        if (c != null) {
            invalidate(c);
        }
        EDIFCell cell = net.getParentCell();
        if (cell != null) {
            cell.netlistChanged();
        }
    }

    /**
     * Drops what is recorded for a net that a port inst was added to (or that has a port inst
     * which changed), and for the nets that the port inst connects it to: those on the other
     * ports of its cell instance that are connected, inside it, to the port of the port inst.
     * @param net The net.
     * @param portInst The port inst on the net.
     */
    static void portInstChanged(EDIFNet net, EDIFPortInst portInst) {
        netChanged(net);
        EDIFCellInst inst = portInst.getCellInst();
        EDIFCell type = (inst != null) ? inst.getCellType() : null;
        String name = portInst.getName();
        if (type == null || name == null) {
            return;
        }
        EDIFNet inner = getConnectedNet(type, name);
        // If nothing is recorded for the net inside, nothing was computed from it
        EDIFNetConnectivity below = (inner != null) ? inner.connectivity : null;
        if (below != null && below.ports instanceof EDIFPortInst[]) {
            for (EDIFPortInst belowPort : (EDIFPortInst[]) below.ports) {
                EDIFNet other = getNetOn(inst, belowPort);
                if (other != null && other != net) {
                    netChanged(other);
                }
            }
        }
    }

    /**
     * Drops what is recorded for the nets on the ports of a cell instance.
     * @param inst A cell instance that changed its cell type.
     */
    static void cellInstChanged(EDIFCellInst inst) {
        EDIFPortInst[] portInsts = inst.getPortInstArray();
        if (portInsts != null) {
            for (EDIFPortInst portInst : portInsts) {
                if (portInst == null) {
                    break;
                }
                EDIFNet net = portInst.getNet();
                if (net != null) {
                    netChanged(net);
                }
            }
        }
        EDIFCell cell = inst.getParentCell();
        if (cell != null) {
            cell.netlistChanged();
        }
    }

    /**
     * Drops connectivity that something changed for, and all that was computed from it.
     * @param set An EDIFNetConnectivity, or a set of them as kept by
     * {@link #addTo(Object, EDIFNetConnectivity)}.
     */
    @SuppressWarnings("unchecked")
    static void invalidate(Object set) {
        ArrayDeque<Object> todo = null;
        while (set != null) {
            if (set instanceof ArrayList) {
                if (todo == null) {
                    todo = new ArrayDeque<>();
                }
                todo.addAll((ArrayList<EDIFNetConnectivity>) set);
            } else {
                EDIFNetConnectivity c = (EDIFNetConnectivity) set;
                if (c.valid) {
                    c.valid = false;
                    Object other = c.forgetNets();
                    Object dependents;
                    synchronized (c) {
                        dependents = c.dependents;
                        c.dependents = null;
                    }
                    if (other != null && dependents != null) {
                        if (todo == null) {
                            todo = new ArrayDeque<>();
                        }
                        todo.add(other);
                    } else if (other != null) {
                        dependents = other;
                    }
                    if (dependents != null) {
                        set = dependents;
                        continue;
                    }
                }
            }
            set = (todo == null) ? null : todo.poll();
        }
    }

    /**
     * Removes this from its nets.
     * @return Other valid connectivity found on its nets (computed by another thread at the same
     * time, or for another netlist), which is to be dropped too: null, or a set as kept by
     * {@link #addTo(Object, EDIFNetConnectivity)}.
     */
    private Object forgetNets() {
        if (nets instanceof EDIFNet) {
            return forgetNet((EDIFNet) nets, null);
        }
        Object others = null;
        for (EDIFNet net : (EDIFNet[]) nets) {
            others = forgetNet(net, others);
        }
        return others;
    }

    private Object forgetNet(EDIFNet net, Object others) {
        if (!RECORDED.compareAndSet(net, this, null)) {
            EDIFNetConnectivity c = net.connectivity;
            if (c != null && c != this && c.valid) {
                others = addTo(others, c);
            }
        }
        return others;
    }
}

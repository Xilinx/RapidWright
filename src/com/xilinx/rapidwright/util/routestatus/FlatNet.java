/*
 * Copyright (c) 2026, Advanced Micro Devices, Inc.
 * All rights reserved.
 *
 * Author: Chris Lavin, Advanced Micro Devices, Inc.
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

package com.xilinx.rapidwright.util.routestatus;

import static com.xilinx.rapidwright.util.routestatus.RouteStatusNetlist.getLeafPortInsts;
import static com.xilinx.rapidwright.util.routestatus.RouteStatusNetlist.getSegments;
import static com.xilinx.rapidwright.util.routestatus.RouteStatusNetlist.getSitePinFeeding;
import static com.xilinx.rapidwright.util.routestatus.RouteStatusNetlist.hasPhysicalPin;
import static com.xilinx.rapidwright.util.routestatus.SiteRouting.isClockBufferSite;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import com.xilinx.rapidwright.design.Design;
import com.xilinx.rapidwright.design.SiteInst;
import com.xilinx.rapidwright.device.BELPin;
import com.xilinx.rapidwright.device.Node;
import com.xilinx.rapidwright.device.SitePin;
import com.xilinx.rapidwright.edif.EDIFHierCellInst;
import com.xilinx.rapidwright.edif.EDIFHierNet;
import com.xilinx.rapidwright.edif.EDIFHierPortInst;
import com.xilinx.rapidwright.edif.EDIFNet;
import com.xilinx.rapidwright.edif.EDIFNetlist;
import com.xilinx.rapidwright.util.Utils;

/** A flat (parent) net, i.e. a set of electrically connected net segments. */
final class FlatNet extends Connections {

    /** How a flat net got its name, in increasing order of precedence */
    static final int NAMED_BY_SEGMENT = 0;
    static final int NAMED_BY_DANGLING_OUTPUT = 1;
    static final int NAMED_BY_INOUT = 2;
    static final int NAMED_BY_DANGLING_INOUT = 3;
    static final int NAMED_BY_DRIVER = 4;

    /** The prefix of the names of the types of interconnect tiles, whose wires are general routing */
    private static final String INTERCONNECT_TILE_PREFIX = "INT";

    /** The prefix of the names of the types of (Versal) interface sites, which routing passes through */
    private static final String INTERFACE_SITE_PREFIX = "IRI";

    /** Hierarchical prefix (instance path plus '/', or empty) and net naming this flat net */
    String prefix;
    EDIFNet net;
    /** Precedence of the name (see NAMED_BY_*) */
    int namedBy;
    int segments;
    /** Set when merged into another flat net by a feedthrough */
    FlatNet mergedInto;
    /** True if created inside a macro primitive (e.g. DSP58), whose internals are expanded */
    boolean insideMacro;
    /** Index of this flat net's entry in the route status engine, if any */
    int entry = -1;
    /**
     * A segment connected to nothing but an output port of its cell (as left by removing a
     * driver), which names a net without a driver
     */
    String undrivenPrefix;
    EDIFNet undrivenNet;
    /** The segment inside a LUT6_2 macro with the smallest (local) name, which names a net without a driver */
    String macroPrefix;
    EDIFNet macroNet;
    /** The leaf loads on segments inside LUT6_2 macros */
    int macroLeafLoads;

    /** The pins on leaf cells, see {@link #getLeafPins(Design)} (or null) */
    private List<LeafPin> leafPins;

    FlatNet(String prefix, EDIFNet net) {
        this.prefix = prefix;
        this.net = net;
    }

    String getName() {
        return prefix.isEmpty() ? net.getName() : prefix + net.getName();
    }

    /**
     * Names the flat net after the given segment if it takes precedence over the current name.
     * Ties (e.g. multiple drivers) are broken by name to be independent of traversal order.
     */
    private void name(int namedBy, String prefix, EDIFNet net) {
        if (namedBy < this.namedBy) {
            return;
        }
        if (namedBy == this.namedBy && (prefix + net.getName()).compareTo(getName()) >= 0) {
            return;
        }
        this.namedBy = namedBy;
        this.prefix = prefix;
        this.net = net;
    }

    /**
     * Segment fed by an output port of a child with nothing but the port inside; it names a
     * flat net without any leaf pin (unless something else does)
     */
    String portOnlyPrefix;
    EDIFNet portOnlyNet;

    /**
     * For a flat net without leaf pins (so far): whether a segment is fed by an output port of
     * a child but not connected to an output port of its cell (where the output ports from a
     * port only output end), and the segment with the smallest (local) name
     */
    boolean hasOutputChainEnd;
    String smallestPrefix;
    EDIFNet smallestNet;

    private void nameSmallest(String prefix, EDIFNet net) {
        if (net == null || leafDrivers + leafLoads + leafInouts > 0) {
            return;
        }
        int c = (smallestNet == null) ? -1 : net.getName().compareTo(smallestNet.getName());
        if (c < 0 || (c == 0 && (prefix + net.getName()).compareTo(smallestPrefix + smallestNet.getName()) < 0)) {
            smallestPrefix = prefix;
            smallestNet = net;
        }
    }

    private void namePortOnly(String prefix, EDIFNet net) {
        if (net == null) {
            return;
        }
        if (portOnlyNet == null || (prefix + net.getName()).compareTo(portOnlyPrefix + portOnlyNet.getName()) < 0) {
            portOnlyPrefix = prefix;
            portOnlyNet = net;
        }
    }

    /** Applies the naming by a port only output, once all segments have been added. */
    void finishNaming() {
        if (portOnlyNet != null && namedBy == NAMED_BY_SEGMENT && leafDrivers + leafLoads + leafInouts == 0) {
            if (hasOutputChainEnd && smallestNet != null && !hasTopPorts()) {
                // When the output ports from the port only output end in a segment (feeding
                // other cells), the segment with the smallest name names the net
                name(NAMED_BY_DANGLING_OUTPUT, smallestPrefix, smallestNet);
            } else {
                name(NAMED_BY_DANGLING_OUTPUT, portOnlyPrefix, portOnlyNet);
            }
        } else if (namedBy == NAMED_BY_SEGMENT && leafLoads + leafInouts > 0 && leafDrivers == 0
                && placedPortDrivers + unplacedPortDrivers + blackBoxDrivers == 0) {
            if (undrivenNet != null) {
                prefix = undrivenPrefix;
                net = undrivenNet;
            } else if (macroNet != null) {
                prefix = macroPrefix;
                net = macroNet;
            }
        }
        portOnlyPrefix = null;
        portOnlyNet = null;
        undrivenPrefix = null;
        undrivenNet = null;
        macroPrefix = null;
        macroNet = null;
        smallestPrefix = null;
        smallestNet = null;
    }

    private void nameMacro(String prefix, EDIFNet net) {
        if (net == null) {
            return;
        }
        // (the smallest local name, then full name)
        int c = (macroNet == null) ? -1 : net.getName().compareTo(macroNet.getName());
        if (c < 0 || (c == 0 && (prefix + net.getName()).compareTo(macroPrefix + macroNet.getName()) < 0)) {
            macroPrefix = prefix;
            macroNet = net;
        }
    }

    private void nameUndriven(String prefix, EDIFNet net) {
        if (net == null) {
            return;
        }
        if (undrivenNet == null || (prefix + net.getName()).compareTo(undrivenPrefix + undrivenNet.getName()) < 0) {
            undrivenPrefix = prefix;
            undrivenNet = net;
        }
    }

    /** Adds the tallies of one net segment (shared by concurrently visited instances). */
    synchronized void add(RouteStatusNetlist.Tally t, String prefix, EDIFNet net) {
        addUnsynchronized(t, prefix, net);
    }

    /** Adds the tallies of one net segment, for a flat net not yet shared with other tasks. */
    void addUnsynchronized(RouteStatusNetlist.Tally t, String prefix, EDIFNet net) {
        if (t.portOnlyOutput) {
            namePortOnly(prefix, net);
        }
        if (t.ownPortOnlyOutput) {
            nameUndriven(prefix, net);
        }
        if (t.insideMacro && net.getParentCell().getName().equals("LUT6_2")) {
            nameMacro(prefix, net);
            macroLeafLoads += t.leafLoads;
        }
        segments++;
        addConnections(t);
        hasOutputChainEnd |= t.childOutputOnly;
        nameSmallest(prefix, net);
        if (t.namedBy > NAMED_BY_SEGMENT) {
            name(t.namedBy, prefix, net);
        }
    }

    void absorb(FlatNet other) {
        segments += other.segments;
        addConnections(other);
        insideMacro &= other.insideMacro;
        name(other.namedBy, other.prefix, other.net);
        namePortOnly(other.portOnlyPrefix, other.portOnlyNet);
        nameUndriven(other.undrivenPrefix, other.undrivenNet);
        nameMacro(other.macroPrefix, other.macroNet);
        macroLeafLoads += other.macroLeafLoads;
        hasOutputChainEnd |= other.hasOutputChainEnd;
        if (other.leafDrivers + other.leafLoads + other.leafInouts == 0) {
            nameSmallest(other.smallestPrefix, other.smallestNet);
        }
        other.mergedInto = this;
    }

    FlatNet find() {
        FlatNet n = this;
        while (n.mergedInto != null) {
            n = n.mergedInto;
        }
        return n;
    }

    /**
     * Checks whether the placed pins with a physical pin are in more than one site, i.e. the
     * net needs routing between sites.
     */
    boolean hasPinsInMultipleSites(Design design) {
        if (!multipleSites) {
            return false;
        }
        if (hasPhysicalPin(firstCell, firstPin) && hasPhysicalPin(secondCell, secondPin)) {
            return true;
        }
        // Rare: look at all the pins
        SiteInst site = null;
        for (LeafPin pin : getLeafPins(design)) {
            if (!pin.isPlaced() || !pin.hasPhysicalPin()) {
                continue;
            }
            if (site == null) {
                site = pin.cell.getSiteInst();
            } else if (site != pin.cell.getSiteInst()) {
                return true;
            }
        }
        return false;
    }

    /**
     * Gets the sites of the placed pins with a physical pin, adding them to the given set
     * until it has more than one.
     */
    void addPhysicalPinSites(Design design, Set<SiteInst> sites) {
        if (firstSite == null || sites.size() > 1) {
            return;
        }
        if (hasPhysicalPin(firstCell, firstPin) && (!multipleSites || hasPhysicalPin(secondCell, secondPin))) {
            sites.add(firstSite);
            if (multipleSites) {
                // Any other site will do
                sites.add(null);
            }
            return;
        }
        for (LeafPin pin : getLeafPins(design)) {
            if (pin.isPlaced() && pin.hasPhysicalPin()) {
                sites.add(pin.cell.getSiteInst());
                if (sites.size() > 1) {
                    return;
                }
            }
        }
    }

    /**
     * Checks whether a placed leaf load is in another site than the (first) placed leaf driver.
     */
    boolean hasLoadOutsideDriverSite(Design design) {
        List<LeafPin> pins = getLeafPins(design);
        SiteInst site = null;
        for (LeafPin pin : pins) {
            if (pin.isOutput() && pin.isPlaced()) {
                site = pin.cell.getSiteInst();
                break;
            }
        }
        if (site == null) {
            return false;
        }
        for (LeafPin pin : pins) {
            if (!pin.isOutput() && pin.isPlaced() && pin.cell.getSiteInst() != site) {
                return true;
            }
        }
        return false;
    }

    /** Control pin kinds, see {@link #getControlPinLoads(Design)} */
    static final int ENABLE_PIN = 1;
    static final int RESET_PIN = 2;

    /**
     * Gets the kinds of control pins (clock, write or clock buffer enable, set/reset) among the placed leaf
     * loads, whose site pins follow from the placement alone.
     * @return A combination of {@link #ENABLE_PIN} and {@link #RESET_PIN}.
     */
    int getControlPinLoads(Design design) {
        int kinds = 0;
        for (LeafPin pin : getLeafPins(design)) {
            if (pin.isOutput() || !pin.isPlaced()) {
                continue;
            }
            boolean ff = pin.cell.getBEL().isFF();
            for (String physPin : pin.getPhysicalPins()) {
                if (ff ? physPin.equals("CE") : (physPin.startsWith("WE") || physPin.equals("CE"))) {
                    kinds |= ENABLE_PIN;
                } else if (ff ? physPin.equals("SR") : physPin.startsWith("RST")) {
                    kinds |= RESET_PIN;
                }
            }
        }
        return kinds;
    }

    /**
     * Checks whether any placed load in a site that is neither a slice, a clock buffer nor the
     * driver's has an input site pin reached through general routing (not only through a
     * dedicated connection, e.g. from an OLOGIC to its IOB, or a DSP cascade).
     */
    boolean hasRoutedHardBlockLoad(Design design) {
        return hasRoutedHardBlockLoad(design, Collections.emptySet());
    }

    /** As {@link #hasRoutedHardBlockLoad(Design)}, ignoring loads in some sites */
    boolean hasRoutedHardBlockLoad(Design design, Set<SiteInst> ignoredSites) {
        SiteInst driverSite = null;
        for (LeafPin pin : getLeafPins(design)) {
            if (pin.isOutput() && pin.isPlaced()) {
                driverSite = pin.cell.getSiteInst();
            }
        }
        for (LeafPin pin : getLeafPins(design)) {
            if (pin.isOutput() || !pin.isPlaced()) {
                continue;
            }
            SiteInst si = pin.cell.getSiteInst();
            if (si == driverSite || Utils.isSLICE(si) || isClockBufferSite(si) || ignoredSites.contains(si)) {
                // (nor clock buffers, whose inputs are reached through clock routing)
                continue;
            }
            for (String physPin : pin.getPhysicalPins()) {
                BELPin belPin = pin.cell.getBEL().getPin(physPin);
                String sitePin = (belPin == null) ? null : getSitePinFeeding(si, belPin);
                Node node = (sitePin == null) ? null : si.getSite().getConnectedNode(si.getPrimarySitePinName(sitePin));
                if (node != null && isReachedByGeneralRouting(node)) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Checks whether a site pin node is reached through general routing: whether the nodes
     * leading to it (within a few steps, not through the pins of other sites, except Versal
     * interface sites) include interconnect tile wires. Otherwise it is only reached through
     * dedicated connections (e.g. from an OLOGIC to its IOB, or clock routing to a BUFG).
     */
    static boolean isReachedByGeneralRouting(Node node) {
        if (node == null) {
            return false;
        }
        List<Node> frontier = Collections.singletonList(node);
        Set<Node> visited = new HashSet<>(frontier);
        for (int depth = 0; depth < 4 && !frontier.isEmpty(); depth++) {
            List<Node> next = new ArrayList<>();
            for (Node n : frontier) {
                for (Node up : n.getAllUphillNodes()) {
                    if (!visited.add(up)) {
                        continue;
                    }
                    if (up.getTile().getTileTypeEnum().name().startsWith(INTERCONNECT_TILE_PREFIX)) {
                        return true;
                    }
                    SitePin sitePin = up.getSitePin();
                    String siteType = (sitePin == null) ? null : sitePin.getSite().getSiteTypeEnum().name();
                    if (siteType == null || siteType.startsWith(INTERFACE_SITE_PREFIX)) {
                        next.add(up);
                    }
                }
            }
            frontier = next;
        }
        return false;
    }

    /** Checks whether any load pin (placed or not) has a physical pin, i.e. needs a connection. */
    boolean hasLoadWithPhysicalPin(Design design) {
        for (LeafPin pin : getLeafPins(design)) {
            if (!pin.isOutput() && (!pin.isPlaced() || pin.hasPhysicalPin())) {
                return true;
            }
        }
        return false;
    }

    /**
     * Checks whether all loads are CLRB_LEAF pins of clock buffers (e.g. MBUFGCE), which have
     * no physical pin: they reach the CLR_B pins of the leaf clock dividers through routing.
     */
    boolean hasOnlyLeafClockClearLoads(Design design) {
        boolean any = false;
        for (LeafPin pin : getLeafPins(design)) {
            if (pin.isOutput()) {
                continue;
            }
            if (!pin.getName().equals("CLRB_LEAF") || !pin.pin.getCellType().getName().startsWith("MBUFG")) {
                return false;
            }
            any = true;
        }
        return any;
    }

    /** Checks whether any placed load is on a LUT input. */
    boolean hasLoadOnLutInput(Design design) {
        for (LeafPin pin : getLeafPins(design)) {
            if (!pin.isOutput() && pin.isPlaced() && pin.cell.getBEL().isLUT()) {
                return true;
            }
        }
        return false;
    }

    /** Checks whether any placed load pin is on a BEL pin that is fed from a site pin. */
    boolean hasLoadOnSitePin(Design design) {
        for (LeafPin pin : getLeafPins(design)) {
            if (pin.isOutput() || !pin.isPlaced()) {
                continue;
            }
            for (String physPin : pin.getPhysicalPins()) {
                BELPin belPin = pin.cell.getBEL().getPin(physPin);
                if (belPin != null && belPin.getConnectedSitePinName() != null) {
                    return true;
                }
            }
        }
        return false;
    }

    /** Gets the segments (hierarchical nets) of this flat net. */
    List<EDIFHierNet> getSegments(Design design) {
        EDIFNetlist netlist = design.getNetlist();
        String instName = prefix.isEmpty() ? "" : prefix.substring(0, prefix.length() - 1);
        EDIFHierCellInst inst;
        // Looking up hierarchical objects by name is not thread safe
        synchronized (netlist) {
            inst = instName.isEmpty() ? netlist.getTopHierCellInst() : netlist.getHierCellInstFromName(instName);
        }
        return RouteStatusNetlist.getSegments(new EDIFHierNet(inst, net));
    }

    /**
     * Gets the pins on leaf cells of all the segments of this flat net, with their cells. They are kept
     * (except for static nets, which may be huge) until {@link #releaseLeafPins()}, as the checks of a
     * net often need them more than once.
     */
    List<LeafPin> getLeafPins(Design design) {
        if (leafPins != null) {
            return leafPins;
        }
        List<LeafPin> pins = new ArrayList<>();
        for (EDIFHierNet hn : getSegments(design)) {
            for (EDIFHierPortInst p : getLeafPortInsts(hn)) {
                pins.add(new LeafPin(p, p.getPhysicalCell(design)));
            }
        }
        if (staticType == null) {
            leafPins = pins;
        }
        return pins;
    }

    /** Releases the pins kept by {@link #getLeafPins(Design)}. */
    void releaseLeafPins() {
        leafPins = null;
    }

    boolean hasTopPorts() {
        return placedPortDrivers + placedPortLoads + unplacedPortDrivers + unplacedPortLoads > 0;
    }

    /** Checks whether a driver (pin of a leaf cell, or top level port) is placed. */
    boolean hasPlacedDriver() {
        return leafDrivers > unplacedLeafDrivers || placedPortDrivers > 0;
    }

    /** Gets the number of loads: pins of leaf cells (and inout pins), and placed top level ports. */
    int getLoads() {
        return leafLoads + leafInouts + placedPortLoads;
    }

    /**
     * Checks whether this is a single segment connected to nothing but a static source, and is
     * named as a bit of a bus (as netlists written by e.g. Yosys have many): Vivado does not merge it
     * into the static net, but reports it as a net internally routed (whether the source is placed
     * or not).
     */
    boolean isUnloadedStaticBusBit() {
        return staticType != null && segments == 1 && getLoads() == 0 && blackBoxLoads == 0 && !hasTopPorts()
                && RouteStatusNetlist.isBusBit(getName());
    }
}

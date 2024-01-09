/*
 * Copyright (c) 2023, Advanced Micro Devices, Inc.
 * All rights reserved.
 *
 * Author: Eddie Hung, Advanced Micro Devices, Inc.
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

package com.xilinx.rapidwright.eco;

import com.xilinx.rapidwright.design.Cell;
import com.xilinx.rapidwright.design.Design;
import com.xilinx.rapidwright.design.DesignTools;
import com.xilinx.rapidwright.design.Net;
import com.xilinx.rapidwright.design.SiteInst;
import com.xilinx.rapidwright.design.SitePinInst;
import com.xilinx.rapidwright.design.Unisim;
import com.xilinx.rapidwright.device.BEL;
import com.xilinx.rapidwright.device.BELClass;
import com.xilinx.rapidwright.device.BELPin;
import com.xilinx.rapidwright.device.Site;
import com.xilinx.rapidwright.device.SitePIP;
import com.xilinx.rapidwright.edif.EDIFCell;
import com.xilinx.rapidwright.edif.EDIFCellInst;
import com.xilinx.rapidwright.edif.EDIFHierCellInst;
import com.xilinx.rapidwright.edif.EDIFHierNet;
import com.xilinx.rapidwright.edif.EDIFHierPortInst;
import com.xilinx.rapidwright.edif.EDIFNet;
import com.xilinx.rapidwright.edif.EDIFNetlist;
import com.xilinx.rapidwright.edif.EDIFPort;
import com.xilinx.rapidwright.edif.EDIFPortInst;
import com.xilinx.rapidwright.edif.EDIFTools;
import com.xilinx.rapidwright.rwroute.RouterHelper;
import com.xilinx.rapidwright.util.Pair;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * A collection of methods for performing ECO operations.
 *
 * NOTE: These methods assume the EDIF (logical) netlist is unfolded.
 *
 *       A folded netlist containing two instances of the 'foo' cell with instances
 *       names 'u1' and 'u2' would have the following hierarchy: 'top/u1(foo)' and
 *       'top/u2(foo)'. Further, assume that 'foo' contains two leaf cell instances
 *       'lut1' and 'lut2' for a total of four leaf cells in the netlist.
 *
 *       Modifying (e.g. connecting, disconnecting, removing, etc.) instance
 *       'top/u1(foo)/lut1' would cause the 'lut1' instance from cell 'foo' to be
 *       modified, which would have the un-obvious effect of also modifying
 *       'top/u2(foo)/lut1' from the netlist too.
 *       With an unfolded netlist, the 'foo' cell would be expected to be replaced
 *       by two (identical) cells 'foo1' and 'foo2', each instantiated once. Here,
 *       modifying 'top/u1(foo1)/lut1' would no longer affect 'top/u2(foo2)/lut1'.
 */
public class ECOTools {

    private static int UNIQUE_COUNT = 0;

    /**
     * Given a list of EDIFHierPortInst objects, disconnect these pins from their current nets.
     * This method modifies the EDIF (logical) netlist as well as the place-and-route (physical)
     * state, and is modelled on Vivado's <TT>disconnect_net -pinlist</TT> command.
     * @param design The design where the pin(s) are instantiated.
     * @param pins A variable array of hierarchical pins for disconnection.
     */
    public static void disconnectNet(Design design, EDIFHierPortInst... pins) {
        disconnectNet(design, Arrays.asList(pins), null);
    }
    
    /**
     * Given a list of EDIFHierPortInst objects, disconnect these pins from their current nets.
     * This method modifies the EDIF (logical) netlist as well as the place-and-route (physical)
     * state, and is modelled on Vivado's <TT>disconnect_net -pinlist</TT> command.
     * @param design The design where the pin(s) are instantiated.
     * @param pins A list of hierarchical pins for disconnection.
     */
    public static void disconnectNet(Design design,
                                     List<EDIFHierPortInst> pins) {
        disconnectNet(design, pins, null);
    }

    /**
     * Given a list of EDIFHierPortInst objects, disconnect these pins from their current nets.
     * This method modifies the EDIF (logical) netlist as well as the place-and-route (physical)
     * state, and is modelled on Vivado's <TT>disconnect_net -pinlist</TT> command.
     * @param design The design where the pin(s) are instantiated.
     * @param pins A list of hierarchical pins for disconnection.
     * @param deferredRemovals An optional map that, if passed in non-null will be populated with
     *                         site pins marked for removal.  The map allows for persistent tracking
     *                         if this method is called many times as the process is expensive
     *                         without batching.  This map can also allow SitePinInst objects to be
     *                         reused by {@link #connectNet(Design, Map, Map)}.
     */
    public static void disconnectNet(Design design,
                                     List<EDIFHierPortInst> pins,
                                     Map<Net, Set<SitePinInst>> deferredRemovals) {
        for (EDIFHierPortInst ehpi : pins) {
            EDIFHierNet ehn = ehpi.getHierarchicalNet();
            List<EDIFHierPortInst> leafPortInsts;
            EDIFHierNet internalEhn = ehpi.getInternalNet();
            if (internalEhn == null) {
                // Pin does not have an internal net (the net on the other side
                // of the port, inside the cell)
                EDIFCell ec = ehpi.getCellType();
                if (ec.isLeafCellOrBlackBox()) {
                    // Pin is a leaf cell
                    if (ehpi.isInput()) {
                        // Input leaf port, meaning that this is the only pin affected
                        leafPortInsts = Collections.singletonList(ehpi);
                    } else {
                        // Output leaf port, thus all sinks are affected
                        assert (ehpi.isOutput());
                        leafPortInsts = ehn.getLeafHierPortInsts(true);
                    }
                } else {
                    // Pin must be unconnected (thus cannot have any leaf ports)
                    leafPortInsts = Collections.emptyList();
                }
            } else {
                // Not a leaf pin

                // Check downstream
                Set<EDIFHierNet> visitedNets = new HashSet<>();
                visitedNets.add(ehn);

                leafPortInsts = internalEhn.getLeafHierPortInsts(true, visitedNets);

                if (ehpi.isInput()) {
                    // Pin is an input, cannot contain a source, remove all downstream sinks
                } else {
                    // Pin is an output or inout, check if downstream contains a source
                    boolean sourcePresent = false;
                    for (EDIFHierPortInst leafPortInst : leafPortInsts) {
                        if (leafPortInst.isOutput()) {
                            sourcePresent = true;
                            break;
                        }
                    }

                    if (sourcePresent) {
                        // Downstream does contains a source, remove everything upstream instead
                        visitedNets.clear();
                        visitedNets.add(internalEhn);
                        leafPortInsts = ehn.getLeafHierPortInsts(false, visitedNets);
                    } else {
                        // Downstream contains only sinks, remove them all
                    }
                }
            }

            // Remove all affected SitePinInsts
            for (EDIFHierPortInst leafEhpi : leafPortInsts) {
                // Do nothing if we're disconnecting an input, but this leaf pin is not an input
                if (ehpi.isInput() && !leafEhpi.isInput())
                    continue;

                Cell cell = leafEhpi.getPhysicalCell(design);
                for (SitePinInst spi : cell.getAllSitePinsFromLogicalPin(leafEhpi.getPortInst().getName(), null)) {
                    DesignTools.handlePinRemovals(spi, deferredRemovals);
                }
            }

            EDIFNet en = ehn.getNet();
            // Detach from net, but do not detach from cell instance since
            // typically we would want to connect it to another net
            en.removePortInst(ehpi.getPortInst());
        }
    }

    /**
     * Given a list of strings with one more space-separated pins, disconnect these pins from
     * their current nets.
     * This method modifies the EDIF (logical) netlist as well as the place-and-route (physical)
     * state, and is modelled on Vivado's <TT>disconnect_net -pinlist</TT> command.
     * @param design The design where the pin(s) are instantiated.
     * @param pins A list of hierarchical pins for disconnection.
     */
    public static void disconnectNetPath(Design design,
                                         List<String> pins) {
        disconnectNetPath(design, pins, null);
    }

    /**
     * Given a list of strings with one more space-separated pins, disconnect these pins from
     * their current nets.
     * This method modifies the EDIF (logical) netlist as well as the place-and-route (physical)
     * state, and is modelled on Vivado's <TT>disconnect_net -pinlist</TT> command.
     * @param design The design where the pin(s) are instantiated.
     * @param pins A list of hierarchical pins for disconnection.
     * @param deferredRemovals An optional map that, if passed in non-null will be populated with
     *                         site pins marked for removal.  The map allows for persistent tracking
     *                         if this method is called many times as the process is expensive
     *                         without batching.  This map can also allow SitePinInst objects to be
     *                         reused by {@link #connectNet(Design, Map, Map)}.
     */
    public static void disconnectNetPath(Design design,
                                         List<String> pins,
                                         Map<Net, Set<SitePinInst>> deferredRemovals) {
        final EDIFNetlist netlist = design.getNetlist();
        List<EDIFHierPortInst> pinObjects = new ArrayList<>(pins.size());
        for (String entry : pins) {
            for (String pin : entry.split(" ")) {
                EDIFHierPortInst ehpi = netlist.getHierPortInstFromName(pin);
                if (ehpi == null) {
                    int pos = pin.lastIndexOf(EDIFTools.EDIF_HIER_SEP);
                    String path = pin.substring(0, pos);
                    EDIFHierCellInst ehci = netlist.getHierCellInstFromName(path);
                    if (ehci == null) {
                        throw new RuntimeException("ERROR: Unable to find inst '" + path + "' corresponding to pin '" + pin + "'");
                    }
                    String name = pin.substring(pos + 1);
                    EDIFCell cell = ehci.getCellType();
                    EDIFPort ep = cell.getPort(name);
                    if (ep == null) {
                        ep = cell.getPort(EDIFTools.getRootBusName(name, false));
                    }
                    if (ep == null) {
                        throw new RuntimeException("ERROR: Unable to find port '" + name + "' on inst '" + path + "' corresponding to pin '" + pin + "'");
                    }

                    // Cell inst exists, as does the port on its cell type, but port inst
                    // does not because it is not connected to anything. Nothing to be done.
                    continue;
                }

                EDIFNet en = ehpi.getNet();
                if (en == null) {
                    System.err.println("WARNING: Pin '" + ehpi + "' is not connected to a net.");
                    throw new RuntimeException(pin);
                }

                pinObjects.add(ehpi);
            }
        }
        disconnectNet(design, pinObjects, deferredRemovals);
    }

    /**
     * Given a map of EDIFHierNet to list of EDIFHierPortInst object(s), connect the latter pins to
     * the former net.
     * This method modifies the EDIF (logical) netlist as well as the place-and-route (physical)
     * state, and is modelled on Vivado's <TT>connect_net -hier -net_object_list</TT> command.
     * @param design The design where the net(s) and pin(s) are instantiated.
     * @param netToPortInsts A map of hierarchical nets and pins for connection.
     * @param deferredRemovals An optional map that, if passed in non-null will allow any SitePinInst
     *                         objects deferred previously for removal to be reused for new connections.
     *                         See {@link #disconnectNet(Design, List, Map)}.
     *
     * By default, this method will throw a RuntimeException if there is a mismatch between
     * the net connected to logical pins (EDIFHierPortInst) and the net connected to its physical pin
     * (SitePinInst). The Java property "rapidwright.ecotools.connectNet.warnIfCellInstStartsWith"
     * allows such pins with an EDIFCellInst name that start with its value to be demoted from an
     * RuntimeException to a printed warning. An example use case for this feature would be if
     * it is known that the conflicting logical pin will be removed later.
     */
    public static void connectNet(Design design,
                                  Map<EDIFHierNet, List<EDIFHierPortInst>> netToPortInsts,
                                  Map<Net, Set<SitePinInst>> deferredRemovals) {
        final Map<EDIFHierNet, EDIFHierPortInst> netToSourcePortInst = new HashMap<>();
        netToPortInsts.entrySet().removeIf((e) -> {
            List<EDIFHierPortInst> portInsts = e.getValue();
            portInsts.removeIf((ehpi) -> {
                if (!ehpi.isOutput()) {
                    return false;
                }
                EDIFHierPortInst oldValue = netToSourcePortInst.put(e.getKey(), ehpi);
                assert(oldValue == null);
                return true;
            });
            // Keep an entry inside netToPortInsts, even if portInsts is empty, so that
            // the physical netlist can still be updated
            return false;
        });

        if (!netToSourcePortInst.isEmpty()) {
            connectNetSource(design, netToSourcePortInst, deferredRemovals);
        }

        // Modify the logical netlist
        for (Map.Entry<EDIFHierNet,List<EDIFHierPortInst>> e : netToPortInsts.entrySet()) {
            EDIFHierNet ehn = e.getKey();
            EDIFNet en = ehn.getNet();
            List<EDIFHierPortInst> portInsts = e.getValue();

            for (EDIFHierPortInst ehpi : portInsts) {
                if (ehpi.isOutput()) {
                    for (EDIFHierPortInst src : ehn.getLeafHierPortInsts(true, false)) {
                        System.err.println("WARNING: Net '" + ehn.getHierarchicalNetName() + "' already has an output pin '" +
                                src + "'. Replacing with new pin '" + ehpi + "'.");
                        Cell cell = src.getPhysicalCell(design);
                        for (SitePinInst spi : cell.getAllSitePinsFromLogicalPin(src.getPortInst().getName(), null)) {
                            DesignTools.handlePinRemovals(spi, deferredRemovals);
                        }
                        src.getNet().removePortInst(src.getPortInst());
                    }
                }
                if (ehn.getHierarchicalInst().equals(ehpi.getHierarchicalInst())) {
                    // Attach if port inst not attached by above
                    EDIFPortInst epi = ehpi.getPortInst();
                    if (!en.getPortInsts().contains(epi)) {
                        en.addPortInst(epi);
                    }
                } else {
                    // Use a unique baseName derived from the net name, to avoid
                    // name collisions with bus nets -- e.g. when baseName is 'foo'
                    // it may be possible that some cell in the hierarchy may contain
                    // a bus net 'foo' -- represented only by a collection of
                    // possibly-arbitrarily-indexed single-bit nets: 'foo[i]', 'foo[j]',
                    // 'foo[k]', etc. making it impractical to identify if a bus net exists.
                    String baseName = ehn.getNet().getName() + EDIFTools.getUniqueSuffix();
                    EDIFTools.connectPortInstsThruHier(ehn, ehpi, baseName);
                }
            }
        }

        final EDIFNetlist netlist = design.getNetlist();
        netlist.resetParentNetMap();

        // Modify the physical netlist
        EDIFCell ecGnd = netlist.getHDIPrimitive(Unisim.GND);
        EDIFCell ecVcc = netlist.getHDIPrimitive(Unisim.VCC);
        nextNet: for (EDIFHierNet ehn : netToPortInsts.keySet()) {
            Net newPhysNet = null;

            // Find the one and only source pin
            List<EDIFHierPortInst> leafEdifPins = ehn.getLeafHierPortInsts(true);
            EDIFHierPortInst sourceEhpi = null;
            SiteInst sourceSi = null;
            BELPin sourceBELPin = null;
            for (EDIFHierPortInst ehpi : leafEdifPins) {
                if (!ehpi.isOutput()) {
                    continue;
                }

                if (sourceEhpi != null) {
                    throw new RuntimeException("ERROR: More than one source pin found on net '" + ehn.getHierarchicalNetName() + "'.");
                }
                sourceEhpi = ehpi;
                Cell sourceCell = sourceEhpi.getPhysicalCell(design);
                if (sourceCell == null) {
                    EDIFCell eci = ehpi.getCellType();
                    if (eci.equals(ecGnd)) {
                        newPhysNet = design.getGndNet();
                    } else if (eci.equals(ecVcc)) {
                        newPhysNet = design.getVccNet();
                    } else if (!eci.isPrimitive() && eci.isLeafCellOrBlackBox()) {
                        // It's a black box or possibly an encrypted cell
                        continue nextNet;
                    } else {
                        throw new RuntimeException("ERROR: Cell corresponding to pin '" + sourceEhpi + "' not found.");
                    }

                    // (Check that these have been handled by connectNetSource())
                    Net oldPhysNet = design.getNet(ehn.getHierarchicalNetName());
                    if (oldPhysNet != null) {
                        assert(oldPhysNet.isStaticNet());
                        assert(oldPhysNet.getSource() == null);
                        assert(oldPhysNet.getAlternateSource() == null);
                    }
                } else {
                    sourceSi = sourceCell.getSiteInst();
                    sourceBELPin = sourceCell.getBELPin(sourceEhpi);

                    EDIFHierNet parentEhn = sourceEhpi.getHierarchicalNet();
                    newPhysNet = design.getNet(parentEhn.getHierarchicalNetName());
                    if (newPhysNet == null) {
                        newPhysNet = design.createNet(parentEhn.getHierarchicalNetName());
                    }
                }
            }

            for (EDIFHierPortInst ehpi : leafEdifPins) {
                if (ehpi.isOutput()) {
                    continue;
                }

                Cell cell = ehpi.getPhysicalCell(design);
                if (cell == null) {
                    throw new RuntimeException("ERROR: Cell corresponding to pin '" + ehpi + "' not found.");
                }
                List<SitePinInst> sitePins = cell.getAllSitePinsFromLogicalPin(ehpi.getPortInst().getName(), null);
                SiteInst si = cell.getSiteInst();
                if (!sitePins.isEmpty()) {
                    // This net's leaf pins already has some site pins
                    // Find those site pins and move them onto the new net
                    String logicalPinName = ehpi.getPortInst().getName();
                    BEL bel = cell.getBEL();
                    for (String physicalPinName : cell.getAllPhysicalPinMappings(logicalPinName)) {
                        String sitePinName = cell.getCorrespondingSitePinName(logicalPinName, physicalPinName, null);
                        SitePinInst spi = si.getSitePinInst(sitePinName);
                        if (spi == null) {
                            continue;
                        }
                        // Check that all port insts serviced by this SPI are on this net
                        List<EDIFHierPortInst> portInstsOnSpi = DesignTools.getPortInstsFromSitePinInst(spi);
                        assert(portInstsOnSpi.contains(ehpi));
                        EDIFHierNet parentNet = sourceEhpi.getHierarchicalNet();
                        for (EDIFHierPortInst otherEhpi : portInstsOnSpi) {
                            if (otherEhpi.equals(ehpi)) {
                                continue;
                            }
                            // TODO: Use getLeafHierPortInst() to get parent net?
                            EDIFHierNet otherParentNet = netlist.getParentNet(otherEhpi.getHierarchicalNet());
                            if (!otherParentNet.equals(parentNet)) {
                                String message = "Site pin " + spi.getSitePinName() + " cannot be used " +
                                        "to connect to logical pin '" + ehpi + "' since it is also connected to pin '" +
                                        otherEhpi + "'.";
                                String warnIfCellInstStartsWith = System.getProperty("rapidwright.ecotools.connectNet.warnIfCellInstStartsWith");
                                String cellInstName = (warnIfCellInstStartsWith != null) ? otherEhpi.getPortInst().getCellInst().getName() : null;
                                if (cellInstName != null && cellInstName.startsWith(warnIfCellInstStartsWith)) {
                                    System.err.println("WARNING: " + message);
                                } else {
                                    throw new RuntimeException("ERROR: " + message);
                                }
                            }
                        }

                        Net oldPhysNet = spi.getNet();
                        if (deferredRemovals != null) {
                            deferredRemovals.computeIfPresent(oldPhysNet, (k, v) -> {
                                v.remove(spi);
                                return v.isEmpty() ? null : v;
                            });
                        }
                        if (!Objects.equals(oldPhysNet, newPhysNet)) {
                            // Unroute and remove pin from old net
                            BELPin snkBp = bel.getPin(physicalPinName);
                            if (!si.unrouteIntraSiteNet(spi.getBELPin(), snkBp)) {
                                throw new RuntimeException("ERROR: Failed to unroute intra-site connection " +
                                        spi.getSiteInst().getSiteName() + "/" + spi.getBELPin() + " to " + snkBp + ".");
                            }
                            boolean preserveOtherRoutes = true;
                            if (oldPhysNet != null) {
                                oldPhysNet.removePin(spi, preserveOtherRoutes);
                                if (RouterHelper.isLoadLessNet(oldPhysNet) && oldPhysNet.hasPIPs()) {
                                    // Since oldPhysNet has no sink pins left, yet still has PIPs, then it may
                                    // mean that a routing stub persevered. To handle such cases, unroute the
                                    // whole net.
                                    oldPhysNet.unroute();
                                }
                            }

                            // Re-do intra-site routing and add pin to new net
                            if (!si.routeIntraSiteNet(newPhysNet, spi.getBELPin(), snkBp)) {
                                throw new RuntimeException("ERROR: Failed to route intra-site connection " +
                                        spi.getSiteInst().getSiteName() + "/" + spi.getBELPin() + " to " + snkBp + ".");
                            }
                            newPhysNet.addPin(spi);
                            spi.setRouted(false);
                        }
                    }
                } else {
                    // If source and sink pin happen to be in the same site, try intra-site routing first
                    if (si.equals(sourceSi) && si.routeIntraSiteNet(newPhysNet, sourceBELPin, cell.getBELPin(ehpi))) {
                        // Intra-site routing successful
                        continue;
                    }

                    // Otherwise create and attach a new site-pin
                    String logicalPinName = ehpi.getPortInst().getName();
                    if (cell.getAllPhysicalPinMappings(logicalPinName) != null) {
                        createExitSitePinInst(design, ehpi, newPhysNet);
                    }
                }
            }

            if (newPhysNet.getSource() == null && !newPhysNet.getPins().isEmpty() && !newPhysNet.isStaticNet()) {
                // We have at least one sink pin -- ensure we have a
                // source pin to exit the site with
                createExitSitePinInst(design, sourceEhpi, newPhysNet);
                assert (newPhysNet.getSource() != null);
            }
        }
    }

    private static void connectNetSource(Design design,
                                         Map<EDIFHierNet, EDIFHierPortInst> netToSourcePortInst,
                                         Map<Net, Set<SitePinInst>> deferredRemovals) {
        for (Map.Entry<EDIFHierNet,EDIFHierPortInst> e : netToSourcePortInst.entrySet()) {
            EDIFHierPortInst ehpi = e.getValue();
            assert(ehpi.isOutput());

            EDIFHierNet ehn = e.getKey();
            EDIFNet en = ehn.getNet();

            // Modify the logical netlist
            for (EDIFHierPortInst src : ehn.getLeafHierPortInsts(true, false)) {
                System.err.println("WARNING: Net '" + ehn.getHierarchicalNetName() + "' already has an output pin '" +
                        src + "'. Replacing with new pin '" + ehpi + "'.");
                Cell cell = src.getPhysicalCell(design);
                for (SitePinInst spi : cell.getAllSitePinsFromLogicalPin(src.getPortInst().getName(), null)) {
                    assert(spi.getNet() != null);
                    if (deferredRemovals != null) {
                        deferredRemovals.computeIfAbsent(spi.getNet(), (p) -> new HashSet<>()).add(spi);
                    }

                }
                src.getNet().removePortInst(src.getPortInst());
            }

            if (ehn.getHierarchicalInst().equals(ehpi.getHierarchicalInst())) {
                // Connect if not already connected
                EDIFPortInst epi = ehpi.getPortInst();
                if (!en.getPortInsts().contains(epi)) {
                    en.addPortInst(epi);
                }
            } else {
                // Use a unique baseName derived from the net name, to avoid
                // name collisions with bus nets -- e.g. when baseName is 'foo'
                // it may be possible that some cell in the hierarchy may contain
                // a bus net 'foo' -- represented only by a collection of
                // possibly-arbitrarily-indexed single-bit nets: 'foo[i]', 'foo[j]',
                // 'foo[k]', etc. making it impractical to identify if a bus net exists.
                String baseName = ehn.getNet().getName() + EDIFTools.getUniqueSuffix();
                EDIFTools.connectPortInstsThruHier(ehn, ehpi, baseName);
            }
        }

        // Since we have changed source pins, regenerate the parent net map
        final EDIFNetlist netlist = design.getNetlist();
        netlist.resetParentNetMap();

        // Modify the physical netlist
        EDIFCell ecGnd = netlist.getHDIPrimitive(Unisim.GND);
        EDIFCell ecVcc = netlist.getHDIPrimitive(Unisim.VCC);
        for (Map.Entry<EDIFHierNet,EDIFHierPortInst> e : netToSourcePortInst.entrySet()) {
            EDIFHierNet ehn = e.getKey();

            List<EDIFHierPortInst> leafLogicalPins = ehn.getLeafHierPortInsts(true, false);
            if (leafLogicalPins.size() != 1) {
                throw new RuntimeException("ERROR: Net '" + ehn.getHierarchicalNetName() + "' does not contain exactly one source pin.");
            }
            EDIFHierPortInst sourceEhpi = leafLogicalPins.get(0);

            Net newPhysNet = null;
            Cell sourceCell = sourceEhpi.getPhysicalCell(design);
            if (sourceCell == null) {
                EDIFCell eci = sourceEhpi.getCellType();
                if (eci.equals(ecGnd)) {
                    newPhysNet = design.getGndNet();
                } else if (eci.equals(ecVcc)) {
                    newPhysNet = design.getVccNet();
                } else {
                    throw new RuntimeException("ERROR: Cell corresponding to pin '" + sourceEhpi + "' not found.");
                }
            }

            if (newPhysNet == null) {
                EDIFHierNet parentEhn = sourceEhpi.getHierarchicalNet();
                newPhysNet = design.getNet(parentEhn.getHierarchicalNetName());
                if (newPhysNet == null) {
                    newPhysNet = design.createNet(parentEhn.getHierarchicalNetName());
                }
            } else if (newPhysNet.isStaticNet()) {
                Net oldPhysNet = design.getNet(ehn.getHierarchicalNetName());
                if (oldPhysNet != null) {
                    // Propagate the net type so that it can be reconciled by
                    // route_design's makePhysNetNamesConsistent
                    oldPhysNet.setType(newPhysNet.getType());

                    Net usedNet = design.getNet(Net.USED_NET);
                    if (usedNet == null) {
                        usedNet = design.createNet(Net.USED_NET);
                    }
                    // Unroute and remove all output pins on this net
                    oldPhysNet.unroute();
                    for (SitePinInst spi : Arrays.asList(oldPhysNet.getAlternateSource(), oldPhysNet.getSource())) {
                        if (spi == null) {
                            continue;
                        }

                        BELPin spiBELPin = spi.getBELPin();
                        for (EDIFHierPortInst ehpi : DesignTools.getPortInstsFromSitePinInst(spi)) {
                            Pair<SiteInst, BELPin> p = ehpi.getRoutedBELPin(design);
                            p.getFirst().unrouteIntraSiteNet(p.getSecond(), spiBELPin);
                        }
                        spi.getSiteInst().removePin(spi);
                        oldPhysNet.removePin(spi);

                        // Mark the output pin sitewire with USED_NET to block it from being
                        // used as a static source
                        spi.getSiteInst().routeIntraSiteNet(usedNet, spiBELPin, spiBELPin);
                    }
                }
                continue;
            }

            Cell cell = sourceEhpi.getPhysicalCell(design);
            if (cell == null) {
                throw new RuntimeException("ERROR: Cell corresponding to pin '" + sourceEhpi + "' not found.");
            }

            List<SitePinInst> sitePins = cell.getAllSitePinsFromLogicalPin(sourceEhpi.getPortInst().getName(), null);
            if (!sitePins.isEmpty()) {
                // This net's leaf pins already has some site pins
                for (SitePinInst spi : sitePins) {
                    assert(spi.isOutPin());

                    Net oldPhysNet = spi.getNet();
                    if (newPhysNet.equals(oldPhysNet)) {
                        // Site pin already on new net
                        continue;
                    }

                    if (oldPhysNet != null) {
                        // Site pin is attached to a different physical net, move them over to the new net
                        // (erasing them from deferredRemovals if present)
                        if (deferredRemovals != null) {
                            deferredRemovals.computeIfPresent(oldPhysNet, (k, v) -> {
                                v.remove(spi);
                                return v.isEmpty() ? null : v;
                            });
                        }
                        fullyUnrouteSources(oldPhysNet);
                    }

                    if (deferredRemovals != null) {
                        deferredRemovals.computeIfPresent(newPhysNet, (k, v) -> {
                            v.remove(k.getSource());
                            v.remove(k.getAlternateSource());
                            return v.isEmpty() ? null : v;
                        });
                    }
                    fullyUnrouteSources(newPhysNet);

                    // Add existing site pin to new net, and update intra-site routing
                    SiteInst si = cell.getSiteInst();
                    Pair<SiteInst, BELPin> siteInstBelPin = sourceEhpi.getRoutedBELPin(design);
                    assert(siteInstBelPin.getFirst() == si);
                    assert(spi.getSiteInst() == null);
                    spi.setSiteInst(si);
                    newPhysNet.addPin(spi);
                    si.routeIntraSiteNet(newPhysNet, siteInstBelPin.getSecond(), spi.getBELPin());
                }
            } else {
                if (deferredRemovals != null) {
                    deferredRemovals.computeIfPresent(newPhysNet, (k, v) -> {
                        v.remove(k.getSource());
                        v.remove(k.getAlternateSource());
                        return v.isEmpty() ? null : v;
                    });

                }
                fullyUnrouteSources(newPhysNet);

                // Create and add a new SitePinInst to new net, including
                // updating any intra-site routing necessary to get out of site
                createExitSitePinInst(design, sourceEhpi, newPhysNet);
            }
        }
    }

    /**
     * Convenience wrapper method to connectNet() that allows for minimal
     * specification of a single connection to be made.
     * 
     * @param d      The design where the net exists.
     * @param cell   The cell on which the pin to be connected exists
     * @param logPin The logical name on the cell to connect
     * @param net    The physical net to connect the logical pin to
     */
    public static void connectNet(Design d, Cell cell, String logPin, Net net) {
        Map<EDIFHierNet, List<EDIFHierPortInst>> map = new HashMap<>();
        EDIFPortInst portInst = cell.getEDIFCellInst().getOrCreatePortInst(logPin);
        map.put(net.getLogicalHierNet(),new ArrayList<>(Arrays.asList(
                new EDIFHierPortInst(cell.getEDIFHierCellInst().getParent(), portInst))));
        ECOTools.connectNet(d, map, null);
    }

    /**
     * Given a list of strings containing one net path followed by one or more pin paths
     * separated by spaces (in line with Tcl convention) connect the latter pins to the former net.
     * This method modifies the EDIF (logical) netlist as well as the place-and-route (physical)
     * state, and is modelled on Vivado's <TT>connect_net -hier -net_object_list</TT> command.
     * @param design The design where the net(s) and pin(s) are instantiated.
     * @param netPinList A list of strings containing net and pin paths.
     */
    public static void connectNet(Design design,
                                  List<String> netPinList) {
        connectNet(design, netPinList, null);
    }

    /**
     * Given a list of strings containing one net path followed by one or more pin paths
     * separated by spaces (in line with Tcl convention) connect the latter pins to the former net.
     * This method modifies the EDIF (logical) netlist as well as the place-and-route (physical)
     * state, and is modelled on Vivado's <TT>connect_net -hier -net_object_list</TT> command.
     * @param design The design where the net(s) and pin(s) are instantiated.
     * @param netPinList A list of strings containing net and pin paths.
     * @param deferredRemovals An optional map that, if passed in non-null will allow any SitePinInst
     *                         objects deferred previously for removal to be reused for new connections.
     *                         See {@link #disconnectNet(Design, List, Map)}.
     */
    public static void connectNet(Design design,
                                  List<String> netPinList,
                                  Map<Net, Set<SitePinInst>> deferredRemovals) {
        final EDIFNetlist netlist = design.getNetlist();
        final Map<EDIFHierNet, List<EDIFHierPortInst>> netPortInsts = new HashMap<>(netPinList.size());
        for (String i : netPinList) {
            String[] net_pins = i.split(" ", 2);
            String net = net_pins[0];
            if (net.isEmpty()) {
                System.err.println("WARNING: Empty net specified for connection to pins: " + net_pins[1]);
                continue;
            }
            EDIFHierNet ehn = netlist.getHierNetFromName(net);
            if (ehn == null) throw new RuntimeException("ERROR: Net " + net + " not found");

            String[] pins = net_pins[1].split("[{} ]+");
            List<EDIFHierPortInst> portInsts = netPortInsts.computeIfAbsent(ehn, (n) -> new ArrayList<>(pins.length));
            for (String pin : pins) {
                if (pin.isEmpty()) {
                    continue;
                }

                EDIFHierPortInst ehpi = netlist.getHierPortInstFromName(pin);
                if (ehpi == null) {
                    // No pin exists currently; create and attach one to its port
                    int pos = pin.lastIndexOf(EDIFTools.EDIF_HIER_SEP);
                    String path = pin.substring(0, pos);
                    EDIFHierCellInst ehci = netlist.getHierCellInstFromName(path);
                    if (ehci == null) {
                        throw new RuntimeException("ERROR: Unable to find inst '" + path + "' corresponding to pin '" + pin + "'");
                    }
                    String name = pin.substring(pos+1);
                    EDIFCell cell = ehci.getCellType();
                    EDIFPort ep = cell.getPort(name);
                    if (ep == null) {
                        ep = cell.getPort(EDIFTools.getRootBusName(name, false));
                    }
                    if (ep == null) {
                        throw new RuntimeException("ERROR: Unable to find port '" + name + "' on inst '" + path + "' corresponding to pin '" + pin + "'");
                    }
                    EDIFPortInst epi;
                    if (ep.isBus()) {
                        int portIdx = EDIFTools.getPortIndexFromName(name);
                        if (ep.isLittleEndian()) {
                            portIdx = ep.getWidth() - 1 - portIdx;
                        }
                        epi = new EDIFPortInst(ep, null, portIdx, ehci.getInst());
                    } else {
                        epi = new EDIFPortInst(ep, null, ehci.getInst());
                    }
                    ehpi = new EDIFHierPortInst(ehci.getParent(), epi);
                }
                portInsts.add(ehpi);
            }
        }
        connectNet(design, netPortInsts, deferredRemovals);
    }

    /**
     * Creates a new SitePinInst source for the physical net provided.  It will route out an
     * internal pin to a site pin output.
     * @param design The current design
     * @param targetHierSrc The hierarchical source pin
     * @param newPhysNet The net to add the new source pin to
     * @return The newly created source site pin
     */
    private static SitePinInst routeOutSitePinInstSource(Design design,
                                                         EDIFHierPortInst targetHierSrc,
                                                         Net newPhysNet) {
        if (!targetHierSrc.isOutput()) {
            throw new RuntimeException("ERROR: Pin '" + targetHierSrc + "' is not an output pin.");
        }
        if (!targetHierSrc.getCellType().isPrimitive()) {
            throw new RuntimeException("ERROR: Pin '" + targetHierSrc + "' is not an primitive source pin.");
        }
        // This net is internal to a site, need to create a site pin and route out to it
        Cell srcCell = design.getCell(targetHierSrc.getFullHierarchicalInstName());
        SiteInst si = srcCell.getSiteInst();
        String sitePinName = null;
        // Find the first available site pin (e.g. between ?_O and ?MUX)
        List<String> sitePins = srcCell.getAllCorrespondingSitePinNames(targetHierSrc.getPortInst().getName());
        for (String pin : sitePins) {
            if (si.getSitePinInst(pin) == null) {
                sitePinName = pin;
                break;
            }
        }
        if (sitePinName == null) {
            BELPin output = srcCell.getBELPin(targetHierSrc);
            // Unroute single output path of slice
            // Identify case (O5 -> MUX output must be blocked by O6, reroute O6 to _O pin, route O5 out MUX output)
            if (output.getName().equals("O5") && sitePins.isEmpty()) {
                char lutID = output.getBELName().charAt(0);

                // Remove OUTMUX SitePIP from O6
                String rBELName = "OUTMUX" + lutID;
                SitePIP sitePIP = si.getUsedSitePIP(rBELName);
                assert(sitePIP.getInputPinName().equals("D6"));
                // TODO: Use DesignTools.unrouteAlternativeOutputSitePin() instead
                si.unrouteIntraSiteNet(sitePIP.getInputPin(), sitePIP.getOutputPin());

                // Move source from *MUX -> *_O
                sitePinName = lutID + "MUX";
                SitePinInst lut6MuxOutput = si.getSitePinInst(sitePinName);
                Net lut6Net = null;
                if (lut6MuxOutput != null) {
                    lut6Net = lut6MuxOutput.getNet();
                    lut6Net.removePin(lut6MuxOutput, true);
                    si.removePin(lut6MuxOutput);
                } else {
                    // OUTMUX is configured to be D6 but no SPI exists; ignore
                }
                String mainPinName = lutID + "_O";
                SitePinInst lut6MainOutput = si.getSitePinInst(mainPinName);
                if (lut6MainOutput != null) {
                    if (lut6Net == null) {
                        lut6Net = lut6MainOutput.getNet();
                    } else {
                        // lut6Net already using the _O output
                        assert(lut6MainOutput.getNet().equals(lut6Net));
                    }

                    // Since unrouteIntraSiteNet() above removed the nets on the ?_O (sitePIP
                    // input) and ?MUX (sitePIP output) sitewires, restore the former here
                    si.routeIntraSiteNet(lut6Net, sitePIP.getInputPin(), sitePIP.getInputPin());
                } else {
                    lut6Net.createPin(mainPinName, si);
                }

                // Reconfigure OUTMUX SitePIP to use O5
                sitePIP = si.getSitePIP(rBELName, "D5");
                si.routeIntraSiteNet(newPhysNet, sitePIP.getInputPin(), sitePIP.getOutputPin());
            }else {
                System.err.println("ERROR: Unable to exit site for target src: "
                        + targetHierSrc + " " + output + " -> " + sitePins);
                return null;
            }
        }
        SitePinInst srcPin = newPhysNet.createPin(sitePinName, si);
        BELPin belPinSrc = srcCell.getBELPin(targetHierSrc);
        if (!si.routeIntraSiteNet(newPhysNet, belPinSrc, srcPin.getBELPin())) {
            System.err.println("ERROR: Failed to route to site pin from target src: "
                    + targetHierSrc);
        }
        return srcPin;
    }

    /**
     * Creates a new SitePinInst source or sink for the physical net provided.  It will route out an
     * EDIFHierPortInst input/output pin to a corresponding SitePinInst.
     * @param design The current design.
     * @param ehpi The hierarchical pin.
     * @param net The net to add the new SitePinInst to.
     * @return The newly created SitePinInst.
     */
    public static SitePinInst createExitSitePinInst(Design design,
                                                    EDIFHierPortInst ehpi,
                                                    Net net) {
        if (ehpi.isOutput()) {
            return routeOutSitePinInstSource(design, ehpi, net);
        }

        Cell cell = ehpi.getPhysicalCell(design);
        BELPin cellBp = cell.getBELPin(ehpi);
        SiteInst si = cell.getSiteInst();
        String logicalPinName = ehpi.getPortInst().getName();
        List<String> siteWires = new ArrayList<>();
        final boolean considerLutRoutethru = true;
        List<String> sitePinNames = cell.getAllCorrespondingSitePinNames(logicalPinName, siteWires, considerLutRoutethru);
        if (sitePinNames.isEmpty()) {
            // Following existing intra-site routing did not get us to a site pin
            // (e.g. previous driver of this BELPin could be a LUT)
            assert(!siteWires.isEmpty());

            // Unroute the first SitePIP
            for (String sitewire : siteWires) {
                BELPin[] belPins = si.getSiteWirePins(sitewire);
                BELPin srcBp = belPins[0].getSourcePin();
                BEL srcBEL = srcBp.getBEL();
                if (srcBEL.getBELClass() == BELClass.RBEL) {
                    SitePIP spip = si.getUsedSitePIP(srcBp);
                    if (spip == null) {
                        continue;
                    }

                    BELPin inputBp = spip.getInputPin();

                    // Save the net on inputBp's sitewire, since
                    // SiteInst.unrouteIntraSiteNet() will rip it up
                    Net inputBpNet = si.getNetFromSiteWire(inputBp.getSiteWireName());

                    // Unroute SitePIP
                    if (!si.unrouteIntraSiteNet(inputBp, cellBp)) {
                        throw new RuntimeException("ERROR: Failed to unroute intra-site connection " +
                                si.getSiteName() + "/" + inputBp + " to " + cellBp);
                    }

                    // Restore inputBp's sitewire
                    if (inputBpNet != null) {
                        si.routeIntraSiteNet(inputBpNet, inputBp, inputBp);
                    }

                    // Try again
                    sitePinNames = cell.getAllCorrespondingSitePinNames(logicalPinName, siteWires, considerLutRoutethru);
                    break;
                }
            }
        }

        Set<Net> netAliases = null;
        SitePinInst spi = null;
        for (String sitePinName : sitePinNames) {
            Net siteWireNet = si.getNetFromSiteWire(sitePinName);
            if (siteWireNet != null && !DesignTools.isNetDrivenByHierPort(siteWireNet)) {
                if (netAliases == null) {
                    // Build a set of all aliases of exit net
                    netAliases = new HashSet<>();
                    for (EDIFHierNet ehn : design.getNetlist().getNetAliases(net.getLogicalHierNet())) {
                        Net netAlias = design.getNet(ehn.getHierarchicalNetName());
                        if (netAlias == null) {
                            continue;
                        }
                        netAliases.add(netAlias);
                    }
                }

                if (!netAliases.contains(siteWireNet)) {
                    // Site wire net is not an alias of the exit net
                    continue;
                }
            } else {
                // Site Pin not currently used or was driven by site port
            }

            spi = net.createPin(sitePinName, si);
            break;
        }

        if (spi == null) {
            throw new RuntimeException("ERROR: Unable to route pin '" + ehpi + "' out of site " + si.getSiteName() + ".");
        }

        BELPin snkBp = cell.getBELPin(ehpi);
        if (!si.unrouteIntraSiteNet(spi.getBELPin(), snkBp)) {
            throw new RuntimeException("ERROR: Failed to unroute intra-site connection " +
                    spi.getSiteInst().getSiteName() + "/" + spi.getBELPin() + " to " + snkBp + ".");
        }
        if (!si.routeIntraSiteNet(net, spi.getBELPin(), snkBp)) {
            throw new RuntimeException("ERROR: Failed to route intra-site connection " +
                    spi.getSiteInst().getSiteName() + "/" + spi.getBELPin() + " to " + snkBp + ".");
        }

        return spi;
    }

    // Unroute both primary and alternate site pin sources on a net, should they exist,
    // and remove those pins from their SiteInst too.
    // Also rip up associated intra-site routing.
    private static void fullyUnrouteSources(Net net) {
        for (SitePinInst spi : Arrays.asList(net.getSource(), net.getAlternateSource())) {
            if (spi == null) continue;
            BELPin srcBp = DesignTools.getLogicalBELPinDriver(spi);
            SiteInst si = spi.getSiteInst();
            si.unrouteIntraSiteNet(srcBp, spi.getBELPin());
            net.removePin(spi);
            si.removePin(spi);
            spi.setSiteInst(null);
        }
    }

    /**
     * Given a list of EDIFHierCellInst objects, remove these cell instances from the design.
     * This method removes and disconnects cells from the EDIF (logical) netlist
     * as well as the place-and-route (physical) state, and is modelled on Vivado's
     * <TT>remove_cell</TT> command.
     * @param design The current design.
     * @param insts A list of hierarchical cell instances for removal.
     * @param deferredRemovals An optional map that, if passed in non-null will be populated with
     *                         site pins marked for removal.  The map allows for persistent tracking
     *                         if this method is called many times as the process is expensive
     *                         without batching.
     */
    public static void removeCell(Design design,
                                  List<EDIFHierCellInst> insts,
                                  Map<Net, Set<SitePinInst>> deferredRemovals) {
        final EDIFNetlist netlist = design.getNetlist();
        for (EDIFHierCellInst ehci : insts) {
            // Disconnect hierarchical cell from connected nets
            EDIFCellInst eci = ehci.getInst();
            for (EDIFPortInst ehpi : eci.getPortInsts()) {
                EDIFNet en = ehpi.getNet();
                if (en != null) {
                    en.removePortInst(ehpi);
                }
            }

            // Remove all leaf cells from physical design
            for (EDIFHierCellInst leafEhci : netlist.getAllLeafDescendants(ehci)) {
                String cellName = leafEhci.getCellName();
                if (cellName.equals("GND") || cellName.equals("VCC")) {
                    continue;
                }
                Cell physCell = design.getCell(leafEhci.getFullHierarchicalInstName());
                if (physCell == null) {
                    throw new RuntimeException("ERROR: Cannot find physical cell corresponding to logical cell '" +
                            leafEhci.getFullHierarchicalInstName() + "'.");
                }

                DesignTools.fullyUnplaceCell(physCell, deferredRemovals);
                design.removeCell(physCell);
            }
        }

        for (EDIFHierCellInst ehci : insts) {
            // Remove cell instance from parent cell
            EDIFCell parentCell = ehci.getParent().getCellType();
            parentCell.removeCellInst(ehci.getInst());
        }
    }

    /**
     * Given a list of strings containing the hierarchical path to cell instances,
     * remove these instances from the design.
     * This method removes and disconnects cells from the EDIF (logical) netlist
     * as well as the place-and-route (physical) state, and is modelled on Vivado's
     * <TT>remove_cell</TT> command.
     * @param design The current design.
     * @param paths A list of instance paths for removal.
     * @param deferredRemovals An optional map that, if passed in non-null will be populated with
     *                         site pins marked for removal.  The map allows for persistent tracking
     *                         if this method is called many times as the process is expensive
     *                         without batching.
     */
    public static void removeCellPath(Design design,
                                      List<String> paths,
                                      Map<Net, Set<SitePinInst>> deferredRemovals) {
        final EDIFNetlist netlist = design.getNetlist();
        List<EDIFHierCellInst> edifCellInsts = new ArrayList<>(paths.size());
        for (String instName : paths) {
            EDIFHierCellInst ehci = netlist.getHierCellInstFromName(instName);
            if (ehci == null) {
                throw new RuntimeException("ERROR: Cannot find cell '" + instName + '"');
            }
            edifCellInsts.add(ehci);
        }
        removeCell(design, edifCellInsts, deferredRemovals);
    }

    private static Pair<EDIFHierCellInst,String> getParentCellInstAndName(EDIFNetlist netlist, String path) {
        int pos = path.lastIndexOf(EDIFTools.EDIF_HIER_SEP);
        String name = path.substring(pos+1);
        EDIFHierCellInst parentEhci;
        if (pos == -1) {
            parentEhci = netlist.getTopHierCellInst();
        } else {
            String parentPath = path.substring(0, pos);
            parentEhci = netlist.getHierCellInstFromName(parentPath);
        }
        if (parentEhci == null) {
            throw new RuntimeException("ERROR: Cannot find parent cell in path '" + path + "'.");
        }
        return new Pair<>(parentEhci, name);
    }

    /**
     * Given a EDIFCell object and a list of instance paths, create these cell instantiations
     * in the design.
     * This method inserts cells in the EDIF (logical) netlist as well as corresponding leaf cells
     * into the physical state (unplaced), and is modelled on Vivado's <TT>create_cell</TT> command.
     * @param design The current design.
     * @param reference The cell to be instantiated.
     * @param paths A list of instance paths for creation.
     */
    public static void createCell(Design design, EDIFCell reference, List<String> paths) {
        final EDIFNetlist netlist = design.getNetlist();
        for (String path : paths) {
            // Modify logical netlist
            Pair<EDIFHierCellInst,String> p = getParentCellInstAndName(netlist, path);
            EDIFHierCellInst parentEhci = p.getFirst();
            EDIFCell parentCell = parentEhci.getCellType();
            String cellName = p.getSecond();
            EDIFCellInst eci = parentCell.createChildCellInst(cellName, reference);

            // Modify physical netlist
            EDIFHierCellInst ehci = parentEhci.getChild(eci);
            for (EDIFHierCellInst leaf : netlist.getAllLeafDescendants(ehci)) {
                String leafCellName = leaf.getCellName();
                if (leafCellName.equals("VCC") || leafCellName.equals("GND")) {
                    continue;
                }
                design.addCell(new Cell(leaf.getFullHierarchicalInstName()));
            }
        }
    }

    /**
     * Creates and places a new primitive cell inline from the existing net driving
     * the input pin provided and connects its output to the existing input. The
     * cell will be created as a sibling to the instance of the provided input pin
     * and will be named with the prefix 'inline_insertion_'. For example, if input
     * is 'FF/D' and reference is a LUT1, this will create a new cell instance of
     * type LUT1 where its input 'I0' will replace the connection 'D' on 'FF' and a
     * new net will be created that will connect 'LUT1/O' to 'FF/D'.
     * 
     * This is useful for scenarios like inserting a LUT1 inline when routethru
     * instances are not possible.
     * 
     * @param design    The existing design.
     * @param input     The reference logical input pin on which the new inline cell
     *                  should be created and connected.
     * @param reference The primitive cell type to create.
     * @param site      The site on which the new cell should be placed.
     * @param bel       The BEL within the provided site onto which the cell should
     *                  be placed.
     * @param logInput  The logical cell's input pin that should connect to the
     *                  existing input's net.
     * @param logOutput The logical cell's output pin that should connect to the
     *                  existing input.
     * @return A hierarchical reference to the newly created cell instance.
     */
    public static Cell createAndPlaceInlineCellOnInputPin(Design design, EDIFHierPortInst input,
            Unisim reference, Site site, BEL bel, String logInput, String logOutput) {
        if (!input.isInput()) return null;
        EDIFHierNet net = input.getHierarchicalNet();
        EDIFCell parent = input.getPortInst().getParentCell();
        EDIFNet newNet = parent.createNet("inline_insertion_net_" + UNIQUE_COUNT++);
        EDIFHierNet newHierNet = new EDIFHierNet(net.getHierarchicalInst(), newNet);

        // Create new cell instance, add to primitives library if necessary
        EDIFCell refCell = design.getNetlist().getHDIPrimitivesLibrary().getCell(reference.name());
        if (refCell == null) {
            refCell = Design.getUnisimCell(reference);
            design.getNetlist().getHDIPrimitivesLibrary().addCell(refCell);
        }
        EDIFCellInst newInst = refCell.createCellInst("inline_insertion_" + refCell.getName()
                + "_" + UNIQUE_COUNT++, parent);
        EDIFHierCellInst newHierInst = net.getHierarchicalInst().getChild(newInst);
        Cell cell = design.createCell(newHierInst.getFullHierarchicalInstName(), newInst);
        if (!design.placeCell(cell, site, bel)) {
            throw new RuntimeException("ERROR: Unable to create new inline cell to be placed on " 
                + site + "/" + bel);
        }

        EDIFPortInst newInput = new EDIFPortInst(refCell.getPort(logInput), null, newInst);
        EDIFPortInst newOutput = new EDIFPortInst(refCell.getPort(logOutput), null, newInst);
        EDIFHierPortInst newHierInput = newHierInst.getPortInst(newInput.getName());
        EDIFHierPortInst newHierOutput = newHierInst.getPortInst(newOutput.getName());
        
        disconnectNet(design, input);
        
        // Connect new cell input to existing net, output and existing input to new net
        Map<EDIFHierNet, List<EDIFHierPortInst>> connectMap = new HashMap<>();
        connectMap.put(net, new ArrayList<>(Arrays.asList(newHierInput)));
        connectMap.put(newHierNet, new ArrayList<>(Arrays.asList(newHierOutput, input)));
        connectNet(design, connectMap, null);
        return cell;
    }
    
    /**
     * Given list of net paths, create these nets in the design.
     * This method inserts nets into the EDIF (logical) netlist as well as corresponding nets
     * into the physical state (unplaced), and is modelled on Vivado's <TT>create_net</TT> command.
     * @param design The current design.
     * @param paths A list of net paths for creation.
     */
    public static void createNet(Design design, List<String> paths) {
        final EDIFNetlist netlist = design.getNetlist();
        for (String path : paths) {
            // Modify logical netlist
            Pair<EDIFHierCellInst,String> p = getParentCellInstAndName(netlist, path);
            EDIFHierCellInst parentEhci = p.getFirst();
            EDIFCell parentCell = parentEhci.getCellType();
            String netName = p.getSecond();
            parentCell.createNet(netName);

            // Modify physical netlist
            assert(design.getNet(path) == null);
            design.createNet(path);
        }
    }
}

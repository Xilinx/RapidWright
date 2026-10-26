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

package com.xilinx.rapidwright.util.route_status;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

import com.xilinx.rapidwright.design.Design;
import com.xilinx.rapidwright.design.DesignTools;
import com.xilinx.rapidwright.design.Net;
import com.xilinx.rapidwright.design.SitePinInst;
import com.xilinx.rapidwright.device.Node;
import com.xilinx.rapidwright.device.PIP;
import com.xilinx.rapidwright.edif.EDIFCellInst;
import com.xilinx.rapidwright.edif.EDIFHierCellInst;
import com.xilinx.rapidwright.edif.EDIFHierNet;
import com.xilinx.rapidwright.edif.EDIFHierPortInst;
import com.xilinx.rapidwright.edif.EDIFNet;
import com.xilinx.rapidwright.edif.EDIFNetlist;
import com.xilinx.rapidwright.edif.EDIFPortInst;
import com.xilinx.rapidwright.util.ReportRouteStatus;

/**
 * Applies seeded, reproducible mutations to a placed and/or routed design (unrouting nets,
 * creating gaps, antennas, conflicts, etc.) to create corner cases for comparing
 * {@link ReportRouteStatus#reportRouteStatus(Design, boolean)} against Vivado's
 * `report_route_status`. A mutation spec is a comma separated list of {@code kind:amount}
 * entries, where amount is a fraction (0.0-1.0] of the eligible objects, or a count if greater
 * than or equal to 1.
 */
public class RouteStatusMutator {

    private final Design design;
    private final Random random;

    public RouteStatusMutator(Design design, long seed) {
        this.design = design;
        this.random = new Random(seed);
    }

    private <T> List<T> pick(List<T> eligible, double amount) {
        int n = (amount >= 1) ? (int) amount : (int) Math.ceil(eligible.size() * amount);
        n = Math.min(n, eligible.size());
        List<T> copy = new ArrayList<>(eligible);
        Collections.shuffle(copy, random);
        return copy.subList(0, n);
    }

    private List<Net> sortedNets() {
        List<Net> nets = new ArrayList<>(design.getNets());
        nets.sort(Comparator.comparing(Net::getName));
        return nets;
    }

    private List<Net> routedSignalNets() {
        List<Net> nets = new ArrayList<>();
        for (Net net : sortedNets()) {
            if (!net.isStaticNet() && !net.isUsedNet() && net.hasPIPs()) {
                nets.add(net);
            }
        }
        return nets;
    }

    private static List<SitePinInst> sinkPins(Net net) {
        List<SitePinInst> sinks = new ArrayList<>(net.getSinkPins());
        sinks.sort(Comparator.comparing(SitePinInst::toString));
        return sinks;
    }

    public void apply(String spec) {
        for (String entry : spec.split(",")) {
            if (entry.isEmpty() || entry.equals("none")) {
                continue;
            }
            String[] kv = entry.split(":");
            String kind = kv[0];
            double amount = (kv.length > 1) ? Double.parseDouble(kv[1]) : 1.0;
            switch (kind) {
                case "unroute":
                    // Fully unroute nets, keeping their site pins
                    for (Net net : pick(routedSignalNets(), amount)) {
                        net.unroute();
                    }
                    break;
                case "unroutesinks":
                    // Unroute some (but not all) sinks of multi-sink nets
                    List<Net> multiSink = new ArrayList<>();
                    for (Net net : routedSignalNets()) {
                        if (net.getSinkPins().size() > 1) {
                            multiSink.add(net);
                        }
                    }
                    for (Net net : pick(multiSink, amount)) {
                        List<SitePinInst> sinks = sinkPins(net);
                        List<SitePinInst> toUnroute = pick(sinks, Math.max(1, sinks.size() / 2));
                        DesignTools.unroutePins(net, toUnroute);
                    }
                    break;
                case "unroutesource":
                    // Remove the routing driven by a net's source pin, leaving the sinks' branches
                    for (Net net : pick(routedSignalNets(), amount)) {
                        if (net.getSource() != null) {
                            DesignTools.unrouteSourcePin(net.getSource());
                        }
                    }
                    break;
                case "removepip":
                    // Remove one random PIP, creating a gap and possibly an island
                    for (Net net : pick(routedSignalNets(), amount)) {
                        List<PIP> pips = new ArrayList<>(net.getPIPs());
                        PIP pip = pips.get(random.nextInt(pips.size()));
                        net.removePIP(pip);
                    }
                    break;
                case "antenna":
                    // Add a dangling PIP to an unused node
                    Set<Node> usedNodes = new HashSet<>();
                    for (Net net : design.getNets()) {
                        for (PIP pip : net.getPIPs()) {
                            usedNodes.add(pip.getStartNode());
                            usedNodes.add(pip.getEndNode());
                        }
                    }
                    for (Net net : pick(routedSignalNets(), amount)) {
                        addAntenna(net, usedNodes);
                    }
                    break;
                case "conflict":
                    // Copy a PIP of another net into a net, so both use the same nodes
                    List<Net> routed = routedSignalNets();
                    if (routed.size() < 2) {
                        break;
                    }
                    for (Net net : pick(routed, amount)) {
                        Net other = routed.get(random.nextInt(routed.size()));
                        if (other == net) {
                            continue;
                        }
                        List<PIP> pips = other.getPIPs();
                        PIP pip = pips.get(random.nextInt(pips.size()));
                        net.addPIP(pip);
                    }
                    break;
                case "removesinkpin":
                    // Remove a sink site pin from the net, keeping its routing
                    for (Net net : pick(routedSignalNets(), amount)) {
                        List<SitePinInst> sinks = sinkPins(net);
                        if (sinks.isEmpty()) {
                            continue;
                        }
                        SitePinInst spi = sinks.get(random.nextInt(sinks.size()));
                        net.removePin(spi, false);
                    }
                    break;
                case "lock":
                    for (Net net : pick(routedSignalNets(), amount)) {
                        net.lockRouting();
                    }
                    break;
                case "unroutestatic":
                    for (Net net : new Net[]{design.getGndNet(), design.getVccNet()}) {
                        List<SitePinInst> sinks = sinkPins(net);
                        List<SitePinInst> toUnroute = pick(sinks, amount);
                        DesignTools.unroutePins(net, toUnroute);
                    }
                    break;
                case "removenet":
                    // Remove physical nets entirely, leaving the logical nets
                    for (Net net : pick(routedSignalNets(), amount)) {
                        design.removeNet(net);
                    }
                    break;
                case "nodriver":
                    // Disconnect the logical driver of nets
                    for (Net net : pick(routedSignalNets(), amount)) {
                        disconnectLogical(net, true);
                    }
                    break;
                case "multidriver":
                    // Move the driver of another net in the same cell onto nets, which then have
                    // two drivers (and the other net none)
                    for (Net net : pick(routedSignalNets(), amount)) {
                        addSecondDriver(net);
                    }
                    break;
                case "noloads":
                    // Disconnect all logical loads of nets
                    for (Net net : pick(routedSignalNets(), amount)) {
                        disconnectLogical(net, false);
                    }
                    break;
                case "unplacedesign":
                    design.unplaceDesign();
                    break;
                default:
                    throw new RuntimeException("Unknown mutation: " + kind);
            }
        }
    }

    private void addAntenna(Net net, Set<Node> usedNodes) {
        List<PIP> pips = new ArrayList<>(net.getPIPs());
        Collections.shuffle(pips, random);
        for (PIP pip : pips) {
            Node node = pip.getEndNode();
            if (node == null) {
                // A PIP marking an explicit gap
                continue;
            }
            for (PIP downhill : node.getAllDownhillPIPs()) {
                Node end = downhill.getEndNode();
                if (end == null || usedNodes.contains(end) || end.isTied() || downhill.isRouteThru()) {
                    continue;
                }
                net.addPIP(downhill);
                usedNodes.add(end);
                return;
            }
        }
    }

    /**
     * Moves the leaf driver of another net, in the cell of the leaf driver of the given net, onto
     * the given net. Cells whose definition is shared by several instances are left alone, as
     * editing them would change every instance.
     */
    private void addSecondDriver(Net net) {
        EDIFHierNet hierNet = net.getLogicalHierNet();
        if (hierNet == null) {
            return;
        }
        // (from the current connectivity, which earlier mutations may have changed)
        List<EDIFHierPortInst> pins = getFlatLeafPortInsts(hierNet);
        EDIFHierPortInst driver = null;
        for (EDIFHierPortInst ehpi : pins) {
            if (ehpi.isOutput()) {
                driver = ehpi;
                break;
            }
        }
        if (driver == null || driver.getHierarchicalInst().getCellType().getLibrary().isHDIPrimitivesLibrary()
                || !driver.getHierarchicalInst().isUniquified()) {
            return;
        }
        EDIFNet target = driver.getNet();
        List<EDIFPortInst> candidates = new ArrayList<>();
        List<EDIFNet> nets = new ArrayList<>(driver.getHierarchicalInst().getCellType().getNets());
        nets.sort(Comparator.comparing(EDIFNet::getName));
        for (EDIFNet other : nets) {
            if (other == target || other.isGND() || other.isVCC()) {
                continue;
            }
            for (EDIFPortInst epi : other.getPortInsts()) {
                if (epi.isOutput() && epi.getCellInst() != null && epi.getCellInst().getCellType().isPrimitive()) {
                    candidates.add(epi);
                }
            }
        }
        if (candidates.isEmpty()) {
            return;
        }
        EDIFPortInst moved = candidates.get(random.nextInt(candidates.size()));
        moved.getNet().removePortInst(moved);
        target.addPortInst(moved);
    }

    private void disconnectLogical(Net net, boolean driver) {
        EDIFHierNet hierNet = net.getLogicalHierNet();
        if (hierNet == null) {
            return;
        }
        // (from the current connectivity, which earlier mutations may have changed)
        for (EDIFHierPortInst ehpi : getFlatLeafPortInsts(hierNet)) {
            if (ehpi.isOutput() != driver) {
                continue;
            }
            EDIFNet edifNet = ehpi.getNet();
            EDIFPortInst portInst = ehpi.getPortInst();
            EDIFHierCellInst parent = ehpi.getHierarchicalInst();
            if (!parent.isTopLevelInst() && parent.getCellType().getLibrary().isHDIPrimitivesLibrary()) {
                // Inside a macro (whose definition all its instances share): disconnect the macro
                // instance's port instead
                EDIFPortInst macroPort = null;
                for (EDIFPortInst p : edifNet.getPortInsts()) {
                    if (p.isTopLevelPort()) {
                        macroPort = parent.getInst().getPortInst(p.getName());
                    }
                }
                if (macroPort == null || macroPort.getNet() == null) {
                    continue;
                }
                edifNet = macroPort.getNet();
                portInst = macroPort;
                parent = parent.getParent();
            }
            if (!parent.isUniquified()) {
                // The definition is shared by several instances, which would all be changed
                continue;
            }
            edifNet.removePortInst(portInst);
        }
    }

    /**
     * Gets the pins on leaf cells (or black boxes) of the flat net a segment is part of, following
     * the current connectivity through the ports of hierarchical cells (unlike
     * {@link com.xilinx.rapidwright.edif.EDIFNetlist#getPhysicalPins(EDIFHierNet)}, which is cached).
     */
    private static List<EDIFHierPortInst> getFlatLeafPortInsts(EDIFHierNet segment) {
        List<EDIFHierPortInst> pins = new ArrayList<>();
        Deque<EDIFHierNet> queue = new ArrayDeque<>();
        Set<EDIFHierNet> visited = new HashSet<>();
        queue.add(segment);
        while (!queue.isEmpty()) {
            EDIFHierNet hn = queue.poll();
            if (!visited.add(hn)) {
                continue;
            }
            for (EDIFHierPortInst p : hn.getPortInsts()) {
                EDIFCellInst eci = p.getPortInst().getCellInst();
                if (eci == null) {
                    EDIFHierPortInst up = hn.getHierarchicalInst().isTopLevelInst() ? null : p.getPortInParent();
                    if (up != null && up.getNet() != null) {
                        queue.add(up.getHierarchicalNet());
                    }
                } else if (eci.getCellType().isLeafCellOrBlackBox()) {
                    pins.add(p);
                } else {
                    EDIFHierNet internal = p.getInternalNet();
                    if (internal != null) {
                        queue.add(internal);
                    }
                }
            }
        }
        return pins;
    }
}

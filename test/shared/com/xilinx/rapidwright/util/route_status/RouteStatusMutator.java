/*
 * Copyright (c) 2026, Advanced Micro Devices, Inc.
 * All rights reserved.
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

import com.xilinx.rapidwright.design.Cell;
import com.xilinx.rapidwright.design.Design;
import com.xilinx.rapidwright.design.DesignTools;
import com.xilinx.rapidwright.design.Net;
import com.xilinx.rapidwright.design.SiteInst;
import com.xilinx.rapidwright.design.SitePinInst;
import com.xilinx.rapidwright.device.Node;
import com.xilinx.rapidwright.device.PIP;
import com.xilinx.rapidwright.edif.EDIFHierCellInst;
import com.xilinx.rapidwright.edif.EDIFHierNet;
import com.xilinx.rapidwright.edif.EDIFHierPortInst;
import com.xilinx.rapidwright.edif.EDIFNet;
import com.xilinx.rapidwright.edif.EDIFPortInst;
import com.xilinx.rapidwright.edif.EDIFTools;
import com.xilinx.rapidwright.util.ReportRouteStatus;
import com.xilinx.rapidwright.util.ReportRouteStatusResult;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

/**
 * Applies seeded, reproducible mutations to a placed and/or routed design (unplacing cells,
 * unrouting nets, creating gaps, antennas, conflicts, etc.) in order to create corner cases for
 * comparing {@link ReportRouteStatus#reportVivadoRouteStatus(Design, boolean)} against Vivado's
 * `report_route_status`.
 *
 * A mutation spec is a comma separated list of {@code kind:amount} entries, where amount is a
 * fraction (0.0-1.0] of the eligible objects, or a count if greater than or equal to 1.
 */
public class RouteStatusMutator {

    private final Design design;
    private final Random random;
    private final List<String> log = new ArrayList<>();

    public RouteStatusMutator(Design design, long seed) {
        this.design = design;
        this.random = new Random(seed);
    }

    public List<String> getLog() {
        return log;
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

    private List<Cell> placedLeafCells() {
        List<Cell> cells = new ArrayList<>();
        for (Cell cell : design.getCells()) {
            if (cell.isPlaced() && !cell.isPortCell() && !cell.isRoutethru() && cell.getEDIFHierCellInst() != null
                    && !isInsideMacro(cell)) {
                cells.add(cell);
            }
        }
        cells.sort(Comparator.comparing(Cell::getName));
        return cells;
    }

    /**
     * Checks whether the cell is part of a macro primitive (e.g. LUT6_2, RAM32M), whose cells are
     * placed together: unplacing only some of them is not represented consistently in a DCP.
     */
    private static boolean isInsideMacro(Cell cell) {
        EDIFHierCellInst parent = cell.getEDIFHierCellInst().getParent();
        return parent != null && !parent.isTopLevelInst()
                && parent.getCellType().getLibrary().getName().equals(EDIFTools.EDIF_LIBRARY_HDI_PRIMITIVES_NAME);
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
            int before = log.size();
            switch (kind) {
                case "unroute":
                    // Fully unroute nets, keeping their site pins
                    for (Net net : pick(routedSignalNets(), amount)) {
                        net.unroute();
                        log.add(kind + " " + net);
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
                        log.add(kind + " " + net + " " + toUnroute);
                    }
                    break;
                case "unroutesource":
                    // Remove the routing driven by a net's source pin, leaving the sinks' branches
                    for (Net net : pick(routedSignalNets(), amount)) {
                        if (net.getSource() != null) {
                            DesignTools.unrouteSourcePin(net.getSource());
                            log.add(kind + " " + net);
                        }
                    }
                    break;
                case "removepip":
                    // Remove one random PIP, creating a gap and possibly an island
                    for (Net net : pick(routedSignalNets(), amount)) {
                        List<PIP> pips = new ArrayList<>(net.getPIPs());
                        PIP pip = pips.get(random.nextInt(pips.size()));
                        net.removePIP(pip);
                        log.add(kind + " " + net + " " + pip);
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
                        PIP added = addAntenna(net, usedNodes);
                        if (added != null) {
                            log.add(kind + " " + net + " " + added);
                        }
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
                        log.add(kind + " " + net + " " + other + " " + pip);
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
                        log.add(kind + " " + net + " " + spi);
                    }
                    break;
                case "lock":
                    for (Net net : pick(routedSignalNets(), amount)) {
                        net.lockRouting();
                        log.add(kind + " " + net);
                    }
                    break;
                case "unroutestatic":
                    for (Net net : new Net[]{design.getGndNet(), design.getVccNet()}) {
                        List<SitePinInst> sinks = sinkPins(net);
                        List<SitePinInst> toUnroute = pick(sinks, amount);
                        DesignTools.unroutePins(net, toUnroute);
                        log.add(kind + " " + net + " " + toUnroute.size());
                    }
                    break;
                case "unplace":
                    // Fully unplace cells, removing their site routing, pins and routing
                    Map<Net, Set<SitePinInst>> deferredRemovals = new HashMap<>();
                    for (Cell cell : pick(placedLeafCells(), amount)) {
                        DesignTools.fullyUnplaceCell(cell, deferredRemovals);
                        log.add(kind + " " + cell);
                    }
                    DesignTools.batchRemoveSitePins(deferredRemovals, true);
                    break;
                case "unplacekeep":
                    // Unplace cells, leaving any site routing, pins and routing behind
                    for (Cell cell : pick(placedLeafCells(), amount)) {
                        cell.getSiteInst().removeCell(cell.getBEL());
                        log.add(kind + " " + cell);
                    }
                    break;
                case "removesiteinst":
                    List<SiteInst> siteInsts = new ArrayList<>();
                    for (SiteInst si : design.getSiteInsts()) {
                        boolean macro = false;
                        for (Cell cell : si.getCells()) {
                            if (cell.getEDIFHierCellInst() != null && isInsideMacro(cell)) {
                                macro = true;
                                break;
                            }
                        }
                        if (!macro) {
                            siteInsts.add(si);
                        }
                    }
                    siteInsts.sort(Comparator.comparing(SiteInst::getName));
                    for (SiteInst si : pick(siteInsts, amount)) {
                        // (its cells are removed first, as removeSiteInst() leaves them in the design)
                        for (Cell cell : new ArrayList<>(si.getCells())) {
                            if (design.getCell(cell.getName()) == cell) {
                                if (cell.getBEL() == null) {
                                    // (not on a BEL of the site, so only in the design)
                                    cell.setSiteInst(null);
                                }
                                design.removeCell(cell);
                            }
                        }
                        design.removeSiteInst(si);
                        log.add(kind + " " + si);
                    }
                    break;
                case "removenet":
                    // Remove physical nets entirely, leaving the logical nets
                    for (Net net : pick(routedSignalNets(), amount)) {
                        design.removeNet(net);
                        log.add(kind + " " + net);
                    }
                    break;
                case "nodriver":
                    // Disconnect the logical driver of nets
                    for (Net net : pick(routedSignalNets(), amount)) {
                        if (disconnectLogical(net, true)) {
                            log.add(kind + " " + net);
                        }
                    }
                    break;
                case "multidriver":
                    // Move the driver of another net in the same cell onto nets, which then have
                    // two drivers (and the other net none)
                    for (Net net : pick(routedSignalNets(), amount)) {
                        String other = addSecondDriver(net);
                        if (other != null) {
                            log.add(kind + " " + net + " " + other);
                        }
                    }
                    break;
                case "noloads":
                    // Disconnect all logical loads of nets
                    for (Net net : pick(routedSignalNets(), amount)) {
                        if (disconnectLogical(net, false)) {
                            log.add(kind + " " + net);
                        }
                    }
                    break;
                case "unroutedesign":
                    design.unrouteDesign();
                    log.add(kind);
                    break;
                case "unplacedesign":
                    design.unplaceDesign();
                    log.add(kind);
                    break;
                default:
                    throw new RuntimeException("Unknown mutation: " + kind);
            }
            System.out.println("Mutation " + entry + ": " + (log.size() - before) + " changes");
        }
    }

    private PIP addAntenna(Net net, Set<Node> usedNodes) {
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
                return downhill;
            }
        }
        return null;
    }

    /**
     * Moves the leaf driver of another net, in the cell of the leaf driver of the given net, onto
     * the given net. Cells whose definition is shared by several instances are left alone, as
     * editing them would change every instance.
     * @return The name of the other net, or null if there is none.
     */
    String addSecondDriver(Net net) {
        EDIFHierNet hierNet = net.getLogicalHierNet();
        if (hierNet == null) {
            return null;
        }
        // (from the current connectivity, which earlier mutations may have changed)
        List<EDIFHierPortInst> pins = RouteStatusNetlist.getFlatLeafPortInsts(hierNet);
        EDIFHierPortInst driver = null;
        for (EDIFHierPortInst ehpi : pins) {
            if (ehpi.isOutput()) {
                driver = ehpi;
                break;
            }
        }
        if (driver == null || driver.getHierarchicalInst().getCellType().getLibrary().isHDIPrimitivesLibrary()
                || !driver.getHierarchicalInst().isUniquified()) {
            return null;
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
            return null;
        }
        EDIFPortInst moved = candidates.get(random.nextInt(candidates.size()));
        EDIFNet other = moved.getNet();
        other.removePortInst(moved);
        target.addPortInst(moved);
        return other.getName();
    }

    private boolean disconnectLogical(Net net, boolean driver) {
        EDIFHierNet hierNet = net.getLogicalHierNet();
        if (hierNet == null) {
            return false;
        }
        boolean changed = false;
        // (from the current connectivity, which earlier mutations may have changed)
        for (EDIFHierPortInst ehpi : RouteStatusNetlist.getFlatLeafPortInsts(hierNet)) {
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
            changed = true;
        }
        return changed;
    }

    public static void main(String[] args) throws IOException {
        if (args.length < 5) {
            System.out.println("USAGE: RouteStatusMutator <in.dcp> <in.edf|-> <spec> <seed> <out.dcp> [inmem_report.rpt]");
            System.exit(1);
        }
        Design design = args[1].equals("-") ? Design.readCheckpoint(args[0])
                : Design.readCheckpoint(Paths.get(args[0]), Paths.get(args[1]));
        RouteStatusMutator mutator = new RouteStatusMutator(design, Long.parseLong(args[3]));
        mutator.apply(args[2]);
        Files.write(Paths.get(args[4] + ".mutations.txt"), mutator.getLog());
        if (args.length > 5) {
            ReportRouteStatusResult rrs = ReportRouteStatus.reportVivadoRouteStatus(design, true);
            Files.write(Paths.get(args[5]), rrs.toListAllNetsString().getBytes());
        }
        design.writeCheckpoint(args[4]);
    }
}

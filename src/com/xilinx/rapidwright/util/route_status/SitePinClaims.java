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

import static com.xilinx.rapidwright.util.route_status.NetStatus.HAS_ROUTING;
import static com.xilinx.rapidwright.util.route_status.NetStatus.LOGICAL_ONLY;
import static com.xilinx.rapidwright.util.route_status.NetStatus.NODRIVER;
import static com.xilinx.rapidwright.util.route_status.NetStatus.UNROUTED;
import static com.xilinx.rapidwright.util.route_status.NetStatus.has;
import static com.xilinx.rapidwright.util.route_status.NodeKeys.concat;
import static com.xilinx.rapidwright.util.route_status.NodeKeys.nodeKey;
import static com.xilinx.rapidwright.util.route_status.NodeKeys.sortedDistinct;
import static com.xilinx.rapidwright.util.route_status.RouteStatusEngine.parallelFor;
import static com.xilinx.rapidwright.util.route_status.RouteStatusNetlist.getLeafPortInsts;
import static com.xilinx.rapidwright.util.route_status.RouteStatusNetlist.getPhysicalPins;
import static com.xilinx.rapidwright.util.route_status.RouteStatusNetlist.getSegments;
import static com.xilinx.rapidwright.util.route_status.SiteRouting.getPrimarySitePinName;
import static com.xilinx.rapidwright.util.route_status.SiteRouting.isCascadeAddressPin;
import static com.xilinx.rapidwright.util.route_status.SiteRouting.reachesPlacedCellPin;
import static com.xilinx.rapidwright.util.route_status.SiteRouting.storedPins;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import com.xilinx.rapidwright.design.Cell;
import com.xilinx.rapidwright.design.Design;
import com.xilinx.rapidwright.design.Net;
import com.xilinx.rapidwright.design.NetType;
import com.xilinx.rapidwright.design.SiteInst;
import com.xilinx.rapidwright.design.SitePinInst;
import com.xilinx.rapidwright.device.BEL;
import com.xilinx.rapidwright.device.BELPin;
import com.xilinx.rapidwright.device.Node;
import com.xilinx.rapidwright.device.Series;
import com.xilinx.rapidwright.device.Site;
import com.xilinx.rapidwright.device.SiteTypeEnum;
import com.xilinx.rapidwright.edif.EDIFHierNet;
import com.xilinx.rapidwright.edif.EDIFHierPortInst;
import com.xilinx.rapidwright.edif.EDIFPort;
import com.xilinx.rapidwright.util.ParallelismTools;
import com.xilinx.rapidwright.util.Utils;

/**
 * Finds the site pins that each net uses without routing to them (from its site routing, or from the
 * placement of its pins), which are its sinks, or conflict with the routing of other nets.
 */
final class SitePinClaims {

    private final Design design;
    private final Entries entries;

    /** The input site pins of a site type: their site wires, indices and the other BELs on them */
    private static class InputSitePins {
        final String[] siteWires;
        final int[] siteWireIndices;
        /** The names of the pins in the primary site type */
        final String[] primaryPinNames;
        final String[][] otherBELs;
        /** Per pin: whether it connects to the pad of an I/O site */
        final boolean[] isPad;

        InputSitePins(SiteInst si) {
            List<String> wires = new ArrayList<>();
            List<Integer> wireIndices = new ArrayList<>();
            List<String> primaryNames = new ArrayList<>();
            List<String[]> others = new ArrayList<>();
            for (String pinName : si.getSitePinNames()) {
                if (!si.isSitePinInput(pinName)) {
                    continue;
                }
                // Site port BELs are named after their site pins
                BEL portBEL = si.getBEL(pinName);
                if (portBEL == null || portBEL.getPins().length == 0) {
                    continue;
                }
                String siteWire = portBEL.getPins()[0].getSiteWireName();
                if (siteWire == null) {
                    continue;
                }
                List<String> otherBELs = new ArrayList<>();
                for (BELPin pin : si.getSiteWirePins(siteWire)) {
                    if (pin.getBEL() != portBEL) {
                        otherBELs.add(pin.getBELName());
                    }
                }
                wires.add(siteWire);
                wireIndices.add(portBEL.getPins()[0].getSiteWireIndex());
                // Pins of alternate site types are named differently in the primary site type
                primaryNames.add(getPrimarySitePinName(si, pinName));
                others.add(otherBELs.toArray(new String[0]));
            }
            siteWires = wires.toArray(new String[0]);
            siteWireIndices = wireIndices.stream().mapToInt(Integer::intValue).toArray();
            primaryPinNames = primaryNames.toArray(new String[0]);
            otherBELs = others.toArray(new String[0][]);
            isPad = new boolean[otherBELs.length];
            for (int p = 0; p < otherBELs.length; p++) {
                isPad[p] = Arrays.asList(otherBELs[p]).contains("PAD");
            }
        }

        /** Per tile type and site index in the tile: the indices of the pins in the site */
        private final Map<Integer, int[]> pinIndices = new ConcurrentHashMap<>();

        /** Gets the indices of the pins in the given site (they differ between tile types). */
        int[] getPinIndices(Site site) {
            int key = (site.getTile().getTileTypeEnum().ordinal() << 8) | site.getSiteIndexInTile();
            int[] indices = pinIndices.get(key);
            if (indices == null) {
                indices = new int[primaryPinNames.length];
                for (int p = 0; p < indices.length; p++) {
                    indices[p] = site.getPinIndex(primaryPinNames[p]);
                }
                pinIndices.put(key, indices);
            }
            return indices;
        }
    }

    /** Keyed by the site type and the primary site type of the site */
    private final Map<List<SiteTypeEnum>, InputSitePins> inputSitePins = new ConcurrentHashMap<>();

    /** Flags returned by {@link #findUnroutedDriverPins} */
    static final int DRIVER_PIN_UNROUTED = 1;

    static final int DRIVER_PIN_CONFLICT = 2;

    SitePinClaims(Design design, Entries entries) {
        this.design = design;
        this.entries = entries;
    }

    InputSitePins getInputSitePins(SiteInst si) {
        List<SiteTypeEnum> key = Arrays.asList(si.getSiteTypeEnum(), si.getSite().getSiteTypeEnum());
        InputSitePins pins = inputSitePins.get(key);
        if (pins == null) {
            pins = new InputSitePins(si);
            inputSitePins.put(key, pins);
        }
        return pins;
    }

    /** Computes {@link Entries#sitePinKeys} in one pass over all site instances (in parallel chunks). */
    void computeSitePinKeys() {
        entries.sitePinKeys = new long[entries.size()][];
        entries.driverPinKeys = new long[entries.size()][];
        Map<Net, Integer> entryOf = entries.getEntryOfNet();
        List<SiteInst> siteInsts = new ArrayList<>(design.getSiteInsts());
        Set<SiteInst> portSites = getPortSites();
        int chunks = Math.max(1, Math.min(siteInsts.size() / 1024, ParallelismTools.maxParallelism() * 4));
        KeyBuffer[] found = new KeyBuffer[chunks];
        parallelFor(chunks, (c) -> {
            int from = (int) ((long) siteInsts.size() * c / chunks);
            int to = (int) ((long) siteInsts.size() * (c + 1) / chunks);
            found[c] = new KeyBuffer(Math.max(64, Math.min((to - from) * 8, 1 << 16)));
            for (int s = from; s < to; s++) {
                addSitePinKeys(siteInsts.get(s), entryOf, portSites, found[c]);
            }
        });
        int[] counts = new int[entries.size()];
        for (KeyBuffer buffer : found) {
            for (int i = 0; i < buffer.size; i++) {
                counts[buffer.entries[i]]++;
            }
        }
        int[] fill = new int[entries.size()];
        for (KeyBuffer buffer : found) {
            for (int i = 0; i < buffer.size; i++) {
                int entry = buffer.entries[i];
                if (entries.sitePinKeys[entry] == null) {
                    entries.sitePinKeys[entry] = new long[counts[entry]];
                }
                entries.sitePinKeys[entry][fill[entry]++] = buffer.keys[i];
            }
        }
        for (int i = 0; i < entries.sitePinKeys.length; i++) {
            if (entries.sitePinKeys[i] != null) {
                entries.sitePinKeys[i] = sortedDistinct(entries.sitePinKeys[i], entries.sitePinKeys[i].length);
            }
        }
    }

    /** Gets the sites of top level ports (whose cells are named after the bits of the ports). */
    private Set<SiteInst> getPortSites() {
        Set<SiteInst> portSites = Collections.newSetFromMap(new IdentityHashMap<>());
        for (EDIFPort port : design.getNetlist().getTopCell().getPorts()) {
            int width = port.isBus() ? port.getWidth() : 1;
            for (int b = 0; b < width; b++) {
                Cell cell = design.getCell(port.isBus() ? port.getPortInstNameFromPort(b) : port.getName());
                if (cell != null && cell.isPortCell() && cell.getSiteInst() != null) {
                    portSites.add(cell.getSiteInst());
                }
            }
        }
        return portSites;
    }

    /** Entries with the keys of nodes they use, in parallel arrays */
    private static final class KeyBuffer {
        int[] entries;
        long[] keys;
        int size;

        KeyBuffer(int capacity) {
            entries = new int[capacity];
            keys = new long[capacity];
        }

        void add(int entry, long key) {
            if (size == keys.length) {
                entries = Arrays.copyOf(entries, size * 2);
                keys = Arrays.copyOf(keys, size * 2);
            }
            entries[size] = entry;
            keys[size++] = key;
        }
    }

    /** Adds the input site pins of a site that the site routing of an entry uses to a buffer. */
    private void addSitePinKeys(SiteInst si, Map<Net, Integer> entryOf, Set<SiteInst> portSites, KeyBuffer buffer) {
        InputSitePins pins = getInputSitePins(si);
        int[] pinIndices = null;
        for (int p = 0; p < pins.siteWires.length; p++) {
            int siteWireIndex = pins.siteWireIndices[p];
            Net net = (siteWireIndex >= 0) ? si.getNetFromSiteWireIndex(siteWireIndex)
                    : si.getNetFromSiteWire(pins.siteWires[p]);
            Integer entry = (net == null) ? null : entryOf.get(net);
            if (entry == null) {
                continue;
            }
            if (pins.isPad[p] || (portSites.contains(si) && isDrivenByPort(si, pins.otherBELs[p]))) {
                // (an I/O pad is connected to its top level port without routing)
                continue;
            }
            if (pinIndices == null) {
                pinIndices = pins.getPinIndices(si.getSite());
            }
            int pinIndex = pinIndices[p];
            Node node = (pinIndex < 0) ? null : si.getSite().getConnectedNode(pinIndex);
            if (node != null) {
                buffer.add(entry, nodeKey(node));
            }
        }
    }

    /** Checks whether a top level port placed in the site (on its pad) drives the site wire. */
    private static boolean isDrivenByPort(SiteInst si, String[] otherBELs) {
        for (String bel : otherBELs) {
            Cell cell = si.getCell(bel);
            if (cell != null && cell.isPortCell()) {
                return true;
            }
        }
        return false;
    }

    /**
     * Adds the input site pins of the placed LUT loads of a routed static net whose site wires carry
     * another net (i.e. LUT inputs tied to it in the netlist only) as sinks of the static net, which
     * need routing. (Vivado does not count such an input without any site routing as a load.)
     */
    void addStaticLoadSitePins(int entry, List<FlatNet> flats, boolean onlyIfRouted) {
        Net net = entries.nets.get(entry);
        if (onlyIfRouted && (net == null || !net.hasPIPs())) {
            return;
        }
        // The pins are looked at by segment (rather than by flat net, as a static net may be one
        // huge flat net)
        List<EDIFHierNet> segments = Collections.synchronizedList(new ArrayList<>());
        parallelFor(flats.size(), (f) -> segments.addAll(flats.get(f).getSegments(design)));
        List<Long> keys = Collections.synchronizedList(new ArrayList<>());
        parallelFor(segments.size(), (i) -> {
            for (EDIFHierPortInst p : RouteStatusNetlist.getLeafPortInsts(segments.get(i))) {
                if (p.isOutput() || !mayBePlacedOnLUT(p.getPortInst().getCellInst().getCellType().getName())) {
                    continue;
                }
                Cell cell = p.getPhysicalCell(design);
                if (cell == null || !cell.isPlaced() || !cell.getBEL().isLUT()) {
                    // (other cells may have their inputs tied to constants inside their sites)
                    continue;
                }
                SiteInst si = cell.getSiteInst();
                for (String physPin : RouteStatusNetlist.getPhysicalPins(cell, p.getPortInst().getName())) {
                    BELPin belPin = cell.getBEL().getPin(physPin);
                    Net siteWireNet = (belPin == null) ? null : si.getNetFromSiteWireIndex(belPin.getSiteWireIndex());
                    if (siteWireNet == null || siteWireNet == net) {
                        continue;
                    }
                    for (BELPin sitePort : si.getSiteWirePins(belPin.getSiteWireIndex())) {
                        // The port BEL of an input site pin drives the site wire
                        if (sitePort.isSitePort() && sitePort.isOutput()) {
                            Node node = si.getSite().getConnectedNode(sitePort.getBELName());
                            if (node != null) {
                                keys.add(nodeKey(node));
                            }
                        }
                    }
                }
            }
        });
        if (keys.isEmpty()) {
            return;
        }
        if (!onlyIfRouted) {
            // (these site pins conflict with the routing of other nets using them)
            long[] ties = new long[keys.size()];
            for (int i = 0; i < ties.length; i++) {
                ties[i] = keys.get(i);
            }
            entries.staticTieKeys.put(entry, sortedDistinct(ties, ties.length));
        }
        long[] old = entries.sitePinKeys[entry];
        int n = (old == null) ? 0 : old.length;
        long[] merged = Arrays.copyOf(old == null ? new long[0] : old, n + keys.size());
        for (int i = 0; i < keys.size(); i++) {
            merged[n + i] = keys.get(i);
        }
        entries.sitePinKeys[entry] = sortedDistinct(merged, merged.length);
    }

    /** Checks whether a cell of the given type may be placed on a LUT BEL (excluding common types that are not). */
    private static boolean mayBePlacedOnLUT(String type) {
        return !(type.startsWith("FD") || type.startsWith("CARRY") || type.startsWith("MUXF") || type.startsWith("DSP")
                || type.startsWith("RAMB") || type.startsWith("FIFO"));
    }

    /**
     * Finds the output site pins of the placed drivers of a net with several drivers that its
     * routing does not start from (see {@link Entries#driverPinKeys}).
     * @return {@link #DRIVER_PIN_UNROUTED} if there are any, plus {@link #DRIVER_PIN_CONFLICT} if the
     * site routing of another net uses the output of any of these drivers that is a LUT.
     */
    int findUnroutedDriverPins(Net net, FlatNet flat, RouteGraph graph, int entry) {
        List<Long> keys = new ArrayList<>();
        int flags = 0;
        for (LeafPin pin : flat.getLeafPins(design)) {
            if (!pin.isOutput() || !pin.isPlaced()) {
                continue;
            }
            SiteInst si = pin.cell.getSiteInst();
            for (String physPin : pin.getPhysicalPins()) {
                BELPin belPin = pin.cell.getBEL().getPin(physPin);
                if (belPin == null) {
                    continue;
                }
                Net siteNet = si.getNetFromSiteWireIndex(belPin.getSiteWireIndex());
                List<Node> nodes = new ArrayList<>();
                if (siteNet != null && siteNet != net) {
                    // Site routed as (the source of) another net: its output site pins in the site
                    for (SitePinInst spi : storedPins(siteNet)) {
                        if (spi.isOutPin() && spi.getSiteInst() == si) {
                            nodes.add(spi.getConnectedNode());
                        }
                    }
                }
                if (nodes.isEmpty()) {
                    for (BELPin sitePort : si.getSiteWirePins(belPin.getSiteWireIndex())) {
                        // The port BEL of an output site pin is driven by the site wire
                        if (sitePort.isSitePort() && sitePort.isInput()) {
                            nodes.add(si.getSite().getConnectedNode(sitePort.getBELName()));
                        }
                    }
                }
                for (Node node : nodes) {
                    if (node != null && graph.index.get(nodeKey(node)) < 0) {
                        keys.add(nodeKey(node));
                        if (siteNet != null && siteNet != net && pin.cell.getBEL().isLUT()) {
                            // (the output of a LUT is site routed as the other net)
                            flags |= DRIVER_PIN_CONFLICT;
                        }
                    }
                }
            }
        }
        if (keys.isEmpty()) {
            return 0;
        }
        long[] array = keys.stream().mapToLong(Long::longValue).toArray();
        entries.driverPinKeys[entry] = sortedDistinct(array, array.length);
        return flags | DRIVER_PIN_UNROUTED;
    }

    /**
     * For each unrouted (or undriven) net without a physical net, adds the input site pins of its placed loads
     * whose site wires carry another net to {@link Entries#driverPinKeys}: Vivado still considers them the
     * net's (from the placement), in conflict with the other net.
     */
    void addClaimedLoadSitePins() {
        parallelFor(entries.nets.size(), (i) -> {
            FlatNet flat = entries.flats.get(i);
            boolean unrouted = has(entries.status[i], UNROUTED) || (has(entries.status[i], NODRIVER) && !has(entries.status[i], HAS_ROUTING));
            if (entries.nets.get(i) != null || flat == null || !unrouted || entries.aliasNets.containsKey(i)) {
                return;
            }
            List<Long> keys = new ArrayList<>();
            for (LeafPin pin : flat.getLeafPins(design)) {
                if (pin.isOutput() || !pin.isPlaced()) {
                    continue;
                }
                SiteInst si = pin.cell.getSiteInst();
                for (String physPin : pin.getPhysicalPins()) {
                    BELPin belPin = pin.cell.getBEL().getPin(physPin);
                    if (belPin == null || si.getNetFromSiteWireIndex(belPin.getSiteWireIndex()) == null) {
                        continue;
                    }
                    for (BELPin sitePort : si.getSiteWirePins(belPin.getSiteWireIndex())) {
                        // The port BEL of an input site pin drives the site wire
                        if (sitePort.isSitePort() && sitePort.isOutput()) {
                            int pinIndex = si.getSite().getPinIndex(getPrimarySitePinName(si, sitePort.getBELName()));
                            Node node = (pinIndex < 0) ? null : si.getSite().getConnectedNode(pinIndex);
                            if (node != null) {
                                keys.add(nodeKey(node));
                            }
                        }
                    }
                }
            }
            if (!keys.isEmpty()) {
                long[] array = new long[keys.size()];
                for (int k = 0; k < array.length; k++) {
                    array[k] = keys.get(k);
                }
                long[] merged = concat(entries.driverPinKeys[i], array);
                entries.driverPinKeys[i] = sortedDistinct(merged, merged.length);
                entries.status[i] |= LOGICAL_ONLY;
            }
        });
    }

    /**
     * Adds the nodes of the input site pins used by the site routing of each (non static) net, and
     * of the output site pins of its unrouted drivers, to the nodes it uses, so that routing of
     * another net to such a site pin is found to conflict. The site routing of static nets is not
     * precise enough.
     */
    void addSitePinNodes() {
        entries.numRoutingKeys = new int[entries.sitePinKeys.length];
        Arrays.fill(entries.numRoutingKeys, -1);
        for (int i = 0; i < entries.sitePinKeys.length; i++) {
            long[] extra = concat(entries.sitePinKeys[i], entries.driverPinKeys[i]);
            Net net = entries.nets.get(i);
            if (net != null && net.isStaticNet()) {
                extra = entries.staticTieKeys.get(i);
            }
            if (extra == null) {
                continue;
            }
            long[] keys = entries.nodeKeys[i];
            int n = (keys == null) ? 0 : keys.length;
            entries.numRoutingKeys[i] = n;
            long[] merged = Arrays.copyOf(keys == null ? new long[0] : keys, n + extra.length);
            System.arraycopy(extra, 0, merged, n, extra.length);
            // (keys may repeat, which markConflicts ignores within a net)
            entries.nodeKeys[i] = merged;
        }
    }

    /**
     * Gets the sorted keys of the nodes of the input site pins of a net whose site routing does not
     * lead to a placed cell pin (e.g. left behind by unplacing a cell), which are not loads.
     */
    static long[] getStaleSitePinKeys(Net net, NetType staticType) {
        List<Long> keys = new ArrayList<>();
        for (SiteInst si : net.getSiteInsts()) {
            for (String siteWire : si.getSiteWiresFromNet(net)) {
                for (BELPin pin : si.getSiteWirePins(siteWire)) {
                    // The port BEL of an input site pin drives the site wire
                    if (!pin.isSitePort() || !pin.isOutput()) {
                        continue;
                    }
                    if (reachesPlacedCellPin(si, siteWire, staticType)
                            && !(staticType != null && isCascadeAddressPin(pin.getBELName()))) {
                        continue;
                    }
                    SitePinInst spi = (staticType != null) ? si.getSitePinInst(pin.getBELName()) : null;
                    if (spi != null && spi.getNet() == net && !(staticType == NetType.GND
                            && si.getSite().getTile().getDevice().getSeries() == Series.Versal)) {
                        // A stored sink pin of a static net is a load regardless (except for GND in
                        // Versal, which ties it off at the inverter behind the pin instead)
                        continue;
                    }
                    int pinIndex = si.getSite().getPinIndex(getPrimarySitePinName(si, pin.getBELName()));
                    Node node = (pinIndex < 0) ? null : si.getSite().getConnectedNode(pinIndex);
                    if (node != null) {
                        keys.add(nodeKey(node));
                    }
                }
            }
        }
        if (staticType == null) {
            // Stored input pins of the net in slices without any site wire assignment, reaching no
            // placed cell
            for (SitePinInst spi : net.getPins()) {
                SiteInst si = spi.getSiteInst();
                if (spi.isOutPin() || si == null || !Utils.isSLICE(si) || spi.getConnectedNode() == null
                        || si.getNetFromSiteWire(spi.getName()) != null) {
                    continue;
                }
                if (!reachesPlacedCellPin(si, spi.getName(), null)) {
                    keys.add(nodeKey(spi.getConnectedNode()));
                }
            }
        }
        long[] result = new long[keys.size()];
        for (int i = 0; i < result.length; i++) {
            result[i] = keys.get(i);
        }
        Arrays.sort(result);
        return result;
    }
}

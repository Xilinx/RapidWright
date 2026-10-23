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
import com.xilinx.rapidwright.design.Net;
import com.xilinx.rapidwright.design.NetType;
import com.xilinx.rapidwright.design.PartitionPin;
import com.xilinx.rapidwright.design.SiteInst;
import com.xilinx.rapidwright.design.SitePinInst;
import com.xilinx.rapidwright.device.BEL;
import com.xilinx.rapidwright.device.BELPin;
import com.xilinx.rapidwright.device.IntentCode;
import com.xilinx.rapidwright.device.Node;
import com.xilinx.rapidwright.device.PIP;
import com.xilinx.rapidwright.device.Series;
import com.xilinx.rapidwright.device.Site;
import com.xilinx.rapidwright.device.SitePIP;
import com.xilinx.rapidwright.device.SitePin;
import com.xilinx.rapidwright.device.SiteTypeEnum;
import com.xilinx.rapidwright.device.Tile;
import com.xilinx.rapidwright.edif.EDIFHierCellInst;
import com.xilinx.rapidwright.edif.EDIFHierNet;
import com.xilinx.rapidwright.edif.EDIFHierPortInst;
import com.xilinx.rapidwright.edif.EDIFNet;
import com.xilinx.rapidwright.edif.EDIFPort;
import com.xilinx.rapidwright.edif.EDIFPortInst;
import com.xilinx.rapidwright.util.ParallelismTools;
import com.xilinx.rapidwright.util.ReportRouteStatus;
import com.xilinx.rapidwright.util.ReportRouteStatusResult;
import com.xilinx.rapidwright.util.Utils;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Computes the route status of a design the way Vivado's `report_route_status` does. See
 * {@link ReportRouteStatus#reportVivadoRouteStatus(Design, boolean)}.
 *
 * Per-net statuses are kept as bit masks of {@link NetRouteStatus} ordinals. Nets are examined in
 * parallel; resource conflicts are found by sorting the nodes used by all nets.
 */
public class RouteStatusEngine {

    private static final int UNPLACED_ALL = bit(NetRouteStatus.UNPLACED_ALL);
    private static final int UNPLACED = bit(NetRouteStatus.UNPLACED);
    private static final int UNROUTED = bit(NetRouteStatus.UNROUTED);
    private static final int PARTIAL = bit(NetRouteStatus.PARTIAL);
    private static final int GAPS = bit(NetRouteStatus.GAPS);
    private static final int HIERPORT = bit(NetRouteStatus.HIERPORT);
    private static final int CONFLICTS = bit(NetRouteStatus.CONFLICTS);
    private static final int ANTENNAS = bit(NetRouteStatus.ANTENNAS);
    private static final int NODRIVER = bit(NetRouteStatus.NODRIVER);
    private static final int LOCKED_NODES = bit(NetRouteStatus.LOCKED_NODES);
    private static final int HAS_ROUTING = bit(NetRouteStatus.HAS_ROUTING);
    private static final int INTRASITE = bit(NetRouteStatus.INTRASITE);
    private static final int NOLOADS = bit(NetRouteStatus.NOLOADS);
    private static final int ROUTED = bit(NetRouteStatus.ROUTED);

    private static int bit(NetRouteStatus s) {
        return 1 << s.ordinal();
    }

    private static boolean has(int status, int bits) {
        return (status & bits) != 0;
    }

    private final Design design;
    private final boolean perNet;

    /** The nets to report: flat (logical) nets, then physical-only nets, then GND and VCC */
    private final List<RouteStatusNetlist.FlatNet> entryFlat = new ArrayList<>();
    private final List<Net> entryNet = new ArrayList<>();
    /** Names of entries without a flat net (physical-only and static nets) */
    private final List<String> entryName = new ArrayList<>();
    private int[] status;
    private boolean[] counted;
    /** Per entry, the sorted distinct keys of the nodes used by its PIPs (null if none) */
    private long[][] nodeKeys;

    private RouteStatusEngine(Design design, boolean perNet) {
        this.design = design;
        this.perNet = perNet;
    }

    /**
     * See {@link ReportRouteStatus#reportVivadoRouteStatus(Design, boolean)}.
     */
    public static ReportRouteStatusResult run(Design design, boolean perNet) {
        return new RouteStatusEngine(design, perNet).run();
    }

    private static final int PHYSICAL_ONLY = -1;

    /** Nets without connections that are only counted (as internal to a site), not entries */
    private long unconnectedNets;

    /** Set when the VCC net has unrouted ties to enable pins (of nets without a physical net) */
    private volatile boolean unroutedVccTies;

    /** Internal status (not listed): a net with no placed pins, but with routing */
    private static final int NO_PLACED_PINS_ROUTED = 1 << 30;

    /** The net of routing that only reserves resources */
    private static final String DUMMY_ROUTE_NET = "GLOBAL_DUMMY_ROUTE";

    private ReportRouteStatusResult run() {
        IntHashSet physNameHashes = new IntHashSet(design.getNets().size());
        for (Net net : design.getNets()) {
            physNameHashes.add(net.getName().hashCode());
        }
        // (nets without connections need not be listed in the summary)
        RouteStatusNetlist flatNetlist = new RouteStatusNetlist(design, physNameHashes, !perNet);
        unconnectedNets = flatNetlist.getUnconnectedNets();
        boolean[] hasLogicalStatic = new boolean[2];
        // Loads of the static nets, and how many are unplaced
        int[] staticLoads = new int[2];
        int[] staticUnplacedLoads = new int[2];
        List<List<RouteStatusNetlist.FlatNet>> staticFlats = Arrays.asList(new ArrayList<>(), new ArrayList<>());
        // The flat nets that are entries, found per list of the netlist (in parallel), then numbered
        List<List<RouteStatusNetlist.FlatNet>> lists = flatNetlist.getFlatNetLists();
        RouteStatusNetlist.FlatNet[][] listEntries = new RouteStatusNetlist.FlatNet[lists.size()][];
        int[] listSizes = new int[lists.size()];
        List<RouteStatusNetlist.FlatNet> statics = Collections.synchronizedList(new ArrayList<>());
        parallelFor(lists.size(), (l) -> {
            List<RouteStatusNetlist.FlatNet> list = lists.get(l);
            RouteStatusNetlist.FlatNet[] entries = new RouteStatusNetlist.FlatNet[list.size()];
            int size = 0;
            for (RouteStatusNetlist.FlatNet flat : list) {
                if (flat.mergedInto != null) {
                    continue;
                }
                if (flat.staticType != null) {
                    statics.add(flat);
                    continue;
                }
                if (flat.insideMacro && flat.leafDrivers == 0 && !flat.hasTopPorts()) {
                    // Undriven pins inside macro primitives are tied to a constant by Vivado (its
                    // expansion of the macro includes the constant source)
                    continue;
                }
                entries[size++] = flat;
            }
            listEntries[l] = entries;
            listSizes[l] = size;
        });
        for (RouteStatusNetlist.FlatNet flat : statics) {
            int t = flat.staticType == NetType.GND ? 0 : 1;
            hasLogicalStatic[t] = true;
            staticLoads[t] += getLoads(flat) - flat.placedPortLoads;
            staticUnplacedLoads[t] += flat.unplacedLeafPins - flat.unplacedLeafDrivers;
            staticFlats.get(t).add(flat);
        }
        int[] listOffsets = new int[lists.size() + 1];
        for (int l = 0; l < lists.size(); l++) {
            listOffsets[l + 1] = listOffsets[l] + listSizes[l];
        }
        int numFlat = listOffsets[lists.size()];
        RouteStatusNetlist.FlatNet[] flats = new RouteStatusNetlist.FlatNet[numFlat];
        parallelFor(lists.size(), (l) -> {
            for (int j = 0; j < listSizes[l]; j++) {
                listEntries[l][j].entry = listOffsets[l] + j;
            }
            System.arraycopy(listEntries[l], 0, flats, listOffsets[l], listSizes[l]);
        });
        entryFlat.addAll(Arrays.asList(flats));
        entryName.addAll(Collections.nCopies(numFlat, null));
        // Each physical net is named after the segment naming a flat net, after another of its
        // segments (an alias, which only matters if it has site routing), or is physical only
        Net[] physNets = design.getNets().toArray(new Net[0]);
        Net[] flatPhysNets = new Net[numFlat];
        // Per physical net: 1 + the entry of the flat net it is an alias of, or PHYSICAL_ONLY
        int[] aliasOrPhysicalOnly = new int[physNets.length];
        flatNetlist.prepareSegmentLookup();
        parallelFor(physNets.length, (i) -> {
            Net net = physNets[i];
            if (net.isStaticNet() || net.isUsedNet()) {
                return;
            }
            RouteStatusNetlist.Segment seg = flatNetlist.getSegment(net.getName());
            if (seg == null) {
                aliasOrPhysicalOnly[i] = PHYSICAL_ONLY;
                return;
            }
            RouteStatusNetlist.FlatNet flat = seg.flat.find();
            if (flat.entry < 0) {
                // Not an entry of its own (e.g. a static net)
                return;
            }
            if (seg.names(flat)) {
                flatPhysNets[flat.entry] = net;
            } else if (!net.getSiteInsts().isEmpty() || net.hasPIPs()) {
                aliasOrPhysicalOnly[i] = flat.entry + 1;
            }
        });
        entryNet.addAll(Arrays.asList(flatPhysNets));
        List<Integer> uncounted = new ArrayList<>();
        int dummyEntry = -1;
        for (int i = 0; i < physNets.length; i++) {
            Net net = physNets[i];
            if (aliasOrPhysicalOnly[i] == PHYSICAL_ONLY) {
                if (flatNetlist.isInsideBlackBox(net.getName())) {
                    // Routing of the internals of a black box is not part of the design
                    continue;
                }
                if (net.getName().equals(DUMMY_ROUTE_NET)) {
                    dummyEntry = entryFlat.size();
                    uncounted.add(dummyEntry);
                }
                entryFlat.add(null);
                entryNet.add(net);
                entryName.add(net.getName());
            } else if (aliasOrPhysicalOnly[i] > 0) {
                int entry = aliasOrPhysicalOnly[i] - 1;
                Net named = entryNet.get(entry);
                if (net.hasPIPs() && (named == null || !named.hasPIPs())) {
                    // The routing of a net stored under the name of another of its segments, which
                    // Vivado uses when the net named after the flat net has none. (When several
                    // physical nets of a flat net have routing, Vivado keeps that of one of them,
                    // in an order not known here: that of the net named after the flat net is used.)
                    entryNet.set(entry, net);
                    if (named != null) {
                        aliasNets.computeIfAbsent(entry, (k) -> new ArrayList<>()).add(named);
                    }
                } else {
                    aliasNets.computeIfAbsent(entry, (k) -> new ArrayList<>()).add(net);
                }
            }
        }

        // Vivado represents all GND (VCC) nets as a single net. It is listed if it is used physically
        // or logically, but only counted in the summary if it is used logically.
        int[] staticEntry = {-1, -1};
        for (NetType type : new NetType[]{NetType.GND, NetType.VCC}) {
            String name = (type == NetType.GND) ? Net.GND_NET : Net.VCC_NET;
            Net net = design.getNet(name);
            boolean hasLogical = hasLogicalStatic[type == NetType.GND ? 0 : 1];
            boolean hasPhysical = net != null && (!net.getPins().isEmpty() || net.hasPIPs() || !net.getSiteInsts().isEmpty());
            if (!hasLogical && !hasPhysical) {
                continue;
            }
            if (!hasLogical) {
                uncounted.add(entryFlat.size());
            }
            staticEntry[type == NetType.GND ? 0 : 1] = entryFlat.size();
            entryFlat.add(null);
            entryNet.add(net);
            entryName.add(name);
        }

        int numEntries = entryFlat.size();
        status = new int[numEntries];
        nodeKeys = new long[numEntries][];
        counted = new boolean[numEntries];
        Arrays.fill(counted, true);
        for (int i : uncounted) {
            counted[i] = false;
        }
        List<PartitionPin> partitionPins = design.getPartitionPins();
        partitionPinKeys = new long[partitionPins.size()];
        int numPartitionPins = 0;
        for (PartitionPin pp : partitionPins) {
            Tile tile = pp.getTile();
            if (pp.isPort() || tile == null || pp.getWireIndex() < 0 || pp.getWireIndex() >= tile.getWireCount()) {
                // Only the partition pins of (black box) instances lead to another partition; those
                // of top level ports (e.g. of a reconfigurable module) do not (nor do those not on
                // a wire of the device)
                continue;
            }
            Node node = pp.getNode();
            if (node != null) {
                partitionPinKeys[numPartitionPins++] = nodeKey(node);
            }
            Net ppNet = design.getNetFromPartitionPin(pp);
            if (ppNet != null) {
                partitionPinNets.add(ppNet);
            }
        }
        partitionPinKeys = Arrays.copyOf(partitionPinKeys, numPartitionPins);
        Arrays.sort(partitionPinKeys);
        computeSitePinKeys();
        for (int t = 0; t < 2; t++) {
            if (staticEntry[t] >= 0) {
                addStaticLoadSitePins(staticEntry[t], staticFlats.get(t));
            }
        }
        final int dummy = dummyEntry;
        parallelFor(numEntries, (i) -> {
            Net net = entryNet.get(i);
            RouteStatusNetlist.FlatNet flat = entryFlat.get(i);
            if (i == dummy) {
                // Routing that reserves resources (for partial reconfiguration) is neither checked
                // nor conflicting
                boolean locked = net.getPIPs().stream().anyMatch(PIP::isPIPFixed);
                status[i] = (net.hasPIPs() ? HAS_ROUTING : 0) | (locked ? LOCKED_NODES : 0) | INTRASITE;
                return;
            }
            status[i] = (net == null && flat == null) ? INTRASITE : classifyNet(net, flat, i);
        });
        if (unroutedVccTies && staticEntry[1] >= 0 && has(status[staticEntry[1]], HAS_ROUTING)) {
            status[staticEntry[1]] = (status[staticEntry[1]] & ~ROUTED) | PARTIAL;
        }
        for (int t = 0; t < 2; t++) {
            int i = staticEntry[t];
            if (i >= 0 && has(status[i], INTRASITE)) {
                // Not routed, but needs routing if its loads are not all in one site or any is
                // fed from a site pin
                Set<SiteInst> sites = new HashSet<>();
                boolean needsRouting = false;
                for (RouteStatusNetlist.FlatNet flat : staticFlats.get(t)) {
                    flat.addPhysicalPinSites(design, sites);
                    if (sites.size() > 1 || (flat.firstSite != null && flat.hasLoadOnSitePin(design))) {
                        needsRouting = true;
                        break;
                    }
                }
                if (needsRouting) {
                    status[i] = (status[i] & ~INTRASITE) | UNROUTED;
                }
            }
            if (i >= 0 && t == 0 && hasLogicalStatic[t] && has(status[i], HAS_ROUTING)
                    && staticFlats.get(t).stream().noneMatch((f) -> f.hasLoadWithPhysicalPin(design))) {
                // Like other nets, routing of GND without logical loads (with physical pins) leads
                // nowhere (for VCC, enable pins without physical pins are loads, tied to VCC)
                status[i] = HAS_ROUTING | ANTENNAS | (status[i] & LOCKED_NODES);
            }
            if (i >= 0 && staticUnplacedLoads[t] > 0) {
                // Like other nets, static nets with unplaced loads are reported as such
                int st = status[i] & ~(ROUTED | INTRASITE | UNROUTED);
                if (staticUnplacedLoads[t] == staticLoads[t]) {
                    status[i] = has(st, HAS_ROUTING) ? st | UNPLACED : UNPLACED_ALL;
                } else {
                    // Its placed loads are partially routed if they needed routing
                    status[i] = st | UNPLACED | (has(status[i], UNROUTED) ? PARTIAL : 0);
                }
            }
        }

        addSitePinNodes();
        markConflicts();

        // The primary status of a net with some unplaced pins is UNPLACED unless it has conflicts or
        // antennas. Vivado lists such nets as having no placed pins too, but only if any other net
        // truly has no placed pins.
        boolean anyNoPlacedPins = false;
        for (int st : status) {
            if ((has(st, UNPLACED_ALL) && !has(st, UNPLACED)) || has(st, NO_PLACED_PINS_ROUTED)) {
                anyNoPlacedPins = true;
                break;
            }
        }
        if (anyNoPlacedPins) {
            for (int i = 0; i < numEntries; i++) {
                int st = status[i];
                if (has(st, UNPLACED) && !has(st, CONFLICTS | ANTENNAS)) {
                    status[i] |= UNPLACED_ALL;
                }
            }
        }

        boolean anyNoLoads = false;
        for (int s : status) {
            if (has(s, NOLOADS) && !has(s, HIERPORT)) {
                anyNoLoads = true;
                break;
            }
        }
        // Vivado lists implicitly routed ports that have no loads as also having no loads, but only
        // if the design has any other net with no loads
        if (anyNoLoads) {
            for (int i = 0; i < numFlat; i++) {
                if (has(status[i], HIERPORT) && getLoads(entryFlat.get(i)) == 0) {
                    status[i] |= NOLOADS;
                }
            }
        }

        // Vivado lists the uncounted static nets only under statuses that some counted net has
        if (!uncounted.isEmpty()) {
            int countedStatuses = unconnectedNets > 0 ? INTRASITE : 0;
            for (int i = 0; i < numEntries; i++) {
                if (counted[i]) {
                    countedStatuses |= status[i];
                }
            }
            for (int i : uncounted) {
                status[i] &= countedStatuses;
            }
        }

        ReportRouteStatusResult rrs = summarize();
        if (perNet) {
            rrs.netsByStatus = new EnumMap<>(NetRouteStatus.class);
            for (NetRouteStatus s : NetRouteStatus.values()) {
                rrs.netsByStatus.put(s, new TreeSet<>());
            }
            for (int i = 0; i < numEntries; i++) {
                RouteStatusNetlist.FlatNet flat = entryFlat.get(i);
                String name = (flat != null) ? flat.getName() : entryName.get(i);
                // Vivado escapes backslashes in the net names it lists
                name = name.replace("\\", "\\\\");
                for (NetRouteStatus s : NetRouteStatus.values()) {
                    if (has(status[i], bit(s))) {
                        rrs.netsByStatus.get(s).add(name);
                    }
                }
            }
        }
        return rrs;
    }

    /**
     * Adds the input site pins of the placed LUT loads of a routed static net whose site wires carry
     * another net (i.e. LUT inputs tied to it in the netlist only) as sinks of the static net, which
     * need routing. (Vivado does not count such an input without any site routing as a load.)
     */
    private void addStaticLoadSitePins(int entry, List<RouteStatusNetlist.FlatNet> flats) {
        Net net = entryNet.get(entry);
        if (net == null || !net.hasPIPs()) {
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
        long[] old = sitePinKeys[entry];
        int n = (old == null) ? 0 : old.length;
        long[] merged = Arrays.copyOf(old == null ? new long[0] : old, n + keys.size());
        for (int i = 0; i < keys.size(); i++) {
            merged[n + i] = keys.get(i);
        }
        sitePinKeys[entry] = sortedDistinct(merged, merged.length);
    }

    /** Checks whether a cell of the given type may be placed on a LUT BEL (excluding common types that are not). */
    private static boolean mayBePlacedOnLUT(String type) {
        return !(type.startsWith("FD") || type.startsWith("CARRY") || type.startsWith("MUXF") || type.startsWith("DSP")
                || type.startsWith("RAMB") || type.startsWith("FIFO"));
    }

    /** Sorted keys of the nodes of the partition pins, and the nets they are on */
    private long[] partitionPinKeys;
    private final Set<Net> partitionPinNets = new HashSet<>();

    /** Per entry, physical nets named after other segments of its flat net, with site routing */
    private final Map<Integer, List<Net>> aliasNets = new HashMap<>();

    private interface IndexTask {
        void run(int i);
    }

    /** Runs the task for every index in [0, n), in parallel chunks. */
    private static void parallelFor(int n, IndexTask task) {
        int chunks = Math.min(n, ParallelismTools.maxParallelism() * 4);
        if (chunks <= 1 || !ParallelismTools.getParallel()) {
            for (int i = 0; i < n; i++) {
                task.run(i);
            }
            return;
        }
        Runnable[] runnables = new Runnable[chunks];
        for (int c = 0; c < chunks; c++) {
            final int from = (int) ((long) n * c / chunks);
            final int to = (int) ((long) n * (c + 1) / chunks);
            runnables[c] = () -> {
                for (int i = from; i < to; i++) {
                    task.run(i);
                }
            };
        }
        ParallelismTools.invokeAll(runnables);
    }

    private static int getLoads(RouteStatusNetlist.FlatNet flat) {
        return flat.leafLoads + flat.leafInouts + flat.placedPortLoads;
    }

    private int classifyNet(Net net, RouteStatusNetlist.FlatNet flat, int entry) {
        int s = 0;
        boolean hasRouting = net != null && net.hasPIPs();
        RouteGraph graph = null;
        boolean isStatic = net != null && net.isStaticNet();
        if (hasRouting) {
            s |= HAS_ROUTING;
            graph = new RouteGraph(net);
            nodeKeys[entry] = graph.nodes;
        }

        if (flat != null) {
            int pinCount = flat.leafDrivers + flat.leafLoads + flat.leafInouts;
            if (pinCount > 0 && flat.unplacedLeafPins == pinCount && flat.placedPortDrivers + flat.placedPortLoads == 0) {
                // A net with no placed pins but with routing has only antennas (and is counted, but
                // not listed, as having no placed pins)
                return hasRouting ? s | NO_PLACED_PINS_ROUTED | ANTENNAS | (graph.locked ? LOCKED_NODES : 0) : s | UNPLACED_ALL;
            }
            if (flat.unplacedLeafPins > 0) {
                s |= UNPLACED;
            }
            if (pinCount == 0) {
                // Only connected to top level ports (and black boxes): an implicitly routed port if
                // driven by a black box
                boolean blackBoxToPort = flat.blackBoxDrivers > 0 && flat.placedPortLoads + flat.unplacedPortLoads > 0;
                return s | (blackBoxToPort ? HIERPORT : INTRASITE);
            }
            // (the output pin of a black box drives, from somewhere)
            int drivers = flat.leafDrivers + flat.placedPortDrivers + flat.blackBoxDrivers;
            int loads = getLoads(flat);
            boolean hasUnplacedPorts = flat.unplacedPortDrivers + flat.unplacedPortLoads > 0;
            if (hasUnplacedPorts && flat.firstSite != null && isIOSite(flat.firstSite)) {
                // A port connected to an I/O buffer is placed on its pad (even if that is not stored)
                drivers += flat.unplacedPortDrivers;
                loads += flat.unplacedPortLoads;
                hasUnplacedPorts = false;
            }
            boolean noPhysicalRouting = net == null || (net.getPins().isEmpty() && !hasRouting && sitePinKeys[entry] == null);
            if (hasUnplacedPorts && drivers == 0 && noPhysicalRouting && !aliasNets.containsKey(entry)
                    && !flat.hasPinsInMultipleSites(design) && !flat.hasLoadOnLutInput(design)) {
                // An unplaced input port without physical routing into a single site (but not into
                // a LUT, whose inputs are only reached through site pins)
                return s | INTRASITE;
            }
            if (hasUnplacedPorts && (drivers == 0 || loads == 0)) {
                s |= HIERPORT;
                if (hasRouting) {
                    // Routing from/to the port leaves an explicit gap, unless it is to the
                    // partition pin of the port (of a reconfigurable module), where it is not
                    // connected to the port
                    s |= flat.partitionPinPorts > 0 ? ANTENNAS | (graph.locked ? LOCKED_NODES : 0) : GAPS;
                }
                return s;
            }
            if (drivers == 0 && flat.leafInouts == 0 && loads > 0) {
                // (an inout pin may drive)
                if (hasRouting) {
                    // Routing without a driver is all islands
                    return s | NODRIVER | ANTENNAS | (graph.locked ? LOCKED_NODES : 0);
                }
                // Not routed, but needs routing if its loads are not all in one site
                boolean needsRouting = (net != null && !net.getPins().isEmpty()) || flat.hasPinsInMultipleSites(design);
                return s | NODRIVER | (needsRouting ? PARTIAL : 0);
            }
            if (net == null && flat.unplacedLeafPins == 0 && !aliasNets.containsKey(entry) && flat.leafDrivers > 0) {
                // Without a physical net, only loads on control pins (whose site pins follow from
                // the placement) and loads in the site of the driver are connected
                int controlPins = flat.getControlPinLoads(design);
                if ((controlPins & RouteStatusNetlist.FlatNet.ENABLE_PIN) != 0) {
                    // Their enable site pins are also tied to VCC, without routing
                    unroutedVccTies = true;
                }
                if (controlPins != 0) {
                    return s | UNROUTED;
                }
                if (flat.hasPinsInMultipleSites(design) && flat.hasLoadOutsideDriverSite(design)) {
                    return s | NOLOADS;
                }
            }
            // (routing to a partition pin leads to loads in another partition, e.g. a reconfigurable
            // module)
            boolean toPartition = loads == 0 && hasRouting && partitionPinNets.contains(net);
            if ((loads == 0 && !toPartition) || (pinCount == 1 && flat.leafInouts == 1 && !flat.hasTopPorts())) {
                // Routing without loads leads nowhere (a lone inout pin has no load either)
                if (hasRouting) {
                    return s | ANTENNAS | (graph.locked ? LOCKED_NODES : 0);
                }
                // A hard block's pin without a physical pin is connected inside its site (or to
                // another block without routing), and so are loads in black boxes
                boolean internalDriver = flat.leafDrivers == 1 && pinCount == 1 && flat.firstCell != null
                        && isHardBlockCell(flat.firstCell) && !RouteStatusNetlist.hasPhysicalPin(flat.firstCell, flat.firstPin);
                return s | (internalDriver || (loads == 0 && flat.blackBoxLoads > 0) ? INTRASITE : NOLOADS);
            }
        }

        if (flat != null && flat.blackBoxDrivers > 0 && flat.leafDrivers == 0 && !hasRouting && !has(s, UNPLACED)) {
            // Driven by a black box: needs routing to its loads
            return s | UNROUTED;
        }

        // A net with some unplaced pins is reported as such instead of being routed, unrouted or
        // internal to a site; whether it is partially routed still is
        boolean someUnplaced = has(s, UNPLACED);
        boolean usesInputSitePin = sitePinKeys[entry] != null;
        if (someUnplaced && !hasRouting) {
            // Partially routed if a placed driver has loads (whether placed or not, as unplaced
            // loads are unrouted) outside its site, or if the placed pins are in different sites
            boolean placedDriver = flat.leafDrivers > flat.unplacedLeafDrivers || flat.placedPortDrivers > 0;
            boolean leavesSite = (net != null && (!net.getPins().isEmpty() || usesOutputSitePin(net))) || usesInputSitePin;
            return (placedDriver && getLoads(flat) > 0 && leavesSite) || flat.hasPinsInMultipleSites(design) ? s | PARTIAL : s;
        }
        if ((net == null || net.getPins().isEmpty()) && !hasRouting && !usesInputSitePin
                && (flat == null || !flat.hasPinsInMultipleSites(design))) {
            if (flat != null && flat.multipleSites && flat.secondCell != null && flat.secondCell.getType().startsWith("RAMB")
                    && flat.leafLoads == 1 && flat.leafDrivers == 1) {
                // An unused (unmapped) block RAM pin in another site is not a load
                return s | NOLOADS;
            }
            return s | INTRASITE;
        }
        if (!hasRouting) {
            return s | UNROUTED;
        }

        long[] staleKeys = null;
        if (someUnplaced && flat != null) {
            boolean placedDriver = flat.leafDrivers > flat.unplacedLeafDrivers || flat.placedPortDrivers > 0;
            if (!placedDriver && !partitionPinNets.contains(net)) {
                // The routing of a net whose driver is unplaced is all islands (unless it comes
                // from another partition)
                return s | ANTENNAS | (graph.locked ? LOCKED_NODES : 0) | (flat.hasPinsInMultipleSites(design) ? PARTIAL : 0);
            }
            // Site pins left in sites without a placed cell of the net are not loads
            staleKeys = getStaleSitePinKeys(net, null);
        }
        boolean placedSource = flat != null && (flat.leafDrivers > flat.unplacedLeafDrivers || flat.placedPortDrivers > 0);
        List<Net> aliases = aliasNets.get(entry);
        graph.analyze(net, aliases, isStatic, sitePinKeys[entry], staleKeys, partitionPinKeys, placedSource);
        if (graph.reachedSinks < graph.sinks && sitePinKeys[entry] != null && staleKeys == null) {
            // Site routing is sometimes left behind (e.g. static ties for LUT pins, or where
            // routing bounces through a site pin) where no placed cell uses it: such site pins
            // are not loads
            graph.analyze(net, aliases, isStatic, sitePinKeys[entry],
                    getStaleSitePinKeys(net, isStatic ? net.getType() : null), partitionPinKeys, placedSource);
        }
        if (graph.locked) {
            s |= LOCKED_NODES;
        }
        if (graph.explicitGaps) {
            // Neither fully nor partially routed
            return s | GAPS | (graph.antennas ? ANTENNAS : 0);
        }
        if (graph.antennas) {
            s |= ANTENNAS;
        }
        // Other placed drivers (of a net with several) whose output site pins the routing does not
        // start from are unrouted
        int unroutedDrivers = (!isStatic && flat != null && flat.leafDrivers - flat.unplacedLeafDrivers > 1)
                ? findUnroutedDriverPins(net, flat, graph, entry) : 0;
        if ((unroutedDrivers & DRIVER_PIN_CONFLICT) != 0) {
            s |= CONFLICTS;
        }
        if (graph.reachedSinks < graph.sinks || graph.unroutedSource || unroutedDrivers != 0) {
            s |= PARTIAL;
        } else if (!graph.antennas && !someUnplaced) {
            s |= ROUTED;
        }
        return s;
    }

    static long nodeKey(Node node) {
        return ((long) node.getTile().getUniqueAddress() << 32) | (node.getWireIndex() & 0xffffffffL);
    }

    /**
     * The routing tree of a net as a graph of node keys, examined to find which sink pins are
     * reachable from its source pins (or for static nets, from tied nodes) through the net's PIPs,
     * and whether it has antennas or islands.
     */
    private static class RouteGraph {
        /** Sorted distinct keys of the nodes used by the PIPs */
        final long[] nodes;
        final Node[] nodeObjects;
        /** Fanouts of each node, in compressed sparse row form */
        final int[] fanoutStart;
        final int[] fanouts;
        final boolean[] driven;
        final boolean[] stubEnd;
        /** Index of each node key in {@link #nodes} */
        LongIndex index;
        /** Per node: whether a PIP leads from it in the direction it is used (not just backwards through a bidirectional PIP) */
        final boolean[] hasForwardFanout;
        boolean locked;
        /** Results of {@link #analyze}: the sinks, and how many of them the routing reaches */
        int sinks;
        int reachedSinks;
        /** Routing that does not lead to a load, or is disconnected from the rest */
        boolean antennas;
        /** Whether some routing starts at a pin feed, leaving an explicit gap before it */
        boolean explicitGaps;
        /** The start nodes of the PIPs marking explicit gaps (PIPs to nowhere), or null */
        List<Node> gapStarts;
        boolean unroutedSource;

        RouteGraph(Net net) {
            List<PIP> pips = net.getPIPs();
            int n = pips.size();
            long[] startKeys = new long[n];
            long[] endKeys = new long[n];
            Node[] startNodes = new Node[n];
            Node[] endNodes = new Node[n];
            boolean[] isStub = new boolean[n];
            int edges = 0;
            boolean[] isBidir = new boolean[n];
            boolean[] isReversed = new boolean[n];
            int numBidir = 0;
            // PIPs are mostly stored in routing order, so a PIP often starts at the tile wire where
            // the previous one ends: reuse that node rather than looking it up again
            Tile prevTile = null;
            int prevEndWire = -1;
            Node prevEnd = null;
            for (int i = 0; i < n; i++) {
                PIP pip = pips.get(i);
                if (pip.isPIPFixed()) {
                    locked = true;
                }
                Node start;
                Node end;
                if (pip.isReversed()) {
                    start = pip.getEndNode();
                    end = pip.getStartNode();
                    prevTile = null;
                } else {
                    Tile tile = pip.getTile();
                    start = (tile == prevTile && pip.getStartWireIndex() == prevEndWire) ? prevEnd : pip.getStartNode();
                    end = pip.getEndNode();
                    prevTile = tile;
                    prevEndWire = pip.getEndWireIndex();
                    prevEnd = end;
                }
                if (start == null || end == null) {
                    if (end == null && start != null) {
                        // A PIP to nowhere marks an explicit gap after its start node
                        if (gapStarts == null) {
                            gapStarts = new ArrayList<>();
                        }
                        gapStarts.add(start);
                    }
                    continue;
                }
                startNodes[edges] = start;
                endNodes[edges] = end;
                startKeys[edges] = nodeKey(start);
                endKeys[edges] = nodeKey(end);
                isStub[edges] = pip.isStub();
                isBidir[edges] = pip.isBidirectional();
                isReversed[edges] = pip.isReversed();
                if (isBidir[edges]) {
                    numBidir++;
                }
                edges++;
            }
            index = new LongIndex(2 * edges);
            int[] startIdx = new int[edges];
            int[] endIdx = new int[edges];
            // Routing is mostly stored in order, each PIP starting where the previous one ends
            long prevKey = Long.MIN_VALUE;
            int prevIdx = -1;
            for (int e = 0; e < edges; e++) {
                startIdx[e] = (startKeys[e] == prevKey) ? prevIdx : index.add(startKeys[e]);
                endIdx[e] = index.add(endKeys[e]);
                prevKey = endKeys[e];
                prevIdx = endIdx[e];
            }
            nodes = index.keys();
            int distinct = nodes.length;
            nodeObjects = new Node[distinct];
            driven = new boolean[distinct];
            stubEnd = new boolean[distinct];
            hasForwardFanout = new boolean[distinct];
            fanoutStart = new int[distinct + 1];
            for (int e = 0; e < edges; e++) {
                nodeObjects[startIdx[e]] = startNodes[e];
                nodeObjects[endIdx[e]] = endNodes[e];
                fanoutStart[startIdx[e] + 1]++;
                hasForwardFanout[startIdx[e]] = true;
                driven[endIdx[e]] = true;
                if (isBidir[e]) {
                    // Bidirectional PIPs connect their nodes either way (and in clock routing,
                    // either way may be the direction they are used in, unless explicitly stored
                    // as reversed)
                    fanoutStart[endIdx[e] + 1]++;
                    driven[startIdx[e]] = true;
                    if (!isReversed[e] && endNodes[e].getIntentCode().toString().contains("GLOBAL")) {
                        hasForwardFanout[endIdx[e]] = true;
                    }
                }
                if (isStub[e]) {
                    // Stubs are deliberately dangling and not considered antennas
                    stubEnd[endIdx[e]] = true;
                }
            }
            for (int i = 0; i < distinct; i++) {
                fanoutStart[i + 1] += fanoutStart[i];
            }
            fanouts = new int[edges + numBidir];
            int[] fill = Arrays.copyOf(fanoutStart, distinct);
            for (int e = 0; e < edges; e++) {
                fanouts[fill[startIdx[e]]++] = endIdx[e];
                if (isBidir[e]) {
                    fanouts[fill[endIdx[e]]++] = startIdx[e];
                }
            }
            // Everything downstream of a stub PIP is part of the stub
            int[] stack = null;
            int top = 0;
            for (int i = 0; i < distinct; i++) {
                if (!stubEnd[i]) {
                    continue;
                }
                if (stack == null) {
                    stack = new int[distinct];
                }
                stack[top++] = i;
                while (top > 0) {
                    int node = stack[--top];
                    for (int f = fanoutStart[node]; f < fanoutStart[node + 1]; f++) {
                        int next = fanouts[f];
                        if (!stubEnd[next]) {
                            stubEnd[next] = true;
                            stack[top++] = next;
                        }
                    }
                }
            }
        }

        private int indexOf(Node node) {
            if (node == null) {
                return -1;
            }
            int i = index.get(nodeKey(node));
            return (i >= 0) ? i : -1;
        }

        /**
         * @param aliases The other physical nets of the flat net (named after other segments), whose
         * site pins and site routing count too, or null.
         * @param partitionPinKeys Sorted keys of the nodes of the design's partition pins, where
         * routing continues in (or comes from) another partition.
         * @param placedSource Whether the net has a placed source (which routing from a partition
         * pin does not connect).
         */
        void analyze(Net net, List<Net> aliases, boolean isStatic, long[] sitePinSinkKeys, long[] staleKeys,
                     long[] partitionPinKeys, boolean placedSource) {
            // (the results of any previous analysis are replaced)
            sinks = 0;
            reachedSinks = 0;
            antennas = false;
            explicitGaps = false;
            unroutedSource = false;
            int distinct = nodes.length;
            boolean[] isSink = new boolean[distinct];
            boolean[] visited = new boolean[distinct];
            int[] queue = new int[distinct];
            int head = 0;
            int tail = 0;
            // Sinks are the input site pins of the net, whether or not they have a SitePinInst
            // (those of unrouted sinks are not stored in a DCP), as found in its site routing
            List<SitePinInst> pins = net.getPins();
            if (aliases != null) {
                pins = new ArrayList<>(pins);
                for (Net alias : aliases) {
                    pins.addAll(alias.getPins());
                }
            }
            int extra = (sitePinSinkKeys == null) ? 0 : sitePinSinkKeys.length;
            long[] sinkKeys = new long[pins.size() + extra];
            int numSinks = 0;
            for (SitePinInst spi : pins) {
                Node node = spi.getConnectedNode();
                // (the net, or an alias)
                Net owner = spi.getNet();
                if (spi.isOutPin()) {
                    if (!isStatic && !isDrivenBySiteRouting(owner, spi)) {
                        // An output pin the site routing of the net does not lead to (e.g. one
                        // inferred from routing that starts there) drives nothing
                        continue;
                    }
                    int i = indexOf(node);
                    if (i >= 0 && !visited[i]) {
                        visited[i] = true;
                        queue[tail++] = i;
                    } else if (i < 0 && !isStatic && spi == owner.getSource()) {
                        // The source pin is not routed
                        unroutedSource = true;
                    }
                } else if (node != null) {
                    sinkKeys[numSinks++] = nodeKey(node);
                }
            }
            if (extra > 0) {
                System.arraycopy(sitePinSinkKeys, 0, sinkKeys, numSinks, extra);
                numSinks += extra;
            }
            if (staleKeys != null) {
                int kept = 0;
                for (int i = 0; i < numSinks; i++) {
                    if (Arrays.binarySearch(staleKeys, sinkKeys[i]) < 0) {
                        sinkKeys[kept++] = sinkKeys[i];
                    }
                }
                numSinks = kept;
            }
            sinkKeys = Arrays.copyOf(sinkKeys, numSinks);
            Arrays.sort(sinkKeys);
            for (int i = 0; i < sinkKeys.length; i++) {
                if (i > 0 && sinkKeys[i] == sinkKeys[i - 1]) {
                    continue;
                }
                sinks++;
                int idx = index.get(sinkKeys[i]);
                if (idx >= 0) {
                    isSink[idx] = true;
                }
            }
            for (int i = 0; i < distinct; i++) {
                if (driven[i] || visited[i] || fanoutStart[i] == fanoutStart[i + 1]) {
                    continue;
                }
                Node node = nodeObjects[i];
                if (isStatic ? node.isTied() : isSiteRoutedOutputPinNode(net, aliases, node)) {
                    // Tied nodes of static nets, and site output pins (driven by the site routing
                    // of the net) without a SitePinInst
                    visited[i] = true;
                    queue[tail++] = i;
                }
            }
            boolean rootedAtSource = tail > 0;
            boolean[] isPartitionPin = null;
            if (partitionPinKeys.length > 0) {
                for (int i = 0; i < distinct; i++) {
                    if (Arrays.binarySearch(partitionPinKeys, nodes[i]) < 0) {
                        continue;
                    }
                    if (isPartitionPin == null) {
                        isPartitionPin = new boolean[distinct];
                    }
                    isPartitionPin[i] = true;
                    if (!driven[i] && !visited[i] && hasForwardFanout[i]) {
                        // Routing from another partition
                        visited[i] = true;
                        queue[tail++] = i;
                    }
                }
            }
            boolean rooted = tail > 0;
            if (rooted && !rootedAtSource && placedSource && !isStatic) {
                // Routing from another partition, but none from the placed source
                unroutedSource = true;
            }
            // Explicit gaps connect the routing to the pins and routing they lead to (any routing
            // may be joined through them), but a gap after a node used by nothing else is an island
            boolean gapMarkers = gapStarts != null;
            if (gapMarkers) {
                explicitGaps = true;
                for (Node node : gapStarts) {
                    if (index.get(nodeKey(node)) < 0 && !isSitePinNode(node)
                            && node.getIntentCode() != IntentCode.NODE_PINFEED && !node.getAllDownhillNodes().isEmpty()) {
                        antennas = true;
                    }
                }
            }
            for (int i = 0; i < distinct; i++) {
                if (driven[i] || visited[i] || !hasForwardFanout[i]) {
                    continue;
                }
                if (gapMarkers && nodeObjects[i].getIntentCode() == IntentCode.NODE_PINFEED) {
                    // Routing from a pin feed, as is stored (with explicit gaps) for clocks routed
                    // only at their ends
                    visited[i] = true;
                    queue[tail++] = i;
                }
            }
            boolean changed = true;
            while (changed) {
                while (head < tail) {
                    int i = queue[head++];
                    int from = fanoutStart[i];
                    int to = fanoutStart[i + 1];
                    if (!hasForwardFanout[i] && (from == to || !(isSitePinUsedBy(net, nodeObjects[i])
                            || nodeObjects[i].getIntentCode().toString().contains("GLOBAL")))) {
                        // (a site pin used by the site routing of the net, or global clock
                        // routing, reached backwards through a bidirectional PIP may lead on)
                        // Leads nowhere, unless to a load. The site pins that loads of static nets
                        // are tied to are not always in their site routing. Stubs are deliberately
                        // dangling, but only on nets with loads.
                        // Routing that ends where an explicit gap follows (to some pin) leads on
                        // and so does routing to another partition
                        if (!isSink[i] && !(stubEnd[i] && sinks > 0) && !(isStatic && isSitePinNode(nodeObjects[i]))
                                && !gapMarkers && !(isPartitionPin != null && isPartitionPin[i])) {
                            antennas = true;
                        }
                    }
                    for (int f = from; f < to; f++) {
                        int next = fanouts[f];
                        if (!visited[next]) {
                            visited[next] = true;
                            queue[tail++] = next;
                        }
                    }
                }
                // Unreached global (clock) routing may still be joined to the rest by a
                // bidirectional PIP that is not stored with the net (e.g. between the halves of a
                // clock distribution row). Other bidirectional PIPs only connect if stored.
                changed = false;
                for (int i = 0; i < distinct; i++) {
                    if (visited[i] || !nodeObjects[i].getIntentCode().toString().contains("GLOBAL")) {
                        continue;
                    }
                    for (PIP pip : nodeObjects[i].getAllUphillPIPs()) {
                        if (!pip.isBidirectional()) {
                            continue;
                        }
                        Node start = pip.getStartNode();
                        Node other = (start != null && nodeKey(start) == nodes[i]) ? pip.getEndNode() : start;
                        int o = indexOf(other);
                        if (o >= 0 && visited[o]) {
                            visited[i] = true;
                            queue[tail++] = i;
                            changed = true;
                            break;
                        }
                    }
                }
            }
            for (int i = 0; i < distinct; i++) {
                if (!visited[i] && fanoutStart[i] != fanoutStart[i + 1] && !gapMarkers) {
                    // Island: routing not reachable from the source (unless through explicit gaps)
                    antennas = true;
                }
                if (isSink[i] && (visited[i] || rooted)) {
                    // Reached by routing (even if that routing is an island, or merely starts
                    // there, unless no routing leaves the source at all)
                    reachedSinks++;
                }
            }
        }
    }

    /**
     * Gets the sorted keys of the nodes of the input site pins of a net whose site routing does not
     * lead to a placed cell pin (e.g. left behind by unplacing a cell), which are not loads.
     */
    private static long[] getStaleSitePinKeys(Net net, NetType staticType) {
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
                    if (spi != null && spi.getNet() == net) {
                        // A stored sink pin of a static net is a load regardless
                        continue;
                    }
                    int pinIndex = si.getSite().getPinIndex(si.getPrimarySitePinName(pin.getBELName()));
                    Node node = (pinIndex < 0) ? null : si.getSite().getConnectedNode(pinIndex);
                    if (node != null) {
                        keys.add(nodeKey(node));
                    }
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

    /**
     * Checks whether the site routing from a site wire (through used site PIPs of routing BELs)
     * reaches an input pin of a placed cell that is mapped to a logical pin.
     * @param staticType For a static net, the logical pin must be tied to the static source
     * (others may be tied off inside the site).
     */
    private static boolean reachesPlacedCellPin(SiteInst si, String siteWire, NetType staticType) {
        Set<String> visited = new HashSet<>();
        List<String> queue = new ArrayList<>();
        queue.add(siteWire);
        while (!queue.isEmpty()) {
            String sw = queue.remove(queue.size() - 1);
            if (!visited.add(sw)) {
                continue;
            }
            for (BELPin pin : si.getSiteWirePins(sw)) {
                if (pin.isSitePort() || !pin.isInput()) {
                    continue;
                }
                Cell cell = si.getCell(pin.getBEL());
                String logicalPin = (cell == null || cell.isRoutethru()) ? null : cell.getLogicalPinMapping(pin.getName());
                if (logicalPin != null && (staticType == null || isTiedTo(cell, logicalPin, staticType))) {
                    return true;
                }
                if (staticType == NetType.VCC && logicalPin == null && isFracturedLutTie(si, pin)) {
                    // A6 of a LUT must be tied high when its 5LUT is used
                    return true;
                }
                if (staticType == NetType.GND && (pin.getBELName().contains("INV") || !Utils.isSLICE(si))) {
                    // Ground is tied off at optional inverters (and in other sites than slices)
                    // without routing
                    continue;
                }
                SitePIP sitePIP = si.getUsedSitePIP(pin);
                if (sitePIP != null) {
                    queue.add(sitePIP.getOutputPin().getSiteWireName());
                } else if (cell != null && cell.isRoutethru()) {
                    // Through a routethru cell to its output
                    for (BELPin out : pin.getBEL().getPins()) {
                        if (out.isOutput()) {
                            queue.add(out.getSiteWireName());
                        }
                    }
                }
            }
        }
        return false;
    }

    /** Checks whether a BEL pin is the A6 pin of a 6LUT whose 5LUT has a placed cell. */
    private static boolean isFracturedLutTie(SiteInst si, BELPin pin) {
        String bel = pin.getBELName();
        if (!pin.getName().equals("A6") || bel.length() != 5 || !bel.endsWith("6LUT")) {
            return false;
        }
        Cell lut5 = si.getCell(bel.charAt(0) + "5LUT");
        // (not a placeholder locking the 5LUT for a memory in the 6LUT)
        return lut5 != null && !lut5.isRoutethru() && !Cell.LOCKED.equals(lut5.getName());
    }

    /**
     * Checks whether a site pin is the cascade address bit of a 36Kb block RAM, to which constants
     * are not routed.
     */
    private static boolean isCascadeAddressPin(String sitePin) {
        return sitePin.startsWith("ADDR") && sitePin.endsWith("L15");
    }

    /**
     * Checks whether a logical pin of a cell is tied to a static source of the given type, either
     * in the cell's parent or, through ports of hierarchical cells, further up. (Pins inside a
     * macro tied through the macro's ports are tied off inside their site: not followed.)
     */
    private static boolean isTiedTo(Cell cell, String logicalPin, NetType staticType) {
        EDIFHierCellInst inst;
        // The logical cell may be looked up by name, which is not thread safe
        synchronized (RouteStatusEngine.class) {
            inst = cell.getEDIFHierCellInst();
        }
        EDIFPortInst epi = (inst == null) ? null : inst.getInst().getPortInst(logicalPin);
        EDIFNet net = (epi == null) ? null : epi.getNet();
        EDIFHierCellInst parent = (inst == null) ? null : inst.getParent();
        while (net != null) {
            if (staticType == NetType.GND ? net.isGND() : net.isVCC()) {
                return true;
            }
            if (parent == null || parent.isTopLevelInst()
                    || parent.getCellType().getLibrary().isHDIPrimitivesLibrary()) {
                return false;
            }
            // Continue with the net that a port of the parent cell on this net connects to
            EDIFPortInst port = null;
            for (EDIFPortInst p : net.getPortInsts()) {
                if (p.isTopLevelPort()) {
                    port = p;
                    break;
                }
            }
            EDIFPortInst outer = (port == null) ? null : parent.getInst().getPortInst(port.getName());
            net = (outer == null) ? null : outer.getNet();
            parent = parent.getParent();
        }
        return false;
    }

    /** Checks whether a site is an I/O site (with a pad). */
    private static boolean isIOSite(SiteInst si) {
        String type = si.getSiteTypeEnum().name();
        return type.contains("IOB") || type.contains("HRIO") || type.contains("HPIO") || type.contains("HDIO")
                || type.contains("XPIO");
    }

    /**
     * Checks whether a cell is a NoC, memory controller or processor hard block (of Versal), or an
     * I/O bitslice, whose pins may be connected to other blocks without routing.
     */
    private static boolean isHardBlockCell(Cell cell) {
        String type = cell.getType();
        return type.startsWith("NOC") || type.startsWith("DDRMC") || type.equals("PS9") || type.contains("BITSLICE");
    }

    /** Checks whether the site routing of a net reaches an output site pin. */
    private static boolean usesOutputSitePin(Net net) {
        for (SiteInst si : net.getSiteInsts()) {
            for (String siteWire : si.getSiteWiresFromNet(net)) {
                for (BELPin pin : si.getSiteWirePins(siteWire)) {
                    if (pin.isSitePort() && pin.isInput()) {
                        return true;
                    }
                }
            }
        }
        return false;
    }


    /**
     * Checks whether the site routing of a net may lead to an output pin of it of a (7 series)
     * slice, i.e. unless it leads to another output pin of the slice (and not to this one).
     */
    private static boolean isDrivenBySiteRouting(Net net, SitePinInst spi) {
        SiteInst si = spi.getSiteInst();
        if (si == null || si.getDesign().getSeries() != Series.Series7 || !si.getSiteTypeEnum().name().startsWith("SLICE")
                || si.getNetFromSiteWire(spi.getSiteWireName()) == net) {
            return true;
        }
        for (String siteWire : si.getSiteWiresFromNet(net)) {
            for (BELPin pin : si.getSiteWirePins(siteWire)) {
                // The port BEL of an output site pin is driven by the site wire
                if (pin.isSitePort() && pin.isInput() && !pin.getBELName().equals(spi.getName())) {
                    return false;
                }
            }
        }
        return true;
    }

    /** Checks whether the node is that of a site pin used by the site routing of the net. */
    private static boolean isSitePinUsedBy(Net net, Node node) {
        SitePin sitePin = node.getSitePin();
        if (sitePin == null) {
            return false;
        }
        SiteInst si = net.getDesign().getSiteInstFromSite(sitePin.getSite());
        BELPin belPin = (si == null) ? null : sitePin.getBELPin(si.getSiteTypeEnum());
        return belPin != null && si.getNetFromSiteWireIndex(belPin.getSiteWireIndex()) == net;
    }

    /** Checks whether the node is that of an output site pin driven by the site routing of the net. */
    private static boolean isSiteRoutedOutputPinNode(Net net, Node node) {
        SitePin sitePin = node.getSitePin();
        return sitePin != null && !sitePin.isInput() && isSitePinUsedBy(net, node);
    }

    /**
     * Checks whether a node is that of an output site pin driven by the site routing of a net, or
     * of one of its aliases.
     */
    private static boolean isSiteRoutedOutputPinNode(Net net, List<Net> aliases, Node node) {
        if (isSiteRoutedOutputPinNode(net, node)) {
            return true;
        }
        if (aliases != null) {
            for (Net alias : aliases) {
                if (isSiteRoutedOutputPinNode(alias, node)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean isSitePinNode(Node node) {
        return node.getSitePin() != null;
    }

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
                primaryNames.add(si.getPrimarySitePinName(pinName));
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

    private InputSitePins getInputSitePins(SiteInst si) {
        List<SiteTypeEnum> key = Arrays.asList(si.getSiteTypeEnum(), si.getPrimarySiteTypeEnum());
        InputSitePins pins = inputSitePins.get(key);
        if (pins == null) {
            pins = new InputSitePins(si);
            inputSitePins.put(key, pins);
        }
        return pins;
    }

    /**
     * Per entry, the sorted distinct keys of the nodes of the input site pins through which its
     * site routing (or that of its aliases) enters a site, i.e. which need routing outside the site
     * even without a SitePinInst (null if none). I/O pads driven by top level ports are excluded.
     */
    private long[][] sitePinKeys;

    /**
     * Per entry, the sorted keys of the nodes of the output site pins of its placed drivers that
     * its routing does not start from (null if none), which the drivers use regardless.
     */
    private long[][] driverPinKeys;

    /** Flags returned by {@link #findUnroutedDriverPins} */
    private static final int DRIVER_PIN_UNROUTED = 1;
    private static final int DRIVER_PIN_CONFLICT = 2;

    /**
     * Finds the output site pins of the placed drivers of a net with several drivers that its
     * routing does not start from (see {@link #driverPinKeys}).
     * @return {@link #DRIVER_PIN_UNROUTED} if there are any, plus {@link #DRIVER_PIN_CONFLICT} if the
     * site routing of another net uses the output of any of these drivers that is a LUT.
     */
    private int findUnroutedDriverPins(Net net, RouteStatusNetlist.FlatNet flat, RouteGraph graph, int entry) {
        List<Long> keys = new ArrayList<>();
        int flags = 0;
        for (RouteStatusNetlist.LeafPin pin : flat.getLeafPins(design)) {
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
                    for (SitePinInst spi : siteNet.getPins()) {
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
        driverPinKeys[entry] = sortedDistinct(array, array.length);
        return flags | DRIVER_PIN_UNROUTED;
    }

    /** Computes {@link #sitePinKeys} in one pass over all site instances. */
    private void computeSitePinKeys() {
        sitePinKeys = new long[entryNet.size()][];
        driverPinKeys = new long[entryNet.size()][];
        Map<Net, Integer> entryOf = new IdentityHashMap<>(entryNet.size());
        for (int i = 0; i < entryNet.size(); i++) {
            if (entryNet.get(i) != null) {
                entryOf.put(entryNet.get(i), i);
            }
        }
        for (Map.Entry<Integer, List<Net>> e : aliasNets.entrySet()) {
            for (Net alias : e.getValue()) {
                entryOf.put(alias, e.getKey());
            }
        }
        List<SiteInst> siteInsts = new ArrayList<>(design.getSiteInsts());
        // The sites of top level ports (whose cells are named after the bits of the ports)
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
        int chunks = Math.max(1, Math.min(siteInsts.size() / 1024, ParallelismTools.maxParallelism() * 4));
        // Per chunk: the entries and node keys found, in parallel arrays
        int[][] chunkEntries = new int[chunks][];
        long[][] chunkKeys = new long[chunks][];
        int[] chunkSizes = new int[chunks];
        parallelFor(chunks, (c) -> {
            int from = (int) ((long) siteInsts.size() * c / chunks);
            int to = (int) ((long) siteInsts.size() * (c + 1) / chunks);
            int[] entries = new int[Math.max(64, Math.min((to - from) * 8, 1 << 16))];
            long[] keys = new long[entries.length];
            int size = 0;
            for (int s = from; s < to; s++) {
                SiteInst si = siteInsts.get(s);
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
                    if (node == null) {
                        continue;
                    }
                    if (size == keys.length) {
                        entries = Arrays.copyOf(entries, size * 2);
                        keys = Arrays.copyOf(keys, size * 2);
                    }
                    entries[size] = entry;
                    keys[size++] = nodeKey(node);
                }
            }
            chunkEntries[c] = entries;
            chunkKeys[c] = keys;
            chunkSizes[c] = size;
        });
        int[] counts = new int[entryNet.size()];
        for (int c = 0; c < chunks; c++) {
            for (int i = 0; i < chunkSizes[c]; i++) {
                counts[chunkEntries[c][i]]++;
            }
        }
        int[] fill = new int[entryNet.size()];
        for (int c = 0; c < chunks; c++) {
            for (int i = 0; i < chunkSizes[c]; i++) {
                int entry = chunkEntries[c][i];
                if (sitePinKeys[entry] == null) {
                    sitePinKeys[entry] = new long[counts[entry]];
                }
                sitePinKeys[entry][fill[entry]++] = chunkKeys[c][i];
            }
        }
        for (int i = 0; i < sitePinKeys.length; i++) {
            if (sitePinKeys[i] != null) {
                sitePinKeys[i] = sortedDistinct(sitePinKeys[i], sitePinKeys[i].length);
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

    private static long[] sortedDistinct(long[] keys, int n) {
        if (n == 1) {
            return (keys.length == 1) ? keys : Arrays.copyOf(keys, 1);
        }
        Arrays.sort(keys, 0, n);
        int distinct = 0;
        for (int i = 0; i < n; i++) {
            if (distinct == 0 || keys[distinct - 1] != keys[i]) {
                keys[distinct++] = keys[i];
            }
        }
        return Arrays.copyOf(keys, distinct);
    }

    /** Per entry with site pin nodes added to its node keys: how many of them are routing nodes (first) */
    private int[] numRoutingKeys;

    /** Concatenates two arrays, either of which may be null. */
    private static long[] concat(long[] a, long[] b) {
        if (a == null || b == null) {
            return (a == null) ? b : a;
        }
        long[] c = Arrays.copyOf(a, a.length + b.length);
        System.arraycopy(b, 0, c, a.length, b.length);
        return c;
    }

    /**
     * Adds the nodes of the input site pins used by the site routing of each (non static) net, and
     * of the output site pins of its unrouted drivers, to the nodes it uses, so that routing of
     * another net to such a site pin is found to conflict. The site routing of static nets is not
     * precise enough.
     */
    private void addSitePinNodes() {
        numRoutingKeys = new int[sitePinKeys.length];
        Arrays.fill(numRoutingKeys, -1);
        for (int i = 0; i < sitePinKeys.length; i++) {
            long[] extra = concat(sitePinKeys[i], driverPinKeys[i]);
            Net net = entryNet.get(i);
            if (extra == null || (net != null && net.isStaticNet())) {
                continue;
            }
            long[] keys = nodeKeys[i];
            int n = (keys == null) ? 0 : keys.length;
            numRoutingKeys[i] = n;
            long[] merged = Arrays.copyOf(keys == null ? new long[0] : keys, n + extra.length);
            System.arraycopy(extra, 0, merged, n, extra.length);
            // (keys may repeat, which markConflicts ignores within a net)
            nodeKeys[i] = merged;
        }
    }

    /**
     * Marks nets that use the same node as another net as conflicting, by sorting the keys of all
     * used nodes, each tagged with the index of its net.
     */
    private void markConflicts() {
        int numEntries = nodeKeys.length;
        long total = 0;
        for (long[] keys : nodeKeys) {
            if (keys != null) {
                total += keys.length;
            }
        }
        if (total == 0) {
            return;
        }
        int entryBits = 64 - Long.numberOfLeadingZeros(Math.max(1, numEntries - 1));
        long maxKey = 0;
        for (long[] keys : nodeKeys) {
            if (keys != null) {
                for (long k : keys) {
                    maxKey = Math.max(maxKey, k);
                }
            }
        }
        // Compact the node keys (tile address and wire index) to make room for the entry index
        int maxWireBits = 0;
        int maxTileBits = 0;
        for (long[] keys : nodeKeys) {
            if (keys == null) {
                continue;
            }
            for (long k : keys) {
                maxWireBits = Math.max(maxWireBits, 64 - Long.numberOfLeadingZeros(k & 0xffffffffL));
            }
        }
        maxTileBits = 64 - Long.numberOfLeadingZeros(maxKey >>> 32);
        // (one more bit flags the nodes of site pins)
        if (maxTileBits + maxWireBits + entryBits + 1 > 63 || total > Integer.MAX_VALUE - 8 || forceConflictsFallback) {
            markConflictsFallback();
            return;
        }
        final int wireBits = maxWireBits;
        long[] tagged = new long[(int) total];
        int[] offsets = new int[numEntries + 1];
        for (int i = 0; i < numEntries; i++) {
            offsets[i + 1] = offsets[i] + (nodeKeys[i] == null ? 0 : nodeKeys[i].length);
        }
        parallelFor(numEntries, (i) -> {
            long[] keys = nodeKeys[i];
            if (keys == null) {
                return;
            }
            int o = offsets[i];
            int routing = (numRoutingKeys == null || numRoutingKeys[i] < 0) ? keys.length : numRoutingKeys[i];
            for (int j = 0; j < keys.length; j++) {
                long k = keys[j];
                long compact = ((k >>> 32) << wireBits) | (k & 0xffffffffL);
                tagged[o++] = (((compact << entryBits) | i) << 1) | (j >= routing ? 1 : 0);
            }
        });
        Arrays.parallelSort(tagged);
        long entryMask = (1L << entryBits) - 1;
        int keyShift = entryBits + 1;
        int[] users = new int[16];
        for (int start = 0; start < tagged.length; ) {
            int end = start + 1;
            while (end < tagged.length && (tagged[end] >>> keyShift) == (tagged[start] >>> keyShift)) {
                end++;
            }
            if (end - start > 1) {
                if (users.length < end - start) {
                    users = new int[end - start];
                }
                for (int t = start; t < end; t++) {
                    users[t - start] = (int) ((tagged[t] >>> 1) & entryMask) << 1 | (int) (tagged[t] & 1);
                }
                markNodeUsers(status, users, end - start);
            }
            start = end;
        }
    }

    /** For testing: always use {@link #markConflictsFallback()} */
    static boolean forceConflictsFallback;

    /** Marks conflicts the same way, without packing keys and entries into longs (for huge designs). */
    private void markConflictsFallback() {
        Map<Long, List<Integer>> users = new HashMap<>();
        for (int i = 0; i < nodeKeys.length; i++) {
            long[] keys = nodeKeys[i];
            if (keys == null) {
                continue;
            }
            int routing = (numRoutingKeys == null || numRoutingKeys[i] < 0) ? keys.length : numRoutingKeys[i];
            for (int j = 0; j < keys.length; j++) {
                users.computeIfAbsent(keys[j], (k) -> new ArrayList<>(2)).add(i << 1 | (j >= routing ? 1 : 0));
            }
        }
        for (List<Integer> list : users.values()) {
            if (list.size() > 1) {
                markNodeUsers(status, list.stream().mapToInt(Integer::intValue).toArray(), list.size());
            }
        }
    }

    /**
     * Marks the consequences of a node being used by several nets. The result does not depend on
     * the order of the users.
     * @param status The per-entry statuses to update.
     * @param users The users of the node: entry index (shifted left by 1), and 1 if the node is that
     * of an input site pin used by the site routing of the entry (0 if by its routing).
     * @param count The number of users.
     */
    static void markNodeUsers(int[] status, int[] users, int count) {
        boolean shared = false;
        boolean hasSitePinOwner = false;
        for (int u = 0; u < count; u++) {
            shared |= (users[u] >> 1) != (users[0] >> 1);
            hasSitePinOwner |= (users[u] & 1) != 0;
        }
        if (!shared) {
            return;
        }
        for (int u = 0; u < count; u++) {
            int e = users[u] >> 1;
            status[e] = (status[e] | CONFLICTS) & ~ROUTED;
        }
        if (!hasSitePinOwner) {
            return;
        }
        for (int u = 0; u < count; u++) {
            if ((users[u] & 1) != 0) {
                continue;
            }
            int e = users[u] >> 1;
            // Routing through the site pin of another net leads nowhere
            if (!isSitePinOwner(users, count, e)) {
                status[e] |= ANTENNAS;
            }
            for (int o = 0; o < count; o++) {
                int owner = users[o] >> 1;
                if ((users[o] & 1) != 0 && owner != e && has(status[owner], UNROUTED)) {
                    // The site pin of an unrouted net is reached, if by the routing of another
                    // net: the net is only partially unrouted
                    status[owner] = (status[owner] & ~UNROUTED) | PARTIAL;
                }
            }
        }
    }

    private static boolean isSitePinOwner(int[] users, int count, int entry) {
        for (int u = 0; u < count; u++) {
            if (users[u] == (entry << 1 | 1)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Derives the summary counts from the per-net statuses, mirroring the hierarchy of the summary
     * table printed by Vivado's `report_route_status`.
     */
    private ReportRouteStatusResult summarize() {
        ReportRouteStatusResult rrs = new ReportRouteStatusResult();
        rrs.logicalNets = (int) unconnectedNets;
        rrs.netsNotNeedingRouting = (int) unconnectedNets;
        rrs.internallyRoutedNets = (int) unconnectedNets;
        final int errors = UNPLACED | PARTIAL | CONFLICTS | ANTENNAS | NODRIVER;
        for (int i = 0; i < status.length; i++) {
            if (!counted[i]) {
                continue;
            }
            int s = status[i];
            rrs.logicalNets++;
            // Nets are counted with each error (or property) they are listed with, whatever else
            // they are counted as
            if (has(s, LOCKED_NODES)) {
                rrs.netsWithFixedRouting++;
            }
            if (has(s, GAPS)) {
                rrs.netsWithExplicitGaps++;
            }
            if (has(s, UNPLACED)) {
                rrs.netsWithSomeUnplacedPins++;
            }
            if (has(s, PARTIAL)) {
                rrs.netsWithSomeUnroutedPins++;
            }
            if (has(s, CONFLICTS)) {
                rrs.netsWithResourceConflicts++;
            }
            if (has(s, ANTENNAS)) {
                rrs.netsWithAntennasOrIslands++;
            }
            if (has(s, NODRIVER)) {
                rrs.netsWithNoDriver++;
            }
            if (has(s, errors)) {
                rrs.netsWithRoutingErrors++;
            }
            // ... and in one category
            if ((has(s, UNPLACED_ALL) && !has(s, UNPLACED)) || has(s, NO_PLACED_PINS_ROUTED)) {
                rrs.netsWithNoPlacedPins++;
            } else if (has(s, NODRIVER)) {
                rrs.netsNotNeedingRouting++;
            } else if (has(s, HIERPORT)) {
                rrs.netsNotNeedingRouting++;
                rrs.implicitlyRoutedPorts++;
            } else if (has(s, UNPLACED)) {
                // (neither routable nor not needing routing)
            } else if (has(s, NOLOADS)) {
                rrs.netsNotNeedingRouting++;
                rrs.netsWithNoLoads++;
            } else if (has(s, INTRASITE)) {
                rrs.netsNotNeedingRouting++;
                rrs.internallyRoutedNets++;
            } else {
                rrs.routableNets++;
                if (has(s, UNROUTED)) {
                    rrs.unroutedNets++;
                } else if (has(s, ROUTED)) {
                    rrs.fullyRoutedNets++;
                }
            }
        }
        return rrs;
    }

    /** Assigns consecutive indices to distinct long keys, in an open addressing table. */
    static final class LongIndex {
        private final long[] table;
        /** Index + 1 of the key in each slot (0 if empty) */
        private final int[] slots;
        private final long[] order;
        private final int mask;
        private int size;

        LongIndex(int expected) {
            int capacity = Integer.highestOneBit(Math.max(4, expected * 2) - 1) << 1;
            table = new long[capacity];
            slots = new int[capacity];
            order = new long[Math.max(1, expected)];
            mask = capacity - 1;
        }

        private static int spread(long k) {
            long h = k * 0x9E3779B97F4A7C15L;
            return (int) (h ^ (h >>> 32));
        }

        /** Gets the index of a key, adding it if new. */
        int add(long k) {
            int i = spread(k) & mask;
            int s;
            while ((s = slots[i]) != 0) {
                if (table[i] == k) {
                    return s - 1;
                }
                i = (i + 1) & mask;
            }
            table[i] = k;
            order[size] = k;
            slots[i] = ++size;
            return size - 1;
        }

        /** Gets the index of a key, or -1 if absent. */
        int get(long k) {
            int i = spread(k) & mask;
            int s;
            while ((s = slots[i]) != 0) {
                if (table[i] == k) {
                    return s - 1;
                }
                i = (i + 1) & mask;
            }
            return -1;
        }

        /** Gets the keys, in order of their indices. */
        long[] keys() {
            return Arrays.copyOf(order, size);
        }
    }

    /** A set of ints, in one open addressing table (0 marks empty slots, so is kept aside). */
    static class IntHashSet {
        private final int[] table;
        private final int mask;
        private boolean hasZero;

        IntHashSet(int expected) {
            int capacity = Integer.highestOneBit(Math.max(4, expected * 2) - 1) << 1;
            table = new int[capacity];
            mask = capacity - 1;
        }

        static int spread(int h) {
            h *= 0x9E3779B9;
            return h ^ (h >>> 16);
        }

        void add(int v) {
            if (v == 0) {
                hasZero = true;
                return;
            }
            int i = spread(v) & mask;
            while (table[i] != 0) {
                if (table[i] == v) {
                    return;
                }
                i = (i + 1) & mask;
            }
            table[i] = v;
        }

        boolean contains(int v) {
            if (v == 0) {
                return hasZero;
            }
            int i = spread(v) & mask;
            int t;
            while ((t = table[i]) != 0) {
                if (t == v) {
                    return true;
                }
                i = (i + 1) & mask;
            }
            return false;
        }
    }
}

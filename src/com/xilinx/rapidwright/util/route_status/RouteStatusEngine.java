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

import static com.xilinx.rapidwright.util.route_status.NetStatus.ANTENNAS;
import static com.xilinx.rapidwright.util.route_status.NetStatus.CONFLICTS;
import static com.xilinx.rapidwright.util.route_status.NetStatus.GAPS;
import static com.xilinx.rapidwright.util.route_status.NetStatus.HAS_ROUTING;
import static com.xilinx.rapidwright.util.route_status.NetStatus.HIERPORT;
import static com.xilinx.rapidwright.util.route_status.NetStatus.INTRASITE;
import static com.xilinx.rapidwright.util.route_status.NetStatus.LOCKED_NODES;
import static com.xilinx.rapidwright.util.route_status.NetStatus.NODRIVER;
import static com.xilinx.rapidwright.util.route_status.NetStatus.NOLOADS;
import static com.xilinx.rapidwright.util.route_status.NetStatus.NO_PLACED_PINS_ROUTED;
import static com.xilinx.rapidwright.util.route_status.NetStatus.PARTIAL;
import static com.xilinx.rapidwright.util.route_status.NetStatus.ROUTED;
import static com.xilinx.rapidwright.util.route_status.NetStatus.ROUTED_UNLISTED;
import static com.xilinx.rapidwright.util.route_status.NetStatus.STATIC_CLAIMS;
import static com.xilinx.rapidwright.util.route_status.NetStatus.UNPLACED;
import static com.xilinx.rapidwright.util.route_status.NetStatus.UNPLACED_ALL;
import static com.xilinx.rapidwright.util.route_status.NetStatus.UNROUTED;
import static com.xilinx.rapidwright.util.route_status.NetStatus.bit;
import static com.xilinx.rapidwright.util.route_status.NetStatus.has;
import static com.xilinx.rapidwright.util.route_status.NodeKeys.concat;
import static com.xilinx.rapidwright.util.route_status.NodeKeys.nodeKey;
import static com.xilinx.rapidwright.util.route_status.NodeKeys.sortedDistinct;
import static com.xilinx.rapidwright.util.route_status.RouteStatusNetlist.hasPhysicalPin;
import static com.xilinx.rapidwright.util.route_status.RouteStatusNetlist.isPrimitive;
import static com.xilinx.rapidwright.util.route_status.SitePinClaims.getStaleSitePinKeys;
import static com.xilinx.rapidwright.util.route_status.SiteRouting.hasStaleSitePin;
import static com.xilinx.rapidwright.util.route_status.SiteRouting.isHardBlockCell;
import static com.xilinx.rapidwright.util.route_status.SiteRouting.isIOSite;
import static com.xilinx.rapidwright.util.route_status.SiteRouting.storedPins;
import static com.xilinx.rapidwright.util.route_status.SiteRouting.usesOutputSitePin;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Deque;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import com.xilinx.rapidwright.design.Cell;
import com.xilinx.rapidwright.design.Design;
import com.xilinx.rapidwright.design.Net;
import com.xilinx.rapidwright.design.NetType;
import com.xilinx.rapidwright.design.PartitionPin;
import com.xilinx.rapidwright.design.SiteInst;
import com.xilinx.rapidwright.device.Node;
import com.xilinx.rapidwright.device.PIP;
import com.xilinx.rapidwright.device.Series;
import com.xilinx.rapidwright.device.Tile;
import com.xilinx.rapidwright.edif.EDIFCell;
import com.xilinx.rapidwright.edif.EDIFCellInst;
import com.xilinx.rapidwright.edif.EDIFHierCellInst;
import com.xilinx.rapidwright.edif.EDIFLibrary;
import com.xilinx.rapidwright.edif.EDIFNetlist;
import com.xilinx.rapidwright.edif.EDIFTools;
import com.xilinx.rapidwright.util.ParallelismTools;
import com.xilinx.rapidwright.util.ReportRouteStatus;
import com.xilinx.rapidwright.util.ReportRouteStatusResult.NetRouteStatus;
import com.xilinx.rapidwright.util.ReportRouteStatusResult;

/**
 * Computes the route status of a design the way Vivado's `report_route_status` does. See
 * {@link ReportRouteStatus#reportRouteStatus(Design, boolean)}.
 *
 * Per-net statuses are kept as bit masks of {@link NetRouteStatus} ordinals. Nets are examined in
 * parallel; resource conflicts are found by sorting the nodes used by all nets.
 */
public final class RouteStatusEngine {

    private final Design design;

    private final boolean perNet;

    /** Whether to find conflicts by sorting node keys packed with their entries (see {@link ConflictMarker}) */
    private final boolean packConflicts;

    /** The nets reported */
    private final Entries entries = new Entries();

    private final SitePinClaims sitePins;

    private final Series7Rules series7;

    private static final int PHYSICAL_ONLY = -1;

    /** Nets without connections that are only counted (as internal to a site), not entries */
    private long unconnectedNets;

    /** Set when the VCC net has unrouted ties to enable pins (of nets without a physical net) */
    private volatile boolean unroutedVccTies;

    /** The net of routing that only reserves resources */
    private static final String DUMMY_ROUTE_NET = "GLOBAL_DUMMY_ROUTE";

    /** Sorted keys of the nodes of the partition pins, and the nets they are on */
    private long[] partitionPinKeys;

    private final Set<Net> partitionPinNets = new HashSet<>();

    private RouteStatusEngine(Design design, boolean perNet, boolean packConflicts) {
        this.design = design;
        this.perNet = perNet;
        this.packConflicts = packConflicts;
        sitePins = new SitePinClaims(design, entries);
        series7 = new Series7Rules(design, entries);
    }

    /**
     * See {@link ReportRouteStatus#reportRouteStatus(Design, boolean)}.
     */
    public static ReportRouteStatusResult run(Design design, boolean perNet) {
        return run(design, perNet, true);
    }

    /**
     * See {@link ReportRouteStatus#reportRouteStatus(Design, boolean)}.
     * @param packConflicts Whether to find conflicts by sorting packed node keys where possible (else
     * as for huge designs, which tests compare).
     */
    static ReportRouteStatusResult run(Design design, boolean perNet, boolean packConflicts) {
        // Writing a checkpoint leaves the macro primitives (e.g. RAM64X1D) collapsed in the
        // netlist, while the cells placed for them are those of their expansion (as read from a
        // checkpoint). Vivado expands them when it reads the design: so does the report, which then
        // restores the netlist.
        EDIFNetlist netlist = design.getNetlist();
        boolean expanded = hasCollapsedMacros(design);
        boolean tracking = netlist.isTrackingCellChanges();
        if (expanded) {
            netlist.setTrackCellChanges(false);
            netlist.expandMacroUnisims(design.getSeries());
        }
        try {
            return new RouteStatusEngine(design, perNet, packConflicts).run();
        } finally {
            if (expanded) {
                netlist.collapseMacroUnisims(design.getSeries());
                netlist.setTrackCellChanges(tracking);
            }
        }
    }

    /**
     * Checks whether the netlist of a design has macro primitives that are collapsed into leaf
     * cells while cells of their expansion are placed, as {@link Design#writeCheckpoint(String)}
     * leaves them.
     */
    private static boolean hasCollapsedMacros(Design design) {
        EDIFNetlist netlist = design.getNetlist();
        EDIFLibrary prims = netlist.getHDIPrimitivesLibrary();
        Series series = design.getSeries();
        if (prims == null || series == null) {
            return false;
        }
        EDIFLibrary macros = Design.getMacroPrimitives(series);
        Set<EDIFCell> collapsed = Collections.newSetFromMap(new IdentityHashMap<>());
        for (EDIFCell cell : prims.getCells()) {
            EDIFCell macro = macros.getCell(cell.getName());
            if (macro != null && cell.isLeafCellOrBlackBox() && !macro.isLeafCellOrBlackBox()) {
                collapsed.add(cell);
            }
        }
        if (collapsed.isEmpty()) {
            return false;
        }
        for (Cell cell : design.getCells()) {
            String name = cell.getName();
            int sep = name.lastIndexOf(EDIFTools.EDIF_HIER_SEP);
            if (cell.isRoutethru() || sep < 0) {
                continue;
            }
            EDIFHierCellInst parent = netlist.getHierCellInstFromName(name.substring(0, sep));
            if (parent != null && collapsed.contains(parent.getCellType())) {
                return true;
            }
        }
        return false;
    }

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
        List<List<FlatNet>> staticFlats = Arrays.asList(new ArrayList<>(), new ArrayList<>());
        // The flat nets that are entries, found per list of the netlist (in parallel), then numbered
        List<List<FlatNet>> lists = flatNetlist.getFlatNetLists();
        FlatNet[][] listEntries = new FlatNet[lists.size()][];
        int[] listSizes = new int[lists.size()];
        List<FlatNet> statics = Collections.synchronizedList(new ArrayList<>());
        List<FlatNet> macroTies = Collections.synchronizedList(new ArrayList<>());
        parallelFor(lists.size(), (l) -> {
            List<FlatNet> list = lists.get(l);
            FlatNet[] entries = new FlatNet[list.size()];
            int size = 0;
            for (FlatNet flat : list) {
                if (flat.mergedInto != null) {
                    continue;
                }
                if (flat.staticType != null && !flat.isUnloadedStaticBusBit()) {
                    statics.add(flat);
                    continue;
                }
                if ((flat.insideMacro || (flat.leafLoads > 0 && flat.macroLeafLoads == flat.leafLoads && flat.leafInouts == 0
                        && flat.blackBoxDrivers == 0))
                        && flat.leafDrivers == 0 && !flat.hasTopPorts()) {
                    // Undriven pins inside macro primitives are tied to a constant by Vivado (its
                    // expansion of the macro includes the constant source), also where the net
                    // reaches no pin outside LUT6_2 macros (its own site routing then does not matter)
                    if (flat.insideMacro && flat.leafLoads > 0) {
                        macroTies.add(flat);
                    }
                    continue;
                }
                entries[size++] = flat;
            }
            listEntries[l] = entries;
            listSizes[l] = size;
        });
        for (FlatNet flat : statics) {
            int t = flat.staticType == NetType.GND ? 0 : 1;
            hasLogicalStatic[t] = true;
            staticLoads[t] += flat.getLoads() - flat.placedPortLoads;
            staticUnplacedLoads[t] += flat.unplacedLeafPins - flat.unplacedLeafDrivers;
            staticFlats.get(t).add(flat);
        }
        int[] listOffsets = new int[lists.size() + 1];
        for (int l = 0; l < lists.size(); l++) {
            listOffsets[l + 1] = listOffsets[l] + listSizes[l];
        }
        int numFlat = listOffsets[lists.size()];
        FlatNet[] flats = new FlatNet[numFlat];
        parallelFor(lists.size(), (l) -> {
            for (int j = 0; j < listSizes[l]; j++) {
                listEntries[l][j].entry = listOffsets[l] + j;
            }
            System.arraycopy(listEntries[l], 0, flats, listOffsets[l], listSizes[l]);
        });
        entries.flats.addAll(Arrays.asList(flats));
        entries.names.addAll(Collections.nCopies(numFlat, null));
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
            FlatNet flat = seg.flat.find();
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
        entries.nets.addAll(Arrays.asList(flatPhysNets));
        List<Integer> uncounted = new ArrayList<>();
        int dummyEntry = -1;
        for (int i = 0; i < physNets.length; i++) {
            Net net = physNets[i];
            if (aliasOrPhysicalOnly[i] == PHYSICAL_ONLY) {
                if (flatNetlist.isInsideBlackBox(net.getName())) {
                    // Routing of the internals of a black box is not part of the design
                    continue;
                }
                if (!net.hasPIPs() && net.getSiteInsts().isEmpty()) {
                    // Neither is an empty net
                    continue;
                }
                if (net.getName().equals(DUMMY_ROUTE_NET)) {
                    dummyEntry = entries.flats.size();
                    uncounted.add(dummyEntry);
                }
                entries.flats.add(null);
                entries.nets.add(net);
                entries.names.add(net.getName());
            } else if (aliasOrPhysicalOnly[i] > 0) {
                int entry = aliasOrPhysicalOnly[i] - 1;
                Net named = entries.nets.get(entry);
                if (net.hasPIPs() && (named == null || !named.hasPIPs())) {
                    // The routing of a net stored under the name of another of its segments, which
                    // Vivado uses when the net named after the flat net has none. (When several
                    // physical nets of a flat net have routing, Vivado keeps that of one of them,
                    // in an order not known here: that of the net named after the flat net is used.)
                    entries.nets.set(entry, net);
                    if (named != null) {
                        entries.aliasNets.computeIfAbsent(entry, (k) -> new ArrayList<>()).add(named);
                    }
                } else {
                    entries.aliasNets.computeIfAbsent(entry, (k) -> new ArrayList<>()).add(net);
                }
            }
        }

        // Vivado represents all GND (VCC) nets as a single net. It is listed if it is used physically
        // or logically, but only counted in the summary if it is used logically. (A netlist with
        // nets but without any primitive also lists GND, as internally routed.)
        int[] staticEntry = {-1, -1};
        boolean placeholderGnd = numFlat > 0 && !hasPrimitiveInstances();
        for (NetType type : new NetType[]{NetType.GND, NetType.VCC}) {
            String name = (type == NetType.GND) ? Net.GND_NET : Net.VCC_NET;
            Net net = design.getNet(name);
            boolean hasLogical = hasLogicalStatic[type == NetType.GND ? 0 : 1];
            boolean hasPhysical = net != null && (net.hasPIPs() || !net.getSiteInsts().isEmpty());
            if (!hasLogical && !hasPhysical && !(type == NetType.GND && placeholderGnd)) {
                continue;
            }
            if (!hasLogical) {
                uncounted.add(entries.flats.size());
            }
            staticEntry[type == NetType.GND ? 0 : 1] = entries.flats.size();
            entries.flats.add(null);
            entries.nets.add(net);
            entries.names.add(name);
        }

        int numEntries = entries.flats.size();
        entries.status = new int[numEntries];
        entries.nodeKeys = new long[numEntries][];
        entries.counted = new boolean[numEntries];
        Arrays.fill(entries.counted, true);
        for (int i : uncounted) {
            entries.counted[i] = false;
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
        sitePins.computeSitePinKeys();
        Map<Integer, Integer> sharedRouting = VersalRules.shareClockBufferRouting(design, entries, flatNetlist);
        for (int t = 0; t < 2; t++) {
            if (staticEntry[t] >= 0) {
                sitePins.addStaticLoadSitePins(staticEntry[t], staticFlats.get(t), true);
            }
        }
        if (staticEntry[0] >= 0 && !macroTies.isEmpty()) {
            // The pins tied to GND inside macros (e.g. left undriven by disconnecting the input of
            // a LUT6_2) are loads of GND where other site routing uses their site pins, whether or
            // not GND is routed
            sitePins.addStaticLoadSitePins(staticEntry[0], macroTies, false);
        }
        int missingTies = getMissingTies();
        if ((missingTies & StaticRouting.VCC_TIE) != 0) {
            unroutedVccTies = true;
        }
        final int dummy = dummyEntry;
        parallelFor(numEntries, (i) -> {
            Net net = entries.nets.get(i);
            FlatNet flat = entries.flats.get(i);
            if (i == dummy) {
                // Routing that reserves resources (for partial reconfiguration) is neither checked
                // nor conflicting
                boolean locked = net.getPIPs().stream().anyMatch(PIP::isPIPFixed);
                entries.status[i] = (net.hasPIPs() ? HAS_ROUTING : 0) | (locked ? LOCKED_NODES : 0) | INTRASITE;
                return;
            }
            entries.status[i] = (net == null && flat == null) ? INTRASITE : classifyNet(net, flat, i);
        });
        for (Map.Entry<Integer, Integer> e : sharedRouting.entrySet()) {
            // (if the routing does not reach all the loads, both nets are partially routed)
            entries.status[e.getKey()] = has(entries.status[e.getValue()], ROUTED) ? ROUTED_UNLISTED : PARTIAL;
        }
        if (unroutedVccTies && staticEntry[1] >= 0 && has(entries.status[staticEntry[1]], HAS_ROUTING)) {
            entries.status[staticEntry[1]] = (entries.status[staticEntry[1]] & ~ROUTED) | PARTIAL;
        }
        if ((missingTies & StaticRouting.GND_TIE) != 0 && staticEntry[0] >= 0 && has(entries.status[staticEntry[0]], HAS_ROUTING)) {
            entries.status[staticEntry[0]] = (entries.status[staticEntry[0]] & ~ROUTED) | PARTIAL;
        }
        for (int t = 0; t < 2; t++) {
            int i = staticEntry[t];
            if (i >= 0 && has(entries.status[i], INTRASITE)) {
                // Not routed, but needs routing if its loads are not all in one site or any is
                // fed from a site pin
                Set<SiteInst> sites = new HashSet<>();
                boolean needsRouting = false;
                for (FlatNet flat : staticFlats.get(t)) {
                    flat.addPhysicalPinSites(design, sites);
                    if (sites.size() > 1 || (flat.firstSite != null && flat.hasLoadOnSitePin(design))) {
                        needsRouting = true;
                        break;
                    }
                }
                if (needsRouting) {
                    entries.status[i] = (entries.status[i] & ~INTRASITE) | UNROUTED;
                }
            }
            if (i >= 0 && t == 0 && hasLogicalStatic[t] && has(entries.status[i], HAS_ROUTING)
                    && staticFlats.get(t).stream().noneMatch((f) -> f.hasLoadWithPhysicalPin(design))) {
                // Like other nets, routing of GND without logical loads (with physical pins) leads
                // nowhere (for VCC, enable pins without physical pins are loads, tied to VCC)
                entries.status[i] = HAS_ROUTING | ANTENNAS | (entries.status[i] & LOCKED_NODES);
            }
            if (i >= 0 && staticUnplacedLoads[t] > 0) {
                // Like other nets, static nets with unplaced loads are reported as such
                int st = entries.status[i] & ~(ROUTED | INTRASITE | UNROUTED);
                if (staticUnplacedLoads[t] == staticLoads[t]) {
                    entries.status[i] = has(st, HAS_ROUTING) ? st | UNPLACED : UNPLACED_ALL;
                } else {
                    // Its placed loads are partially routed if they needed routing
                    entries.status[i] = st | UNPLACED | (has(entries.status[i], UNROUTED) ? PARTIAL : 0);
                }
            }
        }

        sitePins.addClaimedLoadSitePins();
        for (int t = 0; t < 2; t++) {
            int i = staticEntry[t];
            if (i >= 0 && entries.sitePinKeys[i] != null) {
                // The site pins in the site routing of GND (VCC) are its, even where no placed cell
                // uses them: routing of other nets through them conflicts
                long[] keys = concat(entries.staticTieKeys.get(i), entries.sitePinKeys[i]);
                entries.staticTieKeys.put(i, sortedDistinct(keys, keys.length));
                entries.status[i] |= STATIC_CLAIMS;
            }
        }
        sitePins.addSitePinNodes();
        ConflictMarker.mark(entries, packConflicts);
        for (int t = 0; t < 2; t++) {
            if (staticEntry[t] >= 0) {
                entries.status[staticEntry[t]] &= ~STATIC_CLAIMS;
            }
        }
        series7.markInvalidSites(staticEntry);
        series7.markOutputSitePinConflicts();

        // The primary status of a net with some unplaced pins is UNPLACED unless it has conflicts or
        // antennas. Vivado lists such nets as having no placed pins too, but only if any other net
        // truly has no placed pins.
        boolean anyNoPlacedPins = false;
        for (int st : entries.status) {
            if ((has(st, UNPLACED_ALL) && !has(st, UNPLACED)) || has(st, NO_PLACED_PINS_ROUTED)) {
                anyNoPlacedPins = true;
                break;
            }
        }
        if (anyNoPlacedPins) {
            for (int i = 0; i < numEntries; i++) {
                int st = entries.status[i];
                if (has(st, UNPLACED) && !has(st, CONFLICTS | ANTENNAS)) {
                    entries.status[i] |= UNPLACED_ALL;
                }
            }
        }

        boolean anyNoLoads = false;
        for (int s : entries.status) {
            if (has(s, NOLOADS) && !has(s, HIERPORT)) {
                anyNoLoads = true;
                break;
            }
        }
        // Vivado lists implicitly routed ports that have no loads as also having no loads, but only
        // if the design has any other net with no loads
        if (anyNoLoads) {
            for (int i = 0; i < numFlat; i++) {
                if (has(entries.status[i], HIERPORT) && entries.flats.get(i).getLoads() == 0) {
                    entries.status[i] |= NOLOADS;
                }
            }
        }

        // Vivado lists the uncounted static nets only under statuses that some counted net has
        if (!uncounted.isEmpty()) {
            int countedStatuses = unconnectedNets > 0 ? INTRASITE : 0;
            for (int i = 0; i < numEntries; i++) {
                if (entries.counted[i]) {
                    countedStatuses |= entries.status[i];
                }
            }
            for (int i : uncounted) {
                entries.status[i] &= countedStatuses;
            }
        }

        ReportRouteStatusResult rrs = summarize();
        if (perNet) {
            rrs.netsByStatus = new EnumMap<>(NetRouteStatus.class);
            for (NetRouteStatus s : NetRouteStatus.values()) {
                rrs.netsByStatus.put(s, new TreeSet<>());
            }
            for (int i = 0; i < numEntries; i++) {
                FlatNet flat = entries.flats.get(i);
                String name = (flat != null) ? flat.getName() : entries.names.get(i);
                // Vivado escapes backslashes in the net names it lists
                name = name.replace("\\", "\\\\");
                for (NetRouteStatus s : NetRouteStatus.values()) {
                    if (has(entries.status[i], bit(s))) {
                        rrs.netsByStatus.get(s).add(name);
                    }
                }
            }
        }
        return rrs;
    }

    /**
     * Finds site pins that the site programming needs tied to a constant through routing, but which
     * have no site routing (as left by removing nets or their pins): in 7-series devices, inputs of
     * hard blocks (see {@link Series7Rules#getMissingTies(StaticRouting)}); in UltraScale(+) devices,
     * the clock enable and set/reset pins of slices (see
     * {@link UltraScaleRules#getMissingSliceControlTies(Design, StaticRouting)}). Vivado lists them as
     * unrouted nodes of VCC (GND).
     * @return A combination of {@link StaticRouting#VCC_TIE} and {@link StaticRouting#GND_TIE}.
     */
    private int getMissingTies() {
        Series series = design.getDevice().getSeries();
        if (series == Series.UltraScale || series == Series.UltraScalePlus) {
            return UltraScaleRules.getMissingSliceControlTies(design, new StaticRouting(design));
        }
        return (series == Series.Series7) ? series7.getMissingTies(new StaticRouting(design)) : 0;
    }

    /** Checks whether the design instantiates any primitive (below its top cell). */
    private boolean hasPrimitiveInstances() {
        Set<EDIFCell> visited = new HashSet<>();
        Deque<EDIFCell> queue = new ArrayDeque<>();
        queue.add(design.getNetlist().getTopCell());
        while (!queue.isEmpty()) {
            EDIFCell cell = queue.poll();
            if (!visited.add(cell)) {
                continue;
            }
            for (EDIFCellInst inst : cell.getCellInsts()) {
                if (RouteStatusNetlist.isPrimitive(inst.getCellType())) {
                    return true;
                }
                queue.add(inst.getCellType());
            }
        }
        return false;
    }

    interface IndexTask {
        void run(int i);
    }

    /** Runs the task for every index in [0, n), in parallel chunks. */
    static void parallelFor(int n, IndexTask task) {
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

    private int classifyNet(Net net, FlatNet flat, int entry) {
        if (flat != null && flat.staticType != null) {
            // (see isUnloadedStaticBusBit())
            return INTRASITE;
        }
        int s = 0;
        boolean hasRouting = net != null && net.hasPIPs();
        RouteGraph graph = null;
        boolean isStatic = net != null && net.isStaticNet();
        if (hasRouting) {
            s |= HAS_ROUTING;
            graph = new RouteGraph(net);
            entries.nodeKeys[entry] = graph.nodes;
            if (graph.routethruNodes != null && design.getDevice().getSeries() == Series.Series7) {
                series7.findRoutethruInvalidSites(net, entry, graph.routethruNodes);
            }
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
                if (hasRouting && flat.blackBoxDrivers + flat.blackBoxLoads > 0 && partitionPinNets.contains(net)) {
                    // Routing between the partition pins of black boxes (e.g. reconfigurable
                    // modules) is checked like any other
                    graph.analyze(net, entries.aliasNets.get(entry), false, null, null, partitionPinKeys, false);
                    s |= graph.locked ? LOCKED_NODES : 0;
                    if (graph.explicitGaps) {
                        return s | GAPS | (graph.antennas ? ANTENNAS : 0);
                    }
                    return s | (graph.antennas ? ANTENNAS : ROUTED);
                }
                // Only connected to top level ports (and black boxes): an implicitly routed port if
                // driven by a black box (or connected to an inout pin of one), and without loads if
                // a black box drives nothing else
                boolean blackBoxToPort = flat.blackBoxDrivers + flat.blackBoxInouts > 0
                        && flat.placedPortLoads + flat.unplacedPortLoads > 0;
                boolean blackBoxOnly = flat.blackBoxDrivers > 0 && flat.blackBoxLoads == 0 && !flat.hasTopPorts();
                return s | (blackBoxToPort ? HIERPORT : blackBoxOnly ? NOLOADS : INTRASITE);
            }
            // (the output pin of a black box drives, from somewhere)
            int drivers = flat.leafDrivers + flat.placedPortDrivers + flat.blackBoxDrivers;
            int loads = flat.getLoads();
            boolean hasUnplacedPorts = flat.unplacedPortDrivers + flat.unplacedPortLoads > 0;
            if (hasUnplacedPorts && flat.firstSite != null && isIOSite(flat.firstSite)) {
                // A port connected to an I/O buffer is placed on its pad (even if that is not stored)
                drivers += flat.unplacedPortDrivers;
                loads += flat.unplacedPortLoads;
                hasUnplacedPorts = false;
            }
            boolean noPhysicalRouting = net == null || (!hasRouting && entries.sitePinKeys[entry] == null);
            if (hasUnplacedPorts && drivers == 0 && noPhysicalRouting && !entries.aliasNets.containsKey(entry)
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
                boolean needsRouting = (net != null && !storedPins(net).isEmpty()) || flat.hasPinsInMultipleSites(design);
                return s | NODRIVER | (needsRouting ? PARTIAL : 0);
            }
            if (net == null && flat.unplacedLeafPins == 0 && !entries.aliasNets.containsKey(entry) && flat.leafDrivers > 0) {
                // Without a physical net, only loads whose site pins follow from the placement and
                // loads in the site of the driver are connected: in 7-series devices, loads in hard
                // blocks reached through general routing; in others, loads on control pins
                boolean series7 = design.getDevice().getSeries() == Series.Series7;
                if ((series7 || design.getDevice().getSeries() == Series.Versal) && flat.hasRoutedHardBlockLoad(design)) {
                    return s | UNROUTED;
                }
                int controlPins = series7 ? 0 : flat.getControlPinLoads(design);
                if (controlPins != 0) {
                    // Their site pins are also tied to VCC, without routing
                    unroutedVccTies = true;
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
        boolean usesInputSitePin = entries.sitePinKeys[entry] != null;
        if (someUnplaced && !hasRouting) {
            // Partially routed if a placed driver has loads (whether placed or not, as unplaced
            // loads are unrouted) outside its site, or if the placed pins are in different sites
            boolean placedDriver = flat.leafDrivers > flat.unplacedLeafDrivers || flat.placedPortDrivers > 0;
            boolean leavesSite = (net != null && (!storedPins(net).isEmpty() || usesOutputSitePin(net))) || usesInputSitePin;
            return (placedDriver && flat.getLoads() > 0 && leavesSite) || flat.hasPinsInMultipleSites(design) ? s | PARTIAL : s;
        }
        if ((net == null || storedPins(net).isEmpty()) && !hasRouting && !usesInputSitePin
                && (flat == null || !flat.hasPinsInMultipleSites(design))) {
            if (flat != null && flat.multipleSites && flat.hasOnlyLeafClockClearLoads(design)) {
                // The CLRB_LEAF pins of clock buffers are only connected through routing (to the
                // leaf clock dividers, see isLeafClockClearPin())
                return s | NOLOADS;
            }
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
        List<Net> aliases = entries.aliasNets.get(entry);
        graph.analyze(net, aliases, isStatic, entries.sitePinKeys[entry], staleKeys, partitionPinKeys, placedSource);
        if (graph.reachedSinks < graph.sinks && entries.sitePinKeys[entry] != null && staleKeys == null) {
            // Site routing is sometimes left behind (e.g. static ties for LUT pins, or where
            // routing bounces through a site pin) where no placed cell uses it: such site pins
            // are not loads
            graph.analyze(net, aliases, isStatic, entries.sitePinKeys[entry],
                    getStaleSitePinKeys(net, isStatic ? net.getType() : null), partitionPinKeys, placedSource);
        } else if (graph.leafSinks != null && staleKeys == null && hasStaleSitePin(net, graph.leafSinks)) {
            // Neither is a site pin (even with a SitePinInst) where routing ends, if the net's site
            // routing from it reaches no placed cell: the routing to it is an antenna
            graph.analyze(net, aliases, isStatic, entries.sitePinKeys[entry], getStaleSitePinKeys(net, null),
                    partitionPinKeys, placedSource);
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
        if (graph.drivenAgain != null) {
            s |= CONFLICTS;
        }
        // Other placed drivers (of a net with several) whose output site pins the routing does not
        // start from are unrouted
        int unroutedDrivers = (!isStatic && flat != null && flat.leafDrivers - flat.unplacedLeafDrivers > 1)
                ? sitePins.findUnroutedDriverPins(net, flat, graph, entry) : 0;
        if ((unroutedDrivers & SitePinClaims.DRIVER_PIN_CONFLICT) != 0) {
            s |= CONFLICTS;
        }
        boolean sinksReached = graph.reachedSinks == graph.sinks;
        if (!sinksReached && graph.undrivenSource && !graph.rooted && graph.reachedSinks + graph.islandSinks == graph.sinks
                && flat != null && UltraScaleRules.hasOnlyLoadsInDriverSite(design, flat)) {
            // Routing only from output site pins that the site routing of the net does not drive
            // leads nowhere (antennas), but a driver whose loads are all in its own site needs no
            // output site pin: its loads that the routing leads to are routed
            sinksReached = true;
        }
        if (!sinksReached || graph.unroutedSource || unroutedDrivers != 0) {
            s |= PARTIAL;
        } else if (!graph.antennas && !someUnplaced && !has(s, CONFLICTS)) {
            s |= ROUTED;
        }
        return s;
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
        for (int i = 0; i < entries.status.length; i++) {
            if (!entries.counted[i]) {
                continue;
            }
            int s = entries.status[i];
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
                } else if (has(s, ROUTED | ROUTED_UNLISTED)) {
                    rrs.fullyRoutedNets++;
                }
            }
        }
        return rrs;
    }
}

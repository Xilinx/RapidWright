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
import static com.xilinx.rapidwright.util.route_status.SitePinClaims.getStaleSitePinKeys;
import static com.xilinx.rapidwright.util.route_status.SiteRouting.MAX_SITE_ROUTING_DEPTH;
import static com.xilinx.rapidwright.util.route_status.SiteRouting.hasStaleSitePin;
import static com.xilinx.rapidwright.util.route_status.SiteRouting.isHardBlockCell;
import static com.xilinx.rapidwright.util.route_status.SiteRouting.isIOSite;
import static com.xilinx.rapidwright.util.route_status.SiteRouting.isUltraScale;
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
import com.xilinx.rapidwright.device.BELPin;
import com.xilinx.rapidwright.device.Node;
import com.xilinx.rapidwright.device.PIP;
import com.xilinx.rapidwright.device.Series;
import com.xilinx.rapidwright.device.SitePIP;
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

    /** The number of flat (logical) nets that are entries (the first ones) */
    private int numFlat;

    /** Per static net (GND, VCC): its entry, or -1 if it is not reported */
    private final int[] staticEntry = {-1, -1};

    /** Per static net: whether the netlist uses it, and its flat nets */
    private final boolean[] hasLogicalStatic = new boolean[2];

    private final List<List<FlatNet>> staticFlats = Arrays.asList(new ArrayList<>(), new ArrayList<>());

    /** Per static net: its loads, and how many are unplaced */
    private final int[] staticLoads = new int[2];

    private final int[] staticUnplacedLoads = new int[2];

    /** The flat nets of undriven pins inside macro primitives, tied to GND (see isTiedInsideMacro()) */
    private final List<FlatNet> macroTies = Collections.synchronizedList(new ArrayList<>());

    /** The entries that are listed, but not counted in the summary */
    private final List<Integer> uncounted = new ArrayList<>();

    /** The entry of the routing that only reserves resources (see {@link #DUMMY_ROUTE_NET}), or -1 */
    private int dummyEntry = -1;

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
            if (cell.isRoutethru()) {
                continue;
            }
            // The closest instance above the cell in the netlist (macros may be nested, e.g. DSP58C in
            // DSP58)
            String name = cell.getName();
            for (int sep = name.lastIndexOf(EDIFTools.EDIF_HIER_SEP); sep > 0;
                    sep = name.lastIndexOf(EDIFTools.EDIF_HIER_SEP, sep - 1)) {
                EDIFHierCellInst parent = netlist.getHierCellInstFromName(name.substring(0, sep));
                if (parent != null) {
                    if (collapsed.contains(parent.getCellType())) {
                        return true;
                    }
                    break;
                }
            }
        }
        return false;
    }

    private ReportRouteStatusResult run() {
        RouteStatusNetlist flatNetlist = buildNetlist();
        addFlatEntries(flatNetlist);
        addPhysicalEntries(flatNetlist);
        addStaticEntries();
        int numEntries = entries.size();
        entries.status = new int[numEntries];
        entries.nodeKeys = new long[numEntries][];
        entries.counted = new boolean[numEntries];
        Arrays.fill(entries.counted, true);
        for (int i : uncounted) {
            entries.counted[i] = false;
        }
        findPartitionPins();
        sitePins.computeSitePinKeys();
        Map<Integer, Integer> sharedRouting = VersalRules.shareClockBufferRouting(design, entries, flatNetlist);
        addStaticLoadSitePins();
        int missingTies = getMissingTies();
        if ((missingTies & StaticRouting.VCC_TIE) != 0) {
            unroutedVccTies = true;
        }
        classifyEntries();
        for (Map.Entry<Integer, Integer> e : sharedRouting.entrySet()) {
            // (if the routing does not reach all the loads, both nets are partially routed)
            entries.status[e.getKey()] = has(entries.status[e.getValue()], ROUTED) ? ROUTED_UNLISTED : PARTIAL;
        }
        classifyStaticNets(missingTies);
        markConflicts();
        series7.markInvalidSites(staticEntry);
        series7.markOutputSitePinConflicts();
        listUnplacedAsNoPlacedPins();
        listPortsWithoutLoads();
        listUncountedStaticNets();
        return toResult();
    }

    /** Flattens the netlist, looking up the segments named like physical nets. */
    private RouteStatusNetlist buildNetlist() {
        IntHashSet physNameHashes = new IntHashSet(design.getNets().size());
        for (Net net : design.getNets()) {
            physNameHashes.add(net.getName().hashCode());
        }
        // (nets without connections need not be listed in the summary)
        RouteStatusNetlist flatNetlist = new RouteStatusNetlist(design, physNameHashes, !perNet);
        unconnectedNets = flatNetlist.getUnconnectedNets();
        return flatNetlist;
    }

    /**
     * Adds the flat nets that are entries (found per list of the netlist, in parallel, then
     * numbered), and gathers the flat nets of GND and VCC.
     */
    private void addFlatEntries(RouteStatusNetlist flatNetlist) {
        List<List<FlatNet>> lists = flatNetlist.getFlatNetLists();
        FlatNet[][] listEntries = new FlatNet[lists.size()][];
        int[] listSizes = new int[lists.size()];
        List<FlatNet> statics = Collections.synchronizedList(new ArrayList<>());
        parallelFor(lists.size(), (l) -> {
            List<FlatNet> list = lists.get(l);
            FlatNet[] listed = new FlatNet[list.size()];
            int size = 0;
            for (FlatNet flat : list) {
                if (flat.mergedInto != null) {
                    continue;
                }
                if (flat.staticType != null && !flat.isUnloadedStaticBusBit()) {
                    statics.add(flat);
                    continue;
                }
                if (isTiedInsideMacro(flat)) {
                    if (flat.insideMacro && flat.leafLoads > 0) {
                        macroTies.add(flat);
                    }
                    continue;
                }
                listed[size++] = flat;
            }
            listEntries[l] = listed;
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
        numFlat = listOffsets[lists.size()];
        FlatNet[] flats = new FlatNet[numFlat];
        parallelFor(lists.size(), (l) -> {
            for (int j = 0; j < listSizes[l]; j++) {
                listEntries[l][j].entry = listOffsets[l] + j;
            }
            System.arraycopy(listEntries[l], 0, flats, listOffsets[l], listSizes[l]);
        });
        entries.flats.addAll(Arrays.asList(flats));
        entries.names.addAll(Collections.nCopies(numFlat, null));
    }

    /**
     * Checks whether a flat net only has undriven pins inside macro primitives, which Vivado ties to
     * a constant (its expansion of the macro includes the constant source), also where the net
     * reaches no pin outside LUT6_2 macros (its own site routing then does not matter).
     */
    private static boolean isTiedInsideMacro(FlatNet flat) {
        boolean macroLoads = flat.insideMacro || (flat.leafLoads > 0 && flat.macroLeafLoads == flat.leafLoads
                && flat.leafInouts == 0 && flat.blackBoxDrivers == 0);
        return macroLoads && flat.leafDrivers == 0 && !flat.hasTopPorts();
    }

    /**
     * Adds the physical nets of the flat entries, and the physical nets without a flat net as
     * entries. Each physical net is named after the segment naming a flat net, after another of its
     * segments (an alias, which only matters if it has site routing), or is physical only.
     */
    private void addPhysicalEntries(RouteStatusNetlist flatNetlist) {
        Net[] physNets = design.getNets().toArray(new Net[0]);
        Net[] flatPhysNets = new Net[numFlat];
        int[] aliasOrPhysicalOnly = findFlatNetsOfPhysicalNets(flatNetlist, physNets, flatPhysNets);
        entries.nets.addAll(Arrays.asList(flatPhysNets));
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
                    dummyEntry = entries.size();
                    uncounted.add(dummyEntry);
                }
                entries.add(null, net, net.getName());
            } else if (aliasOrPhysicalOnly[i] > 0) {
                addAlias(aliasOrPhysicalOnly[i] - 1, net);
            }
        }
    }

    /**
     * Finds the flat net of each physical net, in parallel.
     * @param flatPhysNets Set to the physical net named after each flat entry.
     * @return Per physical net: 1 + the entry of the flat net it is an alias of, or PHYSICAL_ONLY
     * (else 0).
     */
    private int[] findFlatNetsOfPhysicalNets(RouteStatusNetlist flatNetlist, Net[] physNets, Net[] flatPhysNets) {
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
        return aliasOrPhysicalOnly;
    }

    /** Adds a physical net named after another segment of the flat net of an entry. */
    private void addAlias(int entry, Net net) {
        Net named = entries.nets.get(entry);
        if (net.hasPIPs() && (named == null || !named.hasPIPs())) {
            // The routing of a net stored under the name of another of its segments, which Vivado
            // uses when the net named after the flat net has none. (When several physical nets of a
            // flat net have routing, Vivado keeps that of one of them, in an order not known here:
            // that of the net named after the flat net is used.)
            entries.nets.set(entry, net);
            if (named != null) {
                entries.aliasNets.computeIfAbsent(entry, (k) -> new ArrayList<>()).add(named);
            }
        } else {
            entries.aliasNets.computeIfAbsent(entry, (k) -> new ArrayList<>()).add(net);
        }
    }

    /**
     * Adds GND and VCC as entries. Vivado represents all GND (VCC) nets as a single net. It is listed
     * if it is used physically or logically, but only counted in the summary if it is used logically.
     * (A netlist with nets but without any primitive also lists GND, as internally routed.)
     */
    private void addStaticEntries() {
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
                uncounted.add(entries.size());
            }
            staticEntry[type == NetType.GND ? 0 : 1] = entries.size();
            entries.add(null, net, name);
        }
    }

    /** Finds the nodes of the partition pins that lead to another partition, and the nets on them. */
    private void findPartitionPins() {
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
    }

    /** Adds the site pins of the loads of GND and VCC that need routing (see {@link SitePinClaims}). */
    private void addStaticLoadSitePins() {
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
    }

    /** Classifies each entry (in parallel), see {@link #classifyNet(Net, FlatNet, int)}. */
    private void classifyEntries() {
        parallelFor(entries.size(), (i) -> {
            Net net = entries.nets.get(i);
            FlatNet flat = entries.flats.get(i);
            if (i == dummyEntry) {
                // Routing that reserves resources (for partial reconfiguration) is neither checked
                // nor conflicting
                boolean locked = net.getPIPs().stream().anyMatch(PIP::isPIPFixed);
                entries.status[i] = (net.hasPIPs() ? HAS_ROUTING : 0) | (locked ? LOCKED_NODES : 0) | INTRASITE;
                return;
            }
            entries.status[i] = (net == null && flat == null) ? INTRASITE : classifyNet(net, flat, i);
            if (flat != null) {
                flat.releaseLeafPins();
            }
        });
    }

    /**
     * Classifies GND and VCC from their loads: missing ties, loads in several sites without routing,
     * routing without loads, and unplaced loads.
     */
    private void classifyStaticNets(int missingTies) {
        if (unroutedVccTies && staticEntry[1] >= 0 && has(entries.status[staticEntry[1]], HAS_ROUTING)) {
            entries.status[staticEntry[1]] = (entries.status[staticEntry[1]] & ~ROUTED) | PARTIAL;
        }
        if ((missingTies & StaticRouting.GND_TIE) != 0 && staticEntry[0] >= 0
                && has(entries.status[staticEntry[0]], HAS_ROUTING)) {
            entries.status[staticEntry[0]] = (entries.status[staticEntry[0]] & ~ROUTED) | PARTIAL;
        }
        for (int t = 0; t < 2; t++) {
            int i = staticEntry[t];
            if (i < 0) {
                continue;
            }
            if (has(entries.status[i], INTRASITE) && staticNeedsRouting(t)) {
                // Not routed, but needs routing
                entries.status[i] = (entries.status[i] & ~INTRASITE) | UNROUTED;
            }
            if (t == 0 && hasLogicalStatic[t] && has(entries.status[i], HAS_ROUTING)
                    && staticFlats.get(t).stream().noneMatch((f) -> f.hasLoadWithPhysicalPin(design))) {
                // Like other nets, routing of GND without logical loads (with physical pins) leads
                // nowhere (for VCC, enable pins without physical pins are loads, tied to VCC)
                entries.status[i] = HAS_ROUTING | ANTENNAS | (entries.status[i] & LOCKED_NODES);
            }
            if (staticUnplacedLoads[t] > 0) {
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
    }

    /**
     * Checks whether a static net (0 for GND, 1 for VCC) needs routing: if its loads are not all in
     * one site, or any is fed from a site pin. (Loads that the site routing of GND ties off inside
     * their site, e.g. at an optional inverter, do not count.)
     */
    private boolean staticNeedsRouting(int t) {
        if (t == 0 && entries.nets.get(staticEntry[0]) != null && !entries.nets.get(staticEntry[0]).getSiteInsts().isEmpty()) {
            return gndLoadsNeedRouting();
        }
        Set<SiteInst> sites = new HashSet<>();
        for (FlatNet flat : staticFlats.get(t)) {
            flat.addPhysicalPinSites(design, sites);
            if (sites.size() > 1 || (flat.firstSite != null && flat.hasLoadOnSitePin(design))) {
                return true;
            }
        }
        return false;
    }

    /**
     * Checks whether the loads of GND that its site routing does not tie off inside their site are
     * in more than one site, or any is fed from a site pin.
     */
    private boolean gndLoadsNeedRouting() {
        Net gnd = entries.nets.get(staticEntry[0]);
        SiteInst site = null;
        for (FlatNet flat : staticFlats.get(0)) {
            for (LeafPin pin : flat.getLeafPins(design)) {
                if (pin.isOutput() || !pin.isPlaced()) {
                    continue;
                }
                SiteInst si = pin.cell.getSiteInst();
                for (String physPin : pin.getPhysicalPins()) {
                    BELPin belPin = pin.cell.getBEL().getPin(physPin);
                    if (belPin == null || isTiedInsideSite(si, belPin, gnd)) {
                        continue;
                    }
                    if (belPin.getConnectedSitePinName() != null || (site != null && site != si)) {
                        return true;
                    }
                    site = si;
                }
            }
        }
        return false;
    }

    /**
     * Checks whether the site routing of a net ties a BEL pin off inside its site: its site wire
     * carries the net from a BEL that does not pass it on from a site pin (e.g. a constant source, or
     * an optional inverter whose input carries another net), through used site PIPs.
     */
    private static boolean isTiedInsideSite(SiteInst si, BELPin belPin, Net net) {
        int siteWire = belPin.getSiteWireIndex();
        for (int depth = 0; depth < MAX_SITE_ROUTING_DEPTH && si.getNetFromSiteWireIndex(siteWire) == net; depth++) {
            BELPin driver = null;
            for (BELPin pin : si.getSiteWirePins(siteWire)) {
                if (pin.isOutput()) {
                    driver = pin;
                    break;
                }
            }
            if (driver == null || driver.isSitePort()) {
                return false;
            }
            SitePIP sitePIP = si.getUsedSitePIP(driver.getBEL());
            if (sitePIP == null) {
                return true;
            }
            siteWire = sitePIP.getInputPin().getSiteWireIndex();
            if (si.getNetFromSiteWireIndex(siteWire) != net) {
                return true;
            }
        }
        return false;
    }

    /**
     * Marks the nets that use the same node (through routing or site pins) as conflicting (see
     * {@link ConflictMarker}).
     */
    private void markConflicts() {
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
    }

    /**
     * The primary status of a net with some unplaced pins is UNPLACED unless it has conflicts or
     * antennas. Vivado lists such nets as having no placed pins too, but only if any other net truly
     * has no placed pins.
     */
    private void listUnplacedAsNoPlacedPins() {
        boolean anyNoPlacedPins = false;
        for (int st : entries.status) {
            if ((has(st, UNPLACED_ALL) && !has(st, UNPLACED)) || has(st, NO_PLACED_PINS_ROUTED)) {
                anyNoPlacedPins = true;
                break;
            }
        }
        if (!anyNoPlacedPins) {
            return;
        }
        for (int i = 0; i < entries.status.length; i++) {
            int st = entries.status[i];
            if (has(st, UNPLACED) && !has(st, CONFLICTS | ANTENNAS)) {
                entries.status[i] |= UNPLACED_ALL;
            }
        }
    }

    /**
     * Vivado lists implicitly routed ports that have no loads as also having no loads, but only if
     * the design has any other net with no loads.
     */
    private void listPortsWithoutLoads() {
        boolean anyNoLoads = false;
        for (int s : entries.status) {
            if (has(s, NOLOADS) && !has(s, HIERPORT)) {
                anyNoLoads = true;
                break;
            }
        }
        if (!anyNoLoads) {
            return;
        }
        for (int i = 0; i < numFlat; i++) {
            if (has(entries.status[i], HIERPORT) && entries.flats.get(i).getLoads() == 0) {
                entries.status[i] |= NOLOADS;
            }
        }
    }

    /** Vivado lists the uncounted static nets only under statuses that some counted net has. */
    private void listUncountedStaticNets() {
        if (uncounted.isEmpty()) {
            return;
        }
        int countedStatuses = unconnectedNets > 0 ? INTRASITE : 0;
        for (int i = 0; i < entries.status.length; i++) {
            if (entries.counted[i]) {
                countedStatuses |= entries.status[i];
            }
        }
        for (int i : uncounted) {
            entries.status[i] &= countedStatuses;
        }
    }

    /** Gets the summary, and with {@link #perNet}, the nets listed with each status. */
    private ReportRouteStatusResult toResult() {
        ReportRouteStatusResult rrs = summarize();
        if (!perNet) {
            return rrs;
        }
        rrs.netsByStatus = new EnumMap<>(NetRouteStatus.class);
        for (NetRouteStatus s : NetRouteStatus.values()) {
            rrs.netsByStatus.put(s, new TreeSet<>());
        }
        for (int i = 0; i < entries.size(); i++) {
            // Vivado escapes backslashes in the net names it lists
            String name = entries.getName(i).replace("\\", "\\\\");
            for (NetRouteStatus s : NetRouteStatus.values()) {
                if (has(entries.status[i], bit(s))) {
                    rrs.netsByStatus.get(s).add(name);
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
        if (isUltraScale(series)) {
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

    /**
     * Classifies a net from its connections, its placement and its routing.
     * @param net Its physical net (null if none).
     * @param flat Its flat net (null for physical-only and static nets).
     * @return Its status (see {@link NetStatus}).
     */
    private int classifyNet(Net net, FlatNet flat, int entry) {
        if (flat != null && flat.staticType != null) {
            // (see FlatNet.isUnloadedStaticBusBit())
            return INTRASITE;
        }
        int s = 0;
        RouteGraph graph = null;
        if (net != null && net.hasPIPs()) {
            s |= HAS_ROUTING;
            graph = new RouteGraph(net);
            entries.nodeKeys[entry] = graph.nodes;
            if (graph.routethruNodes != null && design.getDevice().getSeries() == Series.Series7) {
                series7.findRoutethruInvalidSites(net, entry, graph.routethruNodes);
            }
        }
        if (flat != null) {
            int pinCount = flat.leafDrivers + flat.leafLoads + flat.leafInouts;
            if (pinCount > 0 && flat.unplacedLeafPins == pinCount
                    && flat.placedPortDrivers + flat.placedPortLoads == 0) {
                // A net with no placed pins but with routing has only antennas (and is counted, but
                // not listed, as having no placed pins)
                return (graph != null) ? s | NO_PLACED_PINS_ROUTED | ANTENNAS | lockedNodes(graph) : s | UNPLACED_ALL;
            }
            if (flat.unplacedLeafPins > 0) {
                s |= UNPLACED;
            }
            int st = (pinCount == 0) ? classifyWithoutLeafPins(net, flat, entry, s, graph)
                    : classifyByConnections(net, flat, entry, s, graph);
            if (st != UNDECIDED) {
                return st;
            }
        }
        if (flat != null && flat.blackBoxDrivers > 0 && flat.leafDrivers == 0 && graph == null && !has(s, UNPLACED)) {
            // Driven by a black box: needs routing to its loads
            return s | UNROUTED;
        }
        return (graph == null) ? classifyWithoutRouting(net, flat, entry, s)
                : classifyRouting(net, flat, entry, s, graph);
    }

    /** A status not decided by a step of {@link #classifyNet(Net, FlatNet, int)} (statuses are not negative) */
    private static final int UNDECIDED = -1;

    private static int lockedNodes(RouteGraph graph) {
        return graph.locked ? LOCKED_NODES : 0;
    }

    /**
     * Classifies a net without pins on leaf cells: only connected to top level ports and black boxes
     * (unless its routing connects the partition pins of black boxes).
     */
    private int classifyWithoutLeafPins(Net net, FlatNet flat, int entry, int s, RouteGraph graph) {
        if (graph != null && flat.blackBoxDrivers + flat.blackBoxLoads > 0 && partitionPinNets.contains(net)) {
            // Routing between the partition pins of black boxes (e.g. reconfigurable modules) is
            // checked like any other
            graph.analyze(net, entries.aliasNets.get(entry), false, null, null, partitionPinKeys, false);
            s |= lockedNodes(graph);
            if (graph.explicitGaps) {
                return s | GAPS | (graph.antennas ? ANTENNAS : 0);
            }
            return s | (graph.antennas ? ANTENNAS : ROUTED);
        }
        // Only connected to top level ports (and black boxes): an implicitly routed port if driven by
        // a black box (or connected to an inout pin of one), and without loads if a black box drives
        // nothing else
        boolean blackBoxToPort = flat.blackBoxDrivers + flat.blackBoxInouts > 0
                && flat.placedPortLoads + flat.unplacedPortLoads > 0;
        boolean blackBoxOnly = flat.blackBoxDrivers > 0 && flat.blackBoxLoads == 0 && !flat.hasTopPorts();
        return s | (blackBoxToPort ? HIERPORT : blackBoxOnly ? NOLOADS : INTRASITE);
    }

    /**
     * Classifies a net with pins on leaf cells by its drivers and loads: unplaced ports, no driver,
     * no physical net, or no loads.
     * @return Its status, or {@link #UNDECIDED}.
     */
    private int classifyByConnections(Net net, FlatNet flat, int entry, int s, RouteGraph graph) {
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
        if (hasUnplacedPorts) {
            int st = classifyUnplacedPorts(net, flat, entry, s, graph, drivers, loads);
            if (st != UNDECIDED) {
                return st;
            }
        }
        if (drivers == 0 && flat.leafInouts == 0 && loads > 0) {
            // (an inout pin may drive)
            if (graph != null) {
                // Routing without a driver is all islands
                return s | NODRIVER | ANTENNAS | lockedNodes(graph);
            }
            // Not routed, but needs routing if its loads are not all in one site
            boolean needsRouting = (net != null && !storedPins(net).isEmpty()) || flat.hasPinsInMultipleSites(design);
            return s | NODRIVER | (needsRouting ? PARTIAL : 0);
        }
        if (net == null && flat.unplacedLeafPins == 0 && !entries.aliasNets.containsKey(entry)
                && flat.leafDrivers > 0) {
            int st = classifyWithoutPhysicalNet(flat, s);
            if (st != UNDECIDED) {
                return st;
            }
        }
        // (routing to a partition pin leads to loads in another partition, e.g. a reconfigurable
        // module)
        boolean toPartition = loads == 0 && graph != null && partitionPinNets.contains(net);
        int pinCount = flat.leafDrivers + flat.leafLoads + flat.leafInouts;
        if ((loads == 0 && !toPartition) || (pinCount == 1 && flat.leafInouts == 1 && !flat.hasTopPorts())) {
            // Routing without loads leads nowhere (a lone inout pin has no load either)
            if (graph != null) {
                return s | ANTENNAS | lockedNodes(graph);
            }
            // A hard block's pin without a physical pin is connected inside its site (or to another
            // block without routing), and so are loads in black boxes
            boolean internalDriver = flat.leafDrivers == 1 && pinCount == 1 && flat.firstCell != null
                    && isHardBlockCell(flat.firstCell)
                    && !RouteStatusNetlist.hasPhysicalPin(flat.firstCell, flat.firstPin);
            return s | (internalDriver || (loads == 0 && flat.blackBoxLoads > 0) ? INTRASITE : NOLOADS);
        }
        return UNDECIDED;
    }

    /**
     * Classifies a net connected to top level ports that are not placed.
     * @return Its status, or {@link #UNDECIDED}.
     */
    private int classifyUnplacedPorts(Net net, FlatNet flat, int entry, int s, RouteGraph graph, int drivers,
                                      int loads) {
        boolean noPhysicalRouting = net == null || (graph == null && entries.sitePinKeys[entry] == null);
        if (drivers == 0 && noPhysicalRouting && !entries.aliasNets.containsKey(entry)
                && !flat.hasPinsInMultipleSites(design) && !flat.hasLoadOnLutInput(design)) {
            // An unplaced input port without physical routing into a single site (but not into a
            // LUT, whose inputs are only reached through site pins)
            return s | INTRASITE;
        }
        if (drivers == 0 || loads == 0) {
            s |= HIERPORT;
            if (graph != null) {
                // Routing from/to the port leaves an explicit gap, unless it is to the partition pin
                // of the port (of a reconfigurable module), where it is not connected to the port
                s |= flat.partitionPinPorts > 0 ? ANTENNAS | lockedNodes(graph) : GAPS;
            }
            return s;
        }
        return UNDECIDED;
    }

    /**
     * Classifies a placed net without a physical net. Only loads whose site pins follow from the
     * placement and loads in the site of the driver are connected: in 7-series devices, loads in
     * hard blocks reached through general routing; in others, loads on control pins.
     * @return Its status, or {@link #UNDECIDED}.
     */
    private int classifyWithoutPhysicalNet(FlatNet flat, int s) {
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
        return UNDECIDED;
    }

    /** Classifies a net without routing (that needs it, or is internal to a site). */
    private int classifyWithoutRouting(Net net, FlatNet flat, int entry, int s) {
        boolean usesInputSitePin = entries.sitePinKeys[entry] != null;
        if (usesInputSitePin && net != null && net.getType() == NetType.GND
                && design.getDevice().getSeries() == Series.Versal) {
            // (in Versal devices, GND is tied off at optional inverters: their site pins need no
            // routing)
            usesInputSitePin = hasSitePinNotToInverter(net);
        }
        if (has(s, UNPLACED)) {
            // A net with some unplaced pins is reported as such instead of being routed, unrouted or
            // internal to a site. It is partially routed if a placed driver has loads (whether placed
            // or not, as unplaced loads are unrouted) outside its site, or if the placed pins are in
            // different sites.
            boolean leavesSite = (net != null && (!storedPins(net).isEmpty() || usesOutputSitePin(net)))
                    || usesInputSitePin;
            return (flat.hasPlacedDriver() && flat.getLoads() > 0 && leavesSite) || flat.hasPinsInMultipleSites(design)
                    ? s | PARTIAL : s;
        }
        if ((net == null || storedPins(net).isEmpty()) && !usesInputSitePin
                && (flat == null || !flat.hasPinsInMultipleSites(design))) {
            if (flat != null && flat.multipleSites && flat.hasOnlyLeafClockClearLoads(design)) {
                // The CLRB_LEAF pins of clock buffers are only connected through routing (to the leaf
                // clock dividers, see VersalRules.isLeafClockClearPin())
                return s | NOLOADS;
            }
            if (flat != null && flat.multipleSites && flat.secondCell != null
                    && flat.secondCell.getType().startsWith("RAMB")
                    && flat.leafLoads == 1 && flat.leafDrivers == 1) {
                // An unused (unmapped) block RAM pin in another site is not a load
                return s | NOLOADS;
            }
            return s | INTRASITE;
        }
        return s | UNROUTED;
    }

    /** Checks whether the site routing of a net enters a site at a site pin that does not only feed inverters. */
    private static boolean hasSitePinNotToInverter(Net net) {
        for (SiteInst si : net.getSiteInsts()) {
            for (String siteWire : si.getSiteWiresFromNet(net)) {
                boolean fromSitePin = false;
                boolean toInverterOnly = true;
                for (BELPin pin : si.getSiteWirePins(siteWire)) {
                    if (pin.isSitePort()) {
                        fromSitePin |= pin.isOutput();
                    } else if (pin.isInput() && !pin.getBELName().contains("INV")) {
                        toInverterOnly = false;
                    }
                }
                if (fromSitePin && !toInverterOnly) {
                    return true;
                }
            }
        }
        return false;
    }

    /** Classifies a net with routing, from the analysis of its routing (see {@link RouteGraph}). */
    private int classifyRouting(Net net, FlatNet flat, int entry, int s, RouteGraph graph) {
        boolean someUnplaced = has(s, UNPLACED);
        long[] staleKeys = null;
        if (someUnplaced && flat != null) {
            if (!flat.hasPlacedDriver() && !partitionPinNets.contains(net)) {
                // The routing of a net whose driver is unplaced is all islands (unless it comes from
                // another partition)
                return s | ANTENNAS | lockedNodes(graph) | (flat.hasPinsInMultipleSites(design) ? PARTIAL : 0);
            }
            // Site pins left in sites without a placed cell of the net are not loads
            staleKeys = getStaleSitePinKeys(net, null);
        }
        analyzeRouting(net, flat, entry, graph, staleKeys);
        s |= lockedNodes(graph);
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
        int unroutedDrivers = (!net.isStaticNet() && flat != null && flat.leafDrivers - flat.unplacedLeafDrivers > 1)
                ? sitePins.findUnroutedDriverPins(net, flat, graph, entry) : 0;
        if ((unroutedDrivers & SitePinClaims.DRIVER_PIN_CONFLICT) != 0) {
            s |= CONFLICTS;
        }
        boolean sinksReached = graph.reachedSinks == graph.sinks || reachesLoadsInDriverSite(flat, graph);
        if (!sinksReached || graph.unroutedSource || unroutedDrivers != 0) {
            s |= PARTIAL;
        } else if (!graph.antennas && !someUnplaced && !has(s, CONFLICTS)) {
            s |= ROUTED;
        }
        return s;
    }

    /**
     * Analyzes the routing of a net, and again without the site pins found to be stale (whose site
     * routing reaches no placed cell), which are not loads.
     * @param staleKeys The keys of the site pins known to be stale (of a net with unplaced pins), or
     * null.
     */
    private void analyzeRouting(Net net, FlatNet flat, int entry, RouteGraph graph, long[] staleKeys) {
        boolean isStatic = net.isStaticNet();
        boolean placedSource = flat != null && flat.hasPlacedDriver();
        List<Net> aliases = entries.aliasNets.get(entry);
        long[] sitePinKeys = entries.sitePinKeys[entry];
        graph.analyze(net, aliases, isStatic, sitePinKeys, staleKeys, partitionPinKeys, placedSource);
        if (staleKeys != null) {
            return;
        }
        if (graph.reachedSinks < graph.sinks && sitePinKeys != null) {
            // Site routing is sometimes left behind (e.g. static ties for LUT pins, or where routing
            // bounces through a site pin) where no placed cell uses it: such site pins are not loads
            long[] staleSitePinKeys = getStaleSitePinKeys(net, isStatic ? net.getType() : null);
            graph.analyze(net, aliases, isStatic, sitePinKeys, staleSitePinKeys, partitionPinKeys, placedSource);
        } else if (graph.leafSinks != null && hasStaleSitePin(net, graph.leafSinks)) {
            // Neither is a site pin (even with a SitePinInst) where routing ends, if the net's site
            // routing from it reaches no placed cell: the routing to it is an antenna
            graph.analyze(net, aliases, isStatic, sitePinKeys, getStaleSitePinKeys(net, null), partitionPinKeys,
                    placedSource);
        }
    }

    /**
     * Checks whether routing that only starts from output site pins that the site routing of the net
     * does not drive (antennas) still reaches the loads, as a driver whose loads are all in its own
     * site needs no output site pin: its loads that the routing leads to are routed.
     */
    private boolean reachesLoadsInDriverSite(FlatNet flat, RouteGraph graph) {
        return graph.undrivenSource && !graph.rooted && graph.reachedSinks + graph.islandSinks == graph.sinks
                && flat != null && UltraScaleRules.hasOnlyLoadsInDriverSite(design, flat);
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
        for (int i = 0; i < entries.status.length; i++) {
            if (entries.counted[i]) {
                rrs.logicalNets++;
                countErrors(rrs, entries.status[i]);
                countCategory(rrs, entries.status[i]);
            }
        }
        return rrs;
    }

    /** The statuses that are routing errors */
    private static final int ERRORS = UNPLACED | PARTIAL | CONFLICTS | ANTENNAS | NODRIVER;

    /**
     * Counts a net with each error (or property) it is listed with, whatever else it is counted as.
     */
    private static void countErrors(ReportRouteStatusResult rrs, int s) {
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
        if (has(s, ERRORS)) {
            rrs.netsWithRoutingErrors++;
        }
    }

    /** Counts a net in one category (and subcategory) of the summary. */
    private static void countCategory(ReportRouteStatusResult rrs, int s) {
        if ((has(s, UNPLACED_ALL) && !has(s, UNPLACED)) || has(s, NO_PLACED_PINS_ROUTED)) {
            rrs.netsWithNoPlacedPins++;
        } else if (has(s, NODRIVER)) {
            rrs.netsNotNeedingRouting++;
        } else if (has(s, HIERPORT)) {
            rrs.netsNotNeedingRouting++;
            rrs.implicitlyRoutedPorts++;
        } else if (has(s, UNPLACED)) {
            // (neither routable nor not needing routing)
            return;
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
}

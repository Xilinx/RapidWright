/*
 * Copyright (c) 2024, Advanced Micro Devices, Inc.
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

package com.xilinx.rapidwright.util;

import com.xilinx.rapidwright.design.Cell;
import com.xilinx.rapidwright.design.Design;
import com.xilinx.rapidwright.design.DesignTools;
import com.xilinx.rapidwright.design.Net;
import com.xilinx.rapidwright.design.NetType;
import com.xilinx.rapidwright.design.PartitionPin;
import com.xilinx.rapidwright.design.SiteInst;
import com.xilinx.rapidwright.design.SitePinInst;
import com.xilinx.rapidwright.design.Unisim;
import com.xilinx.rapidwright.device.BEL;
import com.xilinx.rapidwright.device.BELClass;
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
import com.xilinx.rapidwright.edif.EDIFCell;
import com.xilinx.rapidwright.edif.EDIFCellInst;
import com.xilinx.rapidwright.edif.EDIFHierCellInst;
import com.xilinx.rapidwright.edif.EDIFHierNet;
import com.xilinx.rapidwright.edif.EDIFHierPortInst;
import com.xilinx.rapidwright.edif.EDIFLibrary;
import com.xilinx.rapidwright.edif.EDIFNet;
import com.xilinx.rapidwright.edif.EDIFNetlist;
import com.xilinx.rapidwright.edif.EDIFPort;
import com.xilinx.rapidwright.edif.EDIFPortInst;
import com.xilinx.rapidwright.edif.EDIFTools;
import com.xilinx.rapidwright.util.ReportRouteStatusResult.NetRouteStatus;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Deque;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.ForkJoinTask;
import java.util.concurrent.RecursiveAction;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.Consumer;

public class ReportRouteStatus {

    /**
     * Compute the route status of the given Design the way Vivado's `report_route_status` does,
     * such that every count in the returned summary matches Vivado's. It examines the logical
     * netlist, the placement (including site routing) and the routing of the design, but does not
     * rely on (nor modify) the {@link SitePinInst#isRouted()} state.
     * @param design Design to examine.
     * @return ReportRouteStatusResult object.
     */
    public static ReportRouteStatusResult reportRouteStatus(Design design) {
        return reportRouteStatus(design, false);
    }

    /**
     * Compute the route status of the given Design the way Vivado's `report_route_status` does,
     * see {@link #reportRouteStatus(Design)}.
     * @param design Design to examine.
     * @param perNet If true, also populate {@link ReportRouteStatusResult#netsByStatus} with the
     * names of the nets having each status, as listed by `report_route_status -list_all_nets`.
     * @return ReportRouteStatusResult object.
     */
    public static ReportRouteStatusResult reportRouteStatus(Design design, boolean perNet) {
        return RouteStatusEngine.run(design, perNet);
    }

    public static void main(String[] args) {
        if (args.length != 1) {
            System.out.println("USAGE: ReportRouteStatus <design.dcp>");
            System.exit(1);
        }

        Design design = Design.readCheckpoint(args[0]);

        ReportRouteStatusResult rrs = reportRouteStatus(design);

        System.out.println();
        System.out.println(rrs.toString("RapidWright Design Route Status"));
        if (!rrs.isFullyRouted()) {
            throw new RuntimeException("Design is not fully routed");
        }
    }

    /**
     * Computes the route status of a design the way Vivado's `report_route_status` does. See
     * {@link ReportRouteStatus#reportRouteStatus(Design, boolean)}.
     *
     * Per-net statuses are kept as bit masks of {@link NetRouteStatus} ordinals. Nets are examined in
     * parallel; resource conflicts are found by sorting the nodes used by all nets.
     */
    static final class RouteStatusEngine {

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

        /**
         * Gets the site pins of a net as stored in a checkpoint (as Vivado sees them), where those of
         * a net without routing are not.
         */
        private static List<SitePinInst> storedPins(Net net) {
            return net.hasPIPs() ? net.getPins() : Collections.emptyList();
        }

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
         * See {@link ReportRouteStatus#reportRouteStatus(Design, boolean)}.
         */
        public static ReportRouteStatusResult run(Design design, boolean perNet) {
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
                return new RouteStatusEngine(design, perNet).run();
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

        private static final int PHYSICAL_ONLY = -1;

        /** Nets without connections that are only counted (as internal to a site), not entries */
        private long unconnectedNets;

        /** Set when the VCC net has unrouted ties to enable pins (of nets without a physical net) */
        private volatile boolean unroutedVccTies;

        /** Internal status (not listed): a net with no placed pins, but with routing */
        private static final int NO_PLACED_PINS_ROUTED = 1 << 30;
        /**
         * Internal status (not listed): a net without a physical net whose placed loads claim input
         * site pins (see {@link #driverPinKeys})
         */
        static final int LOGICAL_ONLY = 1 << 29;

        /**
         * Internal status of an output net of a clock buffer that shares the routing of another output
         * (see {@link #shareClockBufferRouting}): counted as fully routed, but not listed as such
         */
        private static final int ROUTED_UNLISTED = 1 << 28;

        /**
         * Internal status of a static net while conflicts are marked: its site pins (from its site
         * routing) are claims, and it is partially routed where the routing of another net uses one
         */
        static final int STATIC_CLAIMS = 1 << 27;

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
            List<RouteStatusNetlist.FlatNet> macroTies = Collections.synchronizedList(new ArrayList<>());
            parallelFor(lists.size(), (l) -> {
                List<RouteStatusNetlist.FlatNet> list = lists.get(l);
                RouteStatusNetlist.FlatNet[] entries = new RouteStatusNetlist.FlatNet[list.size()];
                int size = 0;
                for (RouteStatusNetlist.FlatNet flat : list) {
                    if (flat.mergedInto != null) {
                        continue;
                    }
                    if (flat.staticType != null && !isUnloadedStaticBusBit(flat)) {
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
                    if (!net.hasPIPs() && net.getSiteInsts().isEmpty()) {
                        // Neither is an empty net
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
            Map<Integer, Integer> sharedRouting = shareClockBufferRouting(flatNetlist);
            for (int t = 0; t < 2; t++) {
                if (staticEntry[t] >= 0) {
                    addStaticLoadSitePins(staticEntry[t], staticFlats.get(t), true);
                }
            }
            if (staticEntry[0] >= 0 && !macroTies.isEmpty()) {
                // The pins tied to GND inside macros (e.g. left undriven by disconnecting the input of
                // a LUT6_2) are loads of GND where other site routing uses their site pins, whether or
                // not GND is routed
                addStaticLoadSitePins(staticEntry[0], macroTies, false);
            }
            int missingTies = getMissingTies();
            if ((missingTies & VCC_TIE) != 0) {
                unroutedVccTies = true;
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
            for (Map.Entry<Integer, Integer> e : sharedRouting.entrySet()) {
                // (if the routing does not reach all the loads, both nets are partially routed)
                status[e.getKey()] = has(status[e.getValue()], ROUTED) ? ROUTED_UNLISTED : PARTIAL;
            }
            if (unroutedVccTies && staticEntry[1] >= 0 && has(status[staticEntry[1]], HAS_ROUTING)) {
                status[staticEntry[1]] = (status[staticEntry[1]] & ~ROUTED) | PARTIAL;
            }
            if ((missingTies & GND_TIE) != 0 && staticEntry[0] >= 0 && has(status[staticEntry[0]], HAS_ROUTING)) {
                status[staticEntry[0]] = (status[staticEntry[0]] & ~ROUTED) | PARTIAL;
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

            addClaimedLoadSitePins();
            for (int t = 0; t < 2; t++) {
                int i = staticEntry[t];
                if (i >= 0 && sitePinKeys[i] != null) {
                    // The site pins in the site routing of GND (VCC) are its, even where no placed cell
                    // uses them: routing of other nets through them conflicts
                    long[] keys = concat(staticTieKeys.get(i), sitePinKeys[i]);
                    staticTieKeys.put(i, sortedDistinct(keys, keys.length));
                    status[i] |= STATIC_CLAIMS;
                }
            }
            addSitePinNodes();
            markConflicts();
            for (int t = 0; t < 2; t++) {
                if (staticEntry[t] >= 0) {
                    status[staticEntry[t]] &= ~STATIC_CLAIMS;
                }
            }
            markInvalidSites(staticEntry);
            markOutputSitePinConflicts();

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
         * Marks the nets with pins in (7-series) sites whose site programming Vivado finds invalid:
         * block RAM sites where a pin of a placed cell, connected to a net that is not static, has no
         * site routing (as left by removing a physical net), and slices that a routethru of another
         * net passes through (see {@link #findRoutethruInvalidSites}). All their nets conflict; the routing into
         * (or out of) such a site leads nowhere, and its pins there are not loads (nor drivers).
         */
        private void markInvalidSites(int[] staticEntry) {
            if (design.getDevice().getSeries() != Series.Series7) {
                // (seen with 7-series block RAMs only; UltraScale+ ones are not found invalid)
                return;
            }
            Map<SiteInst, List<EDIFHierPortInst>> invalidSitePins = new IdentityHashMap<>();
            for (SiteInst si : design.getSiteInsts()) {
                String type = si.getSiteTypeEnum().name();
                boolean routethruInvalid = routethruInvalidSites.contains(si);
                if (!type.startsWith("RAMB") && !type.startsWith("FIFO") && !routethruInvalid) {
                    continue;
                }
                List<EDIFHierPortInst> pins = new ArrayList<>();
                boolean invalid = routethruInvalid;
                for (Cell cell : si.getCells()) {
                    EDIFHierCellInst inst = cell.isRoutethru() ? null : cell.getEDIFHierCellInst();
                    if (inst == null) {
                        continue;
                    }
                    for (Map.Entry<String, String> e : cell.getPinMappingsP2L().entrySet()) {
                        EDIFHierPortInst pin = inst.getPortInst(e.getValue());
                        if (pin == null || pin.getNet() == null) {
                            continue;
                        }
                        pins.add(pin);
                        BELPin belPin = cell.getBEL().getPin(e.getKey());
                        if (!invalid && belPin != null && si.getNetFromSiteWire(belPin.getSiteWireName()) == null
                                && !pin.getNet().isGND() && !pin.getNet().isVCC()) {
                            invalid = true;
                        }
                    }
                }
                if (invalid) {
                    invalidSitePins.put(si, pins);
                }
            }
            if (invalidSitePins.isEmpty()) {
                return;
            }
            Map<String, Integer> entryOfName = new HashMap<>();
            for (int i = 0; i < entryFlat.size(); i++) {
                RouteStatusNetlist.FlatNet flat = entryFlat.get(i);
                entryOfName.put(flat != null ? flat.getName() : entryName.get(i), i);
            }
            // Per entry: whether it is driven from such a site
            Map<Integer, Boolean> drivenFromInvalidSite = new HashMap<>();
            // The entries driven from such a slice (rather than a block RAM)
            Set<Integer> drivenFromSlice = new HashSet<>();
            for (Map.Entry<SiteInst, List<EDIFHierPortInst>> sitePins : invalidSitePins.entrySet()) {
                boolean slice = routethruInvalidSites.contains(sitePins.getKey());
                for (EDIFHierPortInst pin : sitePins.getValue()) {
                    int entry = -1;
                    boolean isStatic = false;
                    for (EDIFHierNet seg : RouteStatusNetlist.getSegments(pin.getHierarchicalNet())) {
                        Integer e = entryOfName.get(seg.getHierarchicalNetName());
                        if (e != null) {
                            entry = e;
                            break;
                        }
                        isStatic |= seg.getNet().isGND() || seg.getNet().isVCC();
                    }
                    if (entry < 0 && isStatic) {
                        entry = staticEntry[pin.getNet().isVCC() ? 1 : 0];
                    }
                    if (entry < 0) {
                        continue;
                    }
                    drivenFromInvalidSite.merge(entry, pin.isOutput(), Boolean::logicalOr);
                    if (slice && pin.isOutput()) {
                        drivenFromSlice.add(entry);
                    }
                }
            }
            for (Map.Entry<Integer, Boolean> e : drivenFromInvalidSite.entrySet()) {
                int i = e.getKey();
                int st = status[i];
                RouteStatusNetlist.FlatNet flat = entryFlat.get(i);
                if (flat == null) {
                    // Static nets conflict too
                    status[i] = (st & ~ROUTED) | CONFLICTS;
                    continue;
                }
                Net net = entryNet.get(i);
                // (loads of a physical net without site pins need site routing of it into their site)
                boolean noPins = net != null && storedPins(net).isEmpty();
                boolean loadsElsewhere = false;
                if (net == null) {
                    // (without a physical net, only loads in hard blocks reached through general
                    // routing are connected, see classifyNet())
                    loadsElsewhere = flat.hasRoutedHardBlockLoad(design, invalidSitePins.keySet());
                } else {
                    for (RouteStatusNetlist.LeafPin pin : flat.getLeafPins(design)) {
                        if (!pin.isOutput() && !(pin.isPlaced() && invalidSitePins.containsKey(pin.cell.getSiteInst()))
                                && (!noPins || hasInputSiteRouting(pin, net))) {
                            loadsElsewhere = true;
                            break;
                        }
                    }
                }
                boolean drivenFromSite = e.getValue();
                if (drivenFromSlice.contains(i) && !loadsElsewhere) {
                    // Driven from such a slice, without loads elsewhere: it is not without loads
                    status[i] = (st & ~(ROUTED | NOLOADS)) | CONFLICTS | (has(st, HAS_ROUTING) ? ANTENNAS : 0);
                    continue;
                }
                if (!has(st, HAS_ROUTING)) {
                    // An unrouted net driven from such a site has no loads unless it has others, and so
                    // does one without a physical net (unless it has loads in other hard blocks reached
                    // through general routing); others are partially routed
                    if (has(st, UNROUTED)) {
                        boolean noLoads = drivenFromSite ? !loadsElsewhere : net == null && !loadsElsewhere;
                        st = (st & ~UNROUTED) | (noLoads ? NOLOADS : PARTIAL);
                    }
                    status[i] = st | CONFLICTS;
                    continue;
                }
                st = (st & ~ROUTED) | CONFLICTS | ANTENNAS;
                if (drivenFromSite) {
                    // Driven from such a site: its routing is all islands
                    st |= loadsElsewhere ? PARTIAL : NOLOADS;
                } else if (!loadsElsewhere) {
                    st |= PARTIAL;
                }
                status[i] = st;
            }
        }

        /** Whether the site routing of a net enters the site of a placed cell pin (at an input site pin) */
        private static boolean hasInputSiteRouting(RouteStatusNetlist.LeafPin pin, Net net) {
            if (!pin.isPlaced()) {
                return false;
            }
            SiteInst si = pin.cell.getSiteInst();
            for (String siteWire : si.getSiteWiresFromNet(net)) {
                String sitePin = si.getPrimarySitePinName(siteWire);
                int pinIndex = (sitePin == null) ? -1 : si.getSite().getPinIndex(sitePin);
                if (pinIndex >= 0 && si.getSite().isInputPin(pinIndex)) {
                    return true;
                }
            }
            return false;
        }

        /**
         * Marks the nets whose site routing drives an output site pin of a (7-series) slice that another
         * net's site pin instance (and routing) uses, as left by unplacing cells but not their site
         * routing: both conflict at the site pin.
         */
        private void markOutputSitePinConflicts() {
            if (design.getDevice().getSeries() != Series.Series7) {
                // (seen with 7-series slices only)
                return;
            }
            Map<Net, Integer> entryOf = new IdentityHashMap<>();
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
            Net[] nets = design.getNets().toArray(new Net[0]);
            Set<Integer> conflicting = ConcurrentHashMap.newKeySet();
            parallelFor(nets.length, (n) -> {
                Net net = nets[n];
                if (net.isStaticNet()) {
                    return;
                }
                for (SitePinInst spi : storedPins(net)) {
                    if (!spi.isOutPin() || !Utils.isSLICE(spi.getSiteInst())) {
                        // (only seen in slices; the site routing of hard blocks, e.g. DSP cascades,
                        // may carry other segments of the net)
                        continue;
                    }
                    Net other = spi.getSiteInst().getNetFromSiteWire(spi.getSiteWireName());
                    if (other == null || other == net || other.isStaticNet()) {
                        continue;
                    }
                    Integer a = entryOf.get(net);
                    Integer b = entryOf.get(other);
                    if (a != null && b != null && !a.equals(b)) {
                        conflicting.add(a);
                        conflicting.add(b);
                    }
                }
            });
            for (int i : conflicting) {
                status[i] = (status[i] & ~ROUTED) | CONFLICTS;
            }
        }

        /** Clock buffers with several outputs whose clocks share the routing of one of them */
        private static final String[] SHARED_ROUTING_CLOCK_BUFFERS = {"MBUFGCE", "MBUFGCE_DIV", "MBUFGCTRL", "MBUFG_GT", "MBUFG_PS"};

        /** The outputs of such clock buffers */
        private static final String[] SHARED_ROUTING_OUTPUTS = {"O1", "O2", "O3", "O4"};

        /**
         * The outputs of a (Versal) clock buffer with several, whose clocks are divided at the leaves
         * (e.g. MBUFGCE), share the routing of one of them: the site pins of the loads of the others,
         * which have no routing, are loads of that routing. The others are counted as routed (but not
         * listed as such) if it is.
         * @return The entries of the nets sharing routing, with the entry of the net that has it.
         */
        private Map<Integer, Integer> shareClockBufferRouting(RouteStatusNetlist flatNetlist) {
            Map<Integer, Integer> shared = new HashMap<>();
            for (SiteInst si : design.getSiteInsts()) {
                if (!si.getSiteTypeEnum().name().startsWith("BUFG")) {
                    continue;
                }
                for (Cell cell : si.getCells()) {
                    if (!Arrays.asList(SHARED_ROUTING_CLOCK_BUFFERS).contains(cell.getType())) {
                        continue;
                    }
                    EDIFHierCellInst inst = cell.getEDIFHierCellInst();
                    if (inst == null) {
                        continue;
                    }
                    int routed = -1;
                    List<Integer> others = new ArrayList<>();
                    for (String output : SHARED_ROUTING_OUTPUTS) {
                        EDIFHierPortInst pin = inst.getPortInst(output);
                        EDIFHierNet hierNet = (pin == null) ? null : pin.getHierarchicalNet();
                        RouteStatusNetlist.Segment seg = (hierNet == null) ? null
                                : flatNetlist.getSegment(hierNet.getHierarchicalNetName());
                        int entry = (seg == null) ? -1 : seg.flat.find().entry;
                        if (entry < 0 || entry == routed || others.contains(entry)) {
                            continue;
                        }
                        Net net = entryNet.get(entry);
                        if (net != null && net.hasPIPs()) {
                            if (routed >= 0) {
                                // (each output routed on its own)
                                routed = -2;
                                break;
                            }
                            routed = entry;
                        } else {
                            others.add(entry);
                        }
                    }
                    if (routed < 0) {
                        continue;
                    }
                    for (int other : others) {
                        if (sitePinKeys[other] == null) {
                            continue;
                        }
                        long[] keys = concat(sitePinKeys[routed], sitePinKeys[other]);
                        sitePinKeys[routed] = sortedDistinct(keys, keys.length);
                        sitePinKeys[other] = null;
                        shared.put(other, routed);
                    }
                }
            }
            return shared;
        }

        /** Checks whether the design instantiates any primitive (below its top cell). */
        /** Kinds of missing ties, see {@link #getMissingTies()} */
        private static final int VCC_TIE = 1;
        private static final int GND_TIE = 2;

        /**
         * Finds site pins that the site programming needs tied to a constant through routing, but which
         * have no site routing (as left by removing nets or their pins): in 7-series devices, the inputs
         * of hard blocks reachable through routing whose net has no site routing there (VCC, or GND for
         * clock enables and clock inputs); in UltraScale(+) devices, the clock enable and set/reset
         * pins of slices (see {@link #getMissingSliceControlTies(Series)}). Vivado lists them as
         * unrouted nodes of VCC (GND).
         * @return A combination of {@link #VCC_TIE} and {@link #GND_TIE}.
         */
        private int getMissingTies() {
            Series series = design.getDevice().getSeries();
            if (series == Series.UltraScale || series == Series.UltraScalePlus) {
                return getMissingSliceControlTies(series);
            }
            if (series != Series.Series7) {
                return 0;
            }
            int ties = 0;
            for (SiteInst si : design.getSiteInsts()) {
                String type = si.getSiteTypeEnum().name();
                if (Utils.isSLICE(si) || type.startsWith("RAMB") || type.startsWith("FIFO") || type.startsWith("BUF")) {
                    // (block RAMs whose pins lack site routing are invalid instead, see markInvalidSites(),
                    // and the inputs of clock buffers are reached through clock routing)
                    continue;
                }
                for (Cell cell : si.getCells()) {
                    EDIFHierCellInst inst = cell.isRoutethru() ? null : cell.getEDIFHierCellInst();
                    if (inst == null) {
                        continue;
                    }
                    for (Map.Entry<String, String> e : cell.getPinMappingsP2L().entrySet()) {
                        BELPin belPin = cell.getBEL().getPin(e.getKey());
                        EDIFHierPortInst pin = inst.getPortInst(e.getValue());
                        if (belPin == null || !belPin.isInput() || pin == null || pin.getNet() == null
                                || pin.getNet().isGND() || pin.getNet().isVCC()) {
                            continue;
                        }
                        String siteWire = belPin.getSiteWireName();
                        int pinIndex = si.getSite().getPinIndex(siteWire);
                        if (pinIndex < 0 || si.getNetFromSiteWire(siteWire) != null || si.getSitePinInst(siteWire) != null
                                || !isReachableThroughRouting(si.getSite().getConnectedNode(pinIndex)) || isStaticRoutingTo(si, siteWire)) {
                            // (nor cascade inputs, which have dedicated connections)
                            continue;
                        }
                        ties |= (siteWire.startsWith("CE") || siteWire.startsWith("CLKIN") || siteWire.endsWith("USERRDY"))
                                ? GND_TIE : VCC_TIE;
                    }
                }
            }
            return ties;
        }

        /**
         * The flip-flops and latches whose unused clock enable and set/reset are tied to VCC, with the
         * names of their logical clock enable and set/reset pins
         */
        private static final Map<String, Pair<String, String>> CE_SR_TIED_TYPES = new HashMap<>();
        static {
            CE_SR_TIED_TYPES.put("FDRE", new Pair<>("CE", "R"));
            CE_SR_TIED_TYPES.put("FDSE", new Pair<>("CE", "S"));
            CE_SR_TIED_TYPES.put("FDCE", new Pair<>("CE", "CLR"));
            CE_SR_TIED_TYPES.put("FDPE", new Pair<>("CE", "PRE"));
            CE_SR_TIED_TYPES.put("LDCE", new Pair<>("GE", "CLR"));
            CE_SR_TIED_TYPES.put("LDPE", new Pair<>("GE", "PRE"));
        }

        /**
         * In UltraScale(+) slices, the clock enable and set/reset site pins (e.g. CKEN1, SRST1) of
         * flip-flops (or latches) that tie them off in the netlist (CE to VCC, SR to GND), without a
         * signal (no site routing to the CE pin, and none or that of GND to the SR pin, as in
         * {@link DesignTools#createCeSrRstPinsToVCC(Design)}) are tied to VCC through routing, the SR
         * pin through the inverting setting of its inverter (without a used site PIP). Finds such a pin
         * that has neither a site pin nor site routing of a signal, and which the routing of GND or VCC
         * does not lead to (e.g. after moving flip-flops into the slice).
         * @return {@link #VCC_TIE} if there is such a pin, else 0.
         */
        private int getMissingSliceControlTies(Series series) {
            Map<String, Pair<String, String>> sitePinNames = DesignTools.belTypeSitePinNameMapping.get(series);
            for (SiteInst si : design.getSiteInsts()) {
                if (!Utils.isSLICE(si)) {
                    continue;
                }
                for (Cell cell : si.getCells()) {
                    BEL bel = cell.getBEL();
                    if (cell.isRoutethru() || bel == null || !bel.isFF() || !CE_SR_TIED_TYPES.containsKey(cell.getType())) {
                        continue;
                    }
                    Pair<String, String> pins = sitePinNames.get(bel.getName());
                    if (pins == null) {
                        continue;
                    }
                    for (boolean ce : new boolean[]{true, false}) {
                        BELPin belPin = bel.getPin(ce ? "CE" : "SR");
                        if (belPin == null) {
                            continue;
                        }
                        Net belPinNet = si.getNetFromSiteWireIndex(belPin.getSiteWireIndex());
                        if (belPinNet != null && (ce || !belPinNet.isGNDNet())) {
                            // Used by a signal (or a tie inside the site)
                            continue;
                        }
                        String sitePin = ce ? pins.getFirst() : pins.getSecond();
                        Net sitePinNet = si.getNetFromSiteWire(sitePin);
                        if (si.getSitePinInst(sitePin) != null || (sitePinNet != null && !sitePinNet.isStaticNet())
                                || isStaticRoutingTo(si, sitePin)) {
                            continue;
                        }
                        // Only a pin that the netlist ties off needs it (not one that is unconnected, nor
                        // that of a cell missing from the netlist). The tied pin is not mapped.
                        Pair<String, String> logicalPins = CE_SR_TIED_TYPES.get(cell.getType());
                        String logicalPin = ce ? logicalPins.getFirst() : logicalPins.getSecond();
                        if (!isTiedTo(cell, logicalPin, ce ? NetType.VCC : NetType.GND)) {
                            continue;
                        }
                        if (!ce && isDrivenThroughUsedSitePIP(si, belPin)) {
                            // The set/reset inverter (e.g. RST_ABCDINV) does not invert: the pin is not
                            // tied to VCC
                            continue;
                        }
                        return VCC_TIE;
                    }
                }
            }
            return 0;
        }

        /** Checks whether a BEL in a site that drives the site wire of a BEL pin has a used site PIP */
        private static boolean isDrivenThroughUsedSitePIP(SiteInst si, BELPin pin) {
            for (BELPin driver : si.getSiteWirePins(pin.getSiteWireIndex())) {
                if (!driver.isOutput() || driver.isSitePort()) {
                    continue;
                }
                for (BELPin input : driver.getBEL().getPins()) {
                    if (input.isInput() && si.getUsedSitePIP(input) != null) {
                        return true;
                    }
                }
            }
            return false;
        }

        /** The nodes that the routing of GND and VCC leads to, see {@link #isStaticRoutingTo} */
        private Set<Long> staticRoutingEnds;

        /** Checks whether the routing of GND or VCC leads to a site pin (even without site routing) */
        private boolean isStaticRoutingTo(SiteInst si, String sitePinName) {
            if (staticRoutingEnds == null) {
                staticRoutingEnds = new HashSet<>();
                for (Net net : new Net[]{design.getGndNet(), design.getVccNet()}) {
                    for (PIP pip : net.getPIPs()) {
                        Node end = pip.isReversed() ? pip.getStartNode() : pip.getEndNode();
                        if (end != null) {
                            staticRoutingEnds.add(nodeKey(end));
                        }
                    }
                }
            }
            int pinIndex = si.getSite().getPinIndex(sitePinName);
            Node node = (pinIndex < 0) ? null : si.getSite().getConnectedNode(pinIndex);
            return node != null && staticRoutingEnds.contains(nodeKey(node));
        }

        /** Checks whether a site pin node is driven by routing (not only by the pins of other sites) */
        private static boolean isReachableThroughRouting(Node node) {
            if (node == null) {
                return false;
            }
            for (Node uphill : node.getAllUphillNodes()) {
                if (uphill.getSitePin() == null) {
                    return true;
                }
            }
            return false;
        }

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

        /**
         * Adds the input site pins of the placed LUT loads of a routed static net whose site wires carry
         * another net (i.e. LUT inputs tied to it in the netlist only) as sinks of the static net, which
         * need routing. (Vivado does not count such an input without any site routing as a load.)
         */
        private void addStaticLoadSitePins(int entry, List<RouteStatusNetlist.FlatNet> flats, boolean onlyIfRouted) {
            Net net = entryNet.get(entry);
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
                staticTieKeys.put(entry, sortedDistinct(ties, ties.length));
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

        /** 7-series slices through which routethrus of other nets pass, see {@link #findRoutethruInvalidSites} */
        private final Set<SiteInst> routethruInvalidSites = ConcurrentHashMap.newKeySet();

        /**
         * Finds the (7-series) slices whose site pins a routethru PIP of a net passes through while
         * their site routing has another net there: Vivado finds their site programming invalid.
         */
        private void findRoutethruInvalidSites(Net net, int entry, List<Node> routethruNodes) {
            List<Net> aliases = aliasNets.get(entry);
            for (Node node : routethruNodes) {
                SitePin sitePin = node.getSitePin();
                SiteInst si = (sitePin == null) ? null : design.getSiteInstFromSite(sitePin.getSite());
                if (si == null || !Utils.isSLICE(si)) {
                    continue;
                }
                Net other = si.getNetFromSiteWire(sitePin.getPinName());
                if (other != null && other != net && (aliases == null || !aliases.contains(other))) {
                    routethruInvalidSites.add(si);
                }
            }
        }

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

        /**
         * Checks whether a flat net is a single segment connected to nothing but a static source, and is
         * named as a bit of a bus (as netlists written by e.g. Yosys have many): Vivado does not merge it
         * into the static net, but reports it as a net internally routed (whether the source is placed
         * or not).
         */
        private static boolean isUnloadedStaticBusBit(RouteStatusNetlist.FlatNet flat) {
            return flat.staticType != null && flat.segments == 1 && getLoads(flat) == 0 && flat.blackBoxLoads == 0
                    && !flat.hasTopPorts() && RouteStatusNetlist.isBusBit(flat.getName());
        }

        private int classifyNet(Net net, RouteStatusNetlist.FlatNet flat, int entry) {
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
                nodeKeys[entry] = graph.nodes;
                if (graph.routethruNodes != null && design.getDevice().getSeries() == Series.Series7) {
                    findRoutethruInvalidSites(net, entry, graph.routethruNodes);
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
                        graph.analyze(net, aliasNets.get(entry), false, null, null, partitionPinKeys, false);
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
                int loads = getLoads(flat);
                boolean hasUnplacedPorts = flat.unplacedPortDrivers + flat.unplacedPortLoads > 0;
                if (hasUnplacedPorts && flat.firstSite != null && isIOSite(flat.firstSite)) {
                    // A port connected to an I/O buffer is placed on its pad (even if that is not stored)
                    drivers += flat.unplacedPortDrivers;
                    loads += flat.unplacedPortLoads;
                    hasUnplacedPorts = false;
                }
                boolean noPhysicalRouting = net == null || (!hasRouting && sitePinKeys[entry] == null);
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
                    boolean needsRouting = (net != null && !storedPins(net).isEmpty()) || flat.hasPinsInMultipleSites(design);
                    return s | NODRIVER | (needsRouting ? PARTIAL : 0);
                }
                if (net == null && flat.unplacedLeafPins == 0 && !aliasNets.containsKey(entry) && flat.leafDrivers > 0) {
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
            boolean usesInputSitePin = sitePinKeys[entry] != null;
            if (someUnplaced && !hasRouting) {
                // Partially routed if a placed driver has loads (whether placed or not, as unplaced
                // loads are unrouted) outside its site, or if the placed pins are in different sites
                boolean placedDriver = flat.leafDrivers > flat.unplacedLeafDrivers || flat.placedPortDrivers > 0;
                boolean leavesSite = (net != null && (!storedPins(net).isEmpty() || usesOutputSitePin(net))) || usesInputSitePin;
                return (placedDriver && getLoads(flat) > 0 && leavesSite) || flat.hasPinsInMultipleSites(design) ? s | PARTIAL : s;
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
            List<Net> aliases = aliasNets.get(entry);
            graph.analyze(net, aliases, isStatic, sitePinKeys[entry], staleKeys, partitionPinKeys, placedSource);
            if (graph.reachedSinks < graph.sinks && sitePinKeys[entry] != null && staleKeys == null) {
                // Site routing is sometimes left behind (e.g. static ties for LUT pins, or where
                // routing bounces through a site pin) where no placed cell uses it: such site pins
                // are not loads
                graph.analyze(net, aliases, isStatic, sitePinKeys[entry],
                        getStaleSitePinKeys(net, isStatic ? net.getType() : null), partitionPinKeys, placedSource);
            } else if (graph.leafSinks != null && staleKeys == null && hasStaleSitePin(net, graph.leafSinks)) {
                // Neither is a site pin (even with a SitePinInst) where routing ends, if the net's site
                // routing from it reaches no placed cell: the routing to it is an antenna
                graph.analyze(net, aliases, isStatic, sitePinKeys[entry], getStaleSitePinKeys(net, null),
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
                    ? findUnroutedDriverPins(net, flat, graph, entry) : 0;
            if ((unroutedDrivers & DRIVER_PIN_CONFLICT) != 0) {
                s |= CONFLICTS;
            }
            boolean sinksReached = graph.reachedSinks == graph.sinks;
            if (!sinksReached && graph.undrivenSource && !graph.rooted && graph.reachedSinks + graph.islandSinks == graph.sinks
                    && flat != null && hasOnlyLoadsInDriverSite(flat)) {
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
         * Checks whether a net has a single placed driver, in an UltraScale(+) slice, and all its loads
         * are placed in the same site.
         */
        private boolean hasOnlyLoadsInDriverSite(RouteStatusNetlist.FlatNet flat) {
            Series series = design.getDevice().getSeries();
            if ((series != Series.UltraScale && series != Series.UltraScalePlus) || flat.leafDrivers != 1
                    || flat.unplacedLeafPins > 0 || flat.leafInouts > 0 || flat.hasTopPorts()) {
                return false;
            }
            SiteInst driverSite = null;
            List<RouteStatusNetlist.LeafPin> pins = flat.getLeafPins(design);
            for (RouteStatusNetlist.LeafPin pin : pins) {
                if (pin.isOutput()) {
                    driverSite = pin.cell.getSiteInst();
                }
            }
            if (driverSite == null || !Utils.isSLICE(driverSite)) {
                return false;
            }
            for (RouteStatusNetlist.LeafPin pin : pins) {
                if (pin.cell.getSiteInst() != driverSite) {
                    return false;
                }
            }
            return true;
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
            /** Per node: whether a routethru PIP (through a site) drives it; routing ending there is no antenna */
            final boolean[] routethruEnd;
            /** The start and end nodes of the routethru PIPs, or null if none */
            List<Node> routethruNodes;
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
            /** Whether an output site pin of the net was not taken as a source, as its site routing does not drive it */
            boolean undrivenSource;
            /** Whether any routing starts at a source of the net (or comes from another partition) */
            boolean rooted;
            /** The sinks that the routing leads to without being reached from a source (e.g. on islands) */
            int islandSinks;
            /** The nodes of the sinks where the routing ends (without stale site pins excluded), or null */
            List<Node> leafSinks;
            /**
             * Per node: whether more than one PIP (or the same PIP more than once) drives it. Vivado
             * gets such a node once more in the route tree, as a leaf: a conflict with itself.
             */
            boolean[] drivenAgain;

            RouteGraph(Net net) {
                List<PIP> pips = net.getPIPs();
                int n = pips.size();
                long[] startKeys = new long[n];
                long[] endKeys = new long[n];
                Node[] startNodes = new Node[n];
                Node[] endNodes = new Node[n];
                boolean[] isStub = new boolean[n];
                boolean[] isStartStub = new boolean[n];
                boolean[] isRoutethru = new boolean[n];
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
                    isStartStub[edges] = pip.isStartStub();
                    isRoutethru[edges] = pip.isRouteThru();
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
                // A START_STUB makes all the routing from its start (e.g. the other output pin of a net
                // driven through two site pins) a stub, not only the PIP it annotates
                boolean[] stubRoot = new boolean[distinct];
                for (int e = 0; e < edges; e++) {
                    if (isStartStub[e]) {
                        stubRoot[startIdx[e]] = true;
                    }
                }
                for (int e = 0; e < edges; e++) {
                    if (stubRoot[startIdx[e]]) {
                        isStub[e] = true;
                    }
                }
                nodeObjects = new Node[distinct];
                driven = new boolean[distinct];
                stubEnd = new boolean[distinct];
                routethruEnd = new boolean[distinct];
                hasForwardFanout = new boolean[distinct];
                fanoutStart = new int[distinct + 1];
                for (int e = 0; e < edges; e++) {
                    nodeObjects[startIdx[e]] = startNodes[e];
                    nodeObjects[endIdx[e]] = endNodes[e];
                    fanoutStart[startIdx[e] + 1]++;
                    hasForwardFanout[startIdx[e]] = true;
                    if (driven[endIdx[e]] && !isReversed[e]) {
                        if (drivenAgain == null) {
                            drivenAgain = new boolean[distinct];
                        }
                        drivenAgain[endIdx[e]] = true;
                    }
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
                    if (isRoutethru[e]) {
                        routethruEnd[endIdx[e]] = true;
                        if (routethruNodes == null) {
                            routethruNodes = new ArrayList<>();
                        }
                        routethruNodes.add(startNodes[e]);
                        routethruNodes.add(endNodes[e]);
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
                undrivenSource = false;
                islandSinks = 0;
                leafSinks = null;
                int distinct = nodes.length;
                boolean sourceUnrouted = false;
                boolean[] isSink = new boolean[distinct];
                boolean[] visited = new boolean[distinct];
                int[] queue = new int[distinct];
                // Per node: the node it was first reached from (or -1)
                int[] parent = new int[distinct];
                Arrays.fill(parent, -1);
                int head = 0;
                int tail = 0;
                // Sinks are the input site pins of the net, whether or not they have a SitePinInst
                // (those of unrouted sinks are not stored in a DCP), as found in its site routing
                List<SitePinInst> pins = net.getPins();
                if (aliases != null) {
                    pins = new ArrayList<>(pins);
                    for (Net alias : aliases) {
                        pins.addAll(storedPins(alias));
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
                            undrivenSource = true;
                            continue;
                        }
                        int i = indexOf(node);
                        if (i >= 0 && !visited[i]) {
                            visited[i] = true;
                            queue[tail++] = i;
                        } else if (i < 0 && !isStatic && spi == owner.getSource()) {
                            // The source pin is not routed (which only matters if no other output pin
                            // of the net is, see below)
                            sourceUnrouted = true;
                        }
                    } else if (node != null) {
                        sinkKeys[numSinks++] = nodeKey(node);
                    }
                }
                if (extra > 0) {
                    System.arraycopy(sitePinSinkKeys, 0, sinkKeys, numSinks, extra);
                    numSinks += extra;
                }
                boolean[] isStale = null;
                if (staleKeys != null) {
                    for (long key : staleKeys) {
                        int idx = index.get(key);
                        if (idx >= 0) {
                            if (isStale == null) {
                                isStale = new boolean[distinct];
                            }
                            isStale[idx] = true;
                        }
                    }
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
                    } else if (!isStatic && node.getSitePin() != null && !node.getSitePin().isInput()
                            && isSitePinUsedBy(net, node)) {
                        undrivenSource = true;
                    }
                }
                boolean rootedAtSource = tail > 0;
                if (sourceUnrouted && !rootedAtSource) {
                    // Vivado does not tell the source from the alternate source of a net (e.g. the xMUX
                    // and x_O outputs of a LUT, both site routed): the routing may start at either
                    unroutedSource = true;
                }
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
                rooted = tail > 0;
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
                        // (a 7-series global clock node only leading back where it was reached from,
                        // through a bidirectional PIP, leads nowhere)
                        boolean onlyBack = from < to && parent[i] >= 0 && nodeObjects[i].getIntentCode() == IntentCode.GLOBAL;
                        for (int f = from; f < to && onlyBack; f++) {
                            onlyBack = fanouts[f] == parent[i];
                        }
                        if (onlyBack || (!hasForwardFanout[i] && (from == to || (isStale != null && isStale[i])
                                || !(isSitePinUsedBy(net, nodeObjects[i]) || nodeObjects[i].getIntentCode().toString().contains("GLOBAL"))))) {
                            // (a site pin used by the site routing of the net, unless that leads to no
                            // placed cell, or global clock routing, reached backwards through a
                            // bidirectional PIP may lead on)
                            // Leads nowhere, unless to a load. The site pins that loads of static nets
                            // are tied to are not always in their site routing. Stubs are deliberately
                            // dangling, but only on nets with loads.
                            // Routing that ends where an explicit gap follows (to some pin) leads on
                            // and so does routing to another partition
                            if (!isSink[i] && !(stubEnd[i] && sinks > 0) && !(isStatic && isSitePinNode(nodeObjects[i]))
                                    && !gapMarkers && !(isPartitionPin != null && isPartitionPin[i])
                                    && !isLeafClockClearPin(nodeObjects[i]) && !routethruEnd[i]) {
                                antennas = true;
                            } else if (isSink[i] && !isStatic && from == to && staleKeys == null) {
                                if (leafSinks == null) {
                                    leafSinks = new ArrayList<>();
                                }
                                leafSinks.add(nodeObjects[i]);
                            }
                        }
                        for (int f = from; f < to; f++) {
                            int next = fanouts[f];
                            if (!visited[next]) {
                                visited[next] = true;
                                parent[next] = i;
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
                    if (drivenAgain != null && drivenAgain[i] && !gapMarkers && !isSink[i] && !(stubEnd[i] && sinks > 0)
                            && !(isStatic && isSitePinNode(nodeObjects[i]))) {
                        // A node driven again leads nowhere the second time, unless it is a load (as
                        // are the site pins that static nets tie, which need not be in their site routing)
                        antennas = true;
                    }
                    if (isSink[i] && (visited[i] || rooted)) {
                        // Reached by routing (even if that routing is an island, or merely starts
                        // there, unless no routing leaves the source at all)
                        reachedSinks++;
                    } else if (isSink[i]) {
                        islandSinks++;
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

        /**
         * Checks whether the site routing from a site wire (through used site PIPs of routing BELs)
         * reaches an input pin of a placed cell that is mapped to a logical pin. For a signal net, the
         * pad of a placed port cell is also such a pin, and BELs with a single input that always passes
         * it on (e.g. FF_CLK_MOD of Versal slices, whose site PIPs are not stored) are followed.
         * @param staticType For a static net, the logical pin must be tied to the static source
         * (others may be tied off inside the site).
         */
        private static boolean reachesPlacedCellPin(SiteInst si, String siteWire, NetType staticType) {
            if (staticType == NetType.GND && isVersalClockingSite(si)) {
                // Ground is tied off inside the clock management sites of Versal devices
                return false;
            }
            Set<String> visited = new HashSet<>();
            List<String> queue = new ArrayList<>();
            queue.add(siteWire);
            // (the site routing of a signal net only leads on through site wires of the same net)
            Net net = (staticType == null) ? si.getNetFromSiteWire(siteWire) : null;
            while (!queue.isEmpty()) {
                String sw = queue.remove(queue.size() - 1);
                if (!visited.add(sw) || (net != null && si.getNetFromSiteWire(sw) != net)) {
                    continue;
                }
                for (BELPin pin : si.getSiteWirePins(sw)) {
                    if (staticType == null && pin.isBidir() && !pin.isSitePort()) {
                        Cell cell = si.getCell(pin.getBEL());
                        if (cell != null && cell.isPortCell()) {
                            return true;
                        }
                    }
                    if (pin.isSitePort() || !pin.isInput()) {
                        continue;
                    }
                    Cell cell = si.getCell(pin.getBEL());
                    String logicalPin = (cell == null || cell.isRoutethru()) ? null : cell.getLogicalPinMapping(pin.getName());
                    if (logicalPin != null && (staticType == null || isTiedTo(cell, logicalPin, staticType))) {
                        return true;
                    }
                    if (staticType == NetType.VCC && logicalPin == null && (isFracturedLutTie(si, pin)
                            || (cell != null && cell.isRoutethru() && pin.getName().equals("A6") && pin.getBELName().endsWith("6LUT")))) {
                        // A6 of a LUT must be tied high when its 5LUT is used, or when it is a
                        // routethru (e.g. to a carry chain)
                        return true;
                    }
                    if (staticType == NetType.GND && logicalPin == null && cell != null
                            && pin.getName().equals("S0") && (pin.getBELName().startsWith("F7") || pin.getBELName().startsWith("F8"))) {
                        // The select of a wide function mux used without it (as a routethru, or after
                        // unplacing the driver of the select) is tied low
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
                    } else if ((cell != null && cell.isRoutethru())
                            || (staticType == null && cell == null && isPassThroughPin(pin))) {
                        // Through a routethru cell (or a pass-through BEL) to its output
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

        /** Checks whether a BEL pin is the only input of its BEL, with a site PIP through the BEL */
        private static boolean isPassThroughPin(BELPin pin) {
            if (pin.getSitePIPs().isEmpty()) {
                return false;
            }
            for (BELPin other : pin.getBEL().getPins()) {
                if (other != pin && other.isInput()) {
                    return false;
                }
            }
            return true;
        }

        private static boolean isVersalClockingSite(SiteInst si) {
            if (si.getSite().getTile().getDevice().getSeries() != Series.Versal) {
                return false;
            }
            String type = si.getSiteTypeEnum().name();
            return type.startsWith("MMCM") || type.startsWith("XPLL") || type.startsWith("DPLL");
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
         * Checks whether the site routing of a net may lead to an output pin of it of a slice. In
         * 7-series devices, unless another net's site routing leads to it, or that of the net leads to
         * another output pin of the slice (and not to this one); in UltraScale(+) devices, if the site
         * wire of the pin carries the net and is driven by it (see
         * {@link #isSiteWireDriven(SiteInst, Net, int, int)}).
         */
        private static boolean isDrivenBySiteRouting(Net net, SitePinInst spi) {
            SiteInst si = spi.getSiteInst();
            if (si == null || !si.getSiteTypeEnum().name().startsWith("SLICE")) {
                return true;
            }
            Series series = si.getDesign().getSeries();
            if (series == Series.UltraScale || series == Series.UltraScalePlus) {
                Integer siteWire = spi.getSiteWireIndex();
                return siteWire == null || siteWire < 0
                        || (si.getNetFromSiteWireIndex(siteWire) == net && isSiteWireDriven(si, net, siteWire, 0));
            }
            if (series != Series.Series7) {
                return true;
            }
            Net siteWireNet = si.getNetFromSiteWire(spi.getSiteWireName());
            if (siteWireNet != null) {
                // (driven by the site routing of another net, e.g. where a routethru of this net
                // starts at the output of another's LUT)
                return siteWireNet == net || siteWireNet.isStaticNet();
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

        /**
         * Checks whether any of the given input site pin nodes of a (signal) net in a slice has site
         * routing of the net that reaches no placed cell pin (see
         * {@link #reachesPlacedCellPin(SiteInst, String, NetType)}). (Other sites, e.g. of I/O, may
         * pass a pin on to a cell without storing the site routing to it.)
         */
        private static boolean hasStaleSitePin(Net net, List<Node> nodes) {
            Design design = net.getDesign();
            for (Node node : nodes) {
                SitePin sitePin = node.getSitePin();
                SiteInst si = (sitePin == null) ? null : design.getSiteInstFromSite(sitePin.getSite());
                BELPin port = (si == null || !Utils.isSLICE(si)) ? null : getSitePortPin(si, sitePin);
                if (port == null || !isNetSitePin(net, si, port)) {
                    continue;
                }
                boolean direct = false;
                for (BELPin pin : si.getSiteWirePins(port.getSiteWireIndex())) {
                    // (the common case: a placed cell's pin on the site wire of the site pin)
                    Cell cell = pin.isInput() && !pin.isSitePort() ? si.getCell(pin.getBEL()) : null;
                    if (cell != null && !cell.isRoutethru() && cell.getLogicalPinMapping(pin.getName()) != null) {
                        direct = true;
                        break;
                    }
                }
                if (!direct && !reachesPlacedCellPin(si, port.getSiteWireName(), null)) {
                    return true;
                }
            }
            return false;
        }

        /**
         * Checks whether an input site pin belongs to a signal net in its site: its site wire carries
         * the net, or (without any site wire assignment) the net has a SitePinInst on it.
         */
        private static boolean isNetSitePin(Net net, SiteInst si, BELPin port) {
            Net wireNet = si.getNetFromSiteWireIndex(port.getSiteWireIndex());
            if (wireNet != null) {
                return wireNet == net;
            }
            SitePinInst spi = si.getSitePinInst(port.getBELName());
            return spi != null && spi.getNet() == net;
        }

        /** Limit on the number of site PIPs and routethrus followed back from a site wire */
        private static final int MAX_SITE_ROUTING_DEPTH = 16;

        /**
         * Checks whether a site wire of an UltraScale(+) slice that carries a net is driven by the net:
         * from an output pin of a placed cell or from an input site pin, through site wires of the net,
         * the used site PIPs (e.g. of an OUTMUX, or of a LUT used as a routethru) and routethru cells.
         * Vivado reports the routing from an output site pin whose site wire is merely assigned to the
         * net (e.g. that of an xMUX output whose OUTMUX selects the output of a LUT that is not site
         * routed as the net) as an antenna, which does not drive the net's loads.
         */
        private static boolean isSiteWireDriven(SiteInst si, Net net, int siteWire, int depth) {
            if (depth > MAX_SITE_ROUTING_DEPTH) {
                return true;
            }
            for (BELPin pin : si.getSiteWirePins(siteWire)) {
                if (!pin.isOutput()) {
                    continue;
                }
                // The pin driving the site wire: the port BEL of an input site pin, or the output of a BEL
                if (pin.isSitePort()) {
                    return true;
                }
                BEL bel = pin.getBEL();
                SitePIP sitePIP = si.getUsedSitePIP(bel);
                if (sitePIP != null) {
                    BELPin input = sitePIP.getInputPin();
                    int inputWire = input.getSiteWireIndex();
                    return si.getNetFromSiteWireIndex(inputWire) == net && isSiteWireDriven(si, net, inputWire, depth + 1);
                }
                Cell cell = si.getCell(bel);
                if (cell == null) {
                    return false;
                }
                if (cell.isRoutethru()) {
                    for (String physPin : cell.getPinMappingsP2L().keySet()) {
                        BELPin input = bel.getPin(physPin);
                        if (input != null && input.isInput() && si.getNetFromSiteWireIndex(input.getSiteWireIndex()) == net) {
                            return isSiteWireDriven(si, net, input.getSiteWireIndex(), depth + 1);
                        }
                    }
                    return false;
                }
                return cell.getLogicalPinMapping(pin.getName()) != null;
            }
            return false;
        }

        /** Gets the port BEL pin of a site pin, named as in the type of the site instance, or null. */
        private static BELPin getSitePortPin(SiteInst si, SitePin sitePin) {
            String pinName = sitePin.getPinName();
            BEL bel = si.getBEL(pinName);
            if (bel == null) {
                pinName = si.getAlternateSitePinName(pinName);
                bel = (pinName == null) ? null : si.getBEL(pinName);
            }
            return (bel == null) ? null : bel.getPin(pinName);
        }

        /** Checks whether the node is that of a site pin used by the site routing of the net. */
        private static boolean isSitePinUsedBy(Net net, Node node) {
            SitePin sitePin = node.getSitePin();
            if (sitePin == null) {
                return false;
            }
            SiteInst si = net.getDesign().getSiteInstFromSite(sitePin.getSite());
            if (si == null) {
                return false;
            }
            BELPin belPin = getSitePortPin(si, sitePin);
            return belPin != null && si.getNetFromSiteWireIndex(belPin.getSiteWireIndex()) == net;
        }

        /** Checks whether the node is that of an output site pin driven by the site routing of the net. */
        private static boolean isSiteRoutedOutputPinNode(Net net, Node node) {
            SitePin sitePin = node.getSitePin();
            if (sitePin == null || sitePin.isInput() || !isSitePinUsedBy(net, node)) {
                return false;
            }
            Series series = net.getDesign().getSeries();
            if (series != Series.UltraScale && series != Series.UltraScalePlus) {
                return true;
            }
            SiteInst si = net.getDesign().getSiteInstFromSite(sitePin.getSite());
            if (!si.getSiteTypeEnum().name().startsWith("SLICE")) {
                return true;
            }
            return isSiteWireDriven(si, net, getSitePortPin(si, sitePin).getSiteWireIndex(), 0);
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

        /**
         * Checks whether a node is the CLR_B pin of a leaf clock divider, which (though not a pin of
         * any cell) is a load of the CLRB_LEAF pin of the clock buffer (e.g. MBUFGCE) whose leaves it
         * resets.
         */
        private static boolean isLeafClockClearPin(Node node) {
            if (node.getIntentCode() != IntentCode.NODE_PINFEED) {
                return false;
            }
            SitePin sitePin = node.getSitePin();
            if (sitePin == null || !sitePin.getPinName().equals("CLR_B")) {
                return false;
            }
            SiteTypeEnum type = sitePin.getSite().getSiteTypeEnum();
            return type == SiteTypeEnum.BUFDIV_LEAF || type == SiteTypeEnum.BUFDIV_LEAF_ULVT;
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

        /**
         * Gets the name of a site pin of a site instance in the primary type of its site. (A site type
         * may be the primary type of some sites and an alternate one of others, e.g. RAMB18E1 in
         * 7-series devices, which {@link SiteInst#getPrimarySitePinName(String)} does not tell apart.)
         */
        private static String getPrimarySitePinName(SiteInst si, String pinName) {
            return (si.getSiteTypeEnum() == si.getSite().getSiteTypeEnum()) ? pinName : si.getPrimarySitePinName(pinName);
        }

        /** Keyed by the site type and the primary site type of the site */
        private final Map<List<SiteTypeEnum>, InputSitePins> inputSitePins = new ConcurrentHashMap<>();

        private InputSitePins getInputSitePins(SiteInst si) {
            List<SiteTypeEnum> key = Arrays.asList(si.getSiteTypeEnum(), si.getSite().getSiteTypeEnum());
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
         * Per entry, the sorted keys of the nodes of the site pins that its placed pins use regardless
         * of its routing (null if none): the output site pins of drivers its routing does not start
         * from, and, for a net without a physical net, the input site pins of loads whose site wires
         * carry another net.
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
            driverPinKeys[entry] = sortedDistinct(array, array.length);
            return flags | DRIVER_PIN_UNROUTED;
        }

        /**
         * For each unrouted (or undriven) net without a physical net, adds the input site pins of its placed loads
         * whose site wires carry another net to {@link #driverPinKeys}: Vivado still considers them the
         * net's (from the placement), in conflict with the other net.
         */
        private void addClaimedLoadSitePins() {
            parallelFor(entryNet.size(), (i) -> {
                RouteStatusNetlist.FlatNet flat = entryFlat.get(i);
                boolean unrouted = has(status[i], UNROUTED) || (has(status[i], NODRIVER) && !has(status[i], HAS_ROUTING));
                if (entryNet.get(i) != null || flat == null || !unrouted || aliasNets.containsKey(i)) {
                    return;
                }
                List<Long> keys = new ArrayList<>();
                for (RouteStatusNetlist.LeafPin pin : flat.getLeafPins(design)) {
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
                    long[] merged = concat(driverPinKeys[i], array);
                    driverPinKeys[i] = sortedDistinct(merged, merged.length);
                    status[i] |= LOGICAL_ONLY;
                }
            });
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
        /** Per static entry: the keys of the site pins of its macro ties (see addStaticLoadSitePins) */
        private final Map<Integer, long[]> staticTieKeys = new HashMap<>();

        private void addSitePinNodes() {
            numRoutingKeys = new int[sitePinKeys.length];
            Arrays.fill(numRoutingKeys, -1);
            for (int i = 0; i < sitePinKeys.length; i++) {
                long[] extra = concat(sitePinKeys[i], driverPinKeys[i]);
                Net net = entryNet.get(i);
                if (net != null && net.isStaticNet()) {
                    extra = staticTieKeys.get(i);
                }
                if (extra == null) {
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
         * of an input site pin used by the site routing of the entry, or claimed by its placed pins (see
         * {@link #LOGICAL_ONLY}), 0 if used by its routing.
         * @param count The number of users.
         */
        static void markNodeUsers(int[] status, int[] users, int count) {
            boolean otherRouting = false;
            boolean staticClaims = false;
            for (int u = 0; u < count; u++) {
                boolean claim = (users[u] & 1) != 0 && has(status[users[u] >> 1], STATIC_CLAIMS);
                staticClaims |= claim;
                otherRouting |= (users[u] & 1) == 0 && !has(status[users[u] >> 1], STATIC_CLAIMS);
            }
            if (staticClaims && !otherRouting) {
                // The site pins of static nets only conflict with the routing of other (not static)
                // nets: leave their claims out
                int kept = 0;
                int[] others = new int[count];
                for (int u = 0; u < count; u++) {
                    if (!((users[u] & 1) != 0 && has(status[users[u] >> 1], STATIC_CLAIMS))) {
                        others[kept++] = users[u];
                    }
                }
                users = others;
                count = kept;
            }
            boolean shared = false;
            boolean hasPhysicalOwner = false;
            for (int u = 0; u < count; u++) {
                shared |= (users[u] >> 1) != (users[0] >> 1);
                hasPhysicalOwner |= (users[u] & 1) != 0 && !has(status[users[u] >> 1], LOGICAL_ONLY);
            }
            if (!shared) {
                return;
            }
            for (int u = 0; u < count; u++) {
                int e = users[u] >> 1;
                status[e] = (status[e] | CONFLICTS) & ~ROUTED;
                if ((users[u] & 1) != 0 && has(status[e], LOGICAL_ONLY)) {
                    // A site pin that only the placement of a net without a physical net claims: the
                    // net is not unrouted, but in conflict
                    status[e] &= ~UNROUTED;
                }
            }
            if (!hasPhysicalOwner) {
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
                    if ((users[o] & 1) != 0 && owner != e && has(status[owner], STATIC_CLAIMS)) {
                        // A site pin of a static net used by the routing of another net is unrouted
                        status[owner] = (status[owner] & ~UNROUTED) | PARTIAL;
                    } else if ((users[o] & 1) != 0 && owner != e && has(status[owner], UNROUTED)) {
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
                    } else if (has(s, ROUTED | ROUTED_UNLISTED)) {
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

    /**
     * A flattened view of a design's logical netlist, as needed by
     * {@link ReportRouteStatus#reportRouteStatus(Design, boolean)}: every flat (parent) net with
     * the counts of its drivers and loads, the placement of the leaf cells it connects to, and the top
     * level ports it connects to. Built in one top-down pass over the hierarchy (in parallel over
     * subtrees), without materializing hierarchical net objects.
     */
    static final class RouteStatusNetlist {

        /** How a flat net got its name, in increasing order of precedence */
        private static final int NAMED_BY_SEGMENT = 0;
        private static final int NAMED_BY_DANGLING_OUTPUT = 1;
        private static final int NAMED_BY_INOUT = 2;
        private static final int NAMED_BY_DANGLING_INOUT = 3;
        private static final int NAMED_BY_DRIVER = 4;

        /**
         * Tracks whether pins are placed in more than one site. Pins without a physical pin (e.g.
         * hardwired between sites) need no routing, so should not count; as checking that is costly,
         * the pins found in the first two sites are kept so that it can be checked later, if needed
         * (see {@link FlatNet#hasPinsInMultipleSites(Design)}).
         */
        static class SiteTracker {
            SiteInst firstSite;
            /** The cells and logical pins found in the first two sites (null cells for top level ports) */
            Cell firstCell;
            String firstPin;
            Cell secondCell;
            String secondPin;
            boolean multipleSites;

            void addPin(SiteInst si, Cell cell, String pin) {
                if (multipleSites || si == firstSite) {
                    return;
                }
                if (firstSite == null) {
                    firstSite = si;
                    firstCell = cell;
                    firstPin = pin;
                    return;
                }
                secondCell = cell;
                secondPin = pin;
                multipleSites = true;
            }

            void addAll(SiteTracker other) {
                if (other.multipleSites && !multipleSites) {
                    firstSite = other.firstSite;
                    firstCell = other.firstCell;
                    firstPin = other.firstPin;
                    secondCell = other.secondCell;
                    secondPin = other.secondPin;
                    multipleSites = true;
                } else if (other.firstSite != null) {
                    addPin(other.firstSite, other.firstCell, other.firstPin);
                }
            }

            void resetSites() {
                firstSite = null;
                firstCell = null;
                firstPin = null;
                secondCell = null;
                secondPin = null;
                multipleSites = false;
            }
        }

        /**
         * Gets the input site pin feeding a BEL input pin directly or through a routing BEL (e.g. an
         * optional inverter), or null.
         */
        static String getSitePinFeeding(SiteInst si, BELPin belPin) {
            for (BELPin p : si.getSiteWirePins(belPin.getSiteWireName())) {
                if (p.isSitePort() && p.isOutput()) {
                    return p.getName();
                }
                if (p.isOutput() && p.getBEL().getBELClass() == BELClass.RBEL) {
                    for (BELPin in : p.getBEL().getPins()) {
                        if (!in.isInput()) {
                            continue;
                        }
                        for (BELPin q : si.getSiteWirePins(in.getSiteWireName())) {
                            if (q.isSitePort() && q.isOutput()) {
                                return q.getName();
                            }
                        }
                    }
                }
            }
            return null;
        }

        static boolean hasPhysicalPin(Cell cell, String logicalPin) {
            if (cell == null) {
                // Top level port
                return true;
            }
            return !getPhysicalPins(cell, logicalPin).isEmpty();
        }

        /** Gets the physical pins a logical pin of a cell maps to (none if unmapped). */
        static Set<String> getPhysicalPins(Cell cell, String logicalPin) {
            Set<String> physPins;
            // The pin mappings of a cell are built lazily, which is not thread safe
            synchronized (cell) {
                physPins = cell.getAllPhysicalPinMappings(logicalPin);
            }
            return (physPins == null) ? Collections.emptySet() : physPins;
        }

        /** The names of all primitives */
        private static final Set<String> UNISIM_NAMES = new HashSet<>();
        static {
            for (Unisim u : Unisim.values()) {
                UNISIM_NAMES.add(u.name());
            }
        }

        /**
         * Checks whether a cell is a primitive: one of the primitives library, or (as Vivado also takes
         * it, e.g. in netlists written by Yosys) a leaf cell of another library named after a primitive.
         */
        static boolean isPrimitive(EDIFCell cell) {
            return cell.isPrimitive() || (cell.getCellInsts().isEmpty() && UNISIM_NAMES.contains(cell.getName()));
        }

        /**
         * Gets the segments (hierarchical nets) of the flat net a segment is part of, following the
         * current connectivity through the ports of hierarchical cells. Unlike
         * {@link EDIFNetlist#getPhysicalPins(EDIFHierNet)}, nothing is cached, and nets with no or
         * several drivers are fine.
         */
        static List<EDIFHierNet> getSegments(EDIFHierNet start) {
            List<EDIFHierNet> segments = new ArrayList<>();
            Deque<EDIFHierNet> queue = new ArrayDeque<>();
            Set<EDIFHierNet> visited = new HashSet<>();
            queue.add(start);
            while (!queue.isEmpty()) {
                EDIFHierNet hn = queue.poll();
                if (!visited.add(hn)) {
                    continue;
                }
                segments.add(hn);
                for (EDIFHierPortInst p : hn.getPortInsts()) {
                    EDIFCellInst eci = p.getPortInst().getCellInst();
                    if (eci == null) {
                        if (hn.getHierarchicalInst().isTopLevelInst()) {
                            // A top level port
                            continue;
                        }
                        EDIFHierPortInst up = p.getPortInParent();
                        if (up != null && up.getNet() != null) {
                            queue.add(up.getHierarchicalNet());
                        }
                    } else if (!eci.getCellType().isLeafCellOrBlackBox()) {
                        EDIFHierNet internal = p.getInternalNet();
                        if (internal != null) {
                            queue.add(internal);
                        }
                    }
                }
            }
            return segments;
        }

        /** Gets the pins on leaf cells (or black boxes) of a segment. */
        static List<EDIFHierPortInst> getLeafPortInsts(EDIFHierNet segment) {
            List<EDIFHierPortInst> pins = new ArrayList<>();
            for (EDIFHierPortInst p : segment.getPortInsts()) {
                EDIFCellInst eci = p.getPortInst().getCellInst();
                if (eci != null && eci.getCellType().isLeafCellOrBlackBox()) {
                    pins.add(p);
                }
            }
            return pins;
        }

        /**
         * Gets the pins on leaf cells (or black boxes) of the flat net a segment is part of, from the
         * current connectivity (see {@link #getSegments(EDIFHierNet)}).
         */
        static List<EDIFHierPortInst> getFlatLeafPortInsts(EDIFHierNet segment) {
            List<EDIFHierPortInst> pins = new ArrayList<>();
            for (EDIFHierNet hn : getSegments(segment)) {
                pins.addAll(getLeafPortInsts(hn));
            }
            return pins;
        }

        /** A pin on a leaf cell, with its cell (null if there is none). */
        static class LeafPin {
            final EDIFHierPortInst pin;
            final Cell cell;

            LeafPin(EDIFHierPortInst pin, Cell cell) {
                this.pin = pin;
                this.cell = cell;
            }

            boolean isOutput() {
                return pin.isOutput();
            }

            boolean isPlaced() {
                return cell != null && cell.isPlaced();
            }

            String getName() {
                return pin.getPortInst().getName();
            }

            Set<String> getPhysicalPins() {
                return RouteStatusNetlist.getPhysicalPins(cell, getName());
            }

            boolean hasPhysicalPin() {
                return !getPhysicalPins().isEmpty();
            }
        }

        /** The connections of a net (segment, or flat net): its pins by kind, and their sites. */
        static class Connections extends SiteTracker {
            int leafDrivers;
            int leafLoads;
            int leafInouts;
            int unplacedLeafPins;
            int unplacedLeafDrivers;
            int placedPortDrivers;
            int placedPortLoads;
            int unplacedPortDrivers;
            int unplacedPortLoads;
            /** Output pins of black boxes, which are not counted as pins */
            int blackBoxDrivers;
            /** Input (or inout) pins of black boxes, which are not counted as pins either */
            int blackBoxLoads;
            /** Inout pins of black boxes (also counted in {@link #blackBoxLoads}), which may drive too */
            int blackBoxInouts;
            /** Top level ports with partition pins */
            int partitionPinPorts;
            /** GND or VCC if driven by a static source primitive */
            NetType staticType;

            /** Adds the connections of another net (segment). */
            void addConnections(Connections other) {
                leafDrivers += other.leafDrivers;
                leafLoads += other.leafLoads;
                leafInouts += other.leafInouts;
                unplacedLeafPins += other.unplacedLeafPins;
                unplacedLeafDrivers += other.unplacedLeafDrivers;
                addAll(other);
                placedPortDrivers += other.placedPortDrivers;
                placedPortLoads += other.placedPortLoads;
                unplacedPortDrivers += other.unplacedPortDrivers;
                unplacedPortLoads += other.unplacedPortLoads;
                blackBoxDrivers += other.blackBoxDrivers;
                blackBoxLoads += other.blackBoxLoads;
                blackBoxInouts += other.blackBoxInouts;
                partitionPinPorts += other.partitionPinPorts;
                if (staticType == null) {
                    staticType = other.staticType;
                }
            }

            /** Removes all connections. */
            void resetConnections() {
                leafDrivers = 0;
                leafLoads = 0;
                leafInouts = 0;
                unplacedLeafPins = 0;
                unplacedLeafDrivers = 0;
                resetSites();
                placedPortDrivers = 0;
                placedPortLoads = 0;
                unplacedPortDrivers = 0;
                unplacedPortLoads = 0;
                blackBoxDrivers = 0;
                blackBoxLoads = 0;
                blackBoxInouts = 0;
                partitionPinPorts = 0;
                staticType = null;
            }
        }

        /** A flat (parent) net, i.e. a set of electrically connected net segments. */
        static class FlatNet extends Connections {
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
            synchronized void add(Tally t, String prefix, EDIFNet net) {
                addUnsynchronized(t, prefix, net);
            }

            /** Adds the tallies of one net segment, for a flat net not yet shared with other tasks. */
            void addUnsynchronized(Tally t, String prefix, EDIFNet net) {
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
                    String type = si.getSiteTypeEnum().name();
                    if (si == driverSite || type.startsWith("SLICE") || type.startsWith("BUF") || ignoredSites.contains(si)) {
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
                            if (up.getTile().getTileTypeEnum().name().startsWith("INT")) {
                                return true;
                            }
                            SitePin sitePin = up.getSitePin();
                            if (sitePin == null || sitePin.getSite().getSiteTypeEnum().name().startsWith("IRI")) {
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

            /** Gets the pins on leaf cells of all the segments of this flat net, with their cells. */
            List<LeafPin> getLeafPins(Design design) {
                List<LeafPin> pins = new ArrayList<>();
                for (EDIFHierNet hn : getSegments(design)) {
                    for (EDIFHierPortInst p : getLeafPortInsts(hn)) {
                        pins.add(new LeafPin(p, p.getPhysicalCell(design)));
                    }
                }
                return pins;
            }

            boolean hasTopPorts() {
                return placedPortDrivers + placedPortLoads + unplacedPortDrivers + unplacedPortLoads > 0;
            }
        }

        /** The connections of one net segment, tallied before being added to its flat net. */
        private static class Tally extends Connections {
            int namedBy;
            /** Whether fed by an output port of a child that has nothing but the port inside */
            boolean portOnlyOutput;
            /** Whether connected to nothing but an output port of its cell */
            boolean ownPortOnlyOutput;
            /** Whether fed by an output port of a child, but not connected to an output port of its cell */
            boolean childOutputOnly;
            /** Whether inside a macro primitive */
            boolean insideMacro;

        }

        /** Connection kinds of a port instance on a net, as precomputed per cell definition */
        private static final byte OWN_PORT = 0;
        private static final byte LEAF_OUTPUT = 1;
        private static final byte LEAF_INPUT = 2;
        private static final byte LEAF_INOUT = 3;
        private static final byte HIER = 4;
        /** Pins of black boxes (leaf cells that are not primitives) are not counted */
        private static final byte BLACK_BOX = 5;
        private static final byte BLACK_BOX_OUTPUT = 6;
        private static final byte BLACK_BOX_INOUT = 7;

        /** Flags of a port instance on a net, combined with its connection kind */
        private static final byte KIND_MASK = 0x0f;
        /** A hierarchical output port that dangles inside the child */
        private static final byte DANGLING = 0x10;
        /** A hierarchical output port that only has a net inside the child */
        private static final byte PORT_ONLY = 0x20;
        /** A hierarchical inout port that dangles inside the child */
        private static final byte DANGLING_INOUT = 0x40;
        /** A hierarchical output port */
        private static final byte CHILD_OUTPUT = (byte) 0x80;

        /** The connectivity of a (non-leaf) cell definition, precomputed once for all its instances. */
        private static class CellInfo {
            final int numSlots;
            final EDIFNet[] nets;
            /** Per net: the hash code of its name, and 31^(length of its name) */
            final int[] nameHashes;
            final int[] namePows;
            /**
             * Per net: whether it is not a net, but a placeholder declaring the range of a bus (see
             * isBusRangeDeclaration()), or a net without connections that is not the bit of a bus
             */
            final boolean[] notNets;
            /** Per net: the range of its port instances in the arrays below */
            final int[] start;
            /** Per port instance: connection kind and flags, child index, and port slot */
            final byte[] kinds;
            final int[] childIdx;
            final int[] slots;
            /** Per port instance: the name of the pin of a leaf cell (null otherwise) */
            final String[] leafPinNames;
            /** Per net, the static source type driving it from within this cell, if any */
            final NetType[] staticTypes;
            final EDIFCellInst[] children;
            final boolean[] childIsLeaf;
            /** Total number of instances in this cell's subtree */
            long leafCount;
            /** True if the cell is a macro primitive, or inside one */
            boolean insideMacro;
            boolean macroVisited;
            /** Per child: whether it only weakly drives its nets (e.g. a pull-up), so does not name them */
            final boolean[] childIsWeakDriver;
            /** Per child: whether it is a black box (a leaf cell that is not a primitive) */
            final boolean[] childIsBlackBox;

            /** Per port slot: whether a net inside connects to it, and whether that net connects to nothing else */
            final boolean[] ownConnected;
            final boolean[] ownPortOnly;
            /** Per port slot: whether it is (part of) an output port */
            final boolean[] ownOutputs;
            private final Map<EDIFPort, Integer>[] childOffsets;
            private final boolean[][] childConnected;
            private final boolean[][] childPortOnly;

            @SuppressWarnings("unchecked")
            CellInfo(EDIFCell cell, RouteStatusNetlist netlist) {
                Map<EDIFPort, Integer> offsets = netlist.getPortOffsets(cell);
                numSlots = offsets.get(null);
                ownConnected = new boolean[numSlots];
                ownPortOnly = new boolean[numSlots];
                ownOutputs = new boolean[numSlots];
                for (EDIFPort port : cell.getPorts()) {
                    if (port.isOutput()) {
                        int offset = offsets.get(port);
                        Arrays.fill(ownOutputs, offset, offset + Math.max(1, port.getWidth()), true);
                    }
                }
                boolean parallel = ForkJoinTask.inForkJoinPool();
                children = toArray(cell.getCellInsts(), new EDIFCellInst[0], parallel);
                childIsLeaf = new boolean[children.length];
                Map<EDIFCellInst, Integer> childIndex = new IdentityHashMap<>(children.length);
                // Per hierarchical child: the port slots of its cell, and which are connected inside
                childOffsets = new Map[children.length];
                childConnected = new boolean[children.length][];
                childPortOnly = new boolean[children.length][];
                childIsWeakDriver = new boolean[children.length];
                childIsBlackBox = new boolean[children.length];
                for (int i = 0; i < children.length; i++) {
                    childIndex.put(children[i], i);
                    EDIFCell type = children[i].getCellType();
                    childIsLeaf[i] = type.isLeafCellOrBlackBox();
                    String typeName = type.getName();
                    childIsWeakDriver[i] = childIsLeaf[i] && (typeName.equals("PULLUP") || typeName.equals("PULLDOWN")
                            || typeName.equals("KEEPER"));
                    childIsBlackBox[i] = childIsLeaf[i] && !isPrimitive(type);
                    if (!childIsLeaf[i]) {
                        childOffsets[i] = netlist.getPortOffsets(type);
                        CellInfo childInfo = netlist.getCellInfo(type);
                        childConnected[i] = childInfo.ownConnected;
                        childPortOnly[i] = childInfo.ownPortOnly;
                    }
                }
                nets = toArray(cell.getNets(), new EDIFNet[0], parallel);
                nameHashes = new int[nets.length];
                namePows = new int[nets.length];
                notNets = new boolean[nets.length];
                start = new int[nets.length + 1];
                staticTypes = new NetType[nets.length];
                // Each chunk of nets is tallied into its own arrays, then they are concatenated
                int numChunks = Math.max(1, (nets.length + CHUNK - 1) / CHUNK);
                Chunk[] chunks = new Chunk[numChunks];
                if (numChunks > 4 && parallel) {
                    List<RecursiveAction> tasks = new ArrayList<>();
                    for (int c = 0; c < numChunks; c++) {
                        final int i = c;
                        tasks.add(new RecursiveAction() {
                            @Override
                            protected void compute() {
                                chunks[i] = initNets(i * CHUNK, Math.min(nets.length, (i + 1) * CHUNK), offsets, childIndex);
                            }
                        });
                    }
                    ForkJoinTask.invokeAll(tasks);
                } else {
                    for (int c = 0; c < numChunks; c++) {
                        chunks[c] = initNets(c * CHUNK, Math.min(nets.length, (c + 1) * CHUNK), offsets, childIndex);
                    }
                }
                int total = 0;
                for (Chunk chunk : chunks) {
                    total += chunk.size;
                }
                kinds = new byte[total];
                childIdx = new int[total];
                slots = new int[total];
                leafPinNames = new String[total];
                int offset = 0;
                for (int c = 0; c < numChunks; c++) {
                    Chunk chunk = chunks[c];
                    System.arraycopy(chunk.kinds, 0, kinds, offset, chunk.size);
                    System.arraycopy(chunk.childIdx, 0, childIdx, offset, chunk.size);
                    System.arraycopy(chunk.slots, 0, slots, offset, chunk.size);
                    System.arraycopy(chunk.leafPinNames, 0, leafPinNames, offset, chunk.size);
                    for (int n = c * CHUNK, end = Math.min(nets.length, (c + 1) * CHUNK); n < end; n++) {
                        start[n + 1] += offset;
                    }
                    offset += chunk.size;
                }
            }

            /** The port instances of a range of nets, before concatenation. */
            private static class Chunk {
                int size;
                byte[] kinds;
                int[] childIdx;
                int[] slots;
                String[] leafPinNames;

                Chunk(int capacity) {
                    kinds = new byte[capacity];
                    childIdx = new int[capacity];
                    slots = new int[capacity];
                    leafPinNames = new String[capacity];
                }

                void ensureCapacity(int capacity) {
                    if (capacity > kinds.length) {
                        int newCapacity = Math.max(capacity, kinds.length * 2);
                        kinds = Arrays.copyOf(kinds, newCapacity);
                        childIdx = Arrays.copyOf(childIdx, newCapacity);
                        slots = Arrays.copyOf(slots, newCapacity);
                        leafPinNames = Arrays.copyOf(leafPinNames, newCapacity);
                    }
                }
            }

            /**
             * Tallies the port instances of nets [from, to). Sets start[n + 1] to the end of net n's
             * port instances within the chunk (offset by the chunk's start later).
             */
            private Chunk initNets(int from, int to, Map<EDIFPort, Integer> offsets, Map<EDIFCellInst, Integer> childIndex) {
                Chunk chunk = new Chunk(Math.max(16, (to - from) * 4));
                for (int n = from; n < to; n++) {
                    String name = nets[n].getName();
                    nameHashes[n] = name.hashCode();
                    namePows[n] = pow31(name.length());
                    List<EDIFPortInst> epis = nets[n].getPortInsts();
                    int count = epis.size();
                    notNets[n] = count == 0 && (isBusRangeDeclaration(name) || !isBusBit(name));
                    int base = chunk.size;
                    chunk.ensureCapacity(base + count);
                    byte[] kinds = chunk.kinds;
                    int[] childIdx = chunk.childIdx;
                    int[] slots = chunk.slots;
                    for (int p = 0; p < count; p++) {
                        int i = base + p;
                        EDIFPortInst epi = epis.get(p);
                        EDIFCellInst eci = epi.getCellInst();
                        if (eci == null) {
                            kinds[i] = OWN_PORT;
                            slots[i] = slot(offsets, epi);
                            // (concurrent writes of the same value are harmless)
                            ownConnected[slots[i]] = true;
                            if (count == 1) {
                                ownPortOnly[slots[i]] = true;
                            }
                            continue;
                        }
                        int c = childIndex.get(eci);
                        childIdx[i] = c;
                        if (childIsBlackBox[c]) {
                            kinds[i] = epi.isOutput() ? BLACK_BOX_OUTPUT : epi.isInput() ? BLACK_BOX : BLACK_BOX_INOUT;
                        } else if (childIsLeaf[c]) {
                            chunk.leafPinNames[i] = epi.getName();
                            if (epi.isOutput()) {
                                kinds[i] = LEAF_OUTPUT;
                                EDIFCell type = eci.getCellType();
                                String typeName = type.getName();
                                if ((typeName.equals("GND") || typeName.equals("VCC")) && isPrimitive(type)) {
                                    staticTypes[n] = typeName.equals("GND") ? NetType.GND : NetType.VCC;
                                }
                            } else {
                                kinds[i] = epi.isInput() ? LEAF_INPUT : LEAF_INOUT;
                            }
                        } else {
                            int slot = slot(childOffsets[c], epi);
                            slots[i] = slot;
                            byte kind = HIER;
                            if (epi.isOutput()) {
                                kind |= CHILD_OUTPUT;
                                if (!childConnected[c][slot]) {
                                    kind |= DANGLING;
                                }
                                if (childPortOnly[c][slot]) {
                                    kind |= PORT_ONLY;
                                }
                            } else if (!epi.isInput() && !childConnected[c][slot]) {
                                kind |= DANGLING_INOUT;
                            }
                            kinds[i] = kind;
                        }
                    }
                    chunk.size = base + count;
                    start[n + 1] = chunk.size;
                }
                return chunk;
            }
        }

        /** Copies a (large) collection into an array, in parallel if in a fork join pool. */
        private static <T> T[] toArray(Collection<T> collection, T[] empty, boolean parallel) {
            if (parallel && collection.size() > 4 * CHUNK) {
                return collection.parallelStream().toArray((n) -> Arrays.copyOf(empty, n));
            }
            return collection.toArray(empty);
        }

        private final Design design;
        /** Flat nets created, in one list per task to avoid contention */
        private final Queue<List<FlatNet>> flatNets = new ConcurrentLinkedQueue<>();
        private final Queue<FlatNet[]> pendingMerges = new ConcurrentLinkedQueue<>();
        private final Map<EDIFCell, Map<EDIFPort, Integer>> portOffsets = new ConcurrentHashMap<>();
        private final Map<EDIFCell, CellInfo> cellInfos = new ConcurrentHashMap<>();

        /** Subtrees with fewer instances than this are visited by the thread visiting the parent */
        private static final long MIN_PARALLEL_INSTANCES = 4096;
        /** Cells with more nets or children than this have them visited in parallel chunks */
        private static final int CHUNK = 8192;

        /** Hash codes of the names of interest (physical net names), or null */
        private final RouteStatusEngine.IntHashSet namesOfInterest;
        /** Full names of the black box instances */
        private final Set<String> blackBoxes = ConcurrentHashMap.newKeySet();

        /** Checks whether a hierarchical name is inside a black box instance. */
        boolean isInsideBlackBox(String name) {
            if (blackBoxes.isEmpty()) {
                return false;
            }
            for (int i = name.indexOf('/'); i >= 0; i = name.indexOf('/', i + 1)) {
                if (blackBoxes.contains(name.substring(0, i))) {
                    return true;
                }
            }
            return false;
        }

        /** Net segments whose full names have the hash code of a name of interest (in one list per task) */
        private final Queue<List<Segment>> segmentsOfInterest = new ConcurrentLinkedQueue<>();
        /** Open addressing table of the segments of interest by hash code */
        private Segment[] segmentTable;

        static class Segment {
            final int hash;
            final String prefix;
            final EDIFNet net;
            final FlatNet flat;

            Segment(int hash, String prefix, EDIFNet net, FlatNet flat) {
                this.hash = hash;
                this.prefix = prefix;
                this.net = net;
                this.flat = flat;
            }

            /** Checks whether this is the segment naming the given flat net. */
            boolean names(FlatNet flat) {
                return flat.net == net && flat.prefix.equals(prefix);
            }
        }

        RouteStatusNetlist(Design design) {
            this(design, null, false);
        }

        /**
         * @param namesOfInterest Hash codes of names (e.g. of physical nets) to be looked up with
         * {@link #getSegment(String)}, or null.
         * @param countUnconnected Whether to only count the nets without any connection.
         */
        RouteStatusNetlist(Design design, RouteStatusEngine.IntHashSet namesOfInterest, boolean countUnconnected) {
            this.design = design;
            this.namesOfInterest = namesOfInterest;
            this.countUnconnected = countUnconnected;
            for (PartitionPin pp : design.getPartitionPins()) {
                if (pp.isPort() && pp.getTerminalName() != null) {
                    partitionPinPorts.add(pp.getTerminalName());
                }
            }
            build();
        }

        /** Names of the (bits of) top level ports with partition pins */
        private final Set<String> partitionPinPorts = new HashSet<>();

        /**
         * If true, nets without any connection (which are flat nets of their own, internal to a site)
         * are only counted (see {@link #getUnconnectedNets()}), unless their names are of interest.
         */
        private final boolean countUnconnected;
        private final LongAdder unconnectedNets = new LongAdder();

        /** Gets the number of nets without any connection that were only counted. */
        long getUnconnectedNets() {
            return unconnectedNets.sum();
        }

        /** Prepares {@link #getSegment(String)} (which is then thread safe). */
        void prepareSegmentLookup() {
            if (segmentTable != null) {
                return;
            }
            List<Segment> segs = new ArrayList<>();
            for (List<Segment> list : segmentsOfInterest) {
                segs.addAll(list);
            }
            int capacity = Integer.highestOneBit(Math.max(4, segs.size() * 2) - 1) << 1;
            Segment[] table = new Segment[capacity];
            for (Segment seg : segs) {
                int i = RouteStatusEngine.IntHashSet.spread(seg.hash) & (capacity - 1);
                while (table[i] != null) {
                    i = (i + 1) & (capacity - 1);
                }
                table[i] = seg;
            }
            segmentTable = table;
        }

        /** Gets the net segment with the given full hierarchical name, if its name was of interest. */
        Segment getSegment(String name) {
            prepareSegmentLookup();
            int h = name.hashCode();
            int mask = segmentTable.length - 1;
            Segment seg;
            for (int i = RouteStatusEngine.IntHashSet.spread(h) & mask; (seg = segmentTable[i]) != null; i = (i + 1) & mask) {
                if (seg.hash != h) {
                    continue;
                }
                String netName = seg.net.getName();
                if (name.length() == seg.prefix.length() + netName.length() && name.startsWith(seg.prefix)
                        && name.endsWith(netName)) {
                    return seg;
                }
            }
            return null;
        }

        /**
         * Gets the flat nets, in lists (as created by the tasks building them), including those
         * merged into others (see {@link FlatNet#mergedInto}).
         */
        List<List<FlatNet>> getFlatNetLists() {
            return new ArrayList<>(flatNets);
        }

        private Map<EDIFPort, Integer> getPortOffsets(EDIFCell cell) {
            Map<EDIFPort, Integer> offsets = portOffsets.get(cell);
            if (offsets != null) {
                return offsets;
            }
            offsets = new IdentityHashMap<>();
            int offset = 0;
            for (EDIFPort port : cell.getPorts()) {
                offsets.put(port, offset);
                offset += Math.max(1, port.getWidth());
            }
            offsets.put(null, offset);
            portOffsets.put(cell, offsets);
            return offsets;
        }

        private CellInfo getCellInfo(EDIFCell cell) {
            CellInfo info = cellInfos.get(cell);
            if (info == null) {
                info = new CellInfo(cell, this);
                cellInfos.put(cell, info);
            }
            return info;
        }

        /** Computes the size of the subtree of every cell definition used, bottom up. */
        private long computeInstanceCount(CellInfo info) {
            if (info.leafCount > 0) {
                return info.leafCount;
            }
            long count = 1;
            for (int c = 0; c < info.children.length; c++) {
                count += info.childIsLeaf[c] ? 1 : computeInstanceCount(getCellInfo(info.children[c].getCellType()));
            }
            info.leafCount = count;
            return count;
        }

        /** Marks the definitions of macro primitives (expanded by RapidWright) and of cells inside them. */
        private void markMacros(CellInfo info, boolean insideMacro) {
            if (info.insideMacro || (!insideMacro && info.macroVisited)) {
                return;
            }
            info.insideMacro = insideMacro;
            info.macroVisited = true;
            for (int c = 0; c < info.children.length; c++) {
                if (info.childIsLeaf[c]) {
                    continue;
                }
                EDIFCell type = info.children[c].getCellType();
                boolean macro = insideMacro || type.getLibrary().getName().equals(EDIFTools.EDIF_LIBRARY_HDI_PRIMITIVES_NAME);
                markMacros(getCellInfo(type), macro);
            }
        }

        private static final int[] POW31 = new int[1024];

        static {
            POW31[0] = 1;
            for (int i = 1; i < POW31.length; i++) {
                POW31[i] = POW31[i - 1] * 31;
            }
        }

        /** Gets 31^n (with int overflow), to combine String hash codes of concatenated parts. */
        static int pow31(int n) {
            if (n < POW31.length) {
                return POW31[n];
            }
            int result = 1;
            int base = 31;
            while (n > 0) {
                if ((n & 1) != 0) {
                    result *= base;
                }
                base *= base;
                n >>= 1;
            }
            return result;
        }

        /** Checks whether the name is that of a bit of a bus, e.g. "data[7]". */
        static boolean isBusBit(String name) {
            int len = name.length();
            if (len < 4 || name.charAt(len - 1) != ']') {
                return false;
            }
            int i = len - 2;
            while (i > 0 && name.charAt(i) >= '0' && name.charAt(i) <= '9') {
                i--;
            }
            return i < len - 2 && name.charAt(i) == '[';
        }

        /** Checks whether the name starts with a bus range, e.g. "[7:0]data". */
        static boolean isBusRangeDeclaration(String name) {
            if (name.isEmpty() || name.charAt(0) != '[') {
                return false;
            }
            int colon = name.indexOf(':');
            int close = name.indexOf(']');
            if (colon < 2 || close < colon + 2) {
                return false;
            }
            for (int i = 1; i < close; i++) {
                char c = name.charAt(i);
                if (i != colon && (c < '0' || c > '9')) {
                    return false;
                }
            }
            return true;
        }

        private static int slot(Map<EDIFPort, Integer> offsets, EDIFPortInst epi) {
            return offsets.get(epi.getPort()) + Math.max(0, epi.getIndex());
        }

        /**
         * The cells of the design by the hash codes of their names, to look up leaf cells without
         * building their full names.
         */
        private static class CellIndex {
            private final int[] hashes;
            private final Cell[] cells;
            private final int mask;

            CellIndex(Collection<Cell> all) {
                int capacity = Integer.highestOneBit(Math.max(4, all.size() * 2) - 1) << 1;
                hashes = new int[capacity];
                cells = new Cell[capacity];
                mask = capacity - 1;
                for (Cell cell : all) {
                    int h = cell.getName().hashCode();
                    int i = RouteStatusEngine.IntHashSet.spread(h) & mask;
                    while (cells[i] != null) {
                        i = (i + 1) & mask;
                    }
                    hashes[i] = h;
                    cells[i] = cell;
                }
            }

            /** Gets the cell named prefix + name, given the hash code of the prefix. */
            Cell get(String prefix, int prefixHash, String name) {
                int h = prefixHash * pow31(name.length()) + name.hashCode();
                for (int i = RouteStatusEngine.IntHashSet.spread(h) & mask; cells[i] != null; i = (i + 1) & mask) {
                    if (hashes[i] != h) {
                        continue;
                    }
                    String cellName = cells[i].getName();
                    if (cellName.length() == prefix.length() + name.length() && cellName.startsWith(prefix)
                            && cellName.endsWith(name)) {
                        return cells[i];
                    }
                }
                return null;
            }
        }

        private CellIndex cellIndex;

        private void build() {
            cellIndex = new CellIndex(design.getCells());
            EDIFCell top = design.getNetlist().getTopCell();
            if (ParallelismTools.getParallel()) {
                // Precompute the connectivity of all hierarchical cell definitions in parallel
                List<EDIFCell> cells = new ArrayList<>();
                for (EDIFLibrary lib : design.getNetlist().getLibraries()) {
                    for (EDIFCell cell : lib.getCells()) {
                        if (!cell.isLeafCellOrBlackBox()) {
                            cells.add(cell);
                        }
                    }
                }
                ForkJoinPool.commonPool().submit(() -> cells.parallelStream().forEach(this::getCellInfo)).join();
            }
            CellInfo topInfo = getCellInfo(top);
            computeInstanceCount(topInfo);
            markMacros(topInfo, false);
            VisitTask root = new VisitTask(topInfo, "", 0, null);
            if (ParallelismTools.getParallel()) {
                ForkJoinPool.commonPool().invoke(root);
            } else {
                root.compute();
            }
            // Merge flat nets connected by feedthroughs (a net connecting two ports of its cell)
            for (FlatNet[] merge : pendingMerges) {
                FlatNet a = merge[0].find();
                FlatNet b = merge[1].find();
                if (a != b) {
                    a.absorb(b);
                }
            }
            List<List<FlatNet>> lists = getFlatNetLists();
            Consumer<List<FlatNet>> finish = (nets) -> {
                for (FlatNet net : nets) {
                    if (net.mergedInto == null) {
                        net.finishNaming();
                    }
                }
            };
            if (ParallelismTools.getParallel()) {
                ForkJoinPool.commonPool().submit(() -> lists.parallelStream().forEach(finish)).join();
            } else {
                lists.forEach(finish);
            }
        }

        private interface RangeTask {
            void run(int from, int to);
        }

        /** Visits one hierarchical instance, with the flat nets bound to its cell's ports. */
        private class VisitTask extends RecursiveAction {
            final CellInfo info;
            final String prefix;
            /** The hash code of the prefix, computed from the parent's (as String.hashCode() would) */
            final int prefixHash;
            final FlatNet[] portBinding;

            VisitTask(CellInfo info, String prefix, int prefixHash, FlatNet[] portBinding) {
                this.info = info;
                this.prefix = prefix;
                this.prefixHash = prefixHash;
                this.portBinding = portBinding;
            }

            @Override
            protected void compute() {
                // Look up the placement of leaf cells and prepare the port bindings of hierarchical children
                int numChildren = info.children.length;
                SiteInst[] leafSites = new SiteInst[numChildren];
                Cell[] leafCells = new Cell[numChildren];
                FlatNet[][] childBindings = new FlatNet[numChildren][];
                forRange(numChildren, (from, to) -> {
                    for (int c = from; c < to; c++) {
                        EDIFCellInst eci = info.children[c];
                        if (info.childIsBlackBox[c]) {
                            blackBoxes.add(prefix + eci.getName());
                        } else if (info.childIsLeaf[c]) {
                            Cell cell = cellIndex.get(prefix, prefixHash, eci.getName());
                            if (cell != null && cell.isPlaced()) {
                                leafSites[c] = cell.getSiteInst();
                                leafCells[c] = cell;
                            }
                        } else {
                            childBindings[c] = new FlatNet[getCellInfo(eci.getCellType()).numSlots];
                        }
                    }
                });

                forRange(info.nets.length, (from, to) -> visitNets(from, to, leafSites, leafCells, childBindings));

                List<VisitTask> forked = null;
                for (int c = 0; c < numChildren; c++) {
                    if (childBindings[c] == null) {
                        continue;
                    }
                    EDIFCellInst eci = info.children[c];
                    CellInfo childInfo = getCellInfo(eci.getCellType());
                    String name = eci.getName();
                    int childPrefixHash = (prefixHash * pow31(name.length()) + name.hashCode()) * 31 + '/';
                    VisitTask task = new VisitTask(childInfo, prefix + name + "/", childPrefixHash, childBindings[c]);
                    if (childInfo.leafCount >= MIN_PARALLEL_INSTANCES && getPool() != null) {
                        if (forked == null) {
                            forked = new ArrayList<>();
                        }
                        forked.add(task);
                    } else {
                        task.compute();
                    }
                }
                if (forked != null) {
                    invokeAll(forked);
                }
            }

            /** Runs the task over [0, n), in parallel chunks if n is large and running in a pool. */
            private void forRange(int n, RangeTask task) {
                if (n <= 2 * CHUNK || getPool() == null) {
                    task.run(0, n);
                    return;
                }
                List<RecursiveAction> chunks = new ArrayList<>();
                for (int from = 0; from < n; from += CHUNK) {
                    final int f = from;
                    final int to = Math.min(n, from + CHUNK);
                    chunks.add(new RecursiveAction() {
                        @Override
                        protected void compute() {
                            task.run(f, to);
                        }
                    });
                }
                invokeAll(chunks);
            }

            private void visitNets(int from, int to, SiteInst[] leafSites, Cell[] leafCells,
                                   FlatNet[][] childBindings) {
                boolean isTop = portBinding == null;
                byte[] kinds = info.kinds;
                int[] slots = info.slots;
                int[] childIdx = info.childIdx;
                String[] leafPinNames = info.leafPinNames;
                List<FlatNet> created = new ArrayList<>(to - from);
                List<Segment> segments = null;
                int unconnected = 0;
                Tally t = new Tally();
                for (int n = from; n < to; n++) {
                    EDIFNet net = info.nets[n];
                    int hash = prefixHash * info.namePows[n] + info.nameHashes[n];
                    int first = info.start[n];
                    int last = info.start[n + 1];
                    FlatNet flat = null;
                    boolean local = false;
                    if (!isTop) {
                        for (int p = first; p < last; p++) {
                            if (kinds[p] != OWN_PORT) {
                                continue;
                            }
                            FlatNet bound = portBinding[slots[p]];
                            if (bound == null) {
                                continue;
                            }
                            if (flat == null) {
                                flat = bound;
                            } else if (flat != bound) {
                                // Feedthrough connecting two nets of the parent
                                pendingMerges.add(new FlatNet[]{flat, bound});
                            }
                        }
                    }
                    if (flat == null) {
                        if (first == last) {
                            if (info.notNets[n]) {
                                // Placeholder nets written by Vivado to declare the range of a bus net
                                // (e.g. "[7:0]data") are not nets, and neither are unconnected nets
                                // (unless they are bits of a bus, which Vivado keeps as a whole)
                                continue;
                            }
                            if (countUnconnected && (namesOfInterest == null || !namesOfInterest.contains(hash))) {
                                // A net without connections, which is only counted (not inside a macro,
                                // where Vivado ties it to a constant)
                                if (!info.insideMacro) {
                                    unconnected++;
                                }
                                continue;
                            }
                        }
                        flat = new FlatNet(prefix, net);
                        local = true;
                        flat.insideMacro = info.insideMacro;
                        created.add(flat);
                    }
                    if (namesOfInterest != null && namesOfInterest.contains(hash)) {
                        if (segments == null) {
                            segments = new ArrayList<>();
                        }
                        segments.add(new Segment(hash, prefix, net, flat));
                    }

                    t.resetConnections();
                    t.staticType = info.staticTypes[n];
                    t.namedBy = NAMED_BY_SEGMENT;
                    t.portOnlyOutput = false;
                    t.insideMacro = info.insideMacro;
                    t.ownPortOnlyOutput = last == first + 1 && kinds[first] == OWN_PORT && info.ownOutputs[slots[first]];
                    boolean childOutput = false;
                    boolean ownOutput = false;
                    for (int p = first; p < last; p++) {
                        byte kind = kinds[p];
                        if ((kind & CHILD_OUTPUT) != 0) {
                            childOutput = true;
                        } else if (kind == OWN_PORT && info.ownOutputs[slots[p]]) {
                            ownOutput = true;
                        }
                        switch (kind & KIND_MASK) {
                            case OWN_PORT:
                                if (isTop) {
                                    addTopPort(t, net.getPortInsts().get(p - first));
                                }
                                break;
                            case BLACK_BOX:
                                t.blackBoxLoads++;
                                break;
                            case BLACK_BOX_INOUT:
                                t.blackBoxLoads++;
                                t.blackBoxInouts++;
                                break;
                            case BLACK_BOX_OUTPUT:
                                t.blackBoxDrivers++;
                                t.namedBy = NAMED_BY_DRIVER;
                                break;
                            case HIER:
                                childBindings[childIdx[p]][slots[p]] = flat;
                                if ((kind & DANGLING) != 0) {
                                    // An output port left unconnected inside its cell names the net,
                                    // unless there is a real driver
                                    t.namedBy = Math.max(t.namedBy, NAMED_BY_DANGLING_OUTPUT);
                                } else if ((kind & DANGLING_INOUT) != 0) {
                                    // So does an inout port left unconnected inside its cell, even over
                                    // a top level inout port
                                    t.namedBy = Math.max(t.namedBy, NAMED_BY_DANGLING_INOUT);
                                } else if ((kind & PORT_ONLY) != 0) {
                                    t.portOnlyOutput = true;
                                }
                                break;
                            default:
                                if (kind == LEAF_OUTPUT) {
                                    t.leafDrivers++;
                                    if (!info.childIsWeakDriver[childIdx[p]]) {
                                        t.namedBy = NAMED_BY_DRIVER;
                                    }
                                } else if (kind == LEAF_INPUT) {
                                    t.leafLoads++;
                                } else {
                                    t.leafInouts++;
                                    // A leaf's inout pin names its net over a top level inout port
                                    t.namedBy = Math.max(t.namedBy, NAMED_BY_DANGLING_INOUT);
                                }
                                SiteInst si = leafSites[childIdx[p]];
                                if (si != null) {
                                    t.addPin(si, leafCells[childIdx[p]], leafPinNames[p]);
                                } else {
                                    t.unplacedLeafPins++;
                                    if (kind == LEAF_OUTPUT) {
                                        t.unplacedLeafDrivers++;
                                    }
                                }
                        }
                    }
                    t.childOutputOnly = childOutput && !ownOutput;
                    if (local) {
                        flat.addUnsynchronized(t, prefix, net);
                    } else {
                        flat.add(t, prefix, net);
                    }
                }
                if (!created.isEmpty()) {
                    flatNets.add(created);
                }
                if (unconnected > 0) {
                    unconnectedNets.add(unconnected);
                }
                if (segments != null) {
                    segmentsOfInterest.add(segments);
                }
            }

            private void addTopPort(Tally t, EDIFPortInst epi) {
                Cell portCell = design.getCell(epi.getName());
                // (an input port with a partition pin, e.g. of a reconfigurable module, is not placed)
                boolean partitionPin = partitionPinPorts.contains(epi.getName());
                if (partitionPin) {
                    t.partitionPinPorts++;
                }
                boolean placed = portCell != null && portCell.isPlaced() && !(!epi.isOutput() && partitionPin);
                if (placed) {
                    t.addPin(portCell.getSiteInst(), null, null);
                }
                // A top level input (or inout) port drives the net; an output (or inout) port is a load
                if (!epi.isOutput()) {
                    if (placed) {
                        t.placedPortDrivers++;
                    } else {
                        t.unplacedPortDrivers++;
                    }
                    t.namedBy = Math.max(t.namedBy, epi.isInput() ? NAMED_BY_DRIVER : NAMED_BY_INOUT);
                }
                if (!epi.isInput()) {
                    if (placed) {
                        t.placedPortLoads++;
                    } else {
                        t.unplacedPortLoads++;
                    }
                }
            }
        }
    }
}

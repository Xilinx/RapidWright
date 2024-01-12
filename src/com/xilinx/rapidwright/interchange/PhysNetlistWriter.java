/*
 * Copyright (c) 2020-2022, Xilinx, Inc.
 * Copyright (c) 2022-2023, Advanced Micro Devices, Inc.
 * All rights reserved.
 *
 * Author: Chris Lavin, Xilinx Research Labs.
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

package com.xilinx.rapidwright.interchange;

import com.xilinx.rapidwright.design.AltPinMapping;
import com.xilinx.rapidwright.design.Cell;
import com.xilinx.rapidwright.design.Design;
import com.xilinx.rapidwright.design.Net;
import com.xilinx.rapidwright.design.SiteInst;
import com.xilinx.rapidwright.design.SitePinInst;
import com.xilinx.rapidwright.device.BEL;
import com.xilinx.rapidwright.device.BELClass;
import com.xilinx.rapidwright.device.BELPin;
import com.xilinx.rapidwright.device.Node;
import com.xilinx.rapidwright.device.PIP;
import com.xilinx.rapidwright.device.Series;
import com.xilinx.rapidwright.device.Site;
import com.xilinx.rapidwright.device.SitePIP;
import com.xilinx.rapidwright.device.SitePIPStatus;
import com.xilinx.rapidwright.interchange.PhysicalNetlist.PhysNetlist;
import com.xilinx.rapidwright.interchange.PhysicalNetlist.PhysNetlist.CellPlacement;
import com.xilinx.rapidwright.interchange.PhysicalNetlist.PhysNetlist.MultiCellPinMapping;
import com.xilinx.rapidwright.interchange.PhysicalNetlist.PhysNetlist.NetType;
import com.xilinx.rapidwright.interchange.PhysicalNetlist.PhysNetlist.PhysBelPin;
import com.xilinx.rapidwright.interchange.PhysicalNetlist.PhysNetlist.PhysCell;
import com.xilinx.rapidwright.interchange.PhysicalNetlist.PhysNetlist.PhysCellType;
import com.xilinx.rapidwright.interchange.PhysicalNetlist.PhysNetlist.PhysNet;
import com.xilinx.rapidwright.interchange.PhysicalNetlist.PhysNetlist.PhysNode;
import com.xilinx.rapidwright.interchange.PhysicalNetlist.PhysNetlist.PhysPIP;
import com.xilinx.rapidwright.interchange.PhysicalNetlist.PhysNetlist.PhysSitePIP;
import com.xilinx.rapidwright.interchange.PhysicalNetlist.PhysNetlist.PhysSitePin;
import com.xilinx.rapidwright.interchange.PhysicalNetlist.PhysNetlist.PinMapping;
import com.xilinx.rapidwright.interchange.PhysicalNetlist.PhysNetlist.Property;
import com.xilinx.rapidwright.interchange.PhysicalNetlist.PhysNetlist.RouteBranch;
import com.xilinx.rapidwright.interchange.PhysicalNetlist.PhysNetlist.RouteBranch.RouteSegment;
import com.xilinx.rapidwright.interchange.PhysicalNetlist.PhysNetlist.SiteInstance;
import com.xilinx.rapidwright.interchange.RouteBranchNode.RouteSegmentType;
import com.xilinx.rapidwright.tests.CodePerfTracker;
import com.xilinx.rapidwright.util.Utils;
import org.capnproto.MessageBuilder;
import org.capnproto.PrimitiveList;
import org.capnproto.StructList;
import org.capnproto.StructList.Builder;
import org.capnproto.Text;
import org.capnproto.TextList;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Queue;

public class PhysNetlistWriter {

    /**
     * By default, routing for physical nets will be written as a list of (connected)
     * trees of routing resources. Disabling this feature will save runtime but write
     * out the same set of routing resources as an unordered list of stubs instead.
     */
    public static boolean BUILD_ROUTING_GRAPH_ON_EXPORT = true;

    /**
     * The Interchange format allows for all physical routing resources to be
     * specified, e.g.
     *   {@code ... -> PIP -> SitePin -> BELPin(output) -> BELPin(input) -> SitePIP -> BELPin(output) -> BELPin(input)}
     * It may not be necessary to use specify all such resources, as many are implied:
     *   {@code ... -> PIP -> SitePin -> (implied)      -> (implied)     -> SitePIP -> (implied)      -> (implied)}
     * Disabling this flag allows such implied resources to be omitted.
     */
    public static boolean VERBOSE_PHYSICAL_NET_ROUTING = true;

    public static final String LOCKED = "<LOCKED>";
    public static final String PORT = "<PORT>";


    protected static void writeSiteInsts(PhysNetlist.Builder physNetlist, Design design,
                                         StringEnumerator strings) {
        Builder<SiteInstance.Builder> siteInsts = physNetlist.initSiteInsts(design.getSiteInsts().size());
        int i=0;
        for (SiteInst si : design.getSiteInsts()) {
            SiteInstance.Builder siBuilder = siteInsts.get(i);
            siBuilder.setSite(strings.getIndex(si.getSiteName()));
            siBuilder.setType(strings.getIndex(si.getSiteTypeEnum().name()));
            i++;
        }
    }

    protected static String getUniqueLockedCellName(Cell cell, Map<String,PhysCellType> physCells) {
        String cellName = cell.getName();
        if (cellName.equals(LOCKED)) {
            cellName = cell.getSiteName() + "_" + cell.getBELName() + "_" + LOCKED;
            physCells.put(cellName,PhysCellType.LOCKED);
        } else if (cell.getType().equals(PORT)) {
            physCells.put(cellName,PhysCellType.PORT);
        }
        return cellName;
    }

    protected static void writePlacement(PhysNetlist.Builder physNetlist, Design design,
                                         StringEnumerator strings) {
        writePlacement(physNetlist, design, strings, design.getSiteInsts());
    }

    public static void writePlacement(PhysNetlist.Builder physNetlist, Design design,
                                      StringEnumerator strings, Collection<SiteInst> siteInsts) {
        Map<String,PhysCellType> physCells = new HashMap<>();
        ArrayList<Cell> allCells = new ArrayList<>();
        Map<String,ArrayList<Cell>> multiBelCells = new HashMap<>();
        int i=0;
        for (SiteInst siteInst : siteInsts) {
            if (!siteInst.isPlaced()) continue;
            for (Cell cell : siteInst.getCells()) {
                allCells.add(cell);
                if (!cell.isPlaced()) continue;
                String cellName = cell.getName();
                if (cellName.equals(PhysNetlistWriter.LOCKED)) continue;
                Cell multiCell = design.getCell(cellName);
                if (multiCell == null) {
                    assert(cell.isFFRoutethruCell());
                }
                else if (!multiCell.getBELName().equals(cell.getBELName())) {
                    multiBelCells.computeIfAbsent(cellName, (k) -> new ArrayList<>())
                            .add(cell);
                    // Don't add multi-bel cells, store relevant info in pin placements
                    allCells.remove(allCells.size()-1);
                }
            }
        }

        Builder<CellPlacement.Builder> cells = physNetlist.initPlacements(allCells.size());
        for (Cell cell : allCells) {
            CellPlacement.Builder physCell = cells.get(i);
            String cellName = getUniqueLockedCellName(cell, physCells);
            physCell.setCellName(strings.getIndex(cellName));
            physCell.setType(strings.getIndex(cell.getType()));
            physCell.setSite(strings.getIndex(cell.getSiteName()));
            String belName = cell.getBELName();
            if (belName != null) {
                physCell.setBel(strings.getIndex(belName));
            }
            physCell.setIsBelFixed(cell.isBELFixed());
            physCell.setIsSiteFixed(cell.isSiteFixed());
            ArrayList<Cell> otherBels = multiBelCells.get(cell.getName());
            int additionalPinMappings = 0;
            if (otherBels != null) {
                PrimitiveList.Int.Builder others = physCell.initOtherBels(otherBels.size());
                int j=0;
                for (Cell c : otherBels) {
                    additionalPinMappings += c.getPinMappingsP2L().size();
                    if (c.hasAltPinMappings()) {
                        additionalPinMappings += c.getAltPinMappings().size();
                    }
                    others.set(j, strings.getIndex(c.getBELName()));
                    j++;
                }
            }
            Builder<PinMapping.Builder> pinMap = physCell.initPinMap(cell.getPinMappingsP2L().size()
                 + additionalPinMappings);
            int idx = addCellPinMappings(cell, strings, pinMap, 0);
            if (otherBels != null) {
                for (Cell c : otherBels) {
                    idx = addCellPinMappings(c, strings, pinMap, idx);
                }
            }

            i++;
        }

        // Add PhysCells
        Builder<PhysCell.Builder> physCellBuilders = physNetlist.initPhysCells(physCells.size());
        int j=0;
        for (Entry<String,PhysCellType> e : physCells.entrySet()) {
            String physCellName = e.getKey();
            PhysCellType type = e.getValue();
            PhysCell.Builder physCellBuilder = physCellBuilders.get(j);
            physCellBuilder.setCellName(strings.getIndex(physCellName));
            physCellBuilder.setPhysType(type);
            j++;
        }
    }

    private static int addCellPinMappings(Cell cell, StringEnumerator strings,
                                            Builder<PinMapping.Builder> pinMap, Integer idx) {
        for (Entry<String,String> e : cell.getPinMappingsP2L().entrySet()) {
            PinMapping.Builder pinMapping = pinMap.get(idx);
            pinMapping.setBel(strings.getIndex(cell.getBELName()));
            pinMapping.setCellPin(strings.getIndex(e.getValue()));
            pinMapping.setBelPin(strings.getIndex(e.getKey()));
            pinMapping.setIsFixed(cell.isPinFixed(e.getKey()));
            idx++;
        }
        if (cell.hasAltPinMappings()) {
            for (Entry<String,AltPinMapping> e : cell.getAltPinMappings().entrySet()) {
                PinMapping.Builder pinMapping = pinMap.get(idx);
                pinMapping.setBel(strings.getIndex(cell.getBELName()));
                AltPinMapping altPinMapping = e.getValue();
                pinMapping.setCellPin(strings.getIndex(altPinMapping.getLogicalName()));
                pinMapping.setBelPin(strings.getIndex(e.getKey()));

                MultiCellPinMapping.Builder otherCell = pinMapping.initOtherCell();
                otherCell.setMultiCell(strings.getIndex(e.getValue().getAltCellName()));
                otherCell.setMultiType(strings.getIndex(e.getValue().getAltCellType()));
                idx++;
            }
        }
        return idx;
    }

    private static void writePhysNets(PhysNetlist.Builder physNetlist, Design design,
                                      StringEnumerator strings) {
        writeNullNet(physNetlist, design, strings);

        int physNetCount = design.getNets().size();
        Builder<PhysNet.Builder> nets = physNetlist.initPhysNets(physNetCount);
        Net[] keys = design.getNets().toArray(new Net[design.getNets().size()]);
        writePhysNetsRange(nets, keys, design, strings, 0, keys.length - 1);
    }

   protected static void writePhysNetsRange(Builder<PhysNet.Builder> nets, Net[] keys,
                                            Design design, StringEnumerator strings, int start,
                                            int end) {
        for (int i = start; i <= end; i++) {
            String netName = keys[i].getName();
            Net net = design.getNet(netName);
            assert(net != null);
            PhysNet.Builder physNet = nets.get(i - start);
            buildNet(net, physNet, strings);
        }
    }

    protected static void writeNullNet(PhysNetlist.Builder physNetlist, Design design,
                                       StringEnumerator strings) {
        List<RouteBranchNode> nullNetStubs = new ArrayList<>();
        for (SiteInst siteInst : design.getSiteInsts()) {
            Site site = siteInst.getSite();
            for (SitePIP sitePIP : siteInst.getUsedSitePIPs()) {
                String siteWire = sitePIP.getInputPin().getSiteWireName();
                if (siteInst.getNetFromSiteWire(siteWire) != null) {
                    continue;
                }

                String sitePinName = sitePIP.getInputPin().getConnectedSitePinName();
                SitePinInst spi = siteInst.getSitePinInst(sitePinName);
                if (spi != null && spi.getNet() != null) {
                    continue;
                }

                SitePIPStatus status = siteInst.getSitePIPStatus(sitePIP);
                nullNetStubs.add(new RouteBranchNode(site, sitePIP, status.isFixed()));
            }
        }

        PhysNet.Builder nullNet = physNetlist.getNullNet();
        Builder<RouteBranch.Builder> stubs = nullNet.initStubs(nullNetStubs.size());
        int i = 0;
        for (RouteBranchNode node : nullNetStubs) {
            RouteBranch.Builder stub = stubs.get(i);
            PhysSitePIP.Builder physSitePIP = stub.initRouteSegment().initSitePIP();
            SiteSitePIP sitePIP = node.getSitePIP();
            physSitePIP.setSite(strings.getIndex(sitePIP.site.getName()));
            physSitePIP.setBel(strings.getIndex(sitePIP.sitePIP.getBELName()));
            physSitePIP.setPin(strings.getIndex(sitePIP.sitePIP.getInputPinName()));
            physSitePIP.setIsFixed(sitePIP.isFixed);
            i++;
        }
    }

    private static void buildNet(Net net, PhysNet.Builder physNet, StringEnumerator strings) {
        physNet.setName(strings.getIndex(net.getName()));
        switch (net.getType()) {
        case GND:
            physNet.setType(NetType.GND);
            break;
        case VCC:
            physNet.setType(NetType.VCC);
            break;
        default:
            physNet.setType(NetType.SIGNAL);
        }

        // We need to traverse the net inside sites to fully populate routing spec
        List<RouteBranchNode> routingSources = new ArrayList<>();
        List<PIP> stubPIPs = new ArrayList<>();
        for (PIP p : net.getPIPs()) {
            if (p.isEndWireNull() || p.isStub()) {
                stubPIPs.add(p);

                if (p.isEndWireNull()) {
                    continue;
                }
            }

            routingSources.add(new RouteBranchNode(p));
        }
        for (SitePinInst spi : net.getPins()) {
            routingSources.add(new RouteBranchNode(spi));
        }

        for (SiteInst siteInst : net.getSiteInsts()) {
            extractIntraSiteRouting(net, routingSources, siteInst);
        }

        populateRouting(routingSources, physNet, strings);

        if (stubPIPs.size() > 0) {
            StructList.Builder<PhysNode.Builder> physNodes = physNet.initStubNodes(stubPIPs.size());
            for (int j = 0; j < stubPIPs.size(); j++) {
                PhysNode.Builder physNode = physNodes.get(j);
                PIP stubPIP = stubPIPs.get(j);
                physNode.setTile(strings.getIndex(stubPIP.getTile().getName()));
                if (stubPIP.isEndWireNull()) {
                    physNode.setWire(strings.getIndex(stubPIP.getStartWireName()));
                } else {
                    assert(stubPIP.isStub());
                    physNode.setWire(strings.getIndex(stubPIP.getEndWireName()));
                }
                physNode.setIsFixed(stubPIP.isPIPFixed());
            }
        }
    }

    public static void extractIntraSiteRouting(Net net, List<RouteBranchNode> nodes, SiteInst siteInst) {
        Site site = siteInst.getSite();
        final boolean isStaticNet = net.isStaticNet();
        for (String siteWire : siteInst.getSiteWiresFromNet(net)) {
            BELPin[] belPins = siteInst.getSiteWirePins(siteWire);
            for (BELPin belPin : belPins) {
                BEL bel = belPin.getBEL();
                Cell cell = siteInst.getCell(bel);
                boolean bidirPinIsInput = false;
                boolean bidirPinIsOutput = false;
                if (bel.getBELClass() == BELClass.BEL) {
                    if (cell == null) {
                        if (belPin.isInput() || !net.isStaticNet()) {
                            // Skip if nothing placed here and cannot be driving a static net
                            continue;
                        }
                        assert(bel.isLUT() || // LUTs can be a GND or VCC source
                                (net.isGNDNet() && bel.isGndSource()) ||
                                (net.isVCCNet() && bel.isVccSource()));
                    } else if (cell.getType().equals(PORT)) {
                        if (Utils.isIOB(siteInst)) {
                            assert(belPin.isBidir());
                            assert(bel.getName().equals("PAD"));

                            Series series = siteInst.getDesign().getDevice().getSeries();
                            SitePIP sitePIP;
                            if (series == Series.UltraScalePlus || series == Series.UltraScale) {
                                BEL padout = siteInst.getBEL("PADOUT");
                                if (padout == null) {
                                    // HPIOB_SNGL site types do not contain this SitePIP; ignore
                                    continue;
                                }
                                sitePIP = siteInst.getSitePIP(padout.getPin("IN"));
                            } else if (series == Series.Series7) {
                                sitePIP = siteInst.getSitePIP("IUSED", "0");
                            } else {
                                throw new RuntimeException("Unsupported series " + series);
                            }

                            SitePIPStatus sitePIPStatus = siteInst.getSitePIPStatus(sitePIP);
                            bidirPinIsOutput = sitePIPStatus.isUsed();
                            bidirPinIsInput = !bidirPinIsOutput;
                        } else if (siteInst.getSiteName().startsWith("GTY")) {
                            if (belPin.isBidir()) {
                                assert(bel.getName().startsWith("REFCLK"));
                                BEL obufdsBel = siteInst.getBEL("OBUFDS" + bel.getName().charAt(6) + "_GTYE4");
                                assert(obufdsBel != null);
                                Cell obufdsCell = siteInst.getCell(obufdsBel);
                                bidirPinIsInput = (obufdsCell != null);
                                bidirPinIsOutput = (obufdsCell == null);
                            }
                        } else {
                            throw new RuntimeException("Unable to process PORT cell at site " + siteInst.getSiteName());
                        }
                    }
                }

                boolean routethru = false;
                if (belPin.isInput() || bidirPinIsInput) {
                    if (bel.getBELClass() == BELClass.BEL) {
                        if (!VERBOSE_PHYSICAL_NET_ROUTING && !cell.isRoutethru()) {
                            // Skip if cell is not a routethru
                            continue;
                        }
                        if (cell.getLogicalPinMapping(belPin.getName()) == null) {
                            // Skip if pin not used (e.g. A1 connects to A[56]LUT.A1;
                            // both cells can exist but not both need be using this pin)
                            continue;
                        }

                        // Fall through
                    } else if (bel.getBELClass() == BELClass.RBEL) {
                        SitePIP sitePIP = siteInst.getSitePIP(belPin);
                        SitePIPStatus status = siteInst.getSitePIPStatus(sitePIP);
                        if (!status.isUsed()) {
                            continue;
                        }

                        nodes.add(new RouteBranchNode(site, sitePIP, status.isFixed()));

                        if (!VERBOSE_PHYSICAL_NET_ROUTING) {
                            // Skip input pins to SitePIPs
                            continue;
                        }
                    } else {
                        assert(bel.getBELClass() == BELClass.PORT);
                        SitePinInst spi = siteInst.getSitePinInst(siteWire);
                        if (spi == null) {
                            // Skip if pin is not used by site port
                            continue;
                        }

                        if (!VERBOSE_PHYSICAL_NET_ROUTING) {
                            // Skip input pins to site ports (will be set when site pin is added
                            // onto site instance)
                            continue;
                        }
                    }
                } else {
                    assert(belPin.isOutput() || bidirPinIsOutput);

                    if (bel.getBELClass() == BELClass.BEL) {
                        routethru = cell != null && cell.isRoutethru();

                        // Fall through
                    } else if (bel.getBELClass() == BELClass.RBEL) {
                        if (isStaticNet && bel.isStaticSource()) {
                            assert(belPin.isOutput());
                            // Skip output pins on static source BELs (e.g. SLICE.HARD0GND)
                            continue;
                        }
                        if (siteInst.getUsedSitePIP(belPin) != null) {
                            if (!VERBOSE_PHYSICAL_NET_ROUTING) {
                                // Skip output pins on SitePIPs
                                continue;
                            }
                        } else {
                            assert(net.isStaticNet() || net.isUsedNet());
                        }
                    } else {
                        assert(bel.getBELClass() == BELClass.PORT);

                        if (!VERBOSE_PHYSICAL_NET_ROUTING) {
                            // Skip output pins on site ports (will be set when site pin is added
                            // onto site instance)
                            continue;
                        }

                        if (bel.getName().equals("IO") && siteInst.getSitePinInst(belPin.getName()) == null) {
                            // Skip IO site ports without a site pin
                            continue;
                        }
                    }
                }

                nodes.add(new RouteBranchNode(site, belPin, routethru));
            }
        }
    }

    @SuppressWarnings("unused")
    private static void debugPrintRouteBranchNodes(List<RouteBranchNode> nodes, String prefix) {
        for (RouteBranchNode n : nodes) {
            System.out.println(prefix + n.toString());
            debugPrintRouteBranchNodes(n.getBranches(), prefix + "  ");
        }
    }

    private static void populateRouting(List<RouteBranchNode> routingBranches,
                                        PhysNet.Builder physNet, StringEnumerator strings) {

        List<RouteBranchNode> sources;
        List<RouteBranchNode> stubs;

        if (BUILD_ROUTING_GRAPH_ON_EXPORT) {
            sources = new ArrayList<>();
            stubs = new ArrayList<>();

            // To be used in conjunction with the "rapidwright.rwroute.lutPinSwapping.deferIntraSiteRoutingUpdates"
            // property (which allows RWRoute to perform LUT pin swapping but not move the swapped SitePinInst nor
            // perform any intra-site routing updates). With the aforementioned RWRoute option, for swapped LUT pins
            // default PhysNetlistWriter would not be able to identify the site pin that the routing services, thus
            // causing any deferred site pin updates to appear as unconnected stubs.
            // Enabling this following option allows the PhysNetlistWriter to simulate LUT pin swapping such that
            // any routes that service an incorrect LUT input site pin are allowed to have a fake branch to the
            // correct site pin on the same LUT for the purposes of simulating (prior to applying deferred updates)
            // a fully routed net.
            // This feature -- despite intra-site routing updates being deferred -- enables a stub-free physical
            // netlist to be output. In addition, when reading this netlist back into RapidWright using PhysNetlistReader,
            // any simulated branches will be discarded allowing deferred updates to continue as before.
            final boolean simulateSwappedLutPins = Boolean.getBoolean("rapidwright.physNetlistWriter.simulateSwappedLutPins");

            Map<String, RouteBranchNode> map = new HashMap<>();
            for (RouteBranchNode rb : routingBranches) {
                map.put(rb.toString(), rb);
            }

            // PASS 1: Connect drivers of each branch, put sources on source list
            for (RouteBranchNode rb : routingBranches) {
                if (rb.isSource()) {
                    sources.add(rb);
                } else {
                    boolean simulateThisSwappedLutPin = simulateSwappedLutPins &&
                        rb.getType() == RouteSegmentType.SITE_PIN &&
                        rb.getSitePin().isLUTInputPin();

                    for (String driver : rb.getDrivers(simulateSwappedLutPins)) {
                        RouteBranchNode driverBranch = map.get(driver);
                        if (driverBranch == null) continue;
                        if (driverBranch.getType() == RouteSegmentType.PIP) {
                            PIP pip = driverBranch.getPIP();
                            if (pip.isBidirectional() && rb.getType() == RouteSegmentType.PIP) {
                                PIP curr = rb.getPIP();
                                Node currNode = !curr.isReversed() ?
                                                curr.getStartNode() : curr.getEndNode();
                                Node driverNode = pip.isReversed() ?
                                                  pip.getStartNode() : pip.getEndNode();
                                if (!currNode.equals(driverNode)) {
                                    continue;
                                }
                            }
                        }
                        if (simulateThisSwappedLutPin) {
                            List<RouteBranchNode> branches = driverBranch.getBranches();
                            if (!branches.isEmpty()) {
                                // driver is already driving something, which must be a (LUT) site pin
                                // that can't be the current site pin. Since it's already servicing a LUT
                                // input, it can't be used to service this one so skip it.
                                assert(branches.get(0).getType() == RouteSegmentType.SITE_PIN);
                                assert(branches.get(0) != rb);
                                continue;
                            }
                        }

                        driverBranch.addBranch(rb);
                        // Assume this must be the only driver
                        break;
                    }
                }
            }

            // PASS 2: Any nodes not reachable by sources, go onto stubs list
            Queue<RouteBranchNode> queue = new LinkedList<>(sources);
            while (!queue.isEmpty()) {
                RouteBranchNode curr = queue.poll();
                if (curr.hasBeenVisited()) {
                    continue;
                }
                curr.setVisited(true);
                map.remove(curr.toString());
                queue.addAll(curr.getBranches());
            }

            NetType type = physNet.getType();
            final boolean isStaticNet = (type == NetType.GND || type == NetType.VCC);
            for (RouteBranchNode rb : map.values()) {
                if (rb.getParent() != null) {
                    // Not a stub if it's connected to something
                    continue;
                }

                if (isStaticNet &&
                        ((rb.getType() == RouteSegmentType.SITE_PIN && rb.getSitePin().isOutPin()) ||
                         (rb.getType() == RouteSegmentType.BEL_PIN && rb.getBELPin().belPin.isOutput()))) {
                    // Assume that output site/bel pin stubs on static nets are static sources
                    // (e.g. LUT outputs, VCC -> GND inverters, etc.)
                    sources.add(rb);
                    continue;
                }

                stubs.add(rb);
            }
        } else {
            sources = null;
            stubs = routingBranches;
        }

        //if (strings.get(physNet.getName()).equals("")) debugPrintRouteBranchNodes(sources, "");

        // Serialize...
        if (sources != null && sources.size() > 0) {
            Builder<RouteBranch.Builder> routeSrcs = physNet.initSources(sources.size());
            for (int i=0; i < sources.size(); i++) {
                RouteBranch.Builder srcBuilder = routeSrcs.get(i);
                RouteBranchNode src = sources.get(i);
                writeRouteBranch(srcBuilder, src, strings);
            }
        }
        if (stubs.size() > 0) {
            Builder<RouteBranch.Builder> routeStubs = physNet.initStubs(stubs.size());
            for (int i=0; i < stubs.size(); i++) {
                RouteBranch.Builder stubBuilder = routeStubs.get(i);
                RouteBranchNode src = stubs.get(i);
                writeRouteBranch(stubBuilder, src, strings);
            }
        }
    }

    public static void writeRouteBranch(RouteBranch.Builder srcBuilder, RouteBranchNode src,
                                        StringEnumerator strings) {
        RouteSegment.Builder segment = srcBuilder.getRouteSegment();
        switch(src.getType()) {
            case PIP:{
                PIP pip = src.getPIP();
                PhysPIP.Builder physPIP = segment.initPip();
                physPIP.setTile(strings.getIndex(pip.getTile().getName()));
                physPIP.setWire0(strings.getIndex(pip.getStartWireName()));
                physPIP.setWire1(strings.getIndex(pip.getEndWireName()));
                physPIP.setIsFixed(pip.isPIPFixed());
                physPIP.setForward(!pip.isReversed());
                break;
            }
            case BEL_PIN:{
                SiteBELPin sbp = src.getBELPin();
                PhysBelPin.Builder physPin = segment.initBelPin();
                physPin.setBel(strings.getIndex(sbp.belPin.getBEL().getName()));
                physPin.setPin(strings.getIndex(sbp.belPin.getName()));
                physPin.setSite(strings.getIndex(sbp.site.getName()));
                break;
            }
            case SITE_PIN:{
                SitePinInst spi = src.getSitePin();
                PhysSitePin.Builder physSitePin = segment.initSitePin();
                physSitePin.setSite(strings.getIndex(spi.getSite().getName()));
                physSitePin.setPin(strings.getIndex(spi.getName()));
                break;
            }
            case SITE_PIP: {
                SiteSitePIP sitePIP = src.getSitePIP();
                PhysSitePIP.Builder physSitePIP = segment.initSitePIP();
                physSitePIP.setSite(strings.getIndex(sitePIP.site.getName()));
                physSitePIP.setBel(strings.getIndex(sitePIP.sitePIP.getBELName()));
                physSitePIP.setPin(strings.getIndex(sitePIP.sitePIP.getInputPinName()));
                physSitePIP.setIsFixed(sitePIP.isFixed);
                break;
            }
            default:
                throw new RuntimeException("Unhandled class in routing representation: " +
                        src.getType());
        }
        int size = src.getBranches().size();
        if (size > 0) {
            Builder<RouteBranch.Builder> branches = srcBuilder.initBranches(size);
            for (int i = 0; i < size; i++) {
                writeRouteBranch(branches.get(i), src.getBranch(i), strings);
            }
        }
    }

    protected static void writeDesignProperties(PhysNetlist.Builder physNetlist, Design design,
                                                StringEnumerator strings) {
        StructList.Builder<Property.Builder> props = physNetlist.initProperties(2);
        Property.Builder autoIOs = props.get(0);
        autoIOs.setKey(strings.getIndex(PhysNetlistReader.DISABLE_AUTO_IO_BUFFERS));
        autoIOs.setValue(strings.getIndex(design.isAutoIOBuffersSet() ? "0" : "1"));

        Property.Builder ooc = props.get(1);
        ooc.setKey(strings.getIndex(PhysNetlistReader.OUT_OF_CONTEXT));
        ooc.setValue(strings.getIndex(design.isDesignOutOfContext() ? "1" : "0"));
    }

    /**
     * Writes list of String objects to the Cap'n Proto message physical netlist
     * @param physNetlist The physical netlist builder.
     * @param strings List of String objects to be written.
     */
    public static void writeStrings(PhysNetlist.Builder physNetlist, List<String> strings) {
        TextList.Builder strList = physNetlist.initStrList(strings.size());
        int stringCount = strList.size();
        for (int i=0; i < stringCount; i++) {
            strList.set(i, new Text.Reader(strings.get(i)));
        }
    }

    public static void writePhysNetlist(Design design, String fileName) throws IOException {
        CodePerfTracker t = new CodePerfTracker("Write PhysNetlist");

        t.start("Initialize");
        MessageBuilder message = new MessageBuilder();
        PhysNetlist.Builder physNetlist = message.initRoot(PhysNetlist.factory);
        StringEnumerator strings = new StringEnumerator();

        physNetlist.setPart(design.getPartName());

        t.stop().start("Write SiteInsts");
        writeSiteInsts(physNetlist, design, strings);

        t.stop().start("Write Placement");
        writePlacement(physNetlist, design, strings);

        t.stop().start("Write Routing");
        writePhysNets(physNetlist, design, strings);

        t.stop().start("Write Design Props");
        writeDesignProperties(physNetlist, design, strings);

        t.stop().start("Write Strings");
        writeStrings(physNetlist, strings);

        t.stop().start("Write File");
        Interchange.writeInterchangeFile(fileName, message);

        t.stop().printSummary();
    }
}

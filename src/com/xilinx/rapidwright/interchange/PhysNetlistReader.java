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

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;

import com.xilinx.rapidwright.design.tools.LUTTools;
import com.xilinx.rapidwright.device.Series;
import com.xilinx.rapidwright.device.SitePIP;
import org.capnproto.MessageReader;
import org.capnproto.PrimitiveList;
import org.capnproto.ReaderOptions;
import org.capnproto.StructList;
import org.capnproto.TextList;
import org.python.google.common.base.Enums;
import org.python.google.common.base.Optional;

import com.xilinx.rapidwright.design.AltPinMapping;
import com.xilinx.rapidwright.design.Cell;
import com.xilinx.rapidwright.design.Design;
import com.xilinx.rapidwright.design.Net;
import com.xilinx.rapidwright.design.NetType;
import com.xilinx.rapidwright.design.SiteInst;
import com.xilinx.rapidwright.design.SitePinInst;
import com.xilinx.rapidwright.design.Unisim;
import com.xilinx.rapidwright.device.BEL;
import com.xilinx.rapidwright.device.BELClass;
import com.xilinx.rapidwright.device.BELPin;
import com.xilinx.rapidwright.device.Device;
import com.xilinx.rapidwright.device.PIP;
import com.xilinx.rapidwright.device.Site;
import com.xilinx.rapidwright.device.SiteTypeEnum;
import com.xilinx.rapidwright.device.Tile;
import com.xilinx.rapidwright.edif.EDIFCell;
import com.xilinx.rapidwright.edif.EDIFCellInst;
import com.xilinx.rapidwright.edif.EDIFHierCellInst;
import com.xilinx.rapidwright.edif.EDIFLibrary;
import com.xilinx.rapidwright.edif.EDIFNet;
import com.xilinx.rapidwright.edif.EDIFNetlist;
import com.xilinx.rapidwright.edif.EDIFPortInst;
import com.xilinx.rapidwright.edif.EDIFTools;
import com.xilinx.rapidwright.interchange.PhysicalNetlist.PhysNetlist;
import com.xilinx.rapidwright.interchange.PhysicalNetlist.PhysNetlist.CellPlacement;
import com.xilinx.rapidwright.interchange.PhysicalNetlist.PhysNetlist.MultiCellPinMapping;
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
import com.xilinx.rapidwright.util.Utils;

public class PhysNetlistReader {

    protected static final String DISABLE_AUTO_IO_BUFFERS = "DISABLE_AUTO_IO_BUFFERS";
    protected static final String OUT_OF_CONTEXT = "OUT_OF_CONTEXT";

    private static final String STATIC_SOURCE = "STATIC_SOURCE";
    private static int tieoffInstanceCount = 0;

    public static Design readPhysNetlist(String physNetlistFileName, EDIFNetlist netlist) throws IOException {
        Design design = new Design();
        design.setNetlist(netlist);
        ReaderOptions rdOptions =
                new ReaderOptions(ReaderOptions.DEFAULT_READER_OPTIONS.traversalLimitInWords * 64,
                ReaderOptions.DEFAULT_READER_OPTIONS.nestingLimit * 128);
        MessageReader readMsg = Interchange.readInterchangeFile(physNetlistFileName, rdOptions);

        PhysNetlist.Reader physNetlist = readMsg.getRoot(PhysNetlist.factory);
        design.setPartName(physNetlist.getPart().toString());

        Enumerator<String> allStrings = readAllStrings(physNetlist);

        checkConstantRoutingAndNetNaming(physNetlist, netlist, allStrings);

        readSiteInsts(physNetlist, design, allStrings);

        readPlacement(physNetlist, design, allStrings);

        checkMacros(design);

        readRouting(physNetlist, design, allStrings);

        readDesignProperties(physNetlist, design, allStrings);

        return design;
    }

    public static Enumerator<String> readAllStrings(PhysNetlist.Reader physNetlist) {
        Enumerator<String> allStrings = new Enumerator<>();
        TextList.Reader strListReader = physNetlist.getStrList();
        int strCount = strListReader.size();
        for (int i=0; i < strCount; i++) {
            String str = strListReader.get(i).toString();
            allStrings.addObject(str);
        }
        return allStrings;
    }

    private static void readSiteInsts(PhysNetlist.Reader physNetlist, Design design,
                                        Enumerator<String> strings) {
        Device device = design.getDevice();
        StructList.Reader<SiteInstance.Reader> siteInsts = physNetlist.getSiteInsts();
        int siteInstCount = siteInsts.size();
        if (siteInstCount == 0 && physNetlist.getPlacements().size() > 0) {
            System.out.println("WARNING: Missing SiteInst information in *.phys file.  RapidWright "

                    + "will attempt to infer the proper SiteInst, however, it is recommended that "
                    + "SiteInst information be specified to avoid SiteTypeEnum mismatch problems.");
        }
        for (int i=0; i < siteInstCount; i++) {
            SiteInstance.Reader si = siteInsts.get(i);
            String siteName = strings.get(si.getSite());
            SiteTypeEnum type = SiteTypeEnum.valueOf(strings.get(si.getType()));
            design.createSiteInst(siteName, type, device.getSite(siteName));
        }
    }

    private static void readPlacement(PhysNetlist.Reader physNetlist, Design design,
                                        Enumerator<String> strings) {
        HashMap<String, PhysCellType> physCells = new HashMap<>();
        StructList.Reader<PhysCell.Reader> physCellReaders = physNetlist.getPhysCells();
        int physCellCount = physCellReaders.size();
        for (int i=0; i < physCellCount; i++) {
            PhysCell.Reader reader = physCellReaders.get(i);
            physCells.put(strings.get(reader.getCellName()), reader.getPhysType());
        }


        StructList.Reader<CellPlacement.Reader> placements = physNetlist.getPlacements();
        int placementCount = placements.size();
        Device device = design.getDevice();
        EDIFNetlist netlist = design.getNetlist();
        EDIFLibrary macroPrims = Design.getMacroPrimitives(device.getSeries());
        Map<String, List<String>> macroLeafChildren = new HashMap<>();
        for (int i=0; i < placementCount; i++) {
            CellPlacement.Reader placement = placements.get(i);
            String cellName = strings.get(placement.getCellName());
            EDIFCellInst cellInst = null;
            Site site = device.getSite(strings.get(placement.getSite()));
            SiteInst siteInst = design.getSiteInstFromSite(site);
            if (siteInst == null) {
                siteInst = design.createSiteInst(site);
            }
            String belName = strings.get(placement.getBel());
            HashSet<String> otherBELLocs = null;
            if (physCells.get(cellName) == PhysCellType.LOCKED) {
                cellInst = new EDIFCellInst(PhysNetlistWriter.LOCKED,null,null);
                if (siteInst == null) {
                    siteInst = new SiteInst(site.getName(), design, site.getSiteTypeEnum(), site);
                }
                siteInst.setSiteLocked(true);
                Cell c = siteInst.getCell(belName);
                if (c == null) {
                    BEL bel = siteInst.getBEL(belName);
                    c = new Cell(PhysNetlistWriter.LOCKED, bel);
                    c.setBELFixed(placement.getIsBelFixed());
                    c.setNullBEL(bel == null);
                    siteInst.addCell(c);
                }
                c.setLocked(true);

                // c Alternative Blocked Site Type // TODO
            } else if (physCells.get(cellName) == PhysCellType.PORT) {
                Cell portCell = new Cell(cellName,siteInst.getBEL(belName));
                portCell.setType(PhysNetlistWriter.PORT);
                siteInst.addCell(portCell);
                portCell.setBELFixed(placement.getIsBelFixed());
                portCell.setSiteFixed(placement.getIsSiteFixed());
            } else {
                cellInst = netlist.getCellInstFromHierName(cellName);
                String cellType = strings.get(placement.getType());
                if (cellInst == null) {
                    Optional<Unisim> maybeUnisim = Enums.getIfPresent(Unisim.class, cellType);
                    Unisim unisim = maybeUnisim.isPresent() ? maybeUnisim.get() : null;
                    if (unisim == null) {
                        EDIFCell cell = new EDIFCell(null,cellType);
                        cellInst = new EDIFCellInst(cellName,cell, null);
                    } else {
                        cellInst = Design.createUnisimInst(null, cellName, unisim);
                    }
                } else {
                    assert(cellInst.getCellType().getName().equals(cellType));
                }
                if (macroPrims.containsCell(cellType)) {
                    throw new RuntimeException("ERROR: Placement for macro primitive "
                            + cellInst.getCellType().getName() + " (instance "+cellName+") is "
                            + "invalid.  Please only provide placements for the macro's children "
                            + "leaf cells: " + cellInst.getCellType().getCellInsts() +".");
                }

                BEL bel = siteInst.getBEL(belName);
                if (bel == null) {
                    throw new RuntimeException(
                          "ERROR: The placement specified on BEL " + site.getName() + "/"
                          + belName + " could not be found in the target "
                          + "device.");
                }
                if (bel.getBELType().equals("HARD0") || bel.getBELType().equals("HARD1")) {
                    throw new RuntimeException(
                              "ERROR: The placement specified on BEL " + site.getName() + "/"
                            + bel.getName() + " is not valid. HARD0 and HARD1 BEL types do not "
                            + "require placed cells.");
                }
                Cell existingCell = siteInst.getCell(bel);
                if (existingCell != null) {
                    throw new RuntimeException(
                            "ERROR: Cell \"" + cellName + "\" placement on BEL " + site.getName() + "/"
                                    + belName + " conflicts with previously placed cell \"" + existingCell.getName()
                                    + "\".");
                }
                Cell cell = new Cell(cellName, siteInst, bel);
                cell.setBELFixed(placement.getIsBelFixed());
                cell.setSiteFixed(placement.getIsSiteFixed());
                if (cellInst != null) {
                    cell.setType(cellInst.getCellType().getName());
                }

                PrimitiveList.Int.Reader otherBELs = placement.getOtherBels();
                int otherBELCount = otherBELs.size();
                if (otherBELCount > 0) otherBELLocs = new HashSet<String>();
                for (int j=0; j < otherBELCount; j++) {
                    String belLoc = strings.get(otherBELs.get(j));
                    otherBELLocs.add(belLoc);
                }
            }

            PhysNet.Reader nullNet = physNetlist.getNullNet();
            StructList.Reader<RouteBranch.Reader> stubs = nullNet.getStubs();
            int stubCount = stubs.size();
            for (int k=0; k < stubCount; k++) {
                RouteSegment.Reader segment = stubs.get(k).getRouteSegment();
                PhysSitePIP.Reader spReader = segment.getSitePIP();
                SiteInst sitePIPSiteInst = getSiteInst(spReader.getSite(), design, strings);
                sitePIPSiteInst.addSitePIP(strings.get(spReader.getBel()),
                                           strings.get(spReader.getPin()));
            }


            StructList.Reader<PinMapping.Reader> pinMap = placement.getPinMap();
            int pinMapCount = pinMap.size();
            for (int j=0; j < pinMapCount; j++) {
                PinMapping.Reader pinMapping = pinMap.get(j);
                belName = strings.get(pinMapping.getBel());
                String belPinName = strings.get(pinMapping.getBelPin());
                String cellPinName = strings.get(pinMapping.getCellPin());
                Cell c = siteInst.getCell(belName);
                if (c == null) {
                    if (otherBELLocs.contains(belName)) {
                        BEL bel = siteInst.getBEL(belName);
                        if (bel == null) {
                            throw new RuntimeException("ERROR: Couldn't find BEL " + belName
                                    + " in site " + siteInst.getSiteName() + " of type "
                                    + siteInst.getSiteTypeEnum());
                        }
                        c = new Cell(cellName, bel);
                        c.setSiteInst(siteInst);
                        siteInst.getCellMap().put(belName, c);
                        c.setRoutethru(true);
                    } else {
                        throw new RuntimeException("ERROR: Missing BEL location in other BEL list: "
                                + belName + " on for pin mapping "
                                + belPinName + " -> " + cellPinName);
                    }
                }
                // Remote pin mappings from other cells
                if (c.getLogicalPinMapping(belPinName) != null && pinMapping.hasOtherCell()) {
                    c.setRoutethru(true);
                    MultiCellPinMapping.Reader otherCell = pinMapping.getOtherCell();
                    c.addAltPinMapping(belPinName, new AltPinMapping(cellPinName,
                            strings.get(otherCell.getMultiCell()),
                            strings.get(otherCell.getMultiType())));
                } else {
                    if (c.getBEL().getPin(belPinName) == null) {
                        System.err.println("WARNING: On cell " + c.getName() + ", a logical pin '" +
                                c.getType() + "." + cellPinName + "' is being mapped on to a BEL pin '"
                                + c.getBELName() + "." + belPinName + "' that does not exist. "
                                + "This may result in an invalid design.");
                    }

                    c.addPinMapping(belPinName, cellPinName);
                    if (pinMapping.getIsFixed()) {
                        c.fixPin(belPinName);
                    }
                }
            }
        }

        // Validate macro primitives are placed fully
        HashSet<String> checked = new HashSet<>();
        for (Cell c : design.getCells()) {
            EDIFCell cellType = c.getParentCell();
            if (cellType != null && macroPrims.containsCell(cellType)) {
                String parentHierName = c.getParentHierarchicalInstName();
                if (checked.contains(parentHierName)) continue;
                List<String> missingPlacements = null;
                List<String> childrenNames = macroLeafChildren.get(cellType.getName());
                if (childrenNames == null) {
                    childrenNames = EDIFTools.getMacroLeafCellNames(cellType);
                    macroLeafChildren.put(cellType.getName(), childrenNames);
                }
                //for (EDIFCellInst inst : cellType.getCellInsts()) { // TODO - Fix up loop list
                for (String childName : childrenNames) {
                    if (childName.equals("VCC") || childName.equals("GND")) {
                        // Ignore VCC (e.g. from FDRS_1) and GND cells
                        continue;
                    }
                    String childCellName = parentHierName + EDIFTools.EDIF_HIER_SEP + childName;
                    Cell child = design.getCell(childCellName);
                    if (child == null) {
                        if (missingPlacements == null) missingPlacements = new ArrayList<String>();
                        missingPlacements.add(childName + " (" + childCellName + ")");
                    }
                }
                if (missingPlacements != null && !cellType.getName().equals("IOBUFDS")) {
                    throw new RuntimeException("ERROR: Macro primitive '"+ parentHierName
                            + "' is not fully placed. Expected placements for all child cells: "
                            + cellType.getCellInsts() + ", but missing placements "
                            + "for cells: " + missingPlacements);
                }

                checked.add(parentHierName);
            }
        }
    }

    private static NetType getNetType(PhysNet.Reader netReader, String netName) {
        switch(netReader.getType()) {
            case GND:
                if (!netName.equals(Net.GND_NET)) {
                    throw new RuntimeException("ERROR: Invalid GND Net " + netName +
                            ", should be named " + Net.GND_NET);
                }
                return NetType.GND;
            case VCC:
                if (!netName.equals(Net.VCC_NET)) {
                    throw new RuntimeException("ERROR: Invalid VCC Net " + netName +
                            ", should be named " + Net.VCC_NET);
                }
                return NetType.VCC;
            default:
                return NetType.WIRE;
        }
    }

    private static void readRouting(PhysNetlist.Reader physNetlist, Design design,
                                    Enumerator<String> strings) {
        StructList.Reader<PhysNet.Reader> nets = physNetlist.getPhysNets();
        EDIFNetlist netlist = design.getNetlist();
        int netCount = nets.size();
        for (int i=0; i < netCount; i++) {
            PhysNet.Reader netReader = nets.get(i);
            String netName = strings.get(netReader.getName());
            Net net = new Net(netName);
            design.addNet(net);
            net.setType(getNetType(netReader, netName));

            // Sources
            StructList.Reader<RouteBranch.Reader> routeSrcs = netReader.getSources();
            int routeSrcsCount = routeSrcs.size();
            for (int j=0; j < routeSrcsCount; j++) {
                RouteBranch.Reader branchReader = routeSrcs.get(j);
                readRouteBranch(branchReader, net, design, strings, null);
            }
            // Stubs
            StructList.Reader<RouteBranch.Reader> routeStubs = netReader.getStubs();
            int routeStubsCount = routeStubs.size();
            for (int j=0; j < routeStubsCount; j++) {
                RouteBranch.Reader branchReader = routeStubs.get(j);
                readRouteBranch(branchReader, net, design, strings, null);
            }

            // Stub Nodes
            StructList.Reader<PhysNode.Reader> stubNodes = netReader.getStubNodes();
            int stubNodeCount = stubNodes.size();
            Device device = design.getDevice();
            for (int j=0; j < stubNodeCount; j++) {
                PhysNode.Reader stubNodeReader = stubNodes.get(j);
                Tile tile = device.getTile(strings.get(stubNodeReader.getTile()));
                PIP pip = new PIP(tile, stubNodeReader.getWire(), PIP.NULL_END_WIRE_IDX);
                net.addPIP(pip);
            }
        }
    }

    private static void readRouteBranch(RouteBranch.Reader branchReader, Net net, Design design,
                                        Enumerator<String> strings, BELPin routeThruLutInput) {
        RouteBranch.RouteSegment.Reader segment = branchReader.getRouteSegment();
        StructList.Reader<RouteBranch.Reader> branches = branchReader.getBranches();
        int branchesCount = branches.size();
        Device device = design.getDevice();
        switch(segment.which()) {
            case PIP:{
                PhysPIP.Reader pReader = segment.getPip();
                Tile tile = device.getTile(strings.get(pReader.getTile()));
                String wire0 = strings.get(pReader.getWire0());
                String wire1 = strings.get(pReader.getWire1());
                if (tile == null) {
                    throw new RuntimeException("ERROR: Tile " + tile + " for pip from wire " + wire0 + " to wire " + wire1 + " not found.");
                }

                Integer wire0Idx = tile.getWireIndex(wire0);
                if (wire0Idx == null) {
                    throw new RuntimeException("ERROR: Wire0 " + wire0 + " in tile " + tile + " not found.");
                }

                Integer wire1Idx = tile.getWireIndex(wire1);
                if (wire1Idx == null) {
                    throw new RuntimeException("ERROR: Wire1 " + wire1 + " in tile " + tile + " not found.");
                }

                PIP pip = tile.getPIP(wire0Idx, wire1Idx);
                if (pip == null) {
                    throw new RuntimeException("ERROR: PIP for tile " + tile + " from wire " + wire0 + " to wire " + wire1 + " not found.");
                }

                pip.setIsPIPFixed(pReader.getIsFixed());
                pip.setIsReversed(!pReader.getForward());
                net.addPIP(pip);
                break;
            }
            case BEL_PIN:{
                PhysBelPin.Reader bpReader = segment.getBelPin();
                SiteInst siteInst = getSiteInst(bpReader.getSite(), design, strings);
                String belName = strings.get(bpReader.getBel());
                BEL bel = siteInst.getBEL(belName);
                if (bel == null) {
                    throw new RuntimeException(String.format("ERROR: Failed to get BEL %s", belName));
                }
                String belPinName = strings.get(bpReader.getPin());
                BELPin belPin = bel.getPin(belPinName);
                if (belPin == null) {
                    throw new RuntimeException(String.format("ERROR: Failed to get BEL pin %s/%s", belName, belPinName));
                }

                // Examine BEL input pins from SLICEL/M only
                if (Utils.isSLICE(siteInst) && bel.getBELClass() == BELClass.BEL && belPin.isInput()) {

                    // If this route branch terminates here ...
                    if (branchesCount == 0) {
                        // ... and it routed through a LUT along the way
                        if (routeThruLutInput != null) {
                            // Check that a routethru cell exists

                            Cell belCell = siteInst.getCell(bel);
                            Cell routeThruCell = siteInst.getCell(routeThruLutInput.getBEL());
                            if (routeThruCell == null) {
                                // Routethru cell does not exist, create one

                                // Make sure nothing placed there already
                                if (siteInst.getCell(routeThruLutInput.getBEL()) != null) {
                                    throw new RuntimeException("Routethru inferred for " + siteInst.getSiteName() + "/" + routeThruLutInput.getBELName()
                                        + " but it is already occupied");
                                }

                                routeThruCell = new Cell(belCell.getName(), routeThruLutInput.getBEL());
                                routeThruCell.setRoutethru(true);
                                routeThruCell.setType(belCell.getType());
                                routeThruCell.setSiteInst(siteInst);
                                siteInst.getCellMap().put(routeThruLutInput.getBELName(), routeThruCell);
                                routeThruCell.addPinMapping(routeThruLutInput.getName(), belCell.getLogicalPinMapping(belPinName));
                            }
                            String physicalPin = routeThruLutInput.getName();
                            String logicalPin = belCell.getLogicalPinMapping(belPinName);
                            if (routeThruCell.getSiteInst() != siteInst ||
                                !routeThruCell.isRoutethru() ||
                                !routeThruCell.getLogicalPinMapping(physicalPin).equals(logicalPin)) {
                                throw new RuntimeException("Invalid routethru cell: " + routeThruCell);
                            }
                        }
                    } else if (belName.endsWith("LUT")) {
                        assert (routeThruLutInput == null);

                        routeThruLutInput = belPin;
                    }
                }

                siteInst.routeIntraSiteNet(net, belPin, belPin);
                break;
            }
            case SITE_P_I_P:{
                PhysSitePIP.Reader spReader = segment.getSitePIP();
                SiteInst siteInst = getSiteInst(spReader.getSite(), design, strings);
                siteInst.addSitePIP(strings.get(spReader.getBel()),
                                    strings.get(spReader.getPin()));
                break;
            }
            case SITE_PIN: {
                PhysSitePin.Reader spReader = segment.getSitePin();
                SiteInst siteInst = getSiteInst(spReader.getSite(), design, strings);
                String pinName = strings.get(spReader.getPin());
                if (siteInst == null && net.isStaticNet()) {
                    Site site = design.getDevice().getSite(strings.get(spReader.getSite()));
                    siteInst = new SiteInst(STATIC_SOURCE + tieoffInstanceCount++, site.getSiteTypeEnum());
                    siteInst.place(site);
                }

                net.addPin(new SitePinInst(pinName, siteInst), false);

                assert(routeThruLutInput == null);
                break;
            }
            case _NOT_IN_SCHEMA: {
                throw new RuntimeException("ERROR: Unknown route segment type");
            }
        }

        for (int j=0; j < branchesCount; j++) {
            RouteBranch.Reader bReader = branches.get(j);
            readRouteBranch(bReader, net, design, strings, routeThruLutInput);
        }

    }

    private static void readDesignProperties(PhysNetlist.Reader physNetlist, Design design,
                                                Enumerator<String> strings) {
        StructList.Reader<Property.Reader> props = physNetlist.getProperties();
        int propCount = props.size();
        for (int i=0; i < propCount; i++) {
            Property.Reader pReader = props.get(i);
            String key = strings.get(pReader.getKey());
            if (DISABLE_AUTO_IO_BUFFERS.equals(key)) {
                boolean setAutoIOBuffers = "0".equals(strings.get(pReader.getValue()));
                design.setAutoIOBuffers(setAutoIOBuffers);
            } else if (OUT_OF_CONTEXT.equals(key)) {
                boolean isDesignOOC = "1".equals(strings.get(pReader.getValue()));
                design.setDesignOutOfContext(isDesignOOC);
            }
        }
    }

    private static SiteInst getSiteInst(int stringIdx, Design design, Enumerator<String> strings) {
        String siteName = strings.get(stringIdx);
        Site site = design.getDevice().getSite(siteName);
        if (site == null) {
            throw new RuntimeException("ERROR: Unknown site " + siteName +
                    " found while parsing routing");
        }
        SiteInst siteInst = design.getSiteInstFromSite(site);
        if (siteInst == null && site.getSiteTypeEnum() == SiteTypeEnum.TIEOFF) {
            // Create a dummy TIEOFF SiteInst
            siteInst = new SiteInst(STATIC_SOURCE + tieoffInstanceCount++, site.getSiteTypeEnum());
            siteInst.place(site);
        }
        return siteInst;
    }

    private static void checkNetTypeFromCellNet(Map<String, PhysNet.Reader> cellPinToPhysicalNet, EDIFNet net, Enumerator<String> strings) {
        // Expand EDIFNet and make sure sink cell pins that are part of a
        // physical net are annotated as a VCC or GND net.
        //
        // It is harder to verify if the VCC/GND net type is correct, because
        // site local inverters may convert signals on a VCC to a GND net or
        // vise versa.
        //
        // If the full physical net is present and the inverting site pips are
        // labelled, then it would be possible to confirm if the NetType was
        // always correct.
        Queue<EDIFNet> netsToExpand = new ArrayDeque<EDIFNet>();
        netsToExpand.add(net);

        while (!netsToExpand.isEmpty()) {
            net = netsToExpand.remove();
            for (EDIFPortInst portInst : net.getPortInsts()) {
                if (portInst.isOutput() && !portInst.isTopLevelPort()) {
                    // Only following downstream connections.
                    continue;
                }

                if (portInst.isInput() && portInst.isTopLevelPort()) {
                    // Only following downstream connections.
                    continue;
                }

                if (portInst.isTopLevelPort()) {
                    // Follow net to parent cell (if any)
                    EDIFCell parent = portInst.getParentCell();
                    if (parent != null) {
                        EDIFNet outerNet = parent.getInternalNet(portInst);
                        if (outerNet != null && outerNet != net) {
                            netsToExpand.add(outerNet);
                        }
                    }
                } else {
                    // Follow net to child cell (if any) or add to sink port
                    // list.
                    EDIFNet innerNet = portInst.getInternalNet();
                    if (innerNet != null) {
                        netsToExpand.add(innerNet);
                    } else {
                        PhysNet.Reader physNet = cellPinToPhysicalNet.get(portInst.getFullName());
                        if (physNet != null) {
                            if (physNet.getType() != PhysNetlist.NetType.VCC && physNet.getType() != PhysNetlist.NetType.GND) {
                                throw new RuntimeException(String.format("ERROR: Net %s connected to cell pin %s should be VCC or GND but is %s", strings.get(physNet.getName()), portInst.getFullName(), physNet.getType().name()));
                            }
                        }
                    }
                }
            }
        }
    }

    private static void mapBelPinsToPhysicalNets(Map<String, PhysNet.Reader> belPinToPhysicalNet, PhysNet.Reader netReader, RouteBranch.Reader routeBranch, Enumerator<String> strings) {
        // Populate a map from strings formatted like "<site>/<bel>/<bel pin>"
        // to PhysNet by recursively expanding routing branches.

        RouteSegment.Reader segment = routeBranch.getRouteSegment();
        if (segment.which() == RouteSegment.Which.BEL_PIN) {
            PhysBelPin.Reader bpReader = segment.getBelPin();
            belPinToPhysicalNet.put(strings.get(bpReader.getSite()) + "/" + strings.get(bpReader.getBel()) + "/" + strings.get(bpReader.getPin()), netReader);
        }

        for (PhysNetlist.RouteBranch.Reader childBranch : routeBranch.getBranches()) {
            mapBelPinsToPhysicalNets(belPinToPhysicalNet, netReader, childBranch, strings);
        }
    }

    private static void checkConstantRoutingAndNetNaming(PhysNetlist.Reader PhysicalNetlist, EDIFNetlist netlist, Enumerator<String> strings) {
        // Checks that constant routing and net names are valid.
        //
        // Specifically:
        //  - At most 1 GND and 1 VCC nets should be present
        //  - The GND and VCC nets names conform to Nets.GND_NET and Nets.VCC_NET.
        //  - Each net name should be unique
        //  - All EDIFPortInst sinks on nets driven from VCC or GND cells
        //    should be connected to nets marked as either VCC or GND nets.

        // First scan physical nets to create a mapping from BEL pins to
        // physical nets.
        //
        // At this time, check that net names are unique.  If the VCC or GND
        // nets appear, ensure they conform with the constant Nets.GND_NET
        // and Nets.VCC_NET.
        boolean foundGndNet = false;
        boolean foundVccNet = false;
        Map<String, PhysNet.Reader> belPinToPhysicalNet = new HashMap<String, PhysNet.Reader>();
        Set<Integer> netNames = new HashSet<Integer>();
        for (PhysNet.Reader physNet : PhysicalNetlist.getPhysNets()) {
            for (PhysNetlist.RouteBranch.Reader routeBranch : physNet.getSources()) {
                mapBelPinsToPhysicalNets(belPinToPhysicalNet, physNet, routeBranch, strings);
            }

            for (PhysNetlist.RouteBranch.Reader routeBranch : physNet.getStubs()) {
                mapBelPinsToPhysicalNets(belPinToPhysicalNet, physNet, routeBranch, strings);
            }

            if (!netNames.add(physNet.getName())) {
                throw new RuntimeException(String.format("ERROR: Net %s appears in physical netlist more than once?", strings.get(physNet.getName())));
            }

            if (physNet.getType() == PhysNetlist.NetType.VCC) {
                if (foundVccNet) {
                    throw new RuntimeException("ERROR: VCC net type appears more than once in physical netlist?");
                }
                foundVccNet = true;

                String netName = strings.get(physNet.getName());
                if (!netName.equals(Net.VCC_NET)) {
                    throw new RuntimeException("ERROR: Invalid VCC Net " + netName +
                            ", should be named " + Net.VCC_NET);
                }
            }

            if (physNet.getType() == PhysNetlist.NetType.GND) {
                if (foundGndNet) {
                    throw new RuntimeException("ERROR: GND net type appears more than once in physical netlist?");
                }
                foundGndNet = true;

                String netName = strings.get(physNet.getName());
                if (!netName.equals(Net.GND_NET)) {
                    throw new RuntimeException("ERROR: Invalid GND Net " + netName +
                            ", should be named " + Net.GND_NET);
                }
            }
        }

        // Iterate over placements and map cell pins to physical nets.
        Map<String, PhysNet.Reader> cellPinToPhysicalNet = new HashMap<String, PhysNet.Reader>();
        for (CellPlacement.Reader placement : PhysicalNetlist.getPlacements()) {
            for (PinMapping.Reader pinMap : placement.getPinMap()) {
                String key = strings.get(placement.getSite()) + "/" + strings.get(pinMap.getBel()) + "/" + strings.get(pinMap.getBelPin());
                PhysNet.Reader net = belPinToPhysicalNet.get(key);
                if (net != null) {
                    cellPinToPhysicalNet.put(strings.get(placement.getCellName()) + "/"  + strings.get(pinMap.getCellPin()), net);
                }
            }
        }

        // Search the EDIFNetlist for sinks from VCC or GND nets.  Find the
        // EDIFPortInst sinks on those nets, and see if a physical net
        // corresponds to that cell pin.
        //
        // If so, verify that the physical net is marked with either VCC or
        // GND.
        //
        // Note: Sink port instances on the VCC net may end up in the GND
        // physical net, or vice versa. This can occur when a constant net is
        // run through a site local inverter.  Modelling these site local
        // inverters is not done here, hence why the requirement is only that
        // the net type be either VCC or GND.
        for (EDIFCellInst leafEdifCellInst : netlist.getAllLeafCellInstances()) {
            EDIFCell leafEdifCell = leafEdifCellInst.getCellType();
            String leafEdifCellName = leafEdifCell.getName();

            EDIFPortInst portInst;
            if (leafEdifCellName.equals("VCC")) {
                portInst = leafEdifCellInst.getPortInst("P");
            } else if (leafEdifCellName.equals("GND")) {
                portInst = leafEdifCellInst.getPortInst("G");
            } else {
                continue;
            }

            if (portInst == null) {
                // Cell must be unplaced and/or unconnected
                continue;
            }
            EDIFNet net = portInst.getNet();
            checkNetTypeFromCellNet(cellPinToPhysicalNet, net, strings);
        }
    }

    /**
     * Examines a design to ensure that the provided macro placement is consistent with the
     * macro definition in the library.
     * @param design The placed design to be checked
     */
    private static void checkMacros(Design design) {
        EDIFNetlist netlist = design.getNetlist();
        List<EDIFHierCellInst> leaves = netlist.getTopCell().getAllLeafDescendants();
        EDIFLibrary macros = Design.getMacroPrimitives(design.getDevice().getSeries());
        for (EDIFHierCellInst leaf : leaves) {
            if (macros.containsCell(leaf.getCellType())) {
                EDIFCell macro = macros.getCell(leaf.getCellName());
                // Check that the macro children instances have the same placement status
                // (all placed or none are placed)
                Boolean isPlaced = null;
                boolean inconsistentPlacement = false;
                String macroName = leaf.getFullHierarchicalInstName() + EDIFTools.EDIF_HIER_SEP;
                List<EDIFHierCellInst> macroLeaves = macro.getAllLeafDescendants();
                for (EDIFHierCellInst inst : macroLeaves) {
                    String cellName = macroName + inst.getFullHierarchicalInstName();
                    Cell cell = design.getCell(cellName);
                    boolean isCellPlaced = !(cell == null || !cell.isPlaced());
                    if (isPlaced == null) isPlaced = isCellPlaced;
                    else if (isPlaced != isCellPlaced) {
                        inconsistentPlacement = true;
                    }
                }
                if (inconsistentPlacement) {
                    System.err.println("ERROR: Inconsistent macro placement for " + macroName
                            + ", please ensure all member cell instances are either "
                            + "unplaced or fully placed: ");
                    for (EDIFHierCellInst inst : macroLeaves) {
                        String cellName = macroName + inst.getFullHierarchicalInstName();
                        Cell cell = design.getCell(cellName);
                        boolean isCellPlaced = !(cell == null || !cell.isPlaced());
                        System.err.println("\t" + cellName + " is " + (isCellPlaced ? "placed" : "unplaced"));
                    }
                }

            }
        }
    }
}

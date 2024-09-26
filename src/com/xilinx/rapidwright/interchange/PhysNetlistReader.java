/*
 * Copyright (c) 2020-2022, Xilinx, Inc.
 * Copyright (c) 2022-2024, Advanced Micro Devices, Inc.
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
import com.xilinx.rapidwright.design.DesignTools;
import com.xilinx.rapidwright.design.Net;
import com.xilinx.rapidwright.design.NetType;
import com.xilinx.rapidwright.design.SiteInst;
import com.xilinx.rapidwright.design.SitePinInst;
import com.xilinx.rapidwright.design.Unisim;
import com.xilinx.rapidwright.device.BEL;
import com.xilinx.rapidwright.device.BELPin;
import com.xilinx.rapidwright.device.Device;
import com.xilinx.rapidwright.device.PIP;
import com.xilinx.rapidwright.device.Site;
import com.xilinx.rapidwright.device.SitePIP;
import com.xilinx.rapidwright.device.SitePin;
import com.xilinx.rapidwright.device.SiteTypeEnum;
import com.xilinx.rapidwright.device.Tile;
import com.xilinx.rapidwright.device.Wire;
import com.xilinx.rapidwright.edif.EDIFCell;
import com.xilinx.rapidwright.edif.EDIFCellInst;
import com.xilinx.rapidwright.edif.EDIFHierCellInst;
import com.xilinx.rapidwright.edif.EDIFHierNet;
import com.xilinx.rapidwright.edif.EDIFHierPortInst;
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
import com.xilinx.rapidwright.tests.CodePerfTracker;
import org.capnproto.MessageReader;
import org.capnproto.PrimitiveList;
import org.capnproto.ReaderOptions;
import org.capnproto.StructList;
import org.capnproto.TextList;
import org.python.google.common.base.Enums;
import org.python.google.common.base.Optional;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.function.Consumer;

public class PhysNetlistReader {

    protected static final String DISABLE_AUTO_IO_BUFFERS = "DISABLE_AUTO_IO_BUFFERS";
    protected static final String OUT_OF_CONTEXT = "OUT_OF_CONTEXT";

    public static boolean VALIDATE_MACROS_PLACED_FULLY = false;

    /**
     *  Checks that constant routing and net names are valid.
     *  This incurs a runtime overhead.
     */
    public static boolean CHECK_CONSTANT_ROUTING_AND_NET_NAMING = false;

    /**
     * Examines a design to ensure that the provided macro placement is consistent with the
     * macro definition in the library.
     * This incurs a runtime overhead.
     */
    public static boolean CHECK_MACROS_CONSISTENT = false;


    /**
     * When reading placement for physical netlist cells, check for the presence of and
     * consistency with logical netlist cells.
     * This incurs a runtime overhead.
     */
    public static boolean CHECK_AND_CREATE_LOGICAL_CELL_IF_NOT_PRESENT = false;

    protected final Design design;
    protected Device device;

    protected List<String> strings;

    protected Map<Integer, SiteInst> siteInsts;

    protected Map<Integer, Tile> tiles;

    protected PIPCache pipCache;

    protected BELPinCache belPinCache;

    public PhysNetlistReader(EDIFNetlist netlist) {
        design = new Design();
        if (netlist != null) {
            design.setNetlist(netlist);
            design.setName(netlist.getName());
        }
    }

    protected Design read(String physNetlistFileName) throws IOException {
        CodePerfTracker t = new CodePerfTracker("Read PhysNetlist");

        t.start("Read File");
        ReaderOptions rdOptions =
                new ReaderOptions(ReaderOptions.DEFAULT_READER_OPTIONS.traversalLimitInWords * 64,
                ReaderOptions.DEFAULT_READER_OPTIONS.nestingLimit * 128);
        MessageReader readMsg = Interchange.readInterchangeFile(physNetlistFileName, rdOptions);

        PhysNetlist.Reader physNetlist = readMsg.getRoot(PhysNetlist.factory);

        design.setPartName(physNetlist.getPart().toString());
        device = design.getDevice();

        t.stop().start("Read Strings");
        strings = readAllStrings(physNetlist);

        if (CHECK_CONSTANT_ROUTING_AND_NET_NAMING) {
            t.stop().start("Check Constant Routing & Net Naming");
            checkConstantRoutingAndNetNaming(physNetlist);
        }

        t.stop().start("Read SiteInsts");
        readSiteInsts(physNetlist);

        t.stop().start("Read Placement");
        readPlacement(physNetlist);

        if (CHECK_MACROS_CONSISTENT) {
            t.stop().start("Check Macros");
            checkMacros();
        }

        t.stop().start("Read Routing");
        readNullNet(physNetlist);
        readRouting(physNetlist);

        t.stop().start("Read Design Props");
        readDesignProperties(physNetlist);

        t.stop().printSummary();

        return design;
    }

    public static Design readPhysNetlist(String physNetlistFileName) throws IOException {
        return readPhysNetlist(physNetlistFileName, null);
    }

    public static Design readPhysNetlist(String physNetlistFileName, EDIFNetlist netlist) throws IOException {
        PhysNetlistReader reader = new PhysNetlistReader(netlist);
        return reader.read(physNetlistFileName);
    }

    public static List<String> readAllStrings(PhysNetlist.Reader physNetlist) {
        TextList.Reader strListReader = physNetlist.getStrList();
        int strCount = strListReader.size();
        List<String> allStrings = new ArrayList<>(strCount);
        for (int i=0; i < strCount; i++) {
            String str = strListReader.get(i).toString();
            allStrings.add(str);
        }
        return allStrings;
    }

    protected void readSiteInsts(PhysNetlist.Reader physNetlist) {
        StructList.Reader<SiteInstance.Reader> siteInstsReader = physNetlist.getSiteInsts();
        int siteInstCount = siteInstsReader.size();
        if (siteInstCount == 0 && physNetlist.getPlacements().size() > 0) {
            System.out.println("WARNING: Missing SiteInst information in *.phys file.  RapidWright "
                    + "will attempt to infer the proper SiteInst, however, it is recommended that "
                    + "SiteInst information be specified to avoid SiteTypeEnum mismatch problems.");
        }

        siteInsts = new HashMap<>(siteInstCount);

        for (int i=0; i < siteInstCount; i++) {
            SiteInstance.Reader r = siteInstsReader.get(i);
            String siteName = strings.get(r.getSite());
            SiteTypeEnum type = SiteTypeEnum.valueOf(strings.get(r.getType()));
            SiteInst si = design.createSiteInst(siteName, type, device.getSite(siteName));
            siteInsts.put(r.getSite(), si);
        }
    }

    protected void readPlacement(PhysNetlist.Reader physNetlist) {
        StructList.Reader<PhysCell.Reader> physCellReaders = physNetlist.getPhysCells();
        int physCellCount = physCellReaders.size();
        Map<Integer, PhysCellType> physCells = new HashMap<>(physCellCount);
        for (int i=0; i < physCellCount; i++) {
            PhysCell.Reader reader = physCellReaders.get(i);
            PhysCellType physType = reader.getPhysType();
            assert(physType == PhysCellType.LOCKED || physType == PhysCellType.PORT);
            physCells.put(reader.getCellName(), physType);
        }

        StructList.Reader<CellPlacement.Reader> placements = physNetlist.getPlacements();
        int placementCount = placements.size();
        EDIFNetlist netlist = design.getNetlist();
        EDIFLibrary macroPrims = Design.getMacroPrimitives(device.getSeries());
        Set<Integer> otherBELLocs = new HashSet<>();
        for (int i=0; i < placementCount; i++) {
            CellPlacement.Reader placement = placements.get(i);
            String cellName = strings.get(placement.getCellName());
            SiteInst siteInst = getSiteInst(placement.getSite());
            assert(siteInst != null);
            String belName = strings.get(placement.getBel());
            PhysCellType physType = physCells.get(placement.getCellName());
            if (physType == PhysCellType.LOCKED) {
                siteInst.setSiteLocked(true);
                Cell c = siteInst.getCell(belName);
                if (c == null) {
                    BEL bel = siteInst.getBEL(belName);
                    c = new Cell(Cell.LOCKED, bel);
                    c.setType(strings.get(placement.getType()));
                    c.setBELFixed(placement.getIsBelFixed());
                    c.setNullBEL(bel == null);
                    siteInst.addCell(c);
                }
                c.setLocked(true);

                // c Alternative Blocked Site Type // TODO
            } else if (physType == PhysCellType.PORT) {
                Cell portCell = new Cell(cellName,siteInst.getBEL(belName));
                portCell.setType(Cell.PORT_TYPE);
                siteInst.addCell(portCell);
                portCell.setBELFixed(placement.getIsBelFixed());
                portCell.setSiteFixed(placement.getIsSiteFixed());
            } else {
                assert(physType == null);
                String cellType = strings.get(placement.getType());

                if (CHECK_AND_CREATE_LOGICAL_CELL_IF_NOT_PRESENT) {
                    if (netlist == null) {
                        throw new RuntimeException("No EDIFNetlist supplied");
                    }

                    EDIFCellInst cellInst = netlist.getCellInstFromHierName(cellName);
                    if (cellInst == null) {
                        Optional<Unisim> maybeUnisim = Enums.getIfPresent(Unisim.class, cellType);
                        Unisim unisim = maybeUnisim.isPresent() ? maybeUnisim.get() : null;
                        if (unisim == null) {
                            EDIFCell cell = new EDIFCell(null, cellType);
                            new EDIFCellInst(cellName, cell, null);
                        } else {
                            Design.createUnisimInst(null, cellName, unisim);
                        }
                    } else {
                        assert (cellInst.getCellType().getName().equals(cellType));
                    }
                }
                if (macroPrims.containsCell(cellType)) {
                    throw new RuntimeException("ERROR: Placement for macro primitive "
                            + cellType + " (instance "+cellName+") is "
                            + "invalid.  Please only provide placements for the macro's children "
                            + "leaf cells: " + cellType +".");
                }

                BEL bel = siteInst.getBEL(belName);
                if (bel == null) {
                    String siteName = strings.get(placement.getSite());
                    throw new RuntimeException(
                          "ERROR: The placement specified on BEL " + siteName + "/"
                          + belName + " could not be found in the target "
                          + "device.");
                }
                if (bel.getBELType().equals("HARD0") || bel.getBELType().equals("HARD1")) {
                    String siteName = strings.get(placement.getSite());
                    throw new RuntimeException(
                              "ERROR: The placement specified on BEL " + siteName + "/"
                            + bel.getName() + " is not valid. HARD0 and HARD1 BEL types do not "
                            + "require placed cells.");
                }
                Cell existingCell = siteInst.getCell(bel);
                if (existingCell != null) {
                    String siteName = strings.get(placement.getSite());
                    throw new RuntimeException(
                            "ERROR: Cell \"" + cellName + "\" placement on BEL " + siteName + "/"
                                    + belName + " conflicts with previously placed cell \"" + existingCell.getName()
                                    + "\".");
                }
                Cell cell = new Cell(cellName, bel);
                cell.setBELFixed(placement.getIsBelFixed());
                cell.setSiteFixed(placement.getIsSiteFixed());
                cell.setType(cellType);
                if (cell.isFFRoutethruCell()) {
                    cell.setRoutethru(true);
                    cell.setSiteInst(siteInst);
                    siteInst.getCellMap().put(belName, cell);
                } else {
                    siteInst.addCell(cell);
                }

                if (placement.hasOtherBels()) {
                    PrimitiveList.Int.Reader otherBELs = placement.getOtherBels();
                    int otherBELCount = otherBELs.size();
                    for (int j=0; j < otherBELCount; j++) {
                        otherBELLocs.add(otherBELs.get(j));
                    }
                }
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
                    if (otherBELLocs.remove(pinMapping.getBel())) {
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
                        c.setType(strings.get(placement.getType()));
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

            assert(otherBELLocs.isEmpty());
        }

        // Validate macro primitives are placed fully
        if (VALIDATE_MACROS_PLACED_FULLY) {
            Map<String, List<String>> macroLeafChildren = new HashMap<>();
            Set<String> checked = new HashSet<>();
            for (Cell c : design.getCells()) {
                EDIFCell cellType = c.getParentCell();
                if (cellType != null && macroPrims.containsCell(cellType)) {
                    String parentHierName = c.getParentHierarchicalInstName();
                    if (checked.contains(parentHierName)) continue;
                    List<String> missingPlacements = null;
                    List<String> childrenNames = macroLeafChildren.computeIfAbsent(cellType.getName(),
                            (k) -> EDIFTools.getMacroLeafCellNames(cellType));
                    //for (EDIFCellInst inst : cellType.getCellInsts()) { // TODO - Fix up loop list
                    for (String childName : childrenNames) {
                        if (childName.equals("VCC") || childName.equals("GND")) {
                            // Ignore VCC (e.g. from FDRS_1) and GND cells
                            continue;
                        }
                        String childCellName = parentHierName + EDIFTools.EDIF_HIER_SEP + childName;
                        Cell child = design.getCell(childCellName);
                        if (child == null) {
                            if (missingPlacements == null) missingPlacements = new ArrayList<>();
                            missingPlacements.add(childName + " (" + childCellName + ")");
                        }
                    }
                    if (missingPlacements != null && !cellType.getName().equals("IOBUFDS")) {
                        throw new RuntimeException("ERROR: Macro primitive '" + parentHierName
                                + "' is not fully placed. Expected placements for all child cells: "
                                + cellType.getCellInsts() + ", but missing placements "
                                + "for cells: " + missingPlacements);
                    }

                    checked.add(parentHierName);
                }
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

    protected void readNullNet(PhysNetlist.Reader physNetlist) {
        PhysNet.Reader nullNet = physNetlist.getNullNet();
        StructList.Reader<RouteBranch.Reader> stubs = nullNet.getStubs();
        int stubCount = stubs.size();
        for (int k=0; k < stubCount; k++) {
            RouteSegment.Reader segment = stubs.get(k).getRouteSegment();
            PhysSitePIP.Reader spReader = segment.getSitePIP();
            SiteInst sitePIPSiteInst = getSiteInst(spReader.getSite());
            sitePIPSiteInst.addSitePIP(strings.get(spReader.getBel()),
                    strings.get(spReader.getPin()));
        }
    }

    private void readRouting(PhysNetlist.Reader physNetlist) {
        tiles = new HashMap<>();
        pipCache = new PIPCache(new HashMap<>(), strings);
        belPinCache = new BELPinCache(new HashMap<>(), strings);

        StructList.Reader<PhysNetlist.PhysNet.Reader> nets = physNetlist.getPhysNets();

        // For single-threaded read, add net to design object immediately
        readRouting(nets, design::addNet);

        tiles = null;
        pipCache = null;
        belPinCache = null;
    }

    protected void readRouting(StructList.Reader<PhysNet.Reader> nets, Consumer<Net> addNetToDesign) {
        int netCount = nets.size();
        Set<Wire> stubWires = new HashSet<>();
        for (int i=0; i < netCount; i++) {
            PhysNet.Reader netReader = nets.get(i);
            String netName = strings.get(netReader.getName());
            Net net = new Net(netName);
            net.setDesign(design);
            net.setType(getNetType(netReader, netName));
            addNetToDesign.accept(net);

            // Stub Nodes
            if (netReader.hasStubNodes()) {
                StructList.Reader<PhysNode.Reader> stubNodes = netReader.getStubNodes();
                int stubNodeCount = stubNodes.size();
                for (int j = 0; j < stubNodeCount; j++) {
                    PhysNode.Reader stubNodeReader = stubNodes.get(j);
                    Tile tile = getTile(stubNodeReader.getTile());
                    Integer wireIdx = getWireIndex(tile, stubNodeReader.getWire());
                    Wire wire = new Wire(tile, wireIdx);
                    boolean added = stubWires.add(wire);
                    assert (added);
                }
            }

            // Sources
            if (netReader.hasSources()) {
                StructList.Reader<RouteBranch.Reader> routeSrcs = netReader.getSources();
                int routeSrcsCount = routeSrcs.size();
                for (int j = 0; j < routeSrcsCount; j++) {
                    RouteBranch.Reader branchReader = routeSrcs.get(j);
                    readRouteBranch(stubWires, branchReader, net, null);
                }
            }
            // Stubs
            if (netReader.hasStubs()) {
                StructList.Reader<RouteBranch.Reader> routeStubs = netReader.getStubs();
                int routeStubsCount = routeStubs.size();
                for (int j=0; j < routeStubsCount; j++) {
                    RouteBranch.Reader branchReader = routeStubs.get(j);
                    readRouteBranch(stubWires, branchReader, net, null);
                }
            }

            // Stub nodes that don't belong on a PIP
            for (Wire wire : stubWires) {
                PIP pip = new PIP(wire.getTile(), wire.getWireIndex(), PIP.NULL_END_WIRE_IDX);
                net.addPIP(pip);
            }
            stubWires.clear();

            // Nets with more than one routed source (e.g. A_O and AMUX) should have
            // the first PIP driven by either source marked as a logical driver
            if (net.getType() == NetType.WIRE) {
                SitePinInst altSource = net.getAlternateSource();
                if (altSource != null) {
                    assert(!net.isClockNet());

                    SitePinInst source = net.getSource();
                    assert(source.getTile() == altSource.getTile());

                    DesignTools.updatePinsIsRouted(net);
                    if (source.isRouted() && altSource.isRouted()) {
                        Tile sourceTile = altSource.getTile();
                        for (PIP pip : net.getPIPs()) {
                            if (pip.getTile() != sourceTile) {
                                continue;
                            }
                            if (pip.isRouteThru()) {
                                continue;
                            }
                            SitePin sp = pip.getStartNode().getSitePin();
                            if (sp.getPinName().equals(source.getName())) {
                                pip.setIsLogicalDriver(true);
                                break;
                            }
                        }
                    }
                }
            }
        }
    }

    protected void addBELPinToSiteInst(BELPin belPin, SiteInst siteInst, Net net) {
        siteInst.routeIntraSiteNet(net, belPin, belPin);
    }

    protected void addCellToSiteInst(Cell cell) {
        SiteInst siteInst = cell.getSiteInst();
        siteInst.getCellMap().put(cell.getBELName(), cell);
    }

    protected void addSitePIPToSiteInst(SitePIP sitePIP, SiteInst siteInst) {
        siteInst.addSitePIP(sitePIP);
    }

    protected SitePinInst createSitePin(int pinNameIdx, SiteInst siteInst, Net net) {
        BELPin belPin = getBELPin(siteInst, pinNameIdx, pinNameIdx);
        // An output BELPin is an input site pin
        boolean outputPin = !belPin.isOutput();
        String pinName = strings.get(pinNameIdx);
        SitePinInst pin = new SitePinInst(outputPin, pinName, siteInst);
        net.addPin(pin);
        return pin;
    }

    private void readRouteBranch(Set<Wire> stubWires,
                                 RouteBranch.Reader branchReader,
                                 Net net,
                                 BELPin routeThruLutInput) {
        RouteBranch.RouteSegment.Reader segment = branchReader.getRouteSegment();
        StructList.Reader<RouteBranch.Reader> branches = null;
        int branchesCount;
        if (branchReader.hasBranches()) {
            branches = branchReader.getBranches();
            branchesCount = branches.size();
        } else {
            branchesCount = 0;
        }
        switch(segment.which()) {
            case PIP:{
                PhysPIP.Reader pReader = segment.getPip();
                Tile tile = getTile(pReader.getTile());
                if (tile == null) {
                    String wire0 = strings.get(pReader.getWire0());
                    String wire1 = strings.get(pReader.getWire1());
                    throw new RuntimeException("ERROR: Tile " + strings.get(pReader.getTile()) + " for pip from wire " + wire0 + " to wire " + wire1 + " not found.");
                }

                PIP pip = getPIP(tile, pReader.getWire0(), pReader.getWire1());
                if (pip == null) {
                    String wire0 = strings.get(pReader.getWire0());
                    String wire1 = strings.get(pReader.getWire1());
                    System.err.println("WARNING: PIP for tile " + strings.get(pReader.getTile()) +
                            " from wire " + wire0 + " to wire " + wire1 + " not found;" +
                            " omitting from net " + net.getName());
                } else {
                    pip.setIsPIPFixed(pReader.getIsFixed());
                    pip.setIsReversed(!pReader.getForward());

                    if (stubWires.remove(pip.getEndWire())) {
                        pip.setIsStub(true);
                    }

                    net.addPIP(pip);
                }
                break;
            }
            case BEL_PIN:{
                PhysBelPin.Reader bpReader = segment.getBelPin();
                SiteInst siteInst = getOrCreatePlacedSiteInst(bpReader.getSite(), net);
                BELPin belPin = getBELPin(siteInst, bpReader.getBel(), bpReader.getPin());

                // Examine LUT input pins only
                if (belPin.isInput()) {
                    BEL bel = belPin.getBEL();
                    if (bel.isLUT()) {
                        // If this route branch terminates here ...
                        if (branchesCount == 0) {
                            // ... and it routed through a LUT along the way
                            if (routeThruLutInput != null) {
                                // Check that a routethru cell exists

                                String belPinName = belPin.getName();

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
                                    addCellToSiteInst(routeThruCell);
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
                        } else {
                            assert (routeThruLutInput == null);

                            routeThruLutInput = belPin;
                        }
                    }
                } else {
                    assert(!belPin.isInput());

                    // Only output BEL pins affect intra-site routing
                    addBELPinToSiteInst(belPin, siteInst, net);
                }
                break;
            }
            case SITE_P_I_P:{
                PhysSitePIP.Reader spReader = segment.getSitePIP();
                SiteInst siteInst = getOrCreatePlacedSiteInst(spReader.getSite(), net);
                BELPin belPin = getBELPin(siteInst, spReader.getBel(), spReader.getPin());
                SitePIP sitePIP = siteInst.getSitePIP(belPin);
                addSitePIPToSiteInst(sitePIP, siteInst);
                break;
            }
            case SITE_PIN: {
                PhysSitePin.Reader spReader = segment.getSitePin();
                SiteInst siteInst = getOrCreatePlacedSiteInst(spReader.getSite(), net);
                createSitePin(spReader.getPin(), siteInst, net);
                assert(routeThruLutInput == null);
                break;
            }
            case _NOT_IN_SCHEMA: {
                throw new RuntimeException("ERROR: Unknown route segment type");
            }
        }

        for (int j=0; j < branchesCount; j++) {
            RouteBranch.Reader bReader = branches.get(j);
            readRouteBranch(stubWires, bReader, net, routeThruLutInput);
        }

    }

    protected void readDesignProperties(PhysNetlist.Reader physNetlist) {
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

    protected SiteInst getSiteInst(int stringIdx) {
        return siteInsts.get(stringIdx);
    }

    private SiteInst getOrCreatePlacedSiteInst(int siteIdx, Net net) {
        SiteInst siteInst = siteInsts.computeIfAbsent(siteIdx, (k) -> {
            Site site = device.getSite(strings.get(k));
            if (!net.isStaticNet()) {
                throw new RuntimeException("ERROR: SiteInst for Site " + site.getName() + " not found.");
            }
            // Create a dummy TIEOFF SiteInst
            String name = SiteInst.STATIC_SOURCE + "_" + site.getName();
            SiteInst si = new SiteInst(name, site.getSiteTypeEnum());
            si.place(site);
            si.setDesign(design);
            // Ensure it is not attached to the design
            assert(design.getSiteInstFromSite(site) == null);
            return si;
        });
        assert(siteInst != null && siteInst.isPlaced());
        return siteInst;
    }

    private Tile getTile(int stringIdx) {
        return tiles.computeIfAbsent(stringIdx, (i) -> {
            String tileName = strings.get(i);
            return device.getTile(tileName);
        });
    }

    private Integer getWireIndex(Tile tile, int wireStringIdx) {
        String wireName = strings.get(wireStringIdx);
        return tile.getWireIndex(wireName);
    }

    protected BELPin getBELPin(SiteInst siteInst, int belStringIdx, int pinStringIdx) {
        return belPinCache.getBELPin(siteInst, belStringIdx, pinStringIdx);
    }

    private PIP getPIP(Tile tile, int wire0StringIdx, int wire1StringIdx) {
        return pipCache.getPIP(tile, wire0StringIdx, wire1StringIdx);
    }

    private static void checkNetTypeFromCellNet(Map<String, PhysNet.Reader> cellPinToPhysicalNet,
                                                EDIFHierNet net,
                                                List<String> strings) {
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
        Queue<EDIFHierNet> netsToExpand = new ArrayDeque<>();
        netsToExpand.add(net);

        while (!netsToExpand.isEmpty()) {
            net = netsToExpand.remove();
            for (EDIFHierPortInst hierPortInst : net.getPortInsts()) {
                EDIFPortInst portInst = hierPortInst.getPortInst();
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
                    EDIFCell parent = hierPortInst.getParentCell();
                    if (parent != null) {
                        EDIFNet outerNet = parent.getInternalNet(portInst);
                        EDIFHierNet outerHierNet = new EDIFHierNet(hierPortInst.getHierarchicalInst(), outerNet);
                        if (outerNet != null && !outerHierNet.equals(net)) {
                            netsToExpand.add(outerHierNet);
                        }
                    }
                } else {
                    // Follow net to child cell (if any) or add to sink port
                    // list.
                    EDIFHierNet innerNet = hierPortInst.getInternalNet();
                    if (innerNet != null) {
                        netsToExpand.add(innerNet);
                    } else {
                        String fullName = hierPortInst.toString();
                        PhysNet.Reader physNet = cellPinToPhysicalNet.get(fullName);
                        if (physNet != null) {
                            if (physNet.getType() != PhysNetlist.NetType.VCC && physNet.getType() != PhysNetlist.NetType.GND) {
                                throw new RuntimeException(String.format("ERROR: Net %s connected to cell pin %s should be VCC or GND but is %s",
                                        strings.get(physNet.getName()), fullName, physNet.getType().name()));
                            }
                        }
                    }
                }
            }
        }
    }

    private void mapBelPinsToPhysicalNets(Map<String, PhysNet.Reader> belPinToPhysicalNet,
                                          PhysNet.Reader netReader,
                                          RouteBranch.Reader routeBranch) {
        // Populate a map from strings formatted like "<site>/<bel>/<bel pin>"
        // to PhysNet by recursively expanding routing branches.

        RouteSegment.Reader segment = routeBranch.getRouteSegment();
        if (segment.which() == RouteSegment.Which.BEL_PIN) {
            PhysBelPin.Reader bpReader = segment.getBelPin();
            belPinToPhysicalNet.put(strings.get(bpReader.getSite()) + "/" + strings.get(bpReader.getBel()) + "/" + strings.get(bpReader.getPin()), netReader);
        }

        for (PhysNetlist.RouteBranch.Reader childBranch : routeBranch.getBranches()) {
            mapBelPinsToPhysicalNets(belPinToPhysicalNet, netReader, childBranch);
        }
    }

    protected void checkConstantRoutingAndNetNaming(PhysNetlist.Reader physNetlist) {
        EDIFNetlist netlist = design.getNetlist();
        if (netlist == null) {
            throw new RuntimeException("No EDIFNetlist supplied");
        }

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
        Map<String, PhysNet.Reader> belPinToPhysicalNet = new HashMap<>();
        Set<Integer> netNames = new HashSet<>();
        for (PhysNet.Reader physNet : physNetlist.getPhysNets()) {
            for (PhysNetlist.RouteBranch.Reader routeBranch : physNet.getSources()) {
                mapBelPinsToPhysicalNets(belPinToPhysicalNet, physNet, routeBranch);
            }

            for (PhysNetlist.RouteBranch.Reader routeBranch : physNet.getStubs()) {
                mapBelPinsToPhysicalNets(belPinToPhysicalNet, physNet, routeBranch);
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
        Map<String, PhysNet.Reader> cellPinToPhysicalNet = new HashMap<>();
        for (CellPlacement.Reader placement : physNetlist.getPlacements()) {
            for (PinMapping.Reader pinMap : placement.getPinMap()) {
                String key = strings.get(placement.getSite()) + "/" + strings.get(pinMap.getBel()) + "/" + strings.get(pinMap.getBelPin());
                PhysNet.Reader net = belPinToPhysicalNet.get(key);
                if (net != null) {
                    PhysNet.Reader oldNet = cellPinToPhysicalNet.put(strings.get(placement.getCellName()) + "/"  + strings.get(pinMap.getCellPin()), net);
                    assert(oldNet == null || oldNet == net);
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
        for (EDIFHierCellInst leafEdifCellInst : netlist.getAllLeafHierCellInstances()) {
            EDIFCell leafEdifCell = leafEdifCellInst.getCellType();
            String leafEdifCellName = leafEdifCell.getName();

            EDIFHierPortInst portInst;
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
            EDIFHierNet net = portInst.getHierarchicalNet();
            checkNetTypeFromCellNet(cellPinToPhysicalNet, net, strings);
        }
    }

    /**
     * Examines a design to ensure that the provided macro placement is consistent with the
     * macro definition in the library.
     */
    protected void checkMacros() {
        EDIFNetlist netlist = design.getNetlist();
        if (netlist == null) {
            throw new RuntimeException("No EDIFNetlist supplied");
        }

        List<EDIFHierCellInst> leaves = netlist.getTopCell().getAllLeafDescendants();
        EDIFLibrary macros = Design.getMacroPrimitives(device.getSeries());
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

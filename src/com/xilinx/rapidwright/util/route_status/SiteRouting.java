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

import static com.xilinx.rapidwright.util.route_status.VersalRules.isVersalClockingSite;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;

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
import com.xilinx.rapidwright.device.SitePIP;
import com.xilinx.rapidwright.device.SitePin;
import com.xilinx.rapidwright.device.SiteTypeEnum;
import com.xilinx.rapidwright.edif.EDIFHierCellInst;
import com.xilinx.rapidwright.edif.EDIFNet;
import com.xilinx.rapidwright.edif.EDIFPortInst;
import com.xilinx.rapidwright.util.Utils;

/**
 * Checks of the site pins and site routing of nets (as stored in a checkpoint), as Vivado makes them.
 */
final class SiteRouting {

    /** The site types of I/O sites (with a pad) */
    private static final Set<SiteTypeEnum> IO_SITE_TYPES = getSiteTypes((n) -> n.contains("IOB") || n.contains("HRIO")
            || n.contains("HPIO") || n.contains("HDIO") || n.contains("XPIO"));

    /** The site types of block RAMs (and their FIFOs) */
    private static final Set<SiteTypeEnum> BLOCK_RAM_SITE_TYPES = getSiteTypes((n) -> n.startsWith("RAMB")
            || n.startsWith("FIFO"));

    /** The site types of clock buffers */
    private static final Set<SiteTypeEnum> CLOCK_BUFFER_SITE_TYPES = getSiteTypes((n) -> n.startsWith("BUF"));

    private SiteRouting() {
    }

    /**
     * Gets the site pins of a net as stored in a checkpoint (as Vivado sees them), where those of
     * a net without routing are not.
     */
    static List<SitePinInst> storedPins(Net net) {
        return net.hasPIPs() ? net.getPins() : Collections.emptyList();
    }

    /**
     * Checks whether the site routing from a site wire (through used site PIPs of routing BELs)
     * reaches an input pin of a placed cell that is mapped to a logical pin. For a signal net, the
     * pad of a placed port cell is also such a pin, and BELs with a single input that always passes
     * it on (e.g. FF_CLK_MOD of Versal slices, whose site PIPs are not stored) are followed.
     * @param staticType For a static net, the logical pin must be tied to the static source
     * (others may be tied off inside the site).
     */
    static boolean reachesPlacedCellPin(SiteInst si, String siteWire, NetType staticType) {
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
                String logicalPin = (cell == null || cell.isRoutethru()) ? null
                        : cell.getLogicalPinMapping(pin.getName());
                if (logicalPin != null && (staticType == null || isTiedTo(cell, logicalPin, staticType))) {
                    return true;
                }
                if (logicalPin == null && staticType != null && isStaticTie(si, pin, cell, staticType)) {
                    return true;
                }
                if (staticType == NetType.GND && (pin.getBELName().contains("INV") || !Utils.isSLICE(si))) {
                    // Ground is tied off at optional inverters (and in other sites than slices)
                    // without routing
                    continue;
                }
                addSiteRoutingFrom(si, pin, cell, staticType == null, queue);
            }
        }
        return false;
    }

    /**
     * Checks whether a BEL pin without a cell pin on it needs a static net tied to it: A6 of a LUT
     * must be tied high when its 5LUT is used, or when it is a routethru (e.g. to a carry chain), and
     * the select of a wide function mux used without it (as a routethru, or after unplacing the driver
     * of the select) is tied low.
     */
    private static boolean isStaticTie(SiteInst si, BELPin pin, Cell cell, NetType staticType) {
        if (staticType == NetType.VCC) {
            return isFracturedLutTie(si, pin)
                    || (cell != null && cell.isRoutethru() && pin.getName().equals("A6")
                        && pin.getBELName().endsWith("6LUT"));
        }
        return cell != null && pin.getName().equals("S0")
                && (pin.getBELName().startsWith("F7") || pin.getBELName().startsWith("F8"));
    }

    /**
     * Adds the site wires that site routing leads to from a BEL input pin: through a used site PIP, a
     * routethru cell or (for a signal net) a pass-through BEL, to the output of the BEL.
     */
    private static void addSiteRoutingFrom(SiteInst si, BELPin pin, Cell cell, boolean signal, List<String> queue) {
        SitePIP sitePIP = si.getUsedSitePIP(pin);
        if (sitePIP != null) {
            queue.add(sitePIP.getOutputPin().getSiteWireName());
        } else if ((cell != null && cell.isRoutethru()) || (signal && cell == null && isPassThroughPin(pin))) {
            for (BELPin out : pin.getBEL().getPins()) {
                if (out.isOutput()) {
                    queue.add(out.getSiteWireName());
                }
            }
        }
    }

    /** Checks whether a BEL pin is the only input of its BEL, with a site PIP through the BEL */
    static boolean isPassThroughPin(BELPin pin) {
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

    /** Checks whether a BEL pin is the A6 pin of a 6LUT whose 5LUT has a placed cell. */
    static boolean isFracturedLutTie(SiteInst si, BELPin pin) {
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
    static boolean isCascadeAddressPin(String sitePin) {
        return sitePin.startsWith("ADDR") && sitePin.endsWith("L15");
    }

    /**
     * Checks whether a logical pin of a cell is tied to a static source of the given type, either
     * in the cell's parent or, through ports of hierarchical cells, further up. (Pins inside a
     * macro tied through the macro's ports are tied off inside their site: not followed.)
     */
    static boolean isTiedTo(Cell cell, String logicalPin, NetType staticType) {
        EDIFHierCellInst inst;
        // The logical cell may be looked up by name, which is not thread safe (see
        // FlatNet.getSegments())
        synchronized (cell.getSiteInst().getDesign().getNetlist()) {
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
    static boolean isIOSite(SiteInst si) {
        return IO_SITE_TYPES.contains(si.getSiteTypeEnum());
    }

    /** Checks whether a site is that of a block RAM (or its FIFO). */
    static boolean isBlockRamSite(SiteInst si) {
        return BLOCK_RAM_SITE_TYPES.contains(si.getSiteTypeEnum());
    }

    /** Checks whether a site is that of a clock buffer. */
    static boolean isClockBufferSite(SiteInst si) {
        return CLOCK_BUFFER_SITE_TYPES.contains(si.getSiteTypeEnum());
    }

    /** Gets the site types whose names match. */
    static Set<SiteTypeEnum> getSiteTypes(Predicate<String> name) {
        Set<SiteTypeEnum> types = EnumSet.noneOf(SiteTypeEnum.class);
        for (SiteTypeEnum type : SiteTypeEnum.values()) {
            if (name.test(type.name())) {
                types.add(type);
            }
        }
        return types;
    }

    /** Checks whether a series is UltraScale or UltraScale+. */
    static boolean isUltraScale(Series series) {
        return series == Series.UltraScale || series == Series.UltraScalePlus;
    }

    /**
     * Checks whether a cell is a NoC, memory controller or processor hard block (of Versal), or an
     * I/O bitslice, whose pins may be connected to other blocks without routing.
     */
    static boolean isHardBlockCell(Cell cell) {
        String type = cell.getType();
        return type.startsWith("NOC") || type.startsWith("DDRMC") || type.equals("PS9") || type.contains("BITSLICE");
    }

    /** Checks whether the site routing of a net reaches an output site pin. */
    static boolean usesOutputSitePin(Net net) {
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
    static boolean isDrivenBySiteRouting(Net net, SitePinInst spi) {
        SiteInst si = spi.getSiteInst();
        if (si == null || !Utils.isSLICE(si)) {
            return true;
        }
        Series series = si.getDesign().getSeries();
        if (isUltraScale(series)) {
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
    static boolean hasStaleSitePin(Net net, List<Node> nodes) {
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
    static boolean isNetSitePin(Net net, SiteInst si, BELPin port) {
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
    static boolean isSiteWireDriven(SiteInst si, Net net, int siteWire, int depth) {
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
                    if (input != null && input.isInput()
                            && si.getNetFromSiteWireIndex(input.getSiteWireIndex()) == net) {
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
    static BELPin getSitePortPin(SiteInst si, SitePin sitePin) {
        String pinName = sitePin.getPinName();
        BEL bel = si.getBEL(pinName);
        if (bel == null) {
            pinName = si.getAlternateSitePinName(pinName);
            bel = (pinName == null) ? null : si.getBEL(pinName);
        }
        return (bel == null) ? null : bel.getPin(pinName);
    }

    /** Checks whether the node is that of a site pin used by the site routing of the net. */
    static boolean isSitePinUsedBy(Net net, Node node) {
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
    static boolean isSiteRoutedOutputPinNode(Net net, Node node) {
        SitePin sitePin = node.getSitePin();
        if (sitePin == null || sitePin.isInput() || !isSitePinUsedBy(net, node)) {
            return false;
        }
        Series series = net.getDesign().getSeries();
        if (!isUltraScale(series)) {
            return true;
        }
        SiteInst si = net.getDesign().getSiteInstFromSite(sitePin.getSite());
        if (!Utils.isSLICE(si)) {
            return true;
        }
        return isSiteWireDriven(si, net, getSitePortPin(si, sitePin).getSiteWireIndex(), 0);
    }

    /**
     * Checks whether a node is that of an output site pin driven by the site routing of a net, or
     * of one of its aliases.
     */
    static boolean isSiteRoutedOutputPinNode(Net net, List<Net> aliases, Node node) {
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

    static boolean isSitePinNode(Node node) {
        return node.getSitePin() != null;
    }

    /**
     * Gets the name of a site pin of a site instance in the primary type of its site. (A site type
     * may be the primary type of some sites and an alternate one of others, e.g. RAMB18E1 in
     * 7-series devices, which {@link SiteInst#getPrimarySitePinName(String)} does not tell apart.)
     */
    static String getPrimarySitePinName(SiteInst si, String pinName) {
        return (si.getSiteTypeEnum() == si.getSite().getSiteTypeEnum()) ? pinName : si.getPrimarySitePinName(pinName);
    }

    /** Gets the node of a site pin of a site instance (named as in the type of the site instance), or null. */
    static Node getSitePinNode(SiteInst si, String pinName) {
        int pinIndex = si.getSite().getPinIndex(getPrimarySitePinName(si, pinName));
        return (pinIndex < 0) ? null : si.getSite().getConnectedNode(pinIndex);
    }

    /**
     * Gets the nodes of the site pins whose port BELs are on a site wire: of the input site pins, whose
     * port BELs drive the site wire, or of the output site pins, driven by the site wire.
     */
    static List<Node> getSitePinNodes(SiteInst si, int siteWireIndex, boolean input) {
        List<Node> nodes = new ArrayList<>(1);
        for (BELPin port : si.getSiteWirePins(siteWireIndex)) {
            if (port.isSitePort() && (input ? port.isOutput() : port.isInput())) {
                Node node = getSitePinNode(si, port.getBELName());
                if (node != null) {
                    nodes.add(node);
                }
            }
        }
        return nodes;
    }

    /** Checks whether a site pin node is driven by routing (not only by the pins of other sites) */
    static boolean isReachableThroughRouting(Node node) {
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
}

/*
 *
 * Copyright (c) 2021 Ghent University.
 * Copyright (c) 2022-2025, Advanced Micro Devices, Inc.
 * All rights reserved.
 *
 * Author: Yun Zhou, Ghent University.
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

package com.xilinx.rapidwright.rwroute;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;

import com.xilinx.rapidwright.design.Cell;
import com.xilinx.rapidwright.design.Design;
import com.xilinx.rapidwright.design.DesignTools;
import com.xilinx.rapidwright.design.Net;
import com.xilinx.rapidwright.design.NetTools;
import com.xilinx.rapidwright.design.NetType;
import com.xilinx.rapidwright.design.SiteInst;
import com.xilinx.rapidwright.design.SitePinInst;
import com.xilinx.rapidwright.design.tools.LUTTools;
import com.xilinx.rapidwright.device.BEL;
import com.xilinx.rapidwright.device.BELPin;
import com.xilinx.rapidwright.device.IntentCode;
import com.xilinx.rapidwright.device.Node;
import com.xilinx.rapidwright.device.PIP;
import com.xilinx.rapidwright.device.Series;
import com.xilinx.rapidwright.device.SitePin;
import com.xilinx.rapidwright.device.SiteTypeEnum;
import com.xilinx.rapidwright.device.Tile;
import com.xilinx.rapidwright.device.TileTypeEnum;
import com.xilinx.rapidwright.edif.EDIFHierCellInst;
import com.xilinx.rapidwright.edif.EDIFHierNet;
import com.xilinx.rapidwright.edif.EDIFHierPortInst;
import com.xilinx.rapidwright.edif.EDIFNet;
import com.xilinx.rapidwright.edif.EDIFNetlist;
import com.xilinx.rapidwright.edif.EDIFPortInst;
import com.xilinx.rapidwright.edif.EDIFTools;
import com.xilinx.rapidwright.timing.delayestimator.DelayEstimatorBase;
import com.xilinx.rapidwright.util.Pair;
import com.xilinx.rapidwright.util.Utils;

/**
 * A collection of supportive methods for the router.
 */
public class RouterHelper {
    public static class NodeWithPrev extends Node {
        protected NodeWithPrev prev;
        public NodeWithPrev(Node node) {
            super(node);
        }

        public NodeWithPrev(Node node, NodeWithPrev prev) {
            super(node);
            setPrev(prev);
        }

        public void setPrev(NodeWithPrev prev) {
            this.prev = prev;
        }

        public NodeWithPrev getPrev() {
            return prev;
        }

        public List<Node> getPrevPath() {
            List<Node> path = new ArrayList<>();
            NodeWithPrev curr = this;
            while (curr != null) {
                path.add(curr);
                curr = curr.getPrev();
            }
            return path;
        }
    }

    /**
     * Checks if a {@link Net} instance has source and sink {@link SitePinInst} instances to be routable.
     * @param net The net to be checked.
     * @return true, if the net has source and sink pins.
     */
    public static boolean isRoutableNetWithSourceSinks(Net net) {
        return net.getSource() != null && net.getSinkPins().size() > 0;
    }

    /**
     * Checks if a {@link Net} instance is driver-less or load-less.
     * @param net The net to be checked.
     * @return true, if the nets is driver-less or load-less.
     */
    public static boolean isDriverLessOrLoadLessNet(Net net) {
        return (isDriverLessNet(net) || isLoadLessNet(net));
    }

    /**
     * Checks if a {@link Net} instance is driver-less.
     * @param net The net to be checked.
     * @return true, if the net does not have a source pin.
     */
    public static boolean isDriverLessNet(Net net) {
        return (net.getSource() == null && net.getSinkPins().size() > 0);
    }

    /**
     * Checks if a {@link Net} instance is load-less.
     * @param net The net to be checked.
     * @return true, if the net does not have sink pins.
     */
    public static boolean isLoadLessNet(Net net) {
        return (net.getSource() != null && net.getSinkPins().size() == 0);
    }

    /**
     * Checks if a {@link Net} instance is internally routed net.
     * @param net The net to be checked.
     * @return true, if the net does not have pins.
     */
    public static boolean isInternallyRoutedNet(Net net) {
        return net.getPins().size() == 0;
    }

    /**
     * Checks if the source-sink connection is an external connection driven by COUT.
     * If true, the source pin swapped to the alternative pin of the {@link Net} instance.
     * Because COUT only connects to CIN.
     * @param source The source SitePinInst of this connection.
     * @param sink The sink SitePinInst of this connection.
     * @return true, if the source is a COUT while the sink is not CIN.
     */
    public static boolean isExternalConnectionToCout(SitePinInst source, SitePinInst sink) {
        return source.getName().equals("COUT") && (!sink.getName().equals("CIN"));
    }

    /**
     * Gets a {@link Node} instance that connects to an INT {@link Tile} instance from an output {@link SitePinInst} instance.
     * @param output The output pin.
     * @return A node that connects to an INT tile from an output pin.
     */
    public static Node projectOutputPinToINTNode(SitePinInst output) {
        Node source = output.getConnectedNode();
        int watchdog = 20;

        // Only block clocking tiles if source is not in a clock tile
        final boolean blockClocking = !Utils.isClocking(source.getTile().getTileTypeEnum());

        // Laguna crossings from the output of the TX flop (TXQ*; the RX flop's output, RXQ*, is in the Laguna tile too)
        final boolean isLagunaTXQ = Utils.isLaguna(source.getTile().getTileTypeEnum()) &&
                output.getName().startsWith("TXQ");

        // Starting from the SPI's connected node, perform a downhill breadth-first search
        Queue<Node> queue = new ArrayDeque<>();
        queue.add(source);
        while (!queue.isEmpty() && watchdog >= 0) {
            Node node = queue.poll();
            watchdog--;
            for (Node downhill : node.getAllDownhillNodes()) {
                TileTypeEnum downhillTileType = downhill.getTile().getTileTypeEnum();
                if (Utils.isInterConnect(downhillTileType)) {
                    // Return node that has at least one downhill in the INT tile
                    return node;
                }
                if (isLagunaTXQ && downhill.getTile() == source.getTile() && node.getWireName().startsWith("UBUMP")) {
                    // For Laguna crossings using the TX flop, do not project back the way we came from
                    continue;
                }
                if (blockClocking && Utils.isClocking(downhillTileType)) {
                    continue;
                }
                queue.add(downhill);
            }
        }

        return null;
    }

    /**
     * Gets a list of {@link Node} instances that connect an input {@link SitePinInst} instance to an INT {@link Tile} instance.
     * @param input The input pin.
     * @return A node that connects to an INT tile from an input pin.
     */
    public static Node projectInputPinToINTNode(SitePinInst input) {
        Node sink = input.getConnectedNode();
        TileTypeEnum sinkTileType = sink.getTile().getTileTypeEnum();
        if (sinkTileType == TileTypeEnum.INT) {
            return sink;
        }

        // Laguna crossings to the input of the RX flop
        final boolean isLagunaRXD = Utils.isLaguna(sink.getTile().getTileTypeEnum());
        assert(!isLagunaRXD || input.getName().startsWith("RXD"));

        final SiteInst sinkSiteInst = input.getSiteInst();
        final Design design = sinkSiteInst != null ? sinkSiteInst.getDesign() : null;

        int watchdog = 100;

        // Starting from the SPI's connected node, perform an uphill breadth-first search
        Queue<Node> queue = new ArrayDeque<>();
        queue.add(sink);
        Set<Node> visited = new HashSet<>();
        visited.add(sink);
        while (!queue.isEmpty() && watchdog >= 0) {
            Node node = queue.poll();
            watchdog--;
            for (Node uphill : node.getAllUphillNodes()) {
                TileTypeEnum uphillTileType = uphill.getTile().getTileTypeEnum();
                if (uphillTileType == TileTypeEnum.INT ||
                        // Versal only: Terminate at non INT (e.g. CLE_BC_CORE) tile type for CTRL pin inputs
                        EnumSet.of(IntentCode.NODE_CLE_CTRL, IntentCode.NODE_INTF_CTRL, IntentCode.NODE_SLL_INPUT).contains(uphill.getIntentCode())) {
                    return uphill;
                }
                if (isLagunaRXD && uphill.getTile() == sink.getTile() && node.getWireName().startsWith("UBUMP")) {
                    // For Laguna crossings using the RX flop, do not project back the way we came from
                    continue;
                }
                if (uphillTileType != sinkTileType && Utils.isClocking(uphillTileType)) {
                    continue;
                }
                if (isUsedSiteOutput(uphill, design)) {
                    // A site output pin in use is where the input is driven from (e.g. the OUTB_B output of a
                    // differential master IOB feeding the OUTB_B_IN input of its slave): searching on uphill
                    // through a routethru of that site would project the input onto an INT node it is not
                    // reached from
                    continue;
                }
                IntentCode uphillIntentCode = uphill.getIntentCode();
                if (uphillIntentCode.isUltraScaleClocking() || uphillIntentCode.isVersalClocking() ||
                        uphill.isTiedToVcc()) {
                    // Global clock routing and VCC do not lead back to an INT tile; skip them so that they do
                    // not exhaust the search (e.g. for the CLK_IN of a BUFGCE in a CMT tile, which is fed by
                    // many clock routing tracks and MMCM/PLL outputs besides its inputs from the fabric)
                    continue;
                }
                if (!visited.add(uphill)) {
                    continue;
                }
                queue.add(uphill);
            }
        }

        return null;
    }

    /**
     * Checks whether a node is the node of a site output pin that is in use in a design, i.e. that has a
     * {@link SitePinInst} on its {@link SiteInst}.
     * @param node The node.
     * @param design The design (may be null).
     * @return True if the node is a used site output pin's node, false otherwise or if design is null.
     */
    private static boolean isUsedSiteOutput(Node node, Design design) {
        if (design == null) {
            return false;
        }
        SitePin sitePin = node.getSitePin();
        if (sitePin == null || sitePin.isInput()) {
            return false;
        }
        SiteInst si = design.getSiteInstFromSite(sitePin.getSite());
        return si != null && si.getSitePinInst(sitePin.getPinName()) != null;
    }

    public static Tile getUpstreamINTTileOfClkIn(SitePinInst clkIn) {
        Node intNode = projectInputPinToINTNode(clkIn);
        if (intNode == null) {
            throw new RuntimeException("ERROR: CLK_IN does not connect to INT Tile directly");
        }

        return intNode.getTile();
    }

    /**
     * Gets a list of {@link PIP} instances for routing a connection.
     * @param connection The {@link Connection} instance that has been routed with a list of {@link Node} instances.
     * @return A list of PIPs for the connection.
     */
    public static List<PIP> getConnectionPIPs(Connection connection) {
        return getPIPsFromNodes(connection.getNodes());
    }

    /**
     * Gets a list of {@link PIP} instances from a list of {@link Node} instances.
     * 
     * @param connectionNodes The list of nodes of a routed {@link Connection}
     *                        instance.
     * @return A list of PIPs generated from the list of nodes.
     */
    public static List<PIP> getPIPsFromNodes(List<Node> connectionNodes) {
        return getPIPsFromNodes(connectionNodes, false);
    }

    /**
     * Gets a list of {@link PIP} instances from a list of {@link Node} instances.
     * 
     * @param connectionNodes The list of nodes of a routed {@link Connection}
     *                        instance.
     * @param srcToSinkOrder  Specifies the order of the connection nodes. True
     *                        indicates the first node is the source and the last
     *                        is the sink. False indicates the opposite.
     * @return A list of PIPs generated from the list of nodes.
     */
    public static List<PIP> getPIPsFromNodes(List<Node> connectionNodes, boolean srcToSinkOrder) {
        List<PIP> connectionPIPs = new ArrayList<>();
        if (connectionNodes == null) return connectionPIPs;
        // Nodes of a connection are added to the list starting from its sink to its
        // source -- unless srcToSinkOrder is true (as is the case in static routing)
        int driverOffsetIdx = 1;
        int loadOffsetIdx = 0;
        if (srcToSinkOrder) {
            driverOffsetIdx = 0;
            loadOffsetIdx = 1;
        }
        for (int i = 0; i < connectionNodes.size() - 1; i++) {
            Node driver = connectionNodes.get(i + driverOffsetIdx);
            Node load = connectionNodes.get(i + loadOffsetIdx);
            PIP pip = PIP.getArbitraryPIP(driver, load);
            if (load.isArcLocked()) {
                pip.setIsPIPFixed(true);
            }
            if (pip != null) {
                if (load.isArcLocked()) {
                    pip.setIsPIPFixed(true);
                }
                connectionPIPs.add(pip);
            } else {
                System.err.println("ERROR: Null PIP connecting these two nodes: " + driver+ ", " + load);
            }
        }
        return connectionPIPs;
    }

    /**
     * Gets a (non-unique) collection of {@link Node} instances used by a {@link Net} instance.
     * Nodes associated with unrouted sink pins on this net will be excluded.
     * @param net The target net.
     * @return A collection of nodes used by target net.
     */
    public static Collection<Node> getNodesOfNet(Net net) {
        List<SitePinInst> pins = net.getPins();
        List<Node> nodes = new ArrayList<>(net.getPins().size() + net.getPIPs().size() / 2);
        SitePinInst sourcePin = net.getSource();
        assert(sourcePin == null || pins.contains(sourcePin));
        SitePinInst altSourcePin = net.getAlternateSource();
        assert(altSourcePin == null || pins.contains(altSourcePin));
        for (SitePinInst pin : net.getPins()) {
            // SitePinInst.isRouted() is meaningless for output pins
            if (!pin.isRouted() && !pin.isOutPin()) {
                continue;
            }

            Node pinNode = pin.getConnectedNode();
            if (pinNode != null) {
                nodes.add(pinNode);
            } else {
                System.err.println("ERROR: No node connects to pin " + pin + ", net " + net);
            }
        }

        for (PIP pip : net.getPIPs()) {
            Node end = pip.getEndNode();
            Node start = pip.getStartNode();
            nodes.add(end);
            nodes.add(start);
        }

        return nodes;
    }

    /**
     * Inverts all possible GND sink pins to VCC pins.
     * @param design The target design.
     * @param pins The GND net pins.
     */
    public static Set<SitePinInst> invertPossibleGndPinsToVccPins(Design design, List<SitePinInst> pins) {
        return invertPossibleGndPinsToVccPins(design, pins, true);
    }

    /**
     * Inverts all possible GND sink pins to VCC pins.
     * @param design The target design.
     * @param pins The GND net pins.
     * @param invertLutInputs True to invert LUT inputs.
     */
    public static Set<SitePinInst> invertPossibleGndPinsToVccPins(Design design,
                                                                  List<SitePinInst> pins,
                                                                  boolean invertLutInputs) {
        final boolean isVersal = (design.getSeries() == Series.Versal);
        final Net gndNet = design.getGndNet();
        final Net vccNet = design.getVccNet();
        final EDIFNetlist netlist = design.getNetlist();
        Set<SitePinInst> toInvertPins = new HashSet<>();
        nextSitePin: for (SitePinInst spi : pins) {
            if (!spi.getNet().equals(gndNet))
                throw new RuntimeException(spi.toString());
            if (spi.isOutPin()) {
                continue;
            }
            SiteInst si = spi.getSiteInst();
            String siteWireName = spi.getSiteWireName();
            if (invertLutInputs && spi.isLUTInputPin()) {
                Collection<Cell> connectedCells = DesignTools.getConnectedCells(spi);
                if (connectedCells.isEmpty()) {
                    for (BELPin belPin : si.getSiteWirePins(spi.getSiteWireName())) {
                        if (belPin.isSitePort()) {
                            continue;
                        }
                        BEL bel = belPin.getBEL();
                        if (isVersal && bel.isIMR()) {
                            // Since DesignTools.getConnectedCells() will only return cells
                            // for which a logical pin mapping exists, for the purpose of
                            // identifying SRL16s walk through this IMR
                            bel = si.getBEL(bel.getName().substring(0,1) + "6LUT");
                        }
                        Cell cell = si.getCell(bel);
                        if (cell == null) {
                            continue;
                        }
                        if (cell.getType().equals("SRL16E") && siteWireName.endsWith("6")) {
                            // SRL16Es that have been transformed from SRLC32E (assume so here,
                            // since we don't always have the logical netlist to check)
                            // require GND on their A6 pin
                            // See DesignTools.createMissingStaticSitePins(BELPin, SiteInst, Cell)
                            continue nextSitePin;
                        }
                    }
                    throw new RuntimeException("ERROR: " + gndNet.getName() + " not connected to any Cells");
                }

                String physicalPinName = "A" + spi.getName().charAt(1);
                BELPin cellBelPin = null;
                for (Cell cell : connectedCells) {
                    if (!LUTTools.isCellALUT(cell)) {
                        continue nextSitePin;
                    }

                    EDIFHierCellInst ehci = cell.getEDIFHierCellInst();
                    if (ehci == null) {
                        // No logical cell (likely encrypted)
                        continue nextSitePin;
                    }

                    if (!ehci.getParent().isUniquified()) {
                        // Parent cell (instantiating this LUT) is not unique
                        // This parent may be a LUT6_2 macro cell that has been expanded into LUT6+LUT5,
                        // and which does not get uniquified by EDIFTools.uniqueifyNetlist().
                        // Thus, LUT6/LUT5 inside expanded LUT6_2 macros are not eligible for inversion.
                        continue nextSitePin;
                    }

                    // Check the logical pin connection
                    String logicalPinName = cell.getLogicalPinMapping(physicalPinName);
                    EDIFHierPortInst ehpi = ehci.getPortInst(logicalPinName);
                    EDIFHierNet ehn = ehpi.getHierarchicalNet();
                    EDIFHierNet parentEhn = netlist.getParentNet(ehn);
                    if (parentEhn == null || !parentEhn.getNet().isGND()) {
                        // Unable to be sure (e.g. due to a partially encrypted design) that this
                        // pin is also logically connected to GND
                        continue nextSitePin;
                    }

                    if (cellBelPin == null) {
                        cellBelPin = cell.getBELPin(ehpi);
                    } else {
                        assert(cellBelPin.getSiteWireIndex() == cell.getBELPin(ehpi).getSiteWireIndex());
                    }
                }

                toInvertPins.add(spi);
                // Re-paint the intra-site routing from GND to VCC
                // (no intra site routing will occur during Net.addPin() later)
                si.routeIntraSiteNet(vccNet, spi.getBELPin(), cellBelPin);

                for (Cell cell : connectedCells) {
                    String logicalPinName = cell.getLogicalPinMapping(physicalPinName);

                    // Get the LUT equation
                    String lutEquation = LUTTools.getLUTEquation(cell);
                    assert(lutEquation.contains(logicalPinName));

                    // Compute a new LUT equation with that logical input inverted
                    String newLutEquation = lutEquation.replace(logicalPinName, "!" + logicalPinName)
                            // Cancel out double inversions
                            // (Note: LUTTools.getLUTEquation() only produces equations with '!' instead of '~')
                            .replace("!!", "");
                    LUTTools.configureLUT(cell, newLutEquation);

                    // Change the logical pin connection
                    EDIFHierCellInst ehci = cell.getEDIFHierCellInst();
                    EDIFHierPortInst ehpi = ehci.getPortInst(logicalPinName);
                    EDIFPortInst epi = ehpi.getPortInst();
                    epi.getNet().removePortInst(epi);

                    EDIFNet const1 = EDIFTools.getStaticNet(NetType.VCC, ehci.getParent().getCellType(), netlist);
                    const1.addPortInst(epi);
                }
            } else {
                BELPin[] belPins = si.getSiteWirePins(siteWireName);
                if (belPins.length != 2) {
                    if (belPins.length == 3 && si.getSiteTypeEnum() == SiteTypeEnum.DSP58 && siteWireName.equals("RSTD")) {
                        assert(isVersal);
                        assert(belPins[1].toString().equals("SRCMXINV.RSTAD_UNUSED"));
                    } else {
                        continue;
                    }
                }
                for (BELPin belPin : belPins) {
                    if (belPin.isSitePort()) {
                        continue;
                    }
                    if (!belPin.getBEL().canInvert()) {
                        continue;
                    }
                    // Emulate Vivado's behaviour and do not invert CLK* site pins
                    if (Utils.isBRAM(spi.getSiteInst()) &&
                            belPin.getBELName().startsWith("CLK") &&
                            !isVersal) {
                        continue;
                    }
                    toInvertPins.add(spi);
                    // Unpaint the sitewire ahead of pin being added below
                    si.unrouteIntraSiteNet(spi.getBELPin(), spi.getBELPin());
                }
            }
        }

        // Unroute all pins in a batch fashion
        DesignTools.unroutePins(gndNet, toInvertPins);
        // Manually remove pins from net, because using DesignTools.batchRemoveSitePins()
        // will cause SitePinInst.detachSiteInst() to be called, which we do not want
        // as we are simply moving the SPI from one net to another
        gndNet.getPins().removeAll(toInvertPins);

        for (SitePinInst toinvert : toInvertPins) {
            assert(toinvert.getSiteInst() != null);
            boolean updateSiteRouting = false;
            if (!vccNet.addPin(toinvert, updateSiteRouting)) {
                  throw new RuntimeException("ERROR: Couldn't invert site pin " +
                          toinvert);
            }
        }

        return toInvertPins;
    }

    /**
     * Adds the {@link IntentCode} and wirelength of an used node to the map.
     * @param node The target node.
     * @param wlNode The wirelength of the node.
     * @param typeUsage The map between each node type and the number of used nodes for the node type.
     * @param typeLength The map between each node type and the total wirelength of used nodes for the node type.
     */
    public static void addNodeTypeLengthToMap(Node node, long wlNode, Map<IntentCode, Long> typeUsage, Map<IntentCode, Long> typeLength) {
        IntentCode ic = node.getIntentCode();
        if (node.getTile().getTileTypeEnum() == TileTypeEnum.LAGUNA_TILE) {
            // UltraScale only
            if (node.getWireName().startsWith("UBUMP")) {
                // Use the intent code from US+
                ic = IntentCode.NODE_LAGUNA_DATA;
            }
        }
        typeUsage.merge(ic, 1L, Long::sum);
        typeLength.merge(ic, wlNode, Long::sum);
    }

    /**
     * Gets the route delay to each sink of a net that the net's routing reaches, which may itself
     * be partially routed.
     * A sink is reached when the net's PIPs give a complete path from an output pin to the sink's
     * projected INT tile node -- or, for a direct connection that projects onto no such node
     * (e.g. COUT -> CIN), to the sink's own node. Sinks that are not reached are omitted, so that
     * a caller can tell one not routed to yet from one whose delay is genuinely zero.
     * Delay is charged no further than that node: its pinfeed delay is carried by the node itself,
     * and any interface PIP beyond it is outside this model, so how the sink is reached from it
     * does not change the delay.
     * Delays are accumulated source-first over the routing trees from {@link NetTools#getNodeTrees(Net)}.
     * The net's routing is assumed to be loop-free and not multiply-driven.
     * @param net The target net; it may be partially routed, but it must not be static since a
     *            constant source has no route delay that this model describes.
     * @param estimator An instantiation of DelayEstimatorBase.
     * @return Reached sinks mapped to their sink INT node and route delay.
     * @throws RuntimeException If the net is static.
     */
    public static Map<SitePinInst, Pair<Node,Short>> getSourceToSinkINTNodeDelays(Net net, DelayEstimatorBase estimator) {
        if (net.isStaticNet()) {
            throw new RuntimeException("ERROR: Cannot compute route delays of static net " + net.getName());
        }

        // NetTools supplies only source-rooted trees and handles reversed PIPs. Accumulate
        // delays in source-to-sink order so each driver's delay is known before its fanouts.
        Map<Node, Integer> delayMap = new HashMap<>();
        Queue<NetTools.NodeTree> queue = new ArrayDeque<>();
        for (NetTools.NodeTree root : NetTools.getNodeTrees(net)) {
            delayMap.put(root, 0);
            queue.add(root);
        }
        while (!queue.isEmpty()) {
            NetTools.NodeTree driver = queue.remove();
            int driverDelay = delayMap.get(driver);
            for (NetTools.NodeTree node : driver.fanouts) {
                if (delayMap.containsKey(node)) {
                    continue;
                }
                int delay = driverDelay;
                if (node.getTile().getTileTypeEnum() == TileTypeEnum.INT) {
                    delay += computeNodeDelay(estimator, node)
                            + DelayEstimatorBase.getExtraDelay(node, DelayEstimatorBase.isLong(driver));
                }
                delayMap.put(node, delay);
                queue.add(node);
            }
        }

        Map<SitePinInst, Pair<Node,Short>> sinkNodeDelays = new HashMap<>();
        for (SitePinInst sink : net.getSinkPins()) {
            Node sinkNode = sink.getConnectedNode();
            if (sinkNode.getTile().getTileTypeEnum() != TileTypeEnum.INT) {
                Node sinkINTNode = projectInputPinToINTNode(sink);
                if (sinkINTNode != null) {
                    sinkNode = sinkINTNode;
                } else {
                    // Must be a direct connection (e.g. COUT -> CIN)
                }
            }

            Integer delay = delayMap.get(sinkNode);
            if (delay == null) {
                // The net's routing does not reach this sink, so it has no route delay to recover
                continue;
            }
            sinkNodeDelays.put(sink, new Pair<>(sinkNode, delay.shortValue()));
        }

        return sinkNodeDelays;
    }

    /**
     * Computes the delay of a node.
     * @param estimator An instantiation of the DelayEstimatorBase.
     * @param node The node in question.
     * @return The delay of the node.
     * @throws RuntimeException If the node is a super long line (an SLR crossing), for which the timing model has no
     *                          delay.
     */
    public static short computeNodeDelay(DelayEstimatorBase estimator, Node node) {
        if (RouteNode.isExitNode(node)) {
            return estimator.getDelayOf(node);
        }
        IntentCode ic = node.getIntentCode();
        if (ic == IntentCode.NODE_LAGUNA_DATA || ic == IntentCode.NODE_SLL_DATA) {
            throw new RuntimeException("ERROR: No delay is modelled for super long line " + node);
        }
        return 0;
    }

    /** Maximum number of nodes after the source node on a dedicated path (see {@link #isDedicatedConnection}). */
    private static final int MAX_DEDICATED_PATH_LENGTH = 3;

    /**
     * Checks whether an input pin is driven by an output pin through dedicated resources only, i.e. a short
     * path that does not enter an INT tile, such as from a global clock-capable IOB input to a clock buffer
     * of its bank or CMT. Such a connection is best routed directly (as Vivado does) rather than projected
     * onto INT tiles and routed through the fabric, where several clock buffers can share a single input
     * (e.g. the BUFGCE_HDIOs of a RCLK_HDIO tile).
     * @param source The output pin.
     * @param sink The input pin.
     * @return true, if the sink pin's node can be reached from the source pin's node through at most
     * {@value #MAX_DEDICATED_PATH_LENGTH} nodes, none in an INT tile.
     */
    public static boolean isDedicatedConnection(SitePinInst source, SitePinInst sink) {
        Node sourceNode = source.getConnectedNode();
        Node sinkNode = sink.getConnectedNode();
        if (sourceNode == null || sinkNode == null ||
                Utils.isInterConnect(sourceNode.getTile().getTileTypeEnum()) ||
                Utils.isInterConnect(sinkNode.getTile().getTileTypeEnum())) {
            return false;
        }
        // As findPathBetweenNodes() does, only cross clocking tiles if the source or sink is in one
        final boolean blockClocking = !Utils.isClocking(sourceNode.getTile().getTileTypeEnum()) &&
                !Utils.isClocking(sinkNode.getTile().getTileTypeEnum());
        List<Node> level = Collections.singletonList(sourceNode);
        Set<Node> visited = new HashSet<>(level);
        for (int length = 1; length <= MAX_DEDICATED_PATH_LENGTH && !level.isEmpty(); length++) {
            List<Node> nextLevel = new ArrayList<>();
            for (Node node : level) {
                for (Node downhill : node.getAllDownhillNodes()) {
                    if (downhill.equals(sinkNode)) {
                        return true;
                    }
                    TileTypeEnum tileType = downhill.getTile().getTileTypeEnum();
                    if (Utils.isInterConnect(tileType) || (blockClocking && Utils.isClocking(tileType))) {
                        continue;
                    }
                    if (visited.add(downhill)) {
                        nextLevel.add(downhill);
                    }
                }
            }
            level = nextLevel;
        }
        return false;
    }

    /**
     * Routes and assigns nodes to a direct connection, e.g. carry chain connections and connections between cascaded BRAMs.
     * @param directConnection The target direct connection.
     * @return true, if the connection is successfully routed.
     */
    public static boolean routeDirectConnection(Connection directConnection) {
        directConnection.setNodes(findPathBetweenNodes(directConnection.getSource().getConnectedNode(), directConnection.getSink().getConnectedNode()));
        return !directConnection.getNodes().isEmpty();
    }

    /**
     * Find a path from a source node to a sink node.
     * Intermediate nodes with tile type returning true for {@link Utils#isClocking(TileTypeEnum)} will be ignored.
     * @param source The source node.
     * @param sink The sink node.
     * @return A list of nodes making up the path.
     */
    public static List<Node> findPathBetweenNodes(Node source, Node sink) {
        if (source.equals(sink)) {
            return Collections.emptyList(); // for pins without additional projected int_node
        }
        if (source.getAllDownhillNodes().contains(sink)) {
            // Modifiable: RouteFixer rewrites the nodes of every connection of a net it fixes
            return new ArrayList<>(Arrays.asList(sink, source));
        }
        NodeWithPrev sourcer = new NodeWithPrev(source);
        sourcer.setPrev(null);
        Queue<NodeWithPrev> queue = new LinkedList<>();
        queue.add(sourcer);

        // Only block clocking tiles if both source and sink are not in a clock tile
        final boolean blockClocking = !Utils.isClocking(source.getTile().getTileTypeEnum()) &&
                !Utils.isClocking(sink.getTile().getTileTypeEnum());

        int watchdog = 10000;
        while (!queue.isEmpty()) {
            NodeWithPrev curr = queue.poll();
            if (curr.equals(sink)) {
                return curr.getPrevPath();
            }
            for (Node n : curr.getAllDownhillNodes()) {
                if (blockClocking && Utils.isClocking(n.getTile().getTileTypeEnum())) {
                    continue;
                }
                NodeWithPrev child = new NodeWithPrev(n);
                child.setPrev(curr);
                queue.add(child);
            }
            watchdog--;
            if (watchdog < 0) {
                break;
            }
        }

        System.err.println("ERROR: Failed to find a path between two nodes: " + source + ", " + sink);
        return Collections.emptyList();
    }
}

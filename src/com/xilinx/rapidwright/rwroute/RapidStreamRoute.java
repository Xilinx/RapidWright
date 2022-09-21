/*
 *
 * Copyright (c) 2021 Ghent University.
 * Copyright (c) 2022, Advanced Micro Devices, Inc.
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

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import com.xilinx.rapidwright.design.Design;
import com.xilinx.rapidwright.design.Net;
import com.xilinx.rapidwright.design.NetType;
import com.xilinx.rapidwright.device.Node;
import com.xilinx.rapidwright.device.Tile;
import com.xilinx.rapidwright.edif.EDIFHierPortInst;
import com.xilinx.rapidwright.edif.EDIFNet;

/**
 * Customized {@link PartialRouter} for the RapidStream use case.
 */
public class RapidStreamRoute extends PartialRouter{
    /** Nets with conflicting nodes that should be added to the routing targets */
    protected Set<Net> conflictNets;
    /** A keyword to help recognize the target conflict nets */
    final private String anchorNameKeyword;

    public RapidStreamRoute(Design design, RWRouteConfig config, String anchorNameKeyword) {
        // FIXME
        super(design, config, Collections.EMPTY_LIST);
        this.anchorNameKeyword = anchorNameKeyword;
    }

    public RapidStreamRoute(Design design, RWRouteConfig config) {
        this(design, config, "q0_reg");
    }

    @Override
    protected Collection<Net> getTimingNets() {
        return conflictNets;
    }

    /**
     * Classifies {@link Net} Objects into different categories: clocks, static nets,
     * and regular signal nets (i.e. {@link NetType}.WIRE) and determines routing targets.
     * Overrides to enable routing of conflict nets in the partial routing mode.
     */
    @Override
    protected void determineRoutingTargets() {
        conflictNets = new HashSet<>();
        super.determineRoutingTargets();
        handleConflictNets();
    }

    private void generateConflictInfo(Node node, Net reserved, Net netToPreserve) {
        System.out.println("WARNING: Conflicting node " + node + ":");
        System.out.println("         " + netToPreserve.getName() + " \n         " + reserved.getName());
    }

    @Override
    protected void addPreservedNodes(Collection<Node> nodes, Net netToPreserve) {
        for (Node node : nodes) {
            Net reserved = routingGraph.preserve(node, netToPreserve);
            if (reserved == null)
                continue;
            // Nodes already preserved by the same net are ignored
            if (reserved.equals(netToPreserve))
                continue;
            if (reserved.getSource() != null && netToPreserve.getSource() != null) {
                boolean generateWarning = conflictNets.size() < 5;
                EDIFNet reservedLogical = reserved.getLogicalNet();
                EDIFNet toReserveLogical = netToPreserve.getLogicalNet();
                if (reservedLogical != null && toReserveLogical != null) {
                    if (!toReserveLogical.equals(reservedLogical)) {
                        if (generateWarning) generateConflictInfo(node, reserved, netToPreserve);
                    }
                }else {
                    if (generateWarning) generateConflictInfo(node, reserved, netToPreserve);
                }
                conflictNets.add(reserved);
                conflictNets.add(netToPreserve);
            }
        }

        super.addPreservedNodes(nodes, netToPreserve);
    }

    /**
     * Deals with nets that are routed but with conflicting nodes.
     */
    private void handleConflictNets() {
        List<Net> toPreserveNets = new ArrayList<>();
        for (Net net : conflictNets) {
            if (!isTargetConflictNetToRoute(net)) {
                toPreserveNets.add(net);
                continue;
            }

            unpreserveNet(net); // remove preserved nodes of a net from the map
            createNetWrapperAndConnections(net);
            net.unroute();//NOTE: no need to unroute if routing tree is reused, then toPreserveNets should be detected before createNetWrapperAndConnections
        }
        for (Net net : toPreserveNets) {
            preserveNet(net);
        }
    }

    /**
     * Checks if a net is the target conflict net to be routed.
     * Note: this method provides an example of customizing the partial router for application-specific tool flows.
     * It is specifically for the RapidStream use case, where the targets are nets connecting anchor FFs of CLB tiles.
     * @param net The net in question.
     * @return true if the net is a target net.
     */
    protected boolean isTargetConflictNetToRoute(Net net) {
        // Skip successfully routed CLK, VCC, and GND nets
        // In the RapidStream flow, the target nets to route are 2-terminal FF-to-FF nets. So nets with more than one sink pin are skipped as well.
        if (net.getType() != NetType.WIRE || net.getSinkPins().size() > 1) return false;
        boolean anchorNet = false;
        List<EDIFHierPortInst> ehportInsts = design.getNetlist().getPhysicalPins(net.getName());
        boolean input = false;
        if (ehportInsts == null) { // e.g. encrypted DSP related nets
            return false;
        }
        for (EDIFHierPortInst eport : ehportInsts) {
            if (eport.getFullHierarchicalInstName().contains(anchorNameKeyword)) {
                //use the key word to identify target anchor nets
                anchorNet = true;
                if (eport.isInput()) input = true;
                break;
            }
        }
        Tile anchorTile;
        if (input) {
            anchorTile = net.getSinkPins().get(0).getTile();
        }else {
            anchorTile = net.getSource().getTile();
        }
        // Note: if laguna anchor nets are never conflicted, there will be no need to check tile names.
        return anchorNet && anchorTile.getName().startsWith("CLE");
    }

    /**
     * Routes a design for the RapidStream flow.
     * Note: Added to indicate the parameters for the use case.
     * @param design The design instance to route.
     * @return The routed design instance.
     */
    public static Design routeDesignRapidStream(Design design) {
        RWRouteConfig config = new RWRouteConfig(new String[] {
                "--enlargeBoundingBox",
                "--useUTurnNodes",
                "--verbose"});
        return routeDesign(design, new RapidStreamRoute(design, config));
    }
}

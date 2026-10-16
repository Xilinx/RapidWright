/*
 *
 * Copyright (c) 2021 Ghent University.
 * Copyright (c) 2022-2024, 2026, Advanced Micro Devices, Inc.
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

import com.xilinx.rapidwright.design.Design;
import com.xilinx.rapidwright.design.Net;
import com.xilinx.rapidwright.design.NetType;
import com.xilinx.rapidwright.design.SitePinInst;
import com.xilinx.rapidwright.device.Node;
import com.xilinx.rapidwright.device.Tile;
import com.xilinx.rapidwright.edif.EDIFHierPortInst;
import com.xilinx.rapidwright.edif.EDIFNet;
import com.xilinx.rapidwright.util.ParallelismTools;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Customized {@link PartialRouter} for the RapidStream use case.
 */
public class RapidStreamRoute extends PartialRouter {

    public RapidStreamRoute(Design design, RWRouteConfig config, Collection<SitePinInst> pinsToRoute) {
        super(design, config, pinsToRoute);
    }

    private static void generateConflictInfo(Node node, Net reserved, Net netToPreserve) {
        System.out.println("WARNING: Conflicting node " + node + ":");
        System.out.println("         " + netToPreserve.getName() + " \n         " + reserved.getName());
    }

    /**
     * Checks if a net is the target conflict net to be routed.
     * Note: this method provides an example of customizing the partial router for application-specific tool flows.
     * It is specifically for the RapidStream use case, where the targets are nets connecting anchor FFs of CLB tiles.
     * @param net The net in question.
     * @return true if the net is a target net.
     */
    protected static boolean isTargetConflictNetToRoute(Net net, String anchorNameKeyword) {
        // Skip successfully routed CLK, VCC, and GND nets
        // In the RapidStream flow, the target nets to route are 2-terminal FF-to-FF nets. So nets with more than one sink pin are skipped as well.
        if (net.getType() != NetType.WIRE || net.getSinkPins().size() > 1) return false;
        boolean anchorNet = false;
        List<EDIFHierPortInst> ehportInsts = net.getDesign().getNetlist().getPhysicalPins(net.getName());
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
        } else {
            anchorTile = net.getSource().getTile();
        }
        // Note: if laguna anchor nets are never conflicted, there will be no need to check tile names.
        return anchorNet && anchorTile.getName().startsWith("CLE");
    }

    /**
     * Find and unroute all conflicted nets (filtered by isTargetConflictNetToRoute())
     * that are expected to be re-routed.
     * @param design The design instance to route.
 *   * @param anchorNameKeyword A keyword to help recognize the target conflict nets
     * @return A Collection of conflicted nets.
     */
    private static Collection<Net> unrouteConflictedNets(Design design, String anchorNameKeyword) {
        // The first net found to use each node, and any others found to use it too (conflicts)
        final Map<Node, Net> nodeToNet = new ConcurrentHashMap<>();
        final Map<Node, Set<Net>> nodeToConflictNets = new ConcurrentHashMap<>();
        ParallelismTools.forEach(design.getNets(), net -> {
            // A net's nodes may include duplicates
            for (Node node : RouterHelper.getNodesOfNet(net)) {
                final Net firstNet = nodeToNet.putIfAbsent(node, net);
                if (firstNet != null && firstNet != net) {
                    nodeToConflictNets.computeIfAbsent(node, n -> ConcurrentHashMap.newKeySet()).add(net);
                }
            }
        });

        Set<Net> conflictNets = new HashSet<>();
        for (Map.Entry<Node,Set<Net>> e : nodeToConflictNets.entrySet()) {
            Node node = e.getKey();
            Net net0 = nodeToNet.get(node);
            for (Net net1 : e.getValue()) {
                if (net0.getSource() == null || net1.getSource() == null) {
                    continue;
                }
                boolean generateWarning = conflictNets.size() < 5;
                EDIFNet logicalNet0 = net0.getLogicalNet();
                EDIFNet logicalNet1 = net1.getLogicalNet();
                if (logicalNet0 != null && logicalNet1 != null) {
                    if (!logicalNet1.equals(logicalNet0)) {
                        if (generateWarning) generateConflictInfo(node, net0, net1);
                    }
                } else {
                    if (generateWarning) generateConflictInfo(node, net0, net1);
                }

                // Unroute every target net of the conflict, rather than the fewest nets needed to leave one net on each
                // node. Once unrouted, net0 has no PIPs, so is not unrouted again for another conflict
                for (Net net : Arrays.asList(net0, net1)) {
                    if (net.hasPIPs() && isTargetConflictNetToRoute(net, anchorNameKeyword)) {
                        conflictNets.add(net);
                        net.unroute();
                    }
                }
            }
        }
        return conflictNets;
    }

    public static Design routeDesignRapidStream(Design design) {
        return routeDesignRapidStream(design, "q0_reg");
    }

    /**
     * Routes a design for the RapidStream flow.
     * Note: Added to indicate the parameters for the use case.
     * @param design The design instance to route.
     * @param anchorNameKeyword A keyword to help recognize the target conflict nets
     * @return The routed design instance.
     */
    public static Design routeDesignRapidStream(Design design, String anchorNameKeyword) {
        // Re-route all pins on all conflicted nets
        Collection<Net> conflictedNets = unrouteConflictedNets(design, anchorNameKeyword);
        List<SitePinInst> pinsToRoute = new ArrayList<>();
        for (Net net : conflictedNets) {
            pinsToRoute.addAll(net.getSinkPins());
        }

        RWRouteConfig config = new RWRouteConfig(new String[] {
                "--enlargeBoundingBox",
                "--useUTurnNodes",
                "--verbose"});
        return routeDesign(new RapidStreamRoute(design, config, pinsToRoute));
    }
}

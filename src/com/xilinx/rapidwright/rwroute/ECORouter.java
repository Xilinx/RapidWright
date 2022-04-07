/*
 *
 * Copyright (c) 2021 Ghent University.
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

import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import com.xilinx.rapidwright.design.Cell;
import com.xilinx.rapidwright.design.Design;
import com.xilinx.rapidwright.design.DesignTools;
import com.xilinx.rapidwright.design.Net;
import com.xilinx.rapidwright.design.SiteInst;
import com.xilinx.rapidwright.design.SitePinInst;
import com.xilinx.rapidwright.device.BELClass;
import com.xilinx.rapidwright.device.BELPin;
import com.xilinx.rapidwright.device.Node;
import com.xilinx.rapidwright.device.PIP;
import com.xilinx.rapidwright.device.Site;
import com.xilinx.rapidwright.device.SitePIP;
import com.xilinx.rapidwright.device.SitePin;
import com.xilinx.rapidwright.device.Tile;

/**
 * TODO
 */
public class ECORouter extends PartialRouter {
    public ECORouter(Design design, RWRouteConfig config){
        super(design, config);

        // FIXME
        if (config.isTimingDriven()) {
            timingManager.setTimingRequirementPs(100 * 1000);
        }
    }

    @Override
    protected void routeGlobalClkNets() {
        if (clkNets.isEmpty()) {
            return;
        }

        throw new RuntimeException("ERROR: PartialECORouter does not support clock routing.");
    }

    @Override
    protected void routeStaticNets() {
        if (staticNetAndRoutingTargets.isEmpty()) {
            return;
        }

        throw new RuntimeException("ERROR: PartialECORouter does not support static net routing.");
    }

    @Override
    protected void determineRoutingTargets(){
        super.determineRoutingTargets();

        for (Map.Entry<Net,NetWrapper> e : nets.entrySet()) {
            Net net = e.getKey();
            NetWrapper netWrapper = e.getValue();

            // Create all nodes used by this net and set its previous pointer so that:
            // (a) the routing for each connection can be recovered by
            //      finishRouteConnection()
            // (b) Routable.setChildren() will know to only allow this incoming
            //     arc on these nodes
            for (PIP pip : net.getPIPs()) {
                Node start = (pip.isReversed()) ? pip.getEndNode() : pip.getStartNode();
                Node end = (pip.isReversed()) ? pip.getStartNode() : pip.getEndNode();
                Routable rstart = createAddRoutableNode(null, start, RoutableType.WIRE);
                Routable rend = createAddRoutableNode(null, end, RoutableType.WIRE);
                assert (rend.getPrev() == null);
                rend.setPrev(rstart);
            }

            for (Connection connection : netWrapper.getConnections()) {
                finishRouteConnection(connection);

                if (connection.getSink().isRouted())
                    continue;

                SitePinInst sink = connection.getSink();
                String sinkPinName = sink.getName();
                if (!Pattern.matches("[A-H](X|_I)", sinkPinName))
                    continue;

                Routable rnode = connection.getSinkRnode();
                String lut = sinkPinName.substring(0, 1);
                Site site = sink.getSite();
                SiteInst siteInst = sink.getSiteInst();

                boolean LUT6 = (siteInst.getCell(lut + "6LUT") != null);
                boolean LUT5 = (siteInst.getCell(lut + "5LUT") != null);
                int i = (LUT6 && LUT5) ? 0 : (LUT6 || LUT5) ? 5 : 6;
                for (; i >= 1; i--) {
                    Node altNode = site.getConnectedNode(lut + i);

                    // Skip if LUT pin is already being preserved
                    Net preservedNet = routingGraph.getPreservedNet(altNode);
                    if (preservedNet != null) {
                        continue;
                    }

                    RoutableType type = RoutableType.WIRE;
                    Routable altRnode = createAddRoutableNode(null, altNode, type);
                    // Trigger a setChildren() for LUT routethrus
                    altRnode.getChildren();
                    // Create a fake edge from [A-H][1-6] to [A-H](I|_X)
                    altRnode.addChild(rnode);
                }
            }
        }
    }

    // Adapted from DesignTools.getConnectedCells()
    public static Set<BELPin> getConnectedBELPins(SitePinInst pin){
        HashSet<BELPin> pins = new HashSet<>();
        SiteInst si = pin.getSiteInst();
        if(si == null) return pins;
        for(BELPin p : pin.getBELPin().getSiteConns()){
            if(p.getBEL().getBELClass() == BELClass.RBEL){
                SitePIP pip = si.getUsedSitePIP(p.getBELName());
                if(pip == null) continue;
                if(p.isOutput()){
                    p = pip.getInputPin().getSiteConns().get(0);
                    Cell c = si.getCell(p.getBELName());
                    if(c != null) pins.add(p);
                }else{
                    for(BELPin snk : pip.getOutputPin().getSiteConns()){
                        Cell c = si.getCell(snk.getBELName());
                        if(c != null) pins.add(snk);
                    }
                }
            }else{
                Cell c = si.getCell(p.getBELName());
                if(c != null && c.getLogicalPinMapping(p.getName()) != null) {
                    pins.add(p);
                }
            }
        }
        return pins;
    }

    @Override
    protected void assignNodesToConnections() {
        for(Map.Entry<Net,NetWrapper> e : nets.entrySet()) {
            NetWrapper netWrapper = e.getValue();
            for (Connection connection : netWrapper.getConnections()) {
                SitePinInst sink = connection.getSink();
                List<Routable> rnodes = connection.getRnodes();
                String sinkPinName = sink.getName();
                if (rnodes.size() >= 2 && Pattern.matches("[A-H](X|_I)", sinkPinName)) {
                    Routable prevRnode = rnodes.get(1);
                    Node prevNode = (prevRnode != null) ? prevRnode.getNode() : null;
                    SitePin prevPin = (prevNode != null) ? prevNode.getSitePin() : null;
                    if (prevPin != null && Pattern.matches("[A-H][1-6]", prevPin.getPinName())) {
                        // Drop the fake LUT -> X/I pin edge
                        connection.setRnodes(rnodes.subList(1, rnodes.size()));

                        // Fix the intra-site routing
                        SiteInst si = sink.getSiteInst();
                        Net net = connection.getNetWrapper().getNet();
                        for (BELPin sinkBELPin : getConnectedBELPins(sink)) {
                            si.unrouteIntraSiteNet(sink.getBELPin(), sinkBELPin);
                            si.routeIntraSiteNet(net, prevPin.getBELPin(), sinkBELPin);
                        }

                        System.out.println(prevPin.getPinName() + " -> " + sinkPinName + " for " + connection.getNetWrapper().getNet());
                    }
                }
            }
        }

        super.assignNodesToConnections();
    }

    @Override
    protected boolean handleUnroutableConnection(Connection connection) {
        if(config.isEnlargeBoundingBox()) {
            connection.enlargeBoundingBox(config.getExtensionXIncrement(), config.getExtensionYIncrement());
        }
        if (routeIteration > 1) {
            if (rnodesCreatedThisIteration == 0) {
                unpreserveNetsAndReleaseResources(connection);
                return true;
            }
        }
        return super.handleUnroutableConnection(connection);
    }

    @Override
    protected Collection<Net> pickNetsToUnpreserve(Connection connection) {
        Set<Net> unpreserveNets = new HashSet<>();

        // Find those preserved nets that are using downhill nodes of the source pin node
        for(Node node : connection.getSourceRnode().getNode().getAllDownhillNodes()) {
            Net toRoute = routingGraph.getPreservedNet(node);
            if(toRoute == null) continue;
            if(toRoute.isClockNet() || toRoute.isStaticNet()) continue;
            unpreserveNets.add(toRoute);
        }

        unpreserveNets.removeIf((net) -> {
            NetWrapper netWrapper = nets.get(net);
            if (netWrapper == null)
                return false;
            if (netWrapper.getPartiallyPreserved())
                return false;
            // Net already seen and is fully unpreserved
            return true;
        });

        return unpreserveNets;
    }

    // @Override
    // protected boolean handleCongestedConnection(Connection connection) {
    //     super.handleCongestedConnection(connection);
    //
    //     if (routeIteration > 1) {
    //         if (rnodesCreatedThisIteration == 0) {
    //             // NetWrapper netWrapper = connection.getNetWrapper();
    //             // if (netWrapper.getPartiallyPreserved()) {
    //             //     Net net = netWrapper.getNet();
    //             //     System.out.println("INFO: Unpreserving rest of '" + net + "' due to congestion");
    //             //     unpreserveNet(net);
    //             //     return true;
    //             // }
    //             //
    //             // return false;
    //
    //             Set<Tile> overUsedTiles = new HashSet<>();
    //             for(Routable rn : connection.getRnodes()){
    //                 if(rn.isOverUsed()) {
    //                     overUsedTiles.add(rn.getNode().getTile());
    //                 }
    //             }
    //
    //             Set<Net> unpreserveNets = new HashSet<>();
    //             for (Tile tile : overUsedTiles) {
    //                 for (int wire = 0; wire < tile.getWireCount(); wire++) {
    //                     Node node = Node.getNode(tile, wire);
    //                     Net net = routingGraph.getPreservedNet(node);
    //                     if (net == null)
    //                         continue;
    //                     if (net.isClockNet() || net.isStaticNet())
    //                         continue;
    //                     NetWrapper netWrapper = nets.get(net);
    //                     if (netWrapper != null && !netWrapper.getPartiallyPreserved())
    //                         continue;
    //
    //                     unpreserveNets.add(net);
    //                 }
    //             }
    //
    //             if (!unpreserveNets.isEmpty()) {
    //                 System.out.println("INFO: Unpreserving " + unpreserveNets.size() + " nets in vicinity of congestion");
    //                 for (Net net : unpreserveNets) {
    //                     System.out.println("\t" + net);
    //                     unpreserveNet(net);
    //                 }
    //                 return true;
    //             }
    //         }
    //     }
    //     return false;
    // }

    public static Design routeDesign(Design design) {
        RWRouteConfig config = new RWRouteConfig(new String[] {
                "--partialRouting",
                // "--fixBoundingBox",
                // "--boundingBoxExtensionX 6", // Necessary to ensure that we can reach a Laguna column
                "--enlargeBoundingBox", // Necessary to ensure that we can reach a Laguna column
                "--nonTimingDriven",
                "--verbose"});
        return routeDesign(design, config, () -> new ECORouter(design, config));
    }

}

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
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.xilinx.rapidwright.design.Design;
import com.xilinx.rapidwright.design.Net;
import com.xilinx.rapidwright.design.PartitionPin;
import com.xilinx.rapidwright.design.SitePinInst;
import com.xilinx.rapidwright.device.Node;
import com.xilinx.rapidwright.device.PIP;
import com.xilinx.rapidwright.edif.EDIFHierNet;
import com.xilinx.rapidwright.edif.EDIFHierPortInst;
import com.xilinx.rapidwright.edif.EDIFNetlist;
import com.xilinx.rapidwright.edif.EDIFTools;
import com.xilinx.rapidwright.router.UltraScaleClockRouting;

/**
 * A class extends {@link RWRoute} for partial routing.
 */
public class PartialRouter extends RWRoute{
    Map<Net, List<RouteTerm>> netToUnroutedTerms;
    Map<Net, RouteTerm> netToSourcePartPin;

    public PartialRouter(Design design, RWRouteConfig config) {
        this(design, config, null);
    }

    public PartialRouter(Design design, RWRouteConfig config, Map<Net, List<RouteTerm>> netToUnroutedTerms) {
        super(design, config);

        if (netToUnroutedTerms == null) {
            netToUnroutedTerms = new HashMap<>();
            for (Net net : design.getNets()) {
                if (net.hasPIPs())
                    continue;
                List<SitePinInst> unroutedPins = net.getSinkPins();
                if (unroutedPins.isEmpty())
                    continue;
                netToUnroutedTerms.put(net, RouteTermSitePin.asList(unroutedPins));
            }
        }
        this.netToUnroutedTerms = netToUnroutedTerms;

        netToSourcePartPin = new HashMap<>();
        Map<Net,Set<Node>> netToNodes = new HashMap<>();
        EDIFNetlist netlist = design.getNetlist();
        for (PartitionPin ppin : design.getPartitionPins()) {
            if (ppin.getInstanceName() == null || ppin.getInstanceName().length() == 0) {
                // Part pin is on the top level cell
                throw new RuntimeException();
            } else {
                // Part pin is inside design hierarchy
                EDIFHierPortInst ehpi = netlist.getHierPortInstFromName(ppin.getInstanceName()
                        + EDIFTools.EDIF_HIER_SEP + ppin.getTerminalName());

                if (ehpi.isInput()) {
                    EDIFHierNet ehn = ehpi.getHierarchicalNet();
                    EDIFHierNet parentEhn = netlist.getParentNet(ehn);
                    Net net = design.getNet((parentEhn != null ? parentEhn : ehn).getHierarchicalNetName());
                    if (net == null) {
                        throw new RuntimeException();
                    }
                    if (net.isClockNet()) {
                        // Ignore part pins on clock net, as in at least one case they pointed to
                        // (many) horizontal distribution lines for use as sources
                        continue;
                    }
                    Node node = Node.getNode(ppin.getTile(), ppin.getWireIndex());

                    // Find all routed input part pins
                    Set<Node> nodes = netToNodes.computeIfAbsent(net, (n) -> new HashSet<>(RouterHelper.getNodesOfNet(n)));
                    if (nodes.contains(node)) {
                        RouteTerm source = new RouteTermPartPin(node);
                            if (netToSourcePartPin.put(net, source) != null) {
                                // Expect only one source part pin
                                throw new RuntimeException(net.getName());
                            }
                        }
                    }
                    }
                }
            }

    @Override
    protected void routeGlobalClkNet(Net clk) {
        if (!clk.hasPIPs()) {
            super.routeGlobalClkNet(clk);
        } else {
            List<RouteTerm> terms = netToUnroutedTerms.get(clk);
            UltraScaleClockRouting.incrementalClockRouter(clk, RouteTermSitePin.fromList(terms));
        }
    }

    @Override
    protected void addGlobalClkRoutingTargets(Net clk) {
        preserveNet(clk);

        List<RouteTerm> unroutedTerms = netToUnroutedTerms.get(clk);
        if (unroutedTerms == null) {
            increaseNumNotNeedingRouting();
            return;
        }

        addClkNet(clk);
        if (clk.hasPIPs()) {
            increaseNumPreservedClks();
        }
    }

    @Override
    protected void addStaticNetRoutingTargets(Net staticNet) {
        preserveNet(staticNet);

        List<RouteTerm> unroutedTerms = netToUnroutedTerms.get(staticNet);
        if (unroutedTerms == null) {
            increaseNumNotNeedingRouting();
            return;
        }

        addStaticNetRoutingTargets(staticNet, unroutedTerms);
        if (staticNet.hasPIPs()) {
            increaseNumPreservedStaticNets();
        }
    }

    @Override
    protected void routeStaticNets() {
        if (staticNetAndRoutingTargets.isEmpty())
            return;

        Net gnd = design.getGndNet();
        Net vcc = design.getVccNet();

        // Move existing PIPs
        List<PIP> gndPips = (staticNetAndRoutingTargets.containsKey(gnd)) ? gnd.getPIPs() : Collections.emptyList();
        List<PIP> vccPips = (staticNetAndRoutingTargets.containsKey(vcc)) ? vcc.getPIPs() : Collections.emptyList();
        if (!gndPips.isEmpty()) gnd.setPIPs(new ArrayList<>());
        if (!vccPips.isEmpty()) vcc.setPIPs(new ArrayList<>());

        // Perform static net routing (which does no rip-up)
        super.routeStaticNets();

        // Since super.routeStaticNets() clobbers the PIPs list,
        // re-insert those existing PIPs
        gnd.getPIPs().addAll(gndPips);
        vcc.getPIPs().addAll(vccPips);
    }

    @Override
    protected RouteTerm getNetSource(Net net) {
        return netToSourcePartPin.getOrDefault(net, super.getNetSource(net));
    }

    @Override
    protected void addNetConnectionToRoutingTargets(Net net) {
        if (net.hasPIPs()) {
            // In partial routing mode, a net with PIPs is preserved.
            // This means the routed net is supposed to be fully routed without conflicts.
            preserveNet(net);
            increaseNumPreservedWireNets();
        }

        List<RouteTerm> sinkPins = netToUnroutedTerms.get(net);
        if (sinkPins != null) {
            if (sinkPins.isEmpty()) {
                throw new RuntimeException(net.getName());
            }

            if (net.hasPIPs()) {
                // preserveNet() above will have preserved all site/part pin nodes, undo that here
                for (RouteTerm term : sinkPins) {
                    Node node = term.getConnectedNode();
                    preservedNodes.remove(node);
                }
            }

            createsNetWrapperAndConnections(net, sinkPins);
        }
    }
}

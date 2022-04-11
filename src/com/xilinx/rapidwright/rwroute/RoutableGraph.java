/* 
 * Copyright (c) 2022 Xilinx, Inc. 
 * All rights reserved.
 *
 * Author: Eddie Hung, Xilinx Research Labs.
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
import com.xilinx.rapidwright.design.SiteInst;
import com.xilinx.rapidwright.design.SitePinInst;
import com.xilinx.rapidwright.device.Device;
import com.xilinx.rapidwright.device.IntentCode;
import com.xilinx.rapidwright.device.Node;
import com.xilinx.rapidwright.device.PIP;
import com.xilinx.rapidwright.device.Site;
import com.xilinx.rapidwright.device.SitePin;
import com.xilinx.rapidwright.device.SiteTypeEnum;
import com.xilinx.rapidwright.device.Tile;
import com.xilinx.rapidwright.device.TileTypeEnum;
import com.xilinx.rapidwright.util.CountUpDownLatch;
import com.xilinx.rapidwright.util.Pair;
import com.xilinx.rapidwright.util.ParallelismTools;
import com.xilinx.rapidwright.util.RuntimeTracker;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public class RoutableGraph {

    protected class RoutableNodeImpl extends RoutableNode {

        public RoutableNodeImpl(Node node, RoutableType type) {
            super(node, type);
        }

        @Override
        protected Routable getOrCreate(Node node, RoutableType type) {
            return RoutableGraph.this.getOrCreate(node, type).getFirst();
        }

        @Override
        public boolean isExcluded(Node parent, Node child) {
            boolean preserved = isPreserved(child);
            // TODO: Only necessary for partial router
            {
                // If preserved, check if child node has been created already
                Routable rnode = (preserved) ? RoutableGraph.this.getNode(child) : null;
                // If so, get its prev pointer
                Routable prev = (rnode != null) ? rnode.getPrev() : null;
                // Presence means that the only arc allowed to enter this child node
                // is if it came from prev
                if (prev != null && prev.getNode() == parent) {
                    preserved = false;
                    rnode.setVisited(false);
                }
            }
            if (preserved)
                return true;
            // Enable LUT routethrus on A1-5 (not A6 since using that should block O5
            // others from being used, but there isn't currently an efficient way to
            // check that during routing expansion so better exclude it during routing
            // generation)
            {
                if (parent.getIntentCode() == IntentCode.NODE_PINFEED) {
                    TileTypeEnum parentTileType = parent.getTile().getTileTypeEnum();
                    TileTypeEnum childTileType = child.getTile().getTileTypeEnum();
                    if (parentTileType == TileTypeEnum.INT &&
                            (childTileType == TileTypeEnum.CLEL_L || childTileType == TileTypeEnum.CLEL_R ||
                            childTileType == TileTypeEnum.CLEM || childTileType == TileTypeEnum.CLEM_R)) {
                        SitePin sp = parent.getSitePin();
                        Site s = sp.getSite();
                        SiteTypeEnum siteType = s.getSiteTypeEnum();
                        assert(siteType == SiteTypeEnum.SLICEL || siteType == SiteTypeEnum.SLICEM);
                        String pinName = sp.getPinName();
                        if (pinName.length() == 2) {
                            char first = pinName.charAt(0);
                            char second = pinName.charAt(1);
                            if (first >= 'A' && first <= 'H' && second >= '1' && second <= '5' /*'6'*/) {
                                SiteInst si = design.getSiteInstFromSite(s);

                                // Nothing placed at site, all routethrus possible
                                if (si == null)
                                    return false;

                                String childWireName = child.getWireName();
                                if (childWireName.endsWith("_O")) {
                                    boolean O6used = si.getNetFromSiteWire(first + "_O") != null;
                                    if (O6used)
                                        return true;
                                } else {
                                    assert(childWireName.endsWith("MUX"));

                                    boolean O5used = (si.getNetFromSiteWire(first + "5LUT_O5") != null);
                                    if (O5used)
                                        return true;

                                    Net A6 = si.getNetFromSiteWire(first + "6");
                                    boolean A6used = (A6 != null && A6.getType() != NetType.VCC);
                                    if (A6used)
                                        return true;
                                }

                                // Routethru allowed
                                return false;
                            }
                        }
                    }
                }
            }
            return super.isExcluded(parent, child);
        }

        @Override
        public Routable[] getChildren() {
            setChildren(setChildrenTimer);
            return super.getChildren();
        }
    }

    /**
     * A map of nodes to created rnodes
     */
    final protected Map<Node, Routable> nodesMap;

    /**
     * A map of preserved nodes to their nets
     */
    final private Map<LightweightNode, Net> preservedMap;

    final private CountUpDownLatch preservedMapOutstanding;

    /**
     * Visited rnodes data during connection routing
     */
    final protected Collection<Routable> visited;

    final protected RuntimeTracker setChildrenTimer;

    private long totalVisited;

    final Design design;

    public RoutableGraph(RuntimeTracker setChildrenTimer, Design design) {
        nodesMap = new HashMap<>();
        preservedMap = new ConcurrentHashMap<>();
        preservedMapOutstanding = new CountUpDownLatch();
        visited = new ArrayList<>();
        this.setChildrenTimer = setChildrenTimer;
        this.design = design;
    }

    public void initialize() {
        totalVisited = 0;
        visited.clear();
    }

    private void preserve(LightweightNode node, Net net) {
        Net existingNet = preservedMap.putIfAbsent(node, net);
        if (existingNet == null)
            return;
        // Nodes already preserved by the same net are ignored
        if (existingNet.equals(net))
            return;
        // TODO: Handle conflicts
    }

    public void asyncPreserve(Collection<Node> nodes, Net net) {
        preservedMapOutstanding.countUp();
        ParallelismTools.submit(() -> {
            nodes.forEach((node) -> preserve(new LightweightNode(node), net));
            preservedMapOutstanding.countDown();
        });
    }

    public void asyncPreserve(Net net) {
        preservedMapOutstanding.countUp();
        ParallelismTools.submit(() -> {
            List<SitePinInst> pins = net.getPins();
            SitePinInst sourcePin = net.getSource();
            assert(sourcePin == null || pins.contains(sourcePin));
            SitePinInst altSourcePin = net.getAlternateSource();
            assert(altSourcePin == null || pins.contains(altSourcePin));
            for(SitePinInst pin : net.getPins()) {
                // SitePinInst.isRouted() is meaningless for output pins
                if (!pin.isRouted() && !pin.isOutPin()) {
                    continue;
                }

                preserve(new LightweightNode(pin), net);
            }

            for(PIP pip : net.getPIPs()) {
                preserve(new LightweightNode(pip, true), net);
                preserve(new LightweightNode(pip, false), net);
            }

            preservedMapOutstanding.countDown();
        });
    }

    public void awaitPreserve() {
        try {
            preservedMapOutstanding.await();
        } catch (InterruptedException e) {
            throw new RuntimeException();
        }
    }

    public void unpreserve(Node node) {
        preservedMap.remove(new LightweightNode(node));
    }

    public boolean isPreserved(Node node) {
        return preservedMap.containsKey(new LightweightNode(node));
    }

    public Collection<Node> getPreservedNodes(Device device) {
        List<Node> nodes = new ArrayList<>(preservedMap.size());
        preservedMap.keySet().forEach((k) -> nodes.add(new Node(device.getTile(k.tileID), k.wireID)));
        return nodes;
    }

    public Net getPreservedNet(Node node) {
        return preservedMap.get(new LightweightNode(node));
    }

    public Routable getNode(Node node) {
        return nodesMap.get(node);
    }

    public Set<Node> getNodes() {
        return Collections.unmodifiableSet(nodesMap.keySet());
    }

    public Set<Map.Entry<Node,Routable>> getNodeEntries() {
        return Collections.unmodifiableSet(nodesMap.entrySet());
    }

    public int numNodes() {
        return nodesMap.size();
    }

    protected Routable create(Node node, RoutableType type) {
        return new RoutableNodeImpl(node, type);
    }

    public Pair<Routable,Boolean> getOrCreate(Node node, RoutableType type) {
        final boolean[] inserted = {false};
        Routable rnode = nodesMap.compute(node, (k, v) -> {
            if (v == null) {
                // this is for initializing sources and sinks of those to-be-routed nets' connections
                v = create(node, type);
                inserted[0] = true;
            }
            return v;
        });
        return new Pair<>(rnode, inserted[0]);
    }

    public void visit(Routable rnode) {
        visited.add(rnode);
    }

    /**
     * Resets the expansion history.
     */
    public void resetExpansion() {
        for (Routable node : visited) {
            node.setVisited(false);
        }
        totalVisited += visited.size();
        visited.clear();
    }

    public long getTotalVisited() {
        return totalVisited;
    }

    public int averageChildren() {
        int sum = 0;
        for(Map.Entry<Node,Routable> e : getNodeEntries()){
            RoutableNodeImpl rnode = (RoutableNodeImpl) e.getValue();
            sum += (rnode.children != null) ? rnode.children.length : 0;
        }
        return Math.round((float) sum / numNodes());
    }

}

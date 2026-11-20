/*
 *
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

package com.xilinx.rapidwright.timing.clock;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;

import com.xilinx.rapidwright.design.Design;
import com.xilinx.rapidwright.design.Net;
import com.xilinx.rapidwright.design.SitePinInst;
import com.xilinx.rapidwright.device.Node;
import com.xilinx.rapidwright.device.PIP;
import com.xilinx.rapidwright.device.Site;
import com.xilinx.rapidwright.device.SitePin;
import com.xilinx.rapidwright.device.Tile;

/**
 * The routing of a net on the global clock network, timed node by node by a {@link ClockNodeDelayModel}: each
 * node has the delay of its class (by its span and its number of branches in the route), the programmable delay
 * of its site if it is a site output pin with one, and the delay that the node driving it adds by the way it is
 * left towards it (its tap terms, from the tile it is entered at to the tile of the PIP leaving it, in tile
 * coordinates, and the delay tap of that PIP). The load site pin's node adds its tap terms to the load's site.
 * {@link ClockModel} times the clock nets' routes this way, and the timing analysis the routes of the clock
 * nets that carry data (those of clock buffers used as data).
 */
public final class ClockRoute {

    private final ClockNodeDelayModel model;
    private final Design design;
    /** The node driving each node of the route */
    private final Map<Node, Node> up = new HashMap<>();
    /** The PIP entering each node of the route */
    private final Map<Node, PIP> enter = new HashMap<>();
    /** The number of branches of each node: the nodes and site pins it drives */
    private final Map<Node, Integer> branches = new HashMap<>();
    /** The programmable delay taps of sites: {key index, tap}, or an empty array if none; cached */
    private final Map<Site, int[]> siteTaps = new ConcurrentHashMap<>();
    /** The keys of the programmable delays of sites, empty if none or without a design */
    private final List<String> siteTapKeys;

    /**
     * Reads the routing of a net.
     * @param net The net (its PIPs and sink site pins).
     * @param model Times the nodes.
     * @param design The design, for the programmable delays of sites (their BEL attributes), or null for none.
     */
    public ClockRoute(Net net, ClockNodeDelayModel model, Design design) {
        this.model = model;
        this.design = design;
        siteTapKeys = design == null ? Collections.emptyList()
                : new ArrayList<>(new TreeSet<>(model.getParams().getSiteTapKeys()));
        for (PIP pip : net.getPIPs()) {
            Node s = pip.getStartNode();
            Node e = pip.getEndNode();
            if (pip.isReversed()) {
                Node t = s;
                s = e;
                e = t;
            }
            if (s == null || e == null) continue;
            up.put(e, s);
            enter.put(e, pip);
            branches.merge(s, 1, Integer::sum);
            if (pip.isRouteThru()) {
                // A route-thru (e.g. a BUFCE_LEAF or BUFCE_ROW) also loads the node driving it with its site
                // pin, as the branch counts of the clock delay data do
                branches.merge(s, 1, Integer::sum);
            }
        }
        // A node driving a sink site pin has that load as a branch too
        for (SitePinInst spi : net.getPins()) {
            if (spi.isOutPin()) continue;
            Node n = spi.getConnectedNode();
            if (n != null) {
                branches.merge(n, 1, Integer::sum);
            }
        }
    }

    /** @return Whether a PIP of the route enters a node. */
    boolean isEntered(Node node) {
        return up.containsKey(node);
    }

    /** @return The node driving a node of the route, null if none (the route's source). */
    Node getDriver(Node node) {
        return up.get(node);
    }

    /** The tile a route node is entered at: that of the PIP entering it, else its own tile */
    private Tile enterTile(Node n) {
        PIP pip = enter.get(n);
        return pip != null ? pip.getTile() : n.getTile();
    }

    /**
     * Gets the delays of a node of the route: its own (its class, its branches, the programmable delay of its
     * site) and those that the node driving it adds by the way it is left towards it.
     * @param node The node.
     * @param out Receives the delays, by corner ordinal.
     */
    void getNodeDelays(Node node, float[] out) {
        model.getDelays(node, branches.getOrDefault(node, 0), out);
        addSiteTap(node, out);
        Node from = up.get(node);
        PIP pip = enter.get(node);
        if (from != null && pip != null) {
            float[] leave = new float[out.length];
            Tile in = enterTile(from);
            Tile to = pip.getTile();
            model.getLeaveDelays(from, Math.abs(to.getTileXCoordinate() - in.getTileXCoordinate()),
                    Math.abs(to.getTileYCoordinate() - in.getTileYCoordinate()), pip, leave);
            for (int c = 0; c < out.length; c++) {
                out[c] += leave[c];
            }
        }
    }

    /**
     * Gets the delays that a load's site pin node adds by the way it is left towards the load's site: its tap
     * terms from the tile it is entered at to the site pin's tile.
     * @param sitePinNode The node of the load site pin.
     * @param sitePin The load site pin.
     * @param out Receives the delays, by corner ordinal.
     */
    void getLeaveToSite(Node sitePinNode, SitePinInst sitePin, float[] out) {
        Tile in = enterTile(sitePinNode);
        Tile to = sitePin.getTile();
        model.getLeaveDelays(sitePinNode, Math.abs(to.getTileXCoordinate() - in.getTileXCoordinate()),
                Math.abs(to.getTileYCoordinate() - in.getTileYCoordinate()), null, out);
    }

    /**
     * Gets the route delay of a sink site pin of the net, from the route's source: the delays of the nodes from
     * the sink's node back to the source (see {@code getNodeDelays}), and those of the sink's
     * node towards the sink's site.
     * @param sink A sink site pin of the net.
     * @return The delays by corner ordinal, in ps, or null if the routing does not reach the sink.
     */
    public float[] getDelays(SitePinInst sink) {
        Node node = sink.getConnectedNode();
        if (node == null || !up.containsKey(node)) {
            return null;
        }
        float[] total = new float[ClockDelayParams.NUM_CORNERS];
        float[] d = new float[ClockDelayParams.NUM_CORNERS];
        getLeaveToSite(node, sink, total);
        Set<Node> seen = new HashSet<>();
        for (; node != null && seen.add(node); node = up.get(node)) {
            getNodeDelays(node, d);
            for (int c = 0; c < total.length; c++) {
                total[c] += d[c];
            }
        }
        return total;
    }

    /**
     * Adds the delay of the programmable clock delay of the site of a route node that is a site output pin
     * (e.g. a Versal IRI quadrant's IRI_IMR_CLK_MOD.CLK_DLY_VAL_COE).
     */
    private void addSiteTap(Node node, float[] delays) {
        if (siteTapKeys.isEmpty() || !node.getWireName().endsWith("_O_PIN")) {
            return;
        }
        SitePin sp = node.getSitePin();
        if (sp == null || sp.isInput() || sp.getSite() == null) {
            return;
        }
        Site site = sp.getSite();
        int[] tap = siteTaps.computeIfAbsent(site, st -> ClockModel.getSiteTap(design, st, siteTapKeys));
        if (tap.length == 2) {
            for (int c = 0; c < delays.length; c++) {
                delays[c] += model.getParams().getSiteTapDelay(siteTapKeys.get(tap[0]), c, tap[1]);
            }
        }
    }
}

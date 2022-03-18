/* 
 * Copyright (c) 2020 Xilinx, Inc. 
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
 
package com.xilinx.rapidwright.device;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Deque;
import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Map.Entry;

import com.xilinx.rapidwright.device.BELPin;
import com.xilinx.rapidwright.device.Device;
import com.xilinx.rapidwright.device.PIP;
import com.xilinx.rapidwright.device.SitePIP;
import com.xilinx.rapidwright.device.SitePin;
import com.xilinx.rapidwright.device.Tile;

/**
 * Helper class for pseudo PIP types and getting site use information.
 *
 */
public class PseudoPIPHelper {

    private Tile tilePrototype;

    private int startWire;

    private int endWire;

    private SiteTypeEnum siteTypeEnum;

    private List<BELPin> belPins;

    private static Map<String, Map<TileTypeEnum,HashMap<PIPWires, PseudoPIPHelper>>> deviceMap;

    private static PIPWires staticInstPIPWires;
    static {
        deviceMap = new HashMap<String, Map<TileTypeEnum,HashMap<PIPWires,PseudoPIPHelper>>>();
        staticInstPIPWires = new PIPWires(0, 0);
    }

    /**
     * Creates a PIP helper instance.  To be used for route thru PIPs (pseudo PIPs only)
     * @see PIP#isRouteThru() To check if PIP is a pseudo (route thru) PIP
     * @param pip A prototype pseudo PIP to use.
     */
    private PseudoPIPHelper(PIP pip) {
        if(pip == null || !pip.isRouteThru()) {
            throw new RuntimeException("ERROR: Attempting to initialize "
                    + getClass().getName() + " with non pseudo PIP: " + pip);
        }
        this.tilePrototype = pip.getTile();
        this.startWire = pip.getStartWireIndex();
        this.endWire = pip.getEndWireIndex();
        SitePin startPin = tilePrototype.getSitePinFromWire(pip.getStartWireIndex());
        SitePin endPin = tilePrototype.getSitePinFromWire(pip.getEndWireIndex());
        siteTypeEnum = startPin.getSite().getSiteTypeEnum();
        belPins = getPath(startPin, endPin);
    }

    public Tile getTilePrototype() {
        return tilePrototype;
    }

    public List<BELPin> getUsedBELPins() {
        return belPins;
    }

    public TileTypeEnum getTileTypeEnum() {
        return tilePrototype.getTileTypeEnum();
    }

    public SiteTypeEnum getSiteTypeEnum() {
        return siteTypeEnum;
    }

    public int getStartWire() {
        return startWire;
    }

    public int getEndWire() {
        return endWire;
    }

    @Override
    public int hashCode() {
        final int prime = 31;
        int result = 1;
        result = prime * result + endWire;
        result = prime * result + startWire;
        TileTypeEnum type = getTileTypeEnum();
        result = prime * result + ((type == null) ? 0 : type.hashCode());
        return result;
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj)
            return true;
        if (obj == null)
            return false;
        if (getClass() != obj.getClass())
            return false;
        PseudoPIPHelper other = (PseudoPIPHelper) obj;
        if (endWire != other.endWire)
            return false;
        if (startWire != other.startWire)
            return false;
        if (getTileTypeEnum() != other.getTileTypeEnum())
            return false;
        return true;
    }

    /**
     * Gets the pseudo PIP helper for the corresponding PIP.
     * (see also {@link PseudoPIPHelper#getPseudoPIPMap})
     * @param pip The existing pseudo PIP of interest.
     * @return The pseudo PIP helper object, or null if none could be found.
     */
    public static PseudoPIPHelper getPseudoPIPHelper(PIP pip) {
        Map<TileTypeEnum,HashMap<PIPWires,PseudoPIPHelper>> map =
                getPseudoPIPMap(pip.getTile().getDevice());
        HashMap<PIPWires,PseudoPIPHelper> pips = map.get(pip.getTile().getTileTypeEnum());
        staticInstPIPWires.setStartWire(pip.getStartWireIndex());
        staticInstPIPWires.setEndWire(pip.getEndWireIndex());
        return pips.get(staticInstPIPWires);
    }

    /**
     * Gets a map for the given device that enumerates all pseudo PIPs for each tile type.  This
     * map is cached so that secondary calls do not regenerate the map.
     * @param device The device of interest
     * @return A map of maps where the set of keys are the tile types and values are the maps of
     * abstract PIP (see {@link PIPWires}) to their respective pseudo PIP helper class.
     */
    public static Map<TileTypeEnum,HashMap<PIPWires, PseudoPIPHelper>> getPseudoPIPMap(Device device) {
        Map<TileTypeEnum,HashMap<PIPWires, PseudoPIPHelper>> map = deviceMap.get(device.getName());
        if(map != null) {
            return map;
        }
        map = new HashMap<TileTypeEnum, HashMap<PIPWires,PseudoPIPHelper>>();
        HashSet<TileTypeEnum> visited = new HashSet<TileTypeEnum>();
        for(Tile tile : device.getAllTiles()) {
            TileTypeEnum type = tile.getTileTypeEnum();
            if(visited.contains(type)) continue;
            HashMap<PIPWires, PseudoPIPHelper> pipMap = new HashMap<PIPWires, PseudoPIPHelper>();
            map.put(type, pipMap);
            visited.add(type);
            for(PIP pip : tile.getPIPs()) {
                if(!pip.isRouteThru()) continue;
                PIPWires wirePair = new PIPWires(pip.getStartWireIndex(), pip.getEndWireIndex());
                pipMap.put(wirePair, new PseudoPIPHelper(pip));
            }
        }
        deviceMap.put(device.getName(), map);
        return map;
    }

    static private class SiteNode {
        public SiteNode parent;

        // Because this is a backwards search, which BEL pin was used to reach
        // this site wire?
        public BELPin sinkBelPin;
    };

    /**
     * Find the shortest route possible from the output pin start and output
     * pin end.
     *
     * @return The list of BELPins occupied when the pseudo PIP is in use for the site.
     */
    private static List<BELPin> findCommonRoute(SitePin start, SitePin end) {
        BELPin startBELPin = start.getBELPin();
        BELPin endBELPin = end.getBELPin();

        // Set of site wires consumed by the output SitePin start.
        // This set is generated by walking backwards from the site port
        // until a logic output BEL pin is reached.
        Set<Integer> siteWires = new HashSet<Integer>();
        siteWires.add(startBELPin.getSiteWireIndex());

        Deque<BELPin> sinkBelPinsToExplore = new ArrayDeque<BELPin>();
        sinkBelPinsToExplore.addLast(startBELPin);

        while(sinkBelPinsToExplore.size() > 0) {
            BELPin belPin = sinkBelPinsToExplore.removeFirst();
            siteWires.add(belPin.getSiteWireIndex());

            BELPin sourceBelPin = belPin.getSourcePin();
            for(SitePIP pip : sourceBelPin.getSitePIPs()) {
                sinkBelPinsToExplore.addLast(pip.getInputPin());
            }
        }

        // Now we have valid site wires that share the net that "start" has.
        // Find the shortest path from "end" to any of those site wires.

        // Using a deque here to ensure that we do a breadth first search,
        // rather than depth first.
        //
        // Enqueue nodes are pushed to the back, and nodes are deque'd from the
        // front.
        Deque<SiteNode> nodesToExplore = new ArrayDeque<SiteNode>();

        SiteNode firstNode = new SiteNode();
        firstNode.sinkBelPin = endBELPin;
        nodesToExplore.addLast(firstNode);

        SiteNode result = null;
        while(nodesToExplore.size() > 0) {
            SiteNode parentNode = nodesToExplore.removeFirst();

            BELPin sourceBelPin = parentNode.sinkBelPin.getSourcePin();

            for(SitePIP pip : sourceBelPin.getSitePIPs()) {
                BELPin newSinkBelPin = pip.getInputPin();

                SiteNode node = new SiteNode();
                node.sinkBelPin = newSinkBelPin;
                node.parent = parentNode;

                if(siteWires.contains(newSinkBelPin.getSiteWireIndex())) {
                    // We found a path to a site wire that is driven by the
                    // net used by "start", all done!
                    result = node;
                    break;
                } else {
                    nodesToExplore.addLast(node);
                }
            }

            if(result != null) {
                break;
            }
        }

        if(result == null) {
            throw new RuntimeException(String.format("ERROR: Failed to find path for pseudo pip from %s/%s to %s/%s",
                        start.getSite().getName(), start.getPinName(),
                        end.getSite().getName(), end.getPinName()));
        }

        LinkedList<BELPin> belPins = new LinkedList<BELPin>();

        // For the initial cursor, only add sink.  The source is driven by
        // the other path, and isn't consumed by using this pseudo pip.
        belPins.addLast(result.sinkBelPin);
        SiteNode node_cursor = result.parent;
        while(node_cursor != null) {
            belPins.addLast(node_cursor.sinkBelPin.getSourcePin());
            belPins.addLast(node_cursor.sinkBelPin);
            node_cursor = node_cursor.parent;
        }

        return belPins;
    }

    /**
     * Discovers and creates the path of BELPins occupied by the pseudo PIP route through a site.
     * @param start The start site pin (not always an input)
     * @param end The end site pin
     * @return The list of BELPins occupied when the pseudo PIP is in use for the site.
     */
    private static List<BELPin> getPath(SitePin start, SitePin end){
        BELPin startBELPin = start.getBELPin();
        BELPin endBELPin = end.getBELPin();

        // Dual output PIP, search back to common point
        if(!start.isInput()) {
            return findCommonRoute(start, end);
        }

        LinkedList<BELPin> belPins = new LinkedList<BELPin>();
        BELPin curr = endBELPin;
        belPins.addFirst(curr);

        return exploreInput(belPins, curr, startBELPin);
    }

    /**
     * Recursive method to explore site routing and identify the path of BELPins used by a
     * pseudo PIP (route thru).
     * @param path Current list of BELPins
     * @param curr The current BELPin in the search
     * @param target The end or target BELPin (always a site output pin)
     * @return The list of BELPins found from the given path or null if no path could be found for
     * the current pin.
     */
    private static LinkedList<BELPin> exploreInput(LinkedList<BELPin> path, BELPin curr, BELPin target) {
        BELPin src = curr.getSourcePin();
        if(src.equals(target)) {
            path.addFirst(src);
            return path;
        }
        if(curr.getName().equals("PAD")) {
            if(curr.getSiteWireName().equals(target.getName())) {
                path.addFirst(target);
                return path;
            }
        }
        for(BELPin input : getRoutableInputs(src)) {
            LinkedList<BELPin> copy = new LinkedList<>(path);
            copy.addFirst(src);
            copy.addFirst(input);
            LinkedList<BELPin> result = exploreInput(copy, input, target);
            if(result != null) return result;
        }

        return null;
    }

    /**
     * Explores the inputs of a BEL either through SitePIPs or inputs for potential connectivity
     * to the output.
     * @param output The output BELPin of a BEL to find backwards arcs through.
     * @return The list of potential source BELPins traveling through a BEL for a pseudo PIP.
     */
    private static List<BELPin> getRoutableInputs(BELPin output){
        List<BELPin> pins = new ArrayList<BELPin>();
        for(SitePIP pip : output.getSitePIPs()) {
            pins.add(pip.getInputPin());
        }
        if(pins.size() == 0) {
            for(BELPin input : output.getBEL().getPins()) {
                if(input.isOutput()) continue;
                pins.add(input);
            }
        }
        return pins;
    }

    public String getPseudoPIPName() {
        return tilePrototype.getTileTypeEnum() + "." + tilePrototype.getWireName(getStartWire())
                + "->" + tilePrototype.getWireName(getEndWire());
    }

    public static void main(String[] args) {
        if(args.length != 1) {
            System.out.println("USAGE: <device name>");
            System.out.println("   Print pseudo pips from device");
            return;
        }

        Device device = Device.getDevice(args[0]);
        Map<TileTypeEnum,HashMap<PIPWires, PseudoPIPHelper>> map = getPseudoPIPMap(device);
        for(Entry<TileTypeEnum, HashMap<PIPWires, PseudoPIPHelper>> e : map.entrySet()) {
            System.out.println(e.getKey() + ": ");
            for(Entry<PIPWires, PseudoPIPHelper> e2 : e.getValue().entrySet()) {
                Tile t = e2.getValue().getTilePrototype();
                System.out.println("  " + t.getWireName(e2.getKey().getStartWire())
                                +  "->" + t.getWireName(e2.getKey().getEndWire())
                                + " " + e2.getValue().getUsedBELPins());
            }
        }
    }
}

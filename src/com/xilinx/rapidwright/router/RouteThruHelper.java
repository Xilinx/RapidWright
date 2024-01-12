/*
 * Copyright (c) 2020-2022, Xilinx, Inc.
 * Copyright (c) 2022-2023, Advanced Micro Devices, Inc.
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

package com.xilinx.rapidwright.router;

import java.io.File;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map.Entry;

import com.esotericsoftware.kryo.io.Input;
import com.esotericsoftware.kryo.io.Output;
import com.xilinx.rapidwright.design.Cell;
import com.xilinx.rapidwright.design.Design;
import com.xilinx.rapidwright.design.Net;
import com.xilinx.rapidwright.design.SiteInst;
import com.xilinx.rapidwright.device.BEL;
import com.xilinx.rapidwright.device.BELPin;
import com.xilinx.rapidwright.device.Device;
import com.xilinx.rapidwright.device.Node;
import com.xilinx.rapidwright.device.PIP;
import com.xilinx.rapidwright.device.SitePin;
import com.xilinx.rapidwright.device.Tile;
import com.xilinx.rapidwright.device.TileTypeEnum;
import com.xilinx.rapidwright.device.Wire;
import com.xilinx.rapidwright.device.WireInterface;
import com.xilinx.rapidwright.util.FileTools;

/**
 * Example of how to check if a node to node connection is a routethru
 */
public class RouteThruHelper {

    private HashMap<TileTypeEnum,HashSet<Integer>> routeThrus;

    private Device device;

    public static String getSerializedFileName(String deviceName) {
        String fileName = FileTools.getRapidWrightResourceFileName(FileTools.getRouteThruFileName(deviceName));
        FileTools.makeDirs(Paths.get(fileName).getParent().toString());
        return fileName;
    }

    public RouteThruHelper(Device device) {
        this.device = device;
        init();
    }

    private void writeFile() {
        try (Output out = FileTools.getKryoZstdOutputStream(getSerializedFileName(device.getName()))) {
            out.writeInt(routeThrus.size());
            for (Entry<TileTypeEnum, HashSet<Integer>> e : routeThrus.entrySet()) {
                out.writeString(e.getKey().toString());
                out.writeInt(e.getValue().size());
                for (Integer i : e.getValue()) {
                    out.writeInt(i);
                }
            }
        }
    }

    private void readFile() {
        routeThrus = new HashMap<TileTypeEnum, HashSet<Integer>>();
        try (Input in = FileTools.getKryoZstdInputStream(getSerializedFileName(device.getName()))) {
            int count = in.readInt();
            for (int i=0; i < count; i++) {
                TileTypeEnum type = TileTypeEnum.valueOf(in.readString());
                int count2 = in.readInt();
                HashSet<Integer> pips = new HashSet<Integer>(count2);
                for (int j=0; j < count2; j++) {
                    pips.add(in.readInt());
                }
                routeThrus.put(type, pips);
            }
        }
    }

    private void init() {
        String serializedFileName = getSerializedFileName(device.getName());
        routeThrus = new HashMap<TileTypeEnum,HashSet<Integer>>();
        if (new File(serializedFileName).exists() && !FileTools.isFileGzipped(Paths.get(serializedFileName))) {
            readFile();
            return;
        }
        for (Tile tile : device.getAllTiles()) {
            if (routeThrus.containsKey(tile.getTileTypeEnum())) continue;
            HashSet<Integer> rtPIPs = new HashSet<Integer>();
            for (PIP p : tile.getPIPs()) {
                if (p.isRouteThru()) {
                    int startEndWirePair = (p.getStartWireIndex() << 16) | p.getEndWireIndex();
                    rtPIPs.add(startEndWirePair);
                }
            }
            if (rtPIPs.size() > 0) routeThrus.put(tile.getTileTypeEnum(), rtPIPs);
        }
        writeFile();
    }

    public boolean isRouteThru(Tile tile, int startWire, int endWire) {
        HashSet<Integer> rtPairs = routeThrus.get(tile.getTileTypeEnum());
        if (rtPairs == null) return false;
        return rtPairs.contains(startWire << 16 | endWire);
    }

    public boolean isRouteThru(Node start, Node end) {
        Tile tile = end.getTile();
        int endWire = end.getWire();
        Wire[] wiresInStartNode = start.getAllWiresInNode();
        HashSet<Integer> rtPairs = routeThrus.get(tile.getTileTypeEnum());
        if (rtPairs == null) return false;
        for (Wire w : wiresInStartNode) {
            if (w.getTile().equals(tile)) {
                if (rtPairs.contains((w.getWireIndex() << 16) | endWire)) {
                    return true;
                }
            }
        }
        return false;
    }

    private void printRouteThrusByTileType() {
        HashSet<TileTypeEnum> visited = new HashSet<>();
        for (Tile tile : device.getAllTiles()) {
            if (visited.contains(tile.getTileTypeEnum())) continue;
            visited.add(tile.getTileTypeEnum());
            HashSet<Integer> rtPairs = routeThrus.get(tile.getTileTypeEnum());
            if (rtPairs == null) continue;
            System.out.println(tile.getTileTypeEnum() + "(" + tile.getName() + "):");
            for (Integer i : rtPairs) {
                int startWire = i >>> 16;
                int endWire = i & 0xffff;
                System.out.println("  " + tile.getWireName(startWire) + " -> " + tile.getWireName(endWire));
            }
        }
    }

    /**
     * Given a routethru PIP check that it is available for use by checking for net and cell
     * collisions on the site it is routing through.
     */
    public static boolean isRouteThruPIPAvailable(Design design, PIP routethru) {
        if (!routethru.isRouteThru()) return false;
        return isRouteThruPIPAvailable(design, routethru.getStartWire(), routethru.getEndWire());
    }

    /**
     * Given a SitePin object, check that it is available for use as part of a route-through.
     */
    public static boolean isRouteThruSitePinAvailable(Design design, SitePin sitePin) {
        if (sitePin == null) {
            return false;
        }
        SiteInst siteInst = design.getSiteInstFromSite(sitePin.getSite());
        if (siteInst == null) {
            return true;
        }
        Net netCollision = siteInst.getNetFromSiteWire(sitePin.getBELPin().getSiteWireName());
        if (netCollision != null) {
            return false;
        }
        return true;
    }

    /**
     * Given two WireInterface objects (assumed to make up a routethru PIP) check that this
     * PIP is available for use by checking for net and cell collisions within the site
     * it is routing through.
     */
    public static boolean isRouteThruPIPAvailable(Design design, WireInterface start, WireInterface end) {
        SitePin outPin = end.getSitePin();
        if (!isRouteThruSitePinAvailable(design, outPin)) {
            return false;
        }
        SitePin inPin = start.getSitePin();
        if (!isRouteThruSitePinAvailable(design, inPin)) {
            return false;
        }
        assert(inPin.getSite() == outPin.getSite());

        SiteInst siteInst = design.getSiteInstFromSite(inPin.getSite());
        if (siteInst != null) {
            for (BELPin sink : inPin.getBELPin().getSiteConns()) {
                BEL sinkBEL = sink.getBEL();
                if (sinkBEL.getName().charAt(0) != inPin.getPinName().charAt(0)) {
                    // Ignore BELs that don't share the same LUT letter
                    // Specifically, this is to prevent H[1-6] inputs on SLICEM sites
                    // -- which also drive [A-G].WA[1-6] -- from considering [A-G]LUT[56]
                    continue;
                }
                Cell cellCollision = siteInst.getCell(sinkBEL);
                if (cellCollision != null) {
                    return false;
                }
            }
        }
        return true;
    }

    public static void main(String[] args) {
        RouteThruHelper rtHelper = new RouteThruHelper(Device.getDevice(Device.AWS_F1));

        //rtHelper.printRouteThrusByTileType();

        for (Tile tile : rtHelper.device.getAllTiles()) {
            if (tile.getTileTypeEnum() == TileTypeEnum.INT) {
                for (String wireName : tile.getWireNames()) {
                    Node node = Node.getNode(tile, wireName);
                    for (Node downhill : node.getAllDownhillNodes()) {
                        if (rtHelper.isRouteThru(node, downhill)) {
                            System.out.println(node + " " + downhill);
                        }
                    }
                }
                break;
            }
        }

    }
}

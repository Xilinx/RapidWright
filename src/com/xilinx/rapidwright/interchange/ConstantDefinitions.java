/*
 * Copyright (c) 2020-2022, Xilinx, Inc.
 * Copyright (c) 2022-2023, Advanced Micro Devices, Inc.
 * All rights reserved.
 *
 * Author: Keith Rothman, Google, Inc.
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

package com.xilinx.rapidwright.interchange;

import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;

import org.capnproto.PrimitiveList;
import org.capnproto.StructList;
import org.capnproto.StructList.Builder;

import com.xilinx.rapidwright.design.CellPinStaticDefaults;
import com.xilinx.rapidwright.design.Design;
import com.xilinx.rapidwright.design.SiteInst;
import com.xilinx.rapidwright.design.Unisim;
import com.xilinx.rapidwright.design.Net;
import com.xilinx.rapidwright.design.NetType;
import com.xilinx.rapidwright.device.BEL;
import com.xilinx.rapidwright.device.BELPin;
import com.xilinx.rapidwright.device.Device;
import com.xilinx.rapidwright.device.Node;
import com.xilinx.rapidwright.device.Tile;
import com.xilinx.rapidwright.device.TileTypeEnum;
import com.xilinx.rapidwright.device.Site;
import com.xilinx.rapidwright.device.SiteTypeEnum;
import com.xilinx.rapidwright.device.Wire;
import com.xilinx.rapidwright.interchange.DeviceResources.Device.Constants;
import com.xilinx.rapidwright.interchange.DeviceResources.Device.ConstantType;
import com.xilinx.rapidwright.interchange.DeviceResources.Device.Constants.SiteConstantSource;
import com.xilinx.rapidwright.interchange.DeviceResources.Device.Constants.CellPinValue;
import com.xilinx.rapidwright.interchange.DeviceResources.Device.Constants.DefaultCellConnection;
import com.xilinx.rapidwright.interchange.DeviceResources.Device.Constants.DefaultCellConnections;
import com.xilinx.rapidwright.interchange.DeviceResources.Device.Constants.NodeConstantSource;
import com.xilinx.rapidwright.interchange.DeviceResources.Device.WireConstantSources;
import com.xilinx.rapidwright.interchange.DeviceResources.Device.TileType;

public class ConstantDefinitions {
    private Map<Map.Entry<SiteTypeEnum, String>, String> vccBels;
    private Map<Map.Entry<SiteTypeEnum, String>, String> gndBels;
    private Set<Map.Entry<TileTypeEnum, Integer>> vccWires;
    private Set<Map.Entry<TileTypeEnum, Integer>> gndWires;
    private Set<Map.Entry<String, String>> exceptionalVccNodes;
    private Set<Map.Entry<String, String>> exceptionalGndNodes;

    public ConstantDefinitions(StringEnumerator allStrings, Constants.Reader reader, Map<TileTypeEnum, TileType.Reader> tileTypes) {
        vccBels = new HashMap<Map.Entry<SiteTypeEnum, String>, String>();
        gndBels = new HashMap<Map.Entry<SiteTypeEnum, String>, String>();
        vccWires = new HashSet<Map.Entry<TileTypeEnum, Integer>>();
        gndWires = new HashSet<Map.Entry<TileTypeEnum, Integer>>();
        exceptionalVccNodes = new HashSet<Map.Entry<String, String>>();
        exceptionalGndNodes = new HashSet<Map.Entry<String, String>>();

        for (SiteConstantSource.Reader source : reader.getSiteSources()) {
            String siteType = allStrings.get(source.getSiteType());
            SiteTypeEnum siteTypeEnum = SiteTypeEnum.valueOf(siteType);
            String bel = allStrings.get(source.getBel());
            String belPin = allStrings.get(source.getBelPin());

            ConstantType constant = source.getConstant();
            if (constant == ConstantType.VCC) {
                vccBels.put(new AbstractMap.SimpleEntry<SiteTypeEnum, String>(siteTypeEnum, bel), belPin);
            } else if (constant == ConstantType.GND) {
                gndBels.put(new AbstractMap.SimpleEntry<SiteTypeEnum, String>(siteTypeEnum, bel), belPin);
            } else {
                throw new RuntimeException("Unexpected value of constant " + constant.name());
            }
        }

        for (NodeConstantSource.Reader source : reader.getNodeSources()) {
            String tile = allStrings.get(source.getTile());
            String wire = allStrings.get(source.getWire());

            ConstantType constant = source.getConstant();
            if (constant == ConstantType.VCC) {
                exceptionalVccNodes.add(new AbstractMap.SimpleEntry<String, String>(tile, wire));
            } else if (constant == ConstantType.GND) {
                exceptionalGndNodes.add(new AbstractMap.SimpleEntry<String, String>(tile, wire));
            } else {
                throw new RuntimeException("Unexpected value of constant " + constant.name());
            }
        }

        for (Map.Entry<TileTypeEnum, TileType.Reader> entry : tileTypes.entrySet()) {
            TileTypeEnum tileTypeEnum = entry.getKey();
            TileType.Reader tileType = entry.getValue();

            for (WireConstantSources.Reader source : tileType.getConstants()) {
                ConstantType constant = source.getConstant();
                PrimitiveList.Int.Reader wires = source.getWires();
                for (int i = 0; i < wires.size(); ++i) {
                    Integer wireIndex = wires.get(i);
                    Map.Entry<TileTypeEnum, Integer> key = new AbstractMap.SimpleEntry<TileTypeEnum, Integer>(tileTypeEnum, wireIndex);
                    if (constant == ConstantType.VCC) {
                        vccWires.add(key);
                    } else if (constant == ConstantType.GND) {
                        gndWires.add(key);
                    } else {
                        throw new RuntimeException("Unexpected value of constant " + constant.name());
                    }
                }
            }
        }
    }

    private Map.Entry<SiteTypeEnum, String> getBelKey(BEL bel) {
        SiteTypeEnum siteType = bel.getSiteTypeEnum();
        return new AbstractMap.SimpleEntry<SiteTypeEnum, String>(siteType, bel.getName());
    }

    public BELPin getConstantSource(BEL bel) {
        Map.Entry<SiteTypeEnum, String> key = getBelKey(bel);
        String pin = vccBels.get(key);
        if (pin != null) {
            return bel.getPin(pin);
        }

        pin = gndBels.get(key);
        if (pin != null) {
            return bel.getPin(pin);
        }

        return null;
    }

    public boolean isBelTied(BEL bel) {
        return isBelTiedVcc(bel) || isBelTiedGnd(bel);
    }

    public boolean isBelTiedVcc(BEL bel) {
        Map.Entry<SiteTypeEnum, String> key = getBelKey(bel);
        return vccBels.containsKey(key);
    }

    public boolean isBelTiedGnd(BEL bel) {
        Map.Entry<SiteTypeEnum, String> key = getBelKey(bel);
        return gndBels.containsKey(key);
    }

    public boolean isNodeTied(Node node) {
        return isNodeTiedVcc(node) || isNodeTiedGnd(node);
    }

    public boolean isNodeTiedVcc(Node node) {
        Tile tile = node.getTile();
        Map.Entry<String, String> key = new AbstractMap.SimpleEntry<String, String>(tile.getName(), node.getWireName());
        if (exceptionalVccNodes.contains(key)) {
            return true;
        }
        if (exceptionalGndNodes.contains(key)) {
            return false;
        }


        for (Wire wire : node.getAllWiresInNode()) {
            TileTypeEnum tileType = wire.getTile().getTileTypeEnum();
            boolean found = vccWires.contains(new AbstractMap.SimpleEntry<TileTypeEnum, Integer>(tileType, wire.getWireIndex()));
            if (found) {
                return true;
            }
        }
        return false;
    }

    public boolean isNodeTiedGnd(Node node) {
        Tile tile = node.getTile();
        Map.Entry<String, String> key = new AbstractMap.SimpleEntry<String, String>(tile.getName(), node.getWireName());
        if (exceptionalGndNodes.contains(key)) {
            return true;
        }
        if (exceptionalVccNodes.contains(key)) {
            return false;
        }

        for (Wire wire : node.getAllWiresInNode()) {
            TileTypeEnum tileType = wire.getTile().getTileTypeEnum();
            boolean found = gndWires.contains(new AbstractMap.SimpleEntry<TileTypeEnum, Integer>(tileType, wire.getWireIndex()));
            if (found) {
                return true;
            }
        }
        return false;
    }

    private static ArrayList<Long> getAllNodes(Device device) {
        ArrayList<Long> allNodes = new ArrayList<>();
        for (Tile tile : device.getAllTiles()) {
            for (int i=0; i < tile.getWireCount(); i++) {
                Node node = Node.getNode(tile, i);
                if (node == null)
                    continue;
                if (node.getTile() == tile && node.getWire() == i)
                    allNodes.add(makeKey(node.getTile(), node.getWire()));
            }
        }
        return allNodes;
    }

    private static ArrayList<Node> getAllTiedNodes(Device device) {
        ArrayList<Node> allNodes = new ArrayList<>();
        for (Tile tile : device.getAllTiles()) {
            for (int i=0; i < tile.getWireCount(); i++) {
                Node node = Node.getNode(tile, i);
                if (node == null || !node.isTied())
                    continue;
                if (node.getTile() == tile && node.getWire() == i)
                    allNodes.add(node);
            }
        }
        return allNodes;
    }


    public static void verifyConstants(StringEnumerator allStrings, Device device, Design design, Map<SiteTypeEnum,Site> siteTypes, Constants.Reader reader, Map<TileTypeEnum, TileType.Reader> tileTypes) {
        if (reader.getDefaultBestConstant() != ConstantType.VCC) {
            throw new RuntimeException("Expected that default best constant be VCC! Got " + reader.getDefaultBestConstant().name());
        }

        if (!reader.getGndNetName().isName()) {
            throw new RuntimeException("Expected GND net be specified.");
        }
        String gnd_net = allStrings.get(reader.getGndNetName().getName());
        if (!gnd_net.equals(Net.GND_NET)) {
            throw new RuntimeException("GND net should be " + Net.GND_NET + " but got " + gnd_net);
        }

        if (!reader.getVccNetName().isName()) {
            throw new RuntimeException("Expected VCC net be specified.");
        }
        String vcc_net = allStrings.get(reader.getVccNetName().getName());
        if (!vcc_net.equals(Net.VCC_NET)) {
            throw new RuntimeException("VCC net should be " + Net.VCC_NET + " but got " + vcc_net);
        }

        ConstantDefinitions constants = new ConstantDefinitions(allStrings, reader, tileTypes);

        ArrayList<Long> allNodes = getAllNodes(device);
        for (int i=0; i < allNodes.size(); i++) {
            long nodeKey = allNodes.get(i);
            Tile tile = device.getTile((int)(nodeKey >>> 32));
            Node node = new Node(tile, (int)(nodeKey & 0xffffffff));

            if (node.isTied() != constants.isNodeTied(node)) {
                throw new RuntimeException(String.format("Node %s tie(gold)=%s =! tie(test)=%s",
                        node.toString(), node.isTied(), constants.isNodeTied(node)));
            }

            if (node.isTied()) {
                if (node.isTiedToGnd() != constants.isNodeTiedGnd(node)) {
                    throw new RuntimeException(String.format("Tile %s node %s GND tie mismatch! %b != %b",
                                tile.getName(),
                                node.getWireName(),
                                node.isTiedToGnd(),
                                constants.isNodeTiedGnd(node)
                                ));
                }

                if (node.isTiedToVcc() != constants.isNodeTiedVcc(node)) {
                    throw new RuntimeException(String.format("Tile %s node %s GND tie mismatch! %b != %b",
                                tile.getName(),
                                node.getWireName(),
                                node.isTiedToVcc(),
                                constants.isNodeTiedVcc(node)
                                ));
                }
            }
        }

        for (Map.Entry<SiteTypeEnum,Site> e : siteTypes.entrySet()) {
            SiteTypeEnum siteType = e.getKey();
            Site site = e.getValue();
            SiteInst siteInst = design.createSiteInst("site_instance", siteType, site);
            for (int j=0; j < siteInst.getBELs().length; j++) {
                BEL bel = siteInst.getBELs()[j];
                if (bel.isGndSource()) {
                    if (!constants.isBelTiedGnd(bel)) {
                        throw new RuntimeException(String.format("Site %s site type %s BEL %s is not tied to GND as expected", site.getName(), siteType, bel.getName()));
                    }
                } else if (bel.isVccSource()) {
                    if (!constants.isBelTiedVcc(bel)) {
                        throw new RuntimeException(String.format("Site %s site type %s BEL %s is not tied to VCC as expected", site.getName(), siteType, bel.getName()));
                    }
                } else {
                    if (constants.isBelTied(bel)) {
                        throw new RuntimeException(String.format("Site %s site type %s BEL %s is tied, but isn't expected", site.getName(), siteType, bel.getName()));
                    }
                }
            }

            design.removeSiteInst(siteInst);
        }

        Map<Unisim, Map<String, NetType>> map = CellPinStaticDefaults.getCellPinDefaultsMap().get(device.getSeries());
        StructList.Reader<DefaultCellConnections.Reader> defaultsReader = reader.getDefaultCellConns();
        for (int i=0; i < defaultsReader.size(); i++) {
            DefaultCellConnections.Reader defaultReader = defaultsReader.get(i);
            String unisimName = allStrings.get(defaultReader.getCellType());
            Unisim u = Unisim.valueOf(unisimName);
            Map<String, NetType> pinMap = map.get(u);
            if (pinMap == null) {
                throw new RuntimeException("ERROR: " + u + " missing cell pin defaults");
            }
            StructList.Reader<DefaultCellConnection.Reader> pinDefaults = defaultReader.getPins();
            if (pinDefaults.size() != pinMap.size()) {
                throw new RuntimeException("ERROR: Mismatch cell pin defaults on " + u);
            }
            for (DefaultCellConnection.Reader r : pinDefaults) {
                String pinName = allStrings.get(r.getName());
                CellPinValue value = r.getValue();
                NetType goldValue = pinMap.get(pinName);
                if (value != getCellPinValue(goldValue)) {
                    throw new RuntimeException("ERROR: Mismatch default on " + u + ", pin "
                            + pinName + ". Expected " + goldValue + ", found " + value);
                }
            }
        }
    }

    private static long makeKey(Tile tile, int wire) {
        long key = wire;
        key = (((long)tile.getUniqueAddress()) << 32) | key;
        return key;
    }

    private static final int TIED_TO_GND = 0;
    private static final int TIED_TO_VCC = 1;
    private static final int UNTIED = 2;

    private static Map<TileTypeEnum, Map<Integer, int[]>> getTiedWires(Device device, List<Node> allTiedNodes) {
        Map<TileTypeEnum, Map<Integer,int[]>> tileTiedWires =
                new HashMap<TileTypeEnum, Map<Integer, int[]>>();
        // Count GND and VCC instances
        for (Node tiedNode : allTiedNodes) {
            TileTypeEnum type = tiedNode.getTile().getTileTypeEnum();
            int wireIdx = tiedNode.getWire();
            Map<Integer,int[]> wireMap = tileTiedWires.get(type);
            if (wireMap == null) {
                wireMap = new HashMap<Integer, int[]>();
                tileTiedWires.put(type, wireMap);
            }
            int[] tiedCounter = wireMap.get(wireIdx);
            if (tiedCounter == null) {
                tiedCounter = new int[3];
                wireMap.put(wireIdx, tiedCounter);
            }
            if (tiedNode.isTiedToGnd()) {
                tiedCounter[TIED_TO_GND]++;
            } else if (tiedNode.isTiedToVcc()) {
                tiedCounter[TIED_TO_VCC]++;
            } else {
                throw new RuntimeException("ERROR: This node was presumed tied to GND or VCC: " +
                        tiedNode);
            }
        }
        // For those tile type/wire pairs that have at least one tied instance, check for untied
        for (Tile tile : device.getAllTiles()) {
            TileTypeEnum type = tile.getTileTypeEnum();
            Map<Integer, int[]> wireMap = tileTiedWires.get(type);
            if (wireMap == null) continue;
            for (int wireIdx=0; wireIdx < tile.getWireCount(); wireIdx++) {
                int[] tiedCounter = wireMap.get(wireIdx);
                if (tiedCounter == null) continue;
                Node node = Node.getNode(tile,wireIdx);
                if (node == null) continue;
                if (!node.isTied()) {
                    tiedCounter[UNTIED]++;
                }
            }
        }

        return tileTiedWires;
    }

    public static void writeTiedWires(StringEnumerator allStrings, Device device,
            Constants.Builder builder, Map<TileTypeEnum, TileType.Builder> tileTypes) {

        ArrayList<Node> allTiedNodes = getAllTiedNodes(device);
        Map<TileTypeEnum, Map<Integer, int[]>> tileTiedWires = getTiedWires(device, allTiedNodes);

        // Find exceptionally tied nodes (inconsistent across tile type / wire)
        ArrayList<Node> tiedNodeExceptions = new ArrayList<Node>();
        Map<TileTypeEnum,Set<Integer>> vccTiedNodes = new HashMap<TileTypeEnum,Set<Integer>>();
        Map<TileTypeEnum,Set<Integer>> gndTiedNodes = new HashMap<TileTypeEnum,Set<Integer>>();
        for (Node tiedNode : allTiedNodes) {
            TileTypeEnum tileType = tiedNode.getTile().getTileTypeEnum();
            Map<Integer, int[]> wireMap = tileTiedWires.get(tileType);
            int[] tiedCounters = wireMap.get(tiedNode.getWire());
            if (tiedCounters[UNTIED] > 0) {
                tiedNodeExceptions.add(tiedNode);
                continue;
            }
            boolean tiedToGnd = tiedNode.isTiedToGnd();
            if (tiedCounters[TIED_TO_GND] > 0 && tiedCounters[TIED_TO_VCC] > 0) {
                // If the node is tied to both GND and VCC, make the less frequent the exceptional
                // case
                if (tiedToGnd == (tiedCounters[TIED_TO_GND] < tiedCounters[TIED_TO_VCC])) {
                    tiedNodeExceptions.add(tiedNode);
                    continue;
                }
            }
            if (tiedToGnd) {
                Set<Integer> gndWires = gndTiedNodes.get(tileType);
                if (gndWires == null) {
                    gndWires = new HashSet<>();
                    gndTiedNodes.put(tileType, gndWires);
                }
                gndWires.add(tiedNode.getWire());
            } else {
                Set<Integer> vccWires = vccTiedNodes.get(tileType);
                if (vccWires == null) {
                    vccWires = new HashSet<>();
                    vccTiedNodes.put(tileType, vccWires);
                }
                vccWires.add(tiedNode.getWire());
            }
        }

        int i = 0;
        StructList.Builder<NodeConstantSource.Builder> nodeSourcesObj =
                builder.initNodeSources(tiedNodeExceptions.size());
        for (Node tiedNodeException : tiedNodeExceptions) {
            NodeConstantSource.Builder nodeSourceObj = nodeSourcesObj.get(i);
            nodeSourceObj.setTile(allStrings.getIndex(tiedNodeException.getTile().getName()));
            nodeSourceObj.setWire(allStrings.getIndex(tiedNodeException.getWireName()));
            nodeSourceObj.setConstant(ConstantType.valueOf(tiedNodeException.isTiedToGnd() ? "GND" : "VCC"));
            i++;
        }

        for (Entry<TileTypeEnum,Map<Integer, int[]>> e : tileTiedWires.entrySet()) {
            TileType.Builder tileType = tileTypes.get(e.getKey());
            Set<Integer> gndWireIdxs = gndTiedNodes.get(e.getKey());
            Set<Integer> vccWireIdxs = vccTiedNodes.get(e.getKey());

            int staticSourceCount = 0;
            staticSourceCount += gndWireIdxs != null ? 1 : 0;
            staticSourceCount += vccWireIdxs != null ? 1 : 0;
            StructList.Builder<WireConstantSources.Builder> wireConstants = tileType.initConstants(staticSourceCount);

            int idx = 0;
            if (gndWireIdxs != null) {
                WireConstantSources.Builder gndWires = wireConstants.get(idx++);
                gndWires.setConstant(ConstantType.GND);
                PrimitiveList.Int.Builder wiresObj = gndWires.initWires(gndWireIdxs.size());
                int j=0;
                for (Integer wireIdx : gndWireIdxs) {
                    wiresObj.set(j, wireIdx);
                    j++;
                }
            }
            if (vccWireIdxs != null) {
                WireConstantSources.Builder vccWires = wireConstants.get(idx++);
                vccWires.setConstant(ConstantType.VCC);
                PrimitiveList.Int.Builder wiresObj = vccWires.initWires(vccWireIdxs.size());
                int j=0;
                for (Integer wireIdx : vccWireIdxs) {
                    wiresObj.set(j, wireIdx);
                    j++;
                }
            }
        }
    }

    private static void writeTiedBels(StringEnumerator allStrings, Device device, Constants.Builder builder, Design design, Map<SiteTypeEnum,Site> siteTypes) {
        Set<List<String>> siteVccSources = new HashSet<List<String>>();
        Set<List<String>> siteGndSources = new HashSet<List<String>>();

        for (Map.Entry<SiteTypeEnum,Site> e : siteTypes.entrySet()) {
            Site site = e.getValue();
            SiteInst siteInst = design.createSiteInst("site_instance", e.getKey(), site);
            for (int j=0; j < siteInst.getBELs().length; j++) {
                BEL bel = siteInst.getBELs()[j];
                if (bel.isGndSource() || bel.isVccSource()) {

                    List<String> source = new ArrayList<String>();

                    source.add(e.getKey().name());
                    source.add(bel.getName());

                    BELPin[] pins = bel.getPins();
                    if (pins.length != 1) {
                        throw new RuntimeException(String.format(
                                    "BEL %s has %d pins, which is not expected",
                                    bel.getName(), pins.length));
                    }
                    source.add(pins[0].getName());

                    if (bel.isGndSource()) {
                        siteGndSources.add(source);
                    } else if (bel.isVccSource()) {
                        siteVccSources.add(source);
                    } else {
                        throw new RuntimeException("Not reachable!");
                    }
                }
            }

            design.removeSiteInst(siteInst);
        }

        StructList.Builder<SiteConstantSource.Builder> sources = builder.initSiteSources(siteVccSources.size() + siteGndSources.size());

        int i = 0;
        for (List<String> siteVccSource : siteVccSources) {
            SiteConstantSource.Builder source = sources.get(i);
            i += 1;

            source.setSiteType(allStrings.getIndex(siteVccSource.get(0)));
            source.setBel(allStrings.getIndex(siteVccSource.get(1)));
            source.setBelPin(allStrings.getIndex(siteVccSource.get(2)));
            source.setConstant(ConstantType.VCC);
        }

        for (List<String> siteGndSource : siteGndSources) {
            SiteConstantSource.Builder source = sources.get(i);
            i += 1;

            source.setSiteType(allStrings.getIndex(siteGndSource.get(0)));
            source.setBel(allStrings.getIndex(siteGndSource.get(1)));
            source.setBelPin(allStrings.getIndex(siteGndSource.get(2)));
            source.setConstant(ConstantType.GND);
        }
    }

    public static CellPinValue getCellPinValue(NetType netType) {
        switch(netType) {
            case GND:
                return CellPinValue.GND;
            case VCC:
                return CellPinValue.VCC;
            default:
                return CellPinValue.FLOAT;
        }
    }

    private static void writeCellPinDefaults(StringEnumerator allStrings, Device device, Constants.Builder builder) {
        Map<Unisim,Map<String,NetType>> map = CellPinStaticDefaults.getCellPinDefaultsMap().get(device.getSeries());
        Builder<DefaultCellConnections.Builder> defaultCellConnsBuilder = builder.initDefaultCellConns(map.keySet().size());
        int i=0;
        for (Entry<Unisim,Map<String,NetType>> e : map.entrySet()) {
            DefaultCellConnections.Builder defaultConnBuilder = defaultCellConnsBuilder.get(i);
            defaultConnBuilder.setCellType(allStrings.getIndex(e.getKey().name()));
            Builder<DefaultCellConnection.Builder> pinsDefaultBuilder = defaultConnBuilder.initPins(e.getValue().size());
            int j = 0;
            for (Entry<String,NetType> e2 : e.getValue().entrySet()) {
                DefaultCellConnection.Builder pinDefault = pinsDefaultBuilder.get(j);
                pinDefault.setName(allStrings.getIndex(e2.getKey()));
                pinDefault.setValue(getCellPinValue(e2.getValue()));
                j++;
            }
            i++;
        }
    }

    public static void writeConstants(StringEnumerator allStrings, Device device, Constants.Builder builder, Design design, Map<SiteTypeEnum,Site> siteTypes, Map<TileTypeEnum, TileType.Builder> tileTypes) {
        builder.setDefaultBestConstant(ConstantType.VCC);

        builder.setGndCellType(allStrings.getIndex("GND"));
        builder.setGndCellPin(allStrings.getIndex("G"));

        builder.setVccCellType(allStrings.getIndex("VCC"));
        builder.setVccCellPin(allStrings.getIndex("P"));

        builder.getGndNetName().setName(allStrings.getIndex(Net.GND_NET));
        builder.getVccNetName().setName(allStrings.getIndex(Net.VCC_NET));

        writeTiedWires(allStrings, device, builder, tileTypes);
        writeTiedBels(allStrings, device, builder, design, siteTypes);
        writeCellPinDefaults(allStrings, device, builder);
    }
}

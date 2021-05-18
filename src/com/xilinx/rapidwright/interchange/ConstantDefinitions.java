package com.xilinx.rapidwright.interchange;

import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.capnproto.PrimitiveList;
import org.capnproto.StructList;

import com.xilinx.rapidwright.design.Design;
import com.xilinx.rapidwright.design.SiteInst;
import com.xilinx.rapidwright.design.Net;
import com.xilinx.rapidwright.device.BEL;
import com.xilinx.rapidwright.device.BELPin;
import com.xilinx.rapidwright.device.Device;
import com.xilinx.rapidwright.device.Node;
import com.xilinx.rapidwright.device.PIP;
import com.xilinx.rapidwright.device.Tile;
import com.xilinx.rapidwright.device.TileTypeEnum;
import com.xilinx.rapidwright.device.Site;
import com.xilinx.rapidwright.device.SiteTypeEnum;
import com.xilinx.rapidwright.device.Wire;
import com.xilinx.rapidwright.interchange.DeviceResources.Device.Constants;
import com.xilinx.rapidwright.interchange.DeviceResources.Device.ConstantType;
import com.xilinx.rapidwright.interchange.DeviceResources.Device.Constants.SiteConstantSource;
import com.xilinx.rapidwright.interchange.DeviceResources.Device.Constants.NodeConstantSource;
import com.xilinx.rapidwright.interchange.DeviceResources.Device.WireConstantSources;
import com.xilinx.rapidwright.interchange.DeviceResources.Device.TileType;
import com.xilinx.rapidwright.interchange.Enumerator;
import com.xilinx.rapidwright.interchange.LongEnumerator;

public class ConstantDefinitions {
    private Map<Map.Entry<SiteTypeEnum, String>, String> vccBels;
    private Map<Map.Entry<SiteTypeEnum, String>, String> gndBels;
    private Set<Map.Entry<TileTypeEnum, Integer>> vccWires;
    private Set<Map.Entry<TileTypeEnum, Integer>> gndWires;
    private Set<Map.Entry<String, String>> exceptionalVccNodes;
    private Set<Map.Entry<String, String>> exceptionalGndNodes;

    public ConstantDefinitions(Enumerator<String> allStrings, Constants.Reader reader, Map<TileTypeEnum, TileType.Reader> tileTypes) {
        vccBels = new HashMap<Map.Entry<SiteTypeEnum, String>, String>();
        gndBels = new HashMap<Map.Entry<SiteTypeEnum, String>, String>();
        vccWires = new HashSet<Map.Entry<TileTypeEnum, Integer>>();
        gndWires = new HashSet<Map.Entry<TileTypeEnum, Integer>>();
        exceptionalVccNodes = new HashSet<Map.Entry<String, String>>();
        exceptionalGndNodes = new HashSet<Map.Entry<String, String>>();

        for(SiteConstantSource.Reader source : reader.getSiteSources()) {
            String siteType = allStrings.get(source.getSiteType());
            SiteTypeEnum siteTypeEnum = SiteTypeEnum.valueOf(siteType);
            String bel = allStrings.get(source.getBel());
            String belPin = allStrings.get(source.getBelPin());

            ConstantType constant = source.getConstant();
            if(constant == ConstantType.VCC) {
                vccBels.put(new AbstractMap.SimpleEntry<SiteTypeEnum, String>(siteTypeEnum, bel), belPin);
            } else if(constant == ConstantType.GND) {
                gndBels.put(new AbstractMap.SimpleEntry<SiteTypeEnum, String>(siteTypeEnum, bel), belPin);
            } else {
                throw new RuntimeException("Unexpected value of constant " + constant.name());
            }
        }

        for(NodeConstantSource.Reader source : reader.getNodeSources()) {
            String tile = allStrings.get(source.getTile());
            String wire = allStrings.get(source.getWire());

            ConstantType constant = source.getConstant();
            if(constant == ConstantType.VCC) {
                exceptionalVccNodes.add(new AbstractMap.SimpleEntry<String, String>(tile, wire));
            } else if(constant == ConstantType.GND) {
                exceptionalGndNodes.add(new AbstractMap.SimpleEntry<String, String>(tile, wire));
            } else {
                throw new RuntimeException("Unexpected value of constant " + constant.name());
            }
        }

        for(Map.Entry<TileTypeEnum, TileType.Reader> entry : tileTypes.entrySet()) {
            TileTypeEnum tileTypeEnum = entry.getKey();
            TileType.Reader tileType = entry.getValue();

            for(WireConstantSources.Reader source : tileType.getConstants()) {
                ConstantType constant = source.getConstant();
                PrimitiveList.Int.Reader wires = source.getWires();
                for(int i = 0; i < wires.size(); ++i) {
                    Integer wireIndex = wires.get(i);
                    Map.Entry<TileTypeEnum, Integer> key = new AbstractMap.SimpleEntry<TileTypeEnum, Integer>(tileTypeEnum, wireIndex);
                    if(constant == ConstantType.VCC) {
                        vccWires.add(key);
                    } else if(constant == ConstantType.GND) {
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
        if(pin != null) {
            return bel.getPin(pin);
        }

        pin = gndBels.get(key);
        if(pin != null) {
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
        if(exceptionalVccNodes.contains(key)) {
            return true;
        }

        for(Wire wire : node.getAllWiresInNode()) {
            TileTypeEnum tileType = wire.getTile().getTileTypeEnum();
            boolean found = vccWires.contains(new AbstractMap.SimpleEntry<TileTypeEnum, Integer>(tileType, wire.getWireIndex()));
            if(found) {
                return true;
            }
        }
        return false;
    }

    public boolean isNodeTiedGnd(Node node) {
        Tile tile = node.getTile();
        Map.Entry<String, String> key = new AbstractMap.SimpleEntry<String, String>(tile.getName(), node.getWireName());
        if(exceptionalGndNodes.contains(key)) {
            return true;
        }

        for(Wire wire : node.getAllWiresInNode()) {
            TileTypeEnum tileType = wire.getTile().getTileTypeEnum();
            boolean found = gndWires.contains(new AbstractMap.SimpleEntry<TileTypeEnum, Integer>(tileType, wire.getWireIndex()));
            if(found) {
                return true;
            }
        }
        return false;
    }

    private static LongEnumerator getAllNodes(Device device) {
        LongEnumerator allNodes = new LongEnumerator();
        for(Tile tile : device.getAllTiles()) {
            for(int i=0; i < tile.getWireCount(); i++) {
                Wire wire = new Wire(tile, i);
                Node node = wire.getNode();
                if(node != null) {
                    allNodes.addObject(makeKey(node.getTile(), node.getWire()));
                }
            }
            for(PIP p : tile.getPIPs()) {
                Node start = p.getStartNode();
                if(start != null) {
                    allNodes.addObject(makeKey(start.getTile(), start.getWire()));
                }

                Node end = p.getEndNode();
                if(end != null) {
                    allNodes.addObject(makeKey(end.getTile(), end.getWire()));
                }
            }
        }

        return allNodes;
    }

    public static void verifyConstants(Enumerator<String> allStrings, Device device, Design design, Map<SiteTypeEnum,Site> siteTypes, Constants.Reader reader, Map<TileTypeEnum, TileType.Reader> tileTypes) {
        if(reader.getDefaultBestConstant() != ConstantType.VCC) {
            throw new RuntimeException("Expected that default best constant be VCC! Got " + reader.getDefaultBestConstant().name());
        }

        if(!reader.getGndNetName().isName()) {
            throw new RuntimeException("Expected GND net be specified.");
        }
        String gnd_net = allStrings.get(reader.getGndNetName().getName());
        if(!gnd_net.equals(Net.GND_NET)) {
            throw new RuntimeException("GND net should be " + Net.GND_NET + " but got " + gnd_net);
        }

        if(!reader.getVccNetName().isName()) {
            throw new RuntimeException("Expected VCC net be specified.");
        }
        String vcc_net = allStrings.get(reader.getVccNetName().getName());
        if(!vcc_net.equals(Net.VCC_NET)) {
            throw new RuntimeException("VCC net should be " + Net.VCC_NET + " but got " + vcc_net);
        }

        ConstantDefinitions constants = new ConstantDefinitions(allStrings, reader, tileTypes);

        LongEnumerator allNodes = getAllNodes(device);
        for(int i=0; i < allNodes.size(); i++) {
            long nodeKey = allNodes.get(i);
            Tile tile = device.getTile((int)(nodeKey >>> 32));
            Node node = new Node(tile, (int)(nodeKey & 0xffffffff));

            if(node.isTied() != constants.isNodeTied(node)) {
                throw new RuntimeException(String.format("Tile %s node %s tie mismatch!", tile.getName(), node.getWireName()));
            }

            if(node.isTied()) {
                if(node.isTiedToGnd() != constants.isNodeTiedGnd(node)) {
                    throw new RuntimeException(String.format("Tile %s node %s GND tie mismatch! %b != %b",
                                tile.getName(),
                                node.getWireName(),
                                node.isTiedToGnd(),
                                constants.isNodeTiedGnd(node)
                                ));
                }

                if(node.isTiedToVcc() != constants.isNodeTiedVcc(node)) {
                    throw new RuntimeException(String.format("Tile %s node %s GND tie mismatch! %b != %b",
                                tile.getName(),
                                node.getWireName(),
                                node.isTiedToVcc(),
                                constants.isNodeTiedVcc(node)
                                ));
                }
            }
        }

        for(Map.Entry<SiteTypeEnum,Site> e : siteTypes.entrySet()) {
            SiteTypeEnum siteType = e.getKey();
            Site site = e.getValue();
            SiteInst siteInst = design.createSiteInst("site_instance", siteType, site);
            for(int j=0; j < siteInst.getBELs().length; j++) {
                BEL bel = siteInst.getBELs()[j];
                if(bel.isGndSource()) {
                    if(!constants.isBelTiedGnd(bel)) {
                        throw new RuntimeException(String.format("Site %s site type %s BEL %s is not tied to GND as expected", site.getName(), siteType, bel.getName()));
                    }
                } else if(bel.isVccSource()) {
                    if(!constants.isBelTiedVcc(bel)) {
                        throw new RuntimeException(String.format("Site %s site type %s BEL %s is not tied to VCC as expected", site.getName(), siteType, bel.getName()));
                    }
                } else {
                    if(constants.isBelTied(bel)) {
                        throw new RuntimeException(String.format("Site %s site type %s BEL %s is tied, but isn't expected", site.getName(), siteType, bel.getName()));
                    }
                }
            }

            design.removeSiteInst(siteInst);
        }
    }

    private static long makeKey(Tile tile, int wire) {
        long key = wire;
        key = (((long)tile.getUniqueAddress()) << 32) | key;
        return key;
    }

    private static Map<TileTypeEnum, Set<Integer>> getTiedWires(Device device) {
        LongEnumerator allNodes = getAllNodes(device);

        Map<TileTypeEnum, Set<Integer>> tileUntiedWires = new HashMap<TileTypeEnum, Set<Integer>>();
        Map<TileTypeEnum, Set<Integer>> tileTiedWires = new HashMap<TileTypeEnum, Set<Integer>>();
        for(int i=0; i < allNodes.size(); i++) {
            long nodeKey = allNodes.get(i);
            Node node = new Node(device.getTile((int)(nodeKey >>> 32)), (int)(nodeKey & 0xffffffff));

            for(Wire wire : node.getAllWiresInNode()) {
                Tile tile = wire.getTile();
                TileTypeEnum tileType = tile.getTileTypeEnum();

                Set<Integer> wires;
                if(node.isTied()) {
                    wires = tileTiedWires.get(tileType);
                    if(wires == null) {
                        wires = new HashSet<Integer>();
                        tileTiedWires.put(tileType, wires);
                    }
                } else {
                    wires = tileUntiedWires.get(tileType);
                    if(wires == null) {
                        wires = new HashSet<Integer>();
                        tileUntiedWires.put(tileType, wires);
                    }
                }

                wires.add(wire.getWireIndex());
            }
        }

        for(TileTypeEnum tileType : tileTiedWires.keySet()) {
            Set<Integer> tiedWires = tileTiedWires.get(tileType);
            Set<Integer> untiedWires = tileUntiedWires.get(tileType);

            if(untiedWires == null) {
                untiedWires = new HashSet<Integer>();
            }

            tiedWires.removeAll(untiedWires);
        }

        return tileTiedWires;
    }

    public static void writeTiedWires(Enumerator<String> allStrings, Device device, Constants.Builder builder, Map<TileTypeEnum, TileType.Builder> tileTypes) {
        Map<TileTypeEnum, Set<Integer>> tileTiedWires = getTiedWires(device);

        Map<TileTypeEnum, Set<Integer>> tiedVccWires = new HashMap<TileTypeEnum, Set<Integer>>();
        Map<TileTypeEnum, Set<Integer>> tiedGndWires = new HashMap<TileTypeEnum, Set<Integer>>();

        for(Tile tile : device.getAllTiles()) {
            TileTypeEnum tileType = tile.getTileTypeEnum();
            if(!tileTiedWires.containsKey(tileType)) {
                continue;
            }

            if(!tiedVccWires.containsKey(tileType)) {
                tiedVccWires.put(tileType, new HashSet<Integer>());
                tiedGndWires.put(tileType, new HashSet<Integer>());
            }

            Set<Integer> vccWires = tiedVccWires.get(tileType);
            Set<Integer> gndWires = tiedGndWires.get(tileType);

            Set<Integer> wires = tileTiedWires.get(tileType);
            for(int wireIndex : wires) {
                Wire wire = new Wire(tile, wireIndex);
                Node node = wire.getNode();
                if(node == null) {
                    continue;
                }

                if(node.isTiedToGnd()) {
                    gndWires.add(wireIndex);
                } else if(node.isTiedToVcc()) {
                    vccWires.add(wireIndex);
                } else {
                    System.out.println("INFO: Node " + node + " is not tied as expected. Tile Type: " + tileType + ", wire: " + wire);
                }
            }
        }

        // Find tile type / wires that are inconstitently tied.
        Map<TileTypeEnum, Set<Integer>> exceptionalWires = new HashMap<TileTypeEnum, Set<Integer>>();

        for(TileTypeEnum tileType : tileTiedWires.keySet()) {
            Set<Integer> bothWires = new HashSet<Integer>();

            Set<Integer> vccWires = tiedVccWires.get(tileType);
            Set<Integer> gndWires = tiedGndWires.get(tileType);

            bothWires.addAll(vccWires);
            bothWires.retainAll(gndWires);

            if(bothWires.size() > 0) {
                if(!exceptionalWires.containsKey(tileType)) {
                    exceptionalWires.put(tileType, new HashSet<Integer>());
                }

                Set<Integer> wires = exceptionalWires.get(tileType);

                for(int wireIndex : bothWires) {
                    wires.add(wireIndex);
                    vccWires.remove(wireIndex);
                    gndWires.remove(wireIndex);
                }
            }
        }

        Set<List<String>> nodeSources = new HashSet<List<String>>();
        for(Tile tile : device.getAllTiles()) {
            TileTypeEnum tileType = tile.getTileTypeEnum();
            if(!tileTiedWires.containsKey(tileType)) {
                continue;
            }

            Set<Integer> wires = exceptionalWires.get(tileType);
            if(wires == null) {
                continue;
            }

            for(int wireIndex : wires) {
                Wire wire = new Wire(tile, wireIndex);
                Node node = wire.getNode();
                if(node == null) {
                    continue;
                }

                List<String> nodeSource = new ArrayList<String>();
                nodeSource.add(node.getTile().getName());
                nodeSource.add(node.getWireName());
                if(node.isTiedToGnd()) {
                    nodeSource.add(new String("GND"));
                } else if(node.isTiedToVcc()) {
                    nodeSource.add(new String("VCC"));
                } else {
                    throw new RuntimeException("Node should be tied?!");
                }

                nodeSources.add(nodeSource);
            }
        }

        int i = 0;
        StructList.Builder<NodeConstantSource.Builder> nodeSourcesObj = builder.initNodeSources(nodeSources.size());
        for(List<String> nodeSource : nodeSources) {
            NodeConstantSource.Builder nodeSourceObj = nodeSourcesObj.get(i);
            i += 1;

            String tile = nodeSource.get(0);
            String wire = nodeSource.get(1);
            ConstantType constant = ConstantType.valueOf(nodeSource.get(2));

            nodeSourceObj.setTile(allStrings.getIndex(tile));
            nodeSourceObj.setWire(allStrings.getIndex(wire));
            nodeSourceObj.setConstant(constant);
        }

        Set<TileTypeEnum> tileTypesInUse = new HashSet<TileTypeEnum>();
        tileTypesInUse.addAll(tiedVccWires.keySet());
        tileTypesInUse.addAll(tiedGndWires.keySet());

        for(TileTypeEnum tileTypeEnum : tileTypesInUse) {
            int count = 0;
            if(tiedVccWires.containsKey(tileTypeEnum)) {
                count += 1;
            }
            if(tiedGndWires.containsKey(tileTypeEnum)) {
                count += 1;
            }

            TileType.Builder tileType = tileTypes.get(tileTypeEnum);

            if(count == 0) {
                continue;
            }

            StructList.Builder<WireConstantSources.Builder> wireConstants = tileType.initConstants(count);
            int idx = 0;
            if(tiedVccWires.containsKey(tileTypeEnum)) {
                WireConstantSources.Builder vccWires = wireConstants.get(idx);
                idx += 1;

                Set<Integer> wireSet = tiedVccWires.get(tileTypeEnum);
                List<Integer> wires = new ArrayList<Integer>();
                wires.addAll(wireSet);

                vccWires.setConstant(ConstantType.VCC);
                PrimitiveList.Int.Builder wiresObj = vccWires.initWires(wires.size());
                for(int j = 0; j < wires.size(); ++j) {
                    Integer wireId = wires.get(j);
                    wiresObj.set(j, wireId);

                    String wireName = allStrings.get(tileType.getWires().get(wireId));
                }
            }

            if(tiedGndWires.containsKey(tileTypeEnum)) {
                WireConstantSources.Builder gndWires = wireConstants.get(idx);
                idx += 1;

                Set<Integer> wireSet = tiedGndWires.get(tileTypeEnum);
                List<Integer> wires = new ArrayList<Integer>();
                wires.addAll(wireSet);

                gndWires.setConstant(ConstantType.GND);
                PrimitiveList.Int.Builder wiresObj = gndWires.initWires(wires.size());
                for(int j = 0; j < wires.size(); ++j) {
                    Integer wireId = wires.get(j);
                    wiresObj.set(j, wireId);

                    String wireName = allStrings.get(tileType.getWires().get(wireId));
                }
            }
        }
    }

    private static void writeTiedBels(Enumerator<String> allStrings, Device device, Constants.Builder builder, Design design, Map<SiteTypeEnum,Site> siteTypes) {
        Set<List<String>> siteVccSources = new HashSet<List<String>>();
        Set<List<String>> siteGndSources = new HashSet<List<String>>();

        for(Map.Entry<SiteTypeEnum,Site> e : siteTypes.entrySet()) {
            Site site = e.getValue();
            SiteInst siteInst = design.createSiteInst("site_instance", e.getKey(), site);
            for(int j=0; j < siteInst.getBELs().length; j++) {
                BEL bel = siteInst.getBELs()[j];
                if(bel.isGndSource() || bel.isVccSource()) {

                    List<String> source = new ArrayList<String>();

                    source.add(e.getKey().name());
                    source.add(bel.getName());

                    BELPin[] pins = bel.getPins();
                    if(pins.length != 1) {
                        throw new RuntimeException(String.format(
                                    "BEL %s has %d pins, which is not expected",
                                    bel.getName(), pins.length));
                    }
                    source.add(pins[0].getName());

                    if(bel.isGndSource()) {
                        siteGndSources.add(source);
                    } else if(bel.isVccSource()) {
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
        for(List<String> siteVccSource : siteVccSources) {
            SiteConstantSource.Builder source = sources.get(i);
            i += 1;

            source.setSiteType(allStrings.getIndex(siteVccSource.get(0)));
            source.setBel(allStrings.getIndex(siteVccSource.get(1)));
            source.setBelPin(allStrings.getIndex(siteVccSource.get(2)));
            source.setConstant(ConstantType.VCC);
        }

        for(List<String> siteGndSource : siteGndSources) {
            SiteConstantSource.Builder source = sources.get(i);
            i += 1;

            source.setSiteType(allStrings.getIndex(siteGndSource.get(0)));
            source.setBel(allStrings.getIndex(siteGndSource.get(1)));
            source.setBelPin(allStrings.getIndex(siteGndSource.get(2)));
            source.setConstant(ConstantType.GND);
        }
    }

    public static void writeConstants(Enumerator<String> allStrings, Device device, Constants.Builder builder, Design design, Map<SiteTypeEnum,Site> siteTypes, Map<TileTypeEnum, TileType.Builder> tileTypes) {
        builder.setDefaultBestConstant(ConstantType.VCC);

        builder.setGndCellType(allStrings.getIndex("GND"));
        builder.setGndCellPin(allStrings.getIndex("G"));

        builder.setVccCellType(allStrings.getIndex("VCC"));
        builder.setVccCellPin(allStrings.getIndex("P"));

        builder.getGndNetName().setName(allStrings.getIndex(Net.GND_NET));
        builder.getVccNetName().setName(allStrings.getIndex(Net.VCC_NET));

        writeTiedWires(allStrings, device, builder, tileTypes);
        writeTiedBels(allStrings, device, builder, design, siteTypes);
    }
}

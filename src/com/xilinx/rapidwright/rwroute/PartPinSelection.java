/*
 * Copyright (c) 2022 Xilinx, Inc.
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
/**
 *
 */
package com.xilinx.rapidwright.rwroute;

import java.io.FileWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;

import com.xilinx.rapidwright.design.Cell;
import com.xilinx.rapidwright.design.ConstraintGroup;
import com.xilinx.rapidwright.design.Design;
import com.xilinx.rapidwright.design.DesignTools;
import com.xilinx.rapidwright.design.Net;
import com.xilinx.rapidwright.design.PartitionPin;
import com.xilinx.rapidwright.design.PinType;
import com.xilinx.rapidwright.design.SitePinInst;
import com.xilinx.rapidwright.design.blocks.PBlock;
import com.xilinx.rapidwright.design.blocks.PBlockRange;
import com.xilinx.rapidwright.device.Device;
import com.xilinx.rapidwright.device.Node;
import com.xilinx.rapidwright.device.PIP;
import com.xilinx.rapidwright.device.SiteTypeEnum;
import com.xilinx.rapidwright.device.Tile;
import com.xilinx.rapidwright.edif.EDIFHierPortInst;
import com.xilinx.rapidwright.edif.EDIFHierNet;
import com.xilinx.rapidwright.edif.EDIFPortInst;
import com.xilinx.rapidwright.edif.EDIFTools;
import com.xilinx.rapidwright.router.UltraScaleClockRouting;
import com.xilinx.rapidwright.edif.EDIFNetlist;


/**
 * Created on: Aug 22, 2022
 */
public class PartPinSelection {

    private static Design routeOverlay(Design design){

        Map<Net,List<SitePinInst>> netToUnroutedPins = new HashMap<>();
        Map<Net,Set<Node>> netToNodes = new HashMap<>();

        // Examine all partition pins to find the ones that are unrouted
        EDIFNetlist netlist = design.getNetlist();
        outer: for (PartitionPin ppin : design.getPartitionPins()) {
            if(ppin.getInstanceName() == null || ppin.getInstanceName().length() == 0) {
                // Part pin is on the top level cell
                throw new RuntimeException();
           } else {
                // Part pin is inside design hierarchy
                EDIFHierPortInst ehpi = netlist.getHierPortInstFromName(ppin.getInstanceName()
                        + EDIFTools.EDIF_HIER_SEP + ppin.getTerminalName());

                EDIFHierNet ehn = ehpi.getHierarchicalNet();
                EDIFHierNet parentEhn = netlist.getParentNet(ehn);
                Net net = design.getNet((parentEhn != null ? parentEhn : ehn).getHierarchicalNetName());
                if (net == null) {
                    throw new RuntimeException();
                }
                if (net.isClockNet()) {
                    // Ignore all part pins on clock nets
                    continue;
                }

                Node node = Node.getNode(ppin.getTile(), ppin.getWireIndex());
                if (ehpi.isInput()) {
                    Set<Node> nodes = netToNodes.computeIfAbsent(net, (n) -> new HashSet<>(RouterHelper.getNodesOfNet(n)));
                    for (Node uphill : node.getAllUphillNodes()) {
                        if (nodes.contains(uphill)) {
                            // If part pin is routed, then it must be a source part pin
                            continue outer;
                        }
                    }
                }

                SitePinInst sink = new SitePinInst();
                if (ehpi.isOutput()) {
                    sink.setPinType(PinType.IN);
                }

                sink.setNet(net);
                sink.setPinName(node.toString());
                netToUnroutedPins.computeIfAbsent(net, (k) -> new ArrayList<>())
                        .add(sink);
            }
        }

        // Examine all physical nets to find all unrouted sinks
        List<Net> clockNets = new ArrayList<>();

        for (Net net : design.getNets()) {
            List<SitePinInst> sinkPins = net.getSinkPins();
            List<SitePinInst> unroutedSinks;

            if (!net.hasPIPs()) {
                unroutedSinks = sinkPins;
            } else {
                unroutedSinks = findUnroutedSinks(net, sinkPins);
            }

            if (unroutedSinks.isEmpty()) {
                continue;
            }

            if (net.isClockNet()) {
                clockNets.add(net);
            }

            netToUnroutedPins.compute(net, (k,v) -> {
                if (v == null) {
                    return unroutedSinks;
                }
                v.addAll(unroutedSinks);
                return v;
            });
        }

        // Incrementally route the clock nets
        for (Net net : clockNets) {
            List<SitePinInst> pins = netToUnroutedPins.remove(net);
            UltraScaleClockRouting.incrementalClockRouter(net, pins);
        }

        // Incrementally route the static and regular nets
        PartialRouter.routeDesignPartialNonTimingDriven(design, netToUnroutedPins);

        return design;
    }

    private static List<SitePinInst> findUnroutedSinks(Net net, List<SitePinInst> sinks) {

        Set<Node> usedNodes = new HashSet<>();
        List<SitePinInst> unroutedSinks = new ArrayList<>();
        for(PIP p : net.getPIPs()) {
            usedNodes.add(p.getStartNode());
            usedNodes.add(p.getEndNode());
        }

        for(SitePinInst sink : sinks) {
            if(!usedNodes.contains(sink.getConnectedNode())) {
                unroutedSinks.add(sink);
            }else {
                sink.setRouted(true);
            }
        }
        return unroutedSinks;
    }

    // get all the site pins of the anchor registers to be routed to
    // we can easily get all the cell pins to be routed to, but we need the site pins as well
    private static Map<SitePinInst, EDIFHierPortInst> anchorRegPinsToRoute(Design design) {
        Set<EDIFHierPortInst> gndPins = new HashSet<>(design.getNetlist().getPhysicalGndPins());
        Set<EDIFHierPortInst> vccPins = new HashSet<>(design.getNetlist().getPhysicalVccPins());

        // for each target pin, we need to know its SitePinInst
        // we also need to know the port instance on the island that the anchor connects to
        // we will add the partition pins onto the island port instances
        Map<SitePinInst, EDIFHierPortInst> mapAnchorPinToIslandPort = new HashMap<>();

        for(Cell c : design.getCells()) {
            if(c.getName().startsWith("pfm_top_i/dynamic_region/gaussian_kernel/inst/") && c.getName().contains("_q_reg")) {

                // the logical pin name
                for(String pin : c.getPinMappingsP2L().values()) {
                	// skip clock net
                	if (!pin.equals("D") && !pin.equals("Q")) {
                		continue;
                	}

                    EDIFHierPortInst portInst = design.getNetlist().getHierPortInstFromName(c.getName() + EDIFTools.EDIF_HIER_SEP + pin);

                    // get the net that connects to the pin
                    String netName = design.getNetlist().getParentNet(portInst.getHierarchicalNet()).toString();
                    Net net = design.getNet(netName);

                    // if the pin is connected to physical nets
                    if(net == null) {
                        if(gndPins.contains(portInst)) net = design.getGndNet();
                        else if(vccPins.contains(portInst)) net = design.getVccNet();
                    }

                    // Gets the site pin that is currently routed to the specified cell pin.
                    String sitePinName = DesignTools.getRoutedSitePin(c, net, pin);
                    SitePinInst pinInst = c.getSiteInst().getSitePinInst(sitePinName);
                    if(pinInst == null) {
                        pinInst = net.createPin(sitePinName, c.getSiteInst());
                    }

                    // get the PortInst on the island wrapper
                    // first get the wrapper pin name
                    Collection<EDIFPortInst> portList = portInst.getNet().getPortInsts();

                    for (EDIFPortInst port: portList) {
                    	// check if the port belongs to a hierarchical cell
                    	// in theory there should be only 3 ports on the anchor net
                    	// the anchor Q/D port; the sink/src D/Q port; the hier port on the island
                    	if (!port.getCellInst().getCellType().isLeafCellOrBlackBox()) {
                    		String hierName = "pfm_top_i/dynamic_region/gaussian_kernel/inst/" + port.toString();
                    		EDIFHierPortInst islandPortInst = design.getNetlist().getHierPortInstFromName(hierName);
                    		mapAnchorPinToIslandPort.put(pinInst, islandPortInst);
                    	}
                    }
                }
            }
        }
        return mapAnchorPinToIslandPort;
    }

    private static Map<String, PBlock> getPBlocksFromXDC(Design design){
        Device device = design.getDevice();
        Map<String,PBlock> pblockMap = new HashMap<>();
        for(ConstraintGroup cg : ConstraintGroup.values()) {
            for(String line : design.getXDCConstraints(cg)) {
                if(line.trim().startsWith("resize_pblock")) {
                    String[] parts = line.split("\\s+");
                    String pblockName = null;
                    String pblockRange = null;
                    boolean nextIsName = false;
                    boolean nextIsRange = false;
                    for(String part : parts) {
                        if(part.contains("get_pblocks")) {
                            nextIsName = true;
                        }else if(nextIsName) {
                            nextIsName = false;
                            pblockName = part.replace("]", "").replace("}", "");
                        }else if(part.contains("-add")) {
                            nextIsRange = true;
                        }else if(nextIsRange) {
                            pblockRange = part.replace("{", "").replace("}", "").replace("]", "");
                            PBlock pblock = pblockMap.computeIfAbsent(pblockName, name -> new PBlock(name));
                            pblock.add(new PBlockRange(device, pblockRange));
                        }
                    }
                }

            }
        }
        return pblockMap;
    }


    private static String getPblockNameOfTile(HashMap<String, Set<Tile> > pblockNameToTiles, Tile t) {
        for (Map.Entry<String, Set<Tile> > e : pblockNameToTiles.entrySet()) {
            if (e.getValue().contains(t)) {
                return e.getKey();
            }
        }
        return null;

    }

    private static boolean isDirectionNode(Node n) {
    	switch (n.getIntentCode()) {
    	case NODE_SINGLE:
    	case NODE_DOUBLE:
    	case NODE_HQUAD:
    	case NODE_VQUAD:
    	case NODE_HLONG:
    	case NODE_VLONG:
    		String name = n.toString();
    		if (name.contains("/EE") || name.contains("/SS") || name.contains("/WW") || name.contains("/NN")) {
    			return true;
    		}
    		else {
    			return false;
    		}
    	default:
    		return false;
    	}
    }

    private static boolean isValidNodeForPartPin(
    		Node n,
    		String targetPblock,
    		HashMap<String, Set<Tile> > pblockNameToTiles,
    		Map<Node, Node> connections
    ) {
    	// we should select a node as a part pin if it satisfies the following rules:
    	// 1 it is a EE/WW/SS/NN node
    	// 2 it should be in the target pblock
    	// 3 its next node should be in the target pblock

    	// case 1: the node is a mux node, should not choose it as the part pin
    	if (!isDirectionNode(n)) {
    		return false;
    	}
    	// case 2: the node is not a mux node
    	else {
    		// case 2.1: the node is in the target pblock
    		if (targetPblock == getPblockNameOfTile(pblockNameToTiles, n.getTile())) {
    			Node nextNode = connections.get(n);
    			// case 2.1.1: the node is the last node
    			if (nextNode == null) {
    				return true;
    			}
    			// case 2.1.2: the node is not the last node
    			else {
    				// case 2.1.2.1: the next node is still in the target pblock
    				if (targetPblock == getPblockNameOfTile(pblockNameToTiles, nextNode.getTile())) {
    					return true;
    				}
    				// case 2.1.2.2: the next node is not in the target pblock
    				// this means some detour happens and the net routes outside the pblock in the middle
    				// we should not choose the current node as the part pin
    				else {
    					return false;
    				}
    			}
    		}
    		// case 2.2: the node is not in the target pblock
    		else {
    			return false;
    		}
    	}
    }

    private static HashMap<String, Set<Tile> > getPblockNameToTiles(Design design) {
        Map<String, PBlock> pblocks = getPBlocksFromXDC(design);
        HashMap<String, Set<Tile> > pblockNameToTiles = new HashMap<String, Set<Tile> >();
        for(Entry<String, PBlock> e : pblocks.entrySet()) {
        	String pblock_name = e.getKey();
            if(pblock_name.contains("WRAPPER_VERTEX_CR_X")) {
            	if (pblockNameToTiles.get(pblock_name) == null) {
            		pblockNameToTiles.put(pblock_name, new HashSet<Tile>());
            	}
            	pblockNameToTiles.get(pblock_name).addAll(e.getValue().getAllTiles());
            }
        }
        return pblockNameToTiles;
    }

    private static void selectPartpinFromRoute(
    		Design design,
            String outputTclName) throws IOException {
        HashMap<String, Set<Tile> > pblockNameToTiles = getPblockNameToTiles(design);
        Map<SitePinInst, EDIFHierPortInst> anchorRegPins = anchorRegPinsToRoute(design);

    	FileWriter myWriter = new FileWriter(outputTclName);
        for(Entry<SitePinInst, EDIFHierPortInst> e : anchorRegPins.entrySet()) {

            // get the pblock name of the src/sink of the anchor
            SitePinInst anchorPin = e.getKey();
            String anchorSrcOrSinkPblock;
            if (anchorPin.isOutPin()) {
              Tile sinkTile = anchorPin.getNet().getSinkPins().get(0).getTile();
              anchorSrcOrSinkPblock = getPblockNameOfTile(pblockNameToTiles, sinkTile);
            }
            else {
              Tile srcTile = anchorPin.getNet().getSourceTile();
              anchorSrcOrSinkPblock = getPblockNameOfTile(pblockNameToTiles, srcTile);
            }

            // get a linked list of nodes from the anchor to src/sink
            Net net = e.getKey().getNet();
            if(net.isStaticNet() || net.isClockNet()) continue;
            Node currNode = e.getKey().getConnectedNode();

            // need to consider bi-directional PIPs
            Map<Node, Node> connections = new HashMap<>();
            if(e.getKey().isOutPin()) {
                for(PIP pip : net.getPIPs()) {
                    if (pip.isBidirectional() && pip.isReversed()) {
                    	connections.put(pip.getEndNode(), pip.getStartNode());
                    }
                    else {
                        connections.put(pip.getStartNode(), pip.getEndNode());
                    }
                }
            }else {
                for(PIP pip : net.getPIPs()) {
                    if (pip.isBidirectional() && pip.isReversed()) {
                        connections.put(pip.getStartNode(), pip.getEndNode());
                    }
                    else {
                        connections.put(pip.getEndNode(), pip.getStartNode());
                    }
                }
            }

            // locate the first node that is in the same pblock as the src/sink of the anchor
            while(!isValidNodeForPartPin(currNode, anchorSrcOrSinkPblock, pblockNameToTiles, connections)) {
                currNode = connections.get(currNode);

                // we fail to filed a preferred NN/SS/EE/WW node, use the last node as the part pin
                if (currNode == null) {
                	String msg = " # WARNING: skip adding part pin to " + e.getValue().toString() + "\n";
                	System.out.print(msg);
                	myWriter.write(msg);

                	// // DEBUG
                	// Node currNodeDebug = e.getKey().getConnectedNode();
                  //   while(!isValidNodeForPartPin(currNodeDebug, anchorSrcOrSinkPblock, pblockNameToTiles, connections)) {
                  //   	currNodeDebug = connections.get(currNodeDebug);
                  //   }

                  //   if(e.getKey().isOutPin()) {
                  //       for(PIP pip : net.getPIPs()) {
                  //           if (pip.isBidirectional() && pip.isReversed()) {
                  //           	connections.put(pip.getEndNode(), pip.getStartNode());
                  //           }
                  //           else {
                  //               connections.put(pip.getStartNode(), pip.getEndNode());
                  //           }
                  //       }
                  //   }else {
                  //       for(PIP pip : net.getPIPs()) {
                  //           if (pip.isBidirectional() && pip.isReversed()) {
                  //               connections.put(pip.getStartNode(), pip.getEndNode());
                  //           }
                  //           else {
                  //               connections.put(pip.getEndNode(), pip.getStartNode());
                  //           }
                  //       }
                  //   }
                  //   //------------------

                	break;
                }
            }

            // add the partition pin to the island ports instead of anchor pins
            if (currNode != null) {
            	myWriter.write("reset_property HD.PARTPIN_LOCS " +
            			" [get_pins " + e.getValue().toString() +" ]\n");
            	myWriter.write("set_property HD.PARTPIN_LOCS " + currNode.toString() +
            			" [get_pins " + e.getValue().toString() +" ]\n");
            }
        }
        myWriter.close();
    }

    public static void main(String[] args) throws IOException {
        if(args.length != 2) {
            System.out.println("USAGE: <overlay_routed.dcp> update_partpin.tcl");
            return;
        }
        String routedDCP = args[0];
        String partPinTcl = args[1];

        Design routed_design = Design.readCheckpoint(routedDCP);
        selectPartpinFromRoute(routed_design, partPinTcl);
    }
}

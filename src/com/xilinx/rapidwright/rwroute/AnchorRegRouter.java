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

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;

import com.xilinx.rapidwright.design.Cell;
import com.xilinx.rapidwright.design.ConstraintGroup;
import com.xilinx.rapidwright.design.Design;
import com.xilinx.rapidwright.design.DesignTools;
import com.xilinx.rapidwright.design.Net;
import com.xilinx.rapidwright.design.SitePinInst;
import com.xilinx.rapidwright.design.blocks.PBlock;
import com.xilinx.rapidwright.design.blocks.PBlockRange;
import com.xilinx.rapidwright.device.Device;
import com.xilinx.rapidwright.device.Node;
import com.xilinx.rapidwright.device.PIP;
import com.xilinx.rapidwright.device.Tile;
import com.xilinx.rapidwright.edif.EDIFHierPortInst;
import com.xilinx.rapidwright.edif.EDIFTools;

/**
 * Created on: Aug 22, 2022
 */
public class AnchorRegRouter {

    private static Map<SitePinInst, EDIFHierPortInst> anchorRegPinsToRoute(Design design) {
        Set<EDIFHierPortInst> gndPins = new HashSet<>(design.getNetlist().getPhysicalGndPins());
        Set<EDIFHierPortInst> vccPins = new HashSet<>(design.getNetlist().getPhysicalVccPins());
        Map<SitePinInst, EDIFHierPortInst> pinsToRoute = new HashMap<>();
        for(Cell c : design.getCells()) {
            if(c.getName().startsWith("pfm_top_i/dynamic_region/gaussian_kernel/inst/") && c.getName().contains("_q_reg")) {
                for(String pin : c.getPinMappingsP2L().values()) {
                    EDIFHierPortInst portInst = design.getNetlist().getHierPortInstFromName(c.getName() + EDIFTools.EDIF_HIER_SEP + pin);
                    String netName = design.getNetlist().getParentNet(portInst.getHierarchicalNet()).toString();
                    Net net = design.getNet(netName);
                    if(net == null) {
                        if(gndPins.contains(portInst)) net = design.getGndNet();
                        else if(vccPins.contains(portInst)) net = design.getVccNet();
                    }
                    String sitePinName = DesignTools.getRoutedSitePin(c, net, pin);
                    SitePinInst pinInst = c.getSiteInst().getSitePinInst(sitePinName);
                    if(pinInst == null) {
                        pinInst = net.createPin(sitePinName, c.getSiteInst());
                    }
                    pinsToRoute.put(pinInst, portInst);
                }
            }
        }        
        return pinsToRoute;
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
    
    private static void createPartitionPins(Design design, Set<Tile> islandTiles, 
            Map<SitePinInst, EDIFHierPortInst> anchorRegPins) {
        for(Entry<SitePinInst, EDIFHierPortInst> e : anchorRegPins.entrySet()) {
            Net net = e.getKey().getNet();
            if(net.isStaticNet() || net.isClockNet()) continue;
            Node currNode = e.getKey().getConnectedNode();
            Map<Node, Node> connections = new HashMap<>();
            if(e.getKey().isOutPin()) {
                for(PIP pip : net.getPIPs()) {
                    connections.put(pip.getStartNode(), pip.getEndNode());
                }
            }else {
                for(PIP pip : net.getPIPs()) {
                    connections.put(pip.getEndNode(), pip.getStartNode());
                }
            }
            while(!islandTiles.contains(currNode.getTile())) {
                currNode = connections.get(currNode);
            }
            design.createPartitionPin(e.getValue(), currNode);
        }        
    }
    
    public static void main(String[] args) {
        if(args.length != 2) {
            System.out.println("USAGE: <overlay_placed.dcp> <overlay_routed.dcp>");
            return;
        }
        
        Design design = Design.readCheckpoint(args[0]);
        
        Map<String, PBlock> pblocks = getPBlocksFromXDC(design);
        Set<Tile> islandTiles = new HashSet<>();
        for(Entry<String, PBlock> e : pblocks.entrySet()) {
            if(e.getKey().contains("WRAPPER_VERTEX_CR_X")) {
                islandTiles.addAll(e.getValue().getAllTiles());
            }
        }

        Map<SitePinInst, EDIFHierPortInst> anchorRegPins = anchorRegPinsToRoute(design);
               
        RWRoute.routeDesignPartialNonTimingDriven(design);
        
        createPartitionPins(design, islandTiles, anchorRegPins);

        design.writeCheckpoint(args[1]);
    }
}

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

import java.util.ArrayList;
import java.util.List;

import com.xilinx.rapidwright.design.Design;
import com.xilinx.rapidwright.design.Net;
import com.xilinx.rapidwright.design.NetType;
import com.xilinx.rapidwright.device.Tile;
import com.xilinx.rapidwright.edif.EDIFHierPortInst;

/**
 * Customized {@link PartialRouter} for the RapidStream use case.
 */
public class RapidStreamRoute extends PartialRouter{
	public RapidStreamRoute(Design design, RWRouteConfig config) {
		super(design, config);
	}
	/**
	 * Classifies {@link Net} Objects into different categories: clocks, static nets,
	 * and regular signal nets (i.e. {@link NetType}.WIRE) and determines routing targets.
	 * Overrides to enable routing of conflict nets in the partial routing mode. 
	 */
	@Override
	protected void determineRoutingTargets(){
		categorizeNets();
		if(config.isResolveConflictNets()) handleConflictNets();
	}
	
	/**
	 * Deals with nets that are routed but with conflicting nodes. 
	 */
	private void handleConflictNets() {
		List<Net> toPreserveNets = new ArrayList<>();
		for(Net net : conflictNets) {
			if(!isTargetConflictNetToRoute(net)) {
				toPreserveNets.add(net);
				continue;
			}
			
			removeNetNodesFromPreservedNodes(net); // remove preserved nodes of a net from the map
			createsNetWrapperAndConnections(net, config.getBoundingBoxExtensionX(), config.getBoundingBoxExtensionY(), this.isMultiSLRDevice());
			net.unroute();//NOTE: no need to unroute if routing tree is reused, then toPreserveNets should be detected before createNetWrapperAndConnections
		}
		for(Net net : toPreserveNets) {
			preserveNet(net);
		}
	}
	
	/**
	 * Checks if a net is the target conflict net to be routed. 
	 * Note: this method provides an example of customizing the partial router for application-specific tool flows.
	 * It is specifically for the RapidStream use case, where the targets are nets connecting anchor FFs of CLB tiles.
	 * @param net The net in question.
	 * @return true if the net is a target net.
	 */
	protected boolean isTargetConflictNetToRoute(Net net) {
		// Skip successfully routed CLK, VCC, and GND nets
		// In the RapidStream flow, the target nets to route are 2-terminal FF-to-FF nets. So nets with more than one sink pin are skipped as well.
		if(net.getType() != NetType.WIRE || net.getSinkPins().size() > 1) return false;
		boolean anchorNet = false;
		List<EDIFHierPortInst> ehportInsts = design.getNetlist().getPhysicalPins(net.getName());
		boolean input = false;
		if(ehportInsts == null) { // e.g. encrypted DSP related nets
			return false;
		}
		for(EDIFHierPortInst eport : ehportInsts) {
			if(eport.getFullHierarchicalInstName().contains(config.getAnchorNameKeyword())) {
				//use the key word to identify target anchor nets
				anchorNet = true;
				if(eport.isInput()) input = true;
				break;
			}
		}
		Tile anchorTile = null;
		if(input) {
			anchorTile = net.getSinkPins().get(0).getTile();
		}else {
			anchorTile = net.getSource().getTile();
		}
		// Note: if laguna anchor nets are never conflicted, there will be no need to check tile names.
		return anchorNet && anchorTile.getName().startsWith("CLE");
	}
	
	/**
	 * Routes a design for the RapidStream flow.
	 * Note: Added to indicate the parameters for the use case.
	 * @param design The design instance to route.
	 * @return The routed design instance.
	 */
	public static Design routeDesignRapidStream(Design design) {
		RWRouteConfig config = new RWRouteConfig(new String[] {"--partialRouting", "--resolveConflictNets", "--useUTurnNodes", "--verbose"});
		return routeDesign(design, config, () -> new RapidStreamRoute(design, config));
	}
}

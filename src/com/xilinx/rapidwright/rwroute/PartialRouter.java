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
import com.xilinx.rapidwright.design.SitePinInst;
import com.xilinx.rapidwright.device.Node;
import com.xilinx.rapidwright.device.PIP;

/**
 * A class extends {@link RWRoute} for partial routing.
 */
public class PartialRouter extends RWRoute{
	public PartialRouter(Design design, RWRouteConfig config){
		super(design, config);
	}
	
	@Override
	protected void addGlobalClkRoutingTargets(Net clk) {
		if(!clk.hasPIPs()) {
			if(RouterHelper.isRoutableNetWithSourceSinks(clk)) {
				addClkNet(clk);
			}else {
				increaseNumNotNeedingRouting();
				System.err.println("ERROR: Incomplete clk net " + clk.getName());
			}
		}else {
			preserveNet(clk);
			increaseNumPreservedClks();
		}
	}
	
	@Override
	protected void addStaticNetRoutingTargets(Net staticNet){
		List<SitePinInst> sinks = new ArrayList<>();
		for(SitePinInst sink : staticNet.getPins()){
			if(sink.isOutPin()) continue;
			sinks.add(sink);
		}
		
		if(sinks.size() > 0 ) {
			if(!staticNet.hasPIPs()) {
				for(SitePinInst sink : sinks) {
					addReservedNode(sink.getConnectedNode(), staticNet);
				}
				addStaticNetRoutingTargets(staticNet, sinks);
			}else {
				preserveNet(staticNet);
				increaseNumPreservedStaticNets();
			}	
			
		}else {// internally routed (sinks.size = 0)
			preserveNet(staticNet);
			increaseNumNotNeedingRouting();
		}
	}
	
	@Override
	protected void addNetConnectionToRoutingTargets(Net net) {
		if (net.hasPIPs()) {
			preserveNet(net);
			increaseNumPreservedWireNets();
		}

		// If all pins are already routed, no routing necessary
		if (net.getSinkPins().stream().allMatch(SitePinInst::isRouted)) {
			return;
		}

		NetWrapper netWrapper = createsNetWrapperAndConnections(net, config.getBoundingBoxExtensionX(), config.getBoundingBoxExtensionY(), isMultiSLRDevice());

		// Create all nodes used by this net, adding itself as a user, and
		// set its previous node so that Routable.setChildren() will know
		// to only allow this edge
		for (PIP pip : net.getPIPs()) {
			Node start = (pip.isReversed()) ? pip.getEndNode() : pip.getStartNode();
			Node end = (pip.isReversed()) ? pip.getStartNode() : pip.getEndNode();
			Routable rstart = routingGraph.create(start, RoutableType.WIRE);
			Routable rend = routingGraph.create(end, RoutableType.WIRE);
			assert(rend.getPrev() == null);
			rend.setPrev(rstart);
			rend.incrementUser(netWrapper);
		}
	}
	
}

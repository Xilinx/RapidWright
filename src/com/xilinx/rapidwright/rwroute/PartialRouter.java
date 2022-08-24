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
import java.util.HashSet;
import java.util.List;
import java.util.Set;

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
				List<SitePinInst> unroutedSinks = findUnroutedSinks(staticNet, sinks);
				addStaticNetRoutingTargets(staticNet, unroutedSinks);
				preserveNet(staticNet);
				increaseNumPreservedStaticNets();
			}	
			
		}else {// internally routed (sinks.size = 0)
			preserveNet(staticNet);
			increaseNumNotNeedingRouting();
		}
	}
	
	private List<SitePinInst> findUnroutedSinks(Net net, List<SitePinInst> sinks) {
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
	
	@Override
	protected void addNetConnectionToRoutingTargets(Net net) {
		if(!net.hasPIPs()) {
			createsNetWrapperAndConnections(net, config.getBoundingBoxExtensionX(), config.getBoundingBoxExtensionY(), isMultiSLRDevice());
		}else{
			// In partial routing mode, a net with PIPs is preserved.
			// This means the routed net is supposed to be fully routed without conflicts.
			// TODO detect partially routed nets.
			preserveNet(net);
			increaseNumPreservedWireNets();
		}
	}
	
}

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

/**
 * A class extends {@link RWRoute} for partial routing.
 */
public class PartialRouter extends RWRoute{
	public PartialRouter(Design design, RWRouteConfig config){
		super(design, config);
	}

	@Override
	protected int getNumIndirectConnectionPins() {
		int totalSitePins = 0;
        for(Connection connection : indirectConnections) {
			totalSitePins += (connection.getSink().isRouted()) ? 0 : 1;
        }
        return totalSitePins;
	}

	@Override
	protected void addGlobalClkRoutingTargets(Net clk) {
		if(!clk.hasPIPs()) {
			super.addGlobalClkRoutingTargets(clk);
		}else {
			preserveNet(clk);
			increaseNumPreservedClks();
		}
	}
	
	@Override
	protected void addStaticNetRoutingTargets(Net staticNet){
		List<SitePinInst> sinks = staticNet.getSinkPins();
		if(sinks.size() > 0) {
			if(!staticNet.hasPIPs()) {
				List<Node> sinkNodes = new ArrayList<>(sinks.size());
				sinks.forEach((p) -> sinkNodes.add(p.getConnectedNode()));
				addPreservedNodes(sinkNodes, staticNet);
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

		createsNetWrapperAndConnections(net, config.getBoundingBoxExtensionX(), config.getBoundingBoxExtensionY(), isMultiSLRDevice());
	}

}

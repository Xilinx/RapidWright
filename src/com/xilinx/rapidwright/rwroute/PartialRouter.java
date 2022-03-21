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
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import com.xilinx.rapidwright.design.Design;
import com.xilinx.rapidwright.design.Net;
import com.xilinx.rapidwright.design.NetType;
import com.xilinx.rapidwright.design.SitePinInst;
import com.xilinx.rapidwright.device.Node;
import com.xilinx.rapidwright.device.PIP;
import com.xilinx.rapidwright.device.Site;
import com.xilinx.rapidwright.device.SitePin;

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

		createsNetWrapperAndConnections(net, config.getBoundingBoxExtensionX(), config.getBoundingBoxExtensionY(), isMultiSLRDevice());
	}

	@Override
	protected void determineRoutingTargets(){
		super.determineRoutingTargets();

		for (Map.Entry<Net,NetWrapper> e : nets.entrySet()) {
			Net net = e.getKey();
			NetWrapper netWrapper = e.getValue();

			// Create all nodes used by this net and set its previous pointer so that:
			// (a) the routing for each connection can be recovered by
			//      finishRouteConnection()
			// (b) Routable.setChildren() will know to only allow this incoming
			//     arc on these nodes
			for (PIP pip : net.getPIPs()) {
				Node start = (pip.isReversed()) ? pip.getEndNode() : pip.getStartNode();
				Node end = (pip.isReversed()) ? pip.getStartNode() : pip.getEndNode();
				Routable rstart = createAddRoutableNode(null, start, RoutableType.WIRE);
				Routable rend = createAddRoutableNode(null, end, RoutableType.WIRE);
				assert (rend.getPrev() == null);
				rend.setPrev(rstart);
			}

			for (Connection connection : netWrapper.getConnections()) {
				finishRouteConnection(connection);

				SitePinInst sink = connection.getSink();
				String sinkPinName = sink.getName();
				if (!Pattern.matches("[A-H](X|_I)", sinkPinName))
					continue;

				Routable rnode = connection.getSinkRnode();
				String lut = sinkPinName.substring(0, 1);
				Site site = sink.getSite();
				for (int i = 6; i >= 1; i--) {
					Node altNode = site.getConnectedNode(lut + i);

					// Skip if LUT pin is already being preserved
					Net preservedNet = routingGraph.getPreservedNet(altNode);
					if (preservedNet != null) {
						continue;
					}

					// RoutableType type = RoutableType.WIRE;
					RoutableType type = RoutableType.PINFEED_I;
					Routable altRnode = createAddRoutableNode(null, altNode, type);
					// Trigger a setChildren() for LUT routethrus
					altRnode.getChildren();
					// Create a fake edge from [A-H][1-6] to [A-H](I|_X)
					altRnode.addChild(rnode);
				}
			}
		}
	}

}

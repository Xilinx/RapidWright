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

/**
 * A class extends {@link RWRoute} for partial routing.
 */
public class PartialRouter extends RWRoute{
	public PartialRouter(Design design, Configuration config){
		super(design, config);
	}
	
	@Override
	protected void addGlobalClkRoutingTargets(Net clk) {
		if(!clk.hasPIPs()) {
			if(RouterHelper.isRoutableNetWithSourceSinks(clk)) {
				this.addClkNet(clk);
			}else {
				this.increaseNumNotNeedingRouting();
				System.err.println("ERROR: Incomplete clk net " + clk.getName());
			}
		}else {
			this.preserveNet(clk);
			this.increaseNumPreservedClks();
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
					this.addReservedNode(sink.getConnectedNode(), staticNet);
				}
				this.addStaticNetRoutingTargets(staticNet, sinks);
			}else {
				this.preserveNet(staticNet);
				this.increaseNumPreservedStaticNets();
			}	
			
		}else {// internally routed (sinks.size = 0)
			this.preserveNet(staticNet);
			this.increaseNumNotNeedingRouting();
		}
	}
	
	@Override
	protected void addNetConnectionToRoutingTargets(Net net, boolean multiSLR) {
		if(!net.hasPIPs()) {
			this.createsNetWrapperAndConnections(net, this.config.getBoundingBoxExtensionX(), this.config.getBoundingBoxExtensionY(),multiSLR);
		}else{
			// In partial routing mode, a net with PIPs is preserved.
			// This means the routed net is supposed to be fully routed without conflicts.
			// TODO detect partially routed nets and nets with possible conflicting nodes.
			this.preserveNet(net);
			this.increaseNumPreservedWireNets();
		}
	}	
}
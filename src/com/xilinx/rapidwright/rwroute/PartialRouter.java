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
import java.util.Collection;
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

		NetWrapper netWrapper = createsNetWrapperAndConnections(net, config.getBoundingBoxExtensionX(), config.getBoundingBoxExtensionY(), isMultiSLRDevice());
		netWrapper.setPartiallyPreserved(true);
	}

	/**
	 * Return preserved nets that are using resources immediately downhill of the source and
	 * immediately uphill of the sink of the connection.
	 * @param connection The connection in question.
	 * @return Collection of nets.
	 */
	protected Collection<Net> pickNetsToUnpreserve(Connection connection) {
		Set<Net> unpreserveNets = new HashSet<>();

		// Find those reserved signals that are using uphill nodes of the target pin node
		for(Node node : connection.getSinkRnode().getNode().getAllUphillNodes()) {
			Net toRoute = routingGraph.getPreservedNet(node);
			if(toRoute == null) continue;
			if(toRoute.isClockNet() || toRoute.isStaticNet()) continue;
			unpreserveNets.add(toRoute);
		}

		// Find those preserved nets that are using downhill nodes of the source pin node
		for(Node node : connection.getSourceRnode().getNode().getAllDownhillNodes()) {
			Net toRoute = routingGraph.getPreservedNet(node);
			if(toRoute == null) continue;
			if(toRoute.isClockNet() || toRoute.isStaticNet()) continue;
			unpreserveNets.add(toRoute);
		}

		unpreserveNets.removeIf((net) -> {
			NetWrapper netWrapper = nets.get(net);
			if (netWrapper == null)
				return false;
			if (netWrapper.getPartiallyPreserved())
				return false;
			// Net already seen and is fully unpreserved
			return true;
		});

		return unpreserveNets;
	}

	/**
	 * Unpreserves nets to release routing resource to resolve congestion that blocks the
	 * routablity of a connection.
	 * The {@link #pickNetsToUnpreserve} method is called to get which nets are to be
	 * unpreserved and its resources released for consideration by others.
	 * @param connection The connection in question.
	 * @return The number of unrouted nets.
	 */
	protected int unpreserveNetsAndReleaseResources(Connection connection) {
		Collection<Net> unpreserveNets = pickNetsToUnpreserve(connection);
		if (unpreserveNets.isEmpty()) {
			return 0;
		}

		System.out.println("INFO: Unpreserving " + unpreserveNets.size() + " nets due to unroutable connection");
		for (Net net : unpreserveNets) {
			System.out.println("\t" + net);
			unpreserveNet(net);
		}

		return unpreserveNets.size();
	}

	protected void unpreserveNet(Net net) {
		Set<Routable> rnodes = new HashSet<>();
		NetWrapper netWrapper = nets.get(net);
		if (netWrapper != null) {
			// Net already exists

			assert(netWrapper.getPartiallyPreserved());
			netWrapper.setPartiallyPreserved(false);

			for(Node toBuild : RouterHelper.getNodesOfNet(net)) {
				// Since net already exists, all the nodes it uses will already
				// have been created
				Routable rnode = routingGraph.getNode(toBuild);
				assert(rnode != null);

				rnodes.add(rnode);
			}
		} else {
			// Net needs to be created
			netWrapper = createsNetWrapperAndConnections(net, config.getBoundingBoxExtensionX(), config.getBoundingBoxExtensionY(), multiSLRDevice);

			// Collect all nodes used by this net
			for (PIP pip : net.getPIPs()) {
				Node start = (pip.isReversed()) ? pip.getEndNode() : pip.getStartNode();
				Node end = (pip.isReversed()) ? pip.getStartNode() : pip.getEndNode();
				Routable rstart = createAddRoutableNode(null, start, RoutableType.WIRE);
				Routable rend = createAddRoutableNode(null, end, RoutableType.WIRE);

				rnodes.add(rstart);
				rnodes.add(rend);

				// Also set the prev pointer according to the PIP
				rend.setPrev(rstart);
			}

			// Use the prev pointers to update the routing for each connection
			for (Connection netnewConnection : netWrapper.getConnections()) {
				finishRouteConnection(netnewConnection);
				assert(netnewConnection.getSink().isRouted());
			}

			if(config.isTimingDriven()) {
				timingManager.getTimingGraph().addNetDelayEdges(net);
				timingManager.setTimingEdgesOfConnections(netWrapper.getConnections());
				for (Connection netnewConnection : netWrapper.getConnections()) {
					netnewConnection.updateRouteDelay();
				}
			}
		}

		// TODO: Re-enable this to be consistent with preserveNet()
		// Set<Node> pinNodes = new HashSet<>();
		// for (SitePinInst pin : n.getPins()) {
		// 	pinNodes.add(pin.getConnectedNode());
		// }

		for (Routable rnode : rnodes) {
			Node toBuild = rnode.getNode();
			/*if (!pinNodes.contains(toBuild))*/ {
				routingGraph.unpreserve(toBuild);
			}
			// Each rnode should be added a child to any of its parents
			// that already exist, unless it was already present
			uphill: for(Node uphill : toBuild.getAllUphillNodes()) {
				// Without this routethru check, there will be Invalid Programming for Site error shown in Vivado.
				// Do not use those nodes, because we do not know if the routethru is available or not
				if(routethruHelper.isRouteThru(uphill, toBuild)) continue;
				Routable parent = routingGraph.getNode(uphill);
				if (parent == null)
					continue;
				for (Routable child : parent.getChildren()) {
					if (child == rnode)
						continue uphill;
				}
				parent.addChild(rnode);
			}

			// Clear the prev pointer (as it is also used to track
			// whether a node has been visited during expansion)
			rnode.setPrev(null);
		}
	}

	@Override
	protected boolean handleUnroutableConnection(Connection connection) {
		boolean hasAltOutput = super.handleUnroutableConnection(connection);
		if (hasAltOutput)
			return true;
		if (config.isSoftPreserve()) {
			if (routeIteration == 2) {
				unpreserveNetsAndReleaseResources(connection);
				return true;
			}
		}
		return false;
	}
}

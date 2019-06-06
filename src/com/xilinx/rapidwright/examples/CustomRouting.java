package com.xilinx.rapidwright.examples;

import java.util.List;

import com.xilinx.rapidwright.design.Cell;
import com.xilinx.rapidwright.design.Design;
import com.xilinx.rapidwright.design.DesignTools;
import com.xilinx.rapidwright.design.Net;
import com.xilinx.rapidwright.design.Unisim;
import com.xilinx.rapidwright.device.Device;
import com.xilinx.rapidwright.device.PIP;
import com.xilinx.rapidwright.router.RouteNode;
import com.xilinx.rapidwright.router.Router;

public class CustomRouting {

	
	public static void main(String[] args) {
		// Let's create a new design with just two LUTs, a source and sink...
		Design design = new Design("customRoute",Device.AWS_F1);
		Cell src = design.createAndPlaceCell("src", Unisim.AND2, "SLICE_X100Y100/A6LUT");
		Cell snk = design.createAndPlaceCell("snk", Unisim.AND2, "SLICE_X110Y110/A6LUT");
		
		// Now we can connect them
		Net customRoutedNet = design.createNet("src");
		customRoutedNet.connect(src, "O");
		customRoutedNet.connect(snk, "I0");
		
		// Route the LUTs' sites
		design.routeSites();
		
		// Run the router to get a typical route solution to this net and write
		// it out for a comparison/baseline
		Router router = new Router(design);
		router.routeDesign();		
		design.writeCheckpoint("typicalRoute.dcp");

		// Routes are a series of Node objects connected by PIP objects (think 
		// Node and Arc in a graph) --- We can get RouteNode objects from the 
		// source and sink of the net to use as start and end point for our 
		// desired custom route
		RouteNode srcNode = customRoutedNet.getSource().getRouteNode();		
		RouteNode snkNode = customRoutedNet.getSinkPins().get(0).getRouteNode();

		// Now, without specifying PIPs directly, we can force a route 
		// to go through a certain node to achieve a longer route if we wish..
		RouteNode detour = new RouteNode("INT_X65Y145/NN2_E_BEG1", design.getDevice());
		
		// Tell the tools to find a path from our source to a detour node...
		List<PIP> pathToDetour = DesignTools.findRoutingPath(srcNode, detour);
		// ...then from the detour node to our sink
		List<PIP> pathFromDetourToSink = DesignTools.findRoutingPath(detour, snkNode);
		
		// Now we can replace the net's routing with that of our custom route
		customRoutedNet.unroute();
		customRoutedNet.getPIPs().addAll(pathToDetour);
		customRoutedNet.getPIPs().addAll(pathFromDetourToSink);
		
		// We can lock or 'fix' the routing so that Vivado can't change it if we wish
		customRoutedNet.lockRouting();
		
		design.writeCheckpoint("customRoute.dcp");
	}
}

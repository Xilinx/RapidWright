/* 
 * Copyright (c) 2021 Xilinx, Inc. 
 * All rights reserved.
 *
 * Author: Eddie Hung, Xilinx Research Labs.
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

import com.xilinx.rapidwright.design.Design;
import com.xilinx.rapidwright.design.DesignTools;
import com.xilinx.rapidwright.design.Net;
import com.xilinx.rapidwright.design.SiteInst;
import com.xilinx.rapidwright.design.SitePinInst;
import com.xilinx.rapidwright.device.Device;
import com.xilinx.rapidwright.support.LargeTest;
import com.xilinx.rapidwright.support.RapidWrightDCP;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.ArrayList;
import java.util.List;

@LargeTest
public class TestRWRoute {
	/**
	 * Tests the non-timing driven full routing, i.e., RWRoute running in its wirelength-driven mode.
	 * The bnn design from Rosetta benchmarks is used.
	 * It is a small heterogeneous design with CLBs, DSPs and BRAMs.
	 * The bnn design does not have any clock nets. 
	 * This test takes around 15s on a machine with a CPU @ 2.5GHz.
	 */
	@Test
	public void testNonTimingDrivenFullRouting() {
		String dcpPath = RapidWrightDCP.getString("bnn.dcp");
		Design design = Design.readCheckpoint(dcpPath);
		RWRoute.routeDesignFullNonTimingDriven(design);
	}
	
	/**
	 * Tests the timing driven full routing, i.e., RWRoute running in timing-driven mode.
	 * The bnn design from Rosetta benchmarks is used.
	 * It is a small heterogeneous design with CLBs, DSPs and BRAMs.
	 * The bnn design does not have any clock nets. 
	 * In this test, the default {@link RWRouteConfig} options are used. We do not provide DSP logic delays 
	 * for the timing-driven routing to test the fallback when DSP timing data is missing. 
	 * This test takes around 20s on a machine with a CPU @ 2.5GHz.
	 */
	@Test
	public void testTimingDrivenFullRouting() {
		String dcpPath = RapidWrightDCP.getString("bnn.dcp");
		Design design = Design.readCheckpoint(dcpPath);
		RWRoute.routeDesignFullTimingDriven(design);
	}
	
	/**
	 * Tests the non-timing driven full routing with a design that has a global clock net.
	 * The optical-flow design from Rosetta benchmarks is used.
	 * It is the largest heterogeneous design from the Rosetta benchmark set.
	 * It has a global clock net, fitting in this test purpose of routing with a clock net.
	 * This test takes around 3 minutes on a machine with a CPU @ 2.5GHz.
	 */
	@Test
	public void testNonTimingDrivenFullRoutingWithClkDesign() {
		String dcpPath = RapidWrightDCP.getString("optical-flow.dcp");
		Design design = Design.readCheckpoint(dcpPath);
		RWRoute.routeDesignFullNonTimingDriven(design);
	}
	
	/**
	 * Tests the non-timing driven partial routing, i.e., RWRoute running in its wirelength-driven partial routing mode.
	 * The picoblaze design is from one of the RapidWright tutorials with nets between computing kernels not routed.
	 * Other nets within each kernel are fully routed.
	 * This test takes around 40s on a machine with a CPU @ 2.5GHz.
	 */
	@Test
	public void testNonTimingDrivenPartialRouting() {
		String dcpPath = RapidWrightDCP.getString("picoblaze_partial.dcp");
		Design design = Design.readCheckpoint(dcpPath);
		DesignTools.createMissingSitePinInsts(design);
		DesignTools.createPossiblePinsToStaticNets(design);

		List<SitePinInst> pinsToRoute = new ArrayList<>();
		for (Net net : design.getNets()) {
			if (!net.hasPIPs()) {
				pinsToRoute.addAll(net.getSinkPins());
			}
		}
		PartialRouter.routeDesignPartialNonTimingDriven(design, pinsToRoute);
	}

	/**
	 * Tests timing driven partial routing.
	 * The picoblaze design is from one of the RapidWright tutorials with nets between computing kernels not routed.
	 * Other nets within each kernel are fully routed.
	 */
	@Test
	@Disabled("Blocked on TimingGraph.build() being able to build partial graphs")
	public void testTimingDrivenPartialRouting() {
		String dcpPath = RapidWrightDCP.getString("picoblaze_partial.dcp");
		Design design = Design.readCheckpoint(dcpPath);
		DesignTools.createMissingSitePinInsts(design);
		DesignTools.createPossiblePinsToStaticNets(design);

		List<SitePinInst> pinsToRoute = new ArrayList<>();
		for (Net net : design.getNets()) {
			if (!net.hasPIPs()) {
				pinsToRoute.addAll(net.getSinkPins());
			}
		}
		PartialRouter.routeDesignPartialTimingDriven(design, pinsToRoute);
	}

	@ParameterizedTest
	@CsvSource({
			// One SLR crossing
			"SLICE_X9Y299,SLICE_X9Y300,10000",	// Close
			"SLICE_X9Y240,SLICE_X9Y300,500",	// Perfect
			"SLICE_X9Y359,SLICE_X9Y299,500",	// Perfect
			"SLICE_X9Y240,SLICE_X9Y359,2000",	// Far
			"SLICE_X9Y359,SLICE_X9Y240,2000",	// Far (opposite direction as above requires noticeably more nodes?)

			// Two SLR crossings
			"SLICE_X0Y240,SLICE_X0Y430,20000",
			"SLICE_X0Y299,SLICE_X0Y599,10000",
			"SLICE_X0Y599,SLICE_X0Y299,30000",	// (opposite direction as above requires noticeably more nodes?)

			// Three SLR crossings
			"SLICE_X0Y0,SLICE_X168Y899,15000",
			"SLICE_X168Y0,SLICE_X0Y899,15000",
	})
	public void testSLRCrossingNonTimingDriven(String srcSiteName, String dstSiteName, long nodesPoppedLimit) {
		Design design = new Design("top", Device.AWS_F1);

		Net net = design.createNet("net");
		SiteInst srcSi = design.createSiteInst(srcSiteName);
		net.createPin("AQ", srcSi);

		SiteInst dstSi = design.createSiteInst(dstSiteName);
		SitePinInst dstSpi = net.createPin("A1", dstSi);

		List<SitePinInst> pinsToRoute = new ArrayList<>();
		pinsToRoute.add(dstSpi);
		PartialRouter.routeDesignPartialNonTimingDriven(design, pinsToRoute);

		Assertions.assertTrue(Long.valueOf(System.getProperty("rapidwright.rwroute.nodesPopped")) < nodesPoppedLimit);
	}
}

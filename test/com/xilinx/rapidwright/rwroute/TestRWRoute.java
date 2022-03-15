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

import com.xilinx.rapidwright.support.RapidWrightDCP;
import org.junit.jupiter.api.Test;

import com.xilinx.rapidwright.design.Design;

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
		RWRoute.routeDesignPartialNonTimingDriven(design);
	}
}

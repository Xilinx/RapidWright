package com.xilinx.rapidwright.rwroute;

import org.junit.jupiter.api.Disabled;
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
		Design design = Design.readCheckpoint("RapidWrightDCP/bnn.dcp");
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
	@Disabled("Disabled because of the data type error reported in DelayModelSourceFromText: line #109")
	public void testTimingDrivenFullRouting() {
		Design design = Design.readCheckpoint("RapidWrightDCP/bnn.dcp");
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
		Design design = Design.readCheckpoint("RapidWrightDCP/optical-flow.dcp");
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
		Design design = Design.readCheckpoint("RapidWrightDCP/picoblaze_partial.dcp");
		RWRoute.routeDesignPartialNonTimingDriven(design);
	}
}

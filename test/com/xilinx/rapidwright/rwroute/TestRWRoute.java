package com.xilinx.rapidwright.rwroute;

import org.junit.jupiter.api.Test;

import com.xilinx.rapidwright.design.Design;

public class TestRWRoute {
	@Test
	public void testNonTimingDrivenFullRouting() {
		Design design = Design.readCheckpoint("RapidWrightDCP/bnn.dcp");
		RWRoute.routeDesignFullNonTimingDriven(design);
	}
	
	@Test
	@Disabled("Disabled because ...")
	public void testTimingDrivenFullRouting() {
		Design design = Design.readCheckpoint("RapidWrightDCP/bnn.dcp");
		RWRoute.routeDesignFullTimingDriven(design);
	}
	
	@Test
	public void testNonTimingDrivenFullRoutingWithClk() {
		Design design = Design.readCheckpoint("RapidWrightDCP/optical-flow.dcp");
		RWRoute.routeDesignFullNonTimingDriven(design);
	}
	
	@Test
	public void testTimingDrivenFullRoutingWithClk() {
//		Design design = Design.readCheckpoint("RapidWrightDCP/optical-flow.dcp");
//		RWRoute.routeDesignWithUserDefinedArguments(design, new String[] {"--clkRouteTiming", "RapidWrightDCP/clkRouteTemplate_BUFGCE_X0Y58.txt", "--verbose"});
	}
	
	@Test
	public void testNonTimingDrivenPartialRouting() {
		Design design = Design.readCheckpoint("RapidWrightDCP/picoblaze_partial.dcp");
		RWRoute.routeDesignPartialNonTimingDriven(design);
	}
}

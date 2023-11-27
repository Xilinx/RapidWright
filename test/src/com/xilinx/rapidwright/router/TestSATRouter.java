package com.xilinx.rapidwright.router;

import com.xilinx.rapidwright.design.Design;
import com.xilinx.rapidwright.design.Net;
import com.xilinx.rapidwright.design.blocks.PBlock;
import com.xilinx.rapidwright.support.RapidWrightDCP;
import com.xilinx.rapidwright.util.FileTools;
import com.xilinx.rapidwright.util.VivadoTools;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

public class TestSATRouter {
    @Test
    public void testSATRouter() {
        // Adapted from https://github.com/clavin-xlnx/RapidWright-binder/blob/24527f33b6aea283cf430ab8f4eab3dc01fa5d64/SATRouter.ipynb
        Design design = RapidWrightDCP.loadDCP("reduce_or_routed_7overlaps.dcp");

        for (Net net : design.getNets()) {
            if (net.isClockNet() || net.isStaticNet()) {
                continue;
            }
            net.unroute();
        }

        PBlock pblock = new PBlock(design.getDevice(), " SLICE_X108Y660:SLICE_X111Y664");
        SATRouter satRouter = new SATRouter(design, pblock, false);

        satRouter.route();

        if (FileTools.isVivadoOnPath()) {
            Assertions.assertTrue(VivadoTools.reportRouteStatus(design).isFullyRouted());
        }
    }
}

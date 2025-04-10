package com.xilinx.rapidwright.design.blocks;

import java.util.Map;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import com.xilinx.rapidwright.design.Design;
import com.xilinx.rapidwright.support.RapidWrightDCP;

public class TestUtilizationType {

    @Test
    public void testComputeUtilization() {
        Design design = RapidWrightDCP.loadDCP("optical-flow.dcp");

        Map<UtilizationType, Integer> utilization = UtilizationType.computeUtilization(design);
        System.out.println(utilization);

        Assertions.assertEquals(2891, utilization.get(UtilizationType.CARRY8S));
        Assertions.assertEquals(25740, utilization.get(UtilizationType.CLB_REGS));
        Assertions.assertEquals(124, utilization.get(UtilizationType.DSPS));
        Assertions.assertEquals(59, utilization.get(UtilizationType.RAMB36S_FIFOS));
        Assertions.assertEquals(10, utilization.get(UtilizationType.RAMB18S));
        Assertions.assertEquals(287, utilization.get(UtilizationType.LUTS_AS_MEMORY));
        Assertions.assertEquals(64, utilization.get(UtilizationType.BRAMS));
        Assertions.assertEquals(30239, utilization.get(UtilizationType.CLB_LUTS));

    }
}

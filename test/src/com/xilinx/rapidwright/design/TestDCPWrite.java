package com.xilinx.rapidwright.design;

import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.xilinx.rapidwright.support.RapidWrightDCP;

public class TestDCPWrite {

    @Test
    public void testVersalDualOutputCOUT(@TempDir Path dir) {
        // Tests a dual-output scenario COUT and HQ2 in Versal (See Xilinx/RapidWright#572)
        Design d = RapidWrightDCP.loadDCP("versal_cout_hq2.dcp");
        d.writeCheckpoint(dir.resolve("output.dcp"));
    }
}

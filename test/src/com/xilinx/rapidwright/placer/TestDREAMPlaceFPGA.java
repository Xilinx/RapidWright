package com.xilinx.rapidwright.placer;

import com.xilinx.rapidwright.design.Design;
import com.xilinx.rapidwright.edif.EDIFNetlist;
import com.xilinx.rapidwright.placer.dreamplacefpga.DREAMPlaceFPGA;
import com.xilinx.rapidwright.support.RapidWrightDCP;
import com.xilinx.rapidwright.util.ReportRouteStatusResult;
import com.xilinx.rapidwright.util.VivadoTools;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;

public class TestDREAMPlaceFPGA {

    @Test
    public void testDREAMPlaceFPGAMain(@TempDir Path tempDir) throws IOException {
        String inputDcp = RapidWrightDCP.getString("gnl_2_4_3_1.3_gnl_3000_07_3_80_80_placed.dcp");
        String outputDcp = tempDir.resolve("output.dcp").toString();
        DREAMPlaceFPGA.main(new String[]{inputDcp, outputDcp, tempDir.toString()});

        ReportRouteStatusResult rrs = VivadoTools.reportRouteStatus(Paths.get(outputDcp));
    }
}

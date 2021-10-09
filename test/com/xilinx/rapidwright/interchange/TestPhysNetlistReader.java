package com.xilinx.rapidwright.interchange;

import java.io.IOException;
import java.nio.file.Path;

import com.xilinx.rapidwright.checker.CheckOpenFiles;
import com.xilinx.rapidwright.design.Design;
import com.xilinx.rapidwright.design.SiteInst;
import com.xilinx.rapidwright.design.Cell;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

public class TestPhysNetlistReader {
    private void testRoutethruLUTsHelper(Design d) {
        SiteInst si = d.getSiteInstFromSiteName("SLICE_X0Y0");
        Cell a6lut = si.getCell("A6LUT");
        Assertions.assertNotNull(a6lut);
        Assertions.assertTrue(a6lut.isRoutethru());
        Assertions.assertEquals(a6lut.getPinMappingsP2L().toString(), "{A3=S[0]}");
        Cell a5lut = si.getCell("A5LUT");
        Assertions.assertNotNull(a5lut);
        Assertions.assertTrue(a5lut.isRoutethru());
        Assertions.assertEquals(a5lut.getPinMappingsP2L().toString(), "{A1=D}");
    }

    @Test
    @CheckOpenFiles
    public void testRoutethruLUTs(@TempDir Path tempDir) throws IOException {
        final String inputPath = "RapidWrightDCP/routethru_luts.dcp";
        Design input = Design.readCheckpoint(inputPath);
        testRoutethruLUTsHelper(input);

        final Path interchangePath = tempDir.resolve("routethru_luts.phys");
        PhysNetlistWriter.writePhysNetlist(input, interchangePath.toString());

        Design output = PhysNetlistReader.readPhysNetlist(interchangePath.toString(), input.getNetlist());
        testRoutethruLUTsHelper(output);
    }

}

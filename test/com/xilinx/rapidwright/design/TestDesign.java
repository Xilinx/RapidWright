package com.xilinx.rapidwright.design;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;

import com.xilinx.rapidwright.support.CheckOpenFiles;
import com.xilinx.rapidwright.support.RapidWrightDCP;
import com.xilinx.rapidwright.device.BEL;
import com.xilinx.rapidwright.device.Device;
import com.xilinx.rapidwright.device.Site;
import com.xilinx.rapidwright.edif.EDIFNetlist;
import com.xilinx.rapidwright.util.ParallelismTools;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Test that we can write a DCP file and read it back in. We currently don't have a way to check designs for equality,
 * so we just try to catch obvious issues.
 */
public class TestDesign {
    public static final String DEVICE = "xc7a12t";

    private static final String SITE = "SLICE_X20Y42";

    private Design createSampleDesign() {
        final EDIFNetlist netlist = TestEDIF.createEmptyNetlist();
        final Design design = new Design(netlist);

        final Cell myCell = design.createCell("myCell", Unisim.FDRE);

        final Site site = design.getDevice().getSite(SITE);
        design.createSiteInst(site);
        final BEL bel = site.getBEL("AFF");
        Assertions.assertNotNull(bel);
        design.placeCell(myCell, site, bel);

        return design;
    }

    @Test
    @CheckOpenFiles
    public void checkDcpRoundtrip(@TempDir Path tempDir) throws IOException {
        //Keep a reference to the device to avoid it being garbage collected during testcase execution
        Device device = Device.getDevice(DEVICE);

        //Use separate files for writing/reading so we can identify leaking file handles by filename
        final Path filenameWrite = tempDir.resolve("testWrite.dcp");
        final Path filenameRead = tempDir.resolve("testRead.dcp");

        createSampleDesign().writeCheckpoint(filenameWrite);
        Files.copy(filenameWrite, filenameRead);

        Design design = Design.readCheckpoint(filenameRead);
        TestEDIF.verifyNetlist(design.getNetlist(), "myCell");

        final Cell cell = design.getCell("myCell");
        Assertions.assertNotNull(cell);
        Assertions.assertEquals(SITE, cell.getSiteInst().getSite().getName());

    }

    @Test
    @CheckOpenFiles
    public void checkDcpRoundtripModuleInstAnchor(@TempDir Path tempDir) throws IOException {
        //Keep a reference to the device to avoid it being garbage collected during testcase execution
        Device device = Device.getDevice(DEVICE);

        //Use separate files for writing/reading so we can identify leaking file handles by filename
        final Path filenameWrite = tempDir.resolve("testWrite.dcp");
        final Path filenameRead = tempDir.resolve("testRead.dcp");

        Module module = new Module(createSampleDesign());

        Design design = new Design("top", device.getDeviceName());
        EDIFNetlist netlist = design.getNetlist();
        netlist.migrateCellAndSubCells(module.getNetlist().getTopCell());

        ModuleInst mi = design.createModuleInst("inst", module);
        mi.getCellInst().setCellType(module.getNetlist().getTopCell());
        mi.placeOnOriginalAnchor();
        String oldAnchor = mi.getAnchor().toString();

        design.writeCheckpoint(filenameWrite);
        Files.copy(filenameWrite, filenameRead);

        design = Design.readCheckpoint(filenameRead);
        mi = design.getModuleInst("inst");
        Assertions.assertEquals(oldAnchor, mi.getAnchor().toString());
    }

    @Test
    @CheckOpenFiles
    public void checkDcpRoundtripParallel(@TempDir Path tempDir) {
        final Path filenameRead = RapidWrightDCP.getPath("optical-flow.dcp");
        final Path filenameWrite = tempDir.resolve("testWrite.dcp");

        Design before = Design.readCheckpoint(filenameRead);

        try {
            ParallelismTools.setParallel(true);
            before.writeCheckpoint(filenameWrite);
        } finally {
            ParallelismTools.setParallel(false);
        }

        Design after = Design.readCheckpoint(filenameWrite);

        Assertions.assertEquals(before.getNetlist(), after.getNetlist());
        Assertions.assertEquals(new HashSet<>(before.getSiteInsts()),
                                new HashSet<>(after.getSiteInsts()));
        Assertions.assertEquals(new HashSet<>(before.getNets()),
                                new HashSet<>(after.getNets()));
    }
  
    @Test
    @CheckOpenFiles
    public void testDcpSecondarySourcePins() {
        final String inputPath = RapidWrightDCP.getString("ramb18.dcp");
        Design design = Design.readCheckpoint(inputPath);

        for (int i = 0; i < 18; i++) {
            Net net = design.getNet("r/U0/inst_blk_mem_gen/gnbram.gnativebmg.native_blk_mem_gen/valid.cstr/ramloop[0].ram.r/prim_noinit.ram/douta[" + i + "]");
            SitePinInst spi = net.getSource();
            if (i == 8 || i == 17)
                Assertions.assertEquals("DOPADOP" + i/9, spi.getName());
            else
                Assertions.assertEquals("DOADO" + (i - i/9), spi.getName());
        }
    }
    
    @Test
    @CheckOpenFiles
    public void testRouteThruPIPsSitePinInsts() {
        final String inputPath = RapidWrightDCP.getString("routethru_pip.dcp");
        Design design = Design.readCheckpoint(inputPath);

        Net net = design.getNet("CO3_OBUF");
        for (SitePinInst spi : net.getSinkPins()) {
            Assertions.assertTrue(spi.getSite().isInputPin(spi.getName()));
        }
    }
}

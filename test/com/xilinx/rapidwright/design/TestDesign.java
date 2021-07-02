package com.xilinx.rapidwright.design;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import com.xilinx.rapidwright.checker.CheckOpenFiles;
import com.xilinx.rapidwright.device.BEL;
import com.xilinx.rapidwright.device.Device;
import com.xilinx.rapidwright.device.Site;
import com.xilinx.rapidwright.edif.EDIFNetlist;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Test that we can write a DCP file and read it back in. We currently don't have a way to check designs for equality,
 * so we just try to catch obvious issues.
 */
public class TestDesign {

    private static final String SITE = "SLICE_X42Y42";

    private Design createSampleDesign() {
        final EDIFNetlist netlist = TestEDIF.createEmptyNetlist();
        final Design design = new Design(netlist);

        final Cell myCell = design.createCell("myCell", Unisim.FDRE);
        System.out.println("myCell.getBEL() = " + myCell.getBEL());
        final Site site = design.getDevice().getSite(SITE);
        final SiteInst siteInst = design.createSiteInst(site);
        final BEL bel = site.getBEL("AFF");
        Assertions.assertNotNull(bel);
        design.placeCell(myCell, site, bel);

        return design;
    }

    @Test
    @CheckOpenFiles
    public void checkDcpRoundtrip(@TempDir Path tempDir) throws IOException {
        //Keep a reference to the device to avoid it being garbage collected during testcase execution
        Device device = Device.getDevice(TestRelocation.DEVICE_ULTRASCALE);

        //Use separate files for writing/reading so we can identify identify leaking file handles by filename
        final Path filenameWrite = tempDir.resolve("testWrite.dcp");
        final Path filenameRead = tempDir.resolve("testRead.dcp");

        createSampleDesign().writeCheckpoint(filenameWrite);
        Files.copy(filenameWrite, filenameRead);

        Design design = Design.readCheckpoint(filenameRead);
        TestEDIF.verifyNetlist(design.getNetlist());

        final Cell cell = design.getCell("myCell");
        Assertions.assertNotNull(cell);
        Assertions.assertEquals(SITE, cell.getSiteInst().getSite().getName());

    }
}

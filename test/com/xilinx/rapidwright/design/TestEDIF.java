package com.xilinx.rapidwright.design;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import com.xilinx.rapidwright.checker.CheckOpenFiles;
import com.xilinx.rapidwright.edif.EDIFCell;
import com.xilinx.rapidwright.edif.EDIFCellInst;
import com.xilinx.rapidwright.edif.EDIFDesign;
import com.xilinx.rapidwright.edif.EDIFLibrary;
import com.xilinx.rapidwright.edif.EDIFNet;
import com.xilinx.rapidwright.edif.EDIFNetlist;
import com.xilinx.rapidwright.edif.EDIFPort;
import com.xilinx.rapidwright.edif.EDIFPortInst;
import com.xilinx.rapidwright.edif.EDIFTools;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Test that we can write an EDIF file and read it back in. We currently don't have a way to check designs for equality,
 * so we just try to catch obvious issues.
 */
public class TestEDIF {

    public static EDIFNetlist createEmptyNetlist() {
        EDIFNetlist netlist = new EDIFNetlist("test");
        netlist.setDesign(new EDIFDesign("test"));
        final EDIFLibrary workLibrary = netlist.getWorkLibrary();
        final EDIFCell topCell = new EDIFCell(workLibrary, "myTestCell");
        netlist.getDesign().setTopCell(topCell);

        EDIFTools.ensureCorrectPartInEDIF(netlist, TestRelocation.DEVICE_ULTRASCALE);

        return netlist;
    }

    @Test
    @CheckOpenFiles
    public void checkEdifRoundtrip(@TempDir Path tempDir) throws IOException {
        //Use separate files for writing/reading so we can identify identify leaking file handles by filename
        final Path filenameWrite = tempDir.resolve("testWrite.edf");
        final Path filenameRead = tempDir.resolve("testRead.edf");

        final EDIFNetlist original = createEmptyNetlist();

        EDIFCellInst inst = Design.createUnisimInst(original.getTopCell(), "inst", Unisim.FDRE);
        original.exportEDIF(filenameWrite);
        Files.copy(filenameWrite, filenameRead);

        final EDIFNetlist netlist = EDIFTools.readEdifFile(filenameRead);

        verifyNetlist(netlist);

    }

    public static void verifyNetlist(EDIFNetlist netlist) {
        final EDIFLibrary workLibrary = netlist.getWorkLibrary();
        Assertions.assertNotNull(workLibrary);

        final EDIFCell cell = workLibrary.getCell("myTestCell");
        Assertions.assertNotNull(cell);
        Assertions.assertEquals(cell, netlist.getTopCell());

        Assertions.assertEquals(cell.getCellInsts().size(),1);
        final EDIFCellInst inst = cell.getCellInsts().iterator().next();
        Assertions.assertEquals("inst", inst.getName());
    }
}

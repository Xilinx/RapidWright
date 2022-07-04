package com.xilinx.rapidwright.edif;

import java.io.IOException;

import com.xilinx.rapidwright.support.RapidWrightDCP;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

public class TestEDIFParseCellNames {

    private void verifyNetlist(EDIFNetlist netlist) {
        final EDIFCell cellA = netlist.getTopCell().getCellInst("instA").getCellType();
        final EDIFCell cellB = netlist.getTopCell().getCellInst("instB").getCellType();

        Assertions.assertNotEquals(cellA, cellB);
        Assertions.assertNotNull(cellA.getPort("portA"));
        Assertions.assertNull(cellB.getPort("portA"));
        Assertions.assertNotNull(cellB.getPort("portB"));
        Assertions.assertNull(cellA.getPort("portB"));
        Assertions.assertNotEquals(cellA.getName(), cellB.getName());
        Assertions.assertEquals(netlist.getWorkLibrary(), cellA.getLibrary());
        Assertions.assertEquals(netlist.getWorkLibrary(), cellB.getLibrary());
    }

    @Test
    public void parseDuplicateCellsSerial() throws IOException {
        try (final EDIFParser edifParser = new EDIFParser(RapidWrightDCP.getPath("duplicateCellNames.edf"))) {
            final EDIFNetlist netlist = edifParser.parseEDIFNetlist();
            verifyNetlist(netlist);
        }
    }
    @Test
    public void parseDuplicateCellsParallel() throws IOException {
        try (final ParallelEDIFParser parallelEDIFParser = new ParallelEDIFParser(RapidWrightDCP.getPath("duplicateCellNames.edf"))) {
            final EDIFNetlist netlist = parallelEDIFParser.parseEDIFNetlist();
            verifyNetlist(netlist);
        }
    }
}

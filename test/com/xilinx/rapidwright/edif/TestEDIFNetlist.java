package com.xilinx.rapidwright.edif;

import java.nio.file.Path;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.xilinx.rapidwright.design.Design;
import com.xilinx.rapidwright.device.Part;
import com.xilinx.rapidwright.device.PartNameTools;
import com.xilinx.rapidwright.support.CheckOpenFiles;

class TestEDIFNetlist {

    private static final String PART_NAME = "xcvu440-flga2892-2-e";
    
    private static final String TEST_MACRO = "IOBUFDS_INTERMDISABLE";
    
    private Design createSampleMacroDesign(String macro, Part part) {
        String designName = TEST_MACRO +"_design";
        final EDIFNetlist netlist = EDIFTools.createNewNetlist(designName);
        final Design design = new Design(designName, PART_NAME);
        design.setNetlist(netlist);
        design.setPartName(part.getName());
        
        final EDIFCell prototypeMacro = Design.getMacroPrimitives(part.getSeries()).getCell(macro); 
        
        EDIFCell macroCell = new EDIFCell(netlist.getHDIPrimitivesLibrary(), prototypeMacro);
        
        macroCell.createCellInst("test" + macro, netlist.getTopCell());

        return design;
    }
    
    @Test
    @CheckOpenFiles
    void testMacroExpansionException(@TempDir Path tempDir) {
        final Part part = PartNameTools.getPart(PART_NAME);
        Design testDesign = createSampleMacroDesign(TEST_MACRO, part);
        final Path outputDCP = tempDir.resolve(testDesign.getName() + ".dcp");
        testDesign.writeCheckpoint(outputDCP);
        
        Design loadAgain = Design.readCheckpoint(outputDCP);
        Assertions.assertTrue(loadAgain.getNetlist().getHDIPrimitivesLibrary().containsCell("OBUFTDS"));
    }

}

/*
 * 
 * Copyright (c) 2022 Xilinx, Inc. 
 * All rights reserved.
 *
 * Author: Chris Lavin, Xilinx Research Labs.
 *
 * This file is part of RapidWright. 
 * 
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 *     http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 * 
 */
/**
 * 
 */
package com.xilinx.rapidwright.edif;

import java.nio.file.Path;
import java.util.List;
import java.util.Map.Entry;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.xilinx.rapidwright.design.Design;
import com.xilinx.rapidwright.support.CheckOpenFiles;
import com.xilinx.rapidwright.support.RapidWrightDCP;
import com.xilinx.rapidwright.util.FileTools;

public class TestBinaryEDIF {

    
    /**
     * Tests EDIFPropertyObjects for equivalence (EDIFName and EDIF property maps are equal and 
     * contain equivalent data.
     * @param golden The reference object
     * @param test The test object
     * @return True if the two objects are equivalent
     */
    private boolean equivalentEDIFPropObject(EDIFPropertyObject golden, EDIFPropertyObject test) {
        Assertions.assertEquals(golden.getEDIFName(), test.getEDIFName());
        Assertions.assertEquals(golden.getProperties().size(), test.getProperties().size());
        for(Entry<EDIFName, EDIFPropertyValue> e : golden.getProperties().entrySet()) {
            EDIFPropertyValue testValue = test.getProperty(e.getKey().getName());
            Assertions.assertNotNull(testValue);
            Assertions.assertEquals(e.getValue().getType(), testValue.getType());
            Assertions.assertEquals(e.getValue().getValue(), testValue.getValue());
        }
        return true;
    }
    
    /**
     * Checks if the two provided EDIFCells are equivalent.  They are equivalent if they have the 
     * same EDIFName, same number and equivalent objects of EDIFPorts, EDIFCellInsts and EDIFNets.  
     * Within EDIFNets, it also checks that the EDIFPortInsts are equivalent.  
     * @param golden The reference cell
     * @param test The test cell
     * @return True if the two cells are equivalent, false otherwise.
     */
    private boolean equivalentEDIFCells(EDIFCell golden, EDIFCell test) {
        Assertions.assertTrue(equivalentEDIFPropObject(golden, test));
        Assertions.assertEquals(golden.getEDIFView(), test.getEDIFView());
        Assertions.assertEquals(golden.getPorts().size(), test.getPorts().size());
        for(EDIFPort port : golden.getPorts()) {
            EDIFPort testPort = test.getPort(port.getBusName());
            Assertions.assertNotNull(testPort);
            Assertions.assertTrue(equivalentEDIFPropObject(port, testPort));
            Assertions.assertEquals(port.getLeft(), testPort.getLeft());
            Assertions.assertEquals(port.getRight(), testPort.getRight());
        }
        Assertions.assertEquals(golden.getCellInsts().size(), test.getCellInsts().size());
        for(EDIFCellInst inst : golden.getCellInsts()) {
            EDIFCellInst testInst = test.getCellInst(inst.getName());
            Assertions.assertNotNull(testInst);
            Assertions.assertTrue(equivalentEDIFPropObject(inst, testInst));
            Assertions.assertEquals(inst.getViewref(), testInst.getViewref());
            Assertions.assertEquals(inst.getCellName(), testInst.getCellName());
            Assertions.assertEquals(inst.getCellType().getLibrary().getEDIFName(), 
                                    testInst.getCellType().getLibrary().getEDIFName());
        }
        Assertions.assertEquals(golden.getNets().size(), test.getNets().size());
        for(EDIFNet net : golden.getNets()) {
            EDIFNet testNet = test.getNet(net.getName());
            Assertions.assertNotNull(testNet);
            Assertions.assertTrue(equivalentEDIFPropObject(net, testNet));
            Assertions.assertEquals(net.getPortInsts().size(), testNet.getPortInsts().size());
            for(EDIFPortInst pInst : net.getPortInsts()) {
                EDIFPortInst testPortInst = testNet.getPortInst(pInst.getCellInst(), pInst.getName());
                Assertions.assertNotNull(testPortInst);
                Assertions.assertEquals(pInst.getIndex(), testPortInst.getIndex());
            }
        }
        return true;
    }
    
    /**
     * Checkes that two EDIF netlists are equivalent
     * @param golden Reference netlist
     * @param test Netlist that is being tested
     * @return True if the two netlists have the same number of libraries, cells, ports, nets, 
     * instances and port instances, false otherwise
     */
    private boolean equivalentEDIFNetlists(EDIFNetlist golden, EDIFNetlist test) {
        Assertions.assertEquals(golden.getEDIFName(), test.getEDIFName());
        Assertions.assertTrue(equivalentEDIFPropObject(golden.getDesign(), test.getDesign()));
        Assertions.assertTrue(equivalentEDIFCells(golden.getDesign().getTopCell(), 
                                                  test.getDesign().getTopCell()));
        Assertions.assertEquals(golden.getLibraries().size(), test.getLibraries().size());
        for(EDIFLibrary lib : golden.getLibraries()) {
            EDIFLibrary testLib = test.getLibrary(lib.getName());
            Assertions.assertNotNull(testLib);
            for(EDIFCell cell : lib.getCells()) {
                EDIFCell testCell = testLib.getCell(cell.getLegalEDIFName());
                Assertions.assertNotNull(testCell);
                Assertions.assertTrue(equivalentEDIFCells(cell, testCell));
            }
        }
        return true;
    }
    
    /**
     * Compares two text-based EDIF files to see if they match 
     * (except for their respective timestamp)
     * @param golden Path to the golden EDIF file
     * @param test Path to the test EDIF file
     * @return True if the two files match (excluding timestamp), false otherwise 
     */
    private boolean compareEDIFFiles(Path golden, Path test) {
        List<String> goldenLines = FileTools.getLinesFromTextFile(golden.toString());
        List<String> testLines = FileTools.getLinesFromTextFile(test.toString());
        if(goldenLines.size() != testLines.size()) return false;
        int length = goldenLines.size();
        for(int i=0 ; i < length; i++) {
            if(!goldenLines.get(i).equals(testLines.get(i))) {
                if(goldenLines.get(i).contains("(timeStamp ")) continue;
                System.err.println("EDIF mismatch on line " + i + ": >>" 
                        + goldenLines.get(i) +"<<  >>" + testLines.get(i) + "<<");
                return false;
            }
        }
        return true;
    }
    

    @Test
    @CheckOpenFiles
    public void testBinaryEDIF(@TempDir Path tempDir) {
        Path dcp = RapidWrightDCP.getPath("optical-flow.dcp");
        boolean noXdef = true;
        Design design = Design.readCheckpoint(dcp, noXdef);
        EDIFNetlist netlist = design.getNetlist();
        
        Path goldenPath = tempDir.resolve("golden.edf");
        netlist.exportEDIF(goldenPath);
        
        EDIFNetlist golden = EDIFTools.readEdifFile(goldenPath);
        
        Path binaryPath = tempDir.resolve("test.bedf"); 
        netlist.writeBinaryEDIF(binaryPath);
        EDIFNetlist test = EDIFNetlist.readBinaryEDIF(binaryPath);
        
        Assertions.assertTrue(equivalentEDIFNetlists(golden, test));
        
        Path testPath = tempDir.resolve("test.edf");
        test.exportEDIF(testPath);

        Assertions.assertTrue(compareEDIFFiles(goldenPath, testPath));
    }
}

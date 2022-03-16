/* 
 * Copyright (c) 2021 Xilinx, Inc. 
 * All rights reserved.
 *
 * Author: Jakob Wenzel, Xilinx Research Labs.
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
 
package com.xilinx.rapidwright.design;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashSet;

import com.xilinx.rapidwright.edif.EDIFCell;
import com.xilinx.rapidwright.edif.EDIFDesign;
import com.xilinx.rapidwright.edif.EDIFLibrary;
import com.xilinx.rapidwright.edif.EDIFNet;
import com.xilinx.rapidwright.edif.EDIFTools;
import com.xilinx.rapidwright.support.CheckOpenFiles;
import com.xilinx.rapidwright.support.RapidWrightDCP;
import com.xilinx.rapidwright.device.BEL;
import com.xilinx.rapidwright.device.Device;
import com.xilinx.rapidwright.device.Site;
import com.xilinx.rapidwright.edif.EDIFNetlist;
import com.xilinx.rapidwright.tests.CodePerfTracker;
import com.xilinx.rapidwright.util.FileTools;
import com.xilinx.rapidwright.util.Job;
import com.xilinx.rapidwright.util.JobQueue;
import com.xilinx.rapidwright.util.LocalJob;
import com.xilinx.rapidwright.util.ParallelismTools;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

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

    private EDIFNetlist generateEDIF(String edifName, long numLibraries, long cellsPerLibrary, long netsPerCell) {
        EDIFNetlist netlist = EDIFTools.createNewNetlist(edifName);
        EDIFTools.ensureCorrectPartInEDIF(netlist, Device.AWS_F1);
        for (int libraryIdx = 0; libraryIdx < numLibraries; libraryIdx++) {
            EDIFLibrary lib = new EDIFLibrary("library_" + libraryIdx);
            for (int cellIdx = 0; cellIdx < cellsPerLibrary; cellIdx++) {
                EDIFCell cell = new EDIFCell(lib, "cell_" + cellIdx);
                for (int netIdx = 0; netIdx < netsPerCell; netIdx++) {
                    new EDIFNet("net_" + netIdx, cell);
                }
            }
            netlist.addLibrary(lib);
        }
        return netlist;
    }

    @ParameterizedTest
    @CheckOpenFiles
    @ValueSource(booleans = {false,true})
    public void testDcpEdifBiggerThan4GB(boolean parallel, @TempDir Path tempDir) {
        long maxMemoryNeeded = 1024L*1024L*1024L*14L;
        Assumptions.assumeTrue(Runtime.getRuntime().maxMemory() >= maxMemoryNeeded);

        try {
            ParallelismTools.setParallel(parallel);

            final String edifName = "testDcpEdifBiggerThan4GB" + ((parallel) ? "Parallel" : "");
            final long numLibraries = 100;
            final long cellsPerLibrary = 1000;
            final long netsPerCell = 1000;
            final Path outputPath = tempDir.resolve(edifName + ".dcp");

            CodePerfTracker t = new CodePerfTracker(edifName, true);
            t.useGCToTrackMemory(true);
            t.start(numLibraries + " x " + cellsPerLibrary + " x " + netsPerCell);
            EDIFNetlist netlist = generateEDIF(edifName, numLibraries, cellsPerLibrary, netsPerCell);
            t.stop();

            Design design = new Design(netlist);
            design.writeCheckpoint(outputPath, t);
        } finally {
            ParallelismTools.setParallel(false);
        }
    }
    
    @Test
    @CheckOpenFiles
    public void testBug349(@TempDir Path tempDir) throws IOException {
        // This test won't run in CI as Vivado is not available
        Assumptions.assumeTrue(FileTools.isVivadoOnPath());

        final String inputPath = RapidWrightDCP.getString("bug349.dcp");
        Design design = Design.readCheckpoint(inputPath);

        final Path filenameWrite = tempDir.resolve("bug349_roundtrip.dcp");
        final Path tclScript = tempDir.resolve("bug349_roundtrip.tcl");
        design.writeCheckpoint(filenameWrite);

        final Job job = new LocalJob();
        job.setCommand(FileTools.getVivadoPath() + " -mode batch -source " + tclScript);

        job.setRunDir(tempDir.toString());

        Files.write(tclScript, Arrays.asList(
                "open_checkpoint " + filenameWrite.toAbsolutePath()
        ));

        JobQueue queue = new JobQueue();
        queue.addJob(job);
        Assertions.assertTrue(queue.runAllToCompletion());
    }
}

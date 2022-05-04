/* 
 * Copyright (c) 2021 Xilinx, Inc. 
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
 
package com.xilinx.rapidwright.design;

import java.io.BufferedWriter;
import java.io.FileWriter;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.xilinx.rapidwright.support.CheckOpenFiles;
import com.xilinx.rapidwright.support.RapidWrightDCP;
import com.xilinx.rapidwright.edif.EDIFTools;
import com.xilinx.rapidwright.tests.CodePerfTracker;
import com.xilinx.rapidwright.util.FileTools;
import com.xilinx.rapidwright.util.Installer;

/**
 * Tests the EDIF auto-generate mechanism when reading DCPs
 * 
 */
public class TestDCPLoad {

    public static void createSimulatedBinaryEDIF(Path edfFileName, int size) {
        try (BufferedWriter bw = new BufferedWriter(new FileWriter(edfFileName.toString()))){
            for(int i=0; i < size; i++) {
                bw.write(0);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
    
    @Test
    @CheckOpenFiles
    public void checkAutoEDIFGenerationFailure(@TempDir Path tempDir) throws IOException {
        Path dcpPath = RapidWrightDCP.getPath("picoblaze_ooc_X10Y235.dcp");
        final Path dcpCopy = tempDir.resolve(dcpPath.getFileName());
        Files.copy(dcpPath, dcpCopy);

        Path binEdfFile = tempDir.resolve(FileTools.replaceExtension(dcpPath.getFileName(), ".edf"));
        createSimulatedBinaryEDIF(binEdfFile, FileTools.BINARY_CHECK_LENGTH+1); 
        assert(Design.replaceEDIFinDCP(dcpCopy.toString(), binEdfFile.toString()));
        FileTools.deleteFile(binEdfFile.toString());

        // This should fail, we won't check Vivado auto-gen as CI's don't have access to it
        Design.setAutoGenerateReadableEdif(false);
        Assertions.assertThrows(RuntimeException.class, () -> {
            Design.readCheckpoint(dcpCopy, CodePerfTracker.SILENT);
        });
    }

    @Test
    @CheckOpenFiles
    public void checkAutoEDIFGenerationSuccess(@TempDir Path tempDir) throws IOException {
        Path dcpPath = RapidWrightDCP.getPath("picoblaze_ooc_X10Y235.dcp");
        final Path dcpCopy = tempDir.resolve(dcpPath.getFileName());
        Files.copy(dcpPath, dcpCopy);

        Path readableEDIFDir = DesignTools.getDefaultReadableEDIFDir(dcpCopy);
        Path readableEDIF = DesignTools.getEDFAutoGenFilePath(dcpCopy, readableEDIFDir);
        Design design = Design.readCheckpoint(dcpPath, CodePerfTracker.SILENT);
        FileTools.makeDirs(readableEDIFDir.toString());
        EDIFTools.writeEDIFFile(readableEDIF, design.getNetlist(), design.getPartName());
        FileTools.writeStringToTextFile(Installer.calculateMD5OfFile(dcpCopy), 
                DesignTools.getDCPAutoGenMD5FilePath(dcpCopy, readableEDIFDir).toString());
        
        Design.setAutoGenerateReadableEdif(true);
        Design.readCheckpoint(dcpCopy, CodePerfTracker.SILENT);
    }
    
    @Test
    @CheckOpenFiles
    public void checkAutoEDIFGenerationWithVivado(@TempDir Path tempDir) throws IOException {
        // This test won't run in CI as Vivado is not available
        Assumptions.assumeTrue(FileTools.isVivadoOnPath());
        
        Path dcpPath = RapidWrightDCP.getPath("picoblaze_ooc_X10Y235.dcp");
        final Path dcpCopy = tempDir.resolve(dcpPath.getFileName());
        Files.copy(dcpPath, dcpCopy);

        Path binEdfFile = tempDir.resolve(FileTools.replaceExtension(dcpPath.getFileName(), ".edf"));
        createSimulatedBinaryEDIF(binEdfFile, FileTools.BINARY_CHECK_LENGTH+1); 
        assert(Design.replaceEDIFinDCP(dcpCopy.toString(), binEdfFile.toString()));
        FileTools.deleteFile(binEdfFile.toString());
        Path readableEDIFDir = DesignTools.getDefaultReadableEDIFDir(dcpCopy);
        Path readableEDIF = DesignTools.getEDFAutoGenFilePath(dcpCopy, readableEDIFDir);

        // Modify DCP with a different binary EDIF
        createSimulatedBinaryEDIF(binEdfFile, FileTools.BINARY_CHECK_LENGTH+2); 
        assert(Design.replaceEDIFinDCP(dcpCopy.toString(), binEdfFile.toString()));
        FileTools.deleteFile(binEdfFile.toString());
        FileTools.deleteFile(readableEDIF.toString());
        Design.setAutoGenerateReadableEdif(true);
        Design.readCheckpoint(dcpCopy, CodePerfTracker.SILENT);
        Assertions.assertTrue(Files.getLastModifiedTime(readableEDIF).toMillis() >
        Files.getLastModifiedTime(dcpPath).toMillis());
    }
}

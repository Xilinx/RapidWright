/* 
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
 
package com.xilinx.rapidwright.interchange;

import java.io.IOException;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.xilinx.rapidwright.device.Device;
import com.xilinx.rapidwright.support.CheckOpenFiles;
import com.xilinx.rapidwright.tests.CodePerfTracker;

public class TestDeviceResources {

    public static final String TEST_DEVICE = "xc7a15t";
    
    @Test
    @CheckOpenFiles
    public void testDeviceResources(@TempDir Path tempDir) throws IOException {
        Path capnProtoFile = tempDir.resolve(TEST_DEVICE + ".device");
        Device device = Device.getDevice(TEST_DEVICE);
        DeviceResourcesWriter.writeDeviceResourcesFile(
                TEST_DEVICE, device, CodePerfTracker.SILENT, capnProtoFile.toString());            
        Device.releaseDeviceReferences();
        DeviceResourcesVerifier.verifyDeviceResources(capnProtoFile.toString(), TEST_DEVICE);
    }
    

}

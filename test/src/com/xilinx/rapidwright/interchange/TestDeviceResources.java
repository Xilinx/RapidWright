/*
 * Copyright (c) 2022, Xilinx, Inc.
 * Copyright (c) 2022, 2024, Advanced Micro Devices, Inc.
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

import org.capnproto.MessageReader;
import org.capnproto.ReaderOptions;
import org.capnproto.TextList;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.xilinx.rapidwright.device.Device;
import com.xilinx.rapidwright.tests.CodePerfTracker;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

public class TestDeviceResources {

    public static final String TEST_DEVICE = "xc7a15t";

    @Test
    public void testDeviceResources(@TempDir Path tempDir) throws IOException {
        Path capnProtoFile = tempDir.resolve(TEST_DEVICE + ".device");
        Device device = Device.getDevice(TEST_DEVICE);
        DeviceResourcesWriter.writeDeviceResourcesFile(
                TEST_DEVICE, device, CodePerfTracker.SILENT, capnProtoFile.toString());
        Device.releaseDeviceReferences();
        DeviceResourcesVerifier.verifyDeviceResources(capnProtoFile.toString(), TEST_DEVICE);
    }

    @ParameterizedTest
    @CsvSource({
            "xcau10p"
    })
    public void testUltraScalePlusTiming(String deviceName, @TempDir Path tempDir) throws IOException {
        Path capnProtoFile = tempDir.resolve(deviceName + ".device");
        Device device = Device.getDevice(deviceName);
        DeviceResourcesWriter.writeDeviceResourcesFile(
                deviceName, device, CodePerfTracker.SILENT, capnProtoFile.toString());

        ReaderOptions readerOptions = new ReaderOptions(1024L * 1024L * 1024L * 64L, 64);
        MessageReader readMsg = Interchange.readInterchangeFile(capnProtoFile.toString(), readerOptions);
        DeviceResources.Device.Reader dReader = readMsg.getRoot(DeviceResources.Device.factory);

        Assertions.assertTrue(dReader.getPipTimings().size() > 1);
        Assertions.assertTrue(dReader.getNodeTimings().size() > 1);

        TextList.Reader sReader = dReader.getStrList();

        // Check SitePIPs have at least one delay
        int reached = 0;
        for (DeviceResources.Device.SiteType.Reader st : dReader.getSiteTypeList()) {
            String name = sReader.get(st.getName()).toString();
            if (!name.startsWith("SLICE")) {
                continue;
            }
            for (DeviceResources.Device.SitePIP.Reader sp : st.getSitePIPs()) {
                if (sp.hasDelay()) {
                    reached++;
                    break;
                }
            }
        }
        // {SLICEL,SLICEM}
        Assertions.assertEquals(2, reached);

        // Check Cells have at least one delay
        reached = 0;
        for (DeviceResources.Device.CellBelMapping.Reader cb : dReader.getCellBelMap()) {
            String name = sReader.get(cb.getCell()).toString();
            if (!name.startsWith("LUT")) {
                continue;
            }
            if (cb.hasPinsDelay()) {
                reached++;
            }
        }
        // LUT[1-6]
        Assertions.assertEquals(6, reached);
    }
}

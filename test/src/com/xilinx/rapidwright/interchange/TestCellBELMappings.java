/*
 * Copyright (c) 2022, Xilinx, Inc.
 * Copyright (c) 2022-2023, Advanced Micro Devices, Inc.
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

import org.capnproto.MessageBuilder;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import com.xilinx.rapidwright.device.Device;
import com.xilinx.rapidwright.interchange.DeviceResources.Device.CellBelMapping;
import com.xilinx.rapidwright.interchange.DeviceResources.Device.CellBelPinEntry;
import com.xilinx.rapidwright.interchange.DeviceResources.Device.ParameterCellBelPinMaps;

public class TestCellBELMappings {

    @Test
    public void testCellBELPinMappings() {
        StringEnumerator allStrings = new StringEnumerator();
        MessageBuilder message = new MessageBuilder();
        Device device = Device.getDevice(TestDeviceResources.TEST_DEVICE);
        DeviceResources.Device.Builder devBuilder = message.initRoot(DeviceResources.Device.factory);
        EnumerateCellBelMapping.populateAllPinMappings(device.getName(), device, devBuilder, allStrings);

        boolean foundIDDRS = false;
        boolean foundIDDRR = false;
        for (int i=0; i < devBuilder.getCellBelMap().size(); i++) {
            CellBelMapping.Builder mapping = devBuilder.getCellBelMap().get(i);
            if (allStrings.get(mapping.getCell()).equals("IDDR")) {
                Assertions.assertTrue(mapping.hasParameterPins());
               for (ParameterCellBelPinMaps.Builder paramPins : mapping.getParameterPins()) {
                    for (CellBelPinEntry.Builder pinObj : paramPins.getPins()) {
                        Assertions.assertEquals(allStrings.get(pinObj.getBelPin()), "SR");
                        String cellPinName = allStrings.get(pinObj.getCellPin());
                        foundIDDRS = foundIDDRS || cellPinName.equals("S");
                        foundIDDRR = foundIDDRR || cellPinName.equals("R");
                    }
                }
            }
        }
        Assertions.assertTrue(foundIDDRS);
        Assertions.assertTrue(foundIDDRR);
    }
}

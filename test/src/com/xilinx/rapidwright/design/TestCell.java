/* 
 * Copyright (c) 2022 Xilinx, Inc. 
 * All rights reserved.
 *
 * Author: Eddie Hung, Xilinx Research Labs.
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

import com.xilinx.rapidwright.device.Device;
import com.xilinx.rapidwright.device.SiteTypeEnum;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.List;

public class TestCell {
    @ParameterizedTest
    @CsvSource({
            "xcvu3p,SLICE_X0Y0,F8MUX_BOT,I1,1,[BMUX]",
    })
    public void testGetAllCorrespondingSitePinNames(String deviceName,
                                                    String siteName,
                                                    String belName,
                                                    String logicalPinName,
                                                    String physicalPinName,
                                                    String expectedSitePins) {
        Device device = Device.getDevice(deviceName);
        Cell cell = new Cell("cell", device.getSite(siteName).getBEL(belName));
        cell.addPinMapping(physicalPinName, logicalPinName);
        List<String> sitePinNames = cell.getAllCorrespondingSitePinNames(logicalPinName);
        Assertions.assertEquals(expectedSitePins, sitePinNames.toString());
    }
}

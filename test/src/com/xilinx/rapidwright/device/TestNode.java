/*
 * Copyright (c) 2022, Advanced Micro Devices, Inc.
 * All rights reserved.
 *
 * Author: Eddie Hung, Advanced Micro Devices, Inc.
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

package com.xilinx.rapidwright.device;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.HashSet;
import java.util.List;

public class TestNode {
    @ParameterizedTest
    @CsvSource({
            "xcvu19p-fsva3824-1-e,INT_X182Y338/WW12_BEG5",
            "xcvu19p-fsva3824-1-e,INT_X182Y338/NN12_BEG5",
    })
    public void testGetDownhillUphillNodesUnique(String deviceName, String nodeName) {
        Device device = Device.getDevice(deviceName);
        Node node = device.getNode(nodeName);

        List<Node> downhill = node.getAllDownhillNodes();
        Assertions.assertEquals(downhill.size(), new HashSet<>(downhill).size());

        List<Node> uphill = node.getAllUphillNodes();
        Assertions.assertEquals(uphill.size(), new HashSet<>(uphill).size());
    }
}


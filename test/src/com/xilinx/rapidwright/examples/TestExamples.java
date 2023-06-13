/*
 * Copyright (c) 2023, Advanced Micro Devices, Inc.
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

package com.xilinx.rapidwright.examples;

import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import com.xilinx.rapidwright.design.Cell;
import com.xilinx.rapidwright.design.Design;
import com.xilinx.rapidwright.design.Net;
import com.xilinx.rapidwright.design.PinType;
import com.xilinx.rapidwright.design.Unisim;
import com.xilinx.rapidwright.device.Device;
import com.xilinx.rapidwright.router.Router;

public class TestExamples {
    @Test
    public void testPipelineGenerator() {
        PipelineGenerator.main(new String[]{
                "-o", "/dev/null"
        });
    }

    @Test
    public void testPipelineGeneratorWithRouting() {
        PipelineGeneratorWithRouting.main(new String[]{
                "-o", "/dev/null"
        });
    }

    /*
     * This test is a reproduction of Lesson1.java. The code is not used directly so
     * as to keep the original example simple and unencumbered.
     */
    @ParameterizedTest
    @CsvSource({ "xc7z020clg400-1", // Device.ZYNQ_Z1
            "xcku040-ffva1156-2-e", // Device.KCU105
    })
    public void testHelloWorld(String device, @TempDir Path tempDir) {
        // Create a new empty design using the given device part
        Design d = new Design("HelloWorld", device);

        String ioStd = "LVCMOS33";
        String[] iobLocs = { "D19", "D20", "R14" };
        if (device.equals(Device.KCU105)) {
            ioStd = "LVCMOS18";
            iobLocs = new String[] { "AE10", "AF9", "AP8" };
        }

        // Create all the design elements (LUT2, and 3 IOs)
        Cell and2 = d.createAndPlaceCell("and2", Unisim.AND2, "SLICE_X100Y100/A6LUT");
        Cell button0 = d.createAndPlaceIOB("button0", PinType.IN, iobLocs[0], ioStd);
        Cell button1 = d.createAndPlaceIOB("button1", PinType.IN, iobLocs[1], ioStd);
        Cell led0 = d.createAndPlaceIOB("led0", PinType.OUT, iobLocs[2], ioStd);

        // Connect Button 0 to the LUT2 input I0
        Net net0 = d.createNet("button0_IBUF");
        net0.connect(button0, "O");
        net0.connect(and2, "I0");

        // Connect Button 1 to the LUT2 input I1
        Net net1 = d.createNet("button1_IBUF");
        net1.connect(button1, "O");
        net1.connect(and2, "I1");

        // Connect the LUT2 (AND2) to the LED IO
        Net net2 = d.createNet("and2");
        net2.connect(and2, "O");
        net2.connect(led0, "I");

        // Route site internal nets
        d.routeSites();

        // Route nets between sites
        new Router(d).routeDesign();

        // Save our work in a Checkpoint
        Path helloWorldDCP = tempDir.resolve("HelloWorld.dcp");
        d.writeCheckpoint(helloWorldDCP);

        // Confirm the DCP can be reloaded
        Design.readCheckpoint(helloWorldDCP);
    }
}

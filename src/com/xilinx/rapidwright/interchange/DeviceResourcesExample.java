/*
 * Copyright (c) 2020-2022, Xilinx, Inc.
 * Copyright (c) 2022, Advanced Micro Devices, Inc.
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

import com.xilinx.rapidwright.device.Device;
import com.xilinx.rapidwright.tests.CodePerfTracker;

public class DeviceResourcesExample {

    public static final String SKIP_ROUTE_RESOURCES_OPTION = "--skip_route_resources";

    public static void main(String[] args) throws IOException {
        if (args.length != 1 && args.length != 2) {
            System.out.println("USAGE: <device name> [" + SKIP_ROUTE_RESOURCES_OPTION + "]");
            System.out.println("   Example dump of device information for interchange format.");
            return;
        }

        boolean skipRouteResources = false;
        if (args.length == 2 && args[1].equals(SKIP_ROUTE_RESOURCES_OPTION)) {
            skipRouteResources = true;
        }

        CodePerfTracker t = new CodePerfTracker("Device Resources Dump: " + args[0]);
        t.useGCToTrackMemory(true);

        // Create device resource file if it doesn't exist
        String capnProtoFileName = args[0] + ".device";
        t.start("Load Device");
        Device device = Device.getDevice(args[0]);
        t.stop();
        // Write Netlist to Cap'n Proto Serialization file
        DeviceResourcesWriter.writeDeviceResourcesFile(args[0], device, t, capnProtoFileName, skipRouteResources);
        Device.releaseDeviceReferences();

        t.start("Verify file");
        // Verify device resources
        DeviceResourcesVerifier.verifyDeviceResources(capnProtoFileName, args[0]);

        t.stop().printSummary();
    }
}

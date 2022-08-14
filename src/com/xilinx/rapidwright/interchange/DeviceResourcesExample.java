/* 
 * Copyright (c) 2020 Xilinx, Inc. 
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

import java.io.File;
import java.io.IOException;

import com.xilinx.rapidwright.device.Device;
import com.xilinx.rapidwright.tests.CodePerfTracker;

public class DeviceResourcesExample {

    public static void main(String[] args) throws IOException {
        if(args.length != 1) {
            System.out.println("USAGE: <device name>");
            System.out.println("   Example dump of device information for interchange format.");
            return;
        }

        CodePerfTracker t = new CodePerfTracker("Device Resources Dump: " + args[0]);
        t.useGCToTrackMemory(true);

        // Create device resource file if it doesn't exist
        String capnProtoFileName = args[0] + ".device";
        //if(!new File(capnProtoFileName).exists()) {
            //MessageGenerator.waitOnAnyKey();
            t.start("Load Device");
            Device device = Device.getDevice(args[0]);
            t.stop();
            // Write Netlist to Cap'n Proto Serialization file
            DeviceResourcesWriter.writeDeviceResourcesFile(args[0], device, t, capnProtoFileName);            
            Device.releaseDeviceReferences();
        //}
        
        t.start("Verify file");
        // Verify device resources
        DeviceResourcesVerifier.verifyDeviceResources(capnProtoFileName, args[0]);
        
        t.stop().printSummary();
    }
}

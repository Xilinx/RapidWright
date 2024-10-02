/*
 *
 * Copyright (c) 2024, Advanced Micro Devices, Inc.
 * All rights reserved.
 *
 * Author: Chris Lavin, AMD Research and Advanced Development.
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

import java.util.Arrays;

import com.xilinx.rapidwright.design.Design;
import com.xilinx.rapidwright.device.Device;
import com.xilinx.rapidwright.device.Part;
import com.xilinx.rapidwright.device.PartNameTools;

/**
 * Allows an existing DCP (placed and/or routed) to be retargeted to another
 * part and optionally be relocated to another SLR. This works only for specific
 * devices such as a VU3P to a VU9P, for example since they have floorplan
 * compatible SLRs.
 */
public class PartRetargeter {
    
    public static void main(String[] args) {
        if (args.length != 4) {
            System.out.println("USAGE: <input.dcp> <output.dcp> <target part name> <target SLR index>");
            System.exit(1);
        }
        Part targetPart = PartNameTools.getPart(args[2]);
        if (targetPart == null) {
            throw new RuntimeException("ERROR: Unrecognized part '" + args[2] + "'");
        }
        Device targetDevice = Device.getDevice(targetPart);

        int targetSLR = Integer.parseInt(args[3]); 
        if (targetSLR < 0 || targetSLR >= targetDevice.getNumOfSLRs()) {
            throw new RuntimeException("ERROR: Invalid SLR index '" + args[3] + "', should be one of "
                    + Arrays.toString(targetDevice.getSLRs()));
        }
        int tileXOffset = 0;
        int tileYOffset = targetSLR * (targetDevice.getMasterSLR().getNumOfClockRegionRows()
                                    * targetPart.getSeries().getCLEHeight());
        
        Design d = Design.readCheckpoint(args[0]);
        boolean result = d.retargetPart(targetPart, tileXOffset, tileYOffset);
        if (!result) {
            System.err.println("WARNING: Incomplete relocation of design.");
        }
        d.writeCheckpoint(args[1]);
    }
}

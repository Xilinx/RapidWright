/*
 * Copyright (c) 2025, Advanced Micro Devices, Inc.
 * All rights reserved.
 *
 * Author: Coherent Ho, Synopsys, Inc.
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

import java.util.ArrayList;
import java.util.Map;

import com.xilinx.rapidwright.design.Design;
import com.xilinx.rapidwright.design.DesignTools;
import com.xilinx.rapidwright.design.Net;
import com.xilinx.rapidwright.design.SitePinInst;


/**
 * Simple tool for get the partially routed nets in a design.
 */
public class CountRoutedNets {

    public static void main(String[] args) {
        if (args.length != 1) {
            System.out.println("Usage: <input DCP>");
            return;
        }

        Design design = Design.readCheckpoint(args[0]);

        DesignTools.makePhysNetNamesConsistent(design);
        DesignTools.createMissingSitePinInsts(design);
        DesignTools.updatePinsIsRouted(design);

        int fullyRoutedNetCount = 0;
        int partiallyRoutedNetCount = 0;

        for (Net net : design.getNets()) {
            if (!net.hasPIPs()) continue;

            boolean isPartiallyRouted = false;
            ArrayList<String> unroutedPins = new ArrayList<>();

            for (SitePinInst pin : net.getPins()) {
                if (!pin.isRouted() && !pin.isOutPin()) {
                    unroutedPins.add(pin.getName());
                    isPartiallyRouted = true;
                }
            }

            if (!unroutedPins.isEmpty()) {
                System.out.println("Net " + net.getName() + " has unrouted pins: " + unroutedPins);
            }

            if (isPartiallyRouted) {
                partiallyRoutedNetCount++;
            } else {
                fullyRoutedNetCount++;
            }
        }

        System.out.println("Fully routed nets: " + fullyRoutedNetCount);
        System.out.println("Partially routed nets: " + partiallyRoutedNetCount);
    }
}

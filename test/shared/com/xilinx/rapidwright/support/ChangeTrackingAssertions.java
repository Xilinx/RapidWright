/*
 * Copyright (c) 2026, Advanced Micro Devices, Inc.
 * All rights reserved.
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

package com.xilinx.rapidwright.support;

import org.junit.jupiter.api.Assertions;

import com.xilinx.rapidwright.design.Design;
import com.xilinx.rapidwright.design.Net;

/**
 * Assertions on a design's change tracking (Design#getModifiedNets()), which an incremental
 * writer walks to find what to write.
 */
public class ChangeTrackingAssertions {

    /**
     * Asserts that every modified Net is the one the design holds under its name. Anything else is
     * a net that has been removed or replaced, and whoever walks the modified set would write its
     * routing out alongside -- or in place of -- whatever the design holds instead.
     */
    public static void assertModifiedNetsAreInDesign(Design design) {
        for (Net net : design.getModifiedNets()) {
            Assertions.assertSame(net, design.getNet(net.getName()),
                    "modified net is not the one the design holds: " + net.getName());
            Assertions.assertSame(design, net.getDesign(), net.getName());
        }
    }
}

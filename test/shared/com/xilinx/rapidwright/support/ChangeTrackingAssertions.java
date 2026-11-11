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
import com.xilinx.rapidwright.design.SiteInst;

/**
 * Assertions on a design's change tracking (Design#getModifiedNets() and
 * Design#getModifiedSiteInsts()), which an incremental writer walks to find what to write.
 */
public class ChangeTrackingAssertions {

    /**
     * Asserts that everything the change tracking holds as modified is something the design still
     * holds: every modified Net is the one the design holds under its name, and every modified
     * SiteInst is the one the design holds under its name and, if placed, the one on its site.
     * Anything else is an object that has been removed or replaced, and whoever walks the modified
     * set would write it out alongside -- or in place of -- whatever the design holds instead.
     */
    public static void assertModifiedAreInDesign(Design design) {
        assertModifiedNetsAreInDesign(design);
        assertModifiedSiteInstsAreInDesign(design);
    }

    /**
     * The half of {@link #assertModifiedAreInDesign(Design)} for Nets.
     */
    public static void assertModifiedNetsAreInDesign(Design design) {
        for (Net net : design.getModifiedNets()) {
            Assertions.assertSame(net, design.getNet(net.getName()),
                    "modified net is not the one the design holds: " + net.getName());
            Assertions.assertSame(design, net.getDesign(), net.getName());
        }
    }

    /**
     * The half of {@link #assertModifiedAreInDesign(Design)} for SiteInsts.
     */
    public static void assertModifiedSiteInstsAreInDesign(Design design) {
        for (SiteInst si : design.getModifiedSiteInsts()) {
            Assertions.assertSame(si, design.getSiteInst(si.getName()),
                    "modified SiteInst is not the one the design holds: " + si.getName());
            Assertions.assertSame(design, si.getDesign(), si.getName());
            if (si.isPlaced()) {
                Assertions.assertSame(si, design.getSiteInstFromSite(si.getSite()),
                        "modified SiteInst is not the one on its site: " + si.getName());
            }
        }
    }
}

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

package com.xilinx.rapidwright.rwroute;

import com.xilinx.rapidwright.design.Design;
import com.xilinx.rapidwright.design.SiteInst;
import com.xilinx.rapidwright.design.SitePinInst;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

public class TestRouterHelper {
    @ParameterizedTest
    @CsvSource({
            "SLICE_X0Y0,COUT,null",
            "SLICE_X0Y299,COUT,null",
            "SLICE_X0Y0,A_O,CLEL_R_X0Y0/CLE_CLE_L_SITE_0_A_O"
    })
    public void testProjectOutputPinToINTNode(String siteName, String pinName, String nodeAsString) {
        Design design = new Design("design", "xcvu3p");
        SiteInst si = design.createSiteInst(siteName);
        SitePinInst spi = new SitePinInst(pinName, si);
        Assertions.assertEquals(nodeAsString, String.valueOf(RouterHelper.projectOutputPinToINTNode(spi)));
    }
}

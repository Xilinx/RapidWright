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

package com.xilinx.rapidwright.router;

import com.xilinx.rapidwright.design.Design;
import com.xilinx.rapidwright.device.Device;
import com.xilinx.rapidwright.device.PIP;
import com.xilinx.rapidwright.support.RapidWrightDCP;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

public class TestRouteThruHelper {
    @ParameterizedTest
    @CsvSource({
            // E_O
            "CLEL_R_X9Y235/CLEL_R.CLE_CLE_L_SITE_0_E1->>CLE_CLE_L_SITE_0_E_O,true",
            "CLEL_R_X9Y235/CLEL_R.CLE_CLE_L_SITE_0_E2->>CLE_CLE_L_SITE_0_E_O,true",
            "CLEL_R_X9Y235/CLEL_R.CLE_CLE_L_SITE_0_E3->>CLE_CLE_L_SITE_0_E_O,true",
            "CLEL_R_X9Y235/CLEL_R.CLE_CLE_L_SITE_0_E4->>CLE_CLE_L_SITE_0_E_O,true",
            "CLEL_R_X9Y235/CLEL_R.CLE_CLE_L_SITE_0_E5->>CLE_CLE_L_SITE_0_E_O,true",
            "CLEL_R_X9Y235/CLEL_R.CLE_CLE_L_SITE_0_E6->>CLE_CLE_L_SITE_0_E_O,true",
            // EMUX
            "CLEL_R_X9Y235/CLEL_R.CLE_CLE_L_SITE_0_E1->>CLE_CLE_L_SITE_0_EMUX,true",
            "CLEL_R_X9Y235/CLEL_R.CLE_CLE_L_SITE_0_E2->>CLE_CLE_L_SITE_0_EMUX,true",
            "CLEL_R_X9Y235/CLEL_R.CLE_CLE_L_SITE_0_E3->>CLE_CLE_L_SITE_0_EMUX,true",
            "CLEL_R_X9Y235/CLEL_R.CLE_CLE_L_SITE_0_E4->>CLE_CLE_L_SITE_0_EMUX,true",
            "CLEL_R_X9Y235/CLEL_R.CLE_CLE_L_SITE_0_E5->>CLE_CLE_L_SITE_0_EMUX,true",
            "CLEL_R_X9Y235/CLEL_R.CLE_CLE_L_SITE_0_E6->>CLE_CLE_L_SITE_0_EMUX,true",

            // Occupied by LUT6_2
            // H_O
            "CLEL_R_X9Y235/CLEL_R.CLE_CLE_L_SITE_0_H1->>CLE_CLE_L_SITE_0_E_O,false",
            "CLEL_R_X9Y235/CLEL_R.CLE_CLE_L_SITE_0_H2->>CLE_CLE_L_SITE_0_E_O,false",
            "CLEL_R_X9Y235/CLEL_R.CLE_CLE_L_SITE_0_H3->>CLE_CLE_L_SITE_0_E_O,false",
            "CLEL_R_X9Y235/CLEL_R.CLE_CLE_L_SITE_0_H4->>CLE_CLE_L_SITE_0_E_O,false",
            "CLEL_R_X9Y235/CLEL_R.CLE_CLE_L_SITE_0_H5->>CLE_CLE_L_SITE_0_E_O,false",
            "CLEL_R_X9Y235/CLEL_R.CLE_CLE_L_SITE_0_H6->>CLE_CLE_L_SITE_0_E_O,false",
            // HMUX
            "CLEL_R_X9Y235/CLEL_R.CLE_CLE_L_SITE_0_H1->>CLE_CLE_L_SITE_0_EMUX,false",
            "CLEL_R_X9Y235/CLEL_R.CLE_CLE_L_SITE_0_H2->>CLE_CLE_L_SITE_0_EMUX,false",
            "CLEL_R_X9Y235/CLEL_R.CLE_CLE_L_SITE_0_H3->>CLE_CLE_L_SITE_0_EMUX,false",
            "CLEL_R_X9Y235/CLEL_R.CLE_CLE_L_SITE_0_H4->>CLE_CLE_L_SITE_0_EMUX,false",
            "CLEL_R_X9Y235/CLEL_R.CLE_CLE_L_SITE_0_H5->>CLE_CLE_L_SITE_0_EMUX,false",
            "CLEL_R_X9Y235/CLEL_R.CLE_CLE_L_SITE_0_H6->>CLE_CLE_L_SITE_0_EMUX,false",

            // Occupied by D6LUT
            "CLEL_R_X9Y235/CLEL_R.CLE_CLE_L_SITE_0_D1->>CLE_CLE_L_SITE_0_D_O,false",
            "CLEL_R_X9Y235/CLEL_R.CLE_CLE_L_SITE_0_D6->>CLE_CLE_L_SITE_0_DMUX,false",

            // Occupied by D5LUT
            "CLEL_R_X10Y239/CLEL_R.CLE_CLE_L_SITE_0_D1->>CLE_CLE_L_SITE_0_D_O,false",
            "CLEL_R_X10Y239/CLEL_R.CLE_CLE_L_SITE_0_D6->>CLE_CLE_L_SITE_0_DMUX,false",

            // Empty SiteInst (CLEL)
            "CLEL_R_X0Y0/CLEL_R.CLE_CLE_L_SITE_0_A1->>CLE_CLE_L_SITE_0_A_O,true",
            "CLEL_R_X0Y0/CLEL_R.CLE_CLE_L_SITE_0_A6->>CLE_CLE_L_SITE_0_AMUX,true",

            // Empty SiteInst (CLEM)
            "CLEM_X1Y0/CLEM.CLE_CLE_M_SITE_0_H1->>CLE_CLE_M_SITE_0_H_O,true",
            "CLEM_X1Y0/CLEM.CLE_CLE_M_SITE_0_H6->>CLE_CLE_M_SITE_0_H_O,true",

            // SiteInst (CLEM) with [FGH]{5,6}LUTs free, but others LUTs occupied
            "CLEM_X9Y238/CLEM.CLE_CLE_M_SITE_0_H1->>CLE_CLE_M_SITE_0_H_O,true", // Technically, the H6LUT and G6LUT are used as GND sources
            "CLEM_X9Y238/CLEM.CLE_CLE_M_SITE_0_H6->>CLE_CLE_M_SITE_0_H_O,true", // for sinks outside the site, so while the PIP may be
                                                                                // available, its end node will not be
            "CLEM_X9Y238/CLEM.CLE_CLE_M_SITE_0_F1->>CLE_CLE_M_SITE_0_F_O,true", // F6LUT is unoccupied and is also not used as a static
            "CLEM_X9Y238/CLEM.CLE_CLE_M_SITE_0_F6->>CLE_CLE_M_SITE_0_F_O,true", // source for the CARRY8

            // SiteInst (CLEL) with a CARRY8
            "CLEL_R_X10Y239/CLEL_R.CLE_CLE_L_SITE_0_A2->>CLE_CLE_L_SITE_0_A_O,false", // A6LUT is used as a GND source for CARRY8.S0
            "CLEL_R_X10Y239/CLEL_R.CLE_CLE_L_SITE_0_A3->>CLE_CLE_L_SITE_0_A_O,false",
            "CLEL_R_X10Y239/CLEL_R.CLE_CLE_L_SITE_0_B4->>CLE_CLE_L_SITE_0_B_O,true",  // B6LUT is not needed by CARRY8.S1
            "CLEL_R_X10Y239/CLEL_R.CLE_CLE_L_SITE_0_B5->>CLE_CLE_L_SITE_0_B_O,true",
    })
    public void testRouteThruPIPAvailable(String pipName, boolean expected) {
        Design design = RapidWrightDCP.loadDCP("picoblaze_ooc_X10Y235.dcp");
        Device device = design.getDevice();
        PIP pip = device.getPIP(pipName);
        Assertions.assertEquals(expected, RouteThruHelper.isRouteThruPIPAvailable(design, pip));

        Assertions.assertEquals(expected, RouteThruHelper.isRouteThruPIPAvailable(design, pip.getStartWire(), pip.getEndWire()));

        Assertions.assertEquals(expected, RouteThruHelper.isRouteThruPIPAvailable(design, pip.getStartNode(), pip.getEndNode()));
    }
}

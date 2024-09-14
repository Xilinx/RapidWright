/*
 * Copyright (c) 2024, Advanced Micro Devices, Inc.
 * All rights reserved.
 *
 * Author: Wenhao Lin, Advanced Micro Devices, Inc.
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

package com.xilinx.rapidwright.design;

import java.util.EnumSet;
import java.util.List;
import java.util.Set;

import com.xilinx.rapidwright.device.SiteTypeEnum;

public class NetTools {
    private static Set<SiteTypeEnum> clkSrcSiteTypeEnums = EnumSet.noneOf(SiteTypeEnum.class);
    static {
        clkSrcSiteTypeEnums.addAll(List.of(
            SiteTypeEnum.BUFGCE,        // All supported series
            SiteTypeEnum.BUFGCTRL,      // All supported series
            SiteTypeEnum.BUFG,          // All supported series
            SiteTypeEnum.BUFGCE_DIV,    // US/US+ and Versal
            SiteTypeEnum.BUFG_GT,       // US/US+ and Versal
            SiteTypeEnum.BUFG_PS,       // US/US+ and Versal
            SiteTypeEnum.BUFG_FABRIC,   // Versal
            SiteTypeEnum.BUFIO,         // Series-7
            SiteTypeEnum.BUFR,          // Series-7
            SiteTypeEnum.BUFHCE,        // Series-7
            SiteTypeEnum.BUFMRCE        // Series-7
        ));
    }

    public static boolean isGlobalClock(Net net) {
        SitePinInst srcSpi = net.getSource();
        if (srcSpi == null)
            return false;        
        
        return clkSrcSiteTypeEnums.contains(srcSpi.getSiteTypeEnum());
    }
}

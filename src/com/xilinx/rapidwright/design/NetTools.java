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

import com.xilinx.rapidwright.device.Series;
import com.xilinx.rapidwright.device.SiteTypeEnum;

public class NetTools {
    private static Set<SiteTypeEnum> commonClkSrcSiteTypeEnums = EnumSet.noneOf(SiteTypeEnum.class);
    static {
        commonClkSrcSiteTypeEnums.addAll(List.of(
            SiteTypeEnum.BUFGCE,
            SiteTypeEnum.BUFGCE_DIV,
            SiteTypeEnum.BUFGCTRL,
            SiteTypeEnum.BUFG_GT,
            SiteTypeEnum.BUFG_PS
        ));
    }
    private static Set<SiteTypeEnum> clkSrcSiteTypeEnumsOfUSOnly = EnumSet.noneOf(SiteTypeEnum.class);
    static {
        clkSrcSiteTypeEnumsOfUSOnly.addAll(List.of(
            SiteTypeEnum.BUFG
        ));
    }

    private static Set<SiteTypeEnum> clkSrcSiteTypeEnumsOfVersalOnly = EnumSet.noneOf(SiteTypeEnum.class);
    static {
        clkSrcSiteTypeEnumsOfVersalOnly.addAll(List.of(
            SiteTypeEnum.BUFG_FABRIC
        ));
    }

    public static boolean isGlobalClock(Net net) {
        Series series = net.getDesign().getDevice().getSeries();
        SitePinInst srcSpi = net.getSource();
        if (srcSpi == null)
            return false;        
        
        if (series == Series.UltraScale || series == Series.UltraScalePlus) {
            return commonClkSrcSiteTypeEnums.contains(srcSpi.getSiteTypeEnum()) || clkSrcSiteTypeEnumsOfUSOnly.contains(srcSpi.getSiteTypeEnum());
        }

        // Not support other series yet
        assert(series == Series.Versal);
        return commonClkSrcSiteTypeEnums.contains(srcSpi.getSiteTypeEnum()) ||  clkSrcSiteTypeEnumsOfVersalOnly.contains(srcSpi.getSiteTypeEnum());
    }
}

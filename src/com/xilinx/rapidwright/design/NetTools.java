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

import java.util.HashSet;
import java.util.List;

import com.xilinx.rapidwright.device.Series;
import com.xilinx.rapidwright.device.SiteTypeEnum;

public class NetTools {
    private static HashSet<String> clkSrcSpiNamesOfUS = new HashSet<>();
    static {
        clkSrcSpiNamesOfUS.add("CLK_OUT");
        clkSrcSpiNamesOfUS.add("CLKOUT");
        clkSrcSpiNamesOfUS.add("CLKFBOUT");
    }

    private static HashSet<SiteTypeEnum> clkSrcSiteTypeEnumsOfVersal = new HashSet<>();
    static {
        clkSrcSiteTypeEnumsOfVersal.addAll(List.of(
            SiteTypeEnum.BUFGCE,
            SiteTypeEnum.BUFG_FABRIC,
            SiteTypeEnum.BUFGCE_DIV,
            SiteTypeEnum.BUFGCTRL,
            SiteTypeEnum.BUFG_GT,
            SiteTypeEnum.BUFG_PS
        ));
    }

    public static boolean isClockNet(Net net) {
        Series series = net.getDesign().getDevice().getSeries();
        SitePinInst srcSpi = net.getSource();
        if (srcSpi == null)
            return false;        
        String srcSpiName = srcSpi.getName();
        
        if (series == Series.UltraScale || series == Series.UltraScalePlus) {
            return clkSrcSpiNamesOfUS.contains(srcSpiName);
        }

        if (series == Series.Versal) {
            return clkSrcSiteTypeEnumsOfVersal.contains(srcSpi.getSiteTypeEnum());
        }
        // fallback
        return net.isClockNet();
    }

    public static boolean isGlobalNet(Net net) {
        return net.isStaticNet() || isClockNet(net);
    }
}

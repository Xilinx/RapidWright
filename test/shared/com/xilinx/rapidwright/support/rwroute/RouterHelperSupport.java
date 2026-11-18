/*
 * Copyright (c) 2024-2025, Advanced Micro Devices, Inc.
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

package com.xilinx.rapidwright.support.rwroute;

import com.xilinx.rapidwright.design.Cell;
import com.xilinx.rapidwright.design.Design;
import com.xilinx.rapidwright.design.NetType;
import com.xilinx.rapidwright.design.SiteInst;
import com.xilinx.rapidwright.design.SitePinInst;
import com.xilinx.rapidwright.design.tools.LUTTools;
import com.xilinx.rapidwright.device.BELPin;
import com.xilinx.rapidwright.edif.EDIFCellInst;
import com.xilinx.rapidwright.edif.EDIFNet;
import com.xilinx.rapidwright.edif.EDIFNetlist;
import com.xilinx.rapidwright.edif.EDIFPortInst;
import com.xilinx.rapidwright.edif.EDIFTools;

import java.util.Set;

public class RouterHelperSupport {
    public static void invertVccLutPinsToGndPins(Design design, Set<SitePinInst> pins) {
        final EDIFNetlist netlist = design.getNetlist();
        for (SitePinInst spi : pins) {
            assert (spi.getNet() == design.getVccNet());
            SiteInst si = spi.getSiteInst();
            for (BELPin bp : spi.getSiteWireBELPins()) {
                if (bp.isSitePort() || bp.getName().charAt(0) != 'A')
                    continue;
                if (bp.getBEL().isLUT()) {
                    Cell lut = si.getCell(bp.getBEL());
                    if (lut != null) {
                        String eq = LUTTools.getLUTEquation(lut);
                        String logInput = lut.getLogicalPinMapping(bp.getName());
                        if (logInput != null) {
                            LUTTools.configureLUT(lut, eq.replace(logInput, "(~" + logInput + ")"));

                            EDIFCellInst eci = lut.getEDIFCellInst();
                            EDIFPortInst epi = eci.getPortInst(logInput);
                            EDIFNet const0 = EDIFTools.getStaticNet(NetType.GND, eci.getParentCell(), netlist);
                            epi.setParentNet(const0);
                        } else {
                            // Doesn't look like this pin is used by this [65]LUT,
                            // could be used by the other [56]LUT
                        }
                    }
                }
            }
            spi.getNet().removePin(spi, true);
            design.getGndNet().addPin(spi, true);
        }
    }
}

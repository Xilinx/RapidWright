/*
 * Copyright (c) 2025, Advanced Micro Devices, Inc.
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

package com.xilinx.rapidwright.eco;

import com.xilinx.rapidwright.design.Cell;
import com.xilinx.rapidwright.design.Design;
import com.xilinx.rapidwright.design.Net;
import com.xilinx.rapidwright.design.SiteInst;
import com.xilinx.rapidwright.design.SitePinInst;
import com.xilinx.rapidwright.design.Unisim;
import com.xilinx.rapidwright.design.blocks.PBlock;
import com.xilinx.rapidwright.device.BEL;
import com.xilinx.rapidwright.device.Site;
import com.xilinx.rapidwright.edif.EDIFCell;
import com.xilinx.rapidwright.edif.EDIFNet;
import com.xilinx.rapidwright.edif.EDIFPort;
import com.xilinx.rapidwright.util.Pair;
import java.util.Iterator;

/**
 * Helper class when instantiating designs in an abstract/DFX shell. LUT1s must
 * be used to tie of any shell outputs.
 */
public class LUT1InsertionTool {

    private Design design;

    private EDIFCell top;

    private PBlock pblock;

    private ECOPlacementHelper placeHelper;

    private Site hint;

    private Iterator<Site> siteIterator;

    private static String netPrefix = "HD_PR_Connection_S_IN_NET_";

    private static String lutPrefix = "HD_PR_Connection_S_IN_BUF_";

    private static String lutProp = "HD.INSERTED";

    public LUT1InsertionTool(Design design, PBlock pblock, Site hint) {
        this.design = design;
        this.pblock = pblock;
        this.hint = hint;
        top = design.getTopEDIFCell();
        placeHelper = new ECOPlacementHelper(design, null);
    }

    /**
     * Creates and places a LUT1 (logically and physically) as a sink on the
     * provided input port. It will also create a logical and physical net inside
     * the top cell.
     * 
     * @param portInstName Name of the input port instance.
     * @param port         The corresponding input port object
     * @param i            If the port is a bus, the specific bus index.
     * @return The LUT1 cell that is created.
     */
    public Cell insertLUT1(String portInstName, EDIFPort port, int i) {
        assert (port.isInput());
        EDIFNet net = top.getInternalNet(portInstName);
        if (net == null) {
            net = top.createNet(netPrefix + portInstName);
        }
        if (port.isBus()) {
            net.createPortInst(port, i);
        } else {
            net.createPortInst(port);
        }
        Pair<Site, BEL> loc = getFreeLUTLocation();
        Cell lut1 = design.createAndPlaceCell(top, lutPrefix + portInstName, Unisim.LUT1, loc.getFirst(),
                loc.getSecond());
        lut1.addProperty("INIT", "2'h2");
        lut1.addProperty(lutProp, true);
        net.createPortInst("I0", lut1);
        String sitePinName = lut1.getBEL().getPin(lut1.getPhysicalPinMapping("I0")).getSiteWireName();
        Net physNet = design.getNet(net.getName());
        if (physNet == null) {
            physNet = design.createNet(net.getName());
        }
        SitePinInst spi = lut1.getSiteInst().getSitePinInst(sitePinName);
        if (spi == null) {
            spi = physNet.createPin(sitePinName, lut1.getSiteInst());
        } else if (spi.getNet() == null) {
            physNet.addPin(spi);
        }
        return lut1;
    }

    /**
     * Finds the next free LUT and returns it.
     * 
     * @return A pair object containing the site and BEL that are available for LUT
     *         placement.
     */
    public Pair<Site, BEL> getFreeLUTLocation() {
        if (siteIterator == null) {
            siteIterator = ECOPlacementHelper.spiralOutFrom(hint, pblock).iterator();
        }
        Pair<Site, BEL> loc = getFreeLUTInSite(hint);
        if (loc == null) {
            while ((loc = getFreeLUTInSite(siteIterator.next())) == null) {
                // Check one site at a time
            }
            hint = loc.getFirst();
        }
        return loc;
    }

    /**
     * Checks a specific site if there are any BELs available for LUT placement.
     * 
     * @param site The site to check.
     * @return An available and compatible LUT placement BEL site, or null if none
     *         are available in this site.
     */
    public Pair<Site, BEL> getFreeLUTInSite(Site site) {
        SiteInst start = design.getSiteInstFromSite(site);
        if (start == null) {
            return new Pair<Site, BEL>(site, site.getBEL("A6LUT"));
        } else {
            BEL bel = placeHelper.getUnusedLUT(start);
            if (bel != null) {
                return new Pair<Site, BEL>(site, bel);
            }
        }
        return null;
    }
}

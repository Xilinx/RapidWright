/*
 * Copyright (c) 2025, Advanced Micro Devices, Inc.
 * All rights reserved.
 *
 * Author: Chris Lavin, AMD Advanced Research and Development.
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

package com.xilinx.rapidwright.design.tools;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.xilinx.rapidwright.design.Cell;
import com.xilinx.rapidwright.design.Design;
import com.xilinx.rapidwright.design.DesignTools;
import com.xilinx.rapidwright.design.Net;
import com.xilinx.rapidwright.design.SiteInst;
import com.xilinx.rapidwright.design.SitePinInst;
import com.xilinx.rapidwright.design.Unisim;
import com.xilinx.rapidwright.design.blocks.PBlock;
import com.xilinx.rapidwright.device.BEL;
import com.xilinx.rapidwright.device.Series;
import com.xilinx.rapidwright.device.Site;
import com.xilinx.rapidwright.eco.ECOPlacementHelper;
import com.xilinx.rapidwright.edif.EDIFCell;
import com.xilinx.rapidwright.edif.EDIFCellInst;
import com.xilinx.rapidwright.edif.EDIFHierNet;
import com.xilinx.rapidwright.edif.EDIFNet;
import com.xilinx.rapidwright.edif.EDIFPort;
import com.xilinx.rapidwright.edif.EDIFPortInst;
import com.xilinx.rapidwright.util.Pair;
import com.xilinx.rapidwright.util.StringTools;

/**
 * Set of tools to add/remove flip flops inline to top level port connections.
 * This is targeted at kernel replication preparation prior to routing to ensure
 * that connections are routed outside of the pblock of the out-of-context
 * kernel.
 * 
 */
public class InlineFlopTools {

    private static final String CLK_OPT = "--clk";
    private static final String PBLOCK_OPT = "--pblock";
    private static final String REMOVE_FLOPS_OPT = "--remove_flops";

    public static final String INLINE_SUFFIX = "_rw_inline_flop";

    /**
     * Add flip flops inline on all the top-level ports of an out-of-context design.
     * This is useful for placed out-of-context kernels prior to routing so that
     * after the flops have been placed, the router is forced to route connections
     * of each of the ports to each of the flops. This can help alleviate congestion
     * when the kernels are placed/relocated in context. Note this assumes the
     * design is not implemented as in most contexts it will be placed and routed
     * immediately following this modification.
     * 
     * @param design  The design to modify
     * @param clkNet  Name of the clock net to use on which to add the flops
     * @param keepOut The pblock used to contain the kernel and the added flops will
     *                not be placed inside this area.
     */
    public static void createAndPlaceFlopsInlineOnTopPorts(Design design, String clkNet, PBlock keepOut) {
        assert (design.getSiteInsts().size() == 0);
        EDIFCell top = design.getTopEDIFCell();
        Site start = keepOut.getAllSites("SLICE").iterator().next(); // TODO this is a bit wasteful
        boolean exclude = true;
        Iterator<Site> siteItr = ECOPlacementHelper.spiralOutFrom(start, keepOut, exclude).iterator();
        siteItr.next(); // Skip the first site, as we are suggesting one inside the pblock

        Net clk = design.getNet(clkNet);

        Set<SiteInst> siteInstsToRoute = new HashSet<>();

        for (EDIFPort port : top.getPorts()) {
            // Don't flop the clock net
            if (port.getName().equals(clkNet)) {
                continue;
            }
            if (port.isBus()) {
                for (int i : port.getBitBlastedIndicies()) {
                    EDIFPortInst inst = port.getInternalPortInstFromIndex(i);
                    Pair<Site, BEL> loc = nextAvailPlacement(design, siteItr);
                    Cell flop = createAndPlaceFlopInlineOnTopPortInst(design, inst, loc, clk);
                    siteInstsToRoute.add(flop.getSiteInst());
                }
            } else {
                EDIFPortInst inst = port.getInternalPortInst();
                Pair<Site, BEL> loc = nextAvailPlacement(design, siteItr);
                Cell flop = createAndPlaceFlopInlineOnTopPortInst(design, inst, loc, clk);
                siteInstsToRoute.add(flop.getSiteInst());
            }
        }
        for (SiteInst si : siteInstsToRoute) {
            si.routeSite();
        }
    }

    private static Pair<Site, BEL> nextAvailPlacement(Design design, Iterator<Site> itr) {
        while (itr.hasNext()) {
            Site curr = itr.next();
            SiteInst candidate = design.getSiteInstFromSite(curr);
            if (candidate == null) {
                // Empty site, let's use it
                return new Pair<Site, BEL>(curr, curr.getBEL("AFF"));
            }
        }
        return null;
    }

    private static Cell createAndPlaceFlopInlineOnTopPortInst(Design design, EDIFPortInst portInst, Pair<Site, BEL> loc,
            Net clk) {
        String name = portInst.getFullName() + INLINE_SUFFIX;
        Cell flop = design.createAndPlaceCell(design.getTopEDIFCell(), name, Unisim.FDRE, loc.getFirst(),
                loc.getSecond());
        Net net = design.createNet(name);
        net.connect(flop, portInst.isInput() ? "D" : "Q");
        design.getGndNet().connect(flop, "R");
        design.getVccNet().connect(flop, "CE");
        clk.connect(flop, "C");
        EDIFNet origNet = portInst.getNet();
        origNet.removePortInst(portInst);
        net.getLogicalNet().addPortInst(portInst);
        Net origPhysNet = design.getNet(origNet.getName());
        if (origPhysNet == null) {
            if (origNet.isGND()) {
                origPhysNet = design.getGndNet();
            } else if (origNet.isVCC()) {
                origPhysNet = design.getVccNet();
            } else {
                origPhysNet = design.createNet(new EDIFHierNet(design.getNetlist().getTopHierCellInst(), origNet));
            }
        }
        origPhysNet.connect(flop, portInst.isInput() ? "Q" : "D");
        return flop;
    }

    /**
     * Removes the inline flops added by
     * {@link #createAndPlaceFlopsInlineOnTopPorts(Design, String, PBlock)}
     * 
     * @param design The current design from which to remove the flops
     */
    public static void removeInlineFlops(Design design) {
        Map<Net, Set<SitePinInst>> pinsToRemove = new HashMap<>();
        List<SiteInst> siteInstToRemove = new ArrayList<>();
        List<EDIFCellInst> cellsToRemove = new ArrayList<>();
        Net vcc = design.getVccNet();
        Set<SitePinInst> vccPins = new HashSet<>();
        pinsToRemove.put(vcc, vccPins);
        String[] staticPins = new String[] { "CKEN1", design.getSeries() == Series.Versal ? "RST" : "SRST1" };
        for (EDIFCellInst inst : design.getTopEDIFCell().getCellInsts()) {
            if (inst.getName().endsWith(INLINE_SUFFIX)) {
                Cell flop = design.getCell(inst.getName());
                SiteInst si = flop.getSiteInst();
                // Assume we only placed one flop per SiteInst
                siteInstToRemove.add(si);
                for (SitePinInst pin : si.getSitePinInsts()) {
                    pinsToRemove.computeIfAbsent(pin.getNet(), p -> new HashSet<>()).add(pin);
                }
                for (String staticPin : staticPins) {
                    if (si.getSitePinInst(staticPin) == null) {
                        vccPins.add(vcc.createPin(staticPin, si));
                    }
                }

                cellsToRemove.add(inst);
            }
        }

        for (SiteInst si : siteInstToRemove) {
            design.removeSiteInst(si);
        }
        DesignTools.batchRemoveSitePins(pinsToRemove, true);

        String[] ctrlPins = new String[] { "C", "R", "CE" };
        EDIFCell top = design.getTopEDIFCell();
        for (EDIFCellInst c : cellsToRemove) {
            // Remove control set pins
            for (String pin : ctrlPins) {
                EDIFPortInst p = c.getPortInst(pin);
                p.getNet().removePortInst(p);
            }
            // Merge 'D' sources and 'Q' sinks, restore original net
            EDIFPortInst d = c.getPortInst("D");
            EDIFNet dNet = d.getNet();
            EDIFPortInst q = c.getPortInst("Q");
            EDIFNet qNet = q.getNet();
            if (dNet.getName().endsWith(INLINE_SUFFIX)) {
                // Input port
                for (EDIFPortInst pi : new ArrayList<>(d.getNet().getPortInsts())) {
                    if (pi.getCellInst() != c) {
                        dNet.removePortInst(pi);
                        qNet.addPortInst(pi);
                    }
                }
                qNet.removePortInst(q);
                top.removeNet(dNet);
            } else {
                // Output port
                for (EDIFPortInst pi : new ArrayList<>(q.getNet().getPortInsts())) {
                    if (pi.getCellInst() != c) {
                        qNet.removePortInst(pi);
                        dNet.addPortInst(pi);
                    }
                }
                top.removeNet(qNet);
                dNet.removePortInst(d);
            }

            top.removeCellInst(c);
            design.removeCell(c.getName());
        }

    }

    public static void main(String[] args) {
        if (args.length < 3 || args.length > 4) {
            System.out.println("USAGE (to add flops)   : <input.dcp> <output.dcp> "+CLK_OPT+"=<clkName> "+PBLOCK_OPT+"=<pblock range(s)>");
            System.out.println("USAGE (to remove flops): <input.dcp> <output.dcp> " + REMOVE_FLOPS_OPT);
            return;
        }
        
        Design d = Design.readCheckpoint(args[0]);

        if (args[2].startsWith(CLK_OPT) || args[2].startsWith(PBLOCK_OPT)) {
            d.unplaceDesign();
            String clkName = StringTools.getOptionValue(CLK_OPT, args);
            String pblockRange = StringTools.getOptionValue(PBLOCK_OPT, args);
            if (clkName == null || pblockRange == null) {
                throw new RuntimeException("ERROR: Missing value(s) for option(s): " 
                        + CLK_OPT + "=" + clkName + ", " + PBLOCK_OPT + "=" + pblockRange);
            }
            PBlock pblock = new PBlock(d.getDevice(), pblockRange);
            createAndPlaceFlopsInlineOnTopPorts(d, clkName, pblock);
        } else if (args[2].equals(REMOVE_FLOPS_OPT)) {
            removeInlineFlops(d);
        } else {
            System.err.println("ERROR: Unrecognized option '" + args[2] +"'");
        }
        
        
        d.writeCheckpoint(args[1]);
    }
}

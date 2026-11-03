/*
 * Copyright (c) 2021-2022, Xilinx, Inc.
 * Copyright (c) 2022-2024, Advanced Micro Devices, Inc.
 * All rights reserved.
 *
 * Author: Eddie Hung, Xilinx Research Labs.
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

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Random;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

import com.xilinx.rapidwright.design.Cell;
import com.xilinx.rapidwright.design.Design;
import com.xilinx.rapidwright.design.DesignTools;
import com.xilinx.rapidwright.design.Net;
import com.xilinx.rapidwright.design.SiteInst;
import com.xilinx.rapidwright.design.SitePinInst;
import com.xilinx.rapidwright.design.Unisim;
import com.xilinx.rapidwright.design.tools.LUTTools;
import com.xilinx.rapidwright.device.Device;
import com.xilinx.rapidwright.device.Node;
import com.xilinx.rapidwright.device.PIP;
import com.xilinx.rapidwright.device.Part;
import com.xilinx.rapidwright.device.PartNameTools;
import com.xilinx.rapidwright.device.Series;
import com.xilinx.rapidwright.eco.ECOTools;
import com.xilinx.rapidwright.edif.EDIFCell;
import com.xilinx.rapidwright.edif.EDIFHierCellInst;
import com.xilinx.rapidwright.edif.EDIFNetlist;
import com.xilinx.rapidwright.edif.EDIFTools;
import com.xilinx.rapidwright.interchange.Interchange;
import com.xilinx.rapidwright.support.LargeTest;
import com.xilinx.rapidwright.support.RapidWrightDCP;
import com.xilinx.rapidwright.util.CodeGenerator;
import com.xilinx.rapidwright.util.FileTools;
import com.xilinx.rapidwright.util.ReportRouteStatusResult;
import com.xilinx.rapidwright.util.VivadoTools;
import com.xilinx.rapidwright.util.VivadoToolsHelper;

public class TestRWRoute {
    private static void assertAllPinsRouted(Net net) {
        Map<SitePinInst, Boolean> sourceRouted = new HashMap<>();
        for (SitePinInst spi : net.getPins()) {
            if (spi.isOutPin()) {
                sourceRouted.put(spi, spi.isRouted());
            } else {
                Assertions.assertTrue(spi.isOutPin() || spi.isRouted());
            }
        }

        // Re-compute the isRouted() state by analyzing from PIPs
        DesignTools.updatePinsIsRouted(net);

        for (SitePinInst spi : net.getPins()) {
            if (spi.isOutPin()) {
                Assertions.assertEquals(sourceRouted.get(spi), spi.isRouted());
            } else {
                Assertions.assertTrue(spi.isRouted());
            }
        }
    }

    public static void assertAllPinsRouted(Design design) {
        for (Net net : design.getNets()) {
            if (net.getSource() == null && !net.isStaticNet()) {
                // Source-less nets may exist in out-of-context design
                continue;
            }
            assertAllPinsRouted(net);
        }
    }

    public static void assertAllSourcesRoutedFlagSet(Design design) {
        for (Net net : design.getNets()) {
            if (net.getSource() == null) {
                // Source-less nets may exist in out-of-context design
                continue;
            }
            for (SitePinInst src : new SitePinInst[] { net.getSource(), net.getAlternateSource() }) {
                if (src == null)
                    continue;
                Assertions.assertTrue(src.isRouted() == isSourceUsed(src));
            }
        }
    }

    private static boolean isSourceUsed(SitePinInst src) {
        Node srcNode = src.getConnectedNode();
        for (PIP p : src.getNet().getPIPs()) {
            if (p.getStartNode().equals(srcNode))
                return true;
        }
        return false;
    }

    /**
     * Tests the non-timing driven full routing, i.e., RWRoute running in its wirelength-driven mode.
     * The bnn design from Rosetta benchmarks is used.
     * It is a small heterogeneous design with CLBs, DSPs and BRAMs.
     * The bnn design does not have any clock nets.
     * This test takes around 15s on a machine with a CPU @ 2.5GHz.
     */
    @Test
    @LargeTest
    public void testNonTimingDrivenFullRouting() {
        Design design = RapidWrightDCP.loadDCP("bnn.dcp");
        RWRoute.routeDesignFullNonTimingDriven(design);
        assertAllSourcesRoutedFlagSet(design);
        assertAllPinsRouted(design);
        VivadoToolsHelper.assertFullyRouted(design);
    }

    /**
     * Tests the non-timing driven full routing with LUT pin swapping enabled.
     */
    @ParameterizedTest
    @ValueSource(strings = {
            "bnn.dcp",
            "optical-flow.dcp"
    })
    @LargeTest(max_memory_gb = 8)
    public void testNonTimingDrivenFullRoutingWithLutPinSwapping(String path) {
        Design design = RapidWrightDCP.loadDCP(path);
        RWRoute.routeDesignWithUserDefinedArguments(design, new String[] {"--nonTimingDriven", "--lutPinSwapping"});
        assertAllSourcesRoutedFlagSet(design);
        assertAllPinsRouted(design);
        VivadoToolsHelper.assertFullyRouted(design);
    }

    /**
     * Tests the timing-driven full routing with LUT pin swapping enabled. Both designs have LUTs that are
     * routethrus into a CARRY8, whose connections have alternate sinks but whose timing edges go to the
     * CARRY8 rather than to a LUT input. bnn.dcp also has distributed RAMs on A-G LUTs whose address pins are
     * also fed (as their WA) by H site pins, so the connections to their own site pins must be given their timing
     * edges rather than those of the H LUTs.
     */
    @ParameterizedTest
    @ValueSource(strings = {
            "bnn.dcp",
            "optical-flow.dcp"
    })
    @LargeTest(max_memory_gb = 8)
    public void testTimingDrivenFullRoutingWithLutPinSwapping(String path) {
        Design design = RapidWrightDCP.loadDCP(path);
        RWRoute.routeDesignWithUserDefinedArguments(design, new String[] {"--lutPinSwapping"});
        assertAllSourcesRoutedFlagSet(design);
        assertAllPinsRouted(design);
        VivadoToolsHelper.assertFullyRouted(design);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "bnn.dcp",
            "optical-flow.dcp"
    })
    @LargeTest(max_memory_gb = 8)
    public void testNonTimingDrivenFullRoutingWithLutRoutethru(String path) {
        Design design = RapidWrightDCP.loadDCP(path);
        RWRoute.routeDesignWithUserDefinedArguments(design, new String[] {"--nonTimingDriven", "--lutRoutethru"});
        assertAllSourcesRoutedFlagSet(design);
        assertAllPinsRouted(design);
        VivadoToolsHelper.assertFullyRouted(design);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "bnn.dcp",          // does not activate HUS
            "optical-flow.dcp"  // activates HUS
    })
    @LargeTest(max_memory_gb = 8)
    public void testNonTimingDrivenFullRoutingWithHUS(String path) {
        Design design = RapidWrightDCP.loadDCP(path);
        RWRoute.routeDesignWithUserDefinedArguments(design, new String[] {"--nonTimingDriven", "--hus"});
        assertAllSourcesRoutedFlagSet(design);
        assertAllPinsRouted(design);
        VivadoToolsHelper.assertFullyRouted(design);
    }

    @ParameterizedTest
    @CsvSource({
            "bnn.dcp,false,false",
            "bnn.dcp,false,true",
            "bnn.dcp,true,false",
            "optical-flow.dcp,false,false",
            "optical-flow.dcp,true,true"
    })
    @LargeTest(max_memory_gb = 8)
    public void testFullRoutingWithCUFR(String path, boolean timingDriven, boolean enlargeBoundingBox) {
        Design design = RapidWrightDCP.loadDCP(path);
        CUFR.routeDesignWithUserDefinedArguments(design, new String[]{
                timingDriven ? "--timingDriven" : "--nonTimingDriven",
                enlargeBoundingBox ? "--enlargeBoundingBox" : "--fixBoundingBox",
                "--verbose"
        });
        assertAllSourcesRoutedFlagSet(design);
        assertAllPinsRouted(design);
        VivadoToolsHelper.assertFullyRouted(design);
    }

    @Test
    @LargeTest(max_memory_gb = 8)
    public void testNonTimingDrivenPartialCUFR() {
        Design design = RapidWrightDCP.loadDCP("picoblaze_partial.dcp");
        design.setTrackNetChanges(true);

        boolean softPreserve = false;
        PartialCUFR.routeDesignWithUserDefinedArguments(design, new String[]{
                "--fixBoundingBox",
                "--useUTurnNodes",
                "--nonTimingDriven",
        }, null, softPreserve);

        Assertions.assertFalse(design.getModifiedNets().isEmpty());
        for (Net net : design.getModifiedNets()) {
            assertAllPinsRouted(net);
        }
        VivadoToolsHelper.assertFullyRouted(design);
    }

    /**
     * Tests the timing driven full routing, i.e., RWRoute running in timing-driven mode.
     * The bnn design from Rosetta benchmarks is used.
     * It is a small heterogeneous design with CLBs, DSPs and BRAMs.
     * The bnn design does not have any clock nets.
     * In this test, the default {@link RWRouteConfig} options are used. We do not provide DSP logic delays
     * for the timing-driven routing to test the fallback when DSP timing data is missing.
     * This test takes around 20s on a machine with a CPU @ 2.5GHz.
     */
    @Test
    @LargeTest
    public void testTimingDrivenFullRouting() {
        Design design = RapidWrightDCP.loadDCP("bnn.dcp");
        RWRoute.routeDesignFullTimingDriven(design);
        assertAllSourcesRoutedFlagSet(design);
        assertAllPinsRouted(design);
        VivadoToolsHelper.assertFullyRouted(design);
    }

    /**
     * Tests the non-timing driven full routing with a design that has a global net.
     * The optical-flow design from Rosetta benchmarks is used.
     * It is the largest heterogeneous design from the Rosetta benchmark set, and has
     * a global enable net.
     * This test takes around 3 minutes on a machine with a CPU @ 2.5GHz.
     */
    @Test
    @LargeTest(max_memory_gb = 8)
    public void testNonTimingDrivenFullRoutingWithGlobalNet() {
        Design design = RapidWrightDCP.loadDCP("optical-flow.dcp");
        RWRoute.routeDesignFullNonTimingDriven(design);
        assertAllSourcesRoutedFlagSet(design);
        assertAllPinsRouted(design);
        VivadoToolsHelper.assertFullyRouted(design);
    }

    /**
     * Tests the non-timing driven partial routing, i.e., RWRoute running in its wirelength-driven partial routing mode.
     * The picoblaze design is from one of the RapidWright tutorials with nets between computing kernels not routed.
     * Other nets within each kernel are fully routed.
     * This test takes around 40s on a machine with a CPU @ 2.5GHz.
     */
    @Test
    @LargeTest(max_memory_gb = 8)
    public void testNonTimingDrivenPartialRouting() {
        Design design = RapidWrightDCP.loadDCP("picoblaze_partial.dcp");
        design.setTrackNetChanges(true);

        // Pseudo-randomly unroute some pins from a multi-pin net
        Random random = new Random(0);
        for (Net net : design.getNets()) {
            if (!net.getName().endsWith("/processor/t_state_0")) {
                continue;
            }

            List<SitePinInst> sinkPins = net.getSinkPins();
            Assertions.assertTrue(sinkPins.size() > 1);
            SitePinInst spi = sinkPins.get(random.nextInt(sinkPins.size()));
            DesignTools.unroutePins(net, Collections.singletonList(spi));
        }

        boolean softPreserve = false;
        Design routed = PartialRouter.routeDesignPartialNonTimingDriven(design, null, softPreserve);

        Assertions.assertFalse(routed.getModifiedNets().isEmpty());
        for (Net net : routed.getModifiedNets()) {
            assertAllPinsRouted(net);
        }
        VivadoToolsHelper.assertFullyRouted(design);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    public void testNonTimingDrivenRoutingOnVersalDevice(boolean partial) {
        // Note: there are no global clocks in this design, just a local clock that doesn't use a BUFG
        Design design = RapidWrightDCP.loadDCP("picoblaze_2022.2.dcp");
        design.setTrackNetChanges(true);

        Net Z_NET = design.createNet(Net.Z_NET);
        int expectedNetsModified = 291;
        if (partial) {
            // Pseudo-randomly unroute at least one pin from each net
            Random random = new Random(0);
            for (Net net : design.getNets()) {
                List<SitePinInst> sinkPins = net.getSinkPins();
                if (sinkPins.isEmpty()) {
                    continue;
                }

                if (net.getName().equals("processor/address_loop[6].output_data.pc_vector_mux_lut/O6")) {
                    // For one hand chosen net, unroute it entirely and block
                    // all of its primary output's downhill PIPs such that 
                    // the alternate output must be used
                    net.unroute();
                    Node sourcePinNode = net.getSource().getConnectedNode();        // CLE_W_CORE_X50Y6/CLE_SLICEL_TOP_0_D_O_PIN
                    Node sourceNode = sourcePinNode.getAllDownhillNodes().get(0);   // CLE_W_CORE_X50Y6/CLE_SLICEL_TOP_0_D_O
                    design.setTrackingChanges(false);
                    for (PIP pip : sourceNode.getAllDownhillPIPs()) {
                        Z_NET.addPIP(pip);
                    }
                    design.setTrackingChanges(true);
                }

                Collections.shuffle(sinkPins, random);
                int numPinsToUnroute = random.nextInt(sinkPins.size()) + 1;
                List<SitePinInst> sinkPinsToUnroute = sinkPins.subList(0, numPinsToUnroute);
                DesignTools.unroutePins(net, sinkPinsToUnroute);
            }

            boolean softPreserve = false;
            PartialRouter.routeDesignPartialNonTimingDriven(design, null, softPreserve);
        } else {
            RWRoute.routeDesignFullNonTimingDriven(design);
        }

        Assertions.assertEquals(expectedNetsModified, design.getModifiedNets().size());
        for (Net net : design.getModifiedNets()) {
            assertAllPinsRouted(net);
        }

        if (FileTools.isVivadoOnPath()) {
            ReportRouteStatusResult rrs = VivadoTools.reportRouteStatus(design);
            Assertions.assertEquals(290, rrs.fullyRoutedNets);
            Assertions.assertEquals(0, rrs.netsWithRoutingErrors);
            Assertions.assertEquals(8, rrs.unroutedNets); // There are 8 nets driven from a blackbox cell;
                                                                    // these are marked as routable despite not being so
        }
    }

    /**
     * Tests timing driven partial routing.
     * The picoblaze design is from one of the RapidWright tutorials with nets between computing kernels not routed.
     * Other nets within each kernel are fully routed.
     */
    @Test
    @LargeTest
    @Disabled("Blocked on TimingGraph.build() being able to build partial graphs")
    public void testTimingDrivenPartialRouting() {
        Design design = RapidWrightDCP.loadDCP("picoblaze_partial.dcp");
        design.setTrackNetChanges(true);

        boolean softPreserve = false;
        Design routed = PartialRouter.routeDesignPartialTimingDriven(design, null, softPreserve);

        Assertions.assertFalse(routed.getModifiedNets().isEmpty());
        for (Net net : routed.getModifiedNets()) {
            assertAllPinsRouted(net);
        }
        VivadoToolsHelper.assertFullyRouted(design);
    }

    /**
     * Gets the cost of a route under the lower-bound model (see getLowerBound()): for every node after the source,
     * its base cost (RouteNode.getBaseCost()) plus 0.1 per half tile of its nominal wirelength
     * (RouteNode.getHalfTileWirelength()). That is RWRoute's own cost of an uncongested, unshared, non-timing-driven
     * connection at the default wirelength weight (0.8), less its bias cost.
     * @param route The route's nodes, from source to sink.
     * @return That cost.
     */
    static float getModelCost(List<RouteNode> route) {
        float cost = 0;
        for (RouteNode rnode : route.subList(1, route.size())) {
            cost += rnode.getBaseCost() + 0.1f * rnode.getHalfTileWirelength();
        }
        return cost;
    }

    /**
     * Gets the least cost, under the model of getModelCost(), of a route between two INT tile half tiles made of the
     * moves below (given whether the source pin can launch a long itself): within an SLR and away from the top and
     * bottom of the device, it depends on the distance between them alone. It is an intended lower bound on the cost
     * of any real route between them, which it is wherever those moves cover every cheaper way to travel, as checked
     * on the connections listed below, but not for every source and sink pin (see the known gaps below). The route is
     * taken as a chain of moves on a regular grid of half tiles (horizontally) and rows (vertically), then one of a few
     * ways into the sink. A move is a wire in one of the four directions, at the cost of the wire (base cost and
     * nominal wirelength, as getModelCost()):
     * <ul>
     * <li>a single, double or quad, plus the SDQ node that it leaves from. A vertical one travels its nominal number
     * of rows (UltraScale's NN5/SS5 quads 5); a horizontal one reaches the tile its nominal length away (1 tile for a
     * single or double, 2 for a quad) and lands on either half of it, so 1 to 3 (3 to 5 for a quad) half tiles. On
     * Versal, the SDQ node may also move the wire one row up or down for free (a two-row SDQ node,
     * OUT_[NESW]NODE_*).</li>
     * <li>a long (vertically 12 or 16 rows on UltraScale, 12 on UltraScale+, 7 or 12 on Versal; horizontally 6 tiles
     * on UltraScale(+), 3 or 5 on Versal, landing on either half). On UltraScale it leaves from an SDQ node
     * (INT_NODE_QUAD_LONG_*), charged too; on UltraScale+ and Versal only from the end of another wire, or from a
     * Versal flop output ([A-H]Q, [A-H]Q2).</li>
     * <li>on UltraScale, one row further or shorter than nominal, at the same cost, as its row feedthroughs allow
     * (measured on xcku115's INT_X13Y150, and the same in 48 INT tiles at least 6 rows from a clock row on each of
     * xcku040, xcku115 and xcvu095). The feedthrough member of a family (one BEG index per INT tile, whose node
     * has a *_BLN_* or *_BLS_* wire one row beyond its END) can be left there: vertically SS1_[EW]_BEG0 2 rows,
     * NN2_[EW]_BEG7 3, NN5_BEG7 6, SS12_BEG0 12 or 13 and NN16_BEG3 16 or 17; horizontally EE1_E_BEG0 and EE4_BEG0 or
     * EE12_BEG0 a row south, EE2_[EW]_BEG7 and EE4_BEG15 or WW12_BEG7 a row north. And an SDQ node feedthrough
     * (SDND[NS][EW]_[EW]_0_FTS or _15_FTN, QLND*), driven in one row by a wire end, CLE output or Laguna RXD, also
     * launches wires from the next row: northbound NN16_BEG3 (15 or 16 rows on) from the row south, southbound SS4_BEG0
     * (3) and SS12_BEG0 (11 or 12) from the row north; horizontally WW1_W_BEG7, INT_INT_SINGLE_55_INT_OUT and
     * WW12_BEG7 from the row south, WW2_[EW]_BEG0 and WW4_BEG0 from the row north. So on UltraScale a horizontal single
     * may land a row south, a double a row north, and a quad or long either (as may an UltraScale+ horizontal quad or
     * long, from its feedthrough member).</li>
     * </ul>
     * The ways into the sink are an INODE and the sink pin; on UltraScale, an INODE feedthrough one row up or down
     * instead of the INODE; on UltraScale+, a GNODE and such a feedthrough; on Versal, a BNODE and an INODE one half
     * tile across. But not straight after an UltraScale vertical quad or long, or horizontal long: those drive only SDQ
     * nodes, which drive only wires (in every INT tile of xcku040 and xcku115), so another move must follow (as before
     * an UltraScale crossing's INODE). Each relaxes what a real route must do (anything else it must pay for, such as
     * bounces, only costs more), so that this is a lower bound if those moves cover every cheaper way to travel. An
     * exact search (under the same costs) found no cheaper route for any of 3,432 connections on xcvu9p, xcku040 and
     * xcvc1902, in every direction from LUT and flop outputs, nor for any of 3,600 on xcku115 from five LUT and flop
     * outputs (AQ, A_O, DQ2, EQ2, HQ) to 24 LUT inputs 1 to 17 rows away: it is known to be a lower bound for those
     * connections only. testCostWithinLowerBoundNonTimingDriven()'s rows are among those whose cheapest route attains
     * it (but for three on xcku040; see there). It is loose where moves cannot follow each other as freely as here:
     * which SDQ nodes and INODE feedthroughs a wire's end reaches depends on the member (BEG index) of its family, e.g.
     * on xcku040 and xcku115 the INODE feedthroughs that NN2_[EW]_BEG7 (3 rows) reaches lead back a row south, and
     * those that NN2_[EW]_BEG6 reaches lead a row north.
     * <p>
     * It is not a lower bound for every source and sink pin. Known gaps, where a route can be cheaper (each also a
     * place where RWRoute's own view of the wire, RouteNodeInfo.get(), differs from these moves), from an exact
     * search of 784 connections on xcvu9p from 12 source pins of SLICE_X130Y450 to sinks up to 9 rows away in its
     * column, 47 of which were cheaper:
     * <ul>
     * <li>UltraScale+'s NN4_[EW]_BEG7 and SS4_[EW]_BEG0 travel 5 rows at a quad's cost (where RouteNodeInfo ends them):
     * e.g. EQ, INT_NODE_SDQ_43_INT_OUT1, NN4_E_BEG7, INT_NODE_SDQ_0_INT_OUT1, NN2_E_BEG0, an INODE and A6, 7 rows for
     * 3.6 against a bound of 4.6.</li>
     * <li>UltraScale+'s INODE feedthroughs (INODE_[EW]_*_FT[01]) enter some sink pins one row beyond a wire's end:
     * e.g. A_O, INT_NODE_SDQ_22_INT_OUT1, SS2_E_BEG0, INODE_E_5_FT1 and H2, 3 rows for 2.2 against 3.0.</li>
     * <li>UltraScale+'s SDQ node feedthroughs (SDQNODE_[EW]_*_FT[01]), driven by some CLE outputs, launch wires from
     * the next row: e.g. CQ2, SDQNODE_E_95_FT0, NN1_E_BEG0, an INODE and A4, 2 rows for 1.8 against 2.0.</li>
     * </ul>
     * Others make it looser than RWRoute's view, which is harmless: on UltraScale+ a vertical quad or long end entering
     * an INODE (in every INT tile of xcvu9p they drive only SDQ nodes and longs) and any wire end launching a long (in
     * xcvu9p's INT_X83Y450, only quad and long ends do); on UltraScale, SS12_BEG0 13 rows and EE1_E_BEG0, EE4_BEG0 and
     * EE12_BEG0 a row south, which the fabric has but RouteNodeInfo does not end them at. Not modelled or checked: INT
     * tiles next to a clock row, and longs near the edge of the device, which are shorter.
     * <p>
     * For a source and sink in different SLRs, no wire crosses an SLR boundary; the only way across is a crossing move
     * (see {@link #getCrossingCost}): from the INT tile half beside a column of super long lines that cross SLRs
     * (RouteNodeGraph.nextCrossingHalfColumn), in a row from which they leave (the
     * {@link RouteNodeGraph#SUPER_LONG_LINE_LENGTH_IN_TILES} rows of an SLR next to its boundary), to the same half
     * that many rows away in the next SLR, at the cost of the whole chain: on UltraScale(+), an INODE, the IMUX into
     * the Laguna tile, its TXOUT, the super long line and the receiving site's RXD and RXD; on Versal, a BNODE (reached
     * from a CLE output or a wire end; from anywhere else only after an INODE and a bounce too, which cost more), the
     * SLL input, the super long line, LAG_OUT and the CLE flop route-through it lands on (LAG pin, [A-H]Q2 pin and
     * output). The landing is then a source of its own: an RXD drives SDQ nodes (and on UltraScale INODEs), as a LUT
     * output does, and the Versal flop output launches longs. On UltraScale the crossing's INODE, as the sink's,
     * cannot directly follow a vertical quad or long or a horizontal long. An exact search found no cheaper route for the existing SLR-crossing tests' connections (and
     * testCostWithinLowerBoundNonTimingDriven()'s), all of whose cheapest routes cross this way, nor for 1,533 more on
     * xcku115.
     * @param series The device's series.
     * @param graph The routing graph (for the SLRs and the crossing columns).
     * @param srcHalfX Half-tile X coordinate of the source.
     * @param srcY INT tile Y coordinate of the source.
     * @param sinkHalfX Half-tile X coordinate of the sink.
     * @param sinkY INT tile Y coordinate of the sink.
     * @param sourceLaunchesLongs Whether the source pin drives longs directly (a Versal flop output).
     * @return That least cost.
     */
    static float getLowerBound(Series series, RouteNodeGraph graph, int srcHalfX, int srcY, int sinkHalfX, int sinkY,
                               boolean sourceLaunchesLongs) {
        // In tenths, so that all arithmetic is exact
        final int sdq = 4;
        final int local = 4;
        final int lng = 8;
        int[] vLocal;
        int[] vLong;
        // UltraScale's vertical moves one row further or shorter than nominal (see above): {rows (north positive),
        // nominal rows of its family}
        int[][] vExtra = new int[0][];
        // Horizontal longs: {tiles, nominal half-tile wirelength}
        int[][] hLong;
        // Rows over (min, max; north positive) that a horizontal single, double, quad and long may land (see above)
        int[][] hRows = {{0, 0}, {0, 0}, {0, 0}, {0, 0}};
        boolean longFromSdq = false;
        boolean shift = false;
        // Ways into the sink: {half tiles, rows, cost}
        int[][] ends;
        switch (series) {
            case UltraScale:
                vLocal = new int[] {1, 2, 4, 5};
                vLong = new int[] {12, 16};
                vExtra = new int[][] {{-2, 1}, {3, 2}, {-3, 4}, {6, 5}, {-11, 12}, {-13, 12}, {15, 16}, {17, 16}};
                hLong = new int[][] {{6, 12}};
                hRows = new int[][] {{-1, 0}, {0, 1}, {-1, 1}, {-1, 1}};
                longFromSdq = true;
                ends = new int[][] {{0, 0, 8}, {0, 1, 10}, {0, -1, 10}};
                break;
            case UltraScalePlus:
                vLocal = new int[] {1, 2, 4};
                vLong = new int[] {12};
                hLong = new int[][] {{6, 12}};
                // The feedthrough member of each horizontal quad and long family (RouteNodeInfo) can also be left one
                // row over (e.g. WW4_E_BEG7)
                hRows = new int[][] {{0, 0}, {0, 0}, {-1, 1}, {-1, 1}};
                ends = new int[][] {{0, 0, 8}};
                break;
            case Versal:
                vLocal = new int[] {1, 2, 4};
                vLong = new int[] {7, 12};
                hLong = new int[][] {{3, 6}, {5, 10}};
                shift = true;
                ends = new int[][] {{0, 0, 8}, {1, 0, 12}, {-1, 0, 12}};
                break;
            default:
                throw new RuntimeException("Unsupported series " + series);
        }
        // Horizontal singles, doubles and quads: {tiles, nominal half-tile wirelength}
        final int[][] hLocal = {{1, 1}, {1, 2}, {2, 4}};
        // An UltraScale vertical quad or long, or horizontal long, drives only SDQ nodes, which drive only wires (as
        // found in every INT tile of xcku040 and xcku115; a horizontal quad drives GNODEs too, which drive INODEs and
        // IMUXes), so it lands at no wire end of its own (plane 0): from there, another move must follow before an
        // INODE (into the sink or a crossing)
        final boolean quadLongDrivesNoInode = series == Series.UltraScale;
        final int quadLongEnd = quadLongDrivesNoInode ? 0 : 1;
        // Moves: {half tiles, rows, cost, 1 if it may only leave from the end of a wire, 1 if it lands at the end of a
        // wire (else 0: an UltraScale vertical quad or long, or horizontal long)}
        List<int[]> moves = new ArrayList<>();
        for (int[] v : vExtra) {
            // UltraScale only: from an SDQ node, as vLocal and vLong, landing on either half if a quad or long
            int r = v[1];
            int maxSide = (r >= 4) ? 1 : 0;
            for (int side = -maxSide; side <= maxSide; side++) {
                moves.add(new int[] {side, v[0], sdq + (r >= 12 ? lng : local) + 2 * r, 0, (r >= 4) ? quadLongEnd : 1});
            }
        }
        for (int sign = -1; sign <= 1; sign += 2) {
            for (int sh = shift ? -1 : 0; sh <= (shift ? 1 : 0); sh++) {
                // A vertical wire's wirelength is 2 half tiles per row, a horizontal one's 1 per half tile. An
                // UltraScale quad lands on the half of its INT tile given by its index, so on either
                for (int r : vLocal) {
                    int maxSide = (series == Series.UltraScale && r >= 4) ? 1 : 0;
                    for (int side = -maxSide; side <= maxSide; side++) {
                        moves.add(new int[] {side, sign * r + sh, sdq + local + 2 * r, 0, (r >= 4) ? quadLongEnd : 1});
                    }
                }
                for (int i = 0; i < hLocal.length; i++) {
                    int[] th = hLocal[i];
                    for (int dx = 2 * th[0] - 1; dx <= 2 * th[0] + 1; dx++) {
                        for (int row = hRows[i][0]; row <= hRows[i][1]; row++) {
                            moves.add(new int[] {sign * dx, sh + row, sdq + local + th[1], 0, 1});
                        }
                    }
                }
            }
            for (int r : vLong) {
                // An UltraScale(+) long lands on the half of its INT tile given by its index (see RouteNodeGraph), so
                // on either
                int maxSide = (series == Series.Versal) ? 0 : 1;
                for (int side = -maxSide; side <= maxSide; side++) {
                    moves.add(longFromSdq ? new int[] {side, sign * r, sdq + lng + 2 * r, 0, quadLongEnd}
                                          : new int[] {side, sign * r, lng + 2 * r, 1, 1});
                }
            }
            for (int[] th : hLong) {
                for (int dx = 2 * th[0] - 1; dx <= 2 * th[0] + 1; dx++) {
                    for (int row = hRows[3][0]; row <= hRows[3][1]; row++) {
                        moves.add(longFromSdq ? new int[] {sign * dx, row, sdq + lng + th[1], 0, quadLongEnd}
                                              : new int[] {sign * dx, row, lng + th[1], 1, 1});
                    }
                }
            }
        }
        // Crossings, if the source and sink are in different SLRs
        final boolean crossing = graph.intYToSLRIndex[srcY] != graph.intYToSLRIndex[sinkY];
        final int sllLength = crossing ? graph.SUPER_LONG_LINE_LENGTH_IN_TILES : 0;
        // {cost, plane of the landing}
        final int[] crossingCost = crossing ? getCrossingCost(series, sllLength) : null;
        // Dijkstra over (half tile, row, at the end of a wire, at a CLE output), with a margin of the longest move
        // around the source and the sink (across the whole device if it must cross SLRs, for the crossing columns)
        final int margin = 18;
        int minX = Math.min(srcHalfX, sinkHalfX) - margin;
        int maxX = Math.max(srcHalfX, sinkHalfX) + margin;
        int minY = Math.min(srcY, sinkY) - margin;
        int maxY = Math.max(srcY, sinkY) + margin;
        if (crossing) {
            // A super long line may overshoot the sink (or come back to it), by up to its length
            minX = 0;
            maxX = graph.nextCrossingHalfColumn.length - 1;
            minY -= sllLength;
            maxY += sllLength;
        }
        minY = Math.max(minY, 0);
        maxY = Math.min(maxY, graph.intYToSLRIndex.length - 1);
        final int width = maxX - minX + 1;
        final int height = maxY - minY + 1;
        final int plane = width * height;
        // Planes: 1 if at the end of a wire (not an UltraScale quad or long), plus 2 if at a CLE output (the source, or
        // an UltraScale or Versal crossing's landing)
        int[] dist = new int[4 * plane];
        Arrays.fill(dist, Integer.MAX_VALUE);
        // What it costs to finish from each state, by a way into the sink (or -1 if none)
        int[] endCost = new int[4 * plane];
        Arrays.fill(endCost, -1);
        for (int[] end : ends) {
            int x = sinkHalfX - end[0];
            int y = sinkY - end[1];
            if (x < minX || x > maxX || y < minY || y > maxY ||
                    graph.intYToSLRIndex[y] != graph.intYToSLRIndex[sinkY]) {
                // Off the grid, or an INODE feedthrough across an SLR boundary
                continue;
            }
            // (Not after an UltraScale quad or long, whose INODE needs another move)
            for (int p = quadLongDrivesNoInode ? 1 : 0; p < 4; p++) {
                int i = p * plane + (y - minY) * width + (x - minX);
                endCost[i] = (endCost[i] == -1) ? end[2] : Math.min(endCost[i], end[2]);
            }
        }
        int best = Integer.MAX_VALUE;
        PriorityQueue<int[]> queue = new PriorityQueue<>((a, b) -> Integer.compare(a[0], b[0]));
        int start = (2 + (sourceLaunchesLongs ? 1 : 0)) * plane + (srcY - minY) * width + (srcHalfX - minX);
        dist[start] = 0;
        queue.add(new int[] {0, start});
        while (!queue.isEmpty()) {
            int[] e = queue.poll();
            int d = e[0];
            int i = e[1];
            if (d >= best) {
                // Every state left costs at least this much already, so none can finish more cheaply
                break;
            }
            if (d > dist[i]) {
                continue;
            }
            if (endCost[i] != -1) {
                best = Math.min(best, d + endCost[i]);
            }
            int p = i / plane;
            boolean wireEnd = (p & 1) == 1;
            int x = (i % plane) % width + minX;
            int y = (i % plane) / width + minY;
            int slr = graph.intYToSLRIndex[y];
            for (int[] m : moves) {
                if (m[3] == 1 && !wireEnd) {
                    continue;
                }
                int nx = x + m[0];
                int ny = y + m[1];
                if (nx < minX || nx > maxX || ny < minY || ny > maxY || graph.intYToSLRIndex[ny] != slr) {
                    // Off the grid, or a wire across an SLR boundary
                    continue;
                }
                int ni = m[4] * plane + (ny - minY) * width + (nx - minX);
                if (d + m[2] < dist[ni]) {
                    dist[ni] = d + m[2];
                    queue.add(new int[] {dist[ni], ni});
                }
            }
            if (crossing && graph.nextCrossingHalfColumn[x] == x) {
                // A crossing to the next SLR north or south, if this row is close enough to its boundary for the
                // super long line to land in it
                for (int dir = -1; dir <= 1; dir += 2) {
                    int ny = y + dir * sllLength;
                    if (ny < minY || ny > maxY || graph.intYToSLRIndex[ny] != slr + dir) {
                        continue;
                    }
                    if (quadLongDrivesNoInode && p == 0) {
                        // Its INODE needs another move after an UltraScale quad or long
                        continue;
                    }
                    int ni = crossingCost[1] * plane + (ny - minY) * width + (x - minX);
                    if (d + crossingCost[0] < dist[ni]) {
                        dist[ni] = d + crossingCost[0];
                        queue.add(new int[] {dist[ni], ni});
                    }
                }
            }
        }
        return best / 10f;
    }

    /**
     * Gets the cost of crossing an SLR (see getLowerBound()), in tenths: a super long line of the given length costs
     * {@link RouteNode#BASE_COST_PER_TILE} plus 0.2 of wirelength per tile.
     * @param series The device's series.
     * @param sllLength Length of a super long line, in rows.
     * @return {cost, plane of the landing (1 if at the end of a wire, plus 2 if at a CLE output)}.
     */
    private static int[] getCrossingCost(Series series, int sllLength) {
        final int sll = 3 * sllLength;
        switch (series) {
            case UltraScale:
                // INODE, IMUX, TXOUT; super long line; RXD site, RXD (which drives SDQ nodes and INODEs, as a LUT
                // output does: e.g. LAGUNA_TILE_X13Y300/RXD19 drives INT_X13Y300/INT_NODE_IMUX_122_INT_OUT)
                return new int[] {12 + sll + 8, 2};
            case UltraScalePlus:
                // INODE, IMUX, TXOUT; super long line; RXD site, RXD (which drives SDQ nodes, as a LUT output does)
                return new int[] {12 + sll + 8, 0};
            case Versal:
                // BNODE (reached from a CLE output, or from a wire landing beside it; otherwise after an INODE and a
                // bounce, which only cost more), SLL input; super long line; LAG_OUT, LAG pin, [A-H]Q2? pin and output
                // (a flop output, which launches longs)
                return new int[] {8 + sll + 16, 3};
            default:
                throw new RuntimeException("Unsupported series " + series);
        }
    }

    /**
     * Checks if a source pin drives longs directly, as Versal flop outputs ([A-H]Q, [A-H]Q2) do (for
     * getLowerBound()).
     * @param series The device's series.
     * @param sourcePinName Name of the source pin.
     * @return True if so.
     */
    static boolean isLongLaunchingSource(Series series, String sourcePinName) {
        return series == Series.Versal && sourcePinName.matches("[A-H]Q2?");
    }

    /**
     * Routes a single connection non-timing-driven (as PartialRouter.routeDesignPartialNonTimingDriven()), between two
     * pins of different SLICEs, each given as "SITE.PIN".
     * @return The router, whose only connection holds the route (see getRoute()).
     */
    static PartialRouter routeSingleConnection(String partName, String srcSitePin, String dstSitePin) {
        Design design = new Design("top", partName);
        Net net = design.createNet("net");
        String[] src = srcSitePin.split("\\.");
        net.createPin(src[1], design.createSiteInst(src[0]));
        String[] dst = dstSitePin.split("\\.");
        SitePinInst dstSpi = net.createPin(dst[1], design.createSiteInst(dst[0]));
        PartialRouter router = new PartialRouter(design, new RWRouteConfig(new String[] {"--fixBoundingBox",
                "--useUTurnNodes", "--nonTimingDriven", "--verbose"}), Collections.singletonList(dstSpi), false);
        RWRoute.routeDesign(router);
        Assertions.assertTrue(dstSpi.isRouted());
        return router;
    }

    /**
     * Gets the route of a router's only connection.
     * @param router The router, after routing.
     * @return The route's nodes, from source to sink.
     */
    static List<RouteNode> getRoute(RWRoute router) {
        Assertions.assertEquals(1, router.indirectConnections.size());
        List<RouteNode> route = new ArrayList<>(router.indirectConnections.get(0).getRnodes());
        // Connection.getRnodes() is from sink to source
        Collections.reverse(route);
        return route;
    }

    /**
     * How far, relative, RWRoute's route may cost more than is expected of it in
     * testCostWithinLowerBoundNonTimingDriven() (the lower bound, or a known excess over it) before failing as a
     * regression. Costing any less than a known excess fails too, as an improvement, so that the entry is updated.
     * A row that costs more than its lower bound but within this is not listed in {@link #LOWER_BOUND_KNOWN_EXCESS}.
     */
    private static final float LOWER_BOUND_LEEWAY = 0.05f;

    /**
     * The cost (see getModelCost()) expected of RWRoute's route on those rows of
     * testCostWithinLowerBoundNonTimingDriven() where it costs more than {@link #LOWER_BOUND_LEEWAY} above the lower
     * bound, keyed by the row ("part,src,dst"); each entry gives RWRoute's route and the cheapest one (from an exact
     * search under the same costs). They are of two kinds:
     * <ul>
     * <li>RWRoute misses: the cheapest route attains the bound, but RWRoute's costs more. In each, near the source
     * RWRoute takes a wire that looks as cheap or cheaper one wire ahead (it travels a row further, or lands on the
     * other half), but the wires that follow it cost more; the lower bounds of the nodes near the source are then too
     * high (being estimates that exceed the true cost) for the search to come back to them.</li>
     * <li>A loose bound: RWRoute finds the cheapest route, but the bound cannot be attained, since getLowerBound()
     * lets any move follow any other (see there).</li>
     * </ul>
     */
    private static final Map<String, Float> LOWER_BOUND_KNOWN_EXCESS = new HashMap<>();
    static {
        // RWRoute misses (UltraScale+). Bound 6.8 (cheapest: INT_NODE_SDQ_70_INT_OUT1, SS4_W_BEG1 4 rows, SS12_BEG0,
        // SS2_W_BEG1). RWRoute: INT_NODE_SDQ_70_INT_OUT0, SS4_W_BEG0 5 rows, then SS12_BEG7 lands on the east half a
        // row short, so it crosses back west and a row north (WW1_E_7_FT0, SDQNODE_W_95_FT0) and takes two SS1_W_BEG0
        LOWER_BOUND_KNOWN_EXCESS.put("xcvu9p-flgb2104-2-i,SLICE_X129Y450.A_O,SLICE_X129Y432.A6", 8.5f);
        // Bound 8.4 (cheapest: SS4_W_BEG1 twice, SS12_BEG0, SS2_W_BEG1). RWRoute: SS4_W_BEG0 5 rows and SS12_BEG7 to
        // the east half as above, SDQNODE_E_93_FT0 back a row, SS4_E_BEG0 5 rows, SDQNODE_E_93_FT0 back a row,
        // SS1_E_BEG0, across to the west half (INT_INT_SDQ_72_INT_OUT0) and SS1_W_BEG0
        LOWER_BOUND_KNOWN_EXCESS.put("xcvu9p-flgb2104-2-i,SLICE_X129Y450.A_O,SLICE_X129Y428.A6", 10.1f);
        // Bound 8.4 (cheapest: INT_NODE_SDQ_65_INT_OUT1, NN4_W_BEG3, NN12_BEG4 to the west half, NN4_W_BEG3,
        // NN2_W_BEG3). RWRoute: INT_NODE_SDQ_65_INT_OUT0, NN4_W_BEG2, NN12_BEG3 to the east half, NN4_E_BEG1,
        // NN2_E_BEG1, then across to the west half (INT_INT_SDQ_32_INT_OUT1) and a bounce (BYPASS_W4) into A4
        LOWER_BOUND_KNOWN_EXCESS.put("xcvu9p-flgb2104-2-i,SLICE_X129Y450.AQ,SLICE_X129Y472.A4", 10.1f);
        // Bound 5.2 (cheapest: INT_NODE_SDQ_22_INT_OUT1, SS4_E_BEG1 twice, SS2_E_BEG1). RWRoute:
        // INT_NODE_SDQ_22_INT_OUT0, SS4_E_BEG0 5 rows and SDQNODE_E_93_FT0 back a row, twice, then two SS1_E_BEG0
        // for the last 2 rows
        LOWER_BOUND_KNOWN_EXCESS.put("xcvu9p-flgb2104-2-i,SLICE_X130Y450.A_O,SLICE_X130Y440.A6", 6.0f);
        // As above, on the west half: bound 5.2 (cheapest: INT_NODE_SDQ_70_INT_OUT1, SS4_W_BEG1 twice, SS2_W_BEG1).
        // RWRoute: INT_NODE_SDQ_70_INT_OUT0, SS4_W_BEG0 and SDQNODE_W_93_FT0 twice, then two SS1_W_BEG0
        LOWER_BOUND_KNOWN_EXCESS.put("xcvu9p-flgb2104-2-i,SLICE_X129Y450.A_O,SLICE_X129Y440.A6", 6.0f);
        // RWRoute misses (Versal). Bound 7.8 (cheapest: OUT_SS12_BEG0, OUT_SS7_BEG0, OUT_SS4_E_BEG0). RWRoute:
        // OUT_SS12_BEG0 twice, a row past the sink, then OUT_NN1_E_BEG0 back
        LOWER_BOUND_KNOWN_EXCESS.put("xcvc1902,SLICE_X60Y140.AQ,SLICE_X60Y117.A4", 8.2f);
        // As above, on the west half: OUT_SS4_W_BEG0 and OUT_NN1_W_BEG0
        LOWER_BOUND_KNOWN_EXCESS.put("xcvc1902,SLICE_X62Y140.AQ,SLICE_X62Y117.A4", 8.2f);
        // Loose bound (UltraScale): bound 3.4 (NN2_[EW]_BEG7 3 rows twice, then an INODE feedthrough a row north, but
        // the INODE feedthroughs that NN2_[EW]_BEG7 reaches lead a row south). RWRoute takes the cheapest route:
        // INT_NODE_QUAD_LONG_*, NN5_BEG0 5 rows, NN2_E_BEG0
        LOWER_BOUND_KNOWN_EXCESS.put("xcku040-ffva1156-2-e,SLICE_X51Y120.A_O,SLICE_X51Y127.A6", 3.8f);
        LOWER_BOUND_KNOWN_EXCESS.put("xcku040-ffva1156-2-e,SLICE_X51Y120.AQ,SLICE_X51Y127.A6", 3.8f);
    }

    /**
     * Gets the lower bound (getLowerBound()) on the cost of a routed connection, between its source and sink.
     * @param graph The routing graph that it was routed on.
     * @param route Its route, from source to sink.
     * @param series The device's series.
     * @param srcSitePin Its source, as given to routeSingleConnection().
     * @return The lower bound.
     */
    static float getRouteLowerBound(RouteNodeGraph graph, List<RouteNode> route, Series series, String srcSitePin) {
        RouteNode source = route.get(0);
        RouteNode sink = route.get(route.size() - 1);
        return getLowerBound(series, graph, source.getEndHalfTileXCoordinate(),
                source.getEndTileYCoordinate(), sink.getEndHalfTileXCoordinate(), sink.getEndTileYCoordinate(),
                isLongLaunchingSource(series, srcSitePin.split("\\.")[1]));
    }

    /**
     * Tests that RWRoute finds a cheap route for a single uncongested connection: one whose cost, under the model of
     * getModelCost() (RWRoute's own costs without bias), is within {@link #LOWER_BOUND_LEEWAY} of a lower bound
     * (getLowerBound()), or of its known excess over that bound ({@link #LOWER_BOUND_KNOWN_EXCESS}). The connections
     * were chosen, with an exact search under the same model, as ones whose cheapest route attains that lower bound,
     * so the bound is known to be achievable; except three on xcku040, where the bound is loose (see
     * getLowerBound()), whose rows give their cheapest cost too. Which wires the route takes does not matter, so
     * neither does how ties are broken.
     * @param partName Part.
     * @param srcSitePin Source pin, as "SITE.PIN".
     * @param dstSitePin Sink pin, as "SITE.PIN".
     */
    @ParameterizedTest
    @CsvSource({
            // Each row's comment gives the source-sink distance (half tiles, rows) and the lower bound.
            // UltraScale+ (xcvu9p SLR1): CLEL_R_X83 on the east half of INT_X83, CLEM_X83 on its west half
            "xcvu9p-flgb2104-2-i,SLICE_X130Y450.A_O,SLICE_X130Y452.A4",    // 0,2; bound 2.0
            "xcvu9p-flgb2104-2-i,SLICE_X130Y450.A_O,SLICE_X130Y452.A6",    // 0,2; bound 2.0
            "xcvu9p-flgb2104-2-i,SLICE_X130Y450.A_O,SLICE_X130Y448.A4",    // 0,-2; bound 2.0
            "xcvu9p-flgb2104-2-i,SLICE_X130Y450.A_O,SLICE_X130Y448.A6",    // 0,-2; bound 2.0
            "xcvu9p-flgb2104-2-i,SLICE_X130Y450.A_O,SLICE_X130Y456.A4",    // 0,6; bound 3.6
            "xcvu9p-flgb2104-2-i,SLICE_X130Y450.A_O,SLICE_X130Y456.A6",    // 0,6; bound 3.6
            "xcvu9p-flgb2104-2-i,SLICE_X130Y450.A_O,SLICE_X130Y444.A4",    // 0,-6; bound 3.6
            "xcvu9p-flgb2104-2-i,SLICE_X130Y450.A_O,SLICE_X130Y444.A6",    // 0,-6; bound 3.6
            "xcvu9p-flgb2104-2-i,SLICE_X130Y450.A_O,SLICE_X130Y460.A4",    // 0,10; bound 5.2
            "xcvu9p-flgb2104-2-i,SLICE_X130Y450.A_O,SLICE_X130Y460.A6",    // 0,10; bound 5.2
            "xcvu9p-flgb2104-2-i,SLICE_X130Y450.A_O,SLICE_X130Y440.A4",    // 0,-10; bound 5.2
            "xcvu9p-flgb2104-2-i,SLICE_X130Y450.A_O,SLICE_X130Y440.A6",    // 0,-10; bound 5.2
            "xcvu9p-flgb2104-2-i,SLICE_X130Y450.A_O,SLICE_X130Y432.A4",    // 0,-18; bound 6.8
            "xcvu9p-flgb2104-2-i,SLICE_X130Y450.A_O,SLICE_X130Y428.A4",    // 0,-22; bound 8.4
            "xcvu9p-flgb2104-2-i,SLICE_X130Y450.A_O,SLICE_X131Y450.A4",    // 1,0; bound 1.7
            "xcvu9p-flgb2104-2-i,SLICE_X130Y450.A_O,SLICE_X131Y450.A6",    // 1,0; bound 1.7
            "xcvu9p-flgb2104-2-i,SLICE_X130Y450.A_O,SLICE_X129Y450.A4",    // -1,0; bound 1.7
            "xcvu9p-flgb2104-2-i,SLICE_X130Y450.A_O,SLICE_X129Y450.A6",    // -1,0; bound 1.7
            "xcvu9p-flgb2104-2-i,SLICE_X130Y450.AQ,SLICE_X130Y452.A4",     // 0,2; bound 2.0
            "xcvu9p-flgb2104-2-i,SLICE_X130Y450.AQ,SLICE_X130Y456.A4",     // 0,6; bound 3.6
            "xcvu9p-flgb2104-2-i,SLICE_X130Y450.AQ,SLICE_X130Y460.A4",     // 0,10; bound 5.2
            "xcvu9p-flgb2104-2-i,SLICE_X130Y450.AQ,SLICE_X130Y468.A4",     // 0,18; bound 6.8
            "xcvu9p-flgb2104-2-i,SLICE_X130Y450.AQ,SLICE_X130Y472.A4",     // 0,22; bound 8.4
            "xcvu9p-flgb2104-2-i,SLICE_X130Y450.AQ,SLICE_X130Y472.A6",     // 0,22; bound 8.4
            "xcvu9p-flgb2104-2-i,SLICE_X130Y450.AQ,SLICE_X131Y450.A4",     // 1,0; bound 1.7
            "xcvu9p-flgb2104-2-i,SLICE_X129Y450.A_O,SLICE_X129Y452.A4",    // 0,2; bound 2.0
            "xcvu9p-flgb2104-2-i,SLICE_X129Y450.A_O,SLICE_X129Y452.A6",    // 0,2; bound 2.0
            "xcvu9p-flgb2104-2-i,SLICE_X129Y450.A_O,SLICE_X129Y448.A4",    // 0,-2; bound 2.0
            "xcvu9p-flgb2104-2-i,SLICE_X129Y450.A_O,SLICE_X129Y448.A6",    // 0,-2; bound 2.0
            "xcvu9p-flgb2104-2-i,SLICE_X129Y450.A_O,SLICE_X129Y456.A4",    // 0,6; bound 3.6
            "xcvu9p-flgb2104-2-i,SLICE_X129Y450.A_O,SLICE_X129Y456.A6",    // 0,6; bound 3.6
            "xcvu9p-flgb2104-2-i,SLICE_X129Y450.A_O,SLICE_X129Y444.A4",    // 0,-6; bound 3.6
            "xcvu9p-flgb2104-2-i,SLICE_X129Y450.A_O,SLICE_X129Y444.A6",    // 0,-6; bound 3.6
            "xcvu9p-flgb2104-2-i,SLICE_X129Y450.A_O,SLICE_X129Y460.A4",    // 0,10; bound 5.2
            "xcvu9p-flgb2104-2-i,SLICE_X129Y450.A_O,SLICE_X129Y460.A6",    // 0,10; bound 5.2
            "xcvu9p-flgb2104-2-i,SLICE_X129Y450.A_O,SLICE_X129Y440.A4",    // 0,-10; bound 5.2
            "xcvu9p-flgb2104-2-i,SLICE_X129Y450.A_O,SLICE_X129Y440.A6",    // 0,-10; bound 5.2
            "xcvu9p-flgb2104-2-i,SLICE_X129Y450.A_O,SLICE_X129Y468.A4",    // 0,18; bound 6.8
            "xcvu9p-flgb2104-2-i,SLICE_X129Y450.A_O,SLICE_X129Y468.A6",    // 0,18; bound 6.8
            "xcvu9p-flgb2104-2-i,SLICE_X129Y450.A_O,SLICE_X129Y432.A6",    // 0,-18; bound 6.8
            "xcvu9p-flgb2104-2-i,SLICE_X129Y450.A_O,SLICE_X129Y472.A4",    // 0,22; bound 8.4
            "xcvu9p-flgb2104-2-i,SLICE_X129Y450.A_O,SLICE_X129Y472.A6",    // 0,22; bound 8.4
            "xcvu9p-flgb2104-2-i,SLICE_X129Y450.A_O,SLICE_X129Y428.A6",    // 0,-22; bound 8.4
            "xcvu9p-flgb2104-2-i,SLICE_X129Y450.A_O,SLICE_X130Y450.A4",    // 1,0; bound 1.7
            "xcvu9p-flgb2104-2-i,SLICE_X129Y450.A_O,SLICE_X130Y450.A6",    // 1,0; bound 1.7
            "xcvu9p-flgb2104-2-i,SLICE_X129Y450.A_O,SLICE_X128Y450.A4",    // -1,0; bound 1.7
            "xcvu9p-flgb2104-2-i,SLICE_X129Y450.A_O,SLICE_X128Y450.A6",    // -1,0; bound 1.7
            "xcvu9p-flgb2104-2-i,SLICE_X129Y450.AQ,SLICE_X129Y452.A4",     // 0,2; bound 2.0
            "xcvu9p-flgb2104-2-i,SLICE_X129Y450.AQ,SLICE_X129Y456.A4",     // 0,6; bound 3.6
            "xcvu9p-flgb2104-2-i,SLICE_X129Y450.AQ,SLICE_X129Y460.A4",     // 0,10; bound 5.2
            "xcvu9p-flgb2104-2-i,SLICE_X129Y450.AQ,SLICE_X129Y472.A4",     // 0,22; bound 8.4
            "xcvu9p-flgb2104-2-i,SLICE_X129Y450.AQ,SLICE_X130Y450.A4",     // 1,0; bound 1.7
            // UltraScale (xcku040): CLEL_R_X33 on the east half of INT_X33, CLE_M_X33 on its west half
            "xcku040-ffva1156-2-e,SLICE_X51Y120.A_O,SLICE_X51Y122.A6",     // 0,2; bound 2.0
            "xcku040-ffva1156-2-e,SLICE_X51Y120.A_O,SLICE_X51Y127.A6",     // 0,7; bound 3.4 (cheapest 3.8)
            "xcku040-ffva1156-2-e,SLICE_X51Y120.A_O,SLICE_X52Y120.A6",     // 1,0; bound 1.7
            "xcku040-ffva1156-2-e,SLICE_X51Y120.AQ,SLICE_X51Y122.A6",      // 0,2; bound 2.0
            "xcku040-ffva1156-2-e,SLICE_X51Y120.AQ,SLICE_X51Y127.A6",      // 0,7; bound 3.4 (cheapest 3.8)
            "xcku040-ffva1156-2-e,SLICE_X51Y120.AQ,SLICE_X52Y120.A6",      // 1,0; bound 1.7
            "xcku040-ffva1156-2-e,SLICE_X50Y120.A_O,SLICE_X50Y122.A6",     // 0,2; bound 2.0
            "xcku040-ffva1156-2-e,SLICE_X50Y120.A_O,SLICE_X50Y97.A4",      // 0,-23; bound 8.0 (cheapest 8.2)
            "xcku040-ffva1156-2-e,SLICE_X50Y120.A_O,SLICE_X51Y120.A6",     // 1,0; bound 1.7
            "xcku040-ffva1156-2-e,SLICE_X50Y120.AQ,SLICE_X50Y122.A6",      // 0,2; bound 2.0
            "xcku040-ffva1156-2-e,SLICE_X50Y120.AQ,SLICE_X51Y120.A6",      // 1,0; bound 1.7
            // Versal (xcvc1902): CLE_W_CORE_X19 on the east half of INT_X19, CLE_E_CORE_X20 on the west half of INT_X20
            "xcvc1902,SLICE_X60Y140.A_O,SLICE_X60Y141.A4",                 // 0,1; bound 1.8
            "xcvc1902,SLICE_X60Y140.A_O,SLICE_X60Y141.A5",                 // 0,1; bound 1.8
            "xcvc1902,SLICE_X60Y140.A_O,SLICE_X60Y139.A4",                 // 0,-1; bound 1.8
            "xcvc1902,SLICE_X60Y140.A_O,SLICE_X60Y139.A5",                 // 0,-1; bound 1.8
            "xcvc1902,SLICE_X60Y140.A_O,SLICE_X60Y144.A4",                 // 0,4; bound 2.4
            "xcvc1902,SLICE_X60Y140.A_O,SLICE_X60Y144.A5",                 // 0,4; bound 2.4
            "xcvc1902,SLICE_X60Y140.A_O,SLICE_X60Y136.A4",                 // 0,-4; bound 2.4
            "xcvc1902,SLICE_X60Y140.A_O,SLICE_X56Y140.A4",                 // -4,0; bound 2.0
            "xcvc1902,SLICE_X60Y140.A_O,SLICE_X56Y140.A5",                 // -4,0; bound 2.0
            "xcvc1902,SLICE_X60Y140.A_O,SLICE_X68Y140.A5",                 // 4,0; bound 2.0
            "xcvc1902,SLICE_X60Y140.AQ,SLICE_X60Y141.A4",                  // 0,1; bound 1.8
            "xcvc1902,SLICE_X60Y140.AQ,SLICE_X60Y141.A5",                  // 0,1; bound 1.8
            "xcvc1902,SLICE_X60Y140.AQ,SLICE_X60Y139.A4",                  // 0,-1; bound 1.8
            "xcvc1902,SLICE_X60Y140.AQ,SLICE_X60Y139.A5",                  // 0,-1; bound 1.8
            "xcvc1902,SLICE_X60Y140.AQ,SLICE_X60Y136.A4",                  // 0,-4; bound 2.4
            "xcvc1902,SLICE_X60Y140.AQ,SLICE_X60Y136.A5",                  // 0,-4; bound 2.4
            "xcvc1902,SLICE_X60Y140.AQ,SLICE_X60Y127.A4",                  // 0,-13; bound 5.0
            "xcvc1902,SLICE_X60Y140.AQ,SLICE_X60Y127.A5",                  // 0,-13; bound 5.0
            "xcvc1902,SLICE_X60Y140.AQ,SLICE_X60Y124.A4",                  // 0,-16; bound 5.6
            "xcvc1902,SLICE_X60Y140.AQ,SLICE_X60Y117.A4",                  // 0,-23; bound 7.8
            "xcvc1902,SLICE_X60Y140.AQ2,SLICE_X60Y141.A5",                 // 0,1; bound 1.8
            "xcvc1902,SLICE_X60Y140.AQ2,SLICE_X60Y139.A4",                 // 0,-1; bound 1.8
            "xcvc1902,SLICE_X60Y140.AQ2,SLICE_X60Y139.A5",                 // 0,-1; bound 1.8
            "xcvc1902,SLICE_X60Y140.AQ2,SLICE_X60Y144.A4",                 // 0,4; bound 2.4
            "xcvc1902,SLICE_X60Y140.AQ2,SLICE_X60Y144.A5",                 // 0,4; bound 2.4
            "xcvc1902,SLICE_X60Y140.AQ2,SLICE_X63Y140.A4",                 // 1,0; bound 1.2
            "xcvc1902,SLICE_X60Y140.AQ2,SLICE_X63Y140.A5",                 // 1,0; bound 1.2
            "xcvc1902,SLICE_X60Y140.AQ2,SLICE_X56Y140.A4",                 // -4,0; bound 2.0
            "xcvc1902,SLICE_X60Y140.AQ2,SLICE_X56Y140.A5",                 // -4,0; bound 2.0
            "xcvc1902,SLICE_X60Y140.AQ2,SLICE_X68Y140.A5",                 // 4,0; bound 2.0
            "xcvc1902,SLICE_X60Y140.AQ2,SLICE_X38Y140.A4",                 // -15,0; bound 3.8
            "xcvc1902,SLICE_X60Y140.AQ2,SLICE_X38Y140.A5",                 // -15,0; bound 3.8
            "xcvc1902,SLICE_X62Y140.A_O,SLICE_X62Y141.A4",                 // 0,1; bound 1.8
            "xcvc1902,SLICE_X62Y140.A_O,SLICE_X62Y141.A5",                 // 0,1; bound 1.8
            "xcvc1902,SLICE_X62Y140.A_O,SLICE_X62Y139.A4",                 // 0,-1; bound 1.8
            "xcvc1902,SLICE_X62Y140.A_O,SLICE_X62Y139.A5",                 // 0,-1; bound 1.8
            "xcvc1902,SLICE_X62Y140.A_O,SLICE_X62Y144.A4",                 // 0,4; bound 2.4
            "xcvc1902,SLICE_X62Y140.A_O,SLICE_X62Y144.A5",                 // 0,4; bound 2.4
            "xcvc1902,SLICE_X62Y140.A_O,SLICE_X62Y136.A4",                 // 0,-4; bound 2.4
            "xcvc1902,SLICE_X62Y140.A_O,SLICE_X60Y140.A4",                 // -1,0; bound 1.2
            "xcvc1902,SLICE_X62Y140.A_O,SLICE_X60Y140.A5",                 // -1,0; bound 1.2
            "xcvc1902,SLICE_X62Y140.A_O,SLICE_X58Y140.A4",                 // -4,0; bound 2.0
            "xcvc1902,SLICE_X62Y140.A_O,SLICE_X58Y140.A5",                 // -4,0; bound 2.0
            "xcvc1902,SLICE_X62Y140.A_O,SLICE_X70Y140.A5",                 // 4,0; bound 2.0
            "xcvc1902,SLICE_X62Y140.AQ,SLICE_X62Y141.A4",                  // 0,1; bound 1.8
            "xcvc1902,SLICE_X62Y140.AQ,SLICE_X62Y141.A5",                  // 0,1; bound 1.8
            "xcvc1902,SLICE_X62Y140.AQ,SLICE_X62Y139.A4",                  // 0,-1; bound 1.8
            "xcvc1902,SLICE_X62Y140.AQ,SLICE_X62Y139.A5",                  // 0,-1; bound 1.8
            "xcvc1902,SLICE_X62Y140.AQ,SLICE_X62Y136.A4",                  // 0,-4; bound 2.4
            "xcvc1902,SLICE_X62Y140.AQ,SLICE_X62Y136.A5",                  // 0,-4; bound 2.4
            "xcvc1902,SLICE_X62Y140.AQ,SLICE_X62Y127.A4",                  // 0,-13; bound 5.0
            "xcvc1902,SLICE_X62Y140.AQ,SLICE_X62Y127.A5",                  // 0,-13; bound 5.0
            "xcvc1902,SLICE_X62Y140.AQ,SLICE_X62Y124.A4",                  // 0,-16; bound 5.6
            "xcvc1902,SLICE_X62Y140.AQ,SLICE_X62Y117.A4",                  // 0,-23; bound 7.8
            // Crossing an SLR boundary (see getLowerBound()). UltraScale+ (xcvu9p, SLR0/SLR1 at rows 299/300): beside
            // the Laguna column of INT_X5 (whose west half crosses), from in, below and above its band
            "xcvu9p-flgb2104-2-i,SLICE_X9Y299.AQ,SLICE_X9Y359.A4",         // 0,60; bound 22.6
            "xcvu9p-flgb2104-2-i,SLICE_X9Y300.AQ,SLICE_X9Y240.A4",         // 0,-60; bound 22.6
            "xcvu9p-flgb2104-2-i,SLICE_X9Y299.A_O,SLICE_X9Y359.A4",        // 0,60; bound 22.6
            "xcvu9p-flgb2104-2-i,SLICE_X9Y299.AQ,SLICE_X7Y359.A4",         // -2,60; bound 22.6
            "xcvu9p-flgb2104-2-i,SLICE_X9Y299.AQ,SLICE_X9Y389.A4",         // 0,90; bound 30.9
            "xcvu9p-flgb2104-2-i,SLICE_X9Y299.A_O,SLICE_X9Y429.A4",        // 0,130; bound 42.1
            "xcvu9p-flgb2104-2-i,SLICE_X9Y270.AQ,SLICE_X9Y329.A6",         // 0,59; bound 23.6
            "xcvu9p-flgb2104-2-i,SLICE_X9Y329.A_O,SLICE_X9Y270.A6",        // 0,-59; bound 23.6
            "xcvu9p-flgb2104-2-i,SLICE_X9Y270.AQ,SLICE_X7Y329.A6",         // -2,59; bound 23.6
            "xcvu9p-flgb2104-2-i,SLICE_X9Y240.AQ,SLICE_X9Y300.A4",         // 0,60; bound 22.6
            "xcvu9p-flgb2104-2-i,SLICE_X9Y359.A_O,SLICE_X9Y299.A4",        // 0,-60; bound 22.6
            "xcvu9p-flgb2104-2-i,SLICE_X9Y210.AQ,SLICE_X9Y300.A4",         // 0,90; bound 30.9
            "xcvu9p-flgb2104-2-i,SLICE_X9Y389.A_O,SLICE_X9Y299.A4",        // 0,-90; bound 30.9
            "xcvu9p-flgb2104-2-i,SLICE_X7Y299.AQ,SLICE_X9Y359.A4",         // 2,60; bound 22.6
            "xcvu9p-flgb2104-2-i,SLICE_X7Y300.A_O,SLICE_X9Y240.A4",        // 2,-60; bound 22.6
            "xcvu9p-flgb2104-2-i,SLICE_X7Y299.A_O,SLICE_X9Y429.A4",        // 2,130; bound 42.1
            "xcvu9p-flgb2104-2-i,SLICE_X7Y300.A_O,SLICE_X9Y170.A4",        // 2,-130; bound 42.1
            "xcvu9p-flgb2104-2-i,SLICE_X7Y270.AQ,SLICE_X9Y329.A6",         // 2,59; bound 23.6
            "xcvu9p-flgb2104-2-i,SLICE_X7Y359.AQ,SLICE_X7Y299.A4",         // 0,-60; bound 22.6
            "xcvu9p-flgb2104-2-i,SLICE_X7Y240.A_O,SLICE_X9Y300.A4",        // 2,60; bound 22.6
            // UltraScale (xcku115): CLEL_R_X12 on the east half of INT_X12, CLEL_R_X13 on the east half of INT_X13
            // (whose west half crosses)
            "xcku115,SLICE_X18Y240.AQ,SLICE_X18Y300.A2",                   // 0,60; bound 22.6
            "xcku115,SLICE_X18Y300.AQ,SLICE_X18Y240.C5",                   // 0,-60; bound 22.6
            "xcku115,SLICE_X18Y270.AQ,SLICE_X18Y329.A4",                   // 0,59; bound 22.6
            "xcku115,SLICE_X18Y270.AQ,SLICE_X18Y329.H6",                   // 0,59; bound 22.6
            "xcku115,SLICE_X20Y240.A_O,SLICE_X20Y300.A6",                  // 0,60; bound 22.6
            "xcku115,SLICE_X20Y240.A_O,SLICE_X20Y300.H3",                  // 0,60; bound 22.6
            // Versal (xcv80, SLR0/SLR1 at rows 335/336): beside the SLL column between INT_X16 and INT_X17
            "xcv80,SLICE_X53Y331.A_O,SLICE_X55Y406.A5",                    // 1,75; bound 26.1
            "xcv80,SLICE_X53Y369.AQ,SLICE_X53Y295.A4",                     // 0,-74; bound 26.7
            "xcv80,SLICE_X53Y294.A_O,SLICE_X53Y368.A4",                    // 0,74; bound 26.7
            "xcv80,SLICE_X53Y369.A_O,SLICE_X53Y295.A4",                    // 0,-74; bound 26.7
            "xcv80,SLICE_X53Y406.AQ,SLICE_X55Y331.A5",                     // 1,-75; bound 26.1
            "xcv80,SLICE_X53Y406.A_O,SLICE_X55Y331.A5",                    // 1,-75; bound 26.1
            "xcv80,SLICE_X53Y406.AQ,SLICE_X61Y331.A5",                     // 4,-75; bound 26.9
    })
    public void testCostWithinLowerBoundNonTimingDriven(String partName, String srcSitePin, String dstSitePin) {
        PartialRouter rwroute = routeSingleConnection(partName, srcSitePin, dstSitePin);
        List<RouteNode> route = getRoute(rwroute);
        Series series = PartNameTools.getPart(partName).getSeries();
        float lowerBound = getRouteLowerBound(rwroute.routingGraph, route, series, srcSitePin);
        float cost = getModelCost(route);
        float expected = LOWER_BOUND_KNOWN_EXCESS.getOrDefault(partName + "," + srcSitePin + "," + dstSitePin,
                lowerBound);
        String message = "cost " + cost + ", expected " + expected + ", lower bound " + lowerBound + ", ratio " +
                cost / lowerBound;
        System.out.println(srcSitePin + " -> " + dstSitePin + ": " + message);
        Assertions.assertTrue(cost >= lowerBound - 1e-4f, "below the lower bound: " + message);
        Assertions.assertTrue(cost <= expected * (1 + LOWER_BOUND_LEEWAY) + 1e-4f, "more than expected: " + message);
        // Non-timing-driven routing is deterministic, so a known excess is met exactly: any cheaper route is an
        // improvement, for which the entry is to be updated
        Assertions.assertTrue(cost >= expected - 1e-4f,
                "less than expected (update LOWER_BOUND_KNOWN_EXCESS): " + message);
    }

    Design testSingleConnectionHelper(String partName,
                                    String srcSiteName, String srcPinName,
                                    String dstSiteName, String dstPinName,
                                    long nodesPoppedLimit) {
        Design design = new Design("top", partName);

        Net net = design.createNet("net");
        SiteInst srcSi = design.createSiteInst(srcSiteName);
        SitePinInst srcSpi = net.createPin(srcPinName, srcSi);

        SiteInst dstSi = design.createSiteInst(dstSiteName);
        SitePinInst dstSpi = net.createPin(dstPinName, dstSi);

        List<SitePinInst> pinsToRoute = new ArrayList<>();
        pinsToRoute.add(dstSpi);
        boolean softPreserve = false;
        PartialRouter.routeDesignPartialNonTimingDriven(design, pinsToRoute, softPreserve);

        Assertions.assertTrue(srcSpi.isRouted());
        Assertions.assertTrue(dstSpi.isRouted());
        long nodesPopped = Long.parseLong(System.getProperty("rapidwright.rwroute.nodesPopped"));
        Assertions.assertTrue(nodesPopped >= (nodesPoppedLimit - 100) && nodesPopped <= nodesPoppedLimit);

        return design;
    }

    @ParameterizedTest
    @CsvSource({
            // Versal
            // One SLR crossing
            // (Too) close
            "xcv80,SLICE_X54Y331,SLICE_X54Y332,100",            // Source adjacent to crossing SLL (east)
            "xcv80,SLICE_X53Y332,SLICE_X53Y331,100",            // Source adjacent to crossing SLL (west)
            "xcv80,SLICE_X51Y331,SLICE_X51Y332,100",            // Close to crossing SLL
            // Perfect
            "xcv80,SLICE_X54Y331,SLICE_X54Y406,100",            // Source adjacent to crossing SLL (east, north)
            "xcv80,SLICE_X53Y331,SLICE_X53Y406,200",            // Source adjacent to crossing SLL (west, north)
            "xcv80,SLICE_X54Y406,SLICE_X54Y331,100",            // Sink adjacent to crossing SLL (east, south)
            "xcv80,SLICE_X53Y406,SLICE_X53Y331,100",            // Sink adjacent to crossing SLL (west, south)
            "xcv80,SLICE_X51Y331,SLICE_X51Y406,200",            // Source close to crossing SLL
            "xcv80,SLICE_X0Y331,SLICE_X49Y406,300",             // Source far from crossing SLL

            // US+
            // One SLR crossing
            // (Too) Close
            Device.AWS_F1 + ",SLICE_X9Y299,SLICE_X9Y300,100",    // On Laguna column
            Device.AWS_F1 + ",SLICE_X9Y300,SLICE_X9Y299,100",
            Device.AWS_F1 + ",SLICE_X0Y299,SLICE_X0Y300,100",    // Far from Laguna column
            Device.AWS_F1 + ",SLICE_X0Y300,SLICE_X0Y299,100",
            Device.AWS_F1 + ",SLICE_X54Y299,SLICE_X56Y300,100",  // Slight closer to one Laguna column that is further from sink
            Device.AWS_F1 + ",SLICE_X54Y300,SLICE_X56Y299,100",
            Device.AWS_F1 + ",SLICE_X50Y299,SLICE_X65Y300,100",
            Device.AWS_F1 + ",SLICE_X50Y300,SLICE_X65Y299,100",
            Device.AWS_F1 + ",SLICE_X55Y299,SLICE_X55Y300,100",  // Equidistant from two Laguna columns
            Device.AWS_F1 + ",SLICE_X55Y300,SLICE_X55Y299,100",
            // Perfect
            Device.AWS_F1 + ",SLICE_X9Y241,SLICE_X9Y300,100",
            Device.AWS_F1 + ",SLICE_X9Y300,SLICE_X9Y241,100",
            Device.AWS_F1 + ",SLICE_X9Y358,SLICE_X9Y299,200",
            Device.AWS_F1 + ",SLICE_X9Y299,SLICE_X9Y358,100",
            Device.AWS_F1 + ",SLICE_X53Y241,SLICE_X69Y300,300",
            Device.AWS_F1 + ",SLICE_X53Y358,SLICE_X69Y299,100",
            // Far
            Device.AWS_F1 + ",SLICE_X9Y240,SLICE_X9Y359,100",    // On Laguna
            Device.AWS_F1 + ",SLICE_X9Y359,SLICE_X9Y240,100",
            Device.AWS_F1 + ",SLICE_X162Y240,SLICE_X162Y430,100",

            Device.AWS_F1 + ",SLICE_X162Y430,SLICE_X162Y240,100",
            Device.AWS_F1 + ",SLICE_X0Y240,SLICE_X12Y430,100",   // Far from Laguna
            Device.AWS_F1 + ",SLICE_X0Y430,SLICE_X12Y240,200",

            // Two SLR crossings
            Device.AWS_F1 + ",SLICE_X162Y299,SLICE_X162Y599,100",
            Device.AWS_F1 + ",SLICE_X162Y599,SLICE_X162Y299,200",

            // Three SLR crossings
            Device.AWS_F1 + ",SLICE_X79Y0,SLICE_X79Y899,200",    // Straight up: on Laguna column (opposite side of Laguna)
            Device.AWS_F1 + ",SLICE_X78Y60,SLICE_X78Y839,100",   // Straight up: on Laguna column (same side as Laguna)
            Device.AWS_F1 + ",SLICE_X0Y0,SLICE_X0Y899,300",      // Straight up: far from Laguna column
            Device.AWS_F1 + ",SLICE_X168Y0,SLICE_X168Y899,200",  // Straight up: far from Laguna column
            Device.AWS_F1 + ",SLICE_X9Y0,SLICE_X162Y899,200",    // Up and right
            Device.AWS_F1 + ",SLICE_X168Y162,SLICE_X9Y899,200",  // Up and left

            // One SLR crossing, far: source in a Laguna row, on the east half of the INT tile on the other side (west) of
            // a Laguna column (INT_X4, beside LAG_LAG_X4, which INT_X5 drives; cf. SLICE_X9Y240/SLICE_X9Y359 above, on
            // the east half of INT_X5)
            Device.AWS_F1 + ",SLICE_X7Y240,SLICE_X7Y359,100",
            Device.AWS_F1 + ",SLICE_X7Y359,SLICE_X7Y240,100",

            // US
            // One SLR crossing, far: source in a Laguna row, on the east half of the INT tile with a Laguna column
            // (INT_X13, which drives LAGUNA_TILE_X13) and of the INT tile on the other side (west) of it (INT_X12)
            "xcku115,SLICE_X20Y240,SLICE_X20Y359,100",
            "xcku115,SLICE_X20Y359,SLICE_X20Y240,100",
            "xcku115,SLICE_X18Y240,SLICE_X18Y359,100",
            "xcku115,SLICE_X18Y359,SLICE_X18Y240,100",

            // Riding: SLR crossings, far, with a source from which the on-ramp is not typed (mostly in the crossing
            // rows), so that the search rides vertical wires towards the sink before stepping onto it
            // US+: on the west half of the INT tile on the other side (west) of a Laguna column (INT_X4: EE2_W doubles
            // into INT_X5), and one INT tile further west (INT_X3)
            Device.AWS_F1 + ",SLICE_X6Y240,SLICE_X6Y359,300",
            Device.AWS_F1 + ",SLICE_X6Y359,SLICE_X6Y240,100",
            Device.AWS_F1 + ",SLICE_X5Y240,SLICE_X5Y359,200",
            // US: on the west half of the INT tile on the other side (west) of a Laguna column (INT_X12)
            "xcku115,SLICE_X17Y240,SLICE_X17Y359,200",
            "xcku115,SLICE_X17Y359,SLICE_X17Y240,200",
            // Versal: in the crossing band (INT Y261-Y335 northbound, Y336-Y410 southbound) on or beside a crossing half
            "xcv80,SLICE_X54Y257,SLICE_X54Y402,200",            // On a crossing half (INT_X17 west)
            "xcv80,SLICE_X56Y257,SLICE_X56Y402,100",            // Beside a crossing half (INT_X17 east)
            "xcv80,SLICE_X54Y402,SLICE_X54Y257,100",
            "xcv80,SLICE_X56Y402,SLICE_X56Y257,100",
            "xcv80,SLICE_X58Y257,SLICE_X58Y402,300",            // Between crossing halves (INT_X18 west)
            "xcv80,SLICE_X56Y290,SLICE_X56Y440,200",            // Mid-band
            "xcv80,SLICE_X54Y150,SLICE_X54Y480,100",            // Below the crossing band
            "xcv80,SLICE_X120Y150,SLICE_X20Y800,200",           // Two crossings, up and left
    })
    public void testSLRCrossingNonTimingDriven(String deviceName, String srcSiteName, String dstSiteName, long nodesPoppedLimit) {
        testSingleConnectionHelper(deviceName, srcSiteName, "AQ", dstSiteName, "A1", nodesPoppedLimit);
    }

    @ParameterizedTest
    @EnumSource(Series.class)
    public void testRWRouteDeviceSupport(Series series) {
        for (Part part : PartNameTools.getAllParts(series)) {
            Design design = new Design("test", part.getName());
            if (!RWRoute.SUPPORTED_SERIES.contains(series)) {
                RuntimeException e = Assertions.assertThrows(RuntimeException.class,
                        () -> RWRoute.routeDesignFullNonTimingDriven(design),
                        "Expected RuntimeException() but was not thrown.");
                Assertions.assertEquals(e.getMessage(), RWRoute.getUnsupportedSeriesMessage(part));
            }
            // Only test one part per series
            break;
        }
    }

    @Test
    public void testBug701() {
        Design design = RapidWrightDCP.loadDCP("bug701.dcp");

        RWRoute.routeDesignWithUserDefinedArguments(design, new String[] {"--nonTimingDriven", "--noInvertGndToVccForLutInputs"});

        Net vcc = design.getVccNet();
        Assertions.assertEquals(1, vcc.getPins().size());
        Assertions.assertTrue(vcc.getPins().stream().allMatch(SitePinInst::isRouted));

        Net gnd = design.getGndNet();
        List<SitePinInst> sinks = gnd.getSinkPins();
        Assertions.assertEquals(31, sinks.size());
        Assertions.assertTrue(sinks.stream().allMatch(SitePinInst::isRouted));

        if (FileTools.isVivadoOnPath()) {
            // Testcase has a number of undriven nets, so just check for unrouted nets
            ReportRouteStatusResult rrs = VivadoTools.reportRouteStatus(design);
            Assertions.assertEquals(0, rrs.unroutedNets);
        }
    }

    private Design generateSmallPlacedDesign() {
        Design d = new Design("HelloWorld", Device.KCU105);
        Cell and2 = d.createAndPlaceCell("and2", Unisim.AND2, "SLICE_X100Y100/A6LUT");
        Net net0 = d.createNet("button0_IBUF");
        net0.connect(and2, "O");
        net0.connect(and2, "I0");
        net0.connect(and2, "I1");

        // Route site internal nets
        d.routeSites();

        EDIFTools.ensureCorrectPartInEDIF(d.getNetlist(), d.getPartName());
        return d;
    }

    @Test
    public void testRWRouteInterchange(@TempDir Path dir) {
        Path rootFile = dir.resolve("interchange-design");
        Interchange.writeDesignToInterchange(generateSmallPlacedDesign(), rootFile.toString());
        Path outputFile = dir.resolve("output.dcp");
        RWRoute.main(new String[] { 
                rootFile.toString() + Interchange.PHYS_NETLIST_EXT, 
                outputFile.toString(),
                "--nonTimingDriven"
                });
        Assertions.assertTrue(Files.exists(outputFile));
    }

    @ParameterizedTest
    @CsvSource({
            "picoblaze_ooc_X10Y235.dcp,false",
            "picoblaze_ooc_X10Y235.dcp,true",
            "gnl_2_4_3_1.3_gnl_3000_07_3_80_80_placed.dcp,false",
            "gnl_2_4_3_1.3_gnl_3000_07_3_80_80_placed.dcp,true",
            "optical-flow.dcp,false",
            "optical-flow.dcp,true",
    })
    public void testTimingAndWirelengthReport(String dcpShortPath, boolean verbose) {
        String dcp = RapidWrightDCP.getString(dcpShortPath);
        String[] args = verbose ? new String[]{dcp, "--verbose"} : new String[]{dcp};

        ByteArrayOutputStream capture = new ByteArrayOutputStream();
        PrintStream originalOut = System.out;
        try {
            System.setOut(new PrintStream(capture, true));
            TimingAndWirelengthReport.main(args);
        } finally {
            System.setOut(originalOut);
        }

        String output = capture.toString();
        System.out.print(output);
        Assertions.assertTrue(output.contains("Critical path delay (ps):"));
        if (verbose) {
            // Every critical path ends on a flop input, so it must cross into that flop's site
            Assertions.assertTrue(output.contains("(intrasite)"));
            // Each intra-site hop must be attributable to a specific pair of BEL pins
            Assertions.assertFalse(output.contains("(BEL pins not recoverable)"));
        }
    }

    @ParameterizedTest
    @CsvSource({
            // Dedicated connections, hence no nodes popped
            "xcvu3p,GTYE4_CHANNEL_X0Y12,TXOUTCLK_INT,BUFG_GT_SYNC_X0Y46,CLK_IN,0",
            "xcvu3p,GTYE4_CHANNEL_X0Y12,TXOUTCLK_INT,BUFG_GT_X0Y78,CLK_IN,0", // (dst pin can be projected to INT but not src pin)

            // Non-dedicated connections
            "xcvu3p,IOB_X0Y47,I,SLICE_X77Y122,FX,100",

            // 240 CLB height SLR, no LAG tiles on Y0 (since HBM on bottom edge)
            "xcu50,SLICE_X38Y239,AQ,SLICE_X38Y240,A1,100"
    })
    public void testSingleConnection(String partName,
                                     String srcSiteName, String srcPinName,
                                     String dstSiteName, String dstPinName,
                                     int nodesPoppedLimit) {
        testSingleConnectionHelper(partName,
                srcSiteName, srcPinName,
                dstSiteName, dstPinName,
                nodesPoppedLimit);
    }

    @Test
    public void testDiscussion1245_20250805() {
        // Adapted from https://github.com/Xilinx/RapidWright/discussions/1245#discussioncomment-14003055
        Design test_place = new Design("test_design", "vp1202");

        Cell cell_1 = test_place.createAndPlaceCell("my_test_cell_1", Unisim.LUT6, "SLICE_X342Y0/A6LUT");
        LUTTools.configureLUT(cell_1, "O=I1");
        cell_1.fixCell(true);

        Cell cell_2 = test_place.createAndPlaceCell("my_test_cell_2", Unisim.LUT6, "SLICE_X342Y0/C6LUT");
        LUTTools.configureLUT(cell_2, "O=I1");
        cell_2.fixCell(true);

        Net net = test_place.createNet("my_test_net");
        ECOTools.connectNet(test_place, cell_1, "O", net);
        ECOTools.connectNet(test_place, cell_2, "I1", net);

        Design test_route = test_place;

        test_route.routeSites();

        RWRoute.routeDesignFullNonTimingDriven(test_route);
    }

    private static int getCriticalPathDelay(String report) {
        Matcher matcher = Pattern.compile("Critical path delay \\(ps\\):\\s+(\\d+)").matcher(report);
        Assertions.assertTrue(matcher.find());
        return Integer.parseInt(matcher.group(1));
    }

    /**
     * Tests timing-driven routing with LUT pin swapping on a minimal design: a flop drives a LUT input
     * placed on the LUT's slowest pin (A1->O6 has a much larger logic delay than A6->O6), and the LUT
     * drives the flop back. Routing must weigh each candidate pin's LUT logic delay, rather than route
     * delay alone, so the input should move to one of the fastest pins; and the timing graph must
     * follow the pin that the connection is routed to, so that RWRoute's own critical path matches a
     * fresh timing analysis of the routed design.
     */
    @Test
    public void testTimingDrivenLutPinSwapping() {
        Design design = new Design("top", "xcvu3p");
        Cell lut = design.createAndPlaceCell("lut", Unisim.LUT1, "SLICE_X40Y61/A6LUT");
        LUTTools.configureLUT(lut, "O=I0");
        lut.removePinMapping(lut.getPhysicalPinMapping("I0"));
        lut.addPinMapping("A1", "I0");
        Cell ff = design.createAndPlaceCell("ff", Unisim.FDCE, "SLICE_X40Y60/AFF");

        Net q = design.createNet("q");
        ECOTools.connectNet(design, ff, "Q", q);
        ECOTools.connectNet(design, lut, "I0", q);
        Net o = design.createNet("o");
        ECOTools.connectNet(design, lut, "O", o);
        ECOTools.connectNet(design, ff, "D", o);
        design.routeSites();

        ByteArrayOutputStream routeCapture = new ByteArrayOutputStream();
        ByteArrayOutputStream reportCapture = new ByteArrayOutputStream();
        PrintStream originalOut = System.out;
        try {
            System.setOut(new PrintStream(routeCapture, true));
            RWRoute.routeDesignWithUserDefinedArguments(design, new String[] {"--lutPinSwapping"});
            System.setOut(new PrintStream(reportCapture, true));
            RWRouteConfig config = new RWRouteConfig(new String[0]);
            config.setTimingDriven(true);
            new TimingAndWirelengthReport(design, config, false).computeStatisticsAndReport();
        } finally {
            System.setOut(originalOut);
        }
        System.out.print(routeCapture);
        System.out.print(reportCapture);

        // The A1..A6 -> O6 logic delays are 150/125/100/90/50/35 ps, whereas routing to the different
        // input pins of the same LUT differs by much less (e.g. the A1..A6 site pin delays are 74..43 ps),
        // so the input must move to one of the two fastest pins
        String pin = lut.getPhysicalPinMapping("I0");
        Assertions.assertTrue(pin.equals("A5") || pin.equals("A6"), "I0 routed to pin " + pin);
        Assertions.assertEquals(getCriticalPathDelay(reportCapture.toString()),
                getCriticalPathDelay(routeCapture.toString()));
    }

    /**
     * Tests LUT pin swapping when one net drives two inputs of the same LUT: each of the two
     * connections may be routed to any free pin of the LUT, but not to the pin used by the other
     * (each LUT input needs its own pin), even though routing normally lets connections of the
     * same net share nodes.
     */
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    public void testLutPinSwappingSameNetTwoLutInputs(boolean timingDriven) {
        Design design = new Design("top", "xcvu3p");
        Cell lut = design.createAndPlaceCell("lut", Unisim.LUT2, "SLICE_X40Y61/A6LUT");
        LUTTools.configureLUT(lut, "O=I0&I1");
        Cell ff = design.createAndPlaceCell("ff", Unisim.FDCE, "SLICE_X40Y60/AFF");

        Net q = design.createNet("q");
        ECOTools.connectNet(design, ff, "Q", q);
        ECOTools.connectNet(design, lut, "I0", q);
        ECOTools.connectNet(design, lut, "I1", q);
        Net o = design.createNet("o");
        ECOTools.connectNet(design, lut, "O", o);
        ECOTools.connectNet(design, ff, "D", o);
        design.routeSites();

        RWRoute.routeDesignWithUserDefinedArguments(design, timingDriven
                ? new String[] {"--lutPinSwapping"}
                : new String[] {"--nonTimingDriven", "--lutPinSwapping"});

        assertAllPinsRouted(design);
        String i0 = lut.getPhysicalPinMapping("I0");
        String i1 = lut.getPhysicalPinMapping("I1");
        Assertions.assertNotNull(i0);
        Assertions.assertNotNull(i1);
        Assertions.assertNotEquals(i0, i1);
        Assertions.assertEquals(3, q.getPins().size());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    public void testDiscussion1245_20250807(boolean forceLagPin) {
        // Adapted from https://github.com/Xilinx/RapidWright/discussions/1245#discussioncomment-14035707

        Design test_place = new Design("test_design", "vp1202");

        Cell cell_1 = test_place.createAndPlaceCell("my_test_cell_1", Unisim.LUT6, "SLICE_X100Y0/A6LUT");
        LUTTools.configureLUT(cell_1, "O=I1");
        cell_1.fixCell(true);

        Cell cell_2 = test_place.createAndPlaceCell("my_test_cell_2", Unisim.LUT6, "SLICE_X100Y0/H6LUT");
        LUTTools.configureLUT(cell_2, "O=I1");
        cell_2.fixCell(true);

        Cell ff = test_place.createAndPlaceCell("flipflop", Unisim.FDCE, "SLICE_X100Y0/AFF");
        ff.fixCell(true);

        Net net = test_place.createNet("my_test_net_0");
        ECOTools.connectNet(test_place, cell_1, "O", net);
        ECOTools.connectNet(test_place, cell_2, "I1", net);

        Net outer_net_1 = test_place.createNet("my_test_net_1");
        ECOTools.connectNet(test_place, cell_2, "O", outer_net_1);
        ECOTools.connectNet(test_place, ff, "D", outer_net_1);

        Net outer_net_2 = test_place.createNet("my_test_net_2");
        ECOTools.connectNet(test_place, ff, "Q", outer_net_2);
        ECOTools.connectNet(test_place, cell_1, "I1", outer_net_2);

        Design test_route = test_place;

        test_route.routeSites();

        Assertions.assertEquals("SLICE_X100Y0.AX", ff.getSitePinFromLogicalPin("D", null).getSitePinName());

        // Test for intra-SLR connections to the LAG input
        if (forceLagPin) {
            SiteInst si = ff.getSiteInst();
            SitePinInst spi = si.getSitePinInst("AX");
            Assertions.assertTrue(si.unrouteIntraSiteNet(spi.getBELPin(), ff.getBEL().getPin("D")));
            Assertions.assertTrue(spi.movePin("LAG_E2"));
            Assertions.assertTrue(si.routeIntraSiteNet(spi.getNet(), spi.getBELPin(), ff.getBEL().getPin("D")));
        }

        RWRoute.routeDesignFullNonTimingDriven(test_route);

        VivadoToolsHelper.assertFullyRouted(test_route);
    }

    @Test
    public void testRWRouteSubstituteBlackBoxFlow(@TempDir Path dir) {
        // Load an example design, make sure RWRoute can route it
        Path dcp = RapidWrightDCP.getPath("optical-flow.dcp");
        Design design = Design.readCheckpoint(dcp);
        RWRoute.routeDesignFullNonTimingDriven(design);
        // VivadoToolsHelper.assertFullyRouted(design);
        
        // Pick a cell to blackbox
        String instToBlackBox = "bd_0_i/hls_inst/inst/Loop_FRAMES_CP_OUTER_U0";
        EDIFHierCellInst inst = design.getNetlist().getHierCellInstFromName(instToBlackBox);
        
        // Create example external library, store the guts of this netlist in external lib
        String cellType = "bd_0_hls_inst_0_Loop_FRAMES_CP_OUTER";
        EDIFNetlist external = EDIFTools.createNewNetlist(inst.getInst());
        Path externalEDIF = dir.resolve(cellType + ".edn");
        external.exportEDIF(externalEDIF);
        
        inst.getCellType().makePrimitive(); // makeBlackBox
        
        // Write out top EDIF that now has a black boxed cell
        Path opticalFlowTopEDIF = dir.resolve("optical-flow.edf");
        design.getNetlist().collapseMacroUnisims(design.getSeries());
        design.getNetlist().exportEDIF(opticalFlowTopEDIF);
        
        // Re-load DCP with black boxed netlist
        Design designWithBlackBox = Design.readCheckpoint(dcp, opticalFlowTopEDIF);

        // Load external lib, set it as the netlist's external library
        EDIFNetlist externalLib = EDIFTools.readEdifFile(externalEDIF);
        EDIFCell cell = designWithBlackBox.getNetlist().getCellInstFromHierName(instToBlackBox).getCellType();
        Assertions.assertTrue(cell.isLeafCellOrBlackBox() && !cell.isPrimitive());
        designWithBlackBox.getNetlist().setExternalLibrary(externalLib.getLibrary("work"));
        cell = designWithBlackBox.getNetlist().getCellInstFromHierName(instToBlackBox).getCellType();
        Assertions.assertFalse(cell.isLeafCellOrBlackBox());
        
        // Run RWRoute
        RWRoute.routeDesignFullNonTimingDriven(designWithBlackBox);

        // Sanity check on routing
        for (Net net : design.getNets()) {
            Net other = designWithBlackBox.getNet(net.getName());
            Assertions.assertNotNull(other);
            Assertions.assertEquals(net.getPins().size(), other.getPins().size());
            Assertions.assertEquals(net.hasPIPs(), other.hasPIPs());
        }

        // Return the netlist back to its original state for Vivado to read it correctly
        designWithBlackBox.getNetlist().blackBoxExternalCells();
        designWithBlackBox.getNetlist().setExternalLibrary(null);
        EDIFCell guts = externalLib.getCell(cellType);
        EDIFHierCellInst instToRestore = designWithBlackBox.getNetlist()
                .getHierCellInstFromName(instToBlackBox);
        designWithBlackBox.getNetlist().getWorkLibrary().removeCell(instToRestore.getCellType());
        designWithBlackBox.getNetlist().copyCellAndSubCells(guts);
        guts = designWithBlackBox.getNetlist().getWorkLibrary().getCell(cellType);
        instToRestore.getInst().setCellType(guts);

        VivadoToolsHelper.assertFullyRouted(designWithBlackBox);
    }

    @Test
    public void testRWRouteSubstituteNetlist(@TempDir Path dir) {
        if (FileTools.isVivadoOnPath() && FileTools.isVivadoAtLeastVersion(2024, 1)) {
            Path dcp = RapidWrightDCP.getPath("multiply_ip.dcp");
            Path outputLog = dir.resolve("output.log");
            Path tclScript = dir.resolve("run.tcl");
            Path subNetlist = dir.resolve("sub.edf");

            List<String> tclCmds = new ArrayList<>();
            tclCmds.add("open_checkpoint " + dcp.toString());
            tclCmds.add("source " + FileTools.getRapidWrightPath() + "/tcl/rapidwright.tcl");
            tclCmds.add("set cell_to_write [get_cells [lsort -unique [get_cells [get_property PARENT "
                    + "[get_cells -hierarchical -filter {is_du_within_envelope==1}]]]] "
                    + "-filter {is_du_within_envelope!=1}]");
            tclCmds.add("write_cell_to_edif $cell_to_write " + subNetlist.toString());
            FileTools.writeLinesToTextFile(tclCmds, tclScript.toString());
            VivadoTools.runTcl(outputLog, tclScript, true);

            Design d = Design.readCheckpoint(dcp);
            EDIFNetlist externalLib = EDIFTools.readEdifFile(subNetlist);
            d.getNetlist().setExternalLibrary(externalLib.getHDIPrimitivesLibrary());

            RWRoute.routeDesignFullNonTimingDriven(d);

            d.getNetlist().blackBoxExternalCells();
            d.getNetlist().setExternalLibrary(null);

            VivadoToolsHelper.assertFullyRouted(d);

        }
    }

    @Test
    public void testRWRouteVersalSLRCrossing() {
        Path dcp = RapidWrightDCP.getPath("versal_slr_crossing.dcp");

        Design design = Design.readCheckpoint(dcp);
        RWRoute.routeDesignFullNonTimingDriven(design);
        assertAllSourcesRoutedFlagSet(design);
        assertAllPinsRouted(design);
        VivadoToolsHelper.assertFullyRouted(design);
    }

    @ParameterizedTest
    @CsvSource({
            // Check that no routethru-s are used when terminating at the LAG pin to reach the FF inside the slice
            "SLICE_X95Y621,SLICE_X93Y546,LAG_N,3,false",
            "SLICE_X95Y621,SLICE_X92Y546,LAG_N,3, false",

            // Connecting to a non-LAG pin requires routethru
            "SLICE_X95Y621,SLICE_X93Y456,FX,100,true",
            "SLICE_X95Y621,SLICE_X92Y456,EX,100,true"
    })
    public void testRWRouteVersalSLRCrossingStraightIntoFlop(String srcSiteName, String dstSiteName, String dstPinName, int nodesPoppedLimit, boolean expectRoutethru) {
        Design design = testSingleConnectionHelper("xcv80",
                srcSiteName, "CQ",
                dstSiteName, dstPinName,
                nodesPoppedLimit);
        Net net = design.getNet("net");
        if (!expectRoutethru) {
            Assertions.assertEquals(6, net.getPIPs().size());
        }
        boolean routethruFound = false;
        for (PIP pip : net.getPIPs()) {
            routethruFound |= pip.isRouteThru();
        }
        Assertions.assertEquals(expectRoutethru, routethruFound);
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    public void testPartialRouterEnsureSinkRoutabilityStatic(boolean preserveInner) {
        Design design = new Design("top", "xcv80");

        Net net = design.createNet("net");
        SiteInst si = design.createSiteInst("SLICE_X95Y621");
        SitePinInst srcSpi = net.createPin("A_O", si);
        SitePinInst dstSpi = net.createPin("A_I", si);

        // Attach either the inner or outer connected node 
        // of the dstSpi above to the gnd net
        Net gndNet = design.getGndNet();
        Node preserveNode = dstSpi.getConnectedNode();
        if (!preserveInner) {
            List<Node> uphillNodes = preserveNode.getAllUphillNodes();
            Assertions.assertEquals(1, uphillNodes.size());
            preserveNode = uphillNodes.get(0);
        }
        List<PIP> uphillPIPs = preserveNode.getAllUphillPIPs();
        gndNet.addPIP(uphillPIPs.get(0));

        List<SitePinInst> pinsToRoute = new ArrayList<>();
        pinsToRoute.add(dstSpi);
        PartialRouter.routeDesignPartialNonTimingDriven(design, pinsToRoute);

        // Check that dstSpi does not get routed because its
        // inner or outer node is preserved for the ground net.
        // Check also that an assertion doesn't fire, but this test
        // is not able to verify that an "ERROR" message gets emitted.
        Assertions.assertFalse(dstSpi.isRouted());
        Assertions.assertFalse(net.hasPIPs());
    }

    @Test
    public void testPartialRouterPreservesOuterNode() {
        Design design = new Design("top", "xcv80");

        // This is the signal sink, the node(s) to which should not be claimed by static routing
        Net signalNet = design.createNet("net");
        SiteInst signalSi = design.createSiteInst("SLICE_X94Y621");
        SitePinInst signalSpi = signalNet.createPin("DX", signalSi);

        Device device = design.getDevice();
        Net Z_NET = design.createNet(Net.Z_NET);

        // Block these nodes so that there's only one path
        // Z_NET.addPIP(device.getNode("INT_X29Y625/INT_NODE_IMUX_ATOM_119_INT_OUT1").getAllUphillPIPs().get(0)); // Force signal through this node
        Z_NET.addPIP(device.getNode("INT_X29Y625/INT_NODE_IMUX_ATOM_122_INT_OUT0").getAllUphillPIPs().get(0));
        Z_NET.addPIP(device.getNode("INT_X29Y625/INT_NODE_IMUX_ATOM_125_INT_OUT0").getAllUphillPIPs().get(0));
        Z_NET.addPIP(device.getNode("INT_X29Y625/INT_NODE_IMUX_ATOM_53_INT_OUT1").getAllUphillPIPs().get(0));
        Z_NET.addPIP(device.getNode("INT_X29Y625/INT_NODE_IMUX_ATOM_57_INT_OUT1").getAllUphillPIPs().get(0));
        Z_NET.addPIP(device.getNode("INT_X29Y625/INT_NODE_IMUX_ATOM_60_INT_OUT0").getAllUphillPIPs().get(0));

        Z_NET.addPIP(device.getNode("SLL_X29Y625/BNODE_OUTS_E0").getAllUphillPIPs().get(0));
        // Z_NET.addPIP(device.getNode("INT_X29Y625/BOUNCE_W2").getAllUphillPIPs().get(0)); // Force signal through this node (which is signalSpi)
        Z_NET.addPIP(device.getNode("INT_X28Y625/OUT_EE1_E_BEG12").getAllUphillPIPs().get(0));
        Z_NET.addPIP(device.getNode("INT_X29Y626/OUT_SS4_W_BEG6").getAllUphillPIPs().get(0));
        Z_NET.addPIP(device.getNode("INT_X29Y626/OUT_SS1_W_BEG12").getAllUphillPIPs().get(0));
        Z_NET.addPIP(device.getNode("INT_X30Y625/OUT_WW2_W_BEG6").getAllUphillPIPs().get(0));

        Net gndNet = design.getGndNet();
        SiteInst gndSi = design.createSiteInst("SLICE_X95Y621");
        SitePinInst gndSpi = gndNet.createPin("HX", gndSi);
        PartialRouter.routeDesignPartialNonTimingDriven(design, Collections.singletonList(gndSpi));

        // Make sure the ground sink is not routed, since INT_X29Y625/BOUNCE_W2
        // is now reserved for signalSpi
        Assertions.assertFalse(gndSpi.isRouted());
        Assertions.assertFalse(gndNet.hasPIPs());
    }

    /**
     * Checks that PartialRouter preserves a signal net that Vivado's high-fanout net
     * optimization has routed through a leaf clock buffer (BUFCE_LEAF). Such a net leaves
     * the interconnect for the RCLK_INT_L tile, passes through the buffer's CE input
     * (NODE_PINFEED) onto its clock output (NODE_GLOBAL_LEAF), and re-enters the
     * interconnect on a GCLK node.
     *
     * This covers the NODE_PINFEED and NODE_GLOBAL_LEAF cases of
     * RouteNode.setBaseCost(); see testPartialRouterPreservesNodeGlobalHdistr() for the
     * third.
     *
     * RCLK tiles are excluded from the routing graph, so nodes inside them exist only to
     * carry the previous pointers of an already-routed net. Without the excluded-tile early
     * return in RouteNodeInfo.getType(), the NODE_PINFEED node falls through to an
     * eastWestWires lookup that has no entry for RCLK_INT_L and throws a
     * NullPointerException; without those two intent codes in RouteNode.setBaseCost(), the
     * same nodes hit its default case and throw
     * "ERROR: Unexpected IntentCode: NODE_PINFEED"/"NODE_GLOBAL_LEAF".
     *
     * The net below is taken verbatim from a real routed design.
     */
    @Test
    public void testPartialRouterPreservesNodeGlobalLeaf() {
        Design design = new Design("top", "xczu3eg");

        // Source and sink are both on this site: the net loops out through the leaf
        // clock buffer and back into the same SLICE
        SiteInst si = design.createSiteInst("SLICE_X9Y90");
        Net net = CodeGenerator.createTestNet(design, "net", new String[]{
                "INT_X6Y90/INT.LOGIC_OUTS_W7->INT_NODE_SDQ_55_INT_OUT1",
                "INT_X6Y90/INT.INT_NODE_SDQ_55_INT_OUT1->>SS1_W_BEG2",
                "RCLK_INT_L_X6Y89/RCLK_INT_L.SOUTHBUSOUT_FT0_27->>INT_NODE_IMUX_13_INT_OUT0",
                "RCLK_INT_L_X6Y89/RCLK_INT_L.INT_NODE_IMUX_13_INT_OUT0->>CLK_LEAF_SITES_30_CE_INT",     // NODE_PINFEED (BUFCE_LEAF.CE_INT)
                "RCLK_INT_L_X6Y89/RCLK_INT_L.CLK_LEAF_SITES_30_CE_INT->>CLK_LEAF_SITES_30_CLK_LEAF",    // NODE_GLOBAL_LEAF
                "INT_X6Y90/INT.GCLK_B_0_15->>INT_NODE_GLOBAL_12_INT_OUT0",
                "INT_X6Y90/INT.INT_NODE_GLOBAL_12_INT_OUT0->>INT_NODE_IMUX_61_INT_OUT1",
                "INT_X6Y90/INT.INT_NODE_IMUX_61_INT_OUT1->>BOUNCE_W_13_FT0"
        });
        net.createPin("EQ2", si);
        SitePinInst routedSpi = net.createPin("F_I", si);
        routedSpi.setRouted(true);

        // An additional, unrouted sink so that the router must also expand (and thus
        // exercise the routing graph) rather than only recover the existing routing
        SiteInst unroutedSi = design.createSiteInst("SLICE_X9Y92");
        SitePinInst unroutedSpi = net.createPin("A1", unroutedSi);

        assertPartialRouterAugmentsRouting(design, net, routedSpi, unroutedSpi);
    }

    /**
     * As testPartialRouterPreservesNodeGlobalLeaf(), but for a net that Vivado has
     * additionally routed through a horizontal distribution buffer (BUFCE_ROW_FSR), whose
     * output node is a NODE_GLOBAL_HDISTR in RCLK_BRAM_INTF_L. This net crosses all three
     * of the intent codes that RouteNode.setBaseCost() has to accept on preserved routing,
     * and is the only one of the two to reach NODE_GLOBAL_HDISTR.
     *
     * The net below is the source-to-sink path of one connection of a real routed net.
     */
    @Test
    public void testPartialRouterPreservesNodeGlobalHdistr() {
        Design design = new Design("top", "xczu3eg");

        SiteInst srcSi = design.createSiteInst("SLICE_X36Y149");
        SiteInst dstSi = design.createSiteInst("SLICE_X37Y148");
        Net net = CodeGenerator.createTestNet(design, "net", new String[]{
                "INT_X23Y149/INT.LOGIC_OUTS_E30->SDQNODE_E_93_FT0",
                "INT_X23Y150/INT.SDQNODE_E_BLN_93_FT1->>WW2_E_BEG0",
                "INT_X22Y149/INT.WW2_E_BLS_0_FT0->INT_NODE_SDQ_47_INT_OUT0",
                "INT_X22Y149/INT.INT_NODE_SDQ_47_INT_OUT0->>EE1_E_BEG7",
                "INT_X23Y149/INT.EE1_E_END7->INT_NODE_SDQ_93_INT_OUT0",
                "INT_X23Y149/INT.INT_NODE_SDQ_93_INT_OUT0->>NN1_W_BEG7",
                "RCLK_INT_L_X23Y149/RCLK_INT_L.NORTHBUSOUT_FT0_28->>INT_NODE_IMUX_23_INT_OUT1",
                "RCLK_INT_L_X23Y149/RCLK_INT_L.INT_NODE_IMUX_23_INT_OUT1->>INT_RCLK_TO_CLK_LEFT_0_0",                           // NODE_PINFEED (BUFCE_ROW_FSR.CE_PRE_OPTINV)
                "RCLK_BRAM_INTF_L_X23Y149/RCLK_BRAM_INTF_L.CLK_BUFCE_ROW_FSR_0_CE_PRE_OPTINV->>CLK_BUFCE_ROW_FSR_0_CLK_OUT",    // NODE_GLOBAL_HDISTR
                "RCLK_BRAM_INTF_L_X23Y149/RCLK_BRAM_INTF_L.CLK_BUFCE_ROW_FSR_0_CLK_OUT->>CLK_TEST_BUF_SITE_1_CLK_IN",           // NODE_GLOBAL_HDISTR
                "RCLK_INT_L_X24Y149/RCLK_INT_L.CLK_HDISTR_FT0_7->>CLK_LEAF_SITES_15_CLK_IN",                                    // INTENT_DEFAULT (BUFCE_LEAF.CLK_IN)
                "RCLK_INT_L_X24Y149/RCLK_INT_L.CLK_LEAF_SITES_15_CLK_IN->>CLK_LEAF_SITES_15_CLK_LEAF",                          // NODE_GLOBAL_LEAF
                "INT_X24Y149/INT.GCLK_B_0_9->>INT_NODE_GLOBAL_13_INT_OUT0",
                "INT_X24Y149/INT.INT_NODE_GLOBAL_13_INT_OUT0->>INT_NODE_IMUX_62_INT_OUT1",
                "INT_X24Y149/INT.INT_NODE_IMUX_62_INT_OUT1->>BYPASS_W1",
                "INT_X24Y149/INT.BYPASS_W1->>INT_NODE_IMUX_38_INT_OUT0",
                "INT_X24Y149/INT.INT_NODE_IMUX_38_INT_OUT0->>BYPASS_W6",
                "INT_X24Y149/INT.BYPASS_W6->>INODE_W_9_FT1",
                "INT_X24Y148/INT.INODE_W_BLS_9_FT0->>BOUNCE_W_15_FT0"
        });
        net.createPin("EQ", srcSi);
        SitePinInst routedSpi = net.createPin("H_I", dstSi);
        routedSpi.setRouted(true);

        // An additional, unrouted sink so that the router must also expand (and thus
        // exercise the routing graph) rather than only recover the existing routing
        SiteInst unroutedSi = design.createSiteInst("SLICE_X37Y151");
        SitePinInst unroutedSpi = net.createPin("A1", unroutedSi);

        assertPartialRouterAugmentsRouting(design, net, routedSpi, unroutedSpi);
    }

    /**
     * Asks PartialRouter to route the unrouted sink of an otherwise already-routed net, and
     * checks that the routing it hands back is a strict superset of the routing it was
     * given: every existing PIP must be preserved -- thus the clock buffer routethru that
     * the already-routed sink is reached through -- and new PIPs must have been added to
     * reach the unrouted sink.
     * @param design The design holding the net.
     * @param net The partially-routed net.
     * @param routedSpi The net's already-routed sink pin, not given to the router.
     * @param unroutedSpi The net's unrouted sink pin; the only pin given to the router.
     */
    private void assertPartialRouterAugmentsRouting(Design design, Net net, SitePinInst routedSpi,
                                                    SitePinInst unroutedSpi) {
        List<PIP> expectedPIPs = new ArrayList<>(net.getPIPs());
        Assertions.assertFalse(expectedPIPs.isEmpty());
        Assertions.assertTrue(routedSpi.isRouted());
        Assertions.assertFalse(unroutedSpi.isRouted());

        PartialRouter.routeDesignPartialNonTimingDriven(design, Collections.singletonList(unroutedSpi));

        for (SitePinInst spi : net.getSinkPins()) {
            Assertions.assertTrue(spi.isRouted());
        }
        // The router is free to hand the routing back in a different order
        List<PIP> actualPIPs = net.getPIPs();
        Assertions.assertTrue(new HashSet<>(actualPIPs).containsAll(expectedPIPs));
        Assertions.assertTrue(actualPIPs.size() > expectedPIPs.size());

        // Note that the newly-routed sink is not expected to share this net's routing through
        // the clock buffer, no matter where it is placed. The buffer's output node (a
        // NODE_GLOBAL_LEAF, or a NODE_GLOBAL_HDISTR for the row distribution buffer) spans the
        // full height/width that it distributes to -- e.g. RCLK_INT_L_X6Y89's
        // CLK_LEAF_SITES_30_CLK_LEAF ends at INT_X6Y119, the top of its clock region -- and
        // RouteNode.isInConnectionBoundingBox() tests the end tile coordinate, which thus lies
        // far outside the bounding box of any connection that a sink near the buffer could
        // create. Reaching the buffer is not necessary here: this test only requires that such
        // routing be preserved, not extended.
    }
}

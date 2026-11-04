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
import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Random;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Assertions;
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
import com.xilinx.rapidwright.device.Site;
import com.xilinx.rapidwright.eco.ECOTools;
import com.xilinx.rapidwright.edif.EDIFCell;
import com.xilinx.rapidwright.edif.EDIFCellInst;
import com.xilinx.rapidwright.edif.EDIFHierCellInst;
import com.xilinx.rapidwright.edif.EDIFNet;
import com.xilinx.rapidwright.edif.EDIFNetlist;
import com.xilinx.rapidwright.edif.EDIFTools;
import com.xilinx.rapidwright.interchange.Interchange;
import com.xilinx.rapidwright.support.LargeTest;
import com.xilinx.rapidwright.support.RapidWrightDCP;
import com.xilinx.rapidwright.timing.delayestimator.DelayEstimatorBase;
import com.xilinx.rapidwright.util.FileTools;
import com.xilinx.rapidwright.util.Pair;
import com.xilinx.rapidwright.util.ReportRouteStatusResult;
import com.xilinx.rapidwright.util.Utils;
import com.xilinx.rapidwright.util.VivadoTools;
import com.xilinx.rapidwright.util.VivadoToolsHelper;

public class TestRWRoute {
    static void assertAllPinsRouted(Net net) {
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
     * it. It is loose where moves cannot follow each other as freely as here: which SDQ nodes and INODE feedthroughs a
     * wire's end reaches depends on the member (BEG index) of its family, e.g. on xcku040 and xcku115 the INODE
     * feedthroughs that NN2_[EW]_BEG7 (3 rows) reaches lead back a row south, and those that NN2_[EW]_BEG6 reaches lead
     * a row north; and which members a CLE output reaches depends on the output, e.g. on xcku040 SLICE_X51Y120's H_O
     * reaches NN5_BEG7 (6 rows) through an SDQ node, but its A_O and AQ do not. So on xcku040, 7 rows north in the
     * same half tile column (3.4: NN2_[EW]_BEG7 twice, then an INODE feedthrough a row north), it was attained by none
     * of the connections from A_O or AQ of SLICE_X50 or SLICE_X51, Y100 to Y140, to the 48 LUT inputs there, nor
     * from any of 24 LUT and flop outputs of SLICE_X50Y120 or SLICE_X51Y120.
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
     * cannot directly follow a vertical quad or long or a horizontal long. An exact search found no cheaper route for
     * the existing SLR-crossing tests' connections (and testCostWithinLowerBoundNonTimingDriven()'s), all of whose cheapest routes cross this way, nor for 1,533 more on
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
     * search under the same costs). All are RWRoute misses: the cheapest route attains the bound, but RWRoute's costs
     * more. In each, near the source RWRoute takes a wire that looks as cheap or cheaper one wire ahead (it travels a
     * row further, or lands on the other half), but the wires that follow it cost more; the lower bounds of the nodes
     * near the source are then too high (being estimates that exceed the true cost) for the search to come back to
     * them.
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
     * so the bound is known to be achievable. Which wires the route takes does not matter, so neither does how ties
     * are broken.
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
            "xcku040-ffva1156-2-e,SLICE_X51Y120.A_O,SLICE_X50Y113.H6",     // -1,-7; bound 3.6
            "xcku040-ffva1156-2-e,SLICE_X51Y120.A_O,SLICE_X52Y120.A6",     // 1,0; bound 1.7
            "xcku040-ffva1156-2-e,SLICE_X51Y120.AQ,SLICE_X51Y122.A6",      // 0,2; bound 2.0
            "xcku040-ffva1156-2-e,SLICE_X51Y120.AQ,SLICE_X50Y113.H6",      // -1,-7; bound 3.6
            "xcku040-ffva1156-2-e,SLICE_X51Y120.AQ,SLICE_X52Y120.A6",      // 1,0; bound 1.7
            "xcku040-ffva1156-2-e,SLICE_X50Y120.A_O,SLICE_X50Y122.A6",     // 0,2; bound 2.0
            "xcku040-ffva1156-2-e,SLICE_X50Y120.A_O,SLICE_X50Y97.C2",      // 0,-23; bound 8.0
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

    /**
     * Routes a single connection timing-driven with PartialRouter, as PartialRouter.routeDesignPartialTimingDriven()
     * (with any further arguments), between placed cells: from a LUT6's output ([A-H]_O) or an FDRE's ([A-H]Q, or
     * [A-H]Q2 on the second flop) to an input of a LUT6 that drives the flop beside it ([A-H]FF), through an
     * intra-site net, so that the connection is on a timing path (flop or LUT to flop). PartialRouter times that
     * intra-site net too (getTimingNets()), so that the connection's only timing path is the critical path, and it
     * gets RWRoute's MAX_CRITICALITY (0.99).
     * @param srcSitePin The source pin, as "SITE.PIN".
     * @param dstSitePin The sink pin (a LUT input, [A-H][1-6]), as "SITE.PIN".
     * @param args Further arguments for RWRouteConfig (e.g. "--timingWeight", "0.5").
     * @return The router, whose only connection holds the route (see getRoute()).
     */
    static PartialRouter routeSingleConnectionTimingDriven(String partName, String srcSitePin, String dstSitePin,
                                                           String... args) {
        Design design = new Design("top", partName);
        String[] src = srcSitePin.split("\\.");
        String[] dst = dstSitePin.split("\\.");
        Assertions.assertNotEquals(src[0], dst[0]);
        Cell srcCell;
        if (src[1].matches("[A-H]_O")) {
            srcCell = design.createAndPlaceCell("src", Unisim.LUT6, src[0] + "/" + src[1].charAt(0) + "6LUT");
            LUTTools.configureLUT(srcCell, "O=I0");
        } else {
            Assertions.assertTrue(src[1].matches("[A-H]Q2?"), src[1]);
            srcCell = design.createAndPlaceCell("src", Unisim.FDRE,
                    src[0] + "/" + src[1].charAt(0) + (src[1].endsWith("2") ? "FF2" : "FF"));
        }
        Assertions.assertTrue(dst[1].matches("[A-H][1-6]"), dst[1]);
        Cell lut = design.createAndPlaceCell("dst", Unisim.LUT6, dst[0] + "/" + dst[1].charAt(0) + "6LUT");
        String lutInput = "I" + (dst[1].charAt(1) - '1');
        LUTTools.configureLUT(lut, "O=" + lutInput);
        Cell ff = design.createAndPlaceCell("ff", Unisim.FDRE, dst[0] + "/" + dst[1].charAt(0) + "FF");
        Net net = design.createNet("net");
        ECOTools.connectNet(design, srcCell, src[1].endsWith("_O") ? "O" : "Q", net);
        ECOTools.connectNet(design, lut, lutInput, net);
        Net lutToFf = design.createNet("lut_to_ff");
        ECOTools.connectNet(design, lut, "O", lutToFf);
        ECOTools.connectNet(design, ff, "D", lutToFf);
        Assertions.assertTrue(lutToFf.getSinkPins().isEmpty());
        SitePinInst dstSpi = net.getSinkPins().get(0);
        Assertions.assertEquals(srcSitePin, net.getSource().getSiteInstName() + "." + net.getSource().getName());
        Assertions.assertEquals(dstSitePin, dstSpi.getSiteInstName() + "." + dstSpi.getName());
        String[] configArgs = Arrays.copyOf(new String[] {"--fixBoundingBox", "--useUTurnNodes", "--verbose"},
                3 + args.length);
        System.arraycopy(args, 0, configArgs, 3, args.length);
        PartialRouter router = new PartialRouter(design, new RWRouteConfig(configArgs),
                Collections.singletonList(dstSpi), false);
        RWRoute.routeDesign(router);
        Assertions.assertTrue(dstSpi.isRouted());
        return router;
    }

    /**
     * Gets a route's delay as RWRoute computes it (Connection.getRouteDelay()): the sum of its nodes' delays
     * (RouteNode.getDelay()), plus DelayEstimatorBase.getExtraDelay() for each long that follows a long.
     * @param route The route's nodes, from source to sink.
     * @return That delay, in ps.
     */
    static float getRouteDelay(List<RouteNode> route) {
        float delay = route.get(0).getDelay();
        for (int i = 1; i < route.size(); i++) {
            RouteNode rnode = route.get(i);
            delay += rnode.getDelay() + DelayEstimatorBase.getExtraDelay(rnode,
                    DelayEstimatorBase.isLong(route.get(i - 1)));
        }
        return delay;
    }

    /**
     * Finds a fastest route of a router's only connection (by getRouteDelay()), with an exact search: a Dijkstra from
     * its source to its sink, by delay alone, over RWRoute's routing graph. It takes a node's children from
     * RouteNode.getChildren() and keeps those that RWRoute's search could push (as RWRoute.exploreAndExpand()) but for
     * the connection's bounding box: the sink, and any other node that is not an exclusive sink and that
     * RouteNodeGraph.isAccessible() accepts. So no route in that graph is faster, even one that leaves the bounding box
     * (as was found, 6 ps faster, for one of 2,360 connections on xcvu9p). Of RWRoute's search, it shares nothing else:
     * not its costs, lower bounds, bounding box or queue order, and it may reach a node again by a faster path.
     * @param router The router, after routing a connection that does not cross SLRs.
     * @return A fastest route's nodes, from source to sink.
     */
    static List<RouteNode> getFastestRoute(RWRoute router) {
        Assertions.assertEquals(1, router.indirectConnections.size());
        RouteNodeGraph graph = router.routingGraph;
        Connection connection = router.indirectConnections.get(0);
        // Every node that a crossing takes is accessible only to a connection that crosses SLRs, for which
        // RouteNodeGraph.isAccessible() reads the path (RouteNode.getPrev()) that this search does not set
        Assertions.assertFalse(connection.isCrossSLR());
        RouteNode source = connection.getSourceRnode();
        RouteNode sink = connection.getSinkRnode();
        Map<RouteNode, Float> delays = new HashMap<>();
        Map<RouteNode, RouteNode> parents = new HashMap<>();
        PriorityQueue<Map.Entry<Float, RouteNode>> queue = new PriorityQueue<>(Map.Entry.comparingByKey());
        delays.put(source, source.getDelay());
        queue.add(new AbstractMap.SimpleImmutableEntry<>(source.getDelay(), source));
        while (!queue.isEmpty()) {
            Map.Entry<Float, RouteNode> entry = queue.poll();
            float delay = entry.getKey();
            RouteNode rnode = entry.getValue();
            if (rnode == sink) {
                break;
            }
            if (delay > delays.get(rnode)) {
                // Reached again by a faster path since
                continue;
            }
            boolean longParent = DelayEstimatorBase.isLong(rnode);
            for (RouteNode child : rnode.getChildren(graph)) {
                if (child != sink && (child.getType().isAnyExclusiveSink() ||
                        !graph.isAccessible(child, rnode, connection))) {
                    continue;
                }
                float childDelay = delay + child.getDelay() + DelayEstimatorBase.getExtraDelay(child, longParent);
                Float known = delays.get(child);
                if (known == null || childDelay < known) {
                    delays.put(child, childDelay);
                    parents.put(child, rnode);
                    queue.add(new AbstractMap.SimpleImmutableEntry<>(childDelay, child));
                }
            }
        }
        Assertions.assertTrue(delays.containsKey(sink));
        List<RouteNode> route = new ArrayList<>();
        for (RouteNode rnode = sink; rnode != null; rnode = parents.get(rnode)) {
            route.add(rnode);
        }
        Collections.reverse(route);
        return route;
    }

    /**
     * Describes a route for a message: each node's INT tile coordinates, wire name and delay (as getRouteDelay()).
     */
    private static String describeRoute(List<RouteNode> route) {
        StringBuilder s = new StringBuilder();
        for (int i = 0; i < route.size(); i++) {
            RouteNode rnode = route.get(i);
            float delay = rnode.getDelay() + ((i == 0) ? 0 : DelayEstimatorBase.getExtraDelay(rnode,
                    DelayEstimatorBase.isLong(route.get(i - 1))));
            s.append(i == 0 ? "" : ", ").append(rnode.getTile().getName()).append('/').append(rnode.getWireName());
            if (delay != 0) {
                s.append(" (").append((int) delay).append(')');
            }
        }
        return s.toString();
    }

    /**
     * The delay (see getRouteDelay()) expected of RWRoute's route on those rows of
     * testDelayWithinFastestTimingDriven() where it is more than {@link #LOWER_BOUND_LEEWAY} slower than the fastest
     * route, keyed by the row ("part,src,dst", with ",timingWeight" if given); each entry is to give both routes and
     * why. None at present.
     */
    private static final Map<String, Float> DELAY_KNOWN_EXCESS = new HashMap<>();

    /**
     * Tests that RWRoute finds a fast route for a single uncongested critical connection, routed timing-driven: one
     * whose delay, under RWRoute's own delay model (getRouteDelay(), as Connection.getRouteDelay()), is within
     * {@link #LOWER_BOUND_LEEWAY} of the least delay of any route in RWRoute's routing graph (getFastestRoute()), or of
     * its known excess over it ({@link #DELAY_KNOWN_EXCESS}). The counterpart of
     * testCostWithinLowerBoundNonTimingDriven(), on UltraScale+ only: RWRoute routes timing-driven only with timing
     * data, which only UltraScale+ has (in $RAPIDWRIGHT_PATH/timing).
     * <p>
     * Each connection is routed with RWRoute's default timing-driven configuration as PartialRouter applies it
     * (routeDesignPartialTimingDriven()), between placed cells, its sink LUT driving a flop (see
     * routeSingleConnectionTimingDriven()), so that it is on a timing path and gets RWRoute's MAX_CRITICALITY (0.99),
     * as asserted. Its delay rather than its combined cost is checked: delay is what a timing-driven route is for, and
     * the least delay does not depend on RWRoute's weights, so that the same check applies at another timing weight
     * (the fourth field, else the default).
     * <p>
     * The reference is the exact least delay, not a lower bound from a hand-built model of the fabric (as
     * getLowerBound()): priced by delay, such a model was found either not to be a lower bound (fabric features such as
     * INODE and SDQ node feedthroughs, NN4/SS4 members that travel 5 rows and a WW1_W member that lands a row north
     * give faster routes than it allows) or, with those features allowed everywhere, so loose that RWRoute was more
     * than 5% above it on all but one of these rows. What this checks is RWRoute's search (its costs, lower bounds,
     * bounding box and queue) against a plain Dijkstra that shares none of them; not RWRoute's routing graph or delays,
     * which both use.
     * <p>
     * The rows lie in xcvu9p's SLR1, between the RCLK rows below INT row 450 and below row 510, across whose RCLK row a
     * vertical wire has a greater delay; neither RWRoute's route nor the fastest route of any row crosses one. Sources
     * are in row 480, in the middle, on either half of INT_X83 (SLICE_X129 on its west half, SLICE_X130 on its east),
     * so that sinks lie up to 24 rows away in either direction, and in INT_X79 to INT_X86, whose columns have no BRAM,
     * DSP or URAM column between them; the rows cover every direction at several distances (half tiles, rows), from LUT
     * and flop outputs, to inputs of all eight LUTs and of all six numbers.
     * <p>
     * TODO: rows that cross an SLR boundary, once super long lines have a delay. RouterHelper.computeNodeDelay() gives
     * a delay only to nodes for which RouteNode.isExitNode() holds, which a super long line is not, so that a
     * crossing's delay is not yet meaningful.
     * @param partName Part.
     * @param srcSitePin Source pin, as "SITE.PIN": a LUT output ([A-H]_O) or a flop output ([A-H]Q).
     * @param dstSitePin Sink pin, as "SITE.PIN": a LUT input ([A-H][1-6]).
     * @param timingWeight RWRoute's timing weight ("--timingWeight"), or null for its default.
     */
    @ParameterizedTest
    @CsvSource({
            // Each row's comment gives the source-sink distance (half tiles, rows) and the least delay (ps).
            // North and south, on the west half (SLICE_X129, INT_X83)
            "xcvu9p-flgb2104-2-i,SLICE_X129Y480.A_O,SLICE_X129Y481.A1,",     // 0,1; least 185
            "xcvu9p-flgb2104-2-i,SLICE_X129Y480.A_O,SLICE_X129Y482.B2,",     // 0,2; least 171
            "xcvu9p-flgb2104-2-i,SLICE_X129Y480.A_O,SLICE_X129Y484.D4,",     // 0,4; least 189
            "xcvu9p-flgb2104-2-i,SLICE_X129Y480.A_O,SLICE_X129Y486.F6,",     // 0,6; least 203
            "xcvu9p-flgb2104-2-i,SLICE_X129Y480.A_O,SLICE_X129Y492.A6,",     // 0,12; least 271
            "xcvu9p-flgb2104-2-i,SLICE_X129Y480.A_O,SLICE_X129Y504.D3,",     // 0,24; least 362
            "xcvu9p-flgb2104-2-i,SLICE_X129Y480.A_O,SLICE_X129Y479.E2,",     // 0,-1; least 164
            "xcvu9p-flgb2104-2-i,SLICE_X129Y480.A_O,SLICE_X129Y478.F1,",     // 0,-2; least 127
            "xcvu9p-flgb2104-2-i,SLICE_X129Y480.A_O,SLICE_X129Y476.H3,",     // 0,-4; least 212
            "xcvu9p-flgb2104-2-i,SLICE_X129Y480.A_O,SLICE_X129Y474.B2,",     // 0,-6; least 225
            "xcvu9p-flgb2104-2-i,SLICE_X129Y480.A_O,SLICE_X129Y468.E5,",     // 0,-12; least 265
            "xcvu9p-flgb2104-2-i,SLICE_X129Y480.A_O,SLICE_X129Y456.H6,",     // 0,-24; least 350
            // North and south, on the east half (SLICE_X130, INT_X83)
            "xcvu9p-flgb2104-2-i,SLICE_X130Y480.AQ,SLICE_X130Y483.H6,",      // 0,3; least 207
            "xcvu9p-flgb2104-2-i,SLICE_X130Y480.AQ,SLICE_X130Y485.B5,",      // 0,5; least 151
            "xcvu9p-flgb2104-2-i,SLICE_X130Y480.AQ,SLICE_X130Y488.D3,",      // 0,8; least 254
            "xcvu9p-flgb2104-2-i,SLICE_X130Y480.AQ,SLICE_X130Y490.E2,",      // 0,10; least 228
            "xcvu9p-flgb2104-2-i,SLICE_X130Y480.AQ,SLICE_X130Y496.G6,",      // 0,16; least 298
            "xcvu9p-flgb2104-2-i,SLICE_X130Y480.AQ,SLICE_X130Y500.H3,",      // 0,20; least 299
            "xcvu9p-flgb2104-2-i,SLICE_X130Y480.AQ,SLICE_X130Y477.D4,",      // 0,-3; least 143
            "xcvu9p-flgb2104-2-i,SLICE_X130Y480.AQ,SLICE_X130Y475.F6,",      // 0,-5; least 150
            "xcvu9p-flgb2104-2-i,SLICE_X130Y480.AQ,SLICE_X130Y472.H6,",      // 0,-8; least 249
            "xcvu9p-flgb2104-2-i,SLICE_X130Y480.AQ,SLICE_X130Y470.A6,",      // 0,-10; least 283
            "xcvu9p-flgb2104-2-i,SLICE_X130Y480.AQ,SLICE_X130Y464.C4,",      // 0,-16; least 297
            "xcvu9p-flgb2104-2-i,SLICE_X130Y480.AQ,SLICE_X130Y460.D3,",      // 0,-20; least 340
            // West, and to the other half of the same INT tile
            "xcvu9p-flgb2104-2-i,SLICE_X130Y480.A_O,SLICE_X129Y480.G6,",     // -1,0; least 132
            "xcvu9p-flgb2104-2-i,SLICE_X129Y480.A_O,SLICE_X128Y480.H3,",     // -1,0; least 159
            "xcvu9p-flgb2104-2-i,SLICE_X129Y480.A_O,SLICE_X127Y480.G6,",     // -2,0; least 149
            "xcvu9p-flgb2104-2-i,SLICE_X129Y480.A_O,SLICE_X126Y480.F1,",     // -3,0; least 180
            "xcvu9p-flgb2104-2-i,SLICE_X129Y480.A_O,SLICE_X125Y480.E2,",     // -4,0; least 202
            "xcvu9p-flgb2104-2-i,SLICE_X129Y480.A_O,SLICE_X124Y480.D3,",     // -5,0; least 214
            "xcvu9p-flgb2104-2-i,SLICE_X129Y480.A_O,SLICE_X123Y480.C4,",     // -6,0; least 182
            "xcvu9p-flgb2104-2-i,SLICE_X129Y480.A_O,SLICE_X122Y480.B5,",     // -7,0; least 228
            // East, and to the other half of the same INT tile
            "xcvu9p-flgb2104-2-i,SLICE_X129Y480.AQ,SLICE_X130Y480.H6,",      // 1,0; least 151
            "xcvu9p-flgb2104-2-i,SLICE_X130Y480.AQ,SLICE_X131Y480.G1,",      // 1,0; least 185
            "xcvu9p-flgb2104-2-i,SLICE_X130Y480.AQ,SLICE_X132Y480.H6,",      // 2,0; least 168
            "xcvu9p-flgb2104-2-i,SLICE_X130Y480.AQ,SLICE_X133Y480.A6,",      // 3,0; least 214
            "xcvu9p-flgb2104-2-i,SLICE_X130Y480.AQ,SLICE_X134Y480.B5,",      // 4,0; least 164
            "xcvu9p-flgb2104-2-i,SLICE_X130Y480.AQ,SLICE_X135Y480.C4,",      // 5,0; least 210
            "xcvu9p-flgb2104-2-i,SLICE_X130Y480.AQ,SLICE_X136Y480.D3,",      // 6,0; least 251
            // North-east
            "xcvu9p-flgb2104-2-i,SLICE_X129Y480.HQ,SLICE_X132Y483.G1,",      // 3,3; least 230
            "xcvu9p-flgb2104-2-i,SLICE_X130Y480.E_O,SLICE_X134Y487.F1,",     // 4,7; least 312
            "xcvu9p-flgb2104-2-i,SLICE_X129Y480.HQ,SLICE_X134Y493.A1,",      // 5,13; least 327
            "xcvu9p-flgb2104-2-i,SLICE_X130Y480.E_O,SLICE_X136Y502.H6,",     // 6,22; least 474
            // North-west
            "xcvu9p-flgb2104-2-i,SLICE_X130Y480.E_O,SLICE_X127Y483.E2,",     // -3,3; least 255
            "xcvu9p-flgb2104-2-i,SLICE_X129Y480.HQ,SLICE_X127Y487.H3,",      // -2,7; least 268
            "xcvu9p-flgb2104-2-i,SLICE_X130Y480.E_O,SLICE_X125Y493.G1,",     // -5,13; least 392
            "xcvu9p-flgb2104-2-i,SLICE_X129Y480.HQ,SLICE_X122Y502.B2,",      // -7,22; least 470
            // South-east
            "xcvu9p-flgb2104-2-i,SLICE_X129Y480.HQ,SLICE_X134Y477.C3,",      // 5,-3; least 230
            "xcvu9p-flgb2104-2-i,SLICE_X130Y480.E_O,SLICE_X132Y473.B5,",     // 2,-7; least 264
            "xcvu9p-flgb2104-2-i,SLICE_X129Y480.HQ,SLICE_X136Y467.E2,",      // 7,-13; least 391
            "xcvu9p-flgb2104-2-i,SLICE_X130Y480.E_O,SLICE_X134Y458.D4,",     // 4,-22; least 383
            // South-west
            "xcvu9p-flgb2104-2-i,SLICE_X130Y480.E_O,SLICE_X127Y477.A1,",     // -3,-3; least 276
            "xcvu9p-flgb2104-2-i,SLICE_X129Y480.HQ,SLICE_X125Y473.D3,",      // -4,-7; least 293
            "xcvu9p-flgb2104-2-i,SLICE_X130Y480.E_O,SLICE_X125Y467.C4,",     // -5,-13; least 340
            "xcvu9p-flgb2104-2-i,SLICE_X129Y480.HQ,SLICE_X122Y458.F6,",      // -7,-22; least 460
            // At a higher timing weight than the default
            "xcvu9p-flgb2104-2-i,SLICE_X129Y480.A_O,SLICE_X129Y462.A6,0.5",  // 0,-18; least 243
            "xcvu9p-flgb2104-2-i,SLICE_X130Y480.AQ,SLICE_X136Y480.A1,0.5",   // 6,0; least 212
            "xcvu9p-flgb2104-2-i,SLICE_X130Y480.E_O,SLICE_X134Y493.C3,0.5",  // 4,13; least 301
            "xcvu9p-flgb2104-2-i,SLICE_X129Y480.AQ,SLICE_X129Y502.A4,0.5",   // 0,22; least 305
    })
    public void testDelayWithinFastestTimingDriven(String partName, String srcSitePin, String dstSitePin,
                                                   String timingWeight) {
        PartialRouter rwroute = (timingWeight == null)
                ? routeSingleConnectionTimingDriven(partName, srcSitePin, dstSitePin)
                : routeSingleConnectionTimingDriven(partName, srcSitePin, dstSitePin, "--timingWeight", timingWeight);
        // RWRoute's MAX_CRITICALITY: the connection's only timing path is the critical path
        Assertions.assertEquals(0.99f, rwroute.indirectConnections.get(0).getCriticality());
        List<RouteNode> route = getRoute(rwroute);
        List<RouteNode> fastest = getFastestRoute(rwroute);
        float delay = getRouteDelay(route);
        float leastDelay = getRouteDelay(fastest);
        float expected = DELAY_KNOWN_EXCESS.getOrDefault(partName + "," + srcSitePin + "," + dstSitePin +
                (timingWeight == null ? "" : "," + timingWeight), leastDelay);
        String message = "delay " + delay + ", expected " + expected + ", least " + leastDelay + ", ratio " +
                delay / leastDelay + "; RWRoute: " + describeRoute(route) + "; fastest: " + describeRoute(fastest);
        System.out.println(srcSitePin + " -> " + dstSitePin + ": " + message);
        Assertions.assertTrue(delay >= leastDelay, "faster than the fastest route: " + message);
        Assertions.assertTrue(delay <= expected * (1 + LOWER_BOUND_LEEWAY), "more than expected: " + message);
        Assertions.assertTrue(delay >= expected, "less than expected (update DELAY_KNOWN_EXCESS): " + message);
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
            "xcv80,SLICE_X51Y331,SLICE_X51Y406,100",            // Source close to crossing SLL
            "xcv80,SLICE_X0Y331,SLICE_X49Y406,200",             // Source far from crossing SLL

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
            Device.AWS_F1 + ",SLICE_X53Y241,SLICE_X69Y300,200",
            Device.AWS_F1 + ",SLICE_X53Y358,SLICE_X69Y299,100",
            // Far
            Device.AWS_F1 + ",SLICE_X9Y240,SLICE_X9Y359,100",    // On Laguna
            Device.AWS_F1 + ",SLICE_X9Y359,SLICE_X9Y240,100",
            Device.AWS_F1 + ",SLICE_X162Y240,SLICE_X162Y430,100",

            Device.AWS_F1 + ",SLICE_X162Y430,SLICE_X162Y240,100",
            Device.AWS_F1 + ",SLICE_X0Y240,SLICE_X12Y430,100",   // Far from Laguna
            Device.AWS_F1 + ",SLICE_X0Y430,SLICE_X12Y240,100",

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
            "xcku115,SLICE_X17Y359,SLICE_X17Y240,100",
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

    /**
     * Creates a design with one LUT lane for the LUT pin swapping tests: a cell on the 6LUT and/or a cell on the 5LUT
     * of the given lane of a SLICE, each input driven by a flop (one per distinct net) in the SLICE below, and each
     * LUT output captured by a flop (there, or the lane's own for a 5LUT cell, unless a LUT6_2 macro). Each cell is
     * given as its type and equation, then the net and physical
     * pin of each of its inputs (I0 first), e.g. "LUT3 O=I0&amp;I1&amp;I2 a@A1 b@A2 c@A5". Net "z" on pin CASC is
     * driven by a LUT2 placed on the 6LUT of the lane below, whose O6 drives this lane's CASC inputs (Versal only).
     * A type such as "LUT4/O5" puts the cell's output on that BEL pin (by default, O6 for the 6LUT, O5 for the 5LUT).
     * With lut6_2 set, the two cells are instead the LUT6 and LUT5 of a LUT6_2 macro (whose INIT is that of the LUT6
     * cell's equation), the LUT5 sharing the LUT6's I0-I4.
     * @param partName Part to target.
     * @param siteName SLICE for the lane.
     * @param lane Lane letter.
     * @param lut6Spec The 6LUT cell, or null for none.
     * @param lut5Spec The 5LUT cell, or null for none.
     * @param lut6_2 Make the two cells a LUT6_2 macro.
     * @return The design, placed and with its sites routed.
     */
    public static Design createLutLaneDesign(String partName, String siteName, char lane, String lut6Spec,
                                             String lut5Spec, boolean lut6_2) {
        Design design = new Design("top", partName);
        Device device = design.getDevice();
        Site site = device.getSite(siteName);
        Site ffSite = device.getSite(siteName.replaceFirst("Y\\d+$", "Y" + (site.getInstanceY() - 1)));
        List<String> ffBELs = new ArrayList<>();
        for (String suffix : new String[] {"FF", "FF2"}) {
            for (char l : LUTTools.lutLetters) {
                ffBELs.add(l + suffix);
            }
        }

        String[] specs = {lut6Spec, lut5Spec};
        if (lut6_2) {
            // Connect the macro (I0-I5 shared as the LUT6 cell's are) and the flops in the logical netlist, expand the
            // macro, then place its LUT6 and LUT5 and route the sites
            EDIFNetlist netlist = design.getNetlist();
            EDIFCellInst lut6_2Inst = Design.createUnisimInst(netlist.getTopCell(), "lut62", Unisim.LUT6_2);
            lut6_2Inst.addProperty("INIT", LUTTools.getLUTInitFromEquation(lut6Spec.split(" ")[1], 6));
            String[] tokens = lut6Spec.split(" ");
            int ff = 0;
            for (int i = 2; i < tokens.length; i++) {
                String name = tokens[i].split("@")[0];
                Cell ffCell = design.createAndPlaceCell("ff_" + name, Unisim.FDCE,
                        ffSite.getName() + "/" + ffBELs.get(ff++));
                EDIFNet net = design.createNet(name).getLogicalNet();
                net.createPortInst("Q", ffCell);
                net.createPortInst("I" + (i - 2), lut6_2Inst);
            }
            for (String name : new String[] {"o6", "o5"}) {
                Cell ffCell = design.createAndPlaceCell("ff_" + name, Unisim.FDCE,
                        ffSite.getName() + "/" + ffBELs.get(ff++));
                EDIFNet net = design.createNet(name).getLogicalNet();
                net.createPortInst(name.toUpperCase(), lut6_2Inst);
                net.createPortInst("D", ffCell);
            }
            netlist.expandMacroUnisims(design.getSeries());
            for (int c = 0; c < 2; c++) {
                String name = "lut62/" + (c == 0 ? "LUT6" : "LUT5");
                Cell cell = design.createCell(name, netlist.getHierCellInstFromName(name).getInst());
                Assertions.assertTrue(design.placeCell(cell, site, site.getBEL(lane + (c == 0 ? "6LUT" : "5LUT"))));
                setInputPinMappings(cell, specs[c]);
            }
            design.routeSites();
            return design;
        }

        // Cells, with their pins mapped as given
        Cell[] cells = new Cell[2];
        for (int c = 0; c < 2; c++) {
            if (specs[c] == null) {
                continue;
            }
            String[] tokens = specs[c].split(" ");
            String[] type = tokens[0].split("/");
            cells[c] = design.createAndPlaceCell(c == 0 ? "lut6" : "lut5", Unisim.valueOf(type[0]),
                    siteName + "/" + lane + (c == 0 ? "6LUT" : "5LUT"));
            LUTTools.configureLUT(cells[c], tokens[1]);
            setInputPinMappings(cells[c], specs[c]);
            if (type.length > 1) {
                // Output on the given BEL pin (e.g. O5 of the 6LUT: its upper half alone)
                String outputPin = cells[c].getPhysicalPinMapping("O");
                cells[c].removePinMapping(outputPin);
                cells[c].addPinMapping(type[1], "O");
            }
        }

        // Driver of any cascade input
        Map<String, Cell> drivers = new HashMap<>();
        int ff = 0;
        if ((lut6Spec != null && lut6Spec.contains("@CASC")) || (lut5Spec != null && lut5Spec.contains("@CASC"))) {
            Cell drv = design.createAndPlaceCell("drv", Unisim.LUT2, siteName + "/" + (char) (lane - 1) + "6LUT");
            LUTTools.configureLUT(drv, "O=I0^I1");
            for (int i = 0; i < 2; i++) {
                Cell ffCell = design.createAndPlaceCell("ff_t" + i, Unisim.FDCE, ffSite.getName() + "/" + ffBELs.get(ff++));
                Net t = design.createNet("t" + i);
                ECOTools.connectNet(design, ffCell, "Q", t);
                ECOTools.connectNet(design, drv, "I" + i, t);
            }
            drivers.put("z", drv);
        }

        // Nets, in order of first use, each connected to its driver then to every input that uses it (with the pins
        // of both cells already mapped, connecting one cell's input first fails in ECOTools on Versal)
        Map<String, List<Pair<Cell, String>>> netInputs = new LinkedHashMap<>();
        for (int c = 0; c < 2; c++) {
            if (specs[c] == null) {
                continue;
            }
            String[] tokens = specs[c].split(" ");
            for (int i = 2; i < tokens.length; i++) {
                netInputs.computeIfAbsent(tokens[i].split("@")[0], (k) -> new ArrayList<>())
                        .add(new Pair<>(cells[c], "I" + (i - 2)));
            }
        }
        for (Map.Entry<String, List<Pair<Cell, String>>> e : netInputs.entrySet()) {
            Net net = design.createNet(e.getKey());
            Cell drv = drivers.get(e.getKey());
            if (drv != null) {
                ECOTools.connectNet(design, drv, "O", net);
            } else {
                Cell ffCell = design.createAndPlaceCell("ff_" + e.getKey(), Unisim.FDCE,
                        ffSite.getName() + "/" + ffBELs.get(ff++));
                ECOTools.connectNet(design, ffCell, "Q", net);
            }
            for (Pair<Cell, String> input : e.getValue()) {
                ECOTools.connectNet(design, input.getFirst(), input.getSecond(), net);
            }
        }

        // Outputs
        for (int c = 0; c < 2; c++) {
            if (cells[c] == null) {
                continue;
            }
            String name = (c == 0) ? "o6" : "o5";
            Net net = design.createNet(name);
            ECOTools.connectNet(design, cells[c], "O", net);
            // An output on O5 is captured by the lane's own flop (the 5LUT's O5 reaches xFF, the 6LUT's xFF2)
            String ffLocation = !"O5".equals(cells[c].getPhysicalPinMapping("O"))
                    ? (ffSite.getName() + "/" + ffBELs.get(ff++))
                    : (siteName + "/" + lane + (c == 0 ? "FF2" : "FF"));
            Cell ffCell = design.createAndPlaceCell("ff_" + name, Unisim.FDCE, ffLocation);
            ECOTools.connectNet(design, ffCell, "D", net);
        }
        // ECOTools has routed the sites as needed (Design.routeSites() would tie A6 to VCC on Versal even where the
        // 5LUT uses it)
        return design;
    }

    /**
     * Maps the inputs of a LUT cell (I0 first) to the physical pins given in its spec (see
     * {@link #createLutLaneDesign(String, String, char, String, String, boolean)}), in place of its default mapping.
     */
    private static void setInputPinMappings(Cell cell, String spec) {
        for (String physicalPin : new ArrayList<>(cell.getPinMappingsP2L().keySet())) {
            if (physicalPin.matches("A[1-6]")) {
                cell.removePinMapping(physicalPin);
            }
        }
        String[] tokens = spec.split(" ");
        for (int i = 2; i < tokens.length; i++) {
            cell.addPinMapping(tokens[i].split("@")[1], "I" + (i - 2));
        }
    }

    /**
     * Routes a design with LUT pin swapping, recording which pins each LUT input connection may be routed to.
     * @param design The design.
     * @param timingDriven Route timing-driven.
     * @param blockedPins LUT input site pins (e.g. "SLICE_X40Y61/A1") to make unreachable while routing, so that
     *                    connections to them must be swapped onto another pin.
     * @return Map from each LUT input sink pin (before swapping, e.g. "SLICE_X40Y61/A1") to the pins
     *         (e.g. "A1") its connection may be routed to (itself included).
     */
    public static Map<String, Set<String>> routeLutPinSwapping(Design design, boolean timingDriven,
                                                               String... blockedPins) {
        Net blocker = null;
        if (blockedPins.length > 0) {
            // Preserve the INT node that leads to each such site pin for another net
            blocker = design.createNet(Net.Z_NET);
            for (String blockedPin : blockedPins) {
                String[] tokens = blockedPin.split("/");
                Node node = design.getDevice().getSite(tokens[0]).getConnectedNode(tokens[1]);
                if (design.getSeries() == Series.Versal) {
                    node = node.getAllUphillNodes().get(0);
                }
                blocker.addPIP(node.getAllUphillPIPs().get(0));
            }
        }

        Map<String, Set<String>> candidates = new TreeMap<>();
        RWRouteConfig config = new RWRouteConfig(timingDriven ? new String[] {"--lutPinSwapping"}
                : new String[] {"--nonTimingDriven", "--lutPinSwapping"});
        RWRoute router = new RWRoute(design, config) {
            @Override
            protected NetWrapper createNetWrapperAndConnections(Net net) {
                NetWrapper netWrapper = super.createNetWrapperAndConnections(net);
                for (Connection connection : netWrapper.getConnections()) {
                    SitePinInst sink = connection.getSink();
                    if (connection.isDirect() || !sink.isLUTInputPin()) {
                        continue;
                    }
                    Set<String> pins = new TreeSet<>();
                    pins.add(getLutInputSitePin(connection.getSinkRnode()).getPinName());
                    for (RouteNode altSinkRnode : connection.getAltSinkRnodes()) {
                        pins.add(getLutInputSitePin(altSinkRnode).getPinName());
                    }
                    candidates.put(sink.getSite().getName() + "/" + sink.getName(), pins);
                }
                return netWrapper;
            }
        };
        RWRoute.routeDesign(router);

        if (blocker != null) {
            design.removeNet(blocker);
        }
        System.out.println("LUT pin swapping candidates: " + candidates);
        assertAllPinsRouted(design);
        return candidates;
    }

    /**
     * Gets the physical pin that each input of a cell is mapped to, e.g. "I0:A3 I1:CASC".
     */
    private static String getInputPinMappings(Cell cell) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < LUTTools.MAX_LUT_SIZE; i++) {
            String physicalPin = cell.getPhysicalPinMapping("I" + i);
            if (physicalPin != null) {
                sb.append(sb.length() == 0 ? "" : " ").append("I").append(i).append(':').append(physicalPin);
            }
        }
        return sb.toString();
    }

    /**
     * Checks a Versal LUT lane's pin mappings against what Vivado 2026.1 accepts (from exhaustive LOCK_PINS +
     * place_cell experiments): a cell on the 5LUT, or on the 6LUT while the 5LUT is used, uses at most one of A5, A6
     * and CASC, unless the two form a LUT6_2 (the 6LUT cell using A6 and one of A5/CASC, and the 5LUT cell not using
     * A6); a lone 6LUT cell does not use both A5 and CASC. Also (from Vivado's own placements, and its route_design
     * rejecting otherwise): with both LUTs used and not a LUT6_2, the lane only uses A6 if the 6LUT cell's output is
     * on O5.
     */
    private static void assertVersalLaneLegal(Cell lut6, Cell lut5) {
        Map<String, Integer> top = new HashMap<>();
        for (Cell cell : new Cell[] {lut6, lut5}) {
            if (cell == null) {
                continue;
            }
            int n = 0;
            for (String pin : new String[] {"A5", "A6", "CASC"}) {
                if (cell.getLogicalPinMapping(pin) != null) {
                    n++;
                }
            }
            top.put(cell.getBELName(), n);
        }
        if (lut5 == null) {
            Assertions.assertFalse(lut6.getLogicalPinMapping("A5") != null && lut6.getLogicalPinMapping("CASC") != null);
            return;
        }
        Assertions.assertTrue(top.get(lut5.getBELName()) <= 1, getInputPinMappings(lut5));
        if (lut6 == null) {
            return;
        }
        boolean lut6_2 = lut6.getLogicalPinMapping("A6") != null &&
                (lut6.getLogicalPinMapping("A5") != null || lut6.getLogicalPinMapping("CASC") != null);
        if (lut6_2) {
            Assertions.assertNull(lut5.getLogicalPinMapping("A6"));
        } else {
            Assertions.assertTrue(top.get(lut6.getBELName()) <= 1, getInputPinMappings(lut6));
            if (lut6.getLogicalPinMapping("O5") == null) {
                Assertions.assertNull(lut6.getLogicalPinMapping("A6"), getInputPinMappings(lut6));
                Assertions.assertNull(lut5.getLogicalPinMapping("A6"), getInputPinMappings(lut5));
            }
        }
    }

    /**
     * Tests LUT pin swapping on a LUT6_2 (a 6-input 6LUT cell and a 5LUT cell, here its A6=0 half) on UltraScale+
     * (a LUT6_2 macro, since Vivado rejects a separate LUT6 and LUT5 using A6 there) and Versal (separate cells,
     * which Vivado accepts there): the input on A6, used only by the 6LUT cell, must stay there, and the others may
     * only swap among the bottom 5 pins (on UltraScale+, since the 5LUT has only those; on Versal, since A6 selects
     * between the 6LUT's halves).
     */
    @ParameterizedTest
    @CsvSource({
            "xcvu3p,false",
            "xcvu3p,true",
            "xcvc1902,false",
    })
    public void testLutPinSwappingLut6_2(String partName, boolean timingDriven) {
        boolean versal = PartNameTools.getPart(partName).getSeries() == Series.Versal;
        Design design = createLutLaneDesign(partName, "SLICE_X40Y61", 'A',
                "LUT6 O=I0^I1^I2^I3^I4^I5 a@A1 b@A2 c@A3 d@A4 e@A5 f@A6",
                "LUT5 O=I0^I1^I2^I3^I4 a@A1 b@A2 c@A3 d@A4 e@A5", !versal);
        Map<String, Set<String>> candidates = routeLutPinSwapping(design, timingDriven);

        Set<String> a1a5 = new HashSet<>(Arrays.asList("A1", "A2", "A3", "A4", "A5"));
        for (int i = 1; i <= 5; i++) {
            Assertions.assertEquals(a1a5, candidates.get("SLICE_X40Y61/A" + i));
        }
        Assertions.assertEquals(Collections.singleton("A6"), candidates.get("SLICE_X40Y61/A6"));

        Cell lut6 = design.getCell(versal ? "lut6" : "lut62/LUT6");
        Cell lut5 = design.getCell(versal ? "lut5" : "lut62/LUT5");
        Assertions.assertEquals("A6", lut6.getPhysicalPinMapping("I5"));
        Set<String> pins = new HashSet<>();
        for (int i = 0; i < 5; i++) {
            // Both halves have each shared input on the same pin
            String pin = lut6.getPhysicalPinMapping("I" + i);
            Assertions.assertEquals(pin, lut5.getPhysicalPinMapping("I" + i));
            pins.add(pin);
        }
        Assertions.assertEquals(a1a5, pins);
        if (versal) {
            assertVersalLaneLegal(lut6, lut5);
        }
    }

    /**
     * Tests LUT pin swapping on Versal with both LUTs of a lane used by cells of up to five inputs each, which
     * Vivado accepts only if each cell uses at most one of A5 and A6 (and, to use A6, the 6LUT cell's output is on
     * O5): inputs on A1-A4 may only swap among A1-A4, and those on A5/A6 only between A5 and A6. Here the 6LUT cell
     * (output on O5) uses A5 and the 5LUT cell A6 for inputs of their own; with A1 blocked, the shared inputs on
     * A1-A3 must end up on A2-A4.
     */
    @Test
    public void testVersalLutPinSwappingTwoLutsDifferentTopPins() {
        Design design = createLutLaneDesign("xcvc1902", "SLICE_X40Y61", 'A',
                "LUT4/O5 O=I0^I1^I2^I3 a@A1 b@A2 c@A3 e@A5",
                "LUT4 O=I0&I1&I2&I3 a@A1 b@A2 c@A3 f@A6", false);
        Map<String, Set<String>> candidates = routeLutPinSwapping(design, false, "SLICE_X40Y61/A1");

        Set<String> a1a4 = new HashSet<>(Arrays.asList("A1", "A2", "A3", "A4"));
        Set<String> a5a6 = new HashSet<>(Arrays.asList("A5", "A6"));
        for (int i = 1; i <= 3; i++) {
            Assertions.assertEquals(a1a4, candidates.get("SLICE_X40Y61/A" + i));
        }
        Assertions.assertEquals(a5a6, candidates.get("SLICE_X40Y61/A5"));
        Assertions.assertEquals(a5a6, candidates.get("SLICE_X40Y61/A6"));

        Cell lut6 = design.getCell("lut6");
        Cell lut5 = design.getCell("lut5");
        Set<String> pins = new HashSet<>();
        for (int i = 0; i < 3; i++) {
            Assertions.assertEquals(lut6.getPhysicalPinMapping("I" + i), lut5.getPhysicalPinMapping("I" + i));
            pins.add(lut6.getPhysicalPinMapping("I" + i));
        }
        Assertions.assertEquals(new HashSet<>(Arrays.asList("A2", "A3", "A4")), pins);
        Assertions.assertEquals(a5a6, new HashSet<>(Arrays.asList(lut6.getPhysicalPinMapping("I3"),
                lut5.getPhysicalPinMapping("I3"))));
        assertVersalLaneLegal(lut6, lut5);
    }

    /**
     * Tests LUT pin swapping on Versal with both LUTs of a lane used by two cells sharing all their inputs (which
     * Vivado accepts on A1-A4 plus one of A5/A6). With the 6LUT cell's output on O5, the input on A5 may move to A6:
     * with A5 blocked, it must (for both cells). With its output on O6 (the whole LUT6, of which A6 is the select),
     * the lane may not use A6, so the input on A5 must stay there; with A1 blocked, the inputs on A1-A3 must end up
     * on A2-A4.
     */
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    public void testVersalLutPinSwappingTwoLutsSharedInputs(boolean lut6OutputO5) {
        Design design = createLutLaneDesign("xcvc1902", "SLICE_X40Y61", 'A',
                lut6OutputO5 ? "LUT5/O5 O=I0^I1^I2^I3^I4 a@A1 b@A2 c@A3 d@A4 e@A5"
                             : "LUT4 O=I0^I1^I2^I3 a@A1 b@A2 c@A3 e@A5",
                lut6OutputO5 ? "LUT5 O=I0&I1&I2&I3&I4 a@A1 b@A2 c@A3 d@A4 e@A5"
                             : "LUT4 O=I0&I1&I2&I3 a@A1 b@A2 c@A3 e@A5", false);
        Map<String, Set<String>> candidates = routeLutPinSwapping(design, false,
                lut6OutputO5 ? "SLICE_X40Y61/A5" : "SLICE_X40Y61/A1");

        Set<String> a1a4 = new HashSet<>(Arrays.asList("A1", "A2", "A3", "A4"));
        for (int i = 1; i <= (lut6OutputO5 ? 4 : 3); i++) {
            Assertions.assertEquals(a1a4, candidates.get("SLICE_X40Y61/A" + i));
        }
        Assertions.assertEquals(lut6OutputO5 ? new HashSet<>(Arrays.asList("A5", "A6")) : Collections.singleton("A5"),
                candidates.get("SLICE_X40Y61/A5"));

        Cell lut6 = design.getCell("lut6");
        Cell lut5 = design.getCell("lut5");
        int last = lut6OutputO5 ? 4 : 3;
        Assertions.assertEquals(lut6OutputO5 ? "A6" : "A5", lut6.getPhysicalPinMapping("I" + last));
        Assertions.assertEquals(lut6OutputO5 ? "A6" : "A5", lut5.getPhysicalPinMapping("I" + last));
        Set<String> pins = new HashSet<>();
        for (int i = 0; i < last; i++) {
            Assertions.assertEquals(lut6.getPhysicalPinMapping("I" + i), lut5.getPhysicalPinMapping("I" + i));
            pins.add(lut6.getPhysicalPinMapping("I" + i));
        }
        Assertions.assertEquals(lut6OutputO5 ? a1a4 : new HashSet<>(Arrays.asList("A2", "A3", "A4")), pins);
        assertVersalLaneLegal(lut6, lut5);
    }

    /**
     * Tests LUT pin swapping on Versal with only one LUT of a lane used: a lone 6LUT cell may use any pins (so,
     * with A1-A3 blocked, its inputs must move to A4-A6); a lone 5LUT cell, like two cells, at most one of A5/A6 (so
     * its inputs on A1-A4 may only swap among A1-A4, and with A1 and A5 blocked, those on A1/A2 must end up on A2-A4
     * and that on A5 on A6).
     */
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    public void testVersalLutPinSwappingOneLut(boolean lut5Only) {
        String spec = "LUT3 O=I0^I1^I2 a@A1 b@A2 c@" + (lut5Only ? "A5" : "A3");
        Design design = createLutLaneDesign("xcvc1902", "SLICE_X40Y61", 'A',
                lut5Only ? null : spec, lut5Only ? spec : null, false);
        Map<String, Set<String>> candidates = routeLutPinSwapping(design, false, lut5Only
                ? new String[] {"SLICE_X40Y61/A1", "SLICE_X40Y61/A5"}
                : new String[] {"SLICE_X40Y61/A1", "SLICE_X40Y61/A2", "SLICE_X40Y61/A3"});

        Cell cell = design.getCell(lut5Only ? "lut5" : "lut6");
        if (lut5Only) {
            Set<String> a1a4 = new HashSet<>(Arrays.asList("A1", "A2", "A3", "A4"));
            Assertions.assertEquals(a1a4, candidates.get("SLICE_X40Y61/A1"));
            Assertions.assertEquals(a1a4, candidates.get("SLICE_X40Y61/A2"));
            Assertions.assertEquals(new HashSet<>(Arrays.asList("A5", "A6")), candidates.get("SLICE_X40Y61/A5"));
            String i0 = cell.getPhysicalPinMapping("I0");
            String i1 = cell.getPhysicalPinMapping("I1");
            Assertions.assertNotEquals(i0, i1);
            Assertions.assertTrue(Arrays.asList("A2", "A3", "A4").containsAll(Arrays.asList(i0, i1)),
                    getInputPinMappings(cell));
            Assertions.assertEquals("A6", cell.getPhysicalPinMapping("I2"));
            assertVersalLaneLegal(null, cell);
        } else {
            Set<String> all = new HashSet<>(Arrays.asList("A1", "A2", "A3", "A4", "A5", "A6"));
            for (int i = 1; i <= 3; i++) {
                Assertions.assertEquals(all, candidates.get("SLICE_X40Y61/A" + i));
            }
            Set<String> pins = new HashSet<>();
            for (int i = 0; i < 3; i++) {
                pins.add(cell.getPhysicalPinMapping("I" + i));
            }
            Assertions.assertEquals(new HashSet<>(Arrays.asList("A4", "A5", "A6")), pins);
            assertVersalLaneLegal(cell, null);
        }
    }

    /**
     * Tests LUT pin swapping on Versal with a cell using the cascade input CASC (driven by the 6LUT of the lane
     * below). Vivado accepts CASC as an alternative to A5 and A6: a cell on the 5LUT, or on the 6LUT while the 5LUT
     * is used, that uses CASC may not use A5 or A6; a lone 6LUT cell that uses CASC may not use A5. Here the 6LUT
     * cell uses CASC, either alone (its inputs, with A1-A3 blocked, must move to A4 and A6, never A5), or with a 5LUT
     * cell that uses CASC too, or with one that does not (and uses A5, which must stay there with the 6LUT cell's
     * output on O6); in both, with A1 blocked, the inputs on A1-A3 must end up on A2-A4.
     */
    @ParameterizedTest
    @ValueSource(strings = {"lone", "both", "one"})
    public void testVersalLutPinSwappingCascade(String lut5) {
        boolean lut5Used = !lut5.equals("lone");
        Design design = createLutLaneDesign("xcvc1902", "SLICE_X40Y61", 'B',
                "LUT3 O=I0^I1^I2 z@CASC a@A1 b@A2",
                lut5.equals("both") ? "LUT2 O=I0&I1 z@CASC c@A3" : lut5.equals("one") ? "LUT2 O=I0&I1 c@A3 e@A5" : null,
                false);
        Map<String, Set<String>> candidates = routeLutPinSwapping(design, false, lut5Used
                ? new String[] {"SLICE_X40Y61/B1"}
                : new String[] {"SLICE_X40Y61/B1", "SLICE_X40Y61/B2", "SLICE_X40Y61/B3"});

        Cell lut6 = design.getCell("lut6");
        Cell lut5Cell = design.getCell("lut5");
        Assertions.assertEquals("CASC", lut6.getPhysicalPinMapping("I0"));
        if (lut5Used) {
            Set<String> a1a4 = new HashSet<>(Arrays.asList("B1", "B2", "B3", "B4"));
            for (int i = 1; i <= 3; i++) {
                Assertions.assertEquals(a1a4, candidates.get("SLICE_X40Y61/B" + i));
            }
            // (The 5LUT cell's input on A3 is its I1 when it uses CASC, its I0 otherwise)
            String lut5Pin = lut5.equals("both") ? "I1" : "I0";
            Assertions.assertEquals(new HashSet<>(Arrays.asList("A2", "A3", "A4")), new HashSet<>(Arrays.asList(
                    lut6.getPhysicalPinMapping("I1"), lut6.getPhysicalPinMapping("I2"),
                    lut5Cell.getPhysicalPinMapping(lut5Pin))));
            if (lut5.equals("both")) {
                Assertions.assertEquals("CASC", lut5Cell.getPhysicalPinMapping("I0"));
            } else {
                Assertions.assertEquals(Collections.singleton("B5"), candidates.get("SLICE_X40Y61/B5"));
                Assertions.assertEquals("A5", lut5Cell.getPhysicalPinMapping("I1"));
            }
        } else {
            Set<String> noA5 = new HashSet<>(Arrays.asList("B1", "B2", "B3", "B4", "B6"));
            Assertions.assertEquals(noA5, candidates.get("SLICE_X40Y61/B1"));
            Assertions.assertEquals(noA5, candidates.get("SLICE_X40Y61/B2"));
            Assertions.assertEquals(new HashSet<>(Arrays.asList("A4", "A6")),
                    new HashSet<>(Arrays.asList(lut6.getPhysicalPinMapping("I1"), lut6.getPhysicalPinMapping("I2"))));
        }
        assertVersalLaneLegal(lut6, lut5Cell);
    }

    /**
     * Tests LUT pin swapping on a whole Versal design placed and routed by Vivado (with LUT6_2s, cells using CASC, and
     * LUT inputs tied to VCC/GND): every pin must be routed, some LUT inputs swapped, and every lane must still be one
     * that Vivado accepts (see {@link #assertVersalLaneLegal(Cell, Cell)}).
     */
    @Test
    public void testNonTimingDrivenRoutingOnVersalDeviceWithLutPinSwapping() {
        Design design = RapidWrightDCP.loadDCP("picoblaze_2022.2.dcp");
        design.setTrackNetChanges(true);
        Map<Cell, String> before = new HashMap<>();
        for (Cell cell : design.getCells()) {
            if (cell.getBEL() != null && cell.getBEL().isLUT()) {
                before.put(cell, getInputPinMappings(cell));
            }
        }

        RWRoute.routeDesignWithUserDefinedArguments(design, new String[] {"--nonTimingDriven", "--lutPinSwapping"});

        Assertions.assertFalse(design.getModifiedNets().isEmpty());
        for (Net net : design.getModifiedNets()) {
            assertAllPinsRouted(net);
        }
        long swapped = before.entrySet().stream().filter((e) -> !e.getValue().equals(getInputPinMappings(e.getKey())))
                .count();
        Assertions.assertTrue(swapped > 0);
        for (SiteInst si : design.getSiteInsts()) {
            if (!Utils.isSLICE(si)) {
                continue;
            }
            for (char lane : LUTTools.lutLetters) {
                Cell lut6 = RWRoute.getUsedLutCell(si, lane + "6LUT");
                Cell lut5 = RWRoute.getUsedLutCell(si, lane + "5LUT");
                if (lut6 != null || lut5 != null) {
                    assertVersalLaneLegal(lut6, lut5);
                }
            }
        }

        if (FileTools.isVivadoOnPath()) {
            ReportRouteStatusResult rrs = VivadoTools.reportRouteStatus(design);
            Assertions.assertEquals(0, rrs.netsWithRoutingErrors);
        }
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
}

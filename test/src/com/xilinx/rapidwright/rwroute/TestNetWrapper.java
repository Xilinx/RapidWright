/*
 * Copyright (c) 2026, Advanced Micro Devices, Inc.
 * All rights reserved.
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

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.xilinx.rapidwright.design.Cell;
import com.xilinx.rapidwright.design.Design;
import com.xilinx.rapidwright.design.Net;
import com.xilinx.rapidwright.design.Unisim;
import com.xilinx.rapidwright.device.Device;
import com.xilinx.rapidwright.device.Node;

public class TestNetWrapper {
    private Design design;
    private RouteNodeGraph routingGraph;
    private Node source;

    @BeforeEach
    public void setUp() {
        design = new Design("test", "xcvu3p");
        routingGraph = new RouteNodeGraph(design, new RWRouteConfig(new String[0]));
        source = design.getDevice().getSite("SLICE_X10Y10").getConnectedNode("A_O");
        Assertions.assertEquals("CLEL_R_X6Y10/CLE_CLE_L_SITE_0_A_O", source.toString());
    }

    /**
     * Adds a connection whose route is given from source to sink, as nodes that each drive the next, and stores its
     * rnodes from sink to source as RWRoute does.
     */
    private void addConnection(NetWrapper netWrapper, String... nodeNames) {
        addConnection(netWrapper, routingGraph.getOrCreate(source, RouteNodeType.EXCLUSIVE_SOURCE), nodeNames);
    }

    /**
     * Adds a connection from the given source rnode (e.g. its net's alternate source) whose route is given from that
     * source to sink, as nodes that each drive the next, and stores its rnodes from sink to source as RWRoute does.
     */
    private void addConnection(NetWrapper netWrapper, RouteNode sourceRnode, String... nodeNames) {
        Device device = design.getDevice();
        List<RouteNode> rnodes = new ArrayList<>();
        Node prev = sourceRnode;
        rnodes.add(sourceRnode);
        for (String nodeName : nodeNames) {
            Node node = device.getNode(nodeName);
            Assertions.assertTrue(prev.getAllDownhillNodes().contains(node), prev + " does not drive " + node);
            rnodes.add(0, routingGraph.getOrCreate(node));
            prev = node;
        }
        Connection connection = new Connection(netWrapper.getConnections().size(), null, null, netWrapper);
        connection.setRnodes(rnodes);
    }

    @Test
    public void testHasMultipleDriversSharedTrunk() {
        // Two connections that share their first nodes and then branch form a tree
        NetWrapper netWrapper = new NetWrapper(0, new Net("net"));
        addConnection(netWrapper,
                "INT_X6Y10/INT_NODE_IMUX_0_INT_OUT0",
                "INT_X6Y10/BOUNCE_E_2_FT1",
                "INT_X6Y9/INT_NODE_IMUX_26_INT_OUT0");
        addConnection(netWrapper,
                "INT_X6Y10/INT_NODE_IMUX_0_INT_OUT0",
                "INT_X6Y10/BOUNCE_E_2_FT1",
                "INT_X6Y9/INT_NODE_IMUX_27_INT_OUT1");
        Assertions.assertFalse(netWrapper.hasMultipleDrivers(1));
    }

    @Test
    public void testHasMultipleDriversBranchAtSource() {
        // Two connections that leave the source by different first nodes also form a tree
        NetWrapper netWrapper = new NetWrapper(0, new Net("net"));
        addConnection(netWrapper,
                "INT_X6Y10/INT_NODE_IMUX_0_INT_OUT0",
                "INT_X6Y10/BOUNCE_E_2_FT1");
        addConnection(netWrapper,
                "INT_X6Y10/INODE_E_1_FT1",
                "INT_X6Y9/BOUNCE_E_15_FT0");
        Assertions.assertFalse(netWrapper.hasMultipleDrivers(1));
    }

    @Test
    public void testHasMultipleDriversConvergence() {
        // The second connection reaches INT_X6Y10/BOUNCE_E_2_FT1 from a different parent than the first does
        NetWrapper netWrapper = new NetWrapper(0, new Net("net"));
        addConnection(netWrapper,
                "INT_X6Y10/INT_NODE_IMUX_0_INT_OUT0",
                "INT_X6Y10/BOUNCE_E_2_FT1",
                "INT_X6Y9/INT_NODE_IMUX_26_INT_OUT0");
        addConnection(netWrapper,
                "INT_X6Y10/INODE_E_1_FT1",
                "INT_X6Y9/BOUNCE_E_15_FT0",
                "INT_X6Y10/INT_NODE_IMUX_2_INT_OUT1",
                "INT_X6Y10/BOUNCE_E_2_FT1",
                "INT_X6Y9/INT_NODE_IMUX_27_INT_OUT1");
        Assertions.assertTrue(netWrapper.hasMultipleDrivers(1));
    }

    /**
     * Creates the wrapper of a net driven by a LUT at SLICE_X10Y10/A6LUT, whose source is its A_O output (the source
     * of every other test) and whose alternate source, as RWRoute finds it, is the AMUX output of the same LUT.
     */
    private NetWrapper createNetWrapperWithAlternateSource() {
        Cell lut = design.createAndPlaceCell("lut", Unisim.LUT1, "SLICE_X10Y10/A6LUT");
        Net net = design.createNet("net");
        net.connect(lut, "O");
        Assertions.assertEquals("A_O", net.getSource().getName());
        NetWrapper netWrapper = new NetWrapper(0, net);
        Assertions.assertNotNull(netWrapper.getOrCreateAlternateSource(routingGraph));
        Assertions.assertEquals("AMUX", net.getAlternateSource().getName());
        RouteNode altSourceRnode = netWrapper.getAltSourceRnode();
        Assertions.assertEquals("CLEL_R_X6Y10/CLE_CLE_L_SITE_0_AMUX",
                altSourceRnode.getTile().getName() + "/" + altSourceRnode.getWireName());
        Assertions.assertEquals(RouteNodeType.EXCLUSIVE_SOURCE, altSourceRnode.getType());
        return netWrapper;
    }

    @Test
    public void testHasMultipleDriversAlternateSourceDisjoint() {
        // One connection from the source and one from the alternate source, whose routes share no node, form a forest
        NetWrapper netWrapper = createNetWrapperWithAlternateSource();
        addConnection(netWrapper,
                "INT_X6Y10/INT_NODE_IMUX_0_INT_OUT0",
                "INT_X6Y10/BOUNCE_E_2_FT1",
                "INT_X6Y9/INT_NODE_IMUX_26_INT_OUT0");
        addConnection(netWrapper, netWrapper.getAltSourceRnode(),
                "INT_X6Y10/INT_NODE_SDQ_27_INT_OUT1",
                "INT_X6Y10/NN1_E_BEG5",
                "INT_X6Y11/INT_NODE_IMUX_18_INT_OUT0",
                "INT_X6Y11/BOUNCE_E_13_FT0");
        Assertions.assertFalse(netWrapper.hasMultipleDrivers(1));
    }

    @Test
    public void testHasMultipleDriversBranchAtAlternateSource() {
        // Two connections that leave the alternate source by different first nodes (beside one from the source) also
        // form a forest
        NetWrapper netWrapper = createNetWrapperWithAlternateSource();
        addConnection(netWrapper,
                "INT_X6Y10/INT_NODE_IMUX_0_INT_OUT0",
                "INT_X6Y10/BOUNCE_E_2_FT1");
        addConnection(netWrapper, netWrapper.getAltSourceRnode(),
                "INT_X6Y10/INT_NODE_SDQ_27_INT_OUT1",
                "INT_X6Y10/NN1_E_BEG5");
        addConnection(netWrapper, netWrapper.getAltSourceRnode(),
                "INT_X6Y10/INT_NODE_SDQ_28_INT_OUT0",
                "INT_X6Y10/EE1_E_BEG4");
        Assertions.assertFalse(netWrapper.hasMultipleDrivers(1));
    }

    @Test
    public void testHasMultipleDriversAlternateSourceConvergence() {
        // The connection from the alternate source reaches INT_X6Y11/BOUNCE_E_13_FT0 from a different parent than the
        // connection from the source does
        NetWrapper netWrapper = createNetWrapperWithAlternateSource();
        addConnection(netWrapper,
                "INT_X6Y10/SDQNODE_E_0_FT1",
                "INT_X6Y9/NN2_E_BEG7",
                "INT_X6Y11/INT_NODE_IMUX_27_INT_OUT1",
                "INT_X6Y11/BOUNCE_E_13_FT0",
                "INT_X6Y12/INT_NODE_IMUX_22_INT_OUT0");
        addConnection(netWrapper, netWrapper.getAltSourceRnode(),
                "INT_X6Y10/INT_NODE_SDQ_27_INT_OUT1",
                "INT_X6Y10/NN1_E_BEG5",
                "INT_X6Y11/INT_NODE_IMUX_18_INT_OUT0",
                "INT_X6Y11/BOUNCE_E_13_FT0");
        Assertions.assertTrue(netWrapper.hasMultipleDrivers(1));
    }
}

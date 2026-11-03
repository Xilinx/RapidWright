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

import com.xilinx.rapidwright.design.Design;
import com.xilinx.rapidwright.design.Net;
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
        Device device = design.getDevice();
        List<RouteNode> rnodes = new ArrayList<>();
        Node prev = source;
        rnodes.add(routingGraph.getOrCreate(source, RouteNodeType.EXCLUSIVE_SOURCE));
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
}

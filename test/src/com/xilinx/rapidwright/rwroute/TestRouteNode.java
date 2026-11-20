/*
 *
 * Copyright (c) 2026, Advanced Micro Devices, Inc.
 * All rights reserved.
 *
 * Author: Chris Lavin, Advanced Micro Devices, Inc.
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
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import com.xilinx.rapidwright.design.Design;
import com.xilinx.rapidwright.design.Net;
import com.xilinx.rapidwright.device.Node;

public class TestRouteNode {

    private static void assertUsers(Map<NetWrapper, Integer> expected, RouteNode rnode, List<NetWrapper> nets) {
        Assertions.assertEquals(expected.size(), rnode.uniqueUserCount());
        Assertions.assertEquals(expected.size(), rnode.getOccupancy());
        Assertions.assertEquals(expected.size() > RouteNode.capacity, rnode.isOverUsed());
        for (NetWrapper net : nets) {
            Assertions.assertEquals(expected.getOrDefault(net, 0), rnode.countConnectionsOfUser(net));
        }
        if (expected.isEmpty()) {
            Assertions.assertNull(rnode.getUsersConnectionCounts());
        } else {
            Assertions.assertEquals(expected, rnode.getUsersConnectionCounts());
        }
    }

    @Test
    public void testUserCounts() {
        Design design = new Design("test", "xcvu3p");
        RouteNodeGraph graph = new RouteNodeGraph(design, new RWRouteConfig(new String[0]));
        RouteNode rnode = graph.getOrCreate(Node.getNode(design.getDevice().getTile("INT_X10Y10"), "EE2_E_BEG0"));
        List<NetWrapper> nets = new ArrayList<>();
        for (int i = 0; i < 12; i++) {
            nets.add(new NetWrapper(i, new Net("n" + i)));
        }
        // Random increments and decrements, checked against a map
        Map<NetWrapper, Integer> expected = new IdentityHashMap<>();
        Random random = new Random(1);
        for (int step = 0; step < 5000; step++) {
            NetWrapper net = nets.get(random.nextInt(step < 2500 ? nets.size() : 3));
            if (expected.containsKey(net) && random.nextBoolean()) {
                rnode.decrementUser(net);
                expected.compute(net, (k, v) -> v == 1 ? null : v - 1);
            } else {
                rnode.incrementUser(net);
                expected.merge(net, 1, Integer::sum);
            }
            assertUsers(expected, rnode, nets);
        }
        // Remove all users
        for (NetWrapper net : nets) {
            for (int n = expected.getOrDefault(net, 0); n > 0; n--) {
                rnode.decrementUser(net);
            }
        }
        expected.clear();
        assertUsers(expected, rnode, nets);
        Assertions.assertFalse(rnode.isUsed());
        Assertions.assertThrows(RuntimeException.class, () -> rnode.decrementUser(nets.get(0)));
    }
}

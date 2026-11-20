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

package com.xilinx.rapidwright.timing.delayestimator;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import com.xilinx.rapidwright.device.Device;
import com.xilinx.rapidwright.device.Node;
import com.xilinx.rapidwright.device.Tile;
import com.xilinx.rapidwright.device.TileTypeEnum;

/**
 * Checks that the fast paths of the delay estimators give the same delays as the original ones:
 * {@link DelayEstimator#getDelayOf(Node, com.xilinx.rapidwright.device.Wire[])} with the wires the
 * caller already has, and {@link DelayEstimator#getExtraDelay(int, int)} by node class; and that
 * {@link DelayEstimator#getLeastExtraDelay(int)} is a lower bound of the extra delays.
 */
public class TestDelayEstimatorFastPaths {

    /** Some INT tiles of a device: at its corners and edges, and in its middle */
    private static List<Tile> getSampleIntTiles(Device device) {
        List<Tile> ints = new ArrayList<>();
        for (Tile tile : device.getAllTiles()) {
            if (tile.getTileTypeEnum() == TileTypeEnum.INT) {
                ints.add(tile);
            }
        }
        ints.sort((a, b) -> a.getRow() != b.getRow() ? Integer.compare(a.getRow(), b.getRow())
                : Integer.compare(a.getColumn(), b.getColumn()));
        List<Tile> sample = new ArrayList<>();
        sample.add(ints.get(0));
        sample.add(ints.get(ints.size() / 2));
        sample.add(ints.get(ints.size() - 1));
        // The first INT tile of the middle row of INT tiles, at the device's left edge
        Tile mid = ints.get(ints.size() / 2);
        for (Tile t : ints) {
            if (t.getRow() == mid.getRow()) {
                sample.add(t);
                break;
            }
        }
        return sample;
    }

    @ParameterizedTest
    @ValueSource(strings = {"xcvc1902", "xcvu3p"})
    public void testSameDelays(String deviceName) {
        Device device = Device.getDevice(deviceName);
        DelayEstimator estimator = DelayEstimator.create(device, false);
        int numNodes = 0;
        int numArcs = 0;
        for (Tile tile : getSampleIntTiles(device)) {
            for (int wire = 0; wire < tile.getWireCount(); wire++) {
                Node node = Node.getNode(tile, wire);
                if (node == null || node.isInvalidNode()) continue;
                if (estimator.isDelayCharged(node)) {
                    Assertions.assertEquals(estimator.getDelayOf(node),
                            estimator.getDelayOf(node, node.getAllWiresInNode()), node.toString());
                    numNodes++;
                }
                int nodeClass = estimator.getExtraDelayClass(node);
                Assertions.assertTrue(nodeClass >= 0 && nodeClass <= Byte.MAX_VALUE, node.toString());
                for (Node child : node.getAllDownhillNodes()) {
                    Assertions.assertEquals(estimator.getExtraDelay(node, child),
                            estimator.getExtraDelay(nodeClass, estimator.getExtraDelayClass(child)),
                            node + " -> " + child);
                    // The least extra delays are lower bounds of every hop's
                    int extra = estimator.getExtraDelay(node, child);
                    Assertions.assertTrue(extra >= estimator.getLeastExtraDelay(estimator.getExtraDelayClass(child)),
                            node + " -> " + child);
                    Assertions.assertTrue(extra >= estimator.getLeastExtraDelay(-1), node + " -> " + child);
                    numArcs++;
                }
            }
        }
        // The fitted models have negative transitions (between a general wire and the node it drives)
        Assertions.assertTrue(estimator.getLeastExtraDelay(-1) < 0, Short.toString(estimator.getLeastExtraDelay(-1)));
        Assertions.assertTrue(numNodes > 1000, "nodes " + numNodes);
        Assertions.assertTrue(numArcs > 10000, "arcs " + numArcs);
    }
}

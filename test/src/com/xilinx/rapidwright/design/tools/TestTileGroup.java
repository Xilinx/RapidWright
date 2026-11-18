/*
 * Copyright (c) 2024, Advanced Micro Devices, Inc.
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

package com.xilinx.rapidwright.design.tools;

import java.util.Map;
import java.util.Map.Entry;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import com.xilinx.rapidwright.device.ClockRegion;
import com.xilinx.rapidwright.device.Device;
import com.xilinx.rapidwright.device.Tile;

public class TestTileGroup {

    @Test
    public void testTileGroup() {
        Device d = Device.getDevice("xcvc1902");

        for (ClockRegion[] crs : d.getClockRegions()) {
            for (ClockRegion cr : crs) {
                if (cr.getUpperLeft() == null || cr.getUpperRight() == null) {
                    continue;
                }
                TileGroup tg = new TileGroup(cr.getUpperLeft(), cr.getLowerRight());
                Map<Tile, Edge> tileMap = tg.getRegionTiles();
                for (Entry<Tile, Edge> e : tileMap.entrySet()) {
                    Assertions.assertEquals(cr, e.getKey().getClockRegion());
                    Assertions.assertTrue(e.getValue() != Edge.EXTERNAL);
                }

                for (Tile tile : d.getAllTiles()) {
                    Edge edge = tg.getEdgeOfTile(tile);
                    if (tile.getClockRegion() == cr) {
                        Assertions.assertNotEquals(Edge.EXTERNAL, edge);
                    } else {
                        Assertions.assertEquals(Edge.EXTERNAL, edge);
                    }
                }
            }
        }
    }
}

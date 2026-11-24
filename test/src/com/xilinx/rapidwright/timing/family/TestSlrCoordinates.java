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

package com.xilinx.rapidwright.timing.family;

import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import com.xilinx.rapidwright.device.Device;
import com.xilinx.rapidwright.device.Tile;
import com.xilinx.rapidwright.device.TileTypeEnum;

public class TestSlrCoordinates {

    /** On devices whose SLRs share one coordinate system, global coordinates are the tile coordinates */
    @ParameterizedTest
    @ValueSource(strings = {"xcvu3p", "xcvc1902", "xcvu9p", "xcvp1552", "xcvp1802"})
    public void testTileCoordinatesUnchanged(String deviceName) {
        Device device = Device.getDevice(deviceName);
        SlrCoordinates g = SlrCoordinates.of(device);
        Assertions.assertFalse(g.isSlrLocal());
        for (Tile t : device.getAllTiles()) {
            Assertions.assertEquals(t.getTileXCoordinate(), g.x(t), t.getName());
            Assertions.assertEquals(t.getTileYCoordinate(), g.y(t), t.getName());
        }
    }

    /**
     * On xcvp1902 (SLR-local tile coordinates), every INT tile gets a global X/Y coordinate of its own, X grows with
     * the device column and Y with decreasing device row, also between SLRs.
     */
    @ParameterizedTest
    @ValueSource(strings = {"xcvp1902"})
    public void testGlobalGrid(String deviceName) {
        Device device = Device.getDevice(deviceName);
        SlrCoordinates g = SlrCoordinates.of(device);
        Assertions.assertTrue(g.isSlrLocal());
        Map<Long, Tile> byGlobal = new HashMap<>();
        int ints = 0;
        for (Tile t : device.getAllTiles()) {
            Assertions.assertTrue(t.getSLRIndex() >= 0, t.getName());
            if (t.getTileTypeEnum() != TileTypeEnum.INT) {
                continue;
            }
            ints++;
            Tile other = byGlobal.put(((long) g.x(t) << 32) | g.y(t), t);
            Assertions.assertNull(other, () -> t.getName() + " and " + other.getName());
        }
        Assertions.assertTrue(ints > 0);
        // Monotonic along device columns and rows: compare each INT tile with the next INT tile in its device row
        // and in its device column
        Map<Integer, Tile> lastInRow = new HashMap<>();
        Map<Integer, Tile> lastInCol = new HashMap<>();
        for (Tile t : device.getAllTiles()) {
            if (t.getTileTypeEnum() != TileTypeEnum.INT) {
                continue;
            }
            Tile r = lastInRow.put(t.getRow(), t);
            if (r != null) {
                Assertions.assertEquals(Integer.signum(t.getColumn() - r.getColumn()),
                        Integer.signum(g.x(t) - g.x(r)), () -> r.getName() + " " + t.getName());
            }
            Tile c = lastInCol.put(t.getColumn(), t);
            if (c != null) {
                Assertions.assertEquals(Integer.signum(c.getRow() - t.getRow()),
                        Integer.signum(g.y(t) - g.y(c)), () -> c.getName() + " " + t.getName());
            }
        }
    }
}

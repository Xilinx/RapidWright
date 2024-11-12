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

import java.util.HashMap;
import java.util.Map;

import com.xilinx.rapidwright.device.ClockRegion;
import com.xilinx.rapidwright.device.Device;
import com.xilinx.rapidwright.device.Tile;

/**
 * Simple class to capture a rectangular group of tiles defined by the upper
 * left and lower right corner tiles.
 */
public class TileGroup {

    private Tile upperLeft;

    private Tile lowerRight;

    private int northRow;

    private int southRow;

    private int westColumn;

    private int eastColumn;

    public TileGroup(Tile upperLeft, Tile lowerRight) {
        this.upperLeft = upperLeft;
        this.lowerRight = lowerRight;
        northRow = upperLeft.getRow();
        southRow = lowerRight.getRow();
        westColumn = upperLeft.getColumn();
        eastColumn = lowerRight.getColumn();
    }

    public TileGroup(ClockRegion cr) {
        this(cr.getUpperLeft(), cr.getLowerRight());
    }

    public Map<Tile, Edge> getRegionTiles() {
        Map<Tile, Edge> tiles = new HashMap<>();
        Device device = upperLeft.getDevice();
        for (int row = northRow; row <= southRow; row++) {
            for (int col = westColumn; col <= eastColumn; col++) {
                Tile tile = device.getTile(row, col);
                tiles.put(tile, getEdgeOfTile(tile));
            }
        }
        return tiles;
    }

    public Edge getEdgeOfTile(Tile tile) {
        if (!tileInRegion(tile)) {
            return Edge.EXTERNAL;
        }
        int row = tile.getRow();
        int col = tile.getColumn();

        if (row == northRow) {
            return col == eastColumn ? Edge.NORTH_EAST : Edge.NORTH;
        } else if (row == southRow) {
            return col == westColumn ? Edge.SOUTH_WEST : Edge.SOUTH;
        } else if (col == westColumn) {
            return row == northRow ? Edge.NORTH_WEST : Edge.WEST;
        } else if (col == eastColumn) {
            return row == southRow ? Edge.SOUTH_EAST : Edge.EAST;
        }

        return Edge.INTERNAL;
    }

    public boolean tileInRegion(Tile tile) {
        int row = tile.getRow();
        int col = tile.getColumn();
        return northRow <= row && row <= southRow && westColumn <= col && col <= eastColumn;

    }

    public String toString() {
        return "[" + upperLeft + " (" + upperLeft.getColumn() + ", " + upperLeft.getRow() + ")" + ":"
                + lowerRight + " (" + lowerRight.getColumn() + ", " + lowerRight.getRow() + ")" + "]";
    }
}

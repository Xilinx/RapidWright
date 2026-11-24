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

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import com.xilinx.rapidwright.device.Device;
import com.xilinx.rapidwright.device.Tile;
import com.xilinx.rapidwright.device.TileTypeEnum;

/**
 * Device-wide tile coordinates for devices whose SLRs each have their own tile coordinates (as xcvp1902, whose tile
 * names carry the SLR: INT_S&lt;slr&gt;X&lt;x&gt;Y&lt;y&gt;, and on which several INT tiles share an X/Y coordinate).
 * The SLRs' local coordinates are placed in one grid: per SLR, global X = offset + (flipped ? max - X : X), global Y
 * likewise, global X growing with the device column and global Y growing upwards (with decreasing device row), the
 * SLRs side by side as their device columns and rows are. On all other devices the global coordinates are the tile
 * coordinates. Distances between tiles of different SLRs (e.g. the span of an SLL, or of a connection between SLRs)
 * are only meaningful in global coordinates.
 */
public final class SlrCoordinates {

    private static final Map<String, SlrCoordinates> CACHE = new ConcurrentHashMap<>();

    private final boolean local;
    private final int[] offX, offY, flip, maxX, maxY;

    /**
     * @param device The device.
     * @return Its coordinates (computed once per device).
     */
    public static SlrCoordinates of(Device device) {
        return CACHE.computeIfAbsent(device.getName(), k -> new SlrCoordinates(device));
    }

    private SlrCoordinates(Device device) {
        int numSlrs = 1;
        // Local coordinates: INT tiles of different SLRs share X/Y coordinates
        Map<Long, Integer> slrOfCoordinate = new HashMap<>();
        boolean shared = false;
        java.util.List<Tile> ints = new java.util.ArrayList<>();
        for (Tile t : device.getAllTiles()) {
            if (t.getTileTypeEnum() != TileTypeEnum.INT) continue;
            ints.add(t);
            int slr = Math.max(0, t.getSLRIndex());
            numSlrs = Math.max(numSlrs, slr + 1);
            Integer other = slrOfCoordinate.put(((long) t.getTileXCoordinate() << 32) | t.getTileYCoordinate(), slr);
            shared |= other != null && other != slr;
        }
        local = shared;
        offX = new int[numSlrs];
        offY = new int[numSlrs];
        flip = new int[numSlrs];
        maxX = new int[numSlrs];
        maxY = new int[numSlrs];
        if (!local) return;
        int[] minC = new int[numSlrs];
        int[] maxC = new int[numSlrs];
        int[] minR = new int[numSlrs];
        int[] maxR = new int[numSlrs];
        Arrays.fill(minC, Integer.MAX_VALUE);
        Arrays.fill(minR, Integer.MAX_VALUE);
        Arrays.fill(maxC, -1);
        Arrays.fill(maxR, -1);
        Arrays.fill(maxX, -1);
        Arrays.fill(maxY, -1);
        int[] sx = new int[numSlrs];
        int[] sy = new int[numSlrs];
        Map<Long, Tile> firstInRow = new HashMap<>();
        Map<Long, Tile> firstInCol = new HashMap<>();
        for (Tile t : ints) {
            int s = Math.max(0, t.getSLRIndex());
            int x = t.getTileXCoordinate();
            int y = t.getTileYCoordinate();
            maxX[s] = Math.max(maxX[s], x);
            maxY[s] = Math.max(maxY[s], y);
            minC[s] = Math.min(minC[s], t.getColumn());
            maxC[s] = Math.max(maxC[s], t.getColumn());
            minR[s] = Math.min(minR[s], t.getRow());
            maxR[s] = Math.max(maxR[s], t.getRow());
            // Direction of X along the device columns, of Y along the device rows
            Tile r = firstInRow.putIfAbsent(((long) s << 32) | t.getRow(), t);
            if (r != null && sx[s] == 0 && r.getTileXCoordinate() != x) {
                sx[s] = Integer.signum((t.getColumn() - r.getColumn()) * (x - r.getTileXCoordinate()));
            }
            Tile c = firstInCol.putIfAbsent(((long) s << 32) | t.getColumn(), t);
            if (c != null && sy[s] == 0 && c.getTileYCoordinate() != y) {
                sy[s] = Integer.signum((t.getRow() - c.getRow()) * (y - c.getTileYCoordinate()));
            }
        }
        // Grid columns: SLRs whose device column ranges overlap; grid rows likewise (bottom first)
        Integer[] byC = new Integer[numSlrs];
        Integer[] byR = new Integer[numSlrs];
        for (int i = 0; i < numSlrs; i++) {
            byC[i] = i;
            byR[i] = i;
        }
        Arrays.sort(byC, (a, b) -> Integer.compare(minC[a], minC[b]));
        Arrays.sort(byR, (a, b) -> Integer.compare(-maxR[a], -maxR[b]));
        int[] gridX = new int[numSlrs];
        int[] gridY = new int[numSlrs];
        for (int k = 1; k < numSlrs; k++) {
            gridX[byC[k]] = gridX[byC[k - 1]] + (minC[byC[k]] > maxC[byC[k - 1]] ? 1 : 0);
            gridY[byR[k]] = gridY[byR[k - 1]] + (maxR[byR[k]] < minR[byR[k - 1]] ? 1 : 0);
        }
        for (int s = 0; s < numSlrs; s++) {
            // sy > 0: Y grows downwards (with the device row)
            flip[s] = (sx[s] < 0 ? 1 : 0) | (sy[s] > 0 ? 2 : 0);
            for (int t = 0; t < numSlrs; t++) {
                if (gridX[t] < gridX[s] && gridY[t] == gridY[s]) offX[s] += maxX[t] + 1;
                if (gridY[t] < gridY[s] && gridX[t] == gridX[s]) offY[s] += maxY[t] + 1;
            }
        }
    }

    /** @return The number of SLRs with INT tiles (at least 1). */
    int getNumSlrs() {
        return offX.length;
    }

    /**
     * Checks if the SLRs of the device have tile coordinates of their own (INT tiles of different SLRs share
     * X/Y coordinates, as on xcvp1902), which {@link #x(Tile)} and {@link #y(Tile)} place in one grid; else
     * these are the tile coordinates.
     * @return True if so.
     */
    public boolean isSlrLocal() {
        return local;
    }

    /** @return The global X of a local X coordinate in an SLR. */
    int x(int slr, int localX) {
        return local ? offX[slr] + ((flip[slr] & 1) != 0 ? maxX[slr] - localX : localX) : localX;
    }

    /** @return The global Y of a local Y coordinate in an SLR. */
    int y(int slr, int localY) {
        return local ? offY[slr] + ((flip[slr] & 2) != 0 ? maxY[slr] - localY : localY) : localY;
    }

    /** @return The global X coordinate of a tile. */
    public int x(Tile t) {
        return local ? x(Math.max(0, t.getSLRIndex()), t.getTileXCoordinate()) : t.getTileXCoordinate();
    }

    /** @return The global Y coordinate of a tile. */
    public int y(Tile t) {
        return local ? y(Math.max(0, t.getSLRIndex()), t.getTileYCoordinate()) : t.getTileYCoordinate();
    }

    /** @return The largest local X coordinate of an INT tile in an SLR (only with SLR-local coordinates). */
    int maxX(int slr) {
        return maxX[slr];
    }

    /** @return The largest local Y coordinate of an INT tile in an SLR (only with SLR-local coordinates). */
    int maxY(int slr) {
        return maxY[slr];
    }
}

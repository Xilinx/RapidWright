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

import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

import com.xilinx.rapidwright.device.ClockRegion;
import com.xilinx.rapidwright.device.Device;
import com.xilinx.rapidwright.device.Node;
import com.xilinx.rapidwright.device.Tile;
import com.xilinx.rapidwright.device.TileTypeEnum;
import com.xilinx.rapidwright.device.Wire;

/**
 * Counts the discontinuities a node crosses: the "d" of the delay model of the FPT 2019 RapidWright
 * timing paper, delay = k0 + k1*L + k2*d. A node crosses the gaps between consecutive INT columns and
 * between consecutive INT rows that lie within the span of its INT tiles. Each gap holds non-INT tiles (DSP,
 * BRAM, RCLK, ...), which column and row classes classify by matching their tile type names against
 * regular expressions (see {@link InterconnectParams}); a gap counts once for every class with at least
 * one tile in it. A tile type matching several patterns belongs to the class of the first one.
 * <p>
 * Column gaps are found per band of INT rows (a clock region), since hard blocks differ between
 * bands; row gaps are found per INT column. Both are stored as cumulative
 * counts indexed by INT tile coordinate, so a node's crossings cost one subtraction per class.
 * On devices whose INT tile coordinates are local to each SLR (they restart in every SLR, as on xcvp1902),
 * the tables are kept per SLR and a node uses those of the SLR of its base tile; a node
 * whose wires lie in several SLRs (an SLL) is measured in device-wide coordinates (see {@link SlrCoordinates}),
 * in tables of their own whose bands are strips of {@value #BAND_ROWS} rows across the SLRs. Both sets are needed:
 * measured in the device-wide tables, some nodes of one SLR (e.g. of the interface tiles of the processing
 * system) would cross other gaps than in their SLR's tables.
 * <p>
 * The index of a device is built once and shared, see {@link #of(Device, InterconnectParams)}.
 */
public class CrossingIndex {

    /**
     * The INT rows of a Versal clock region: the height of a band of INT tiles without a clock region, and of the
     * bands of the device-wide tables of devices with SLR-local coordinates (Versal devices only; every INT tile of
     * an UltraScale+ device has a clock region)
     */
    private static final int BAND_ROWS = 96;

    /**
     * The indexes of each device and set of column and row classes, shared by the delay estimators of all corners
     * of a device. Kept for the life of the process, as the node classes of the wires and Device's own cache of
     * loaded devices: an index holds no reference to its device, only its tables (cumulative counts, a few bytes
     * per INT column and row and class), so their number is bounded by the devices a process opens and the sets of
     * class patterns it uses on them (one per family with the shipped data).
     */
    private static final Map<String, CrossingIndex> CACHE = new ConcurrentHashMap<>();

    private final int numColClasses;
    private final int numRowClasses;
    /**
     * Whether INT tile coordinates are local to each SLR: INT tiles of different SLRs share X/Y coordinates, as on
     * xcvp1902, whose tile names carry the SLR (INT_S&lt;slr&gt;X&lt;x&gt;Y&lt;y&gt;). The tables are then kept per SLR;
     * otherwise there is one set of tables, for SLR key 0.
     */
    private final boolean slrLocal;
    /** Band of each INT Y coordinate, per SLR key */
    private final int[][] bandOfY;
    /** Cumulative column gap counts, indexed by band, column class and INT X coordinate */
    private final short[][][] colCum;
    /** Cumulative row gap counts, indexed by SLR key, INT X coordinate, row class and INT Y coordinate */
    private final short[][][][] rowCum;
    /** Cumulative row gap counts of the INT column with the most INT tiles, per SLR key */
    private final short[][][] refRowCum;
    /**
     * With SLR-local coordinates: the number of SLRs, which is also the key of the tables in device-wide (global)
     * coordinates that nodes spanning several SLRs (SLLs) are measured in
     */
    private final int globalKey;
    /** The device-wide coordinates of the SLRs' local ones */
    private final SlrCoordinates global;
    /** The largest local INT X and Y coordinates */
    private final int localMaxX, localMaxY;

    /**
     * Gets the column and row gaps of a device, for the column and row classes of interconnect parameters: found
     * once per device and set of classes, then shared.
     * @param device The device.
     * @param params The parameters, defining the column and row classes.
     * @return The index.
     */
    public static CrossingIndex of(Device device, InterconnectParams params) {
        List<Pattern> cols = params.getColClassPatterns();
        List<Pattern> rows = params.getRowClassPatterns();
        return CACHE.computeIfAbsent(device.getName() + "|" + cols + "|" + rows,
                k -> new CrossingIndex(device, cols, rows));
    }

    /**
     * Finds the column and row gaps of a device.
     * @param device The device.
     * @param colClassPatterns Tile type patterns of the column classes, in order of precedence.
     * @param rowClassPatterns Tile type patterns of the row classes, in order of precedence.
     */
    private CrossingIndex(Device device, List<Pattern> colClassPatterns, List<Pattern> rowClassPatterns) {
        numColClasses = colClassPatterns.size();
        numRowClasses = rowClassPatterns.size();
        Map<TileTypeEnum, Integer> colClassOfType = new EnumMap<>(TileTypeEnum.class);
        Map<TileTypeEnum, Integer> rowClassOfType = new EnumMap<>(TileTypeEnum.class);

        // INT tiles by band, then X; and by X, then Y
        int maxX = 0;
        int maxY = 0;
        Map<Integer, TreeMap<Integer, List<Tile>>> intTilesByBandAndX = new HashMap<>();
        Map<Long, Integer> bandIds = new HashMap<>();
        List<Tile> intTiles = new ArrayList<>();
        for (Tile tile : device.getAllTiles()) {
            if (tile.getTileTypeEnum() == TileTypeEnum.INT) {
                intTiles.add(tile);
                maxX = Math.max(maxX, tile.getTileXCoordinate());
                maxY = Math.max(maxY, tile.getTileYCoordinate());
            }
        }
        global = SlrCoordinates.of(device);
        slrLocal = global.isSlrLocal();
        int numSlrs = global.getNumSlrs();
        int numKeys = slrLocal ? numSlrs + 1 : 1;
        globalKey = slrLocal ? numSlrs : -1;
        localMaxX = maxX;
        localMaxY = maxY;
        if (slrLocal) {
            for (int s = 0; s < numSlrs; s++) {
                maxX = Math.max(maxX, Math.max(global.x(s, 0), global.x(s, global.maxX(s))));
                maxY = Math.max(maxY, Math.max(global.y(s, 0), global.y(s, global.maxY(s))));
            }
        }
        bandOfY = new int[numKeys][maxY + 1];
        for (int[] b : bandOfY) Arrays.fill(b, -1);
        List<Map<Integer, TreeMap<Integer, Tile>>> intTilesByXAndY = new ArrayList<>();
        for (int k = 0; k < numKeys; k++) intTilesByXAndY.add(new TreeMap<>());
        for (Tile tile : intTiles) {
            int x = tile.getTileXCoordinate();
            int y = tile.getTileYCoordinate();
            int sk = slrKey(tile);
            addIntTile(tile, sk, x, y, bandIds, intTilesByBandAndX, intTilesByXAndY);
            if (slrLocal) {
                // The same tile in the global tables (bands of their own)
                int s = slrIndex(tile);
                addIntTile(tile, globalKey, gx(s, x), gy(s, y), bandIds, intTilesByBandAndX, intTilesByXAndY);
            }
        }

        Tile[][] grid = device.getTiles();
        colCum = new short[bandIds.size()][numColClasses][maxX + 1];
        for (Map.Entry<Integer, TreeMap<Integer, List<Tile>>> e : intTilesByBandAndX.entrySet()) {
            short[][] cum = colCum[e.getKey()];
            int minRow = Integer.MAX_VALUE;
            int maxRow = Integer.MIN_VALUE;
            for (List<Tile> tiles : e.getValue().values()) {
                for (Tile t : tiles) {
                    minRow = Math.min(minRow, t.getRow());
                    maxRow = Math.max(maxRow, t.getRow());
                }
            }
            Integer prevX = null;
            int prevCol = 0;
            for (Map.Entry<Integer, List<Tile>> xe : e.getValue().entrySet()) {
                int x = xe.getKey();
                int col = xe.getValue().get(0).getColumn();
                if (prevX != null) {
                    boolean[] present = new boolean[numColClasses];
                    int lo = Math.min(prevCol, col) + 1;
                    int hi = Math.max(prevCol, col) - 1;
                    for (int r = minRow; r <= maxRow; r++) {
                        for (int c = lo; c <= hi; c++) {
                            int cls = classOf(grid[r][c], colClassPatterns, colClassOfType);
                            if (cls >= 0) {
                                present[cls] = true;
                            }
                        }
                    }
                    for (int j = 0; j < numColClasses; j++) {
                        short v = (short) (cum[j][prevX] + (present[j] ? 1 : 0));
                        for (int xx = prevX + 1; xx <= x; xx++) {
                            cum[j][xx] = (xx == x) ? v : cum[j][prevX];
                        }
                    }
                }
                prevX = x;
                prevCol = col;
            }
            if (prevX != null) {
                for (int j = 0; j < numColClasses; j++) {
                    Arrays.fill(cum[j], prevX + 1, maxX + 1, cum[j][prevX]);
                }
            }
        }

        // Row gaps along each INT column, falling back to the column with the most INT tiles for nodes
        // in columns without INT tiles
        rowCum = new short[numKeys][maxX + 1][][];
        refRowCum = new short[numKeys][][];
        for (int k = 0; k < numKeys; k++) {
            TreeMap<Integer, Tile> refColumn = null;
            for (TreeMap<Integer, Tile> c : intTilesByXAndY.get(k).values()) {
                if (refColumn == null || c.size() > refColumn.size()) {
                    refColumn = c;
                }
            }
            for (Map.Entry<Integer, TreeMap<Integer, Tile>> xe : intTilesByXAndY.get(k).entrySet()) {
                rowCum[k][xe.getKey()] = buildRowCum(xe.getValue(), grid, rowClassPatterns, rowClassOfType, maxY);
            }
            refRowCum[k] = refColumn == null ? new short[numRowClasses][maxY + 1]
                    : buildRowCum(refColumn, grid, rowClassPatterns, rowClassOfType, maxY);
        }
    }

    private void addIntTile(Tile tile, int sk, int x, int y, Map<Long, Integer> bandIds,
                            Map<Integer, TreeMap<Integer, List<Tile>>> intTilesByBandAndX,
                            List<Map<Integer, TreeMap<Integer, Tile>>> intTilesByXAndY) {
        if (bandOfY[sk][y] < 0) {
            ClockRegion cr = tile.getClockRegion();
            long key = cr == null ? (slrLocal ? -1 - ((long) sk << 32) - y / BAND_ROWS : -1 - y / BAND_ROWS)
                    : ((long) slrIndex(tile) << 32) | cr.getRow();
            if (sk == globalKey) {
                // Global bands: strips of global INT rows (a clock region) across the SLRs side by side
                key = (1L << 40) | (y / BAND_ROWS);
            }
            Integer id = bandIds.get(key);
            if (id == null) {
                id = bandIds.size();
                bandIds.put(key, id);
            }
            bandOfY[sk][y] = id;
        }
        intTilesByBandAndX.computeIfAbsent(bandOfY[sk][y], k -> new TreeMap<>())
                .computeIfAbsent(x, k -> new ArrayList<>()).add(tile);
        intTilesByXAndY.get(sk).computeIfAbsent(x, k -> new TreeMap<>()).put(y, tile);
    }

    /** Global X of a local X coordinate of an SLR */
    private int gx(int slr, int x) {
        return global.x(slr, x);
    }

    /** Global Y of a local Y coordinate of an SLR */
    private int gy(int slr, int y) {
        return global.y(slr, y);
    }

    /** The SLR index of a tile; 0 for a tile without one */
    private static int slrIndex(Tile t) {
        return Math.max(0, t.getSLRIndex());
    }

    /** The SLR key of a tile: its SLR index when coordinates are local to SLRs, else 0 */
    private int slrKey(Tile t) {
        return slrLocal ? slrIndex(t) : 0;
    }

    /** @return Whether INT tile coordinates are local to each SLR (and the tables are kept per SLR). */
    boolean isSlrLocal() {
        return slrLocal;
    }

    private short[][] buildRowCum(TreeMap<Integer, Tile> column, Tile[][] grid, List<Pattern> rowClassPatterns,
                                  Map<TileTypeEnum, Integer> rowClassOfType, int maxY) {
        short[][] cum = new short[numRowClasses][maxY + 1];
        Integer prevY = null;
        Tile prevTile = null;
        for (Map.Entry<Integer, Tile> ye : column.entrySet()) {
            int y = ye.getKey();
            Tile tile = ye.getValue();
            if (prevY != null) {
                boolean[] present = new boolean[numRowClasses];
                int lo = Math.min(prevTile.getRow(), tile.getRow()) + 1;
                int hi = Math.max(prevTile.getRow(), tile.getRow()) - 1;
                for (int r = lo; r <= hi; r++) {
                    int cls = classOf(grid[r][tile.getColumn()], rowClassPatterns, rowClassOfType);
                    if (cls >= 0) {
                        present[cls] = true;
                    }
                }
                for (int j = 0; j < numRowClasses; j++) {
                    short v = (short) (cum[j][prevY] + (present[j] ? 1 : 0));
                    for (int yy = prevY + 1; yy <= y; yy++) {
                        cum[j][yy] = (yy == y) ? v : cum[j][prevY];
                    }
                }
            }
            prevY = y;
            prevTile = tile;
        }
        if (prevY != null) {
            for (int j = 0; j < numRowClasses; j++) {
                Arrays.fill(cum[j], prevY + 1, maxY + 1, cum[j][prevY]);
            }
        }
        return cum;
    }

    private static int classOf(Tile tile, List<Pattern> patterns, Map<TileTypeEnum, Integer> cache) {
        if (tile == null) {
            return -1;
        }
        return cache.computeIfAbsent(tile.getTileTypeEnum(), type -> {
            for (int i = 0; i < patterns.size(); i++) {
                if (patterns.get(i).matcher(type.name()).find()) {
                    return i;
                }
            }
            return -1;
        });
    }

    /**
     * @return The number of column classes plus the number of row classes.
     */
    public int getNumCrossingClasses() {
        return numColClasses + numRowClasses;
    }

    /**
     * Counts the gaps of each class that a node crosses, and measures the node's span.
     * The span of a node is that of the tiles of its wires in INT tiles; a node without wires in INT
     * tiles, such as a super long line, spans the tiles of all its wires instead. On Versal devices, all
     * tiles share the X/Y coordinates of the INT tiles in their row and column, so either way the span
     * is measured in INT columns and rows.
     * @param node The node in question.
     * @param counts Receives the counts of the column classes followed by those of the row classes;
     *               its length must be at least {@link #getNumCrossingClasses()}.
     * @return The length of the node's span: the number of INT columns plus the number of INT rows it
     * spans.
     */
    public int getCrossings(Node node, int[] counts) {
        return getCrossings(node, node.getAllWiresInNode(), counts);
    }

    /**
     * Counts the gaps of each class that a node crosses, and measures the node's span, given the
     * wires of the node (see {@link #getCrossings(Node, int[])}).
     * @param node The node in question.
     * @param wires All the wires of the node, as {@link Node#getAllWiresInNode()} returns them.
     * @param counts Receives the counts of the column classes followed by those of the row classes.
     * @return The length of the node's span.
     */
    int getCrossings(Node node, Wire[] wires, int[] counts) {
        Arrays.fill(counts, 0, getNumCrossingClasses(), 0);
        int minX = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE;
        int minY = Integer.MAX_VALUE;
        int maxY = Integer.MIN_VALUE;
        for (Wire w : wires) {
            Tile t = w.getTile();
            if (t.getTileTypeEnum() != TileTypeEnum.INT) {
                continue;
            }
            int x = t.getTileXCoordinate();
            int y = t.getTileYCoordinate();
            minX = Math.min(minX, x);
            maxX = Math.max(maxX, x);
            minY = Math.min(minY, y);
            maxY = Math.max(maxY, y);
        }
        boolean intWires = minX != Integer.MAX_VALUE;
        Tile base = node.getTile();
        int sk = slrKey(base);
        if (slrLocal && spansSlrs(wires, intWires)) {
            // A node between SLRs (an SLL): measured in global coordinates, so that its span and the gaps it crosses
            // (the SLR boundary included) are those between its ends in the device
            minX = minY = Integer.MAX_VALUE;
            maxX = maxY = Integer.MIN_VALUE;
            for (Wire w : wires) {
                Tile t = w.getTile();
                if (intWires && t.getTileTypeEnum() != TileTypeEnum.INT) {
                    continue;
                }
                int s = slrIndex(t);
                int x = Math.min(gx(s, Math.min(t.getTileXCoordinate(), global.maxX(s))),
                        rowCum[globalKey].length - 1);
                int y = Math.min(gy(s, Math.min(t.getTileYCoordinate(), global.maxY(s))),
                        bandOfY[globalKey].length - 1);
                minX = Math.min(minX, x);
                maxX = Math.max(maxX, x);
                minY = Math.min(minY, y);
                maxY = Math.max(maxY, y);
            }
            int s = slrIndex(base);
            int bx = gx(s, Math.min(base.getTileXCoordinate(), global.maxX(s)));
            int by = gy(s, Math.min(base.getTileYCoordinate(), global.maxY(s)));
            return countGaps(globalKey, base.getTileTypeEnum() == TileTypeEnum.INT, bx, by, minX, maxX, minY, maxY,
                    counts);
        }
        if (!intWires) {
            for (Wire w : wires) {
                Tile t = w.getTile();
                int x = Math.min(t.getTileXCoordinate(), localMaxX);
                int y = Math.min(t.getTileYCoordinate(), localMaxY);
                minX = Math.min(minX, x);
                maxX = Math.max(maxX, x);
                minY = Math.min(minY, y);
                maxY = Math.max(maxY, y);
            }
        }
        return countGaps(sk, base.getTileTypeEnum() == TileTypeEnum.INT, base.getTileXCoordinate(),
                base.getTileYCoordinate(), minX, maxX, minY, maxY, counts);
    }

    /** Whether the wires (those in INT tiles if intWires) of a node are in more than one SLR */
    private static boolean spansSlrs(Wire[] wires, boolean intWires) {
        int s0 = -1;
        for (Wire w : wires) {
            Tile t = w.getTile();
            if (intWires && t.getTileTypeEnum() != TileTypeEnum.INT) {
                continue;
            }
            int s = slrIndex(t);
            if (s0 < 0) {
                s0 = s;
            } else if (s != s0) {
                return true;
            }
        }
        return false;
    }

    /**
     * Counts the gaps crossed by a node of the given extent in the tables of an SLR key.
     * @param baseInt Whether the node's base tile is an INT tile (whose coordinates bx, by then select the band and
     *                column of the gaps; else the extent's minima do).
     * @return The length of the node's span.
     */
    private int countGaps(int sk, boolean baseInt, int bx, int by, int minX, int maxX, int minY, int maxY,
                          int[] counts) {
        if (maxX > minX) {
            // Horizontal gaps are taken in the band of the node's base row
            int y = baseInt ? by : minY;
            int band = bandOfY[sk][y];
            if (band >= 0) {
                short[][] cum = colCum[band];
                for (int j = 0; j < numColClasses; j++) {
                    counts[j] = cum[j][maxX] - cum[j][minX];
                }
            }
        }
        if (maxY > minY) {
            int x = baseInt ? bx : minX;
            short[][] cum = rowCum[sk][x] != null ? rowCum[sk][x] : refRowCum[sk];
            for (int j = 0; j < numRowClasses; j++) {
                counts[numColClasses + j] = cum[j][maxY] - cum[j][minY];
            }
        }
        return (maxX - minX) + (maxY - minY);
    }
}

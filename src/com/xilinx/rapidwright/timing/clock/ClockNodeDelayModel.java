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

package com.xilinx.rapidwright.timing.clock;

import java.util.Arrays;
import java.util.EnumMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import com.xilinx.rapidwright.device.ClockRegion;
import com.xilinx.rapidwright.device.Node;
import com.xilinx.rapidwright.device.PIP;
import com.xilinx.rapidwright.device.Tile;
import com.xilinx.rapidwright.device.TileTypeEnum;
import com.xilinx.rapidwright.device.Wire;
import com.xilinx.rapidwright.timing.family.Corner;

/**
 * Times the nodes of clock routes: classifies them ({@code ClockNodeClassifier}), caching the class
 * of each base wire per tile type, and computes their delays from the {@code ClockDelayParams}.
 * Nodes of classes the parameters do not have get 0 ps, with one warning per class
 * ({@code getUnknownClasses}).
 */
public final class ClockNodeDelayModel {

    private static final short NOT_COMPUTED = -3;
    private static final short DATA_CLASS = -2;
    private static final short UNKNOWN_CLASS = -1;

    private final ClockNodeClassifier classifier;
    private final ClockDelayParams params;
    private final Map<TileTypeEnum, short[]> cache = new EnumMap<>(TileTypeEnum.class);
    private final Set<String> unknownClasses = new TreeSet<>();

    ClockNodeDelayModel(ClockNodeClassifier classifier, ClockDelayParams params) {
        this.classifier = classifier;
        this.params = params;
    }

    ClockDelayParams getParams() {
        return params;
    }

    /** @return The classes met that the parameters do not have (timed as 0 ps). */
    Set<String> getUnknownClasses() {
        return unknownClasses;
    }

    /**
     * Gets the parameter class index of a node.
     * @return The index, -2 for a DATA node, -1 for a class the parameters do not have.
     */
    synchronized int getClassIndex(Node node) {
        Tile tile = node.getTile();
        TileTypeEnum type = tile.getTileTypeEnum();
        short[] classes = cache.get(type);
        int wire = node.getWireIndex();
        if (classes == null) {
            classes = new short[tile.getWireCount()];
            Arrays.fill(classes, NOT_COMPUTED);
            cache.put(type, classes);
        }
        short c = classes[wire];
        if (c == NOT_COMPUTED) {
            String name = classifier.classify(node);
            if (ClockNodeClassifier.DATA.equals(name)) {
                c = DATA_CLASS;
            } else {
                int idx = name == null ? -1 : params.getClassIndex(name);
                if (idx < 0) {
                    unknownClasses.add(String.valueOf(name));
                    c = UNKNOWN_CLASS;
                } else {
                    c = (short) idx;
                }
            }
            classes[wire] = c;
        }
        return c;
    }

    /**
     * Checks if a node is timed as general interconnect in clock routes: by the delay of the device's data
     * interconnect model (see {@link com.xilinx.rapidwright.timing.delayestimator.DelayEstimator}) rather than by a
     * clock node class of the parameters.
     * @param node The node.
     * @return True if so.
     */
    public boolean isDataNode(Node node) {
        return getClassIndex(node) == DATA_CLASS;
    }

    /**
     * Checks if a node belongs to a clock node class of the parameters, which times it in clock routes. A node
     * that neither is a data node ({@link #isDataNode(Node)}) nor has a clock class gets 0 ps.
     * @param node The node.
     * @return True if so.
     */
    public boolean hasClockClass(Node node) {
        return getClassIndex(node) >= 0;
    }

    /**
     * Gets the delay of a node of a clock route at every corner: that of its clock node class (base, length, clock
     * region, SLR and fanout terms), or of the data interconnect model for a data node, 0 for other nodes. The
     * delays that depend on how the route leaves the node (tap terms, programmable delay taps) are not included.
     * Safe to call from several threads.
     * @param node The node.
     * @param branches The number of nodes it drives in the route.
     * @param out Receives the delays in ps, indexed by {@link Corner#ordinal()}; its length must be
     *            {@code Corner.values().length}.
     */
    public void getDelays(Node node, int branches, float[] out) {
        int cls = getClassIndex(node);
        if (cls == DATA_CLASS) {
            ClockDelayParams.DataNodeDelay d = params.getDataNodeDelay();
            for (Corner c : Corner.values()) {
                out[c.ordinal()] = d.getDelay(node, c);
            }
            return;
        }
        if (cls < 0) {
            Arrays.fill(out, 0f);
            return;
        }
        int length = 0;
        int crCols = 0;
        int crRows = 0;
        int slrs = 0;
        if (params.dependsOnSpan(cls)) {
            int[] span = getSpan(node);
            length = span[0];
            crCols = span[1];
            crRows = span[2];
            slrs = span[3];
        }
        for (int c = 0; c < out.length; c++) {
            out[c] = params.getClassDelay(cls, c, length, crCols, crRows, slrs, branches);
        }
    }

    /**
     * Gets the delay a node adds by the way it is left on a path, at every corner: its class's tap
     * terms, and the delay of the programmable delay tap of the PIP leaving it if its wire has a delay
     * tap table.
     * @param node The node.
     * @param tapX Tiles |dx| between the PIP entering the node and the PIP leaving it (or the load).
     * @param tapY Tiles |dy| likewise.
     * @param leaving The PIP leaving the node, or null (e.g. for a load's site pin node).
     * @param out Receives the delays, by corner ordinal.
     */
    void getLeaveDelays(Node node, int tapX, int tapY, PIP leaving, float[] out) {
        int cls = getClassIndex(node);
        boolean terms = cls >= 0 && params.dependsOnLeave(cls);
        int table = leaving == null || leaving.getDelayIndex() <= 0 ? -1 : getDelayTapTable(node);
        for (int c = 0; c < out.length; c++) {
            out[c] = (terms ? params.getLeaveDelay(cls, c, tapX, tapY) : 0f)
                    + (table < 0 ? 0f : params.getDelayTapDelay(table, c, leaving.getDelayIndex()));
        }
    }

    private final Map<TileTypeEnum, short[]> tapTableCache = new EnumMap<>(TileTypeEnum.class);

    /** The delay tap table of a node's base wire (-1 if none), cached per tile type */
    private synchronized int getDelayTapTable(Node node) {
        if (params.getNumDelayTapTables() == 0) {
            return -1;
        }
        Tile tile = node.getTile();
        short[] tables = tapTableCache.get(tile.getTileTypeEnum());
        if (tables == null) {
            tables = new short[tile.getWireCount()];
            Arrays.fill(tables, NOT_COMPUTED);
            tapTableCache.put(tile.getTileTypeEnum(), tables);
        }
        int w = node.getWireIndex();
        if (tables[w] == NOT_COMPUTED) {
            tables[w] = (short) params.getDelayTapTable(node.getWireName());
        }
        return tables[w];
    }

    /**
     * Gets the span of a node, over the tiles of all its wires.
     * @return {LENGTH (dx+dy of the tile coordinates), clock-region columns spanned, clock-region rows
     * spanned, SLRs touched minus 1}.
     */
    static int[] getSpan(Node node) {
        int x0 = Integer.MAX_VALUE;
        int x1 = Integer.MIN_VALUE;
        int y0 = Integer.MAX_VALUE;
        int y1 = Integer.MIN_VALUE;
        int c0 = Integer.MAX_VALUE;
        int c1 = Integer.MIN_VALUE;
        int r0 = Integer.MAX_VALUE;
        int r1 = Integer.MIN_VALUE;
        int s0 = Integer.MAX_VALUE;
        int s1 = Integer.MIN_VALUE;
        for (Wire w : node.getAllWiresInNode()) {
            Tile t = w.getTile();
            x0 = Math.min(x0, t.getTileXCoordinate());
            x1 = Math.max(x1, t.getTileXCoordinate());
            y0 = Math.min(y0, t.getTileYCoordinate());
            y1 = Math.max(y1, t.getTileYCoordinate());
            ClockRegion cr = t.getClockRegion();
            if (cr != null) {
                c0 = Math.min(c0, cr.getColumn());
                c1 = Math.max(c1, cr.getColumn());
                r0 = Math.min(r0, cr.getRow());
                r1 = Math.max(r1, cr.getRow());
            }
            if (t.getSLR() != null) {
                int s = t.getSLRIndex();
                s0 = Math.min(s0, s);
                s1 = Math.max(s1, s);
            }
        }
        return new int[]{
            x1 >= x0 ? (x1 - x0) + (y1 - y0) : 0,
            c1 >= c0 ? c1 - c0 : 0,
            r1 >= r0 ? r1 - r0 : 0,
            s1 >= s0 ? s1 - s0 : 0
        };
    }
}

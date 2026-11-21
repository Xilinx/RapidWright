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
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import com.xilinx.rapidwright.device.Device;
import com.xilinx.rapidwright.device.IntentCode;
import com.xilinx.rapidwright.device.Node;
import com.xilinx.rapidwright.device.Tile;
import com.xilinx.rapidwright.device.TileTypeEnum;
import com.xilinx.rapidwright.device.Wire;
import com.xilinx.rapidwright.timing.delayestimator.DelayEstimator;

/**
 * The SLR crossings of a multi-SLR device as a delay estimator times them: the delay of the cheapest
 * super long line (SLL; UltraScale+ UBUMP wires of intent NODE_LAGUNA_DATA, Versal NODE_SLL_DATA)
 * that joins two SLRs, together with the hops onto and off it, a lower bound of the delay of any route
 * across an SLR boundary. Only SLLs that join two SLRs count: on Versal devices most NODE_SLL_DATA nodes
 * start and end in one SLR (e.g. at the device's edges), and are cheaper than any crossing. A router's lookahead can charge it for each SLR left to cross instead of the delay per
 * row of the rows that the SLLs span, which keeps it admissible over the SLLs (see
 * {@link com.xilinx.rapidwright.rwroute.RWRouteConfig#isSllLookahead()}).
 */
public final class SlrModel {

    private final int numSlrs;
    private final float sllDelay;
    private final int numSllNodes;

    private SlrModel(int numSlrs, float sllDelay, int numSllNodes) {
        this.numSlrs = numSlrs;
        this.sllDelay = sllDelay;
        this.numSllNodes = numSllNodes;
    }

    /**
     * Checks if a node is a super long line between two SLRs.
     * @param node The node.
     * @return True for UltraScale+ UBUMP (NODE_LAGUNA_DATA) and Versal NODE_SLL_DATA nodes.
     */
    public static boolean isSll(Node node) {
        IntentCode ic = node.getIntentCode();
        return ic == IntentCode.NODE_LAGUNA_DATA || ic == IntentCode.NODE_SLL_DATA;
    }

    /**
     * Checks if an SLL node joins two SLRs: if a wire, a driver or a load of it lies in another SLR than its base
     * tile.
     * @param sll The SLL node (see {@link #isSll(Node)}).
     * @return True if it joins two SLRs.
     */
    static boolean joinsSlrs(Node sll) {
        int slr = sll.getTile().getSLRIndex();
        for (Wire w : sll.getAllWiresInNode()) {
            if (w.getTile().getSLRIndex() != slr) {
                return true;
            }
        }
        for (Node v : sll.getAllDownhillNodes()) {
            if (v.getTile().getSLRIndex() != slr) {
                return true;
            }
        }
        for (Node u : sll.getAllUphillNodes()) {
            if (u.getTile().getSLRIndex() != slr) {
                return true;
            }
        }
        return false;
    }

    /**
     * Creates the model of a device under a delay estimator: the cheapest SLL is that of least
     * {@code min over drivers u of extra(u, s) + delay(s) + min over loads v of (delay(v) + extra(s, v))}
     * over all the SLL nodes s of the device that join two SLRs (see {@link #joinsSlrs(Node)}).
     * @param device The device.
     * @param estimator The delay estimator, e.g. a router's.
     * @return The model; for a device with one SLR, or whose SLLs the estimator does not charge, its
     * SLL delay is 0.
     */
    static SlrModel create(Device device, DelayEstimator estimator) {
        int numSlrs = device.getNumOfSLRs();
        if (numSlrs < 2) {
            return new SlrModel(numSlrs, 0f, 0);
        }
        float min = Float.MAX_VALUE;
        int count = 0;
        for (int[] tileWire : getJoiningSlls(device)) {
            Node sll = Node.getNode(device.getTile(tileWire[0]), tileWire[1]);
            float d = getCrossingDelay(estimator, sll);
            if (Float.isNaN(d)) {
                continue;
            }
            count++;
            min = Math.min(min, d);
        }
        return new SlrModel(numSlrs, count == 0 ? 0f : Math.max(0f, min), count);
    }

    /**
     * The SLL nodes that join two SLRs (see {@link #joinsSlrs(Node)}) of each device, by device name, as the unique
     * address of the tile and the index of their base wire: found once per device (a scan of all its tiles) and
     * shared by the models of all estimators and routers; no reference to the device is held.
     */
    private static final Map<String, int[][]> JOINING_SLLS = new ConcurrentHashMap<>();

    private static int[][] getJoiningSlls(Device device) {
        return JOINING_SLLS.computeIfAbsent(device.getName(), k -> findJoiningSlls(device));
    }

    private static int[][] findJoiningSlls(Device device) {
        List<int[]> slls = new ArrayList<>();
        boolean[] typeChecked = new boolean[TileTypeEnum.values().length];
        boolean[] typeHasSll = new boolean[typeChecked.length];
        for (Tile tile : device.getAllTiles()) {
            int type = tile.getTileTypeEnum().ordinal();
            if (typeChecked[type] && !typeHasSll[type]) {
                continue;
            }
            boolean has = false;
            for (int w = 0; w < tile.getWireCount(); w++) {
                IntentCode ic = tile.getWireIntentCode(w);
                if (ic != IntentCode.NODE_LAGUNA_DATA && ic != IntentCode.NODE_SLL_DATA) {
                    continue;
                }
                has = true;
                Node sll = Node.getNode(tile, w);
                // Each node once: at its base wire
                if (sll == null || !sll.getTile().equals(tile) || sll.getWireIndex() != w || !joinsSlrs(sll)) {
                    continue;
                }
                slls.add(new int[]{tile.getUniqueAddress(), w});
            }
            typeChecked[type] = true;
            typeHasSll[type] |= has;
        }
        return slls.toArray(new int[0][]);
    }

    /**
     * Gets the delay of a crossing over an SLL node: its own delay with the least transition from a
     * driver, plus the least delay of a node it drives, with its transition.
     * @return The delay in ps, or NaN for an SLL that nothing drives or that drives nothing.
     */
    static float getCrossingDelay(DelayEstimator estimator, Node sll) {
        List<Node> uphill = sll.getAllUphillNodes();
        List<Node> downhill = sll.getAllDownhillNodes();
        if (uphill.isEmpty() || downhill.isEmpty()) {
            return Float.NaN;
        }
        float in = Float.MAX_VALUE;
        for (Node u : uphill) {
            in = Math.min(in, estimator.getExtraDelay(u, sll));
        }
        float out = Float.MAX_VALUE;
        for (Node v : downhill) {
            float dv = estimator.isDelayCharged(v) ? estimator.getDelayOf(v) : 0f;
            out = Math.min(out, dv + estimator.getExtraDelay(sll, v));
        }
        float self = estimator.isDelayCharged(sll) ? estimator.getDelayOf(sll) : 0f;
        return in + self + out;
    }

    /** @return The number of SLRs of the device. */
    int getNumSlrs() {
        return numSlrs;
    }

    /**
     * @return The delay of the cheapest SLR crossing over an SLL (with the hops onto and off it), in
     * ps; 0 for a device with one SLR.
     */
    public float getSllDelay() {
        return sllDelay;
    }

    /** @return The number of SLL nodes (joining two SLRs) the SLL delay is the least of. */
    int getNumSllNodes() {
        return numSllNodes;
    }

    @Override
    public String toString() {
        return "SlrModel{SLRs " + numSlrs + ", SLL delay " + sllDelay + " ps over " + numSllNodes + " SLLs}";
    }
}

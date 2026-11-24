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

package com.xilinx.rapidwright.timing.ultrascaleplus;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import com.xilinx.rapidwright.device.Device;
import com.xilinx.rapidwright.device.IntentCode;
import com.xilinx.rapidwright.device.Node;
import com.xilinx.rapidwright.device.Tile;
import com.xilinx.rapidwright.device.TileTypeEnum;
import com.xilinx.rapidwright.device.Wire;
import com.xilinx.rapidwright.timing.family.ClassBasedDelayEstimator;
import com.xilinx.rapidwright.timing.family.Corner;
import com.xilinx.rapidwright.timing.family.InterconnectParams;

/**
 * Delay estimator of the fitted UltraScale+ interconnect model: a {@link ClassBasedDelayEstimator}
 * whose node classes are the {@code UltraScalePlusNodeClass}es, with the parameters of the UltraScale+
 * timing data ({@link UltraScalePlusTimingFamily#getInterconnectParams(Corner)}) at a corner, scaled to the
 * device and the part's speed grade at that corner. It charges every node of a route and has pin deltas (see {@link #getPinDelay}).
 * <p>
 * {@link UltraScalePlusTimingFamily} creates it.
 */
public class UltraScalePlusDelayEstimator extends ClassBasedDelayEstimator {

    /** Delay of a masked U-turn node, which RWRoute excludes */
    public static final short UTURN_MASK_DELAY = Short.MAX_VALUE / 2;

    /** Whether U-turn nodes get {@link #UTURN_MASK_DELAY} */
    private final boolean masksUTurnNodes;
    /**
     * Per wire index of the INT tile type, the signed nominal length of a general wire along its axis, in INT
     * tiles (see {@link #setNominalStep}): positive for east and north, negative for west
     * and south; 0 for other wires. Null when U-turn nodes are allowed.
     */
    private final byte[] nominalStep;
    /** Per wire index of the INT tile type, whether the general wire runs vertically (see {@link #nominalStep}) */
    private final boolean[] vertical;
    /** The largest X and Y coordinates of the INT tiles of the device */
    private final int maxIntX, maxIntY;

    /**
     * Creates the delay estimator of an UltraScale+ device at the slow corner, maximum delay.
     * @param device The UltraScale+ device.
     * @param speedGrade The speed grade of the part, e.g. -2, or null for the grade the data is for.
     */
    UltraScalePlusDelayEstimator(Device device, String speedGrade) {
        this(device, UltraScalePlusTimingFamily.getInterconnectParams(Corner.SLOW_MAX), speedGrade);
    }

    /**
     * Creates the delay estimator of an UltraScale+ device from given interconnect parameters.
     * @param device The UltraScale+ device.
     * @param params The parameters, whose node classes must be the {@link UltraScalePlusNodeClass}es.
     * @param speedGrade The speed grade of the part, e.g. -2, or null for the grade the parameters are for.
     */
    UltraScalePlusDelayEstimator(Device device, InterconnectParams params, String speedGrade) {
        this(device, params, speedGrade, true);
    }

    /**
     * Creates the delay estimator of an UltraScale+ device from given interconnect parameters.
     * @param device The UltraScale+ device.
     * @param params The parameters, whose node classes must be the {@link UltraScalePlusNodeClass}es.
     * @param speedGrade The speed grade of the part, e.g. -2, or null for the grade the parameters are for.
     * @param useUTurnNodes Whether the nodes that turn back towards their driver at the device's edges
     *                      are allowed; when not, they get the delay {@link #UTURN_MASK_DELAY}, which
     *                      RWRoute excludes, see {@link #isUTurnNode(Node)}.
     */
    UltraScalePlusDelayEstimator(Device device, InterconnectParams params, String speedGrade,
                                        boolean useUTurnNodes) {
        super(device, checkNodeClasses(params, UltraScalePlusNodeClass.values()),
                UltraScalePlusNodeClass.getWireClasses(device), getScale(device, params, speedGrade,
                        "UltraScale+ delay data"), getFanoutScale(device, params, speedGrade, "UltraScale+ delay data"));
        // The lookahead and pre-route delays of the data are those of SLOW_MAX
        setCornerReference(UltraScalePlusTimingFamily.getInterconnectParams(Corner.SLOW_MAX));
        masksUTurnNodes = !useUTurnNodes;
        IntGeometry g = IntGeometry.of(device);
        maxIntX = g.maxIntX;
        maxIntY = g.maxIntY;
        nominalStep = masksUTurnNodes ? g.nominalStep : null;
        vertical = masksUTurnNodes ? g.vertical : null;
    }

    /**
     * The INT tiles of a device that U-turn detection needs: their largest X and Y coordinates and the nominal
     * steps of the wires of the INT tile type. Found once per device (a scan of all its tiles) and shared by the
     * estimators of all corners and routers; holds no reference to the device.
     */
    private static final class IntGeometry {
        private static final Map<String, IntGeometry> CACHE = new ConcurrentHashMap<>();

        final int maxIntX;
        final int maxIntY;
        /** See {@link UltraScalePlusDelayEstimator#nominalStep}; null if the device has no INT tile */
        final byte[] nominalStep;
        /** See {@link UltraScalePlusDelayEstimator#vertical}; null if the device has no INT tile */
        final boolean[] vertical;

        private IntGeometry(Device device) {
            int mx = 0;
            int my = 0;
            Tile intTile = null;
            for (Tile t : device.getAllTiles()) {
                if (t.getTileTypeEnum() == TileTypeEnum.INT) {
                    intTile = t;
                    mx = Math.max(mx, t.getTileXCoordinate());
                    my = Math.max(my, t.getTileYCoordinate());
                }
            }
            maxIntX = mx;
            maxIntY = my;
            if (intTile != null) {
                nominalStep = new byte[intTile.getWireCount()];
                vertical = new boolean[nominalStep.length];
                for (int w = 0; w < nominalStep.length; w++) {
                    setNominalStep(nominalStep, vertical, w, intTile.getWireName(w), intTile.getWireIntentCode(w));
                }
            } else {
                nominalStep = null;
                vertical = null;
            }
        }

        static IntGeometry of(Device device) {
            return CACHE.computeIfAbsent(device.getName(), k -> new IntGeometry(device));
        }
    }

    /**
     * Sets the nominal step of a general wire of the INT tile type: its direction from the first letter of its name
     * (EE, WW, NN, SS), its length the nominal length of its intent code, in INT tiles (singles 1;
     * doubles 1 horizontally, 2 vertically; quads 2 and 4; longs 6 and 12). The single WW1_E_7_FT0, despite its
     * name, runs north, like NN1_E. INT_INT_SDQ_* singles stay in their tile.
     */
    private static void setNominalStep(byte[] nominalStep, boolean[] vertical, int w, String name, IntentCode ic) {
        if (name.contains("WW1_E")) {
            name = "NN1_E";
        }
        if ((name.startsWith("INT") && ic == IntentCode.NODE_SINGLE) || name.indexOf('_') < 0) {
            return;
        }
        char side = name.charAt(0);
        if (side != 'E' && side != 'W' && side != 'N' && side != 'S') {
            return;
        }
        boolean vert = side == 'N' || side == 'S';
        int length;
        switch (ic) {
            case NODE_SINGLE:
                length = 1;
                break;
            case NODE_DOUBLE:
                length = vert ? 2 : 1;
                break;
            case NODE_HQUAD:
                vert = false;
                length = 2;
                break;
            case NODE_VQUAD:
                vert = true;
                length = 4;
                break;
            case NODE_HLONG:
                vert = false;
                length = 6;
                break;
            case NODE_VLONG:
                vert = true;
                length = 12;
                break;
            default:
                return;
        }
        nominalStep[w] = (byte) (side == 'E' || side == 'N' ? length : -length);
        vertical[w] = vert;
    }

    @Override
    public boolean masksUTurnNodes() {
        return masksUTurnNodes;
    }

    /**
     * Checks if a node is a U-turn node: a general wire of an INT tile whose nominal end (see
     * {@link #setNominalStep}) lies outside the device's INT tiles, so that it turns back at the device's edge.
     * @param node The node, whose base wire is in an INT tile.
     * @return True for a U-turn node; false for any node when U-turn nodes are allowed.
     */
    boolean isUTurnNode(Node node) {
        if (nominalStep == null) {
            return false;
        }
        int w = node.getWireIndex();
        int step = w < nominalStep.length ? nominalStep[w] : 0;
        if (step == 0) {
            return false;
        }
        Tile tile = node.getTile();
        int end = (vertical[w] ? tile.getTileYCoordinate() : tile.getTileXCoordinate()) + step;
        return end < 0 || end > (vertical[w] ? maxIntY : maxIntX);
    }

    @Override
    public short getUnmaskedDelayOf(Node node) {
        return super.getDelayOf(node, null);
    }

    @Override
    public short getDelayOf(Node node, Wire[] wires) {
        if (masksUTurnNodes && node.getTile().getTileTypeEnum() == TileTypeEnum.INT && isUTurnNode(node)) {
            return UTURN_MASK_DELAY;
        }
        return super.getDelayOf(node, wires);
    }
}

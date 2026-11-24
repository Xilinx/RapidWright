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

import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.Map;
import java.util.Queue;
import java.util.Set;

import com.xilinx.rapidwright.design.SitePinInst;
import com.xilinx.rapidwright.device.Device;
import com.xilinx.rapidwright.device.Node;
import com.xilinx.rapidwright.device.Site;
import com.xilinx.rapidwright.device.SitePin;
import com.xilinx.rapidwright.device.Tile;
import com.xilinx.rapidwright.device.TileTypeEnum;
import com.xilinx.rapidwright.device.Wire;
import com.xilinx.rapidwright.timing.delayestimator.DelayEstimator;

/**
 * Delay estimator of a class-based interconnect model. Following the model of "An Open-source Lightweight
 * Timing Model for RapidWright" (FPT 2019), a node of class c has the delay
 * <pre>
 *   delay = (base[c] + l[c] * length + sum over column and row classes j of w[c][j] * crossings[j]
 *            + trans[p][c]) * scale
 * </pre>
 * where crossings[j] counts the gaps of class j that the node spans (see {@link CrossingIndex}),
 * standing in for the paper's k2*d term, length is the number of INT columns and rows the node spans
 * (the paper's k1*L term), and trans[p][c] corrects for the class p of the node driving it. A general
 * wire and the local node it drives act as one stage; the parameters are fitted to the delays of whole
 * paths, so how a stage's delay is split between its nodes is arbitrary, and only sums over paths are
 * meaningful. The parameters are {@link InterconnectParams}; scale adjusts them to the device and the
 * part's speed grade. Nodes of class 0 carry no delay.
 * <p>
 * Where a net's routing branches, every connection routed through a node also incurs the node's fanout
 * delays: per extra branch, that of its class plus, per gap of a column or row class that the node spans,
 * that of the gap's class ({@link #getFanoutDelay(Node, int)}), and per doubling of the sink pins that
 * the routing reaches through the node, that of its class ({@link #getFanoutLoadDelay(Node, int)}). These fanout
 * delays have a scale of their own ({@link #getFanoutScale()}): from one speed grade to another they do not change
 * in proportion to the other delays.
 * <p>
 * A node that crosses a gap of the column class of the track deltas also adds the deltas of its wires in the
 * tiles of the gap ({@link InterconnectParams#getTrackDeltas()}): the track it takes through the gap.
 */
public class ClassBasedDelayEstimator implements DelayEstimator {

    private final InterconnectParams params;
    private final CrossingIndex crossings;
    /** Class indices of the wires of the device, indexed by tile type ordinal, then wire index */
    private final byte[][] wireClasses;
    /** Factor scaling all delays to the device and speed grade */
    private final float scale;
    /** Factor scaling the fanout delays to the device and speed grade (see {@link #getFanoutScale()}) */
    private final float fanoutScale;
    /**
     * The factor of the lookahead and pre-route delays, which the data only has at SLOW_MAX: at another
     * corner, the ratio of the sums of its class base delays and SLOW_MAX's; 1 at SLOW_MAX. See
     * {@link #setCornerReference(InterconnectParams)}.
     */
    private float cornerScale = 1f;
    /** Buffer for the crossing counts of a node, one per thread */
    private final ThreadLocal<int[]> crossingCounts;
    /** Extra delays of {@link #getExtraDelay(Node, Node)} by driver class, then node class */
    private final short[][] extraDelays;
    /** The least of {@link #extraDelays} by node class, then (last) over all classes */
    private final short[] leastExtraDelays;
    /** Track deltas by tile type ordinal, then wire index (null for tile types without), or null if none */
    private final float[][] trackDeltas;
    /** The crossing classes whose crossings add the track deltas; empty without track deltas */
    private final int[] trackClasses;

    /**
     * Creates a delay estimator.
     * @param device The device.
     * @param params The parameters of the model.
     * @param wireClasses Class indices, into the parameters, of the wires of the device: indexed by tile
     *                    type ordinal, then wire index, as nodes with those base wires would have.
     * @param scale The factor scaling all delays to the device and speed grade.
     */
    public ClassBasedDelayEstimator(Device device, InterconnectParams params, byte[][] wireClasses, float scale) {
        this(device, params, wireClasses, scale, scale);
    }

    /**
     * Creates a delay estimator whose fanout delays scale differently from its other delays.
     * @param device The device.
     * @param params The parameters of the model.
     * @param wireClasses Class indices, into the parameters, of the wires of the device: indexed by tile
     *                    type ordinal, then wire index, as nodes with those base wires would have.
     * @param scale The factor scaling all delays but the fanout delays to the device and speed grade.
     * @param fanoutScale The factor scaling the fanout delays to the device and speed grade.
     */
    public ClassBasedDelayEstimator(Device device, InterconnectParams params, byte[][] wireClasses, float scale,
                                    float fanoutScale) {
        this.params = params;
        this.scale = scale;
        this.fanoutScale = fanoutScale;
        this.crossings = CrossingIndex.of(device, params);
        this.wireClasses = wireClasses;
        int numCrossingClasses = params.getNumCrossingClasses();
        this.crossingCounts = ThreadLocal.withInitial(() -> new int[numCrossingClasses]);
        int numClasses = params.getNumNodeClasses();
        if (numClasses > Byte.MAX_VALUE + 1) {
            throw new IllegalArgumentException("Too many node classes: " + numClasses);
        }
        extraDelays = new short[numClasses][numClasses];
        leastExtraDelays = new short[numClasses + 1];
        Arrays.fill(leastExtraDelays, numClasses > 0 ? Short.MAX_VALUE : 0);
        for (int p = 0; p < numClasses; p++) {
            for (int c = 0; c < numClasses; c++) {
                extraDelays[p][c] = (short) Math.round(params.getTransitionDelay(p, c) * scale);
                leastExtraDelays[c] = (short) Math.min(leastExtraDelays[c], extraDelays[p][c]);
                leastExtraDelays[numClasses] = (short) Math.min(leastExtraDelays[numClasses], extraDelays[p][c]);
            }
        }
        trackDeltas = params.hasTrackDeltas() ? getTrackDeltaTable(device, params.getTrackDeltas()) : null;
        trackClasses = trackDeltas != null ? params.getTrackDeltaClasses() : new int[0];
    }

    /**
     * Indexes track deltas by tile type ordinal and wire index, for the tile types of a device.
     * @param device The device.
     * @param deltas The deltas, by tile type name, then wire name (see {@link InterconnectParams#getTrackDeltas()}).
     * @return The deltas by tile type ordinal, then wire index; null where a tile type has none, or null if the
     * device has none of the tile types.
     */
    private static float[][] getTrackDeltaTable(Device device, Map<String, Map<String, Float>> deltas) {
        float[][] table = new float[TileTypeEnum.values().length][];
        Set<TileTypeEnum> missing = EnumSet.noneOf(TileTypeEnum.class);
        for (String name : deltas.keySet()) {
            try {
                missing.add(TileTypeEnum.valueOf(name));
            } catch (IllegalArgumentException e) {
                // A tile type of no device of this RapidWright version
            }
        }
        boolean any = false;
        for (Tile tile : device.getAllTiles()) {
            if (missing.isEmpty()) {
                break;
            }
            TileTypeEnum type = tile.getTileTypeEnum();
            if (!missing.remove(type)) {
                continue;
            }
            float[] t = new float[tile.getWireCount()];
            for (Map.Entry<String, Float> e : deltas.get(type.name()).entrySet()) {
                int w = tile.getWireIndex(e.getKey());
                if (w >= 0) {
                    t[w] = e.getValue();
                    any = true;
                }
            }
            table[type.ordinal()] = t;
        }
        return any ? table : null;
    }

    public InterconnectParams getParams() {
        return params;
    }

    /**
     * Scales the lookahead and pre-route delays, which are the same in the data of all corners (fitted at
     * SLOW_MAX), to the corner of the parameters: by the ratio of the sums of the class base delays at
     * that corner and at SLOW_MAX. Nothing changes at SLOW_MAX or with parameters of other classes. The
     * admissible lookahead is a lower bound of route delays at SLOW_MAX, the corner a router searches at;
     * scaled to another corner, it is an estimate only.
     * @param slowMax The parameters of the same model at SLOW_MAX.
     */
    protected void setCornerReference(InterconnectParams slowMax) {
        if (slowMax == null || params.getCorner() == Corner.SLOW_MAX
                || !slowMax.getNodeClassNames().equals(params.getNodeClassNames())) {
            return;
        }
        double sum = 0;
        double sumRef = 0;
        for (int i = 0; i < params.getNumNodeClasses(); i++) {
            float b = params.getBaseDelay(i);
            float r = slowMax.getBaseDelay(i);
            if (b > 0 && r > 0) {
                sum += b;
                sumRef += r;
            }
        }
        if (sumRef > 0) {
            cornerScale = (float) (sum / sumRef);
        }
    }

    /**
     * Checks that the node classes of interconnect parameters are those of a node class enum, in order.
     * @param params The parameters.
     * @param classes The constants of the enum, e.g. {@code VersalNodeClass.values()}.
     * @return The parameters.
     * @throws IllegalArgumentException If the classes differ.
     */
    protected static InterconnectParams checkNodeClasses(InterconnectParams params, Enum<?>[] classes) {
        boolean match = params.getNumNodeClasses() == classes.length;
        for (int i = 0; match && i < classes.length; i++) {
            match = classes[i].name().equals(params.getNodeClassNames().get(i));
        }
        if (!match) {
            throw new IllegalArgumentException("The node classes of " + params.getDataVersion() + " differ from "
                    + classes.getClass().getComponentType().getSimpleName());
        }
        return params;
    }

    /**
     * Gets the factor scaling the delays of interconnect parameters to a device and speed grade.
     * @param device The device.
     * @param params The parameters.
     * @param speedGrade The speed grade, e.g. -2MP, or null for the grade of the parameters. An unknown grade
     *                   takes the factor of the nearest listed one (see {@link SpeedGrade#nearest}).
     * @param table The name of the data in the warning about an unknown grade.
     * @return The factor.
     */
    protected static float getScale(Device device, InterconnectParams params, String speedGrade, String table) {
        float speedScale = speedGrade == null ? 1.0f : params.getNearestSpeedScale(speedGrade, table);
        return speedScale * params.getDeviceScale(device.getName());
    }

    /**
     * Gets the factor scaling the fanout delays of interconnect parameters to a device and speed grade: that of
     * {@link #getScale}, with the grade's fanout factor ({@link InterconnectParams#getFanoutSpeedScale(String)})
     * in place of its factor.
     * @param device The device.
     * @param params The parameters.
     * @param speedGrade The speed grade, e.g. -2HP, or null for the grade of the parameters.
     * @param table The name of the data in the warning about an unknown grade.
     * @return The factor.
     */
    protected static float getFanoutScale(Device device, InterconnectParams params, String speedGrade, String table) {
        float speedScale = speedGrade == null ? 1.0f : params.getNearestFanoutSpeedScale(speedGrade, table);
        return speedScale * params.getDeviceScale(device.getName());
    }

    /**
     * @return The index of the gaps between INT columns and rows that the nodes' delays count (shared by the
     * estimators of a device).
     */
    public CrossingIndex getCrossings() {
        return crossings;
    }

    /**
     * @return The factor scaling all delays but the fanout delays to the device and speed grade.
     */
    public float getScale() {
        return scale;
    }

    /**
     * @return The factor scaling the fanout delays ({@link #getFanoutDelay(Node, int)},
     * {@link #getFanoutLoadDelay(Node, int)}) to the device and speed grade.
     */
    public float getFanoutScale() {
        return fanoutScale;
    }

    /**
     * Gets the class index of a node.
     * @param node The node.
     * @return The index of its class into the parameters.
     */
    public int getNodeClass(Node node) {
        return wireClasses[node.getTile().getTileTypeEnum().ordinal()][node.getWireIndex()];
    }

    @Override
    public short getDelayOf(Node node) {
        return getDelayOf(node, null);
    }

    /**
     * Gets the delay of a node, given its wires if the caller already has them.
     * @param node The node in question.
     * @param wires All the wires of the node, or null to get them only if needed.
     * @return The delay in ps.
     */
    @Override
    public short getDelayOf(Node node, Wire[] wires) {
        int cls = getNodeClass(node);
        if (cls == 0) {
            return 0;
        }
        float delay = params.getBaseDelay(cls);
        if (params.dependsOnSpan(cls)) {
            float[] weights = params.getCrossingWeights(cls);
            int[] counts = crossingCounts.get();
            Wire[] all = wires != null ? wires : node.getAllWiresInNode();
            int length = crossings.getCrossings(node, all, counts);
            delay += params.getLengthWeight(cls) * length;
            for (int j = 0; j < weights.length; j++) {
                delay += weights[j] * counts[j];
            }
            if (crossesTrackClass(counts)) {
                delay += getTrackDelta(all);
            }
        }
        // Negative length weights only correct wires cut short at device edges; never go below zero
        return (short) Math.max(0, Math.round(delay * scale));
    }

    /** Whether crossing counts include a gap of a class of the track deltas */
    private boolean crossesTrackClass(int[] counts) {
        for (int c : trackClasses) {
            if (counts[c] > 0) {
                return true;
            }
        }
        return false;
    }

    /** The sum of the track deltas of wires (see {@link InterconnectParams#getTrackDeltas()}) */
    private float getTrackDelta(Wire[] wires) {
        float delta = 0;
        for (Wire w : wires) {
            float[] t = trackDeltas[w.getTile().getTileTypeEnum().ordinal()];
            if (t != null) {
                delta += t[w.getWireIndex()];
            }
        }
        return delta;
    }

    /**
     * Gets the delay that the branches of a net's routing add to the node they branch from, which
     * all connections routed through that node incur.
     * @param node The node in question.
     * @param numBranches The number of nodes it drives.
     * @return The delay in ps, 0 for a node without extra branches.
     */
    @Override
    public float getFanoutDelay(Node node, int numBranches) {
        if (numBranches < 2) {
            return 0;
        }
        int cls = getNodeClass(node);
        if (cls == 0) {
            return 0;
        }
        float perBranch = params.getFanoutDelay(cls);
        if (params.hasFanoutCrossingDelays()) {
            // A wire across a column (e.g. of I/O) slows down more with each extra branch than a short one
            int[] counts = crossingCounts.get();
            crossings.getCrossings(node, node.getAllWiresInNode(), counts);
            for (int j = 0; j < counts.length; j++) {
                perBranch += params.getFanoutCrossingDelay(j) * counts[j];
            }
        }
        return perBranch * (numBranches - 1) * fanoutScale;
    }

    /**
     * Gets the delay that the sink pins a net's routing reaches through a node add to that node:
     * {@link InterconnectParams#getFanoutLoadDelay(int)} per doubling of their number.
     * @param node The node in question.
     * @param numLoads The number of sink pins reached through it.
     * @return The delay in ps, 0 for a node that leads to a single sink pin.
     */
    @Override
    public float getFanoutLoadDelay(Node node, int numLoads) {
        if (numLoads < 2 || !params.hasFanoutLoadDelays()) {
            return 0;
        }
        return params.getFanoutLoadDelay(getNodeClass(node)) * (float) (Math.log(numLoads) / LN2) * fanoutScale;
    }

    @Override
    public boolean hasFanoutDelays() {
        return true;
    }

    @Override
    public boolean isDelayCharged(Node node) {
        return getNodeClass(node) != 0;
    }

    /**
     * All nodes of a route, including those of site-adjacent and interface tiles, are part of the
     * route delay.
     */
    @Override
    public boolean isInterconnectNode(Node node) {
        return true;
    }

    @Override
    public short getExtraDelay(Node parent, Node child) {
        return extraDelays[getNodeClass(parent)][getNodeClass(child)];
    }

    /**
     * The extra delay class of a node is its node class (see {@link #getNodeClass(Node)}).
     */
    @Override
    public int getExtraDelayClass(Node node) {
        return getNodeClass(node);
    }

    @Override
    public short getExtraDelay(int parentClass, int childClass) {
        return extraDelays[parentClass][childClass];
    }

    /**
     * The least transition delay into the class (see {@link InterconnectParams#getTransitionDelay(int, int)}),
     * scaled; or the least of all for -1.
     */
    @Override
    public short getLeastExtraDelay(int nodeClass) {
        return leastExtraDelays[nodeClass < 0 ? leastExtraDelays.length - 1 : nodeClass];
    }

    /**
     * Gets the pin deltas of the parameters (see {@link InterconnectParams#getSinkPinDelta}) of a
     * connection's source and sink site pins, scaled like the node delays.
     */
    @Override
    public short getPinDelay(SitePinInst source, SitePinInst sink) {
        if (!params.hasPinDeltas()) {
            return 0;
        }
        float delta = 0;
        if (source != null) {
            Site site = source.getSite();
            delta += params.getSourcePinDelta(site.getSiteTypeEnum(), getPrimaryPinName(source));
        }
        if (sink != null) {
            Site site = sink.getSite();
            delta += params.getSinkPinDelta(site.getSiteTypeEnum(), getPrimaryPinName(sink));
        }
        return (short) Math.round(delta * scale);
    }

    @Override
    public short getPinDelay(SitePinInst source, Site sinkSite, String sinkPinName) {
        if (!params.hasPinDeltas()) {
            return 0;
        }
        float delta = 0;
        if (source != null) {
            delta += params.getSourcePinDelta(source.getSite().getSiteTypeEnum(), getPrimaryPinName(source));
        }
        delta += params.getSinkPinDelta(sinkSite.getSiteTypeEnum(), sinkPinName);
        return (short) Math.round(delta * scale);
    }

    /**
     * Gets the name of a site pin in the primary type of its site, which the pin deltas are keyed by.
     * @param spi The site pin.
     * @return The pin name.
     */
    private static String getPrimaryPinName(SitePinInst spi) {
        if (spi.getSiteInst().getSiteTypeEnum() == spi.getSite().getSiteTypeEnum()) {
            return spi.getName();
        }
        Node node = spi.getConnectedNode();
        SitePin sitePin = node == null ? null : node.getSitePin();
        return sitePin == null ? spi.getName() : sitePin.getPinName();
    }

    /** Number of nodes from a site pin searched for the interconnect tile serving it */
    private static final int MAX_INT_TILE_SEARCH_DEPTH = 4;

    /** The natural logarithm of 2, for the base-2 logarithm of the fanout load delays */
    private static final double LN2 = Math.log(2);

    /**
     * Gets the interconnect (INT) tile that serves a site pin, between whose tiles the pre-route estimate of a
     * connection charges the lookahead delays: the tile of the first INT node found downhill of an output pin or
     * uphill of an input pin.
     * @param spi The site pin in question.
     * @return The INT tile, or the tile of the site pin's site if none was found nearby.
     */
    public static Tile getInterconnectTile(SitePinInst spi) {
        Node start = spi.getConnectedNode();
        if (start != null) {
            boolean downhill = spi.isOutPin();
            Queue<Node> queue = new ArrayDeque<>();
            Set<Node> visited = new HashSet<>();
            queue.add(start);
            visited.add(start);
            for (int depth = 0; depth <= MAX_INT_TILE_SEARCH_DEPTH && !queue.isEmpty(); depth++) {
                for (int i = queue.size(); i > 0; i--) {
                    Node node = queue.poll();
                    if (node.getTile().getTileTypeEnum() == TileTypeEnum.INT) {
                        return node.getTile();
                    }
                    for (Node next : downhill ? node.getAllDownhillNodes() : node.getAllUphillNodes()) {
                        if (visited.add(next)) {
                            queue.add(next);
                        }
                    }
                }
            }
        }
        return spi.getTile();
    }

    @Override
    public float getLookaheadDelayPerTileX() {
        return params.getLookaheadPerTileX() * cornerScale * scale / 100f;
    }

    @Override
    public float getLookaheadDelayPerTileY() {
        return params.getLookaheadPerTileY() * cornerScale * scale / 100f;
    }

    @Override
    public float getAdmissibleLookaheadDelayPerTileX() {
        return params.getAdmissibleLookaheadPerTileX() * cornerScale * scale / 100f;
    }

    @Override
    public float getAdmissibleLookaheadDelayPerTileY() {
        return params.getAdmissibleLookaheadPerTileY() * cornerScale * scale / 100f;
    }

    @Override
    public short getPreRouteBaseDelay() {
        return (short) Math.round(params.getPreRouteDelay() * cornerScale * scale);
    }
}

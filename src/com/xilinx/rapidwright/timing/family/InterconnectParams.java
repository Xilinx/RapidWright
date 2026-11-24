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
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import com.xilinx.rapidwright.device.SiteTypeEnum;

/**
 * The parameters of a class-based interconnect delay model at one {@link Corner}, see
 * {@link ClassBasedDelayEstimator}. Every routing node belongs to a node class; nodes of a class
 * share these parameters, indexed by the class's position in {@link #getNodeClassNames()}:
 * <ul>
 * <li>base delay: the delay of a node of the class, including its driving PIP;</li>
 * <li>length weight: the delay a node adds per INT column or row it spans;</li>
 * <li>crossing weights: the delay a node adds per crossing of a gap between INT columns or rows
 * whose tiles belong to a column or row class (DSP, BRAM, RCLK, ...), see {@link CrossingIndex};
 * column and row classes are defined by tile type patterns, the first match winning;</li>
 * <li>transition delays: the delay a node adds when driven by a node of another class;</li>
 * <li>fanout delays: the delay per extra branch of a net's routing out of a node;</li>
 * <li>fanout load delays (optional): the delay per doubling of the number of sink pins that a net's
 * routing reaches through a node;</li>
 * <li>fanout crossing delays (optional): the delay per extra branch out of a node per crossing of a gap of
 * each column or row class that the node spans, e.g. a wire across an I/O column that branches.</li>
 * <li>pin deltas (optional): the delay a connection adds for its source and sink site pins, see
 * {@link #getSinkPinDelta(SiteTypeEnum, String)};</li>
 * <li>track deltas (optional): the delay a node that crosses a gap of some column or row classes adds for the
 * track it takes through the gap, keyed by the wires it has in the tiles there, see {@link #getTrackDeltas()}.</li>
 * </ul>
 * Delays are in ps at the speed grade {@link #getSpeedRef()}; speed and device scales, which are
 * those of the corner, adjust them to other speed grades and devices. Instances come from the timing data
 * files (see {@link TimingData#getInterconnectParams(Corner)}), through {@link Builder}.
 */
public final class InterconnectParams {

    private final String dataVersion;
    private final Corner corner;
    private final String speedRef;
    private final List<String> nodeClassNames;
    private final List<String> colClassNames;
    private final List<Pattern> colClassPatterns;
    private final List<String> rowClassNames;
    private final List<Pattern> rowClassPatterns;
    private final float[] base;
    private final float[] lengthWeights;
    private final float[] fanout;
    private final float[] fanoutLoad;
    private final boolean hasFanoutLoad;
    /** Fanout crossing delays, indexed by column classes followed by row classes */
    private final float[] fanoutCross;
    private final boolean hasFanoutCross;
    private final float[][] trans;
    /** Weights indexed by node class, then by column classes followed by row classes */
    private final float[][] dWeights;
    private final boolean[] hasCrossingWeights;
    private final float lookaheadX;
    private final float lookaheadY;
    private final float admissibleLookaheadX;
    private final float admissibleLookaheadY;
    private final float preRoute;
    private final Map<String, Float> speedScales;
    /** Factors of the fanout delays of the speed grades whose fanout delays scale differently; empty if none */
    private final Map<String, Float> fanoutSpeedScales;
    private final Map<String, Float> deviceScales;
    /** Pin deltas in ps, by site type, then pin or bus name; empty if the data has none */
    private final Map<SiteTypeEnum, Map<String, Float>> sinkPinDeltas;
    private final Map<SiteTypeEnum, Map<String, Float>> sourcePinDeltas;
    /**
     * The crossing classes whose crossings the track deltas apply to (indexes of the column classes, then of the
     * row classes after them); empty if the data has none
     */
    private final int[] trackClasses;
    /** Track deltas in ps, by tile type name, then wire name; empty if the data has none */
    private final Map<String, Map<String, Float>> trackDeltas;

    private InterconnectParams(Builder b) {
        int n = b.nodeClassNames.length;
        dataVersion = b.dataVersion;
        corner = b.corner;
        speedRef = b.speedRef;
        nodeClassNames = Collections.unmodifiableList(Arrays.asList(b.nodeClassNames.clone()));
        colClassNames = Collections.unmodifiableList(Arrays.asList(b.colClassNames.clone()));
        rowClassNames = Collections.unmodifiableList(Arrays.asList(b.rowClassNames.clone()));
        colClassPatterns = compile(b.colClassRegexes);
        rowClassPatterns = compile(b.rowClassRegexes);
        base = checkLength(b.base, n, "base").clone();
        lengthWeights = checkLength(b.lengthWeights, n, "length").clone();
        fanout = checkLength(b.fanout, n, "fanout").clone();
        fanoutLoad = checkLength(b.fanoutLoad, n, "fanout load").clone();
        boolean anyLoad = false;
        for (float f : fanoutLoad) {
            anyLoad |= f != 0;
        }
        hasFanoutLoad = anyLoad;
        int numCrossingClasses = colClassNames.size() + rowClassNames.size();
        fanoutCross = b.fanoutCross == null ? new float[numCrossingClasses]
                : checkLength(b.fanoutCross, numCrossingClasses, "fanout crossing").clone();
        boolean anyCross = false;
        for (float f : fanoutCross) {
            anyCross |= f != 0;
        }
        hasFanoutCross = anyCross;
        checkLength(b.dWeights, n, "crossing weights");
        dWeights = new float[n][];
        hasCrossingWeights = new boolean[n];
        for (int i = 0; i < n; i++) {
            dWeights[i] = checkLength(b.dWeights[i], numCrossingClasses, "crossing weights").clone();
            for (float w : dWeights[i]) {
                hasCrossingWeights[i] |= w != 0;
            }
        }
        trans = new float[n][n];
        for (int i = 0; i < b.transFrom.length; i++) {
            trans[b.transFrom[i]][b.transTo[i]] = b.transDelay[i];
        }
        lookaheadX = b.lookaheadX;
        lookaheadY = b.lookaheadY;
        admissibleLookaheadX = b.admissibleLookaheadX;
        admissibleLookaheadY = b.admissibleLookaheadY;
        preRoute = b.preRoute;
        speedScales = toMap(b.speedGrades, b.speedScales);
        fanoutSpeedScales = toMap(b.fanoutSpeedGrades, b.fanoutSpeedScales);
        deviceScales = toMap(b.devices, b.deviceScales);
        sinkPinDeltas = toPinMap(b.sinkPinKeys, b.sinkPinDeltas);
        sourcePinDeltas = toPinMap(b.sourcePinKeys, b.sourcePinDeltas);
        trackDeltas = toTrackMap(b.trackKeys, b.trackDeltas);
        trackClasses = new int[b.trackClasses.length];
        for (int i = 0; i < trackClasses.length; i++) {
            String name = b.trackClasses[i];
            int c = colClassNames.indexOf(name);
            if (c < 0 && rowClassNames.contains(name)) {
                c = colClassNames.size() + rowClassNames.indexOf(name);
            }
            if (c < 0) {
                throw new IllegalArgumentException("Unknown crossing class of the track deltas: " + name);
            }
            trackClasses[i] = c;
        }
    }

    private static Map<String, Map<String, Float>> toTrackMap(String[] keys, short[] deltasTenthsPs) {
        if (keys.length != deltasTenthsPs.length) {
            throw new IllegalArgumentException("Expected " + keys.length + " track deltas, got " + deltasTenthsPs.length);
        }
        Map<String, Map<String, Float>> map = new HashMap<>();
        for (int i = 0; i < keys.length; i++) {
            if (deltasTenthsPs[i] == 0) {
                continue;
            }
            int dot = keys[i].indexOf('.');
            if (dot < 0) {
                throw new IllegalArgumentException("Track delta key " + keys[i] + " is not <tile type>.<wire>");
            }
            map.computeIfAbsent(keys[i].substring(0, dot), k -> new HashMap<>()).put(keys[i].substring(dot + 1),
                    deltasTenthsPs[i] / 10f);
        }
        // Read-only from here on (see getTrackDeltas())
        for (Map.Entry<String, Map<String, Float>> e : map.entrySet()) {
            e.setValue(Collections.unmodifiableMap(e.getValue()));
        }
        return Collections.unmodifiableMap(map);
    }

    private static Map<SiteTypeEnum, Map<String, Float>> toPinMap(String[] keys, short[] deltasTenthsPs) {
        Map<SiteTypeEnum, Map<String, Float>> map = new EnumMap<>(SiteTypeEnum.class);
        for (int i = 0; i < keys.length; i++) {
            if (deltasTenthsPs[i] == 0) {
                continue;
            }
            int dot = keys[i].indexOf('.');
            if (dot < 0) {
                throw new IllegalArgumentException("Pin delta key " + keys[i] + " is not <site type>.<pin>");
            }
            SiteTypeEnum siteType = SiteTypeEnum.valueOf(keys[i].substring(0, dot));
            map.computeIfAbsent(siteType, k -> new HashMap<>()).put(keys[i].substring(dot + 1),
                    deltasTenthsPs[i] / 10f);
        }
        // Read-only from here on (see getSinkPinDeltas())
        for (Map.Entry<SiteTypeEnum, Map<String, Float>> e : map.entrySet()) {
            e.setValue(Collections.unmodifiableMap(e.getValue()));
        }
        return Collections.unmodifiableMap(map);
    }

    private static List<Pattern> compile(String[] regexes) {
        List<Pattern> patterns = new ArrayList<>(regexes.length);
        for (String regex : regexes) {
            patterns.add(Pattern.compile(regex));
        }
        return Collections.unmodifiableList(patterns);
    }

    private static float[] checkLength(float[] a, int n, String what) {
        if (a.length != n) {
            throw new IllegalArgumentException("Expected " + n + " " + what + ", got " + a.length);
        }
        return a;
    }

    private static float[][] checkLength(float[][] a, int n, String what) {
        if (a.length != n) {
            throw new IllegalArgumentException("Expected " + n + " rows of " + what + ", got " + a.length);
        }
        return a;
    }

    private static Map<String, Float> toMap(String[] keys, float[] values) {
        Map<String, Float> map = new HashMap<>();
        for (int i = 0; i < keys.length; i++) {
            map.put(keys[i], values[i]);
        }
        return Collections.unmodifiableMap(map);
    }

    /**
     * @return Family, kind and version of the data, e.g. "versal_gen1 interconnect v1".
     */
    public String getDataVersion() {
        return dataVersion;
    }

    public Corner getCorner() {
        return corner;
    }

    /**
     * @return The names of the node classes; a class's position is its index into the parameters.
     * The first class (index 0) holds the nodes without delay.
     */
    public List<String> getNodeClassNames() {
        return nodeClassNames;
    }

    /**
     * @return The number of node classes.
     */
    public int getNumNodeClasses() {
        return nodeClassNames.size();
    }

    public float getBaseDelay(int nodeClass) {
        return base[nodeClass];
    }

    public float getTransitionDelay(int driverClass, int nodeClass) {
        return trans[driverClass][nodeClass];
    }

    public float getFanoutDelay(int nodeClass) {
        return fanout[nodeClass];
    }

    /**
     * Gets the delay a node of a class adds per doubling of the number of sink pins that its net's
     * routing reaches through it: {@code getFanoutLoadDelay(c) * log2(loads)}.
     * @param nodeClass The index of the node class.
     * @return The delay in ps.
     */
    public float getFanoutLoadDelay(int nodeClass) {
        return fanoutLoad[nodeClass];
    }

    /**
     * Checks if any node class has a fanout load delay, see {@link #getFanoutLoadDelay(int)}.
     * @return True if so.
     */
    public boolean hasFanoutLoadDelays() {
        return hasFanoutLoad;
    }

    /**
     * Gets the delay a node adds per extra branch out of it per crossing of a gap of a column or row class
     * that it spans, see {@link CrossingIndex}.
     * @param crossingClass The index of the column class, or the number of column classes plus that of the
     *                      row class.
     * @return The delay in ps.
     */
    public float getFanoutCrossingDelay(int crossingClass) {
        return fanoutCross[crossingClass];
    }

    /**
     * Checks if any column or row class has a fanout crossing delay, see {@link #getFanoutCrossingDelay(int)}.
     * @return True if so.
     */
    public boolean hasFanoutCrossingDelays() {
        return hasFanoutCross;
    }

    /**
     * Gets the delay a node of a class adds per INT column or row it spans.
     * @param nodeClass The index of the node class.
     * @return The delay in ps.
     */
    public float getLengthWeight(int nodeClass) {
        return lengthWeights[nodeClass];
    }

    /**
     * Gets the delays a node of a class adds per crossing.
     * @param nodeClass The index of the node class.
     * @return Delays indexed like the counts of {@link CrossingIndex#getCrossings}; not to be modified.
     */
    public float[] getCrossingWeights(int nodeClass) {
        return dWeights[nodeClass];
    }

    /**
     * Checks if a node of a class adds delay for any crossing.
     * @param nodeClass The index of the node class.
     * @return True, if any of the class's crossing weights is non-zero.
     */
    public boolean hasCrossingWeights(int nodeClass) {
        return hasCrossingWeights[nodeClass];
    }

    /**
     * Checks if the delay of a node of a class depends on its span, through either its crossings or
     * its length.
     * @param nodeClass The index of the node class.
     * @return True, if computing the node's delay needs its span.
     */
    public boolean dependsOnSpan(int nodeClass) {
        return hasCrossingWeights[nodeClass] || lengthWeights[nodeClass] != 0;
    }

    public List<String> getColClassNames() {
        return colClassNames;
    }

    public List<Pattern> getColClassPatterns() {
        return colClassPatterns;
    }

    public List<String> getRowClassNames() {
        return rowClassNames;
    }

    public List<Pattern> getRowClassPatterns() {
        return rowClassPatterns;
    }

    /**
     * @return The number of column classes plus the number of row classes.
     */
    public int getNumCrossingClasses() {
        return colClassNames.size() + rowClassNames.size();
    }

    /**
     * @return The speed grade the delays are for, without a leading dash, e.g. 2MP.
     */
    public String getSpeedRef() {
        return speedRef;
    }

    /**
     * Gets the factor scaling the delays to a speed grade.
     * @param speedGrade The speed grade, with or without a leading '-', e.g. 2MHP.
     * @return The factor, or null if the speed grade has none.
     */
    public Float getSpeedScale(String speedGrade) {
        String grade = speedGrade.startsWith("-") ? speedGrade.substring(1) : speedGrade;
        if (grade.equals(speedRef)) {
            return 1.0f;
        }
        return speedScales.get(grade);
    }

    /**
     * @return The speed grades with a factor, including the reference grade, without leading dashes.
     */
    public Set<String> getSpeedGrades() {
        Set<String> grades = new LinkedHashSet<>(speedScales.keySet());
        grades.add(speedRef);
        return grades;
    }

    /**
     * Gets the factor of a speed grade, or of the nearest listed one if it has none (see
     * {@link SpeedGrade#resolve}), with one warning.
     * @param speedGrade The speed grade, with or without a leading '-'.
     * @param table What the data is, for the warning, e.g. "UltraScale+ delay data".
     * @return The factor.
     */
    public float getNearestSpeedScale(String speedGrade, String table) {
        return getSpeedScale(SpeedGrade.resolve(speedGrade, getSpeedGrades(), speedRef, table));
    }

    /**
     * Gets the factor scaling the fanout delays ({@link #getFanoutDelay(int)}, {@link #getFanoutLoadDelay(int)},
     * {@link #getFanoutCrossingDelay(int)}) to a speed grade. The delays that a node's extra branches add do not
     * change from grade to grade in proportion to the other delays; grades without a fanout factor of their own
     * scale them like the other delays ({@link #getSpeedScale(String)}).
     * @param speedGrade The speed grade, with or without a leading '-', e.g. 2HP.
     * @return The factor, or null if the speed grade has neither a fanout factor nor a factor.
     */
    public Float getFanoutSpeedScale(String speedGrade) {
        String grade = speedGrade.startsWith("-") ? speedGrade.substring(1) : speedGrade;
        Float f = fanoutSpeedScales.get(grade);
        return f != null ? f : getSpeedScale(grade);
    }

    /**
     * Gets the fanout factor ({@link #getFanoutSpeedScale(String)}) of a speed grade, or of the nearest listed one
     * if it has none (see {@link SpeedGrade#resolve}).
     * @param speedGrade The speed grade, with or without a leading '-'.
     * @param table What the data is, for the warning about an unknown grade.
     * @return The factor.
     */
    public float getNearestFanoutSpeedScale(String speedGrade, String table) {
        return getFanoutSpeedScale(SpeedGrade.resolve(speedGrade, getSpeedGrades(), speedRef, table));
    }

    /**
     * @return True if any speed grade has a fanout factor that differs from its factor.
     */
    public boolean hasFanoutSpeedScales() {
        return !fanoutSpeedScales.isEmpty();
    }

    /**
     * Gets the factor scaling the delays of a device.
     * @param deviceName The name of the device, e.g. xcvc1902.
     * @return The factor, 1 if the device has none.
     */
    public float getDeviceScale(String deviceName) {
        return deviceScales.getOrDefault(deviceName, 1.0f);
    }

    /**
     * @return The delay per INT column of the pre-route estimate of a connection's delay (an estimate of the
     * best route's, not a lower bound), in ps.
     */
    public float getLookaheadPerTileX() {
        return lookaheadX;
    }

    /**
     * @return The delay per INT row of the pre-route estimate of a connection's delay, in ps.
     */
    public float getLookaheadPerTileY() {
        return lookaheadY;
    }

    /**
     * @return A lower bound of the delay per INT column, in ps, or 0 if the data has none.
     */
    public float getAdmissibleLookaheadPerTileX() {
        return admissibleLookaheadX;
    }

    /**
     * @return A lower bound of the delay per INT row, in ps, or 0 if the data has none.
     */
    public float getAdmissibleLookaheadPerTileY() {
        return admissibleLookaheadY;
    }

    /**
     * @return The delay assumed from a source to its first routing node, in ps.
     */
    public float getPreRouteDelay() {
        return preRoute;
    }

    /**
     * @return True if the data has source or sink pin deltas.
     */
    public boolean hasPinDeltas() {
        return !sinkPinDeltas.isEmpty() || !sourcePinDeltas.isEmpty();
    }

    /**
     * Checks if the data has track deltas, see {@link #getTrackDeltas()}.
     * @return True if so.
     */
    public boolean hasTrackDeltas() {
        return trackClasses.length > 0 && !trackDeltas.isEmpty();
    }

    /**
     * Gets the first crossing class whose crossings the track deltas apply to, see {@link #getTrackDeltaClasses()}.
     * @return The index of the crossing class (a column class index, or the number of column classes plus a row
     * class index), -1 if the data has no track deltas.
     */
    public int getTrackDeltaClass() {
        return trackClasses.length > 0 ? trackClasses[0] : -1;
    }

    /**
     * Gets the crossing classes whose crossings the track deltas apply to: a node that crosses a gap of any of them
     * adds the deltas of its wires.
     * @return The indexes of the crossing classes: column class indexes, and the number of column classes plus the
     * index for row classes (the order of {@link CrossingIndex#getCrossings}'s counts); empty if the data has none.
     */
    public int[] getTrackDeltaClasses() {
        return trackClasses.clone();
    }

    /**
     * Gets the track deltas: a node that crosses a gap of a class of {@link #getTrackDeltaClasses()} adds,
     * for each of its wires in a tile of a listed type, the delta of that wire's name. The wires of such a node in
     * the interface tiles of the gap identify the track it takes through it, and tracks that the class's crossing
     * weights do not tell apart differ in delay (e.g. by some 190 ps across the I/O columns of UltraScale+; on Versal,
     * a wire across a hard block or memory column that also reaches into the column's interface tiles is slower than
     * one that only passes it). The deltas are deviations from the class delays, scaled like them (speed, device).
     * @return The deltas in ps, by tile type name, then wire name (unmodifiable).
     */
    public Map<String, Map<String, Float>> getTrackDeltas() {
        return trackDeltas;
    }

    /**
     * Gets the delay that a connection adds, on top of the delays of its nodes, for the site pin it
     * ends at. On UltraScale+ devices, slice pins share their node with the interconnect, and the last
     * hop into the pin differs per pin (e.g. LUT input 1 versus 6) in a way no node class can express.
     * The delta is that listed for the pin name plus, for pins of sites other than slices whose name
     * ends in digits, that listed for its bus name: the pin name without its trailing digits, e.g. A for
     * DSP48E2 pin A12. Pins without a delta add 0.
     * @param siteType The type of the site the pin belongs to, as {@link com.xilinx.rapidwright.device.Site#getSiteTypeEnum()}
     *                 gives it (the site's primary type).
     * @param pinName The name of the pin in that site type.
     * @return The delta in ps, before speed and device scaling.
     */
    public float getSinkPinDelta(SiteTypeEnum siteType, String pinName) {
        return getPinDelta(sinkPinDeltas, siteType, pinName);
    }

    /**
     * Gets the delay that a connection adds, on top of the delays of its nodes, for the site pin it
     * starts from, see {@link #getSinkPinDelta(SiteTypeEnum, String)}.
     * @param siteType The type of the site the pin belongs to (the site's primary type).
     * @param pinName The name of the pin in that site type.
     * @return The delta in ps, before speed and device scaling.
     */
    public float getSourcePinDelta(SiteTypeEnum siteType, String pinName) {
        return getPinDelta(sourcePinDeltas, siteType, pinName);
    }

    /**
     * Gets all sink pin deltas, as {@link #getSinkPinDelta(SiteTypeEnum, String)} combines them: by site type, then
     * by pin or bus name (see {@link #getPinDeltaBusName(SiteTypeEnum, String)}). Pins without a delta are absent.
     * @return The deltas in ps, before speed and device scaling; an unmodifiable map of unmodifiable maps, empty if
     * the data has none. Safe to read from several threads.
     */
    public Map<SiteTypeEnum, Map<String, Float>> getSinkPinDeltas() {
        return sinkPinDeltas;
    }

    /**
     * Gets all source pin deltas, see {@link #getSourcePinDelta(SiteTypeEnum, String)} and
     * {@link #getSinkPinDeltas()}.
     * @return The deltas in ps, before speed and device scaling; an unmodifiable map of unmodifiable maps, empty if
     * the data has none. Safe to read from several threads.
     */
    public Map<SiteTypeEnum, Map<String, Float>> getSourcePinDeltas() {
        return sourcePinDeltas;
    }

    /**
     * Creates a builder that holds every term of these parameters, e.g. to derive parameters that differ in a
     * few terms (a builder setter then replaces those) without changing these, which may be shared. Built
     * unchanged, it gives parameters that equal these in every getter, with the given data version.
     * @param dataVersion The data version of the parameters to build (see {@link #getDataVersion()}), e.g.
     *                    this version with a note on what the derived parameters change.
     * @return A new builder; it shares no mutable state with these parameters.
     */
    public Builder toBuilder(String dataVersion) {
        int n = nodeClassNames.size();
        Builder b = new Builder(dataVersion, corner, speedRef, nodeClassNames.toArray(new String[0]));
        b.setColClasses(colClassNames.toArray(new String[0]), toRegexes(colClassPatterns));
        b.setRowClasses(rowClassNames.toArray(new String[0]), toRegexes(rowClassPatterns));
        b.setClassDelays(base.clone(), lengthWeights.clone(), fanout.clone());
        b.setFanoutLoadDelays(fanoutLoad.clone());
        b.setFanoutCrossingDelays(fanoutCross.clone());
        float[][] weights = new float[n][];
        for (int i = 0; i < n; i++) {
            weights[i] = dWeights[i].clone();
        }
        b.setCrossingWeights(weights);
        // The transitions with a delay (the others are 0, as unset ones)
        int numTrans = 0;
        for (int i = 0; i < n; i++) {
            for (int j = 0; j < n; j++) {
                if (Float.floatToRawIntBits(trans[i][j]) != 0) {
                    numTrans++;
                }
            }
        }
        int[] from = new int[numTrans];
        int[] to = new int[numTrans];
        float[] delay = new float[numTrans];
        int k = 0;
        for (int i = 0; i < n; i++) {
            for (int j = 0; j < n; j++) {
                if (Float.floatToRawIntBits(trans[i][j]) != 0) {
                    from[k] = i;
                    to[k] = j;
                    delay[k++] = trans[i][j];
                }
            }
        }
        b.setTransitions(from, to, delay);
        b.setLookahead(lookaheadX, lookaheadY, admissibleLookaheadX, admissibleLookaheadY);
        b.setPreRouteDelay(preRoute);
        String[] grades = speedScales.keySet().toArray(new String[0]);
        b.setSpeedScales(grades, toValues(speedScales, grades));
        String[] fanoutGrades = fanoutSpeedScales.keySet().toArray(new String[0]);
        b.setFanoutSpeedScales(fanoutGrades, toValues(fanoutSpeedScales, fanoutGrades));
        String[] devices = deviceScales.keySet().toArray(new String[0]);
        b.setDeviceScales(devices, toValues(deviceScales, devices));
        String[] sinkKeys = toKeys(sinkPinDeltas);
        String[] sourceKeys = toKeys(sourcePinDeltas);
        b.setPinDeltas(sinkKeys, toTenths(sinkPinDeltas, sinkKeys), sourceKeys, toTenths(sourcePinDeltas, sourceKeys));
        String[] classes = new String[trackClasses.length];
        for (int i = 0; i < classes.length; i++) {
            int c = trackClasses[i];
            classes[i] = c < colClassNames.size() ? colClassNames.get(c) : rowClassNames.get(c - colClassNames.size());
        }
        String[] trackKeys = toKeys(trackDeltas);
        b.setTrackDeltas(classes, trackKeys, toTenths(trackDeltas, trackKeys));
        return b;
    }

    private static String[] toRegexes(List<Pattern> patterns) {
        String[] regexes = new String[patterns.size()];
        for (int i = 0; i < regexes.length; i++) {
            regexes[i] = patterns.get(i).pattern();
        }
        return regexes;
    }

    private static float[] toValues(Map<String, Float> map, String[] keys) {
        float[] values = new float[keys.length];
        for (int i = 0; i < keys.length; i++) {
            values[i] = map.get(keys[i]);
        }
        return values;
    }

    /** The keys {@code <outer key>.<inner key>} of a two-level map of deltas, sorted */
    private static String[] toKeys(Map<?, Map<String, Float>> deltas) {
        List<String> keys = new ArrayList<>();
        for (Map.Entry<?, Map<String, Float>> e : deltas.entrySet()) {
            for (String inner : e.getValue().keySet()) {
                keys.add(e.getKey() + "." + inner);
            }
        }
        Collections.sort(keys);
        return keys.toArray(new String[0]);
    }

    /** The deltas of keys of {@link #toKeys}, in tenths of a ps as the builder takes them */
    private static short[] toTenths(Map<?, Map<String, Float>> deltas, String[] keys) {
        Map<String, Map<String, Float>> byName = new HashMap<>();
        for (Map.Entry<?, Map<String, Float>> e : deltas.entrySet()) {
            byName.put(e.getKey().toString(), e.getValue());
        }
        short[] tenths = new short[keys.length];
        for (int i = 0; i < keys.length; i++) {
            int dot = keys[i].indexOf('.');
            float ps = byName.get(keys[i].substring(0, dot)).get(keys[i].substring(dot + 1));
            // The builder's tenths of a ps, which the deltas hold divided by 10
            tenths[i] = (short) Math.round(ps * 10f);
        }
        return tenths;
    }

    private static float getPinDelta(Map<SiteTypeEnum, Map<String, Float>> deltas, SiteTypeEnum siteType,
                                     String pinName) {
        Map<String, Float> pins = deltas.get(siteType);
        if (pins == null) {
            return 0f;
        }
        float delta = pins.getOrDefault(pinName, 0f);
        String busName = getPinDeltaBusName(siteType, pinName);
        if (busName.length() != pinName.length()) {
            delta += pins.getOrDefault(busName, 0f);
        }
        return delta;
    }

    /**
     * Gets the bus name of a pin for the pin deltas: the pin name without its trailing digits, or the
     * pin name itself for slice pins.
     * @param siteType The site type of the pin.
     * @param pinName The name of the pin.
     * @return The bus name.
     */
    public static String getPinDeltaBusName(SiteTypeEnum siteType, String pinName) {
        if (siteType == SiteTypeEnum.SLICEL || siteType == SiteTypeEnum.SLICEM) {
            return pinName;
        }
        int end = pinName.length();
        while (end > 0 && Character.isDigit(pinName.charAt(end - 1))) {
            end--;
        }
        return pinName.substring(0, end);
    }

    /**
     * Builds {@link InterconnectParams} from the arrays of the timing data.
     */
    public static final class Builder {
        private final String dataVersion;
        private final Corner corner;
        private final String speedRef;
        private final String[] nodeClassNames;
        private String[] colClassNames = new String[0];
        private String[] colClassRegexes = new String[0];
        private String[] rowClassNames = new String[0];
        private String[] rowClassRegexes = new String[0];
        private float[] base;
        private float[] lengthWeights;
        private float[] fanout;
        private float[] fanoutLoad;
        private float[] fanoutCross;
        private float[][] dWeights;
        private int[] transFrom = new int[0];
        private int[] transTo = new int[0];
        private float[] transDelay = new float[0];
        private float lookaheadX;
        private float lookaheadY;
        private float admissibleLookaheadX;
        private float admissibleLookaheadY;
        private float preRoute;
        private String[] speedGrades = new String[0];
        private float[] speedScales = new float[0];
        private String[] fanoutSpeedGrades = new String[0];
        private float[] fanoutSpeedScales = new float[0];
        private String[] devices = new String[0];
        private float[] deviceScales = new float[0];
        private String[] sinkPinKeys = new String[0];
        private short[] sinkPinDeltas = new short[0];
        private String[] sourcePinKeys = new String[0];
        private short[] sourcePinDeltas = new short[0];
        private String[] trackClasses = new String[0];
        private String[] trackKeys = new String[0];
        private short[] trackDeltas = new short[0];

        /**
         * @param dataVersion Family, kind and version of the data.
         * @param corner The corner of the delays.
         * @param speedRef The speed grade of the delays, without a leading dash.
         * @param nodeClassNames The names of the node classes, the first one being that of nodes without delay.
         */
        public Builder(String dataVersion, Corner corner, String speedRef, String[] nodeClassNames) {
            this.dataVersion = dataVersion;
            this.corner = corner;
            this.speedRef = speedRef;
            this.nodeClassNames = nodeClassNames;
            int n = nodeClassNames.length;
            base = new float[n];
            lengthWeights = new float[n];
            fanout = new float[n];
            fanoutLoad = new float[n];
            dWeights = new float[n][0];
        }

        public Builder setColClasses(String[] names, String[] regexes) {
            colClassNames = names;
            colClassRegexes = regexes;
            return this;
        }

        public Builder setRowClasses(String[] names, String[] regexes) {
            rowClassNames = names;
            rowClassRegexes = regexes;
            return this;
        }

        /**
         * @param base Base delay per node class.
         * @param lengthWeights Delay per INT column or row spanned, per node class.
         * @param fanout Delay per extra branch, per node class.
         */
        public Builder setClassDelays(float[] base, float[] lengthWeights, float[] fanout) {
            this.base = base;
            this.lengthWeights = lengthWeights;
            this.fanout = fanout;
            return this;
        }

        /**
         * @param fanoutLoad Delay per doubling of the sink pins reached through a node, per node class.
         */
        public Builder setFanoutLoadDelays(float[] fanoutLoad) {
            this.fanoutLoad = fanoutLoad;
            return this;
        }

        /**
         * @param fanoutCross Delay per extra branch per crossing, indexed by column classes followed by row
         *                    classes.
         */
        public Builder setFanoutCrossingDelays(float[] fanoutCross) {
            this.fanoutCross = fanoutCross;
            return this;
        }

        /**
         * @param dWeights Delay per crossing, indexed by node class, then by column classes followed by
         *                 row classes.
         */
        public Builder setCrossingWeights(float[][] dWeights) {
            this.dWeights = dWeights;
            return this;
        }

        /**
         * Sets the transition delays; a later transition between the same classes replaces an earlier one.
         * @param from Driver classes.
         * @param to Driven classes.
         * @param delay Delays.
         */
        public Builder setTransitions(int[] from, int[] to, float[] delay) {
            transFrom = from;
            transTo = to;
            transDelay = delay;
            return this;
        }

        public Builder setLookahead(float perTileX, float perTileY, float admissiblePerTileX,
                                    float admissiblePerTileY) {
            lookaheadX = perTileX;
            lookaheadY = perTileY;
            admissibleLookaheadX = admissiblePerTileX;
            admissibleLookaheadY = admissiblePerTileY;
            return this;
        }

        public Builder setPreRouteDelay(float preRoute) {
            this.preRoute = preRoute;
            return this;
        }

        public Builder setSpeedScales(String[] speedGrades, float[] scales) {
            this.speedGrades = speedGrades;
            this.speedScales = scales;
            return this;
        }

        /**
         * Sets the factors of the fanout delays of the speed grades whose fanout delays scale differently from
         * their other delays (see {@link InterconnectParams#getFanoutSpeedScale(String)}).
         * @param speedGrades The speed grades, without leading dashes.
         * @param scales Their factors.
         * @return This builder.
         */
        public Builder setFanoutSpeedScales(String[] speedGrades, float[] scales) {
            this.fanoutSpeedGrades = speedGrades;
            this.fanoutSpeedScales = scales;
            return this;
        }

        public Builder setDeviceScales(String[] devices, float[] scales) {
            this.devices = devices;
            this.deviceScales = scales;
            return this;
        }

        /**
         * Sets the pin deltas, see {@link InterconnectParams#getSinkPinDelta(SiteTypeEnum, String)}.
         * @param sinkKeys Keys of the sink pin deltas, as &lt;site type&gt;.&lt;pin or bus name&gt;, e.g. SLICEL.A1.
         * @param sinkDeltasTenthsPs The sink pin deltas, in tenths of a ps.
         * @param sourceKeys Keys of the source pin deltas.
         * @param sourceDeltasTenthsPs The source pin deltas, in tenths of a ps.
         */
        public Builder setPinDeltas(String[] sinkKeys, short[] sinkDeltasTenthsPs, String[] sourceKeys,
                                    short[] sourceDeltasTenthsPs) {
            sinkPinKeys = sinkKeys;
            sinkPinDeltas = sinkDeltasTenthsPs;
            sourcePinKeys = sourceKeys;
            sourcePinDeltas = sourceDeltasTenthsPs;
            return this;
        }

        /**
         * Sets the track deltas, see {@link InterconnectParams#getTrackDeltas()}.
         * @param colClass The name of the column class whose crossings they apply to.
         * @param keys The keys: &lt;tile type&gt;.&lt;wire name&gt;.
         * @param deltasTenthsPs The deltas of the keys, in tenths of a ps.
         * @return This builder.
         */
        public Builder setTrackDeltas(String colClass, String[] keys, short[] deltasTenthsPs) {
            return setTrackDeltas(new String[]{colClass}, keys, deltasTenthsPs);
        }

        /**
         * Sets the track deltas, see {@link InterconnectParams#getTrackDeltas()}.
         * @param classes The names of the column and row classes whose crossings they apply to.
         * @param keys The keys: &lt;tile type&gt;.&lt;wire name&gt;.
         * @param deltasTenthsPs The deltas of the keys, in tenths of a ps.
         * @return This builder.
         */
        public Builder setTrackDeltas(String[] classes, String[] keys, short[] deltasTenthsPs) {
            trackClasses = classes.clone();
            trackKeys = keys;
            trackDeltas = deltasTenthsPs;
            return this;
        }

        public InterconnectParams build() {
            return new InterconnectParams(this);
        }
    }
}

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

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

import com.xilinx.rapidwright.device.Device;
import com.xilinx.rapidwright.device.Node;
import com.xilinx.rapidwright.device.Series;
import com.xilinx.rapidwright.device.SiteTypeEnum;
import com.xilinx.rapidwright.timing.delayestimator.DelayEstimator;
import com.xilinx.rapidwright.timing.family.Corner;
import com.xilinx.rapidwright.timing.family.SpeedGrade;
import com.xilinx.rapidwright.timing.family.TimingData;
import com.xilinx.rapidwright.timing.family.TimingDataFile;
import com.xilinx.rapidwright.timing.family.TimingFamilies;
import com.xilinx.rapidwright.timing.family.TimingFamily;

/**
 * The fitted clock data of a timing data set (see {@link TimingData}; e.g. ultrascaleplus, versal_gen1,
 * versal_gen2) at a speed grade, from the section {@link TimingData#CLOCK} of its timing data file: the
 * classification of the clock route nodes, their delays and delay taps, the leaf configuration and CLE
 * clock modifier, the buffer arcs, the clock generator compensations and the unrouted estimate
 * ({@code ClockDelayParams}), the uncertainty coefficients and the inter-SLR compensation.
 * <p>
 * The data of a set is a tree of maps, lists, strings and numbers (as JSON, see {@code ClockDataJson};
 * as strings and integers in the timing data file, see {@link #decode}). Delays are in ps; a per-corner value is
 * a map from the corner name (SLOW_MAX, SLOW_MIN, FAST_MAX, FAST_MIN) to the value. Its keys:
 * <ul>
 * <li>{@code family}, {@code version}, {@code speedRef}: the family, the data version and the speed grade
 * the delays are for;</li>
 * <li>{@code clockClasses}, {@code classRules}, {@code dataIntents}, {@code dataClass}: the node classes
 * and the rules (intent code and node name regular expressions) mapping clock route nodes to them, the
 * class of general interconnect nodes;</li>
 * <li>{@code clockCorners}: the per-class terms of each corner (base, length, clock region column and row,
 * SLR crossing and fanout terms, see {@code ClockDelayParams});</li>
 * <li>{@code speedScalesByCorner}, {@code gradeGroups}: the scale of the route delays at other speed grades,
 * and per-grade-group class terms and load pin deltas ({@code gradeRegex} selects the group);</li>
 * <li>{@code delayTaps}, {@code siteTaps}, {@code clockModifier}: the delays per tap value of the
 * programmable delay taps and of the CLE clock modifier, per corner;</li>
 * <li>{@code leafConfig}, {@code leafConfigByGrade}, {@code leafSpeedScalesByCorner},
 * {@code loadPinDelta}: the leaf delays per load site type and pin;</li>
 * <li>{@code bufferArcs}, {@code inputBuffers}, {@code dedicatedRoutes}, {@code dedicatedRoutesByDevice},
 * {@code zeroDelayConnections}, {@code unroutedInputs}: the arcs of clock buffers and input buffers, the
 * delays of dedicated clock connections, and the constant of unrouted clock inputs per speed grade;</li>
 * <li>{@code generators}: the compensation of the clock generators per kind and compensation mode;</li>
 * <li>{@code unrouted}: the estimate of unrouted clock nets per corner ({@code a + av*rows + ah*columns +
 * abuf*buffer distance + aslr*SLRs});</li>
 * <li>{@code uncertainty}: the clock uncertainty coefficients ({@link UncertaintyModel.Coefficients});</li>
 * <li>{@code interSlr}: the inter-SLR clock pessimism prorate factor;</li>
 * <li>{@code crpr}: {@code branchStart}, the node classes whose clock pessimism removal at a routing node where
 * clock paths branch ends at the node's start (by default, UltraScale+: {@code CLK_HDISTR}, {@code CLK_LOCAL}).</li>
 * </ul>
 * Other keys document the data and are not read.
 * <p>
 * Typical use:
 * <pre>
 *   ClockData data = ClockData.get(device, speedGrade);
 *   ClockModel model = ClockModel.build(design, clockNetwork, data.createNodeDelayModel());
 *   UncertaintyModel unc = data.createUncertaintyModel(clockNetwork, constraints);
 * </pre>
 * A variant of the data with some terms changed, e.g. for a calibration or a sensitivity study, is a private copy
 * ({@link #withOverrides(Overrides)}); the shared data does not change.
 */
public final class ClockData {

    private final String family;
    private final String dataVersion;
    private final String speedGrade;
    private final ClockNodeClassifier classifier;
    private final ClockDelayParams params;
    private final UncertaintyModel.Coefficients coefficients;
    private final float interSlrProrate;
    /** Whether the data's general interconnect nodes are timed by the data interconnect model */
    private final boolean dataNodesExternal;

    private static final Map<String, ClockData> CACHE = new ConcurrentHashMap<>();

    private ClockData(String family, String dataVersion, String speedGrade, ClockNodeClassifier classifier,
                      ClockDelayParams params, UncertaintyModel.Coefficients coefficients, float interSlrProrate,
                      boolean dataNodesExternal) {
        this.family = family;
        this.dataVersion = dataVersion;
        this.speedGrade = speedGrade;
        this.classifier = classifier;
        this.params = params;
        this.coefficients = coefficients;
        this.interSlrProrate = interSlrProrate;
        this.dataNodesExternal = dataNodesExternal;
    }

    // ------------------------------------------------------------------------------------------
    // Access
    // ------------------------------------------------------------------------------------------

    /** The decoded data trees of the timing data sets */
    private static final Map<String, Map<String, Object>> TREES = new ConcurrentHashMap<>();

    /**
     * Gets the clock data of a device at a speed grade. The data of each timing data set (UltraScale+; Versal
     * gen1 and gen2) is read from its timing data file and decoded once; general interconnect nodes of clock
     * routes are timed by the family's delay estimator when the data says so.
     * @param device The device.
     * @param speedGrade The speed grade, or null for the data's reference grade.
     * @return The data, or null if the device's series has none.
     */
    public static ClockData get(Device device, SpeedGrade speedGrade) {
        String fam = getFamilyId(device);
        if (fam == null) {
            return null;
        }
        String grade = speedGrade == null ? null : speedGrade.getName();
        ClockData d = CACHE.computeIfAbsent(fam + "|" + grade, k -> get(fam, grade));
        if (d.dataNodesExternal) {
            // A copy whose DATA nodes use the device's delay estimators
            ClockData copy = d.copy();
            copy.params.setDataNodeDelay(dataNodeDelay(device, speedGrade));
            return copy;
        }
        return d;
    }

    /**
     * Gets the clock data of a timing data set at a speed grade (without a device: DATA nodes of the sets
     * that time them with the data interconnect model then cost 0).
     * @param family The name of the timing data set: ultrascaleplus, versal_gen1 or versal_gen2.
     * @param speedGrade The speed grade without a leading dash, or null for the reference grade.
     * @return The data.
     */
    static ClockData get(String family, String speedGrade) {
        return fromTree(getTree(family), speedGrade, TimingData.get(family).getDataVersion(TimingData.CLOCK));
    }

    /**
     * @return The timing data set that has the clock data of a device (ultrascaleplus, versal_gen1,
     * versal_gen2), null if none.
     */
    static String getFamilyId(Device device) {
        TimingData timingData = TimingData.get(device);
        return timingData == null || timingData.getClockSection() == null ? null : timingData.getName();
    }

    /**
     * Gets the data tree of a timing data set, decoded on first use.
     * @param family The name of the timing data set.
     * @return The tree (not to be modified).
     * @throws IllegalArgumentException If the data set has no clock data.
     */
    static Map<String, Object> getTree(String family) {
        Map<String, Object> tree = TREES.get(family);
        if (tree == null) {
            TimingDataFile.Section section = TimingData.get(family).getClockSection();
            if (section == null) {
                throw new IllegalArgumentException("No clock data in timing data " + family);
            }
            tree = TREES.computeIfAbsent(family,
                    f -> decode(section.getStrings("strings"), section.getInts("ints")));
        }
        return tree;
    }

    /**
     * The delays of the general interconnect nodes of clock routes: those of the family's delay estimators,
     * unmasked (U-turn nodes allowed, as in the STA), at each corner.
     */
    private static ClockDelayParams.DataNodeDelay dataNodeDelay(Device device, SpeedGrade grade) {
        TimingFamily f = TimingFamilies.of(device);
        DelayEstimator[] est = new DelayEstimator[Corner.values().length];
        return (Node node, Corner c) -> {
            DelayEstimator e;
            synchronized (est) {
                e = est[c.ordinal()];
                if (e == null) {
                    try {
                        e = f.createDelayEstimator(device, grade, c, true);
                    } catch (UnsupportedOperationException ex) {
                        e = c == Corner.SLOW_MAX ? null : f.createDelayEstimator(device, grade, Corner.SLOW_MAX, true);
                    }
                    est[c.ordinal()] = e;
                }
            }
            return e == null ? 0f : e.getDelayOf(node);
        };
    }

    /**
     * A term of the delay of a clock node class at a corner. A node of the class on a clock route has the delay
     * <pre>
     *   scale * (BASE + LENGTH*length + CR_COL*columns + CR_ROW*rows + SLR*crossings + FANOUT*(branches - 1)
     *            + TAP_X*|dx| + TAP_Y*|dy|)
     * </pre>
     * with scale the route scale of the speed grade at the corner, length the dx+dy of the tile bounding box of the
     * node's wires, columns and rows the clock region columns and rows it crosses (the largest minus the smallest
     * clock region column, row, of its wires' tiles), crossings the SLR boundaries it
     * crosses, branches the nodes and loads it drives in the route, and dx and dy the tiles between the PIP that
     * enters the node and the PIP that leaves it towards the load. Each term is in ps per unit of its feature.
     */
    public enum Term {
        /** The delay of every node of the class, in ps */
        BASE,
        /** The delay per tile of the node's length (dx+dy of the tile bounding box of its wires), in ps */
        LENGTH,
        /** The delay per clock region column that the node crosses (largest minus smallest column), in ps */
        CR_COL,
        /** The delay per clock region row that the node crosses (largest minus smallest row), in ps */
        CR_ROW,
        /** The delay per SLR boundary that the node crosses, in ps */
        SLR,
        /** The delay per extra branch of the route out of the node, in ps */
        FANOUT,
        /** The delay per tile in X between the PIPs that enter and leave the node, in ps */
        TAP_X,
        /** The delay per tile in Y between the PIPs that enter and leave the node, in ps */
        TAP_Y
    }

    /**
     * Changes to existing terms of clock data, for {@link ClockData#withOverrides(Overrides)}: the delay terms of
     * clock node classes, the route corrections of clock load pins, and the discrete jitter of kinds of clocks. A
     * setter replaces any value an earlier call set for the same item. Not thread-safe; {@code withOverrides}
     * copies it, so it may be changed or reused afterwards.
     */
    public static final class Overrides {
        /** By {@code <siteType>.<sitePin>}: ps by corner ordinal, NaN where not set */
        private final Map<String, float[]> loadPinDeltas = new LinkedHashMap<>();
        /** By node class: ps by term ordinal * corners + corner ordinal, NaN where not set */
        private final Map<String, float[]> classTerms = new LinkedHashMap<>();
        /** By kind of clock: {a ps, b ps per ns} */
        private final Map<String, float[]> jitter = new LinkedHashMap<>();

        /** Creates overrides that change nothing. */
        public Overrides() {
        }

        private Overrides copy() {
            Overrides o = new Overrides();
            for (Map.Entry<String, float[]> e : loadPinDeltas.entrySet()) {
                o.loadPinDeltas.put(e.getKey(), e.getValue().clone());
            }
            for (Map.Entry<String, float[]> e : classTerms.entrySet()) {
                o.classTerms.put(e.getKey(), e.getValue().clone());
            }
            for (Map.Entry<String, float[]> e : jitter.entrySet()) {
                o.jitter.put(e.getKey(), e.getValue().clone());
            }
            return o;
        }

        private static float checkFinite(float value, String what) {
            if (!Float.isFinite(value)) {
                throw new IllegalArgumentException("The " + what + " must be finite: " + value);
            }
            return value;
        }

        /**
         * Sets the route correction of the clock loads on a site pin at a corner: the delay that the clock arrival
         * at such a load adds to that of its route (the data's load pin delta, outside the speed grade's route
         * scale). The data must have a correction for the pin ({@code withOverrides} checks it); its other
         * corners keep the data's values.
         * @param siteType The type of the load's site (that of its site instance, else that of its site), e.g.
         *                 RAMB36.
         * @param sitePin The name of the site pin, e.g. CLKARDCLKL.
         * @param corner The corner.
         * @param ps The correction in ps.
         * @return These overrides.
         * @throws IllegalArgumentException If the correction is not finite.
         */
        public Overrides setLoadPinDelta(SiteTypeEnum siteType, String sitePin, Corner corner, float ps) {
            Objects.requireNonNull(siteType, "siteType");
            Objects.requireNonNull(sitePin, "sitePin");
            checkFinite(ps, "load pin delta");
            float[] d = loadPinDeltas.computeIfAbsent(siteType + "." + sitePin,
                    k -> nanArray(ClockDelayParams.NUM_CORNERS));
            d[corner.ordinal()] = ps;
            return this;
        }

        /**
         * Sets a delay term of a clock node class at a corner.
         * @param nodeClass The name of the class, one of the data's (checked by {@code withOverrides}).
         * @param corner The corner.
         * @param term The term.
         * @param value The term's value, in ps per unit of its feature, before the speed grade's route scale.
         * @return These overrides.
         * @throws IllegalArgumentException If the value is not finite.
         */
        public Overrides setClassTerm(String nodeClass, Corner corner, Term term, float value) {
            Objects.requireNonNull(nodeClass, "nodeClass");
            checkFinite(value, "class term");
            float[] t = classTerms.computeIfAbsent(nodeClass,
                    k -> nanArray(Term.values().length * ClockDelayParams.NUM_CORNERS));
            t[term.ordinal() * ClockDelayParams.NUM_CORNERS + corner.ordinal()] = value;
            return this;
        }

        /**
         * Sets the discrete jitter coefficients of a kind of clock: {@code a + b*T}, T being the period in ns of the
         * clock (generator output) the jitter comes from (see
         * {@link UncertaintyModel.Coefficients#getDiscreteJitter}). This replaces the data's coefficients of that
         * kind only, and its table by generator settings if it has one ({@link UncertaintyModel.DiscreteJitterTable});
         * how the uncertainty model uses them does not change: a kind without coefficients of its own
         * still takes those of a related kind (e.g. FABRIC and GT those of PORT), and the port jitter of a device
         * that has it ({@link UncertaintyModel.PortJitter}) still replaces the coefficients of PORT clocks.
         * @param clockKind The kind: MMCM, PLL, PS, GT, PORT or FABRIC (see {@link Clock#getKind()}).
         * @param aPs a, in ps.
         * @param bPsPerNs b, in ps per ns of period.
         * @return These overrides.
         * @throws IllegalArgumentException If the kind is unknown, or a coefficient not finite.
         */
        public Overrides setDiscreteJitter(String clockKind, float aPs, float bPsPerNs) {
            if (!Clock.KINDS.contains(clockKind)) {
                throw new IllegalArgumentException("Unknown kind of clock " + clockKind + ", expected one of "
                        + Clock.KINDS);
            }
            jitter.put(clockKind, new float[]{checkFinite(aPs, "jitter"), checkFinite(bPsPerNs, "jitter")});
            return this;
        }

        private static float[] nanArray(int n) {
            float[] a = new float[n];
            Arrays.fill(a, Float.NaN);
            return a;
        }

        /**
         * Applies the overrides to a private copy of clock data.
         * @throws IllegalArgumentException If a load pin or node class is not one of the data's.
         */
        private void applyTo(ClockData d) {
            for (Map.Entry<String, float[]> e : loadPinDeltas.entrySet()) {
                float[] old = d.params.getLoadPinDelta(e.getKey());
                if (old == null) {
                    // A new entry would also shadow that of the site's type for the loads of a site instance of
                    // another type (see ClockModel), with 0 at the other corners
                    throw new IllegalArgumentException("No load pin delta for " + e.getKey() + " in "
                            + d.dataVersion);
                }
                float[] delta = old.clone();
                for (int c = 0; c < delta.length; c++) {
                    if (!Float.isNaN(e.getValue()[c])) {
                        delta[c] = e.getValue()[c];
                    }
                }
                d.params.setLoadPinDelta(e.getKey(), delta);
            }
            for (Map.Entry<String, float[]> e : classTerms.entrySet()) {
                if (d.params.getClassIndex(e.getKey()) < 0) {
                    throw new IllegalArgumentException("Unknown clock node class " + e.getKey() + " in "
                            + d.dataVersion + ", expected one of " + d.params.getClassNames());
                }
                for (Term term : Term.values()) {
                    for (Corner corner : Corner.values()) {
                        float v = e.getValue()[term.ordinal() * ClockDelayParams.NUM_CORNERS + corner.ordinal()];
                        if (!Float.isNaN(v)) {
                            d.params.setClassTerm(e.getKey(), corner, term, v);
                        }
                    }
                }
            }
            for (Map.Entry<String, float[]> e : jitter.entrySet()) {
                d.coefficients.jitter.put(e.getKey(), e.getValue().clone());
                d.coefficients.jitterTables.remove(e.getKey());
            }
        }
    }

    /** A copy of the data, decoded again from the data tree, with the same overrides */
    private ClockData copy() {
        ClockData d = fromTree(tree, speedGrade, null);
        ClockData c = new ClockData(d.family, dataVersion, speedGrade, d.classifier, d.params, d.coefficients,
                d.interSlrProrate, d.dataNodesExternal);
        c.tree = tree;
        for (Overrides o : overrides) {
            o.applyTo(c);
        }
        c.overrides = overrides;
        return c;
    }

    /**
     * Creates a private copy of this data with some of its terms changed. This data, which may be the shared
     * instance of {@link #get(Device, SpeedGrade)}, does not change, and neither does any data that
     * {@code get} returns later. The copy times the general interconnect nodes of clock routes as this data does,
     * and keeps the overrides of this data if it is itself such a copy (later ones replacing earlier ones). Safe
     * to call from several threads, as long as no other thread changes the overrides meanwhile.
     * <p>
     * The copy acts only where it is used, e.g. for {@link #createNodeDelayModel()} and
     * {@link #createUncertaintyModel}. RapidWright's own timing analysis
     * ({@code com.xilinx.rapidwright.timing.TimingManager}) and the timing engine's arc provider
     * ({@code com.xilinx.rapidwright.timing.sta.FamilyArcProvider}, for the clock routes of the clock buffers that
     * it times as data) take the shared data of {@link #get(Device, SpeedGrade)}, so for now a copy is for callers
     * that build their own clock model or uncertainty model from it; the provider is to take the clock data at
     * construction instead (null for that of {@link #get(Device, SpeedGrade)}). The copy keeps the data version of
     * this data ({@link #getDataVersion()}), although its terms differ.
     * @param overrides The changes, copied: later changes to them do not affect the copy.
     * @return The copy.
     * @throws IllegalArgumentException If the overrides change a load pin delta or a node class that the data does
     * not have.
     */
    public ClockData withOverrides(Overrides overrides) {
        Overrides snapshot = overrides.copy();
        ClockData d = copy();
        d.params.setDataNodeDelay(params.getDataNodeDelay());
        snapshot.applyTo(d);
        List<Overrides> all = new ArrayList<>(this.overrides);
        all.add(snapshot);
        d.overrides = Collections.unmodifiableList(all);
        return d;
    }

    public String getFamily() {
        return family;
    }

    /** @return Family, version and speed grade of the data, for logs. */
    public String getDataVersion() {
        return dataVersion;
    }

    /** @return The speed grade, null for the reference grade. */
    public String getSpeedGrade() {
        return speedGrade;
    }

    ClockNodeClassifier getClassifier() {
        return classifier;
    }

    /**
     * @return The delay parameters: node class terms, delay taps, leaf configuration, clock modifier,
     * buffer arcs, generator compensations and unrouted estimate.
     */
    ClockDelayParams getParams() {
        return params;
    }

    UncertaintyModel.Coefficients getCoefficients() {
        return coefficients;
    }

    /**
     * @return The inter-SLR compensation factor PF: setup and hold checks whose launch and capture clock
     * pins are in different SLRs get {@code (DCD - CCD)*PF} (see {@link ClockModel#getInterSlrCompensation}),
     * 0 if the family has none.
     */
    public float getInterSlrProrate() {
        return interSlrProrate;
    }

    /** @return The node delay model of these classes and parameters. */
    public ClockNodeDelayModel createNodeDelayModel() {
        return new ClockNodeDelayModel(classifier, params);
    }

    /**
     * Creates the uncertainty model of a design's clocks.
     * @param cn The clock network.
     * @param constraints Its constraints (user uncertainty and jitter).
     * @return The model.
     */
    public UncertaintyModel createUncertaintyModel(ClockNetwork cn, TimingConstraints constraints) {
        return new UncertaintyModel(cn.getClocks(), cn, constraints, coefficients);
    }

    // ------------------------------------------------------------------------------------------
    // From the data tree
    // ------------------------------------------------------------------------------------------

    private Map<String, Object> tree;
    /** The overrides of a copy of the data (see {@link #withOverrides(Overrides)}) in order, none for decoded data */
    private List<Overrides> overrides = Collections.emptyList();

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object o) {
        return o instanceof Map ? (Map<String, Object>) o : Collections.emptyMap();
    }

    @SuppressWarnings("unchecked")
    private static List<Object> list(Object o) {
        return o instanceof List ? (List<Object>) o : Collections.emptyList();
    }

    private static float num(Object o, float dflt) {
        return o instanceof Number ? ((Number) o).floatValue() : dflt;
    }

    private static String str(Object o, String dflt) {
        return o instanceof String ? (String) o : dflt;
    }

    /**
     * Builds the clock data of a speed grade from the data tree (the clock.json structure).
     * @param o The tree.
     * @param speedGrade The speed grade without a leading dash, or null for the reference grade.
     * @param version The data version (null: from the tree).
     * @return The data.
     */
    static ClockData fromTree(Map<String, Object> o, String speedGrade, String version) {
        String family = str(o.get("family"), "?");
        String dataVersion = (version != null ? version : family + " clock v" + num(o.get("version"), 0))
                + ", grade " + (speedGrade == null ? str(o.get("speedRef"), "ref") : speedGrade);
        ClockDelayParams p = new ClockDelayParams();
        // A speed grade without data takes that of the nearest listed grade (see SpeedGrade#resolve)
        speedGrade = resolveGrade(o, speedGrade);
        Set<String> classNames = new TreeSet<>();
        for (Object n : list(o.get("clockClasses"))) {
            classNames.add(String.valueOf(n));
            p.addClass(String.valueOf(n));
        }
        String dataClass = str(o.get("dataClass"), null);
        boolean dataExternal = dataClass == null || !classNames.contains(dataClass);
        ClockNodeClassifier classifier = parseRules(o, dataClass, dataExternal);
        // Node terms, from the grade group of the speed grade if any
        Map<String, Object> corners = map(o.get("clockCorners"));
        Map<String, Object> scales = map(o.get("speedScalesByCorner"));
        Map<String, Object> loadPinDelta = map(o.get("loadPinDelta"));
        if (speedGrade != null) {
            for (Object g : list(o.get("gradeGroups"))) {
                Map<String, Object> group = map(g);
                String regex = str(group.get("gradeRegex"), null);
                if (regex != null && Pattern.compile(regex).matcher(speedGrade).find()) {
                    corners = map(group.get("clockCorners"));
                    if (group.containsKey("speedScalesByCorner")) {
                        scales = map(group.get("speedScalesByCorner"));
                    }
                    if (group.containsKey("loadPinDelta")) {
                        loadPinDelta = map(group.get("loadPinDelta"));
                    }
                    break;
                }
            }
        }
        String[][] terms = {{"base", "BASE"}, {"length", "LENGTH"}, {"crCol", "CR_COL"}, {"crRow", "CR_ROW"},
            {"slr", "SLR"}, {"fanout", "FANOUT"}, {"tapX", "TAP_X"}, {"tapY", "TAP_Y"}};
        for (Corner c : Corner.values()) {
            Map<String, Object> cc = map(corners.get(c.name()));
            for (String[] t : terms) {
                for (Map.Entry<String, Object> e : map(cc.get(t[0])).entrySet()) {
                    p.setClassTerm(e.getKey(), c, Term.valueOf(t[1]), num(e.getValue(), 0));
                }
            }
            p.setRouteScale(c, gradeScale(scales, speedGrade, c));
        }
        // Delay tap tables (at the reference grade, scaled with the route)
        for (Map.Entry<String, Object> e : new TreeMap<>(map(o.get("delayTaps"))).entrySet()) {
            float[][] d = new float[ClockDelayParams.NUM_CORNERS][];
            for (Corner c : Corner.values()) {
                d[c.ordinal()] = floats(map(e.getValue()).get(c.name()));
            }
            p.addDelayTaps(e.getKey(), d);
        }
        // Leaf configuration: measured at the grade if available, else scaled from the reference grade
        Map<String, Object> leaf = map(o.get("leafConfig"));
        Map<String, Object> leafByGrade = speedGrade == null ? Collections.emptyMap()
                : map(map(o.get("leafConfigByGrade")).get(speedGrade));
        Set<String> keys = new TreeSet<>();
        for (Map<String, Object> src : java.util.Arrays.asList(leaf, leafByGrade)) {
            for (Object corner : src.values()) {
                keys.addAll(map(corner).keySet());
            }
        }
        Map<String, Object> leafScales = map(o.get("leafSpeedScalesByCorner"));
        for (String key : keys) {
            float[] d = new float[ClockDelayParams.NUM_CORNERS];
            for (Corner c : Corner.values()) {
                Map<String, Object> g = map(leafByGrade.get(c.name()));
                if (g.containsKey(key)) {
                    d[c.ordinal()] = num(g.get(key), 0);
                } else {
                    Map<String, Object> r = map(leaf.get(c.name()));
                    d[c.ordinal()] = r.containsKey(key) ? num(r.get(key), 0) * gradeScale(leafScales, speedGrade, c)
                            : 0f;
                }
            }
            p.setLeafConfig(key, d);
        }
        // CLE clock modifier, at its reference grade
        Map<String, Object> mod = map(o.get("clockModifier"));
        if (mod.containsKey("delays")) {
            String ref = str(mod.get("refGrade"), null);
            float[][] taps = new float[ClockDelayParams.NUM_CORNERS][];
            for (Corner c : Corner.values()) {
                float scale = speedGrade == null || speedGrade.equals(ref) ? 1f
                        : gradeScale(map(mod.get("speedScalesByCorner")), speedGrade, c);
                taps[c.ordinal()] = floats(map(mod.get("delays")).get(c.name()));
                for (int i = 0; i < taps[c.ordinal()].length; i++) {
                    taps[c.ordinal()][i] *= scale;
                }
            }
            p.setClockModifierDelays(taps);
        }
        // By site type (SLICEL, SLICEM), at their reference grade
        for (Map.Entry<String, Object> e : new TreeMap<>(map(mod.get("delaysBySiteType"))).entrySet()) {
            Map<String, Object> st = map(e.getValue());
            String ref = str(st.get("refGrade"), str(mod.get("refGrade"), null));
            Map<String, Object> stScales = map(st.containsKey("gradeScale") ? st.get("gradeScale")
                    : mod.get("speedScalesByCorner"));
            float[][] taps = new float[ClockDelayParams.NUM_CORNERS][];
            for (Corner c : Corner.values()) {
                float scale = speedGrade == null || speedGrade.equals(ref) ? 1f : gradeScale(stScales, speedGrade, c);
                taps[c.ordinal()] = floats(map(st.get("delays")).get(c.name()));
                for (int i = 0; i < taps[c.ordinal()].length; i++) {
                    taps[c.ordinal()][i] *= scale;
                }
            }
            p.setClockModifierDelays(e.getKey(), taps);
        }
        // Site taps (Versal programmable clock delays), at their reference grade
        for (Map.Entry<String, Object> e : new TreeMap<>(map(o.get("siteTaps"))).entrySet()) {
            Map<String, Object> st = map(e.getValue());
            String ref = str(st.get("refGrade"), null);
            float[][] taps = new float[ClockDelayParams.NUM_CORNERS][];
            for (Corner c : Corner.values()) {
                float scale = speedGrade == null || speedGrade.equals(ref) ? 1f
                        : gradeScale(map(st.get("speedScalesByCorner")), speedGrade, c);
                taps[c.ordinal()] = floats(st.get(c.name()));
                for (int i = 0; i < taps[c.ordinal()].length; i++) {
                    taps[c.ordinal()][i] *= scale;
                }
            }
            p.setSiteTaps(e.getKey(), taps);
        }
        for (Map.Entry<String, float[]> e : cornerMap(loadPinDelta).entrySet()) {
            p.setLoadPinDelta(e.getKey(), e.getValue());
        }
        for (Map.Entry<String, float[]> e : cornerMap(map(o.get("dedicatedRoutes"))).entrySet()) {
            p.setDedicatedRoute(e.getKey(), e.getValue());
        }
        for (Object r : list(o.get("dedicatedRoutesByDevice"))) {
            Map<String, Object> m = map(r);
            p.addDeviceDedicatedRoutes(str(m.get("deviceRegex"), "^$"), cornerMap(map(m.get("routes"))));
        }
        // Unrouted port -> clock buffer inputs: the first grade regex matching the grade (else the reference)
        String g = speedGrade != null ? speedGrade : str(o.get("speedRef"), "");
        for (Object r : list(map(o.get("unroutedInputs")).get("byGrade"))) {
            Map<String, Object> m = map(r);
            if (Pattern.compile(str(m.get("gradeRegex"), "^$")).matcher(g).find()) {
                float[] d = new float[ClockDelayParams.NUM_CORNERS];
                for (Corner c : Corner.values()) {
                    d[c.ordinal()] = num(m.get(c.name()), 0);
                }
                p.setUnroutedInput(d);
                break;
            }
        }
        for (Object z : list(o.get("zeroDelayConnections"))) {
            Map<String, Object> m = map(z);
            p.addZeroDelayConnection(str(m.get("sourceSiteType"), ".*"), str(m.get("sourcePin"), ".*"),
                    str(m.get("loadSiteType"), ".*"));
        }
        Map<String, Object> ib = map(o.get("inputBuffers"));
        for (Map.Entry<String, float[]> e : cornerMap(map(ib.get("base"))).entrySet()) {
            p.setInputBuffer(e.getKey(), e.getValue());
        }
        for (Map.Entry<String, float[]> e : cornerMap(map(ib.get("portNet"))).entrySet()) {
            p.setInputBufferPortNet(e.getKey(), e.getValue());
        }
        for (Map.Entry<String, float[]> e : cornerMap(map(o.get("bufferArcs"))).entrySet()) {
            p.setBufferDelay(e.getKey(), e.getValue());
        }
        for (Map.Entry<String, float[]> e : cornerMap(map(map(o.get("generators")).get("compensation"))).entrySet()) {
            p.setGeneratorDelay(e.getKey(), e.getValue());
        }
        Map<String, Object> unrouted = map(o.get("unrouted"));
        for (Corner c : Corner.values()) {
            Map<String, Object> u = map(map(unrouted.get("corners")).get(c.name()));
            if (!u.isEmpty()) {
                p.setUnrouted(c, num(u.get("a"), 0), num(u.get("av"), 0), num(u.get("ah"), 0), num(u.get("abuf"), 0),
                        num(u.get("aslr"), 0));
            }
            Map<String, Object> m = map(map(unrouted.get("cornersLoadMedianRoot")).get(c.name()));
            if (!m.isEmpty()) {
                p.setUnroutedLoadMedianRoot(c, num(m.get("a"), 0), num(m.get("av"), 0), num(m.get("ah"), 0),
                        num(m.get("abuf"), 0), num(m.get("aslr"), 0));
            }
        }
        UncertaintyModel.Coefficients coef = new UncertaintyModel.Coefficients();
        Map<String, Object> unc = map(o.get("uncertainty"));
        if (unc.containsKey("tsj")) {
            coef.totalSystemJitter = num(unc.get("tsj"), Float.NaN);
        }
        // Versal: CJ/2 + PE + PJ, the clock jitter CJ adding the system jitter to the discrete jitter
        coef.systemJitterAdds = str(unc.get("formula"), "").startsWith("CJ");
        for (Map.Entry<String, Object> e : map(unc.get("jitter")).entrySet()) {
            List<Object> ab = list(e.getValue());
            coef.jitter.put(e.getKey(), new float[]{ab.isEmpty() ? 0 : num(ab.get(0), 0),
                ab.size() < 2 ? 0 : num(ab.get(1), 0)});
        }
        // The discrete jitter of the outputs of clock generators by their settings (see DiscreteJitterTable), a
        // table for one or several kinds ('+'-joined)
        for (Map.Entry<String, Object> e : map(unc.get("jitterTables")).entrySet()) {
            Map<String, Object> t = map(e.getValue());
            UncertaintyModel.DiscreteJitterTable table = new UncertaintyModel.DiscreteJitterTable(
                    floats(t.get("vcoMHz")), floats(t.get("mult")), floats(t.get("divide")), floats(t.get("jitter")));
            for (String kind : e.getKey().split("\\+")) {
                coef.jitterTables.put(kind, table);
            }
        }
        Map<String, Object> pe = map(unc.get("phaseError"));
        coef.phaseErrorSame = num(pe.get("same"), 0);
        coef.phaseErrorIn = num(pe.get("in"), 0);
        coef.phaseErrorDiff = num(pe.get("diff"), 0);
        coef.sameClockHold = num(unc.get("sameClockHold"), 0);
        // Versal: the jitter of port clocks by device, groups of devices sharing data (see UncertaintyModel.PortJitter)
        Map<String, Object> pj = map(unc.get("portJitter"));
        Map<String, Object> groups = map(pj.get("groups"));
        for (Map.Entry<String, Object> e : map(pj.get("devices")).entrySet()) {
            Map<String, Object> grp = map(groups.get(str(e.getValue(), "")));
            if (!grp.isEmpty()) {
                coef.portJitter.put(e.getKey(), new UncertaintyModel.PortJitter(floats(grp.get("periods")),
                        floats(grp.get("jitter")), floats(grp.get("byRows")), floats(grp.get("byColumns"))));
            }
        }
        Map<String, Object> interSlr = map(o.get("interSlr"));
        float prorate = num(interSlr.get("prorate"), 0);
        // Where the common clock delay ends: "lcaStart" or "lca" (the default, except for UltraScale+,
        // whose clock buffers' delays are on the nodes they drive)
        String common = str(interSlr.get("common"), family.equals("usplus") ? "lcaStart" : "lca");
        p.setInterSlrCommonAtLcaStart(common.equals("lcaStart"));
        // The node classes whose CRPR credit, where the clock paths branch at a routing node of the class, ends at
        // the node's start (the others: at its end); by default, on UltraScale+, the horizontal distribution and the
        // local clock nodes, whose delays are those of the buffers driving them (as Vivado's pessimism removal)
        Object branchStart = map(o.get("crpr")).get("branchStart");
        if (branchStart instanceof List) {
            List<String> classes = new ArrayList<>();
            for (Object c : (List<?>) branchStart) {
                classes.add(String.valueOf(c));
            }
            p.setCrprBranchStartClasses(classes);
        } else if (family.equals("usplus")) {
            p.setCrprBranchStartClasses(Arrays.asList("CLK_HDISTR", "CLK_LOCAL"));
        }
        ClockData d = new ClockData(family, dataVersion, speedGrade, classifier, p, coef, prorate, dataExternal);
        d.tree = o;
        return d;
    }

    private static float[] floats(Object o) {
        List<Object> l = list(o);
        float[] a = new float[l.size()];
        for (int i = 0; i < a.length; i++) {
            a[i] = num(l.get(i), 0);
        }
        return a;
    }

    /** A {corner: {key: ps}} map as key -> delays per corner */
    private static Map<String, float[]> cornerMap(Map<String, Object> byCorner) {
        Map<String, float[]> out = new TreeMap<>();
        for (Corner c : Corner.values()) {
            for (Map.Entry<String, Object> e : map(byCorner.get(c.name())).entrySet()) {
                out.computeIfAbsent(e.getKey(), k -> new float[ClockDelayParams.NUM_CORNERS])[c.ordinal()] =
                        num(e.getValue(), 0);
            }
        }
        return out;
    }

    /**
     * The speed grade whose data a grade uses: itself if the data lists it (speedScalesByCorner or the
     * reference speedRef), else the nearest listed grade (see {@link SpeedGrade#resolve}, which warns once).
     * @return The grade, null for the reference grade.
     */
    private static String resolveGrade(Map<String, Object> o, String speedGrade) {
        if (speedGrade == null) {
            return null;
        }
        String ref = str(o.get("speedRef"), null);
        Set<String> known = new TreeSet<>(map(o.get("speedScalesByCorner")).keySet());
        if (ref != null) {
            known.add(ref);
        }
        if (known.isEmpty()) {
            return speedGrade;
        }
        return SpeedGrade.resolve(speedGrade, known, ref, "clock data");
    }

    /** The factor of a grade at a corner in a {grade: {corner: factor}} map (1 if missing) */
    private static float gradeScale(Map<String, Object> scales, String speedGrade, Corner c) {
        if (speedGrade == null) {
            return 1f;
        }
        return num(map(scales.get(speedGrade)).get(c.name()), 1f);
    }

    /** The classification rules of the data (classRules, dataIntents/dataClass), else the defaults */
    private static ClockNodeClassifier parseRules(Map<String, Object> o, String dataClass, boolean dataExternal) {
        List<Object> rules = list(o.get("classRules"));
        if (rules.isEmpty()) {
            return ClockNodeClassifier.forSeries(str(o.get("family"), "").startsWith("versal") ? Series.Versal
                    : Series.UltraScalePlus);
        }
        String dataTarget = dataExternal ? ClockNodeClassifier.DATA : dataClass;
        List<ClockNodeClassifier.Rule> out = new ArrayList<>();
        boolean dataRule = false;
        for (Object r : rules) {
            Map<String, Object> m = map(r);
            String cls = str(m.get("class"), "");
            if (cls.equals(dataClass)) {
                cls = dataTarget;
                dataRule = true;
            }
            out.add(new ClockNodeClassifier.Rule(str(m.get("intent"), ".*"), str(m.get("wire"), ""), cls));
        }
        List<Object> intents = list(o.get("dataIntents"));
        if (!dataRule && !intents.isEmpty()) {
            // The general interconnect intents come before the catch-all rule (the last one)
            StringBuilder sb = new StringBuilder("^(");
            for (int i = 0; i < intents.size(); i++) {
                if (i > 0) sb.append('|');
                sb.append(Pattern.quote(String.valueOf(intents.get(i))));
            }
            sb.append(")$");
            out.add(Math.max(0, out.size() - 1), new ClockNodeClassifier.Rule(sb.toString(), "", dataTarget));
        }
        return new ClockNodeClassifier(out);
    }

    /**
     * Decodes the clock data of a timing data file (the entries of its section
     * {@link TimingData#CLOCK}) into its data tree.
     * @param strings The strings.
     * @param ints The integers.
     * @return The tree.
     * @throws IllegalArgumentException If the integers are not clock data.
     */
    public static Map<String, Object> decode(String[] strings, int[] ints) {
        return ClockDataPack.decode(strings, ints);
    }
}

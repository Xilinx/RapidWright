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
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import com.xilinx.rapidwright.device.Node;
import com.xilinx.rapidwright.timing.clock.ClockData.Term;
import com.xilinx.rapidwright.timing.family.Corner;

/**
 * The parameters of a {@link ClockModel}, per timing corner, in ps, at one speed grade (see
 * {@link ClockData} for the fitted data). All default to 0, so that the model
 * can be built and tested before fitted data exists.
 * <ul>
 * <li><b>Node classes</b> (see {@link ClockNodeClassifier}): a node of class c on a clock route costs
 * <pre>
 *   scale * ( base[c] + length[c]*LENGTH + crCol[c]*dcrCol + crRow[c]*dcrRow + slr[c]*(nSLR-1)
 *             + fanout[c]*(branches-1)
 *             + tapX[c]*|dx| + tapY[c]*|dy| )
 *   + delayTaps[regex of the node's wire][corner][delay index of the PIP leaving the node]
 * </pre>
 * LENGTH being the dx+dy of the tile bounding box of the node's wires, dcrCol and dcrRow the clock
 * regions it spans, branches its route children (a load counting as one), and dx, dy the tiles between
 * the PIP entering the node and the PIP leaving it towards the load (from its own tile for the first
 * node, to the load site pin's tile for the last one). The delay tap table holds, for the nodes whose
 * wire name matches a regular expression (e.g. US+ {@code ^CLK_BUFCE_ROW_FSR_\d+_CLK_IN$}), the delay
 * added by each programmable delay tap of the PIP leaving them ({@link com.xilinx.rapidwright.device.PIP#getDelayIndex()};
 * tap 0 adds 0, the class terms carrying its delay). {@link ClockNodeClassifier#DATA} nodes are timed
 * by a {@code DataNodeDelay}.</li>
 * <li><b>Leaf configuration</b>, by {@code <siteType>.<sitePin>|<cellLogicalPin>} (the placed site type, e.g.
 * {@code SLICEL.CLK1|C}, {@code MMCM.CLKFBIN|CLKFBIN}): from a load site pin to the cell pin; missing
 * keys add the default leaf delay (0).</li>
 * <li><b>Clock modifier</b> (Versal CLE programmable clock delay): when a site's
 * {@code FF_CLK_MOD.IMUX_CLK_MODE} is {@code DELAY}, the cells on its flip-flop BELs get the delay of
 * the tap {@code FF_CLK_MOD.CLK_DLY_VAL}.</li>
 * <li><b>Site taps</b> (Versal): on a route node that is a site output pin whose site has a programmable
 * clock delay BEL attribute (e.g. {@code IRI_IMR_CLK_MOD.CLK_DLY_VAL_COE}, digits of the attribute name
 * written N), the delay of the attribute's tap, outside the route speed scale.</li>
 * <li><b>Load pin deltas</b>: a route correction per load site pin ({@code <siteType>.<sitePin>}),
 * outside the route speed scale.</li>
 * <li><b>Dedicated routes</b>: the route delay of a clock input connection by
 * {@code <srcSiteType>.<srcSitePin>-><loadSiteType>.<loadSitePin>} (e.g. {@code HPIOB_M.I->BUFGCE.CLK_IN}),
 * replacing the node model (scaled with the route), also per device and load site
 * ({@code ...@<loadSiteName>}); <b>zero-delay connections</b> by regular expressions of the source site type,
 * source pin and load site type; and a constant for an unrouted connection from a top-level port to a
 * clock buffer input (out-of-context designs).</li>
 * <li><b>Input buffers</b> (US+): the pad to output arc of INBUF / DIFFINBUF by
 * {@code <HPIOB|HDIOB>:INBUF[:<IOSTANDARD>]} or {@code HPIOBDIFFINBUF:DIFFINBUF}, to which the model adds
 * the package pin's delay, and the port to pad net by cell type.</li>
 * <li><b>Buffers</b>, by {@code <cellType>:<in>-><out>} or {@code <cellType>}, for when the cell arc
 * library has none.</li>
 * <li><b>Clock generators</b> (MMCM/PLL/XPLL/X5PLL/DPLL): the CLKIN to CLKOUT compensation by
 * {@code <kind>:<COMPENSATION>[:<outPin>[:<CLKOUTPHY_DIVIDE>]]} (see {@link #getGeneratorKind(String)});
 * the model adds the phase shift and subtracts the feedback insertion at the opposite min/max corner.</li>
 * <li><b>Unrouted</b> clock nets: {@code a + av*|dCRy| + ah*|dCRx| + abuf*d(buffer, root) + aslr*|dSLR|}
 * from the clock root (CLOCK_ROOT / USER_CLOCK_ROOT, else the load-median clock region, with its own
 * fit) to the loads of each clock region.</li>
 * </ul>
 */
final class ClockDelayParams {

    /** Number of corners */
    static final int NUM_CORNERS = Corner.values().length;

    private static final int NUM_TERMS = Term.values().length;

    /** Delay of a general interconnect node of a clock route, from the data interconnect model */
    @FunctionalInterface
    interface DataNodeDelay {
        /**
         * @param node The node.
         * @param corner The corner.
         * @return Its delay in ps.
         */
        float getDelay(Node node, Corner corner);
    }

    private final List<String> classNames = new ArrayList<>();
    private final Map<String, Integer> classIndex = new HashMap<>();
    /** [term][corner][class] */
    private final float[][][] classTerms = new float[NUM_TERMS][NUM_CORNERS][0];
    private final float[] routeScale = {1f, 1f, 1f, 1f};
    private final List<Pattern> tapPatterns = new ArrayList<>();
    private final List<float[][]> tapDelays = new ArrayList<>();

    private final Map<String, float[]> bufferDelay = new HashMap<>();
    private final Map<String, float[]> generatorDelay = new HashMap<>();
    private final Map<String, float[]> leafConfig = new HashMap<>();
    private final Map<String, float[][]> siteTaps = new HashMap<>();
    private final Map<String, float[]> loadPinDelta = new HashMap<>();
    private final Map<String, float[]> dedicatedRoutes = new HashMap<>();
    private final List<Pattern> deviceRoutePatterns = new ArrayList<>();
    private final List<Map<String, float[]>> deviceRoutes = new ArrayList<>();
    private float[] unroutedInput;
    private final List<Pattern[]> zeroDelayConnections = new ArrayList<>();
    private final Map<String, float[]> inputBufferBase = new HashMap<>();
    private final Map<String, float[]> inputBufferPortNet = new HashMap<>();
    /** [corner][tap] */
    private float[][] modifierDelays;
    /** [corner][tap] by site type (e.g. SLICEL, SLICEM), over modifierDelays */
    private final Map<String, float[][]> modifierDelaysBySiteType = new HashMap<>();
    private final float[][] unrouted = new float[NUM_CORNERS][5];
    private final float[][] unroutedMedian = new float[NUM_CORNERS][5];
    private boolean hasUnroutedMedian;
    private DataNodeDelay dataDelay = (n, c) -> 0f;
    /** Whether the common clock delay of the inter-SLR compensation ends at the start of the last common node */
    private boolean interSlrCommonAtLcaStart;
    /** The node classes whose CRPR credit, at a routing node where clock paths branch, is the arrival at its start */
    private final Set<String> crprBranchStartClasses = new HashSet<>();

    /** Creates parameters with no classes and all delays 0. */
    ClockDelayParams() {
    }

    /**
     * Gets (adding it if need be) the index of a node class.
     * @param name The class name.
     * @return Its index.
     */
    int addClass(String name) {
        Integer i = classIndex.get(name);
        if (i != null) {
            return i;
        }
        int idx = classNames.size();
        classNames.add(name);
        classIndex.put(name, idx);
        for (int t = 0; t < NUM_TERMS; t++) {
            for (int c = 0; c < NUM_CORNERS; c++) {
                classTerms[t][c] = Arrays.copyOf(classTerms[t][c], idx + 1);
            }
        }
        return idx;
    }

    /** @return The index of a class, -1 if unknown. */
    int getClassIndex(String name) {
        Integer i = classIndex.get(name);
        return i == null ? -1 : i;
    }

    List<String> getClassNames() {
        return Collections.unmodifiableList(classNames);
    }

    /**
     * Sets a delay term of a node class at a corner (adding the class if need be).
     * @param name The class.
     * @param corner The corner.
     * @param term The term.
     * @param value Its weight, in ps per unit of its feature.
     */
    void setClassTerm(String name, Corner corner, Term term, float value) {
        int cls = addClass(name);
        classTerms[term.ordinal()][corner.ordinal()][cls] = value;
    }

    /** @return A delay term of a node class at a corner ordinal. */
    float getClassTerm(int cls, int corner, Term term) {
        return classTerms[term.ordinal()][corner][cls];
    }

    /**
     * Sets the node delay terms of a class at a corner (adding the class if need be), the tap and
     * delay arc terms being left unchanged.
     */
    void setClassDelay(String name, Corner corner, float base, float length, float crCol, float crRow,
                              float slr, float fanout) {
        setClassTerm(name, corner, Term.BASE, base);
        setClassTerm(name, corner, Term.LENGTH, length);
        setClassTerm(name, corner, Term.CR_COL, crCol);
        setClassTerm(name, corner, Term.CR_ROW, crRow);
        setClassTerm(name, corner, Term.SLR, slr);
        setClassTerm(name, corner, Term.FANOUT, fanout);
    }

    /** Sets the factor scaling the node delays (route classes) at a corner, e.g. for a speed grade. */
    void setRouteScale(Corner corner, float scale) {
        routeScale[corner.ordinal()] = scale;
    }

    float getRouteScale(int corner) {
        return routeScale[corner];
    }

    /**
     * Gets the delay of a node of a class from its own features (without the terms of the PIP leaving
     * it, see {@link #getLeaveDelay}).
     * @param cls The class index.
     * @param corner The corner ordinal.
     * @param length LENGTH (dx+dy of the tile bounding box).
     * @param crCols Clock-region columns spanned.
     * @param crRows Clock-region rows spanned.
     * @param slrs SLRs crossed.
     * @param branches Number of branches (route children, a load counting as one).
     * @return The delay in ps, scaled.
     */
    float getClassDelay(int cls, int corner, int length, int crCols, int crRows, int slrs, int branches) {
        float d = term(Term.BASE, corner, cls) + term(Term.LENGTH, corner, cls) * length
                + term(Term.CR_COL, corner, cls) * crCols + term(Term.CR_ROW, corner, cls) * crRows
                + term(Term.SLR, corner, cls) * slrs
                + (branches > 1 ? term(Term.FANOUT, corner, cls) * (branches - 1) : 0f);
        return d * routeScale[corner];
    }

    /**
     * Gets the delay a node of a class adds by the way it is left: the tap terms between the PIP
     * entering it and the PIP leaving it (see {@link #getDelayTapDelay} for the programmable delays).
     * @param cls The class index.
     * @param corner The corner ordinal.
     * @param tapX Tiles |dx| between the PIP entering the node and the one leaving it.
     * @param tapY Tiles |dy| likewise.
     * @return The delay in ps, scaled.
     */
    float getLeaveDelay(int cls, int corner, int tapX, int tapY) {
        return (term(Term.TAP_X, corner, cls) * tapX + term(Term.TAP_Y, corner, cls) * tapY) * routeScale[corner];
    }

    /**
     * Adds a delay tap table: the nodes whose base wire name matches a regular expression add, for the
     * delay index of the PIP leaving them, the delay of that tap.
     * @param wireRegex The regular expression (found in the wire name), e.g. ^CLK_LEAF_SITES_\d+_CLK_IN$.
     * @param delays [corner ordinal][tap] in ps, at the parameters' speed grade (tap 0 is normally 0).
     */
    void addDelayTaps(String wireRegex, float[][] delays) {
        float[][] d = new float[NUM_CORNERS][];
        for (int c = 0; c < NUM_CORNERS; c++) {
            d[c] = delays[c] == null ? new float[0] : delays[c].clone();
        }
        tapPatterns.add(Pattern.compile(wireRegex));
        tapDelays.add(d);
    }

    /** @return The number of delay tap tables. */
    int getNumDelayTapTables() {
        return tapPatterns.size();
    }

    /**
     * Gets the delay tap table of a wire.
     * @param wireName A node's base wire name.
     * @return The index of the first table whose regular expression it matches, -1 if none.
     */
    int getDelayTapTable(String wireName) {
        for (int i = 0; i < tapPatterns.size(); i++) {
            if (tapPatterns.get(i).matcher(wireName).find()) {
                return i;
            }
        }
        return -1;
    }

    /**
     * Gets the delay of a programmable delay tap.
     * @param table The table (see {@link #getDelayTapTable(String)}), -1 for none.
     * @param corner The corner ordinal.
     * @param index The delay index of the PIP leaving the node.
     * @return The delay in ps, scaled (0 for no table or an index out of the table).
     */
    float getDelayTapDelay(int table, int corner, int index) {
        if (table < 0 || index <= 0) {
            return 0f;
        }
        float[] d = tapDelays.get(table)[corner];
        return index < d.length ? d[index] * routeScale[corner] : 0f;
    }

    private float term(Term t, int corner, int cls) {
        return classTerms[t.ordinal()][corner][cls];
    }

    /** Whether the delay of a class depends on the span of its nodes at any corner. */
    boolean dependsOnSpan(int cls) {
        for (int c = 0; c < NUM_CORNERS; c++) {
            if (term(Term.LENGTH, c, cls) != 0 || term(Term.CR_COL, c, cls) != 0 || term(Term.CR_ROW, c, cls) != 0
                    || term(Term.SLR, c, cls) != 0) {
                return true;
            }
        }
        return false;
    }

    /** Whether a class has a tap term at any corner. */
    boolean dependsOnLeave(int cls) {
        for (int c = 0; c < NUM_CORNERS; c++) {
            if (term(Term.TAP_X, c, cls) != 0 || term(Term.TAP_Y, c, cls) != 0) {
                return true;
            }
        }
        return false;
    }

    // ------------------------------------------------------------------------------------------
    // Buffers and clock generators
    // ------------------------------------------------------------------------------------------

    /**
     * Sets the delay of a buffer cell, for when the cell arc library has none.
     * @param key {@code <cellType>:<in>-><out>} (e.g. BUFGCE:I->O) or {@code <cellType>} for any arc.
     * @param delays Per corner ordinal.
     */
    void setBufferDelay(String key, float[] delays) {
        bufferDelay.put(key, delays.clone());
    }

    /** @return The delays of a buffer cell arc per corner (else of the cell type), null if unknown. */
    float[] getBufferDelay(String cellType, String inPin, String outPin) {
        float[] d = bufferDelay.get(cellType + ":" + inPin + "->" + outPin);
        return d != null ? d : bufferDelay.get(cellType);
    }

    /**
     * Gets the kind of a clock generator cell type, which keys its compensation data: the longest of
     * X5PLL, XPLL, DPLL, MMCM and PLL prefixing it, else the type itself.
     */
    static String getGeneratorKind(String cellType) {
        if (cellType.startsWith("X5PLL")) return "X5PLL";
        if (cellType.startsWith("XPLL")) return "XPLL";
        if (cellType.startsWith("DPLL")) return "DPLL";
        if (cellType.startsWith("MMCM")) return "MMCM";
        if (cellType.startsWith("PLL")) return "PLL";
        return cellType;
    }

    /**
     * Sets the CLKIN to CLKOUT compensation delay of clock generators.
     * @param key {@code <kind>:<COMPENSATION>} (e.g. MMCM:ZHOLD, XPLL:NONE), {@code <cellType>:<COMPENSATION>},
     *            {@code <cellType>} or {@code DEFAULT}.
     * @param delays Per corner ordinal.
     */
    void setGeneratorDelay(String key, float[] delays) {
        generatorDelay.put(key, delays.clone());
    }

    /**
     * Gets the CLKIN to CLKOUT compensation delay of a clock generator: that of
     * {@code <cellType>:<comp>}, {@code <kind>:<comp>}, {@code <kind>:INTERNAL}, {@code <cellType>},
     * {@code DEFAULT}, else 0 (an X5PLL without data of its own takes the XPLL's).
     * @param cellType e.g. MMCME4_ADV.
     * @param compensation The value of its COMPENSATION attribute, or null (NONE).
     * @return The delays per corner.
     */
    float[] getGeneratorDelay(String cellType, String compensation) {
        return getGeneratorDelay(cellType, compensation, null, null);
    }

    /**
     * Gets the CLKIN to CLKOUT compensation delay of an output of a clock generator: that of
     * {@code <kind>:<comp>:<outPin>:<phyDivide>} (CLKOUTPHY outputs), {@code <kind>:<comp>:<outPin>}, else
     * as {@link #getGeneratorDelay(String, String)}.
     * @param cellType e.g. XPLL.
     * @param compensation The value of its COMPENSATION attribute, or null (NONE).
     * @param outPin The output pin (e.g. CLKOUT1, CLKOUTPHY_90), or null.
     * @param phyDivide The CLKOUTPHY_DIVIDE of a CLKOUTPHY output (e.g. DIV2), or null.
     * @return The delays per corner.
     */
    float[] getGeneratorDelay(String cellType, String compensation, String outPin, String phyDivide) {
        String comp = compensation == null ? "NONE" : compensation;
        String kind = getGeneratorKind(cellType);
        List<String> kinds = kind.equals("X5PLL") ? Arrays.asList(kind, "XPLL") : Collections.singletonList(kind);
        for (String k : kinds) {
            List<String> keys = new ArrayList<>();
            if (outPin != null) {
                if (phyDivide != null) {
                    keys.add(k + ":" + comp + ":" + outPin + ":" + phyDivide);
                }
                keys.add(k + ":" + comp + ":" + outPin);
            }
            keys.addAll(Arrays.asList(cellType + ":" + comp, k + ":" + comp, k + ":INTERNAL"));
            for (String key : keys) {
                float[] d = generatorDelay.get(key);
                if (d != null) {
                    return d;
                }
            }
        }
        for (String key : new String[]{cellType, "DEFAULT"}) {
            float[] d = generatorDelay.get(key);
            if (d != null) {
                return d;
            }
        }
        return new float[NUM_CORNERS];
    }

    /**
     * @return Whether the data has a compensation delay for a clock generator: one of the keys of
     * {@link #getGeneratorDelay(String, String)} other than {@code DEFAULT}.
     */
    boolean hasGeneratorDelay(String cellType, String compensation) {
        String comp = compensation == null ? "NONE" : compensation;
        String kind = getGeneratorKind(cellType);
        for (String k : kind.equals("X5PLL") ? new String[]{kind, "XPLL"} : new String[]{kind}) {
            for (String key : new String[]{cellType + ":" + comp, k + ":" + comp, k + ":INTERNAL"}) {
                if (generatorDelay.containsKey(key)) {
                    return true;
                }
            }
        }
        return generatorDelay.containsKey(cellType);
    }

    /**
     * @return Whether the data has a compensation delay of its own for an output of a clock generator
     * ({@code <kind>:<comp>:<outPin>[:<phyDivide>]}, see {@link #getGeneratorDelay(String, String, String, String)}).
     */
    boolean hasGeneratorOutputDelay(String cellType, String compensation, String outPin, String phyDivide) {
        String comp = compensation == null ? "NONE" : compensation;
        String kind = getGeneratorKind(cellType);
        for (String k : kind.equals("X5PLL") ? new String[]{kind, "XPLL"} : new String[]{kind}) {
            if (phyDivide != null && generatorDelay.containsKey(k + ":" + comp + ":" + outPin + ":" + phyDivide)
                    || generatorDelay.containsKey(k + ":" + comp + ":" + outPin)) {
                return true;
            }
        }
        return false;
    }

    // ------------------------------------------------------------------------------------------
    // Site taps, load pin deltas, dedicated routes and input buffers
    // ------------------------------------------------------------------------------------------

    /**
     * Sets the delays of the taps of a site's programmable clock delay.
     * @param key {@code <BEL>.<attribute>}, digits of the attribute written N (e.g.
     *            IRI_IMR_CLK_MOD.CLK_DLY_VAL_COE).
     * @param delays [corner ordinal][tap], in ps, at the parameters' speed grade.
     */
    void setSiteTaps(String key, float[][] delays) {
        siteTaps.put(key, new float[][]{delays[0].clone(), delays[1].clone(), delays[2].clone(),
            delays[3].clone()});
    }

    /** @return The keys of the site taps ({@code <BEL>.<attribute>}). */
    java.util.Set<String> getSiteTapKeys() {
        return Collections.unmodifiableSet(siteTaps.keySet());
    }

    /** @return The delay of a tap of a site's programmable clock delay at a corner, 0 if unknown. */
    float getSiteTapDelay(String key, int corner, int tap) {
        float[][] d = siteTaps.get(key);
        if (d == null || tap < 0 || tap >= d[corner].length) {
            return 0f;
        }
        return d[corner][tap];
    }

    /**
     * Sets the route correction of the loads on a site pin.
     * @param key {@code <siteType>.<sitePin>}, e.g. RAMB36.CLKARDCLKL.
     * @param delays Per corner ordinal.
     */
    void setLoadPinDelta(String key, float[] delays) {
        loadPinDelta.put(key, delays.clone());
    }

    /** @return The route correction of the loads on a site pin per corner, null if none. */
    float[] getLoadPinDelta(String key) {
        return loadPinDelta.get(key);
    }

    /** @return The keys ({@code <siteType>.<sitePin>}) of the site pins with a route correction. */
    Set<String> getLoadPinKeys() {
        return Collections.unmodifiableSet(loadPinDelta.keySet());
    }

    /**
     * Sets the route delay of a dedicated clock input connection (at the reference grade).
     * @param key {@code <srcSiteType>.<srcSitePin>-><loadSiteType>.<loadSitePin>}.
     * @param delays Per corner ordinal.
     */
    void setDedicatedRoute(String key, float[] delays) {
        dedicatedRoutes.put(key, delays.clone());
    }

    /** @return The route delays of a dedicated connection per corner (scaled with the route), null if none. */
    float[] getDedicatedRoute(String key) {
        float[] d = dedicatedRoutes.get(key);
        if (d == null) {
            return null;
        }
        float[] out = new float[NUM_CORNERS];
        for (int c = 0; c < NUM_CORNERS; c++) {
            out[c] = d[c] * routeScale[c];
        }
        return out;
    }

    boolean hasDedicatedRoutes() {
        return !dedicatedRoutes.isEmpty() || !zeroDelayConnections.isEmpty() || !deviceRoutes.isEmpty();
    }

    /**
     * Adds the dedicated routes of the devices whose name a regular expression finds (checked before the
     * other dedicated routes, the first matching expression only).
     * @param deviceRegex The regular expression of the device names (e.g. ^xc2v[em]3558$).
     * @param routes By {@code <srcSiteType>.<srcSitePin>-><loadSiteType>.<loadSitePin>@<loadSiteName>}, the delays
     *               per corner ordinal (at the reference grade).
     */
    void addDeviceDedicatedRoutes(String deviceRegex, Map<String, float[]> routes) {
        deviceRoutePatterns.add(Pattern.compile(deviceRegex));
        Map<String, float[]> m = new HashMap<>();
        for (Map.Entry<String, float[]> e : routes.entrySet()) {
            m.put(e.getKey(), e.getValue().clone());
        }
        deviceRoutes.add(m);
    }

    /**
     * @return The route delays per corner (scaled with the route) of a dedicated connection of a device, keyed
     * {@code <src>-><load>@<loadSiteName>}, from the first device expression finding the device name; null if none.
     */
    float[] getDeviceDedicatedRoute(String deviceName, String key) {
        for (int i = 0; i < deviceRoutePatterns.size(); i++) {
            if (!deviceRoutePatterns.get(i).matcher(deviceName).find()) continue;
            float[] d = deviceRoutes.get(i).get(key);
            if (d == null) {
                return null;
            }
            float[] out = new float[NUM_CORNERS];
            for (int c = 0; c < NUM_CORNERS; c++) {
                out[c] = d[c] * routeScale[c];
            }
            return out;
        }
        return null;
    }

    /**
     * Sets the delay of a clock connection from a top-level port to a clock buffer input without a routed
     * net (out-of-context designs), at the parameters' speed grade.
     */
    void setUnroutedInput(float[] delays) {
        unroutedInput = delays == null ? null : delays.clone();
    }

    /** @return The delay of an unrouted port to clock buffer input connection per corner, null if unknown. */
    float[] getUnroutedInput() {
        return unroutedInput;
    }

    /**
     * Adds a zero-delay connection: the connections from a source site type and pin to a load site type
     * that all three regular expressions find cost 0.
     */
    void addZeroDelayConnection(String sourceSiteType, String sourcePin, String loadSiteType) {
        zeroDelayConnections.add(new Pattern[]{Pattern.compile(sourceSiteType), Pattern.compile(sourcePin),
            Pattern.compile(loadSiteType)});
    }

    /** @return Whether a connection is a zero-delay connection. */
    boolean isZeroDelayConnection(String sourceSiteType, String sourcePin, String loadSiteType) {
        for (Pattern[] p : zeroDelayConnections) {
            if (p[0].matcher(sourceSiteType).find() && p[1].matcher(sourcePin).find()
                    && p[2].matcher(loadSiteType).find()) {
                return true;
            }
        }
        return false;
    }

    /**
     * Sets the pad to output arc of an input buffer, without the package pin's delay.
     * @param key {@code <HPIOB|HDIOB>:INBUF[:<IOSTANDARD>]} or {@code HPIOBDIFFINBUF:DIFFINBUF}.
     * @param delays Per corner ordinal.
     */
    void setInputBuffer(String key, float[] delays) {
        inputBufferBase.put(key, delays.clone());
    }

    /** @return The pad to output arc of an input buffer per corner (without the package delay), null if none. */
    float[] getInputBuffer(String key) {
        return inputBufferBase.get(key);
    }

    boolean hasInputBuffers() {
        return !inputBufferBase.isEmpty();
    }

    /** Sets the port to pad net delay of an input buffer cell type (INBUF, DIFFINBUF). */
    void setInputBufferPortNet(String cellType, float[] delays) {
        inputBufferPortNet.put(cellType, delays.clone());
    }

    /** @return The port to pad net delay of an input buffer cell type per corner, null if none. */
    float[] getInputBufferPortNet(String cellType) {
        return inputBufferPortNet.get(cellType);
    }

    // ------------------------------------------------------------------------------------------
    // Leaf configuration and clock modifier
    // ------------------------------------------------------------------------------------------

    /**
     * Sets the delay from a load site pin to a cell pin.
     * @param key {@code <siteType>.<sitePin>|<cellLogicalPin>}, e.g. SLICEL.CLK1|C.
     * @param delays Per corner ordinal.
     */
    void setLeafConfig(String key, float[] delays) {
        leafConfig.put(key, delays.clone());
    }

    /** @return Whether the leaf configuration has a key. */
    boolean hasLeafConfig(String key) {
        return leafConfig.containsKey(key);
    }

    /** @return The delay from a load site pin to a cell pin at a corner (0 if unknown). */
    float getLeafDelay(String key, int corner) {
        float[] d = leafConfig.get(key);
        return d == null ? 0f : d[corner];
    }

    /**
     * Sets the delays of the taps of the CLE clock modifier (Versal programmable clock delay).
     * @param delays [corner ordinal][tap], in ps, at the parameters' speed grade.
     */
    void setClockModifierDelays(float[][] delays) {
        modifierDelays = delays == null ? null : new float[][]{delays[0].clone(), delays[1].clone(),
            delays[2].clone(), delays[3].clone()};
    }

    /**
     * Sets the delays of the taps of the CLE clock modifier in the sites of a type.
     * @param siteType The site type, e.g. SLICEM.
     * @param delays [corner ordinal][tap], in ps, at the parameters' speed grade.
     */
    void setClockModifierDelays(String siteType, float[][] delays) {
        modifierDelaysBySiteType.put(siteType, new float[][]{delays[0].clone(), delays[1].clone(),
            delays[2].clone(), delays[3].clone()});
    }

    boolean hasClockModifier() {
        return modifierDelays != null || !modifierDelaysBySiteType.isEmpty();
    }

    /**
     * @return The delay of a tap of the clock modifier in a site of a type at a corner: that of the site type
     * if set, else the default; 0 if unknown.
     */
    float getClockModifierDelay(String siteType, int corner, int tap) {
        float[][] d = siteType == null ? null : modifierDelaysBySiteType.get(siteType);
        if (d == null) {
            return getClockModifierDelay(corner, tap);
        }
        return tap < 0 || tap >= d[corner].length ? 0f : d[corner][tap];
    }

    /** @return The delay of a tap of the clock modifier at a corner, 0 if unknown. */
    float getClockModifierDelay(int corner, int tap) {
        if (modifierDelays == null || tap < 0 || tap >= modifierDelays[corner].length) {
            return 0f;
        }
        return modifierDelays[corner][tap];
    }

    // ------------------------------------------------------------------------------------------
    // Unrouted clocks
    // ------------------------------------------------------------------------------------------

    /**
     * Sets the estimate of unrouted clock nets at a corner (clock data "unrouted": a, av, ah, abuf, aslr).
     * @param corner The corner.
     * @param a Constant term.
     * @param av Per clock-region row from the clock root.
     * @param ah Per clock-region column from the clock root.
     * @param abuf Per clock region (columns + rows) from the buffer to the clock root.
     * @param aslr Per SLR crossed from the clock root.
     */
    void setUnrouted(Corner corner, float a, float av, float ah, float abuf, float aslr) {
        unrouted[corner.ordinal()] = new float[]{a, av, ah, abuf, aslr};
    }

    /**
     * Sets the estimate of unrouted clock nets at a corner when the clock root is the load-median clock
     * region (nets without a CLOCK_ROOT / USER_CLOCK_ROOT), as {@link #setUnrouted}.
     */
    void setUnroutedLoadMedianRoot(Corner corner, float a, float av, float ah, float abuf, float aslr) {
        unroutedMedian[corner.ordinal()] = new float[]{a, av, ah, abuf, aslr};
        hasUnroutedMedian = true;
    }

    /**
     * Gets the estimate of an unrouted clock net from its buffer output to a load:
     * {@code a + av*|dCRrow| + ah*|dCRcol| + abuf*(buffer-to-root clock region distance) + aslr*|dSLR|}.
     * @param corner The corner ordinal.
     * @param dCrX Clock-region columns from the root to the load.
     * @param dCrY Clock-region rows from the root to the load.
     * @param dSlr SLRs from the root to the load.
     * @param bufToRoot Clock regions (columns + rows) from the buffer to the root.
     * @param loadMedianRoot Whether the root is the load-median clock region (no root property): the
     *                       fit of such nets is used if the data has one.
     * @return The delay in ps.
     */
    float getEstimateDelay(int corner, int dCrX, int dCrY, int dSlr, int bufToRoot, boolean loadMedianRoot) {
        float[] u = loadMedianRoot && hasUnroutedMedian ? unroutedMedian[corner] : unrouted[corner];
        return u[0] + u[1] * Math.abs(dCrY) + u[2] * Math.abs(dCrX) + u[3] * Math.abs(bufToRoot)
                + u[4] * Math.abs(dSlr);
    }

    /** @return The estimate's delay from the clock root to the loads of a clock region. */
    float getEstimateRegionDelay(int corner, int dCrX, int dCrY, int dSlr) {
        float[] u = unrouted[corner];
        return u[0] + u[1] * Math.abs(dCrY) + u[2] * Math.abs(dCrX) + u[4] * Math.abs(dSlr);
    }

    void setDataNodeDelay(DataNodeDelay d) {
        dataDelay = d;
    }

    DataNodeDelay getDataNodeDelay() {
        return dataDelay;
    }

    /**
     * Sets where the common clock delay (CCD) of the inter-SLR compensation ends (see
     * {@link ClockModel#getInterSlrCompensation}): at the start of the clock paths' last common node
     * (the arrival at its driver, e.g. UltraScale+, whose node classes put a clock buffer's delay on
     * the node it drives), or at its end (e.g. Versal).
     * @param atLcaStart True for the start of the last common node, false for its end (the default).
     */
    void setInterSlrCommonAtLcaStart(boolean atLcaStart) {
        interSlrCommonAtLcaStart = atLcaStart;
    }

    /** @return True if the common clock delay of the inter-SLR compensation ends at the start of the last
     * common node, see {@link #setInterSlrCommonAtLcaStart(boolean)}. */
    boolean isInterSlrCommonAtLcaStart() {
        return interSlrCommonAtLcaStart;
    }

    /**
     * Sets the node classes whose CRPR credit, when two clock paths branch at a routing node of the class, ends at
     * the start of that node (the clock arrival at the node driving it; e.g. the UltraScale+ horizontal
     * distribution, whose class puts the delay of the buffer driving it on the node) instead of its end (the other
     * classes). The routing node of a site pin that both clock pins are on is always credited whole.
     * @param classes The node class names (see {@link ClockNodeClassifier}).
     */
    void setCrprBranchStartClasses(Collection<String> classes) {
        crprBranchStartClasses.clear();
        crprBranchStartClasses.addAll(classes);
    }

    /** @return The node classes credited at their start by the CRPR, see
     * {@link #setCrprBranchStartClasses(Collection)}. */
    Set<String> getCrprBranchStartClasses() {
        return Collections.unmodifiableSet(crprBranchStartClasses);
    }
}

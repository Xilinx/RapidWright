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

import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Pattern;

/**
 * The fitted timing arcs of the cells of a family, at four corners: the logic delays and timing
 * checks between the pins of cells, and the configuration delays between site pins and BEL pins.
 * Delays are in ps at the library's reference speed grade ({@link #getSpeedRef()}), in the corner
 * order of the data: SLOW_MIN, SLOW_MAX, FAST_MIN, FAST_MAX (see {@link #getDataIndex(Corner)}).
 * <p>
 * Arcs are found by {@link #lookup}:
 * <ul>
 * <li>Arcs are keyed by cell type, role and from and to pin. Cells placed in SLICEL and SLICEM sites
 * use physical BEL pin names; other cells use logical pin names without bus index. The arcs of LUT1
 * to LUT6 cells on LUT BELs are stored under the cell type null (they depend on the BEL and physical
 * pins only); slice cells without arcs of their own fall back on them.</li>
 * <li>Among the arcs of a key, those of the cell's BEL and site type are preferred, then those of its
 * BEL, then those of its site type, then those for any BEL and site.</li>
 * <li>Among those, the arc whose configuration (cell and macro properties, and the pseudo
 * properties {@code @from} and {@code @to}, the logical pin base names of the arc's pins) matches
 * best wins: the one with the smallest mismatch weight (4 for register properties, whose name ends
 * in REG or REG_&lt;letter&gt;, 1 otherwise; unset properties have the value {@value #NONE}), then
 * the one with the most properties, then the first.</li>
 * <li>Gate properties (register and mode properties such as AREG, A_INPUT, CREG, AMULTSEL or
 * LOOKB of the arc's own group, found from Vivado's timing arcs of characterized designs) decide
 * whether an arc exists: arcs whose gate properties do not all match the cell's are skipped. If none
 * remains, the result is
 * {@link Status#GATED} without an arc only when the characterized designs had the cell's gate values
 * without the arc; for other (unseen or unset) values the nearest arc is kept.</li>
 * <li>Bus arcs may have per-bit values, by the bit of their to or from pin, or by both.</li>
 * </ul>
 * Speed grade factors (per corner, category, check kind, cell type and arc, and per configuration
 * delay) and device factors scale the delays, see {@link #scale} and {@link #scaleConfig}.
 */
public class FittedCellArcLibrary {

    /** Roles of timing arcs */
    public enum Role {
        COMB, CLK_TO_Q, SETUP, HOLD, RECOVERY, REMOVAL, SET_CLR;

        private static final Role[] VALUES = values();

        /** @return True for the setup-like checks, SETUP and RECOVERY. */
        public boolean isSetupCheck() {
            return this == SETUP || this == RECOVERY;
        }

        /** @return True for the hold-like checks, HOLD and REMOVAL. */
        public boolean isHoldCheck() {
            return this == HOLD || this == REMOVAL;
        }

        /** @return True for timing checks: SETUP, HOLD, RECOVERY and REMOVAL. */
        public boolean isCheck() {
            return isSetupCheck() || isHoldCheck();
        }
    }

    /** How an arc was found */
    public enum Status {
        /** An arc of the cell type whose configuration matches */
        EXACT,
        /** An arc of the cell type with the nearest configuration */
        NEAREST,
        /** An arc of the cell type for another BEL or site type */
        OTHER_BEL,
        /** An arc of the LUT wildcard, for a slice cell without arcs of its own */
        LUT_WILDCARD,
        /** The arc does not exist in the cell's configuration (no arc matches its gate properties) */
        GATED,
        /** No arc */
        MISSING
    }

    /** How a configuration delay was found */
    public enum ConfigStatus {
        EXACT, NO_VIA, DEFAULT, MISSING
    }

    /** Value of unset properties */
    public static final String NONE = "<none>";

    private static final Pattern LUT_RE = Pattern.compile("^LUT[1-6](_2)?$");
    private static final Pattern REG_ATTR_RE = Pattern.compile("REG(_[A-Z])?$");
    private static final Pattern IO_TYPE_RE = Pattern.compile("^(I|O)BUF|^INBUF|^OUTBUF|^IBUFCTRL|^DIFF|^OBUFT"
            + "|^IOBUF|^ISERDES|^OSERDES|^IDELAY|^ODELAY|^IDDR|^ODDR|^XPIO|^XPHY|^BITSLICE|^RXTX|^TX_BITSLICE"
            + "|^RX_BITSLICE|^HPIO|^IOB|^IBUFDS|^OBUFDS|^PULL|^KEEPER");
    private static final int MAGIC = 0x41524333;
    private static final int NUM_CORNERS = 4;

    /**
     * The mismatch weight of a register property (whose name ends in REG or REG_&lt;letter&gt;): a {@link Result}
     * whose {@link Result#getMismatch()} is at least this differs from the cell's configuration in a register
     * property, so its arc is not that of the cell's configuration (e.g. the combinational arc of a registered
     * DSP input).
     */
    public static final int REGISTER_MISMATCH = 4;

    /** A timing arc */
    public static final class Arc {
        final String cell;
        final String bel;
        final String site;
        final Role role;
        final String from;
        final String to;
        final String[] cfgKeys;
        final String[] cfgValues;
        final int[] cfgWeights;
        /** Whether a configuration key gates the arc's existence (see {@link #lookup}) */
        final boolean[] cfgGate;
        /** The configuration indices of the gate keys, in gate order (empty if the arc is not gated) */
        int[] gateIndex = new int[0];
        /** The gate key values the characterized designs had without the arc (each in gate order) */
        String[][] absent = new String[0][];
        final float[] d;
        /** 0: no bits, 1: by to bit, 2: by from bit, 3: by pair */
        final int bitsOf;
        final Map<Long, float[]> bits;
        /** The key of the speed grade factors of the arc, see {@link #getArcScaleKey} */
        final String scaleKey;

        Arc(String cell, String bel, String site, Role role, String from, String to, String[] cfgKeys,
            String[] cfgValues, float[] d, int bitsOf, Map<Long, float[]> bits) {
            this.cell = cell;
            this.bel = bel;
            this.site = site;
            this.role = role;
            this.from = from;
            this.to = to;
            this.cfgKeys = cfgKeys;
            this.cfgValues = cfgValues;
            this.cfgWeights = new int[cfgKeys.length];
            this.cfgGate = new boolean[cfgKeys.length];
            for (int i = 0; i < cfgKeys.length; i++) {
                cfgWeights[i] = isRegisterAttribute(cfgKeys[i]) ? REGISTER_MISMATCH : 1;
            }
            this.d = d;
            this.bitsOf = bitsOf;
            this.bits = bits;
            this.scaleKey = (cell == null ? "*" : cell) + ":" + from + ":" + to;
        }

        public String getCellType() {
            return cell;
        }

        public String getBEL() {
            return bel;
        }

        public String getSiteType() {
            return site;
        }

        public Role getRole() {
            return role;
        }

        public String getFrom() {
            return from;
        }

        public String getTo() {
            return to;
        }

        /** @return Whether the arc has per-bit values. */
        public boolean hasBits() {
            return bitsOf != 0;
        }

        /** @return Whether its values depend on the bit of its from pin. */
        public boolean dependsOnFromBit() {
            return bitsOf == 2 || bitsOf == 3;
        }

        /** @return Whether its values depend on the bit of its to pin. */
        public boolean dependsOnToBit() {
            return bitsOf == 1 || bitsOf == 3;
        }

        /**
         * Gets the delays of a pair of bits.
         * @param fromBit The bus bit of the from pin, -1 for a scalar pin.
         * @param toBit The bus bit of the to pin, -1 for a scalar pin.
         * @return The delays in data corner order; not to be modified.
         */
        public float[] getDelays(int fromBit, int toBit) {
            if (bits == null) {
                return d;
            }
            float[] v;
            if (bitsOf == 1) {
                v = bits.get((long) toBit);
            } else if (bitsOf == 2) {
                v = bits.get((long) fromBit);
            } else {
                v = bits.get(pairKey(fromBit, toBit));
            }
            return v == null ? d : v;
        }
    }

    /** The key of the arcs of a cell type (null for the LUT wildcard) between two pins */
    public static final class ArcKey {
        final String cell;
        final Role role;
        final String from;
        final String to;
        private final int hash;

        ArcKey(String cell, Role role, String from, String to) {
            this.cell = cell;
            this.role = role;
            this.from = from;
            this.to = to;
            this.hash = Objects.hash(cell, role, from, to);
        }

        public String getCellType() {
            return cell;
        }

        public Role getRole() {
            return role;
        }

        public String getFrom() {
            return from;
        }

        public String getTo() {
            return to;
        }

        @Override
        public int hashCode() {
            return hash;
        }

        @Override
        public boolean equals(Object o) {
            if (!(o instanceof ArcKey)) return false;
            ArcKey k = (ArcKey) o;
            return k.role == role && Objects.equals(k.cell, cell) && k.from.equals(from) && k.to.equals(to);
        }
    }

    /** The result of a lookup */
    public static final class Result {
        private final Arc arc;
        private final float[] delays;
        private final Status status;
        private final int mismatch;

        Result(Arc arc, float[] delays, Status status, int mismatch) {
            this.arc = arc;
            this.delays = delays;
            this.status = status;
            this.mismatch = mismatch;
        }

        /** @return The arc found, or null if missing. */
        public Arc getArc() {
            return arc;
        }

        /** @return The delays in data corner order, or null if missing; not to be modified. */
        public float[] getDelays() {
            return delays;
        }

        public Status getStatus() {
            return status;
        }

        /**
         * @return The mismatch weight of the configuration of the arc found: 0 if it matches, at
         * least {@link FittedCellArcLibrary#REGISTER_MISMATCH} if a register property differs.
         */
        public int getMismatch() {
            return mismatch;
        }
    }

    private static final Result MISSING = new Result(null, null, Status.MISSING, 0);
    private static final Result GATED = new Result(null, null, Status.GATED, 0);

    private final String dataVersion;
    private final long dataChecksum;
    private final String speedRef;
    private final Map<ArcKey, List<Arc>> index = new HashMap<>();
    /** The keys of the arcs of each cell type (null for the LUT wildcard), in data order */
    private final Map<String, List<ArcKey>> keysByCell = new HashMap<>();
    /** The configuration properties of the arcs of each cell type, in data order */
    private final Map<String, Set<String>> configKeysByCell = new HashMap<>();
    /** Configuration delays by site type, from, to and via */
    private final Map<String, ConfigResult> config = new HashMap<>();
    private final Map<String, Map<String, float[]>> speedScales = new LinkedHashMap<>();
    private final Map<String, Map<String, float[]>> deviceScales = new LinkedHashMap<>();
    private int numArcs;

    private FittedCellArcLibrary(String dataVersion, long dataChecksum, String speedRef) {
        this.dataVersion = dataVersion;
        this.dataChecksum = dataChecksum;
        this.speedRef = speedRef;
    }

    /** Reads the integers of cell arc data */
    private static final class IntReader {
        private final int[] data;
        private int pos;

        IntReader(int[] data) {
            this.data = data;
        }

        boolean hasNext() {
            return pos < data.length;
        }

        int next() {
            if (pos >= data.length) {
                throw new IllegalStateException("Cell arc data ends early");
            }
            return data[pos++];
        }
    }

    /**
     * Decodes cell arc data, as the {@link TimingData#CELL_ARCS} section of a timing data file holds it: a
     * table of strings and integers. After a magic number, the integers hold the speed grade factors and the
     * device factors (in 1/10000), the arcs and the configuration delays (delays in tenths of a ps, at the
     * four corners in data order); strings are indices into the table, per-bit values are differences to the
     * values of their arc.
     * @param dataVersion Family, kind and version of the data.
     * @param dataChecksum The checksum of the data.
     * @param speedRef The speed grade of the delays.
     * @param strings The strings.
     * @param ints The integers.
     * @return The library.
     * @throws IllegalArgumentException If the integers are not cell arc data.
     */
    public static FittedCellArcLibrary decode(String dataVersion, long dataChecksum, String speedRef,
                                              String[] strings, int[] ints) {
        FittedCellArcLibrary lib = new FittedCellArcLibrary(dataVersion, dataChecksum, speedRef);
        IntReader r = new IntReader(ints);
        if (r.next() != MAGIC) {
            throw new IllegalArgumentException("Not cell arc data: " + dataVersion);
        }
        readScales(r, strings, lib.speedScales);
        readScales(r, strings, lib.deviceScales);
        int numArcs = r.next();
        lib.numArcs = numArcs;
        for (int i = 0; i < numArcs; i++) {
            String cell = optString(r, strings);
            String bel = optString(r, strings);
            String site = optString(r, strings);
            Role role = Role.VALUES[r.next()];
            String from = strings[r.next()];
            String to = strings[r.next()];
            int numCfg = r.next();
            String[] keys = new String[numCfg];
            String[] values = new String[numCfg];
            for (int k = 0; k < numCfg; k++) {
                keys[k] = strings[r.next()];
                values[k] = strings[r.next()];
            }
            int numGate = r.next();
            int[] gate = new int[numGate];
            for (int k = 0; k < numGate; k++) {
                gate[k] = r.next();
            }
            String[][] absent = new String[0][];
            if (numGate > 0) {
                absent = new String[r.next()][numGate];
                for (String[] t : absent) {
                    for (int k = 0; k < numGate; k++) {
                        t[k] = strings[r.next()];
                    }
                }
            }
            int[] dt = new int[NUM_CORNERS];
            float[] d = new float[NUM_CORNERS];
            for (int c = 0; c < NUM_CORNERS; c++) {
                dt[c] = r.next();
                d[c] = dt[c] / 10f;
            }
            int bitsOf = r.next();
            Map<Long, float[]> bits = null;
            if (bitsOf != 0) {
                int numRows = r.next();
                bits = new HashMap<>(numRows * 2);
                int fromBit = -1;
                int toBit = -1;
                for (int k = 0; k < numRows; k++) {
                    long key;
                    if (bitsOf == 3) {
                        fromBit += r.next();
                        toBit += r.next();
                        key = pairKey(fromBit, toBit);
                    } else {
                        fromBit += r.next();
                        key = fromBit;
                    }
                    float[] v = new float[NUM_CORNERS];
                    for (int c = 0; c < NUM_CORNERS; c++) {
                        v[c] = (dt[c] + r.next()) / 10f;
                    }
                    bits.put(key, v);
                }
            }
            Arc arc = new Arc(cell, bel, site, role, from, to, keys, values, d, bitsOf, bits);
            for (int g : gate) {
                arc.cfgGate[g] = true;
            }
            arc.gateIndex = gate;
            arc.absent = absent;
            ArcKey key = new ArcKey(cell, role, from, to);
            List<Arc> list = lib.index.get(key);
            if (list == null) {
                list = new ArrayList<>(1);
                lib.index.put(key, list);
                lib.keysByCell.computeIfAbsent(cell, k -> new ArrayList<>()).add(key);
            }
            list.add(arc);
            Set<String> cfgKeys = lib.configKeysByCell.computeIfAbsent(cell, k -> new LinkedHashSet<>());
            Collections.addAll(cfgKeys, keys);
        }
        int numConfig = r.next();
        for (int i = 0; i < numConfig; i++) {
            String site = strings[r.next()];
            String from = strings[r.next()];
            String to = strings[r.next()];
            String via = optString(r, strings);
            float[] d = new float[NUM_CORNERS];
            for (int c = 0; c < NUM_CORNERS; c++) {
                d[c] = r.next() / 10f;
            }
            lib.config.put(configKey(site, from, to, via == null ? "" : via),
                    new ConfigResult(d, ConfigStatus.EXACT, getConfigScaleKey(site, from, to)));
        }
        if (r.hasNext()) {
            throw new IllegalArgumentException("Cell arc data too long: " + dataVersion);
        }
        return lib;
    }

    private static String optString(IntReader r, String[] strings) {
        int i = r.next();
        return i == 0 ? null : strings[i - 1];
    }

    private static void readScales(IntReader r, String[] strings, Map<String, Map<String, float[]>> into) {
        int n = r.next();
        for (int i = 0; i < n; i++) {
            String name = strings[r.next()];
            int numKeys = r.next();
            Map<String, float[]> m = new HashMap<>();
            for (int k = 0; k < numKeys; k++) {
                String key = strings[r.next()];
                float[] f = new float[NUM_CORNERS];
                for (int c = 0; c < NUM_CORNERS; c++) {
                    f[c] = r.next() / 10000f;
                }
                m.put(key, f);
            }
            into.put(name, m);
        }
    }

    private static long pairKey(int fromBit, int toBit) {
        return ((long) fromBit << 32) | (toBit & 0xffffffffL);
    }

    private static String configKey(String site, String from, String to, String via) {
        return site + '\u0000' + from + '\u0000' + to + '\u0000' + via;
    }

    /**
     * Gets the position of a corner in the delay arrays of this library.
     * @param corner The corner.
     * @return The index: SLOW_MIN 0, SLOW_MAX 1, FAST_MIN 2, FAST_MAX 3.
     */
    public static int getDataIndex(Corner corner) {
        switch (corner) {
            case SLOW_MIN:
                return 0;
            case SLOW_MAX:
                return 1;
            case FAST_MIN:
                return 2;
            default:
                return 3;
        }
    }

    public String getDataVersion() {
        return dataVersion;
    }

    public long getDataChecksum() {
        return dataChecksum;
    }

    /** @return The speed grade the delays are for, without a leading dash. */
    public String getSpeedRef() {
        return speedRef;
    }

    /** @return The number of arcs. */
    public int getNumArcs() {
        return numArcs;
    }

    /** @return The number of configuration delays. */
    int getNumConfigDelays() {
        return config.size();
    }

    /**
     * Gets the keys of the arcs of a cell type.
     * @param cellType The cell type, or null for the LUT wildcard.
     * @return The keys, in data order; empty if none.
     */
    public List<ArcKey> getArcKeys(String cellType) {
        List<ArcKey> keys = keysByCell.get(cellType);
        return keys == null ? Collections.emptyList() : Collections.unmodifiableList(keys);
    }

    /**
     * Gets the configuration properties that the arcs of a cell type depend on.
     * @param cellType The cell type, or null for the LUT wildcard.
     * @return The property names, including the pseudo properties @from and @to.
     */
    public Set<String> getConfigKeys(String cellType) {
        Set<String> keys = configKeysByCell.get(cellType);
        return keys == null ? Collections.emptySet() : Collections.unmodifiableSet(keys);
    }

    /**
     * Writes this library as text, for debugging: a line per speed grade factor, device factor, arc and
     * configuration delay, sorted (arcs by cell type, then in the order of the data), with the delays in ps at
     * the reference speed grade and the factors in the corner order of the data (SLOW_MIN, SLOW_MAX, FAST_MIN,
     * FAST_MAX). The line of an arc has its cell type (* for the LUT wildcard), role, from and to pins, the
     * BEL and the site type it is for (* for any), its delays, its configuration (gate properties are
     * marked with !) and the gate values that were seen without the arc; the lines of its per-bit delays
     * follow it.
     * @param out Where to write.
     */
    public void writeText(PrintWriter out) {
        out.println("# " + dataVersion + ": " + numArcs + " arcs, " + config.size() + " configuration delays");
        out.println("# delays in ps at speed grade " + speedRef
                + "; delays and factors at SLOW_MIN SLOW_MAX FAST_MIN FAST_MAX");
        writeFactors(out, "speedFactor", speedScales);
        writeFactors(out, "deviceFactor", deviceScales);
        List<String> cells = new ArrayList<>(keysByCell.keySet());
        cells.sort(Comparator.nullsFirst(Comparator.naturalOrder()));
        StringBuilder sb = new StringBuilder();
        for (String cell : cells) {
            for (ArcKey key : keysByCell.get(cell)) {
                for (Arc arc : index.get(key)) {
                    sb.setLength(0);
                    sb.append("arc ").append(cell == null ? "*" : cell).append(' ').append(arc.role).append(' ')
                            .append(arc.from).append(' ').append(arc.to)
                            .append(" bel=").append(arc.bel == null ? "*" : arc.bel)
                            .append(" site=").append(arc.site == null ? "*" : arc.site);
                    appendValues(sb, arc.d);
                    for (int i = 0; i < arc.cfgKeys.length; i++) {
                        sb.append(i == 0 ? " cfg " : " ").append(arc.cfgGate[i] ? "!" : "").append(arc.cfgKeys[i])
                                .append('=').append(arc.cfgValues[i]);
                    }
                    for (int i = 0; i < arc.absent.length; i++) {
                        sb.append(i == 0 ? " absent " : " ").append(String.join(",", arc.absent[i]));
                    }
                    out.println(sb);
                    if (arc.bits == null) {
                        continue;
                    }
                    for (Map.Entry<Long, float[]> e : new TreeMap<>(arc.bits).entrySet()) {
                        long bit = e.getKey();
                        sb.setLength(0);
                        sb.append("  bit");
                        if (arc.bitsOf != 1) {
                            sb.append(" from=").append(arc.bitsOf == 3 ? (int) (bit >> 32) : (int) bit);
                        }
                        if (arc.bitsOf != 2) {
                            sb.append(" to=").append((int) bit);
                        }
                        appendValues(sb, e.getValue());
                        out.println(sb);
                    }
                }
            }
        }
        for (Map.Entry<String, ConfigResult> e : new TreeMap<>(config).entrySet()) {
            String[] k = e.getKey().split("\u0000", -1);
            sb.setLength(0);
            sb.append("config ").append(k[0]).append(' ').append(k[1]).append(' ').append(k[2]);
            if (!k[3].isEmpty()) {
                sb.append(" via=").append(k[3]);
            }
            appendValues(sb, e.getValue().delays);
            out.println(sb);
        }
    }

    private static void writeFactors(PrintWriter out, String kind, Map<String, Map<String, float[]>> factors) {
        StringBuilder sb = new StringBuilder();
        for (String name : new TreeSet<>(factors.keySet())) {
            for (Map.Entry<String, float[]> e : new TreeMap<>(factors.get(name)).entrySet()) {
                sb.setLength(0);
                sb.append(kind).append(' ').append(name).append(' ').append(e.getKey());
                appendValues(sb, e.getValue());
                out.println(sb);
            }
        }
    }

    private static void appendValues(StringBuilder sb, float[] values) {
        for (float v : values) {
            sb.append(' ');
            if (v == (int) v) {
                sb.append((int) v);
            } else {
                sb.append(v);
            }
        }
    }

    /**
     * Checks if a cell is a LUT on a LUT BEL, whose arcs are those of the LUT wildcard.
     * @param cellType The cell type.
     * @param bel The name of the BEL.
     * @return True for LUT1 to LUT6 (and LUT6_2) cells on BELs whose name ends in LUT.
     */
    public static boolean isLUTOnLUTBEL(String cellType, String bel) {
        return LUT_RE.matcher(cellType).matches() && bel != null && bel.endsWith("LUT");
    }

    /**
     * Checks if a property is a register property (DOA_REG, OREG_B, AREG, MREG, ...), whose
     * mismatch weighs {@link #REGISTER_MISMATCH} in the nearest configuration.
     */
    static boolean isRegisterAttribute(String name) {
        return REG_ATTR_RE.matcher(name).find();
    }

    private static boolean isSlice(String siteType) {
        return "SLICEL".equals(siteType) || "SLICEM".equals(siteType);
    }

    /**
     * Looks a timing arc up.
     * @param cellType The cell type, e.g. FDRE or DSP_ALU (the leaf type of a macro's cell).
     * @param bel The name of the BEL the cell is placed on.
     * @param site The site type the cell is placed in, e.g. SLICEL.
     * @param role The role of the arc.
     * @param fromPin The from pin: a physical BEL pin in slices, a logical pin base name otherwise.
     * @param fromBit The bus bit of the from pin, -1 for a scalar pin (always -1 in slices).
     * @param toPin The to pin, likewise.
     * @param toBit The bus bit of the to pin, likewise.
     * @param attrs The properties of the cell and of its macro instance; may be null.
     * @param logicalFrom For slice cells, the logical base name of the from pin (for @from), else null.
     * @param logicalTo For slice cells, the logical base name of the to pin (for @to), else null.
     * @return The result.
     */
    public Result lookup(String cellType, String bel, String site, Role role, String fromPin, int fromBit,
                         String toPin, int toBit, Map<String, String> attrs, String logicalFrom,
                         String logicalTo) {
        boolean lut = isLUTOnLUTBEL(cellType, bel);
        List<Arc> cands = index.get(new ArcKey(lut ? null : cellType, role, fromPin, toPin));
        Status status = Status.EXACT;
        if (cands == null && isSlice(site) && !lut) {
            cands = index.get(new ArcKey(null, role, fromPin, toPin));
            status = Status.LUT_WILDCARD;
        }
        if (cands == null) {
            return MISSING;
        }
        List<Arc> sel = select(cands, bel, site);
        if (sel.isEmpty()) {
            sel = cands;
            if (status == Status.EXACT) {
                status = Status.OTHER_BEL;
            }
        }
        boolean pseudo = logicalFrom != null && !logicalFrom.isEmpty() && !lut;
        Arc best = null;
        int bestMism = 0;
        int bestKeys = 0;
        Arc gatedArc = null;
        // entries whose gate values match the cell's (or that have no gate)
        for (Arc e : sel) {
            int mism = 0;
            boolean gateMiss = false;
            for (int i = 0; i < e.cfgKeys.length; i++) {
                if (!value(e.cfgKeys[i], attrs, pseudo, logicalFrom, logicalTo).equals(e.cfgValues[i])) {
                    if (e.cfgGate[i]) {
                        gateMiss = true;
                        break;
                    }
                    mism += e.cfgWeights[i];
                }
            }
            if (gateMiss) {
                gatedArc = e;
                continue;
            }
            if (best == null || mism < bestMism || (mism == bestMism && e.cfgKeys.length > bestKeys)) {
                best = e;
                bestMism = mism;
                bestKeys = e.cfgKeys.length;
            }
        }
        if (best == null) {
            if (gatedArc == null) {
                return MISSING;
            }
            // No entry for the cell's gate values: the arc is absent only if the characterized designs had exactly
            // these values without it; otherwise (unseen or missing values) the nearest entry is kept
            String[] mine = new String[gatedArc.gateIndex.length];
            for (int k = 0; k < mine.length; k++) {
                mine[k] = value(gatedArc.cfgKeys[gatedArc.gateIndex[k]], attrs, pseudo, logicalFrom, logicalTo);
            }
            for (String[] t : gatedArc.absent) {
                if (Arrays.equals(t, mine)) {
                    return GATED;
                }
            }
            for (Arc e : sel) {
                int mism = 0;
                for (int i = 0; i < e.cfgKeys.length; i++) {
                    if (!value(e.cfgKeys[i], attrs, pseudo, logicalFrom, logicalTo).equals(e.cfgValues[i])) {
                        mism += e.cfgWeights[i];
                    }
                }
                if (best == null || mism < bestMism || (mism == bestMism && e.cfgKeys.length > bestKeys)) {
                    best = e;
                    bestMism = mism;
                    bestKeys = e.cfgKeys.length;
                }
            }
            bestMism = Math.max(bestMism, 1);
        }
        if (bestMism > 0 && status == Status.EXACT) {
            status = Status.NEAREST;
        }
        return new Result(best, best.getDelays(fromBit, toBit), status, bestMism);
    }

    private static String value(String key, Map<String, String> attrs, boolean pseudo, String logicalFrom,
                                String logicalTo) {
        if (pseudo && key.equals("@from")) {
            return logicalFrom;
        }
        if (pseudo && key.equals("@to")) {
            return logicalTo;
        }
        String v = attrs == null ? null : attrs.get(key);
        return v == null ? NONE : v;
    }

    private static List<Arc> select(List<Arc> cands, String bel, String site) {
        for (int k = 0; k < 4; k++) {
            String b = k < 2 ? bel : null;
            String s = (k & 1) == 0 ? site : null;
            List<Arc> sel = null;
            for (Arc e : cands) {
                if (Objects.equals(e.bel, b) && Objects.equals(e.site, s)) {
                    if (sel == null) {
                        sel = new ArrayList<>(2);
                    }
                    sel.add(e);
                }
            }
            if (sel != null) {
                return sel;
            }
        }
        return Collections.emptyList();
    }

    /** The result of a configuration delay lookup */
    public static final class ConfigResult {
        private final float[] delays;
        private final ConfigStatus status;
        /** The key of the speed grade factors of the entry found, null if none, see {@link #getConfigScaleKey} */
        private final String scaleKey;

        ConfigResult(float[] delays, ConfigStatus status, String scaleKey) {
            this.delays = delays;
            this.status = status;
            this.scaleKey = scaleKey;
        }

        /** @return The delays in data corner order, or null if missing; not to be modified. */
        public float[] getDelays() {
            return delays;
        }

        public ConfigStatus getStatus() {
            return status;
        }
    }

    private static final ConfigResult CONFIG_MISSING = new ConfigResult(null, ConfigStatus.MISSING, null);

    /**
     * Looks a configuration delay up: from a BEL pin ("BEL/pin") to a site pin, from a site pin to a
     * BEL pin, or between two BEL pins of a net that stays in its site. Outside slices, pin names drop
     * their bus indices (see {@link #normalizeConfigPin}). The exact route-through BELs are tried, then
     * the entry without them, then the default of the site pin.
     * @param site The site type.
     * @param from The from pin.
     * @param to The to pin.
     * @param via The route-through BELs between them, '&gt;'-joined, or "" for none.
     * @return The result.
     */
    public ConfigResult config(String site, String from, String to, String via) {
        ConfigResult r = config.get(configKey(site, from, to, via));
        if (r != null) {
            return r;
        }
        r = config.get(configKey(site, from, to, ""));
        if (r != null) {
            return new ConfigResult(r.delays, ConfigStatus.NO_VIA, r.scaleKey);
        }
        if (from.indexOf('/') >= 0) {
            r = config.get(configKey(site, "*", to, ""));
        } else {
            r = config.get(configKey(site, from, "*", ""));
        }
        if (r != null) {
            return new ConfigResult(r.delays, ConfigStatus.DEFAULT, r.scaleKey);
        }
        return CONFIG_MISSING;
    }

    /**
     * Gets the key of the speed grade factors of a configuration delay: config, its site type, from pin
     * and to pin, separated by colons, e.g. config:SLICEL:A6LUT/O6:CARRY8/S0. Entries with route-through
     * BELs share the key of the entry without them.
     * @param site The site type.
     * @param from The from pin of the entry.
     * @param to The to pin of the entry.
     * @return The key.
     */
    static String getConfigScaleKey(String site, String from, String to) {
        return "config:" + site + ':' + from + ':' + to;
    }

    /**
     * Removes the bus index of a pin name: trailing digits, '[', ']' and '_', e.g. DOUTADOUT12 or A[3] become
     * DOUTADOUT and A.
     */
    static String stripBus(String pin) {
        int end = pin.length();
        while (end > 0) {
            char c = pin.charAt(end - 1);
            if (Character.isDigit(c) || c == '[' || c == ']' || c == '_') {
                end--;
            } else {
                break;
            }
        }
        return end == 0 ? pin : pin.substring(0, end);
    }

    /**
     * Gets the name of a pin in configuration delay keys: unchanged in slices, else without bus index.
     * @param site The site type.
     * @param pin A site pin name or a BEL pin as "BEL/pin".
     * @return The name.
     */
    public static String normalizeConfigPin(String site, String pin) {
        if (isSlice(site)) {
            return pin;
        }
        int slash = pin.indexOf('/');
        if (slash >= 0) {
            return pin.substring(0, slash + 1) + stripBus(pin.substring(slash + 1));
        }
        return stripBus(pin);
    }

    /**
     * Gets the speed grade and reporting category of a cell type: lut, carry, ff, dsp, bram, clock, io
     * or other.
     * @param cellType The cell type.
     * @param site The site type, or null.
     * @return The category.
     */
    public static String getCategory(String cellType, String site) {
        String t = cellType;
        if (LUT_RE.matcher(t).matches()) return "lut";
        if (startsWith(t, "CARRY", "LOOKAHEAD", "LUTCY")) return "carry";
        if (startsWith(t, "FD", "LD", "AND2B1L", "OR2L")) return "ff";
        if (startsWith(t, "SRL", "RAMD", "RAMS", "MUXF", "LUT")) return "lut";
        if (t.startsWith("DSP")) return "dsp";
        if (startsWith(t, "RAMB", "FIFO", "URAM")) return "bram";
        if (startsWith(t, "BUFG", "BUFCE", "BUFGCE", "MMCM", "PLL", "XPLL", "DPLL", "MBUFG", "BUFDIV", "CLKOUT")) {
            return "clock";
        }
        String s = site == null ? "" : site;
        if (IO_TYPE_RE.matcher(t).find()
                || startsWith(s, "IOB", "HPIOB", "HDIOB", "XPIOB", "BITSLICE", "XPHY", "HPIOBDIFF", "HDIOBDIFF", "XPIO")) {
            return "io";
        }
        return "other";
    }

    private static boolean startsWith(String s, String... prefixes) {
        for (String p : prefixes) {
            if (s.startsWith(p)) return true;
        }
        return false;
    }

    private static String checkSuffix(Role role) {
        return role == null ? "" : role.isSetupCheck() ? ".setup" : role.isHoldCheck() ? ".hold" : "";
    }

    /**
     * Checks if the library has speed grade factors for a grade.
     * @param speedGrade The speed grade, without a leading dash.
     * @return True if it has, or if it is the reference grade.
     */
    public boolean hasSpeedGrade(String speedGrade) {
        return speedGrade.equals(speedRef) || speedScales.containsKey(speedGrade);
    }

    /**
     * @return The speed grades with factors, including the reference grade, without leading dashes.
     */
    public Set<String> getSpeedGrades() {
        Set<String> grades = new LinkedHashSet<>(speedScales.keySet());
        grades.add(speedRef);
        return grades;
    }

    /**
     * Gets the key of the speed grade factors of an arc: its cell type (* for the LUT wildcard), from
     * pin and to pin, separated by colons, e.g. LOOKAHEAD8:PROPB:COUTH.
     * @param arc The arc.
     * @return The key.
     */
    static String getArcScaleKey(Arc arc) {
        return arc.scaleKey;
    }

    /**
     * Gets the speed grade factor of a delay: that of the cell type and check kind (.setup for
     * SETUP and RECOVERY arcs, .hold for HOLD and REMOVAL arcs), else of the category and check
     * kind, else of the category, else 1.
     * @param speedGrade The speed grade without a leading dash, or null for the reference grade.
     * @param cellType The cell type, or null (e.g. for configuration delays).
     * @param category The category (see {@link #getCategory}), e.g. config for configuration delays.
     * @param role The role of the arc, or null.
     * @param corner The data index of the corner, see {@link #getDataIndex(Corner)}.
     * @return The factor.
     */
    float getSpeedFactor(String speedGrade, String cellType, String category, Role role, int corner) {
        return getSpeedFactor(speedGrade, cellType, null, category, role, corner);
    }

    /**
     * Gets the speed grade factor of the delay of an arc: that of the arc and check kind (see
     * {@link #getArcScaleKey}; arcs whose delays scale unlike the other arcs of their cell type have
     * their own factors), else as {@link #getSpeedFactor(String, String, String, Role, int)}.
     * @param speedGrade The speed grade without a leading dash, or null for the reference grade.
     * @param cellType The cell type, or null (e.g. for configuration delays).
     * @param arc The arc of the delay, or null.
     * @param category The category (see {@link #getCategory}), e.g. config for configuration delays.
     * @param role The role of the arc, or null.
     * @param corner The data index of the corner, see {@link #getDataIndex(Corner)}.
     * @return The factor.
     */
    float getSpeedFactor(String speedGrade, String cellType, Arc arc, String category, Role role, int corner) {
        if (speedGrade == null) {
            return 1f;
        }
        Map<String, float[]> m = speedScales.get(speedGrade);
        if (m == null) {
            return 1f;
        }
        String sfx = checkSuffix(role);
        float[] f = arc == null ? null : m.get(sfx.isEmpty() ? arc.scaleKey : arc.scaleKey + sfx);
        if (f == null && cellType != null) {
            f = m.get(cellType + sfx);
        }
        if (f == null) {
            f = m.get(category + sfx);
        }
        if (f == null) {
            f = m.get(category);
        }
        return f == null ? 1f : f[corner];
    }

    /**
     * Gets the device factor of a delay, for devices whose cell delays at the reference grade differ
     * from the family's: that of the category and check kind, else of the category, else 1.
     * @param deviceName The name of the device, e.g. xc2ve3358.
     * @param category The category.
     * @param role The role of the arc, or null.
     * @param corner The data index of the corner.
     * @return The factor.
     */
    float getDeviceFactor(String deviceName, String category, Role role, int corner) {
        Map<String, float[]> m = deviceScales.get(deviceName);
        if (m == null) {
            return 1f;
        }
        float[] f = m.get(category + checkSuffix(role));
        if (f == null) {
            f = m.get(category);
        }
        return f == null ? 1f : f[corner];
    }

    /**
     * Gets a delay scaled to a device and speed grade: the delay times the device factor times the
     * speed grade factor.
     */
    public float scale(float delay, String deviceName, String speedGrade, String cellType, String category,
                       Role role, int corner) {
        return scale(delay, deviceName, speedGrade, cellType, null, category, role, corner);
    }

    /**
     * Gets the delay of an arc scaled to a device and speed grade: the delay times the device factor
     * times the speed grade factor of the arc (see {@link #getArcScaleKey}), else of its cell type.
     * @param delay The delay at the reference grade, in ps.
     * @param deviceName The name of the device.
     * @param speedGrade The speed grade without a leading dash, or null for the reference grade.
     * @param cellType The cell type, or null.
     * @param arc The arc that {@link #lookup} found for the delay, or null.
     * @param category The category (see {@link #getCategory}).
     * @param role The role of the arc, or null.
     * @param corner The data index of the corner, see {@link #getDataIndex(Corner)}.
     * @return The scaled delay, in ps.
     */
    public float scale(float delay, String deviceName, String speedGrade, String cellType, Arc arc,
                       String category, Role role, int corner) {
        return delay * getDeviceFactor(deviceName, category, role, corner)
                * getSpeedFactor(speedGrade, cellType, arc, category, role, corner);
    }

    /**
     * Gets the speed grade factor of a configuration delay: that of its entry (see
     * {@link #getConfigScaleKey}; entries whose delays scale unlike the other configuration delays have
     * their own factors), else that of the configuration delays (category config).
     * @param speedGrade The speed grade without a leading dash, or null for the reference grade.
     * @param config The result of {@link #config} for the delay.
     * @param corner The data index of the corner, see {@link #getDataIndex(Corner)}.
     * @return The factor.
     */
    float getConfigSpeedFactor(String speedGrade, ConfigResult config, int corner) {
        if (speedGrade == null) {
            return 1f;
        }
        if (config.scaleKey != null) {
            Map<String, float[]> m = speedScales.get(speedGrade);
            float[] f = m == null ? null : m.get(config.scaleKey);
            if (f != null) {
                return f[corner];
            }
        }
        return getSpeedFactor(speedGrade, null, "config", null, corner);
    }

    /**
     * Gets a configuration delay scaled to a device and speed grade: the delay times the device factor of
     * the configuration delays times the speed grade factor of the delay's entry (see
     * {@link #getConfigSpeedFactor}).
     * @param config The result of {@link #config} for the delay; its delays must not be null.
     * @param deviceName The name of the device.
     * @param speedGrade The speed grade without a leading dash, or null for the reference grade.
     * @param corner The data index of the corner, see {@link #getDataIndex(Corner)}.
     * @return The scaled delay, in ps.
     */
    public float scaleConfig(ConfigResult config, String deviceName, String speedGrade, int corner) {
        return config.delays[corner] * getDeviceFactor(deviceName, "config", null, corner)
                * getConfigSpeedFactor(speedGrade, config, corner);
    }
}

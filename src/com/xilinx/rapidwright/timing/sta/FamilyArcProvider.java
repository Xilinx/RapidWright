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

package com.xilinx.rapidwright.timing.sta;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicLong;

import com.xilinx.rapidwright.design.Cell;
import com.xilinx.rapidwright.design.Design;
import com.xilinx.rapidwright.design.Net;
import com.xilinx.rapidwright.design.NetTools;
import com.xilinx.rapidwright.design.SiteInst;
import com.xilinx.rapidwright.design.SitePinInst;
import com.xilinx.rapidwright.design.VivadoProp;
import com.xilinx.rapidwright.device.BELPin;
import com.xilinx.rapidwright.device.Node;
import com.xilinx.rapidwright.device.PartNameTools;
import com.xilinx.rapidwright.device.Series;
import com.xilinx.rapidwright.device.SitePin;
import com.xilinx.rapidwright.device.Tile;
import com.xilinx.rapidwright.edif.EDIFHierCellInst;
import com.xilinx.rapidwright.edif.EDIFHierNet;
import com.xilinx.rapidwright.edif.EDIFHierPortInst;
import com.xilinx.rapidwright.edif.EDIFNetlist;
import com.xilinx.rapidwright.edif.EDIFPortInst;
import com.xilinx.rapidwright.edif.EDIFPropertyValue;
import com.xilinx.rapidwright.edif.EDIFTools;
import com.xilinx.rapidwright.rwroute.RouterHelper;
import com.xilinx.rapidwright.timing.clock.ClockData;
import com.xilinx.rapidwright.timing.clock.ClockNodeDelayModel;
import com.xilinx.rapidwright.timing.clock.ClockRoute;
import com.xilinx.rapidwright.timing.delayestimator.DelayEstimator;
import com.xilinx.rapidwright.timing.family.ClassBasedDelayEstimator;
import com.xilinx.rapidwright.timing.family.Corner;
import com.xilinx.rapidwright.timing.family.FittedCellArcLibrary;
import com.xilinx.rapidwright.timing.family.FittedCellArcLibrary.ArcKey;
import com.xilinx.rapidwright.timing.family.FittedCellArcLibrary.Role;
import com.xilinx.rapidwright.timing.family.SlrCoordinates;
import com.xilinx.rapidwright.timing.family.SpeedGrade;
import com.xilinx.rapidwright.timing.family.TimingFamilies;
import com.xilinx.rapidwright.timing.family.TimingFamily;
import com.xilinx.rapidwright.util.ParallelismTools;

/**
 * An arc provider of the fitted timing model of a device's family:
 * <ul>
 * <li>Cell arcs come from the family's {@link FittedCellArcLibrary}, scaled to the device and the
 * part's speed grade: combinational and clock-to-output arcs between the cell's pins. Arcs between
 * two buses go through a hub pseudo pin unless their values depend on both bits. An arc only exists
 * if the best match of its configuration differs from the cell's in no register property (e.g. the
 * combinational arcs of an unregistered DSP input do not exist when the input is registered).
 * Pins with setup or recovery checks are timing endpoints, whose setup and hold times
 * {@link FittedCheckTimes} gives; asynchronous set/clear arcs are not timed.</li>
 * <li>A net edge's delay is the configuration delay from the source BEL pin to its site pin, the
 * route delay, and the configuration delay from the sink site pin to the sink BEL pin; a net that
 * stays in its site has the configuration delay between its BEL pins. The nets of the clock buffers
 * used as data (see {@link DataClockBuffers}) are timed with the clock node delay model of the
 * device's clock data once routed on the global clock network.</li>
 * <li>The route delay walks the routed nodes from the sink back to the source with the family's
 * {@link DelayEstimator}: node, transition and fanout delays, plus the pin delays of
 * {@link DelayEstimator#getPinDelay}. An unrouted connection gets the delay of the dedicated path
 * between its pins if it is a direct connection, else the estimator's pre-route delay plus its
 * lookahead delay per INT column and row.</li>
 * </ul>
 * The max and min delays of the graph are those of two corners chosen by the caller, e.g. SLOW_MAX
 * for setup and FAST_MIN for hold. The provider keeps no timing model state, so cell arcs may be
 * added from several threads, and so may net delays ({@link #getNetArc(NetArc)}) with a {@link NetArc} per thread,
 * which keeps the routing of the net it last looked at; {@link #getRoutedDelays} is computed from one thread at a
 * time, with a cache of the routing of the net last looked at.
 */
public class FamilyArcProvider implements StaArcProvider {

    private final FittedCellArcLibrary lib;
    private final String deviceName;
    private final String speedGrade;
    private final int maxIdx;
    private final int minIdx;
    /** The data index of the second min corner (e.g. SLOW_MIN), -1 if none */
    private final int min2Idx;
    private final DelayEstimator maxEstimator;
    private final DelayEstimator minEstimator;
    private final DelayEstimator min2Estimator;
    /** The corners of the max, min and second min (or null) delays */
    private final Corner maxCorner;
    private final Corner minCorner;
    private final Corner min2Corner;
    /** The speed grade of the part, or null */
    private final SpeedGrade partGrade;
    /** The clock node delay model of the clock nets that are timed, see {@link #getClockNodeModel()} */
    private ClockNodeDelayModel clockNodeModel;
    private boolean clockNodeModelCreated;
    private final Design design;
    /** The series of the device, for the default property values of cells */
    private final Series series;
    private final Set<String> unknownCellTypes = ConcurrentHashMap.newKeySet();
    private final AtomicLong numCellArcs = new AtomicLong();
    private final AtomicLong numMissingConfig = new AtomicLong();

    /**
     * Register properties of the UltraScale+ DSP48E2 sub-cells (and of the Versal DSP58's, see
     * {@link #dspType}), by cell type and input pin. The arcs of
     * these cells do not depend on their configuration in the library, which holds both the
     * combinational arcs of an unregistered input and the setup check of a registered one: an input
     * whose register property is not 0 (it is 1 by default) has the setup check and no combinational
     * arcs, otherwise the reverse.
     */
    private static final Map<String, String> REGISTER_PROPERTIES = new HashMap<>();

    static {
        String[][] table = {
                {"DSP_A_B_DATA", "A", "AREG"}, {"DSP_A_B_DATA", "ACIN", "AREG"},
                {"DSP_A_B_DATA", "B", "BREG"}, {"DSP_A_B_DATA", "BCIN", "BREG"},
                {"DSP_C_DATA", "C", "CREG"},
                {"DSP_M_DATA", "U", "MREG"}, {"DSP_M_DATA", "V", "MREG"},
                {"DSP_PREADD_DATA", "DIN", "DREG"}, {"DSP_PREADD_DATA", "AD", "ADREG"},
                {"DSP_OUTPUT", "ALU_OUT", "PREG"}, {"DSP_OUTPUT", "ALUMODE10", "PREG"}, {"DSP_OUTPUT", "COUT", "PREG"},
                {"DSP_OUTPUT", "MULTSIGN_ALU", "PREG"}, {"DSP_OUTPUT", "XOR_MX", "PREG"},
                {"DSP_OUTPUT", "PATT_DET", "PREG"}, {"DSP_OUTPUT", "PATT_B_DET", "PREG"},
        };
        for (String[] t : table) {
            REGISTER_PROPERTIES.put(t[0] + "/" + t[1], t[2]);
        }
    }

    /**
     * Checks if an input pin of a cell is registered, see {@link #REGISTER_PROPERTIES}.
     * @return True or false, or null if the pin has no register property.
     */
    private static Boolean isRegisteredInput(String cellType, String pin, Map<String, String> attrs) {
        String property = REGISTER_PROPERTIES.get(dspType(cellType) + "/" + pin);
        if (property == null) {
            return null;
        }
        String value = attrs.get(property);
        return value == null || !(value.equals("0") || value.equalsIgnoreCase("FALSE"));
    }

    /**
     * The registers behind the clock-to-output arcs of the DSP cells: (cell type/output pin -> register
     * properties, any of which enables the arc). Without its register (e.g. INMODEREG=0), an output has no
     * clock-to-output arc, as in Vivado.
     */
    private static final Map<String, String[]> CLK_TO_Q_REGISTERS = new HashMap<>();

    static {
        String[] a = {"AREG"};
        String[] b = {"BREG"};
        for (String pin : new String[]{"A1_DATA", "A2_DATA", "A_ALU", "ACOUT"}) {
            CLK_TO_Q_REGISTERS.put("DSP_A_B_DATA/" + pin, a);
        }
        for (String pin : new String[]{"B1_DATA", "B2_DATA", "B_ALU", "BCOUT"}) {
            CLK_TO_Q_REGISTERS.put("DSP_A_B_DATA/" + pin, b);
        }
        CLK_TO_Q_REGISTERS.put("DSP_PREADD_DATA/A2A1", new String[]{"INMODEREG"});
        CLK_TO_Q_REGISTERS.put("DSP_PREADD_DATA/B2B1", new String[]{"INMODEREG"});
        CLK_TO_Q_REGISTERS.put("DSP_PREADD_DATA/AD_DATA", new String[]{"ADREG"});
        CLK_TO_Q_REGISTERS.put("DSP_PREADD_DATA/D_DATA", new String[]{"DREG"});
        CLK_TO_Q_REGISTERS.put("DSP_M_DATA/U_DATA", new String[]{"MREG"});
        CLK_TO_Q_REGISTERS.put("DSP_M_DATA/V_DATA", new String[]{"MREG"});
        CLK_TO_Q_REGISTERS.put("DSP_C_DATA/C_DATA", new String[]{"CREG"});
        String[] alu = {"OPMODEREG", "ALUMODEREG", "CARRYINREG", "CARRYINSELREG"};
        for (String pin : new String[]{"ALUMODE10", "ALU_OUT", "COUT", "MULTSIGN_ALU"}) {
            CLK_TO_Q_REGISTERS.put("DSP_ALU/" + pin, alu);
        }
    }

    /**
     * Checks if the clock-to-output arc of a DSP cell's output has its register disabled.
     * @return True if the register properties are all set and 0.
     */
    static boolean isClockToOutputDisabled(String cellType, String outPin, Map<String, String> attrs) {
        String[] regs = CLK_TO_Q_REGISTERS.get(dspType(cellType) + "/" + outPin);
        if (regs == null) {
            return false;
        }
        for (String r : regs) {
            String v = attrs.get(r);
            if (v == null || !(v.equals("0") || v.equalsIgnoreCase("FALSE"))) {
                return false;
            }
        }
        return true;
    }

    /** Properties of the DSP macro that select the multiplier's inputs */
    private static final String[] DSP_MULTSEL_PROPERTIES = {"AMULTSEL", "BMULTSEL"};

    /**
     * Checks if a pin of a DSP cell is left unused by the multiplier input selection of its macro
     * (AMULTSEL / BMULTSEL: A or B, else AD, the pre-adder): the multiplier's AD_DATA input and the
     * pre-adder register's AD input unless either selects AD, its A2A1 (B2B1) input if AMULTSEL (BMULTSEL)
     * is AD. Vivado times no path through them.
     * @return True if unused.
     */
    static boolean isUnusedDspPin(String cellType, String pin, Map<String, String> attrs) {
        cellType = dspType(cellType);
        String a = attrs.get("AMULTSEL");
        String b = attrs.get("BMULTSEL");
        boolean adUnused = a != null && b != null && !a.equals("AD") && !b.equals("AD");
        if (cellType.equals("DSP_MULTIPLIER")) {
            switch (pin) {
                case "AD_DATA":
                    return adUnused;
                case "A2A1":
                    return "AD".equals(a);
                case "B2B1":
                    return "AD".equals(b);
                default:
                    return false;
            }
        }
        return cellType.equals("DSP_PREADD_DATA") && pin.equals("AD") && adUnused;
    }

    /**
     * The DSP48E2 sub-cell type of a Versal DSP58 sub-cell (e.g. DSP_M_DATA58: DSP_M_DATA), whose register
     * properties and pins are those of the tables above; other types are returned unchanged.
     */
    static String dspType(String cellType) {
        return cellType.startsWith("DSP_") && cellType.endsWith("58")
                ? cellType.substring(0, cellType.length() - 2) : cellType;
    }

    /**
     * The clock enables of the register groups of the UltraScale+ DSP48E2 sub-cells: a group whose clock
     * enables are all tied to ground never loads, so Vivado times no path from or to its registers.
     */
    private static final Map<String, String[]> DSP_GROUP_CLOCK_ENABLES = new HashMap<>();

    static {
        DSP_GROUP_CLOCK_ENABLES.put("DSP_C_DATA", new String[]{"CEC"});
        DSP_GROUP_CLOCK_ENABLES.put("DSP_M_DATA", new String[]{"CEM"});
        DSP_GROUP_CLOCK_ENABLES.put("DSP_OUTPUT", new String[]{"CEP"});
        DSP_GROUP_CLOCK_ENABLES.put("DSP_PREADD_DATA", new String[]{"CED", "CEAD", "CEINMODE"});
        DSP_GROUP_CLOCK_ENABLES.put("DSP_ALU", new String[]{"CEALUMODE", "CECARRYIN", "CEM", "CECTRL"});
    }

    /** The block RAM properties that {@link #isUntimedBlockRamInput} reads */
    private static final Set<String> BRAM_REGISTER_PROPERTIES = new LinkedHashSet<>(Arrays.asList("DOA_REG", "DOB_REG"));

    /** The properties of the DSP48E2 macro that {@link #isUnusedClockEnable} reads */
    private static final Set<String> DSP_CLOCK_ENABLE_PROPERTIES = new LinkedHashSet<>(Arrays.asList("AREG", "BREG",
            "DREG", "ADREG", "INMODEREG", "CREG", "MREG", "PREG", "ALUMODEREG", "CARRYINREG", "OPMODEREG",
            "CARRYINSELREG", "AMULTSEL", "BMULTSEL"));

    /** The number of stages of a DSP register property (e.g. AREG=0, 1 or 2), 1 (the default) if unknown */
    private static int getRegisterStages(Map<String, String> attrs, String property) {
        String v = attrs.get(property);
        if (v == null) {
            return 1;
        }
        v = v.trim();
        int quote = v.indexOf('\'');
        try {
            if (quote >= 0 && quote + 2 <= v.length()) {
                char base = Character.toLowerCase(v.charAt(quote + 1));
                return Integer.parseInt(v.substring(quote + 2), base == 'h' ? 16 : base == 'b' ? 2 : 10);
            }
            return Integer.parseInt(v);
        } catch (NumberFormatException e) {
            return 1;
        }
    }

    /**
     * Checks if a clock enable of an UltraScale+ DSP48E2 sub-cell enables no register: its register is bypassed
     * (e.g. AREG=0 for CEA1 and CEA2; with AREG=1, Vivado checks both CEA1 and CEA2 when logic drives them), or, for
     * the pre-adder's register, feeds nothing (no AMULTSEL or BMULTSEL of AD). Vivado times no path to it.
     * @param cellType The sub-cell type, e.g. DSP_A_B_DATA.
     * @param pin The clock enable pin, e.g. CEA1.
     * @param attrs The DSP48E2 properties (see {@link #DSP_CLOCK_ENABLE_PROPERTIES}).
     * @return True if unused.
     */
    static boolean isUnusedClockEnable(String cellType, String pin, Map<String, String> attrs) {
        switch (cellType + "/" + pin) {
            case "DSP_A_B_DATA/CEA1":
            case "DSP_A_B_DATA/CEA2":
                return getRegisterStages(attrs, "AREG") == 0;
            case "DSP_A_B_DATA/CEB1":
            case "DSP_A_B_DATA/CEB2":
                return getRegisterStages(attrs, "BREG") == 0;
            case "DSP_PREADD_DATA/CED":
                return getRegisterStages(attrs, "DREG") == 0;
            case "DSP_PREADD_DATA/CEAD":
                return getRegisterStages(attrs, "ADREG") == 0
                        || !"AD".equals(attrs.get("AMULTSEL")) && !"AD".equals(attrs.get("BMULTSEL"));
            case "DSP_PREADD_DATA/CEINMODE":
                return getRegisterStages(attrs, "INMODEREG") == 0;
            case "DSP_C_DATA/CEC":
                return getRegisterStages(attrs, "CREG") == 0;
            case "DSP_M_DATA/CEM":
                return getRegisterStages(attrs, "MREG") == 0;
            case "DSP_OUTPUT/CEP":
                return getRegisterStages(attrs, "PREG") == 0;
            case "DSP_ALU/CEALUMODE":
                return getRegisterStages(attrs, "ALUMODEREG") == 0;
            case "DSP_ALU/CECARRYIN":
                return getRegisterStages(attrs, "CARRYINREG") == 0;
            case "DSP_ALU/CECTRL":
                return getRegisterStages(attrs, "OPMODEREG") == 0 && getRegisterStages(attrs, "CARRYINSELREG") == 0;
            default:
                return false;
        }
    }

    /**
     * Gets the leaf cell pins of the physical net of a cell pin. The netlist's parent net map must be built
     * (see {@link EDIFNetlist#getParentNetMap()}) before calling this from several threads.
     * @return The pins, or null if the pin has no net.
     */
    static List<EDIFHierPortInst> getPhysicalPins(EDIFNetlist netlist, EDIFHierPortInst pin) {
        EDIFHierNet net = pin.getHierarchicalNet();
        if (net == null) {
            return null;
        }
        EDIFHierNet parent = netlist.getParentNet(net);
        return netlist.getPhysicalNetPinMap().get(parent == null ? net : parent);
    }

    /** Checks if a port of a cell is on a net that a ground cell drives (its first leaf driver). */
    private static boolean isTiedToGround(EDIFNetlist netlist, EDIFHierCellInst inst, String port) {
        EDIFPortInst pi = inst.getInst().getPortInst(port);
        List<EDIFHierPortInst> pins = pi == null ? null
                : getPhysicalPins(netlist, new EDIFHierPortInst(inst.getParent(), pi));
        if (pins == null) {
            return false;
        }
        for (EDIFHierPortInst p : pins) {
            if (p.isOutput() && !p.getPortInst().isTopLevelPort()) {
                return p.getCellType().getName().equals("GND");
            }
        }
        return false;
    }

    /**
     * Checks if a register group of an UltraScale+ DSP48E2 sub-cell never loads: its clock enables are all tied
     * to ground (see {@link #DSP_GROUP_CLOCK_ENABLES}). Vivado then times neither its clock-to-output arcs nor
     * the checks of its inputs.
     */
    private static boolean isDisabledRegisterGroup(EDIFNetlist netlist, EDIFHierCellInst inst, String cellType) {
        String[] enables = DSP_GROUP_CLOCK_ENABLES.get(cellType);
        if (enables == null) {
            return false;
        }
        for (String ce : enables) {
            if (!isTiedToGround(netlist, inst, ce)) {
                return false;
            }
        }
        return true;
    }

    /**
     * Checks if an input of an input buffer is the pad of an inout port, which an output buffer of the design
     * also drives (e.g. the INBUF of an IOBUF): Vivado breaks the paths at the port, so the paths from the
     * output buffer through the pad back into the design are not timed.
     */
    private static boolean isPadLoopbackInput(EDIFNetlist netlist, EDIFHierCellInst inst, EDIFPortInst input) {
        List<EDIFHierPortInst> pins = getPhysicalPins(netlist, new EDIFHierPortInst(inst.getParent(), input));
        if (pins == null) {
            return false;
        }
        for (EDIFHierPortInst p : pins) {
            if (p.isOutput() && !p.getPortInst().isTopLevelPort() && p.getCellType().getName().startsWith("OBUF")) {
                return true;
            }
        }
        return false;
    }

    /**
     * Checks if a port of a cell is unconnected (no net) or on a net that a ground or power cell drives (its first
     * leaf driver).
     */
    static boolean isTiedOrUnconnected(EDIFNetlist netlist, EDIFHierCellInst inst, String port) {
        EDIFPortInst pi = inst.getInst().getPortInst(port);
        if (pi == null || pi.getNet() == null) {
            return true;
        }
        List<EDIFHierPortInst> pins = getPhysicalPins(netlist, new EDIFHierPortInst(inst.getParent(), pi));
        if (pins == null) {
            return false;
        }
        for (EDIFHierPortInst p : pins) {
            if (p.isOutput() && !p.getPortInst().isTopLevelPort()) {
                String type = p.getCellType().getName();
                return type.equals("GND") || type.equals("VCC");
            }
        }
        // (driven from a top-level port, or by nothing that is a leaf cell: not tied)
        return false;
    }

    /** Whether a cell type is an UltraScale+ block RAM (RAMB36E2, RAMB18E2) */
    private static boolean isUltraScaleBlockRam(Series series, String cellType) {
        return series != Series.Versal && (cellType.equals("RAMB36E2") || cellType.equals("RAMB18E2"));
    }

    /**
     * Checks if an input of an UltraScale+ block RAM has no timing check in Vivado beyond those its clock port
     * decides: a logical pin wired to no physical pin (e.g. WEA[1] to WEA[3] when every write enable site pin is
     * WEA[0]'s), and the reset and clock enable of an unused output register (RSTREG / REGCE of a port whose
     * DOA_REG / DOB_REG is 0).
     */
    static boolean isUntimedBlockRamInput(Cell c, String port, Map<String, String> attrs) {
        String base = busBase(port);
        Set<String> phys = c.getAllPhysicalPinMappings(port);
        if (c.getPhysicalPinMapping(port) == null && (phys == null || phys.isEmpty())) {
            return true;
        }
        if (base.equals("RSTREGARSTREG") || base.equals("REGCEAREGCE")) {
            return getRegisterStages(attrs, "DOA_REG") == 0;
        }
        if (base.equals("RSTREGB") || base.equals("REGCEB")) {
            return getRegisterStages(attrs, "DOB_REG") == 0;
        }
        return false;
    }

    private static boolean isInputBufferType(String cellType) {
        return cellType.contains("INBUF") || cellType.startsWith("IBUF");
    }

    private static Set<String> getRegisterProperties(String cellType) {
        cellType = dspType(cellType);
        Set<String> names = new LinkedHashSet<>();
        if (cellType.equals("DSP_MULTIPLIER") || cellType.equals("DSP_PREADD_DATA")) {
            names.addAll(Arrays.asList(DSP_MULTSEL_PROPERTIES));
        }
        for (Map.Entry<String, String[]> e : CLK_TO_Q_REGISTERS.entrySet()) {
            if (e.getKey().startsWith(cellType + "/")) {
                names.addAll(Arrays.asList(e.getValue()));
            }
        }
        for (Map.Entry<String, String> e : REGISTER_PROPERTIES.entrySet()) {
            if (e.getKey().startsWith(cellType + "/")) {
                names.add(e.getValue());
            }
        }
        return names;
    }

    /** The routing of the net last looked at (dropped by {@link #invalidateRouting()}) */
    private NetRouting routing = new NetRouting();

    private static final class NetRouting {
        Net net;
        int numPIPs;
        Map<Node, Node> nodeToDriver;
        Map<Node, Integer> nodeToNumBranches;
        /**
         * The number of the net's sink pins that its routing reaches through each node, for the nodes that
         * lead to more than one (built on first use, see {@link #getNumLoads})
         */
        Map<Node, Integer> nodeToNumLoads;
        /** The net's route on the global clock network, if it is a clock net (built on first use) */
        ClockRoute clockRoute;
        /** The source site pin whose INT tile is {@link #sourceTile}, null if none yet */
        SitePinInst tileSource;
        Tile sourceTile;
    }

    /**
     * Creates the provider of one corner, with delay estimators of the device's family.
     * @param design The design.
     * @param corner The corner of both the max and the min delays.
     */
    public FamilyArcProvider(Design design, Corner corner) {
        this(design, corner, corner);
    }

    /**
     * Creates the provider of two corners, with delay estimators of the device's family.
     * @param design The design.
     * @param maxCorner The corner of the max delays, e.g. SLOW_MAX.
     * @param minCorner The corner of the min delays, e.g. FAST_MIN.
     */
    public FamilyArcProvider(Design design, Corner maxCorner, Corner minCorner) {
        this(design, null, maxCorner, null, minCorner);
    }

    /**
     * Creates the provider.
     * @param design The design.
     * @param maxEstimator The delay estimator of the max corner (e.g. the router's), or null to create
     *                     that of the device's family.
     * @param maxCorner The corner of the max delays.
     * @param minEstimator The delay estimator of the min corner, or null to create it (or to use
     *                     maxEstimator if both corners are the same).
     * @param minCorner The corner of the min delays.
     */
    public FamilyArcProvider(Design design, DelayEstimator maxEstimator, Corner maxCorner,
                             DelayEstimator minEstimator, Corner minCorner) {
        this(design, maxEstimator, maxCorner, minEstimator, minCorner, null);
    }

    /**
     * Creates the provider, with the delays of a second min corner too (see {@link #hasSecondMinCorner()}),
     * e.g. SLOW_MIN for the slow-corner hold analysis. Its route delays are given by
     * {@link #getRoutedDelays(Net, SitePinInst, SitePinInst)} once routed (the graph starts with those of the
     * min corner).
     * @param design The design.
     * @param maxEstimator The delay estimator of the max corner, or null to create it.
     * @param maxCorner The corner of the max delays.
     * @param minEstimator The delay estimator of the min corner, or null to create it.
     * @param minCorner The corner of the min delays.
     * @param min2Corner The second min corner, or null for none.
     */
    public FamilyArcProvider(Design design, DelayEstimator maxEstimator, Corner maxCorner,
                             DelayEstimator minEstimator, Corner minCorner, Corner min2Corner) {
        this(design, maxEstimator, maxCorner, minEstimator, minCorner, null, min2Corner);
    }

    /**
     * Creates the provider, with the delays of a second min corner too (see
     * {@link #FamilyArcProvider(Design, DelayEstimator, Corner, DelayEstimator, Corner, Corner)}), and the delay
     * estimator of each of its corners given or created. The estimators give the route delays of net arcs
     * ({@link #getNetArc(NetArc)}, {@link #getRoutedDelays(Net, SitePinInst, SitePinInst)}) and nothing else:
     * cell arcs and intra-site delays come from the family's fitted cell arcs, and nets routed on the global clock
     * network from the device's clock data. A caller may thus give estimators of its own, e.g. of derived
     * interconnect parameters (see {@link TimingFamily#createDelayEstimator(com.xilinx.rapidwright.device.Device,
     * com.xilinx.rapidwright.timing.family.InterconnectParams, SpeedGrade, boolean)}), for the route delays of
     * their corners. The estimators must be safe to use from several threads if net arcs are computed from
     * several threads.
     * @param design The design.
     * @param maxEstimator The delay estimator of the max corner, or null to create that of the device's family.
     * @param maxCorner The corner of the max delays.
     * @param minEstimator The delay estimator of the min corner, or null to create it (or to use maxEstimator if
     *                     both corners are the same).
     * @param minCorner The corner of the min delays.
     * @param min2Estimator The delay estimator of the second min corner, or null to create it; ignored without a
     *                      second min corner.
     * @param min2Corner The second min corner, or null for none.
     */
    public FamilyArcProvider(Design design, DelayEstimator maxEstimator, Corner maxCorner,
                             DelayEstimator minEstimator, Corner minCorner, DelayEstimator min2Estimator,
                             Corner min2Corner) {
        TimingFamily family = TimingFamilies.of(design.getDevice());
        this.design = design;
        this.series = design.getDevice().getSeries();
        this.lib = family.getFittedCellArcs(design.getDevice());
        this.deviceName = design.getDevice().getName();
        SpeedGrade grade = null;
        String partName = design.getPartName();
        if (partName != null && !partName.isEmpty()) {
            grade = family.resolveSpeedGrade(PartNameTools.getPart(partName));
        }
        // An unknown grade takes the factors of the nearest listed one (see SpeedGrade.nearest())
        this.speedGrade = grade == null ? null
                : SpeedGrade.resolve(grade.getName(), lib.getSpeedGrades(), lib.getSpeedRef(), "cell delay data");
        this.maxIdx = FittedCellArcLibrary.getDataIndex(maxCorner);
        this.minIdx = FittedCellArcLibrary.getDataIndex(minCorner);
        if (maxEstimator == null) {
            // (U-turn nodes unmasked: the timing of existing routes charges their actual delays)
            maxEstimator = family.createDelayEstimator(design.getDevice(), grade, maxCorner, true);
        }
        if (minEstimator == null) {
            minEstimator = minCorner == maxCorner ? maxEstimator
                    : family.createDelayEstimator(design.getDevice(), grade, minCorner, true);
        }
        this.maxEstimator = maxEstimator;
        this.minEstimator = minEstimator;
        this.min2Idx = min2Corner == null ? -1 : FittedCellArcLibrary.getDataIndex(min2Corner);
        this.min2Estimator = min2Corner == null ? null : min2Estimator != null ? min2Estimator
                : family.createDelayEstimator(design.getDevice(), grade, min2Corner, true);
        this.maxCorner = maxCorner;
        this.minCorner = minCorner;
        this.min2Corner = min2Corner;
        this.partGrade = grade;
    }

    /** Gets the clock node delay model of the device (null if it has no clock data), created on first use */
    private synchronized ClockNodeDelayModel getClockNodeModel() {
        if (!clockNodeModelCreated) {
            ClockData data = ClockData.get(design.getDevice(), partGrade);
            clockNodeModel = data == null ? null : data.createNodeDelayModel();
            clockNodeModelCreated = true;
        }
        return clockNodeModel;
    }

    @Override
    public boolean hasSecondMinCorner() {
        return min2Idx >= 0;
    }

    /** Adds a cell arc with its delays at the max, min (and second min) corners */
    private void addArc(CellArcs ca, int from, int to, byte kind, float[] d, String cellType,
                        FittedCellArcLibrary.Arc arc, String category, Role role) {
        float max = scaleArc(d, maxIdx, cellType, arc, category, role);
        float min = scaleArc(d, minIdx, cellType, arc, category, role);
        if (min2Idx >= 0) {
            ca.addArc(from, to, kind, max, min, scaleArc(d, min2Idx, cellType, arc, category, role));
        } else {
            ca.addArc(from, to, kind, max, min);
        }
    }

    FittedCellArcLibrary getLibrary() {
        return lib;
    }

    /**
     * @return The number of cell arcs added so far.
     */
    long getNumCellArcs() {
        return numCellArcs.get();
    }

    /**
     * @return The cell types that had no arcs.
     */
    Set<String> getUnknownCellTypes() {
        return Collections.unmodifiableSet(unknownCellTypes);
    }

    /**
     * @return The number of configuration delays looked up but missing (counted as 0).
     */
    long getNumMissingConfigDelays() {
        return numMissingConfig.get();
    }

    // ------------------------------------------------------------------------------------------
    // Cell arcs
    // ------------------------------------------------------------------------------------------

    private static boolean isSlice(String site) {
        return site.equals("SLICEL") || site.equals("SLICEM");
    }

    /** Logical pin name -> base name and bit, e.g. A[3] -> A, 3; bit -1 for scalar pins */
    static String busBase(String pin) {
        int open = pin.lastIndexOf('[');
        if (open > 0 && pin.endsWith("]")) {
            for (int i = open + 1; i < pin.length() - 1; i++) {
                if (!Character.isDigit(pin.charAt(i))) {
                    return pin;
                }
            }
            if (open + 1 < pin.length() - 1) {
                return pin.substring(0, open);
            }
        }
        return pin;
    }

    static int busBit(String pin) {
        String base = busBase(pin);
        return base.length() == pin.length() ? -1 : Integer.parseInt(pin.substring(base.length() + 1, pin.length() - 1));
    }

    /** UNISIM default property values by series and cell type (see {@link Design#getDefaultCellProperties}) */
    private static final Map<String, Map<String, String>> DEFAULT_PROPERTIES = new ConcurrentHashMap<>();

    private static Map<String, String> getDefaultProperties(Series series, String cellType) {
        return DEFAULT_PROPERTIES.computeIfAbsent(series + "/" + cellType, k -> {
            Map<String, VivadoProp> props = series == null ? null : Design.getDefaultCellProperties(series, cellType);
            if (props == null || props.isEmpty()) {
                return Collections.emptyMap();
            }
            Map<String, String> values = new HashMap<>();
            for (Map.Entry<String, VivadoProp> e : props.entrySet()) {
                values.put(e.getKey(), e.getValue().getValue().replace("\"", ""));
            }
            return values;
        });
    }

    /**
     * Gets the properties of a cell and of the macro instances it belongs to (the outermost value
     * winning), restricted to some names. A property that no instance sets takes its UNISIM default
     * value (that of the outermost primitive that has one), so that a cell created without properties
     * (e.g. by Design.createAndPlaceCell) matches the arcs of its default configuration.
     * @param series The series of the device, for the default values (null for none).
     * @param c The cell.
     * @param names The property names.
     * @return The values by name.
     */
    static Map<String, String> getProperties(Series series, Cell c, Set<String> names) {
        if (names.isEmpty()) {
            return Collections.emptyMap();
        }
        Map<String, String> properties = new HashMap<>();
        List<String> types = new ArrayList<>(2);
        EDIFHierCellInst inst = c.getEDIFHierCellInst();
        while (inst != null && !inst.isTopLevelInst()
                && inst.getCellType().getLibrary().getName().equals(EDIFTools.EDIF_LIBRARY_HDI_PRIMITIVES_NAME)) {
            for (Map.Entry<String, EDIFPropertyValue> p : inst.getInst().getPropertiesMap().entrySet()) {
                if (names.contains(p.getKey())) {
                    properties.put(p.getKey(), p.getValue().getValue().replace("\"", ""));
                }
            }
            types.add(inst.getCellType().getName());
            inst = inst.getParent();
        }
        if (properties.size() < names.size()) {
            for (String name : names) {
                if (properties.containsKey(name) || name.startsWith("@")) continue;
                // The default of the outermost primitive that has the property
                for (int i = types.size() - 1; i >= 0; i--) {
                    String v = getDefaultProperties(series, types.get(i)).get(name);
                    if (v != null) {
                        properties.put(name, v);
                        break;
                    }
                }
            }
        }
        return properties;
    }

    /** The arc keys, configuration properties and category of the cells of a type on a site type (and BEL kind) */
    private static final class CellTypeArcs {
        final Set<ArcKey> keys;
        final Set<String> cfgKeys;
        final String category;

        CellTypeArcs(Set<ArcKey> keys, Set<String> cfgKeys, String category) {
            this.keys = keys;
            this.cfgKeys = cfgKeys;
            this.category = category;
        }
    }

    /** {@link CellTypeArcs} by cell type, site type and whether the BEL is a LUT BEL */
    private final Map<String, CellTypeArcs> cellTypeArcs = new ConcurrentHashMap<>();

    private CellTypeArcs getCellTypeArcs(String cellType, String bel, String site) {
        boolean lutBel = bel.endsWith("LUT");
        return cellTypeArcs.computeIfAbsent(cellType + "|" + site + "|" + lutBel, k -> {
            boolean slice = isSlice(site);
            boolean lut = FittedCellArcLibrary.isLUTOnLUTBEL(cellType, bel);
            Set<ArcKey> keys = new LinkedHashSet<>(lib.getArcKeys(lut ? null : cellType));
            Set<String> cfgKeys = new LinkedHashSet<>(lib.getConfigKeys(lut ? null : cellType));
            if (slice && !lut) {
                // Slice cells fall back on the LUT wildcard per arc
                keys.addAll(lib.getArcKeys(null));
            }
            cfgKeys.addAll(getRegisterProperties(cellType));
            return new CellTypeArcs(Collections.unmodifiableSet(keys), Collections.unmodifiableSet(cfgKeys),
                    FittedCellArcLibrary.getCategory(cellType, site));
        });
    }

    private float scaleArc(float[] d, int idx, String cellType, FittedCellArcLibrary.Arc arc, String category,
                           Role role) {
        return lib.scale(d[idx], deviceName, speedGrade, cellType, arc, category, role, idx);
    }

    @Override
    public void addCellArcs(CellArcs ca) {
        Cell c = ca.getCell();
        SiteInst si = c.getSiteInst();
        if (c.getBEL() == null || si == null) {
            return;
        }
        String cellType = c.getType();
        String bel = c.getBELName();
        String site = si.getSiteTypeEnum().name();
        boolean slice = isSlice(site);
        CellTypeArcs typeArcs = getCellTypeArcs(cellType, bel, site);
        Set<ArcKey> keys = typeArcs.keys;
        if (keys.isEmpty()) {
            if (!cellType.startsWith("BUFG") && !cellType.startsWith("MBUFG") && unknownCellTypes.add(cellType)) {
                System.out.println("WARNING: No fitted timing arcs for cell type " + cellType + " (e.g. cell "
                        + ca.getName() + ")");
            }
            return;
        }
        Map<String, String> attrs = getProperties(series, c, typeArcs.cfgKeys);
        String category = typeArcs.category;
        // A LUT's inputs that its function does not depend on have no arcs
        int lutSize = LutInputs.getSize(cellType);
        // (with the constant inputs fixed at their values, as Vivado's constant propagation does)
        int lutInputs = lutSize > 0 ? LutInputs.getDependencies(design, c, ca.getCellInst(), lutSize, true) : -1;
        // The DSP multiplexer, adder and pre-adder inputs that constant control inputs (OPMODE, ALUMODE,
        // CARRYINSEL, INMODE) deselect have no arcs
        DspControl dsp = DspControl.isControlled(dspType(cellType)) ? DspControl.of(c) : null;
        List<EDIFPortInst> ports = ca.getPortInsts();
        // The paths that Vivado does not time, beyond those of the configurations of the arcs: from the pad of an
        // inout port (the loopback through its output and input buffers), and through the registers of a DSP48E2
        // that are bypassed or never enabled
        boolean[] padLoopback = null;
        if (isInputBufferType(cellType)) {
            for (int i = 0; i < ports.size(); i++) {
                EDIFPortInst input = ports.get(i);
                if (input.isInput() && isPadLoopbackInput(design.getNetlist(), ca.getHierCellInst(), input)) {
                    if (padLoopback == null) {
                        padLoopback = new boolean[ports.size()];
                    }
                    padLoopback[i] = true;
                }
            }
        }
        boolean dsp48e2 = series != Series.Versal && cellType.startsWith("DSP_");
        // A block RAM port whose clock is tied or unconnected times nothing (no checks against it, no launch from it)
        boolean bram = isUltraScaleBlockRam(series, cellType);
        Map<String, String> bramRegisters = bram ? getProperties(series, c, BRAM_REGISTER_PROPERTIES) : null;
        boolean registersDisabled = dsp48e2 && isDisabledRegisterGroup(design.getNetlist(), ca.getHierCellInst(),
                cellType);
        Map<String, String> dspRegisters = dsp48e2 ? getProperties(series, c, DSP_CLOCK_ENABLE_PROPERTIES) : null;
        // Pins of the arcs: physical BEL pins in slices, logical bus base names otherwise
        Map<String, List<Integer>> pinPorts = new HashMap<>();
        for (int i = 0; i < ports.size(); i++) {
            String name = ports.get(i).getName();
            if (!slice) {
                pinPorts.computeIfAbsent(busBase(name), k -> new ArrayList<>(1)).add(i);
                continue;
            }
            // All the BEL pins of a slice cell's port (e.g. a LUT RAM's address on both its read and write
            // address pins, A1 and WA1)
            Set<String> phys = c.getAllPhysicalPinMappings(name);
            if (phys == null || phys.isEmpty()) {
                String key = c.getPhysicalPinMapping(name);
                phys = key == null ? Collections.emptySet() : Collections.singleton(key);
            }
            for (String key : phys) {
                pinPorts.computeIfAbsent(key, k -> new ArrayList<>(1)).add(i);
            }
        }
        for (ArcKey key : keys) {
            Role role = key.getRole();
            if (role != Role.COMB && role != Role.CLK_TO_Q && !role.isSetupCheck()) {
                continue;
            }
            List<Integer> froms = pinPorts.get(key.getFrom());
            List<Integer> tos = pinPorts.get(key.getTo());
            if (froms == null && (role.isSetupCheck() || role == Role.CLK_TO_Q)) {
                // A check against, or a launch by, an internal clock (e.g. an RF ADC's INTERNAL_FABRIC_CLK0): that
                // of the clock port that drives it
                froms = clockPortOf(keys, key.getFrom(), pinPorts);
            }
            if (padLoopback != null && froms != null) {
                List<Integer> timed = new ArrayList<>(froms.size());
                for (int from : froms) {
                    if (!padLoopback[from]) timed.add(from);
                }
                froms = timed.isEmpty() ? null : timed;
            }
            if (bram && froms != null && (role == Role.CLK_TO_Q || role.isSetupCheck())) {
                List<Integer> clocked = new ArrayList<>(froms.size());
                for (int from : froms) {
                    if (!isTiedOrUnconnected(design.getNetlist(), ca.getHierCellInst(), ports.get(from).getName())) {
                        clocked.add(from);
                    }
                }
                froms = clocked.isEmpty() ? null : clocked;
            }
            if (bram && tos != null && role.isSetupCheck()) {
                List<Integer> timed = new ArrayList<>(tos.size());
                for (int to : tos) {
                    if (!isUntimedBlockRamInput(c, ports.get(to).getName(), bramRegisters)) {
                        timed.add(to);
                    }
                }
                tos = timed.isEmpty() ? null : timed;
            }
            if (froms == null || tos == null) {
                continue;
            }
            if (registersDisabled && (role == Role.CLK_TO_Q || role.isSetupCheck())
                    || dspRegisters != null && role.isSetupCheck()
                    && isUnusedClockEnable(cellType, key.getTo(), dspRegisters)) {
                continue;
            }
            if (dsp != null && role == Role.COMB && !dsp.keepsArc(dspType(cellType), key.getFrom(), key.getTo())) {
                continue;
            }
            if (isUnusedDspPin(cellType, role.isSetupCheck() ? key.getTo() : key.getFrom(), attrs)
                    || role == Role.CLK_TO_Q && isClockToOutputDisabled(cellType, key.getTo(), attrs)) {
                continue;
            }
            Boolean registered = isRegisteredInput(cellType, role.isSetupCheck() ? key.getTo() : key.getFrom(), attrs);
            if (registered != null && registered != role.isSetupCheck()) {
                continue;
            }
            if (role.isSetupCheck()) {
                FittedCellArcLibrary.Result r = lib.lookup(cellType, bel, site, role, key.getFrom(), -1, key.getTo(),
                        -1, attrs, null, null);
                if (r.getArc() != null && r.getMismatch() < FittedCellArcLibrary.REGISTER_MISMATCH) {
                    // The clock port of the check, for the hard blocks with several clocks
                    String clockPort = slice ? null : ports.get(froms.get(0)).getName();
                    for (int to : tos) {
                        if (ports.get(to).isInput()) {
                            ca.forceEndpoint(to, clockPort);
                        }
                    }
                }
                continue;
            }
            byte kind = role == Role.CLK_TO_Q ? StaGraph.CLK_TO_Q : StaGraph.CELL_COMB;
            if (slice) {
                for (int from : froms) {
                    EDIFPortInst fp = ports.get(from);
                    if (!fp.isInput()) continue;
                    if (lutInputs >= 0 && !LutInputs.isUsed(fp.getName(), lutInputs)) continue;
                    for (int to : tos) {
                        EDIFPortInst tp = ports.get(to);
                        if (!tp.isOutput()) continue;
                        if (isLutCarryInput(cellType, fp.getName(), tp.getName())) continue;
                        FittedCellArcLibrary.Result r = lib.lookup(cellType, bel, site, role, key.getFrom(), -1,
                                key.getTo(), -1, attrs, busBase(fp.getName()), busBase(tp.getName()));
                        if (r.getArc() == null || r.getMismatch() >= FittedCellArcLibrary.REGISTER_MISMATCH) continue;
                        addArc(ca, from, to, kind, r.getDelays(), cellType, r.getArc(), category, role);
                        numCellArcs.incrementAndGet();
                    }
                }
                continue;
            }
            FittedCellArcLibrary.Result r = lib.lookup(cellType, bel, site, role, key.getFrom(), -1, key.getTo(), -1,
                    attrs, null, null);
            if (r.getArc() == null || r.getMismatch() >= FittedCellArcLibrary.REGISTER_MISMATCH) continue;
            addBusArcs(ca, r.getArc(), froms, tos, kind, cellType, category, role);
        }
    }

    /**
     * Gets the delays of the combinational arc of a LUT cell from one of its inputs to one of its outputs,
     * as {@link #addCellArcs} gives them, but with the input on the given BEL pin: e.g. after LUT
     * pin swapping moved the input to another pin of the LUT.
     * @param c The LUT cell, placed on a LUT BEL.
     * @param input The logical input pin, e.g. I0.
     * @param inputBelPin The BEL pin of the input, e.g. A6.
     * @param output The logical output pin, e.g. O.
     * @return The delays at the max, min and second min (if any, else the min) corners, in ps; null if there is
     * no such arc.
     */
    public float[] getLutArcDelays(Cell c, String input, String inputBelPin, String output) {
        SiteInst si = c.getSiteInst();
        String outputBelPin = c.getPhysicalPinMapping(output);
        if (c.getBEL() == null || si == null || outputBelPin == null) {
            return null;
        }
        String cellType = c.getType();
        String bel = c.getBELName();
        String site = si.getSiteTypeEnum().name();
        boolean lut = FittedCellArcLibrary.isLUTOnLUTBEL(cellType, bel);
        // The configuration of the cell, as for its other arcs (see addCellArcs())
        Set<String> cfgKeys = new LinkedHashSet<>(lib.getConfigKeys(lut ? null : cellType));
        cfgKeys.addAll(getRegisterProperties(cellType));
        Map<String, String> attrs = getProperties(series, c, cfgKeys);
        FittedCellArcLibrary.Result r = lib.lookup(cellType, bel, site, Role.COMB, inputBelPin, -1, outputBelPin, -1,
                attrs, busBase(input), busBase(output));
        if (r.getArc() == null || r.getMismatch() >= FittedCellArcLibrary.REGISTER_MISMATCH) {
            return null;
        }
        String category = FittedCellArcLibrary.getCategory(cellType, site);
        float[] d = r.getDelays();
        float max = scaleArc(d, maxIdx, cellType, r.getArc(), category, Role.COMB);
        float min = scaleArc(d, minIdx, cellType, r.getArc(), category, Role.COMB);
        return new float[]{max, min,
                min2Idx >= 0 ? scaleArc(d, min2Idx, cellType, r.getArc(), category, Role.COMB) : min};
    }

    /**
     * Gets the intra-site delays that the net arcs charge a net arc from an input site pin to a BEL
     * pin that the site pin drives, as part of the arc's fixed delays: e.g. for a LUT input that LUT pin swapping
     * moved to another pin of the LUT.
     * @param siteInst The site instance of the sink.
     * @param sitePinName The name of the input site pin, e.g. A6.
     * @param belPin The BEL pin that the site pin drives, e.g. A6LUT/A6.
     * @return The delays at the max, min and second min (if any, else the min) corners, in ps; null if
     * none.
     */
    public float[] getSinkIntraSiteDelays(SiteInst siteInst, String sitePinName, BELPin belPin) {
        // As getNetArc() charges them
        float[] d = new float[3];
        config(siteInst.getSiteTypeEnum().name(), sitePinName, belPinName(belPin), d);
        return d;
    }

    /** Adds the arcs between the pins of a from bus and a to bus, through a hub where exact */
    private void addBusArcs(CellArcs ca, FittedCellArcLibrary.Arc arc, List<Integer> froms, List<Integer> tos,
                            byte kind, String cellType, String category, Role role) {
        List<EDIFPortInst> ports = ca.getPortInsts();
        List<Integer> ins = new ArrayList<>(froms.size());
        for (int i : froms) {
            if (ports.get(i).isInput()) ins.add(i);
        }
        List<Integer> outs = new ArrayList<>(tos.size());
        for (int i : tos) {
            if (ports.get(i).isOutput()) outs.add(i);
        }
        if (ins.isEmpty() || outs.isEmpty()) {
            return;
        }
        boolean byPair = (arc.dependsOnFromBit() && arc.dependsOnToBit()) || ins.size() == 1 || outs.size() == 1;
        if (byPair) {
            for (int from : ins) {
                int fb = busBit(ports.get(from).getName());
                for (int to : outs) {
                    addArc(ca, from, to, kind, arc.getDelays(fb, busBit(ports.get(to).getName())), cellType, arc,
                            category, role);
                    numCellArcs.incrementAndGet();
                }
            }
            return;
        }
        int hub = ca.getPseudoPin(arc.getFrom() + "->" + arc.getTo() + ":" + role);
        for (int from : ins) {
            if (arc.dependsOnFromBit()) {
                addArc(ca, from, hub, kind, arc.getDelays(busBit(ports.get(from).getName()), -1), cellType, arc,
                        category, role);
            } else {
                ca.addArc(from, hub, kind, 0f, 0f, 0f);
            }
        }
        for (int to : outs) {
            if (!arc.dependsOnFromBit()) {
                addArc(ca, hub, to, StaGraph.CELL_COMB, arc.getDelays(-1, busBit(ports.get(to).getName())), cellType,
                        arc, category, role);
            } else {
                ca.addArc(hub, to, StaGraph.CELL_COMB, 0f, 0f, 0f);
            }
        }
        numCellArcs.addAndGet(ins.size() + outs.size());
    }

    // ------------------------------------------------------------------------------------------
    // Net delays
    // ------------------------------------------------------------------------------------------

    private static String belPinName(BELPin pin) {
        return pin.getBELName() + "/" + pin.getName();
    }

    /** Gets a configuration delay, scaled, at the max (0), min (1) and second min (2, if any) corners */
    private void config(String site, String from, String to, float[] out) {
        FittedCellArcLibrary.ConfigResult r = lib.config(site, FittedCellArcLibrary.normalizeConfigPin(site, from),
                FittedCellArcLibrary.normalizeConfigPin(site, to), "");
        if (r.getDelays() == null) {
            numMissingConfig.incrementAndGet();
            out[0] = 0;
            out[1] = 0;
            out[2] = 0;
            return;
        }
        out[0] = lib.scaleConfig(r, deviceName, speedGrade, maxIdx);
        out[1] = lib.scaleConfig(r, deviceName, speedGrade, minIdx);
        out[2] = min2Idx < 0 ? out[1] : lib.scaleConfig(r, deviceName, speedGrade, min2Idx);
    }

    /**
     * Checks if an arc of a Versal LUT with carry (LUTCY1, LUTCY2, the halves of a LUT6CY) goes from its I4
     * input to its propagate or generate output (PROP, GE), which only depend on I0-I3: Vivado has no such
     * arc (I4 is the LUT's carry input, used by its O output only).
     */
    static boolean isLutCarryInput(String cellType, String in, String out) {
        return (cellType.equals("LUTCY1") || cellType.equals("LUTCY2")) && in.equals("I4")
                && (out.equals("PROP") || out.equals("GE"));
    }

    /**
     * The ports of the clock input that drives an internal clock pin of a cell through a combinational arc,
     * null if none.
     */
    private static List<Integer> clockPortOf(Set<ArcKey> keys, String internalClock,
                                             Map<String, List<Integer>> pinPorts) {
        for (ArcKey k : keys) {
            if (k.getRole() == Role.COMB && k.getTo().equals(internalClock) && pinPorts.containsKey(k.getFrom())) {
                return pinPorts.get(k.getFrom());
            }
        }
        return null;
    }

    @Override
    public boolean getNetArc(NetArc arc) {
        Cell srcCell = arc.sourceCell;
        Cell dstCell = arc.sinkCell;
        if (srcCell == null || dstCell == null || srcCell.getSiteInst() == null || dstCell.getSiteInst() == null) {
            return false;
        }
        float[] d = new float[3];
        if (arc.intraSiteNet || arc.sinkSitePin == null) {
            if (arc.sourceBELPin != null && arc.sinkBELPin != null) {
                config(dstCell.getSiteInst().getSiteTypeEnum().name(), belPinName(arc.sourceBELPin),
                        belPinName(arc.sinkBELPin), d);
            }
            arc.fixedMax = d[0];
            arc.fixedMin = d[1];
            arc.fixedMin2 = d[2];
            arc.intraSiteMax = d[0];
            arc.routeMax = 0;
            arc.routeMin = 0;
            return true;
        }
        SitePinInst sink = arc.sinkSitePin;
        SitePinInst source = arc.sourceSitePin;
        float fixedMax = 0;
        float fixedMin = 0;
        float fixedMin2 = 0;
        if (arc.sinkBELPin != null) {
            config(sink.getSiteInst().getSiteTypeEnum().name(), sink.getName(), belPinName(arc.sinkBELPin), d);
            fixedMax += d[0];
            fixedMin += d[1];
            fixedMin2 += d[2];
        }
        float routeMax;
        float routeMin;
        float routeMin2 = Float.NaN;
        SitePinInst routedSource = null;
        if (source == null) {
            routeMax = 0;
            routeMin = 0;
        } else {
            NetRouting nr = getRouting(arc);
            Node end = walkToSource(nr, sink);
            routedSource = end == null ? null : getRoutedSource(end, source, arc.net);
            if (routedSource == null) {
                routedSource = getUnroutedSource(arc.net, source, sink);
                // (The path or the INT tiles of the estimate are the same at every corner)
                List<Node> direct = getDirectConnectionPath(routedSource, sink);
                Tile s = null;
                Tile t = null;
                if (direct == null) {
                    if (nr.tileSource != routedSource) {
                        nr.tileSource = routedSource;
                        nr.sourceTile = ClassBasedDelayEstimator.getInterconnectTile(routedSource);
                    }
                    s = nr.sourceTile;
                    t = ClassBasedDelayEstimator.getInterconnectTile(sink);
                }
                routeMax = estimateRouteDelay(maxEstimator, routedSource, sink, direct, s, t);
                routeMin = minEstimator == maxEstimator ? routeMax
                        : estimateRouteDelay(minEstimator, routedSource, sink, direct, s, t);
                if (min2Estimator != null) {
                    routeMin2 = estimateRouteDelay(min2Estimator, routedSource, sink, direct, s, t);
                }
            } else {
                routeMax = routeDelay(maxEstimator, nr, sink, routedSource);
                routeMin = minEstimator == maxEstimator ? routeMax : routeDelay(minEstimator, nr, sink, routedSource);
                if (min2Estimator != null) {
                    // (The hold analysis of the second min corner starts with the routes' own delays)
                    routeMin2 = routeDelay(min2Estimator, nr, sink, routedSource);
                }
            }
        }
        if (routedSource != null && arc.sourceBELPin != null) {
            config(routedSource.getSiteInst().getSiteTypeEnum().name(), belPinName(arc.sourceBELPin),
                    routedSource.getName(), d);
            fixedMax += d[0];
            fixedMin += d[1];
            fixedMin2 += d[2];
        }
        float[] clockRoute = getClockRouteDelays(arc.net, sink, cacheOf(arc));
        if (clockRoute != null) {
            routeMax = clockRoute[maxCorner.ordinal()];
            routeMin = clockRoute[minCorner.ordinal()];
            if (min2Corner != null) {
                routeMin2 = clockRoute[min2Corner.ordinal()];
            }
        }
        arc.fixedMax = fixedMax;
        arc.fixedMin = fixedMin;
        arc.fixedMin2 = fixedMin2;
        arc.intraSiteMax = fixedMax;
        arc.routeMax = routeMax;
        arc.routeMin = routeMin;
        arc.routeMin2 = routeMin2;
        return true;
    }

    /**
     * Gets the route delays of a sink of a clock net (that of a clock buffer used as data, the only clock nets
     * that the timing graph times) from its route on the global clock network, as the clock timing times clock
     * routes, rather than with the delay estimators of the general interconnect.
     * @return The delays by corner ordinal, or null if the net is no clock net, the device has no clock data or
     * the net's routing does not reach the sink.
     * @param cache The routing cache to use, or null for the provider's.
     */
    private float[] getClockRouteDelays(Net net, SitePinInst sink, NetRouting cache) {
        if (!net.isClockNet() || !net.hasPIPs()) {
            return null;
        }
        ClockNodeDelayModel model = getClockNodeModel();
        if (model == null) {
            return null;
        }
        NetRouting nr = cache != null ? update(cache, net) : getRouting(net);
        if (nr.clockRoute == null) {
            nr.clockRoute = new ClockRoute(net, model, design);
        }
        return nr.clockRoute.getDelays(sink);
    }

    /**
     * Gets the route delays of a routed connection at the provider's max and min corners (and second min
     * corner, if any), from the routing of its net, as {@link #getNetArc(NetArc)} computes them (pin delays
     * included), e.g. to update the min delays of the routes of a router once routed.
     * @param net The net.
     * @param source The source site pin of the connection.
     * @param sink The sink site pin of the connection.
     * @return The max and min (and second min) route delays in ps, or null if the net's routing does not
     * reach the sink from the source's site.
     * @see #invalidateRouting()
     */
    public float[] getRoutedDelays(Net net, SitePinInst source, SitePinInst sink) {
        // (The routing of the last net looked at is cached: see invalidateRouting())
        return getRoutedDelays(net, source, sink, null);
    }

    /**
     * Gets the min route delays of routed connections, as {@link #getRoutedDelays(Net, SitePinInst, SitePinInst)}
     * gives them for each connection from the source of its sink's net, in parallel chunks of connections if
     * parallel processing is enabled (see {@link ParallelismTools#getParallel()}).
     * @param sinks The sink site pins of the connections (null for none).
     * @param min Output: the route delays at the min corner, NaN where none (no net, or not routed).
     * @param min2 Output: those at the second min corner, NaN where none or if the provider has no second min
     * corner.
     */
    public void getRoutedMinDelays(SitePinInst[] sinks, float[] min, float[] min2) {
        final int chunk = 4096;
        List<Future<?>> futures = new ArrayList<>();
        for (int from = 0; from < sinks.length; from += chunk) {
            final int f = from;
            final int t = Math.min(sinks.length, from + chunk);
            futures.add(ParallelismTools.submit(() -> {
                // (Each chunk with a cache of the routing of the net it last looked at)
                NetRouting cache = new NetRouting();
                for (int c = f; c < t; c++) {
                    Net net = sinks[c] == null ? null : sinks[c].getNet();
                    float[] d = net == null ? null : getRoutedDelays(net, net.getSource(), sinks[c], cache);
                    min[c] = d == null ? Float.NaN : d[1];
                    min2[c] = d == null || d.length < 3 ? Float.NaN : d[2];
                }
            }));
        }
        for (Future<?> future : futures) {
            ParallelismTools.get(future);
        }
    }

    /** As {@link #getRoutedDelays(Net, SitePinInst, SitePinInst)}, with a routing cache (null for the provider's) */
    private float[] getRoutedDelays(Net net, SitePinInst source, SitePinInst sink, NetRouting cache) {
        if (net == null || source == null || sink == null) {
            return null;
        }
        float[] clockRoute = getClockRouteDelays(net, sink, cache);
        if (clockRoute != null) {
            float[] d = {clockRoute[maxCorner.ordinal()], clockRoute[minCorner.ordinal()]};
            return min2Corner == null ? d : new float[]{d[0], d[1], clockRoute[min2Corner.ordinal()]};
        }
        NetRouting nr = cache != null ? update(cache, net) : getRouting(net);
        Node end = walkToSource(nr, sink);
        SitePinInst routedSource = end == null ? null : getRoutedSource(end, source, net);
        if (routedSource == null) {
            return null;
        }
        float max = routeDelay(maxEstimator, nr, sink, routedSource);
        float min = minEstimator == maxEstimator ? max : routeDelay(minEstimator, nr, sink, routedSource);
        if (min2Estimator != null) {
            return new float[]{max, min, routeDelay(min2Estimator, nr, sink, routedSource)};
        }
        return new float[]{max, min};
    }

    /**
     * Discards the cached routing of the net last looked at: to call after changing the routing of a net
     * (e.g. rerouting it, even with as many PIPs) before {@link #getRoutedDelays}, and once done with the
     * provider's net delays (the cache holds the net, hence its design).
     */
    public void invalidateRouting() {
        routing = new NetRouting();
    }

    private NetRouting getRouting(Net net) {
        return update(routing, net);
    }

    /** The routing of a net arc's net, cached in the arc */
    private static NetRouting getRouting(NetArc arc) {
        return update(cacheOf(arc), arc.net);
    }

    /** The routing cache of a net arc (see {@link NetArc#scratch}) */
    private static NetRouting cacheOf(NetArc arc) {
        if (!(arc.scratch instanceof NetRouting)) {
            arc.scratch = new NetRouting();
        }
        return (NetRouting) arc.scratch;
    }

    /** Makes a routing cache that of a net (as now routed) */
    private static NetRouting update(NetRouting nr, Net net) {
        if (nr.net != net || net.getPIPs().size() != nr.numPIPs) {
            nr.net = net;
            nr.numPIPs = net.getPIPs().size();
            nr.clockRoute = null;
            nr.nodeToNumLoads = null;
            nr.tileSource = null;
            nr.sourceTile = null;
            if (net.hasPIPs()) {
                nr.nodeToDriver = NetTools.getNodeToDriver(net);
                nr.nodeToNumBranches = new HashMap<>();
                for (Node driver : nr.nodeToDriver.values()) {
                    nr.nodeToNumBranches.merge(driver, 1, Integer::sum);
                }
            } else {
                nr.nodeToDriver = Collections.emptyMap();
                nr.nodeToNumBranches = Collections.emptyMap();
            }
        }
        return nr;
    }

    /** Walks the routing from a sink site pin to its source node, or returns null if unrouted. */
    private static Node walkToSource(NetRouting nr, SitePinInst sink) {
        Node node = sink.getConnectedNode();
        if (node == null || nr.nodeToDriver.isEmpty()) {
            return null;
        }
        int maxHops = nr.nodeToDriver.size() + 1;
        while (maxHops-- > 0) {
            Node driver = nr.nodeToDriver.get(node);
            if (driver == null) {
                return node;
            }
            node = driver;
        }
        return null;
    }

    /** The output site pin of the source's site that a route starts from, or null if none */
    private static SitePinInst getRoutedSource(Node node, SitePinInst source, Net net) {
        for (SitePinInst s : new SitePinInst[]{source, net.getSource(), net.getAlternateSource()}) {
            if (s != null && node.equals(s.getConnectedNode())) {
                return s;
            }
        }
        SitePin sitePin = node.getSitePin();
        if (sitePin != null && sitePin.getSite().equals(source.getSite())
                && sitePin.getSite().isOutputPin(sitePin.getPinName())) {
            SitePinInst spi = source.getSiteInst().getSitePinInst(sitePin.getPinName());
            return spi != null ? spi : source;
        }
        return null;
    }

    /**
     * Gets the number of a net's sink pins that its routing reaches through each node, for the nodes that lead
     * to more than one (see {@link DelayEstimator#getFanoutLoadDelay(Node, int)}).
     */
    private static Map<Node, Integer> getNumLoads(NetRouting nr) {
        if (nr.nodeToNumLoads == null) {
            Map<Node, Integer> all = new HashMap<>();
            for (SitePinInst p : nr.net.getPins()) {
                if (p.isOutPin()) continue;
                Node node = p.getConnectedNode();
                if (node == null || !nr.nodeToDriver.containsKey(node)) continue;
                int maxHops = nr.nodeToDriver.size() + 1;
                while (node != null && maxHops-- > 0) {
                    all.merge(node, 1, Integer::sum);
                    node = nr.nodeToDriver.get(node);
                }
            }
            all.values().removeIf(n -> n < 2);
            nr.nodeToNumLoads = all;
        }
        return nr.nodeToNumLoads;
    }

    /** The route delay of a routed connection, see the class description */
    private static float routeDelay(DelayEstimator estimator, NetRouting nr, SitePinInst sink, SitePinInst source) {
        float delay = 0;
        Node node = sink.getConnectedNode();
        Map<Node, Integer> loads = estimator.hasFanoutDelays() ? getNumLoads(nr) : Collections.emptyMap();
        int maxHops = nr.nodeToDriver.size() + 1;
        while (maxHops-- > 0) {
            Node driver = nr.nodeToDriver.get(node);
            if (driver == null) {
                break;
            }
            if (estimator.isDelayCharged(node)) {
                delay += estimator.getDelayOf(node);
            }
            delay += estimator.getExtraDelay(driver, node);
            Integer branches = nr.nodeToNumBranches.get(driver);
            if (branches != null) {
                delay += estimator.getFanoutDelay(driver, branches);
            }
            Integer n = loads.get(node);
            if (n != null) {
                delay += estimator.getFanoutLoadDelay(node, n);
            }
            node = driver;
        }
        if (estimator.isDelayCharged(node)) {
            delay += estimator.getDelayOf(node);
        }
        Integer n = loads.get(node);
        if (n != null) {
            delay += estimator.getFanoutLoadDelay(node, n);
        }
        return delay + estimator.getPinDelay(source, sink);
    }

    /**
     * The source site pin that a router routes an unrouted connection from: the net's alternate source if the source
     * has no INT projection (e.g. a carry output) and the sink is a pin of the interconnect, else the source.
     */
    private static SitePinInst getUnroutedSource(Net net, SitePinInst source, SitePinInst sink) {
        SitePinInst alternate = net.getAlternateSource();
        if (alternate == null || alternate == source || RouterHelper.projectOutputPinToINTNode(source) != null
                || RouterHelper.projectInputPinToINTNode(sink) == null
                || RouterHelper.projectOutputPinToINTNode(alternate) == null) {
            return source;
        }
        return alternate;
    }

    /**
     * The route delay of an unrouted connection, see the class description.
     * @param direct The path of the connection if it is direct (see {@link #getDirectConnectionPath}), else null.
     * @param s The INT tile of the source if the connection is not direct.
     * @param t The INT tile of the sink if the connection is not direct.
     */
    private static float estimateRouteDelay(DelayEstimator estimator, SitePinInst source, SitePinInst sink,
                                            List<Node> direct, Tile s, Tile t) {
        if (direct != null) {
            // With the pin delays, as routeDelay() charges them once routed
            return getDirectConnectionDelay(estimator, direct) + estimator.getPinDelay(source, sink);
        }
        // Device-wide coordinates: on devices whose SLRs have local coordinates, those of two SLRs do not compare
        SlrCoordinates g = SlrCoordinates.of(s.getDevice());
        int dx = Math.abs(g.x(s) - g.x(t));
        int dy = Math.abs(g.y(s) - g.y(t));
        // The lookahead delays are in units of 100 ps
        return estimator.getPreRouteBaseDelay()
                + 100f * (dx * estimator.getLookaheadDelayPerTileX() + dy * estimator.getLookaheadDelayPerTileY())
                + estimator.getPinDelay(source, sink);
    }

    /**
     * The path of a direct connection (one that the router routes through dedicated resources, e.g. a carry or
     * DSP cascade) between its site pins: empty if both pins are on the same node; null if not direct.
     */
    private static List<Node> getDirectConnectionPath(SitePinInst source, SitePinInst sink) {
        if (source.getNet() != null && source.getNet().isClockNet()) {
            // A clock net (of a clock buffer used as data) goes through the global clock network
            return null;
        }
        Node sourceNode = source.getConnectedNode();
        Node sinkNode = sink.getConnectedNode();
        if (sourceNode == null || sinkNode == null) {
            return null;
        }
        if (RouterHelper.projectInputPinToINTNode(sink) != null) {
            // A sink of the interconnect: not reached through dedicated resources only, even from a source without
            // INT projection (e.g. a carry output, which a router routes from another output of its site)
            return null;
        }
        List<Node> path = RouterHelper.findPathBetweenNodes(sourceNode, sinkNode);
        if (path.isEmpty()) {
            return sourceNode.equals(sinkNode) ? path : null;
        }
        return path;
    }

    /** The route delay of the path of a direct connection (see {@link #getDirectConnectionPath}) */
    private static float getDirectConnectionDelay(DelayEstimator estimator, List<Node> path) {
        float delay = 0;
        for (int i = 0; i < path.size(); i++) {
            Node node = path.get(i);
            if (estimator.isDelayCharged(node)) {
                delay += estimator.getDelayOf(node);
            }
            if (i + 1 < path.size()) {
                delay += estimator.getExtraDelay(path.get(i + 1), node);
            }
        }
        return delay;
    }
}

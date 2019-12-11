/*
 * Copyright (c) 2019 Xilinx, Inc.
 * All rights reserved.
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

package com.xilinx.rapidwright.timing;

import com.xilinx.rapidwright.design.Cell;
import com.xilinx.rapidwright.design.Design;
import com.xilinx.rapidwright.design.DesignTools;
import com.xilinx.rapidwright.design.Net;
import com.xilinx.rapidwright.design.SitePinInst;
import com.xilinx.rapidwright.device.BELPin;
import com.xilinx.rapidwright.device.Device;
import com.xilinx.rapidwright.device.IntentCode;
import com.xilinx.rapidwright.device.Node;
import com.xilinx.rapidwright.device.PIP;
import com.xilinx.rapidwright.device.PIPType;
import com.xilinx.rapidwright.device.Site;
import com.xilinx.rapidwright.device.Tile;
import com.xilinx.rapidwright.device.TileTypeEnum;
import com.xilinx.rapidwright.device.Wire;
import com.xilinx.rapidwright.edif.EDIFPortInst;
import com.xilinx.rapidwright.util.FileTools;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.LinkedList;
import java.util.Arrays;
import java.util.Set;

/**
 * A TimingModel calculates net delay by implementing the lightweight timing model described in our 
 * FPT'19 paper.
 */
public class TimingModel {

    private Design design;
    public boolean debug = false;
    public boolean debugFile = false;
    public boolean verbose = false;

    public static final String TIMING_DATA_DIR = 
            FileTools.DATA_FOLDER_NAME + File.separator + "timing";
    
    boolean adjustQuadConnectedToQuadDelays = false;
    boolean adjustDoubleConnectedToDoubleDelays = false;

    private DelayModel intrasiteAndLogicDelayModel;

    // some default values, these can be overwritten later by reading in a delay_terms.dat file
    // the code is using hard coded enumerated types, and this will be switched over to use these variables
    int START_TILE_ROW =		1;
    int START_TILE_COL =		52;

    // these are initialized to some defaults for example, however, these will be set based on 
    // reading in the intersite_delay_terms.txt
    float INTRASITE_DELAY_SITEPIN_TO_LUT_INPUT = 	0.f;
    float INTRASITE_DELAY_LUT_OUTPUT_TO_O_SITEPIN = 0.f;
    float INTRASITE_DELAY_SITEPIN_TO_FF_INPUT = 	100.f;
    float INTRASITE_DELAY_FF_INPUT_TO_SITEPIN = 	0.f;
    float INTRASITE_DELAY_LUT_OUTPUT_TO_FF_INPUT = 48.f;
    float INTRASITE_DELAY_LUT_OUTPUT_TO_MUX_SITEPIN = 60.f;
    public float BOUNCE_DELAY	=	    46.f;
    float L_HORIZONTAL_BOUNCE =0.f;
    float L_HORIZONTAL_INTERNAL =0.f;
    float L_HORIZONTAL_SINGLE=	1.f;
    float L_HORIZONTAL_DOUBLE=	2.f;
    float L_HORIZONTAL_QUAD=	6.f;
    float L_HORIZONTAL_LONG=	12.f;
    float L_HORIZONTAL_GLOBAL=	15.f;
    float L_VERTICAL_SINGLE=	1.f;
    float L_VERTICAL_DOUBLE=	3.f;
    float L_VERTICAL_QUAD=		6.f;
    float L_VERTICAL_LONG=		12.f;
    float K0_HORIZONTAL=		46.0f;
    float K1_HORIZONTAL=		4.5f;
    float K2_HORIZONTAL_SINGLE=	2.4f;
    float K2_HORIZONTAL_DOUBLE=	2.4f;
    float K2_HORIZONTAL_QUAD=	2.9f;
    float K2_HORIZONTAL_LONG=	1.2f;
    float K2_HORIZONTAL_GLOBAL=	2.4f;
    float K0_VERTICAL=		43.0f;
    float K1_VERTICAL=		3.7f;
    float K2_VERTICAL_SINGLE=	14.5f;
    float K2_VERTICAL_DOUBLE=	5.6f;
    float K2_VERTICAL_QUAD	=9.5f;
    float K2_VERTICAL_LONG	=4.0f;
    float RCLK_SINGLE_AND_DOUBLE	=3.f;
    float RCLK_QUAD=		3.f;
    float RCLK_LONG=		3.f;
    float DSP_SINGLE_AND_DOUBLE=	3.f;
    float DSP_QUAD =		3.f;
    float DSP_LONG	=	3.f;
    float BRAM_SINGLE_AND_DOUBLE=	16.f;
    float BRAM_QUAD=		16.f;
    float BRAM_LONG=		16.f;
    float CFRM_SINGLE_AND_DOUBLE=	33.f;
    float CFRM_QUAD=		33.f;
    float CFRM_LONG=		33.f;
    float URAM_SINGLE_AND_DOUBLE=	34.f;
    float URAM_QUAD=		34.f;
    float URAM_LONG=		34.f;
    float PCIE_SINGLE_AND_DOUBLE=	62.f;
    float PCIE_QUAD=		62.f;
    float PCIE_LONG=		62.f;
    float IO_SINGLE_AND_DOUBLE=	86.f;
    float IO_QUAD=			68.f;
    float IO_LONG=			186.f;

    public float LOGIC_FF_DELAY = 78f;
    public float CARRY_CO_DELAY = 216f;
    public float LOGIC_LUT_A1_DELAY = 150f;
    public float LOGIC_LUT_A2_DELAY = 125f;
    public float LOGIC_LUT_A3_DELAY = 100f;
    public float LOGIC_LUT_A4_DELAY = 90f;
    public float LOGIC_LUT_A5_DELAY = 53f;
    public float LOGIC_LUT_A6_DELAY = 38f;
    public float SITEPIN_A1_DELAY = 74f;
    public float SITEPIN_A2_DELAY = 53f;
    public float SITEPIN_A3_DELAY = 48f;
    public float SITEPIN_A4_DELAY = 44f;
    public float SITEPIN_A5_DELAY = 44f;
    public float SITEPIN_A6_DELAY = 43f;
    public float SITEPIN_A_I_DELAY = 65f;
    public float SITEPIN_AX_DELAY = 65f;

    public int NEAR_MIN = 1;
    public int NEAR_MAX = 2;
    public int MID_MIN = 4;
    public int MID_MAX = 4;
    public int FAR_MIN = 12;
    public int FAR_MAX = 12;

    private TimingManager timingManager;
    private Tile[] goodRowTypes;
    private PrintStream printStream;
    private Device device;

    public HashMap<String, List<TimingGroup>> forDebugTimingGroupByPorts;

    private static HashSet<String> ultraScaleFlopNames;
    private static HashSet<String> xPinNames;
    private static HashSet<String> iPinNames;
    static {
    	ultraScaleFlopNames = new HashSet<String>();
        ultraScaleFlopNames.add("AFF");
        ultraScaleFlopNames.add("AFF2");
        ultraScaleFlopNames.add("BFF");
        ultraScaleFlopNames.add("BFF2");
        ultraScaleFlopNames.add("CFF");
        ultraScaleFlopNames.add("CFF2");
        ultraScaleFlopNames.add("DFF");
        ultraScaleFlopNames.add("DFF2");
        ultraScaleFlopNames.add("EFF");
        ultraScaleFlopNames.add("EFF2");
        ultraScaleFlopNames.add("FFF");
        ultraScaleFlopNames.add("FFF2");
        ultraScaleFlopNames.add("GFF");
        ultraScaleFlopNames.add("GFF2");
        ultraScaleFlopNames.add("HFF");
        ultraScaleFlopNames.add("HFF2");
        
        xPinNames = new HashSet<String>();
        xPinNames.add("AX");
        xPinNames.add("BX");
        xPinNames.add("CX");
        xPinNames.add("DX");
        xPinNames.add("EX");
        xPinNames.add("FX");
        xPinNames.add("GX");
        xPinNames.add("HX");
        
        iPinNames = new HashSet<String>();
        iPinNames.add("A_I");
        iPinNames.add("B_I");
        iPinNames.add("C_I");
        iPinNames.add("D_I");
        iPinNames.add("E_I");
        iPinNames.add("F_I");
        iPinNames.add("G_I");
        iPinNames.add("H_I");

    }
    
    /**
     * A TimingModel is the object for calculating the net delay between two pins on a net.
     * @param design A RapidWright Design object.
     */
    public TimingModel(Design design) {
        this.design = design;
        this.device = design.getDevice();
    }

    /**
     * This performs the initialization of the timing model.  Based on the selected device some data 
     * structures for the model are initialized.
     */
    public void build() {
        if (design == null) {
            throw new RuntimeException("Error: Design is null when building the TimingModel.");
        }
        forDebugTimingGroupByPorts = new LinkedHashMap<>();
        String series = device.getSeries().name().toLowerCase();
        String fileName = TimingModel.TIMING_DATA_DIR + File.separator +series+
                File.separator + "intersite_delay_terms.txt";
        if (!readDelayTerms(fileName)) {
        	throw new RuntimeException("Error reading file:" + fileName);
        }
        intrasiteAndLogicDelayModel = DelayModelBuilder.getDelayModel(series);

        // create a good row for netDelay model, in terms of capturing resource types within a row
        Tile[][] tiles = this.design.getDevice().getTiles();
        HashMap<TileTypeEnum, Integer> tiletypes = new LinkedHashMap<>();
        goodRowTypes = new Tile[tiles[1].length];

        for (int u = START_TILE_ROW; u< tiles.length; u++) { // start at row START_TILE_ROW
            boolean consecutiveTilesNonNull = true;
            for (int v = 0; v< tiles[1].length; v++) {

                Tile t = tiles[u][v];
                TileTypeEnum tte = t.getTileTypeEnum();

                if (tte == TileTypeEnum.NULL)
                    consecutiveTilesNonNull = false;
                else {
                    if ( goodRowTypes[v] == null)
                        goodRowTypes[v] = tiles[u][v];
                }
            }
            if (consecutiveTilesNonNull) {
                if (verbose)
                    System.out.println("Found good consecutive row at:"+u);
            }
        }
        for (Tile tile : goodRowTypes) {
            if (tile != null) {
                TileTypeEnum tte = tile.getTileTypeEnum();

                if (tiletypes.get(tte) == null) {
                    tiletypes.put(tte, 1);
                } else {
                    tiletypes.put(tte, tiletypes.get(tte) + 1);
                }
            }
        }
        buildDistArrays(tiles[0].length , tiles.length);
    }

    /**
     * Calculates the delay in picoseconds between a pair of pins on a physical "Net" object.
     * @param startPinInst Source SitePinInst from the Net.
     * @param endPinInst A selected sink SitePinInst from the Net.
     * @param net RapidWright physical "Net" object.
     * @return The estimated delay in picoseconds.
     */
    public float calcDelay(SitePinInst startPinInst, SitePinInst endPinInst,  Net net) {
        Site startSite = (startPinInst != null)? startPinInst.getSite() : null;
        Site endSite = (endPinInst != null)? endPinInst.getSite() : null;
        return calcDelay(startPinInst, endPinInst, null, null, startSite, endSite,  net);
    }

    private List<Node> nodeList;
    private List<PIP> relevantPIPs;


    /**
     * Calculates the delay in picoseconds between a pair of pins on a physical "Net" object.
     * @param startPinInst Source SitePinInst from the Net.
     * @param endPinInst A selected sink SitePinInst from the Net.
     * @param startSite The site containing the source SitePinInst.
     * @param endSite The site containing the sink SitePinInst.
     * @param net RapidWright physical "Net" object.
     * @return The estimated delay in picoseconds.
     */
    public float calcDelay(SitePinInst startPinInst, SitePinInst endPinInst, BELPin sourceBELPin, 
                           BELPin sinkBELPin, Site startSite, Site endSite, Net net) {
        ArrayList<IntentCode> intentCodes = new ArrayList<>();
        HashMap<PIPType, Integer> pipTypes = new LinkedHashMap<>();

        nodeList = new ArrayList<>();
        relevantPIPs = new ArrayList<>();

        determineNodeList(net, startPinInst, endPinInst);

        for (PIP p : relevantPIPs) {
            int tmp = 0;
            if (pipTypes.get(p.getPIPType()) != null) {
                tmp = pipTypes.get(p.getPIPType());
                pipTypes.put(p.getPIPType(), ++tmp);
            } else {
                pipTypes.put(p.getPIPType(), 1);
            }
        }

        List<IntentCode> nodeIntents = new ArrayList<>();
        List<Wire> node_start_wires = new ArrayList<>();

        for (Node node : nodeList) {
            node_start_wires.add(node.getAllWiresInNode()[0]);
        }

        String[] ptkeys = new String[pipTypes.size()];
        int ptkCntr = 0;
        for (
                PIPType ptk : pipTypes.keySet()) {
            ptkeys[ptkCntr++] = "" + ptk;
        }
        Arrays.sort(ptkeys);

        for (Wire w : node_start_wires) {
            IntentCode code = w.getIntentCode();
            nodeIntents.add(code);
            if (!intentCodes.contains(code)) {
                intentCodes.add(code);
            }
        }

        IntentCode[] codes = intentCodes.toArray(new IntentCode[intentCodes.size()]);
        HashMap<IntentCode, Integer> intentHash = new LinkedHashMap<>();
        Arrays.sort(codes);
        for (
                IntentCode c : codes) {
            List<String> intentList = new LinkedList<>();
            for (Wire w : node_start_wires) {
                if (w.getIntentCode() == c) {
                    intentList.add(w.getWireName());
                }
            }
            intentHash.put(c, intentList.size());
        }

        List<TimingGroup> groups = null;

        if (nodeList.size() > 0)
            groups = determineGroups(nodeList, nodeIntents, relevantPIPs);

        float result = 0f;
        if (groups != null)
            result = calcDelay( startPinInst, endPinInst, sourceBELPin, sinkBELPin, groups);

        return result;
    }

    /**
     * Given a list of nodes, a list of pips, and the types for items in both lists this abstracts 
     * this method determines a set of corresponding TimingGroups.
     * @param nodes List of device nodes (determined from PIPs from a physical Net).
     * @param nodeTypes Type information corresponding to the list of device nodes.
     * @param pips List of PIPs (obtained from a physical Net).
     * @return List of TimingGroups.  Timing groups is the abstraction featured by our model 
     * representing a basic grouping that the delay can be calculated by our model.
     */
    protected List<TimingGroup> determineGroups(List<Node> nodes, List<IntentCode> nodeTypes, 
    		List<PIP> pips) {
        // Check the inputs
        if (nodes.size() != nodeTypes.size()) {
            throw new RuntimeException("node size and node types size do not match");
        }

        List<TimingGroup> result = new LinkedList<>();
        if (nodes.size()>= 2 && pips.size() >=1) {
            TimingGroup initialGroup = new TimingGroup(this);
            initialGroup.add(nodes.get(0), nodeTypes.get(0));
            boolean initialHasPinbounce = false;
            initialGroup.setInitialGroup(true);
            result.add(initialGroup);
            for (int i = !initialHasPinbounce? 1:2; i < nodes.size() - 1; ) {
                TimingGroup midGroup = new TimingGroup(this);
                boolean thisNodeContainsGlobal = false;
                for (Wire w : nodes.get(i).getAllWiresInNode()) {
                    if (w.getWireName().contains("_GLOBAL"))
                        thisNodeContainsGlobal = true;
                }
                boolean nextNodeContainsGlobal = false;
                for (Wire w : nodes.get(i + 1).getAllWiresInNode()) {
                    if (w.getWireName().contains("_GLOBAL"))
                        nextNodeContainsGlobal = true;
                }
                IntentCode n0 = nodeTypes.get(i);
                IntentCode n1 = nodeTypes.get(i+1);
                if (thisNodeContainsGlobal || nextNodeContainsGlobal ||
                        n0 == IntentCode.NODE_PINFEED ||
                        n0 == IntentCode.NODE_HLONG ||
                        n0 == IntentCode.NODE_VLONG ||
                        (n0 == IntentCode.NODE_HQUAD && n1 == IntentCode.NODE_HLONG) ||
                        (n0 == IntentCode.NODE_HQUAD && n1 == IntentCode.NODE_VLONG) ||
                        (n0 == IntentCode.NODE_VQUAD && n1 == IntentCode.NODE_HLONG) ||
                        (n0 == IntentCode.NODE_VQUAD && n1 == IntentCode.NODE_VLONG)
                ) {
                    midGroup.add(pips.get(i - 1));
                    midGroup.add(nodes.get(i), n0);
                    i = i + 1;
                } else {
                    if (i == nodes.size() - 2) {
                        midGroup.add(pips.get(i - 1));
                        midGroup.add(nodes.get(i), n0);
                        i = i + 1;
                    } else {
                        midGroup.add(pips.get(i - 1));
                        midGroup.add(nodes.get(i), n0);
                        midGroup.add(pips.get(i));
                        midGroup.add(nodes.get(i + 1), n1);
                        i = i + 2;
                    }
                }
                result.add(midGroup);
            }

        }
        TimingGroup finalGroup = new TimingGroup(this);
        if (pips != null && pips.size() >0)
            finalGroup.add(pips.get(pips.size() - 1));
        if (nodes != null && nodes.size() >0)
            finalGroup.add(nodes.get(nodes.size() - 1), nodeTypes.get(nodes.size() - 1));
        finalGroup.setFinalGroup(true);
        result.add(finalGroup);

        for (int i = 0; i < result.size(); i++) {
            if (result.get(i).getNodes().size() == 0) {
                throw new RuntimeException("Invalid group:"+i+" with zero nodes out of "+
                                           result.size()+" groups.");
            }
        }
        return result;
    }

    /**
     * Reads the text file containing the delay terms needed by this timing model.
     * @param filename Name (and maybe the path) of the text file, the default is delay_terms.dat in the current directory.
     * @return Boolean indication of completion.
     */
    protected boolean readDelayTerms(String filename) {
        boolean result = true;
        InputStream in = FileTools.getRapidWrightResourceInputStream(filename); 
        try {
            BufferedReader br = new BufferedReader(new InputStreamReader(in));
            String line = "";
            int lineCntr = 0;
            while ((line=br.readLine()) != null) {// && line.length() != 0) {
                String[] split = line.split("\\s+");
                lineCntr++;
                if (split.length < 2 || split[0].startsWith("#"))
                    continue;
                Float value = 0.0f;
                value = Float.valueOf(split[1]);
                if (split[0].equalsIgnoreCase("START_TILE_ROW"))       START_TILE_ROW = (int)(float)value;
                else if (split[0].equalsIgnoreCase("START_TILE_COL"))  START_TILE_COL = (int)(float)value;
                else if (split[0].equalsIgnoreCase("INTRASITE_DELAY_SITEPIN_TO_LUT_INPUT")) INTRASITE_DELAY_SITEPIN_TO_LUT_INPUT = value;
                else if (split[0].equalsIgnoreCase("INTRASITE_DELAY_LUT_OUTPUT_TO_O_SITEPIN")) INTRASITE_DELAY_LUT_OUTPUT_TO_O_SITEPIN = value;
                else if (split[0].equalsIgnoreCase("INTRASITE_DELAY_SITEPIN_TO_FF_INPUT")) INTRASITE_DELAY_SITEPIN_TO_FF_INPUT = value;
                else if (split[0].equalsIgnoreCase("INTRASITE_DELAY_FF_INPUT_TO_SITEPIN")) INTRASITE_DELAY_FF_INPUT_TO_SITEPIN = value;
                else if (split[0].equalsIgnoreCase("INTRASITE_DELAY_LUT_OUTPUT_TO_MUX_SITEPIN")) INTRASITE_DELAY_LUT_OUTPUT_TO_MUX_SITEPIN = value;
                else if (split[0].equalsIgnoreCase("INTRASITE_DELAY_LUT_OUTPUT_TO_FF_INPUT")) INTRASITE_DELAY_LUT_OUTPUT_TO_FF_INPUT = value;
                else if (split[0].equalsIgnoreCase("L_HORIZONTAL_BOUNCE"))  L_HORIZONTAL_BOUNCE = value;
                else if (split[0].equalsIgnoreCase("L_HORIZONTAL_INTERNAL"))  L_HORIZONTAL_INTERNAL = value;
                else if (split[0].equalsIgnoreCase("L_HORIZONTAL_SINGLE"))  L_HORIZONTAL_SINGLE = value;
                else if (split[0].equalsIgnoreCase("L_HORIZONTAL_DOUBLE"))  L_HORIZONTAL_DOUBLE = value;
                else if (split[0].equalsIgnoreCase("L_HORIZONTAL_QUAD"))    L_HORIZONTAL_QUAD = value;
                else if (split[0].equalsIgnoreCase("L_HORIZONTAL_LONG"))    L_HORIZONTAL_LONG = value;
                else if (split[0].equalsIgnoreCase("L_HORIZONTAL_GLOBAL"))  L_HORIZONTAL_GLOBAL = value;
                else if (split[0].equalsIgnoreCase("L_VERTICAL_SINGLE"))  L_VERTICAL_SINGLE = value;
                else if (split[0].equalsIgnoreCase("L_VERTICAL_DOUBLE"))  L_VERTICAL_DOUBLE = value;
                else if (split[0].equalsIgnoreCase("L_VERTICAL_QUAD"))    L_VERTICAL_QUAD = value;
                else if (split[0].equalsIgnoreCase("L_VERTICAL_LONG"))    L_VERTICAL_LONG = value;
                else if (split[0].equalsIgnoreCase("K0_HORIZONTAL"))         K0_HORIZONTAL = value;
                else if (split[0].equalsIgnoreCase("K1_HORIZONTAL"))         K1_HORIZONTAL = value;
                else if (split[0].equalsIgnoreCase("K2_HORIZONTAL_SINGLE"))  K2_HORIZONTAL_SINGLE = value;
                else if (split[0].equalsIgnoreCase("K2_HORIZONTAL_DOUBLE"))  K2_HORIZONTAL_DOUBLE = value;
                else if (split[0].equalsIgnoreCase("K2_HORIZONTAL_QUAD"))    K2_HORIZONTAL_QUAD   = value;
                else if (split[0].equalsIgnoreCase("K2_HORIZONTAL_LONG"))    K2_HORIZONTAL_LONG   = value;
                else if (split[0].equalsIgnoreCase("K2_HORIZONTAL_GLOBAL"))  K2_HORIZONTAL_GLOBAL = value;
                else if (split[0].equalsIgnoreCase("K0_VERTICAL"))         K0_VERTICAL = value;
                else if (split[0].equalsIgnoreCase("K1_VERTICAL"))         K1_VERTICAL = value;
                else if (split[0].equalsIgnoreCase("K2_VERTICAL_SINGLE"))  K2_VERTICAL_SINGLE = value;
                else if (split[0].equalsIgnoreCase("K2_VERTICAL_DOUBLE"))  K2_VERTICAL_DOUBLE = value;
                else if (split[0].equalsIgnoreCase("K2_VERTICAL_QUAD"))    K2_VERTICAL_QUAD   = value;
                else if (split[0].equalsIgnoreCase("K2_VERTICAL_LONG"))    K2_VERTICAL_LONG   = value;
                else if (split[0].equalsIgnoreCase("RCLK_SINGLE_AND_DOUBLE"))  RCLK_SINGLE_AND_DOUBLE = value;
                else if (split[0].equalsIgnoreCase("RCLK_QUAD"))               RCLK_QUAD = value;
                else if (split[0].equalsIgnoreCase("RCLK_LONG"))               RCLK_LONG = value;
                else if (split[0].equalsIgnoreCase("DSP_SINGLE_AND_DOUBLE"))  DSP_SINGLE_AND_DOUBLE = value;
                else if (split[0].equalsIgnoreCase("DSP_QUAD"))               DSP_QUAD = value;
                else if (split[0].equalsIgnoreCase("DSP_LONG"))               DSP_LONG = value;
                else if (split[0].equalsIgnoreCase("BRAM_SINGLE_AND_DOUBLE"))  BRAM_SINGLE_AND_DOUBLE = value;
                else if (split[0].equalsIgnoreCase("BRAM_QUAD"))               BRAM_QUAD = value;
                else if (split[0].equalsIgnoreCase("BRAM_LONG"))               BRAM_LONG = value;
                else if (split[0].equalsIgnoreCase("CFRM_SINGLE_AND_DOUBLE"))  CFRM_SINGLE_AND_DOUBLE = value;
                else if (split[0].equalsIgnoreCase("CFRM_QUAD"))               CFRM_QUAD = value;
                else if (split[0].equalsIgnoreCase("CFRM_LONG"))               CFRM_LONG = value;
                else if (split[0].equalsIgnoreCase("URAM_SINGLE_AND_DOUBLE"))  URAM_SINGLE_AND_DOUBLE = value;
                else if (split[0].equalsIgnoreCase("URAM_QUAD"))               URAM_QUAD = value;
                else if (split[0].equalsIgnoreCase("URAM_LONG"))               URAM_LONG = value;
                else if (split[0].equalsIgnoreCase("PCIE_SINGLE_AND_DOUBLE"))  PCIE_SINGLE_AND_DOUBLE = value;
                else if (split[0].equalsIgnoreCase("PCIE_QUAD"))               PCIE_QUAD = value;
                else if (split[0].equalsIgnoreCase("PCIE_LONG"))               PCIE_LONG = value;
                else if (split[0].equalsIgnoreCase("IO_SINGLE_AND_DOUBLE"))  IO_SINGLE_AND_DOUBLE = value;
                else if (split[0].equalsIgnoreCase("IO_QUAD"))               IO_QUAD = value;
                else if (split[0].equalsIgnoreCase("IO_LONG"))               IO_LONG = value;
                else if (split[0].equalsIgnoreCase("LOGIC_FF_DELAY"))        LOGIC_FF_DELAY = value;
                else if (split[0].equalsIgnoreCase("LOGIC_LUT_A1_DELAY"))        LOGIC_LUT_A1_DELAY = value;
                else if (split[0].equalsIgnoreCase("LOGIC_LUT_A2_DELAY"))        LOGIC_LUT_A2_DELAY = value;
                else if (split[0].equalsIgnoreCase("LOGIC_LUT_A3_DELAY"))        LOGIC_LUT_A3_DELAY = value;
                else if (split[0].equalsIgnoreCase("LOGIC_LUT_A4_DELAY"))        LOGIC_LUT_A4_DELAY = value;
                else if (split[0].equalsIgnoreCase("LOGIC_LUT_A5_DELAY"))        LOGIC_LUT_A5_DELAY = value;
                else if (split[0].equalsIgnoreCase("LOGIC_LUT_A6_DELAY"))        LOGIC_LUT_A6_DELAY = value;
                else if (split[0].equalsIgnoreCase("SITEPIN_A1_DELAY"))        SITEPIN_A1_DELAY = value;
                else if (split[0].equalsIgnoreCase("SITEPIN_A2_DELAY"))        SITEPIN_A2_DELAY = value;
                else if (split[0].equalsIgnoreCase("SITEPIN_A3_DELAY"))        SITEPIN_A3_DELAY = value;
                else if (split[0].equalsIgnoreCase("SITEPIN_A4_DELAY"))        SITEPIN_A4_DELAY = value;
                else if (split[0].equalsIgnoreCase("SITEPIN_A5_DELAY"))        SITEPIN_A5_DELAY = value;
                else if (split[0].equalsIgnoreCase("SITEPIN_A6_DELAY"))        SITEPIN_A6_DELAY = value;
                else if (split[0].equalsIgnoreCase("SITEPIN_A_I_DELAY"))        SITEPIN_A_I_DELAY = value;
                else if (split[0].equalsIgnoreCase("SITEPIN_AX_DELAY"))        SITEPIN_AX_DELAY = value;
                else if (split[0].equalsIgnoreCase("NEAR_MIN"))       NEAR_MIN = (int) Math.floor(value);
                else if (split[0].equalsIgnoreCase("NEAR_MAX"))       NEAR_MAX =(int) Math.floor(value);
                else if (split[0].equalsIgnoreCase("MID_MIN"))        MID_MIN =(int) Math.floor(value);
                else if (split[0].equalsIgnoreCase("MID_MAX"))        MID_MAX =(int) Math.floor(value);
                else if (split[0].equalsIgnoreCase("FAR_MIN"))        FAR_MIN =(int) Math.floor(value);
                else if (split[0].equalsIgnoreCase("FAR_MAX"))        FAR_MAX =(int) Math.floor(value);

                else {
                	String errMessage;
                    if (split.length == 2) {
                        errMessage = "Bad formatted line:"+lineCntr+": \""+split[0]+"\"";
                    } else {
                        errMessage = "Unrecognized term on line:"+lineCntr+": \""+split[0]+"\"";
                    }
                    throw new RuntimeException("ERROR: " + errMessage);
                }
            }
            br.close();
        } catch (IOException e) {
            e.printStackTrace();
            result = false;
        }
        return result;
    }

    int[] sDistHorizontal;
    int[] dDistHorizontal;
    int[] qDistHorizontal;
    int[] lDistHorizontal;

    int[] sDistVertical;
    int[] dDistVertical;
    int[] qDistVertical;
    int[] lDistVertical;


    /**
     * Computes the Horizontal Distance used later by the delay calculation. Please note the initial
     * distance terms are integers.
     * @param left Leftmost tile column coordinate of a wire within a TimingGroup.
     * @param right Rightmost tile column coordinate of a wire within a TimingGroup.
     * @param swt Type of TimingGroup, for example SINGLE, DOUBLE, etc.  The type is enumerated.
     * @return Distance term used by the delay calculation.
     */
    protected int computeHorizontalDistFromArray(int left, int right, GroupDelayType swt) {
        int result = 0;
        switch (swt) {
            case SINGLE:
                for (int i=left; i<=right; i++) {
                    result += sDistHorizontal[i];
                }
                break;
            case DOUBLE:
                for (int i=left; i<=right; i++) {
                    result += dDistHorizontal[i];
                }
                break;
            case QUAD:
                for (int i=left; i<=right; i++) {
                    result += qDistHorizontal[i];
                }
                break;
            case LONG:
                for (int i=left; i<=right; i++) {
                    result += lDistHorizontal[i];
                }
                break;
            default:
                break;
        }
        return result;
    }

    /**
     * Computes the Vertical Distance used later by the delay calculation.  Please note the initial 
     * distance terms are integers.
     * @param top Topmost tile column coordinate of a wire within a TimingGroup.
     * @param bottom Bottom most tile column coordinate of a wire within a TimingGroup.
     * @param swt Type of TimingGroup, for example SINGLE, DOUBLE, etc.  The type is enumerated.
     * @return Distance term used by the delay calculation.
     */
    int computeVerticalDistFromArray(int top, int bottom, GroupDelayType swt) {
        int result = 0;
        switch (swt) {
            case SINGLE:
                for (int i=top; i<=bottom; i++) {
                    result += sDistVertical[i];
                }
                break;
            case DOUBLE:
                for (int i=top; i<=bottom; i++) {
                    result += dDistVertical[i];
                }
                break;
            case QUAD:
                for (int i=top; i<=bottom; i++) {
                    result += qDistVertical[i];
                }
                break;
            case LONG:
                for (int i=top; i<=bottom; i++) {
                    result += lDistVertical[i];
                }
                break;
            default:
                break;
        }
        return result;
    }

    /**
     * The distance arrays are created when the model is initialized basically to create a lookup 
     * table having the distances associated with column resource types or RCLK vertical crossings.
     * @param maxCol The maximum column coordinate for the given device.
     * @param maxRow  The maximum row coordinate for the given device.
     */
    void buildDistArrays(int maxCol, int maxRow) {
        // these arrays will be initialized to zeros
        sDistHorizontal = new int[maxCol ];
        dDistHorizontal = new int[maxCol ];
        qDistHorizontal = new int[maxCol ];
        lDistHorizontal = new int[maxCol ];

        sDistVertical = new int[maxRow ];
        dDistVertical = new int[maxRow ];
        qDistVertical = new int[maxRow ];
        lDistVertical = new int[maxRow ];

        int col1 = 0;
        int col2 = maxCol-1;

        for (int i = col1; i <= col2; i++) {
            Tile testT = goodRowTypes[i];
            if (testT == null)
                continue;
            else {
                sDistHorizontal[i] = checkTileType(testT, GroupDelayType.SINGLE);
                dDistHorizontal[i] = checkTileType(testT, GroupDelayType.DOUBLE);
                qDistHorizontal[i] = checkTileType(testT, GroupDelayType.QUAD);
                lDistHorizontal[i] = checkTileType(testT, GroupDelayType.LONG);
            }
        }

        int col = START_TILE_COL;
        int row1 = 0;
        int row2 = maxRow-1;

        for (int i = row1 ; i <= row2; i++) {
            Tile testT = design.getDevice().getTile(i+1, col);
            if (testT == null)
                continue;
            else {
                sDistVertical[i] = check_RCLK_TileType(testT, GroupDelayType.SINGLE);
                dDistVertical[i] = check_RCLK_TileType(testT, GroupDelayType.DOUBLE);
                qDistVertical[i] = check_RCLK_TileType(testT, GroupDelayType.QUAD);
                lDistVertical[i] = check_RCLK_TileType(testT, GroupDelayType.LONG);
            }
        }
        return;
    }

    /**
     * This checks in the horizontal direction a given tile and returns the value associated with 
     * type of tile used by the model.
     * @param testT Test tile to check the type.
     * @param swt Type of the TimingGroup crossing the given tile.
     * @return The value used by the model for the given tile type and also based on the type of 
     * TimingGroup.
     */
    public int checkTileType(Tile testT, GroupDelayType swt) {
        int result = 0; // some types will return zero
        if (testT == null)
            return result;

        // RCLK
        if (
                testT.getTileTypeEnum() == TileTypeEnum.RCLK_INT_L ||
                        testT.getTileTypeEnum() == TileTypeEnum.RCLK_INT_R

        ) {
            if (swt == GroupDelayType.SINGLE || swt == GroupDelayType.DOUBLE) {
                result += RCLK_SINGLE_AND_DOUBLE;
            } else if (swt == GroupDelayType.QUAD) {
                result += RCLK_QUAD;
            } else if (swt == GroupDelayType.LONG) {
                result += RCLK_LONG;
            }
        }
        // DSP
        if (testT.getTileTypeEnum() == TileTypeEnum.DSP ||
                testT.getTileTypeEnum() == TileTypeEnum.DSP_TERM_T

        ) {
            if (swt == GroupDelayType.SINGLE || swt == GroupDelayType.DOUBLE) {
                result += DSP_SINGLE_AND_DOUBLE;
            } else if (swt == GroupDelayType.QUAD) {
                result += DSP_QUAD;
            } else if (swt == GroupDelayType.LONG) {
                result += DSP_LONG;
            }
        }

        // BRAM
        else if (testT.getTileTypeEnum() == TileTypeEnum.BRAM
                ||
                testT.getTileTypeEnum() == TileTypeEnum.BRAM_L ||
                testT.getTileTypeEnum() == TileTypeEnum.BRAM_R ||
                testT.getTileTypeEnum() == TileTypeEnum.BRAM_TERM_T
        ) {
            if (swt == GroupDelayType.SINGLE || swt == GroupDelayType.DOUBLE) {
                result += BRAM_SINGLE_AND_DOUBLE;
            } else if (swt == GroupDelayType.QUAD) {
                result += BRAM_QUAD;
            } else if (swt == GroupDelayType.LONG) {
                result += BRAM_LONG;
            }
        }

        // CFRM
        else if (
                testT.getTileTypeEnum() == TileTypeEnum.CFRM_CONFIG ||
                        testT.getTileTypeEnum() == TileTypeEnum.CFRM_AMS_CFGIO ||
                        testT.getTileTypeEnum() == TileTypeEnum.CFRM_T ||
                        testT.getTileTypeEnum() == TileTypeEnum.CFRM_B


        ) {
            if (swt == GroupDelayType.SINGLE || swt == GroupDelayType.DOUBLE) {
                result += CFRM_SINGLE_AND_DOUBLE;
            } else if (swt == GroupDelayType.QUAD) {
                result += CFRM_QUAD;
            } else if (swt == GroupDelayType.LONG) {
                result += CFRM_LONG;
            }
        }

        // URAM
        else if (
                testT.getTileTypeEnum() == TileTypeEnum.URAM_URAM_FT ||
                        testT.getTileTypeEnum() == TileTypeEnum.URAM_URAM_DELAY_FT ||
                        testT.getTileTypeEnum() == TileTypeEnum.URAM_URAM_TERM_T_FT

        ) {
            if (swt == GroupDelayType.SINGLE || swt == GroupDelayType.DOUBLE) {
                result += URAM_SINGLE_AND_DOUBLE;
            } else if (swt == GroupDelayType.QUAD) {
                result += URAM_QUAD;
            } else if (swt == GroupDelayType.LONG) {
                result += URAM_LONG;
            }
        }

        // PCIE_MISC
        else if (
                testT.getTileTypeEnum() == TileTypeEnum.PCIE4_PCIE4_FT ||
                        testT.getTileTypeEnum() == TileTypeEnum.ILKN_ILKN_FT ||
                        testT.getTileTypeEnum() == TileTypeEnum.CFG_CONFIG ||
                        testT.getTileTypeEnum() == TileTypeEnum.CMAC ||
                        //testT.getTileTypeEnum() == TileTypeEnum.CMAC_CMAC_FT ||
                        testT.getTileTypeEnum() == TileTypeEnum.CFGIO_IOB20

        ) {
            if (swt == GroupDelayType.SINGLE || swt == GroupDelayType.DOUBLE) {
                result += PCIE_SINGLE_AND_DOUBLE;
            } else if (swt == GroupDelayType.QUAD) {
                result += PCIE_QUAD;
            } else if (swt == GroupDelayType.LONG) {
                result += PCIE_LONG;
            }
        }
        // IO
        else if (
                testT.getTileTypeEnum() == TileTypeEnum.HPIO_L ||
                        testT.getTileTypeEnum() == TileTypeEnum.HPIO_L_TERM_T

        ) {
            if (swt == GroupDelayType.SINGLE || swt == GroupDelayType.DOUBLE) {
                result += IO_SINGLE_AND_DOUBLE;
            } else if (swt == GroupDelayType.QUAD) {
                result += IO_QUAD;
            } else if (swt == GroupDelayType.LONG) {
                result += IO_LONG;
            }
        }
        return result;
    }

    /**
     * This checks a vertical direction given tile and returns the value associated with type of 
     * tile used by the model.
     * @param testT Test tile to check the type.
     * @param swt Type of the TimingGroup crossing the given tile.
     * @return The value used by the model for the given tile type and also based on the type of 
     * TimingGroup.
     */
    int check_RCLK_TileType(Tile testT, GroupDelayType swt) {
        int result = 0; // some types will return zero
        if (testT == null)
            return result;

        // RCLK
        if (testT.getTileTypeEnum() == TileTypeEnum.RCLK_INT_L ||
                testT.getTileTypeEnum() == TileTypeEnum.RCLK_INT_R
        ) {
            if (swt == GroupDelayType.SINGLE || swt == GroupDelayType.DOUBLE) {
                result += RCLK_SINGLE_AND_DOUBLE;
            } else if (swt == GroupDelayType.QUAD) {
                result += RCLK_QUAD;
            } else if (swt == GroupDelayType.LONG) {
                result += RCLK_LONG;
            }
        }
        return result;
    }

    /**
     * Estimates the delay of a timing group in picoseconds.
     * @param tGroup TimingGroup to be analyzed.
     * @return Estimated delay in picoseconds.
     */
    public float calcDelay (TimingGroup tGroup) {
        List<TimingGroup> tGroups = new LinkedList<>();
        tGroups.add(tGroup);
        return calcDelay(null, null, null, null, tGroups);
    }

    /**
     * Estimates the delay of a timing group in picoseconds.
     * @param groups List of TimingGroups to be analyzed.
     * @return Estimated delay in picoseconds.
     */
    public float calcDelay (List<TimingGroup> groups) {
        return calcDelay(null, null, null, null, groups);
    }


    private float intrasiteDelay;
    private SitePinInst startPinInst;
    private SitePinInst endPinInst;
    private BELPin sourceBELPin;
    private BELPin sinkBELPin;
    private List<TimingGroup> groups;

    /**
     * Estimates the delay of a timing group in picoseconds.
     * @param startPinInst Source pin as a SitePinInst for the physical Net.
     * @param endPinInst Sink pin as a SitePinInst for the physical Net.
     * @param groups List of TimingGroups to be analyzed.
     * @return Estimated delay in picoseconds.
     */
    public float calcDelay (SitePinInst startPinInst, SitePinInst endPinInst, BELPin sourceBELPin, 
                            BELPin sinkBELPin, List<TimingGroup> groups) {
        for (TimingGroup g : groups) {
            if (g.getNodes().size() == 0) {
                throw new RuntimeException("Invalid group passed into calcDelay:" + g);
            }
        }

        // set these member variables for use in method: "checkForSomeIntrasiteDelays()" down below
        this.intrasiteDelay = 0;
        this.startPinInst = startPinInst;
        this.endPinInst = endPinInst;
        this.sourceBELPin = sourceBELPin;
        this.sinkBELPin = sinkBELPin;
        this.groups = groups;

        int GroupCntr = 0;
        float netDelayCalc = 0;

        for (TimingGroup group : groups) {
            float GroupDelayCalc = 0;
            group.computeTypes();

            /**
             * This is based on the formula in our FPT'19 paper for calculating the net delay
             */
            float k0 = 0; // this is independent of type, but dependent on direction
            float k1 = 0; // this is independent of type, but dependent on direction
            float L = 0;     // this is dependent on both type and direction
            float k2 = 0; // this is dependent on both type and direction
            int d = 0;     // this is dependent on type, direction, location, distance based on what 
                           // resources have been crossed

            // initialize the terms
            if (group.getDelayType() == null) {
                if (verbose) {
                    throw new RuntimeException("Groupwire type is null, Group:" + group + " sdt:" +
                                               group.getDelayType());
                }

            } else {
                switch (group.getDelayType()) {
                    case SINGLE:
                        if (group.getWireDirection() == GroupWireDirection.HORIZONTAL) {
                            k0 = K0_HORIZONTAL;
                            k1 = K1_HORIZONTAL;
                            k2 = K2_HORIZONTAL_SINGLE;
                            L = L_HORIZONTAL_SINGLE;
                        } else if (group.getWireDirection() == GroupWireDirection.VERTICAL) {
                            k0 = K0_VERTICAL;
                            k1 = K1_VERTICAL;
                            k2 = K2_VERTICAL_SINGLE;
                            L = L_VERTICAL_SINGLE;
                        }
                        break;

                    case DOUBLE:
                        if (group.getWireDirection() == GroupWireDirection.HORIZONTAL) {
                            k0 = K0_HORIZONTAL;
                            k1 = K1_HORIZONTAL;
                            k2 = K2_HORIZONTAL_DOUBLE;
                            L = L_HORIZONTAL_DOUBLE;
                        } else if (group.getWireDirection() == GroupWireDirection.VERTICAL) {
                            k0 = K0_VERTICAL;
                            k1 = K1_VERTICAL;
                            k2 = K2_VERTICAL_DOUBLE;
                            L = L_VERTICAL_DOUBLE;
                        }
                        break;

                    case QUAD:
                        if (group.getWireDirection() == GroupWireDirection.HORIZONTAL) {
                            k0 = K0_HORIZONTAL;
                            k1 = K1_HORIZONTAL;
                            k2 = K2_HORIZONTAL_QUAD;
                            L = L_HORIZONTAL_QUAD;
                        } else if (group.getWireDirection() == GroupWireDirection.VERTICAL) {
                            k0 = K0_VERTICAL;
                            k1 = K1_VERTICAL;
                            k2 = K2_VERTICAL_QUAD;
                            L = L_VERTICAL_QUAD;
                        }
                        break;

                    case LONG:
                        if (group.getWireDirection() == GroupWireDirection.HORIZONTAL) {
                            k0 = K0_HORIZONTAL;
                            k1 = K1_HORIZONTAL;
                            k2 = K2_HORIZONTAL_LONG;
                            L = L_HORIZONTAL_LONG;
                        } else if (group.getWireDirection() == GroupWireDirection.VERTICAL) {
                            k0 = K0_VERTICAL;
                            k1 = K1_VERTICAL;
                            k2 = K2_VERTICAL_LONG;
                            L = L_VERTICAL_LONG;
                        }
                        break;

                    case GLOBAL:
                        k0 = K0_HORIZONTAL;
                        k1 = K1_HORIZONTAL;
                        k2 = K2_HORIZONTAL_GLOBAL;
                        L = L_HORIZONTAL_GLOBAL;
                        break;

                    case INTERNAL:
                        k0 = K0_HORIZONTAL;
                        k1 = K1_HORIZONTAL;
                        k2 = 0;
                        L = L_HORIZONTAL_INTERNAL;
                        break;

                    case PIN_BOUNCE:
                        if (group.isInitialGroup())
                            break;
                        k0 = K0_HORIZONTAL;
                        k1 = K1_HORIZONTAL;
                        k2 = 0;
                        L = L_HORIZONTAL_BOUNCE;
                        break;
                }
                d = group.d;
                GroupDelayCalc = k0 + k1 * L + k2 * d;
                group.delay = GroupDelayCalc;
            }
            if ((!group.isInitialGroup() || (group.isInitialGroup() && group.getDelayType() != null)) 
            		&& !group.isFinalGroup()) {
                netDelayCalc += GroupDelayCalc;
            }                

            GroupCntr++;
            if (debugFile) {
                printStream.print("\tTimingGroup[" + GroupCntr + "]:" + group);
                printStream.println("\n");
            }
        }

        netDelayCalc += checkForSitePinDelay(groups);

        checkForIntrasiteDelay();  // implementation refactored into a helper method below

        for (int i =1 ; i < groups.size(); i++) {
            TimingGroup gprev = groups.get(i-1);
            TimingGroup gcur = groups.get(i);
            if (adjustDoubleConnectedToDoubleDelays && gprev.getDelayType() == GroupDelayType.DOUBLE 
            		&& gcur.getDelayType() == GroupDelayType.DOUBLE) {
                netDelayCalc -= 6;
            }
            if (adjustQuadConnectedToQuadDelays && gprev.getDelayType() == GroupDelayType.QUAD 
            		&& gcur.getDelayType() == GroupDelayType.QUAD) {
                netDelayCalc += 9;
            }
        }

        if (debugFile)
            printStream.println();

        if(verbose) {
            for (TimingGroup group : groups) {
                System.out.println("\t" + group.getDelayType() + ":\t" + group.delay + "\t, d:" +
                                   group.d);
            }
            System.out.println("\tintraSite:\t" + intrasiteDelay);
            System.out.println("total:\t" + (netDelayCalc + intrasiteDelay));
            for (TimingGroup group : groups) {
                System.out.println("\t" + group.getDelayType() + " with netDelay:" + group.delay +
                                   " wires below:");
                for (int i = 0; i < group.getNodes().size(); i++) {
                    Node n = group.getNode(i);
                    IntentCode nIntent = group.getNodeType(i);
                    System.out.println("\t\tnode " + i + " type:" + nIntent);
                    for (Wire w : n.getAllWiresInNode()) {
                        System.out.println("\t\t\tw:" + w);
                    }
                }
            }        	
        }
        
        return netDelayCalc + intrasiteDelay; // returning sum of net delay and intrasite delay
    }

    private float checkForSitePinDelay(List<TimingGroup> groups) {
        float total_sitepin_delay = 0.f;
        float sitepin_delay = 0.f;
        boolean includeSitePinDelay = false;

        for (TimingGroup group : groups) {
            sitepin_delay = 0.f;
            if (group.hasPinFeed()) {
                includeSitePinDelay = true;
                Node checkNode = group.getLastNode();

                if (checkNode.getSitePin() != null && checkNode.getSitePin().isInput()) {
                    String pinName = checkNode.getSitePin().getPinName();
                    if (pinName.endsWith("1")) {
                        group.delay += SITEPIN_A1_DELAY;
                        sitepin_delay += SITEPIN_A1_DELAY;
                    } else if (pinName.endsWith("2")) {
                        group.delay += SITEPIN_A2_DELAY;
                        sitepin_delay += SITEPIN_A2_DELAY;
                    } else if (pinName.endsWith("3")) {
                        group.delay += SITEPIN_A3_DELAY;
                        sitepin_delay += SITEPIN_A3_DELAY;
                    } else if (pinName.endsWith("4")) {
                        group.delay += SITEPIN_A4_DELAY;
                        sitepin_delay += SITEPIN_A4_DELAY;
                    } else if (pinName.endsWith("5")) {
                        group.delay += SITEPIN_A5_DELAY;
                        sitepin_delay += SITEPIN_A5_DELAY;
                    } else if (pinName.endsWith("6")) {
                        group.delay += SITEPIN_A6_DELAY;
                        sitepin_delay += SITEPIN_A6_DELAY;
                    } else if (pinName.endsWith("I")) {
                        group.delay += SITEPIN_A_I_DELAY;
                        sitepin_delay += SITEPIN_A_I_DELAY;
                    } else if (pinName.endsWith("X")) {
                        group.delay += SITEPIN_AX_DELAY;
                        sitepin_delay += SITEPIN_AX_DELAY;
                    } else if (pinName.endsWith("WCKEN")) {
                        group.delay += SITEPIN_AX_DELAY;
                        sitepin_delay += SITEPIN_AX_DELAY;
                    }
                }
            }
            if (group.getNodeType(0) == IntentCode.NODE_PINBOUNCE) {
                if (group.getNode(0).getSitePin() == null)
                    continue;
                String pinName = group.getNode(0).getSitePin().getPinName();
                if (xPinNames.contains(pinName)) {
                    includeSitePinDelay = true;
                    group.delay += SITEPIN_AX_DELAY;
                    sitepin_delay += SITEPIN_AX_DELAY;

                } else if (iPinNames.contains(pinName)) {
                    includeSitePinDelay = true;
                    group.delay += SITEPIN_A_I_DELAY;
                    sitepin_delay += SITEPIN_A_I_DELAY;
                }
            }
            total_sitepin_delay += sitepin_delay;
        }

        return (includeSitePinDelay) ? total_sitepin_delay : 0;
    }


    /**
     * Used for the router example to filter the unfiltered list based on a given direction.
     * @param targetDirection Enumerated type TimingDirection representing the given direction.
     * @param unfiltered Unfiltered array of TimingGroup objects.
     * @return Filtered array of TimingGroup objects in the given direction.
     */
    public TimingGroup[] filter(TimingDirection targetDirection, TimingGroup[] unfiltered) {
        ArrayList<TimingGroup> result = new ArrayList<>();

        for (TimingGroup ts : unfiltered) {
            if (ts.getDirection() != targetDirection && ts.getDirection() != null) {
            } else {
                result.add(ts);
            }
        }
        return result.toArray(new TimingGroup[result.size()]);
    }


    /**
     * Used for the router example to filter the unfiltered list based on a given direction and 
     * given distance.
     * @param targetDist The given distance for filtering.
     * @param targetDirection Enumerated type TimingDirection representing the given direction.
     * @param unfiltered Unfiltered array of TimingGroup objects.
     * @return Filtered array of TimingGroup objects in the given distance and direction.
     */
    public TimingGroup[] filter(int targetDist, TimingDirection targetDirection, 
                                TimingGroup[] unfiltered) {
        ArrayList<TimingGroup> result = new ArrayList<>();
        for (TimingGroup ts : unfiltered) {
            if (ts.dist != targetDist || (ts.getDirection() != targetDirection && ts.getDirection() != null)) {
            } else {
                result.add(ts);
            }
        }
        return result.toArray(new TimingGroup[result.size()]);
    }

    /**
     * Used for the router example to filter the unfiltered list based on a given group direction 
     * and given distance.
     * @param groupDistance Enumerated type for given group distance for filtering.
     * @param targetDirection Enumerated type TimingDirection representing the given direction.
     * @param unfiltered Unfiltered array of TimingGroup objects.
     * @return Filtered array of TimingGroup objects in the given group distance and direction.
     */
    public TimingGroup[] filter(GroupDistance groupDistance, TimingDirection targetDirection, 
                                TimingGroup[] unfiltered) {
        ArrayList<TimingGroup> result = new ArrayList<>();

        switch (groupDistance) {
            case SAME:
                for (TimingGroup ts : unfiltered) {
                    if ((ts.dist == 0 && ts.getDelayType() != GroupDelayType.PINFEED)// && (ts.dist == 0 && ts.getDelayType() != TimingGroup.GroupDelayType.GLOBAL) //(ts.dist == 0) || (ts.dist == 1 && ts.getDirection() == null)//){// && (ts.getDirection() == targetDirection || ts.getDirection() == null)) {
                    ) {
                        result.add(ts);
                    }
                }
                for (TimingGroup ts : unfiltered) {
                    if ((ts.dist == 0 && ts.getDelayType() == GroupDelayType.PINFEED) //(ts.dist == 0) || (ts.dist == 1 && ts.getDirection() == null)//){// && (ts.getDirection() == targetDirection || ts.getDirection() == null)) {
                    ) {
                        result.add(ts);
                    }
                }
                break;
            case NEAR:
                for (TimingGroup ts : unfiltered) {
                    if (ts.getDirection() == TimingDirection.NORTH || ts.getDirection() == TimingDirection.SOUTH) {
                        if (((ts.dist >= NEAR_MIN && ts.dist <= NEAR_MAX) && ts.getDirection() == targetDirection)
                        ) {
                            result.add(ts);
                        }
                    } else {
                        if (((ts.dist >= NEAR_MIN && ts.dist <= NEAR_MAX) && ts.getDirection() == targetDirection)
                                || (ts.dist == 1 && ts.getDirection() == null)
                        ) {
                            result.add(ts);
                        }
                    }
                }
                break;
            case MID:
                for (TimingGroup ts : unfiltered) {
                    if (((ts.dist >= MID_MIN && ts.dist <= MID_MAX) && (ts.getDirection() == targetDirection))
                            || (ts.getDirection() == targetDirection && ts.dist == 2)
                    ) {
                        result.add(ts);
                    }
                }
                break;
            case FAR:
                for (TimingGroup ts : unfiltered) {
                    if (((ts.dist >= FAR_MIN && ts.dist <= FAR_MAX) && (ts.getDirection() == targetDirection))
                    ) {
                        result.add(ts);
                    }
                }
                break;

        }
        return result.toArray(new TimingGroup[result.size()]);
    }

    private HashMap<String, PIP> pipEndNodeHashMap;

    private void determineNodeListInitHelper(Net net) {
        pipEndNodeHashMap = new HashMap<>();
        for (PIP p : net.getPIPs()) {
            pipEndNodeHashMap.put(p.getEndNode().toString(), p);
        }
    }


    /**
     * This method basically creates an ordered list of nodes from the source to selected sink.
     * This is computed based on the randomly ordered set of PIPs returned by the net.
     * This is currently one of the performance bottlenecks within the timing library.
     * @param net Physical net.
     * @param startPinInst The source (SitePinInst) from the net.
     * @param endPinInst  The selected sink (SitePinInst) from the net.
     */
    private void determineNodeList(Net net, SitePinInst startPinInst, SitePinInst endPinInst) {
        determineNodeListInitHelper(net);

        Node sourcePinNode = null;
        if (startPinInst != null)
            sourcePinNode = startPinInst.getConnectedNode();
        else if (net.getPIPs().size() > 0)
            sourcePinNode = net.getPIPs().get(0).getStartNode();
        else
            sourcePinNode = null;


        /**
         *  Getting the PIPs will return all of them, and depending on the sink pin, maybe only a 
         *  subset are needed for a timing path.
         *  An ordered list of Nodes is created called "nodeList".
         *  An ordered list of PIPs is created called "relevantPIPs".
         *  The associated types for the PIPs is stored in "pipTypesForGroups".
         */
        boolean skipRelevantPipCheck = false;
        if (!skipRelevantPipCheck) {
            Node node = null;
            if (endPinInst != null)
                node = endPinInst.getConnectedNode();
            else
                node = null;

            while (node != null && !node.equals(sourcePinNode)) {
                PIP p = pipEndNodeHashMap.get(node.toString());
                if (p != null) {
                    relevantPIPs.add(relevantPIPs.size(), p);
                    nodeList.add(nodeList.size(), node);
                } else
                    break;
                node = p.getStartNode();//pipStartNodeHashMap.get(p);//p.getStartNode();
                //node = pipStartNodeHashMap.get(p);//p.getStartNode();
            }
            if (node != null) {
                nodeList.add(nodeList.size(), node);
            }
        } else {
            for (PIP p : net.getPIPs()) {
                relevantPIPs.add(p);
            }
        }

/* experimental code towards handling bi-directional PIPs, not debugged yet
        HashMap<String, Node> pipStartNodeHashMap2 = new LinkedHashMap<>();
        HashMap<String, PIP> pipEndNodeHashMap2 = new LinkedHashMap<>();

        // some nets contain bidirectional PIPs and the code for figuring out the path of PIPs will need to take this into account
        for (PIP p : net.getPIPs()) {
            pipStartNodeHashMap.put(p.toString(), p.getStartNode());
            pipEndNodeHashMap.put(p.getEndNode().toString(), p);
            if (p.isBidirectional()) {
                pipStartNodeHashMap2.put(p.toString(), p.getEndNode());
                pipEndNodeHashMap2.put(p.getStartNode().toString(), p);
            } else {
                pipEndNodeHashMap2.put(p.getEndNode().toString(), p);
            }
        }

        Node sourcePinNode = null;
        if (startPinInst != null)
            sourcePinNode=  startPinInst.getConnectedNode();
        else if (net.getPIPs().size() > 0)
            sourcePinNode = net.getPIPs().get(0).getStartNode();
        else sourcePinNode = null;

        boolean skipRelevantPipCheck = false;

        if (!skipRelevantPipCheck) {
            Node node = null;
            if (endPinInst != null)
                node = endPinInst.getConnectedNode();
            else if (net.getSinkPins() != null) {
                boolean debugCheckCondition1 = true;
            }
            else
                node = null;

            while (node != null && !node.equals(sourcePinNode)) {
                PIP p = null;
                p = pipEndNodeHashMap.get(node.toString());
                //if (p != null && p.isBidirectional()) {
                //    node = p.getStartNode();
                //    if (!nodeList.contains(node))
                //        nodeList.add(node);
                //} else
                if (p != null && !p.isBidirectional()) {
                    relevantPIPs.add(relevantPIPs.size(), p);
                    pipTypesForGroups.add(pipTypesForGroups.size(), p.getPIPType());
                    if (!nodeList.contains(node))
                        nodeList.add(nodeList.size(), node);
                    Node tmpNode = p.getStartNode();
                    if (p.isBidirectional() && nodeList.contains(tmpNode))
                        tmpNode = pipStartNodeHashMap2.get(pipStartNodeHashMap2.get(p));
                    node =tmpNode;
                } else {
                    p = pipEndNodeHashMap2.get(node.toString());
                    if (p != null  && p.isBidirectional()) {
                        relevantPIPs.add(relevantPIPs.size(), p);
                        pipTypesForGroups.add(pipTypesForGroups.size(), p.getPIPType());
                        if (!nodeList.contains(node))
                            nodeList.add(nodeList.size(), node);
                        Node tmpNode = pipStartNodeHashMap2.get(p.toString());
                        if (p.isBidirectional() && nodeList.contains(tmpNode))
                            tmpNode = pipStartNodeHashMap2.get(pipStartNodeHashMap.get(p).toString());
                        node = tmpNode;
                    } else if (p != null && !p.isBidirectional()) {
                        relevantPIPs.add(relevantPIPs.size(), p);
                        pipTypesForGroups.add(pipTypesForGroups.size(), p.getPIPType());
                        if (!nodeList.contains(node))
                            nodeList.add(nodeList.size(), node);
                        Node tmpNode = p.getStartNode();
                        if (p.isBidirectional() && nodeList.contains(tmpNode))
                            tmpNode = pipStartNodeHashMap2.get(pipStartNodeHashMap2.get(p));
                        node = tmpNode;
                    }
                    else
                        break;
                }
            }
            if (node != null) {
                nodeList.add(nodeList.size(), node);
            }
           //if (!nodeList.contains(sourcePinNode))
           //     nodeList.add(sourcePinNode);
        } else {
            for (PIP p : net.getPIPs()) {
                relevantPIPs.add(p);
                pipTypesForGroups.add(p.getPIPType());
            }
        }
*/
    }

    private void checkForIntrasiteDelay() {
        String sourceType = "";
        String sinkType = "";
        if (endPinInst != null) {
            Set<Cell> cells = DesignTools.getConnectedCells(endPinInst);
            for (Cell c : cells) {
                if (c.getBEL() != null) {
                    sinkType = c.getBEL().getBELType();
                    break;
                }
            }
        }
        BELPin tmpPin = null;
        SitePinInst pin = endPinInst;
        Integer startPinSiteWireIdx = null;

        if (endPinInst != null) {
        	Site site = pin.getSiteInst().getSite();
            startPinSiteWireIdx = site.getSiteWireIndex(startPinInst.getName());
        }
        
        /*
         * Checking for some intrasite delays related to MUX driver pins
         */
        if (startPinInst != null) {
            String muxletter = startPinInst.getName().contains("MUX") ? 
                               startPinInst.getName().substring(0, 1) : "";
        	
            Set<Cell> cells = DesignTools.getConnectedCells(startPinInst);

            for (Cell c : cells) {
                if (c.getBEL() == null) continue;
                sourceType = c.getBEL().getName();
                if (sourceBELPin != null && sinkBELPin != null) continue;
                for (EDIFPortInst epi : c.getEDIFCellInst().getPortInsts()) {
                    if (!epi.isOutput()) continue;
                    String epiToPhysicalPinName = c.getPhysicalPinMapping(epi.getName());
                    BELPin p = c.getBEL().getPin(epiToPhysicalPinName);
                    if (!p.isOutput()) continue;
                    ArrayList<BELPin> connectedPins = p.getSiteConns();
                    for (BELPin connectedToP : connectedPins) {
                        if (startPinSiteWireIdx != null && connectedToP.isInput()) {
                            if (connectedToP.getBEL().equals(c.getBEL()) ||
                                    (connectedToP.getBEL().getName().contains("MUX" + muxletter) ||
                                            startPinInst.getName().contains("_O")) ||
                                    p.getSiteWireIndex() == startPinSiteWireIdx) {
                                tmpPin = p;
                                break;
                            }
                        }
                    }
                }
                break;
            }
        }

        if (ultraScaleFlopNames.contains(sinkType)) {
            if (endPinInst != null) {
                String sourcepin = endPinInst.getName();

                if (!sourcepin.startsWith("CKEN") &&
                        !sourcepin.startsWith("CLK1") &&
                        !sourcepin.startsWith("CLK2") &&
                        !sourcepin.startsWith("SRST")) {

                    short tmpIntrasiteDelay = 0;
                    tmpIntrasiteDelay = intrasiteAndLogicDelayModel.getIntraSiteDelay("SLICEL", 
                                            sourcepin, sinkType + "/" + "D");
                    intrasiteDelay += tmpIntrasiteDelay;
                } else if (sourcepin.startsWith("CKEN")) {
                    intrasiteDelay += INTRASITE_DELAY_SITEPIN_TO_FF_INPUT;
                }
            }
        }


        /**
         * Checking for additional intrasite delays
         */
        for (TimingGroup g : groups) {
            for (Node n : g.getNodes()) {
                if (g.getDelayType() == GroupDelayType.OTHER ||
                        g.getDelayType() == GroupDelayType.PINFEED ||
                        (g.getDelayType() == GroupDelayType.PIN_BOUNCE)) {
                    for (Wire w : n.getAllWiresInNode()) {
                        if (w.getWireName().endsWith("MUX") && !sourceType.startsWith("F8MUX")) {
                            short tmpIntrasiteDelay = 0;
                            if ((startPinInst == null || sourceType == null) || 
                                (tmpPin == null && sourceBELPin == null)) {
                                continue;
                            }
                            String fromPinName = sourceType + "/";
                            if ((sourceBELPin == null || sinkBELPin == null) && tmpPin != null)
                                fromPinName += tmpPin.getName();
                            else {
                                fromPinName += sourceBELPin.getName();
                            }
                            tmpIntrasiteDelay = intrasiteAndLogicDelayModel.getIntraSiteDelay(
                            		"SLICEL", fromPinName, startPinInst.getName());
                            intrasiteDelay += tmpIntrasiteDelay;
                        } else if (w.getWireName().endsWith("_O"))
                            intrasiteDelay += INTRASITE_DELAY_LUT_OUTPUT_TO_O_SITEPIN;
                    }
                }
            }
        }


    }

    /**
     * Used by the TimingManager that creates the TimingModel object for setting a reference back.
     * @param tManager The TimingManager passes itself as the argument.
     */
    protected void setTimingManager(TimingManager tManager) {
        timingManager = tManager;
    }

    /**
     * Gets the TimingManager that created this TimingModel object.
     * @return TimingManager that created this TimingModel.
     */
    public TimingManager getTimingManager() {
        return timingManager;
    }
}

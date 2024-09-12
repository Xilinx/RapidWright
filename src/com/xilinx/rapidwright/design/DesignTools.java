/*
 * Copyright (c) 2017-2022, Xilinx, Inc.
 * Copyright (c) 2022-2024, Advanced Micro Devices, Inc.
 * All rights reserved.
 *
 * Author: Chris Lavin, Xilinx Research Labs.
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

package com.xilinx.rapidwright.design;

import java.io.BufferedWriter;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintStream;
import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Objects;
import java.util.PriorityQueue;
import java.util.Queue;
import java.util.Set;
import java.util.function.Consumer;
import java.util.stream.Collectors;

import com.xilinx.rapidwright.design.blocks.PBlock;
import com.xilinx.rapidwright.design.blocks.UtilizationType;
import com.xilinx.rapidwright.design.tools.LUTTools;
import com.xilinx.rapidwright.device.BEL;
import com.xilinx.rapidwright.device.BELClass;
import com.xilinx.rapidwright.device.BELPin;
import com.xilinx.rapidwright.device.Node;
import com.xilinx.rapidwright.device.PIP;
import com.xilinx.rapidwright.device.Series;
import com.xilinx.rapidwright.device.Site;
import com.xilinx.rapidwright.device.SitePIP;
import com.xilinx.rapidwright.device.SitePin;
import com.xilinx.rapidwright.device.SiteTypeEnum;
import com.xilinx.rapidwright.device.Tile;
import com.xilinx.rapidwright.device.Wire;
import com.xilinx.rapidwright.eco.ECOTools;
import com.xilinx.rapidwright.edif.EDIFCell;
import com.xilinx.rapidwright.edif.EDIFCellInst;
import com.xilinx.rapidwright.edif.EDIFHierCellInst;
import com.xilinx.rapidwright.edif.EDIFHierNet;
import com.xilinx.rapidwright.edif.EDIFHierPortInst;
import com.xilinx.rapidwright.edif.EDIFLibrary;
import com.xilinx.rapidwright.edif.EDIFNet;
import com.xilinx.rapidwright.edif.EDIFNetlist;
import com.xilinx.rapidwright.edif.EDIFPort;
import com.xilinx.rapidwright.edif.EDIFPortInst;
import com.xilinx.rapidwright.edif.EDIFPropertyValue;
import com.xilinx.rapidwright.edif.EDIFTools;
import com.xilinx.rapidwright.placer.blockplacer.BlockPlacer2Impls;
import com.xilinx.rapidwright.placer.blockplacer.ImplsInstancePort;
import com.xilinx.rapidwright.placer.blockplacer.ImplsPath;
import com.xilinx.rapidwright.router.RouteNode;
import com.xilinx.rapidwright.tests.CodePerfTracker;
import com.xilinx.rapidwright.util.FileTools;
import com.xilinx.rapidwright.util.Installer;
import com.xilinx.rapidwright.util.Job;
import com.xilinx.rapidwright.util.JobQueue;
import com.xilinx.rapidwright.util.LocalJob;
import com.xilinx.rapidwright.util.MessageGenerator;
import com.xilinx.rapidwright.util.Pair;
import com.xilinx.rapidwright.util.StringTools;
import com.xilinx.rapidwright.util.Utils;

/**
 * A collection of methods to operate on {@link Design} objects.
 *
 * Created on: Dec 7, 2015
 */
public class DesignTools {

    private static int uniqueBlackBoxCount = 0;

    // Map from site_pin to list of bels
    // TODO: derive from architecture.
    private static HashMap<String, List<String>> sitePin2Bels = new HashMap<String, List<String>>()
    {{
        put("A_O",  Arrays.asList("A5LUT", "A6LUT"));
        put("AMUX", Arrays.asList("A5LUT", "A6LUT"));
        put("B_O",  Arrays.asList("B5LUT", "B6LUT"));
        put("BMUX", Arrays.asList("B5LUT", "B6LUT"));
        put("C_O",  Arrays.asList("C5LUT", "C6LUT"));
        put("CMUX", Arrays.asList("C5LUT", "C6LUT"));
        put("D_O",  Arrays.asList("D5LUT", "D6LUT"));
        put("DMUX", Arrays.asList("D5LUT", "D6LUT"));
        put("E_O",  Arrays.asList("E5LUT", "E6LUT"));
        put("EMUX", Arrays.asList("E5LUT", "E6LUT"));
        put("F_O",  Arrays.asList("F5LUT", "F6LUT"));
        put("FMUX", Arrays.asList("F5LUT", "F6LUT"));
        put("G_O",  Arrays.asList("G5LUT", "G6LUT"));
        put("GMUX", Arrays.asList("G5LUT", "G6LUT"));
        put("H_O",  Arrays.asList("H5LUT", "H6LUT"));
        put("HMUX", Arrays.asList("H5LUT", "H6LUT"));
    }};

    /**
     * Tries to identify the clock pin source for the given user signal output by
     * tracing back to a FF within a SLICE.  TODO - This method is not very robust.
     * @param netSource The site pin output from which to start the clock search
     * @return The source clock pin for the clock net or null if unable to determine one.
     */
    public static SitePinInst identifyClockSource(SitePinInst netSource) {
        if (!netSource.isOutPin()) return null;
        BELPin p = netSource.getBELPin();
        if (p == null) return null;
        BELPin src = p.getSiteConns().get(0);
        if (src.getBELName().contains("FF")) {
            BELPin clk = src.getBEL().getPin("CLK");
            Net n = netSource.getSiteInst().getNetFromSiteWire(clk.getSiteWireName());
            if (n == null) return null;
            return n.getSource();
        }
        return null;
    }

    public static Net getClockDomain(Design d, String edifNet) {
        // TODO - WIP
        String tokens[] = edifNet.split("/");
        EDIFCellInst curr = d.getNetlist().getTopCellInst();
        for (int i=0; i < tokens.length; i++) {
            if (i == tokens.length-1) {
                for (EDIFPortInst port : curr.getPortInsts()) {
                    System.out.println(port.getPort().getName());
                    if (port.getNet().getName().equals(tokens[i])) {

                    }
                }
            } else {
                curr = curr.getCellType().getCellInst(tokens[i]);
            }

        }

        return null;
    }

    /**
     * Tries to determine the driving cell within the site from the output site pin.
     * @param netSource The output site pin from which to start searching
     * @return The corresponding driving cell, or null if none could be found.
     */
    public static Cell getDrivingCell(SitePinInst netSource) {
        BELPin src = getDrivingBELPin(netSource);
        Cell c = netSource.getSiteInst().getCell(src.getBELName());
        return c;
    }

    /**
     * Gets the originating driving element pin that corresponds to the given site pin.
     * @param netSource The source pin
     * @return The corresponding element pin or null if none could be found.
     */
    public static BELPin getDrivingBELPin(SitePinInst netSource) {
        if (!netSource.isOutPin()) return null;
        return getDrivingBELPin(netSource.getBELPin());
    }

    /**
     * Gets the driving element pin of either a site port output or an element input pin.
     * @param elementPin The element pin of interest.  Cannot be a element output pin.
     * @return The source element pin within the site for the given element pin.
     */
    public static BELPin getDrivingBELPin(BELPin elementPin) {
        if (elementPin == null) return null;
        if (elementPin.isOutput() && elementPin.getBEL().getBELClass() != BELClass.PORT) return null;
        return elementPin.getSiteConns().get(0);
    }

    /**
     * Gets the driven element pins of either a site port input or an element output pin.
     * @param elementPin The element pin of interest. Cannot be an element input pin.
     * @return A list of sink element pins within the site for the given element pin.
     */
    public static ArrayList<BELPin> getDrivenBELPins(BELPin elementPin) {
        if (elementPin == null) return null;
        if (elementPin.isInput() && elementPin.getBEL().getBELClass() == BELClass.PORT) return null;
        return elementPin.getSiteConns();
    }

    /**
     * Uses SitePIP information in the site instance to determine the driving input of the RBEL element
     * output pin.  There should only be one input that can affect the output and this method
     * returns that input pin.
     * @param outputPin The output pin on the RBEL element of interest.
     * @param inst The corresponding site instance where the pin resides.
     * @return The driving input element pin on the RBEL, or null if none could be found.
     */
    public static BELPin getCorrespondingRBELInputPin(BELPin outputPin, SiteInst inst) {
        BEL element = outputPin.getBEL();
        if (element.getHighestInputIndex() == 0) {
            return element.getPin(0);
        }
        SitePIP pip = inst.getUsedSitePIP(outputPin);
        if (pip == null) return null;
        return pip.getInputPin();
    }

    /**
     * Returns the element's output pin corresponding that is being driven by the provided input pin.
     * @param inputPin The input pin of interest
     * @param inst The site instance corresponding to the element of interest
     * @return The element's output pin that is driven by the provided input pin.
     */
    public static BELPin getCorrespondingRBELOutputPin(BELPin inputPin, SiteInst inst) {
        int idx = inputPin.getBEL().getHighestInputIndex() + 1;
        if (inputPin.getBEL().getPins().length > idx + 1) {
            throw new RuntimeException("ERROR: False assumption, this routing BEL has more than one output: " +
                    inputPin.getBELName());
        }
        return inputPin.getBEL().getPin(idx);
    }

    /*
     * TODO - Work in progress
     */
    public static ArrayList<BELPin> getCorrespondingBELInputPins(BELPin outputPin, SiteInst inst) {
        ArrayList<BELPin> inputs = new ArrayList<BELPin>();
        BEL element = outputPin.getBEL();
        // Check if element only has one input
        if (element.getHighestInputIndex() == 0) {
            inputs.add(element.getPin(0));
            return inputs;
        }

        Cell cell = inst.getCell(element.getName());
        /*if (!element.getName().equals("BUFCE")) {
            for (Entry<String,Property> entry : cell.getEdifCellInst().getPropertyList().entrySet()) {
                System.out.println(entry.getKey() + ": " + entry.getValue().getName());
            }// TODO
        }*/
        switch(element.getName()) {
            case "BUFCE":{
                inputs.add(element.getPin("I"));
                break;
            }

            case "A5LUT":
            case "B5LUT":
            case "C5LUT":
            case "D5LUT":
            case "E5LUT":
            case "F5LUT":
            case "G5LUT":
            case "H5LUT":
            case "A6LUT":
            case "B6LUT":
            case "C6LUT":
            case "D6LUT":
            case "E6LUT":
            case "F6LUT":
            case "G6LUT":
            case "H6LUT":
            case "CARRY8":{
                for (int i=0; i <= element.getHighestInputIndex(); i++) {
                    BELPin pin = element.getPin(i);
                    String siteWireName = pin.getSiteWireName();
                    Net net = inst.getNetFromSiteWire(siteWireName);
                    if (net == null) continue;
                    if (net.isStaticNet()) continue;
                    if (!cell.getPinMappingsP2L().containsKey(pin.getName())) continue;
                    inputs.add(pin);
                }
                break;
            }
            default:{
                throw new RuntimeException("ERROR: Problem tracing through " + element.getName() + " in " + inst.getName());
            }

        }


        return inputs;
    }

    /**
     * TODO - Work in progress
     * @param inputPin
     * @param inst
     * @return
     */
    public static ArrayList<BELPin> getCorrespondingBELOutputPins(BELPin inputPin, SiteInst inst) {
        ArrayList<BELPin> outputs = new ArrayList<BELPin>();
        BEL element = inputPin.getBEL();

        Cell cell = inst.getCell(element.getName());
        if (cell == null) {
            return outputs;
        }
        for (int i=element.getHighestInputIndex()+1; i < element.getPins().length; i++) {
            BELPin pin = element.getPin(i);
            String siteWireName = pin.getSiteWireName();
            Net net = inst.getNetFromSiteWire(siteWireName);
            if (net == null) continue;
            if (net.isStaticNet()) continue;
            if (net.getName().equals(Net.USED_NET)) continue;
            if (!cell.getPinMappingsP2L().containsKey(pin.getName())) continue;
            outputs.add(pin);
        }

        return outputs;
    }

    private static HashSet<String> stopElements;
    private static HashSet<String> lutElements;
    private static HashSet<String> regElements;
    static {
        stopElements = new HashSet<String>();
        // CONFIG
        stopElements.add("BSCAN1");
        stopElements.add("BSCAN2");
        stopElements.add("BSCAN3");
        stopElements.add("BSCAN4");
        stopElements.add("DCIRESET");
        stopElements.add("DNAPORT");
        stopElements.add("EFUSE_USR");
        stopElements.add("FRAME_ECC");
        stopElements.add("ICAP_BOT");
        stopElements.add("ICAP_TOP");
        stopElements.add("MASTER_JTAG");
        stopElements.add("STARTUP");
        stopElements.add("USR_ACCESS");

        // BRAM
        stopElements.add("RAMBFIFO36E2");

        // IOB
        stopElements.add("IBUFCTRL");
        stopElements.add("INBUF");
        stopElements.add("OUTBUF");
        stopElements.add("PADOUT");

        // MMCM
        stopElements.add("MMCME3_ADV");

        // DSP
        stopElements.add("DSP_PREADD_DATA");
        stopElements.add("DSP_A_B_DATA");
        stopElements.add("DSP_C_DATA");
        stopElements.add("DSP_OUTPUT");

        lutElements = new HashSet<String>();
        regElements = new HashSet<String>();

        for (String letter : Arrays.asList("A", "B", "C", "D", "E", "F", "G", "H")) {
            regElements.add(letter +"FF");
            regElements.add(letter +"FF2");
            for (String size : Arrays.asList("5", "6")) {
                lutElements.add(letter + size + "LUT");
            }
        }
    }

    public static boolean isBELALut(String elementName) {
        return lutElements.contains(elementName);
    }

    public static boolean isBELAReg(String elementName) {
        return regElements.contains(elementName);
    }

    public static boolean isPinStateBEL(BELPin pin) {
        BEL element = pin.getBEL();
        if (stopElements.contains(element.getName())) return true;

        // TODO - This only finds flops that exist in SLICEs. Need IOs/BRAM/DSP/etc
        return element.getName().contains("FF");
    }

    public static int invertBit(int i, int col) {
        long tmpRow = (long)i;
        long tmpCol = 1 << col;
        tmpRow = tmpRow ^ tmpCol;
        return (int)tmpRow;
    }

    public static long getCurrVal(long lutValue, int i) {
        long out = 0;
        long tmpVal = 1 << i;
        out = lutValue & tmpVal;
        return out;
    }

    public static long moveValToNewRow(long lutValue, int i, int newRow) {
        long out = 0;
        int moveIndex = Math.abs(i-newRow);
        if (i > newRow) {
            out = lutValue >> moveIndex;
        } else {
            out = lutValue << moveIndex;
        }
        return out;
    }

    public static int getInvertCol(String logicalPinName) {
        int result = -1;
        switch (logicalPinName) {
            case "0" : result = 0;
            case "1" : result = 1;
            case "2" : result = 2;
            case "3" : result = 3;
            case "4" : result = 4;
            case "5" : result = 5;
        }
        return result;
    }

    public static String invertLutInput (Cell lut, String physicalPinName) {
        String lutValue = lut.getEDIFCellInst().getProperty("INIT").getValue();
        //String lutValue = "4'hE";
        String numLutRowsStr = lutValue.substring(0, lutValue.indexOf("'"));
        String hexValueStr = lutValue.substring(lutValue.indexOf("h")+1, lutValue.length());
        //long oldVal = Long.parseLong(hexValueStr);
        long oldVal = new BigInteger(hexValueStr, 16).longValue();
        int numLutRows = Integer.parseInt(numLutRowsStr);
        int numInput = (int)(Math.log(numLutRows)/Math.log(2));
        String logicalPinName = lut.getPinMappingsP2L().get(physicalPinName);
        int invertCol = getInvertCol(logicalPinName.substring(logicalPinName.length()-1));
        if (invertCol == -1) {
            System.err.println("Inverted Column is -1 is Function DesignTools.invertLutInput");
        }
        long outHex = 0;

        for (int i = 0; i < 1<<numInput; i++) {
            int newRow = invertBit(i, invertCol);
            System.out.println("old_Row = " + i + " new_Row = " + newRow);
            long currVal = getCurrVal(oldVal, i);
            currVal = moveValToNewRow(currVal, i, newRow);
            outHex |= currVal;
        }
        String hexOutput = numLutRowsStr + "'h";
        hexOutput = hexOutput + Long.toHexString(outHex);
        System.out.println("output INIT = "+ hexOutput);
        //System.out.println("output = " + outHex);
        return hexOutput;
    }

    /**
     * Determines if all the pins connected to this net connect to only LUTs
     * @return True if all pins connect only to LUTs, false otherwise.
     */
    public static boolean areAllPinsConnectedToALUT(Net n) {
        for (SitePinInst p : n.getPins()) {
            Set<Cell> connectedCells = getConnectedCells(p);
            if (connectedCells == null || connectedCells.size() == 0) return false;
            for (Cell lut : connectedCells) {
                if (!lut.getType().contains("LUT")) {
                    return false;
                }
            }
        }
        return true;
    }

    public static void optimizeLUT1Inverters(Design design) {
        ArrayList<Cell> lut1Cells = new ArrayList<Cell>();
        for (Cell c : design.getCells()) {
            if (c.getType().equals("LUT1")) {
                lut1Cells.add(c);
            }
        }

        for (Cell c : lut1Cells) {

            // 1. Determine if this LUT can be merged into its source or sink
            String lutInputSiteWire = c.getSiteWireNameFromLogicalPin("I0");
            Net inputNet = c.getSiteInst().getNetFromSiteWire(lutInputSiteWire);
            String lutOutputSiteWire = c.getSiteWireNameFromLogicalPin("O");
            Net outputNet = c.getSiteInst().getNetFromSiteWire(lutOutputSiteWire);

            if (inputNet == null || outputNet == null || inputNet.getPins().size() == 0 || outputNet.getPins().size() == 0) continue;

            SitePinInst lut1InputPin = null;
            for (SitePinInst p : inputNet.getPins()) {
                if (p.isOutPin()) continue;
                if (p.getName().equals(lutInputSiteWire)) {
                    lut1InputPin = p;
                    break;
                }
            }
            //invertLutInput(c, lutInputSiteWire);
            boolean pushInverterForward = true;
            if (areAllPinsConnectedToALUT(outputNet)) {
                pushInverterForward = true;
            } else if (areAllPinsConnectedToALUT(inputNet)) {
                // We can push the inverter backwards
                // TODO - I don't think this will happen for now.
                pushInverterForward = false;
            } else {
                // We can't do it
                continue;
            }

            // 2. Modify LUT equation of neighboring LUT TODO TODO TODO
            if (pushInverterForward) {

            } else {

            }

            // 3. Remove LUT1 from logical netlist
            EDIFPortInst toRemove = null;
            for (EDIFPortInst portInst : inputNet.getLogicalNet().getPortInsts()) {
                if (portInst.getCellInst().equals(c.getEDIFCellInst())) {
                    toRemove = portInst;
                    break;
                }
            }
            if (toRemove != null) inputNet.getLogicalNet().removePortInst(toRemove);
            for (EDIFPortInst portInst : outputNet.getLogicalNet().getPortInsts()) {
                if (portInst.getCellInst() != null && portInst.getCellInst().equals(c.getEDIFCellInst())) continue;
                inputNet.getLogicalNet().addPortInst(portInst);
            }
            outputNet.getLogicalNet().getParentCell().removeNet(outputNet.getLogicalNet());
            c.getEDIFCellInst().getParentCell().removeCellInst(c.getEDIFCellInst());

            // 4. Remove the LUT1, reconnect nets
            design.removeCell(c);
            inputNet.removePin(lut1InputPin);
            outputNet.removeSource();
            design.movePinsToNewNetDeleteOldNet(outputNet, inputNet, false);

            // 5. Detach module instance if it exists
            c.getSiteInst().detachFromModule();
        }


    }

    /**
     * Creates a new pin on a site connected to the cell pin and also adds it to the
     * provided net. Updates both logical and physical netlist representations. Note: If the
     * site pin being added is an output it will displace any existing output source on
     *  the given net.
     * @param cell The placed source or sink BEL instance from which to trace to a site pin.
     * Must have a direct connection to a site pin.
     * @param cellPinName Name of the logical pin name on the cell.
     * @param net The net to add the pin to.
     * @return The newly created pin that has been added to net
     */
    public static SitePinInst createPinAndAddToNet(Cell cell, String cellPinName, Net net) {
        // Cell must be placed
        if (cell.getSiteInst() == null || cell.getSiteInst().isPlaced() == null) {
            throw new RuntimeException("ERROR: Cannot create pin for cell " + cell.getName() + " that is unplaced.");
        }
        // Get the cell pin
        BELPin cellPin = cell.getBEL().getPin(cell.getPhysicalPinMapping(cellPinName));
        if (cellPin == null) {
            throw new RuntimeException("ERROR: Couldn't find " + cellPinName + " on element " + cell.getBELName() + ".");
        }
        // Get the connected site pin from cell pin
        String sitePinName = cellPin.getConnectedSitePinName();
        if (sitePinName == null) {
            sitePinName = cell.getCorrespondingSitePinName(cellPinName);
        }
        if (sitePinName == null) {
            throw new RuntimeException("ERROR: Couldn't find corresponding site pin for element pin " + cellPin + ".");
        }
        if (!cell.getSiteInst().getSite().hasPin(sitePinName)) {
            throw new RuntimeException("ERROR: Site pin mismatch.");
        }
        // Create the pin
        SitePinInst sitePin = new SitePinInst(cellPin.isOutput(), sitePinName, cell.getSiteInst());
        if (sitePin.isOutPin()) {
            SitePinInst oldSource = net.replaceSource(sitePin);
            if (oldSource != null)
                oldSource.detachSiteInst();
        } else {
            net.addPin(sitePin);
        }

        EDIFNetlist e = cell.getSiteInst().getDesign().getNetlist();
        EDIFNet logicalNet = net.getLogicalNet() == null ? e.getTopCell().getNet(net.getName()) : net.getLogicalNet();
        if (logicalNet == null) {
            throw new RuntimeException("ERROR: Unable to determine logical net for physical net: " + net.getName());
        }

        // Remove logical sources
        if (sitePin.isOutPin()) {
            boolean includeTopPorts = true;
            Collection<EDIFPortInst> sources = logicalNet.getSourcePortInsts(includeTopPorts);
            for (EDIFPortInst epr : sources) {
                logicalNet.removePortInst(epr);
            }
        }

        if (cell.getEDIFCellInst() == null) {
            throw new RuntimeException("ERROR: Couldn't identify logical cell from cell " + cell.getName());
        }
        EDIFCellInst eCellInst = cell.getEDIFCellInst();
        EDIFPort ePort = eCellInst.getPort(cellPinName);

        EDIFPortInst newPortInst = new EDIFPortInst(ePort, logicalNet, eCellInst);
        logicalNet.addPortInst(newPortInst);

        return sitePin;
    }

    public static Map<UtilizationType, Integer> calculateUtilization(Design d, PBlock pblock) {
        Set<Site> sites = pblock.getAllSites(null);
        List<SiteInst> siteInsts = d.getSiteInsts().stream().filter(s -> sites.contains(s.getSite()))
                .collect(Collectors.toList());
        return calculateUtilization(siteInsts);
    }

    public static Map<UtilizationType, Integer> calculateUtilization(Design d) {
        return calculateUtilization(d.getSiteInsts());
    }

    public static Map<UtilizationType, Integer> calculateUtilization(Collection<SiteInst> siteInsts) {
        Map<UtilizationType, Integer> map = new HashMap<UtilizationType, Integer>();

        for (UtilizationType ut : UtilizationType.values()) {
            map.put(ut, 0);
        }

        for (SiteInst si : siteInsts) {
            SiteTypeEnum s = si.getSite().getSiteTypeEnum();
            if (Utils.isSLICE(si)) {
                incrementUtilType(map, UtilizationType.CLBS);
                if (s == SiteTypeEnum.SLICEL) {
                    incrementUtilType(map, UtilizationType.CLBLS);
                } else if (s == SiteTypeEnum.SLICEM) {
                    incrementUtilType(map, UtilizationType.CLBMS);
                }
            } else if (Utils.isDSP(si)) {
                incrementUtilType(map, UtilizationType.DSPS);
            } else if (Utils.isBRAM(si)) {
                if (s == SiteTypeEnum.RAMBFIFO36) {
                    incrementUtilType(map, UtilizationType.RAMB36S_FIFOS);
                } else if (s == SiteTypeEnum.RAMB181 || s == SiteTypeEnum.RAMBFIFO18) {
                    incrementUtilType(map, UtilizationType.RAMB18S);
                }
            } else if (Utils.isURAM(si)) {
                incrementUtilType(map, UtilizationType.URAMS);
            }
            for (Cell c : si.getCells()) {
                /*
                CLB_LUTS("CLB LUTs"),
                LUTS_AS_LOGIC("LUTs as Logic"),
                LUTS_AS_MEMORY("LUTs as Memory"),
                CLB_REGS("CLB Regs"),
                REGS_AS_FFS("Regs as FF"),
                REGS_AS_LATCHES("Regs as Latch"),
                CARRY8S("CARRY8s"),
                F7_MUXES("F7 Muxes"),
                F8_MUXES("F8 Muxes"),
                F9_MUXES("F9 Muxes"),
                CLBS("CLBs"),
                CLBLS("CLBLs"),
                CLBMS("CLBMs"),
                LUT_FF_PAIRS("Lut/FF Pairs"),
                RAMB36S_FIFOS("RAMB36s/FIFOs"),
                RAMB18S("RAMB18s"),
                DSPS("DSPs");
                */
                String belName = c.getBELName();
                if (isBELAReg(belName)) {
                    incrementUtilType(map, UtilizationType.CLB_REGS);
                    incrementUtilType(map, UtilizationType.REGS_AS_FFS);
                } else if (belName != null && belName.contains("CARRY")) {
                    incrementUtilType(map, UtilizationType.CARRY8S);
                }
            }
            for (char letter : LUTTools.lutLetters) {
                Cell c5 = si.getCell(letter +"5LUT");
                Cell c6 = si.getCell(letter +"6LUT");
                if (c5 != null && c5.isRoutethru()) {
                    c5 = null;
                } else if (c6 != null && c6.isRoutethru()) {
                    c6 = null;
                }
                if (c5 != null || c6 != null) {
                    incrementUtilType(map, UtilizationType.CLB_LUTS);

                    if (isCellLutMemory(c5) || isCellLutMemory(c6)) {
                        incrementUtilType(map, UtilizationType.LUTS_AS_MEMORY);
                    } else {
                        incrementUtilType(map, UtilizationType.LUTS_AS_LOGIC);
                    }
                }
            }
        }
        return map;
    }

    private static boolean isCellLutMemory(Cell c) {
        if (c == null) return false;
        if (c.getType().contains("SRL") || c.getType().contains("RAM")) return true;
        return false;
    }

    private static void incrementUtilType(Map<UtilizationType, Integer> map, UtilizationType ut) {
        Integer val = map.get(ut);
        val++;
        map.put(ut, val);
    }

    /**
     * Creates a verilog wrapper file for this design by examining the
     * top level netlist.
     * @param fileName Name of the desired verilog file.
     */
    public static void writeVerilogStub(Design design, String fileName) {
        FileOutputStream fos = null;
        try {
            fos = new FileOutputStream(fileName);
            RTLStubGenerator.createVerilogStub(design, fos);
            fos.close();
        } catch (IOException e) {
            MessageGenerator.briefError("ERROR: Failed to write verilog stub " + fileName);
            e.printStackTrace();
        }
    }

    /**
     * Creates two CSV files based on this design, one for instances and one
     * for nets.
     * @param fileName
     */
    public static void toCSV(String fileName, Design design) {
        String nl = System.getProperty("line.separator");
        try {
            BufferedWriter bw = new BufferedWriter(new FileWriter(fileName + ".instances.csv"));
            bw.write("\"Name\",\"Type\",\"Site\",\"Tile\",\"#Pins\"" + nl);

            for (SiteInst i : design.getSiteInsts()) {
                bw.write("\"" + i.getName() + "\",\"" +
                                i.getSiteTypeEnum() + "\",\"" +
                                i.getSiteName() + "\",\"" +
                                i.getTile()+ "\",\"" +
                                i.getSitePinInstMap().size()+ "\"" + nl);
            }
            bw.close();
        } catch (IOException e) {
            e.printStackTrace();
        }

        try {
            BufferedWriter bw = new BufferedWriter(new FileWriter(fileName + ".nets.csv"));
            bw.write("\"Name\",\"Type\",\"Fanout\"" + nl);

            for (Net n : design.getNets()) {
                bw.write("\"" + n.getName() + "\",\"" +
                                n.getType() + "\",\"" +
                                n.getFanOut()+ "\"" + nl);
            }
            bw.close();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    /**
     * Demonstrates a rudimentary path expansion for finding a routing path in the
     * interconnect.
     * @param start Desired start node
     * @param end Desired end node
     * @return A list of PIPs that configure a path from start to end nodes, or null if a path could not be found.
     */
    public static List<PIP> findRoutingPath(Node start, Node end) {
        return findRoutingPath(new RouteNode(start), new RouteNode(end));
    }

    /**
     * Demonstrates a rudimentary path expansion for finding a routing path in the
     * interconnect.
     * @param start Desired start node
     * @param end Desired end node
     * @return A list of PIPs that configure a path from start to end nodes, or null if a path could not be found.
     */
    public static List<PIP> findRoutingPath(RouteNode start, RouteNode end) {
        PriorityQueue<RouteNode> q = new PriorityQueue<RouteNode>(16, new Comparator<RouteNode>() {
            public int compare(RouteNode i, RouteNode j) {return i.getCost() - j.getCost();}});
        q.add(start);
        HashSet<Wire> visited = new HashSet<>();
        visited.add(new Wire(start.getTile(), start.getWire()));

        while (!q.isEmpty()) {
            RouteNode curr = q.remove();
            if (curr.equals(end)) {
                return curr.getPIPsBackToSource();
            }
            if (visited.size() > 100000) return null;
            for (Wire w : curr.getConnections()) {
                if (visited.contains(w)) continue;
                visited.add(w);
                RouteNode rn = new RouteNode(w,curr);
                rn.setCost((rn.getManhattanDistance(end) << 1) + rn.getLevel());
                q.add(rn);
            }
        }
        return null;
    }

    /**
     * Examines a site wire in a populated site inst for all the connected BELPins for
     * cells occupying those BELs.  It attempts to lookup the net attached to the cell pin
     * in order to find the hierarchical parent net name of the net and returns its name.
     * @param inst The site instance where the site wire in question resides.
     * @param siteWire The site wire index in the site where the site inst resides.
     * @return The hierarchical parent net name using the site wire or null if none could be found.
     */
    public static String resolveNetNameFromSiteWire(SiteInst inst, int siteWire) {
        String parentNetName = null;
        EDIFNetlist netlist = inst.getDesign().getNetlist();
        Map<String,String> parentNetMap = netlist != null ? netlist.getParentNetMapNames() : Collections.emptyMap();
        BELPin[] pins = inst.getSite().getBELPins(siteWire);
        for (BELPin pin : pins) {
            if (pin.isSitePort()) continue;
            Cell c = inst.getCell(pin.getBELName());
            if (c == null || c.getEDIFCellInst() == null) {
                Net currNet = inst.getNetFromSiteWire(pin.getSiteWireName());
                if (currNet == null) {
                    continue;
                } else {
                    return parentNetMap.getOrDefault(currNet.getName(), currNet.getName());
                }
            }
            String logPinName = c.getLogicalPinMapping(pin.getName());
            EDIFPortInst portInst = logPinName == null ? null : c.getEDIFCellInst().getPortInst(logPinName);
            if (portInst == null) continue;
            EDIFNet net =  portInst.getNet();
            String netName = c.getParentHierarchicalInstName() + EDIFTools.EDIF_HIER_SEP + net.getName();
            parentNetName = parentNetMap.getOrDefault(netName, netName);
        }
        return parentNetName;
    }

    private static String createInformativeCellInterfaceMismatchMessage(String hierCellInstName,
                                                    EDIFCell target, EDIFCell src) {
        Map<String, EDIFPort> cellPorts = new HashMap<>(target.getPortMap());
        StringBuilder sb = new StringBuilder();
        for (EDIFPort p : src.getPorts()) {
            EDIFPort otherPort = cellPorts.remove(p.getBusName(true));
            if (otherPort == null) {
                otherPort = cellPorts.remove(p.getName());
                if (otherPort == null) {
                    sb.append("\n  port " + p.getName() + " doesn't exist on " + src);
                }
            }

            if (!Objects.equals(p.getWidth(), p.getWidth()) ||
               !Objects.equals(p.getDirection(), p.getDirection())) {
                sb.append("\n  port " + p.getName() + " mismatch in direction/width");
            }
        }
        for (String portName : cellPorts.keySet()) {
            sb.append("\n  port " + portName + " is missing on " + src);
        }

        return "\nERROR: The destination instance " + hierCellInstName +
                " has a different port signature than " + src.getName() + ":" + sb.toString();
    }

    /**
     * NOTE: This method is not fully tested.
     * Populates a black box in a netlist with the provided design. This method
     * most closely resembles the Vivado command {@code read_checkpoint -cell <cell name> <DCP Name>}
     * @param design The top level design
     * @param hierarchicalCellName Name of the black box in the design netlist.
     * @param cell The 'guts' to be inserted into the black box
     */
    public static void populateBlackBox(Design design, String hierarchicalCellName, Design cell) {
        EDIFNetlist netlist = design.getNetlist();

        // Populate Logical Netlist into cell
        EDIFCellInst inst = netlist.getCellInstFromHierName(hierarchicalCellName);
        if (!inst.isBlackBox()) {
            System.err.println("ERROR: The cell instance " + hierarchicalCellName + " is not a black box.");
            return;
        }
        if (!inst.getCellType().hasCompatibleInterface(cell.getTopEDIFCell())) {
            throw new RuntimeException(createInformativeCellInterfaceMismatchMessage(
                    hierarchicalCellName, inst.getCellType(), cell.getTopEDIFCell()));
        }

        inst.getCellType().getLibrary().removeCell(inst.getCellType());
        netlist.migrateCellAndSubCells(cell.getTopEDIFCell(), true);
        inst.setCellType(cell.getTopEDIFCell());
        netlist.removeUnusedCellsFromAllWorkLibraries();

        // Add placement information
        // We need to prefix all cell and net names with the hierarchicalCellName as a prefix
        for (SiteInst si : cell.getSiteInsts()) {
            for (Cell c : new ArrayList<Cell>(si.getCells())) {
                c.updateName(hierarchicalCellName + "/" + c.getName());
                if (!c.isRoutethru())
                    design.addCell(c);
                else {
                    for (Entry<String, AltPinMapping> p : c.getAltPinMappings().entrySet()) {
                        p.getValue().setAltCellName(hierarchicalCellName + "/" + p.getValue().getAltCellName());
                    }
                }
            }
            design.addSiteInst(si);
        }

        // Add routing information
        for (Net net : new ArrayList<>(cell.getNets())) {
            if (net.getName().equals(Net.USED_NET)) continue;
            if (net.isStaticNet()) {
                Net staticNet = design.getStaticNet(net.getType());
                staticNet.addPins((ArrayList<SitePinInst>)net.getPins());
                HashSet<PIP> uniquePIPs = new HashSet<PIP>(net.getPIPs());
                uniquePIPs.addAll(staticNet.getPIPs());
                staticNet.setPIPs(uniquePIPs);
            } else {
                net.updateName(hierarchicalCellName + "/" + net.getName());
                design.addNet(net);
            }
        }

        // Rectify boundary nets
        netlist.resetParentNetMap();

        postBlackBoxCleanup(hierarchicalCellName, design);

        List<String> encryptedCells = cell.getNetlist().getEncryptedCells();
        if (encryptedCells != null && encryptedCells.size() > 0) {
            design.getNetlist().addEncryptedCells(encryptedCells);
        }
    }

    /**
     * Attempts to rename boundary nets around the previous blackbox to follow naming convention
     * (net is named after source).
     * @param hierCellName The hierarchical cell instance that was previously a black box
     * @param design The current design.
     */
    public static void postBlackBoxCleanup(String hierCellName, Design design) {
        EDIFNetlist netlist = design.getNetlist();
        EDIFHierCellInst inst = netlist.getHierCellInstFromName(hierCellName);
        final EDIFHierCellInst parentInst = inst.getParent();

        // for each port on the black box,
        //   iterate over all the nets and regularize on the proper net name for the physical
        //   net.  Put all physical pins on the correct physical net once the black box has been
        //   updated.
        for (EDIFPortInst portInst : inst.getInst().getPortInsts()) {
            EDIFNet net = portInst.getNet();
            EDIFHierNet netName = new EDIFHierNet(parentInst, net);
            EDIFHierNet parentNetName = netlist.getParentNet(netName);
            Net parentNet = design.getNet(parentNetName.getHierarchicalNetName());
            if (parentNet == null) {
                parentNet = new Net(parentNetName);
            }
            for (EDIFHierNet netAlias : netlist.getNetAliases(netName)) {
                if (parentNet.getName().equals(netAlias.getHierarchicalNetName())) continue;
                Net alias = design.getNet(netAlias.getHierarchicalNetName());
                if (alias != null) {
                    // Move this non-parent net physical information to the parent
                    for (SiteInst si : new ArrayList<>(alias.getSiteInsts())) {
                        List<String> siteWires = si.getSiteWiresFromNet(alias);
                        if (siteWires != null) {
                            for (String siteWire : new ArrayList<>(siteWires)) {
                                BELPin belPin = si.getSite().getBELPins(siteWire)[0];
                                si.unrouteIntraSiteNet(belPin, belPin);
                                si.routeIntraSiteNet(parentNet, belPin, belPin);
                            }
                        }
                    }
                    for (SitePinInst pin : new ArrayList<SitePinInst>(alias.getPins())) {
                        alias.removePin(pin);
                        parentNet.addPin(pin);
                    }
                    alias.unroute();
                }
            }
            parentNet.unroute();
        }
    }

    /**
     * Creates a map from Node to a list of PIPs for a given list of PIPs
     * (likely from the routing of a net).
     * @param route The list of PIPs to create the map from.
     * @return The map of all involved nodes to their respectively connected PIPs.
     */
    public static Map<Node, ArrayList<PIP>> getNodePIPMap(List<PIP> route) {
        Map<Node,ArrayList<PIP>> conns = new HashMap<>();
        // Create a map from nodes to PIPs
        for (PIP pip : route) {
            for (int wireIndex : new int[]{pip.getStartWireIndex(), pip.getEndWireIndex()}) {
                Node curr = Node.getNode(pip.getTile(), wireIndex);
                ArrayList<PIP> pips = conns.get(curr);
                if (pips == null) {
                    pips = new ArrayList<>();
                    conns.put(curr, pips);
                }
                pips.add(pip);
            }
        }
        return conns;
    }

    /**
     * Examines the routing of a net and will remove all parts of the routing
     * that connect to the provided node.  This is most useful when attempting to
     * unroute parts of a static (VCC/GND) net that have multiple sources.
     * @param net The net with potential disjoint routing trees
     * @param node Node belonging to the routing tree to remove.
     * @return True if PIPs were removed, false otherwise
     */
    public static boolean removeConnectedRouting(Net net, Node node) {
        HashSet<PIP> toRemove = new HashSet<>();
        Map<Node,ArrayList<PIP>> conns = getNodePIPMap(net.getPIPs());

        // Traverse the connected set of PIPs starting from the node
        Queue<Node> q = new LinkedList<>();
        q.add(node);
        while (!q.isEmpty()) {
            Node curr = q.poll();
            ArrayList<PIP> pips = conns.get(curr);
            if (pips == null) continue;
            for (PIP p : pips) {
                // Be careful to detect a cycle
                if (!toRemove.contains(p)) {
                    toRemove.add(p);
                    Node startNode = p.getStartNode();
                    q.add(curr.equals(startNode) ? startNode : p.getEndNode());
                }
            }
        }

        if (toRemove.size() == 0) return false;

        // Update net with new PIPs
        ArrayList<PIP> keep = new ArrayList<>();
        for (PIP p : net.getPIPs()) {
            if (toRemove.contains(p)) continue;
            keep.add(p);
        }
        net.setPIPs(keep);

        return true;
    }

    /**
     * Unroutes pins from a specific net by only removing the routing (PIPs) that are essential
     * for those pins.  This allows the net to remain routed in the context of other pins not
     * being removed.  This enables a batch approach which is much more efficient than removing
     * pins individually.
     * @param net The current net to modify routing and to which all pins will have their routing
     * removed. If any pin passed in is not of this net, it is skipped and no effect is taken.
     * @param pins Pins that belong to the provided net that should have their selective routing
     * removed.
     * Source pins are handled by {@link #unrouteSourcePin(SitePinInst)}.
     */
    public static void unroutePins(Net net, Collection<SitePinInst> pins) {
        List<SitePinInst> sinkPins = new ArrayList<>(pins.size());
        pins.forEach((spi) -> {
            if (spi.isOutPin()) {
                // TODO - This can lead to a slow down in VCC and GND nets as it is not batched
                DesignTools.unrouteSourcePin(spi);
            } else {
                sinkPins.add(spi);
            }
        });
        removePIPsFromNet(net,getTrimmablePIPsFromPins(net, sinkPins));
        for (SitePinInst pin : sinkPins) {
            pin.setRouted(false);
        }
    }

    private static void removePIPsFromNet(Net net, Set<PIP> pipsToRemove) {
        if (pipsToRemove.size() > 0) {
            List<PIP> updatedPIPs = new ArrayList<>();
            for (PIP pip : net.getPIPs()) {
                if (!pipsToRemove.contains(pip)) updatedPIPs.add(pip);
            }
            net.setPIPs(updatedPIPs);
        }
    }

    /**
     * Unroutes a SitePinInst of a net.  This is desirable when a net has multiple SitePinInst
     * source pins (multiple outputs of a Site) and only a particular branch is desired to be
     * unrouted.  If the entire net is to be unrouted, a more efficient method is {@link Net#unroute()}.
     * @param src The source pin of the net from which to remove the routing
     * @return The set of PIPs that were unrouted from the net.
     */
    public static Set<PIP> unrouteSourcePin(SitePinInst src) {
        if (!src.isOutPin() || src.getNet() == null) return Collections.emptySet();
        Node srcNode = src.getConnectedNode();
        Set<PIP> pipsToRemove = new HashSet<>();

        Map<Node, List<PIP>> pipMap = new HashMap<>();
        for (PIP pip : src.getNet().getPIPs()) {
            Node node = pip.isReversed() ? pip.getEndNode() : pip.getStartNode();
            pipMap.computeIfAbsent(node, k -> new ArrayList<>()).add(pip);
        }

        Map<Node,SitePinInst> sinkNodes = new HashMap<>();
        for (SitePinInst sinkPin : src.getNet().getSinkPins()) {
            sinkNodes.put(sinkPin.getConnectedNode(), sinkPin);
        }

        Queue<Node> q = new LinkedList<>();
        q.add(srcNode);
        while (!q.isEmpty()) {
            Node curr = q.poll();
            List<PIP> pips = pipMap.get(curr);
            if (pips != null) {
                for (PIP p : pips) {
                    Node endNode = p.isReversed() ? p.getStartNode() : p.getEndNode();
                    q.add(endNode);
                    pipsToRemove.add(p);
                    SitePinInst sink = sinkNodes.get(endNode);
                    if (sink != null) {
                        sink.setRouted(false);
                    }
                }
            }
        }

        src.setRouted(false);
        removePIPsFromNet(src.getNet(), pipsToRemove);
        return pipsToRemove;
    }

    /**
     * For the given set of pins, if they were removed, determine which PIPs could be trimmed as
     * they no longer route to any specific sink.  This method only works for sink pins.
     * See {@link #unrouteSourcePin(SitePinInst)} for handling source pin unroutes.
     * @param net The current net
     * @param pins The set of pins to remove.
     * @return The set of redundant (trimmable) PIPs that cane safely be removed when removing the
     * set of provided pins from the net.
     */
    public static Set<PIP> getTrimmablePIPsFromPins(Net net, Collection<SitePinInst> pins) {
        // Map listing the PIPs that drive a Node
        Map<Node,ArrayList<PIP>> reverseConns = new HashMap<>();
        Map<Node,Integer> fanout = new HashMap<>();
        Set<Node> nodeSinkPins = new HashSet<>();
        for (SitePinInst sinkPin : net.getSinkPins()) {
            nodeSinkPins.add(sinkPin.getConnectedNode());
        }
        for (PIP pip : net.getPIPs()) {
            Node endNode = pip.isReversed() ? pip.getStartNode() : pip.getEndNode();
            Node startNode = pip.isReversed() ? pip.getEndNode() : pip.getStartNode();

            ArrayList<PIP> rPips = reverseConns.computeIfAbsent(endNode, (n) -> new ArrayList<>());
            rPips.add(pip);

            fanout.merge(startNode, 1, Integer::sum);

            if (nodeSinkPins.contains(endNode)) {
                fanout.merge(endNode, 1, Integer::sum);
            }
        }

        HashSet<PIP> toRemove = new HashSet<>();
        ArrayList<Node> updateFanout = new ArrayList<>();

        for (SitePinInst p : pins) {
            if (p.getSiteInst() == null || p.getSite() == null) continue;
            if (p.getNet() != net) continue;
            Node sink = p.getConnectedNode();
            Integer fanoutCount = fanout.get(sink);
            if (fanoutCount == null) {
                // Pin is not routed
            } else {
                assert(fanoutCount >= 1);
                updateFanout.add(sink);

                if (fanoutCount > 1) {
                    // This node is also used to connect another downstream pin, no more
                    // analysis necessary
                } else {
                    ArrayList<PIP> curr = reverseConns.get(sink);
                    while (curr != null && curr.size() == 1 && fanoutCount < 2) {
                        PIP pip = curr.get(0);
                        toRemove.add(pip);
                        updateFanout.add(pip.isReversed() ? pip.getEndNode() : pip.getStartNode());
                        sink = new Node(pip.getTile(), pip.isReversed() ? pip.getEndWireIndex() :
                                pip.getStartWireIndex());
                        curr = reverseConns.get(sink);
                        fanoutCount = fanout.getOrDefault(sink, 0);
                    }
                    if (curr == null && !net.isStaticNet()) {
                        if (fanoutCount == 1 && net.getAlternateSource() != null && net.getSource() != null) {
                            // check if this is a dual-output net and if we just removed one of the outputs
                            // if so, remove the logical driver flag
                            for (PIP pip : net.getPIPs()) {
                                if (pip.isLogicalDriver()) {
                                    pip.setIsLogicalDriver(false);
                                    break;
                                }
                            }
                        }

                        if (fanout.size() == 1) {
                            // We got all the way back to the source site. It is likely that
                            // the net is using dual exit points from the site as is common in
                            // SLICEs -- we should unroute the sitenet
                            SitePin sPin = sink.getSitePin();
                            if (net.getSource() != null) {
                                SiteInst si = net.getSource().getSiteInst();
                                BELPin belPin = sPin.getBELPin();
                                si.unrouteIntraSiteNet(belPin, belPin);
                            }
                        }
                    }
                }
            }
            for (Node startNode : updateFanout) {
                fanout.compute(startNode, (k,v) -> {
                        if (v == null) throw new RuntimeException();
                        assert(v > 0);
                        return (--v == 0) ? null : v;
                });
            }
            updateFanout.clear();
        }
        return toRemove;
    }

    private static void fullyUnplaceCellHelper(Cell cell, Map<Net, Set<SitePinInst>> deferRemovals) {
        SiteInst siteInst = cell.getSiteInst();
        BEL bel = cell.getBEL();
        // If cell was using shared control signals (CLK, CE, RST), check to see if this was
        // the last cell used and then remove the site routing, site pin, and partial routing if
        // it exists
        for (BELPin pin : bel.getPins()) {
            Net net = siteInst.getNetFromSiteWire(pin.getSiteWireName());
            if (net == null) {
                // Under certain circumstances, site routing is not explicit for VCC/GND
                // Unfortunately, Vivado does not label the SR pin as SET or RESET
                if (bel.isFF() && (pin.isEnable() || pin.getName().equals("SR"))) {
                    String sitePinName = getSitePinSource(pin);
                    SitePinInst spi = siteInst.getSitePinInst(sitePinName);
                    if (spi == null || !spi.getNet().isStaticNet()) continue;
                    boolean otherUsers = false;
                    for (BELPin otherPin : siteInst.getSiteWirePins(pin.getSiteWireName())) {
                        if (otherPin == pin || otherPin.isOutput()) continue;
                        if (siteInst.getCell(otherPin.getBEL()) != null) {
                            otherUsers = true;
                            break;
                        }
                    }
                    if (!otherUsers) {
                        handlePinRemovals(spi, deferRemovals);
                    }
                }
                continue;
            }
            boolean otherUser = false;
            Queue<String> siteWires = new LinkedList<>();
            Set<String> visited = new HashSet<>();
            siteWires.add(pin.getSiteWireName());
            while (!siteWires.isEmpty()) {
                String siteWire = siteWires.poll();
                visited.add(siteWire);
                for (BELPin otherPin : siteInst.getSiteWirePins(siteWire)) {
                    if (otherPin == pin) continue;
                    if (otherPin.getBEL().getBELClass() == BELClass.RBEL) {
                        SitePIP pip = siteInst.getUsedSitePIP(otherPin);
                        if (pip != null) {
                            String nextSiteWire = pip.getInputPin() == otherPin ?
                                    pip.getOutputPin().getSiteWireName() : pip.getInputPin().getSiteWireName();
                            if (!visited.contains(nextSiteWire)) {
                                siteWires.add(nextSiteWire);
                            }
                        }
                        continue;
                    }
                    Cell otherCell = siteInst.getCell(otherPin.getBEL());
                    if (otherCell == null) continue;
                    if (otherCell.isRoutethru()) {
                        // This will be handled outside of the loop in SiteInst.unrouteIntraSiteNet()
                        continue;
                    }
                    String logicalPinName = otherCell.getLogicalPinMapping(otherPin.getName());
                    if (logicalPinName == null) continue;
                    otherUser = true;
                    break;
                }
            }
            if (otherUser == false) {
                // Unroute site routing back to pin and remove site pin
                for (String sitePinName : getAllRoutedSitePinsFromPhysicalPin(cell, net, pin.getName())) {
                    BELPin sitePortBelPin = siteInst.getSite().getBELPin(sitePinName);
                    assert(sitePortBelPin.isSitePort());
                    boolean outputSitePin = sitePortBelPin.isInput(); // Input BELPin means output SitePin
                    if (outputSitePin) {
                        siteInst.unrouteIntraSiteNet(pin, sitePortBelPin);
                    } else {
                        siteInst.unrouteIntraSiteNet(sitePortBelPin, pin);
                    }
                    SitePinInst spi = siteInst.getSitePinInst(sitePinName);
                    // It's possible site wire could be set (e.g. reserved using GLOBAL_USEDNET)
                    // but no inter-site routing (thus no SPI) associated
                    if (spi != null) {
                        handlePinRemovals(spi, deferRemovals);

                        if (outputSitePin) {
                            assert(spi.isOutPin());
                            SitePinInst altSpi = net.getAlternateSource();
                            if (altSpi != null) {
                                if (spi == altSpi) {
                                    altSpi = net.getSource();
                                    assert(spi != altSpi);
                                }
                                siteInst.unrouteIntraSiteNet(pin, altSpi.getBELPin());
                                handlePinRemovals(altSpi, deferRemovals);
                            }
                        }
                    }
                }
            }
        }

        if (bel.isLUT() && bel.getName().endsWith("5LUT")) {
            String lut6 = bel.getName().replace('5', '6');
            if (siteInst.getCell(lut6) == null) {
                SitePinInst a6Spi = siteInst.getSitePinInst(lut6.substring(0,2));
                siteInst.unrouteIntraSiteNet(a6Spi.getBELPin(), siteInst.getBELPin(lut6, "A6"));
                handlePinRemovals(a6Spi, deferRemovals);
            }
        }

        // Check and remove routethrus that exist that point to removed cell
        List<BEL> belsToRemove = null;
        for (Cell otherCell : siteInst.getCells()) {
            if (otherCell.hasAltPinMappings() && otherCell.getName().equals(cell.getName())) {
                if (belsToRemove == null) belsToRemove = new ArrayList<>();
                belsToRemove.add(otherCell.getBEL());
            }
        }
        if (belsToRemove != null) {
            for (BEL b : belsToRemove) {
                siteInst.removeCell(b);
            }
        }
    }

    /**
     * This method will fully unplace (but not remove) a physical cell from a design.
     * In the case where the unplaced cell is the last user of a shared control signal (CLK, CE, SR)
     * then that pin will also be removed and unrouted immediately if deferRemovals is null, otherwise
     * it is added to this map.
     * @param cell The cell to unplace
     * @param deferRemovals An optional map that, if passed in non-null will be populated with
     * site pins marked for removal.  The map allows for persistent tracking if this method is called
     * many times as the process is expensive without batching.
     */
    public static void fullyUnplaceCell(Cell cell, Map<Net, Set<SitePinInst>> deferRemovals) {
        fullyUnplaceCellHelper(cell, deferRemovals);
        cell.unplace();
    }

    /**
     * This method will completely remove a placed cell (both logical and physical) from a design.
     * In the case where the removed cell is the last user of a shared control signal (CLK, CE, SR)
     * then that pin will also be removed and unrouted immediately if deferRemovals is null, otherwise
     * it is added to this map.
     * @param design The design where the cell is instantiated
     * @param cell The cell to remove
     * @param deferRemovals An optional map that, if passed in non-null will be populated with
     * site pins marked for removal.  The map allows for persistent tracking if this method is called
     * many times as the process is expensive without batching.
     */
    public static void fullyRemoveCell(Design design, Cell cell, Map<Net, Set<SitePinInst>> deferRemovals) {
        fullyUnplaceCellHelper(cell, deferRemovals);

        // Remove Physical Cell
        design.removeCell(cell);

        // Remove Logical Cell
        for (EDIFPortInst portInst : cell.getEDIFCellInst().getPortInsts()) {
            EDIFNet en = portInst.getNet();
            if (en != null) {
                en.removePortInst(portInst);
            }
        }
        cell.getParentCell().removeCellInst(cell.getEDIFCellInst());
    }

    /**
     * Helper method for either removing (and unrouting) a SitePinInst immediately (when deferRemovals is null)
     * or deferring its removal by putting it into the deferRemovals map.
     * @param spi SitePinInst object to be removed/unrouted.
     * @param deferRemovals Optional map for deferring the removal of SitePinInst objects, grouped by their
     *                      associated Net object.
     */
    public static void handlePinRemovals(SitePinInst spi, Map<Net,Set<SitePinInst>> deferRemovals) {
        if (deferRemovals != null) {
            assert(spi.getNet() != null);
            Set<SitePinInst> pins = deferRemovals.computeIfAbsent(spi.getNet(), p -> new HashSet<>());
            pins.add(spi);
        } else {
            final boolean preserveOtherRoutes = true;
            spi.getNet().removePin(spi, preserveOtherRoutes);
        }
    }

    /**
     * Given an unroute site wire path, find the site pin name that would drive the given BELPin.
     * @param pin The BELpin to search from
     * @return Name of the site pin that would drive the given BELPin or null if it could not be
     * determined.
     */
    public static String getSitePinSource(BELPin pin) {
        String currSitePinName = pin.getConnectedSitePinName();
        outer: while (currSitePinName == null) {
            boolean changedPin = false;
            for (BELPin p : pin.getSiteConns()) {
                if (p.getBEL().getBELClass() == BELClass.RBEL) {
                    for (SitePIP pip : p.getSitePIPs()) {
                        pin = p.equals(pip.getInputPin()) ? pip.getOutputPin() : pip.getInputPin();
                        changedPin = true;
                        String isSitePin = pin.getConnectedSitePinName();
                        if (isSitePin == null) continue;
                        break outer;
                    }
                }
            }
            if (!changedPin) {
                return null;
            }
        }
        currSitePinName = pin.getConnectedSitePinName();
        return currSitePinName;
    }

    /**
     * Remove a batch of site pins from nets for efficiency purposes.  Used in conjunction with
     * {@link #fullyRemoveCell(Design, Cell, Map)}.
     * @param deferredRemovals Mapping between nets and the site pins to be removed
     * @param preserveOtherRoutes Flag indicating if when pins are removed, if other routes on the
     * net should be preserved.
     */
    public static void batchRemoveSitePins(Map<Net, Set<SitePinInst>> deferredRemovals,
                                            boolean preserveOtherRoutes) {
        for (Entry<Net, Set<SitePinInst>> e : deferredRemovals.entrySet()) {
            Net net = e.getKey();
            SitePinInst srcPin = net.getSource();
            Set<SitePinInst> removals = e.getValue();
            if (preserveOtherRoutes) {
                DesignTools.unroutePins(net, removals);
            } else {
                net.unroute();
            }
            List<SitePinInst> pins = new ArrayList<>();
            for (SitePinInst pin : net.getPins()) {
                if (removals.contains(pin)) {
                    if (pin.isOutPin() && pin.equals(srcPin)) {
                        net.setSource(null);
                    }
                    assert(pin.getNet() == net);
                    pin.setNet(null);
                    pin.detachSiteInst();
                    continue;
                }
                pins.add(pin);
            }
            net.setPins(pins);
        }
    }

    /**
     * Turns the cell named hierarchicalCellName into a blackbox and removes any
     * associated placement and routing information associated with that instance. In Vivado,
     * this can be accomplished by running: (1) {@code update_design -cells <name> -black_box} or (2)
     * by deleting all of the cells and nets insides of a cell instance.  Method (2) is
     * more likely to have complications.
     * @param d The current design
     * @param hierarchicalCellName The name of the hierarchical cell to become a black box.
     */
    public static void makeBlackBox(Design d, String hierarchicalCellName) {
        final EDIFHierCellInst inst = d.getNetlist().getHierCellInstFromName(hierarchicalCellName);
        if (inst == null) {
            throw new IllegalStateException("Did not find cell to make into a blackbox: "+hierarchicalCellName);
        }
        makeBlackBox(d, inst);
    }

    /**
     * Unroutes the site routing connected to the provided cell's logical pin.
     * Preserves other parts of the net if used by other sinks in the site if an
     * input. For the unrouting to be successful, this method depends on the site
     * routing to be consistent.
     * 
     * @param cell           The cell of the pin
     * @param logicalPinName The logical pin name source or sink to have routing
     *                       removed.
     * @returns A list of site pins (if any) that should also be removed from inter-site routing to complete the unroute.
     */
    public static List<SitePinInst> unrouteCellPinSiteRouting(Cell cell, String logicalPinName) {
        String physPinName = cell.getPhysicalPinMapping(logicalPinName);
        if (physPinName == null) {
            physPinName = cell.getDefaultPinMapping(logicalPinName);
        }
        if (physPinName == null) {
            // Assume not routed
            return Collections.emptyList();
        }
        BELPin belPin = cell.getBEL().getPin(physPinName);
        SiteInst siteInst = cell.getSiteInst();
        Net net = siteInst.getNetFromSiteWire(belPin.getSiteWireName());
        if (net == null)
            return Collections.emptyList();

        List<String> sitePinNames = new ArrayList<>();
        List<BELPin> internalTerminals = new ArrayList<>();
        List<BELPin> internalSinks = new ArrayList<>();
        Set<BELPin> visited = new HashSet<>();
        Queue<BELPin> queue = new LinkedList<>();
        queue.add(belPin);

        while (!queue.isEmpty()) {
            BELPin currPin = queue.poll();
            visited.add(currPin);
            BELPin unrouteSegment = null;
            for (BELPin pin : siteInst.getSiteWirePins(currPin.getSiteWireIndex())) {
                if (currPin == pin || visited.contains(pin)) {
                    visited.add(pin);
                    continue;
                }
                // Check if it is a site pin, cell pin, sitepip or routethru
                switch (pin.getBEL().getBELClass()) {
                    case PORT: {
                        // We found a site pin, add it to solution set
                        sitePinNames.add(pin.getName());
                        break;
                    }
                    case BEL: {
                        // Check if this is another cell being driven by the net, or a route thru
                        Cell otherCell = siteInst.getCell(pin.getBEL());
                        if (otherCell != null) {
                            if (otherCell.isRoutethru()) {
                                BELPin otherPin = null;
                                if (pin.isOutput()) {
                                    assert(otherCell.getPinMappingsP2L().size() == 1);
                                    String otherPinName = otherCell.getPinMappingsP2L().keySet().iterator().next();
                                    otherPin = pin.getBEL().getPin(otherPinName);
                                } else {
                                    // Make sure we are coming in on the routed-thru pin
                                    String otherPinName = otherCell.getPinMappingsP2L().keySet().iterator().next();
                                    if (pin.getName().equals(otherPinName)) {
                                        otherPin = LUTTools.getLUTOutputPin(pin.getBEL());
                                    }
                                }
                                if (otherPin != null) {
                                    Net otherNet = siteInst.getNetFromSiteWire(otherPin.getSiteWireName());
                                    if (otherNet != null && net.getName().equals(otherNet.getName())) {
                                        queue.add(otherPin);
                                        // Check if the routethru pin is used by companion LUT
                                        if (otherPin.isInput()) {
                                            String otherBELName = LUTTools.getCompanionLUTName(otherPin.getBEL());
                                            Cell companionCell = siteInst.getCell(otherBELName);
                                            if (companionCell != null
                                                    && companionCell.getLogicalPinMapping(otherPin.getName()) != null) {
                                                // We need to remove the routethru if there are no other sinks
                                                // downstream
                                                if (internalSinks.size() == 0) {
                                                    siteInst.removeCell(otherCell.getBEL());
                                                    siteInst.unrouteIntraSiteNet(pin, pin);
                                                }
                                            }

                                        }
                                    } else {
                                        // site routing terminates here or is invalid
                                    }                                    
                                }
                                
                            } else if (otherCell != cell && otherCell.getLogicalPinMapping(pin.getName()) != null) {
                                // Don't search farther, we don't need to unroute anything else
                                if (pin.isInput() && belPin.isInput()) {
                                    internalSinks.add(pin);
                                } else {
                                    internalTerminals.add(pin);
                                }

                            }
                        }
                        break;
                    }
                    case RBEL: {
                        // We found a routing BEL, follow its sitepip
                        SitePIP sitePIP = siteInst.getUsedSitePIP(pin);
                        if (sitePIP != null) {
                            BELPin otherPin = pin.isInput() ? sitePIP.getOutputPin() : sitePIP.getInputPin();
                            Net otherNet = siteInst.getNetFromSiteWire(otherPin.getSiteWireName());
                            if (otherNet != null && net.getName().equals(otherNet.getName())) {
                                queue.add(otherPin);
                                unrouteSegment = otherPin;
                            } else {
                                // site routing terminates here or is invalid
                            }
                        }
                        break;
                    }
                }
                visited.add(pin);
            }
            if (unrouteSegment != null && unrouteSegment.isInput() && internalSinks.size() == 0) {
                // Unroute this branch of the sitePIP
                Net otherNet = siteInst.getNetFromSiteWire(unrouteSegment.getSiteWireName());
                siteInst.unrouteIntraSiteNet(unrouteSegment, belPin);
                siteInst.routeIntraSiteNet(otherNet, unrouteSegment, unrouteSegment);
            }
        }

        List<SitePinInst> sitePinsToRemove = new ArrayList<>();

        // This net is routed internally to the site
        for (BELPin internalTerminal : internalTerminals) {
            if (internalTerminal.isOutput() && internalSinks.size() > 0) {
                continue;
            }
            if (belPin.isOutput()) {
                siteInst.unrouteIntraSiteNet(belPin, internalTerminal);
            } else {
                siteInst.unrouteIntraSiteNet(internalTerminal, belPin);
            }
        }
        if (internalSinks.size() == 0) {
            for (String sitePinName : sitePinNames) {
                SitePinInst pin = siteInst.getSitePinInst(sitePinName);
                if (pin != null) {
                    sitePinsToRemove.add(pin);
                    if (belPin.isInput()) {
                        siteInst.unrouteIntraSiteNet(pin.getBELPin(), belPin);
                    } else {
                        siteInst.unrouteIntraSiteNet(belPin, pin.getBELPin());
                    }
                } else {
                    // Vivado leaves dual output *MUX partially routed, unroute the site for this MUX pin
                    // Could also be a cell with no loads
                    siteInst.unrouteIntraSiteNet(belPin, siteInst.getBELPin(sitePinName, sitePinName));
                }
            }
            if (internalTerminals.size() == 0 && sitePinNames.size() == 0) {
                // internal site route with no loads
                siteInst.unrouteIntraSiteNet(belPin, belPin);
            }
        }
        return sitePinsToRemove;
    }

    /**
     * Turns the cell named hierarchicalCell into a blackbox and removes any
     * associated placement and routing information associated with that instance.
     * In Vivado, this can be accomplished by running: (1)
     * {@code update_design -cells <name> -black_box} or (2) by deleting all of the
     * cells and nets insides of a cell instance. Method (2) is more likely to have
     * complications. This also unroutes both GND and VCC nets to avoid
     * implementation issues by Vivado in subsequent place and route runs.
     * 
     * @param d                The current design
     * @param hierarchicalCell The hierarchical cell to become a black box.
     */
    public static void makeBlackBox(Design d, EDIFHierCellInst hierarchicalCell) {
        CodePerfTracker t = CodePerfTracker.SILENT;// new CodePerfTracker("makeBlackBox", true);
        t.start("Init");
        EDIFCellInst futureBlackBox = hierarchicalCell.getInst();
        if (futureBlackBox == null)
            throw new RuntimeException(
                    "ERROR: Couldn't find cell " + hierarchicalCell + " in source design " + d.getName());

        if (hierarchicalCell.getCellType() == d.getTopEDIFCell()) {
            d.unplaceDesign();
            d.getTopEDIFCell().makePrimitive();
            d.getTopEDIFCell().addProperty(EDIFCellInst.BLACK_BOX_PROP, true);
            return;
        }

        Set<SiteInst> touched = new HashSet<>();
        Map<String, String> boundaryNets = new HashMap<>();

        Map<Net, Set<SitePinInst>> pinsToRemove = new HashMap<>();

        t.stop().start("Find border nets");
        // Find all the nets that connect to the cell (keep them)
        for (EDIFPortInst portInst : futureBlackBox.getPortInsts()) {
            EDIFNet net = portInst.getNet();
            EDIFHierCellInst hierParentName = hierarchicalCell.getParent();
            EDIFHierNet hierNetName = new EDIFHierNet(hierParentName, net);
            EDIFHierNet parentNetName = d.getNetlist().getParentNet(hierNetName);
            boundaryNets.put(parentNetName.getHierarchicalNetName(),
                    portInst.isOutput() ? hierNetName.getHierarchicalNetName() : null);

            // Remove parts of routed GND/VCC nets exiting the black box
            if (portInst.isInput())
                continue;
            NetType netType = NetType.getNetTypeFromNetName(parentNetName.getHierarchicalNetName());
            if (netType.isStaticNetType()) {
                // Black box is supplying VCC/GND, we must unroute connected tree
                EDIFHierNet hierNet = new EDIFHierNet(hierParentName, net);
                List<EDIFHierPortInst> sinks = d.getNetlist().getSinksFromNet(hierNet);
                // extract site wire and site pins and nodes to unroute
                for (EDIFHierPortInst sink : sinks) {
                    Cell c = d.getCell(sink.getFullHierarchicalInstName());
                    if (c == null || !c.isPlaced())
                        continue;
                    String logicalPinName = sink.getPortInst().getName();
                    // Remove all physical nets first
                    List<SitePinInst> removePins = unrouteCellPinSiteRouting(c, logicalPinName);
                    for (SitePinInst pin : removePins) {
                        pinsToRemove.computeIfAbsent(pin.getNet(), $ -> new HashSet<>()).add(pin);
                    }
                }
            }
        }

        t.stop().start("Remove p&r");

        List<EDIFHierCellInst> allLeafs = d.getNetlist().getAllLeafDescendants(hierarchicalCell);
        Set<Cell> cells = new HashSet<>();
        for (EDIFHierCellInst i : allLeafs) {
            // Get the physical cell, make sure we can unplace/unroute it first
            Cell c = d.getCell(i.getFullHierarchicalInstName());
            if (c == null) {
                continue;
            }
            cells.add(c);
        }

        // Remove all placement and routing information related to the cell to be
        // blackboxed
        for (Cell c : cells) {
            BEL bel = c.getBEL();
            SiteInst si = c.getSiteInst();

            // Check for VCC on A6 and remove if needed
            if (c.getBEL().isLUT() && c.getBELName().endsWith("5LUT")) {
                SitePinInst vcc = c.getSiteInst().getSitePinInst(c.getBELName().charAt(0) + "6");
                if (vcc != null && vcc.getNet().getName().equals(Net.VCC_NET)) {
                    boolean hasOtherSink = false;
                    for (BELPin otherSink : si.getSiteWirePins(vcc.getBELPin().getSiteWireIndex())) {
                        if (otherSink.isOutput())
                            continue;
                        Cell otherCell = si.getCell(otherSink.getBEL());
                        if (otherCell != null && otherCell.getLogicalPinMapping(otherSink.getName()) != null) {
                            hasOtherSink = true;
                            break;
                        }
                    }
                    if (!hasOtherSink) {
                        pinsToRemove.computeIfAbsent(vcc.getNet(), $ -> new HashSet<>()).add(vcc);
                    }
                }
            }

            // Remove all physical nets first
            for (String logPin : c.getPinMappingsP2L().values()) {
                List<SitePinInst> removePins = unrouteCellPinSiteRouting(c, logPin);
                for (SitePinInst pin : removePins) {
                    pinsToRemove.computeIfAbsent(pin.getNet(), $ -> new HashSet<>()).add(pin);
                }
            }
            touched.add(c.getSiteInst());

            c.unplace();
            d.removeCell(c.getName());
            si.removeCell(bel);
        }

        t.stop().start("cleanup t-prims");

        // Clean up any cells from Transformed Prims
        String keepPrefix = hierarchicalCell.getFullHierarchicalInstName() + EDIFTools.EDIF_HIER_SEP;
        for (SiteInst si : d.getSiteInsts()) {
            for (Cell c : si.getCells()) {
                if (c.getName().startsWith(keepPrefix)) {
                    touched.add(si);
                }
            }
        }

        t.stop().start("new net names");

        // Update black box output nets with new net names (those with sinks inside the
        // black box)
        Map<Net, String> netsToUpdate = new HashMap<>();
        for (Net n : d.getNets()) {
            String newName = boundaryNets.get(n.getName());
            if (newName != null) {
                netsToUpdate.put(n, newName);
            }
        }

        batchRemoveSitePins(pinsToRemove, true);

        // Rename nets if source was removed
        Set<String> netsToKeep = new HashSet<>();
        for (Entry<Net, String> e : netsToUpdate.entrySet()) {
            EDIFHierNet newSource = d.getNetlist().getHierNetFromName(e.getValue());
            Net net = e.getKey();
            if (!net.rename(e.getValue())) {
                throw new RuntimeException("ERROR: Failed to rename net '" + net.getName() + "'");
            }
            netsToKeep.add(net.getName());
        }

        t.stop().start("cleanup siteinsts");

        // Keep track of site instances to remove, but keep those supplying static
        // sources
        List<SiteInst> siteInstsToRemove = new ArrayList<>();
        for (SiteInst siteInst : touched) {
            if (siteInst.getCells().size() == 0) {
                siteInstsToRemove.add(siteInst);
            }
        }

        for (SiteInst siteInst : siteInstsToRemove) {
            d.removeSiteInst(siteInst);
        }

        // Remove any stray stubs on any remaining nets
        for (Net net : pinsToRemove.keySet()) {
            if (net.getFanOut() == 0 && net.hasPIPs()) {
                net.unroute();
            }
        }

        t.stop().start("create bbox");

        // Make EDIFCell blackbox
        EDIFCell blackBox = new EDIFCell(futureBlackBox.getCellType().getLibrary(),
                "black_box" + uniqueBlackBoxCount++);
        for (EDIFPort port : futureBlackBox.getCellType().getPorts()) {
            blackBox.addPort(port);
        }
        futureBlackBox.setCellType(blackBox);
        futureBlackBox.addProperty(EDIFCellInst.BLACK_BOX_PROP, true);

        unrouteGNDNetAndLUTSources(d);
        d.getVccNet().unroute();

        t.stop().printSummary();
    }

    /**
     * Helper method for makeBlackBox(). When cutting out nets that used to be
     * source'd from something inside a black box, the net names need to be updated.
     * 
     * @param d         The current design
     * @param currNet   Current net that requires a name change
     * @param newSource The source net (probably a pin on the black box)
     * @param newName   New name for the net
     * @return A reference to the newly updated/renamed net.
     */
    private static Net updateNetName(Design d, Net currNet, EDIFNet newSource, String newName) {
        List<PIP> pips = currNet.getPIPs();
        List<SitePinInst> pins = currNet.getPins();

        d.removeNet(currNet);

        Net newNet = d.createNet(newName);
        newNet.setPIPs(pips);
        for (SitePinInst pin : pins) {
            newNet.addPin(pin);
        }

        return newNet;
    }

    /**
     * Gets or creates the corresponding SiteInst from the prototype orig from a module.
     * @param design The current design from which to get the corresponding site instance.
     * @param orig The original site instance (from the module)
     * @param newAnchor The new anchor location of the module.
     * @param module The Module to use as the template.
     * @return The corresponding SiteInst from design if it exists,
     * or a newly created one in the translated location. If the new location
     * cannot be determined or is invalid, null is returned.
     */
    public static SiteInst getCorrespondingSiteInst(Design design, SiteInst orig, Site newAnchor, Module module) {
        Tile newTile = Module.getCorrespondingTile(orig.getTile(), newAnchor.getTile(), module.getAnchor().getTile());
        Site newSite = newTile.getSites()[orig.getSite().getSiteIndexInTile()];
        SiteInst newSiteInst = design.getSiteInstFromSite(newSite);
        if (newSiteInst == null) {
            newSiteInst = design.createSiteInst(newSite.getName(), orig.getSiteTypeEnum(), newSite);
        }
        return newSiteInst;
    }

    /**
     * Given a design with multiple identical cell instances, place
     * each of those instances using the stamp module template
     * at the anchored site locations provided in instPlacements.
     * @param design The top level design with identical multiple cell instances.
     * @param stamp The prototype stamp (or stencil) to use for replicated placement and routing.
     * This must match identically with the named instances in instPlacements
     * @param instPlacements Desired locations for placements
     * @return True if the procedure completed successfully, false otherwise.
     */
    public static boolean stampPlacement(Design design, Module stamp, Map<String,Site> instPlacements) {
        for (Entry<String,Site> e : instPlacements.entrySet()) {
            String instName = e.getKey();
            String prefix = instName + "/";
            Site newAnchor = e.getValue();
            Site anchor = stamp.getAnchor();

            // Create New Nets
            for (Net n : stamp.getNets()) {
                Net newNet = null;
                if (n.isStaticNet()) {
                    newNet = n.getName().equals(Net.GND_NET) ? design.getGndNet() : design.getVccNet();
                } else {
                    String newNetName = prefix + n.getName();
                    EDIFHierNet newEDIFNet = design.getNetlist().getHierNetFromName(newNetName);
                    newNet = design.createNet(newEDIFNet);
                }

                for (SitePinInst p : n.getPins()) {
                    SiteInst newSiteInst = getCorrespondingSiteInst(design, p.getSiteInst(), newAnchor, stamp);
                    if (newSiteInst == null)
                        return false;
                    SitePinInst newPin = new SitePinInst(p.isOutPin(), p.getName(), newSiteInst);
                    newNet.addPin(newPin);
                }

                for (PIP p : n.getPIPs()) {
                    Tile newTile = Module.getCorrespondingTile(p.getTile(), newAnchor.getTile(), anchor.getTile());
                    if (newTile == null) {
                        return false;
                    }
                    PIP newPIP = new PIP(newTile, p.getStartWireIndex(), p.getEndWireIndex());
                    newNet.addPIP(newPIP);
                }
            }

            // Create SiteInst & New Cells
            for (SiteInst si : stamp.getSiteInsts()) {
                SiteInst newSiteInst = getCorrespondingSiteInst(design, si, newAnchor, stamp);
                if (newSiteInst == null)
                    return false;
                for (Cell c : si.getCells()) {
                    String newCellName = prefix + c.getName();
                    EDIFHierCellInst cellInst = design.getNetlist().getHierCellInstFromName(newCellName);
                    if (cellInst == null && c.getEDIFCellInst() != null) {
                        System.out.println("WARNING: Stamped cell not found: " + newCellName);
                        continue;
                    }

                    Cell newCell = c.copyCell(newCellName, cellInst);
                    design.placeCell(newCell, newSiteInst.getSite(), c.getBEL(), c.getPinMappingsP2L());
                }

                for (SitePIP sitePIP : si.getUsedSitePIPs()) {
                    newSiteInst.addSitePIP(sitePIP);
                }

                for (Entry<String, Net> e2 : si.getSiteWireToNetMap().entrySet()) {
                    String siteWire = e2.getKey();
                    String netName = e2.getValue().getName();
                    Net newNet = null;
                    if (e2.getValue().isStaticNet()) {
                        newNet = netName.equals(Net.GND_NET) ? design.getGndNet() : design.getVccNet();
                    } else if (netName.equals(Net.USED_NET)) {
                        newNet = design.getNet(Net.USED_NET);
                        if (newNet == null) {
                            newNet = new Net(Net.USED_NET);
                        }
                    } else {
                        newNet = design.getNet(prefix + netName);
                    }

                    BELPin[] belPins = newSiteInst.getSite().getBELPins(siteWire);
                    newSiteInst.routeIntraSiteNet(newNet, belPins[0], belPins[0]);
                }
            }
        }
        return true;
    }

    /**
     * Looks in the site instance for BEL pins connected to this site pin.
     * @param pin The BELPin to examine for connected BEL pins.
     * @param si The SiteInst to examine for connected cells.
     * @param action Perform this action on each connected BELPin.
     */
    private static void foreachConnectedBELPin(BELPin pin, SiteInst si, Consumer<BELPin> action) {
        if (si == null) {
            return;
        }
        for (BELPin p : pin.getSiteConns()) {
            if (p.getBEL().getBELClass() == BELClass.RBEL) {
                SitePIP pip = si.getUsedSitePIP(p.getBELName());
                if (pip == null) continue;
                if (p.isOutput()) {
                    p = pip.getInputPin().getSiteConns().get(0);
                    action.accept(p);
                } else {
                    for (BELPin snk : pip.getOutputPin().getSiteConns()) {
                        action.accept(snk);
                    }
                }
            } else {
                Cell c = si.getCell(p.getBELName());
                if (c != null && c.getLogicalPinMapping(p.getName()) != null) {
                    action.accept(p);
                }
            }
        }
    }

    /**
     * Looks in the site instance for cells connected to this BEL pin and SiteInst.
     * @param pin The BELPin to examine for connected cells.
     * @param si The SiteInst to examine for connected cells.
     * @return Set of connected cells to this pin.
     */
    public static Set<Cell> getConnectedCells(BELPin pin, SiteInst si) {
        final Set<Cell> cells = new HashSet<>();
        foreachConnectedBELPin(pin, si, (p) -> {
            Cell c = si.getCell(p.getBELName());
            if (c != null) {
                cells.add(c);
            }
        });
        return cells;
    }

    /**
     * Looks in the site instance for cells connected to this site pin.
     * @param pin The SitePinInst to examine for connected cells.
     * @return Set of connected cells to this pin.
     */
    public static Set<Cell> getConnectedCells(SitePinInst pin) {
        return getConnectedCells(pin.getBELPin(), pin.getSiteInst());
    }

    /**
     * Looks in the site instance for BEL pins connected to this BEL pin and SiteInst.
     * @param pin The SitePinInst to examine for connected BEL pins.
     * @param si The SiteInst to examine for connected cells.
     * @return Set of BEL pins to this site pin.
     */
    public static Set<BELPin> getConnectedBELPins(BELPin pin, SiteInst si) {
        final Set<BELPin> pins = new HashSet<>();
        foreachConnectedBELPin(pin, si, pins::add);
        return pins;
    }

    /**
     * Looks in the site instance for BEL pins connected to this site pin.
     * @param pin The SitePinInst to examine for connected BEL pins.
     * @return Set of BEL pins to this site pin.
     */
    public static Set<BELPin> getConnectedBELPins(SitePinInst pin) {
        return getConnectedBELPins(pin.getBELPin(), pin.getSiteInst());
    }

    /**
     * Quick and dumb placement of a cell.  Does not attempt
     * any optimization and will not change the placement
     * of other cells.  Currently it will only place a cell in an empty site.
     * If the cell is already placed, it will leave it as is.
     * TODO - implement basic optimizations
     * @param c The cell to place
     * @return True if the cell is successfully placed, false otherwise.
     */
    public static boolean placeCell(Cell c, Design design) {
        if (c.isPlaced()) {
            // Don't move cell if already placed
            return true;
        }
        Map<SiteTypeEnum, Set<String>> compatTypes = c.getCompatiblePlacements(design.getDevice());

        for (Entry<SiteTypeEnum, Set<String>> e : compatTypes.entrySet()) {
            for (Site s : design.getDevice().getAllSitesOfType(e.getKey())) {
                SiteInst i = design.getSiteInstFromSite(s);
                if (i == null) {
                    for (String bel : e.getValue()) {
                        boolean success = design.placeCell(c, s, s.getBEL(bel));
                        if (success) return true;
                    }
                }
            }
        }
        return false;
    }


    /**
     * Creates any and all missing SitePinInsts for this net.  This is common as a placed
     * DCP will not have SitePinInsts annotated and this information is generally necessary
     * for routing to take place.
     * @param design The current design of this net.
     * @return The list of pins that were created or an empty list if none were created.
     */
    public static List<SitePinInst> createMissingSitePinInsts(Design design, Net net) {
        EDIFNetlist n = design.getNetlist();
        List<EDIFHierPortInst> physPins = n.getPhysicalPins(net);
        if (physPins == null) {
            // Perhaps net is not a parent net name
            final EDIFHierNet hierNet = n.getHierNetFromName(net.getName());
            if (hierNet != null) {
                final EDIFHierNet parentHierNet = n.getParentNet(hierNet);
                if (!hierNet.equals(parentHierNet)) {
                    physPins = n.getPhysicalPins(parentHierNet);
                    if (physPins != null) {
                        System.out.println("WARNING: Physical net '" + net.getName() +
                                "' is not the parent net but is treated as such." );
                    }
                }
            }
        }
        List<SitePinInst> newPins = new ArrayList<>();
        if (physPins == null) {
            // Likely net inside encrypted IP, let's see if we can infer anything from existing
            // physical description
            for (SiteInst siteInst : new ArrayList<>(net.getSiteInsts())) {
                for (String siteWire : new ArrayList<>(siteInst.getSiteWiresFromNet(net))) {
                    for (BELPin pin : siteInst.getSiteWirePins(siteWire)) {
                        if (!pin.isSitePort()) {
                            continue;
                        }

                        SitePinInst currPin = siteInst.getSitePinInst(pin.getName());
                        if (currPin != null) {
                            // SitePinInst already exists
                            continue;
                        }

                        if (pin.isInput()) {
                            // Input BELPin means output site port; check that this site port is driven
                            // by a cell, rather than coming from an input site port
                            boolean foundOutputPin = false;
                            for (BELPin connectedBELPin : getConnectedBELPins(pin, siteInst)) {
                                if (connectedBELPin.isInput()) {
                                    continue;
                                }
                                foundOutputPin = true;
                                break;
                            }
                            if (!foundOutputPin) {
                                continue;
                            }
                        }

                        currPin = net.createPin(pin.getName(), siteInst);
                        newPins.add(currPin);
                    }
                }
            }

            return newPins;
        }

        EDIFNetlist netlist = design.getNetlist();
        EDIFHierNet parentEhn = null;
        for (EDIFHierPortInst p :  physPins) {
            Cell c = design.getCell(p.getFullHierarchicalInstName());
            if (c == null) continue;
            BEL bel = c.getBEL();
            if (bel == null) continue;
            String logicalPinName = p.getPortInst().getName();
            Set<String> physPinMappings = c.getAllPhysicalPinMappings(logicalPinName);
            // BRAMs can have two (or more) physical pin mappings for a logical pin
            if (physPinMappings != null) {
                SiteInst si = c.getSiteInst();
                for (String physPin : physPinMappings) {
                    BELPin belPin = bel.getPin(physPin);
                    // Use the net attached to the phys pin
                    Net siteWireNet = si.getNetFromSiteWire(belPin.getSiteWireName());
                    if (siteWireNet == null) {
                        continue;
                    }
                    if (siteWireNet != net && !siteWireNet.isStaticNet()) {
                        if (parentEhn == null) {
                            parentEhn = netlist.getParentNet(net.getLogicalHierNet());
                        }
                        EDIFHierNet parentSiteWireEhn = netlist.getParentNet(siteWireNet.getLogicalHierNet());
                        if (!parentSiteWireEhn.equals(parentEhn)) {
                            // Site wire net is not an alias of the net
                            throw new RuntimeException("ERROR: Net on " + si.getSiteName() + "/" + belPin +
                                    "'" + siteWireNet.getName() + "' is not an alias of " +
                                    "'" + net.getName() + "'");
                        }
                    }
                    String sitePinName = getRoutedSitePinFromPhysicalPin(c, siteWireNet, physPin);
                    if (sitePinName == null) continue;
                    SitePinInst newPin = si.getSitePinInst(sitePinName);
                    if (newPin != null) continue;
                    if (sitePinName.equals("IO") && Utils.isIOB(si)) {
                        // Do not create a SitePinInst for the "IO" input site pin of any IOB site,
                        // since the sitewire it drives is assumed to be driven by the IO PAD.
                        continue;
                    }
                    newPin = net.createPin(sitePinName, si);
                    if (newPin != null) newPins.add(newPin);
                }
            }
        }
        return newPins;
    }

    /**
     * Gets the first site pin that is currently routed to the specified cell pin.  If
     * the site instance is not routed, it will return null.
     * @param cell The cell with the pin of interest.
     * @param net The physical net to which this pin belongs
     * @param logicalPinName The logical pin name of the cell to query.
     * @return The name of the first site pin on the cell's site to which the pin is routed.
     */
    public static String getRoutedSitePin(Cell cell, Net net, String logicalPinName) {
        String belPinName = cell.getPhysicalPinMapping(logicalPinName);
        return getRoutedSitePinFromPhysicalPin(cell, net, belPinName);
    }

    /**
     * Gets the first site pin that is currently routed to the specified cell pin.  If
     * the site instance is not routed, it will return null.
     * @param cell The cell with the pin of interest.
     * @param net The physical net to which this pin belongs
     * @param belPinName The physical pin name of the cell
     * @return The name of the first site pin on the cell's site to which the pin is routed.
     */
    public static String getRoutedSitePinFromPhysicalPin(Cell cell, Net net, String belPinName) {
        List<String> sitePins = getAllRoutedSitePinsFromPhysicalPin(cell, net, belPinName);
        return (!sitePins.isEmpty()) ? sitePins.get(0) : null;
    }

    /**
     * Gets all site pins that are currently routed to the specified cell pin.  If
     * the site instance is not routed, it will return null.
     * @param cell The cell with the pin of interest.
     * @param net The physical net to which this pin belongs
     * @param belPinName The physical pin name of the cell
     * @return A list of site pin names on the cell's site to which the pin is routed.
     * @since 2023.1.2
     */
    public static List<String> getAllRoutedSitePinsFromPhysicalPin(Cell cell, Net net, String belPinName) {
        SiteInst inst = cell.getSiteInst();
        if (belPinName == null) return Collections.emptyList();
        List<String> sitePins = new ArrayList<>();
        Set<String> siteWires = new HashSet<>(inst.getSiteWiresFromNet(net));
        Queue<BELPin> queue = new LinkedList<>();
        queue.add(cell.getBEL().getPin(belPinName));
        while (!queue.isEmpty()) {
            BELPin curr = queue.remove();
            String siteWireName = curr.getSiteWireName();
            if (!siteWires.contains(siteWireName)) {
                // Allow dedicated paths to pass without site routing
                if (siteWireName.equals("CIN") || siteWireName.equals("COUT")) {
                    return Collections.singletonList(siteWireName);
                }
                return Collections.emptyList();
            }
            if (curr.isInput()) {
                BELPin source = curr.getSourcePin();
                if (source == null) return Collections.emptyList();
                if (source.isSitePort()) {
                    return Collections.singletonList(source.getName());
                } else if (source.getBEL().getBELClass() == BELClass.RBEL) {
                    SitePIP sitePIP = inst.getUsedSitePIP(source.getBELName());
                    if (sitePIP == null) continue;
                    queue.add(sitePIP.getInputPin());
                } else if (source.getBEL().isLUT() || source.getBEL().getBELType().endsWith("MUX")) {
                    Cell possibleRouteThru = inst.getCell(source.getBEL());
                    if (possibleRouteThru != null && possibleRouteThru.isRoutethru()) {
                        String routeThru = possibleRouteThru.getPinMappingsP2L().keySet().iterator().next();
                        queue.add(source.getBEL().getPin(routeThru));
                    }
                }
            } else { // output
                for (BELPin sink : curr.getSiteConns()) {
                    if (!siteWires.contains(sink.getSiteWireName())) continue;
                    if (sink.isSitePort()) {
                        sitePins.add(sink.getName());
                    } else if (sink.getBEL().getBELClass() == BELClass.RBEL) {
                        // Check if the SitePIP is being used
                        SitePIP sitePIP = inst.getUsedSitePIP(sink.getBELName());
                        if (sitePIP == null) continue;
                        // Don't proceed if it's configured for a different pin
                        if (!sitePIP.getInputPinName().equals(sink.getName())) continue;
                        // Make this the new source to search from and keep looking...
                        queue.add(sitePIP.getOutputPin());
                    } else if (sink.getBEL().isFF()) {
                        // FF pass thru option (not a site PIP)
                        siteWireName = sink.getBEL().getPin("Q").getSiteWireName();
                        if (siteWires.contains(siteWireName)) {
                            sitePins.add(siteWireName);
                        }
                    }
                }
            }
        }
        return sitePins;
    }

    /**
     * Creates all missing SitePinInsts in a design, except GLOBAL_USEDNET.
     * See also {@link #createMissingSitePinInsts(Design, Net)}.
     * @param design The current design
     */
    public static void createMissingSitePinInsts(Design design) {
        EDIFNetlist netlist = design.getNetlist();
        for (Net net : design.getNets()) {
            if (net.isUsedNet()) {
                continue;
            }
            EDIFHierNet ehn = net.getLogicalHierNet();
            EDIFHierNet parentEhn = (ehn != null) ? netlist.getParentNet(ehn) : null;
            if (parentEhn != null && !parentEhn.equals(ehn)) {
                Net parentNet = design.getNet(parentEhn.getHierarchicalNetName());
                if (parentNet != null) {
                    // 'net' is not a parent net (which normally causes createMissingSitePinInsts(Design, Net)
                    // to analyze its parent net) but that parent net also exist in the design and has been/
                    // will be analyzed in due course, so skip doing so here
                    continue;
                }
            }
            createMissingSitePinInsts(design,net);
        }
    }

    private static HashSet<String> muxPins;

    static {
        muxPins = new HashSet<String>();
        for (char c = 'A' ; c <= 'H' ; c++) {
            muxPins.add(c + "MUX");
        }
    }

    /**
     * In Series7 and UltraScale architectures, there are dual output site pin scenarios where an
     * optional additional output can be used to drive out of the SLICE using the OUTMUX routing
     * BEL.  When unrouting a design, some site routing can be left "dangling".  This method will
     * remove those unnecessary sitePIPs and site routing for the *MUX output.  It will also remove
     * the output source pin if it is the *MUX output.
     * @param design The design from which to remove the unnecessary site routing
     */
    public static void unrouteDualOutputSitePinRouting(Design design) {
        boolean isSeries7 = design.getDevice().getSeries() == Series.Series7;
        for (SiteInst siteInst : design.getSiteInsts()) {
            if (Utils.isSLICE(siteInst)) {
                ArrayList<String> toRemove = null;
                for (Entry<String, Net> e : siteInst.getSiteWireToNetMap().entrySet()) {
                    if (muxPins.contains(e.getKey())) {
                        // MUX output is used, is the same net also driving the direct output?
                        String directPin = e.getKey().charAt(0) + (isSeries7 ? "" : "_O");
                        Net net = siteInst.getNetFromSiteWire(directPin);
                        if (e.getValue().equals(net)) {
                            if (toRemove == null) {
                                toRemove = new ArrayList<String>();
                            }
                            toRemove.add(e.getKey());
                        }
                    }
                }
                if (toRemove == null) continue;
                for (String name : toRemove) {
                    Net net = siteInst.getNetFromSiteWire(name);
                    BELPin belPin = siteInst.getBEL(name).getPin(name);
                    BELPin muxOutput = belPin.getSourcePin();
                    SitePIP sitePIP = siteInst.getUsedSitePIP(muxOutput.getBELName());
                    BELPin srcPin = sitePIP.getInputPin().getSourcePin();
                    boolean success = siteInst.unrouteIntraSiteNet(srcPin, belPin);
                    if (!success) throw new RuntimeException("ERROR: Failed to unroute dual output "
                            + "net/pin scenario: " + net + " on pin " + name);
                    siteInst.routeIntraSiteNet(net, srcPin, srcPin);
                    if (net.getSource() != null && net.getSource().getName().equals(belPin.getName())) {
                        net.removePin(net.getSource());
                    }
                }
            }
        }
    }

    /**
     * Finds a legal/available alternative output site pin for the given net.  The most common case
     * is the SLICE.  It depends on the existing output pin of the net to be routed within the site
     * and checks if site routing resource are available to use the alternative output site pin.
     * @param net The net of interest.
     * @return A new potential site pin inst that could be added/routed to.
     */
    public static SitePinInst getLegalAlternativeOutputPin(Net net) {
        SitePinInst alt = net.getAlternateSource();
        if (alt != null) return alt;
        SitePinInst src = net.getSource();
        if (src == null) return null;
        SiteInst siteInst = src.getSiteInst();
        // Currently only support SLICE scenarios
        if (!Utils.isSLICE(siteInst)) return null;

        // Series 7: AMUX <-> A, BMUX <-> B, CMUX <-> C, DMUX <-> D
        // UltraScale/+: AMUX <-> A_O, BMUX <-> B_O, ... HMUX <-> H_O
        Queue<BELPin> q = new LinkedList<>();
        BELPin srcPin = src.getBELPin();

        // Find the logical source
        BELPin logicalSource = getLogicalBELPinDriver(src);
        if (logicalSource == null) return null;
        q.add(logicalSource);

        // Fan out from logical source to all site pins
        BELPin alternateExit = null;
        while (!q.isEmpty()) {
            BELPin currOutPin = q.poll();
            Net currNet = siteInst.getNetFromSiteWire(currOutPin.getSiteWireName());
            // Skip any resources used by another net
            if (currNet != null && !currNet.equals(net)) continue;
            for (BELPin pin : currOutPin.getSiteConns()) {
                if (pin.getBEL().getBELClass() == BELClass.RBEL) {
                    SitePIP pip = src.getSiteInst().getSitePIP(pin);
                    q.add(pip.getOutputPin());
                } else if (pin.isSitePort() && !pin.equals(srcPin)) {
                    Net currNet2 = siteInst.getNetFromSiteWire(pin.getSiteWireName());
                    if (currNet2 == null || currNet2.equals(net)) {
                        alternateExit = pin;
                    }
                }
            }
        }
        if (alternateExit != null) {
            // Create the pin in such a way as it is not put in the SiteInst map
            SitePinInst sitePinInst = new SitePinInst();
            sitePinInst.setSiteInst(src.getSiteInst());
            sitePinInst.setPinName(alternateExit.getName());
            sitePinInst.setIsOutputPin(true);
            return sitePinInst;
        }
        return null;
    }

    /**
     * Looks backwards from a SitePinInst output pin and finds the corresponding BELPin of the
     * driver.
     * @param sitePinInst The output site pin instance from which to find the logical driver.
     * @return The logical driver's BELPin of the provided sitePinInst.
     */
    public static BELPin getLogicalBELPinDriver(SitePinInst sitePinInst) {
        if (!sitePinInst.isOutPin()) return null;
        SiteInst siteInst = sitePinInst.getSiteInst();
        for (BELPin pin : sitePinInst.getBELPin().getSiteConns()) {
            if (pin.isInput()) continue;
            if (pin.getBEL().getBELClass() == BELClass.RBEL) {
                SitePIP p = siteInst.getUsedSitePIP(pin.getBELName());
                if (p == null) continue;
                for (BELPin pin2 : p.getInputPin().getSiteConns()) {
                    if (pin2.isOutput()) {
                        return pin2;
                    }
                }
            }
            return pin;
        }
        // Looks like the approach above failed (site may not be routed), try logical path
        Net net = sitePinInst.getNet();
        if (net == null) return null;
        Design design = siteInst.getDesign();
        EDIFNetlist netlist = design.getNetlist();
        EDIFHierNet hierNet = netlist.getHierNetFromName(net.getName());
        if (hierNet == null) return null;
        List<EDIFPortInst> portInsts = hierNet.getNet().getSourcePortInsts(false);
        for (EDIFPortInst portInst : portInsts) {
            Cell c = design.getCell(hierNet.getHierarchicalInstName(portInst));
            if (c != null) {
                return c.getBELPin(portInst);
            }
        }
        return null;
    }

    /**
     * Routes (within the site) the alternate site output pin for SLICE dual-output scenarios.
     * @param net The current net of interest to be routed
     * @param sitePinInst The alternate site output pin to be routed
     * @return True if the routing was successful, false otherwise
     */
    public static boolean routeAlternativeOutputSitePin(Net net, SitePinInst sitePinInst) {
        if (sitePinInst == null) return false;
        net.setAlternateSource(sitePinInst);
        sitePinInst.setNet(net);

        BELPin driver = getLogicalBELPinDriver(net.getSource());
        BELPin belPinPort = sitePinInst.getBELPin();

        return sitePinInst.getSiteInst().routeIntraSiteNet(net, driver, belPinPort);
    }

    /**
     * Un-routes (within the site) the alternate source pin on the provided net.  This is in
     * reference to dual-output site pin scenarios for SLICEs.
     * @param net The relevant net that has the populated alternative site pin.
     * @return True if the alternate source was unrouted successfully, false otherwise.
     */
    public static boolean unrouteAlternativeOutputSitePin(Net net) {
        SitePinInst altPin = net.getAlternateSource();
        if (altPin == null) return false;
        SiteInst siteInst = altPin.getSiteInst();

        BELPin driver = getLogicalBELPinDriver(net.getSource());
        BELPin belPinPort = altPin.getBELPin();

        boolean result = siteInst.unrouteIntraSiteNet(driver, belPinPort);
        // Re-route the driver site wire
        siteInst.routeIntraSiteNet(net, driver, driver);

        return result;
    }
    /**
     * Given a SitePinInst, this method will find any return hierarchical logical cell pins within
     * the site directly connected to the site pin.
     * @param sitePin The site pin to query.
     * @return A list of hierarchical port instances that connect to the site pin.
     */
    public static List<EDIFHierPortInst> getPortInstsFromSitePinInst(SitePinInst sitePin) {
        SiteInst siteInst = sitePin.getSiteInst();
        BELPin[] belPins = siteInst.getSiteWirePins(sitePin.getName());
        List<EDIFHierPortInst> portInsts = new ArrayList<>();
        Queue<BELPin> queue = new LinkedList<>();
        queue.addAll(Arrays.asList(belPins));
        while (!queue.isEmpty()) {
            BELPin belPin = queue.remove();
            if (belPin.isOutput() == sitePin.isOutPin()) {
                BEL bel = belPin.getBEL();
                if (bel.getBELClass() == BELClass.RBEL) {
                    // Routing BEL, lets look ahead/behind it
                    SitePIP sitePIP = siteInst.getUsedSitePIP(belPin);
                    if (sitePIP != null) {
                        BELPin otherPin = belPin.isOutput() ? sitePIP.getInputPin() : sitePIP.getOutputPin();
                        for (BELPin belPin2 : otherPin.getSiteConns()) {
                            if (belPin2.equals(otherPin)) continue;
                            EDIFHierPortInst portInst = getPortInstFromBELPin(siteInst, belPin2);
                            if (portInst != null) portInsts.add(portInst);
                        }
                    }
                } else {
                    Cell lut = bel.isLUT() ? siteInst.getCell(bel) : null;
                    if (lut != null && lut.isRoutethru() && lut.getLogicalPinMapping(belPin.getName()) != null) {
                        BELPin opin = bel.getPin("O" + bel.getName().charAt(1));
                        belPins = siteInst.getSiteWirePins(opin.getSiteWireName());
                        queue.addAll(Arrays.asList(belPins));
                    } else {
                        EDIFHierPortInst portInst = getPortInstFromBELPin(siteInst, belPin);
                        if (portInst != null) portInsts.add(portInst);
                    }
                }
            }
        }
        return portInsts;
    }

    private static EDIFHierPortInst getPortInstFromBELPin(SiteInst siteInst, BELPin belPin) {
        Cell targetCell = siteInst.getCell(belPin.getBEL());
        if (targetCell == null) {
            // Is it routing through a FF? (Series 7 / UltraScale)
            if (belPin.getName().equals("Q")) {
                Net net = siteInst.getNetFromSiteWire(belPin.getSiteWireName());
                BELPin d = belPin.getBEL().getPin("D");
                Net otherNet = siteInst.getNetFromSiteWire(d.getSiteWireName());
                if (net == otherNet) {
                    BELPin muxOut = d.getSourcePin();
                    SitePIP pip = siteInst.getUsedSitePIP(muxOut);
                    if (pip != null) {
                        BELPin src = pip.getInputPin().getSourcePin();
                        return getPortInstFromBELPin(siteInst, src);
                    }
                }
            }

            return null;
        }
        String logPinName = targetCell.getLogicalPinMapping(belPin.getName());
        if (logPinName == null) return null;
        EDIFCellInst eci = targetCell.getEDIFCellInst();
        if (eci == null) {
            return null;
        }
        EDIFPortInst portInst = eci.getPortInst(logPinName);
        final EDIFNetlist netlist = targetCell.getSiteInst().getDesign().getNetlist();
        EDIFHierPortInst hierPortInst =
                new EDIFHierPortInst(netlist.getHierCellInstFromName(targetCell.getParentHierarchicalInstName()), portInst);
        return hierPortInst;
    }

    /**
     * Creates a map that contains pin names for keys that map to the Unisim Verilog parameter that
     * can invert a pins value.
     * @param series The series of interest.
     * @param unisim The unisim of interest.
     * @return A map of invertible pins that are that are mapped to their respective parameter name
     * that controls inversion.
     */
    public static Map<String,String> getInvertiblePinMap(Series series, Unisim unisim) {
        Map<String,String> invertPinMap = new HashMap<String, String>();
        for (Entry<String, VivadoProp> e : Design.getDefaultCellProperties(series, unisim.name()).entrySet()) {
            String propName = e.getKey();
            if (propName.startsWith("IS_") && propName.endsWith("_INVERTED")) {
                String pinName = propName.substring(propName.indexOf('_')+1, propName.lastIndexOf('_'));
                invertPinMap.put(pinName, propName);
            }
        }
        return invertPinMap;
    }

    /**
     * Copies the logic and implementation of a set of cells from one design to another.  This will
     * replace the destination logical cell instances with those of the source design.
     * @param src The source design (with partial or full implementation)
     * @param dest The destination design (with matching cell instance interfaces).
     * @param lockPlacement Flag indicating if the destination implementation copy should have the
     *     placement locked
     * @param lockRouting Flag indicating if the destination implementation copy should have the
     *     routing locked
     * @param srcToDestInstNames A map of source (key) to destination (value) pairs of cell
     * instances from which to copy the implementation. If targeting the top instance, use an
     * empty String ("") as the destination instance name.
     */
    public static void copyImplementation(Design src, Design dest, boolean lockPlacement,
                                          boolean lockRouting, Map<String,String> srcToDestInstNames) {
        copyImplementation(src, dest, false, false, lockPlacement, lockRouting, srcToDestInstNames);
    }

    /**
     * Copies the logic and implementation of a set of cells from one design to another with additional flags to control copying nets.
     * @param src The source design (with partial or full implementation)
     * @param dest The destination design (with matching cell instance interfaces)
     * @param copyStaticNets Flag indicating if static nets should be copied
     * @param copyOnlyInternalNets Flag indicating if only nets with every terminal inside the cell should be copied
     * @param lockPlacement Flag indicating if the destination implementation copy should have the
     *     placement locked
     * @param lockRouting Flag indicating if the destination implementation copy should have the
     *     routing locked
     * @param srcToDestInstNames A map of source (key) to destination (value) pairs of cell
     * instances from which to copy the implementation
     */
    public static void copyImplementation(Design src, Design dest, boolean copyStaticNets, boolean copyOnlyInternalNets, boolean lockPlacement,
            boolean lockRouting, Map<String,String> srcToDestInstNames) {
        // Removing existing logic in target cells in destination design
        EDIFNetlist destNetlist = dest.getNetlist();
        for (Entry<String,String> e : srcToDestInstNames.entrySet()) {
            DesignTools.makeBlackBox(dest, e.getValue());
        }
        destNetlist.removeUnusedCellsFromAllWorkLibraries();

        // Populate black boxes with existing logical netlist cells
        HashSet<String> instsWithSeparator = new HashSet<>();
        for (Entry<String,String> e : srcToDestInstNames.entrySet()) {
            EDIFHierCellInst cellInst = e.getKey().length()==0 ? src.getNetlist().getTopHierCellInst()
                    : src.getNetlist().getHierCellInstFromName(e.getKey());
            if (e.getValue().length() == 0) {
                // If its the top cell, remove the top cell from destNetlist
                EDIFLibrary destLib = destNetlist.getLibrary(cellInst.getCellType().getLibrary().getName());
                if (destLib == null) {
                    destLib = destNetlist.getWorkLibrary();
                }
                EDIFCell existingCell = destLib.getCell(cellInst.getCellType().getName());
                if (existingCell != null) {
                    destLib.removeCell(existingCell);
                }
            }
            destNetlist.copyCellAndSubCells(cellInst.getCellType());
            EDIFHierCellInst bbInst = destNetlist.getHierCellInstFromName(e.getValue());
            EDIFCell destCell = destNetlist.getCell(cellInst.getCellType().getName());
            if (destNetlist.getTopCell() == bbInst.getCellType()) {
                destNetlist.getDesign().setTopCell(destCell);
            }
            bbInst.getInst().setCellType(destCell);
            instsWithSeparator.add(e.getKey() + EDIFTools.EDIF_HIER_SEP);
        }
        destNetlist.resetParentNetMap();

        Map<String,String> prefixes = new HashMap<>();
        for (String srcPrefix : srcToDestInstNames.keySet()) {
            if (srcPrefix.length()==0) {
                prefixes.put(srcPrefix, srcPrefix);
            } else {
                    prefixes.put(srcPrefix + "/", srcPrefix);
            }
        }

        // Identify cells to copy placement
        Set<SiteInst> siteInstsOfCells = new HashSet<>();
        for (Cell cell : src.getCells()) {
            String cellName = cell.getName();

            String prefixMatch = null;
            if ((prefixMatch = StringTools.startsWithAny(cellName, prefixes.keySet())) != null) {
                SiteInst dstSiteInst = dest.getSiteInstFromSite(cell.getSite());
                SiteInst srcSiteInst = cell.getSiteInst();
                siteInstsOfCells.add(srcSiteInst);
                if (dstSiteInst == null) {
                    dstSiteInst = dest.createSiteInst(srcSiteInst.getName(),
                                    srcSiteInst.getSiteTypeEnum(), srcSiteInst.getSite());
                }
                String newCellName = getNewHierName(cellName, srcToDestInstNames, prefixes, prefixMatch);
                Cell copy = cell.copyCell(newCellName, cell.getEDIFHierCellInst(), dstSiteInst);
                dstSiteInst.addCell(copy);
                copy.setBELFixed(lockPlacement);
                copy.setSiteFixed(lockPlacement);

                // Preserve site routing from cell pins to site pins
                copySiteRouting(copy, cell, srcToDestInstNames, prefixes);
            }
        }

        List<Net> staticNets = new ArrayList();

        // Identify nets to copy routing
        for (Net net : src.getNets()) {
            if (net.isStaticNet()) {
                staticNets.add(net);
                continue;
            }

            List<EDIFHierPortInst> pins = src.getNetlist().getPhysicalPins(net);
            if (pins == null) continue;
            // Identify the kinds of routes to preserve:
            //  - Has the source in the preservation zone
            //  - Has at least one sink inside preservation zone
            boolean srcInside = false;
            List<EDIFHierPortInst> outside = new ArrayList<EDIFHierPortInst>();
            for (EDIFHierPortInst portInst : pins) {
                String portInstName = portInst.getFullHierarchicalInstName();
                String prefixMatch = StringTools.startsWithAny(portInstName, prefixes.keySet());
                if (portInst.isOutput() && prefixMatch != null) {
                    srcInside = true;
                }
                if (prefixMatch == null) {
                    outside.add(portInst);
                }
            }
            // Don't keep routing if source is not in preservation zone
            if (!srcInside) continue;
            if (copyOnlyInternalNets && outside.size() > 0) {
                continue;
            }
            if ((outside.size() + 1) >= pins.size()) continue;

            Set<SitePinInst> pinsToRemove = new HashSet<>();
            // Net is partially inside, preserve only portions inside
            for (EDIFHierPortInst removeMe : outside) {
                pinsToRemove.addAll(removeMe.getAllRoutedSitePinInsts(src));
            }
            Set<PIP> pipsToRemove = getTrimmablePIPsFromPins(net, pinsToRemove);

            String newNetName = net.getName();
            String prefixMatch = null;
            if ((prefixMatch = StringTools.startsWithAny(net.getName(), prefixes.keySet())) != null) {
                newNetName = getNewHierName(newNetName, srcToDestInstNames, prefixes, prefixMatch);
            }
            Net copiedNet = dest.createNet(newNetName);
            for (PIP p : net.getPIPs()) {
                if (pipsToRemove.contains(p)) continue;
                copiedNet.addPIP(p);
                if (lockRouting) {
                    p.setIsPIPFixed(true);
                }
            }
            for (SitePinInst spi : net.getPins()) {
                if (pinsToRemove.contains(spi)) continue;
                SiteInst siteInst = dest.getSiteInstFromSite(spi.getSite());
                if (siteInst == null) {
                    dest.createSiteInst(spi.getSite());
                }
                copiedNet.createPin(spi.getName(), siteInst);
            }
        }

        if (copyStaticNets) {
            copyStaticNets(dest, staticNets, siteInstsOfCells);
        }
    }

    /**
     * Copy the route of static nets feeding the sinks within the given SiteInst.
     * The route of static nets connecting to every site pin of the given site instances will be copied.
     * @param dest The destination design
     * @param staticNets The list of static nets to copy
     * @param siteInstsOfCells The set of SiteInst containing the sinks of the static nets
     */
    private static void copyStaticNets(Design dest, List<Net> staticNets, Set<SiteInst> siteInstsOfCells) {
        // This method traces a route similar to that in getTrimmablePIPsFromPins. However, there is one subtle difference.
        // Let's consider a route from one GND source (S) to two sinks (T1 and T2) and the last common node/pip is X.
        // If only T1 is in a cell to be copied, the tracing code here will extract all pips from S to T1.
        // However, getTrimmablePIPsFromPins return only the pips from X to T1.

        // Map from a node to its driver PIP
        // Note: Some PIPs are bidirectional. But, every PIP allows the signal to flow in only one direction.
        // The direction of a bidirectional PIP is determined from the context, ie., its connecting directional PIPs.
        // To determine that context, go through directional PIPs first. This process does not support consecutive bidirectional PIPs.
        Map<Net,Map<Node,PIP>> netToUphillPIPMap = new HashMap<>();
        // netToPIPs to store PIPs extracted for static nets.
        Map<Net,Set<PIP>> netToPIPs = new HashMap<>();
        for (Net net : staticNets) {
            netToPIPs.put(net, new HashSet<>());
            Map<Node,PIP> nodeToDriverPIP = new HashMap<>();
            List<PIP> biPIPs = new ArrayList<>();
            for (PIP pip : net.getPIPs()) {
                if (pip.isBidirectional()) {
                    biPIPs.add(pip);
                } else {
                    nodeToDriverPIP.put(pip.getEndNode(), pip);
                }
            }
            for (PIP pip : biPIPs) {
                Node stNode = pip.getStartNode();
                if (nodeToDriverPIP.containsKey(stNode)) {
                    nodeToDriverPIP.put(pip.getEndNode(), pip);
                } else {
                    nodeToDriverPIP.put(stNode, pip);
                }
            }
            netToUphillPIPMap.put(net, nodeToDriverPIP);
        }

        Set<String> prohibitBels = new HashSet<>();

        for (SiteInst siteInst : siteInstsOfCells) {
            // Go through the set of pins being used on this site instance.
            for (SitePinInst sitePinInst : siteInst.getSitePinInsts()) {
                if (sitePinInst.isOutPin())
                    continue;

                Net net = sitePinInst.getNet();
                Map<Node,PIP> nodeToDriverPIP = netToUphillPIPMap.get(net);
                if (nodeToDriverPIP != null) {
                    Set<PIP> allPIPs = netToPIPs.get(net);
                    // This SitePinInst connects to a static net. Trace and collect all the PIPs to a source.
                    Node node = sitePinInst.getConnectedNode();
                    SitePin sitePin = node.getSitePin();
                    // Backtrack through routing nodes (no SitePin)
                    while ((sitePin == null) || sitePin.isInput()) {
                        PIP pip = nodeToDriverPIP.get(node);
                        allPIPs.add(pip);
                        if (pip.isBidirectional()) {
                            node = pip.getStartNode().equals(node) ? pip.getEndNode() : pip.getStartNode();
                        } else {
                            node = pip.getStartNode();
                        }
                        if (node.getWireName().contains(Net.VCC_WIRE_NAME))
                            break;

                        sitePin = node.getSitePin();
                    }
                    if ((sitePin != null) && !node.getWireName().contains(Net.VCC_WIRE_NAME))  { // GND source
                        String  pinName = sitePin.getPinName();
                        String  siteName = sitePin.getSite().getName();
                        List<String> bels = sitePin2Bels.get(pinName);
                        for (String bel : bels) {
                            prohibitBels.add(siteName + "/" + bel);
                        }
                    }
                    netToPIPs.put(net,allPIPs);
                }
            }
        }

        // When we copy the static nets, we must preserve their sources so that nothing should be placed on it.
        for (String bel : prohibitBels) {
            dest.addXDCConstraint(ConstraintGroup.LATE, "set_property PROHIBIT true [get_bels " + bel + "]");
        }


        for (Map.Entry<Net, Set<PIP>> entry : netToPIPs.entrySet()) {
            if ((entry == null) || (entry.getKey() == null) || (entry.getValue() == null))
                continue;

            Net net = dest.getStaticNet(entry.getKey().getType());
            for (PIP p : entry.getValue()) {
                net.addPIP(p);
            }
        }
    }

    private static String getNewHierName(String srcName, Map<String,String> srcToDestInstNames,
                                            Map<String,String> prefixes, String prefixMatch) {
        String newCellPrefix = srcToDestInstNames.get(prefixes.get(prefixMatch));
        int idx = prefixMatch.length() - (newCellPrefix.length() == 0 ? 0 : 1);
        if (idx == -1) {
            return newCellPrefix + "/" + srcName;
        }
        return newCellPrefix + srcName.substring(idx);
    }

    /**
     * Copies the logic and implementation of a set of cells from one design to another.
     * @param src The source design (with partial or full implementation)
     * @param dest The destination design (with matching cell instance interfaces)
     * @param instNames Names of the cell instances to copy
     */
    public static void copyImplementation(Design src, Design dest, String... instNames) {
        copyImplementation(src, dest, false, false, instNames);
    }

    /**
     * Copies the logic and implementation of a set of cells from one design to another.
     * @param src The source design (with partial or full implementation)
     * @param dest The destination design (with matching cell instance interfaces)
     * @param lockPlacement Flag indicating if the destination implementation copy should have the
     *     placement locked
     * @param lockRouting Flag indicating if the destination implementation copy should have the
     *     routing locked
     * @param instNames Names of the cell instances to copy
     */
    public static void copyImplementation(Design src, Design dest, boolean lockPlacement,
            boolean lockRouting, String... instNames) {
        Map<String,String> map = new HashMap<>();
        for (String instName : instNames) {
            map.put(instName, instName);
        }
        copyImplementation(src, dest, lockPlacement, lockRouting, map);
    }

    /**
     * Copies the site routing for all nets connected to the copied cell based on the original
     * cell.  This method will use destination netlist net names to be consistent with source-named
     * net names as used in Vivado.
     * @param copy The destination cell context to receive the site routing
     * @param orig The original cell with the blueprint of site routing that should be copied
     * @param srcToDestNames Map of source to destination cell instance name prefixes that should be
     * copied.
     * @param prefixes Map of prefixes with '/' at the end (keys) that map to the same String
     * without the '/'
     */
    private static void copySiteRouting(Cell copy, Cell orig, Map<String,String> srcToDestNames,
            Map<String,String> prefixes) {
        Design dest = copy.getSiteInst().getDesign();
        EDIFNetlist destNetlist = dest.getNetlist();
        SiteInst dstSiteInst = copy.getSiteInst();
        SiteInst origSiteInst = orig.getSiteInst();
        // Ensure A6 has VCC if driven in original (dual LUT usage scenarios)
        if (orig.getBELName().contains("LUT")) {
            BEL lut6 = origSiteInst.getBEL(orig.getBELName().replace("5", "6"));
            BELPin a6 = lut6.getPin("A6");
            Net net = origSiteInst.getNetFromSiteWire(a6.getSiteWireName());
            if (net != null && net.getName().equals(Net.VCC_NET)) {
                dstSiteInst.routeIntraSiteNet(dest.getVccNet(), a6, a6);
            }
        }

        EDIFHierCellInst cellInst = destNetlist.getHierCellInstFromName(copy.getName());
        for (Entry<String,String> e : copy.getPinMappingsP2L().entrySet()) {
            EDIFPortInst portInst = cellInst.getInst().getPortInst(e.getValue());
            if (portInst == null) continue;
            EDIFNet edifNet = portInst.getNet();

            String netName = new EDIFHierNet(cellInst.getParent(), edifNet).getHierarchicalNetName();

            String siteWireName = orig.getSiteWireNameFromPhysicalPin(e.getKey());
            Net origNet = origSiteInst.getNetFromSiteWire(siteWireName);
            if (origNet == null) continue;
            Net net = null;
            if (origNet.isStaticNet()) {
                net = origNet;
            } else {
                String parentNetName = destNetlist.getParentNetName(netName);
                if (parentNetName == null) {
                    parentNetName = netName;
                }
                net = dest.getNet(parentNetName);
                if (net == null) {
                    net = dest.createNet(parentNetName);
                }
            }

            BELPin curr = copy.getBEL().getPin(e.getKey());
            dstSiteInst.routeIntraSiteNet(net, curr, curr);
            boolean routingForward = curr.isOutput();
            Queue<BELPin> q = new LinkedList<BELPin>();
            q.add(curr);
            while (!q.isEmpty()) {
                curr = q.poll();
                if (routingForward) {
                    for (BELPin pin : curr.getSiteConns()) {
                        if (pin == curr) continue;
                        SitePIP sitePIP = origSiteInst.getUsedSitePIP(pin);
                        if (sitePIP != null) {
                            String currSiteWireName = sitePIP.getOutputPin().getSiteWireName();
                            Net test = origSiteInst.getNetFromSiteWire(currSiteWireName);
                            if (origNet.equals(test)) {
                                dstSiteInst.addSitePIP(sitePIP);
                                curr = sitePIP.getOutputPin();
                                dstSiteInst.routeIntraSiteNet(net, curr, curr);
                                q.add(curr);
                            }
                        }
                    }
                } else {
                    curr = curr.getSourcePin();
                    if (curr.isSitePort()) continue;
                    String belName = curr.getBELName();
                    Cell tmpCell = origSiteInst.getCell(belName);
                    if (tmpCell != null) {
                        if (tmpCell.isRoutethru()) {
                            String cellName = tmpCell.getName();
                            String prefixMatch = StringTools.startsWithAny(cellName, prefixes.keySet());
                            if (prefixMatch == null) {
                                throw new RuntimeException("ERROR: Unable to find appropriate "
                                    + "translation name for cell: " + tmpCell);
                            }
                            String newCellName = getNewHierName(cellName, srcToDestNames, prefixes, prefixMatch);
                            Cell rtCopy = tmpCell
                                    .copyCell(newCellName, tmpCell.getEDIFHierCellInst(), dstSiteInst);
                            dstSiteInst.getCellMap().put(belName, rtCopy);
                            for (String belPinName : rtCopy.getPinMappingsP2L().keySet()) {
                                BELPin tmp = rtCopy.getBEL().getPin(belPinName);
                                if (tmp.isInput()) {
                                    curr = tmp;
                                    break;
                                }
                            }
                            if (rtCopy.getBELName().endsWith("6LUT") && isUltraScale(rtCopy)) {
                                // Check A6 if it has VCC assignment
                                BELPin a6 = rtCopy.getBEL().getPin("A6");
                                Net isVcc = origSiteInst.getNetFromSiteWire(a6.getSiteWireName());
                                if (isVcc != null && isVcc.getName().equals(Net.VCC_NET)) {
                                    dstSiteInst.routeIntraSiteNet(
                                            dstSiteInst.getDesign().getVccNet(), a6, a6);
                                }
                            }
                        } else {
                            // We found the source
                            break;
                        }
                    } else if (net.isStaticNet() && (belName.contains("LUT") ||
                            curr.getBEL().isStaticSource())) {
                        // LUT used as a static source
                        dstSiteInst.routeIntraSiteNet(net, curr, curr);
                        break;
                    } else {
                        SitePIP sitePIP = origSiteInst.getUsedSitePIP(curr);
                        if (sitePIP != null) {
                            dstSiteInst.addSitePIP(sitePIP);
                            curr = sitePIP.getInputPin();
                        } else {
                            continue;
                        }
                    }
                    dstSiteInst.routeIntraSiteNet(net, curr, curr);
                    q.add(curr);
                }
            }
        }
    }

    private static boolean isUltraScale(Cell cell) {
        SiteInst si = cell.getSiteInst();
        if (si == null) return false;
        Series s = si.getDesign().getDevice().getSeries();
        return s == Series.UltraScale || s == Series.UltraScalePlus;
    }

    public static void printSiteInstInfo(SiteInst siteInst, PrintStream ps) {
        ps.println("=====================================================================");
        ps.println(siteInst.getSiteName() + " :");
        for (BEL bel : siteInst.getSite().getBELs()) {
            Cell cell = siteInst.getCell(bel);
            ps.println("  BEL: " + bel.getName() + " : " + cell);
            for (BELPin pin : bel.getPins()) {
                String isPinFixed = cell != null && cell.isPinFixed(pin.getName()) ? " [*]" : "";
                String logPin = (cell != null ? cell.getLogicalPinMapping(pin.getName()) : "null");
                ps.println("    Pin: " + pin.getName() + " : " + logPin + isPinFixed);
            }
        }

        for (String siteWireName : siteInst.getSite().getSiteWireNames()) {
            ps.println("  SiteWire: " + siteWireName + " " + siteInst.getNetFromSiteWire(siteWireName));
        }

        for (SitePIP pip : siteInst.getSite().getSitePIPs()) {
            ps.println("  SitePIP: " + pip + " : " + siteInst.getSitePIPStatus(pip));
        }
    }

    /**
     * Make all of a Design's physical Net objects consistent with its logical (EDIF) netlist.
     * Specifically, merge all sitewire and SitePinInst-s associated with physical Net-s that
     * are not the parent/canonical logical net into the parent Net, and delete all
     * non-parent Net-s.
     * @param design Design object to be modified in-place.
     */
    public static void makePhysNetNamesConsistent(Design design) {
        Map<EDIFHierNet, EDIFHierNet> netParentMap = design.getNetlist().getParentNetMap();
        EDIFNetlist netlist = design.getNetlist();
        for (Net net : new ArrayList<>(design.getNets())) {
            Net parentPhysNet = null;
            if (net.isStaticNet()) {
                if (net.getType() == NetType.GND) {
                    parentPhysNet = design.getGndNet();
                } else if (net.getType() == NetType.VCC) {
                    parentPhysNet = design.getVccNet();
                } else {
                    throw new RuntimeException();
                }
                if (parentPhysNet == net) {
                    continue;
                }
            } else {
                EDIFHierNet hierNet = netlist.getHierNetFromName(net.getName());
                if (hierNet == null) {
                    // Likely an encrypted cell
                    continue;
                }
                EDIFHierNet parentHierNet = netParentMap.get(hierNet);
                if (parentHierNet == null) {
                    // System.out.println("WARNING: Couldn't find parent net for '" +
                    //         hierNet.getHierarchicalNetName() + "'");
                    continue;
                }

                if (!hierNet.equals(parentHierNet)) {
                    String parentNetName = parentHierNet.getNet().getName();
                    // Assume that a net named <const1> or <const0> is always a VCC or GND net
                    if (parentNetName.equals(EDIFTools.LOGICAL_VCC_NET_NAME)) {
                        parentPhysNet = design.getVccNet();
                    } else if (parentNetName.equals(EDIFTools.LOGICAL_GND_NET_NAME)) {
                        parentPhysNet = design.getGndNet();
                    } else {
                        parentPhysNet = design.getNet(parentHierNet.getHierarchicalNetName());
                    }

                    if (parentPhysNet != null) {
                        // Fall through
                    } else if (net.rename(parentHierNet.getHierarchicalNetName())) {
                        // Fall through
                    } else {
                        System.out.println("WARNING: Failed to adjust physical net name " + net.getName());
                    }
                }
            }

            if (parentPhysNet != null) {
                design.movePinsToNewNetDeleteOldNet(net, parentPhysNet, true);
            }
        }
    }

    public static void createPossiblePinsToStaticNets(Design design) {
        if (design.getSeries() == Series.Versal) {
            // TODO
        } else {
            createA1A6ToStaticNets(design);
            createCeClkOfRoutethruFFToVCC(design);
        }
        createCeSrRstPinsToVCC(design);
    }

    public static void createCeClkOfRoutethruFFToVCC(Design design) {
        Net vcc = design.getVccNet();
        Net gnd = design.getGndNet();
        for (SiteInst si : design.getSiteInsts()) {
            if (!Utils.isSLICE(si)) {
                continue;
            }
            for (Cell cell : si.getCells()) {
                if (!cell.isFFRoutethruCell()) {
                    continue;
                }

                BEL bel = cell.getBEL();
                if (bel == null) {
                    continue;
                }

                // Need VCC at CE
                BELPin ceInput = bel.getPin("CE");
                String ceInputSitePinName = ceInput.getConnectedSitePinName();
                SitePinInst ceSitePin = si.getSitePinInst(ceInputSitePinName);
                if (ceSitePin == null) {
                    ceSitePin = vcc.createPin(ceInputSitePinName, si);
                }
                si.routeIntraSiteNet(vcc, ceSitePin.getBELPin(), ceInput);
                // ...and GND at CLK
                BELPin clkInput = bel.getPin("CLK");
                BELPin clkInvOut = clkInput.getSourcePin();
                si.routeIntraSiteNet(gnd, clkInvOut, clkInput);
                BELPin clkInvIn = clkInvOut.getBEL().getPin(0);
                String clkInputSitePinName = clkInvIn.getConnectedSitePinName();
                SitePinInst clkInputSitePin = si.getSitePinInst(clkInputSitePinName);
                if (clkInputSitePin == null) {
                    clkInputSitePin = vcc.createPin(clkInputSitePinName, si);
                }
                si.routeIntraSiteNet(vcc, clkInputSitePin.getBELPin(), clkInvIn);
            }
        }
    }

    public static void createA1A6ToStaticNets(Design design) {
        for (SiteInst si : design.getSiteInsts()) {
            if (!Utils.isSLICE(si)) {
                continue;
            }
            for (Cell cell : si.getCells()) {
                BEL bel = cell.getBEL();
                if (bel == null || !bel.isLUT()) {
                    continue;
                }

                // SKIPPING <LOCKED> LUTs to resolve site pin conflicts between GND and VCC
                // Without skipping <LOCKED>, some A6 pins of SRL16E LUTs (5LUT and 6LUT used) will be handled twice in createMissingStaticSitePins().
                // In the second processing, those A6 pins are somehow added to VCC while they should stay in GND.
                if (cell.getName().equals(Cell.LOCKED)) {
                    continue;
                }

                if (bel.getName().endsWith("5LUT")) {
                    bel = si.getBEL(bel.getName().charAt(0) + "6LUT");
                }

                boolean isSRL = ("SRL16E".equals(cell.getType()) || "SRLC32E".equals(cell.getType()));
                for (String belPinName : lut6BELPins) {
                    if (!isSRL && belPinName.equals("A1")) {
                        continue;
                    }
                    BELPin belPin = bel.getPin(belPinName);
                    if (belPin != null) {
                        createMissingStaticSitePins(belPin, si, cell);
                    }
                }
            }
        }
    }

    /**
     * Create and add any missing SitePinInst-s belonging to the VCC net.
     * This is indicated by the sitewire corresponding to CE and SR pins of SLICE FFs,
     * or to the RST pins on RAMBs, having no associated net.
     * @param design Design object to be modified in-place.
     */
    public static void createCeSrRstPinsToVCC(Design design) {
        Series series = design.getDevice().getSeries();
        if (series == Series.Series7) {
            // Series7 have {CE,SR}USEDMUX which is used to supply VCC and GND respectively from
            // inside the site, so no inter-site routing necessary
            return;
        }
        Map<String, Pair<String, String>> pinMapping = belTypeSitePinNameMapping.get(series);
        Net vccNet = design.getVccNet();
        if (series == Series.Versal) {
            // In Versal, sitewires for a SLICE's CE pins are not assigned to the VCC net
            // Assume that the lack of sitewire for a placed FF indicates VCC
            for (SiteInst si : design.getSiteInsts()) {
                if (!Utils.isSLICE(si)) {
                    continue;
                }
                for (Cell cell : si.getCells()) {
                    BEL bel = cell.getBEL();
                    if (bel == null || !bel.isFF()) {
                        continue;
                    }

                    if (!bel.getBELType().equals("FF")) {
                        assert(bel.getBELType().matches("(SLICE_IMI|SLICE[LM]_IMC)_FF(_T)?"));
                        continue;
                    }

                    Pair<String, String> sitePinNames = pinMapping.get(bel.getName());
                    final String[] belPinNames = new String[] {"CE"}; // TODO: "SR"
                    for (String belPinName : belPinNames) {
                        String sitePinName = belPinName == belPinNames[0] ? sitePinNames.getFirst() : sitePinNames.getSecond();
                        if (si.getSitePinInst(sitePinName) != null) {
                            continue;
                        }

                        Net net = si.getNetFromSiteWire(sitePinName);
                        if (net != null) {
                            // It is possible for sitewire to be assigned to a non VCC net, but a SitePinInst to not yet exist
                            assert(!net.isVCCNet());
                            continue;
                        }
                        BELPin belPin = bel.getPin(belPinName);
                        assert(si.getNetFromSiteWire(belPin.getSiteWireName()) == null);

                        SitePinInst spi = new SitePinInst(false, sitePinName, si);
                        boolean updateSiteRouting = false;
                        vccNet.addPin(spi, updateSiteRouting);
                    }
                }
            }
        } else if (series == Series.UltraScale || series == Series.UltraScalePlus) {
            Net gndInvertibleToVcc = design.getGndNet();
            final String[] pins = new String[] {"CE", "SR"};
            for (Cell cell : design.getCells()) {
                if (isUnisimFlipFlopType(cell.getType())) {
                    SiteInst si = cell.getSiteInst();
                    if (!Utils.isSLICE(si)) {
                        continue;
                    }
                    BEL bel = cell.getBEL();
                    Pair<String, String> sitePinNames = pinMapping.get(bel.getBELType());
                    for (String pin : pins) {
                        BELPin belPin = cell.getBEL().getPin(pin);
                        Net net = si.getNetFromSiteWire(belPin.getSiteWireName());
                        if (net == null || (net == gndInvertibleToVcc && pin.equals("SR"))) {
                            String sitePinName;
                            if (pin.equals("CE")) { // CKEN
                                sitePinName = sitePinNames.getFirst();
                            } else { //SRST
                                sitePinName = sitePinNames.getSecond();
                            }
                            maybeCreateVccPinAndPossibleInversion(si, sitePinName, vccNet, gndInvertibleToVcc);
                        }
                    }
                } else if (cell.getType().equals("RAMB36E2") && cell.getAllPhysicalPinMappings("RSTREGB") == null) {
                    //cell.getEDIFCellInst().getProperty("DOB_REG")): integer(0)
                    SiteInst si = cell.getSiteInst();
                    String siteWire = cell.getSiteWireNameFromLogicalPin("RSTREGB");
                    Net net = si.getNetFromSiteWire(siteWire);
                    if (net == null) {
                        for (String pinName : Arrays.asList("RSTREGBU", "RSTREGBL")) {
                            maybeCreateVccPinAndPossibleInversion(si, pinName, vccNet, gndInvertibleToVcc);
                        }
                    }
                } else if (cell.getType().equals("RAMB18E2") && cell.getAllPhysicalPinMappings("RSTREGB") == null) {
                    SiteInst si = cell.getSiteInst();
                    // type RAMB180: L_O, type RAMB181: U_O
                    // TODO Type should be consistent with getPrimarySiteTypeEnum()?
                    // System.out.println(cell.getAllPhysicalPinMappings("RSTREGB") + ", " + si + ", " + cell.getSiteWireNameFromLogicalPin("RSTREGB") + ", " + si.getPrimarySiteTypeEnum());
                    // [RSTREGB], SiteInst(name="RAMB18_X5Y64", type="RAMB180", site="RAMB18_X5Y64"), OPTINV_RSTREGB_L_O, RAMBFIFO18
                    // [RSTREGB], SiteInst(name="RAMB18_X5Y31", type="RAMB181", site="RAMB18_X5Y31"), OPTINV_RSTREGB_U_O, RAMB181
                    // null, SiteInst(name="RAMB18_X6Y43", type="RAMB181", site="RAMB18_X6Y43"), null, RAMB181
                    // null, SiteInst(name="RAMB18_X5Y22", type="RAMB180", site="RAMB18_X5Y22"), null, RAMBFIFO18
                    // The following workaround solves the RAMB18 RSTREGB pin issue
                    String siteWire = cell.getBEL().getPin("RSTREGB").getSiteWireName();
                    Net net = si.getNetFromSiteWire(siteWire);
                    if (net == null) {
                        String pinName;
                        if (siteWire.endsWith("L_O")) {
                            pinName = "RSTREGBL";
                        } else {
                            pinName = "RSTREGBU";
                        }
                        maybeCreateVccPinAndPossibleInversion(si, pinName, vccNet, gndInvertibleToVcc);
                    }
                }
            }
        } else {
            throw new RuntimeException("ERROR: Unsupported series: " + series);
        }
    }
    
    private static void maybeCreateVccPinAndPossibleInversion(SiteInst si, String sitePinName, Net vcc, Net gndInvertibleToVcc) {
        SitePinInst sitePin = si.getSitePinInst(sitePinName);
        if (sitePin == null) {
            sitePin = vcc.createPin(sitePinName, si);
        }
        if (gndInvertibleToVcc != null) {
            // For the RST inversion to be interpreted properly by Vivado, there must be no
            // site routing on the path around the inverter BEL
            BELPin belPin = sitePin.getBELPin();
            si.unrouteIntraSiteNet(belPin, belPin);
        }
    }

    public static void createMissingStaticSitePins(BELPin belPin, SiteInst si, Cell cell) {
        // SiteWire and SitePin Name are the same for LUT inputs
        String siteWireName = belPin.getSiteWireName();
        // VCC returned based on the site wire, site pins are not stored in dcp
        Net netOnSiteWire = si.getNetFromSiteWire(siteWireName);
        Net net = (netOnSiteWire != null) ? netOnSiteWire : si.getDesign().getVccNet();
        if (net.isStaticNet()) {
            // SRL16Es that have been transformed from SRLC32E require GND on their A6 pin
            if (cell.getType().equals("SRL16E") && siteWireName.endsWith("6")) {
                EDIFPropertyValue val = cell.getProperty("XILINX_LEGACY_PRIM");
                if (val != null && val.getValue().equals("SRLC32E")) {
                    net = si.getDesign().getGndNet();
                }
            }
            String belName = belPin.getBELName();
            if (LUTTools.isCellALUT(cell) &&
                    // No net originally present on input sitewire
                    netOnSiteWire != net &&
                    // No cell placed in the 5LUT spot
                    si.getCell(belName.replace('6', '5')) == null &&
                    // No net present on output sitewire
                    si.getNetFromSiteWire(belName.charAt(0) + "5LUT_O5") == null) {
                // LUT input siteWire has no net attached, nor does the LUT output sitewire: no need for site pin
                return;
            }
            SitePinInst pin = si.getSitePinInst(siteWireName);
            if (pin == null) {
                net.createPin(siteWireName, si);
            } else if (!pin.getNet().equals(net)) {
                pin.getNet().removePin(pin);
                net.addPin(pin);
            }
        }
    }

    //NOTE: SRL16E (reference name SRL16E, EDIFCell in RW) uses A2-A5, so we need to connect A1 & A6 to VCC,
    //however, when SitePinInsts (e.g. A3) are already in GND, adding those again will cause problems to A1
    static String[] lut6BELPins = new String[] {"A1", "A6"};
    static HashSet<String> unisimFlipFlopTypes;
    static {
        unisimFlipFlopTypes = new HashSet<>();
        unisimFlipFlopTypes.add("FDSE");//S CE, logical cell
        unisimFlipFlopTypes.add("FDPE");//PRE CE
        unisimFlipFlopTypes.add("FDRE");//R and CE
        unisimFlipFlopTypes.add("FDCE");//CLR CE
    }

    private static boolean isUnisimFlipFlopType(String cellType) {
        return unisimFlipFlopTypes.contains(cellType);
    }

    /** Mapping from device Series to another mapping from FF BEL name to CKEN/SRST site pin name **/
    static public final Map<Series, Map<String, Pair<String, String>>> belTypeSitePinNameMapping;
    static{
        belTypeSitePinNameMapping = new EnumMap(Series.class);
        Pair<String,String> p;

        {
            Map<String, Pair<String, String>> ultraScalePlus = new HashMap<>();
            belTypeSitePinNameMapping.put(Series.UltraScalePlus, ultraScalePlus);

            p = new Pair<>("CKEN1", "SRST1");
            ultraScalePlus.put("AFF", p);
            ultraScalePlus.put("BFF", p);
            ultraScalePlus.put("CFF", p);
            ultraScalePlus.put("DFF", p);
            p = new Pair<>("CKEN2", "SRST1");
            ultraScalePlus.put("AFF2", p);
            ultraScalePlus.put("BFF2", p);
            ultraScalePlus.put("CFF2", p);
            ultraScalePlus.put("DFF2", p);

            p = new Pair<>("CKEN3", "SRST2");
            ultraScalePlus.put("EFF", p);
            ultraScalePlus.put("FFF", p);
            ultraScalePlus.put("GFF", p);
            ultraScalePlus.put("HFF", p);
            p = new Pair<>("CKEN4", "SRST2");
            ultraScalePlus.put("EFF2", p);
            ultraScalePlus.put("FFF2", p);
            ultraScalePlus.put("GFF2", p);
            ultraScalePlus.put("HFF2", p);
        }
        {
            Map<String, Pair<String, String>> ultraScale = new HashMap<>();
            belTypeSitePinNameMapping.put(Series.UltraScale, ultraScale);

            p = new Pair<>("CKEN_B1", "SRST_B1");
            ultraScale.put("AFF", p);
            ultraScale.put("BFF", p);
            ultraScale.put("CFF", p);
            ultraScale.put("DFF", p);
            p = new Pair<>("CKEN_B2", "SRST_B1");
            ultraScale.put("AFF2", p);
            ultraScale.put("BFF2", p);
            ultraScale.put("CFF2", p);
            ultraScale.put("DFF2", p);

            p = new Pair<>("CKEN_B3", "SRST_B2");
            ultraScale.put("EFF", p);
            ultraScale.put("FFF", p);
            ultraScale.put("GFF", p);
            ultraScale.put("HFF", p);
            p = new Pair<>("CKEN_B4", "SRST_B2");
            ultraScale.put("EFF2", p);
            ultraScale.put("FFF2", p);
            ultraScale.put("GFF2", p);
            ultraScale.put("HFF2", p);
        }
        {
            Map<String, Pair<String, String>> series7 = new HashMap<>();
            belTypeSitePinNameMapping.put(Series.Series7, series7);

            p = new Pair<>("CE", "SR");
            series7.put("AFF",  p);
            series7.put("A5FF", p);
            series7.put("BFF",  p);
            series7.put("B5FF", p);
            series7.put("CFF",  p);
            series7.put("C5FF", p);
            series7.put("DFF",  p);
            series7.put("D5FF", p);
        }
        {
            Map<String, Pair<String, String>> versal = new HashMap<>();
            belTypeSitePinNameMapping.put(Series.Versal, versal);

            p = new Pair<>("CKEN1", "RST");
            versal.put("AFF",  p);
            versal.put("AFF2", p);
            versal.put("BFF",  p);
            versal.put("BFF2", p);
            p = new Pair<>("CKEN2", "RST");
            versal.put("CFF",  p);
            versal.put("CFF2", p);
            versal.put("DFF",  p);
            versal.put("DFF2", p);

            p = new Pair<>("CKEN3", "RST");
            versal.put("EFF",  p);
            versal.put("EFF2", p);
            versal.put("FFF",  p);
            versal.put("FFF2", p);
            p = new Pair<>("CKEN4", "RST");
            versal.put("GFF",  p);
            versal.put("GFF2", p);
            versal.put("HFF",  p);
            versal.put("HFF2", p);
        }
    }

    /**
     * Finds the essential PIPs which connect the provided sink pin to its source.
     * @param sinkPin A sink pin from a routed net.
     * @return The list of PIPs that for the routing connection from the sink to the source.
     */
    public static List<PIP> getConnectionPIPs(SitePinInst sinkPin) {
        if (sinkPin.isOutPin() || sinkPin.getNet() == null) return Collections.emptyList();
        Map<Node, PIP> reverseNodeToPIPMap = new HashMap<>();
        List<PIP> biDirs = null;
        for (PIP p : sinkPin.getNet().getPIPs()) {
            PIP collision = reverseNodeToPIPMap.put(p.getEndNode(), p);
            if (collision != null) {
                if (p.isBidirectional()) {
                    // Put back the original if trying to add a bi-directional PIP
                    reverseNodeToPIPMap.put(collision.getEndNode(), collision);
                }
            }
            if (p.isBidirectional()) {
                if (biDirs == null) {
                    biDirs = new ArrayList<>(1);
                }
                biDirs.add(p);
            }
        }

        Node sinkNode = sinkPin.getConnectedNode();
        Node srcNode = sinkPin.getNet().getSource().getConnectedNode();
        Node curr = sinkNode;

        List<PIP> path = new ArrayList<>();
        loop: while (!curr.equals(srcNode)) {
            PIP pip = reverseNodeToPIPMap.get(curr);
            if (pip == null) {
                for (PIP biDirPIP : biDirs) {
                    if (biDirPIP.getStartNode().equals(curr)) {
                        path.add(biDirPIP);
                        curr = biDirPIP.getEndNode();
                        continue loop;
                    }
                }
            }
            path.add(pip);
            curr = pip.getStartNode();
        }

        return path;
    }

    /**
     * Create a Job running Vivado to create a readable version of
     * the EDIF inside the checkpoint to a separate file.
     * @param checkpoint the input checkpoint
     * @param edif the output EDIF
     * @return the created Job
     */
    public static Job generateReadableEDIFJob(Path checkpoint, Path edif) {
        try {

            final Job job = new LocalJob();
            job.setCommand(FileTools.getVivadoPath() + " -mode batch -source readable.tcl");

            final Path runDir = Files.createTempDirectory(edif.toAbsolutePath().getParent(),edif.getFileName()+"_readable_edif_");
            job.setRunDir(runDir.toString());

            Files.write(runDir.resolve("readable.tcl"), Arrays.asList(
                    "open_checkpoint " + checkpoint.toAbsolutePath(),
                    "write_edif " + edif.toAbsolutePath()
            ));


            return job;
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Gets the corresponding EDIF directory created when auto generating EDIF from Vivado.
     * @param dcpFile The source DCP file path
     * @return The directory where auto generated .edf and .edn files go.
     */
    public static Path getDefaultReadableEDIFDir(Path dcpFile) {
        return Paths.get(dcpFile.toString() + ".edf");
    }

    /**
     * Gets the corresponding MD5 file path for a DCP that has had its EDIF file auto-generated
     * @param dcpFile The Path to the DCP source
     * @param edfDir The directory containing the auto-generated edf and md5 file
     * @return The path to the MD5 file
     */
    public static Path getDCPAutoGenMD5FilePath(Path dcpFile, Path edfDir) {
        return edfDir.resolve(dcpFile.getFileName().toString() + ".md5");
    }

    /**
     * Gets the path to the auto-generated EDIF file for the provided DCP and EDIF directory.
     * @param dcpFile The Path to the DCP source
     * @param edfDir The directory where the auto-generated EDIF is stored
     * @return The path to the auto-generated EDIF file
     */
    public static Path getEDFAutoGenFilePath(Path dcpFile, Path edfDir) {
        return edfDir.resolve(FileTools.replaceExtension(dcpFile.getFileName(), ".edf"));
    }

    /**
     * Use Vivado to create a readable version of the EDIF file inside a design checkpoint. If no
     * edf file name is provided (edfFileName=null), it will manage over-written DCPs with an md5
     * hash so that edf can stay in sync with a DCP.
     * @param dcp the checkpoint
     * @param edfFileName filename to use or null if we should select a filename
     * @return the readable output edif filename
     */
    public static Path generateReadableEDIF(Path dcp, Path edfFileName) {
        String currMD5 = null;
        Path existingMD5File = null;
        if (edfFileName == null) {
            // We'll manage the creation and location of the edf file, DCP will be checked with
            // an MD5 hash so that if it changes, we re-update the edf file
            currMD5 = Installer.calculateMD5OfFile(dcp);
            Path edfDir = getDefaultReadableEDIFDir(dcp);
            existingMD5File = getDCPAutoGenMD5FilePath(dcp, edfDir);
            edfFileName = getEDFAutoGenFilePath(dcp, edfDir);
            if (!Files.exists(edfDir)) {
                FileTools.makeDirs(edfDir.toString());
            }

            // Check if a previously auto-generated edf is still usable
            String existingMD5 = FileTools.getStoredMD5FromFile(existingMD5File);
            if (Files.exists(edfFileName)) {
                if (currMD5.equals(existingMD5)) {
                    return edfFileName;
                } else {
                    try {
                        Files.delete(edfFileName);
                    } catch (IOException e) {
                        throw new RuntimeException("ERROR: Couldn't auto-generate updated edf file"
                                + " as the file appears to be in use or no permission to do so.");
                    }
                }
            }

        }
        JobQueue queue = new JobQueue();
        Job job = generateReadableEDIFJob(dcp, edfFileName);
        queue.addJob(job);
        if (!queue.runAllToCompletion()) {
            throw new RuntimeException("Generating Readable EDIF job failed");
        }
        FileTools.deleteFolder(job.getRunDir());
        if (currMD5 != null && existingMD5File != null) {
            FileTools.writeStringToTextFile(currMD5, existingMD5File.toString());
        }
        return edfFileName;
    }

    /**
     * When importing designs that have been taken from an in-context implementation
     * (write_checkpoint -cell), often Vivado will write out residual nets that do not necessarily
     * exist in the module or are retaining GND/VCC routing from the parent context.  This method
     * will resolve conflicts by examining the site routing leading from input ports to the sinks
     * and update the site routing to the appropriate net as dictated in the new design.  GND and
     * VCC will be replaced by the name of the source net being driven by the input ports.
     * @param design The design of interest.
     */
    public static void resolveSiteRoutingFromInContextPorts(Design design) {
        EDIFNetlist netlist = design.getNetlist();
        for (EDIFNet net : design.getTopEDIFCell().getNets()) {
            EDIFHierNet parentNet = netlist.getHierNetFromName(net.getName());
            Set<EDIFHierNet> aliases = null;
            for (EDIFPortInst portInst : net.getSourcePortInsts(true)) {
                // Identify top level inport ports
                if (portInst.isTopLevelPort() && portInst.isInput()) {
                    List<EDIFHierPortInst> portInsts = netlist.getSinksFromNet(parentNet);
                    // Iterate over all sinks of the physical net to identify potential site routing
                    // issues
                    for (EDIFHierPortInst sink : portInsts) {
                        Cell c = design.getCell(sink.getFullHierarchicalInstName());
                        if (c == null || !c.isPlaced()) continue;
                        SiteInst i = c.getSiteInst();
                        String logicalPinName = sink.getPortInst().getName();
                        List<String> siteWires = new ArrayList<>();
                        // Using this method just to get site wires along the path
                        c.getSitePinFromLogicalPin(logicalPinName, siteWires);
                        for (String siteWire : siteWires) {
                            Net existingSiteRoutedNet = i.getNetFromSiteWire(siteWire);
                            if (existingSiteRoutedNet == null) continue;
                            EDIFHierNet currNet = netlist.getHierNetFromName(existingSiteRoutedNet.getName());
                            if (aliases == null) {
                                aliases = new HashSet<>(netlist.getNetAliases(parentNet));
                            }
                            if (aliases.contains(currNet)) continue;
                            String updateNetName = parentNet.getHierarchicalNetName();
                            Net updateNet = design.getNet(updateNetName);
                            if (updateNet == null) {
                                updateNet = design.createNet(parentNet);
                            }
                            BELPin belPin = i.getSiteWirePins(siteWire)[0];
                            i.unrouteIntraSiteNet(belPin, belPin);
                            if (i.getSiteWiresFromNet(existingSiteRoutedNet).size() == 0) {
                                existingSiteRoutedNet.getSiteInsts().remove(i);
                            }
                            i.routeIntraSiteNet(updateNet, belPin, belPin);
                        }
                    }
                }
            }
        }
    }

    /**
     * Create a {@link ModuleImplsInst}, i.e. a Module instance with flexible implementation. If an edif cell inst
     * of the given name already exists in the design hierarchy, it will be used for the module. Otherwise, a new
     * EDIF Cell Inst will be created.
     * @param design the design
     * @param name name of the module instance
     * @param module the module to use
     * @return the newly created instance
     */
    public static ModuleImplsInst createModuleImplsInst(Design design, String name, ModuleImpls module) {
        EDIFCellInst cell = design.createOrFindEDIFCellInst(name, module.getNetlist().getTopCell());
        return new ModuleImplsInst(name, cell, module);
    }

    /**
     * Find the physical net corresponding to a {@link ModuleImplsInst}'s port
     * @param port the port to find the net for
     * @param instanceMap map from {@link ModuleImplsInst} to the corresponding real {@link ModuleInst}
     * @return the physical net. This can only be null if the port has no pins
     */
    private static Net findPortNet(ImplsInstancePort port, Map<ModuleImplsInst, ModuleInst> instanceMap) {
        if (port instanceof ImplsInstancePort.SitePinInstPort) {
            SitePinInst spi = ((ImplsInstancePort.SitePinInstPort) port).getSitePinInst();
            Net net = spi.getNet();
            if (net == null) {
                throw new IllegalStateException("No net on SPI "+spi);
            }
            return net;
        } else if (port instanceof ImplsInstancePort.InstPort) {
            ImplsInstancePort.InstPort instPort = (ImplsInstancePort.InstPort) port;
            final Module module = instPort.getInstance().getCurrentModuleImplementation();
            Port modPort = module.getPort(instPort.getPort());
            ModuleInst moduleInst = instanceMap.get(instPort.getInstance());
            Net net = moduleInst.getCorrespondingNet(modPort);
            if (net == null && !modPort.getSitePinInsts().isEmpty()) {
                throw new IllegalStateException("No net on module port "+moduleInst+"."+modPort.getName()+" but we have pins");
            }

            if (!modPort.getPassThruPortNames().isEmpty() && port.isOutputPort()) {
                final List<String> inPorts = modPort.getPassThruPortNames().stream().filter(p -> !module.getPort(p).isOutPort())
                        .collect(Collectors.toList());
                if (inPorts.size()>1) {
                    throw new IllegalStateException("Multiple inputs connected to "+instPort.getInstance().getName()+"."+instPort.getName()+": "+inPorts);
                } else if (inPorts.size() == 1) {
                    final ImplsInstancePort otherPort = instPort.getInstance().getPort(inPorts.get(0));
                    final ImplsInstancePort source = otherPort.getPath().findSource();
                    return findPortNet(source, instanceMap);
                } //Else we only have multiple outs sourced by the same Pin internally, nothing to do
            }

            return net;
        } else {
            throw new IllegalStateException("unknown subtype!");
        }
    }

    /**
     * In a design containing {@link ModuleImplsInst}s, convert them into {@link ModuleInst}s so that the design
     * can be exported to a checkpoint
     * @param design the design
     * @param instances the instances to be converted
     * @param paths nets connecting the instances as returned by {@link BlockPlacer2Impls#getPaths()}
     */
    public static void createModuleInstsFromModuleImplsInsts(Design design, Collection<ModuleImplsInst> instances, Collection<ImplsPath> paths) {
        Map<ModuleImplsInst, ModuleInst> instanceMap = new HashMap<>();
        for (ModuleImplsInst implsInst : instances) {
            ModuleInst modInst = design.createModuleInst(implsInst.getName(), implsInst.getCurrentModuleImplementation());
            boolean success = modInst.place(implsInst.getPlacement().placement);
            if (!success) {
                throw new IllegalStateException("could not place module "+modInst.getName()+" at "+implsInst.getPlacement().placement);
            }
            instanceMap.put(implsInst, modInst);
        }
        for (ImplsPath path : paths) {
            Net net = null;
            for (ImplsInstancePort port : path) {
                Net portNet = findPortNet(port, instanceMap);
                if (portNet == null) {
                    continue;
                }
                if (net == null) {
                    net = portNet;
                } else if (port.isOutputPort()) {
                    design.movePinsToNewNetDeleteOldNet(net, portNet, false);
                    net = portNet;
                } else {
                    design.movePinsToNewNetDeleteOldNet(portNet, net, false);
                }
            }
        }
    }

    /**
     * Determine if a Net is driven by a hierarchical port, created as part of an out-of-context
     * synthesis flow, for example.
     * @param net Net to examine.
     * @return True if driven by a hierport.
     */
    public static boolean isNetDrivenByHierPort(Net net) {
        if (net.getSource() != null) {
            // Net can only be driven by a hier port if it has no site pin driver
            return false;
        }

        if (net.isStaticNet()) {
            // Static nets cannot be driven by a hier port
            return false;
        }

        EDIFNet en = net.getLogicalNet();
        if (en == null) {
            // No corresponding logical net (e.g. present inside an encrypted cell)
            return false;
        }

        List<EDIFPortInst> sourcePorts = en.getSourcePortInsts(true);
        if (sourcePorts.isEmpty()) {
            // Net has no source ports; truly an driver-less net
            return false;
        }

        if (sourcePorts.size() != 1) {
            return false;
        }

        EDIFPortInst epi = sourcePorts.get(0);
        if (!epi.isTopLevelPort()) {
            // Hier ports must be top level ports
            return false;
        }

        return true;
    }

    /**
     * Locks the logical netlist of the design using the DONT_TOUCH property. This
     * strives to be as close as possible to what Vivado's 'lock_design -level
     * netlist' does to lock the design. {@link EDIFTools#lockNetlist(EDIFNetlist)}.
     * 
     * @param design The design of the netlist to lock.
     */
    public static void lockNetlist(Design design) {
        EDIFTools.lockNetlist(design.getNetlist());
    }

    /**
     * Unlocks the logical netlist of the design by removing the DONT_TOUCH
     * property. This strives to be as close as possible to what Vivado's
     * 'lock_design -unlock -level netlist' does to lock the
     * design.{@link EDIFTools#unlockNetlist(EDIFNetlist)}.
     * 
     * @param design The design of the netlist to unlock.
     */
    public static void unlockNetlist(Design design) {
        EDIFTools.unlockNetlist(design.getNetlist());
    }

    /**
     * Locks or unlocks all placement of a design against changes in Vivado. It will
     * also lock or unlock the netlist of the design (see
     * {@link #lockNetlist(Design)}). This strives to be as close as possible to
     * what Vivado's 'lock_design -level placement' does to lock the design.
     * 
     * @param design The design to lock
     * @param lock   Flag indicating to lock (true) or unlock (false) the design's
     *               placement and netlist.
     */
    public static void lockPlacement(Design design, boolean lock) {
        if (lock) {
            lockNetlist(design);
        } else {
            unlockNetlist(design);
        }
        for (SiteInst si : design.getSiteInsts()) {
            si.setSiteLocked(lock);
            for (Cell cell : si.getCells()) {
                cell.setBELFixed(lock);
                cell.setSiteFixed(lock);
            }
        }
    }

    /**
     * Locks placement of cells of a design against changes in Vivado. It will also
     * lock the netlist the design (see {@link #lockNetlist(Design)}). This strives
     * to be as close as possible to what Vivado's 'lock_design -level placement'
     * does to lock the design.
     * 
     * @param design The design to lock
     */
    public static void lockPlacement(Design design) {
        lockPlacement(design, true);
    }

    /**
     * Unlocks placement of cells of a design. It will also unlock the netlist the
     * design (see {@link #unlockNetlist(Design)}). This strives to be as close as
     * possible to what Vivado's 'lock_design -unlock -level placement' does to lock
     * the design.
     * 
     * @param design The design to unlock
     */
    public static void unlockPlacement(Design design) {
        lockPlacement(design, false);
    }

    /**
     * Locks or unlocks all routing of a design (except GND and VCC nets) against
     * changes in Vivado. It will also lock or unlock the netlist and placement of
     * the design (see {@link #lockPlacement(Design, boolean)}). This strives to be
     * as close as possible to what Vivado's 'lock_design -level routing' does to
     * lock the design.
     * 
     * @param design The design to lock
     * @param lock   Flag indicating to lock (true) or unlock (false) the design's
     *               routing, placement and netlist.
     */
    public static void lockRouting(Design design, boolean lock) {
        lockPlacement(design, lock);
        for (Net net : design.getNets()) {
            if (net.isStaticNet())
                continue;
            for (PIP p : net.getPIPs()) {
                p.setIsPIPFixed(lock);
            }
        }
    }

    /**
     * Locks all routing of a design (except GND and VCC nets) against changes in
     * Vivado. It will also lock the netlist and placement of the design. This
     * strives to be as close as possible to what Vivado's 'lock_design -level
     * routing' does to lock the design.
     * 
     * @param design The design to lock
     */
    public static void lockRouting(Design design) {
        lockRouting(design, true);
    }

    /**
     * Unlocks any and all routing of a design. It will also unlock the netlist and
     * placement of the design. This strives to be as close as possible to what
     * Vivado's 'lock_design -unlock -level routing' does to lock the design.
     * 
     * @param design The design to unlock
     */
    public static void unlockRouting(Design design) {
        lockRouting(design, false);
    }

    /***
     * Unroutes the GND net of a design and unroutes the site routing of any LUT GND
     * sources while leaving other site routing inputs intact.
     * 
     * @param design The design to modify.
     */
    public static void unrouteGNDNetAndLUTSources(Design design) {
        // Unroute the site routing of implicit LUT GND sources
        Set<Node> gndNodes = new HashSet<>();
        for (PIP p : design.getGndNet().getPIPs()) {
            gndNodes.add(p.getStartNode());
        }

        for (Node n : gndNodes) {
            SitePin sp = n.getSitePin();
            if (sp != null && !sp.isInput() && Utils.isSLICE(sp.getSite().getSiteTypeEnum())) {
                BELPin src = sp.getBELPin().getSourcePin();
                if (src.getBEL().isLUT()) {
                    SiteInst si = design.getSiteInstFromSite(sp.getSite());
                    if (si != null) {
                        si.unrouteIntraSiteNet(src, sp.getBELPin());
                    }
                }
            }
        }

        design.getGndNet().unroute();
    }

    /**
     * Adds a PROHIBIT constraint for each LUT BEL supplying GND. This is useful
     * when trying to preserve a partially implemented design that have additional
     * logic placed and routed onto it later. The Vivado placer doesn't recognize
     * the GND sources so this prevents the placer from using those BEL sites.
     * 
     * @param design The design to which the PROHIBIT constraints are added.
     */
    public static void prohibitGNDSources(Design design) {
        Set<Node> gndNodes = new HashSet<>();
        for (PIP p : design.getGndNet().getPIPs()) {
            gndNodes.add(p.getStartNode());
        }

        List<String> bels = new ArrayList<>();
        for (Node n : gndNodes) {
            SitePin sp = n.getSitePin();
            if (sp != null && !sp.isInput() && Utils.isSLICE(sp.getSite().getSiteTypeEnum())) {
                BELPin src = sp.getBELPin().getSourcePin();
                if (src.getBEL().isLUT()) {
                    bels.add(sp.getSite().getName() + "/" + src.getBELName());
                }
            }
        }
        addProhibitConstraint(design, bels);
    }

    /**
     * Checks the provided BEL's first letter to determine if it is in the top half
     * of a SLICE or bottom half.
     * 
     * @param bel The BEL of a SLICE to query
     * @return True if the BEL resides in the top half of a SLICE (E6LUT, E5LUT,
     *         EFF, EFF2, ..). Returns false if it is in the bottom half and null if
     *         it couldn't be determined.
     */
    public static Boolean isUltraScaleSliceTop(BEL bel) {
        if (bel.isLUT() || bel.isFF()) {
            char letter = bel.getName().charAt(0);
            return letter >= 'E' && letter <= 'H';
        }
        return null;
    }

    /**
     * This adds PROHIBIT constraints to the design (via .XDC) that will prohibit
     * the use of BEL sites in the same half SLICE if there are any other cells
     * placed in it. It also detects unroutable situations on flip flop inputs and
     * inserts LUT1-routethrus into the netlist. It will also add PROHIBIT
     * constraints onto flip flop sites that are unroutable. This is used for shell
     * creation when an existing placed and routed implementation is desired to be
     * preserved but to allow additional logic to be placed and routed on top of it
     * without an area (pblock) constraint.
     * 
     * @param design The design to which the constraints are added.
     */
    public static void prepareShellBlackBoxForRouting(Design design) {
        List<String> bels = new ArrayList<>();

        // Keep track of used nodes to detect unroutable situations
        Set<Node> used = new HashSet<>();
        Set<Tile> routedTiles = new HashSet<Tile>();
        for (Net net : design.getNets()) {
            for (PIP p : net.getPIPs()) {
                routedTiles.add(p.getTile());
                used.add(p.getStartNode());
                used.add(p.getEndNode());
            }
        }

        for (SiteInst si : design.getSiteInsts()) {
            if (!Utils.isSLICE(si)) continue;
            boolean bottomUsed = false;
            boolean topUsed = false;
            for (Cell c : new ArrayList<>(si.getCells())) {
                Boolean sliceHalf = isUltraScaleSliceTop(c.getBEL());
                if (sliceHalf != null) {
                    if (sliceHalf) {
                        topUsed = true;
                    } else {
                        bottomUsed = true;
                    }
                }
                if (c.getBEL().isFF()) {
                    if(c.getName().equals(Cell.LOCKED)) continue;
                    String belName = c.getBELName();
                    char letter = belName.charAt(0);
                    boolean isFF2 = belName.charAt(belName.length() - 1) == '2';
                    String sitePinName = letter + (isFF2 ? "_I" : "X");
                    Node n = si.getSite().getConnectedNode(sitePinName);
                    if (used.contains(n)) {
                        if (si.getCell(letter + "6LUT") == null && si.getCell(letter + "5LUT") == null) {
                            // Add a 'user-routethru' cell to make input path available
                            BELPin input = c.getBEL().getPin("D");
                            Net net = si.getNetFromSiteWire(input.getSiteWireName());
                            if (net == null) {
                                EDIFHierCellInst inst = c.getEDIFHierCellInst();
                                if (inst != null) {
                                    EDIFHierPortInst portInst = inst.getPortInst("D");
                                    if (portInst != null) {
                                        EDIFHierNet logNet = portInst.getHierarchicalNet();
                                        if (logNet != null) {
                                            net = design.createNet(logNet);
                                        }
                                    }
                                }
                            }
                            if (net != null) {
                                SitePinInst spi = si.getSitePinInst(sitePinName);
                                if (spi == null || (spi != null && !spi.getNet().equals(net))) {
                                    // Check for rare instance of FF driven by GND post inside SLICE
                                    if (letter == 'A' && net.isGNDNet() && isUsingSLICEGND(input, si)) {
                                        continue;
                                    }
                                    BELPin lutInput = si.getBEL(letter + "6LUT").getPin("A6");
                                    EDIFHierPortInst ffInput = c.getEDIFHierCellInst().getPortInst("D");
                                    Cell lut1 = ECOTools.createAndPlaceInlineCellOnInputPin(design, ffInput,
                                            Unisim.LUT1,
                                            si.getSite(), lutInput.getBEL(), "I0", "O");
                                    lut1.addProperty("INIT", "2'h1");
                                }
                            }
                        } else {
                            BELPin muxOutput = c.getBEL().getPin("D").getSourcePin();
                            SitePIP sitePIP = si.getUsedSitePIP(muxOutput);
                            if (sitePIP == null) {
                                System.err.println(
                                        "ERROR: Unable to insert a LUT1 routethru to route an input path for the FF "
                                                + c.getName() + " placed on " + si.getSiteName() + "/" + belName);
                            }
                        }
                    }
                }
            }


            for (BEL bel : si.getSite().getBELs()) {
                if (bel.getBELClass() == BELClass.BEL && si.getCell(bel) == null) {
                    Boolean isTop = isUltraScaleSliceTop(bel);
                    if (isTop != null) {
                        if ((isTop && topUsed) || (!isTop && bottomUsed)) {
                            bels.add(si.getSiteName() + "/" + bel.getName());
                            continue;
                        }
                    }
                    if (bel.isFF()) {
                        // check if the FF BEL output is routable, if not prohibit it from being used
                        if (isFFQOutputBlocked(si.getSite(), bel, used)) {
                            bels.add(si.getSiteName() + "/" + bel.getName());
                        }
                    }
                }
            }
        }

        // Check unused SLICEs FF outputs for unroutable situations and prohibit if
        // needed
        for (Tile tile : routedTiles) {
            Tile left = tile.getTileNeighbor(-1, 0);
            Tile right = tile.getTileNeighbor(1, 0);
            for (Tile neighbor : Arrays.asList(left, right)) {
                if (neighbor != null && Utils.isCLB(neighbor.getTileTypeEnum())) {
                    Site slice = neighbor.getSites()[0];
                    if (design.getSiteInstFromSite(slice) == null) {
                        for (BEL bel : slice.getBELs()) {
                            if (bel.isFF() && isFFQOutputBlocked(slice, bel, used)) {
                                bels.add(slice.getName() + "/" + bel.getName());
                            }
                        }
                    }
                }
            }
        }

        addProhibitConstraint(design, bels);
    }

    private static boolean isUsingSLICEGND(BELPin input, SiteInst si) {
        BEL bel = input != null ? input.getSourcePin().getBEL() : null;
        SitePIP usedSitePIP = bel == null ? null : si.getUsedSitePIP(bel.getName());
        return usedSitePIP != null && usedSitePIP.getInputPinName().equals("F7F8");
    }

    private static boolean isFFQOutputBlocked(Site site, BEL bel, Set<Node> used) {
        String sitePinName = bel.getPin("Q").getConnectedSitePinName();
        Node n = site.getConnectedNode(sitePinName);
        boolean blocked = true;
        for (Node n2 : n.getAllDownhillNodes()) {
            if (!used.contains(n2)) {
                blocked = false;
                break;
            }
        }
        return blocked;
    }

    /**
     * Adds a PROHIBIT constraint to the specified BEL Locations (ex:
     * "SLICE_X10Y10/AFF")
     * 
     * @param design       The design to which the constraint should be added
     * @param belLocations A list of BEL locations using the syntax
     *                     {@literal '<SITE-NAME>/<BEL-NAME>'}.
     */
    public static void addProhibitConstraint(Design design, List<String> belLocations) {
        for (String bel : belLocations) {
            design.addXDCConstraint(ConstraintGroup.LATE,
                    "set_property PROHIBIT true [get_bels { " + bel + "} ]");

        }
    }

    /**
     * Update the SitePinInst.isRouted() value of all pins on the given
     * Net. A sink pin will be marked as being routed if it is reachable from the
     * Net's source pins (or in the case of static nets, also from nodes
     * tied to GND or VCC) when following the Net's PIPs.
     * A source pin will be marked as being routed if it drives at least one PIP.
     * @param net Net on which pins are to be updated.
     */
    public static void updatePinsIsRouted(Net net) {
        for (SitePinInst spi : net.getPins()) {
            spi.setRouted(false);
        }
        if (!net.hasPIPs()) {
            return;
        }

        Queue<Node> queue = new ArrayDeque<>();
        Map<Node, List<Node>> node2fanout = new HashMap<>();
        for (PIP pip : net.getPIPs()) {
            boolean isReversed = pip.isReversed();
            Node startNode = isReversed ? pip.getEndNode() : pip.getStartNode();
            Node endNode = isReversed ? pip.getStartNode() : pip.getEndNode();
            node2fanout.computeIfAbsent(startNode, k -> new ArrayList<>())
                    .add(endNode);
            if (pip.isBidirectional()) {
                node2fanout.computeIfAbsent(endNode, k -> new ArrayList<>())
                        .add(startNode);
            }

            if ((net.getType() == NetType.GND && startNode.isTiedToGnd()) ||
                    (net.getType() == NetType.VCC && startNode.isTiedToVcc())) {
                queue.add(startNode);
            }
        }

        Map<Node, SitePinInst> node2spi = new HashMap<>();
        for (SitePinInst spi : net.getPins()) {
            Node node = spi.getConnectedNode();
            if (spi.isOutPin()) {
                if (node2fanout.get(node) == null) {
                    // Skip source pins with no fanout
                    continue;
                }
                queue.add(node);
            }
            node2spi.put(node, spi);
        }

        while (!queue.isEmpty()) {
            Node node = queue.poll();
            SitePinInst spi = node2spi.get(node);
            if (spi != null) {
                spi.setRouted(true);
            }

            List<Node> fanouts = node2fanout.remove(node);
            if (fanouts != null) {
                queue.addAll(fanouts);
            }
        }
    }

    /**
     * Update the SitePinInst.isRouted() value of all sink pins in the given
     * Design. See {@link #updatePinsIsRouted(Net)}.
     * @param design Design in which pins are to be updated.
     */
    public static void updatePinsIsRouted(Design design) {
        for (Net net : design.getNets()) {
            updatePinsIsRouted(net);
        }
    }

    /**
     * Removes all the existing encrypted cell files from a design and replaces them
     * with the provided list and black boxes those cells. The provided files
     * should be named after the cell type. This is useful in the scenarios where a
     * design has many thousand of individual encrypted cell files that are time
     * consuming to load. By providing a higher level of hierarchy cell definition,
     * encompassing all existing encrypted cells, the number of individual 
     * files to be loaded by Vivado can be reduced.
     * 
     * @param design   The design to modify.
     * @param netlists The list of encrypted cell files (*.edn, *.edf, or *.dcp)
     *                 that should be used instead.
     */
    public static void replaceEncryptedCells(Design design, List<Path> netlists) {
        EDIFNetlist n = design.getNetlist();
        for (Path p : netlists) {
            String fileName = p.getFileName().toString();
            String cellType = fileName.substring(0, fileName.lastIndexOf('.'));
            EDIFCell cell = n.getCell(cellType);
            cell.makePrimitive();
        }
        n.removeUnusedCellsFromAllWorkLibraries();
        n.setEncryptedCells(netlists.stream().map(Object::toString).collect(Collectors.toList()));
    }
}

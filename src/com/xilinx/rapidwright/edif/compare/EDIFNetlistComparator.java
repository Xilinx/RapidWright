/*
 * Copyright (c) 2022, Advanced Micro Devices, Inc.
 * All rights reserved.
 *
 * Author: Chris Lavin, AMD AECG Research Labs.
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

/**
 * 
 */
package com.xilinx.rapidwright.edif.compare;

import java.util.HashMap;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Objects;

import com.xilinx.rapidwright.device.PartNameTools;
import com.xilinx.rapidwright.device.Series;
import com.xilinx.rapidwright.edif.EDIFCell;
import com.xilinx.rapidwright.edif.EDIFCellInst;
import com.xilinx.rapidwright.edif.EDIFDirection;
import com.xilinx.rapidwright.edif.EDIFLibrary;
import com.xilinx.rapidwright.edif.EDIFNet;
import com.xilinx.rapidwright.edif.EDIFNetlist;
import com.xilinx.rapidwright.edif.EDIFPort;
import com.xilinx.rapidwright.edif.EDIFPortInst;
import com.xilinx.rapidwright.edif.EDIFPropertyObject;
import com.xilinx.rapidwright.edif.EDIFPropertyValue;
import com.xilinx.rapidwright.edif.EDIFTools;
import com.xilinx.rapidwright.edif.EDIFValueType;
import com.xilinx.rapidwright.tests.CodePerfTracker;

/**
 * Created on: Sep 26, 2022
 */
public class CompareNetlists {
    
    private static boolean equivalentEDIFPropObject(EDIFPropertyObject gold, EDIFPropertyObject test) {
        if (gold.getPropertiesMap().size() == 1 && test.getPropertiesMap().size() == 0) {
            if (gold.getPropertiesMap().keySet().iterator().next().equals("RTL_KEEP")) {
                // Filtering out RTL_KEEP properties as they only exist in EDIF and not XN
                return true;
            }
        }
        check(gold.getPropertiesMap().size(), test.getPropertiesMap().size(), "EDIFPropertyObject.getProperties().size() [name=" + gold + "]");
        Map<String, EDIFPropertyValue> testMap = new HashMap<>(test.getPropertiesMap());
        for (Entry<String, EDIFPropertyValue> e : gold.getPropertiesMap().entrySet()) {
            EDIFPropertyValue testValue = testMap.remove(e.getKey());
            if (testValue == null) {
                System.err.println("ERROR: Missing property " + e + " on " + gold.getName());
                continue;
            }
            EDIFPropertyValue goldValue = e.getValue();
            check(goldValue.getOwner(), testValue.getOwner(), "EDIFPropertyValue.getOwner() [key=" +e.getKey()+ ", obj name=" + gold +"]");
            check(goldValue.getValue(), testValue.getValue(), "EDIFPropertyValue.getValue() [key=" +e.getKey()+ ", obj name=" + gold +"]");
            check(goldValue.getType(), testValue.getType(), "EDIFPropertyValue.getType() [key=" +e.getKey()+ ", obj name=" + gold +"]");
        }
        return true;
    }
    
    private static void checkPorts(EDIFCell gold, EDIFCell test) {
        Map<String, EDIFPort> testPorts = new HashMap<>(test.getPortMap());
        for (Entry<String, EDIFPort> e : gold.getPortMap().entrySet()) {
            EDIFPort testPort = testPorts.remove(e.getKey());
            if (testPort == null) {
                System.err.println("ERROR: Missing test port " + e.getKey() + " on cell " + gold 
                        + " from library " + gold.getLibrary());
                continue;
            }
            EDIFPort goldPort = e.getValue();
            equivalentEDIFPropObject(goldPort, testPort);
            check(goldPort.getName(), testPort.getName(), "EDIFPort.getName()  [lib=" + gold.getLibrary()+", name="+ gold + "]");
            check(goldPort.getBusName(), testPort.getBusName(), "EDIFPort.getBusName() [lib=" + gold.getLibrary()+", name="+ gold + "]");
            check(goldPort.getWidth(), testPort.getWidth(), "EDIFPort.getWidth() [lib=" + gold.getLibrary()+", name="+ gold + "]");
            check(goldPort.getDirection(), testPort.getDirection(), "EDIFPort.getDirection() [lib=" + gold.getLibrary()+", name="+ gold + "]");
            check(goldPort.getLeft(), testPort.getLeft(), "EDIFPort.getLeft() [lib=" + gold.getLibrary()+", name="+ gold + "]"); 
            check(goldPort.getRight(), testPort.getRight(), "EDIFPort.getRight() [lib=" + gold.getLibrary()+", name="+ gold + "]");
            check(goldPort.isLittleEndian(), testPort.isLittleEndian(), "EDIFPort.isLittleEndian() [lib=" + gold.getLibrary()+", name="+ gold + "]");
        }
        for (Entry<String, EDIFPort> e : testPorts.entrySet()) {
            System.err.println("ERROR: Extra port " + e.getKey() + " present on cell " + gold + " in library " + gold.getLibrary());
        }
    }
    
    private static void checkNets(EDIFCell gold, EDIFCell test) {
        Map<String, EDIFNet> testNets = new HashMap<>();
        for (EDIFNet net : test.getNets()) {
            testNets.put(net.getName(), net);
        }
        Map<String, EDIFNet> goldNets = new HashMap<>();
        for (EDIFNet net : gold.getNets()) {
            goldNets.put(net.getName(), net);
        }
        for (Entry<String, EDIFNet> e : goldNets.entrySet()) {
            EDIFNet testNet = testNets.remove(e.getKey());
            if (testNet == null) {
                System.err.println("ERROR: Missing test net " + e.getKey() + " on cell " + gold 
                        + " from library " + gold.getLibrary());
                continue;
            }
            EDIFNet goldNet = e.getValue();
            equivalentEDIFPropObject(goldNet, testNet);
            check(goldNet.getName(), testNet.getName(), "EDIFNet.getName()  [lib=" + gold.getLibrary()+", name="+ gold + "]");
            Map<String, EDIFPortInst> goldPortInsts = new HashMap<>();
            for (EDIFPortInst p : goldNet.getPortInsts()) {
                goldPortInsts.put(p.getName(), p);
            }
            Map<String, EDIFPortInst> testPortInsts = new HashMap<>();
            for (EDIFPortInst p : testNet.getPortInsts()) {
                testPortInsts.put(p.getName(), p);
            }
            for (Entry<String, EDIFPortInst> e2 : goldPortInsts.entrySet()) {
                EDIFPortInst testPortInst = testPortInsts.remove(e2.getKey());
                if (testPortInst == null) {
                    System.err.println("ERROR: Missing port inst "+e2.getKey()+" on net " + goldNet 
                            + " in cell " + gold + " from library " + gold.getLibrary());
                    continue;
                }
                EDIFPortInst goldPortInst = e2.getValue();
                check(goldPortInst.getName(), testPortInst.getName(), "EDIFPortInst.getName() [lib="
                                            +gold.getLibrary()+", cell="+gold+", net="+goldNet+"]");
                check(goldPortInst.getDirection(), testPortInst.getDirection(), "EDIFPortInst.getDirection() [lib="
                        +gold.getLibrary()+", cell="+gold+", net="+goldNet+"]");
                check(goldPortInst.getFullName(), testPortInst.getFullName(), "EDIFPortInst.getFullName() [lib="
                        +gold.getLibrary()+", cell="+gold+", net="+goldNet+"]");
                check(goldPortInst.getIndex(), testPortInst.getIndex(), "EDIFPortInst.getIndex() [lib="
                        +gold.getLibrary()+", cell="+gold+", net="+goldNet+"]");
                check(goldPortInst.getPort().getName(), testPortInst.getPort().getName(), "EDIFPortInst.getPort() [lib="
                        +gold.getLibrary()+", cell="+gold+", net="+goldNet+"]");
                String goldInstName = goldPortInst.getCellInst() == null ? null : goldPortInst.getCellInst().getName();
                String testInstName = testPortInst.getCellInst() == null ? null : testPortInst.getCellInst().getName();
                check(goldInstName, testInstName,
                            "EDIFPortInst.getCellInst() [lib=" + gold.getLibrary() + ", cell=" + gold + ", net="
                                    + goldNet + "]");
            }
            
        }
        for (Entry<String,EDIFNet> e : testNets.entrySet()) {
            System.err.println("ERROR: Extra net " + e.getKey() + " present in cell " + gold + " in library " + gold.getLibrary());
        }
    }
    
    private static void checkInsts(EDIFCell gold, EDIFCell test) {
        Map<String, EDIFCellInst> goldCellInsts = new HashMap<>();
        for (EDIFCellInst inst : gold.getCellInsts()) {
            goldCellInsts.put(inst.getName(), inst);
        }
        Map<String, EDIFCellInst> testCellInsts = new HashMap<>();
        for (EDIFCellInst inst : test.getCellInsts()) {
            testCellInsts.put(inst.getName(), inst);
        }
        for (Entry<String, EDIFCellInst> e : goldCellInsts.entrySet()) {
            EDIFCellInst testInst = testCellInsts.remove(e.getKey());
            if (testInst == null) {
                System.err.println("ERROR: missing inst " + e.getKey() + " in cell " + gold + " from library "
                        + gold.getLibrary());
                continue;
            }
            EDIFCellInst goldInst = e.getValue();
            check(goldInst.getName(), testInst.getName(),
                    "EDIFCellInst.getName() [cell=" + gold + ", lib=" + gold.getLibrary() + "]");
            check(goldInst.getViewref().getName(), testInst.getViewref().getName(),
                    "EDIFCellInst.getViewref().getName() [cell=" + gold + ", lib=" + gold.getLibrary() + "]");
        }
        for (Entry<String, EDIFCellInst> e : testCellInsts.entrySet()) {
            System.err.println("ERROR: Extra cell inst " + e.getKey() + " present in cell " + gold + " in libarary "
                    + gold.getLibrary());
        }
    }
    
    private static void checkCell(EDIFCell gold, EDIFCell test) {
        equivalentEDIFPropObject(gold, test);
        check(gold.getName(), test.getName(), "EDIFCell.getName() [lib=" + gold.getLibrary()+"]");
        check(gold.getView(), test.getView(), "EDIFCell.getView() [lib=" + gold.getLibrary()+", name="+ gold + "]");
        
        checkPorts(gold,test);
        
        checkNets(gold, test);
        
        checkInsts(gold, test);
    }
    
    private static void check(String gold, String test, String desc) {
        if (!Objects.equals(gold, test)) {
            System.err.println("[" + desc + "] ERROR: expected=" + gold + ", found=" + test);
        }
    }

    private static void check(int gold, int test, String desc) {
        if (gold != test) {
            System.err.println("[" + desc + "] ERROR: expected=" + gold + ", found=" + test);
        }
    }

    private static void check(Integer gold, Integer test, String desc) {
        if (!Objects.equals(gold, test)) {
            System.err.println("[" + desc + "] ERROR: expected=" + gold + ", found=" + test);
        }
    }

    
    private static void check(EDIFValueType gold, EDIFValueType test, String desc) {
        if (gold != test) {
            System.err.println("[" + desc + "] ERROR: expected=" + gold + ", found=" + test);
        }
    }

    private static void check(EDIFDirection gold, EDIFDirection test, String desc) {
        if (gold != test) {
            System.err.println("[" + desc + "] ERROR: expected=" + gold + ", found=" + test);
        }
    }

    private static void check(boolean gold, boolean test, String desc) {
        if (gold != test) {
            System.err.println("[" + desc + "] ERROR: expected=" + gold + ", found=" + test);
        }
    }
    
    public static void compareNetlists(EDIFNetlist gold, EDIFNetlist test) {
        check(gold.getName(), test.getName(), "EDIFNetlist.getName()");
        check(gold.getDesign().getName(), test.getDesign().getName(), "EDIFNetlist.getDesign().getName()");
        equivalentEDIFPropObject(gold.getDesign(), test.getDesign());
        check(gold.getLibraries().size(), test.getLibraries().size(), "EDIFNetlist.getLibraries().size()");

        Map<String, EDIFLibrary> testLibs = new HashMap<>(test.getLibrariesMap());
        for (Entry<String, EDIFLibrary> e : gold.getLibrariesMap().entrySet()) {
            EDIFLibrary testLib = testLibs.remove(e.getKey());
            if (testLib == null && e.getKey().endsWith("_")) {
                StringBuilder sb = new StringBuilder(
                        e.getKey().substring(0, e.getKey().length() - 1));
                int idx = sb.lastIndexOf("_");
                sb.replace(idx, idx + 1, "[");
                sb.append("]");
                testLib = testLibs.remove(sb.toString());
            }
            if (testLib == null) {
                System.err.println("ERROR: test missing library: " + e.getKey());
                continue;
            }
            EDIFLibrary goldLib = e.getValue();
            check(goldLib.getName(), testLib.getName(), "EDIFLibrary.getName()");
            check(goldLib.getCells().size(), testLib.getCells().size(), "EDIFLibrary.getCells().size() lib=" + goldLib);
            Map<String,EDIFCell> testCells = new HashMap<>(testLib.getCellMap());
            for (Entry<String,EDIFCell> e2 : goldLib.getCellMap().entrySet()) {
                EDIFCell testCell = testCells.remove(e2.getKey());
                if (testCell == null) {
                    System.err.println("ERROR: test missing cell " + e2.getKey() + " from library " + goldLib);
                }
                EDIFCell goldCell = e2.getValue();
                checkCell(goldCell, testCell);
            }
            for (Entry<String, EDIFCell> e2 : testCells.entrySet()) {
                System.err.println("ERROR: Extra cell present: " + e2.getKey() + " in library " + goldLib);
            }
        }
        for (Entry<String, EDIFLibrary> e : testLibs.entrySet()) {
            System.err.println("ERROR: Extra library present: " + e.getKey() + ", " + e.getValue());
        }
    }

    public static void main(String[] args) {
        if(args.length != 2) {
            System.out.println("USAGE: <golden EDIF Netlist> <test EDIFNetlist>");
            return;
        }
        CodePerfTracker t = new CodePerfTracker("Compare EDIF Netlists");
        t.start("Load Gold");
        EDIFNetlist gold = EDIFTools.readEdifFile(args[0]);
        Series series = PartNameTools.getPart(EDIFTools.getPartName(gold)).getSeries();
        gold.expandMacroUnisims(series);
        t.stop().start("Load Test");
        EDIFNetlist test = EDIFTools.readEdifFile(args[1]);
        test.expandMacroUnisims(series);
        t.stop().start("Compare");
        compareNetlists(gold, test);
        t.stop().printSummary();
    }
}

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

import java.io.IOException;
import java.io.PrintStream;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Objects;

import com.xilinx.rapidwright.device.PartNameTools;
import com.xilinx.rapidwright.device.Series;
import com.xilinx.rapidwright.edif.EDIFCell;
import com.xilinx.rapidwright.edif.EDIFCellInst;
import com.xilinx.rapidwright.edif.EDIFLibrary;
import com.xilinx.rapidwright.edif.EDIFNet;
import com.xilinx.rapidwright.edif.EDIFNetlist;
import com.xilinx.rapidwright.edif.EDIFPort;
import com.xilinx.rapidwright.edif.EDIFPortInst;
import com.xilinx.rapidwright.edif.EDIFPropertyObject;
import com.xilinx.rapidwright.edif.EDIFPropertyValue;
import com.xilinx.rapidwright.edif.EDIFTools;
import com.xilinx.rapidwright.tests.CodePerfTracker;

/**
 * This is a helper class designed to compare two EDIFNetlists for differences.
 * It can filter out differences introduced by Vivado by reading in the EDIF and
 * writing out as a DCP. It can also generate a report file describing all the
 * differences found.
 */
public class EDIFNetlistComparator {

    /**
     * Setting this to true will attempt to account for legalized square brackets
     * replaced by underscores around an indexed suffix on a Library name. For
     * example, if the Library name was originally 'my_signal[4]' but in translation
     * by Vivado was changed to 'my_signal_4_', setting this flag to true will
     * attempt to find the square bracketed name equivalent
     */
    public boolean restoreBrackets = true;

    /**
     * A netlist will undergo several changes when loaded via DCP vs. via EDIF. This
     * flag attempts to account for the majority of those changes.
     */
    public boolean filterVivadoChanges = true;
    
    private Map<EDIFDiffType, List<EDIFDiff>> diffMap;

    private int diffCount;

    public EDIFNetlistComparator() {
        diffMap = new HashMap<>();
        diffCount = 0;
    }

    private static EDIFCell getParentCell(EDIFPropertyObject o) {
        if (o instanceof EDIFNet) {
            return ((EDIFNet) o).getParentCell();
        } else if (o instanceof EDIFCellInst) {
            return ((EDIFCellInst) o).getParentCell();
        } else if (o instanceof EDIFPort) {
            return ((EDIFPort) o).getParentCell();
        }
        return null;
    }

    private void equivalentEDIFPropObject(EDIFPropertyObject gold, EDIFPropertyObject test) {
        Map<String, EDIFPropertyValue> testMap = new HashMap<>(test.getPropertiesMap());
        EDIFCell parent = getParentCell(gold);
        EDIFLibrary parentLib = parent == null ? ((EDIFCell) gold).getLibrary() : parent.getLibrary();

        for (Entry<String, EDIFPropertyValue> e : gold.getPropertiesMap().entrySet()) {
            EDIFPropertyValue testValue = testMap.remove(e.getKey());
            EDIFPropertyValue goldValue = e.getValue();
            if (testValue == null) {
                if (!filterVivadoChanges) {
                    addDiff(EDIFDiffType.PROPERTY_MISSING, goldValue, testValue, parent, parentLib,
                            "key=" + e.getKey());
                }
                continue;
            }

            checkDiff(goldValue.getOwner(), testValue.getOwner(), EDIFDiffType.PROPERTY_OWNER,
                    goldValue, testValue, parent, parentLib);
            checkDiff(goldValue.getValue(), testValue.getValue(), EDIFDiffType.PROPERTY_VALUE,
                    goldValue, testValue, parent, parentLib);
            checkDiff(goldValue.getType(), testValue.getType(), EDIFDiffType.PROPERTY_TYPE, goldValue,
                    testValue, parent, parentLib);
        }

        if (!filterVivadoChanges) {
            for (Entry<String, EDIFPropertyValue> e : testMap.entrySet()) {
                addDiff(EDIFDiffType.PROPERTY_EXTRA, null, e.getValue(), parent, parentLib,
                        "key=" + e.getKey());
            }
        }
    }
    
    private void checkPorts(EDIFCell gold, EDIFCell test) {
        Map<String, EDIFPort> testPorts = new HashMap<>(test.getPortMap());
        for (Entry<String, EDIFPort> e : gold.getPortMap().entrySet()) {
            EDIFPort testPort = testPorts.remove(e.getKey());
            if (testPort == null) {
                addDiff(EDIFDiffType.PORT_MISSING, e.getValue(), null, gold, gold.getLibrary(), "");
                continue;
            }
            EDIFPort goldPort = e.getValue();
            equivalentEDIFPropObject(goldPort, testPort);
            checkDiff(goldPort.getName(), testPort.getName(), EDIFDiffType.PORT_NAME, goldPort,
                    testPort, gold, gold.getLibrary());
            checkDiff(goldPort.getBusName(), testPort.getBusName(), EDIFDiffType.PORT_BUSNAME,
                    goldPort, testPort, gold, gold.getLibrary());
            checkDiff(goldPort.getWidth(), testPort.getWidth(), EDIFDiffType.PORT_WIDTH, goldPort,
                    testPort, gold, gold.getLibrary());
            checkDiff(goldPort.getDirection(), testPort.getDirection(), EDIFDiffType.PORT_DIRECTION,
                    goldPort, testPort, gold, gold.getLibrary());
            checkDiff(goldPort.getLeft(), testPort.getLeft(), EDIFDiffType.PORT_LEFT_RANGE_LIMIT,
                    goldPort, testPort, gold,
                        gold.getLibrary());
            checkDiff(goldPort.getRight(), testPort.getRight(), EDIFDiffType.PORT_RIGHT_RANGE_LIMIT,
                    goldPort, testPort, gold,
                        gold.getLibrary());
            checkDiff(goldPort.isLittleEndian(), testPort.isLittleEndian(),
                    EDIFDiffType.PORT_ENDIANNESS, goldPort, testPort, gold, gold.getLibrary());
        }
        for (Entry<String, EDIFPort> e : testPorts.entrySet()) {
            addDiff(EDIFDiffType.PORT_EXTRA, null, e.getValue(), test, test.getLibrary(), "");
        }
    }
    
    private void checkNets(EDIFCell gold, EDIFCell test) {
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
                addDiff(EDIFDiffType.NET_MISSING, e.getValue(), testNet, gold, gold.getLibrary(), "");
                continue;
            }
            EDIFNet goldNet = e.getValue();
            equivalentEDIFPropObject(goldNet, testNet);
            Map<String, EDIFPortInst> goldPortInsts = new HashMap<>();
            checkDiff(goldNet.getName(), testNet.getName(), EDIFDiffType.NET_NAME, goldNet, testNet,
                    gold, gold.getLibrary());
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
                    addDiff(EDIFDiffType.NET_PORT_INST_MISSING, e2.getValue(), testPortInst, gold,
                            gold.getLibrary(), "");
                    continue;
                }
                EDIFPortInst goldPortInst = e2.getValue();
                checkDiff(goldPortInst.getName(), testPortInst.getName(),
                        EDIFDiffType.NET_PORT_INST_NAME, goldPortInst, testPortInst, gold,
                        gold.getLibrary());
                checkDiff(goldPortInst.getDirection(), testPortInst.getDirection(),
                        EDIFDiffType.NET_PORT_INST_DIRECTION, goldPortInst, testPortInst, gold,
                        gold.getLibrary());
                checkDiff(goldPortInst.getFullName(), testPortInst.getFullName(),
                        EDIFDiffType.NET_PORT_INST_FULLNAME, goldPortInst, testPortInst, gold,
                        gold.getLibrary());
                checkDiff(goldPortInst.getIndex(), testPortInst.getIndex(),
                        EDIFDiffType.NET_PORT_INST_INDEX, goldPortInst, testPortInst, gold,
                        gold.getLibrary());
                checkDiff(goldPortInst.getPort().getName(), testPortInst.getPort().getName(),
                        EDIFDiffType.NET_PORT_INST_PORT, goldPortInst, testPortInst, gold,
                        gold.getLibrary());
                String goldInstName = goldPortInst.getCellInst() == null ? null : goldPortInst.getCellInst().getName();
                String testInstName = testPortInst.getCellInst() == null ? null : testPortInst.getCellInst().getName();
                checkDiff(goldInstName, testInstName, EDIFDiffType.NET_PORT_INST_INSTNAME,
                        goldPortInst, testPortInst, gold, gold.getLibrary());
            }
            
            for (Entry<String, EDIFPortInst> e2 : testPortInsts.entrySet()) {
                addDiff(EDIFDiffType.NET_PORT_INST_EXTRA, null, e2.getValue(), test,
                        test.getLibrary(), "");
            }
        }
        for (Entry<String,EDIFNet> e : testNets.entrySet()) {
            addDiff(EDIFDiffType.NET_EXTRA, null, e.getValue(), test, test.getLibrary(), "");
        }
    }
    
    private void checkInsts(EDIFCell gold, EDIFCell test) {
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
                addDiff(EDIFDiffType.INST_MISSING, e.getValue(), testInst, gold, gold.getLibrary(),
                        "");
                continue;
            }
            EDIFCellInst goldInst = e.getValue();
            equivalentEDIFPropObject(goldInst, testInst);
            checkDiff(goldInst.getName(), testInst.getName(), EDIFDiffType.INST_NAME, goldInst,
                    testInst, gold, gold.getLibrary());
            checkDiff(goldInst.getViewref(), testInst.getViewref(), EDIFDiffType.INST_VIEWREF,
                    goldInst, testInst, gold, gold.getLibrary());
        }
        for (Entry<String, EDIFCellInst> e : testCellInsts.entrySet()) {
            addDiff(EDIFDiffType.INST_EXTRA, null, e.getValue(), test, test.getLibrary(), "");
        }
    }
    
    private void checkCell(EDIFCell gold, EDIFCell test) {
        equivalentEDIFPropObject(gold, test);
        checkDiff(gold.getName(), test.getName(), EDIFDiffType.CELL_NAME, gold, test, null,
                gold.getLibrary());
        checkDiff(gold.getView(), test.getView(), EDIFDiffType.CELL_VIEWREF, gold, test, null,
                gold.getLibrary());
        
        checkPorts(gold,test);
        
        checkNets(gold, test);
        
        checkInsts(gold, test);
    }
    
    private static String restoreEndingSquareBrackets(String name) {
        StringBuilder sb = new StringBuilder(name.substring(0, name.length() - 1));
        int idx = sb.lastIndexOf("_");
        sb.replace(idx, idx + 1, "[");
        sb.append("]");
        return sb.toString();
    }

    private void checkDiff(Object checkGold, Object checkTest, EDIFDiffType type, Object gold,
            Object test, EDIFCell parentCell, EDIFLibrary parentLibrary) {
        if (!Objects.equals(checkGold, checkTest)) {
            if (filterVivadoChanges) {
                if (type == EDIFDiffType.INST_VIEWREF && checkTest.toString().equals("abstract")) {
                    return;
                } else if (type == EDIFDiffType.PROPERTY_VALUE
                        && checkGold.toString().endsWith(" nS")
                        && checkGold.toString().replace(" nS", "").equals(checkTest.toString())) {
                    return;
                }
            }
            String notEqualString = checkGold + " != " + checkTest;
            addDiff(type, gold, test, parentCell, parentLibrary, notEqualString);
        }
    }

    private void addDiff(EDIFDiffType type, Object gold, Object test, EDIFCell parentCell,
            EDIFLibrary parentLibrary, String notEqualString) {
        List<EDIFDiff> diffs = diffMap.computeIfAbsent(type, l -> new ArrayList<>());
        diffs.add(new EDIFDiff(type, gold, test, parentCell, parentLibrary, notEqualString));
        diffCount++;
    }

    public int compareNetlists(EDIFNetlist gold, EDIFNetlist test) {
        diffMap = new LinkedHashMap<>();

        Map<String, EDIFLibrary> testLibs = new HashMap<>(test.getLibrariesMap());
        for (Entry<String, EDIFLibrary> e : gold.getLibrariesMap().entrySet()) {
            EDIFLibrary testLib = testLibs.remove(e.getKey());
            if (testLib == null && restoreBrackets && e.getKey().endsWith("_")) {
                testLib = testLibs.remove(restoreEndingSquareBrackets(e.getKey()));
            }
            if (testLib == null) {
                addDiff(EDIFDiffType.LIBRARY_MISSING, e.getValue(), testLib, null, null, "");
                continue;
            }
            EDIFLibrary goldLib = e.getValue();
            checkDiff(gold.getName(), test.getName(), EDIFDiffType.LIBRARY_NAME, gold, test, null,
                    null);
            Map<String,EDIFCell> testCells = new HashMap<>(testLib.getCellMap());
            for (Entry<String,EDIFCell> e2 : goldLib.getCellMap().entrySet()) {
                EDIFCell testCell = testCells.remove(e2.getKey());
                if (testCell == null) {
                    addDiff(EDIFDiffType.CELL_MISSING, e2.getValue(), testCell, null, goldLib, "");
                }
                EDIFCell goldCell = e2.getValue();
                checkCell(goldCell, testCell);
            }
            for (Entry<String, EDIFCell> e2 : testCells.entrySet()) {
                if (filterVivadoChanges && isHDUniqueified(e2.getValue())) {
                    continue;
                }
                addDiff(EDIFDiffType.CELL_EXTRA, null, e2.getValue(), null, goldLib, "");
            }
        }
        for (Entry<String, EDIFLibrary> e : testLibs.entrySet()) {
            addDiff(EDIFDiffType.LIBRARY_EXTRA, null, e.getValue(), null, null, "");
        }
        return diffCount;
    }

    private static boolean isHDUniqueified(EDIFCell cell) {
        String name = cell.getName();
        int index = name.length() - 1;
        while (Character.isDigit(name.charAt(index)) && index > 0) {
            index--;
        }
        if (index == name.length() - 1)
            return false;
        if (index > 2 && name.charAt(index) == 'D' && name.charAt(index - 1) == 'H'
                && name.charAt(index - 2) == '_') {
            index = index - 2;
        } else {
            return false;
        }
        String rootName = name.substring(0, index);
        return cell.getLibrary().containsCell(rootName);
    }

    public void printDiffReportSummary(PrintStream ps) {
        ps.println("=============================================================================");
        ps.println("= EDIFNetlist Diff Summary");
        ps.println("=============================================================================");
        int totalSanity = 0;
        for (EDIFDiffType type : EDIFDiffType.values()) {
            int typeDiffCount = diffMap.getOrDefault(type, Collections.emptyList()).size();
            totalSanity += typeDiffCount;
            ps.printf("%9d %s Diffs\n", typeDiffCount, type.name());
        }
        ps.println("-----------------------------------------------------------------------------");
        assert (totalSanity == diffCount);
        ps.printf("%9d Total Diffs\n\n", diffCount);
    }

    public void printDiffReport(PrintStream ps) {
        printDiffReportSummary(ps);

        for (Entry<EDIFDiffType, List<EDIFDiff>> e : diffMap.entrySet()) {
            ps.println(" *** " + e.getKey() + ": " + e.getValue().size() + " diffs");
            for (EDIFDiff diff : e.getValue()) {
                ps.println("  " + diff.toString());
            }

        }
    }

    public static void main(String[] args) {
        if (args.length != 2 && args.length != 3) {
            System.out.println(
                    "USAGE: <golden EDIF Netlist> <test EDIFNetlist> [diff report filename]");
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
        EDIFNetlistComparator comparator = new EDIFNetlistComparator();
        int diffs = comparator.compareNetlists(gold, test);
        if (args.length == 3) {
            try (PrintStream ps = new PrintStream(args[2])) {
                comparator.printDiffReport(ps);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        } else {
            comparator.printDiffReport(System.out);
        }
        t.stop().printSummary();
        
        System.exit(diffs > 0 ? 1 : 0);
    }
}

/*
 *
 * Copyright (c) 2018-2022, Xilinx, Inc.
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

package com.xilinx.rapidwright.design.tools;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Queue;
import java.util.Set;

import com.xilinx.rapidwright.design.AltPinMapping;
import com.xilinx.rapidwright.design.Cell;
import com.xilinx.rapidwright.design.Design;
import com.xilinx.rapidwright.design.DesignTools;
import com.xilinx.rapidwright.design.Net;
import com.xilinx.rapidwright.design.PinSwap;
import com.xilinx.rapidwright.design.SiteInst;
import com.xilinx.rapidwright.design.SitePinInst;
import com.xilinx.rapidwright.design.Unisim;
import com.xilinx.rapidwright.device.BEL;
import com.xilinx.rapidwright.device.BELPin;
import com.xilinx.rapidwright.device.Device;
import com.xilinx.rapidwright.device.Node;
import com.xilinx.rapidwright.device.PIP;
import com.xilinx.rapidwright.device.Site;
import com.xilinx.rapidwright.device.SitePin;
import com.xilinx.rapidwright.edif.EDIFCell;
import com.xilinx.rapidwright.edif.EDIFCellInst;
import com.xilinx.rapidwright.edif.EDIFPropertyValue;


/**
 * A collection of tools to help modify LUTs.
 *
 * @author clavin
 *
 */
public class LUTTools {

    public static final String LUT_INIT = "INIT";

    public static final int MAX_LUT_SIZE = 6;

    public static final String[][] lutMap;

    public static final char[] lutLetters = new char[] {'A', 'B', 'C', 'D', 'E', 'F', 'G', 'H'};

    public static final String[] empty = new String[0];

    static {
        lutMap = new String[lutLetters.length][];
        for (char letter : lutLetters) {
            lutMap[letter - 'A'] = new String[] {letter + "6LUT", letter + "5LUT"};
        }
    }

    /**
     * Gets the corresponding BEL site names for a given LUT letter.  For example, 'A' will return
     * ["A6LUT", "A5LUT"]
     * @param lutLetter The letter of the LUT of interest.  Valid values are 'A'..'H',
     * case insensitive.
     * @return The list of corresponding BEL site names or an empty list if none were found.
     */
    public static String[] getLUTBELNames(char lutLetter) {
        int index = lutLetter - (lutLetter >= 'a' ? 'a' : 'A');
        if (index < 0 || index >= lutLetters.length) return empty;
        return lutMap[index];
    }

    /**
     * Gets the BEL's companion LUT.  For example the A5LUT bel's companion would be A6LUT and vice
     * versa.
     * @param lut The LUT bel in question.
     * @return The name of the companion LUT or null if undetermined.
     */
    public static String getCompanionLUTName(BEL lut) {
        if (!lut.isLUT()) return null;
        return getCompanionLUTName(lut.getName());
    }

    /**
     * Get the companion LUT name of the provided LUT name. For example the A5LUT bel's companion
     * would be A6LUT and vice versa.
     * @param lutName Name of the LUT in question.
     * @return The companion LUT name or null if undetermined.
     */
    public static String getCompanionLUTName(String lutName) {
        String[] belNames = getLUTBELNames(lutName.charAt(0));
        return belNames[(lutName.charAt(1) == '6') ? 1 : 0];
    }

    /**
     * From a placed LUT cell, get the companion LUT BEL location. For example, a
     * cell placed on A5LUT would return the A6LUT and vice versa.
     * 
     * @param cell A placed cell on a LUT BEL.
     * @return The companion LUT BEL or null if the cell is not placed on a LUT BEL
     *         location.
     */
    public static BEL getCompanionLUT(Cell cell) {
        assert (cell.getBEL().isLUT());
        String belName = cell.getBELName();
        if (belName == null) return null;
        String otherLUTName = getCompanionLUTName(belName);
        return otherLUTName != null ? cell.getSite().getBEL(otherLUTName) : null;
    }

    /**
     * From a placed LUT cell, get the cell in the companion LUT BEL location. For
     * example, a cell placed on A5LUT would return the cell in the A6LUT and vice
     * versa.
     * 
     * @param cell A placed cell on a LUT BEL.
     * @return The companion cell placed in the LUT BEL location or null if the cell
     *         is not placed on a LUT BEL location or there is no cell in the
     *         companion LUT BEL.
     */
    public static Cell getCompanionLUTCell(Cell cell) {
        SiteInst si = cell.getSiteInst();
        return si != null ? si.getCell(getCompanionLUT(cell)) : null;
    }

    /**
     * Gets the output pin from the BEL of a LUT
     * 
     * @param bel The physical bel site of the LUT
     * @return O5 or O6 based on the BEL
     */
    public static BELPin getLUTOutputPin(BEL bel) {
        return bel.getPin("O" + bel.getName().charAt(1));
    }

    /**
     * Checks if this cell is a LUT (LUT1, LUT2, LUT3,...). A CFGLUT5 will return
     * false.
     * 
     * @param c The cell in question
     * @return True if this is a LUT[1-6], false otherwise.
     */
    public static boolean isCellALUT(Cell c) {
        return isCellALUT(c.getEDIFCellInst());
    }

    /**
     * Checks if this cell is a LUT (LUT1, LUT2, LUT3,...). A CFGLUT5 will return false.
     * @param i The cell in question
     * @return True if this is a LUT[1-6], false otherwise.
     */
    public static boolean isCellALUT(EDIFCellInst i) {
        if (i == null) return false;
        EDIFCell ec = i.getCellType();
        if (ec == null) return false;
        boolean isPrimitive = ec.isPrimitive();
        boolean startsWithLUT = ec.getName().startsWith("LUT");
        if (isPrimitive && startsWithLUT) return true;
        return false;
    }

    /**
     * If the provided cell is a LUT, it will return the LUT input
     * count.  If it is not a LUT, it will return 0.
     * @param c The LUT cell
     * @return The number of LUT inputs or 0 if cell is not a LUT.
     */
    public static int getLUTSize(Cell c) {
        return getLUTSize(c.getEDIFCellInst());
    }

    /**
     * If the provided cell instance is a LUT, it will return the LUT input
     * count.  If it is not a LUT, it will return 0.
     * @param c The LUT cell
     * @return The number of LUT inputs or 0 if cell is not a LUT.
     */
    public static int getLUTSize(EDIFCellInst c) {
        if (!isCellALUT(c)) return 0;
        return Character.getNumericValue(c.getCellType().getName().charAt(3));
    }

    /**
     * Extracts the length value from the INIT String (16, in 16'hA8A2)
     * @param init The init string.
     * @return The init length.
     */
    public static int initLength(String init) {
        int idx = init.indexOf('\'');
        if (idx == -1) throw new RuntimeException("ERROR: Bad " + LUT_INIT +
                                " String \"" + init +"\", missing \'");
        return Integer.parseInt(init.substring(0, idx));
    }

    /**
     * Performs essentially a log_2 operation on the init string length
     * to get the LUT size.
     * @param init The LUT's init string.
     * @return Size of the LUT by number of inputs (1-6).
     */
    public static int getLUTSize(String init) {
        int lutSize = 0;
        int initLength = initLength(init);
        switch (initLength) {
        case 2:
            lutSize = 1;
            break;
        case 4:
            lutSize = 2;
            break;
        case 8:
            lutSize = 3;
            break;
        case 16:
            lutSize = 4;
            break;
        case 32:
            lutSize = 5;
            break;
        case 64:
            lutSize = 6;
            break;
        default:
            throw new RuntimeException("ERROR: Unsupported LUT Size/INIT value " + init);
        }
        return lutSize;
    }

    /**
     * Prints out the truth table for the given INIT string on the cell. If the
     * cell is not a LUT, it prints nothing.
     * @param c The Cell in question.
     */
    public static void printTruthTable(Cell c) {
        printTruthTable(c.getEDIFCellInst());
    }

    /**
     * Prints out the truth table for the given INIT string on the cell. If the
     * cell is not a LUT, it prints nothing.
     * @param c The cell instance in question.
     */
    public static void printTruthTable(EDIFCellInst c) {
        if (!isCellALUT(c)) return;
        String init = c.getProperty(LUT_INIT).getValue();
        int lutSize = getLUTSize(init);
        int initLength = initLength(init);
        long initValue = getInitValue(init);

        for (int i=lutSize-1; i >= 0; i--) {
            System.out.print("I" + i + " ");
        }
        System.out.println("| " + getLUTEquation(init));
        for (int i=0; i < initLength; i++) {
            for (int j=lutSize-1; j >= 0; j--) {
                System.out.print(" " + getBit(i,j) + " ");
            }
            System.out.println("| " + getBit(initValue,i));
        }
    }

    /**
     * Converts the Verilog-syntax number literal for the LUT initialization
     * to a primitive long integer.
     * @param init The LUT INIT string.
     * @return
     */
    public static long getInitValue(String init) {
        int idx = init.indexOf('\'');
        if (idx == -1) throw new RuntimeException("ERROR: Bad " + LUT_INIT +
                                " String \"" + init +"\", missing \'");
        String radix = init.substring(idx+1, idx+2).toLowerCase();
        if (radix.equals("h")) return Long.parseUnsignedLong(init.substring(idx+2), 16);
        if (radix.equals("d")) return Long.parseUnsignedLong(init.substring(idx+2), 10);
        if (radix.equals("o")) return Long.parseUnsignedLong(init.substring(idx+2), 8);
        if (radix.equals("b")) return Long.parseUnsignedLong(init.substring(idx+2), 2);

        throw new RuntimeException("Unsupported radix '" + radix + "' in " +
                    LUT_INIT + " string \'" + init + "'");
    }

    public static long setBit(long value, int bitIndex) {
        return value | (1L << bitIndex);
    }

    public static int getBit(int value, int bitIndex) {
        return (value >> bitIndex) & 0x1;
    }

    public static int getBit(long value, int bitIndex) {
        return (int)(value >> bitIndex) & 0x1;
    }

    /**
     * Evaluates the provided equation in order to create the INIT string
     * of a LUT of the given size. Equation syntax:
     * {@code
     * Operators:
     *   XOR: @, ^
     *   AND: &, *, .
     *    OR: |, +
     *   NOT: ~, !
     *
     * A Few Examples:
     *   O = 0
     *   O = 1
     *   O = (I0 & I1) + (I2 & I3)
     *   O = I0 & !I1 & !I3
     *
     * }
     *
     * NOTE: XOR and AND have the same precedence and are evaluated from left
     * to right. Vivado uses different rules for handling operators of the
     * same precedence, so be sure to use parentheses to avoid any ambiguity!
     *
     *
     * @param equation A Vivado LUT Equation String.  This follows the same
     * conventions as the Vivado LUT equation string editor.
     * @param lutSize LUT size (input count), supported sizes are: 1,2,3,4,5,6
     * @return The INIT string to program the LUT of the provided size
     */
    public static String getLUTInitFromEquation(String equation, int lutSize) {
        int length = 1 << lutSize;
        long init = 0;
        LUTEquationEvaluator b = new LUTEquationEvaluator(equation);
        for (int i=0; i < length; i++) {
            boolean result = b.eval(i);
            if (result) init = setBit(init,i);
        }
        return length + "'h" + Long.toUnsignedString(init, 16).toUpperCase();
    }

    /**
     * Programs a LUT cell's init string from the provided equation.
     * @param c The LUT cell to program.
     * @param equation The desired programming of the LUT using Vivado LUT equation syntax.
     * @return The previous LUT init string or null if none.
     */
    public static EDIFPropertyValue configureLUT(Cell c, String equation) {
        int size = getLUTSize(c);
        if (size == 0) throw new RuntimeException("ERROR: Cell " + c.getName() + " is not a LUT");
        String init = getLUTInitFromEquation(equation, size);
        return c.addProperty(LUT_INIT, init);
    }

    /**
     * Programs a LUT cell's init string from the provided equation.
     * @param c The LUT cell instance to program.
     * @param equation The desired programming of the LUT using Vivado LUT equation syntax.
     * @return The previous LUT init string or null if none.
     */
    public static EDIFPropertyValue configureLUT(EDIFCellInst c, String equation) {
        int size = getLUTSize(c);
        if (size == 0) throw new RuntimeException("ERROR: Cell " + c.getName() + " is not a LUT");
        String init = getLUTInitFromEquation(equation, size);
        return c.addProperty(LUT_INIT, init);
    }

    /**
     * Reads the init string in this LUT and creates an equivalent (non-optimal) equation.
     * @param c The LUT instance
     * @return The equation following LUT equation syntax or null if cell is not configured.
     */
    public static String getLUTEquation(Cell c) {
        if (c.isRoutethru()) {
            Set<Entry<String, String>> entrySet = c.getPinMappingsP2L().entrySet();
            assert (entrySet.size() == 1);
            return "O" + c.getBELName().charAt(1) + "=" + entrySet.iterator().next().getKey();
        }
        return getLUTEquation(c.getEDIFCellInst());
    }

    /**
     * Reads the init string in this LUT and creates an equivalent (non-optimal) equation.
     * @param i The LUT instance
     * @return The equation following LUT equation syntax or null if cell is not configured.
     */
    public static String getLUTEquation(EDIFCellInst i) {
        String init = i.getProperty(LUT_INIT).getValue();
        if (init == null) return null;
        return getLUTEquation(init);
    }


    /**
     * Creates a (dumb, non-reduced) boolean logic equation compatible
     * with Vivado's LUT Equation Editor from an INIT string.
     * TODO - Enhance with a logic minimization method such as Quine-McCluskey method.
     * @param init The existing INIT string configuring a LUT.
     * @return A Vivado LUT Equation Editor compatible equation.
     */
    public static String getLUTEquation(String init) {
        long value = getInitValue(init);
        int length = initLength(init);
        int lutSize = getLUTSize(init);
        StringBuilder sb = new StringBuilder("O=");
        int termCount = 0;
        for (int i=0; i < length; i++) {
            if (getBit(value,i) == 1) {
                if (termCount > 0) sb.append(" + ");
                for (int j=lutSize-1; j >= 0; j--) {
                    sb.append(getBit(i,j) == 1 ? "" : "!");
                    sb.append("I" + j);
                    if (j > 0) sb.append(" & ");
                }
                termCount++;
            }
        }
        if (termCount==0) return "O=0";
        if (termCount==length) return "O=1";
        return sb.toString();
    }


    private static List<Map<String,String>> tests;

    static{
        tests = new ArrayList<>();
        for (int i=0; i < MAX_LUT_SIZE; i++) {
            int lutSize = i+1;
            tests.add(new LinkedHashMap<>());
            Map<String,String> test = tests.get(i);
            long lutInitLength = 1L << (i+1);
            test.put("O=0", lutInitLength + "'h0");
            long initValue = lutInitLength == 64 ? -1L : (1L << (lutInitLength))-1;
            test.put("O=1", lutInitLength + "'h" + Long.toUnsignedString(initValue,16).toUpperCase());

            if (lutSize > 1) {
                char[] repeatTemplate = new char[(int) (lutInitLength >> 2)];
                test.put("O=I0 "+LUTEquationEvaluator.XOR+ " I1", lutInitLength + "'h" + new String(repeatTemplate).replace("\0", "6"));
                test.put("O=I0 "+LUTEquationEvaluator.XOR2+" I1", lutInitLength + "'h" + new String(repeatTemplate).replace("\0", "6"));
            }
            if (lutSize == 3)
                test.put("O=!I2 & I1 & !I0 + !I2 & I1 & I0 + I2 & !I1 & I0 + I2 & I1 & I0", "8'hAC");
            if (lutSize == 6) {
                test.put("O=!I0 + ~I1 * (I2 ^ (I3 & I4 . I5))","64'h5775757575757575");
                test.put("O=I0 & I1 & !I3 & !I4 + !I1 & I2 & !I3 & !I4 + I3 & I5 + !I3 & I4 & I5","64'hFFFFFFB8000000B8");
                test.put("O=!(I0 + ~I1) @ (I2 ^ I3) + (I4 . I5)","64'hFFFF4BB44BB44BB4");
                test.put("O=I0 ^ (I1 & I2)", "64'h6A6A6A6A6A6A6A6A");
                test.put("O=I0 & (I1 ^ I2)", "64'h2828282828282828");
                test.put("O=I0 ^ I1 + I2 ^ I3", "64'h6FF66FF66FF66FF6");
                test.put("O=(I0 & I1) ^ (I2 & I3)", "64'h7888788878887888");
            }

        }
    }

    /**
     * Given a mapping from old SitePinInsts to new site pin name, update all state
     * necessary to reflect these LUT pin swaps. This includes updating cells'
     * logical-to-physical pin mappings, updating intra-site routing, moving
     * the SitePinInst objects, etc.
     * @param oldPinToNewPins Mapping from old pins to new pins.
     */
    public static int swapMultipleLutPins(Map<SitePinInst, String> oldPinToNewPins) {
        Map<String,Map<String,PinSwap>> pinSwaps = new HashMap<>();

        for (Map.Entry<SitePinInst, String> e : oldPinToNewPins.entrySet()) {
            SitePinInst oldSinkSpi = e.getKey();
            String newSitePinName = e.getValue();

            if (oldSinkSpi.getName().equals(newSitePinName)) {
                continue;
            }

            SiteInst si = oldSinkSpi.getSiteInst();
            if (!SitePinInst.isLUTInputPin(si, newSitePinName)) {
                continue;
            }

            Set<Cell> cells = DesignTools.getConnectedCells(oldSinkSpi);
            if (cells.isEmpty()) {
                continue;
            }
            PinSwap ps = null;
            for (Cell cell : cells) {
                BEL bel = cell.getBEL();
                if (!bel.isLUT()) {
                    continue;
                }

                // Only consider LUT cell or a routethru ...
                if (!cell.getType().startsWith("LUT") && !cell.isRoutethru() &&
                        // ... or distributed RAM cells not on a "H" BEL
                        (!cell.getType().startsWith("RAM") || bel.getName().startsWith("H"))) {
                    // SRL cells do not support pin swapping
                    assert(cell.getType().startsWith("SRL"));
                    continue;
                }

                String oldPhysicalPinName = "A" + oldSinkSpi.getName().charAt(1);
                String oldLogicalPinName = cell.getLogicalPinMapping(oldPhysicalPinName);
                if (ps == null) {
                    String newPhysicalPinName = "A" + newSitePinName.charAt(1);
                    String depopulatedLogicalPinName = cell.getLogicalPinMapping(newPhysicalPinName);
                    ps = new PinSwap(cell, oldLogicalPinName, oldPhysicalPinName,
                            newPhysicalPinName, depopulatedLogicalPinName, newSitePinName);

                    String siteAndLut = cell.getSiteName() + "/" + bel.getName().charAt(0);
                    String oldToNewPhysicalPin = oldPhysicalPinName + ">" + newPhysicalPinName;
                    pinSwaps.computeIfAbsent(siteAndLut, (k) -> new HashMap<>())
                            .put(oldToNewPhysicalPin, ps);
                } else {
                    if (bel.getName().charAt(1) == '6') {
                        // Unpredictable set ordering can mean that x5LUT appears before x6LUT; swap them back here
                        assert(ps.getCell().getBELName().charAt(1) == '5');
                        ps.setCompanionCell(ps.getCell(), ps.getLogicalName());

                        ps.setCell(cell);
                        String newPhysicalPinName = "A" + newSitePinName.charAt(1);
                        ps.setLogicalName(oldLogicalPinName);
                        assert(ps.getOldPhysicalName().equals(oldPhysicalPinName));
                        assert(ps.getNewPhysicalName().equals(newPhysicalPinName));
                        String depopulatedLogicalPinName = cell.getLogicalPinMapping(newPhysicalPinName);
                        ps.setDepopulatedLogicalName(depopulatedLogicalPinName);
                        assert(ps.getNewNetPinName().equals(newSitePinName));
                    } else {
                        assert(bel.getName().charAt(1) == '5');
                        ps.setCompanionCell(cell, oldLogicalPinName);
                    }
                }
            }
        }

        // Make all pin swaps per LUT site simultaneously
        int numPinSwaps = 0;
        for (Map.Entry<String,Map<String,PinSwap>> e : pinSwaps.entrySet()) {
            Collection<PinSwap> swaps = e.getValue().values();
            swapSingleLutPins(e.getKey(), swaps);
            numPinSwaps += swaps.size();
        }

        return numPinSwaps;
    }

    /**
     * For each pair of LUT sites (5LUT/6LUT), perform pin swapping.
     * @param key The name of the site and letter of LUT pair (ex: SLICE_X54Y44/D)
     * @param pinSwaps The list of pin swaps to be performed on the pair of LUT sites
     */
    public static void swapSingleLutPins(String key, Collection<PinSwap> pinSwaps) {
        Collection<PinSwap> copyOnWritePinSwaps = pinSwaps;
        LinkedHashMap<String,PinSwap> overwrittenPins = new LinkedHashMap<>();
        LinkedHashMap<String,PinSwap> emptySlots = new LinkedHashMap<>();
        for (PinSwap ps : copyOnWritePinSwaps) {
            overwrittenPins.put(ps.getNewPhysicalName(),ps);
            emptySlots.put(ps.getOldPhysicalName(),ps);
        }
        for (PinSwap ps : copyOnWritePinSwaps) {
            String oldPin = ps.getOldPhysicalName();
            String newPin = ps.getNewPhysicalName();
            if (emptySlots.containsKey(newPin) && overwrittenPins.containsKey(newPin)) {
                overwrittenPins.remove(newPin);
                emptySlots.remove(newPin);
            }
            if (emptySlots.containsKey(oldPin) && overwrittenPins.containsKey(oldPin)) {
                overwrittenPins.remove(oldPin);
                emptySlots.remove(oldPin);
            }
        }

        if (overwrittenPins.size() != emptySlots.size()) {
            throw new RuntimeException("ERROR: Couldn't identify proper pin swap for BEL(s) " + key + "LUT");
        }
        String[] oPins = overwrittenPins.keySet().toArray(new String[overwrittenPins.size()]);
        String[] ePins = emptySlots.keySet().toArray(new String[emptySlots.size()]);
        for (int i=0; i < oPins.length; i++) {
            String oldPhysicalPin = oPins[i];
            String newPhysicalPin = ePins[i];
            Cell c = emptySlots.get(newPhysicalPin).getCell();
            String newNetPinName = c.getSiteWireNameFromPhysicalPin(newPhysicalPin);
            // Handles special cases
            if (c.getLogicalPinMapping(oldPhysicalPin) == null) {
                Cell neighborLUT = emptySlots.get(newPhysicalPin).checkForCompanionCell();
                if (neighborLUT != null && emptySlots.get(newPhysicalPin).getCompanionCell() == null) {
                    String neighborLogicalPinMapping = neighborLUT.getLogicalPinMapping(oldPhysicalPin);
                    // Makes sure if both LUT sites are occupied, that pin movements
                    // are lock-step
                    if (neighborLogicalPinMapping != null) {
                        PinSwap ps = new PinSwap(neighborLUT, neighborLUT.getLogicalPinMapping(oldPhysicalPin),oldPhysicalPin,newPhysicalPin,
                                neighborLUT.getLogicalPinMapping(newPhysicalPin),newNetPinName);

                        if (copyOnWritePinSwaps == pinSwaps) {
                            copyOnWritePinSwaps = new ArrayList<>(pinSwaps);
                        }
                        copyOnWritePinSwaps.add(ps);
                        continue;
                    }
                }
                continue;
            }
            // Make implicit swaps when one of the pins is not being routed
            // or is unconnected for one or both of the cells
            PinSwap ps = new PinSwap(c, c.getLogicalPinMapping(oldPhysicalPin),oldPhysicalPin,newPhysicalPin,
                    c.getLogicalPinMapping(newPhysicalPin),newNetPinName);
            Cell neighborLUT = ps.checkForCompanionCell();
            if (neighborLUT != null) {
                if (neighborLUT.getLogicalPinMapping(oldPhysicalPin) != null) {
                    ps.setCompanionCell(neighborLUT, neighborLUT.getLogicalPinMapping(oldPhysicalPin));
                }
            }
            if (copyOnWritePinSwaps == pinSwaps) {
                copyOnWritePinSwaps = new ArrayList<>(pinSwaps);
            }
            copyOnWritePinSwaps.add(ps);
        }

        // Prepares pins for swapping by removing them
        Queue<SitePinInst> q = new LinkedList<>();
        for (PinSwap ps : copyOnWritePinSwaps) {
            Cell cell = ps.getCell();
            String oldSitePinName = cell.getSiteWireNameFromPhysicalPin(ps.getOldPhysicalName());
            SiteInst si = cell.getSiteInst();
            SitePinInst pinToMove = si.getSitePinInst(oldSitePinName);
            q.add(pinToMove);
            if (pinToMove == null) {
                continue;
            }
            pinToMove.setSiteInst(null,true);
            // Removes pin mappings to prepare for new pin mappings
            cell.removePinMapping(ps.getOldPhysicalName());
            if (ps.getCompanionCell() != null) {
                ps.getCompanionCell().removePinMapping(ps.getOldPhysicalName());
            }
        }

        assert(q.size() == copyOnWritePinSwaps.size());

        // Perform the actual swap on cell pin mappings
        for (PinSwap ps : copyOnWritePinSwaps) {
            Cell cell = ps.getCell();
            cell.addPinMapping(ps.getNewPhysicalName(), ps.getLogicalName());
            if (cell.isRoutethru() && cell.hasAltPinMappings()) {
                Map<String, AltPinMapping> altPinMappings = cell.getAltPinMappings();
                assert(altPinMappings.size() == 1);
                AltPinMapping apm = altPinMappings.remove(ps.getOldPhysicalName());
                assert(apm != null);
                cell.addAltPinMapping(ps.getNewPhysicalName(), apm);
            }
            if (ps.getCompanionCell() != null) {
                ps.getCompanionCell().addPinMapping(ps.getNewPhysicalName(), ps.getCompanionLogicalName());
            }
            SitePinInst pinToMove = q.poll();
            if (pinToMove == null) {
                continue;
            }
            pinToMove.setPinName(ps.getNewNetPinName());
            pinToMove.setSiteInst(cell.getSiteInst());
        }

        assert(q.isEmpty());
    }

    /**
     * Analyze the routing PIPs of a design to identify (based on whether a PIP existing
     * on a Net drives the expected SitePin based on Net.getPins()) and perform any necessary
     * LUT pin swapping.
     * @param design Design object to analyze and fix.
     * @return Number of pin swaps processed.
     */
    public static int swapLutPinsFromPIPs(Design design) {
        Map<SitePinInst, String> oldPinToNewPins = new HashMap<>();
        Map<Site, List<SitePinInst>> siteToLutSpis = new HashMap<>();
        List<SitePin> unmatchedSitePins = new ArrayList<>();
        Set<SitePin> routethruSitePins = new HashSet<>();
        for (Net net : design.getNets()) {
            if (net.isClockNet()) {
                continue;
            }
            if (net.isVCCNet()) {
                // All LUT inputs have a direct PIP to VCC
                continue;
            }
            if (net.isGNDNet()) {
                // TODO:
                continue;
            }
            if (!net.hasPIPs()) {
                continue;
            }

            for (SitePinInst spi : net.getPins()) {
                if (spi.isOutPin() || !spi.isLUTInputPin()) {
                    continue;
                }
                assert(spi.getSiteInst().getSitePinInst(spi.getName()) == spi);
                siteToLutSpis.computeIfAbsent(spi.getSite(), (k) -> new ArrayList<>(1))
                        .add(spi);
            }

            for (PIP pip : net.getPIPs()) {
                if (pip.isRouteThru()) {
                    Node startNode = pip.getStartNode();
                    SitePin newSitePin = startNode.getSitePin();
                    assert(newSitePin != null);
                    routethruSitePins.add(newSitePin);
                    continue;
                }

                Node endNode = pip.getEndNode();
                SitePin newSitePin = (endNode != null) ? endNode.getSitePin() : null;
                if (newSitePin == null) {
                    continue;
                }

                Site site = newSitePin.getSite();
                List<SitePinInst> lutSpis = siteToLutSpis.get(site);
                if (lutSpis == null) {
                    // No sink pins from this net exist on this site
                    // (e.g. this pin is used as routethru)
                    continue;
                }
                SiteInst si = design.getSiteInstFromSite(site);
                assert(si != null);

                String newSitePinName = newSitePin.getPinName();
                if (!SitePinInst.isLUTInputPin(si, newSitePinName)) {
                    continue;
                }
                SitePinInst newSpi = si.getSitePinInst(newSitePinName);
                List<SitePinInst> spis = siteToLutSpis.get(site);
                if (spis == null) {
                    System.out.println("WARNING: SitePin " + newSitePin + " visited by PIP " + pip +
                            " is not a SitePinInst on net " + net + ". Ignoring.");
                } else if (!spis.remove(newSpi)) {
                    // spi is not already on this net -- could require pin swapping,
                    // or could be a routethru
                    unmatchedSitePins.add(newSitePin);
                }
            }

            for (SitePin newSitePin : unmatchedSitePins) {
                if (routethruSitePins.contains(newSitePin)) {
                    // Pin is part of a routethru, ignore it
                    continue;
                }
                Site site = newSitePin.getSite();
                List<SitePinInst> unmatchedSpis = siteToLutSpis.get(site);
                Iterator<SitePinInst> it = unmatchedSpis.iterator();
                assert(it.hasNext());
                String newSitePinName = newSitePin.getPinName();
                char lutLetter = newSitePinName.charAt(0);
                // Assume that unmatchedSpis is generally a small ArrayList
                // such that O(N) operations are not unwieldy
                boolean found = false;
                while (it.hasNext()) {
                    SitePinInst oldSpi = it.next();
                    if (oldSpi.getName().charAt(0) != lutLetter) {
                        continue;
                    }
                    oldPinToNewPins.put(oldSpi, newSitePinName);
                    it.remove();
                    found = true;
                    break;
                }
                assert(found);
            }

            siteToLutSpis.clear();
            unmatchedSitePins.clear();
            routethruSitePins.clear();
        }

        return swapMultipleLutPins(oldPinToNewPins);
    }

    public static void main(String[] args) {
        Design d = new Design("test_design",Device.PYNQ_Z1);

        for (int k=1; k <= 6; k++) {
            String name = "fred_" + k;
            Cell c = d.createCell(name, Design.getUnisimCell(Unisim.valueOf("LUT" + k)));
            EDIFCellInst i = c.getEDIFCellInst();
            System.out.println("==== LUT" + k + " TESTS ====");
            Map<String,String> lutTests = tests.get(k-1);
            for (Entry<String,String> e : lutTests.entrySet()) {
                String equation = e.getKey();
                String init = e.getValue();
                i.addProperty(LUT_INIT, init);
                String altInit = getLUTInitFromEquation(equation, k);
                System.out.println((init.equals(altInit) ? "PASS" : "FAIL") + "ED:" + init);
                if (!init.equals(altInit)) {
                    System.out.println("  FAILED INIT: " + altInit);
                }
                String altEq = getLUTEquation(init);
                altInit = getLUTInitFromEquation(altEq, k);
                System.out.println((init.equals(altInit) ? "PASS" : "FAIL") + "ED:" + altEq);
                if (!init.equals(altInit)) {
                    System.out.println("  FAILED INIT: " + altInit);
                }
            }
        }
    }
}

/*
 *
 * Copyright (c) 2025, Advanced Micro Devices, Inc.
 * All rights reserved.
 *
 * Author: Andrew Butt, AMD Advanced Research and Development.
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

import com.xilinx.rapidwright.design.Cell;
import com.xilinx.rapidwright.design.Design;
import com.xilinx.rapidwright.edif.EDIFCellInst;
import com.xilinx.rapidwright.edif.EDIFPropertyValue;

import joptsimple.OptionParser;
import joptsimple.OptionSet;

import java.io.IOException;
import java.math.BigInteger;
import java.util.Arrays;

/**
 * Utility methods for updating flip-flop initial values after place and route.
 * This is useful to change the INIT value of registers without re-running implementation.
 */
public class RegisterInitTools {

    private static final String INIT_PROPERTY = "INIT";

    private static Boolean getInitValue(Cell cell) {
        if (cell == null) return null;
        EDIFCellInst cellInst = cell.getEDIFCellInst();
        if (cellInst == null) return null;
        EDIFPropertyValue prop = cellInst.getProperty(INIT_PROPERTY);
        if (prop == null) return null;
        String value = prop.getValue();
        if (value.contains("1'b1")) return true;
        if (value.contains("1'b0")) return false;
        return null;
    }

    private static void setInitValue(Cell cell, boolean initValue) {
        if (cell == null) {
            throw new IllegalArgumentException("Cell cannot be null");
        }
        EDIFCellInst cellInst = cell.getEDIFCellInst();
        if (cellInst == null) {
            throw new IllegalArgumentException("Cell has no EDIFCellInst");
        }
        String initString = initValue ? "1'b1" : "1'b0";
        cellInst.addProperty(INIT_PROPERTY, initString);
    }

    /**
     * Updates the INIT values of a multi-bit register given a long value.
     * Assumes register bits are named with bus notation, e.g., "my_reg_reg[0]", "my_reg_reg[1]", etc.
     *
     * @param design The design containing the register
     * @param registerBaseName The base name of the register without bus index (e.g., "my_reg_reg")
     * @param value The new value to set
     * @param width The width of the register in bits
     * @return The number of flip-flops updated
     */
    public static int setRegisterValue(Design design, String registerBaseName, long value, int width) {
        return setRegisterValue(design, registerBaseName, BigInteger.valueOf(value), width);
    }

    /**
     * Updates the INIT values of a multi-bit register given a BigInteger value.
     * Assumes register bits are named with bus notation, e.g., "my_reg_reg[0]", "my_reg_reg[1]", etc.
     *
     * @param design The design containing the register
     * @param registerBaseName The base name of the register without bus index (e.g., "my_reg_reg")
     * @param value The new value to set
     * @param width The width of the register in bits
     * @return The number of flip-flops updated
     */
    public static int setRegisterValue(Design design, String registerBaseName, BigInteger value, int width) {
        int updatedCount = 0;
        for (int i = 0; i < width; i++) {
            String cellName = registerBaseName + "[" + i + "]";
            Cell cell = design.getCell(cellName);
            if (cell == null) {
                throw new RuntimeException("Cell not found: " + cellName);
            }
            if (cell.getBEL() == null) {
                throw new RuntimeException("Cell is not placed: " + cellName);
            }
            if (!cell.getBEL().isFF()) {
                throw new RuntimeException("Cell is not a flip-flop: " + cellName);
            }
            setInitValue(cell, value.testBit(i));
            updatedCount++;
        }
        return updatedCount;
    }

    /**
     * Reads the current value of a multi-bit register.
     *
     * @param design The design containing the register
     * @param registerBaseName The base name of the register without bus index
     * @param width The width of the register in bits
     * @return The current value as a BigInteger, or null if register not found
     */
    public static BigInteger getRegisterValue(Design design, String registerBaseName, int width) {
        BigInteger value = BigInteger.ZERO;
        for (int i = 0; i < width; i++) {
            String cellName = registerBaseName + "[" + i + "]";
            Cell cell = design.getCell(cellName);
            if (cell == null) {
                throw new RuntimeException("Cell not found: " + cellName);
            }
            if (cell.getBEL() == null) {
                throw new RuntimeException("Cell is not placed: " + cellName);
            }
            if (!cell.getBEL().isFF()) {
                throw new RuntimeException("Cell is not a flip-flop: " + cellName);
            }
            Boolean bitValue = getInitValue(cell);
            if (bitValue != null && bitValue) {
                value = value.setBit(i);
            }
        }
        return value;
    }

    // Command-line option names
    private static final String INPUT_DCP_OPT = "input";
    private static final String OUTPUT_DCP_OPT = "output";
    private static final String REGISTER_OPT = "register";
    private static final String WIDTH_OPT = "width";
    private static final String VALUE_OPT = "value";
    private static final String READ_OPT = "read";
    private static final String HELP_OPT = "help";

    private static OptionParser createOptionParser() {
        OptionParser p = new OptionParser() {{
            accepts(INPUT_DCP_OPT, "Input DCP file").withRequiredArg().required();
            accepts(OUTPUT_DCP_OPT, "Output DCP file").withRequiredArg();
            accepts(REGISTER_OPT, "Register base name (e.g., 'my_reg_reg')").withRequiredArg().required();
            accepts(WIDTH_OPT, "Register width in bits").withRequiredArg().ofType(Integer.class).required();
            accepts(VALUE_OPT, "New value (decimal or 0x hex)").withRequiredArg();
            accepts(READ_OPT, "Read and print current value only (no modification)");
            acceptsAll(Arrays.asList(HELP_OPT, "?"), "Print help").forHelp();
        }};
        return p;
    }

    private static void printHelp(OptionParser p) {
        System.out.println("RegisterInitTools - Update flip-flop initial values after place and route");
        System.out.println();
        System.out.println("Usage:");
        System.out.println("  Read:  RegisterInitTools -input <dcp> -register <name> -width <n> -read");
        System.out.println("  Write: RegisterInitTools -input <dcp> -output <dcp> -register <name> -width <n> -value <val>");
        System.out.println();
        try {
            p.printHelpOn(System.out);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public static void main(String[] args) {
        OptionParser p = createOptionParser();

        if (args.length == 0) {
            printHelp(p);
            return;
        }

        OptionSet opts;
        try {
            opts = p.parse(args);
        } catch (Exception e) {
            System.err.println("Error: " + e.getMessage());
            printHelp(p);
            return;
        }

        if (opts.has(HELP_OPT)) {
            printHelp(p);
            return;
        }

        String inputDcp = (String) opts.valueOf(INPUT_DCP_OPT);
        String registerName = (String) opts.valueOf(REGISTER_OPT);
        int width = (int) opts.valueOf(WIDTH_OPT);
        boolean readOnly = opts.has(READ_OPT);

        if (!readOnly) {
            if (!opts.has(VALUE_OPT)) {
                System.err.println("Error: -value is required unless -read is specified");
                return;
            }
            if (!opts.has(OUTPUT_DCP_OPT)) {
                System.err.println("Error: -output is required unless -read is specified");
                return;
            }
        }

        System.out.println("Loading design: " + inputDcp);
        Design design = Design.readCheckpoint(inputDcp);

        BigInteger currentValue = getRegisterValue(design, registerName, width);
        if (currentValue != null) {
            System.out.println("Current value: 0x" + currentValue.toString(16) + " (" + currentValue + ")");
        } else {
            System.err.println("Warning: Could not read register. Check register name and width.");
        }

        if (readOnly) {
            return;
        }

        String valueStr = (String) opts.valueOf(VALUE_OPT);
        BigInteger value;
        try {
            if (valueStr.startsWith("0x") || valueStr.startsWith("0X")) {
                value = new BigInteger(valueStr.substring(2), 16);
            } else {
                value = new BigInteger(valueStr);
            }
        } catch (NumberFormatException e) {
            System.err.println("Error: Invalid value format: " + valueStr);
            return;
        }

        int updated = setRegisterValue(design, registerName, value, width);
        System.out.println("Updated " + updated + " flip-flops");

        if (updated == 0) {
            System.err.println("Warning: No flip-flops were updated. Check register name and width.");
        } else if (updated < width) {
            System.err.println("Warning: Only " + updated + " of " + width + " bits were found.");
        }

        BigInteger newValue = getRegisterValue(design, registerName, width);
        if (newValue != null) {
            System.out.println("New value: 0x" + newValue.toString(16) + " (" + newValue + ")");
        }

        String outputDcp = (String) opts.valueOf(OUTPUT_DCP_OPT);
        System.out.println("Writing design: " + outputDcp);
        design.writeCheckpoint(outputDcp);
        System.out.println("Done.");
    }
}

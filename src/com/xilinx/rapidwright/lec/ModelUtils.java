/*
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

package com.xilinx.rapidwright.lec;

import com.xilinx.rapidwright.edif.EDIFCellInst;
import com.xilinx.rapidwright.edif.EDIFPropertyValue;

/**
 * Shared helpers for the primitive models: reading configuration properties and
 * resolving pin names to port bits.
 */
public class ModelUtils {

    private ModelUtils() {
    }

    /**
     * Reads a configuration property.
     *
     * @return The property's value, or the default if the instance does not
     *         carry it.
     */
    public static String property(EDIFCellInst inst, String key, String defaultValue) {
        if (inst == null) {
            return defaultValue;
        }
        EDIFPropertyValue value = inst.getProperty(key);
        if (value == null || value.getValue() == null) {
            return defaultValue;
        }
        return value.getValue();
    }

    /**
     * Reads a property that carries a single bit, in any of the several
     * spellings a netlist may use for one.
     *
     * This has to be liberal. Pin inversion attributes such as
     * {@code IS_D_INVERTED} are written as Verilog sized literals,
     * {@code 1'b1} and {@code 1'b0}. Lookahead attributes such as
     * {@code LOOKB} are declared as native booleans in the primitive library
     * but appear as the strings {@code "TRUE"} and {@code "FALSE"} in real
     * netlists. Failing to recognise any one of these spellings would silently
     * drop the attribute, and for an inversion that means computing the
     * complement of the intended function while reporting nothing.
     */
    public static boolean booleanProperty(EDIFCellInst inst, String key, boolean defaultValue) {
        String raw = property(inst, key, null);
        if (raw == null) {
            return defaultValue;
        }
        String value = raw.trim();
        if (value.equalsIgnoreCase("TRUE") || value.equalsIgnoreCase("yes")) {
            return true;
        }
        if (value.equalsIgnoreCase("FALSE") || value.equalsIgnoreCase("no")) {
            return false;
        }
        Long bits = parseLiteral(value);
        if (bits == null) {
            // The default is only for an absent property; guessing a supplied
            // one could build the same function from two different settings.
            throw new LecException("Unreadable " + key + " value '" + raw + "' on "
                    + inst.getName());
        }
        return bits != 0;
    }

    /**
     * Reads a single bit attribute value, such as a register's {@code INIT} of
     * {@code 1'b1}.
     *
     * @param raw  The attribute value.
     * @param what What it is, for the error message.
     * @return The bit.
     */
    static boolean parseBit(String raw, String what) {
        Long bits = raw == null ? null : parseLiteral(raw.trim());
        if (bits == null) {
            throw new LecException("Unreadable " + what + " value '" + raw + "'");
        }
        return bits != 0;
    }

    /**
     * Parses a Verilog sized literal such as {@code 4'b0010}, or plain decimal
     * digits.
     *
     * @return The value, or null if it cannot be read.
     */
    private static Long parseLiteral(String value) {
        int quote = value.indexOf('\'');
        if (quote >= 0 && quote + 2 <= value.length() - 1) {
            return parseSizedLiteral(value.substring(quote + 1));
        }
        return parseDigits(value);
    }

    /**
     * Interprets the part of a Verilog sized literal after the apostrophe, such
     * as the {@code b1} of {@code 1'b1}.
     */
    private static Long parseSizedLiteral(String tail) {
        int radix;
        switch (Character.toLowerCase(tail.charAt(0))) {
        case 'b':
            radix = 2;
            break;
        case 'o':
            radix = 8;
            break;
        case 'd':
            radix = 10;
            break;
        case 'h':
            radix = 16;
            break;
        default:
            return parseDigits(tail);
        }
        try {
            return Long.parseLong(tail.substring(1).trim(), radix);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static Long parseDigits(String value) {
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * @return True if the pin carries an inversion attribute set on this
     *         instance, as in {@code IS_SRI_INVERTED}.
     */
    public static boolean isPinInverted(EDIFCellInst inst, String pinName) {
        return booleanProperty(inst, "IS_" + pinName + "_INVERTED", false);
    }

    /**
     * @return True if one bit of a pin is inverted. A bus pin such as
     *         {@code ALUMODE} carries a per bit mask, as in
     *         {@code IS_ALUMODE_INVERTED = 4'b0010}, so only the bits set in the
     *         mask are inverted.
     */
    public static boolean isPinBitInverted(EDIFCellInst inst, String basePinName, int bit) {
        String raw = property(inst, "IS_" + basePinName + "_INVERTED", null);
        if (raw == null) {
            return false;
        }
        String value = raw.trim();
        if (value.equalsIgnoreCase("TRUE") || value.equalsIgnoreCase("FALSE")) {
            return bit == 0 && value.equalsIgnoreCase("TRUE");
        }
        Long mask = parseLiteral(value);
        if (mask == null) {
            throw new LecException("Unreadable IS_" + basePinName + "_INVERTED value '" + raw
                    + "' on " + inst.getName());
        }
        return bit < 64 && ((mask >>> bit) & 1L) != 0;
    }

    /**
     * Resolves a list of pin names to port bits, failing loudly rather than
     * silently dropping a pin the model believes exists.
     */
    public static int[] bitsOf(CellIndex index, String... pinNames) {
        int[] bits = new int[pinNames.length];
        for (int i = 0; i < pinNames.length; i++) {
            bits[i] = index.portBitIndexOfPinName(pinNames[i]);
            if (bits[i] < 0) {
                throw new LecException("Cell type " + index.getCell().getName()
                        + " has no pin '" + pinNames[i] + "'");
            }
        }
        return bits;
    }

    /**
     * @return The port bit of a pin, or -1 if the cell type does not have it.
     *         Used where a pin is genuinely optional across variants of a type.
     */
    public static int optionalBit(CellIndex index, String pinName) {
        return index.portBitIndexOfPinName(pinName);
    }

    /** @return The name of an output port bit, for error messages. */
    public static String pinName(CellIndex index, int portBit) {
        return index.getPortBitName(portBit);
    }
}

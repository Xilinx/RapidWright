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

/**
 * The frequencies of the output clocks of UltraScale/UltraScale+ GTH/GTY transceivers (UG576, UG578)
 * from their reference clock and attributes (Versal GT quads state theirs in CHn_*OUTCLK_FREQ
 * attributes, see ClockNetwork).
 */
final class GtClocks {

    private GtClocks() {
    }

    /** Whether a cell type is a GTH/GTY channel */
    static boolean isChannel(String type) {
        return type.matches("GT[HY]E[34]_CHANNEL");
    }

    /** Whether a cell type is a Versal GT quad (e.g. GTYP_QUAD), whose attributes give its clock frequencies */
    static boolean isQuad(String type) {
        return type.startsWith("GT") && type.endsWith("_QUAD");
    }

    /** Whether a cell type is a GTH/GTY common (QPLLs) */
    static boolean isCommon(String type) {
        return type.matches("GT[HY]E[34]_COMMON");
    }

    /**
     * Gets the reference clock input that a REFCLKSEL value selects.
     * @param sel The value of CPLLREFCLKSEL / QPLLnREFCLKSEL (1..7).
     * @param q The QPLL index ("0" or "1"), or "" for the CPLL of a channel.
     * @return The pin name, or null for other values.
     */
    static String refclkPinOf(int sel, String q) {
        switch (sel) {
            case 1:
                return "GTREFCLK0" + q;
            case 2:
                return "GTREFCLK1" + q;
            case 3:
                return "GTNORTHREFCLK0" + q;
            case 4:
                return "GTNORTHREFCLK1" + q;
            case 5:
                return "GTSOUTHREFCLK0" + q;
            case 6:
                return "GTSOUTHREFCLK1" + q;
            case 7:
                return "GTGREFCLK" + q;
            default:
                return null;
        }
    }

    /**
     * Checks if a reference clock on a pin feeds a PLL.
     * @param pin The reference clock input pin.
     * @param sel The PLL's tied REFCLKSEL value, -1 if not constant (then any matching REFCLK pin).
     * @param q The QPLL index ("0" or "1"), or "" for a CPLL.
     */
    static boolean refclkFeeds(String pin, int sel, String q) {
        if (sel < 0) {
            return pin.contains("REFCLK") && (q.isEmpty() || pin.endsWith(q));
        }
        String p = refclkPinOf(sel, q);
        return p != null && p.equals(pin);
    }

    /**
     * Gets the output frequency of a GT PLL: QPLL {@code fref*FBDIV/REFCLK_DIV} (halved at HALF rate),
     * CPLL {@code fref*FBDIV_45*FBDIV/REFCLK_DIV}.
     * @param fref The reference frequency, in GHz.
     * @param qpll Whether a QPLL (else a CPLL).
     * @param fbdiv QPLLn_FBDIV or CPLL_FBDIV (NaN: 4).
     * @param fbdiv45 CPLL_FBDIV_45 (NaN: 5).
     * @param refclkDiv The REFCLK_DIV (NaN or 0: 1).
     * @param full Whether QPLLnCLKOUT_RATE is FULL.
     * @return The frequency, in GHz.
     */
    static double pllFrequency(double fref, boolean qpll, double fbdiv, double fbdiv45, double refclkDiv,
                               boolean full) {
        double m = Double.isNaN(refclkDiv) || refclkDiv <= 0 ? 1 : refclkDiv;
        if (qpll) {
            return fref * fbdiv / m / (full ? 1 : 2);
        }
        return fref * (Double.isNaN(fbdiv45) ? 5 : fbdiv45) * (Double.isNaN(fbdiv) ? 4 : fbdiv) / m;
    }

    /**
     * Gets the frequency of a TXOUTCLK / RXOUTCLK: serial clock = PLL output*2/OUT_DIV; PMA (and PCS)
     * parallel clock = serial / internal width (2, 4 or 8 bytes by INT_DATAWIDTH, of 10 bits if
     * DATA_WIDTH is a multiple of 10, else 8); programmable divider clock = PLL output /
     * (PROGDIV_CFG*(PROGDIV_RATE[0] ? 1 : 2)); reference clock (/1, /2). OUTCLKSEL: 1, 2 PCS/PMA;
     * 3, 4 reference; 5 programmable divider; 0, 6, 7 no clock; -1 (not constant) the programmable
     * divider if powered, else the PMA clock.
     * @return The frequency in GHz, NaN if none or unknown.
     */
    static double outputFrequency(double fpll, double fref, double outDiv, double progCfg, double progRate,
                                  double dataWidth, double intWidth, int sel) {
        double serial = fpll * 2 / (Double.isNaN(outDiv) || outDiv <= 0 ? 1 : outDiv);
        double progdiv = progCfg > 0
                ? fpll / (progCfg * (!Double.isNaN(progRate) && ((long) progRate & 1) == 1 ? 1 : 2)) : Double.NaN;
        int bytes = Double.isNaN(intWidth) || intWidth == 0 ? 2 : intWidth == 1 ? 4 : 8;
        int bitsPerByte = !Double.isNaN(dataWidth) && ((int) dataWidth) % 10 == 0 ? 10 : 8;
        double pma = serial / (bytes * bitsPerByte);
        switch (sel) {
            case 1:
            case 2:
                return pma;
            case 3:
                return fref;
            case 4:
                return fref / 2;
            case 5:
                return progdiv;
            case 0:
            case 6:
            case 7:
                // Static 1 or reserved: no clock
                return Double.NaN;
            default:
                return !Double.isNaN(progdiv) ? progdiv : pma;
        }
    }
}

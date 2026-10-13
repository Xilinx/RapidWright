/*
 * Copyright (c) 2026, Advanced Micro Devices, Inc.
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

package com.xilinx.rapidwright.timing.sdf;

/**
 * Interpretation of an SDF {@code (TIMESCALE ...)} unit.
 *
 * The SDF standard allows a multiplier of 1, 10 or 100 followed by one of {@code s}, {@code ms},
 * {@code us}, {@code ns}, {@code ps} or {@code fs}. Vivado writes {@code 1ps}, but the full set is
 * accepted so that hand-written or third-party files are handled correctly rather than being
 * silently misinterpreted by a factor of a thousand.
 */
public class SdfTimeScale {

    private SdfTimeScale() {
    }

    /**
     * Returns the factor converting a value expressed in tenths of the given time scale unit into
     * picoseconds.
     *
     * @param timeScale The {@code TIMESCALE} value, e.g. {@code 1ps} or {@code 100ps}. Whitespace
     *                  between the multiplier and the unit is tolerated, as is any letter case.
     * @return Picoseconds per tenth of the given unit.
     * @throws SdfParseException If the time scale is malformed or uses an unknown unit.
     */
    public static double tenthsToPs(String timeScale) {
        return unitToPs(timeScale) / 10.0;
    }

    /**
     * Returns the number of picoseconds in one of the given time scale units.
     *
     * @param timeScale The {@code TIMESCALE} value, e.g. {@code 1ps}.
     * @return Picoseconds per unit.
     * @throws SdfParseException If the time scale is malformed or uses an unknown unit.
     */
    public static double unitToPs(String timeScale) {
        if (timeScale == null) {
            throw new SdfParseException("ERROR: missing SDF TIMESCALE");
        }
        String s = timeScale.trim();
        int i = 0;
        while (i < s.length() && s.charAt(i) >= '0' && s.charAt(i) <= '9') {
            i++;
        }
        int multiplier = 1;
        if (i > 0) {
            try {
                multiplier = Integer.parseInt(s.substring(0, i));
            } catch (NumberFormatException e) {
                throw new SdfParseException("ERROR: malformed SDF TIMESCALE: " + timeScale);
            }
            if (multiplier != 1 && multiplier != 10 && multiplier != 100) {
                throw new SdfParseException("ERROR: SDF TIMESCALE multiplier must be 1, 10 or 100,"
                        + " found: " + timeScale);
            }
        }
        String unit = s.substring(i).trim().toLowerCase();
        double psPerUnit;
        switch (unit) {
            case "fs": psPerUnit = 1e-3; break;
            case "ps": psPerUnit = 1.0; break;
            case "ns": psPerUnit = 1e3; break;
            case "us": psPerUnit = 1e6; break;
            case "ms": psPerUnit = 1e9; break;
            case "s": psPerUnit = 1e12; break;
            default:
                throw new SdfParseException("ERROR: unknown SDF TIMESCALE unit: " + timeScale);
        }
        return multiplier * psPerUnit;
    }
}

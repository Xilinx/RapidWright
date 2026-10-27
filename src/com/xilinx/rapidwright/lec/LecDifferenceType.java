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

/**
 * The kinds of finding the checker can report, in roughly decreasing severity.
 *
 * The split between a difference and an inconclusive result is deliberate. A
 * difference is a proof that the two netlists compute different logic. An
 * inconclusive result is an admission that nothing was proven, which must never
 * be presented as a pass.
 */
public enum LecDifferenceType {

    /** The functions driving a checked pin are provably different. */
    CONE_FUNCTION_DIFFERS("Cone function differs"),

    /**
     * A genuine combinational loop that the implementation has and the
     * reference does not, so the transform under test created it.
     */
    COMBINATIONAL_LOOP_INTRODUCED("Combinational loop introduced"),

    /**
     * A genuine combinational loop present in both netlists. This is an
     * error, but it is a property of the input rather than something
     * the transform did.
     */
    COMBINATIONAL_LOOP("Combinational loop (pre-existing in both)"),

    /** A combinational loop the reference has and the implementation does not. */
    COMBINATIONAL_LOOP_RESOLVED("Combinational loop resolved"),

    /** Matched terminals are of different primitive types. */
    TERMINAL_CELL_TYPE_DIFFERS("Terminal cell type differs"),

    /** Matched terminals disagree on a functional attribute. */
    TERMINAL_PROPERTY_DIFFERS("Terminal property differs"),

    /** A checked pin exists in the reference but not the implementation. */
    CONE_PIN_MISSING_IN_IMP("Checked pin missing in IMP"),

    /** A checked pin exists in the implementation but not the reference. */
    CONE_PIN_MISSING_IN_REF("Checked pin missing in REF"),

    /** A terminal exists in the reference but not the implementation. */
    TERMINAL_MISSING_IN_IMP("Terminal missing in IMP"),

    /** A terminal exists in the implementation but not the reference. */
    TERMINAL_MISSING_IN_REF("Terminal missing in REF"),

    /** The comparison could not be decided, so nothing was proven. */
    CONE_INCONCLUSIVE("Cone inconclusive");

    private final String description;

    LecDifferenceType(String description) {
        this.description = description;
    }

    public String getDescription() {
        return description;
    }

    /**
     * @return True if this finding is a proof that the netlists differ, as
     *         opposed to an admission that the check was not completed.
     */
    public boolean isProvenDifference() {
        return this != CONE_INCONCLUSIVE;
    }

    /**
     * @return True if this finding says a netlist is invalid, rather than that
     *         the two netlists disagree.
     *
     *         The distinction matters in the report. A combinational loop is a
     *         defect in a netlist that must be reported, but it is a
     *         property of the netlist containing it and says nothing about
     *         whether the two compute the same logic. Presenting it as an
     *         equivalence difference would be actively misleading on a design
     *         whose every cone matched.
     */
    public boolean isNetlistValidityError() {
        return this == COMBINATIONAL_LOOP || this == COMBINATIONAL_LOOP_INTRODUCED
                || this == COMBINATIONAL_LOOP_RESOLVED;
    }
}

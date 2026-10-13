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
 * The edge qualifier on a port specification.
 *
 * A port in an SDF file is written either bare, as {@code C}, or qualified by the transition it
 * applies to, as {@code (posedge C)}. Both a timing check's ports and the source of an
 * {@code IOPATH} can carry one; Vivado writes the qualified form for the asynchronous set and reset
 * arcs of a flip-flop, as in {@code (IOPATH (posedge PRE) Q ...)}.
 */
public enum SdfEdge {

    /** No edge qualifier was written; the port applies to any transition. */
    NONE,

    /** {@code posedge} */
    POSEDGE,

    /** {@code negedge} */
    NEGEDGE;

    /**
     * @return The SDF keyword for this edge, or null when no qualifier applies.
     */
    public String getKeyword() {
        switch (this) {
            case POSEDGE: return SdfKeywords.POSEDGE;
            case NEGEDGE: return SdfKeywords.NEGEDGE;
            default: return null;
        }
    }

    /**
     * Maps an SDF edge keyword onto this enum.
     *
     * @param keyword The keyword read from the file, or null if the port was written bare.
     * @return The corresponding edge.
     * @throws IllegalArgumentException If the keyword is not an SDF edge qualifier.
     */
    public static SdfEdge fromKeyword(String keyword) {
        if (keyword == null) {
            return NONE;
        }
        if (SdfKeywords.POSEDGE.equalsIgnoreCase(keyword)) {
            return POSEDGE;
        }
        if (SdfKeywords.NEGEDGE.equalsIgnoreCase(keyword)) {
            return NEGEDGE;
        }
        throw new IllegalArgumentException("ERROR: unsupported edge qualifier '" + keyword + "'");
    }
}

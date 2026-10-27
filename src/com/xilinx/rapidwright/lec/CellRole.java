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
 * The role a leaf cell plays in cone construction.
 *
 * Besides TERMINAL, which bounds a cone, and COMBINATIONAL, which forms its
 * interior, two further roles cover constant sources and cells whose function
 * this tool does not model.
 */
public enum CellRole {

    /**
     * Interior of a cone. The cell's outputs are Boolean functions of its inputs
     * and cone traversal continues through it. A model must exist in
     * {@link PrimitiveModelLibrary}.
     */
    COMBINATIONAL,

    /**
     * A cone endpoint. Its output pins are source terminals (free variables of
     * the compared functions) and its non-clock input pins are checked cone
     * roots. Flip-flops, latches, SRLs, block and ultra RAM, distributed RAM and
     * opaque DSPs are all terminals.
     */
    TERMINAL,

    /** A VCC tie cell. Its output is the constant one literal. */
    CONSTANT_VCC,

    /** A GND tie cell. Its output is the constant zero literal. */
    CONSTANT_GND,

    /**
     * A cell whose function this tool does not model: a black box, an encrypted
     * cell, or a primitive with no entry in the model library. Treated as an
     * uninterpreted function - each output becomes a free variable. Whether that
     * variable can be matched across the two netlists depends on where the cell
     * sits; see {@link CellClassifier} and the stability rules in
     * {@link TerminalKey}.
     */
    UNINTERPRETED;

    /**
     * @return True if this role terminates a cone, i.e. its outputs are cone
     *         sources rather than internal nodes.
     */
    public boolean isConeBoundary() {
        return this != COMBINATIONAL;
    }

    /**
     * @return True if this role produces a compile-time constant value.
     */
    public boolean isConstant() {
        return this == CONSTANT_VCC || this == CONSTANT_GND;
    }
}

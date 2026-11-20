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

/**
 * The Boolean function of one combinational primitive, expressed per output
 * pin.
 *
 * <h2>Why support is a separate method from build</h2>
 *
 * Carry chains and multiplier compression trees are cyclic when viewed as a
 * graph of whole cells but acyclic at the bit level, and must not be reported
 * as combinational loops. The way this tool satisfies that is to make the
 * dependency graph per output pin rather than per cell, and that only works if
 * each output declares its <i>exact minimal</i> input support.
 *
 * Consider a carry cell whose {@code O[2]} feeds, through a look up table, the
 * {@code S[5]} of a second cell, whose {@code O[1]} feeds back to the
 * {@code S[6]} of the first. Viewed as cells that is a cycle. But {@code O[2]}
 * depends only on {@code CI} and on {@code S} and {@code DI} bits 0 to 2; it
 * does not depend on {@code S[6]}. Carry information only ever flows from lower
 * bit index to higher, so at bit granularity there is no cycle, and any cycle
 * that does remain is a genuine logic loop.
 *
 * Declaring a lazy over-approximation such as "this output depends on all
 * inputs" would leave those chains cyclic and report false loops. The
 * support must also be property aware, because it can depend on configuration:
 * a {@code CARRY8} in dual mode takes its upper carry in from {@code CI_TOP}
 * rather than from {@code CO[3]}.
 *
 * Support is therefore consulted <i>before</i> any literal exists, so that the
 * cone builder can resolve the inputs itself and hand back the results.
 */
public interface PrimitiveModel {

    /**
     * The input port bits this output pin depends on.
     *
     * @param index      Index tables of the cell type.
     * @param inst       The instance, for reading configuration properties.
     * @param outPortBit The output port bit being asked about.
     * @return The input port bits, in the order {@code build} expects them, or
     *         null if this model does not drive that output pin.
     */
    int[] supportOf(CellIndex index, EDIFCellInst inst, int outPortBit);

    /**
     * Builds the output's function into the graph.
     *
     * @param aig        The graph to build into.
     * @param index      Index tables of the cell type.
     * @param inst       The instance, for reading configuration properties.
     * @param outPortBit The output port bit being built.
     * @param inputLits  The literal driving each entry of
     *                   {@link #supportOf(CellIndex, EDIFCellInst, int)}, in the
     *                   same order.
     * @return The literal computing the output.
     */
    int build(Aig aig, CellIndex index, EDIFCellInst inst, int outPortBit, int[] inputLits);
}

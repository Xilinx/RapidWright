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

/**
 * A logic equivalence checker for two versions of the same netlist.
 *
 * <p>Given a reference netlist {@code REF} and an implementation netlist
 * {@code IMP}, the checker proves that for <b>every terminal input pin the
 * Boolean function driving that pin is identical in both</b>. Cones are built
 * straight through hierarchy boundaries, so the two netlists may differ
 * arbitrarily in hierarchy, boundary ports, instance naming and combinational
 * structure and still be proven equivalent. This makes it suited to validating
 * netlist transformations, such as moving logic between hierarchical blocks,
 * where a structural diff is useless because the structure is <i>supposed</i>
 * to change.
 *
 * <h2>Running it</h2>
 *
 * <pre>
 * rapidwright LogicEquivalenceChecker ref.dcp imp.dcp [options]
 * rapidwright LogicEquivalenceChecker ref.dcp imp.dcp --help
 * </pre>
 *
 * Both {@code .dcp} and {@code .edf} inputs are accepted. Only the
 * <i>logical</i> netlist is read, never the device database or the physical
 * netlist. The exit code is {@code 0} when every checked cone is proven
 * equivalent, {@code 1} when anything differs or could not be decided, and
 * {@code 2} on a tool error such as an unreadable netlist. With a waiver such
 * as {@code --allow-inconclusive}, {@code 0} means only that nothing was found
 * to differ; the report counts what was waived. For a large design,
 * raise the heap, for example
 * {@code JAVA_TOOL_OPTIONS=-Xmx48g rapidwright LogicEquivalenceChecker ...}.
 * As a rough guide, allow about 20 GB of heap per million leaf primitives.
 *
 * <p>Commonly used options:
 * <ul>
 * <li>{@code --report <file>} writes the report to a file;
 * {@code --verbose} adds per-phase timing.</li>
 * <li>{@code --classify-only} audits the netlists without comparing them, and
 * lists every cell type with no functional model: the black boxes whose
 * contents the comparison takes on trust.</li>
 * <li>{@code --allow-preexisting-loops} accepts a combinational loop present
 * in <i>both</i> netlists. A loop only the implementation has always fails.</li>
 * <li>{@code --allow-inconclusive} downgrades undecided cones to a warning.</li>
 * <li>{@code --strict-black-boxes} stops assuming that same-typed black boxes,
 * such as encrypted IP, compute the same function.</li>
 * <li>{@code --sat-timeout} and {@code --sat-budget} bound the solver. Cones
 * beyond {@code --sat-max-support} inputs or {@code --sat-max-nodes} nodes are
 * attempted last, smallest first, with whatever budget remains; raise
 * {@code --sat-budget} when the report shows large cones left unattempted.</li>
 * </ul>
 *
 * <h2>What it checks</h2>
 *
 * A <b>terminal</b> is a cone endpoint: a flip-flop, latch, shift-register LUT,
 * block or ultra RAM, distributed RAM, an opaque DSP, a constant tie cell, or a
 * top-level design port. Terminals correspond one to one by hierarchical name
 * between the two netlists and form the fixed reference frame of the
 * comparison. Everything between terminals is the <b>combinational cloud</b>:
 * LUTs, wide-function multiplexers, every carry primitive ({@code CARRY4},
 * {@code CARRY8}, {@code LOOKAHEAD8}, {@code MUXCY}, {@code XORCY},
 * {@code LUT6CY}), soft gates and IO buffers.
 *
 * <p>Reported as differences: a differing cone function, a terminal present in
 * one netlist only, a terminal whose type or functional attributes changed, and
 * genuine combinational loops. Deliberately <i>not</i> differences: which
 * hierarchical block a combinational cell sits in, instance names in the
 * cloud, cell counts, cone structure, added or removed boundary ports, and
 * which tie cell a constant came from.
 *
 * <h2>Theory of operation</h2>
 *
 * Both netlists are elaborated into <b>one shared and-inverter graph</b>
 * ({@link com.xilinx.rapidwright.lec.Aig}), not two graphs that are then
 * matched. The graph's primary inputs are the terminals, keyed by hierarchical
 * name, so comparing a pin costs an integer comparison rather than a graph
 * isomorphism search. A run has six stages:
 * <ol>
 * <li><b>Load.</b> {@link com.xilinx.rapidwright.lec.NetlistLoader} reads the
 * EDIF straight out of the checkpoint without calling
 * {@code Design.readCheckpoint}.</li>
 * <li><b>Flatten and connect.</b>
 * {@link com.xilinx.rapidwright.lec.FlatNetlist} flattens to leaf primitives
 * and {@link com.xilinx.rapidwright.lec.ConnectivityBuilder} unions every net
 * joined through a port into one electrical node, which is what makes
 * hierarchy boundaries vanish. Multi-driver nets are tolerated and
 * classified.</li>
 * <li><b>Classify.</b> {@link com.xilinx.rapidwright.lec.CellClassifier}
 * assigns every leaf a {@link com.xilinx.rapidwright.lec.CellRole}. A cell
 * with no functional model is a black box, matched like a terminal: by instance
 * name, with its type and properties compared and its inputs checked, on the
 * standard assumption that two black boxes of the same type compute the same
 * function. {@code --strict-black-boxes} drops that assumption, making every
 * cone that reaches a black box inconclusive.</li>
 * <li><b>Elaborate.</b> {@link com.xilinx.rapidwright.lec.ConeBuilder} walks
 * backwards from every terminal input pin, clocks included, lowering each
 * primitive through {@link com.xilinx.rapidwright.lec.PrimitiveModelLibrary}.
 * The graph is structurally hashed, so two cones built from the same operations
 * over the same terminals collapse to the <i>same literal</i>, across netlists.
 * Every primitive declares its exact, property-aware input support per output
 * pin, so the dependency graph is over pins rather than cells: a carry chain,
 * which is cyclic as a graph of cells, is acyclic as a graph of pins, and a
 * {@code LUT6_2} whose {@code O5} ignores inputs that {@code O6} uses does not
 * create a false loop. Genuine loops are reported by {@link
 * com.xilinx.rapidwright.lec.LoopReport}.</li>
 * <li><b>Compare.</b> Terminal sets and functional attributes are compared
 * first ({@link com.xilinx.rapidwright.lec.TerminalAttributes}), then every
 * pin present in both netlists is run up the escalation ladder in
 * {@link com.xilinx.rapidwright.lec.EquivalenceEngine}: equal literals prove
 * equivalence; complementary literals refute it; bit-parallel random
 * simulation ({@link com.xilinx.rapidwright.lec.AigSimulator}) refutes most
 * differing pairs cheaply; and the remainder go to a CP-SAT miter
 * ({@link com.xilinx.rapidwright.lec.SatMiter}), where UNSAT proves
 * equivalence and SAT yields a counter-example. Verdicts are cached per literal
 * pair, so a cone shared by thousands of pins is settled once.</li>
 * <li><b>Verdict.</b> Nothing unproven is ever presented as a pass. A solver
 * timeout, a cone beyond the size limits, an exhausted budget, or a cone
 * reaching a variable the two netlists could not be made to share (a broken
 * loop, a multi-driver net, a black box) is <i>inconclusive</i>, which fails
 * the run by default.</li>
 * </ol>
 *
 * <p>Read the escalation counters in the report, not only the PASS/FAIL
 * banner. A run settled entirely by structural hash means the netlists are
 * structurally near-identical, which is expected for a pure relocation of
 * logic. A non-trivial count proven by the solver means genuinely different
 * structure was proven to compute the same function. Large cones left
 * unattempted are a budget problem rather than a logic problem.
 *
 * <h2>Verifying the checker</h2>
 *
 * A checker that always said "equivalent" would pass every real comparison.
 * {@link com.xilinx.rapidwright.lec.LecSelfCheck} guards against that:
 *
 * <pre>
 * rapidwright LecSelfCheck ref.dcp imp.dcp
 * </pre>
 *
 * It runs fifteen cases against the implementation: twelve known kinds of
 * damage (LUT INIT bits, swapped pins, rewired and retyped flip-flops, deleted
 * terminals, an introduced loop, and so on) that must each be detected, and
 * three controls that provably change nothing and must <i>not</i> be reported.
 * Where the
 * strongest cases apply, the reported counter-example is checked against the
 * raw LUT INIT bits independently of the graph, simulator and solver. A case
 * for which the design has no suitable site is reported as skipped and
 * excluded from the exercised count, so read that count before trusting a
 * pass on a new design family.
 *
 * <h2>Limitations</h2>
 *
 * <ul>
 * <li>DSPs are opaque terminals: their inputs are checked, but their internal
 * logic is not modelled.</li>
 * <li>Distributed RAM is a terminal.</li>
 * <li>Macro primitives are modelled natively rather than expanded, because
 * {@code expandMacroUnisims} shares one expanded cell per macro type and does
 * not propagate per-instance parameters. If one netlist holds a macro where the
 * other holds its expanded primitives, that is reported as a terminal set
 * difference.</li>
 * <li>Tristate is cut rather than modelled: buffers that can drive high
 * impedance are opaque terminals.</li>
 * <li>Terminals are identified by full hierarchical path, so a terminal moved
 * to a different hierarchical block is reported as one missing and one
 * added.</li>
 * </ul>
 */
package com.xilinx.rapidwright.lec;

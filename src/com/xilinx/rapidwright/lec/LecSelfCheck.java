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

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.xilinx.rapidwright.design.NetType;
import com.xilinx.rapidwright.design.tools.LUTTools;
import com.xilinx.rapidwright.device.Series;
import com.xilinx.rapidwright.edif.EDIFCell;
import com.xilinx.rapidwright.edif.EDIFCellInst;
import com.xilinx.rapidwright.edif.EDIFDirection;
import com.xilinx.rapidwright.edif.EDIFNet;
import com.xilinx.rapidwright.edif.EDIFNetlist;
import com.xilinx.rapidwright.edif.EDIFPortInst;
import com.xilinx.rapidwright.edif.EDIFPropertyValue;
import com.xilinx.rapidwright.edif.EDIFValueType;

/**
 * Deliberately damages the implementation netlist in many different ways and
 * checks that the equivalence checker notices.
 *
 * Two PASS verdicts prove nothing on their own; a checker that always said
 * "equivalent" would produce them too. What makes a negative control
 * meaningful is knowing, before the run, what the answer has to be. Every
 * mutation here therefore carries an expectation, and the campaign fails if any
 * expectation is not met in either direction:
 *
 * <ul>
 * <li>a mutation that provably changes logic and is <b>not</b> reported is a
 * false pass, the failure mode that matters;</li>
 * <li>a mutation that provably changes nothing and <b>is</b> reported is a
 * false alarm, which would make the tool useless in practice.</li>
 * </ul>
 *
 * The logic-changing cases are chosen so the expectation is a fact rather than
 * a guess. The primary sites are look up tables whose inputs are all terminal
 * outputs or constants and whose output drives a single flip flop data pin: the
 * cone at that pin is then exactly the look up table's own function over free
 * variables, so any change to a reachable entry of its INIT must change the
 * cone, and the reported counter-example can be checked against the raw INIT
 * bits without involving any of the checker's own machinery.
 */
public class LecSelfCheck {

    /** What the campaign requires of the checker for a given mutation. */
    private enum Expect {
        /** The logic provably changed; failing to report it is a false pass. */
        DETECT,
        /** Nothing observable changed; reporting it would be a false alarm. */
        NO_DETECT,
        /** The retiming certificate is invalid; the run must refuse it. */
        REFUSE
    }

    /** A mutation that has been applied, with the site it was applied at. */
    private static class Applied {
        String description;
        String conePin;          // the checked pin whose cone must change, if known
        long refInit;            // for the independent INIT oracle
        long impInit;
        int[] inputPiOrder;      // LUT input index -> source terminal name, for the oracle
        String[] inputTerminals;
        boolean haveOracle;
        RetimingMap retiming;    // a mutated certificate, replacing the given one
    }

    private final Path refPath;
    private final Path impPath;
    private final LecConfig config;
    private final RetimingMap retiming;
    private FlatNetlist refFlat;
    private Connectivity refConn;
    private final LecLog quiet =
            new LecLog(false, new PrintStream(new ByteArrayOutputStream()));
    private EDIFNetlist reference;
    private final List<String[]> results = new ArrayList<>();
    private int failures;
    private final List<String> skipped = new ArrayList<>();

    public LecSelfCheck(Path refPath, Path impPath) {
        this(refPath, impPath, null);
    }

    /**
     * A campaign on two netlists related by a retiming. The certificate is
     * used for every comparison, and the retiming cases are added.
     */
    public LecSelfCheck(Path refPath, Path impPath, RetimingMap retiming) {
        this.refPath = refPath;
        this.impPath = impPath;
        this.retiming = retiming;
        this.config = LecConfig.parse(new String[] { refPath.toString(), impPath.toString(),
                "--max-diffs", "4", "--allow-preexisting-loops" });
    }

    public static void main(String[] args) {
        boolean withMap = args.length == 4 && args[2].equals("--retiming-map");
        if (args.length != 2 && !withMap) {
            System.out.println("USAGE: LecSelfCheck <reference.dcp> <implementation.dcp> "
                    + "[--retiming-map <file>]");
            System.out.println();
            System.out.println("  Damages the implementation in a series of known ways and checks");
            System.out.println("  that the equivalence checker notices each one. Exits non-zero if");
            System.out.println("  any mutation that provably changes logic goes unreported, or if");
            System.out.println("  any mutation that provably changes nothing is reported.");
            System.out.println("  With a retiming certificate, also damages the retimed registers");
            System.out.println("  and the certificate itself.");
            System.exit(LogicEquivalenceChecker.EXIT_TOOL_ERROR);
        }
        RetimingMap map = withMap ? RetimingMap.read(Paths.get(args[3])) : null;
        System.exit(new LecSelfCheck(Paths.get(args[0]), Paths.get(args[1]), map).run());
    }

    /** @return An exit code: zero when every expectation was met. */
    public int run() {
        System.out.println("REF: " + refPath);
        System.out.println("IMP: " + impPath);
        reference = NetlistLoader.load(refPath, config, quiet);

        check("control: no mutation at all", Expect.NO_DETECT, imp -> {
            Applied a = new Applied();
            a.description = "identity";
            return a;
        });
        check("control: INIT bit that is unreachable (constant-tied input)", Expect.NO_DETECT,
                this::mutateDontCareBit);
        check("control: LUT whose output drives nothing", Expect.NO_DETECT, this::mutateDeadLut);

        check("LUT INIT, reachable bit, cone = LUT function", Expect.DETECT,
                imp -> mutateCleanLut(imp, false));
        check("LUT INIT, whole table inverted", Expect.DETECT, imp -> mutateCleanLut(imp, true));
        check("LUT input pins swapped (asymmetric function)", Expect.DETECT, this::swapLutInputs);
        check("LUT6CY INIT changed", Expect.DETECT, this::mutateLut6cy);
        check("LOOKAHEAD8 carry inputs CYA and CYB swapped", Expect.DETECT,
                this::swapLookaheadCarries);
        check("flip flop D pin rewired to a different driver", Expect.DETECT, this::rewireFlopD);
        check("flip flop clock enable retied from VCC to GND", Expect.DETECT, this::retieEnable);
        check("flip flop type changed FDRE -> FDSE", Expect.DETECT, this::changeFlopType);
        check("flip flop INIT attribute changed", Expect.DETECT, this::changeFlopInit);
        check("IS_D_INVERTED set on a flip flop", Expect.DETECT, this::invertFlopD);
        check("terminal deleted", Expect.DETECT, this::deleteFlop);
        check("combinational loop introduced", Expect.DETECT, this::introduceLoop);
        if (retiming != null && !retiming.isEmpty()) {
            checkRetiming();
        }

        System.out.println();
        System.out.printf("%-58s %-11s %-11s %s%n", "MUTATION", "EXPECTED", "OBSERVED", "DETAIL");
        for (String[] r : results) {
            System.out.printf("%-58s %-11s %-11s %s%n", r[0], r[1], r[2], r[3]);
        }
        printOutcome();
        return failures == 0 ? LogicEquivalenceChecker.EXIT_PASS
                : LogicEquivalenceChecker.EXIT_FAIL;
    }

    /**
     * Prints the verdict, keeping exercised cases and skipped ones apart.
     *
     * A skipped case is a hole in the campaign's coverage, not a success. Rolling
     * skips into the headline count would let a design that contains no LUT6CY,
     * no LOOKAHEAD8 and no clean mutation site report "all expectations met"
     * while having tested almost nothing — precisely the false confidence this
     * whole campaign exists to prevent. They are therefore named individually.
     *
     * Skips do not fail the run: whether a design contains a given primitive is a
     * property of the design, not a defect in the checker. The exit code still
     * answers "did the checker get anything wrong", and the text answers "how
     * much was actually tried".
     */
    private void printOutcome() {
        int exercised = results.size() - skipped.size();
        System.out.println();
        if (failures == 0) {
            System.out.println("ALL EXPECTATIONS MET (" + exercised + " of " + results.size()
                    + " cases exercised)");
        } else {
            System.out.println(failures + " EXPECTATION(S) VIOLATED out of " + exercised
                    + " exercised (" + results.size() + " cases attempted)");
        }
        if (skipped.isEmpty()) {
            return;
        }
        System.out.println();
        System.out.println("WARNING: " + skipped.size() + " case(s) were SKIPPED because this "
                + "design contains no suitable site, so they");
        System.out.println("         tested nothing and must not be read as having passed:");
        for (String name : skipped) {
            System.out.println("           - " + name);
        }
        System.out.println("         Run the campaign on a design exercising those constructs to "
                + "cover them.");
    }

    private interface Mutator {
        Applied apply(Context ctx);
    }

    /**
     * The implementation netlist plus the checker's view of it, including an
     * index from each electrical node to the leaf pins attached to it.
     *
     * Without the index, asking "what does this net drive" means scanning every
     * pin in the design, which turns site selection into a quadratic sweep over
     * the whole design. Built once by counting sort, each query
     * then costs only the net's own fanout.
     */
    private static class Context {
        EDIFNetlist netlist;
        Series series;
        FlatNetlist flat;
        Connectivity conn;
        int[] rootStart;
        int[] rootPinInst;
        int[] rootPinBit;
        List<LutSite> cleanSites;

        void indexFanout() {
            int roots = conn.getRootCount();
            int[] counts = new int[roots];
            for (int id : flat.getLeafInstIds()) {
                CellIndex idx = flat.getIndex(id);
                for (int b = 0; b < idx.getPortBitCount(); b++) {
                    int r = conn.getPinRoot(flat.getGlobalPin(id, b));
                    if (r >= 0) {
                        counts[r]++;
                    }
                }
            }
            rootStart = new int[roots + 1];
            for (int r = 0; r < roots; r++) {
                rootStart[r + 1] = rootStart[r] + counts[r];
            }
            rootPinInst = new int[rootStart[roots]];
            rootPinBit = new int[rootStart[roots]];
            int[] cursor = rootStart.clone();
            for (int id : flat.getLeafInstIds()) {
                CellIndex idx = flat.getIndex(id);
                for (int b = 0; b < idx.getPortBitCount(); b++) {
                    int r = conn.getPinRoot(flat.getGlobalPin(id, b));
                    if (r >= 0) {
                        rootPinInst[cursor[r]] = id;
                        rootPinBit[cursor[r]] = b;
                        cursor[r]++;
                    }
                }
            }
        }

        /**
         * @return True if anything at all can see this output pin.
         *
         *         Two situations mean nothing can: the pin is on no electrical
         *         node, or it is on one that no other pin attaches to. Testing
         *         only the first misses the far commoner second case — a net
         *         that exists but has no consumer.
         *
         *         The top port check is what keeps this sound. The fanout index
         *         holds leaf pins only, so a table driving a top level output
         *         would otherwise look unobserved, and mutating it would change
         *         a checked cone that the caller has just asserted cannot move.
         */
        boolean isObserved(int globalPin) {
            int root = conn.getPinRoot(globalPin);
            if (root < 0) {
                return false;
            }
            return rootStart[root + 1] - rootStart[root] > 1 || conn.getTopPortBit(root) >= 0;
        }
    }

    /**
     * Applies one mutation to a freshly loaded implementation, runs the checker,
     * and compares the outcome against the expectation.
     */
    private void check(String name, Expect expect, Mutator mutator) {
        Context ctx = new Context();
        ctx.netlist = NetlistLoader.load(impPath, config, quiet);
        ctx.series = NetlistLoader.getSeries(ctx.netlist, impPath);
        ctx.flat = FlatNetlist.build(ctx.netlist, quiet);
        ctx.conn = ConnectivityBuilder.build(ctx.flat, quiet);
        ctx.indexFanout();
        Applied applied;
        try {
            applied = mutator.apply(ctx);
        } catch (RuntimeException e) {
            record(name, expect, "ERROR", "mutation could not be applied: " + e);
            failures++;
            return;
        }
        if (applied == null) {
            // A mutation with nowhere to be applied is neither met nor violated:
            // this design simply does not contain the construct. It must not be
            // counted as a pass, because nothing was exercised.
            skipped.add(name);
            record(name, expect, "SKIPPED", "no suitable site found in this design");
            return;
        }
        CellIndex.clearCache();
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        LecResult result;
        try {
            result = LogicEquivalenceChecker.compare(reference, ctx.netlist, config,
                    applied.retiming != null ? applied.retiming : retiming,
                    new PrintStream(buffer));
        } catch (LecException e) {
            // A refused certificate certifies nothing, so it detects a damaging
            // change; it must never happen to a harmless one.
            boolean ok = expect != Expect.NO_DETECT;
            if (!ok) {
                failures++;
            }
            record(name, expect, "REFUSED", (ok ? "" : "*** EXPECTATION VIOLATED *** ")
                    + applied.description + "  ->  " + firstLine(e.getMessage()));
            return;
        }
        evaluate(name, expect, applied, result, buffer.toString());
    }

    private static String firstLine(String message) {
        String[] lines = message.split("\\R");
        return lines.length > 1 ? lines[0] + " " + lines[1].trim() : message;
    }

    private void evaluate(String name, Expect expect, Applied applied, LecResult result,
            String report) {
        LecReport r = result.getReport();
        int diffs = r.getEquivalenceDifferenceCount() + r.getIntroducedLoopCount();
        int undecided = r.getInconclusiveCount();
        // Refusing to certify is a detection too: the run fails and nothing is
        // presented as proven. Only a clean PASS counts as "not detected".
        boolean detected = diffs > 0 || undecided > 0;
        String detail = applied.description;
        if (undecided > 0 && diffs == 0) {
            detail += "  ->  " + undecided + " cone(s) could not be certified";
        }
        if (diffs > 0) {
            detail += "  ->  " + diffs + " finding(s)";
            String pin = firstReportedPin(result);
            if (pin != null) {
                detail += ", first=" + shorten(pin);
            }
            if (applied.conePin != null) {
                boolean named = reportsPin(result, applied.conePin)
                        || reportsRetimedRegister(result, applied.conePin);
                detail += named ? ", names the expected pin" : ", DOES NOT name expected pin "
                        + shorten(applied.conePin);
                if (!named) {
                    failures++;
                }
            }
            if (applied.haveOracle) {
                detail += verifyAgainstInitOracle(result, applied);
            }
        }
        boolean ok = expect == Expect.REFUSE ? false : (expect == Expect.DETECT) == detected;
        if (!ok) {
            failures++;
        }
        String observed = diffs > 0 ? "DETECTED"
                : (undecided > 0 ? "NOT CERTIFIED" : "not detected");
        record(name, expect, observed,
                (ok ? "" : "*** EXPECTATION VIOLATED *** ") + detail);
    }

    /**
     * Checks the reported counter-example against the raw INIT bits of the
     * mutated look up table.
     *
     * This is the part that does not trust the checker at all: the cone at the
     * chosen pin is by construction the look up table's own function, so the
     * assignment is turned into an INIT index by pure arithmetic and the two
     * INIT values are read at that index directly.
     */
    private String verifyAgainstInitOracle(LecResult result, Applied applied) {
        for (LecDifference d : result.getReport().getDifferences()) {
            if (d.getType() != LecDifferenceType.CONE_FUNCTION_DIFFERS
                    || !d.getSubject().equals(applied.conePin)) {
                continue;
            }
            Map<String, Boolean> assignment = d.getVerdict().getCounterExample();
            int index = 0;
            for (int i = 0; i < applied.inputTerminals.length; i++) {
                Boolean value = assignment.get(applied.inputTerminals[i]);
                if (value == null) {
                    return ", oracle: assignment omits " + shorten(applied.inputTerminals[i]);
                }
                if (value) {
                    index |= 1 << applied.inputPiOrder[i];
                }
            }
            boolean refBit = ((applied.refInit >>> index) & 1L) != 0;
            boolean impBit = ((applied.impInit >>> index) & 1L) != 0;
            if (refBit != impBit) {
                return ", counter-example verified against raw INIT bit " + index
                        + " (" + (refBit ? 1 : 0) + " vs " + (impBit ? 1 : 0) + ")";
            }
            // The independent oracle rejects the witness: the checker is wrong.
            failures++;
            return ", *** ORACLE DISAGREES: INIT bit " + index + " is identical ***";
        }
        return ", oracle: expected pin not among reported findings";
    }

    private static String firstReportedPin(LecResult result) {
        for (LecDifference d : result.getReport().getDifferences()) {
            return d.getSubject();
        }
        return null;
    }

    private static boolean reportsPin(LecResult result, String pin) {
        for (LecDifference d : result.getReport().getDifferences()) {
            if (pin.equals(d.getSubject())) {
                return true;
            }
        }
        // The pin may be past the reporting limit; fall back to the count only.
        return result.getReport().getDifferences().size() >= 4;
    }

    private void record(String name, Expect expect, String observed, String detail) {
        String expected = expect == Expect.DETECT ? "detect"
                : expect == Expect.NO_DETECT ? "no detect" : "refuse";
        results.add(new String[] { name, expected, observed, detail });
        System.out.println("  [done] " + name + " -> " + observed);
    }

    /**
     * @return True if the pin belongs to a register the certificate defines and
     *         a retiming finding names that register. A defined register's own
     *         pins are not compared by name; its retiming obligations are.
     */
    private boolean reportsRetimedRegister(LecResult result, String pin) {
        if (retiming == null) {
            return false;
        }
        String register = pin.substring(0, pin.lastIndexOf('/'));
        if (!retiming.registersOn(RetimingMap.Side.IMP).contains(register)) {
            return false;
        }
        for (LecDifference d : result.getReport().getDifferences()) {
            String subject = d.getSubject();
            if (subject.startsWith("retiming ") && (subject.endsWith(" IMP " + register)
                    || subject.contains(" IMP " + register + " vs "))) {
                return true;
            }
        }
        return false;
    }

    private static String shorten(String s) {
        return s.length() <= 64 ? s : "..." + s.substring(s.length() - 61);
    }

    // ------------------------------------------------------------- site finding

    /**
     * Finds a look up table whose cone is exactly its own function: every input
     * driven by a terminal output or a constant, and its output driving a single
     * checked flip flop data pin.
     */
    private LutSite findCleanLut(Context ctx, int skip) {
        if (ctx.cleanSites == null) {
            ctx.cleanSites = new ArrayList<>();
            for (int id : ctx.flat.getLeafInstIds()) {
                String type = ctx.flat.getCellTypeName(id);
                if (!type.startsWith("LUT") || type.startsWith("LUT6CY")
                        || type.equals("LUT6_2") || type.equals("LUT1")) {
                    continue;
                }
                LutSite site = describeLutSite(ctx, id);
                if (site != null) {
                    ctx.cleanSites.add(site);
                    if (ctx.cleanSites.size() >= 600) {
                        break;
                    }
                }
            }
            System.out.println("  (" + ctx.cleanSites.size() + " look up tables found whose cone "
                    + "is exactly their own function)");
        }
        return skip < ctx.cleanSites.size() ? ctx.cleanSites.get(skip) : null;
    }

    private static class LutSite {
        int instId;
        EDIFCellInst inst;
        String initText;
        long init;
        int arity;
        String conePin;              // hierarchical FF D pin
        String[] inputTerminals;     // source terminal name per LUT input, null if constant
        int[] inputIndex;            // LUT input index
        boolean[] constantValue;     // value if the input is a constant
        boolean[] isConstant;
    }

    private LutSite describeLutSite(Context ctx, int id) {
        CellIndex idx = ctx.flat.getIndex(id);
        EDIFCellInst inst = ctx.flat.getInst(id);
        EDIFPropertyValue init = inst.getProperty("INIT");
        if (init == null || init.getValue() == null) {
            return null;
        }
        // Output must drive exactly one sink, a checked flip flop data pin.
        int outBit = idx.portBitIndexOfPinName("O");
        if (outBit < 0) {
            return null;
        }
        int root = ctx.conn.getPinRoot(ctx.flat.getGlobalPin(id, outBit));
        if (root < 0) {
            return null;
        }
        String conePin = soleFlopDataSink(ctx, root, id);
        if (conePin == null) {
            return null;
        }
        int arity = Integer.parseInt(ctx.flat.getCellTypeName(id).substring(3));
        LutSite site = new LutSite();
        site.inputTerminals = new String[arity];
        site.inputIndex = new int[arity];
        site.isConstant = new boolean[arity];
        site.constantValue = new boolean[arity];
        for (int i = 0; i < arity; i++) {
            int bit = idx.portBitIndexOfPinName("I" + i);
            if (bit < 0) {
                return null;
            }
            int inRoot = ctx.conn.getPinRoot(ctx.flat.getGlobalPin(id, bit));
            site.inputIndex[i] = i;
            if (inRoot < 0) {
                return null;
            }
            byte kind = ctx.conn.getRootKind(inRoot);
            if (kind == Connectivity.CONST0 || kind == Connectivity.CONST1) {
                site.isConstant[i] = true;
                site.constantValue[i] = kind == Connectivity.CONST1;
                continue;
            }
            if (kind != Connectivity.DRIVEN_BY_LEAF) {
                return null;
            }
            int driverPin = ctx.conn.getDriverPin(inRoot);
            int owner = ctx.flat.getPinOwner(driverPin);
            String ownerType = ctx.flat.getCellTypeName(owner);
            if (!ownerType.startsWith("FD")) {
                return null;                 // insist the input is a terminal output
            }
            int driverBit = driverPin - ctx.flat.getGlobalPin(owner, 0);
            site.inputTerminals[i] = ctx.flat.getPinName(owner, driverBit);
        }
        site.instId = id;
        site.inst = inst;
        site.initText = init.getValue();
        site.init = LUTTools.getInitValue(site.initText);
        site.arity = arity;
        site.conePin = conePin;
        return site;
    }

    /** @return The single flip flop data pin the net drives, or null. */
    private String soleFlopDataSink(Context ctx, int root, int driverId) {
        String found = null;
        for (int k = ctx.rootStart[root]; k < ctx.rootStart[root + 1]; k++) {
            int id = ctx.rootPinInst[k];
            int b = ctx.rootPinBit[k];
            if (id == driverId) {
                continue;
            }
            CellIndex idx = ctx.flat.getIndex(id);
            if (idx.getPortOfBit(b).getDirection() != EDIFDirection.INPUT) {
                return null;
            }
            if (!ctx.flat.getCellTypeName(id).startsWith("FD")
                    || !idx.getPortBitName(b).equals("D")) {
                return null;
            }
            if (found != null) {
                return null;
            }
            found = ctx.flat.getPinName(id, b);
        }
        return found;
    }

    // --------------------------------------------------------------- mutations

    /**
     * Changes a reachable INIT entry of a clean look up table, which provably
     * changes the cone at the flip flop it drives.
     */
    private Applied mutateCleanLut(Context ctx, boolean invertWholeTable) {
        LutSite site = findCleanLut(ctx, invertWholeTable ? 1 : 0);
        if (site == null) {
            return null;
        }
        long mask = TruthTable.widthMask(site.arity);
        long newInit;
        int changedIndex;
        if (invertWholeTable) {
            newInit = (~site.init) & mask;
            changedIndex = reachableIndex(site);
        } else {
            changedIndex = reachableIndex(site);
            newInit = site.init ^ (1L << changedIndex);
        }
        setInit(site.inst, site.arity, newInit);

        Applied a = new Applied();
        a.description = shorten(ctx.flat.getInstName(site.instId)) + " " + site.initText + " -> "
                + Long.toHexString(newInit) + " (index " + changedIndex + ")";
        a.conePin = site.conePin;
        a.refInit = site.init;
        a.impInit = newInit;
        List<String> terms = new ArrayList<>();
        List<Integer> order = new ArrayList<>();
        for (int i = 0; i < site.arity; i++) {
            if (!site.isConstant[i]) {
                terms.add(site.inputTerminals[i]);
                order.add(site.inputIndex[i]);
            }
        }
        a.inputTerminals = terms.toArray(new String[0]);
        a.inputPiOrder = new int[order.size()];
        for (int i = 0; i < order.size(); i++) {
            a.inputPiOrder[i] = order.get(i);
        }
        a.haveOracle = true;
        return a;
    }

    /**
     * @return An INIT index consistent with the constant-tied inputs, so that
     *         changing the entry actually affects the function.
     */
    private static int reachableIndex(LutSite site) {
        int index = 0;
        for (int i = 0; i < site.arity; i++) {
            if (site.isConstant[i] && site.constantValue[i]) {
                index |= 1 << i;
            }
        }
        return index;
    }

    /**
     * Changes an INIT entry that a constant-tied input makes unreachable.
     *
     * Soundness here rests only on the tie: if input {@code Ii} is held at a
     * constant, every INIT index whose bit {@code i} contradicts that constant is
     * never addressed, so flipping it cannot change what the table computes no
     * matter what the table drives. This case therefore does <i>not</i> need the
     * "clean" sites the detection cases use — that property exists so the cone
     * equals the table's own function, which only the INIT oracle cares about.
     *
     * Searching only the clean pool would often find nothing: a clean site
     * requires every input to be driven by a terminal output, and a
     * design whose tables are all flop-driven has no clean site with a constant
     * input at all. The search therefore runs over every look up table in the
     * design.
     *
     * The output must still be live, or this would merely repeat the dead-table
     * control below it.
     */
    private Applied mutateDontCareBit(Context ctx) {
        for (int id : ctx.flat.getLeafInstIds()) {
            String type = ctx.flat.getCellTypeName(id);
            if (!type.matches("LUT[1-6]")) {
                continue;                     // excludes LUT6CY, LUT6_2 and friends
            }
            EDIFCellInst inst = ctx.flat.getInst(id);
            EDIFPropertyValue init = inst.getProperty("INIT");
            if (init == null || init.getValue() == null) {
                continue;
            }
            CellIndex idx = ctx.flat.getIndex(id);
            int outBit = idx.portBitIndexOfPinName("O");
            if (outBit < 0 || !ctx.isObserved(ctx.flat.getGlobalPin(id, outBit))) {
                continue;                     // dead output; a different control covers that
            }
            int arity = Integer.parseInt(type.substring(3));
            int tied = -1;
            boolean tiedHigh = false;
            for (int i = 0; i < arity && tied < 0; i++) {
                int bit = idx.portBitIndexOfPinName("I" + i);
                if (bit < 0) {
                    continue;
                }
                int root = ctx.conn.getPinRoot(ctx.flat.getGlobalPin(id, bit));
                if (root < 0 || ctx.conn.getRootKind(root) == Connectivity.UNDRIVEN) {
                    // Exactly the condition ConeBuilder.resolveInput treats as an
                    // implicit tie: no net at all, or a net nothing drives. Both
                    // elaborate to the pin's static default. Vivado maps narrow
                    // functions onto wide tables this way constantly, so without
                    // this case the search would often find nothing — a design
                    // can easily have no explicitly tied table input at all.
                    tied = i;
                    tiedHigh = ConeBuilder.pinDefault(ctx.series, type,
                            CellClassifier.stripBusIndex(idx.getPortBitName(bit))) == NetType.VCC;
                    continue;
                }
                byte kind = ctx.conn.getRootKind(root);
                if (kind == Connectivity.CONST0 || kind == Connectivity.CONST1) {
                    tied = i;
                    tiedHigh = kind == Connectivity.CONST1;
                }
            }
            if (tied < 0) {
                continue;
            }
            // The tied bit takes one value; any index asserting the other is dead.
            int unreachable = tiedHigh ? 0 : (1 << tied);
            long value = LUTTools.getInitValue(init.getValue());
            setInit(inst, arity, value ^ (1L << unreachable));
            Applied a = new Applied();
            a.description = shorten(ctx.flat.getInstName(id)) + " I" + tied + " tied "
                    + (tiedHigh ? "high" : "low") + ", unreachable INIT index " + unreachable
                    + " flipped";
            return a;
        }
        return null;
    }

    /**
     * Inverts the whole INIT of a look up table nothing can see.
     *
     * "Nothing can see it" covers both a wholly unconnected output and — much
     * more commonly — an output sitting on a net with no consumer. Insisting on
     * the first alone often finds no site at all, even on a design carrying
     * thousands of the second kind.
     */
    private Applied mutateDeadLut(Context ctx) {
        for (int id : ctx.flat.getLeafInstIds()) {
            String type = ctx.flat.getCellTypeName(id);
            if (!type.startsWith("LUT")) {
                continue;
            }
            CellIndex idx = ctx.flat.getIndex(id);
            boolean observed = false;
            for (int b = 0; b < idx.getPortBitCount() && !observed; b++) {
                observed = idx.getPortOfBit(b).isOutput()
                        && ctx.isObserved(ctx.flat.getGlobalPin(id, b));
            }
            if (observed) {
                continue;
            }
            EDIFCellInst inst = ctx.flat.getInst(id);
            EDIFPropertyValue init = inst.getProperty("INIT");
            if (init == null || init.getValue() == null) {
                continue;
            }
            long value = LUTTools.getInitValue(init.getValue());
            int width = Integer.parseInt(init.getValue().substring(0,
                    init.getValue().indexOf('\'')));
            long mask = width >= 64 ? -1L : (1L << width) - 1;
            inst.addProperty("INIT", width + "'h" + Long.toHexString((~value) & mask),
                    EDIFValueType.STRING);
            Applied a = new Applied();
            a.description = shorten(ctx.flat.getInstName(id)) + " (output observed by nothing) "
                    + "inverted";
            return a;
        }
        return null;
    }

    /**
     * Exchanges two input connections of a look up table whose function is not
     * symmetric in them, so the swap provably changes the function.
     */
    private Applied swapLutInputs(Context ctx) {
        for (int skip = 0; skip < 400; skip++) {
            LutSite site = findCleanLut(ctx, skip);
            if (site == null) {
                return null;
            }
            for (int i = 0; i < site.arity; i++) {
                for (int j = i + 1; j < site.arity; j++) {
                    if (site.isConstant[i] || site.isConstant[j]) {
                        continue;
                    }
                    if (isSymmetric(site.init, site.arity, i, j)) {
                        continue;
                    }
                    if (swapConnections(site.inst, "I" + i, "I" + j)) {
                        Applied a = new Applied();
                        a.description = shorten(ctx.flat.getInstName(site.instId))
                                + " swapped I" + i + " and I" + j + " (asymmetric)";
                        a.conePin = site.conePin;
                        return a;
                    }
                }
            }
        }
        return null;
    }

    /** @return True if exchanging two variables leaves the function unchanged. */
    private static boolean isSymmetric(long table, int vars, int a, int b) {
        for (int index = 0; index < (1 << vars); index++) {
            int bitA = (index >>> a) & 1;
            int bitB = (index >>> b) & 1;
            if (bitA == bitB) {
                continue;
            }
            int swapped = index ^ (1 << a) ^ (1 << b);
            if (((table >>> index) & 1L) != ((table >>> swapped) & 1L)) {
                return false;
            }
        }
        return true;
    }

    /** Exchanges the nets attached to two pins of an instance. */
    private static boolean swapConnections(EDIFCellInst inst, String pinA, String pinB) {
        EDIFPortInst a = inst.getPortInst(pinA);
        EDIFPortInst b = inst.getPortInst(pinB);
        if (a == null || b == null || a.getNet() == null || b.getNet() == null) {
            return false;
        }
        EDIFNet netA = a.getNet();
        EDIFNet netB = b.getNet();
        if (netA == netB) {
            return false;
        }
        netA.removePortInst(a);
        netB.removePortInst(b);
        netA.createPortInst(pinB, inst);
        netB.createPortInst(pinA, inst);
        return true;
    }

    private Applied mutateLut6cy(Context ctx) {
        for (int id : ctx.flat.getLeafInstIds()) {
            if (!ctx.flat.getCellTypeName(id).equals("LUT6CY")) {
                continue;
            }
            CellIndex idx = ctx.flat.getIndex(id);
            boolean live = false;
            for (int b = 0; b < idx.getPortBitCount(); b++) {
                if (idx.getPortOfBit(b).isOutput()
                        && ctx.conn.getPinRoot(ctx.flat.getGlobalPin(id, b)) >= 0) {
                    live = true;
                }
            }
            if (!live) {
                continue;
            }
            EDIFCellInst inst = ctx.flat.getInst(id);
            EDIFPropertyValue init = inst.getProperty("INIT");
            if (init == null || init.getValue() == null) {
                continue;
            }
            long value = LUTTools.getInitValue(init.getValue());
            inst.addProperty("INIT", "64'h" + Long.toHexString(~value), EDIFValueType.STRING);
            Applied a = new Applied();
            a.description = shorten(ctx.flat.getInstName(id)) + " LUT6CY INIT inverted";
            return a;
        }
        return null;
    }

    /**
     * Exchanges two carry inputs of a carry lookahead cell.
     *
     * Flipping a {@code LOOK} attribute instead would not do: enabling
     * lookahead is a different way to compute the <i>same</i> carry, which is
     * the entire point of lookahead, so the netlist stays equivalent. Swapping
     * which signal arrives as which bit's carry changes the value itself.
     */
    private Applied swapLookaheadCarries(Context ctx) {
        for (int id : ctx.flat.getLeafInstIds()) {
            if (!ctx.flat.getCellTypeName(id).equals("LOOKAHEAD8")) {
                continue;
            }
            CellIndex idx = ctx.flat.getIndex(id);
            int a = idx.portBitIndexOfPinName("CYA");
            int b = idx.portBitIndexOfPinName("CYB");
            if (a < 0 || b < 0) {
                continue;
            }
            int rootA = ctx.conn.getPinRoot(ctx.flat.getGlobalPin(id, a));
            int rootB = ctx.conn.getPinRoot(ctx.flat.getGlobalPin(id, b));
            if (rootA < 0 || rootB < 0 || rootA == rootB) {
                continue;
            }
            if (!swapConnections(ctx.flat.getInst(id), "CYA", "CYB")) {
                continue;
            }
            Applied ap = new Applied();
            ap.description = shorten(ctx.flat.getInstName(id)) + " swapped CYA and CYB";
            return ap;
        }
        return null;
    }

    /** Reconnects a flip flop data pin to a different, unrelated net. */
    private Applied rewireFlopD(Context ctx) {
        LutSite first = findCleanLut(ctx, 0);
        LutSite second = findCleanLut(ctx, 1);
        if (first == null || second == null) {
            return null;
        }
        int flopId = ownerOfPin(ctx, second.conePin);
        if (flopId < 0) {
            return null;
        }
        EDIFCellInst flop = ctx.flat.getInst(flopId);
        EDIFPortInst dPin = flop.getPortInst("D");
        EDIFPortInst otherOut = first.inst.getPortInst("O");
        if (dPin == null || otherOut == null || otherOut.getNet() == null
                || dPin.getNet() == otherOut.getNet()) {
            return null;
        }
        EDIFNet target = otherOut.getNet();
        if (target.getParentCell() != flop.getParentCell()) {
            return null;                 // keep the rewire inside one cell
        }
        dPin.getNet().removePortInst(dPin);
        target.createPortInst("D", flop);
        Applied a = new Applied();
        a.description = shorten(second.conePin) + " rewired to the driver of "
                + shorten(first.conePin);
        a.conePin = second.conePin;
        return a;
    }

    private int ownerOfPin(Context ctx, String pinName) {
        int slash = pinName.lastIndexOf('/');
        String instName = pinName.substring(0, slash);
        for (int id : ctx.flat.getLeafInstIds()) {
            if (ctx.flat.getInstName(id).equals(instName)) {
                return id;
            }
        }
        return -1;
    }

    /** Moves a clock enable from the constant one net to the constant zero net. */
    private Applied retieEnable(Context ctx) {
        for (int id : ctx.flat.getLeafInstIds()) {
            if (!ctx.flat.getCellTypeName(id).startsWith("FD")) {
                continue;
            }
            CellIndex idx = ctx.flat.getIndex(id);
            int ceBit = idx.portBitIndexOfPinName("CE");
            if (ceBit < 0) {
                continue;
            }
            int root = ctx.conn.getPinRoot(ctx.flat.getGlobalPin(id, ceBit));
            if (root < 0 || ctx.conn.getRootKind(root) != Connectivity.CONST1) {
                continue;
            }
            EDIFCellInst flop = ctx.flat.getInst(id);
            EDIFCell parent = flop.getParentCell();
            EDIFNet gnd = findConstantNet(parent, false);
            if (gnd == null) {
                continue;
            }
            EDIFPortInst ce = flop.getPortInst("CE");
            ce.getNet().removePortInst(ce);
            gnd.createPortInst("CE", flop);
            Applied a = new Applied();
            a.description = shorten(ctx.flat.getPinName(id, ceBit)) + " retied VCC -> GND";
            a.conePin = ctx.flat.getPinName(id, ceBit);
            return a;
        }
        return null;
    }

    private static EDIFNet findConstantNet(EDIFCell cell, boolean high) {
        for (EDIFNet net : cell.getNets()) {
            for (EDIFPortInst pi : net.getPortInsts()) {
                if (pi.getCellInst() == null || !pi.isOutput()) {
                    continue;
                }
                String type = pi.getCellInst().getCellType().getName();
                if (high ? type.equals("VCC") : type.equals("GND")) {
                    return net;
                }
            }
        }
        return null;
    }

    private Applied changeFlopType(Context ctx) {
        for (int id : ctx.flat.getLeafInstIds()) {
            if (!ctx.flat.getCellTypeName(id).equals("FDRE")) {
                continue;
            }
            EDIFCell fdse = null;
            for (com.xilinx.rapidwright.edif.EDIFLibrary lib : ctx.netlist.getLibraries()) {
                if (lib.isHDIPrimitivesLibrary() && lib.getCell("FDSE") != null) {
                    fdse = lib.getCell("FDSE");
                }
            }
            if (fdse == null) {
                return null;
            }
            // FDSE has S where FDRE has R, so the reset pin is remapped by hand:
            // the synchronous reset becomes a synchronous set on the same net.
            EDIFCellInst inst = ctx.flat.getInst(id);
            inst.setCellTypeRaw(fdse);
            for (EDIFPortInst pi : inst.getPortInsts()) {
                String portName = pi.getPort().getName();
                pi.setPort(fdse.getPort(portName.equals("R") ? "S" : portName));
            }
            Applied a = new Applied();
            a.description = shorten(ctx.flat.getInstName(id)) + " FDRE -> FDSE";
            return a;
        }
        return null;
    }

    private Applied changeFlopInit(Context ctx) {
        for (int id : ctx.flat.getLeafInstIds()) {
            if (!ctx.flat.getCellTypeName(id).startsWith("FD")) {
                continue;
            }
            EDIFCellInst flop = ctx.flat.getInst(id);
            EDIFPropertyValue init = flop.getProperty("INIT");
            String before = init == null ? "<absent>" : init.getValue();
            flop.addProperty("INIT", "1'b1".equals(before) ? "1'b0" : "1'b1",
                    EDIFValueType.STRING);
            Applied a = new Applied();
            a.description = shorten(ctx.flat.getInstName(id)) + " INIT " + before + " -> flipped";
            return a;
        }
        return null;
    }

    private Applied invertFlopD(Context ctx) {
        LutSite site = findCleanLut(ctx, 0);
        if (site == null) {
            return null;
        }
        int flopId = ownerOfPin(ctx, site.conePin);
        if (flopId < 0) {
            return null;
        }
        ctx.flat.getInst(flopId).addProperty("IS_D_INVERTED", "1'b1", EDIFValueType.STRING);
        Applied a = new Applied();
        a.description = shorten(site.conePin) + " IS_D_INVERTED set";
        a.conePin = site.conePin;
        return a;
    }

    private Applied deleteFlop(Context ctx) {
        LutSite site = findCleanLut(ctx, 0);
        if (site == null) {
            return null;
        }
        int flopId = ownerOfPin(ctx, site.conePin);
        if (flopId < 0) {
            return null;
        }
        EDIFCellInst flop = ctx.flat.getInst(flopId);
        EDIFCell parent = flop.getParentCell();
        for (EDIFPortInst pi : new ArrayList<>(flop.getPortInsts())) {
            if (pi.getNet() != null) {
                pi.getNet().removePortInst(pi);
            }
        }
        parent.removeCellInst(flop);
        Applied a = new Applied();
        a.description = shorten(ctx.flat.getInstName(flopId)) + " deleted";
        return a;
    }

    /**
     * Feeds a look up table's own output back into one of its inputs.
     *
     * The input has to be one the INIT actually depends on. Closing the ring
     * through a pin the function ignores creates no loop and changes nothing, so
     * the checker would rightly stay silent and this case would report a
     * violation that is really the mutation's fault, not the checker's.
     */
    private Applied introduceLoop(Context ctx) {
        for (int skip = 0; skip < 200; skip++) {
            LutSite site = findCleanLut(ctx, skip);
            if (site == null) {
                return null;
            }
            EDIFPortInst out = site.inst.getPortInst("O");
            boolean[] dependent = new boolean[site.arity];
            for (int v : TruthTable.dependentVars(site.init, site.arity)) {
                dependent[v] = true;
            }
            for (int i = 0; i < site.arity; i++) {
                if (site.isConstant[i] || !dependent[i]) {
                    continue;
                }
                EDIFPortInst in = site.inst.getPortInst("I" + i);
                if (in == null || in.getNet() == null || out == null || out.getNet() == null) {
                    continue;
                }
                in.getNet().removePortInst(in);
                out.getNet().createPortInst("I" + i, site.inst);
                Applied a = new Applied();
                a.description = shorten(ctx.flat.getInstName(site.instId))
                        + " output fed back into I" + i;
                return a;
            }
        }
        return null;
    }

    private static void setInit(EDIFCellInst inst, int arity, long value) {
        int width = 1 << arity;
        inst.addProperty("INIT", width + "'h" + Long.toHexString(value), EDIFValueType.STRING);
    }

    // ---------------------------------------------------------------- retiming

    /**
     * The retiming cases: damage to the registers the retiming created and to
     * the certificate. Each damaging change must fail the run, and an invalid
     * certificate must be refused.
     */
    private void checkRetiming() {
        check("retimed register INIT flipped", Expect.DETECT,
                ctx -> retimedFlop(ctx, null, (c, id) -> flipInit(c, id)));
        check("retimed register reset turned into set", Expect.DETECT,
                ctx -> retimedFlop(ctx, "SR", (c, id) -> swapResetAndSet(c, id)));
        check("retimed register enable tied high", Expect.DETECT,
                ctx -> retimedFlop(ctx, "CE", (c, id) -> tiePin(c, id, "CE", true)));
        check("retimed register D tied low", Expect.DETECT,
                ctx -> retimedFlop(ctx, "D", (c, id) -> tiePin(c, id, "D", false)));
        check("retimed register moved to another clock", Expect.DETECT,
                ctx -> retimedFlop(ctx, null, (c, id) -> moveClock(c, id)));
        check("look up table feeding a retimed register inverted", Expect.DETECT,
                ctx -> retimedFlop(ctx, null, (c, id) -> invertDriverLut(c, id)));
        check("certificate entry dropped", Expect.DETECT, ctx -> dropEntry());
        check("certificate definitions swapped", Expect.DETECT, ctx -> swapDefinitions());
        check("certificate made cyclic", Expect.REFUSE, this::makeCyclic);
    }

    private interface FlopMutation {
        String apply(Context ctx, int flopId);
    }

    /**
     * Applies a mutation to the first register the retiming created in the
     * implementation that it can be applied to. These are the registers the
     * certificate defines, and the registers the implementation has and the
     * reference does not.
     *
     * @param livePin A pin that must not be tied to a constant for the change to
     *                be observable: {@code "SR"} for the reset or set pin, or
     *                null for none.
     */
    private Applied retimedFlop(Context ctx, String livePin, FlopMutation mutation) {
        for (int id : retimedImpFlops(ctx)) {
            if (livePin != null && isTied(ctx, id, livePin)) {
                continue;
            }
            String description = mutation.apply(ctx, id);
            if (description != null) {
                Applied a = new Applied();
                a.description = shorten(ctx.flat.getInstName(id)) + " " + description;
                return a;
            }
        }
        return null;
    }

    private List<Integer> retimedImpFlops(Context ctx) {
        Set<String> names = new LinkedHashSet<>(
                retiming.registersOn(RetimingMap.Side.IMP));
        Set<String> refFlops = new HashSet<>();
        for (int id : refFlat().getLeafInstIds()) {
            if (isRetimableType(refFlat().getCellTypeName(id))) {
                refFlops.add(refFlat().getInstName(id));
            }
        }
        for (int id : ctx.flat.getLeafInstIds()) {
            if (isRetimableType(ctx.flat.getCellTypeName(id))) {
                String name = ctx.flat.getInstName(id);
                if (!refFlops.contains(name)) {
                    names.add(name);
                }
            }
        }
        List<Integer> ids = new ArrayList<>();
        for (String name : names) {
            int id = ctx.flat.findInstId(name);
            if (id >= 0 && isRetimableType(ctx.flat.getCellTypeName(id))) {
                ids.add(id);
            }
        }
        return ids;
    }

    private static boolean isRetimableType(String type) {
        return type.equals("FDRE") || type.equals("FDSE");
    }

    private static String setOrResetPin(Context ctx, int id) {
        return ctx.flat.getCellTypeName(id).equals("FDSE") ? "S" : "R";
    }

    /** @return True if the pin is tied to a constant, so changing it is invisible. */
    private static boolean isTied(Context ctx, int id, String pin) {
        String name = pin.equals("SR") ? setOrResetPin(ctx, id) : pin;
        int bit = ctx.flat.getIndex(id).portBitIndexOfPinName(name);
        int root = ctx.conn.getPinRoot(ctx.flat.getGlobalPin(id, bit));
        if (root < 0) {
            return true;
        }
        byte kind = ctx.conn.getRootKind(root);
        return kind == Connectivity.CONST0 || kind == Connectivity.CONST1
                || kind == Connectivity.UNDRIVEN;
    }

    private static String flipInit(Context ctx, int id) {
        EDIFCellInst flop = ctx.flat.getInst(id);
        EDIFPropertyValue init = flop.getProperty("INIT");
        boolean wasSet = init == null ? ctx.flat.getCellTypeName(id).equals("FDSE")
                : ModelUtils.parseBit(init.getValue(), "INIT");
        flop.addProperty("INIT", wasSet ? "1'b0" : "1'b1", EDIFValueType.STRING);
        return "INIT " + (wasSet ? 1 : 0) + " -> " + (wasSet ? 0 : 1);
    }

    private static String swapResetAndSet(Context ctx, int id) {
        boolean set = ctx.flat.getCellTypeName(id).equals("FDSE");
        String toType = set ? "FDRE" : "FDSE";
        String fromPin = set ? "S" : "R";
        String toPin = set ? "R" : "S";
        EDIFCellInst inst = ctx.flat.getInst(id);
        EDIFCell source = inst.getCellType();
        EDIFCell target = source.getLibrary().getCell(toType);
        if (target == null) {
            // The netlist need not declare the other primitive; declare it with
            // the same interface, the reset pin renamed.
            target = new EDIFCell(source.getLibrary(), toType);
            for (com.xilinx.rapidwright.edif.EDIFPort port : source.getPorts()) {
                target.createPort(port.getName().equals(fromPin) ? toPin : port.getName(),
                        port.getDirection(), port.getWidth());
            }
        }
        inst.setCellTypeRaw(target);
        for (EDIFPortInst pi : inst.getPortInsts()) {
            String port = pi.getPort().getName();
            pi.setPort(target.getPort(port.equals(fromPin) ? toPin : port));
        }
        return (set ? "FDSE" : "FDRE") + " -> " + toType;
    }

    private static String tiePin(Context ctx, int id, String pin, boolean high) {
        if (isTied(ctx, id, pin)) {
            return null;
        }
        EDIFCellInst flop = ctx.flat.getInst(id);
        EDIFNet constant = findConstantNet(flop.getParentCell(), high);
        if (constant == null) {
            return null;
        }
        EDIFPortInst pi = flop.getPortInst(pin);
        pi.getNet().removePortInst(pi);
        constant.createPortInst(pin, flop);
        return pin + " tied " + (high ? "high" : "low");
    }

    /** Moves the register to the clock net of a register in another clock domain. */
    private static String moveClock(Context ctx, int id) {
        int own = rootOf(ctx, id, "C");
        for (int other : ctx.flat.getLeafInstIds()) {
            if (!ctx.flat.getCellTypeName(other).startsWith("FD")) {
                continue;
            }
            int root = rootOf(ctx, other, "C");
            EDIFCellInst flop = ctx.flat.getInst(id);
            EDIFCellInst donor = ctx.flat.getInst(other);
            if (root < 0 || root == own || donor.getParentCell() != flop.getParentCell()) {
                continue;
            }
            EDIFNet clock = donor.getPortInst("C").getNet();
            EDIFPortInst pi = flop.getPortInst("C");
            pi.getNet().removePortInst(pi);
            clock.createPortInst("C", flop);
            return "moved to clock " + clock.getName();
        }
        return null;
    }

    /** Inverts the whole table of a look up table driving the register's D. */
    private static String invertDriverLut(Context ctx, int id) {
        int root = rootOf(ctx, id, "D");
        if (root < 0 || ctx.conn.getRootKind(root) != Connectivity.DRIVEN_BY_LEAF) {
            return null;
        }
        int driver = ctx.flat.getPinOwner(ctx.conn.getDriverPin(root));
        String type = ctx.flat.getCellTypeName(driver);
        if (!type.matches("LUT[1-6]")) {
            return null;
        }
        EDIFCellInst lut = ctx.flat.getInst(driver);
        int inputs = type.charAt(3) - '0';
        long init = LUTTools.getInitValue(lut.getProperty("INIT").getValue());
        long mask = inputs == 6 ? -1L : (1L << (1 << inputs)) - 1;
        long inverted = ~init & mask;
        lut.addProperty("INIT", (1 << inputs) + "'h" + Long.toHexString(inverted).toUpperCase(),
                EDIFValueType.STRING);
        return "driver " + shorten(ctx.flat.getInstName(driver)) + " inverted";
    }

    private static int rootOf(Context ctx, int id, String pin) {
        int bit = ctx.flat.getIndex(id).portBitIndexOfPinName(pin);
        return bit < 0 ? -1 : ctx.conn.getPinRoot(ctx.flat.getGlobalPin(id, bit));
    }

    private Applied dropEntry() {
        List<RetimingMap.Entry> entries = retiming.getEntries();
        RetimingMap mutated = new RetimingMap();
        for (int i = 1; i < entries.size(); i++) {
            define(mutated, entries.get(i), entries.get(i).getDefiningPinKey());
        }
        Applied a = new Applied();
        a.description = "dropped " + shorten(entries.get(0).toString());
        a.retiming = mutated;
        return a;
    }

    /**
     * Swaps the definitions of two registers on the same side whose defining
     * pins differ. Two registers holding the same value would make the swap
     * harmless, which is why the pins must differ; a design where they differ
     * but compute the same function would be a false expectation, not a false
     * pass.
     */
    private Applied swapDefinitions() {
        List<RetimingMap.Entry> entries = retiming.getEntries();
        for (int i = 0; i < entries.size(); i++) {
            for (int j = i + 1; j < entries.size(); j++) {
                RetimingMap.Entry x = entries.get(i);
                RetimingMap.Entry y = entries.get(j);
                if (x.getRegisterSide() != y.getRegisterSide()
                        || x.getDefiningPinKey().equals(y.getDefiningPinKey())) {
                    continue;
                }
                RetimingMap mutated = new RetimingMap();
                for (RetimingMap.Entry e : entries) {
                    String pin = e == x ? y.getDefiningPinKey()
                            : e == y ? x.getDefiningPinKey() : e.getDefiningPinKey();
                    define(mutated, e, pin);
                }
                Applied a = new Applied();
                a.description = "swapped " + shorten(x.getRegister()) + " and "
                        + shorten(y.getRegister());
                a.retiming = mutated;
                return a;
            }
        }
        return null;
    }

    /**
     * Adds an entry defining a register the first definition reads by the
     * defined register itself, so each is defined in terms of the other.
     */
    private Applied makeCyclic(Context ctx) {
        RetimingMap.Entry first = retiming.getEntries().get(0);
        boolean defineInRef = first.getRegisterSide() == RetimingMap.Side.IMP;
        FlatNetlist flat = defineInRef ? refFlat() : ctx.flat;
        Connectivity conn = defineInRef ? refConn : ctx.conn;
        String register = registerFeeding(flat, conn, first.getDefiningInst(),
                retiming.registersOn(defineInRef ? RetimingMap.Side.REF : RetimingMap.Side.IMP));
        if (register == null) {
            return null;
        }
        RetimingMap mutated = new RetimingMap();
        for (RetimingMap.Entry e : retiming.getEntries()) {
            define(mutated, e, e.getDefiningPinKey());
        }
        mutated.define(defineInRef ? RetimingMap.Side.REF : RetimingMap.Side.IMP, register,
                TerminalKey.forPin(first.getRegister(), "Q"));
        Applied a = new Applied();
        a.description = shorten(register) + " defined by " + shorten(first.getRegister());
        a.retiming = mutated;
        return a;
    }

    /**
     * @return An FDRE or FDSE, not already defined, whose output reaches the
     *         instance through combinational cells only, or null.
     */
    private static String registerFeeding(FlatNetlist flat, Connectivity conn, String instName,
            Set<String> defined) {
        int start = flat.findInstId(instName);
        if (start < 0) {
            return null;
        }
        Deque<Integer> work = new ArrayDeque<>();
        Set<Integer> seen = new HashSet<>();
        work.add(start);
        while (!work.isEmpty() && seen.size() < 4096) {
            int id = work.poll();
            if (!seen.add(id)) {
                continue;
            }
            String type = flat.getCellTypeName(id);
            if (id != start && isRetimableType(type)) {
                String name = flat.getInstName(id);
                if (!defined.contains(name)) {
                    return name;
                }
                continue;
            }
            if (id != start && type.startsWith("FD")) {
                continue;
            }
            CellIndex idx = flat.getIndex(id);
            for (int b = 0; b < idx.getPortBitCount(); b++) {
                if (idx.getPortOfBit(b).getDirection() != EDIFDirection.INPUT) {
                    continue;
                }
                int root = conn.getPinRoot(flat.getGlobalPin(id, b));
                if (root >= 0 && conn.getRootKind(root) == Connectivity.DRIVEN_BY_LEAF) {
                    work.add(flat.getPinOwner(conn.getDriverPin(root)));
                }
            }
        }
        return null;
    }

    private static void define(RetimingMap map, RetimingMap.Entry entry, String pin) {
        map.define(entry.getRegisterSide(), entry.getRegister(), pin);
    }

    private FlatNetlist refFlat() {
        if (refFlat == null) {
            refFlat = FlatNetlist.build(reference, quiet);
            refConn = ConnectivityBuilder.build(refFlat, quiet);
        }
        return refFlat;
    }
}

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

import java.io.PrintStream;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Accumulates findings and renders the verdict.
 *
 * <h2>Why an undecided cone fails the run</h2>
 *
 * A comparison is naturally PASS or FAIL, but a solver timeout, a cone
 * too large to attempt, a genuine combinational loop or a variable that cannot
 * be matched between the two netlists all leave the question genuinely open.
 * Reporting PASS in that situation would present something that is not a proof
 * as though it were one, so by default an inconclusive cone fails the run and
 * is counted separately from a proven difference. The distinction is visible in
 * the summary, and {@code --allow-inconclusive} downgrades it for callers who
 * want only confirmed differences to matter.
 */
public class LecReport {

    private final List<LecDifference> differences = new ArrayList<>();

    private final Map<LecDifferenceType, Integer> counts =
            new EnumMap<>(LecDifferenceType.class);

    private final LecConfig config;

    private int conesChecked;

    private int conesEquivalent;

    private int conesDiffering;

    private int conesInconclusive;

    private int truncated;

    public LecReport(LecConfig config) {
        this.config = config;
        for (LecDifferenceType type : LecDifferenceType.values()) {
            counts.put(type, 0);
        }
    }

    /**
     * Records a finding. Only the first {@code --max-diffs} are retained in
     * full; the rest are counted. On a large design a systematic error can
     * affect a million pins, and holding a record for each would exhaust memory
     * for no benefit.
     */
    public void add(LecDifference difference) {
        counts.merge(difference.getType(), 1, Integer::sum);
        if (differences.size() < config.getMaxReportedDiffs()) {
            differences.add(difference);
        } else {
            truncated++;
        }
    }

    public void countCone(ConeVerdict.Kind kind) {
        conesChecked++;
        switch (kind) {
        case EQUIVALENT:
            conesEquivalent++;
            break;
        case DIFFERENT:
            conesDiffering++;
            break;
        default:
            conesInconclusive++;
            break;
        }
    }

    /** @return The number of findings that prove the two netlists differ. */
    public int getProvenDifferenceCount() {
        int total = 0;
        for (Map.Entry<LecDifferenceType, Integer> e : counts.entrySet()) {
            if (e.getKey().isProvenDifference()) {
                total += e.getValue();
            }
        }
        return total;
    }

    /**
     * @return Findings that prove the two netlists compute different logic,
     *         excluding defects that make a netlist invalid on its own.
     */
    public int getEquivalenceDifferenceCount() {
        int total = 0;
        for (Map.Entry<LecDifferenceType, Integer> e : counts.entrySet()) {
            if (e.getKey().isProvenDifference() && !e.getKey().isNetlistValidityError()) {
                total += e.getValue();
            }
        }
        return total;
    }

    /** @return Findings that make a netlist invalid regardless of the comparison. */
    public int getValidityErrorCount() {
        int total = 0;
        for (Map.Entry<LecDifferenceType, Integer> e : counts.entrySet()) {
            if (e.getKey().isNetlistValidityError()) {
                total += e.getValue();
            }
        }
        return total;
    }

    public int getInconclusiveCount() {
        return counts.get(LecDifferenceType.CONE_INCONCLUSIVE);
    }

    public int getCount(LecDifferenceType type) {
        return counts.get(type);
    }

    public int getConesChecked() {
        return conesChecked;
    }

    public int getConesEquivalent() {
        return conesEquivalent;
    }

    public int getConesDiffering() {
        return conesDiffering;
    }

    public int getConesInconclusive() {
        return conesInconclusive;
    }

    /** @return Loops the implementation introduced, which are always a failure. */
    public int getIntroducedLoopCount() {
        return counts.get(LecDifferenceType.COMBINATIONAL_LOOP_INTRODUCED);
    }

    /** @return Loops present in both netlists, inherited from the input. */
    public int getPreexistingLoopCount() {
        return counts.get(LecDifferenceType.COMBINATIONAL_LOOP);
    }

    /**
     * @return True if every checked cone was proven equivalent and nothing else
     *         disqualified the run.
     */
    public boolean isPass() {
        if (getEquivalenceDifferenceCount() > 0 || getIntroducedLoopCount() > 0) {
            return false;
        }
        if (getPreexistingLoopCount() > 0 && !config.isAllowPreexistingLoops()) {
            return false;
        }
        return getInconclusiveCount() == 0 || config.isAllowInconclusive();
    }

    public int getExitCode() {
        return isPass() ? LogicEquivalenceChecker.EXIT_PASS : LogicEquivalenceChecker.EXIT_FAIL;
    }

    /** Prints the counts, the per finding detail, and the verdict banner. */
    public void print(PrintStream out, EquivalenceEngine engine) {
        printSummary(out);
        printEscalation(out, engine);
        printDifferences(out);
        printVerdict(out);
    }

    private void printSummary(PrintStream out) {
        out.println();
        out.println("Summary");
        out.println("  cones checked            : " + conesChecked);
        out.println("  cones equivalent         : " + conesEquivalent);
        out.println("  cones differing          : " + conesDiffering);
        out.println("  cones inconclusive       : " + conesInconclusive);
        out.println("  equivalence differences  : " + getEquivalenceDifferenceCount());
        out.println("  loops introduced by IMP  : " + getIntroducedLoopCount());
        out.println("  loops in both netlists   : " + getPreexistingLoopCount());
        for (LecDifferenceType type : LecDifferenceType.values()) {
            int count = counts.get(type);
            if (count > 0) {
                out.println("  " + padded(type.getDescription()) + count);
            }
        }
    }

    /**
     * Shows how many distinct questions each rung of the ladder settled. This is
     * more than a curiosity: it is what tells you whether a passing run actually
     * exercised the solver or merely found everything identical.
     */
    private static void printEscalation(PrintStream out, EquivalenceEngine engine) {
        if (engine == null) {
            return;
        }
        out.println();
        out.println("How the distinct comparisons were settled");
        out.println("  proven by structural hash: " + engine.getProvenByHash());
        out.println("  refuted by structure     : " + engine.getRefutedByHash());
        out.println("  refuted by simulation    : " + engine.getRefutedBySimulation());
        out.println("  proven by solver         : " + engine.getProvenBySat());
        out.println("  refuted by solver        : " + engine.getRefutedBySat());
        out.println("  undecided                : " + engine.getInconclusiveQuestions());
        if (engine.getSkippedTooLarge() > 0) {
            out.println("  skipped, cone too large  : " + engine.getSkippedTooLarge());
        }
        if (engine.getSkippedNoSolver() > 0) {
            out.println("  skipped, no solver       : " + engine.getSkippedNoSolver());
        }
        if (engine.getSatCalls() > 0) {
            out.println("  solver calls / seconds   : " + engine.getSatCalls() + " / "
                    + String.format("%.2f", engine.getSatSeconds()));
        }
    }

    private void printDifferences(PrintStream out) {
        if (differences.isEmpty()) {
            return;
        }
        out.println();
        out.println("Findings");
        for (LecDifference difference : differences) {
            difference.print(out);
        }
        if (truncated > 0) {
            out.println("  ... " + truncated + " further findings not shown; raise --max-diffs "
                    + "to see them");
        }
    }

    /**
     * Prints the verdict, keeping the two reasons a run can fail apart.
     *
     * A design whose every cone matched but which contains a pre-existing
     * combinational loop is a very different situation from one whose logic
     * changed, and a banner that called both "differences" would send the
     * reader looking for a difference that is not there.
     */
    private void printVerdict(PrintStream out) {
        int equivalenceDifferences = getEquivalenceDifferenceCount();
        int validityErrors = getValidityErrorCount();
        out.println();
        out.println("==============================================================");
        if (isPass()) {
            out.println(" PASS - every checked cone is equivalent");
            if (getPreexistingLoopCount() > 0) {
                out.println(" (" + getPreexistingLoopCount() + " combinational loops exist in "
                        + "both netlists; they are inherited from the input and are being "
                        + "ignored because --allow-preexisting-loops was given)");
            }
            if (getInconclusiveCount() > 0) {
                out.println(" (" + getInconclusiveCount() + " cones were undecided and are being "
                        + "ignored because --allow-inconclusive was given)");
            }
        } else {
            printFailureReasons(out, equivalenceDifferences, validityErrors);
        }
        out.println("==============================================================");
    }

    private void printFailureReasons(PrintStream out, int equivalenceDifferences,
            int validityErrors) {
        out.println(" FAIL");
        if (equivalenceDifferences > 0) {
            out.println("   " + equivalenceDifferences
                    + " cone or terminal differences: the two netlists do not compute the same "
                    + "logic");
        } else if (conesChecked > 0) {
            out.println("   all " + conesEquivalent + " checked cones are equivalent");
        }
        if (getIntroducedLoopCount() > 0) {
            out.println("   " + getIntroducedLoopCount() + " combinational loops INTRODUCED by "
                    + "the implementation: the transform created a logic loop");
        }
        if (getPreexistingLoopCount() > 0) {
            out.println("   " + getPreexistingLoopCount() + " combinational loops present in "
                    + "both netlists: inherited from the input, not created by the transform");
            out.println("   use --allow-preexisting-loops to treat those as a warning instead");
        }
        if (getInconclusiveCount() > 0) {
            out.println("   " + getInconclusiveCount() + " cones could not be decided, so "
                    + "equivalence is not established for them");
            out.println("   use --allow-inconclusive to treat those as a warning instead");
        }
    }

    private static String padded(String text) {
        StringBuilder sb = new StringBuilder("  ").append(text);
        while (sb.length() < 27) {
            sb.append(' ');
        }
        return sb.append(": ").toString();
    }

    public List<LecDifference> getDifferences() {
        return differences;
    }
}

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
import java.util.List;
import java.util.Map;

/**
 * One finding, with enough context to localise its cause.
 *
 * A finding names the terminal pin, the source terminals present in one
 * netlist and not the other, and/or a counter-example assignment. All three
 * are carried here when they are available.
 */
public class LecDifference {

    private final LecDifferenceType type;

    private final String subject;

    private final String detail;

    private final ConeVerdict verdict;

    private final List<String> attributeDifferences;

    public LecDifference(LecDifferenceType type, String subject, String detail,
            ConeVerdict verdict, List<String> attributeDifferences) {
        this.type = type;
        this.subject = subject;
        this.detail = detail;
        this.verdict = verdict;
        this.attributeDifferences = attributeDifferences;
    }

    public LecDifferenceType getType() {
        return type;
    }

    /** @return The pin, terminal or loop this finding is about. */
    public String getSubject() {
        return subject;
    }

    public ConeVerdict getVerdict() {
        return verdict;
    }

    /**
     * Prints the finding, followed by whatever localisation is available: the
     * asymmetric source terminals, an assignment that separates the two
     * functions, and for an inconclusive result the variables that prevented a
     * verdict.
     */
    public void print(PrintStream out) {
        out.println("  " + type.getDescription() + ": " + subject);
        if (detail != null) {
            out.println("      " + detail);
        }
        if (attributeDifferences != null) {
            for (String difference : attributeDifferences) {
                out.println("      " + difference);
            }
        }
        if (verdict == null) {
            return;
        }
        if (verdict.getReason() != null) {
            out.println("      reason: " + verdict.getReason());
        }
        printNames(out, "source terminals only in REF", verdict.getRefOnlyInputs());
        printNames(out, "source terminals only in IMP", verdict.getImpOnlyInputs());
        printNames(out, "unmatchable variables", verdict.getTaintedInputs());
        printCounterExample(out);
    }

    private static void printNames(PrintStream out, String heading, String[] names) {
        if (names == null || names.length == 0) {
            return;
        }
        out.println("      " + heading + " (" + names.length + "):");
        int shown = 0;
        for (String name : names) {
            if (shown++ == 8) {
                out.println("        ... " + (names.length - 8) + " more");
                break;
            }
            out.println("        " + name);
        }
    }

    private void printCounterExample(PrintStream out) {
        Map<String, Boolean> assignment = verdict.getCounterExample();
        if (assignment.isEmpty()) {
            return;
        }
        out.println("      counter-example assignment (" + assignment.size() + " terminals):");
        int shown = 0;
        for (Map.Entry<String, Boolean> e : assignment.entrySet()) {
            if (shown++ == 16) {
                out.println("        ... " + (assignment.size() - 16) + " more");
                break;
            }
            out.println("        " + e.getKey() + " = " + (e.getValue() ? "1" : "0"));
        }
    }
}

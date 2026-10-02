/*
 *
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
package com.xilinx.rapidwright.edif.validate;

import java.io.IOException;
import java.io.PrintStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Collects the {@link ValidationIssue} objects produced by
 * {@link NetlistValidator}. To stay bounded on very large netlists, only the
 * first {@code maxIssuesPerCode} issues of each {@link IssueCode} are retained;
 * subsequent issues of that code are counted but not stored.
 */
public class ValidationReport {

    /** Default cap on retained issues per code (overflow is still counted). */
    public static final int DEFAULT_MAX_ISSUES_PER_CODE = 100;

    private final List<ValidationIssue> issues = new ArrayList<>();
    private final EnumMap<IssueCode, Integer> totalCounts = new EnumMap<>(IssueCode.class);
    private final EnumMap<Severity, Integer> severityCounts = new EnumMap<>(Severity.class);

    private final int maxIssuesPerCode;

    public ValidationReport() {
        this(DEFAULT_MAX_ISSUES_PER_CODE);
    }

    public ValidationReport(int maxIssuesPerCode) {
        this.maxIssuesPerCode = maxIssuesPerCode <= 0 ? Integer.MAX_VALUE : maxIssuesPerCode;
    }

    /**
     * Adds an issue to the report. Storage of the issue object is capped per code,
     * but the total and severity counters always reflect every reported issue.
     *
     * @param issue The issue to record.
     */
    public synchronized void add(ValidationIssue issue) {
        int priorForCode = totalCounts.getOrDefault(issue.getCode(), 0);
        totalCounts.put(issue.getCode(), priorForCode + 1);
        severityCounts.merge(issue.getSeverity(), 1, Integer::sum);
        if (priorForCode < maxIssuesPerCode) {
            issues.add(issue);
        }
    }

    /**
     * Convenience helper that constructs and adds an issue using a code's default
     * severity.
     */
    public void add(IssueCode code, String location, String message) {
        add(new ValidationIssue(code, location, message));
    }

    /**
     * @return The retained (capped) list of issues, in discovery order.
     */
    public List<ValidationIssue> getIssues() {
        return Collections.unmodifiableList(issues);
    }

    /**
     * @return The total number of issues reported for the given code, including
     *         those dropped by the per-code cap.
     */
    public int getCount(IssueCode code) {
        return totalCounts.getOrDefault(code, 0);
    }

    public int getCount(Severity severity) {
        return severityCounts.getOrDefault(severity, 0);
    }

    public int getErrorCount() {
        return getCount(Severity.ERROR);
    }

    public int getWarningCount() {
        return getCount(Severity.WARNING);
    }

    public int getInfoCount() {
        return getCount(Severity.INFO);
    }

    public int getTotalIssueCount() {
        int total = 0;
        for (int v : severityCounts.values()) {
            total += v;
        }
        return total;
    }

    /**
     * @return True if no ERROR-severity issues were reported.
     */
    public boolean isValid() {
        return getErrorCount() == 0;
    }

    public Map<IssueCode, Integer> getTotalCounts() {
        return Collections.unmodifiableMap(totalCounts);
    }

    /**
     * Prints the full list of retained issues followed by a summary to the given
     * stream.
     *
     * @param ps The stream to print to.
     */
    public void print(PrintStream ps) {
        for (ValidationIssue issue : issues) {
            ps.println(issue.toString());
        }
        printSummary(ps);
    }

    /**
     * Prints just the per-code and per-severity summary to the given stream.
     *
     * @param ps The stream to print to.
     */
    public void printSummary(PrintStream ps) {
        ps.println("===== EDIF Netlist Validation Summary =====");
        if (totalCounts.isEmpty()) {
            ps.println("No issues found.");
        } else {
            for (Map.Entry<IssueCode, Integer> e : totalCounts.entrySet()) {
                int stored = Math.min(e.getValue(), maxIssuesPerCode);
                String capNote = e.getValue() > stored
                        ? " (showing " + stored + " of " + e.getValue() + ")" : "";
                ps.println("  " + e.getKey().getDefaultSeverity() + "  " + e.getKey()
                        + ": " + e.getValue() + capNote);
            }
        }
        ps.println("-------------------------------------------");
        ps.println("  ERRORS:   " + getErrorCount());
        ps.println("  WARNINGS: " + getWarningCount());
        ps.println("  INFO:     " + getInfoCount());
        ps.println("  Netlist is " + (isValid() ? "VALID (no errors)" : "INVALID"));
        ps.println("===========================================");
    }

    /**
     * Writes the full report (issues + summary) to a file.
     *
     * @param reportFile Destination path.
     */
    public void writeReport(Path reportFile) {
        try (PrintStream ps = new PrintStream(Files.newOutputStream(reportFile))) {
            print(ps);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}

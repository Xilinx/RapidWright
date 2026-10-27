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

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Explains why an EDIF file could not be parsed, when the reason is that it
 * connects to ports that do not exist.
 *
 * A netlist writer that emits a connection to a port it never declared produces
 * a file no reader can load. RapidWright and Vivado both stop at the first such
 * connection, which makes the defect painful to chase: fix one, reload, hit the
 * next. This scan finds all of them in one pass so the whole defect can be seen
 * at once.
 *
 * The check is textual rather than a real parse, precisely because it has to
 * work on files that cannot be parsed. It is only ever run after a parse has
 * already failed, so a false positive costs nothing beyond a slightly confusing
 * extra line in a report that is already reporting a failure.
 */
public class EdifPortDiagnostics {

    /** Characters an EDIF identifier can contain, in the forms Vivado writes. */
    private static final String NAME = "[A-Za-z0-9_$&\\-\\.\\[\\]]+";

    private static final Pattern CELL_START =
            Pattern.compile("\\(cell\\s+(?:\\(rename\\s+)?(" + NAME + ")");

    private static final Pattern PORT_DECL =
            Pattern.compile("\\(port\\s+(?:\\(array\\s+)?(?:\\(rename\\s+)?(" + NAME + ")");

    private static final Pattern INSTANCE = Pattern.compile("\\(instance\\s+(?:\\(rename\\s+)?("
            + NAME + ")\\s+\\(viewref\\s+" + NAME + "\\s+\\(cellref\\s+(" + NAME + ")");

    private static final Pattern PORT_REF = Pattern.compile("\\(portref\\s+(?:\\(member\\s+)?("
            + NAME + ")(?:\\s+\\d+\\))?\\s*(?:\\(instanceref\\s+(" + NAME + ")\\))?");

    /** One connection to a port that the referenced cell does not declare. */
    public static class Finding {

        private final String enclosingCell;

        private final String instanceName;

        private final String referencedCell;

        private final String portName;

        private final long line;

        Finding(String enclosingCell, String instanceName, String referencedCell, String portName,
                long line) {
            this.enclosingCell = enclosingCell;
            this.instanceName = instanceName;
            this.referencedCell = referencedCell;
            this.portName = portName;
            this.line = line;
        }

        public String getPortName() {
            return portName;
        }

        public String getReferencedCell() {
            return referencedCell;
        }

        public long getLine() {
            return line;
        }

        @Override
        public String toString() {
            return "line " + line + ": port '" + portName + "' on instance '" + instanceName
                    + "' of cell '" + referencedCell + "', inside cell '" + enclosingCell + "'";
        }
    }

    private EdifPortDiagnostics() {
    }

    /**
     * Scans an EDIF file for connections naming ports that the referenced cell
     * does not declare.
     *
     * @param edifFile The file to scan.
     * @param limit    Maximum number of findings to retain; all are counted.
     * @return The findings, most of interest first, or an empty list if the
     *         scan itself could not be completed.
     */
    public static List<Finding> scan(Path edifFile, int limit) {
        try {
            Map<String, Set<String>> portsByCell = collectDeclaredPorts(edifFile);
            return findUndeclaredReferences(edifFile, portsByCell, limit);
        } catch (IOException | RuntimeException e) {
            return new ArrayList<>();
        }
    }

    /** First pass: every port each cell declares. */
    private static Map<String, Set<String>> collectDeclaredPorts(Path edifFile) throws IOException {
        Map<String, Set<String>> portsByCell = new HashMap<>();
        Set<String> current = null;
        try (BufferedReader reader = Files.newBufferedReader(edifFile, StandardCharsets.ISO_8859_1)) {
            String line;
            while ((line = reader.readLine()) != null) {
                Matcher cell = CELL_START.matcher(line);
                if (cell.find()) {
                    current = portsByCell.computeIfAbsent(cell.group(1), k -> new HashSet<>());
                }
                if (current == null || line.indexOf("(port ") < 0) {
                    continue;
                }
                Matcher port = PORT_DECL.matcher(line);
                while (port.find()) {
                    current.add(port.group(1));
                }
            }
        }
        return portsByCell;
    }

    /**
     * Second pass: every connection whose port is absent from the cell it names.
     *
     * A cell whose ports were never seen is skipped rather than reported, since
     * that would mean the scan itself missed the declaration and any finding
     * would be noise.
     */
    private static List<Finding> findUndeclaredReferences(Path edifFile,
            Map<String, Set<String>> portsByCell, int limit) throws IOException {
        List<Finding> findings = new ArrayList<>();
        Map<String, String> instanceToCell = new HashMap<>();
        String enclosing = "?";
        long lineNumber = 0;
        try (BufferedReader reader = Files.newBufferedReader(edifFile, StandardCharsets.ISO_8859_1)) {
            String line;
            while ((line = reader.readLine()) != null) {
                lineNumber++;
                Matcher cell = CELL_START.matcher(line);
                if (cell.find()) {
                    enclosing = cell.group(1);
                    instanceToCell = new HashMap<>();
                }
                if (line.indexOf("(instance ") >= 0) {
                    Matcher instance = INSTANCE.matcher(line);
                    while (instance.find()) {
                        instanceToCell.put(instance.group(1), instance.group(2));
                    }
                }
                if (line.indexOf("(portref ") < 0) {
                    continue;
                }
                collectFromLine(line, lineNumber, enclosing, instanceToCell, portsByCell, findings,
                        limit);
            }
        }
        return findings;
    }

    private static void collectFromLine(String line, long lineNumber, String enclosing,
            Map<String, String> instanceToCell, Map<String, Set<String>> portsByCell,
            List<Finding> findings, int limit) {
        Matcher ref = PORT_REF.matcher(line);
        while (ref.find()) {
            String instance = ref.group(2);
            if (instance == null) {
                continue;
            }
            String targetCell = instanceToCell.get(instance);
            if (targetCell == null) {
                continue;
            }
            Set<String> ports = portsByCell.get(targetCell);
            if (ports == null || ports.isEmpty() || ports.contains(ref.group(1))) {
                continue;
            }
            if (findings.size() < limit) {
                findings.add(new Finding(enclosing, instance, targetCell, ref.group(1),
                        lineNumber));
            }
        }
    }

    /**
     * Renders the findings as an explanation of why the netlist could not be
     * read, attributing the problem to the netlist rather than to the reader.
     */
    public static String describe(Path source, List<Finding> findings) {
        StringBuilder sb = new StringBuilder();
        sb.append("The netlist connects to ports that its own cells do not declare, so no EDIF ")
                .append("reader can load it. This is a defect in whatever wrote the netlist, not ")
                .append("in this tool; Vivado rejects the same file.");
        sb.append(System.lineSeparator()).append("  ").append(findings.size())
                .append(" such connections were found in ").append(source).append(":");
        Map<String, Integer> byCell = new HashMap<>();
        for (Finding finding : findings) {
            byCell.merge(finding.getReferencedCell(), 1, Integer::sum);
        }
        int shown = 0;
        for (Finding finding : findings) {
            if (shown++ == 10) {
                sb.append(System.lineSeparator()).append("    ... ")
                        .append(findings.size() - 10).append(" more");
                break;
            }
            sb.append(System.lineSeparator()).append("    ").append(finding);
        }
        sb.append(System.lineSeparator()).append("  Affected cell types: ").append(byCell.size());
        return sb.toString();
    }
}

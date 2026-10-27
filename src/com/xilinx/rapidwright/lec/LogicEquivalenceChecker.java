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

import java.io.FileOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import com.xilinx.rapidwright.device.Series;
import com.xilinx.rapidwright.edif.EDIFNetlist;
import com.xilinx.rapidwright.util.RuntimeTracker;
import com.xilinx.rapidwright.util.RuntimeTrackerTree;

/**
 * Proves, or refutes, that two netlists implement the same logic.
 *
 * For every terminal input pin present in both netlists, the Boolean function
 * driving that pin must be identical, evaluated over the unified combinational
 * cone feeding it and expressed in terms of the source terminals that cone
 * reaches. Cones are built through hierarchy boundaries transparently, so
 * the two netlists may differ arbitrarily in hierarchy, instance naming and
 * cloud structure and still be equivalent.
 *
 * <h2>Shape of the run</h2>
 *
 * Both netlists are elaborated into a <i>single</i> and-inverter graph whose
 * primary inputs are the terminals, keyed by name. That is what makes the
 * comparison cheap: two cones computed the same way become the same graph node,
 * and equivalence is then an integer comparison. Whatever that does not settle
 * escalates to simulation and then to a solver.
 *
 * <h2>Exit codes</h2>
 * <ul>
 * <li>0 - PASS, every checked cone proven equivalent, or nothing found to
 * differ when undecided cones or pre-existing loops were explicitly waived</li>
 * <li>1 - FAIL, at least one cone differs or could not be decided</li>
 * <li>2 - tool error, no verdict was reached</li>
 * </ul>
 */
public class LogicEquivalenceChecker {

    /** Every cone was proven equivalent. */
    public static final int EXIT_PASS = 0;

    /** At least one cone differs, or could not be decided. */
    public static final int EXIT_FAIL = 1;

    /** The check could not be run at all. */
    public static final int EXIT_TOOL_ERROR = 2;

    private LogicEquivalenceChecker() {
    }

    public static void main(String[] args) {
        System.exit(run(args, System.out));
    }

    /**
     * Runs the checker and returns the exit code, rather than calling
     * {@code System.exit}, so that tests can drive it in process.
     */
    public static int run(String[] args, PrintStream defaultOut) {
        LecConfig config;
        try {
            config = LecConfig.parse(args);
        } catch (LecException e) {
            defaultOut.println("ERROR: " + e.getMessage());
            return EXIT_TOOL_ERROR;
        }
        if (config == null) {
            LecConfig.printHelp(defaultOut);
            return EXIT_PASS;
        }

        // Validated before the report file is opened, so that a configuration
        // the tool will refuse outright is reported on the caller's own stream
        // rather than written into a report nobody is watching.
        try {
            config.validate();
        } catch (LecException e) {
            defaultOut.println("ERROR: " + e.getMessage());
            return EXIT_TOOL_ERROR;
        }

        PrintStream out = defaultOut;
        FileOutputStream reportStream = null;
        try {
            if (config.getReportFile() != null) {
                reportStream = new FileOutputStream(config.getReportFile().toFile());
                out = new PrintStream(reportStream, true);
            }
            return check(config, out);
        } catch (LecException e) {
            reportError(e.getMessage(), out, defaultOut);
            return EXIT_TOOL_ERROR;
        } catch (IOException e) {
            defaultOut.println("ERROR: Could not write report: " + e.getMessage());
            return EXIT_TOOL_ERROR;
        } finally {
            closeQuietly(reportStream, out, defaultOut);
        }
    }

    /**
     * Reports a failure that aborted the run.
     *
     * The message goes to the report when there is one, so the file explains why
     * it is short, and always to the caller's own stream as well. Writing it only
     * to the report would leave someone running under {@code --report} with a
     * bare exit code and a silent console.
     */
    private static void reportError(String message, PrintStream out, PrintStream defaultOut) {
        out.println("ERROR: " + message);
        if (out != defaultOut) {
            defaultOut.println("ERROR: " + message);
        }
    }

    private static void closeQuietly(FileOutputStream stream, PrintStream out,
            PrintStream defaultOut) {
        if (stream == null) {
            return;
        }
        out.flush();
        try {
            stream.close();
        } catch (IOException e) {
            defaultOut.println("WARNING: Could not close report file: " + e.getMessage());
        }
    }

    /** The main flow: read both netlists, elaborate them, then compare. */
    private static int check(LecConfig config, PrintStream out) {
        LecLog log = new LecLog(config.isVerbose(), out);
        EDIFNetlist refNetlist = NetlistLoader.load(config.getRefFile(), config, log);
        EDIFNetlist impNetlist = NetlistLoader.load(config.getImpFile(), config, log);
        return compare(refNetlist, impNetlist, config, out, log).getExitCode();
    }

    /**
     * Compares two already loaded netlists and writes the report.
     *
     * This is the in-process entry point. It takes netlists rather than paths so
     * that a caller can supply netlists built or modified in memory, which is
     * what the acceptance tests do to introduce a difference in exactly one
     * known place.
     */
    public static LecResult compare(EDIFNetlist refNetlist, EDIFNetlist impNetlist,
            LecConfig config, PrintStream out) {
        return compare(refNetlist, impNetlist, config, out,
                new LecLog(config.isVerbose(), out));
    }

    private static LecResult compare(EDIFNetlist refNetlist, EDIFNetlist impNetlist,
            LecConfig config, PrintStream out, LecLog log) {
        RuntimeTrackerTree timers = new RuntimeTrackerTree("LogicEquivalenceChecker",
                config.isVerbose());
        Aig aig = new Aig(1 << 20);

        printHeader(out, config);
        NetlistSummary ref = elaborate(refNetlist, "REF", config, log, timers, aig);
        NetlistSummary imp = elaborate(impNetlist, "IMP", config, log, timers, aig);
        NetlistLoader.checkSeriesMatch(ref.getSeries(), imp.getSeries(), config, log);

        ref.getStats().print(out);
        imp.getStats().print(out);
        printGraphStats(out, aig, ref, imp);

        LecReport report = new LecReport(config);
        if (config.isClassifyOnly()) {
            log.printWarnings(out);
            out.println();
            out.println("Classification only; no comparison performed.");
            printTimers(out, timers, config);
            return new LecResult(report, null, ref, imp, aig);
        }

        recordLoops(ref, imp, report);
        if (config.isElaborateOnly()) {
            log.printWarnings(out);
            ref.getLoopReport().print(out, 10);
            imp.getLoopReport().print(out, 10);
            out.println();
            out.println("Elaboration only; no comparison performed.");
            printTimers(out, timers, config);
            return new LecResult(report, null, ref, imp, aig);
        }

        EquivalenceEngine engine = compare(ref, imp, report, config, log, timers, aig);
        log.printWarnings(out);
        ref.getLoopReport().print(out, 10);
        imp.getLoopReport().print(out, 10);
        report.print(out, engine);
        printTimers(out, timers, config);
        return new LecResult(report, engine, ref, imp, aig);
    }

    /**
     * Loads, flattens, connects, classifies and elaborates one netlist, then
     * releases everything except the summary.
     */
    private static NetlistSummary elaborate(EDIFNetlist netlist, String label, LecConfig config,
            LecLog log, RuntimeTrackerTree timers, Aig aig) {
        RuntimeTracker timer = timers.createRuntimeTracker("elaborate " + label,
                timers.getRootRuntimeTracker());
        timer.start();
        try {
            Series series = NetlistLoader.getSeries(netlist, null);
            if (series == null) {
                log.getPrintStream().println("WARNING: " + label + " netlist does not name a "
                        + "recognised part; clock pin identification will rely on pin names and "
                        + "implicit pin tie values will default to ground.");
            }
            FlatNetlist flat = FlatNetlist.build(netlist, log);
            Connectivity conn = ConnectivityBuilder.build(flat, log);
            CellClassifier classifier = new CellClassifier(series, config, log);
            NetlistStats stats = NetlistStats.collect(label, flat, conn, classifier, log);
            if (config.isClassifyOnly()) {
                return new NetlistSummary(label, NetlistLoader.getPartName(netlist), series, stats);
            }
            return NetlistSummary.build(label, flat, conn, classifier, aig, series, stats, log);
        } finally {
            CellIndex.clearCache();
            timer.stop();
        }
    }

    /**
     * Compares the two summaries: first the terminal sets and their attributes,
     * then every cone the two have in common.
     */
    private static EquivalenceEngine compare(NetlistSummary ref, NetlistSummary imp,
            LecReport report, LecConfig config, LecLog log, RuntimeTrackerTree timers, Aig aig) {
        RuntimeTracker timer = timers.createRuntimeTracker("compare",
                timers.getRootRuntimeTracker());
        timer.start();
        try {
            compareTerminals(ref, imp, report);
            List<EquivalenceEngine.Task> tasks = buildTasks(ref, imp, report);
            EquivalenceEngine engine = new EquivalenceEngine(aig, config, log);
            engine.run(tasks);
            for (EquivalenceEngine.Task task : tasks) {
                recordVerdict(task, report);
            }
            return engine;
        } finally {
            timer.stop();
        }
    }

    /**
     * Checks that the terminals correspond one to one, which is the
     * fixed reference frame of the whole comparison, and that matched terminals
     * are configured the same way.
     */
    private static void compareTerminals(NetlistSummary ref, NetlistSummary imp,
            LecReport report) {
        Map<String, TerminalAttributes> refTerminals = ref.getTerminals();
        Map<String, TerminalAttributes> impTerminals = imp.getTerminals();
        for (Map.Entry<String, TerminalAttributes> e : refTerminals.entrySet()) {
            TerminalAttributes other = impTerminals.get(e.getKey());
            if (other == null) {
                report.add(new LecDifference(LecDifferenceType.TERMINAL_MISSING_IN_IMP,
                        e.getKey(), "present in REF as " + e.getValue().getCellTypeName(),
                        null, null));
                continue;
            }
            List<String> attributeDifferences = e.getValue().diff(other);
            if (attributeDifferences.isEmpty()) {
                continue;
            }
            boolean typeDiffers = attributeDifferences.get(0).startsWith("cell type ");
            report.add(new LecDifference(typeDiffers
                    ? LecDifferenceType.TERMINAL_CELL_TYPE_DIFFERS
                    : LecDifferenceType.TERMINAL_PROPERTY_DIFFERS,
                    e.getKey(), null, null, attributeDifferences));
        }
        for (String key : impTerminals.keySet()) {
            if (!refTerminals.containsKey(key)) {
                report.add(new LecDifference(LecDifferenceType.TERMINAL_MISSING_IN_REF, key,
                        "present in IMP as " + impTerminals.get(key).getCellTypeName(),
                        null, null));
            }
        }
    }

    /**
     * Pairs up the checked pins. A pin present in one netlist and absent in the
     * other is itself a difference.
     */
    private static List<EquivalenceEngine.Task> buildTasks(NetlistSummary ref, NetlistSummary imp,
            LecReport report) {
        Map<String, Integer> refCones = ref.getConeLiterals();
        Map<String, Integer> impCones = imp.getConeLiterals();
        List<EquivalenceEngine.Task> tasks = new ArrayList<>();
        for (String key : new TreeSet<>(refCones.keySet())) {
            Integer impLit = impCones.get(key);
            if (impLit == null) {
                report.add(new LecDifference(LecDifferenceType.CONE_PIN_MISSING_IN_IMP, key,
                        null, null, null));
                continue;
            }
            tasks.add(new EquivalenceEngine.Task(key, refCones.get(key), impLit));
        }
        for (String key : new TreeSet<>(impCones.keySet())) {
            if (!refCones.containsKey(key)) {
                report.add(new LecDifference(LecDifferenceType.CONE_PIN_MISSING_IN_REF, key,
                        null, null, null));
            }
        }
        return tasks;
    }

    private static void recordVerdict(EquivalenceEngine.Task task, LecReport report) {
        ConeVerdict verdict = task.getVerdict();
        report.countCone(verdict.getKind());
        if (verdict.getKind() == ConeVerdict.Kind.DIFFERENT) {
            report.add(new LecDifference(LecDifferenceType.CONE_FUNCTION_DIFFERS, task.getKey(),
                    null, verdict, null));
        } else if (verdict.getKind() == ConeVerdict.Kind.INCONCLUSIVE) {
            report.add(new LecDifference(LecDifferenceType.CONE_INCONCLUSIVE, task.getKey(),
                    null, verdict, null));
        }
    }

    /**
     * Records the combinational loops, separating the ones the implementation
     * introduced from the ones it inherited.
     *
     * Any genuine loop is an error, but for someone validating a
     * transform the two cases mean completely different things. A loop present
     * in both netlists is a property of the input that the transform merely
     * carried through; a loop present only in the implementation was created by
     * the transform and is a regression. Reporting them as one undifferentiated
     * count would bury exactly the signal that matters, and would also mean a
     * design with a pre-existing loop could never produce a clean run no matter
     * how correct the transform was.
     *
     * Comparison is on the set of pins in each cycle, since the pin a loop is
     * reported from depends on which cone reached it first.
     */
    private static void recordLoops(NetlistSummary ref, NetlistSummary imp, LecReport report) {
        Set<String> refLoops = ref.getLoopReport().getCanonicalKeys();
        Set<String> impLoops = imp.getLoopReport().getCanonicalKeys();
        for (String key : impLoops) {
            boolean preexisting = refLoops.contains(key);
            addLoop(report, imp.getLoopReport().getCycle(key),
                    preexisting ? LecDifferenceType.COMBINATIONAL_LOOP
                            : LecDifferenceType.COMBINATIONAL_LOOP_INTRODUCED,
                    preexisting ? "present in both netlists, so it is a property of the input "
                            + "rather than something the transform did"
                            : "present only in IMP, so the transform under test created it");
        }
        for (String key : refLoops) {
            if (!impLoops.contains(key)) {
                addLoop(report, ref.getLoopReport().getCycle(key),
                        LecDifferenceType.COMBINATIONAL_LOOP_RESOLVED,
                        "present only in REF, so the transform removed it");
            }
        }
    }

    private static void addLoop(LecReport report, String[] cycle, LecDifferenceType type,
            String detail) {
        if (cycle == null) {
            return;
        }
        report.add(new LecDifference(type, cycle[0], "a cycle of " + cycle.length
                + " pins that is not attributable to carry structure; " + detail, null,
                Arrays.asList(cycle)));
    }

    private static void printHeader(PrintStream out, LecConfig config) {
        out.println("==============================================================");
        out.println(" RapidWright Logic Equivalence Check");
        out.println("==============================================================");
        out.println("  REF      : " + config.getRefFile());
        out.println("  IMP      : " + config.getImpFile());
        out.println("  DSP mode : " + config.getDspMode().name().toLowerCase().replace('_', '-'));
        out.println("  Dist RAM : " + (config.isDistributedRamTerminal() ? "terminal"
                : "combinational"));
    }

    private static void printGraphStats(PrintStream out, Aig aig, NetlistSummary ref,
            NetlistSummary imp) {
        out.println();
        out.println("=== shared logic graph ===");
        out.println("  primary inputs           : " + aig.getPiCount());
        out.println("  and nodes                : " + aig.getAndCount());
        out.println("  nodes added by REF       : " + ref.getAigNodesAdded());
        out.println("  nodes added by IMP       : " + imp.getAigNodesAdded());
        out.println("  deepest cone traversal   : "
                + Math.max(ref.getMaxConeDepth(), imp.getMaxConeDepth()));
        out.println("  approximate graph memory : "
                + (aig.getApproximateBytes() / (1024 * 1024)) + " MB");
    }

    private static void printTimers(PrintStream out, RuntimeTrackerTree timers, LecConfig config) {
        if (config.isVerbose()) {
            out.println();
            out.print(timers.toString());
        }
    }
}

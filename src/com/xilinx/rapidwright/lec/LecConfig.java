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

import java.io.IOException;
import java.io.PrintStream;
import java.io.StringWriter;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.List;

import joptsimple.OptionParser;
import joptsimple.OptionSet;

/**
 * Command line options for the equivalence checker.
 *
 * Uses jopt-simple, matching {@code CopyImplementation} and the other option
 * driven tools in this code base.
 */
public class LecConfig {

    /** How DSP slices are treated during cone construction. */
    public enum DspMode {
        /** A DSP is a terminal; all of its inputs are checked cone roots. */
        OPAQUE,
        /** A DSP is combinational. Not supported in this release. */
        COMBINATIONAL,
        /** A registered DSP is a terminal, an unregistered one combinational. Not supported. */
        REGISTER_AWARE
    }

    private static final List<String> HELP_OPTS = Arrays.asList("h", "help");

    private static final List<String> REF_OPTS = Arrays.asList("r", "ref");

    private static final List<String> IMP_OPTS = Arrays.asList("i", "imp");

    private static final List<String> REPORT_OPTS = Arrays.asList("o", "report");

    private Path refFile;

    private Path impFile;

    private Path reportFile;

    private DspMode dspMode = DspMode.OPAQUE;

    private boolean distributedRamTerminal = true;

    private boolean verbose;

    private boolean allowInconclusive;

    private boolean allowPreexistingLoops;

    private boolean allowSeriesMismatch;

    private boolean elaborateOnly;

    private boolean classifyOnly;

    private int maxReportedDiffs = 100;

    private int simRounds = 4;

    private double satTimeoutSec = 10.0;

    private double satBudgetSec = 1200.0;

    /**
     * Cones wider than this are reported inconclusive rather than being sent to
     * the solver.
     *
     * Carry cones can be wide, notably around a Versal carry lookahead, and a
     * much lower limit turns questions the solver settles in seconds into
     * inconclusive results. The per cone timeout and the overall budget are the
     * real protection against a hopeless solve; this limit only avoids spending
     * any of that budget on cones that are obviously out of reach.
     */
    private int satMaxSupport = 128;

    private int satMaxNodes = 200000;

    private boolean sweep;

    private boolean expandMacros;

    LecConfig() {
    }

    /**
     * Builds the option parser. Kept separate from parsing so that
     * {@link #printHelp(PrintStream)} can render the same option definitions.
     */
    private static OptionParser buildParser() {
        OptionParser parser = new OptionParser();
        parser.acceptsAll(REF_OPTS, "Reference netlist (.dcp or .edf)").withRequiredArg()
                .describedAs("file");
        parser.acceptsAll(IMP_OPTS, "Implementation netlist (.dcp or .edf)").withRequiredArg()
                .describedAs("file");
        parser.acceptsAll(REPORT_OPTS, "Write the report to a file instead of stdout")
                .withRequiredArg().describedAs("file");
        parser.accepts("dsp-mode", "DSP handling: opaque. NOT IMPLEMENTED: combinational, "
                + "register-aware; they parse but are rejected").withRequiredArg()
                .describedAs("mode").defaultsTo("opaque");
        parser.accepts("dram-mode", "Distributed RAM handling: terminal. NOT IMPLEMENTED: "
                + "combinational; it parses but is rejected").withRequiredArg()
                .describedAs("mode").defaultsTo("terminal");
        parser.accepts("max-diffs", "Maximum number of differences reported in detail")
                .withRequiredArg().ofType(Integer.class).describedAs("n").defaultsTo(100);
        parser.accepts("sim-rounds", "Rounds of 64 pattern simulation before invoking SAT")
                .withRequiredArg().ofType(Integer.class).describedAs("n").defaultsTo(4);
        parser.accepts("sat-timeout", "Per cone SAT time limit in seconds").withRequiredArg()
                .ofType(Double.class).describedAs("sec").defaultsTo(10.0);
        parser.accepts("sat-budget", "Total SAT time limit in seconds across all cones")
                .withRequiredArg().ofType(Double.class).describedAs("sec").defaultsTo(1200.0);
        parser.accepts("sat-max-support", "Skip SAT for cones with more inputs than this")
                .withRequiredArg().ofType(Integer.class).describedAs("n").defaultsTo(128);
        parser.accepts("sat-max-nodes", "Skip SAT for cones larger than this many AIG nodes")
                .withRequiredArg().ofType(Integer.class).describedAs("n").defaultsTo(200000);
        parser.accepts("sweep", "NOT IMPLEMENTED: merge internal equivalences before comparison; "
                + "this option parses but is rejected");
        parser.accepts("allow-inconclusive", "Report inconclusive cones as a warning, not a failure");
        parser.accepts("allow-preexisting-loops", "Treat combinational loops present in BOTH "
                + "netlists as a warning; loops introduced by the transform still fail");
        parser.accepts("allow-series-mismatch", "Permit the two netlists to target different series");
        parser.accepts("expand-macros", "Expand macro primitives instead of modelling them natively");
        parser.accepts("elaborate-only", "Build cones and report statistics without comparing");
        parser.accepts("classify-only", "Report connectivity and cell classification only");
        parser.accepts("verbose", "Print progress and per phase timing");
        parser.acceptsAll(HELP_OPTS, "Print this help and exit").forHelp();
        return parser;
    }

    /**
     * Parses a command line. Accepts the reference and implementation netlists
     * either as the first two positional arguments or via named options.
     *
     * @return The parsed configuration, or null if help was requested.
     */
    public static LecConfig parse(String[] args) {
        OptionParser parser = buildParser();
        OptionSet options;
        try {
            options = parser.parse(args);
        } catch (Exception e) {
            throw new LecException(e.getMessage() + System.lineSeparator() + usage(parser));
        }
        if (options.has("help")) {
            return null;
        }

        LecConfig config = new LecConfig();
        config.readNetlistPaths(options, parser);
        config.readModes(options);
        config.readLimits(options);
        config.sweep = options.has("sweep");
        config.allowInconclusive = options.has("allow-inconclusive");
        config.allowPreexistingLoops = options.has("allow-preexisting-loops");
        config.allowSeriesMismatch = options.has("allow-series-mismatch");
        config.expandMacros = options.has("expand-macros");
        config.elaborateOnly = options.has("elaborate-only");
        config.classifyOnly = options.has("classify-only");
        config.verbose = options.has("verbose");
        if (options.has("report")) {
            config.reportFile = Paths.get((String) options.valueOf("report"));
        }
        return config;
    }

    private void readNetlistPaths(OptionSet options, OptionParser parser) {
        List<?> positional = options.nonOptionArguments();
        if (options.has("ref")) {
            refFile = Paths.get((String) options.valueOf("ref"));
        } else if (positional.size() > 0) {
            refFile = Paths.get((String) positional.get(0));
        }
        if (options.has("imp")) {
            impFile = Paths.get((String) options.valueOf("imp"));
        } else if (positional.size() > 1) {
            impFile = Paths.get((String) positional.get(1));
        }
        if (refFile == null || impFile == null) {
            throw new LecException("Two netlists are required." + System.lineSeparator()
                    + usage(parser));
        }
    }

    private void readModes(OptionSet options) {
        String dsp = ((String) options.valueOf("dsp-mode")).toLowerCase();
        if (dsp.equals("opaque")) {
            dspMode = DspMode.OPAQUE;
        } else if (dsp.equals("combinational")) {
            dspMode = DspMode.COMBINATIONAL;
        } else if (dsp.equals("register-aware")) {
            dspMode = DspMode.REGISTER_AWARE;
        } else {
            throw new LecException("Unknown --dsp-mode '" + dsp
                    + "', expected opaque, combinational or register-aware");
        }

        String dram = ((String) options.valueOf("dram-mode")).toLowerCase();
        if (dram.equals("terminal")) {
            distributedRamTerminal = true;
        } else if (dram.equals("combinational")) {
            distributedRamTerminal = false;
        } else {
            throw new LecException("Unknown --dram-mode '" + dram
                    + "', expected terminal or combinational");
        }
    }

    private void readLimits(OptionSet options) {
        maxReportedDiffs = (Integer) options.valueOf("max-diffs");
        simRounds = (Integer) options.valueOf("sim-rounds");
        satTimeoutSec = (Double) options.valueOf("sat-timeout");
        satBudgetSec = (Double) options.valueOf("sat-budget");
        satMaxSupport = (Integer) options.valueOf("sat-max-support");
        satMaxNodes = (Integer) options.valueOf("sat-max-nodes");
    }

    /**
     * Rejects combinations that parse but are not implemented, so the tool fails
     * with a clear message rather than silently checking something weaker than
     * the user asked for.
     */
    public void validate() {
        if (dspMode != DspMode.OPAQUE) {
            throw new LecException("DSP mode '" + dspMode.name().toLowerCase().replace('_', '-')
                    + "' is not supported in this release; only 'opaque' is available. Modelling "
                    + "a DSP combinationally requires a bit level model of the multiplier, ALU, "
                    + "pre-adder and pattern detector.");
        }
        if (!distributedRamTerminal) {
            throw new LecException("Distributed RAM mode 'combinational' is not supported in "
                    + "this release; only 'terminal' is available.");
        }
        if (sweep) {
            throw new LecException("--sweep is not implemented in this release. It would merge "
                    + "internal equivalences before comparison, which only pays off when a large "
                    + "share of cones reach the solver; in typical use few "
                    + "do. Cones the solver cannot settle are reported inconclusive rather "
                    + "than passed, so correctness does not depend on it.");
        }
        if (maxReportedDiffs < 0) {
            throw new LecException("--max-diffs must not be negative");
        }
        if (simRounds < 0) {
            throw new LecException("--sim-rounds must not be negative");
        }
    }

    private static String usage(OptionParser parser) {
        StringBuilder sb = new StringBuilder();
        sb.append("USAGE: LogicEquivalenceChecker <ref.dcp|ref.edf> <imp.dcp|imp.edf> [options]");
        sb.append(System.lineSeparator());
        try {
            StringWriter writer = new StringWriter();
            parser.printHelpOn(writer);
            sb.append(writer.toString());
        } catch (IOException e) {
            sb.append("(help unavailable: ").append(e.getMessage()).append(")");
        }
        return sb.toString();
    }

    /** Prints the full usage text. */
    public static void printHelp(PrintStream out) {
        out.println(usage(buildParser()));
    }

    public Path getRefFile() {
        return refFile;
    }

    public Path getImpFile() {
        return impFile;
    }

    public Path getReportFile() {
        return reportFile;
    }

    public DspMode getDspMode() {
        return dspMode;
    }

    public boolean isDistributedRamTerminal() {
        return distributedRamTerminal;
    }

    public boolean isVerbose() {
        return verbose;
    }

    public boolean isAllowInconclusive() {
        return allowInconclusive;
    }

    /**
     * @return True if a loop present in both netlists should be a warning. A
     *         loop the implementation introduced always fails, since that is a
     *         regression rather than an inherited property of the input.
     */
    public boolean isAllowPreexistingLoops() {
        return allowPreexistingLoops;
    }

    public boolean isAllowSeriesMismatch() {
        return allowSeriesMismatch;
    }

    public boolean isElaborateOnly() {
        return elaborateOnly;
    }

    public boolean isClassifyOnly() {
        return classifyOnly;
    }

    public int getMaxReportedDiffs() {
        return maxReportedDiffs;
    }

    public int getSimRounds() {
        return simRounds;
    }

    public double getSatTimeoutSec() {
        return satTimeoutSec;
    }

    public double getSatBudgetSec() {
        return satBudgetSec;
    }

    public int getSatMaxSupport() {
        return satMaxSupport;
    }

    public int getSatMaxNodes() {
        return satMaxNodes;
    }

    public boolean isSweep() {
        return sweep;
    }

    public boolean isExpandMacros() {
        return expandMacros;
    }

    void setRefFile(Path refFile) {
        this.refFile = refFile;
    }

    void setImpFile(Path impFile) {
        this.impFile = impFile;
    }

    void setVerbose(boolean verbose) {
        this.verbose = verbose;
    }

    void setAllowInconclusive(boolean allowInconclusive) {
        this.allowInconclusive = allowInconclusive;
    }

    void setAllowPreexistingLoops(boolean allowPreexistingLoops) {
        this.allowPreexistingLoops = allowPreexistingLoops;
    }

    void setClassifyOnly(boolean classifyOnly) {
        this.classifyOnly = classifyOnly;
    }
}

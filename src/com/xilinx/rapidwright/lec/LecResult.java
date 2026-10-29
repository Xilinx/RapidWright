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
 * Everything one comparison produced: the findings, how they were reached, and
 * the shared graph the two netlists were elaborated into.
 *
 * Returned by the in-process entry point so that callers, including the
 * acceptance tests, can inspect more than the exit code. Being able to assert
 * on <i>how</i> a verdict was reached matters: a positive result that came
 * entirely from structural hashing says nothing about whether the solver path
 * works, so the tests check the escalation counters as well as the verdict.
 */
public class LecResult {

    private final LecReport report;

    private final EquivalenceEngine engine;

    private final NetlistSummary reference;

    private final NetlistSummary implementation;

    private final Aig aig;

    LecResult(LecReport report, EquivalenceEngine engine, NetlistSummary reference,
            NetlistSummary implementation, Aig aig) {
        this.report = report;
        this.engine = engine;
        this.reference = reference;
        this.implementation = implementation;
        this.aig = aig;
    }

    public LecReport getReport() {
        return report;
    }

    /** @return The comparison engine, or null when only elaboration was run. */
    public EquivalenceEngine getEngine() {
        return engine;
    }

    public NetlistSummary getReference() {
        return reference;
    }

    public NetlistSummary getImplementation() {
        return implementation;
    }

    /** @return The graph both netlists were elaborated into. */
    public Aig getAig() {
        return aig;
    }

    public int getExitCode() {
        return report.getExitCode();
    }

    public boolean isPass() {
        return report.isPass();
    }
}

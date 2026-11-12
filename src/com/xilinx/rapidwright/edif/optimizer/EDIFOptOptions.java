/*
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

package com.xilinx.rapidwright.edif.optimizer;

import java.io.Writer;

/**
 * Options controlling the netlist optimizations performed by
 * {@link EDIFTools#optimizeNetlist(EDIFNetlist, EDIFOptOptions)}, modeled after Vivado's
 * opt_design -propconst and -sweep.
 */
public class EDIFOptOptions {

    private boolean propConst = true;
    private boolean sweep = true;
    private boolean pushInverters = true;
    private boolean postProcess = true;
    private boolean markModified = true;
    private Writer report;

    public EDIFOptOptions() {
    }

    /** Copy constructor. */
    public EDIFOptOptions(EDIFOptOptions o) {
        propConst = o.propConst;
        sweep = o.sweep;
        pushInverters = o.pushInverters;
        postProcess = o.postProcess;
        markModified = o.markModified;
        report = o.report;
    }

    /** @return True if constant propagation (and the LUT simplifications it implies) runs. */
    public boolean isPropConst() {
        return propConst;
    }

    public EDIFOptOptions setPropConst(boolean propConst) {
        this.propConst = propConst;
        return this;
    }

    /** @return True if dead (unobservable) logic is removed. */
    public boolean isSweep() {
        return sweep;
    }

    public EDIFOptOptions setSweep(boolean sweep) {
        this.sweep = sweep;
        return this;
    }

    /**
     * @return True if inverters are absorbed into the LUTs and programmably-invertible pins they
     *         drive (part of constant propagation).
     */
    public boolean isPushInverters() {
        return pushInverters;
    }

    public EDIFOptOptions setPushInverters(boolean pushInverters) {
        this.pushInverters = pushInverters;
        return this;
    }

    /**
     * @return True if the post-processing step runs (removing remaining inverters by inverting
     *         the LUT that drives them).
     */
    public boolean isPostProcess() {
        return postProcess;
    }

    public EDIFOptOptions setPostProcess(boolean postProcess) {
        this.postProcess = postProcess;
        return this;
    }

    /** @return True if modified cells are annotated with the OPT_MODIFIED property. */
    public boolean isMarkModified() {
        return markModified;
    }

    public EDIFOptOptions setMarkModified(boolean markModified) {
        this.markModified = markModified;
        return this;
    }

    /** @return The writer receiving the optimization report, null if none. */
    public Writer getReport() {
        return report;
    }

    /**
     * Requests a report of every change: one tab-separated line per decision (action, object,
     * cell type, explanation), e.g. a cell removed as constant and the input that made it so, a
     * buffer removed and the net its output was merged into, a LUT rewritten and why each input
     * was dropped, or a cell swept; then the number of each action. The report is written to the
     * given writer (which is flushed, not closed). Off by default.
     * @param report The writer, or null for no report.
     * @return This object.
     */
    public EDIFOptOptions setReport(Writer report) {
        this.report = report;
        return this;
    }
}

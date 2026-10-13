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

package com.xilinx.rapidwright.edif;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Statistics reported by the netlist optimizations in {@link EDIFTools}, counted the same way as
 * Vivado's opt_design phase summaries ("Phase X created N cells and removed M cells"): a cell
 * replaced by a cell of a different type counts as both created and removed, while in-place
 * modifications (e.g. a LUT INIT change) are not counted. Static sources (GND/VCC) are not counted.
 */
public class EDIFOptStats {

    /** Optimization phases, named as in Vivado's opt_design log. */
    public enum Phase {
        CONSTANT_PROPAGATION("Constant propagation"),
        SWEEP("Sweep"),
        POST_PROCESSING_NETLIST("Post Processing Netlist");

        private final String title;

        Phase(String title) {
            this.title = title;
        }

        public String getTitle() {
            return title;
        }
    }

    private final Map<Phase, int[]> counts = new LinkedHashMap<>();
    private final Map<String, Long> times = new LinkedHashMap<>();
    private int lutsModified;
    private int pinsInverted;
    private int cellsUniquified;

    public EDIFOptStats() {
        for (Phase p : Phase.values()) {
            counts.put(p, new int[2]);
        }
    }

    public void addCreated(Phase p, int n) {
        counts.get(p)[0] += n;
    }

    public void addRemoved(Phase p, int n) {
        counts.get(p)[1] += n;
    }

    public int getCreated(Phase p) {
        return counts.get(p)[0];
    }

    public int getRemoved(Phase p) {
        return counts.get(p)[1];
    }

    public void addTime(String step, long nanos) {
        times.merge(step, nanos, Long::sum);
    }

    /** @return Elapsed time per step in nanoseconds, in execution order. */
    public Map<String, Long> getTimes() {
        return times;
    }

    public int getLutsModified() {
        return lutsModified;
    }

    void addLutsModified(int n) {
        lutsModified += n;
    }

    public int getPinsInverted() {
        return pinsInverted;
    }

    void addPinsInverted(int n) {
        pinsInverted += n;
    }

    public int getCellsUniquified() {
        return cellsUniquified;
    }

    void addCellsUniquified(int n) {
        cellsUniquified += n;
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<Phase, int[]> e : counts.entrySet()) {
            sb.append("Phase ").append(e.getKey().getTitle()).append(" created ")
                    .append(e.getValue()[0]).append(" cells and removed ")
                    .append(e.getValue()[1]).append(" cells\n");
        }
        sb.append("LUTs modified in place: ").append(lutsModified)
                .append(", pins absorbing an inverter: ").append(pinsInverted)
                .append(", cell definitions uniquified: ").append(cellsUniquified).append("\n");
        for (Map.Entry<String, Long> e : times.entrySet()) {
            sb.append(String.format("  %-28s %10.3fs%n", e.getKey(), e.getValue() / 1e9));
        }
        return sb.toString();
    }
}

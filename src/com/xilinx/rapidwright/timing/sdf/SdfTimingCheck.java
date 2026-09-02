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

package com.xilinx.rapidwright.timing.sdf;

import java.util.Objects;

/**
 * One entry inside a {@link SdfCell}'s {@code (TIMINGCHECK ...)} block.
 *
 * Vivado emits four of SDF's timing checks: {@code SETUPHOLD}, {@code PERIOD}, {@code WIDTH} and,
 * for flip-flops with an asynchronous set or reset, {@code RECREM}. These are not annotated onto the {@code TimingGraph}, but they are retained
 * because they are the device-agnostic way to identify a cell's clock pin: a pin named as the
 * reference event of a {@code PERIOD} or as the second port of a {@code SETUPHOLD} is a clock. That
 * classification drives how an {@code IOPATH} out of a sequential cell is mapped.
 */
public class SdfTimingCheck {

    /** Which timing check this entry describes. */
    public enum Kind {
        /** A combined setup and hold check; carries two delval lists. */
        SETUPHOLD,
        /** A minimum clock period check; carries one delval list. */
        PERIOD,
        /** A minimum pulse width check; carries one delval list. */
        WIDTH,
        /**
         * A combined recovery and removal check on an asynchronous control pin; carries two delval
         * lists and has the same shape as {@link #SETUPHOLD}.
         */
        RECREM
    }

    private final Kind kind;

    private final SdfEdge firstEdge;

    private final String firstPort;

    private final SdfEdge secondEdge;

    private final String secondPort;

    private final SdfDelayValues firstValues;

    private final SdfDelayValues secondValues;

    private final long byteOffset;

    private final long lineNumber;

    /**
     * @param kind Which timing check this is.
     * @param firstEdge Edge qualifier on the first port, or {@link SdfEdge#NONE}.
     * @param firstPort The first port name, escaped and verbatim.
     * @param secondEdge Edge qualifier on the second port, or {@link SdfEdge#NONE} if there is none.
     * @param secondPort The second port name, or null for {@code PERIOD} and {@code WIDTH}.
     * @param firstValues The first delval list.
     * @param secondValues The second delval list, or null for single-value checks.
     * @param byteOffset Byte offset of this entry's opening parenthesis in the source file.
     * @param lineNumber 1-based line number of this entry in the source file.
     */
    public SdfTimingCheck(Kind kind, SdfEdge firstEdge, String firstPort, SdfEdge secondEdge,
            String secondPort, SdfDelayValues firstValues, SdfDelayValues secondValues,
            long byteOffset, long lineNumber) {
        this.kind = Objects.requireNonNull(kind);
        this.firstEdge = Objects.requireNonNull(firstEdge);
        this.firstPort = Objects.requireNonNull(firstPort);
        this.secondEdge = Objects.requireNonNull(secondEdge);
        this.secondPort = secondPort;
        this.firstValues = Objects.requireNonNull(firstValues);
        this.secondValues = secondValues;
        this.byteOffset = byteOffset;
        this.lineNumber = lineNumber;
    }

    /**
     * @return Which timing check this is.
     */
    public Kind getKind() {
        return kind;
    }

    /**
     * @return Edge qualifier on the first port.
     */
    public SdfEdge getFirstEdge() {
        return firstEdge;
    }

    /**
     * @return The first port name, escaped and verbatim. For {@code SETUPHOLD} this is the data
     *         pin; for {@code PERIOD} and {@code WIDTH} it is the pin being checked.
     */
    public String getFirstPort() {
        return firstPort;
    }

    /**
     * @return Edge qualifier on the second port, or {@link SdfEdge#NONE} if there is no second port.
     */
    public SdfEdge getSecondEdge() {
        return secondEdge;
    }

    /**
     * @return The second port name for {@code SETUPHOLD} (the clock pin), or null otherwise.
     */
    public String getSecondPort() {
        return secondPort;
    }

    /**
     * @return The first delval list: the setup values for {@code SETUPHOLD}, or the period or width
     *         for the single-value checks.
     */
    public SdfDelayValues getFirstValues() {
        return firstValues;
    }

    /**
     * @return The hold values for {@code SETUPHOLD}, or null for single-value checks.
     */
    public SdfDelayValues getSecondValues() {
        return secondValues;
    }

    /**
     * Returns the pin this check identifies as a clock, if any.
     *
     * @return For {@code SETUPHOLD} and {@code RECREM} the second (reference) port; for
     *         {@code PERIOD} the first port; null for {@code WIDTH}, which is also emitted for
     *         non-clock pins such as resets.
     */
    public String getClockPort() {
        switch (kind) {
            case SETUPHOLD:
            case RECREM:
                return secondPort;
            case PERIOD:
                return firstPort;
            default:
                return null;
        }
    }

    /**
     * @return Byte offset of this entry in the source file, for diagnostics.
     */
    public long getByteOffset() {
        return byteOffset;
    }

    /**
     * @return 1-based line number of this entry in the source file, for diagnostics.
     */
    public long getLineNumber() {
        return lineNumber;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        SdfTimingCheck that = (SdfTimingCheck) o;
        // Positional metadata is deliberately excluded; see SdfDelayEntry.equals.
        return kind == that.kind && firstEdge == that.firstEdge && secondEdge == that.secondEdge
                && firstPort.equals(that.firstPort)
                && Objects.equals(secondPort, that.secondPort)
                && firstValues.equals(that.firstValues)
                && Objects.equals(secondValues, that.secondValues);
    }

    @Override
    public int hashCode() {
        return Objects.hash(kind, firstEdge, firstPort, secondEdge, secondPort, firstValues,
                secondValues);
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder("(").append(kind);
        appendPort(sb, firstEdge, firstPort);
        if (secondPort != null) {
            appendPort(sb, secondEdge, secondPort);
        }
        sb.append(' ').append(firstValues);
        if (secondValues != null) {
            sb.append(' ').append(secondValues);
        }
        return sb.append(')').toString();
    }

    private static void appendPort(StringBuilder sb, SdfEdge edge, String port) {
        sb.append(' ');
        if (edge == SdfEdge.NONE) {
            sb.append(port);
        } else {
            sb.append('(').append(edge.getKeyword()).append(' ').append(port).append(')');
        }
    }
}

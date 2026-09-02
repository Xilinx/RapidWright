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
 * One entry inside a {@link SdfCell}'s {@code (DELAY (ABSOLUTE ...))} block: either an
 * {@code IOPATH} (a delay arc internal to the cell) or an {@code INTERCONNECT} (a delay arc between
 * two cell pins, i.e. a routed net segment).
 *
 * All names are stored exactly as they appear in the file, including any Verilog-style backslash
 * escapes. Unescaping is the job of {@link SdfNames} at annotation time; storing an unescaped form
 * here would make writing the file back out ambiguous.
 */
public class SdfDelayEntry {

    /** Which kind of delay arc this entry describes. */
    public enum Kind {
        /** An arc between two pins of the enclosing cell. */
        IOPATH,
        /** An arc between two pins of other cells, i.e. a net segment. */
        INTERCONNECT
    }

    private final Kind kind;

    /**
     * Edge qualifier on the source, or {@link SdfEdge#NONE} for the usual bare form.
     *
     * Vivado writes a qualified source for the asynchronous set and reset arcs of a flip-flop, as
     * in {@code (IOPATH (posedge PRE) Q (162.0:213.0:213.0))}. Those entries also carry a single
     * delay value rather than the usual rise and fall pair.
     */
    private final SdfEdge sourceEdge;

    private final String source;

    private final String destination;

    private final SdfDelayValues values;

    private final long byteOffset;

    private final long lineNumber;

    /**
     * @param kind Whether this is an IOPATH or INTERCONNECT entry.
     * @param sourceEdge Edge qualifier on the source, or {@link SdfEdge#NONE}.
     * @param source For IOPATH, the input pin name; for INTERCONNECT, the driving
     *               {@code instance/pin}. Stored escaped, verbatim.
     * @param destination For IOPATH, the output pin name; for INTERCONNECT, the sink
     *                    {@code instance/pin}. Stored escaped, verbatim.
     * @param values The delay values.
     * @param byteOffset Byte offset of this entry's opening parenthesis in the source file.
     * @param lineNumber 1-based line number of this entry in the source file.
     */
    public SdfDelayEntry(Kind kind, SdfEdge sourceEdge, String source, String destination,
            SdfDelayValues values, long byteOffset, long lineNumber) {
        this.kind = Objects.requireNonNull(kind);
        this.sourceEdge = Objects.requireNonNull(sourceEdge);
        this.source = Objects.requireNonNull(source);
        this.destination = Objects.requireNonNull(destination);
        this.values = Objects.requireNonNull(values);
        this.byteOffset = byteOffset;
        this.lineNumber = lineNumber;
    }

    /**
     * @return Whether this is an IOPATH or INTERCONNECT entry.
     */
    public Kind getKind() {
        return kind;
    }

    /**
     * @return Edge qualifier on the source, or {@link SdfEdge#NONE} if the source was written bare.
     */
    public SdfEdge getSourceEdge() {
        return sourceEdge;
    }

    /**
     * @return The source pin, escaped and verbatim, without any edge qualifier. For IOPATH this is
     *         a bare pin name on the enclosing cell; for INTERCONNECT it is a full
     *         {@code instance/pin} path.
     */
    public String getSource() {
        return source;
    }

    /**
     * @return The destination pin, escaped and verbatim.
     */
    public String getDestination() {
        return destination;
    }

    /**
     * @return The delay values.
     */
    public SdfDelayValues getValues() {
        return values;
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
        SdfDelayEntry that = (SdfDelayEntry) o;
        // Byte offset and line number are positional metadata, not content, and are deliberately
        // excluded so that a parallel parse can be compared against a serial one.
        return kind == that.kind && sourceEdge == that.sourceEdge && source.equals(that.source)
                && destination.equals(that.destination) && values.equals(that.values);
    }

    @Override
    public int hashCode() {
        return Objects.hash(kind, sourceEdge, source, destination, values);
    }

    @Override
    public String toString() {
        String src = sourceEdge == SdfEdge.NONE ? source
                : "(" + sourceEdge.getKeyword() + " " + source + ")";
        return "(" + kind + " " + src + " " + destination + " " + values + ")";
    }
}

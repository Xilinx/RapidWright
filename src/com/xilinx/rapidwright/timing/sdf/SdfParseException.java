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

import java.nio.file.Path;

/**
 * Thrown when an SDF file cannot be parsed.
 *
 * RapidWright's SDF support covers exactly the subset of SDF that Vivado's {@code write_sdf}
 * emits, and rejects everything else rather than ignoring it. A construct RapidWright does not
 * model would otherwise be silently dropped, which would make both a round-trip and a delay
 * annotation quietly wrong. Reporting it loudly, with a position, is the safer failure.
 *
 * Where a position is known the message carries {@code file:line:byteOffset}.
 */
public class SdfParseException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final transient Path file;

    private final long lineNumber;

    private final long byteOffset;

    /**
     * @param message Description of the problem.
     */
    public SdfParseException(String message) {
        this(null, -1, -1, message);
    }

    /**
     * @param message Description of the problem.
     * @param cause Underlying cause.
     */
    public SdfParseException(String message, Throwable cause) {
        super(message, cause);
        this.file = null;
        this.lineNumber = -1;
        this.byteOffset = -1;
    }

    /**
     * @param file The file being parsed, or null if unknown.
     * @param lineNumber 1-based line number, or -1 if unknown.
     * @param byteOffset Byte offset within the file, or -1 if unknown.
     * @param message Description of the problem.
     */
    public SdfParseException(Path file, long lineNumber, long byteOffset, String message) {
        super(format(file, lineNumber, byteOffset, message));
        this.file = file;
        this.lineNumber = lineNumber;
        this.byteOffset = byteOffset;
    }

    private static String format(Path file, long lineNumber, long byteOffset, String message) {
        StringBuilder sb = new StringBuilder("ERROR: ");
        if (file != null) {
            sb.append(file).append(':');
        }
        if (lineNumber >= 0) {
            sb.append(lineNumber).append(':');
        }
        if (byteOffset >= 0) {
            sb.append("byte ").append(byteOffset).append(": ");
        } else if (file != null || lineNumber >= 0) {
            sb.append(' ');
        }
        sb.append(message);
        return sb.toString();
    }

    /**
     * @return The file being parsed, or null if unknown.
     */
    public Path getFile() {
        return file;
    }

    /**
     * @return 1-based line number of the problem, or -1 if unknown.
     */
    public long getLineNumber() {
        return lineNumber;
    }

    /**
     * @return Byte offset of the problem within the file, or -1 if unknown.
     */
    public long getByteOffset() {
        return byteOffset;
    }
}

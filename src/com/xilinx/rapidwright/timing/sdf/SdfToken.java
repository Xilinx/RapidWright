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
 * A token together with the position at which it started.
 *
 * The position is part of the token's identity, not just diagnostic decoration: the parallel parser
 * hands the token that begins one worker's chunk to the preceding worker as a stop marker, and that
 * handshake is only sound if two tokens with the same text at different positions compare unequal.
 */
public class SdfToken {

    /** The token text, with any backslash escapes preserved. */
    public final String text;

    /** Byte offset of the token's first character within the file. */
    public final long byteOffset;

    /** 1-based line number of the token's first character. */
    public final long lineNumber;

    /**
     * @param text The token text.
     * @param byteOffset Byte offset of the token's first character.
     * @param lineNumber 1-based line number of the token's first character.
     */
    public SdfToken(String text, long byteOffset, long lineNumber) {
        this.text = Objects.requireNonNull(text);
        this.byteOffset = byteOffset;
        this.lineNumber = lineNumber;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        SdfToken other = (SdfToken) o;
        // The line number is derived from the byte offset, so it is not part of the comparison.
        return byteOffset == other.byteOffset && text.equals(other.text);
    }

    @Override
    public int hashCode() {
        return Objects.hash(text, byteOffset);
    }

    @Override
    public String toString() {
        String displayText = text;
        if (text.length() > 120) {
            displayText = text.substring(0, 100) + "[shortened, length is " + text.length() + "]";
        }
        return displayText + "@" + byteOffset + " (line " + lineNumber + ")";
    }
}

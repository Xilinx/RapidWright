/*
 *
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

package com.xilinx.rapidwright.timing.family;

import java.nio.charset.StandardCharsets;

/**
 * Checksum of timing data: a 64-bit FNV-1a hash of the data's values, see
 * {@link TimingDataFile.Section#getChecksum()}.
 * <p>
 * Values are hashed as big-endian bytes: an int as 4 bytes, a short as 2, a float as the 4 bytes of
 * {@link Float#floatToIntBits(float)}, a String as its UTF-8 bytes followed by a 0 byte (a null
 * String as one 0xFF byte), and an array as its length followed by its elements.
 */
public final class DataChecksum {

    private static final long FNV_OFFSET_BASIS = 0xcbf29ce484222325L;
    private static final long FNV_PRIME = 0x100000001b3L;

    private long hash = FNV_OFFSET_BASIS;

    DataChecksum addByte(int b) {
        hash ^= (b & 0xFF);
        hash *= FNV_PRIME;
        return this;
    }

    public DataChecksum add(int v) {
        return addByte(v >>> 24).addByte(v >>> 16).addByte(v >>> 8).addByte(v);
    }

    public DataChecksum add(short v) {
        return addByte(v >>> 8).addByte(v);
    }

    public DataChecksum add(float v) {
        return add(Float.floatToIntBits(v));
    }

    public DataChecksum add(String s) {
        if (s == null) {
            return addByte(0xFF);
        }
        for (byte b : s.getBytes(StandardCharsets.UTF_8)) {
            addByte(b);
        }
        return addByte(0);
    }

    public DataChecksum add(int[] values) {
        add(values.length);
        for (int v : values) {
            add(v);
        }
        return this;
    }

    public DataChecksum add(short[] values) {
        add(values.length);
        for (short v : values) {
            add(v);
        }
        return this;
    }

    public DataChecksum add(float[] values) {
        add(values.length);
        for (float v : values) {
            add(v);
        }
        return this;
    }

    public DataChecksum add(float[][] values) {
        add(values.length);
        for (float[] v : values) {
            add(v);
        }
        return this;
    }

    public DataChecksum add(String[] values) {
        add(values.length);
        for (String v : values) {
            add(v);
        }
        return this;
    }

    /**
     * @return The checksum of the values added so far.
     */
    public long getValue() {
        return hash;
    }
}

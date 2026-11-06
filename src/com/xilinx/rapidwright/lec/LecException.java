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
 * Signals a condition that prevents the equivalence check from producing a
 * verdict at all, such as an unreadable input file, an unusable command line or
 * a missing solver back end.
 *
 * This is distinct from a netlist difference: differences are accumulated into
 * the {@link LecReport} and yield a FAIL verdict, whereas a LecException aborts
 * the run with the tool error exit code.
 */
public class LecException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public LecException(String message) {
        super(message);
    }

    public LecException(String message, Throwable cause) {
        super(message, cause);
    }
}

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

import java.util.Arrays;

import com.xilinx.rapidwright.edif.EDIFTools;

/**
 * Canonical names for the things the two netlists are compared against.
 *
 * Terminals correspond one to one by name between the two netlists, and that
 * correspondence is the fixed reference frame of the whole comparison. These
 * keys are what let a function built from the reference netlist and a function built
 * from the implementation netlist be expressed over the same variables. A
 * terminal output pin becomes a primary input of the shared graph named by its
 * key; if the key matches, the variable is shared.
 *
 * <h2>Stable and unstable keys</h2>
 *
 * A key is <i>stable</i> when it is guaranteed to mean the same thing in both
 * netlists. Terminal pins and top level ports are stable, because terminals
 * correspond by name. A name derived from a combinational cloud instance is
 * <i>not</i> stable, because instance naming in the cloud may differ
 * arbitrarily. Comparing two cones whose support includes an
 * unstable variable proves nothing, so such cones are reported as inconclusive
 * rather than as differing. See {@link #unstable(String)}.
 */
public class TerminalKey {

    /** Prefix marking a variable that cannot be matched across the two netlists. */
    public static final String UNSTABLE_PREFIX = "!";

    /** Key of the variable standing in for a broken combinational loop. */
    public static final String LOOP_PREFIX = UNSTABLE_PREFIX + "loop:";

    /** Key prefix for a net with drivers that cannot be named stably. */
    public static final String MULTI_DRIVER_PREFIX = UNSTABLE_PREFIX + "multi:";

    /** Key prefix for the output of an unmodelled cell inside the cloud. */
    public static final String OPAQUE_PREFIX = UNSTABLE_PREFIX + "opaque:";

    /**
     * Key prefix for the output of a register a retiming certificate defines.
     * Its variable is private to one netlist, so a retimed register that keeps
     * an old name cannot be confused with its namesake, and the certificate
     * check substitutes it away; one left over would taint the cone.
     */
    public static final String RETIMED_PREFIX = UNSTABLE_PREFIX + "retimed:";

    private TerminalKey() {
    }

    /**
     * The key of a terminal cell pin, which is its full hierarchical path.
     *
     * Terminals are assumed to keep their hierarchical position between the two
     * netlists, so the full path is a valid identity. A terminal present in one
     * netlist and absent in the other is reported as a terminal set difference,
     * which is what makes an accidental move of a terminal visible rather than
     * silent.
     */
    public static String forPin(String hierarchicalInstName, String pinName) {
        if (hierarchicalInstName == null || hierarchicalInstName.isEmpty()) {
            return pinName;
        }
        return hierarchicalInstName + EDIFTools.EDIF_HIER_SEP + pinName;
    }

    /**
     * The key of a top level port bit. Top level ports are terminals, and
     * are named by the port bit alone since they have no instance path.
     */
    public static String forTopPort(String portBitName) {
        return portBitName;
    }

    /**
     * The key of a net driven by several leaf outputs. Built from the sorted
     * driver keys so that it is independent of the order drivers were found in,
     * and therefore identical in both netlists whenever the driver set is. Each
     * key is length prefixed, since a name can contain any separator.
     */
    public static String forMultiDriver(String[] driverPinKeys) {
        String[] sorted = Arrays.copyOf(driverPinKeys, driverPinKeys.length);
        Arrays.sort(sorted);
        StringBuilder sb = new StringBuilder(MULTI_DRIVER_PREFIX);
        for (String key : sorted) {
            sb.append(key.length()).append(':').append(key);
        }
        return sb.toString();
    }

    /**
     * @return The key of a register output pin that a retiming certificate
     *         defines, private to one netlist.
     */
    public static String forRetimed(String scope, String pinKey) {
        return RETIMED_PREFIX + scope + ":" + pinKey;
    }

    /**
     * Marks a key as unmatchable across the two netlists. Any cone whose support
     * reaches such a variable can only be reported inconclusive.
     */
    public static String unstable(String key) {
        return key.startsWith(UNSTABLE_PREFIX) ? key : UNSTABLE_PREFIX + key;
    }

    /**
     * @return True if the key names a variable that cannot be assumed to mean
     *         the same thing in both netlists.
     */
    public static boolean isStable(String key) {
        return !key.startsWith(UNSTABLE_PREFIX);
    }
}

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

import java.util.Collections;
import java.util.Map;

/**
 * The outcome of comparing one pair of cones, with enough detail to localise a
 * difference to a pin, its differing sources and a counter-example.
 */
public class ConeVerdict {

    /** What was established about the pair. */
    public enum Kind {
        /** The two functions are equal on every input assignment. */
        EQUIVALENT,
        /** The two functions differ, and a witnessing assignment is available. */
        DIFFERENT,
        /**
         * Neither could be established. Either the comparison ran out of time or
         * size budget, or the cone reaches a variable that cannot be matched
         * between the two netlists, so nothing about it can be proven.
         */
        INCONCLUSIVE
    }

    /** How the verdict was reached, for the summary counters. */
    public enum Provenance {
        /** The two cones are the same graph node. */
        STRUCTURAL_HASH,
        /** The two cones are provably opposite. */
        STRUCTURAL_COMPLEMENT,
        /** A simulation pattern separated them. */
        SIMULATION,
        /** The solver proved them equal. */
        SAT_PROOF,
        /** The solver found a differing assignment. */
        SAT_COUNTEREXAMPLE,
        /** The comparison was abandoned. */
        ABANDONED
    }

    private final Kind kind;

    private final Provenance provenance;

    private final String reason;

    private final Map<String, Boolean> counterExample;

    private final String[] refOnlyInputs;

    private final String[] impOnlyInputs;

    private final String[] taintedInputs;

    ConeVerdict(Kind kind, Provenance provenance, String reason,
            Map<String, Boolean> counterExample, String[] refOnlyInputs, String[] impOnlyInputs,
            String[] taintedInputs) {
        this.kind = kind;
        this.provenance = provenance;
        this.reason = reason;
        this.counterExample = counterExample;
        this.refOnlyInputs = refOnlyInputs;
        this.impOnlyInputs = impOnlyInputs;
        this.taintedInputs = taintedInputs;
    }

    static ConeVerdict equivalent(Provenance provenance) {
        return new ConeVerdict(Kind.EQUIVALENT, provenance, null, null, null, null, null);
    }

    public Kind getKind() {
        return kind;
    }

    public Provenance getProvenance() {
        return provenance;
    }

    /** @return Why the comparison ended as it did, or null when equivalent. */
    public String getReason() {
        return reason;
    }

    /**
     * @return An input assignment on which the two functions disagree, by source
     *         terminal name, or null if none was produced.
     */
    public Map<String, Boolean> getCounterExample() {
        return counterExample == null ? Collections.<String, Boolean>emptyMap() : counterExample;
    }

    /** @return Source terminals the reference cone reaches and the other does not. */
    public String[] getRefOnlyInputs() {
        return refOnlyInputs == null ? new String[0] : refOnlyInputs;
    }

    /** @return Source terminals the implementation cone reaches and the other does not. */
    public String[] getImpOnlyInputs() {
        return impOnlyInputs == null ? new String[0] : impOnlyInputs;
    }

    /**
     * @return Variables in the cone that cannot be matched between the two
     *         netlists, which is why an otherwise differing cone is reported
     *         inconclusive.
     */
    public String[] getTaintedInputs() {
        return taintedInputs == null ? new String[0] : taintedInputs;
    }
}

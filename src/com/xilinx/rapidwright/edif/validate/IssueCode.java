/*
 *
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
package com.xilinx.rapidwright.edif.validate;

/**
 * Stable identifiers for each class of inconsistency the
 * {@link NetlistValidator} detects. The default {@link Severity} travels
 * with the code but can be escalated (e.g. warnings-as-errors) by the caller.
 */
public enum IssueCode {
    // A. Netlist / Design / top-level
    NULL_DESIGN(Severity.ERROR),
    NULL_TOP_CELL(Severity.ERROR),
    TOP_CELL_NOT_IN_NETLIST(Severity.ERROR),
    TOP_CELL_IN_PRIMITIVES_LIB(Severity.ERROR),
    TOP_INST_STALE(Severity.WARNING),
    MISSING_PART_PROPERTY(Severity.WARNING),

    // B. Library
    LIBRARY_NETLIST_BACKPTR_BROKEN(Severity.ERROR),
    LIBRARY_KEY_MISMATCH(Severity.ERROR),
    CELL_LIBRARY_BACKPTR_BROKEN(Severity.ERROR),
    CELL_IN_MULTIPLE_LIBRARIES(Severity.ERROR),
    CIRCULAR_LIBRARY_DEP(Severity.ERROR),

    // C. Cell
    CELL_MAP_KEY_MISMATCH(Severity.ERROR),
    PORT_MAP_KEY_MISMATCH(Severity.ERROR),
    INTERNAL_PORTMAP_STALE(Severity.ERROR),
    INTERNAL_PORTMAP_MISSING(Severity.WARNING),
    EMPTY_OBJECT_NAME(Severity.ERROR),

    // D. CellInst
    CELLINST_PARENT_BROKEN(Severity.ERROR),
    CELLINST_NULL_CELLTYPE(Severity.ERROR),
    INST_DANGLING_CELLTYPE(Severity.ERROR),
    VIEWREF_MISMATCH(Severity.WARNING),
    BLACKBOX_HAS_CONTENTS(Severity.WARNING),
    INSTANTIATION_COUNT_DRIFT(Severity.INFO),

    // E. Port
    PORT_PARENT_BROKEN(Severity.ERROR),
    PORT_MALFORMED_BUS_NAME(Severity.ERROR),
    PORT_NONPOSITIVE_WIDTH(Severity.ERROR),
    PORT_WIDTH_MISMATCH(Severity.ERROR),
    PORT_NULL_DIRECTION(Severity.ERROR),

    // F. Net
    NET_PARENT_BROKEN(Severity.ERROR),
    NET_MULTI_DRIVER(Severity.ERROR),
    // A net that only appears multi-driven because of a Vivado "lopt"
    // logic-optimization output pin; a benign post-optimization artifact.
    NET_MULTI_DRIVER_LOPT(Severity.INFO),
    // Undriven-with-sinks is legal and common in OOC / black-box / emulation
    // netlists, so it is informational rather than a warning.
    NET_UNDRIVEN(Severity.INFO),
    NET_EMPTY(Severity.INFO),
    NET_NAME_LEGALIZATION_COLLISION(Severity.ERROR),

    // G. PortInst
    PORTINST_PARENTNET_BROKEN(Severity.ERROR),
    PORTINST_DUAL_MEMBERSHIP_BROKEN(Severity.ERROR),
    PORTINST_NULL_PORT(Severity.ERROR),
    PORTINST_PORT_WRONG_CELL(Severity.ERROR),
    PORTINST_INDEX_MISMATCH(Severity.ERROR),
    PORTINST_NAME_DESYNC(Severity.ERROR),
    PORTINST_DUPLICATE_ON_NET(Severity.ERROR),
    PORT_BIT_MULTIPLY_CONNECTED(Severity.ERROR),

    // H. Name legalization (sibling-scope collisions)
    NAME_LEGALIZATION_COLLISION(Severity.ERROR),

    // I. Hierarchy
    CYCLIC_HIERARCHY(Severity.ERROR),
    UNREACHABLE_CELL(Severity.INFO);

    private final Severity defaultSeverity;

    IssueCode(Severity defaultSeverity) {
        this.defaultSeverity = defaultSeverity;
    }

    public Severity getDefaultSeverity() {
        return defaultSeverity;
    }
}

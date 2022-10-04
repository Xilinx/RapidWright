/*
 * Copyright (c) 2022, Advanced Micro Devices, Inc.
 * All rights reserved.
 *
 * Author: Chris Lavin, AMD AECG Research Labs.
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

package com.xilinx.rapidwright.edif.compare;

/**
 * An enumeration of all the checked difference types between two netlists in
 * {@link EDIFNetlistComparator}
 */
public enum EDIFDiffType {
    PROPERTY_OWNER,
    PROPERTY_VALUE,
    PROPERTY_TYPE,
    PROPERTY_MISSING,
    PROPERTY_EXTRA,
    PORT_NAME,
    PORT_BUSNAME,
    PORT_DIRECTION,
    PORT_WIDTH,
    PORT_MISSING,
    PORT_EXTRA,
    PORT_LEFT_RANGE_LIMIT,
    PORT_RIGHT_RANGE_LIMIT,
    PORT_ENDIANNESS,
    NET_NAME,
    NET_MISSING,
    NET_EXTRA,
    NET_PORT_INST_NAME,
    NET_PORT_INST_DIRECTION,
    NET_PORT_INST_PORT,
    NET_PORT_INST_FULLNAME,
    NET_PORT_INST_INDEX,
    NET_PORT_INST_INSTNAME,
    NET_PORT_INST_MISSING,
    NET_PORT_INST_EXTRA,
    INST_NAME,
    INST_VIEWREF,
    INST_MISSING,
    INST_EXTRA,
    CELL_NAME,
    CELL_VIEWREF,
    CELL_MISSING,
    CELL_EXTRA,
    LIBRARY_NAME,
    LIBRARY_MISSING,
    LIBRARY_EXTRA;

    private boolean isMissingType;

    private boolean isExtraType;

    private boolean isNonNullMismatch;

    private boolean isNameType;

    private boolean isViewrefType;

    private EDIFDiffType() {
        this.isMissingType = this.name().endsWith("_MISSING");
        this.isExtraType = this.name().endsWith("_EXTRA");
        this.isNameType = this.name().endsWith("NAME");
        this.isViewrefType = this.name().endsWith("_VIEWREF");
        this.isNonNullMismatch = !isMissingType && !isExtraType;
    }

    /**
     * @return the isMissingType
     */
    public boolean isMissingType() {
        return isMissingType;
    }

    /**
     * @return the isExtraType
     */
    public boolean isExtraType() {
        return isExtraType;
    }

    /**
     * @return the isNonNullMismatch
     */
    public boolean isNonNullMismatch() {
        return isNonNullMismatch;
    }

    /**
     * @return the isNameType
     */
    public boolean isNameType() {
        return isNameType;
    }

    /**
     * @return the isViewrefType
     */
    public boolean isViewrefType() {
        return isViewrefType;
    }
}

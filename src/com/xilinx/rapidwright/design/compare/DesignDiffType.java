/*
 * Copyright (c) 2024, Advanced Micro Devices, Inc.
 * All rights reserved.
 *
 * Author: Chris Lavin, AMD Research and Advanced Development.
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
package com.xilinx.rapidwright.design.compare;

import java.util.EnumSet;
import java.util.Set;

/**
 * Types of design differences that are detected using DesignComparator.
 *
 */
public enum DesignDiffType {

    DESIGN_PARTNAME,
    SITEINST_MISSING,
    SITEINST_EXTRA,
    SITEINST_NAME,
    SITEINST_TYPE,
    PLACED_CELL_MISSING, 
    PLACED_CELL_EXTRA, 
    PLACED_CELL_TYPE,
    PLACED_CELL_NAME,
    SITEPIP_MISSING,
    SITEPIP_EXTRA,
    SITEPIP_INPIN_NAME,
    SITEWIRE_NET_MISSING,
    SITEWIRE_NET_EXTRA,
    SITEWIRE_NET_NAME,
    NET_MISSING,
    NET_EXTRA,
    PIP_MISSING,
    PIP_EXTRA,
    PIP_FLAGS;
    
    
    private boolean isMissingType;

    private boolean isExtraType;

    private boolean isNonNullMismatch;

    private boolean isNameType;

    private static Set<DesignDiffType> siteInstParentTypes = EnumSet.of(SITEINST_TYPE,
            PLACED_CELL_MISSING, PLACED_CELL_EXTRA, PLACED_CELL_TYPE, PLACED_CELL_NAME,
            SITEPIP_MISSING, SITEPIP_EXTRA, SITEPIP_INPIN_NAME, SITEWIRE_NET_MISSING, SITEWIRE_NET_EXTRA,
            SITEWIRE_NET_NAME);

    private static Set<DesignDiffType> netParentTypes = EnumSet.of(PIP_EXTRA, PIP_FLAGS, PIP_MISSING);

    private DesignDiffType() {
        this.isMissingType = this.name().endsWith("_MISSING");
        this.isExtraType = this.name().endsWith("_EXTRA");
        this.isNameType = this.name().endsWith("NAME");
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

    public boolean isNetParentContext() {
        return netParentTypes.contains(this);
    }

    public boolean isSiteInstParentContext() {
        return siteInstParentTypes.contains(this);
    }
}

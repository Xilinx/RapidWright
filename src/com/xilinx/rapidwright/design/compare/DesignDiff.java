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

import com.xilinx.rapidwright.design.Design;
import com.xilinx.rapidwright.design.Net;
import com.xilinx.rapidwright.design.SiteInst;

/**
 * Stores a design difference found from DesignComparator.
 * 
 */
public class DesignDiff {

    private DesignDiffType type;

    private Object gold;

    private Object test;

    private Object contextParent;

    private String notEqualString;

    public DesignDiff(DesignDiffType type, Object gold, Object test, Object contextParent, String notEqualString) {
        this.type = type;
        this.gold = gold;
        this.test = test;
        this.contextParent = contextParent;
        this.notEqualString = notEqualString;
    }

    public String getClassName() {
        if (gold != null)
            return gold.getClass().getSimpleName();
        if (test != null)
            return test.getClass().getSimpleName();
        return null;
    }

    public String getContext() {
        if (contextParent == null)
            return "";
        if (type.isSiteInstParentContext()) {
            SiteInst si = (SiteInst) contextParent;
            return " in SiteInst placed at " + si.getSiteName();
        }
        if (type.isNetParentContext()) {
            Net net = (Net) contextParent;
            return " in Net " + net.getName();
        }
        if (contextParent instanceof Design) {
            return " in Design " + ((Design) contextParent).getName();
        }
        throw new RuntimeException("ERROR: Unhandled DesignDiffType: " + type);
    }

    public String toString() {
        StringBuilder sb = new StringBuilder();
        if (type.isMissingType()) {
            sb.append("Missing ");
            sb.append(getClassName());
            sb.append(" ");
            sb.append(gold);
            if (notEqualString.length() > 0) {
                sb.append(" (");
                sb.append(notEqualString);
                sb.append(")");
            }
        } else if (type.isExtraType()) {
            sb.append("Extra ");
            sb.append(getClassName());
            sb.append(" ");
            sb.append(test);
        } else if (type.isNonNullMismatch()) {
            sb.append("Mismatch found");
            if (!notEqualString.isEmpty()) {
                sb.append(" (" + notEqualString + ")");
            }
            sb.append(", expected ");
            sb.append(gold);
            sb.append(", but found ");
            sb.append(test);
        }
        sb.append(getContext());

        return sb.toString();
    }
}

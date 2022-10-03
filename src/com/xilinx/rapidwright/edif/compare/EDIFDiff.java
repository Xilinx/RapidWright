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

import com.xilinx.rapidwright.edif.EDIFCell;
import com.xilinx.rapidwright.edif.EDIFLibrary;

public class EDIFDiff {

    private EDIFDiffType type;

    private Object gold;

    private Object test;

    private String className;

    private EDIFCell parentCell;

    private EDIFLibrary parentLibrary;

    public EDIFDiff(EDIFDiffType type, Object gold, Object test, String className,
            EDIFCell parentCell, EDIFLibrary parentLibrary) {
        this.type = type;
        this.gold = gold;
        this.test = test;
        this.className = className;
        this.parentCell = parentCell;
        this.parentLibrary = parentLibrary;
    }

    public String getContext() {
        if (parentCell == null) return " in Library " + parentLibrary;
        return " in Cell " + parentCell.getName() + " from Library " + parentLibrary;
    }

    public String toString() {
        StringBuilder sb = new StringBuilder();
        if (type.isMissingType()) {
            sb.append("Missing ");
            sb.append(className);
            sb.append(" ");
            sb.append(gold);
        } else if (type.isExtraType()) {
            sb.append("Extra ");
            sb.append(className);
            sb.append(" ");
            sb.append(test);
        } else if (type.isNonNullMismatch()) {
            sb.append("Mismatch found, expected ");
            sb.append(gold);
            sb.append(", but found ");
            sb.append(test);
        }
        sb.append(getContext());

        return sb.toString();
    }
}

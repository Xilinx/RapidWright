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
import com.xilinx.rapidwright.edif.EDIFCellInst;
import com.xilinx.rapidwright.edif.EDIFLibrary;

/**
 * This is a helper class for {@link EDIFNetlistComparator} that encapsulates
 * the reference information for a difference found between two netlists.
 *
 */
public class EDIFDiff {

    private EDIFDiffType type;

    private Object gold;

    private Object test;

    private EDIFCell parentCell;

    private EDIFLibrary parentLibrary;

    private String notEqualString;

    private EDIFCellInst sourceInst;

    private String propertyKey;

    public EDIFDiff(EDIFDiffType type, Object gold, Object test, EDIFCell parentCell,
            EDIFLibrary parentLibrary, String notEqualString, 
            EDIFCellInst sourceInst, String propertyKey) {
        this.type = type;
        this.gold = gold;
        this.test = test;
        this.parentCell = parentCell;
        this.parentLibrary = parentLibrary;
        this.notEqualString = notEqualString;
        this.sourceInst = sourceInst;
        this.propertyKey = propertyKey;
    }

    public EDIFCellInst getSourceInst() {
        return sourceInst;
    }

    public String getPropertyKey() {
        return propertyKey;
    }

    public String getContext() {
        if (parentLibrary == null) return "";
        if (parentCell == null) return " in Library " + parentLibrary;
        return " in Cell " + parentCell.getName() + " from Library " + parentLibrary;
    }

    public String getClassName() {
        if (gold != null)
            return gold.getClass().getSimpleName();
        if (test != null)
            return test.getClass().getSimpleName();
        return null;
    }
    
    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();

        if (type.isMissingType()) {
            sb.append("Missing ")
              .append(getClassName()).append(' ')
              .append(gold);

            if (notEqualString != null && !notEqualString.isEmpty()) {
                sb.append(" (").append(notEqualString).append(')');
            }
        } else if (type.isExtraType()) {
            sb.append("Extra ")
              .append(getClassName()).append(' ')
              .append(test);
        } else if (type.isNonNullMismatch()) {
            sb.append("Mismatch found (")
              .append(notEqualString).append("), expected ")
              .append(gold).append(", but found ").append(test);
        }

        sb.append(getContext());

        if (sourceInst != null)
            sb.append(" inst=").append(sourceInst.getName());

        if (propertyKey != null && !propertyKey.isEmpty())
            sb.append(" property=").append(propertyKey);

        return sb.toString();
    }
}

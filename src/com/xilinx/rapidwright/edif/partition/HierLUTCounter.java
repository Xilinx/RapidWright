/*
 * Copyright (c) 2026, Advanced Micro Devices, Inc.
 * All rights reserved.
 *
 * Author: Perry Newlin
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

package com.xilinx.rapidwright.edif.partition;

import java.util.Map;

import com.xilinx.rapidwright.design.tools.LUTTools;
import com.xilinx.rapidwright.edif.EDIFCell;
import com.xilinx.rapidwright.edif.EDIFCellInst;
import com.xilinx.rapidwright.edif.EDIFHierCellInst;

/**
 * Supports LUT counting at different hierarchy levels for a given
 * EDIF or DCP input file.
 */
public final class HierLUTCounter {

    /**
     * Recursively counts LUTs in the hierarchy with caching.
     * Useful for netlist coarsening in partitioning flows on
     * monolithic netlists.
     * 
     * @param hierInst instance to analyze
     * @param cellTypeCache cache of cell type to LUT count
     * @param instanceLutCounts map to store per-instance counts (can be null)
     * @return total LUT count for this instance
     */
    public static Integer computeLUTCount(EDIFHierCellInst hierInst,
            Map<EDIFCell, Integer> cellTypeCache,
            Map<EDIFHierCellInst, Integer> instanceLutCounts) {
        Integer totalLuts = 0;
        for (EDIFCellInst childInst : hierInst.getCellType().getCellInsts()) {
            // Leaf cell: check if it's a LUT primitive
            if (childInst.getCellType().isLeafCellOrBlackBox()) {
                int lutValue = LUTTools.isCellALUT(childInst) ? 1 : 0;
                totalLuts += lutValue;
            // Cache hit: reuse previously computed count for this cell type
            } else if (cellTypeCache.containsKey(childInst.getCellType())) {
                Integer cachedValue = cellTypeCache.get(childInst.getCellType());
                totalLuts += cachedValue;
                // Fill per-instance counts from cache if tracking instances
                if (instanceLutCounts != null) {
                    EDIFHierCellInst childHierInst = hierInst.getChild(childInst);
                    populateFromCache(childHierInst, cellTypeCache, instanceLutCounts);
                }
            // Cache miss: recurse into hierarchical cell
            } else {
                totalLuts += computeLUTCount(hierInst.getChild(childInst),
                        cellTypeCache, instanceLutCounts);
            }
        }

        // Store result in cache and check for netlist consistency
        Integer previousCount = cellTypeCache.put(hierInst.getCellType(), totalLuts);
        if (previousCount != null && !previousCount.equals(totalLuts)) {
            throw new RuntimeException("ERROR: Inconsistent netlist detected - "
                    + "cell type " + hierInst.getCellType().getName()
                    + " has conflicting LUT counts");
        }

        // Track this instance's count if per-instance tracking enabled
        if (instanceLutCounts != null) {
            instanceLutCounts.put(hierInst, totalLuts);
        }
        return totalLuts;
    }

    /**
     * Fills per-instance LUT counts from cached cell type values.
     * 
     * @param hierInst instance whose subtree to populate
     * @param cellTypeCache cache with precomputed counts
     * @param instanceLutCounts map to populate
     */
    public static void populateFromCache(EDIFHierCellInst hierInst,
            Map<EDIFCell, Integer> cellTypeCache,
            Map<EDIFHierCellInst, Integer> instanceLutCounts) {
        if (instanceLutCounts == null) {
            return;
        }

        Integer lutCount = cellTypeCache.get(hierInst.getCellType());
        if (lutCount != null) {
            instanceLutCounts.put(hierInst, lutCount);
        }

        // Recurse into children if not a leaf
        if (!hierInst.getCellType().isLeafCellOrBlackBox()) {
            for (EDIFCellInst childInst : hierInst.getCellType().getCellInsts()) {
                populateFromCache(hierInst.getChild(childInst), cellTypeCache, instanceLutCounts);
            }
        }
    }
}

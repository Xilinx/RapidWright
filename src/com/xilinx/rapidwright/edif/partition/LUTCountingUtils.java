/*
 *
 * Copyright (c) 2025, Advanced Micro Devices, Inc.
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

import com.xilinx.rapidwright.edif.EDIFCell;
import com.xilinx.rapidwright.edif.EDIFCellInst;
import com.xilinx.rapidwright.edif.EDIFHierCellInst;

/**
 * LUT counting utilities for partition, allows us to traverse monolithic netlists
 * and count LUTs per hierarchical instance, with caching to avoid recomputation.
 */
public final class LUTCountingUtils {

    static long dbgCacheHits = 0;
    static long dbgRecursiveCalls = 0;
    static long dbgInstMapWrites = 0;
    static long dbgCacheHitInstMapWrites = 0;
    static int dbgCacheHitLogBudget = 10;
    static long dbgSubtreePopulations = 0;
    static long dbgSubtreeInstances = 0;

    private LUTCountingUtils() {
    }

    /**
     * Populates instance map from cache. When we hit a cache entry, we already know 
     * the LUT count for that cell type, quickly fill instMap for entire subtree.
     * 
     * @param hierInst Hierarchical instance
     * @param cellTypeCache Cell type to LUT count cache
     * @param instMap Instance to LUT count map
     */
    public static void populateFromCache(EDIFHierCellInst hierInst,
            Map<EDIFCell, Integer> cellTypeCache,
            Map<EDIFHierCellInst, Integer> instMap) {
        if (instMap == null) {
            return;
        }
        Integer lutCount = cellTypeCache.get(hierInst.getCellType());
        if (lutCount != null) {
            instMap.put(hierInst, lutCount);
            dbgInstMapWrites++;
            dbgSubtreeInstances++;
        }
        if (!hierInst.getCellType().isLeafCellOrBlackBox()) {
            for (EDIFCellInst childInst : hierInst.getCellType().getCellInsts()) {
                populateFromCache(hierInst.getChild(childInst), cellTypeCache, instMap);
            }
        }
    }

    /**
     * Recursively computes LUT count for hierarchical cell instance. Utilize caching 
     * to avoid recalculating known cell types.
     * 
     * @param hierInst Hierarchical cell instance
     * @param cellTypeCache Cell type cache
     * @param instMap Instance map
     * @return LUT count
     */
    public static Integer computeLUTCount(EDIFHierCellInst hierInst,
            Map<EDIFCell, Integer> cellTypeCache,
            Map<EDIFHierCellInst, Integer> instMap) {
        Integer totalLUTs = 0;
        for (EDIFCellInst childInst : hierInst.getCellType().getCellInsts()) {
            if (childInst.getCellType().isLeafCellOrBlackBox()) {
                int lutVal = logicDiscoveryPolicy.isLogicLUT(
                        childInst.getCellType().getName()) ? 1 : 0;
                totalLUTs += lutVal;
            } else if (cellTypeCache.containsKey(childInst.getCellType())) {
                dbgCacheHits++;
                Integer cachedVal = cellTypeCache.get(childInst.getCellType());
                EDIFHierCellInst childHierInst = hierInst.getChild(childInst);
                if (dbgCacheHitLogBudget > 0) {
                    PartitionTools.logPartitionerDebug(System.err,
                            "cache hit -> parentPath=%s childInst=%s cellType=%s "
                            + "childPath=%s cachedLUTs=%d",
                            hierInst.toString(), childInst.getName(),
                            childInst.getCellType().getName(),
                            childHierInst.toString(), cachedVal);
                    dbgCacheHitLogBudget--;
                }
                totalLUTs += cachedVal;
                if (instMap != null) {
                    long beforeSubtree = dbgSubtreeInstances;
                    populateFromCache(childHierInst, cellTypeCache, instMap);
                    dbgCacheHitInstMapWrites++;
                    dbgSubtreePopulations++;
                    long subtreeCount = dbgSubtreeInstances - beforeSubtree;
                    if (dbgSubtreePopulations <= 5) {
                        PartitionTools.logPartitionerDebug(System.err,
                                "subtree populated -> childPath=%s subtreeInstances=%d",
                                childHierInst.toString(), subtreeCount);
                    }
                }
            } else {
                dbgRecursiveCalls++;
                totalLUTs += computeLUTCount(hierInst.getChild(childInst),
                        cellTypeCache, instMap);
            }
        }
        Integer prevCount = cellTypeCache.put(hierInst.getCellType(), totalLUTs);
        if (prevCount != null && !prevCount.equals(totalLUTs)) {
            throw new RuntimeException("ERROR: Inconsistent netlist");
        }
        if (instMap != null) {
            instMap.put(hierInst, totalLUTs);
            dbgInstMapWrites++;
        }
        return totalLUTs;
    }
}

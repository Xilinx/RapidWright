/*
 * Copyright (c) 2022 Xilinx, Inc.
 * All rights reserved.
 *
 * Author: Chris Lavin, Xilinx Research Labs.
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
package com.xilinx.rapidwright.design.merge;

import com.xilinx.rapidwright.design.Net;
import com.xilinx.rapidwright.design.SiteInst;
import com.xilinx.rapidwright.edif.EDIFCellInst;
import com.xilinx.rapidwright.edif.EDIFNet;
import com.xilinx.rapidwright.edif.EDIFPort;

/**
 * Provides a standardized set of APIs to coordinate design merging.  Allows the user to implement 
 * customized design merge behavior by extending this abstract class.  RapidWright provides
 * a default merging behavior in {@link DefaultDesignMerger}.  These methods are only called by
 * {@link MergeDesigns#mergeDesigns(com.xilinx.rapidwright.design.Design...)} when the two objects
 * collide in their respective namespace.
 */
public abstract class AbstractDesignMerger {

    public abstract void mergePorts(EDIFPort p0, EDIFPort p1);
    
    public abstract void mergeLogicalNets(EDIFNet n0, EDIFNet n1);
    
    public abstract void mergeCellInsts(EDIFCellInst i0, EDIFCellInst i1);
    
    public abstract void mergeSiteInsts(SiteInst s0, SiteInst s1);

    public abstract void mergePhysicalNets(Net n0, Net n1);    
}

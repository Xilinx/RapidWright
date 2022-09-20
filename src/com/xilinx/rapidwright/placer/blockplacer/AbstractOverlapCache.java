/*
 * Copyright (c) 2021-2022, Xilinx, Inc.
 * Copyright (c) 2022, Advanced Micro Devices, Inc.
 * All rights reserved.
 *
 * Author: Jakob Wenzel, Xilinx Research Labs.
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
package com.xilinx.rapidwright.placer.blockplacer;

import java.util.Collection;
import java.util.List;

import com.xilinx.rapidwright.design.AbstractModuleInst;

/**
 * Manages overlap detection inside a Block Placer. This gets called to check if a moved module overlaps any other
 * modules. It has callbacks to get notified of accepted/rejected moves.
 */
public abstract class AbstractOverlapCache<PlacementT, ModuleInstT extends AbstractModuleInst<?,PlacementT,? super ModuleInstT>> {

    public abstract void unplace(ModuleInstT mii);

    public abstract void place(ModuleInstT mii);

    public abstract boolean isValidPlacement(ModuleInstT mii);

    public abstract List<ModuleInstT> getAllOverlaps(ModuleInstT mii);

    protected void enterOverlaps(ModuleInstT mii, Collection<ModuleInstT> l, List<ModuleInstT> overlaps) {
        if (mii.getPlacement() == null) {
            throw new RuntimeException(mii+" is not placed!");
        }
        for (ModuleInstT other : l) {
            if (other == mii) {
                continue;
            }
            if (other.getPlacement() == null) {
                continue;
            }
            if (mii.overlaps(other)) {
                overlaps.add(other);
            }
        }
    }

    protected boolean doesNotOverlapAny(ModuleInstT mii, Collection<ModuleInstT> l) {
        if (mii.getPlacement() == null) {
            return true;
        }
        for (ModuleInstT other : l) {
            if (other == mii) {
                continue;
            }
            if (other.getPlacement() == null) {
                continue;
            }
            if (mii.overlaps(other)) {
                return false;
            }
        }
        return true;
    }

    public abstract void printStats();
}

/*
 * Copyright (c) 2021 Xilinx, Inc.
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

import com.xilinx.rapidwright.design.ModuleImplsInstance;

public abstract class AbstractOverlapCache {
    public abstract void unPlace(ModuleImplsInstance mii);

    public abstract void place(ModuleImplsInstance mii);

    public abstract boolean isValidPlacement(ModuleImplsInstance mii);

    protected boolean doesNotOverlapAny(ModuleImplsInstance mii, Collection<ModuleImplsInstance> l) {
        if (mii.getPlacement() == null) {
            return true;
        }
        for (ModuleImplsInstance other : l) {
            if (other == mii) {
                continue;
            }
            if (other.getPlacement() == null) {
                continue;
            }
            if (mii.getPlacement().placement == other.getPlacement().placement) {
                return false;
            }
            if (mii.overlaps(other)){
                return false;
            }
        }
        return true;
    }

    public abstract void printStats();
}

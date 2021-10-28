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
package com.xilinx.rapidwright.design;

import java.util.Objects;

import com.xilinx.rapidwright.device.Site;

/**
 * A placement of a {@link ModuleImplsInst}. This consists of implementation index (indexes into the list
 * of implementations of the corresponding {@link ModuleImpls}) as well as an anchor Site.
 */
public class ModulePlacement {
    public final int implementationIndex;
    public final Site placement;

    public ModulePlacement(int implementationIndex, Site placement) {
        this.implementationIndex = implementationIndex;
        this.placement = Objects.requireNonNull(placement);
    }

    @Override
    public String toString() {
        return "impl "+implementationIndex+" at "+placement;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        ModulePlacement that = (ModulePlacement) o;
        return implementationIndex == that.implementationIndex && placement.equals(that.placement);
    }

    @Override
    public int hashCode() {
        return Objects.hash(implementationIndex, placement);
    }
}

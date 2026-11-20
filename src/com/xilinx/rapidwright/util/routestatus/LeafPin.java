/*
 * Copyright (c) 2026, Advanced Micro Devices, Inc.
 * All rights reserved.
 *
 * Author: Chris Lavin, Advanced Micro Devices, Inc.
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

package com.xilinx.rapidwright.util.routestatus;

import static com.xilinx.rapidwright.util.routestatus.RouteStatusNetlist.getPhysicalPins;
import static com.xilinx.rapidwright.util.routestatus.RouteStatusNetlist.hasPhysicalPin;

import java.util.Set;

import com.xilinx.rapidwright.design.Cell;
import com.xilinx.rapidwright.edif.EDIFHierPortInst;

/** A pin on a leaf cell, with its cell (null if there is none). */
final class LeafPin {

    final EDIFHierPortInst pin;
    final Cell cell;

    LeafPin(EDIFHierPortInst pin, Cell cell) {
        this.pin = pin;
        this.cell = cell;
    }

    boolean isOutput() {
        return pin.isOutput();
    }

    boolean isPlaced() {
        return cell != null && cell.isPlaced();
    }

    String getName() {
        return pin.getPortInst().getName();
    }

    Set<String> getPhysicalPins() {
        return RouteStatusNetlist.getPhysicalPins(cell, getName());
    }

    boolean hasPhysicalPin() {
        return !getPhysicalPins().isEmpty();
    }
}

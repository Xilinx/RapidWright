/*
 * Copyright (c) 2025, Advanced Micro Devices, Inc.
 * All rights reserved.
 *
 * Author: Jakob Wenzel, Technical University of Darmstadt
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

package com.xilinx.rapidwright.design.xdc.parser;

public enum ObjType {
    Design("current_design"),
    Cell("get_cells"),
    Port("get_ports"),
    Pin("get_pins"),
    PBlock("get_pblocks");

    private final String xdcCommand;

    ObjType(String xdcCommand) {
        this.xdcCommand = xdcCommand;
    }

    public String getXdcCommand() {
        return xdcCommand;
    }
}

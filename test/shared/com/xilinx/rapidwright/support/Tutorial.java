/*
 * Copyright (c) 2023, Advanced Micro Devices, Inc.
 * All rights reserved.
 *
 * Author: Chris Lavin, AMD Research and Advanced Development.
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

package com.xilinx.rapidwright.support;

public enum Tutorial {

    RWROUTE_TIMING_DRIVEN("RWRoute_timing_driven_routing"),
    RWROUTE_WIRELENGTH_DRIVEN("RWRoute_wirelength_driven_routing"),
    RWROUTE_PARTIAL("RWRoute_partial_routing"),
    REPORT_TIMING_EXAMPLE("ReportTimingExample"),
    REUSING_TIMING_CLOSED_LOGIC_AS_A_SHELL("ReusingTimingClosedLogicAsAShell"),
    SLR_CROSSER_DCP_CREATOR("SLR_Crosser_DCP_Creator_Tutorial"),
    PREIMPLEMENTED_MODULES_PART_I("PreImplemented_Modules_Part_I"),
    PREIMPLEMENTED_MODULES_PART_II("PreImplemented_Modules_Part_II"),
    CREATE_AND_USE_AN_SLR_BRIDGE("Create_and_Use_an_SLR_Bridge"),
    ;

    private String filename;

    private Tutorial(String filename) {
        this.filename = filename;
    }

    public String getFileName() {
        return filename;
    }
}

/*
 * Copyright (c) 2026, Advanced Micro Devices, Inc.
 * All rights reserved.
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
 */

package com.xilinx.rapidwright.timing;

import com.xilinx.rapidwright.device.SiteTypeEnum;

/**
 * Topology-only fallback DelayModel used when no per-series delay data ships
 * for the device (e.g., Versal). Returns zero for every lookup so that
 * TimingGraph can still build the structural graph; callers are expected to
 * overwrite delays via TimingEdge.set*Delay(...) if accurate timing is needed.
 */
class NullDelayModel implements DelayModel {

    static final NullDelayModel INSTANCE = new NullDelayModel();

    private NullDelayModel() {}

    @Override
    public Short getIntraSiteDelay(SiteTypeEnum siteTypeName, String frBelPin, String toBelPin) {
        return 0;
    }

    @Override
    public short getLogicDelay(short belIdx, String frBelPin, String toBelPin, int encodedConfig) {
        return 0;
    }

    @Override
    public short getLogicDelay(short belIdx, String frBelPin, String toBelPin) {
        return 0;
    }

    @Override
    public int getEncodedConfigCode(String value) {
        return 0;
    }

    @Override
    public short getBELIndex(String belName) {
        return 0;
    }
}

/*
 *
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

package com.xilinx.rapidwright.timing.series7;

import com.xilinx.rapidwright.device.Device;
import com.xilinx.rapidwright.device.Series;
import com.xilinx.rapidwright.timing.delayestimator.DelayEstimator;
import com.xilinx.rapidwright.timing.family.Corner;
import com.xilinx.rapidwright.timing.family.RouterKnobs;
import com.xilinx.rapidwright.timing.family.SpeedGrade;
import com.xilinx.rapidwright.timing.family.TimingFamilies;
import com.xilinx.rapidwright.timing.family.TimingFamily;

/**
 * The timing family of 7 series devices: a stub, since there is no timing data for them. Its
 * factories throw {@link UnsupportedOperationException}.
 */
public class Series7TimingFamily implements TimingFamily {

    private static final Series7TimingFamily INSTANCE = new Series7TimingFamily();

    protected Series7TimingFamily() {
    }

    public static Series7TimingFamily getInstance() {
        return INSTANCE;
    }

    private UnsupportedOperationException noData() {
        return new UnsupportedOperationException(TimingFamilies.getUnsupportedMessage(getSeries()));
    }

    @Override
    public Series getSeries() {
        return Series.Series7;
    }

    @Override
    public DelayEstimator createDelayEstimator(Device device, SpeedGrade speedGrade, Corner corner,
                                               boolean useUTurnNodes) {
        throw noData();
    }

    @Override
    public RouterKnobs getRouterKnobs() {
        return RouterKnobs.DEFAULT;
    }
}

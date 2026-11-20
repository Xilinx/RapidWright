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

package com.xilinx.rapidwright.timing.versal;
import com.xilinx.rapidwright.device.Device;
import com.xilinx.rapidwright.timing.family.ClassBasedDelayEstimator;
import com.xilinx.rapidwright.timing.family.Corner;
import com.xilinx.rapidwright.timing.family.InterconnectParams;

/**
 * Delay estimator for Versal devices: a {@link ClassBasedDelayEstimator} whose node classes are the
 * {@code VersalNodeClass}es, with the interconnect parameters of the device's generation (see
 * {@link VersalTimingFamily}), scaled to the device and the part's speed grade. Nodes that turn back at device
 * edges and SLR boundaries are charged the delay of their span like any other node, so none are masked.
 */
public class VersalDelayEstimator extends ClassBasedDelayEstimator {

    /**
     * Creates the delay estimator of a Versal device from given interconnect parameters.
     * @param device The Versal device.
     * @param params The parameters, whose node classes must be the {@link VersalNodeClass}es.
     * @param speedGrade The speed grade of the part, e.g. -2MP, or null for the grade the parameters
     *                   are for.
     */
    VersalDelayEstimator(Device device, InterconnectParams params, String speedGrade) {
        super(device, checkNodeClasses(params, VersalNodeClass.values()), VersalNodeClass.getWireClasses(device),
                getScale(device, params, speedGrade, "Versal delay data"),
                getFanoutScale(device, params, speedGrade, "Versal delay data"));
        // The lookahead and pre-route delays of the data are those of SLOW_MAX
        setCornerReference(VersalTimingFamily.getInterconnectParams(VersalTimingFamily.getGeneration(device),
                Corner.SLOW_MAX));
    }
}

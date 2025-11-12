/*
 *
 * Copyright (c) 2022-2025, Advanced Micro Devices, Inc.
 * All rights reserved.
 *
 * Author: Andrew Butt
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
package com.xilinx.rapidwright.rwroute;

import com.xilinx.rapidwright.design.Design;
import com.xilinx.rapidwright.design.SitePinInst;
import com.xilinx.rapidwright.device.IntentCode;
import com.xilinx.rapidwright.device.Node;
import com.xilinx.rapidwright.device.Series;
import com.xilinx.rapidwright.timing.delayestimator.DelayEstimatorBase;
import com.xilinx.rapidwright.timing.delayestimator.InterconnectInfo;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

public class HoldFixRouter extends PartialRouter {

    static List<IntentCode> defaultDisallowedNodeTypesVersal = new ArrayList<>();
    static {
        defaultDisallowedNodeTypesVersal.add(IntentCode.NODE_VQUAD);
        defaultDisallowedNodeTypesVersal.add(IntentCode.NODE_HQUAD);
        defaultDisallowedNodeTypesVersal.add(IntentCode.NODE_VLONG7);
        defaultDisallowedNodeTypesVersal.add(IntentCode.NODE_VLONG12);
        defaultDisallowedNodeTypesVersal.add(IntentCode.NODE_HLONG6);
        defaultDisallowedNodeTypesVersal.add(IntentCode.NODE_HLONG10);
    }

    static List<IntentCode> defaultDisallowedNodeTypesUltraScale = new ArrayList<>();
    static {
        defaultDisallowedNodeTypesUltraScale.add(IntentCode.NODE_VQUAD);
        defaultDisallowedNodeTypesUltraScale.add(IntentCode.NODE_HQUAD);
        defaultDisallowedNodeTypesUltraScale.add(IntentCode.NODE_VLONG);
        defaultDisallowedNodeTypesUltraScale.add(IntentCode.NODE_HLONG);
    }

    private final List<IntentCode> disallowedNodeTypes;

    public HoldFixRouter(Design design, RWRouteConfig config, Collection<SitePinInst> pinsToRoute, boolean softPreserve,
                         List<IntentCode> disallowedNodeTypes) {
        super(design, config, pinsToRoute, softPreserve);
        this.disallowedNodeTypes = disallowedNodeTypes;
    }

    public HoldFixRouter(Design design, RWRouteConfig config, Collection<SitePinInst> pinsToRoute, boolean softPreserve) {
        super(design, config, pinsToRoute, softPreserve);
        if (design.getSeries().equals(Series.Versal)) {
            disallowedNodeTypes = defaultDisallowedNodeTypesVersal;
        } else if (design.getSeries().equals(Series.UltraScale) || design.getSeries().equals(Series.UltraScalePlus)) {
            disallowedNodeTypes = defaultDisallowedNodeTypesUltraScale;
        } else {
            disallowedNodeTypes = new ArrayList<>();
        }
    }

    public HoldFixRouter(Design design, RWRouteConfig config, Collection<SitePinInst> pinsToRoute) {
        this(design, config, pinsToRoute, false);
    }

    protected static class RouteNodeGraphHoldFix extends RouteNodeGraphPartial {
        private final List<IntentCode> disallowedNodeTypes;

        public RouteNodeGraphHoldFix(Design design, RWRouteConfig config, List<IntentCode> disallowedNodeTypes) {
            super(design, config);
            this.disallowedNodeTypes = disallowedNodeTypes;
        }

        @Override
        protected boolean isExcluded(RouteNode parent, Node child) {
            IntentCode ic = child.getIntentCode();
            if (disallowedNodeTypes.contains(ic)) {
                return true;
            }
            return super.isExcluded(parent, child);
        }
    }

    protected static class RouteNodeGraphHoldFixTimingDriven extends RouteNodeGraphPartialTimingDriven {
        private final List<IntentCode> disallowedNodeTypes;

        public RouteNodeGraphHoldFixTimingDriven(Design design,
                                                 RWRouteConfig config,
                                                 DelayEstimatorBase<InterconnectInfo> delayEstimator,
                                                 List<IntentCode> disallowedNodeTypes) {
            super(design, config, delayEstimator);
            this.disallowedNodeTypes = disallowedNodeTypes;
        }

        @Override
        protected boolean isExcluded(RouteNode parent, Node child) {
            IntentCode ic = child.getIntentCode();
            if (disallowedNodeTypes.contains(ic)) {
                return true;
            }
            return super.isExcluded(parent, child);
        }
    }

    @Override
    protected RouteNodeGraph createRouteNodeGraph() {
        if (config.isTimingDriven()) {
            /* An instantiated delay estimator that is used to calculate delay of routing resources */
            DelayEstimatorBase<InterconnectInfo> estimator = new DelayEstimatorBase<InterconnectInfo>(
                    design.getDevice(), new InterconnectInfo(), config.isUseUTurnNodes(), 0);
            return new RouteNodeGraphHoldFixTimingDriven(design, config, estimator, disallowedNodeTypes);
        } else {
            return new RouteNodeGraphHoldFix(design, config, disallowedNodeTypes);
        }
    }
}

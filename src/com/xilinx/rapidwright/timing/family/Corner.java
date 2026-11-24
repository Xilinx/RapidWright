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

package com.xilinx.rapidwright.timing.family;

/**
 * A timing corner: a process corner (slow or fast) combined with the delay taken at it (maximum or
 * minimum). Setup analysis uses {@link #SLOW_MAX} delays; hold analysis uses {@link #FAST_MIN}
 * delays for data paths, with the other corners for the launch and capture clock paths.
 */
public enum Corner {
    /** Slow process corner, maximum delays: setup data paths */
    SLOW_MAX,
    /** Slow process corner, minimum delays: early capture clocks of setup checks */
    SLOW_MIN,
    /** Fast process corner, minimum delays: hold data paths */
    FAST_MIN,
    /** Fast process corner, maximum delays: late capture clocks of hold checks */
    FAST_MAX;
}

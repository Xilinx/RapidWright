/*
 * Copyright (c) 2019 Xilinx, Inc.
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
 *
 */

package com.xilinx.rapidwright.timing;

/**
 * The current set of TimingGroup types including types for each of the basic wire length types, 
 * bounces, pin types, and other.
 */
public enum GroupDelayType {
    SINGLE,
    DOUBLE,
    QUAD,
    LONG,
    PIN_BOUNCE,
    INTERNAL,
    GLOBAL,
    PINFEED,
    OTHER
}

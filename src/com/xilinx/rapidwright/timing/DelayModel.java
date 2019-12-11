/*
 *
 * Copyright (c) 2019 Xilinx, Inc.
 * All rights reserved.
 *
 * Author: Pongstorn Maidee, Xilinx Research Labs.
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


import java.util.List;


/**
 * Provide delay lookup for logic and intra site delay.
 *
 * Never construct DelayModel directly. Use DelayModelBuilder to guarantee that there is at most one DelayModel.
 *
 * Conventions:
 * A user see a cell with all its input and output pins. But, the user do NOT know
 * if a timing arc between each pair of input and output exists or not.
 * Thus, the user will call the delay model with each possible pair of input/output.
 * If the pair does not correspond to a timing arc, -1 will be returned.
 * the user have to interpret negative values as an indicator that the arc DOES NOT exist.
 *
 * The model is not context-aware. This means 1) a delay of an arc does not change with its load.
 * 2) there is an arc even when its input or output is not connected.
 * When the user see an input pin connected to VCC or GND, the user have to assume the
 * responsibility of replacing the delay with 0.
 *
 * TODO: change interaction with user to reduce runtime. In particular, a list of timing arcs will be
 * returned for the cell passed in by a user.
 */
interface DelayModel {

    /**
     * Get the delay in ps between two bel pins within the given site name.
     *
     * @param siteName The name of the site, such as SLICEL and SLICEM.
     * @param frBelPin The bel pin which is the driver of the connection.  Thus, it must be a bel output pin.
     * The bel name must be included, ie., AFF2/D. An input site pin is considered a valid frBelPin.
     * @param toBelPin The bel pin which is the sink of the connection (a bel input pin, or an output site pin).
     * @return Intra-site delay in ps. Return -1 if the connection does not exist.
     * @throws  IllegalArgumentException if the given siteName is not recognized by the model.
     */
    short getIntraSiteDelay(String siteName, String frBelPin, String toBelPin);

    /**
     * Get the delay between input and output pins of a bel.
     *
     * @param belName  The name of the bel, such as A6LUT and CARRY8.
     * @param frBelPin An input bel pin. It must NOT include bel name.
     * @param toBelPin An output bel pin. It must NOT include bel name.
     * @param config  A list of config_name:value of the bel, ie., {CYINIT_BOT:GND, CARRY_TYPE:SINGLE_CY8}.
     *                What is the possible config_name and its value?
     *                Ones need to look at the logic delay files used for DelayModelSource_text to find that out.
     *                Where to get the config's value from the design?
     *                There is no uniform way to find the value. It is to determined per case.
     *                For example, some configs of carry8 is from bel, while some from cell.
     * @return Logic delay in ps. Return -1 if the connection does not exist.
     * @throws  IllegalArgumentException if the given bel is not recognized by the model.
     */
    short getLogicDelay(String belName, String frBelPin, String toBelPin, List<String> config);
    /**
     * Get the delay between input and output pins of a bel.
     *
     * For parameters' description,
     * please see getLogicDelay(String belName, String frBelPin, String toBelPin, List<String> config).
     */
     short getLogicDelay(String belName, String frBelPin, String toBelPin);
}










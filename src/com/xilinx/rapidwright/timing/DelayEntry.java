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

/**
 * Represent one timing arc built by DelayModelSource to be stored in  DelayModel.
 */
class DelayEntry {
    /**
     * Scope of the connection.
     */
    public String scope;
    /**
     * Source of the connection.
     */
    public String fr;
    /**
     * Sink of the connection.
     */
    public String to;
    /**
     * Delay  of the connection.
     */
    public short  delay;
    /**
     * Valid configs of for this entry.
     */
    public short  config;

    /**
     * Constructor with config parameter.
     * @param s Scope of the arc. This is a bel name for logic delay  or a site name for intra-site delay.
     * @param f Source name of the timing arc.
     * @param t Sink name of the arc.
     * @param d Delay of the arc.
     * @param c Valid configuration of the arc.
     */
    public DelayEntry(String s, String f, String t, short d, short c) {
        scope  = s;
        fr     = f;
        to     = t;
        delay  = d;
        config = c;
    }
    /**
     * Constructor without config parameter.
     * For parameter meaning see the other constructor.
     */
    public DelayEntry(String s, String f, String t, short d) {
        scope  = s;
        fr     = f;
        to     = t;
        delay  = d;
        config = -1;
    }
}

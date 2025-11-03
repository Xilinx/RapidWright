/*
 * Copyright (c) 2022, Advanced Micro Devices, Inc.
 * All rights reserved.
 *
 * Author: Jakob Wenzel
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

package com.xilinx.rapidwright.design.xdc;

import java.util.Locale;

/**
 * A constraint representing a clock
 */
public class ClockConstraint implements Constraint<ClockConstraint>{
    private String clockName;
    private double period;

    private String portName;

    public ClockConstraint(String clockName, double period, String portName) {
        this.clockName = clockName;
        this.period = period;
        this.portName = portName;
    }

    @Override
    public ClockConstraint clone() {
        return new ClockConstraint(clockName, period, portName);
    }

    public String getClockName() {
        return clockName;
    }

    public void setClockName(String clockName) {
        this.clockName = clockName;
    }

    public double getPeriod() {
        return period;
    }

    public void setPeriod(double period) {
        this.period = period;
    }

    public String asXdc() {
        String periodString = String.format(Locale.US, "%.3f", period);
        return "create_clock -period "+periodString+" -name "+clockName+" [get_ports "+portName+"]";
    }

    public String getPortName() {
        return portName;
    }

    public void setPortName(String portName) {
        this.portName = portName;
    }
}

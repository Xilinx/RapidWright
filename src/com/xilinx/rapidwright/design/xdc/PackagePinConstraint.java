/*
 *
 * Copyright (c) 2018-2022, Xilinx, Inc.
 * Copyright (c) 2022, Advanced Micro Devices, Inc.
 * All rights reserved.
 *
 * Author: Chris Lavin, Xilinx Research Labs.
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
/**
 *
 */
package com.xilinx.rapidwright.design.xdc;

import java.util.stream.Stream;

/**
 * Annotates a package pin name with an IO standard
 * Created on: Jan 25, 2018
 */
public class PackagePinConstraint implements Constraint<PackagePinConstraint> {

    private String portName;

    private String packagePin;

    private String ioStandard;

    public PackagePinConstraint() {
    }

    @Override
    public PackagePinConstraint clone() {
        PackagePinConstraint res = new PackagePinConstraint(portName);
        res.setPackagePin(packagePin);
        res.setIOStandard(ioStandard);
        return res;
    }

    public PackagePinConstraint(String portName) {
        setPortName(portName);
    }

    /**
     * @return the name
     */
    public String getPackagePin() {
        return packagePin;
    }

    /**
     * @param packagePin the name to set
     */
    public void setPackagePin(String packagePin) {
        this.packagePin = packagePin;
    }

    /**
     * @return the ioStandard
     */
    public String getIoStandard() {
        return ioStandard;
    }

    /**
     * @param ioStandard the ioStandard to set
     */
    public void setIOStandard(String ioStandard) {
        this.ioStandard = ioStandard;
    }

    public String toString() {
        return packagePin + ":" + ioStandard;
    }

    public String getPortName() {
        return portName;
    }

    public Stream<String> asXdc() {
        Stream.Builder<String> res = Stream.builder();
        if (packagePin!=null) {
            res.add("set_property PACKAGE_PIN "+packagePin+" [get_ports "+portName+"]");
        }
        if (ioStandard!=null) {
            res.add("set_property IOSTANDARD "+ioStandard+" [get_ports "+portName+"]");
        }
        return res.build();
    }

    public void setPortName(String portName) {
        this.portName = portName;
    }
}

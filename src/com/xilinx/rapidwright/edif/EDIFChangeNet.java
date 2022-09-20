/*
 *
 * Copyright (c) 2022, Xilinx, Inc.
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
package com.xilinx.rapidwright.edif;

/**
 * Keeps track of an EDIFNet change such as an add or removal of a EDIFPortInst.
 *
 */
public class EDIFChangeNet extends EDIFChange {

    private String netName;

    private String instName;

    public EDIFChangeNet(EDIFChangeType type, String name, String netName, String instName) {
        super(type, name);
        this.netName = netName;
        this.instName = instName;
    }

    public String getNetName() {
        return netName;
    }

    public String getInstName() {
        return instName;
    }

    @Override
    public int hashCode() {
        final int prime = 31;
        int result = super.hashCode();
        result = prime * result + ((netName == null) ? 0 : netName.hashCode());
        return result;
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj)
            return true;
        if (!super.equals(obj))
            return false;
        if (getClass() != obj.getClass())
            return false;
        EDIFChangeNet other = (EDIFChangeNet) obj;
        if (netName == null) {
            if (other.netName != null)
                return false;
        } else if (!netName.equals(other.netName))
            return false;
        return true;
    }

    @Override
    public String toString() {
        return "EDIFChangeNet [netName=" + netName + ", instName=" + instName + ", type=" + getType()
                + ", portInstName=" + getName() + "]";
    }
}

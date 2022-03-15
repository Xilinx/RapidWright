/* 
 * Copyright (c) 2020 Xilinx, Inc. 
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
 
package com.xilinx.rapidwright.device;

/**
 * Simple pair of ints that act as the start and end wire of a prototype PIP.  Primarily used
 * as a key for a map of PIP types.
 */
public class PIPWires {

    private int startWire;
    
    private int endWire;

    public PIPWires(int startWire, int endWire) {
        super();
        this.startWire = startWire;
        this.endWire = endWire;
    }

    public int getStartWire() {
        return startWire;
    }

    public void setStartWire(int startWire) {
        this.startWire = startWire;
    }

    public int getEndWire() {
        return endWire;
    }

    public void setEndWire(int endWire) {
        this.endWire = endWire;
    }

    @Override
    public int hashCode() {
        final int prime = 31;
        int result = 1;
        result = prime * result + endWire;
        result = prime * result + startWire;
        return result;
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj)
            return true;
        if (obj == null)
            return false;
        if (getClass() != obj.getClass())
            return false;
        PIPWires other = (PIPWires) obj;
        if (endWire != other.endWire)
            return false;
        if (startWire != other.startWire)
            return false;
        return true;
    }
}

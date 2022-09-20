/*
 *
 * Copyright (c) 2017-2022, Xilinx, Inc.
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
package com.xilinx.rapidwright.edif;

/**
 * Placeholder class for an EDIF design.  Keeps
 * track of the top cell.
 *
 * Created on: May 12, 2017
 */
public class EDIFDesign extends EDIFPropertyObject {

    private EDIFCell topCell;

    public EDIFDesign(String name) {
        super(name);
    }

    protected EDIFDesign() {

    }

    /**
     * @return the topCell
     */
    public EDIFCell getTopCell() {
        return topCell;
    }

    /**
     * @param topCell the topCell to set
     */
    public void setTopCell(EDIFCell topCell) {
        this.topCell = topCell;
    }



}

/*
 * Copyright (c) 2024, Advanced Micro Devices, Inc.
 * All rights reserved.
 *
 * Author: Eddie Hung, Advanced Micro Devices, Inc.
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
 * Extension of {@link EDIFLibrary} with setNetlist() and removeCell() methods disabled.
 */
public class EDIFLibraryBuiltin extends EDIFLibrary {
    public EDIFLibraryBuiltin(String name) {
        super(name);
    }

    @Override
    public void setNetlist(EDIFNetlist netlist) {
        throw new UnsupportedOperationException();
    }

    @Override
    public EDIFCell removeCell(EDIFCell cell) {
        throw new UnsupportedOperationException();
    }
}

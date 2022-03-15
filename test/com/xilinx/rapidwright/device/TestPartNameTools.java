/* 
 * Copyright (c) 2021 Xilinx, Inc. 
 * All rights reserved.
 *
 * Author: Jakob Wenzel, Xilinx Research Labs.
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

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

public class TestPartNameTools {
    @Test
    public void testGetPartCase() {
        Assertions.assertEquals(PartNameTools.getPart("xcvu3p-ffvc1517-2-i"), PartNameTools.getPart("xcVu3P-ffVC1517-2-i"));
    }

    @Test
    public void testXazu2egResources() {
        Part p = PartNameTools.getPart("xazu2eg-sfvc784-1");
        Assertions.assertEquals(p.getDsp(), 240);
        Assertions.assertEquals(p.getBlockRams(), 150);
        Assertions.assertEquals(p.getUltraRams(), 0);
        Assertions.assertEquals(p.getLutElements(), 47232);
        Assertions.assertEquals(p.getFlipflops(), 94464);
    }
}

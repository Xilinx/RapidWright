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
 
package com.xilinx.rapidwright.placer;

import java.io.File;

import com.xilinx.rapidwright.design.Design;
import com.xilinx.rapidwright.examples.PicoBlazeArray;
import com.xilinx.rapidwright.placer.blockplacer.BlockPlacer2;
import com.xilinx.rapidwright.support.CheckOpenFiles;
import com.xilinx.rapidwright.support.RapidWrightDCP;
import com.xilinx.rapidwright.tests.CodePerfTracker;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

public class TestBlockPlacer2 {

    private void placeSomePicoBlazeArray(PicoBlazeArray.PicoBlazeArrayCreator<?> arrayCreator) {
        CodePerfTracker t = new CodePerfTracker("PicoBlazeArray Test");
        File srcDir = RapidWrightDCP.getPath("PicoBlazeArray").toFile();
        Design design = arrayCreator.createDesign(srcDir, "xcvu3p-ffvc1517-2-i", t);

        BlockPlacer2<?, ?, ?, ?> placer = arrayCreator.createPlacer(design, null);
        double cost = placer.placeDesign(false);

        Assertions.assertTrue(cost < 400000);

        arrayCreator.lowerToModules(design, t);

        t.stop();
        t.printSummary();

    }

    @Test
    @CheckOpenFiles
    public void placePicoBlazeArrayModules() {
        placeSomePicoBlazeArray(PicoBlazeArray.makeModuleCreator());
    }

    @Test
    @CheckOpenFiles
    public void placePicoBlazeArrayImpls() {
        placeSomePicoBlazeArray(PicoBlazeArray.makeImplsCreator());
    }
}

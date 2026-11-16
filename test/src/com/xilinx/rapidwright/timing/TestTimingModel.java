/*
 * Copyright (c) 2022, Xilinx, Inc.
 * Copyright (c) 2022, Advanced Micro Devices, Inc.
 * All rights reserved.
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

import com.xilinx.rapidwright.design.Cell;
import com.xilinx.rapidwright.design.Design;
import com.xilinx.rapidwright.design.SiteInst;
import com.xilinx.rapidwright.design.SitePinInst;
import com.xilinx.rapidwright.device.Device;
import com.xilinx.rapidwright.device.Tile;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;


public class TestTimingModel {
    @ParameterizedTest
    @CsvSource({
            "xcvu3p,2,0,61,309",
            "xck26,2,0,169,247",
            "xczu7ev,27,240,161,123",
            "vu19p,1,0,58,1242",
    })
    public void checkFindReferenceTile (String deviceName,
                                                int expectedTileX, int expectedTileY,
                                                int expectedCol, int expectedRow) {
        TimingModel model = new TimingModel(Device.getDevice(deviceName));
        model.build();
        Tile tile = model.getRefIntTile();
        System.out.println(deviceName + " " + tile.getTileXCoordinate() + " " + tile.getTileYCoordinate() + " " + tile.getColumn() + " " + tile.getRow());
        // check tile coor that can be visually checked in Vivado gui
        Assertions.assertEquals(expectedTileX,tile.getTileXCoordinate());
        Assertions.assertEquals(expectedTileY,tile.getTileYCoordinate());
        // check tile consistent with what specify in the file
        Assertions.assertEquals(expectedCol,tile.getColumn());
        Assertions.assertEquals(expectedRow,tile.getRow());
    }

    @Test
    public void testSourceIntraSiteDelayTermThroughRoutethru() {
        Design design = new Design("test", "xcvu3p");
        SiteInst si = design.createSiteInst("SLICE_X0Y0");
        // A flip-flop used as a routethru (e.g. by the output of a CARRY8 leaving its site through AQ), as read from a
        // DCP: a cell without an EDIFCellInst
        Cell routethru = new Cell(si.getName() + "/AQ", si.getBEL("AFF"));
        routethru.setType(Cell.FF_ROUTETHRU_TYPE);
        routethru.setRoutethru(true);
        routethru.addPinMapping("D", "D");
        routethru.addPinMapping("Q", "Q");
        si.addCell(routethru);
        Assertions.assertNull(routethru.getEDIFCellInst());
        SitePinInst aq = design.createNet("net").createPin("AQ", si);

        TimingModel model = new TimingModel(design.getDevice());
        model.build();
        // A Q output is neither a MUX nor an _O output: no intra-site hop to describe
        Assertions.assertNull(model.getSourceIntraSiteDelayTerm(aq));
    }
}

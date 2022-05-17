package com.xilinx.rapidwright.util;

import com.xilinx.rapidwright.device.Device;
import com.xilinx.rapidwright.device.Tile;
import com.xilinx.rapidwright.timing.TimingModel;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;


public class TestTimingModel {
    private Tile testOneDevice(String deviceName) {
        TimingModel model = new TimingModel(Device.getDevice(deviceName));
        model.build();
        return model.getRefIntTile();
    }
    @Test
    public void checkFindReferenceTileLocation () {
        Tile tile = null;

        tile = testOneDevice("xcvu3p");
        // check tile coor that can be visually checked in Vivado gui
        Assertions.assertEquals(2,tile.getTileXCoordinate());
        Assertions.assertEquals(0,tile.getTileYCoordinate());
        // check tile consistent with what specify in the file
        Assertions.assertEquals(61,tile.getColumn());
        Assertions.assertEquals(309,tile.getRow());

        tile = testOneDevice("xck26");
        Assertions.assertEquals(2,tile.getTileXCoordinate());
        Assertions.assertEquals(0,tile.getTileYCoordinate());
        Assertions.assertEquals(169,tile.getColumn());
        Assertions.assertEquals(247,tile.getRow());

        tile = testOneDevice("xczu7ev");
        Assertions.assertEquals(66,tile.getTileXCoordinate());
        Assertions.assertEquals(256,tile.getTileYCoordinate());
        Assertions.assertEquals(379,tile.getColumn());
        Assertions.assertEquals(107,tile.getRow());

        tile = testOneDevice("vu19p");
        Assertions.assertEquals(1,tile.getTileXCoordinate());
        Assertions.assertEquals(0,tile.getTileYCoordinate());
        Assertions.assertEquals(58,tile.getColumn());
        Assertions.assertEquals(1242,tile.getRow());
    }
}

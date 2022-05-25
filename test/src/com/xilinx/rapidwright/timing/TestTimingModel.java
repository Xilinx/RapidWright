package com.xilinx.rapidwright.timing;

import com.xilinx.rapidwright.device.Device;
import com.xilinx.rapidwright.device.Tile;
import org.junit.jupiter.api.Assertions;
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
}

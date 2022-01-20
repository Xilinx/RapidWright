package com.xilinx.rapidwright.interchange;

import org.capnproto.MessageBuilder;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import com.xilinx.rapidwright.device.Device;
import com.xilinx.rapidwright.interchange.DeviceResources.Device.CellBelMapping;
import com.xilinx.rapidwright.interchange.DeviceResources.Device.CellBelPinEntry;
import com.xilinx.rapidwright.interchange.DeviceResources.Device.ParameterCellBelPinMaps;

public class TestCellBELMappings {
    
    @Test
    public void testCellBELPinMappings() {
        Enumerator<String> allStrings = new Enumerator<String>();
        MessageBuilder message = new MessageBuilder();
        Device device = Device.getDevice(TestDeviceResources.TEST_DEVICE);
        DeviceResources.Device.Builder devBuilder = message.initRoot(DeviceResources.Device.factory);
        EnumerateCellBelMapping.populateAllPinMappings(device.getName(), device, devBuilder, allStrings);
        
        boolean foundIDDRS = false;
        boolean foundIDDRR = false;
        for(int i=0; i < devBuilder.getCellBelMap().size(); i++) {
            CellBelMapping.Builder mapping = devBuilder.getCellBelMap().get(i);
            if(allStrings.get(mapping.getCell()).equals("IDDR")) {
                Assertions.assertTrue(mapping.hasParameterPins());
               for(ParameterCellBelPinMaps.Builder paramPins : mapping.getParameterPins()) {
                    for(CellBelPinEntry.Builder pinObj : paramPins.getPins()) {
                        Assertions.assertEquals(allStrings.get(pinObj.getBelPin()), "SR");
                        String cellPinName = allStrings.get(pinObj.getCellPin());
                        foundIDDRS = foundIDDRS || cellPinName.equals("S");
                        foundIDDRR = foundIDDRR || cellPinName.equals("R");
                    }
                }
            }
        }
        Assertions.assertTrue(foundIDDRS);
        Assertions.assertTrue(foundIDDRR);
    }
}

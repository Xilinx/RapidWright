package com.xilinx.rapidwright.interchange;

import java.io.IOException;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.xilinx.rapidwright.device.Device;
import com.xilinx.rapidwright.support.CheckOpenFiles;
import com.xilinx.rapidwright.tests.CodePerfTracker;

public class TestDeviceResources {

    public static final String TEST_DEVICE = "xc7a15t";
    
    @Test
    @CheckOpenFiles
    public void testDeviceResources(@TempDir Path tempDir) throws IOException {
        Path capnProtoFile = tempDir.resolve(TEST_DEVICE + ".device");
        Device device = Device.getDevice(TEST_DEVICE);
        DeviceResourcesWriter.writeDeviceResourcesFile(
                TEST_DEVICE, device, CodePerfTracker.SILENT, capnProtoFile.toString());            
        Device.releaseDeviceReferences();
        DeviceResourcesVerifier.verifyDeviceResources(capnProtoFile.toString(), TEST_DEVICE);
    }
    

}

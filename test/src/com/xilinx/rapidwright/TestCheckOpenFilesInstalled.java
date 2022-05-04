package com.xilinx.rapidwright;

import java.io.IOException;

import com.xilinx.rapidwright.support.CheckOpenFilesExtension;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

public class TestCheckOpenFilesInstalled {
    @Test
    @ExtendWith(CheckOpenFilesExtension.CheckOpenFilesWorkingExtension.class)
    public void test() throws IOException {
        //Actual test is in extension
    }
}

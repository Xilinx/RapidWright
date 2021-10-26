package com.xilinx.rapidwright.support;

import java.io.File;
import java.nio.file.Path;
import java.nio.file.Paths;

import com.xilinx.rapidwright.util.FileTools;

public class RapidWrightDCP {
    public static final Path dirPath = Paths.get(FileTools.getRapidWrightPath(),  "test", "RapidWrightDCP");

    public static Path getPath(String name) {
        return dirPath.resolve(name);
    }

    public static String getString(String name) {
        return getPath(name).toString();
    }
}

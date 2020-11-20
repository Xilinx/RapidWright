package com.xilinx.rapidwright.tests;

import java.io.File;
import java.io.BufferedWriter;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Arrays;
import java.util.ArrayList;
import java.util.AbstractMap;

import com.xilinx.rapidwright.device.Device;
import com.xilinx.rapidwright.device.Node;
import com.xilinx.rapidwright.device.Tile;
import com.xilinx.rapidwright.device.PIP;
import com.xilinx.rapidwright.tests.CodePerfTracker;

public class IsNodeTiedTest {
    private static long makeKey(Tile tile, int wire) {
        long key = wire;
        key = (((long)tile.getUniqueAddress()) << 32) | key;
        return key;
    }

    public static void main(String[] args) throws IOException {
        if(args.length != 1) {
            System.out.println("USAGE: <device name>");
            System.out.println("   Example dump of device information for interchange format.");
            return;
        }

        CodePerfTracker t = new CodePerfTracker("Check Node.IsTied: " + args[0]);
        t.useGCToTrackMemory(true);

        t.start("Load Device");
        Device device = Device.getDevice(args[0]);

        t.stop().start("Ask all nodes IsTied");
        int tiedNodeCount = 0;
        for(Tile tile : device.getAllTiles()) {
            for(PIP p : tile.getPIPs()) {
                Node start = p.getStartNode();
                if(start != null && start.isTied()) {
                    tiedNodeCount += 1;
                }

                Node end = p.getEndNode();
                if(end != null && end.isTied()) {
                    tiedNodeCount += 1;
                }
            }
        }

        System.out.printf("Is tied count %d\n", tiedNodeCount);

        t.stop().printSummary();
    }
}


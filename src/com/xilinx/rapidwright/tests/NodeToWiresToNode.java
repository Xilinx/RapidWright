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
import com.xilinx.rapidwright.device.Wire;
import com.xilinx.rapidwright.tests.CodePerfTracker;

public class NodeToWiresToNode {
    private static long makeKey(Tile tile, int wire) {
        long key = wire;
        key = (((long)tile.getUniqueAddress()) << 32) | key;
        return key;
    }

    private static void testNode(Node node) {
        for(Wire wire : node.getAllWiresInNode()) {
            Node nodeFromWire = wire.getNode();
            //if(!node.equals(nodeFromWire)) {
            if(!node.getWireName().equals(nodeFromWire.getWireName())) {
                throw new RuntimeException(String.format("Got different Node on Node -> Wire -> Node, node 1 = %s node 2 = %s", node.getWireName(), nodeFromWire.getWireName()));
            }
        }
    }

    public static void main(String[] args) throws IOException {
        if(args.length != 1) {
            System.out.println("USAGE: <device name>");
            System.out.println("   Example dump of device information for interchange format.");
            return;
        }

        CodePerfTracker t = new CodePerfTracker("Check Node -> Wires -> Node: " + args[0]);
        t.useGCToTrackMemory(true);

        t.start("Load Device");
        Device device = Device.getDevice(args[0]);

        t.stop().start("Test");
        int tiedNodeCount = 0;
        for(Tile tile : device.getAllTiles()) {
            for(PIP p : tile.getPIPs()) {
                Node start = p.getStartNode();
                if(start != null) {
                    testNode(start);
                }

                Node end = p.getEndNode();
                if(end != null) {
                    testNode(end);
                }
            }

            for(int i = 0; i < tile.getWireCount(); ++i) {
                Wire wire = new Wire(tile, i);
                Node node = wire.getNode();
                if(node != null) {
                    testNode(node);
                }
            }
        }

        t.stop().printSummary();
    }
}


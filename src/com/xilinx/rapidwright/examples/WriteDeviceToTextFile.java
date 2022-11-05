package com.xilinx.rapidwright.examples;

import java.io.BufferedWriter;
import java.io.FileWriter;
import java.io.IOException;
import java.io.UncheckedIOException;

import com.xilinx.rapidwright.device.Device;
import com.xilinx.rapidwright.device.Node;
import com.xilinx.rapidwright.device.PIP;
import com.xilinx.rapidwright.device.Site;
import com.xilinx.rapidwright.device.Tile;

public class WriteDeviceToTextFile {

    public static void main(String[] args) {
        String partName = null;
        if (args.length == 0) {
            System.out.println("USAGE: [partname]");
            return;
        } else if (args.length == 1) {
            partName = args[0];
        }
        
        Device device = Device.getDevice(partName);
        try (BufferedWriter bw = new BufferedWriter(new FileWriter(partName + ".txt"))) {
            for (Tile[] tileCol : device.getTiles()) {
                for (Tile tile : tileCol) {
                    bw.write("TILE: " + tile.getName() + " (" + tile.getColumn() + ", " + tile.getRow() + ") \n");
                    for (String wireName : tile.getWireNames()) {
                        bw.write("  WIRE: "  + tile.getName() +"/" + wireName + "\n");
                    }
                    for (PIP pip : tile.getPIPs()) {
                        bw.write("  PIP: " + pip + "\n");
                        Node startNode = pip.getStartNode();
                        if (startNode != null) {
                            bw.write("    START_NODE: " + startNode + "\n");
                            bw.write("      START_NODE_INTENT_CODE: " + startNode.getIntentCode() + "\n");                            
                        }
                        Node endNode = pip.getEndNode();
                        if (endNode != null) {
                            bw.write("    END_NODE: " + endNode + "\n");
                            bw.write("      END_NODE_INTENT_CODE: " + endNode.getIntentCode() + "\n");                            
                        }
                    }
                    for (Site site : tile.getSites()) {
                        bw.write("  SITE: " + site.getName() + "\n");
                        for (int i=0; i < site.getSitePinCount(); i++) {
                            String pinName = site.getPinName(i);
                            boolean isPinInput = i <= site.getHighestInputPinIndex();
                            bw.write("    SITE_PIN: " + pinName + "\n");
                            bw.write("      SITE_PIN_DIRECTION: " + (isPinInput ? "INPUT" : "OUTPUT") + "\n");
                            bw.write("      SITE_PIN_NODE: " + site.getConnectedNode(i) + "\n");
                        }
                    }
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}

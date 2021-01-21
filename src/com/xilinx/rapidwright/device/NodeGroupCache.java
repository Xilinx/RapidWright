package com.xilinx.rapidwright.device;

import java.util.HashSet;
import java.util.Set;

public class NodeGroupCache {
	
	public static boolean isExitNode(Node node) {
		switch(node.getIntentCode()) {
			case SINGLE:
			case DOUBLE:
			case HQUAD:
			case VQUAD:
			case VLONG:
			case HLONG:
			case PINBOUNCE:
			case PINFEED:
				return true;
			case NODE_LOCAL:
				if(node.getWireName().contains("GLOBAL")) {
					return true;
				}
			default:
		}
		return false;
	}
	
	public static boolean isEntryNode(Node node) {
		return !isExitNode(node);
	}
	
	public static void main(String[] args) {
		Device device = Device.getDevice("xcvu3p");
		
		for(Tile tile : device.getAllTiles()) {
			for(PIP pip : tile.getPIPs()) {
				Node start = pip.getStartNode();
				Node end = pip.getEndNode();
			}
		}
	}
}

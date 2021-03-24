/*
 * 
 * Copyright (c) 2021 Xilinx, Inc. 
 * All rights reserved.
 *
 * Author: Chris Lavin, Xilinx Research Labs.
 * 
 */
/**
 * 
 */
package com.xilinx.rapidwright.device;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Used as a compact cached representation of node group clusters. Not for general use. 
 */
public class CompactCluster {
	public long[] clusters;

	public CompactCluster(long[] clusters) {
		this.clusters = clusters;
	}

	public List<Node> getDownstreamNodes(Node referenceNode){
		List<Node> nodes = new ArrayList<>(clusters.length);
		Tile refTile = referenceNode.getTile();
		for(long cluster : clusters) {
			int wire = getWireIndex(cluster);
			int dx = getDx(cluster);
			int dy = getDy(cluster);
			Tile newTile = refTile.getTileXYNeighbor(dx, dy);
			nodes.add(Node.getNode(newTile, wire));
		}
		return nodes;
	}
	
	protected static Long getCompactCluster(int wireIdx, int dx, int dy) {
		return (((long)wireIdx) << 32) | (0xffff0000L & ((long)dx << 16)) 
				| (dy & 0x000000000000ffffL);
	}
	
	protected static int getWireIndex(long compactCluster) {
		return 0xffffffff & (int)(compactCluster >>> 32);
	}

	protected static int getDx(long compactCluster) {
		int dx = 0xffff & (int)(compactCluster >>> 16);
		return (dx << 16) >> 16;
	}
	
	protected static int getDy(long compactCluster) {
		int dy = (int)(0xffff & compactCluster);
		return (dy << 16) >> 16;
	}	

	
	@Override
	public int hashCode() {
		final int prime = 31;
		int result = 1;
		result = prime * result + Arrays.hashCode(clusters);
		return result;
	}

	@Override
	public boolean equals(Object obj) {
		if (this == obj)
			return true;
		if (obj == null)
			return false;
		if (getClass() != obj.getClass())
			return false;
		CompactCluster other = (CompactCluster) obj;
		if (!Arrays.equals(clusters, other.clusters))
			return false;
		return true;
	}
}

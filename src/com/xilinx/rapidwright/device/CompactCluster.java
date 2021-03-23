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

import java.util.Arrays;

/**
 * Created on: Mar 18, 2021
 */
public class CompactCluster {
	public long[] clusters;

	public CompactCluster(long[] clusters) {
		this.clusters = clusters;
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

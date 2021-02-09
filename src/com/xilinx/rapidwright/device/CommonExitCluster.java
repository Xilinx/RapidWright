/* 
 * Copyright (c) 2021 Xilinx, Inc. 
 * All rights reserved.
 *
 * Author: Chris Lavin, Xilinx Research Labs.
 *  
 * This file is part of RapidWright. 
 * 
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 *     http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 * 
 */
package com.xilinx.rapidwright.device;

import java.util.ArrayList;

public class CommonExitCluster extends ArrayList<NodeGroup> {

	/**
	 * 
	 */
	private static final long serialVersionUID = -4526368058806707149L;

	/**
	 * Empty constructor
	 */
	public CommonExitCluster() {
		
	}
	
	/**
	 * Creates a single node group with a single node to define the cluster.  
	 * @param single The singlar exit node that defines the cluster
	 */
	public CommonExitCluster(Node single) {
		add(new NodeGroup(null, single));
	}
}

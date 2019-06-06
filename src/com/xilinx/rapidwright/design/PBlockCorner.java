/* 
 * Copyright (c) 2018 Xilinx, Inc. 
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

package com.xilinx.rapidwright.design;

import com.xilinx.rapidwright.device.Device;

/**
 * An attempt to hide the two different types of objects (Site and ClockRegion)
 * from the view of the PBlockRange.  
 * @author clavin
 *
 */
public interface PBlockCorner {

	public static final String CLOCK_REGION = "CLOCKREGION";
			
	public String getName();
	
	public int getInstanceX();
	
	public int getInstanceY();
	
	public Device getDevice();
}

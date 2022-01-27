/*
 * 
 * Copyright (c) 2021 Ghent University. 
 * All rights reserved.
 *
 * Author: Yun Zhou, Ghent University.
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

package com.xilinx.rapidwright.rwroute;

import com.xilinx.rapidwright.design.Net;
import com.xilinx.rapidwright.device.IntentCode;
import com.xilinx.rapidwright.device.Node;

public enum RoutableType {
	/** 
	 * Denotes {@link Routable} Objects that correspond to the output pins of {@link Net} Objects, 
	 * typically the source {@link Routable} Objects of {@link Connection} Objects.
	 */
	PINFEED_O,
	/** 
	 * Denotes {@link Routable} Objects that correspond to input pins of {@link Net} Objects, 
	 * typically the sink {@link Routable} Objects of {@link Connection} Objects. 
	 */
	PINFEED_I,
	/** 
	 * Denotes {@link Routable} Objects that are created based on {@link Node} Objects 
	 * that have an {@link IntentCode} of NODE_PINBOUNCE.
	 */
	PINBOUNCE,
	/** 
	 * Denotes other wiring {@link Routable} Objects 
	 * that are created for routing {@link Connection} Objects.
	 */
	WIRE
	
}

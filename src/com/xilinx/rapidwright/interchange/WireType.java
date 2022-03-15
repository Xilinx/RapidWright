/* 
 * Copyright (c) 2021 Xilinx, Inc. 
 * All rights reserved.
 *
 * Author: gatecat
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
 
package com.xilinx.rapidwright.interchange;

import com.xilinx.rapidwright.device.IntentCode;
import com.xilinx.rapidwright.interchange.DeviceResources.Device.WireCategory;

public class WireType {
    public static WireCategory intentToCategory(IntentCode intent) {
        switch (intent) {
            case NODE_OUTPUT:
            case NODE_DEDICATED:
            case NODE_PINFEED:
            case NODE_LOCAL:
            case NODE_PINBOUNCE:
            case NODE_OPTDELAY:
            case NODE_CLE_OUTPUT:
            case NODE_INT_INTERFACE:
            case NODE_LAGUNA_DATA:
            case NODE_LAGUNA_OUTPUT:
            case INPUT:
            case CLKPIN:
            case OUTPUT:
            case PINFEED:
            case BOUNCEIN:
            case LUTINPUT:
            case IOBOUTPUT:
            case PINBOUNCE:
            case PINFEEDR:
            case OPTDELAY:
            case IOBIN2OUT:
            case IOBINPUT:
            case PADINPUT:
            case PADOUTPUT:
            case BUFINP2OUT:
            case GENERIC:
            case NODE_CLE_CTRL:
            case NODE_IRI:
            case NODE_INTF_CTRL:
            case NODE_OPTDELAY_MUX:
            case NODE_CLE_LNODE:
            case INTENT_DEFAULT:
                return WireCategory.SPECIAL;
            case NODE_GLOBAL_VDISTR:
            case NODE_GLOBAL_HROUTE:
            case NODE_GLOBAL_HDISTR:
            case NODE_GLOBAL_VROUTE:
            case NODE_GLOBAL_LEAF:
            case NODE_GLOBAL_BUFG:
            case GLOBAL:
            case BUFGROUT:
            case NODE_GLOBAL_VDISTR_LVL2:
            case NODE_GLOBAL_VDISTR_LVL1:
            case NODE_GLOBAL_GCLK:
            case NODE_GLOBAL_HROUTE_HSR:
            case NODE_GLOBAL_HDISTR_HSR:
            case NODE_GLOBAL_HDISTR_LOCAL:
                return WireCategory.GLOBAL;
            default:
                return WireCategory.GENERAL;
        }
    }
}

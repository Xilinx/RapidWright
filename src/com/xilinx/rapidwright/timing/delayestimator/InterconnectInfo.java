/*
 *
 * Copyright (c) 2021 Xilinx, Inc.
 * All rights reserved.
 *
 * Author: Pongstorn Maidee, Xilinx Research Labs.
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

package com.xilinx.rapidwright.timing.delayestimator;


import java.util.HashMap;
import java.util.Map;

import com.xilinx.rapidwright.timing.GroupDelayType;

/**
 * Encapsulate interconnect information for a device family.
 * The info must be just accurate enough for estimating the min delay.
 * This is for Ultrascale+.
 */
// TODO: Consider moving this to a file.  Leaving them in code is easier to be extended for future generations.
// TODO: The key issue is it is not certain what features are enough to capture future generations.
public class InterconnectInfo implements java.io.Serializable {

    // TODO: Can we get this association from device model?
    private Map<String,String> nodeToSitePin;


    /**
     * Define direction of routing resource in reference to INT tile coordinate.
     */
    // up is in increasing INT tile coordinate direction
    public enum Direction {
        U, // up
        D, // down
        S; // same place
    }


    /**
     * Define orientation of routing resource.
     * Override must be a superset of this list
     */
    public enum Orientation {
        VERTICAL,
        HORIZONTAL,
        INPUT,
        OUTPUT,
        LOCAL
    };


    /**
     * Define routing resources.
     * Override must be a superset. length can be changed.
     */
    public enum NodeGroupType {
        // Enum ensure there is no duplication of each type stored in the tables.
        // Need to distinguish between ver and hor. Thus we can't use GroupDelayType.

        VERT_SINGLE (Orientation.VERTICAL, GroupDelayType.SINGLE,(short) 1,'S'),
        VERT_DOUBLE (Orientation.VERTICAL, GroupDelayType.DOUBLE,(short) 2,'D'),
        VERT_QUAD   (Orientation.VERTICAL, GroupDelayType.QUAD,(short) 4,'Q'),
        VERT_LONG   (Orientation.VERTICAL, GroupDelayType.LONG,(short) 12,'L'),

        HORT_SINGLE  (Orientation.HORIZONTAL, GroupDelayType.SINGLE,(short) 1,'s'),
        HORT_DOUBLE  (Orientation.HORIZONTAL, GroupDelayType.DOUBLE,(short) 1,'d'),
        HORT_QUAD    (Orientation.HORIZONTAL, GroupDelayType.QUAD,(short) 2,'q'),
        HORT_LONG    (Orientation.HORIZONTAL, GroupDelayType.LONG,(short) 6,'l'),

        CLE_OUT      (Orientation.OUTPUT, GroupDelayType.OTHER,(short) 0, '-'),
        CLE_IN       (Orientation.INPUT, GroupDelayType.PINFEED,(short) 0, '-'),
        // BOUNCE jump within the same side. Thus, they are of no used in delay estimator and are not modeled.
        INTERNAL_SINGLE (Orientation.LOCAL, GroupDelayType.PIN_BOUNCE,(short) 0, 'i'),
        // global has a very high delay and thus is not on a min delay path. Thus, it only use as a source for a lookup.
        // global go to PIN_BOUNCE and PINFEED, ie., INT_X0Y0/BYPASS_E9  - NODE_PINBOUNCE and INT_X0Y0/IMUX_E9  - NODE_PINFEED
        // global node drive only one side of INT_TILE, but its driver drive 2 globals, one for one side.
        // Because we only consider when global is a source of a lookup, logically a global node has side.
        // However, its side must be derived from its grandchild.
        GLOBAL  (Orientation.HORIZONTAL, GroupDelayType.GLOBAL,(short) 0, 'g');


        private final Orientation orientation;
        private final GroupDelayType type;
        private final short length;
        private final char  abbr;


        NodeGroupType(Orientation orientation, GroupDelayType type, short length, char abbr) {
            this.orientation = orientation;
            this.type      = type;
            this.length    = length;
            this.abbr      = abbr;
        }

        public Orientation orientation() {
            return orientation;
        }
        public GroupDelayType type() {
            return type;
        }
        public short length() {
            return length;
        }
        public char abbr() {
            return abbr;
        }
    }


    public InterconnectInfo() {
        buildNodeToSitePin();
    }


    /**
     * Return a map between a node name and its associated site pin.
     */
    public Map<String,String> getNodeToSitePin() {
        return nodeToSitePin;
    }


    private void buildNodeToSitePin() {
        // TODO: consider 1) load from file or 2) build it from RW device model.
        nodeToSitePin = new HashMap<>();
        nodeToSitePin.put("IMUX_E10"           ,"A1"                );
        nodeToSitePin.put("IMUX_E8"            ,"A2"                );
        nodeToSitePin.put("IMUX_E24"           ,"A3"                );
        nodeToSitePin.put("IMUX_E22"           ,"A4"                );
        nodeToSitePin.put("IMUX_E26"           ,"A5"                );
        nodeToSitePin.put("IMUX_E18"           ,"A6"                );
        nodeToSitePin.put("BOUNCE_E_0_FT1"     ,"AX"                );
        nodeToSitePin.put("BYPASS_E4"          ,"A_I"               );
        nodeToSitePin.put("IMUX_E11"           ,"B1"                );
        nodeToSitePin.put("IMUX_E9"            ,"B2"                );
        nodeToSitePin.put("IMUX_E25"           ,"B3"                );
        nodeToSitePin.put("IMUX_E23"           ,"B4"                );
        nodeToSitePin.put("IMUX_E27"           ,"B5"                );
        nodeToSitePin.put("IMUX_E19"           ,"B6"                );
        nodeToSitePin.put("BYPASS_E1"          ,"BX"                );
        nodeToSitePin.put("BYPASS_E5"          ,"B_I"               );
        nodeToSitePin.put("IMUX_E6"            ,"C1"                );
        nodeToSitePin.put("IMUX_E12"           ,"C2"                );
        nodeToSitePin.put("IMUX_E16"           ,"C3"                );
        nodeToSitePin.put("IMUX_E28"           ,"C4"                );
        nodeToSitePin.put("IMUX_E30"           ,"C5"                );
        nodeToSitePin.put("IMUX_E20"           ,"C6"                );
        nodeToSitePin.put("BOUNCE_E_2_FT1"     ,"CX"                );
        nodeToSitePin.put("BYPASS_E6"          ,"C_I"               );
        nodeToSitePin.put("IMUX_E7"            ,"D1"                );
        nodeToSitePin.put("IMUX_E13"           ,"D2"                );
        nodeToSitePin.put("IMUX_E17"           ,"D3"                );
        nodeToSitePin.put("IMUX_E29"           ,"D4"                );
        nodeToSitePin.put("IMUX_E31"           ,"D5"                );
        nodeToSitePin.put("IMUX_E21"           ,"D6"                );
        nodeToSitePin.put("BYPASS_E3"          ,"DX"                );
        nodeToSitePin.put("BYPASS_E7"          ,"D_I"               );
        nodeToSitePin.put("IMUX_E2"            ,"E1"                );
        nodeToSitePin.put("IMUX_E4"            ,"E2"                );
        nodeToSitePin.put("IMUX_E40"           ,"E3"                );
        nodeToSitePin.put("IMUX_E44"           ,"E4"                );
        nodeToSitePin.put("IMUX_E42"           ,"E5"                );
        nodeToSitePin.put("IMUX_E34"           ,"E6"                );
        nodeToSitePin.put("BYPASS_E8"          ,"EX"                );
        nodeToSitePin.put("BYPASS_E12"         ,"E_I"               );
        nodeToSitePin.put("IMUX_E3"            ,"F1"                );
        nodeToSitePin.put("IMUX_E5"            ,"F2"                );
        nodeToSitePin.put("IMUX_E41"           ,"F3"                );
        nodeToSitePin.put("IMUX_E45"           ,"F4"                );
        nodeToSitePin.put("IMUX_E43"           ,"F5"                );
        nodeToSitePin.put("IMUX_E35"           ,"F6"                );
        nodeToSitePin.put("BYPASS_E9"          ,"FX"                );
        nodeToSitePin.put("BOUNCE_E_13_FT0"    ,"F_I"               );
        nodeToSitePin.put("IMUX_E0"            ,"G1"                );
        nodeToSitePin.put("IMUX_E14"           ,"G2"                );
        nodeToSitePin.put("IMUX_E32"           ,"G3"                );
        nodeToSitePin.put("IMUX_E36"           ,"G4"                );
        nodeToSitePin.put("IMUX_E38"           ,"G5"                );
        nodeToSitePin.put("IMUX_E46"           ,"G6"                );
        nodeToSitePin.put("BYPASS_E10"         ,"GX"                );
        nodeToSitePin.put("BYPASS_E14"         ,"G_I"               );
        nodeToSitePin.put("IMUX_E1"            ,"H1"                );
        nodeToSitePin.put("IMUX_E15"           ,"H2"                );
        nodeToSitePin.put("IMUX_E33"           ,"H3"                );
        nodeToSitePin.put("IMUX_E37"           ,"H4"                );
        nodeToSitePin.put("IMUX_E39"           ,"H5"                );
        nodeToSitePin.put("IMUX_E47"           ,"H6"                );
        nodeToSitePin.put("BYPASS_E11"         ,"HX"                );
        nodeToSitePin.put("BOUNCE_E_15_FT0"    ,"H_I"               );

        nodeToSitePin.put("IMUX_W10"           ,"A1"                );
        nodeToSitePin.put("IMUX_W8"            ,"A2"                );
        nodeToSitePin.put("IMUX_W24"           ,"A3"                );
        nodeToSitePin.put("IMUX_W22"           ,"A4"                );
        nodeToSitePin.put("IMUX_W26"           ,"A5"                );
        nodeToSitePin.put("IMUX_W18"           ,"A6"                );
        nodeToSitePin.put("BOUNCE_W_0_FT1"     ,"AX"                );
        nodeToSitePin.put("BYPASS_W4"          ,"A_I"               );
        nodeToSitePin.put("IMUX_W11"           ,"B1"                );
        nodeToSitePin.put("IMUX_W9"            ,"B2"                );
        nodeToSitePin.put("IMUX_W25"           ,"B3"                );
        nodeToSitePin.put("IMUX_W23"           ,"B4"                );
        nodeToSitePin.put("IMUX_W27"           ,"B5"                );
        nodeToSitePin.put("IMUX_W19"           ,"B6"                );
        nodeToSitePin.put("BYPASS_W1"          ,"BX"                );
        nodeToSitePin.put("BYPASS_W5"          ,"B_I"               );
        nodeToSitePin.put("IMUX_W6"            ,"C1"                );
        nodeToSitePin.put("IMUX_W12"           ,"C2"                );
        nodeToSitePin.put("IMUX_W16"           ,"C3"                );
        nodeToSitePin.put("IMUX_W28"           ,"C4"                );
        nodeToSitePin.put("IMUX_W30"           ,"C5"                );
        nodeToSitePin.put("IMUX_W20"           ,"C6"                );
        nodeToSitePin.put("BOUNCE_W_2_FT1"     ,"CX"                );
        nodeToSitePin.put("BYPASS_W6"          ,"C_I"               );
        nodeToSitePin.put("IMUX_W7"            ,"D1"                );
        nodeToSitePin.put("IMUX_W13"           ,"D2"                );
        nodeToSitePin.put("IMUX_W17"           ,"D3"                );
        nodeToSitePin.put("IMUX_W29"           ,"D4"                );
        nodeToSitePin.put("IMUX_W31"           ,"D5"                );
        nodeToSitePin.put("IMUX_W21"           ,"D6"                );
        nodeToSitePin.put("BYPASS_W3"          ,"DX"                );
        nodeToSitePin.put("BYPASS_W7"          ,"D_I"               );
        nodeToSitePin.put("IMUX_W2"            ,"E1"                );
        nodeToSitePin.put("IMUX_W4"            ,"E2"                );
        nodeToSitePin.put("IMUX_W40"           ,"E3"                );
        nodeToSitePin.put("IMUX_W44"           ,"E4"                );
        nodeToSitePin.put("IMUX_W42"           ,"E5"                );
        nodeToSitePin.put("IMUX_W34"           ,"E6"                );
        nodeToSitePin.put("BYPASS_W8"          ,"EX"                );
        nodeToSitePin.put("BYPASS_W12"         ,"E_I"               );
        nodeToSitePin.put("IMUX_W3"            ,"F1"                );
        nodeToSitePin.put("IMUX_W5"            ,"F2"                );
        nodeToSitePin.put("IMUX_W41"           ,"F3"                );
        nodeToSitePin.put("IMUX_W45"           ,"F4"                );
        nodeToSitePin.put("IMUX_W43"           ,"F5"                );
        nodeToSitePin.put("IMUX_W35"           ,"F6"                );
        nodeToSitePin.put("BYPASS_W9"          ,"FX"                );
        nodeToSitePin.put("BOUNCE_W_13_FT0"    ,"F_I"               );
        nodeToSitePin.put("IMUX_W0"            ,"G1"                );
        nodeToSitePin.put("IMUX_W14"           ,"G2"                );
        nodeToSitePin.put("IMUX_W32"           ,"G3"                );
        nodeToSitePin.put("IMUX_W36"           ,"G4"                );
        nodeToSitePin.put("IMUX_W38"           ,"G5"                );
        nodeToSitePin.put("IMUX_W46"           ,"G6"                );
        nodeToSitePin.put("BYPASS_W10"         ,"GX"                );
        nodeToSitePin.put("BYPASS_W14"         ,"G_I"               );
        nodeToSitePin.put("IMUX_W1"            ,"H1"                );
        nodeToSitePin.put("IMUX_W15"           ,"H2"                );
        nodeToSitePin.put("IMUX_W33"           ,"H3"                );
        nodeToSitePin.put("IMUX_W37"           ,"H4"                );
        nodeToSitePin.put("IMUX_W39"           ,"H5"                );
        nodeToSitePin.put("IMUX_W47"           ,"H6"                );
        nodeToSitePin.put("BYPASS_W11"         ,"HX"                );
        nodeToSitePin.put("BOUNCE_W_15_FT0"    ,"H_I"               );

        nodeToSitePin.put("CTRL_E0"            ,"CKEN1"             );
        nodeToSitePin.put("CTRL_E1"            ,"CKEN2"             );
        nodeToSitePin.put("CTRL_E2"            ,"CKEN3"             );
        nodeToSitePin.put("CTRL_E3"            ,"CKEN4"             );
//        nodeToSitePin.put("CTRL_E4"            ,"CLK1"              );
//        nodeToSitePin.put("CTRL_E5"            ,"CLK2"              );
        nodeToSitePin.put("CTRL_E6"            ,"SRST1"             );
        nodeToSitePin.put("CTRL_E7"            ,"SRST2"             );
        nodeToSitePin.put("CTRL_W0"            ,"CKEN1"             );
        nodeToSitePin.put("CTRL_W1"            ,"CKEN2"             );
        nodeToSitePin.put("CTRL_W2"            ,"CKEN3"             );
        nodeToSitePin.put("CTRL_W3"            ,"CKEN4"             );
//        nodeToSitePin.put("CTRL_W4"            ,"CLK1"              );
//        nodeToSitePin.put("CTRL_W5"            ,"CLK2"              );
        nodeToSitePin.put("CTRL_W6"            ,"SRST1"             );
        nodeToSitePin.put("CTRL_W7"            ,"SRST2"             );
//        nodeToSitePin.put("CTRL_W8"            ,"LCLK"              );
        nodeToSitePin.put("CTRL_W9"            ,"WCKEN"             );
    }

}

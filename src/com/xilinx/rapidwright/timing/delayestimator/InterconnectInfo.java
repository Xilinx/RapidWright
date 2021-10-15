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


import com.xilinx.rapidwright.timing.GroupDelayType;

/**
 * Encapsulate interconnect information for a device family.
 * The info must be just accurate enough for estimating the min delay.
 * This is for Ultrascale+.
 */
// TODO: Consider moving this to a file.  Leaving them in code is easier to be extended for future generations.
// TODO: The key issue is it is not certain what features are enough to capture future generations.
public class InterconnectInfo implements java.io.Serializable {

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
    }
}

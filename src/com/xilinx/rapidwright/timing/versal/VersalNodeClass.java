/*
 *
 * Copyright (c) 2026, Advanced Micro Devices, Inc.
 * All rights reserved.
 *
 * Author: Chris Lavin, Advanced Micro Devices, Inc.
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

package com.xilinx.rapidwright.timing.versal;

import com.xilinx.rapidwright.device.Device;
import com.xilinx.rapidwright.device.IntentCode;
import com.xilinx.rapidwright.device.Node;
import com.xilinx.rapidwright.timing.family.WireClasses;

/**
 * The classes that the Versal timing model groups nodes into. Nodes of a class share one set of
 * delay parameters. General interconnect wires are split by direction; the remaining classes
 * follow the node's intent code. Nodes that carry no delay in the model, the nodes of site pins and of the clock
 * network and those without a routing role (INTENT_DEFAULT), are {@link #OTHER}.
 */
enum VersalNodeClass {
    OTHER,
    HSINGLE_E, HSINGLE_W, HSINGLE_RED,
    HDOUBLE_E, HDOUBLE_W,
    HQUAD_E, HQUAD_W,
    HLONG6_E, HLONG6_W,
    HLONG10_E, HLONG10_W,
    VSINGLE_N, VSINGLE_S,
    VDOUBLE_N, VDOUBLE_S,
    VQUAD_N, VQUAD_S,
    VLONG7_N, VLONG7_S,
    VLONG12_N, VLONG12_S,
    SDQNODE, SDQNODE_FT,
    INODE, IMUX, BOUNCE,
    CLE_OUTPUT, CLE_BNODE, CLE_CNODE, CLE_CTRL,
    INTF2, INTF4, INTF_BNODE, INTF_CNODE, INTF_CTRL,
    IRI, OPTDELAY, OUTPUT, PINFEED, DEDICATED,
    SLL_INPUT, SLL_DATA, SLL_OUTPUT,
    /** Laguna input pin of a slice that a super long line lands on, e.g. CLE_SLICEL_TOP_0_LAG_E1_PIN or _LAG_W1_PIN */
    LAGUNA_IN;

    private static final VersalNodeClass[] VALUES = values();

    private static final WireClasses WIRE_CLASSES = new WireClasses("Versal", (ic, name) -> {
        VersalNodeClass c = classify(ic, name);
        return c == OTHER && !name.endsWith("_PIN") && isRoutingIntent(ic) ? -1 : c.ordinal();
    });

    /**
     * Gets the class of a node.
     * @param node The node in question.
     * @return The node's class.
     */
    static VersalNodeClass of(Node node) {
        return VALUES[ordinalOf(node)];
    }

    /**
     * Gets the ordinal of the class of a node.
     * @param node The node in question.
     * @return The ordinal of the node's class.
     */
    static int ordinalOf(Node node) {
        byte[][] classes = getWireClasses(node.getTile().getDevice());
        return classes[node.getTile().getTileTypeEnum().ordinal()][node.getWireIndex()];
    }

    /**
     * Gets the classes of the wires of a device, as nodes with those base wires would have (see
     * {@link WireClasses}).
     * @param device The device in question.
     * @return The ordinals of the classes, indexed by tile type ordinal, then wire index.
     * @throws IllegalStateException If a wire whose intent code is not that of a site pin, a clock network node or
     * a wire without a role (INTENT_DEFAULT) has no class, which means that the device has wires this model does not
     * know.
     */
    public static byte[][] getWireClasses(Device device) {
        return WIRE_CLASSES.get(device);
    }

    /**
     * Whether a wire of an intent code that {@link #classify} does not know is a routing wire: all intent codes but
     * those of the clock network (NODE_GLOBAL_*) and INTENT_DEFAULT, which the timing model does not time.
     */
    private static boolean isRoutingIntent(IntentCode ic) {
        return ic != IntentCode.INTENT_DEFAULT && !ic.name().startsWith("NODE_GLOBAL_");
    }

    /**
     * Classifies the base wire of a node.
     * @param ic The intent code of the wire.
     * @param wireName The name of the wire.
     * @return The class of nodes with such a base wire.
     */
    public static VersalNodeClass classify(IntentCode ic, String wireName) {
        if (wireName.endsWith("_PIN")) {
            if (ic == IntentCode.NODE_PINFEED && wireName.contains("_LAG_")) {
                // Kept apart so that its transition to the output pin of a slice route-thru can carry
                // the delay of passing through the slice
                return LAGUNA_IN;
            }
            // A site pin's own node: the delay of reaching it is carried by the node driving it, and
            // that of leaving it by the node it drives
            return OTHER;
        }
        switch (ic) {
            case NODE_HSINGLE:
                if (wireName.startsWith("INT_SDQ_RED")) {
                    return HSINGLE_RED;
                }
                return isEast(wireName) ? HSINGLE_E : HSINGLE_W;
            case NODE_HDOUBLE:
                return isEast(wireName) ? HDOUBLE_E : HDOUBLE_W;
            case NODE_HQUAD:
                return isEast(wireName) ? HQUAD_E : HQUAD_W;
            case NODE_HLONG6:
                return isEast(wireName) ? HLONG6_E : HLONG6_W;
            case NODE_HLONG10:
                return isEast(wireName) ? HLONG10_E : HLONG10_W;
            case NODE_VSINGLE:
                return isNorth(wireName) ? VSINGLE_N : VSINGLE_S;
            case NODE_VDOUBLE:
                return isNorth(wireName) ? VDOUBLE_N : VDOUBLE_S;
            case NODE_VQUAD:
                return isNorth(wireName) ? VQUAD_N : VQUAD_S;
            case NODE_VLONG7:
                return isNorth(wireName) ? VLONG7_N : VLONG7_S;
            case NODE_VLONG12:
                return isNorth(wireName) ? VLONG12_N : VLONG12_S;
            case NODE_SDQNODE:
                // The INT_NODE_SDQ_ATOM nodes stay in their INT tile; the OUT_[ENSW]NODE ones feed through
                // to a vertically adjacent INT tile
                return wireName.startsWith("OUT_") ? SDQNODE_FT : SDQNODE;
            case NODE_INODE:
                return INODE;
            case NODE_IMUX:
                return IMUX;
            case NODE_PINBOUNCE:
                return BOUNCE;
            case NODE_CLE_OUTPUT:
                return CLE_OUTPUT;
            case NODE_CLE_BNODE:
                return CLE_BNODE;
            case NODE_CLE_CNODE:
                return CLE_CNODE;
            case NODE_CLE_CTRL:
                return CLE_CTRL;
            case NODE_INTF2:
                return INTF2;
            case NODE_INTF4:
                return INTF4;
            case NODE_INTF_BNODE:
                return INTF_BNODE;
            case NODE_INTF_CNODE:
                return INTF_CNODE;
            case NODE_INTF_CTRL:
                return INTF_CTRL;
            case NODE_IRI:
                return IRI;
            case NODE_OPTDELAY:
                return OPTDELAY;
            case NODE_OUTPUT:
                return OUTPUT;
            case NODE_PINFEED:
                return PINFEED;
            case NODE_DEDICATED:
                return DEDICATED;
            case NODE_SLL_INPUT:
                return SLL_INPUT;
            case NODE_SLL_DATA:
                return SLL_DATA;
            case NODE_SLL_OUTPUT:
                return SLL_OUTPUT;
            default:
                return OTHER;
        }
    }

    // General wires are named OUT_<EE|WW|NN|SS><n>_..., e.g. OUT_EE2_E_BEG0
    private static boolean isEast(String wireName) {
        return wireName.startsWith("OUT_EE");
    }

    private static boolean isNorth(String wireName) {
        return wireName.startsWith("OUT_NN");
    }
}

/*
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

package com.xilinx.rapidwright.util.routestatus;

import com.xilinx.rapidwright.design.Cell;
import com.xilinx.rapidwright.design.NetType;
import com.xilinx.rapidwright.design.SiteInst;

/**
 * The connections of a net (segment, or flat net): its pins by kind, and their sites. It tracks
 * whether pins are placed in more than one site. Pins without a physical pin (e.g. hardwired between
 * sites) need no routing, so should not count; as checking that is costly, the pins found in the
 * first two sites are kept so that it can be checked later, if needed (see
 * {@link FlatNet#hasPinsInMultipleSites(Design)}).
 */
class Connections {

    int leafDrivers;
    int leafLoads;
    int leafInouts;
    int unplacedLeafPins;
    int unplacedLeafDrivers;
    int placedPortDrivers;
    int placedPortLoads;
    int unplacedPortDrivers;
    int unplacedPortLoads;
    /** Output pins of black boxes, which are not counted as pins */
    int blackBoxDrivers;
    /** Input (or inout) pins of black boxes, which are not counted as pins either */
    int blackBoxLoads;
    /** Inout pins of black boxes (also counted in {@link #blackBoxLoads}), which may drive too */
    int blackBoxInouts;
    /** Top level ports with partition pins */
    int partitionPinPorts;
    /** GND or VCC if driven by a static source primitive */
    NetType staticType;

    /** The first site of a placed pin */
    SiteInst firstSite;
    /** The cells and logical pins found in the first two sites (null cells for top level ports) */
    Cell firstCell;
    String firstPin;
    Cell secondCell;
    String secondPin;
    boolean multipleSites;

    /** Adds the connections of another net (segment). */
    void addConnections(Connections other) {
        leafDrivers += other.leafDrivers;
        leafLoads += other.leafLoads;
        leafInouts += other.leafInouts;
        unplacedLeafPins += other.unplacedLeafPins;
        unplacedLeafDrivers += other.unplacedLeafDrivers;
        addAll(other);
        placedPortDrivers += other.placedPortDrivers;
        placedPortLoads += other.placedPortLoads;
        unplacedPortDrivers += other.unplacedPortDrivers;
        unplacedPortLoads += other.unplacedPortLoads;
        blackBoxDrivers += other.blackBoxDrivers;
        blackBoxLoads += other.blackBoxLoads;
        blackBoxInouts += other.blackBoxInouts;
        partitionPinPorts += other.partitionPinPorts;
        if (staticType == null) {
            staticType = other.staticType;
        }
    }

    /** Removes all connections. */
    void resetConnections() {
        leafDrivers = 0;
        leafLoads = 0;
        leafInouts = 0;
        unplacedLeafPins = 0;
        unplacedLeafDrivers = 0;
        resetSites();
        placedPortDrivers = 0;
        placedPortLoads = 0;
        unplacedPortDrivers = 0;
        unplacedPortLoads = 0;
        blackBoxDrivers = 0;
        blackBoxLoads = 0;
        blackBoxInouts = 0;
        partitionPinPorts = 0;
        staticType = null;
    }

    void addPin(SiteInst si, Cell cell, String pin) {
        if (multipleSites || si == firstSite) {
            return;
        }
        if (firstSite == null) {
            firstSite = si;
            firstCell = cell;
            firstPin = pin;
            return;
        }
        secondCell = cell;
        secondPin = pin;
        multipleSites = true;
    }

    void addAll(Connections other) {
        if (other.multipleSites && !multipleSites) {
            firstSite = other.firstSite;
            firstCell = other.firstCell;
            firstPin = other.firstPin;
            secondCell = other.secondCell;
            secondPin = other.secondPin;
            multipleSites = true;
        } else if (other.firstSite != null) {
            addPin(other.firstSite, other.firstCell, other.firstPin);
        }
    }

    void resetSites() {
        firstSite = null;
        firstCell = null;
        firstPin = null;
        secondCell = null;
        secondPin = null;
        multipleSites = false;
    }
}

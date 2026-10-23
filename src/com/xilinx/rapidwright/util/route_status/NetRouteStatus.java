/*
 * Copyright (c) 2026, Advanced Micro Devices, Inc.
 * All rights reserved.
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

package com.xilinx.rapidwright.util.route_status;

/**
 * Per-net route status flags, mirroring the route types of Vivado's
 * `report_route_status -route_type` and the per-category sections of
 * `report_route_status -list_all_nets`. A net may have more than one flag.
 * Constants are declared in the order Vivado lists the sections.
 */
public enum NetRouteStatus {
    /** None of the pins for the net are placed. */
    UNPLACED_ALL("Nets with no placed ports or pins"),
    /** The net has some unplaced pins. */
    UNPLACED("Nets with some unplaced ports or pins"),
    /** The net needs to be routed but has no route data. */
    UNROUTED("Unrouted Nets"),
    /** The net has routing but is not fully routed. */
    PARTIAL("Partially Routed Nets"),
    /** The net has known gaps in the route tree. */
    GAPS("Nets with Gaps"),
    /** The net has either no loads or no drivers and is connected to a top level port. */
    HIERPORT("Nets with Hierarchal Ports"),
    /** The net shares routing resources or site pins with another net. */
    CONFLICTS("Nets with Routing or Site Pin Conflicts"),
    /** The net has routing that does not go to a valid load, or disconnected routing. */
    ANTENNAS("Nets with Antennas or Islands"),
    /** The net has no drivers. */
    NODRIVER("Nets with No Driver"),
    /** The net has multiple drivers. */
    MULTI_DRIVER("Nets with Multiple Driver"),
    /** The net has locked routing nodes. */
    LOCKED_NODES("Nets with Locked Nodes"),
    /** The net has a route tree associated with it. */
    HAS_ROUTING("Nets with any Stored Routing"),
    /** The net is fully routed inside a site and needs no routing. */
    INTRASITE("Nets Internally Routed Inside a Site"),
    /** The net has no loads and so needs no routing. */
    NOLOADS("Nets with No Loads"),
    /** The net is fully routed with no errors. */
    ROUTED("Routed Nets"),
    ;

    private final String sectionTitle;

    NetRouteStatus(String sectionTitle) {
        this.sectionTitle = sectionTitle;
    }

    /**
     * Gets the title of the section listing nets with this status in the output of Vivado's
     * `report_route_status -list_all_nets`.
     * @return The section title, without the trailing colon.
     */
    public String getSectionTitle() {
        return sectionTitle;
    }

    /**
     * Looks up the status from the title of a section in the output of Vivado's
     * `report_route_status -list_all_nets`.
     * @param sectionTitle The section title, with or without the trailing colon.
     * @return The matching status, or null if there is none.
     */
    public static NetRouteStatus fromSectionTitle(String sectionTitle) {
        String title = sectionTitle.endsWith(":") ? sectionTitle.substring(0, sectionTitle.length() - 1)
                : sectionTitle;
        for (NetRouteStatus s : values()) {
            if (s.sectionTitle.equals(title)) {
                return s;
            }
        }
        return null;
    }
}

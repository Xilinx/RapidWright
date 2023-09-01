/*
 * Copyright (c) 2020-2022, Xilinx, Inc.
 * Copyright (c) 2022-2023, Advanced Micro Devices, Inc.
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

package com.xilinx.rapidwright.interchange;

import com.xilinx.rapidwright.design.SitePinInst;
import com.xilinx.rapidwright.device.BELClass;
import com.xilinx.rapidwright.device.BELPin;
import com.xilinx.rapidwright.device.Node;
import com.xilinx.rapidwright.device.PIP;
import com.xilinx.rapidwright.device.Site;
import com.xilinx.rapidwright.device.SitePIP;
import com.xilinx.rapidwright.device.SitePin;
import com.xilinx.rapidwright.device.Wire;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class RouteBranchNode {

    enum RouteSegmentType {
        SITE_PIN,
        BEL_PIN,
        SITE_PIP,
        PIP
    }

    private Object routeSegment;

    private RouteSegmentType type;

    private List<RouteBranchNode> branches;

    private RouteBranchNode parent = null;

    private boolean visited = false;

    private boolean routethru = false;

    public RouteBranchNode(SitePinInst sitePin) {
        routeSegment = sitePin;
        type = RouteSegmentType.SITE_PIN;
    }

    public RouteBranchNode(SiteBELPin belPin) {
        routeSegment = belPin;
        type = RouteSegmentType.BEL_PIN;
    }

    public RouteBranchNode(SitePIP sitePIP) {
        routeSegment = sitePIP;
        type = RouteSegmentType.SITE_PIP;
    }

    public RouteBranchNode(PIP pip) {
        routeSegment = pip;
        type = RouteSegmentType.PIP;
    }

    public RouteBranchNode(Site site, SitePIP sitePIP, boolean isFixed) {
        routeSegment = new SiteSitePIP(site, sitePIP, isFixed);
        type = RouteSegmentType.SITE_PIP;
    }

    public RouteBranchNode(Site site, BELPin belPin, boolean isRoutethru) {
        routeSegment = new SiteBELPin(site,belPin);
        type = RouteSegmentType.BEL_PIN;
        routethru = isRoutethru;
    }

    public RouteSegmentType getType() {
        return type;
    }

    public SitePinInst getSitePin() {
        return (SitePinInst) routeSegment;
    }

    public SiteBELPin getBELPin() {
        return (SiteBELPin) routeSegment;
    }

    public SiteSitePIP getSitePIP() {
        return (SiteSitePIP) routeSegment;
    }

    public PIP getPIP() {
        return (PIP) routeSegment;
    }

    public String toString() {
        if (type == RouteSegmentType.SITE_PIN) {
            return getSitePin().getSitePinName();
        }
        return routeSegment.toString();
    }

    public RouteBranchNode getParent() {
        return parent;
    }

    private void setParent(RouteBranchNode parent) {
        this.parent = parent;
    }

    public boolean isSource() {
        if (type == RouteSegmentType.BEL_PIN) {
            SiteBELPin belPin = getBELPin();
            if (belPin.belPin.isInput()) return false;
            if (belPin.belPin.getBEL().getBELClass() == BELClass.BEL) {
                return !routethru;
            }
        }
        return false;
    }

    public void addBranch(RouteBranchNode routeBranch) {
        if (routeBranch.getParent() != null) {
            return;
        }
        if (branches == null) {
            branches = new ArrayList<>(1);
        }
        branches.add(routeBranch);
        routeBranch.setParent(this);
    }

    public List<String> getDrivers() {
        ArrayList<String> drivers = new ArrayList<String>();

        switch(type) {
            case PIP:{
                PIP pip = getPIP();
                Node node = pip.isReversed() ? pip.getEndNode() : pip.getStartNode();
                for (Wire w : node.getAllWiresInNode()) {
                    for (PIP p : w.getBackwardPIPs()) {
                        if (!p.equals(getPIP())) {
                            drivers.add(p.toString());
                        }
                    }
                }
                SitePin pin = node.getSitePin();
                if (pin != null && !pin.isInput()) {
                    drivers.add(pin.getSite().getName() + "." + pin.getPinName());
                }
                break;
            }
            case SITE_PIP:{
                SiteSitePIP sitePIP = getSitePIP();
                BELPin belPinSrc = sitePIP.sitePIP.getInputPin().getSourcePin();
                drivers.add(sitePIP.site.getName() + "/" + belPinSrc.toString());
                break;
            }
            case SITE_PIN:{
                SitePinInst spi = getSitePin();
                if (spi.isOutPin()) {
                    BELPin belPin = spi.getBELPin();
                    drivers.add(spi.getSite().getName() + "/" + belPin.toString());
                } else {
                    Node node = getSitePin().getConnectedNode();
                    for (Wire w : node.getAllWiresInNode()) {
                        for (PIP p : w.getBackwardPIPs()) {
                            drivers.add(p.toString());
                        }
                    }
                }
                break;
            }case BEL_PIN:{
                SiteBELPin belPin = getBELPin();
                if (belPin.belPin.isOutput() && belPin.belPin.getBEL().getBELClass() == BELClass.RBEL
                                                                                && !isSource()) {
                    String site = belPin.site.getName() + "/";
                    for (SitePIP p : belPin.belPin.getSitePIPs()) {
                        drivers.add(site + p.toString());
                    }
                } else if (belPin.belPin.getBEL().getBELClass() == BELClass.PORT) {
                    String site = belPin.site.getName();
                    if (belPin.belPin.isOutput()) {
                        // Input site pin
                        drivers.add(site + "." + belPin.belPin.getName());
                    } else {
                        // Output site pin
                        drivers.add(site + "/" + belPin.belPin.getSourcePin().toString());
                    }
                } else {
                    String site = belPin.site.getName() + "/";
                    if (belPin.belPin.isInput()) {
                        drivers.add(site + belPin.belPin.getSourcePin().toString());
                    } else if (routethru) {
                        for (BELPin bp : belPin.belPin.getBEL().getPins()) {
                            if (bp.isInput()) {
                                drivers.add(site + bp.toString());
                            }
                        }
                    }
                }
                break;
            }
        }

        return drivers;
    }

    public List<RouteBranchNode> getBranches() {
        return branches != null ? branches : Collections.emptyList();
    }

    public RouteBranchNode getBranch(int idx) {
        return branches.get(idx);
    }

    public boolean hasBeenVisited() {
        return visited;
    }

    public void setVisited(boolean value) {
        this.visited = value;
    }
}

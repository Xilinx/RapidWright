package com.xilinx.rapidwright.interchange;

import java.util.ArrayList;
import java.util.List;

import com.xilinx.rapidwright.design.SitePinInst;
import com.xilinx.rapidwright.device.BEL;
import com.xilinx.rapidwright.device.BELClass;
import com.xilinx.rapidwright.device.BELPin;
import com.xilinx.rapidwright.device.Node;
import com.xilinx.rapidwright.device.PIP;
import com.xilinx.rapidwright.device.Site;
import com.xilinx.rapidwright.device.SitePIP;
import com.xilinx.rapidwright.device.SitePin;
import com.xilinx.rapidwright.device.Wire;

public class RouteBranchNode {

    enum RouteSegmentType {
        SITE_PIN,
        BEL_PIN,
        SITE_PIP,
        PIP
    }
    
    private Object routeSegment;
    
    private RouteSegmentType type;
    
    private List<RouteBranchNode> branches = new ArrayList<RouteBranchNode>();
    
    private RouteBranchNode parent = null;
    
    private boolean visited = false;
    
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
    
    public RouteBranchNode(Site site, BELPin belPin) {
        routeSegment = new SiteBELPin(site,belPin);
        type = RouteSegmentType.BEL_PIN;
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
        if(type == RouteSegmentType.SITE_PIN) {
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
        if(type == RouteSegmentType.BEL_PIN) {
            SiteBELPin belPin = getBELPin();
            if(belPin.belPin.isInput()) return false;
            if(belPin.belPin.getBEL().getBELClass() == BELClass.BEL) {
                return true;
            }
        }
        return false;
    }
    
    public void addBranch(RouteBranchNode routeBranch) {
        branches.add(routeBranch);
        routeBranch.setParent(this);
    }
    
    public List<String> getDrivers() {
        ArrayList<String> drivers = new ArrayList<String>();
        
        switch(type) {
            case PIP:{
                Node node = getPIP().getStartNode();
                for(Wire w : node.getAllWiresInNode()) {
                    for(PIP p : w.getBackwardPIPs()) {
                        drivers.add(p.toString());
                    }
                }
                SitePin pin = node.getSitePin();
                if(pin != null && !pin.isInput()) {
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
                if(spi.isOutPin()) {
                    BEL bel = spi.getSite().getBEL(spi.getName());
                    BELPin belPin = bel.getPins()[0].getSourcePin();
                    drivers.add(spi.getSite().getName() + "/" + belPin.toString());
                } else {
                    Node node = getSitePin().getConnectedNode();
                    for(Wire w : node.getAllWiresInNode()) {
                        for(PIP p : w.getBackwardPIPs()) {
                            drivers.add(p.toString());
                        }
                    }                    
                }
                break;
            }case BEL_PIN:{
                SiteBELPin belPin = getBELPin();
                if(belPin.belPin.isOutput() && belPin.belPin.getBEL().getBELClass() == BELClass.RBEL
                                                                                && !isSource()) {
                    String site = belPin.site.getName() + "/";
                    for(SitePIP p : belPin.belPin.getSitePIPs()) {
                        drivers.add(site + p.toString());
                    }
                }else if(belPin.belPin.getBEL().getBELClass() == BELClass.PORT) {
                    String site = belPin.site.getName() + ".";
                    drivers.add(site + belPin.belPin.getName());
                }
                break;
            }
        }
        
        return drivers;
    }
    
    public List<RouteBranchNode> getBranches() {
        return branches;
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

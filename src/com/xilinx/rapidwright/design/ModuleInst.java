/*
 * Original work: Copyright (c) 2010-2011 Brigham Young University
 * Modified work: Copyright (c) 2017-2022, Xilinx, Inc.
 * Copyright (c) 2022, Advanced Micro Devices, Inc.
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

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import com.xilinx.rapidwright.design.blocks.PBlock;
import com.xilinx.rapidwright.design.blocks.PBlockRange;
import com.xilinx.rapidwright.design.noc.NOCClient;
import com.xilinx.rapidwright.device.Device;
import com.xilinx.rapidwright.device.Node;
import com.xilinx.rapidwright.device.PIP;
import com.xilinx.rapidwright.device.Site;
import com.xilinx.rapidwright.device.SiteTypeEnum;
import com.xilinx.rapidwright.device.Tile;
import com.xilinx.rapidwright.edif.EDIFCell;
import com.xilinx.rapidwright.util.MessageGenerator;
import com.xilinx.rapidwright.util.Utils;

/**
 * There is no direct representation of a module instance in Vivado. Each member of
 * a module instance is referenced in a particular way back to the module
 * instance. This class attempts to collect all the module instance information
 * into a single class.
 *
 * @author Chris Lavin Created on: Jun 22, 2010
 */
public class ModuleInst extends AbstractModuleInst<Module, Site, ModuleInst>{

    /** The design which contains this module instance */
    private transient Design design;
    /** The module of which this object is an instance of */
    private Module module;
    /** The anchor instance of the module instance */
    private SiteInst anchor;
    /** A list of all primitive instances which make up this module instance */
    private ArrayList<SiteInst> instances;
    /** A list of all nets internal to this module instance 
     * Note: These are references to nets inside 'design' that this ModuleInst
     * inserted and is exclusively responsible for. As such, any static nets present in
     * this list must be handled with care.
     */
    private ArrayList<Net> nets;
    /** A list of all NOCClients belonging to this instance **/
    private ArrayList<NOCClient> nocClients;
    /** Keeps track of currently placed GND PIPs */
    private Set<PIP> gndPIPs;
    /** Keeps track of currently placed VCC PIPs */
    private Set<PIP> vccPIPs;
    /**
     * Constructor initializing instance module name
     * @param name Name of the module instance
     */
    public ModuleInst(String name, Design design) {
        super(name);
        this.setDesign(design);
        if (design != null) {
            design.getModuleInstMap().put(name, this);
        }
        this.module = null;
        this.setAnchor(null);
        instances = new ArrayList<SiteInst>();
        nets = new ArrayList<Net>();
    }

    /**
     * This will initialize this module instance to the same attributes
     * as the module instance passed in.  This is primarily used for classes
     * which extend {@link ModuleInst}.
     *
     * This performs a shallow copy of the original Module Instance. It will point to the same cell and instances as the
     * original module.
     * @param moduleInst The module instance to mimic.
     */
    public ModuleInst(ModuleInst moduleInst) {
        super(moduleInst.getName());
        this.setDesign(moduleInst.design);
        this.module = moduleInst.module;
        this.setAnchor(moduleInst.anchor);
        instances =  moduleInst.instances;
        nets = moduleInst.nets;
        setCellInst(getCellInst());
    }

    /**
     * Adds the instance inst to the instances list that are members of the
     * module instance.
     * @param inst The instance to add.
     */
    public void addInst(SiteInst inst) {
        instances.add(inst);
    }

    public void removeInst(SiteInst inst) {
        instances.remove(inst);
    }

    /**
     * Adds the net to the net list that are members of the module instance.
     * @param net The net to add.
     */
    public void addNet(Net net) {
        nets.add(net);
    }

    /**
     * @param design the design to set
     */
    public void setDesign(Design design) {
        this.design = design;
    }

    /**
     * @return the design
     */
    public Design getDesign() {
        return design;
    }

    /**
     * @return the moduleType
     */
    public Module getModule() {
        return module;
    }

    /**
     * @param module the module to set.
     */
    public void setModule(Module module) {
        this.module = module;
    }

    /**
     * Gets the site instance that belong to this module instance.
     * @return The list of site instances that belong to this module instance.
     */
    public List<SiteInst> getSiteInsts() {
        return instances;
    }

    /**
     * @param instances the instances to set
     */
    public void setInsts(ArrayList<SiteInst> instances) {
        this.instances = instances;
    }

    /**
     * Gets the list of physical nets in the module instance.
     * @return Full list of physical nets in this module instance.
     */
    public ArrayList<Net> getNets() {
        return nets;
    }

    /**
     * Sets the anchor instance for this module instance.
     * @param anchor The new anchor instance for this module instance.
     */
    public void setAnchor(SiteInst anchor) {
        this.anchor = anchor;
    }

    /**
     * Gets and returns the anchor instance for this module instance.
     * @return The anchor instance for this module instance.
     */
    public SiteInst getAnchor() {
        return anchor;
    }

    public boolean isPlaced() {
        return anchor != null && anchor.isPlaced();
    }

    public void addNOCClient(NOCClient nc) {
        getNOCClients().add(nc);
    }

    public List<NOCClient> getNOCClients() {
        if (nocClients == null) {
            nocClients = new ArrayList<NOCClient>();
        }
        return nocClients;
    }

    /**
     * Does a brute force search to find all valid locations of where this module
     * instance can be placed.  It returns the module instance to its original
     * location.
     * @return A list of valid anchor sites for the module instance to be placed.
     */
    public ArrayList<Site> getAllValidPlacements() {
        ArrayList<Site> validSites = new ArrayList<Site>();
        if (getAnchor() == null) return validSites;
        Site originalSite = getAnchor().getSite();
        Design design = getDesign();
        Site[] sites = design.getDevice().getAllCompatibleSites(getAnchor().getSiteTypeEnum());
        for (Site newAnchorSite : sites) {
            if (place(newAnchorSite)) {
                validSites.add(newAnchorSite);
                unplace();
            }
        }

        // Put hard macro back
        if (originalSite != null) place(originalSite);

        return validSites;
    }

    /**
     * Places the module instance anchor at the newAnchorSite as well as all other
     * instances and nets within the module instance at their relative offsets of the new site. Note
     * that this method allows placement overlap by default.  See
     * {@link #place(Site, boolean, boolean)} to disallow module overlap.
     * @param newAnchorSite The new site for the anchor of the module instance.
     * @return True if placement was successful, false otherwise.
     */
    public boolean place(Site newAnchorSite) {
        return place(newAnchorSite, false);
    }

    /**
     * Places the module instance on the module's anchor site (original location of the module).
     * This is the same as place(getModule().getAnchor().getSite()). Note
     * that this method allows placement overlap by default.  See
     * {@link #place(Site, boolean, boolean)} to disallow module overlap.
     * @return True if the placement was successful, false otherwise.
     */
    public boolean placeOnOriginalAnchor() {
        return place(module.getAnchor(), false);
    }

    /**
     * Places the module instance anchor at the newAnchorSite as well as all other
     * instances and nets within the module instance at their relative offsets of the new site.  Note
     * that this method allows placement overlap by default.  See
     * {@link #place(Site, boolean, boolean)} to disallow module overlap.
     * @param newAnchorSite The new site for the anchor of the module instance.
     * @param skipIncompatible Flag telling the placement checks to skip any incompatible site that
     * does not match the floorplan according to the original module and simply leave it unplaced.
     * Setting to false will cause placement to fail on first mismatch of floorplan placement
     * attempt.
     * @return True if placement was successful, false otherwise.
     */
    public boolean place(Site newAnchorSite, boolean skipIncompatible) {
        return place(newAnchorSite, skipIncompatible, true);
    }

    /**
     * Places the module instance anchor at the newAnchorSite as well as all other
     * instances and nets within the module instance at their relative offsets of the new site.
     * @param newAnchorSite The new site for the anchor of the module instance.
     * @param skipIncompatible Flag telling the placement checks to skip any incompatible site that
     * does not match the floorplan according to the original module and simply leave it unplaced.
     * Setting to false will cause placement to fail on first mismatch of floorplan placement
     * attempt.
     * @param allowOverlap True if the module instance is allowed to overlap with existing placed logic.
     * @return True if placement was successful, false otherwise.
     */
    public boolean place(Site newAnchorSite, boolean skipIncompatible, boolean allowOverlap) {
        // Check if parameters are null
        if (newAnchorSite == null) {
            return false;
        }
        Device dev = newAnchorSite.getDevice();

        // Do some error checking on the newAnchorSite
        if (module.getAnchor() == null) return false;
        Site p = module.getAnchor();
        Tile t = newAnchorSite.getTile();
        Site newValidSite = p.getCorrespondingSite(module.getAnchor().getSiteTypeEnum(), t);
        if (!newAnchorSite.equals(newValidSite)) {
            //MessageGenerator.briefError("New anchor site (" + newAnchorSite.getName() +
            //        ") is incorrect.  Should be " + newValidSite.getName());
            //this.unplace();
            return false;
        }

        // save original placement in case new placement is invalid
        HashMap<SiteInst, Site> originalSites;
        boolean placedPreviously = isPlaced();
        originalSites = placedPreviously ? new HashMap<SiteInst, Site>() : null;

        //=======================================================//
        /* Place instances at new location                       */
        //=======================================================//
        for (SiteInst inst : instances) {
            // Certain site types cannot move, and will have to remain
            if (Utils.isLockedSiteType(inst.getSiteTypeEnum())) {
                inst.place(inst.getModuleTemplateInst().getSite());
                continue;
            }

            Site templateSite = inst.getModuleTemplateInst().getSite();
            Tile newTile = module.getCorrespondingTile(templateSite.getTile(), newAnchorSite.getTile());
            Site newSite = templateSite.getCorrespondingSite(inst.getSiteTypeEnum(), newTile);

            SiteInst existingSiteInst = allowOverlap ? null : design.getSiteInstFromSite(newSite);

            if (newSite == null || existingSiteInst != null) {
                //MessageGenerator.briefError("ERROR: No matching site found." +
                //    " (Template Site:"    + templateSite.getName() +
                //    ", Template Tile:" + templateSite.getTile() +
                //    " => New Site:" + newSite + ", New Tile:" + newTile+")");

                // revert placement to original placement before method call
                if (originalSites == null) {
                    if (skipIncompatible) {
                        continue;
                    } else {
                        unplace();
                        return false;
                    }
                }
                for (SiteInst i : originalSites.keySet()) {
                    design.getSiteInst(i.getName()).place(originalSites.get(i));
                }
                return false;
            }
            if (newSite.getSiteTypeEnum() == SiteTypeEnum.BUFGCE && design.isSiteUsed(newSite)) {
                // Choose a different buffer if the specific one in the block is already used
                for (Site s : newSite.getTile().getSites()) {
                    if (s.isCompatibleSiteType(newSite.getSiteTypeEnum()) && !design.isSiteUsed(s)) {
                        newSite = s;
                        break;
                    }
                }
                if (design.isSiteUsed(newSite)) {
                    throw new RuntimeException("ERROR: BlockGuide ("+ getName() +") contains a BUFGCE that is already fully occupied in the tile specified.");
                }
            }

            if (originalSites != null) {
                originalSites.put(inst, inst.getSite());
            }
            inst.place(newSite);
        }

        //=======================================================//
        /* Place net at new location                             */
        //=======================================================//
        nextnet: for (Net net : nets) {
            unrouteNet(net, placedPreviously);

            Net templateNet = net.getModuleTemplateNet();
            Set<PIP> pipSet = getUsedStaticPIPs(templateNet);
            for (PIP pip : templateNet.getPIPs()) {
                Tile templatePipTile = pip.getTile();
                Tile newPipTile = module.getCorrespondingTile(templatePipTile, newAnchorSite.getTile());
                if (newPipTile == null) {
                    if (skipIncompatible) {
                        continue nextnet;
                    } else {
                        unplace();
                        MessageGenerator.briefError("Warning: Unable to return module instance "+ getName() +" back to original placement.");
                        return false;
                    }
                }
                PIP newPip = new PIP(pip);
                newPip.setTile(newPipTile);
                if (pipSet != null) {
                    pipSet.add(newPip);
                }
                // Some tiles have nodes that are depopulated, we need to detect those
                Node endNode = newPip.getEndNode();
                if (endNode != null && endNode.getAllDownhillPIPs().size() == 0
                        && pip.getEndNode().getAllDownhillPIPs().size() != 0) {
                    return false;
                }
                net.addPIP(newPip);
            }
        }
        // Update location of NOCClients
        for (NOCClient nc : getNOCClients()) {
            Site templateSite = dev.getSite(nc.getLocation());
            if (templateSite == null)
                continue;
            Tile newTile = module.getCorrespondingTile(templateSite.getTile(), newAnchorSite.getTile());
            Site newSite = templateSite.getCorrespondingSite(templateSite.getSiteTypeEnum(), newTile);
            nc.setLocation(newSite != null ? newSite.getName() : null);
        }

        return true;
    }

    /**
     * Removes all placement information and unroutes all nets of the module instance.
     */
    public void unplace() {
        boolean placedPreviously = isPlaced();
        // unplace instances
        for (SiteInst inst : instances) {
            inst.unPlace();
        }
        // unplace nets (remove pips)
        for (Net net : nets) {
            unrouteNet(net, placedPreviously);
        }
    }

    private void unrouteNet(Net net, boolean placedPreviously) {
        if (net.isStaticNet()) {
            // Any static nets that appear in 'nets' is not the exclusive responsibility
            // of this ModuleInst, instead it is shared with everything in 'design'.
            if (placedPreviously) {
                // We need to remove the GND/VCC PIPs inserted by this ModuleInst out of the global design net
                Net designNet = design.getNet(net.getName());
                Set<PIP> prevUsed = getUsedStaticPIPs(designNet);
                designNet.getPIPs().removeIf(p -> prevUsed.remove(p));
                assert(prevUsed.isEmpty());
            }
        } else {
            net.getPIPs().clear();
        }
    }

    /**
     * This method will calculate and return the corresponding tile of a module instance.
     * for a new anchor location.
     * @param templateTile The tile in the module which acts as a template.
     * @param newAnchorTile This is the tile of the new anchor instance of the module instance.
     * @return The new tile of the module instance which corresponds to the templateTile, or null
     * if none exists.
     */
    public Tile getCorrespondingTile(Tile templateTile, Tile newAnchorTile) {
        return module.getCorrespondingTile(templateTile, newAnchorTile);
    }

    /**
     * Gets the corresponding pins (SitePinInst) on this module instance
     * that corresponds to the module's SitePinInst.
     * @param modulePin The SitePinInst on the prototype module.
     * @return The corresponding pin on this module instance, or null if could not be found.
     */
    private SitePinInst getCorrespondingPin(SitePinInst modulePin) {
        if (modulePin == null) return null;

        String siteInstName = getName()+"/"+modulePin.getSiteInst().getName();
        SiteInst newSiteInst = design.getSiteInst(siteInstName);
        if (newSiteInst == null) {
            throw new RuntimeException("Did not find corresponding Site Inst for "+modulePin.getSiteInst().getName()+" in "+getName());
        }
        return newSiteInst.getSitePinInst(modulePin.getName());
    }

    /**
     * Gets the corresponding port pins (SitePinInst) on this module instance
     * that corresponds to the module's port.
     * @param port The port on the prototype module.
     * @return The corresponding port pins on this module instance
     */
    public Set<SitePinInst> getCorrespondingPins(Port port) {
        return port.getSitePinInsts().stream().map(this::getCorrespondingPin).collect(Collectors.toSet());
    }

    /**
     * Gets the single corresponding port pin (SitePinInst) on this module instance
     * that corresponds to the module's port. If the module port has multiple pins, this will throw an Exception.
     * @param port The port on the prototype module.
     * @return The corresponding port pin on this module instance, or null if could not be found.
     */
    public SitePinInst getSingleCorrespondingPin(Port port) {

        if (port.getSitePinInsts().size()>1) {
            throw new IllegalStateException("Cannot get single SitePinInst of Module "+module.getName()+"."+port.getName()+", as it has "+port.getSitePinInsts().size()+" pins");
        }
        return getCorrespondingPin(port.getSingleSitePinInst());
    }


    private Port findPassthruInput(Port p) {
        for (String passthroughName : p.getPassThruPortNames()) {
            Port ptPort = getModule().getPort(passthroughName);
            if (!ptPort.isOutPort()) {
                return ptPort;
            }
        }
        return null;
    }

    /**
     * Gets (if it exists), the corresponding net within the module instance of the port.
     * @param p The port on the module of interest
     * @return The corresponding net on the module instance.
     */
    public Net getCorrespondingNet(Port p) {
        Net net = p.getNet();
        if (net != null) {
            String name = getName() + "/" + net.getName();
            return design.getNet(name);
        }
        // Get net of input port pass-thru
        if (p.isOutPort() && p.getPassThruPortNames().size() > 0) {
            Port input = findPassthruInput(p);
            if (input == null) {
                return null;
            }
            return getCorrespondingNet(input);
        }
        return null;
    }


    /**
     * Gets the corresponding port on the module by name.
     * @param name
     * @return Port object.
     */
    public Port getPort(String name) {
        return module.getPort(name);
    }


    /**
     * Get's the current lower left site as used for a placement directive
     * for an implementation guide.  If a pblock was used and is presently annotated
     * on the Module, it will use the lower left most corner of the pblock.
     * @return The current lower left site used for placement.
     */
    public Site getLowerLeftPlacement() {
        return getLowerLeftPlacement(null);
    }

    /**
     * Get's the current lower left site as used for a placement directive
     * for an implementation guide.  If a pblock was used and is presently annotated
     * on the Module, it will use the lower left most corner of the pblock.
     * @param type If a desired type is requested for the corner, otherwise it will search
     * for the most lower left corner site
     * @return The current lower left site used for placement.
     */
    public Site getLowerLeftPlacement(SiteTypeEnum type) {
        // Calculate anchor offset
        Site anchor = getModule().getAnchor();
        if (anchor == null) return null;

        Tile origAnchor = anchor.getTile();
        Tile currAnchor = getAnchor().getSite().getTile();
        int dx = currAnchor.getTileXCoordinate() - origAnchor.getTileXCoordinate();
        int dy = currAnchor.getTileYCoordinate() - origAnchor.getTileYCoordinate();

        // Get original lower left placement
        Tile origLowerLeft = getLowerLeftTile(type);

        String origTilePrefix = origLowerLeft.getRootName();
        String newSuffix = "_X" + (origLowerLeft.getTileXCoordinate() + dx) + "Y" + (origLowerLeft.getTileYCoordinate() + dy);

        Tile newTile = origLowerLeft.getDevice().getTile(origTilePrefix + newSuffix);
        if (type == null) {
            if (newTile.getSites().length == 0) {
                throw new RuntimeException("no sites in tile "+newTile+", orig lower left is "+origLowerLeft+" for mi " + getName());
            }
            return newTile.getSites()[0];
        }
        for (Site s : newTile.getSites()) {
            if (s.getSiteTypeEnum() == type) return s;
        }
        return null;
    }

    /**
     * Chooses a lower left reference tile in a module instance for the purpose
     * of placement. The tile chosen is from the context of the original module, not
     * the module instance's current location.
     * @param type The site type space in which to reference.
     * @return A lower left tile from the module's original footprint.
     */
    public Tile getLowerLeftTile(SiteTypeEnum type) {
        PBlock pb = getModule().getPBlock();
        if (pb != null) {
            if (type == null)
                return pb.getBottomLeftTile();

            for (PBlockRange range : pb) {
                if (range.getLowerLeftSite().getSiteTypeEnum() == type) {
                    return range.getLowerLeftSite().getTile();
                }
            }
        }


        SiteInst lowerLeftIP = null;
        int x = Integer.MAX_VALUE;
        int y = Integer.MAX_VALUE;
        for (SiteInst s : getModule().getSiteInsts()) {
            boolean isSiteCompatible = type == null ? PBlock.isPBlockCornerSiteType(s.getSiteTypeEnum()) :
                (s.getSite().isCompatibleSiteType(type) || (Utils.isSLICE(s) && Utils.isSLICE(type)));
            if (isSiteCompatible) {
                if (lowerLeftIP == null) {
                    lowerLeftIP = s;
                } else if (s.getSite().getInstanceY() < lowerLeftIP.getSite().getInstanceY()) {
                    lowerLeftIP = s;
                }
                if (s.getSite().getInstanceX() < x) {
                    x = s.getSite().getInstanceX();
                }
                if (s.getSite().getInstanceY() < y) {
                    y = s.getSite().getInstanceY();
                }
            }
        }

        Device dev = getDesign().getDevice();
        String prefix = lowerLeftIP.getSite().getName().substring(0, lowerLeftIP.getSite().getName().lastIndexOf('_')+1);
        Site target = dev.getSite(prefix + "X" + x + "Y" + y);

        return target.getTile();
    }

    /**
     * Attempts to place the module instance such that it's lower left tile falls
     * on the specified IP tile (CLB,DSP,BRAM,...)
     * @param ipTile The specific tile onto which the lower left tile of the {@link ModuleInst} should be placed.
     * @param type The specific site type in the tile.
     * @return True if the placement succeeded, false otherwise.
     */
    public boolean placeMINearTile(Tile ipTile, SiteTypeEnum type) {
        Tile targetTile = getLowerLeftTile(type);
        Device dev = targetTile.getDevice();

        Tile newAnchorTile = getModule().getCorrespondingAnchorTile(targetTile, ipTile, dev);
        if (newAnchorTile == null) return false;

        Site anchor = getModule().getAnchor();
        if (anchor == null) return false;
        Site moduleAnchor = anchor;
        boolean success = place(newAnchorTile.getSites()[moduleAnchor.getTile().getSiteIndex(moduleAnchor)]);

        if (!success) System.out.println("Failed placement attempt, TargetTile="+targetTile.getName()+" ipTile="+ipTile.getName());
        return success;
    }

    /**
     * Connects two signals by port name between this module instance and another.
     * This method will create a new net for the connection handling adding both
     * a logical net (EDIFNet) and physical net (Net).  In the case of a top level connection, the
     * physical net will not be created.
     * @param portName This module instance's port name to connect.
     * @param busIndex0 If the assigned port of this module instance is multi-bit,
     * specify the index to connect or -1 if single bit bus.
     * @param other The other module instance to connect to. If this is null, it will
     * connect it to an existing parent cell port named otherPortName. When null, this is presumed
     * an out of context design where there might not be a source/sink.
     * @param otherPortName The port name on the other module instance to connect to or
     * the top-level port of the the cell instance.
     * @param busIndex1 If the port (of the other module instance or the existing parent cell) is multi-bit,
     * specify the index to connect or -1 if single bit bus.
     */
    @Override
    public void connect(String portName, int busIndex0, ModuleInst other, String otherPortName, int busIndex1) {
        super.connect(portName, busIndex0, other, otherPortName, busIndex1);

        if (other == null) {
            return;
        }
        // Connect physical pins
        Port p0 = getPort(busIndex0 == -1 ? portName : portName + "[" + busIndex0 + "]");
        Port p1 = other.getPort(busIndex1 == -1 ? otherPortName : otherPortName + "[" + busIndex1 + "]");

        Net physicalNet;
        Port inPort;
        ModuleInst modInst = this;
        if (p0.isOutPort()) {
            physicalNet = getCorrespondingNet(p0);
            if (physicalNet == null) {
                // This is a pass-thru situation and we'll need to create the net
                EDIFCell top = getCellInst().getParentCell();
                String newNetName = super.getNewNetName(portName, busIndex0, other, otherPortName, busIndex1);
                physicalNet = design.createNet(newNetName);
            }
            inPort = p1;
            modInst = other;
        } else {
            physicalNet = other.getCorrespondingNet(p1);
            inPort = p0;
        }

        for (SitePinInst inPin : modInst.getCorrespondingPins(inPort)) {
            Net oldPhysicalNet = inPin.getNet();
            if (oldPhysicalNet != null) {
                oldPhysicalNet.removePin(inPin, true);
            }
            physicalNet.addPin(inPin);
        }
    }

    @Override
    public RelocatableTileRectangle getBoundingBox() {
        return module.getBoundingBox().getCorresponding(getAnchor().getTile(), module.getAnchor().getTile());
    }

    @Override
    public Site getPlacement() {
        return getAnchor().getSite();
    }

    @Override
    public boolean overlaps(ModuleInst hm) {
        if (!hm.isPlaced()) {
            return false;
        }
        return getBoundingBox().overlaps(hm.getBoundingBox());
    }

    /**
     * Gets the current set of used GND/VCC PIPs by this ModuleInst.  If the module instance 
     * is not placed, the set will be empty.  
     * @param staticNet A static net of the type to get.
     * @return The set of PIPs used by this module instance if placed, null if the net provided is
     * not a static net.
     */
    public Set<PIP> getUsedStaticPIPs(Net staticNet) {
        if (staticNet.getName().equals(Net.GND_NET)) {
            if (gndPIPs == null) {
                gndPIPs = new HashSet<>();
            }
            return gndPIPs;
        } else if (staticNet.getName().equals(Net.VCC_NET)) {
            if (vccPIPs == null) {
                vccPIPs = new HashSet<>();
            }
            return vccPIPs;
        }
        return null;
    }
}

package com.xilinx.rapidwright.interchange;

import java.io.IOException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;

import com.xilinx.rapidwright.design.Design;
import com.xilinx.rapidwright.design.Net;
import com.xilinx.rapidwright.design.SiteInst;
import com.xilinx.rapidwright.device.SitePIP;
import com.xilinx.rapidwright.edif.EDIFNetlist;

public class CompareSiteRouting {

    public static void compareSiteRouting(SiteInst expected, SiteInst found) {
        // Site Routing
        Set<SitePIP> usedSitePIPs = new HashSet<>(expected.getUsedSitePIPs());
        for(SitePIP sitePIP : found.getUsedSitePIPs()) {
            if(!usedSitePIPs.remove(sitePIP)) {
                System.out.println(expected.getSiteName() + " has sitePIP " + sitePIP + " that is not used in reference.");
            }
        }
        for(SitePIP sitePIP : usedSitePIPs) {
            System.out.println(expected.getSiteName() + " is missing sitePIP " + sitePIP + ".");
        }
        
        Map<String,Net> usedSiteWiresMap = new HashMap<>(expected.getNetSiteWireMap());
        for(Entry<String,Net> e : found.getNetSiteWireMap().entrySet()) {
            Net netUsed = usedSiteWiresMap.remove(e.getKey());
            if(netUsed == null) {
                System.out.println(expected.getSiteName() + " is using site wire " + e.getKey() + " by net " + e.getValue() + " that is not used in reference.");
            }
        }
        for(Entry<String, Net> e : usedSiteWiresMap.entrySet()) {
            System.out.println(expected.getSiteName() + " is not using site wire " + e.getKey() + " which is being used in the reference by net " + e.getValue());
        }
    }
    
    public static void main(String[] args) throws IOException {
        if(args.length != 2) {
            System.out.println("USAGE: <interchange.netlist> <interchange.phys>");
            return;
        }
        
        EDIFNetlist n2 = LogNetlistReader.readLogNetlist(args[0]);
        Design original = PhysNetlistReader.readPhysNetlist(args[1], n2);
        
        Design rwSiteRouted = PhysNetlistReader.readPhysNetlist(args[1], n2);
        rwSiteRouted.routeSites();
        
        for(SiteInst expected : rwSiteRouted.getSiteInsts()) {
            if(!expected.getSiteName().equals("SLICE_X100Y101")) continue;
            compareSiteRouting(expected, original.getSiteInstFromSiteName(expected.getSiteName()));
        }
        
    }
}

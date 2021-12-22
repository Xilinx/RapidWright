package com.xilinx.rapidwright.design;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import com.xilinx.rapidwright.device.BELPin;
import com.xilinx.rapidwright.support.CheckOpenFiles;
import com.xilinx.rapidwright.support.RapidWrightDCP;
import com.xilinx.rapidwright.tests.CodePerfTracker;
import com.xilinx.rapidwright.util.Pair;

public class TestDesignTools {

    private Pair<String,String> inputSiteWire1 = new Pair<>("SLICE_X16Y238","A2"); 
    
    private Pair<String,String> inputSiteWire2 = new Pair<>("SLICE_X13Y237","F5");
    
    private Map<Pair<String,String>,String> mimicInContextInputPortNetSiteRouting(Design design) {
        Map<Pair<String,String>,String> initialState = new HashMap<>();
        
        for(Pair<String,String> siteWire : Arrays.asList(inputSiteWire1, inputSiteWire2)) {
            SiteInst i = design.getSiteInstFromSiteName(siteWire.getFirst());
            Net net = i.getNetFromSiteWire(siteWire.getSecond());
            initialState.put(siteWire, net.getName());
            BELPin pin = i.getSiteWirePins(siteWire.getSecond())[0];
            i.unrouteIntraSiteNet(pin, pin);
            i.routeIntraSiteNet(design.getVccNet(), pin, pin);
        }
        
        return initialState;
    }
    
    @Test
    @CheckOpenFiles
    public void testResolveSiteRoutingFromInContextPorts() {
        String dcpPath = RapidWrightDCP.getString("picoblaze_ooc_X10Y235.dcp");
        Design design = Design.readCheckpoint(dcpPath, CodePerfTracker.SILENT);

        // Convert DCP to introduce test scenario
        Map<Pair<String,String>,String> initialSiteRoutes = mimicInContextInputPortNetSiteRouting(design);
        
        DesignTools.resolveSiteRoutingFromInContextPorts(design);
        
        for(Entry<Pair<String,String>,String> e : initialSiteRoutes.entrySet()) {
            SiteInst i = design.getSiteInstFromSiteName(e.getKey().getFirst());
            Net net = i.getNetFromSiteWire(e.getKey().getSecond());
            Assertions.assertEquals(net.getName(), e.getValue());
        }
    }
    
    @Test
    public void testCopyImplementationRouteThruVCCPinCheck() {
        String dcpPath = RapidWrightDCP.getString("bnn.dcp");
        Design srcDesign = Design.readCheckpoint(dcpPath);
        Design dstDesign = Design.readCheckpoint(dcpPath, true);
        DesignTools.copyImplementation(srcDesign, dstDesign, "bd_0_i/hls_inst/inst");
        
        SiteInst srcSiteInst = srcDesign.getSiteInstFromSiteName("SLICE_X73Y155");
        SiteInst dstSiteInst = dstDesign.getSiteInstFromSiteName(srcSiteInst.getSiteName());
        List<Pair<String,Boolean>> routeThrus = new ArrayList<>();
        routeThrus.add(new Pair<>("A6LUT", true)); // It has VCC pin
        routeThrus.add(new Pair<>("B6LUT", false)); // It does not have a VCC pin
        
        for(Pair<String, Boolean> routeThru : routeThrus) {
            Cell rtCell = dstSiteInst.getCell(routeThru.getFirst());
            Assertions.assertTrue(rtCell.isRoutethru());
            String siteWireName = rtCell.getBEL().getPin("A6").getSiteWireName();
            
            Assertions.assertEquals(srcSiteInst.getNetFromSiteWire(siteWireName).getName(),
                                    dstSiteInst.getNetFromSiteWire(siteWireName).getName());
            
            if(routeThru.getSecond()) {
                Assertions.assertEquals(dstSiteInst.getNetFromSiteWire(siteWireName), 
                                        dstDesign.getVccNet());
            }else {
                Assertions.assertNotEquals(dstSiteInst.getNetFromSiteWire(siteWireName), 
                                        dstDesign.getVccNet());
            }
        }
    }
}

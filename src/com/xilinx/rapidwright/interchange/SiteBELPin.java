package com.xilinx.rapidwright.interchange;

import com.xilinx.rapidwright.device.BELPin;
import com.xilinx.rapidwright.device.Site;

public class SiteBELPin {
    
    Site site;
    BELPin belPin;
    
    public SiteBELPin(Site site, BELPin belPin) {
        this.site = site;
        this.belPin = belPin;
    }        
    
    public String toString() {
        return site.getName() + "/" + belPin.toString();
    }
}

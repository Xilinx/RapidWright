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

    @Override
    public int hashCode() {
        final int prime = 31;
        int result = 1;
        result = prime * result
                + ((site == null) ? 0 : site.hashCode());
        result = prime * result
                + ((belPin == null) ? 0 : belPin.hashCode());
        return result;
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj)
            return true;
        if (obj == null)
            return false;
        if (getClass() != obj.getClass())
            return false;
        SiteBELPin other = (SiteBELPin) obj;
        if (!site.equals(other.site))
            return false;
        if (!belPin.equals(other.belPin))
            return false;
        return true;
    }
}

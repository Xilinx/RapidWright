package com.xilinx.rapidwright.rwroute;

import com.xilinx.rapidwright.design.SitePinInst;
import com.xilinx.rapidwright.device.Node;

import java.util.ArrayList;
import java.util.List;

public class RouteTermSitePin implements RouteTerm {
    private SitePinInst spi;

    RouteTermSitePin(SitePinInst spi) {
        this.spi = spi;
    }

    @Override
    public String getName() {
        return spi.getName();
    }

    @Override
    public Node getConnectedNode() {
        return spi.getConnectedNode();
    }

    @Override
    public SitePinInst getSitePinInst() {
        return spi;
    }

    @Override
    public void setRouted(boolean isRouted) {
        spi.setRouted(isRouted);
    }

    @Override
    public boolean isRouted() {
        return spi.isRouted();
    }

    @Override
    public String toString() {
        return spi.toString();
    }

    @Override
    public int hashCode() {
        return spi.hashCode();
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj)
            return true;
        if (obj == null)
            return false;
        if (getClass() != obj.getClass())
            return false;
        RouteTermSitePin that = (RouteTermSitePin) obj;
        return spi.equals(that.spi);
    }

    public static List<RouteTerm> asList(List<SitePinInst> pins) {
        List<RouteTerm> terms = new ArrayList<>(pins.size());
        for (SitePinInst spi : pins) {
            terms.add(new RouteTermSitePin(spi));
        }
        return terms;
    }

    public static List<SitePinInst> fromList(List<RouteTerm> terms) {
        List<SitePinInst> pins = new ArrayList<>(terms.size());
        for (RouteTerm term : terms) {
            SitePinInst spi = term.getSitePinInst();
            if (spi == null) {
                throw new RuntimeException();
            }
            pins.add(spi);
        }
        return pins;
    }
}

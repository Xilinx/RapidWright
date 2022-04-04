package com.xilinx.rapidwright.edif;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;

public class EDIFPortCache {
    private final Map<String, EDIFPort> cache;
    private final EDIFCell cell;

    public EDIFPortCache(EDIFCell cell) {
        this.cell = cell;
        /*if (cell.getPorts().size()<1000) {
            cache = null;
            return;
        }*/
        cache = new HashMap<>(cell.getPortMap());
        final Collection<EDIFPort> ports = cell.getPorts();
        for (EDIFPort port : ports) {
            if (port.getEDIFName() != null) {
                cache.put(port.getEDIFName(), port);
            }
        }

    }

    public EDIFPort getPort(String name) {
        return cache.get(name);
    }
}

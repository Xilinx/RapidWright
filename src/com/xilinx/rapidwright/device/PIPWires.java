package com.xilinx.rapidwright.device;

/**
 * Simple pair of ints that act as the start and end wire of a prototype PIP.  Primarily used
 * as a key for a map of PIP types.
 */
public class PIPWires {

    private int startWire;
    
    private int endWire;

    public PIPWires(int startWire, int endWire) {
        super();
        this.startWire = startWire;
        this.endWire = endWire;
    }

    public int getStartWire() {
        return startWire;
    }

    public void setStartWire(int startWire) {
        this.startWire = startWire;
    }

    public int getEndWire() {
        return endWire;
    }

    public void setEndWire(int endWire) {
        this.endWire = endWire;
    }

    @Override
    public int hashCode() {
        final int prime = 31;
        int result = 1;
        result = prime * result + endWire;
        result = prime * result + startWire;
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
        PIPWires other = (PIPWires) obj;
        if (endWire != other.endWire)
            return false;
        if (startWire != other.startWire)
            return false;
        return true;
    }
}

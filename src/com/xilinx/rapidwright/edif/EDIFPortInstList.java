package com.xilinx.rapidwright.edif;

import java.util.ArrayList;

public class EDIFPortInstList extends ArrayList<EDIFPortInst> {

    private static final long serialVersionUID = 8718591209309655922L;

    public static final EDIFPortInstList EMPTY = new EDIFPortInstList();
    
    @Override
    public boolean add(EDIFPortInst e) {
        if(e.getCellInst().getName().equals("processor") && e.getName().equals("DOADO[2]")) {
            System.out.println(e);
        }
        
        int insertionPoint = binarySearch(e.getCellInst(), e.getName());
        if(insertionPoint >= 0) {
            System.out.println();
        }
        // We allow duplicates during EDIF parsing
        super.add(insertionPoint >= 0 ? insertionPoint : ~insertionPoint, e);
        return true;
    }
    
    public EDIFPortInst get(EDIFCellInst i, String name) {
        int index = binarySearch(i, name);
        if(index < 0) return null;
        return get(index);
    }
    
    public EDIFPortInst remove(EDIFPortInst e) {
        return remove(e.getCellInst(), e.getName());
    }

    public EDIFPortInst remove(EDIFCellInst inst, String portInstName) {
        int index = binarySearch(inst, portInstName);
        if(index < 0) return null;
        return super.remove(index);
    }
    
    private int binarySearch(EDIFCellInst inst, String portInstName) {
        String instName = inst == null ? null : inst.getName();
        int left = 0;
        int right = size()-1;
        while(left <= right) {
            int pivot = (left + right) >>> 1;
            int result = compare(get(pivot), instName, portInstName);
            if(result < 0) {
                left = pivot + 1;
            } else if (result > 0) {
                right = pivot - 1;
            } else {
                return pivot;
            }
        }
        return ~left;
    }
    
    private int compare(EDIFPortInst left, String rightInstName, String rightPortInstName) {
        String leftInstName = left.getCellInst() == null ? null : left.getCellInst().getName();
        if(leftInstName == null) {
            return left.getName().compareTo(rightInstName == null ? rightPortInstName : rightInstName);
        } else if(rightInstName == null) {
            return leftInstName.compareTo(rightPortInstName);
        }
        int compare = leftInstName.compareTo(rightInstName);
        return compare == 0 ? left.getName().compareTo(rightPortInstName) : compare; 
    }
}

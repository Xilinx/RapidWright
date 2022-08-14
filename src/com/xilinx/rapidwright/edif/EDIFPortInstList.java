/* 
 * Copyright (c) 2022 Xilinx, Inc. 
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
 
package com.xilinx.rapidwright.edif;

import java.util.ArrayList;

/**
 * Customized ArrayList<EDIFPortInst> for the {@link EDIFNet} and {@link EDIFCellInst} classes. 
 * Maintains a sorted list to allow for a O(log n) retrieval lookup by name.  Does not allow 
 * duplicate entries. 
 */
public class EDIFPortInstList extends ArrayList<EDIFPortInst> {

    private static final long serialVersionUID = 8718591209309655922L;
    
    public static final EDIFPortInstList EMPTY = new EDIFPortInstList();
    
    @Override
    public boolean add(EDIFPortInst e) {
        int insertionPoint = binarySearch(e.getCellInst(), e.getName());
        // Do not allow duplicates
        if(insertionPoint >= 0) {
            return false;
        }
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
    
    /**
     * Performs a 'compareTo' operation without having to create disposable Strings or EDIFPortInst 
     * objects.  Performs the same operation as 'left.getFullName().compareTo(right.getFullName())'
     *  where right is the EDIFPortInst represented by rightInstName and rightPortInstName.  
     * @param left This is the existing EDIFPortInst within the lists that is being compared
     * @param rightInstName This is the cell instance name {@link EDIFCellInst#getName()} of the 
     * considered port instance to compare left against.  
     * @param rightPortInstName This is the port instance name {@link EDIFPortInst#getName()} of the
     * considered port instance to compare left against.
     * @return 0 if the left and corresponding right Strings are equal.  A number less than 0 if
     * left is lexicographically before right, or a number greater than 0 if left is after right. 
     */
    private int compare(EDIFPortInst left, String rightInstName, String rightPortInstName) {
        if(left.getCellInst() == null) {
            if(rightInstName == null) {
                // left and right are both a top-level port insts, compare their port insts name only
                return left.getName().compareTo(rightPortInstName);
            }
            int compare = left.getName().compareTo(rightInstName);
            return compare == 0 ? -(rightPortInstName.length()) : compare;
        } else if(rightInstName == null) {
            // right is a top-level port inst, but left is not. Compare left's inst name with 
            // right's port inst name.
            int compare = left.getCellInst().getName().compareTo(rightPortInstName);
            // If the two happen to be equal, then right's full name is a prefix of left's full name
            // and thus left should go after right.
            return compare == 0 ? left.getName().length() : compare;
        }
        // left and right are both non top-level port insts. Compare their inst names first, then 
        // compare their port inst names.
        int compare = left.getCellInst().getName().compareTo(rightInstName);
        return compare == 0 ? left.getName().compareTo(rightPortInstName) : compare; 
    }
}

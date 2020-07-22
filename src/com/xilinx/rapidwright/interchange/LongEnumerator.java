package com.xilinx.rapidwright.interchange;

import java.util.ArrayList;
import java.util.HashMap;

public class LongEnumerator extends ArrayList<Long> {

    private static final long serialVersionUID = 5235125492429382642L;

    private HashMap<Long, Integer> map = new HashMap<Long, Integer>();
    
    
    public Integer getIndex(Long obj) {
        Integer idx = map.get(obj);
        if (idx == null) {
            idx = map.size();
            map.put(obj, idx);
            add(obj);
        }
        return idx;
    }
    
    public void addObject(Long obj) {
        getIndex(obj);
    }
    
    public void update(Long obj, int index) {
        set(index, obj);
        map.put(obj, index);
    }
    
    public void ensureSize(int size) {
        ensureCapacity(size);
        while(size() < size) {
            add(null);
        }
    }
    
    @Override
    public Long get(int index) {
        if(size() -1 < index) return null;
        return super.get(index);
    }
    
    @Override
    public void clear() {
        super.clear();
        map.clear();
    }
}


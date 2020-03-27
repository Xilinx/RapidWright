package com.xilinx.rapidwright.interchange;

import java.util.ArrayList;
import java.util.HashMap;

import com.xilinx.rapidwright.edif.EDIFEnumerable;

public class Enumerator<T> extends ArrayList<T> {

    private static final long serialVersionUID = 5235125492429382642L;

    private HashMap<String, Integer> map = new HashMap<String, Integer>();
    
    private String getKey(T obj) {
        String key = null;
        if(obj instanceof EDIFEnumerable) {
            key = ((EDIFEnumerable)obj).getUniqueKey();
        } else {
            key = obj.toString();
        }
        return key;
    }
    
    public Integer getIndex(T obj) {
        String key = getKey(obj);
        Integer idx = map.get(key);
        if (idx == null) {
            idx = map.size();
            map.put(key, idx);
            add(obj);
        }
        return idx;
    }
    
    public void addObject(T obj) {
        getIndex(obj);
    }
    
    public void update(T obj, int index) {
        set(index, obj);
        map.put(getKey(obj), index);
    }
    
    public void ensureSize(int size) {
        ensureCapacity(size);
        while(size() < size) {
            add(null);
        }
    }
    
    @Override
    public T get(int index) {
        if(size() -1 < index) return null;
        return super.get(index);
    }
    
    @Override
    public void clear() {
        super.clear();
        map.clear();
    }
}

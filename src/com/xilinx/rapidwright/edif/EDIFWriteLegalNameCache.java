package com.xilinx.rapidwright.edif;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Helper class that keeps track of EDIF Renames during writing. This class is thread-safe.
 */
public abstract class EDIFWriteLegalNameCache<T> {

    /**
     * Marker String to indicate that some does not need to be renamed.
     *
     * We cannot use null, as null values have special behaviour in {@link Map#computeIfAbsent(Object, Function)}.
     */
    private static final String MARKER_NO_RENAME = "";

    /**
     * Maps from unsuffixed legal name to the number of times we saw it from different source names
     */
    protected final Map<String, T> usedRenames;
    /**
     * The actual renamed names
     */
    private final Map<String, String>[] renames;

    private EDIFWriteLegalNameCache(Map<String, T> usedRenames, Supplier<Map<String, String>> renameSupplier) {
        this.usedRenames = usedRenames;
        this.renames = new Map[256];
        for (int i = 0; i < renames.length; i++) {
            renames[i] = renameSupplier.get();
        }
    }

    protected abstract int getAndIncrement(String rename);

    private String calcRename(String name) {
        final String rename = EDIFTools.makeNameEDIFCompatible(name);
        if (rename.equals(name)) {
            return MARKER_NO_RENAME;
        }
        int previousCount = getAndIncrement(rename);
        if (previousCount == 0) {
            return rename;
        }
        return rename+"_HDI_"+(previousCount-1);
    }

    public String getEDIFRename(String name) {
        Map<String, String> map = renames[name.charAt(0)&0xFF];
        final String rename = map.computeIfAbsent(name, this::calcRename);
        //Checking equality against this special marker instance is ok
        if (rename == MARKER_NO_RENAME) {
            return null;
        }
        return rename;
    }

    public static EDIFWriteLegalNameCache<?> singleThreaded() {
        return new EDIFWriteLegalNameCache<Integer>(new HashMap<>(), HashMap::new) {

            @Override
            protected int getAndIncrement(String rename) {
                int count = usedRenames.getOrDefault(rename, 0);
                usedRenames.put(rename, count+1);
                return count;
            }
        };
    }

    public static EDIFWriteLegalNameCache<?> multiThreaded() {
        return new EDIFWriteLegalNameCache<AtomicInteger>(new ConcurrentHashMap<>(), HashMap::new) {
            @Override
            protected int getAndIncrement(String rename) {
                AtomicInteger counter = usedRenames.computeIfAbsent(rename, x->new AtomicInteger());
                return counter.getAndIncrement();
            }
        };
    }
}

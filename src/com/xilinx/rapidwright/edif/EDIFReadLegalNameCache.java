package com.xilinx.rapidwright.edif;

import java.util.IdentityHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public abstract class EDIFReadLegalNameCache {

    private static class IdentityEqualsHash<T> {
        private final T obj;

        private IdentityEqualsHash(T obj) {
            this.obj = obj;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            IdentityEqualsHash<?> that = (IdentityEqualsHash<?>) o;
            return obj==that.obj;
        }

        @Override
        public int hashCode() {
            return System.identityHashCode(obj);
        }
    }

    private EDIFReadLegalNameCache() {
    }

    public abstract void setRename(EDIFName name, String rename);
    public abstract String getEDIFName(EDIFName name);

    public String getLegalEDIFName(EDIFName name) {
        String rename = getEDIFName(name);
        if (rename != null) {
            return rename;
        }
        return name.getName();
    }

    public static EDIFReadLegalNameCache createSingleThreaded() {
        return new EDIFReadLegalNameCache() {
            private final Map<EDIFName, String> renames = new IdentityHashMap<>();
            public void setRename(EDIFName name, String rename) {
                renames.put(name, rename);
            }
            public String getEDIFName(EDIFName name) {
                return renames.get(name);
            }
        };
    }
    public static EDIFReadLegalNameCache createMultiThreaded() {
        return new EDIFReadLegalNameCache() {
            private final Map<IdentityEqualsHash<EDIFName>, String> renames = new ConcurrentHashMap<>();
            public void setRename(EDIFName name, String rename) {
                renames.put(new IdentityEqualsHash<>(name), rename);
            }
            public String getEDIFName(EDIFName name) {
                return renames.get(new IdentityEqualsHash<>(name));
            }
        };
    }
}

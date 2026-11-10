/*
 * Copyright (c) 2026, Advanced Micro Devices, Inc.
 * All rights reserved.
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

package com.xilinx.rapidwright.support;

import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryPoolMXBean;
import java.lang.management.MemoryType;
import java.lang.management.MemoryUsage;

import org.junit.jupiter.api.extension.AfterAllCallback;
import org.junit.jupiter.api.extension.AfterEachCallback;
import org.junit.jupiter.api.extension.ExtensionContext;

import com.xilinx.rapidwright.device.Device;

/**
 * Keeps the heap of the shared test JVM from filling up with cached devices.
 *
 * Device.getDevice() keeps every device it loads, so over a whole test run the cache grows to most
 * of the heap, and the garbage collector then spends most of the time in back-to-back full
 * collections (especially with the serial collector, which the JVM picks when only one processor
 * is available). This extension clears the device cache after any test or test class once the
 * old generation still holds more than {@link #MAX_HEAP_FRACTION} of the heap after a garbage
 * collection. Later tests then reload the devices they need. Measuring the occupancy after a
 * collection (rather than the current heap usage, which includes garbage), and waiting for
 * another such collection after each release, avoids clearing the cache, and reloading the same
 * devices, after every test.
 */
public class ReleaseDevicesExtension implements AfterEachCallback, AfterAllCallback {

    /** Fraction of the maximum heap above which the device cache is cleared */
    private static final double MAX_HEAP_FRACTION = 0.4;

    /** Old generation collection count when the device cache was last cleared */
    private static long collectionsAtLastRelease = -1;

    private static boolean isOldGenPool(MemoryPoolMXBean pool) {
        String name = pool.getName();
        return pool.getType() == MemoryType.HEAP && (name.contains("Old Gen") || name.contains("Tenured"));
    }

    private static boolean isOldGenPool(String poolName) {
        return poolName.contains("Old Gen") || poolName.contains("Tenured");
    }

    /**
     * @return The number of garbage collections that collected (part of) the old generation.
     */
    private static long getOldGenCollectionCount() {
        long count = 0;
        for (GarbageCollectorMXBean gc : ManagementFactory.getGarbageCollectorMXBeans()) {
            for (String poolName : gc.getMemoryPoolNames()) {
                if (isOldGenPool(poolName)) {
                    count += Math.max(0, gc.getCollectionCount());
                    break;
                }
            }
        }
        return count;
    }

    /**
     * @return The heap occupied after the most recent garbage collection of the old generation,
     *         or the current heap usage if the JVM does not report it.
     */
    private static long getRetainedHeapBytes() {
        long retained = -1;
        for (MemoryPoolMXBean pool : ManagementFactory.getMemoryPoolMXBeans()) {
            if (!isOldGenPool(pool)) {
                continue;
            }
            MemoryUsage usage = pool.getCollectionUsage();
            if (usage != null) {
                retained = Math.max(retained, usage.getUsed());
            }
        }
        if (retained < 0) {
            Runtime rt = Runtime.getRuntime();
            retained = rt.totalMemory() - rt.freeMemory();
        }
        return retained;
    }

    private static synchronized void releaseDevicesIfHeapIsFilling() {
        long collections = getOldGenCollectionCount();
        if (collections == collectionsAtLastRelease) {
            // The retained size predates the last release, so it says nothing about the cache now
            return;
        }
        if (getRetainedHeapBytes() > MAX_HEAP_FRACTION * Runtime.getRuntime().maxMemory()) {
            Device.releaseDeviceReferences();
            collectionsAtLastRelease = collections;
        }
    }

    @Override
    public void afterEach(ExtensionContext context) {
        releaseDevicesIfHeapIsFilling();
    }

    @Override
    public void afterAll(ExtensionContext context) {
        releaseDevicesIfHeapIsFilling();
    }
}

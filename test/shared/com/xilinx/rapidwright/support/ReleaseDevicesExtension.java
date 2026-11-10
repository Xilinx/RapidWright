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
 * that measurement to be refreshed by another collection after each release, avoids clearing the
 * cache, and reloading the same devices, after every test.
 */
public class ReleaseDevicesExtension implements AfterEachCallback, AfterAllCallback {

    /** Fraction of the maximum heap above which the device cache is cleared */
    private static final double MAX_HEAP_FRACTION = 0.4;

    /** Retained heap measured when the device cache was last cleared */
    private static long retainedAtLastRelease = -1;

    private static boolean isOldGenPool(MemoryPoolMXBean pool) {
        String name = pool.getName();
        return pool.getType() == MemoryType.HEAP && (name.contains("Old Gen") || name.contains("Tenured"));
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
        long retained = getRetainedHeapBytes();
        if (retained == retainedAtLastRelease) {
            // No collection has measured the old generation since the last release (e.g. G1 only
            // measures it in mixed and full collections, not in young ones), so this measurement
            // predates the release and says nothing about the cache now
            return;
        }
        if (retained > MAX_HEAP_FRACTION * Runtime.getRuntime().maxMemory()) {
            Device.releaseDeviceReferences();
            retainedAtLastRelease = retained;
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

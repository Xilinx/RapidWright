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
 * is available). This extension clears the device cache after any test or test class that leaves
 * the heap more than {@link #MAX_HEAP_FRACTION} full. Later tests then reload the devices they
 * need. Clearing it unconditionally would make every test class reload common devices.
 */
public class ReleaseDevicesExtension implements AfterEachCallback, AfterAllCallback {

    /** Fraction of the maximum heap above which the device cache is cleared */
    private static final double MAX_HEAP_FRACTION = 0.4;

    private static void releaseDevicesIfHeapIsFilling() {
        Runtime rt = Runtime.getRuntime();
        long used = rt.totalMemory() - rt.freeMemory();
        if (used > MAX_HEAP_FRACTION * rt.maxMemory()) {
            Device.releaseDeviceReferences();
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

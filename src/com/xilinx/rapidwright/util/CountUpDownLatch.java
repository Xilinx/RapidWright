/* 
 * Copyright (c) 2022 Xilinx, Inc. 
 * All rights reserved.
 *
 * Author: Eddie Hung, Xilinx Research Labs.
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
 
package com.xilinx.rapidwright.util;

import java.util.concurrent.atomic.AtomicInteger;

public class CountUpDownLatch {
    private final AtomicInteger count;
    private final Object lock;

    public CountUpDownLatch() {
        count = new AtomicInteger();
        lock = new Object();
    }

    public void countUp() {
        count.incrementAndGet();
    }

    public void countDown() {
        int value = count.decrementAndGet();
        if (value == 0) {
            synchronized (lock) {
                lock.notifyAll();
            }
        }
    }

    public void await() throws InterruptedException {
        synchronized (lock) {
            // AtomicInteger.get() is not synchronized, but it
            // is expected that by the time await() is called
            // all countUp()-s have been completed and only
            // countDown()-s are called.
            while(count.get() > 0) lock.wait();
        }
    }

}

/*
 * Copyright (c) 2022, Xilinx, Inc.
 * Copyright (c) 2022-2023, Advanced Micro Devices, Inc.
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

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Semaphore;

/**
 * Mirrors {@link CountDownLatch} functionality but with the ability to
 * count up too.
 */
public class CountUpDownLatch {
    private int count;
    private Semaphore sem;

    public CountUpDownLatch() {
        count = 0;
        sem = new Semaphore(0);
    }

    public int countUp() {
        return count++;
    }

    public void countDown() {
        sem.release();
    }

    public void await() {
        sem.acquireUninterruptibly(count);
        count = 0;
    }

}

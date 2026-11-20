/*
 * Copyright (c) 2026, Advanced Micro Devices, Inc.
 * All rights reserved.
 *
 * Author: Eddie Hung, Advanced Micro Devices, Inc.
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

package com.xilinx.rapidwright.rwroute;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import com.xilinx.rapidwright.design.Design;
import com.xilinx.rapidwright.design.Net;
import com.xilinx.rapidwright.design.SitePinInst;
import com.xilinx.rapidwright.util.ParallelismTools;

// A hang (e.g. a task that is never run) fails the test rather than stalling the build
@Timeout(value = 120, unit = TimeUnit.SECONDS)
public class TestRouteNodeGraph {

    /**
     * awaitPreserve() must not return (or throw) while a preserveAsync() task is still running, even if the calling
     * thread has been interrupted: the task is still modifying the graph.
     */
    @Test
    public void testAwaitPreserveWaitsWhenInterrupted() throws InterruptedException {
        final boolean wasParallel = ParallelismTools.getParallel();
        // With parallel processing disabled, preserveAsync() runs the task itself, so there is nothing to wait for
        Assumptions.assumeTrue(ParallelismTools.setParallel(true));
        final CountDownLatch started = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        final AtomicBoolean finished = new AtomicBoolean();
        try {
            Design design = new Design("test", "xcvu3p");
            RouteNodeGraph routingGraph = new RouteNodeGraph(design, new RWRouteConfig(new String[0])) {
                @Override
                public void preserve(Net net, List<SitePinInst> pins) {
                    started.countDown();
                    try {
                        release.await();
                    } catch (InterruptedException e) {
                        throw new RuntimeException(e);
                    }
                    finished.set(true);
                }
            };
            routingGraph.preserveAsync(design.createNet("net"));
            // Only a thread of the pool can have started it, as this thread has not waited for it yet
            Assertions.assertTrue(started.await(60, TimeUnit.SECONDS));

            Thread releaser = new Thread(() -> {
                try {
                    Thread.sleep(500);
                } catch (InterruptedException e) {
                    // Release it anyway
                }
                release.countDown();
            });
            releaser.start();
            Thread.currentThread().interrupt();
            try {
                routingGraph.awaitPreserve();
                Assertions.assertTrue(finished.get(), "awaitPreserve() returned while its task was still running");
            } finally {
                // Clear this thread's interrupt status, for the tests that follow
                Thread.interrupted();
            }
            releaser.join();

            // Reusable: a later task is waited for too
            finished.set(false);
            routingGraph.preserveAsync(design.createNet("net2"));
            routingGraph.awaitPreserve();
            Assertions.assertTrue(finished.get());
        } finally {
            release.countDown();
            ParallelismTools.setParallel(wasParallel);
        }
    }

    /** An exception thrown by a preserveAsync() task is rethrown, rather than only printed */
    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    public void testAwaitPreserveRethrows(boolean parallel) {
        final boolean wasParallel = ParallelismTools.getParallel();
        Assumptions.assumeTrue(ParallelismTools.setParallel(parallel));
        try {
            Design design = new Design("test", "xcvu3p");
            RouteNodeGraph routingGraph = new RouteNodeGraph(design, new RWRouteConfig(new String[0])) {
                @Override
                public void preserve(Net net, List<SitePinInst> pins) {
                    throw new IllegalStateException("preserve failed");
                }
            };
            Net net = design.createNet("net");
            // By awaitPreserve(), or by preserveAsync() itself when parallel processing is disabled
            Assertions.assertThrows(IllegalStateException.class, () -> {
                routingGraph.preserveAsync(net);
                routingGraph.awaitPreserve();
            });
        } finally {
            ParallelismTools.setParallel(wasParallel);
        }
    }
}

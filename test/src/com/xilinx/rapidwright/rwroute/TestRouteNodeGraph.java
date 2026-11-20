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
import java.util.concurrent.atomic.AtomicReference;

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

// Stops a test whose own thread waits for too long. It cannot stop awaitPreserve(), which waits uninterruptibly: see
// awaitPreserve(RouteNodeGraph, boolean)
@Timeout(value = 120, unit = TimeUnit.SECONDS)
public class TestRouteNodeGraph {

    /** How long (in seconds) anything here waits for another thread, before the test fails */
    private static final long WAIT_SECONDS = 60;

    /**
     * Calls awaitPreserve() on a (daemon) thread of its own, and waits only so long for it to return. Its wait is
     * uninterruptible, so JUnit's timeout (which interrupts the test's thread) could not stop a call that never
     * returns were it made on the test's thread: the test would hang, rather than fail. Made on another thread, the
     * test fails instead, and that thread, as a daemon, does not keep the JVM from exiting.
     * @param routingGraph The graph to call awaitPreserve() on.
     * @param interrupted Whether the calling thread has been interrupted when it calls awaitPreserve().
     * @return What awaitPreserve() threw (null if it returned normally).
     */
    private static Throwable awaitPreserve(RouteNodeGraph routingGraph, boolean interrupted)
            throws InterruptedException {
        final AtomicReference<Throwable> thrown = new AtomicReference<>();
        Thread thread = new Thread(() -> {
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
            try {
                routingGraph.awaitPreserve();
            } catch (Throwable e) {
                thrown.set(e);
            }
        }, "awaitPreserve");
        thread.setDaemon(true);
        thread.start();
        thread.join(TimeUnit.SECONDS.toMillis(WAIT_SECONDS));
        Assertions.assertFalse(thread.isAlive(), "awaitPreserve() did not return within " + WAIT_SECONDS + " s");
        return thrown.get();
    }

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
                        // Bounded, so that a thread of the pool is not held forever if the test fails
                        if (!release.await(WAIT_SECONDS, TimeUnit.SECONDS)) {
                            throw new IllegalStateException("preserve() was not released");
                        }
                    } catch (InterruptedException e) {
                        throw new RuntimeException(e);
                    }
                    finished.set(true);
                }
            };
            routingGraph.preserveAsync(design.createNet("net"));
            // Only a thread of the pool can have started it, as no thread has waited for it yet
            Assertions.assertTrue(started.await(WAIT_SECONDS, TimeUnit.SECONDS));

            Thread releaser = new Thread(() -> {
                try {
                    Thread.sleep(500);
                } catch (InterruptedException e) {
                    // Release it anyway
                }
                release.countDown();
            });
            releaser.setDaemon(true);
            releaser.start();
            Assertions.assertNull(awaitPreserve(routingGraph, true));
            Assertions.assertTrue(finished.get(), "awaitPreserve() returned while its task was still running");

            // Reusable: a later task is waited for too
            finished.set(false);
            routingGraph.preserveAsync(design.createNet("net2"));
            Assertions.assertNull(awaitPreserve(routingGraph, false));
            Assertions.assertTrue(finished.get());
        } finally {
            release.countDown();
            ParallelismTools.setParallel(wasParallel);
        }
    }

    /** An exception thrown by a preserveAsync() task is rethrown, rather than only printed */
    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    public void testAwaitPreserveRethrows(boolean parallel) throws InterruptedException {
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
            Throwable thrown;
            try {
                routingGraph.preserveAsync(net);
                thrown = awaitPreserve(routingGraph, false);
            } catch (IllegalStateException e) {
                thrown = e;
            }
            Assertions.assertTrue(thrown instanceof IllegalStateException, "thrown: " + thrown);
        } finally {
            ParallelismTools.setParallel(wasParallel);
        }
    }
}

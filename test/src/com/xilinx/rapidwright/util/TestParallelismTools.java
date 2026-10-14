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

package com.xilinx.rapidwright.util;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.ForkJoinWorkerThread;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicIntegerArray;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

// A hang (e.g. a task that is never run) fails the test rather than stalling the build
@Timeout(value = 120, unit = TimeUnit.SECONDS)
public class TestParallelismTools {

    private static final int NUM_TASKS = 200;

    /** Tasks that count how many times each is run, and spin for a while so that they overlap */
    @SuppressWarnings("unchecked")
    private static Callable<Integer>[] countingTasks(AtomicIntegerArray runs) {
        final Callable<Integer>[] tasks = new Callable[runs.length()];
        for (int i = 0; i < tasks.length; i++) {
            final int index = i;
            tasks[i] = () -> {
                runs.incrementAndGet(index);
                long x = index;
                for (int j = 0; j < 100_000; j++) {
                    x = x * 6364136223846793005L + 1442695040888963407L;
                }
                // Always true; uses x so that the loop is not optimized away
                return x != 0 || index >= 0 ? index : -1;
            };
        }
        return tasks;
    }

    private static void assertEachRunOnce(AtomicIntegerArray runs) {
        for (int i = 0; i < runs.length(); i++) {
            Assertions.assertEquals(1, runs.get(i), "task " + i);
        }
    }

    /** Collect the results of invokeFirstSubmitRest() in order, checking that each task is run exactly once */
    private static void checkJoinFirstInOrder() {
        final AtomicIntegerArray runs = new AtomicIntegerArray(NUM_TASKS);
        final Deque<Future<Integer>> futures = ParallelismTools.invokeFirstSubmitRest(countingTasks(runs));
        for (int i = 0; i < NUM_TASKS; i++) {
            Assertions.assertEquals(i, ParallelismTools.joinFirst(futures));
        }
        Assertions.assertTrue(futures.isEmpty());
        assertEachRunOnce(runs);
    }

    /** Submit tasks one by one and collect their results in order with joinFirst(), as EDIF export does */
    private static void checkSubmitJoinFirstInOrder() {
        final AtomicIntegerArray runs = new AtomicIntegerArray(NUM_TASKS);
        final Deque<Future<Integer>> futures = new ArrayDeque<>();
        for (Callable<Integer> task : countingTasks(runs)) {
            futures.add(ParallelismTools.submit(task));
        }
        for (int i = 0; i < NUM_TASKS; i++) {
            Assertions.assertEquals(i, ParallelismTools.joinFirst(futures));
        }
        assertEachRunOnce(runs);
    }

    @Test
    public void testJoinFirstInOrder() {
        checkJoinFirstInOrder();
    }

    @Test
    public void testSubmitJoinFirstInOrder() {
        checkSubmitJoinFirstInOrder();
    }

    @Test
    public void testFromPoolThread() throws Exception {
        // The same on a thread of a ForkJoinPool (which blocks in managedBlock()); a separate pool, so
        // that this thread cannot run the task itself
        final ForkJoinPool pool = new ForkJoinPool(4);
        try {
            pool.submit(() -> {
                Assertions.assertTrue(Thread.currentThread() instanceof ForkJoinWorkerThread);
                checkJoinFirstInOrder();
                checkSubmitJoinFirstInOrder();
                return null;
            }).get();
        } finally {
            pool.shutdown();
        }
    }

    @Test
    public void testSubmitJoinRunsEachTaskOnce() {
        final AtomicIntegerArray runs = new AtomicIntegerArray(NUM_TASKS);
        final List<Future<Integer>> futures = new ArrayList<>();
        for (Callable<Integer> task : countingTasks(runs)) {
            futures.add(ParallelismTools.submit(task));
        }
        ParallelismTools.join(futures);
        for (int i = 0; i < NUM_TASKS; i++) {
            Assertions.assertEquals(i, ParallelismTools.get(futures.get(i)));
        }
        assertEachRunOnce(runs);
    }

    @Test
    public void testJoinRunsEachDeferredTaskOnce() {
        final AtomicIntegerArray runs = new AtomicIntegerArray(NUM_TASKS);
        final List<Future<Integer>> futures = new ArrayList<>(
                ParallelismTools.invokeFirstSubmitRest(countingTasks(runs)));
        ParallelismTools.join(futures);
        for (int i = 0; i < NUM_TASKS; i++) {
            Assertions.assertEquals(i, ParallelismTools.get(futures.get(i)));
        }
        assertEachRunOnce(runs);
    }

    @Test
    public void testExceptionRethrownAsIs() {
        final IllegalStateException thrown = new IllegalStateException("task 1");
        final Deque<Future<Integer>> futures = ParallelismTools.invokeFirstSubmitRest(
                () -> 0,
                () -> {
                    throw thrown;
                },
                () -> 2);
        Assertions.assertEquals(0, ParallelismTools.joinFirst(futures));
        // The task's own exception, whichever thread ran it
        Assertions.assertSame(thrown,
                Assertions.assertThrows(IllegalStateException.class, () -> ParallelismTools.joinFirst(futures)));
        Assertions.assertEquals(2, ParallelismTools.joinFirst(futures));

        final Future<Object> future = ParallelismTools.submit(() -> {
            throw thrown;
        });
        Assertions.assertSame(thrown,
                Assertions.assertThrows(IllegalStateException.class, () -> ParallelismTools.get(future)));
    }

    @Test
    public void testCheckedExceptionWrapped() {
        final Exception thrown = new Exception("checked");
        final Future<Object> future = ParallelismTools.submit(() -> {
            throw thrown;
        });
        final RuntimeException rethrown = Assertions.assertThrows(RuntimeException.class,
                () -> ParallelismTools.get(future));
        Assertions.assertSame(thrown, rethrown.getCause());
    }

    @Test
    public void testFirstTaskExceptionRethrownByJoinFirst() {
        final Deque<Future<Integer>> futures = ParallelismTools.invokeFirstSubmitRest(
                () -> {
                    throw new IllegalArgumentException("task 0");
                },
                () -> 1);
        Assertions.assertThrows(IllegalArgumentException.class, () -> ParallelismTools.joinFirst(futures));
        Assertions.assertEquals(1, ParallelismTools.joinFirst(futures));
    }

    @Test
    public void testJoinAllCancelsRestAfterFailure() {
        final boolean wasParallel = ParallelismTools.getParallel();
        // With parallel processing disabled, tasks are deferred until waited for, so those after the one that throws
        // cannot have been started when joinAll() rethrows
        ParallelismTools.setParallel(false);
        try {
            final IllegalStateException thrown = new IllegalStateException("task 1");
            final AtomicIntegerArray runs = new AtomicIntegerArray(4);
            final List<CompletableFuture<Integer>> completables = new ArrayList<>();
            completables.add(ParallelismTools.submitCompletable(() -> runs.incrementAndGet(0)));
            completables.add(ParallelismTools.submitCompletable(() -> {
                runs.incrementAndGet(1);
                throw thrown;
            }));
            completables.add(ParallelismTools.submitCompletable(() -> runs.incrementAndGet(2)));
            final Future<Integer> plain = ParallelismTools.submit(() -> runs.incrementAndGet(3));
            // Work chained on the CompletableFutures in order, as by EDIF export's relay
            CompletableFuture<Void> chained = CompletableFuture.completedFuture(null);
            for (CompletableFuture<Integer> completable : completables) {
                chained = chained.thenCombine(completable, (x, y) -> null);
            }
            final List<Future<Integer>> futures = new ArrayList<>(completables);
            futures.add(plain);

            Assertions.assertSame(thrown,
                    Assertions.assertThrows(IllegalStateException.class, () -> ParallelismTools.joinAll(futures)));
            // The tasks after it were cancelled rather than run, so the chained work has completed without them
            Assertions.assertEquals(1, runs.get(0));
            Assertions.assertEquals(1, runs.get(1));
            Assertions.assertTrue(completables.get(2).isCancelled());
            Assertions.assertTrue(plain.isCancelled());
            Assertions.assertTrue(chained.isDone());
            // Nor are they run by waiting for them afterwards, even with their own join() or get()
            Assertions.assertThrows(CancellationException.class, () -> completables.get(2).join());
            Assertions.assertThrows(CancellationException.class, plain::get);
            Assertions.assertEquals(0, runs.get(2));
            Assertions.assertEquals(0, runs.get(3));
        } finally {
            ParallelismTools.setParallel(wasParallel);
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    public void testInvokeFirstSubmitRestNoTasks() {
        Assertions.assertTrue(ParallelismTools.invokeFirstSubmitRest(new Callable[0]).isEmpty());
    }

    @Test
    public void testInvokeAllRunsEachTaskOnce() {
        final AtomicIntegerArray runs = new AtomicIntegerArray(NUM_TASKS);
        final List<Future<Integer>> futures = ParallelismTools.invokeAll(countingTasks(runs));
        for (int i = 0; i < NUM_TASKS; i++) {
            Assertions.assertEquals(i, ParallelismTools.get(futures.get(i)));
        }
        assertEachRunOnce(runs);
    }

    @Test
    public void testSingleThreaded() throws Exception {
        final boolean wasParallel = ParallelismTools.getParallel();
        ParallelismTools.setParallel(false);
        try {
            checkJoinFirstInOrder();
            checkSubmitJoinFirstInOrder();

            // All but the first task of invokeFirstSubmitRest() are deferred until joined
            final AtomicIntegerArray deferred = new AtomicIntegerArray(3);
            final Deque<Future<Integer>> futures = ParallelismTools.invokeFirstSubmitRest(
                    () -> deferred.incrementAndGet(0),
                    () -> deferred.incrementAndGet(1),
                    () -> deferred.incrementAndGet(2));
            Assertions.assertEquals(1, deferred.get(0));
            Assertions.assertEquals(0, deferred.get(1));
            Assertions.assertEquals(0, deferred.get(2));
            ParallelismTools.joinFirst(futures);
            ParallelismTools.joinFirst(futures);
            Assertions.assertEquals(1, deferred.get(1));
            Assertions.assertEquals(0, deferred.get(2));
            ParallelismTools.joinFirst(futures);
            assertEachRunOnce(deferred);

            // All tasks are run, in order, then the first exception (in order) is rethrown
            final AtomicIntegerArray runs = new AtomicIntegerArray(3);
            Assertions.assertThrows(IllegalStateException.class, () -> ParallelismTools.invokeAll(
                    () -> {
                        runs.incrementAndGet(0);
                        throw new IllegalStateException("task 0");
                    },
                    () -> {
                        runs.incrementAndGet(1);
                        throw new IllegalArgumentException("task 1");
                    },
                    () -> runs.incrementAndGet(2)));
            assertEachRunOnce(runs);

            // A submitted task is deferred until waited for, then run once by the waiting thread: whether waited for
            // with ParallelismTools.get() or the Future's own get()
            final AtomicIntegerArray submitted = new AtomicIntegerArray(5);
            final Future<Integer> future = ParallelismTools.submit(() -> submitted.incrementAndGet(0));
            Assertions.assertFalse(future.isDone());
            Assertions.assertEquals(0, submitted.get(0));
            Assertions.assertEquals(1, ParallelismTools.get(future));
            Assertions.assertEquals(1, ParallelismTools.get(future));
            final Future<Integer> plain = ParallelismTools.submit(() -> submitted.incrementAndGet(1));
            Assertions.assertFalse(plain.isDone());
            Assertions.assertEquals(0, submitted.get(1));
            Assertions.assertEquals(1, (int) plain.get());
            Assertions.assertEquals(1, (int) plain.get(1, TimeUnit.SECONDS));

            // Its exception is rethrown once it has been run there: as-is by ParallelismTools.get(), and wrapped in an
            // ExecutionException by the Future's own get()
            final Future<Integer> failing = ParallelismTools.submit(() -> {
                throw new IllegalStateException("submitted");
            });
            Assertions.assertFalse(failing.isDone());
            Assertions.assertThrows(IllegalStateException.class, () -> ParallelismTools.get(failing));
            final Future<Integer> failingPlain = ParallelismTools.submit(() -> {
                throw new IllegalStateException("submitted");
            });
            Assertions.assertFalse(failingPlain.isDone());
            Assertions.assertTrue(Assertions.assertThrows(ExecutionException.class, failingPlain::get).getCause()
                    instanceof IllegalStateException);

            // So is a task given to submitCompletable(), and so is work chained on it: whether waited for with
            // ParallelismTools.joinAll() or the CompletableFuture's own join() or get()
            final CompletableFuture<Integer> completable = ParallelismTools.submitCompletable(
                    () -> submitted.incrementAndGet(2));
            final CompletableFuture<Integer> chained = completable.thenApply((x) -> x + 1);
            Assertions.assertFalse(completable.isDone());
            Assertions.assertFalse(chained.isDone());
            Assertions.assertEquals(0, submitted.get(2));
            ParallelismTools.joinAll(new ArrayList<>(Collections.singletonList(completable)));
            Assertions.assertTrue(chained.isDone());
            Assertions.assertEquals(2, (int) chained.join());

            final CompletableFuture<Integer> joined = ParallelismTools.submitCompletable(
                    () -> submitted.incrementAndGet(3));
            final CompletableFuture<Integer> joinedChained = joined.thenApply((x) -> x + 1);
            Assertions.assertFalse(joined.isDone());
            Assertions.assertEquals(0, submitted.get(3));
            Assertions.assertEquals(1, (int) joined.join());
            Assertions.assertEquals(2, (int) joinedChained.join());

            final CompletableFuture<Integer> got = ParallelismTools.submitCompletable(
                    () -> submitted.incrementAndGet(4));
            Assertions.assertFalse(got.isDone());
            Assertions.assertEquals(0, submitted.get(4));
            Assertions.assertEquals(1, (int) got.get());
            assertEachRunOnce(submitted);
        } finally {
            ParallelismTools.setParallel(wasParallel);
        }
    }
}

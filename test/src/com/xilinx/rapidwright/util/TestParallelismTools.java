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
import java.util.Arrays;
import java.util.Deque;
import java.util.Iterator;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.concurrent.Callable;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CountedCompleter;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.ForkJoinWorkerThread;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicIntegerArray;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Assumptions;
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
    public void testOriginalExceptionRethrownByAll() {
        boolean wasParallel = ParallelismTools.getParallel();
        try {
            for (boolean parallel : new boolean[] {false, true}) {
                ParallelismTools.setParallel(parallel);
                List<Integer> items = new ArrayList<>();
                for (int i = 0; i < NUM_TASKS; i++) {
                    items.add(i);
                }
                // Every item throws the same exception, after spinning (so that other threads start on items too),
                // so whichever is rethrown, from whichever thread, must be that one, rather than a copy of it
                Callable<Integer>[] tasks = countingTasks(new AtomicIntegerArray(NUM_TASKS));
                IllegalStateException thrown = new IllegalStateException("every item");
                Consumer<Integer> throwing = (i) -> {
                    try {
                        tasks[i].call();
                    } catch (Exception e) {
                        throw new AssertionError(e);
                    }
                    throw thrown;
                };

                Assertions.assertSame(thrown, Assertions.assertThrows(IllegalStateException.class,
                        () -> ParallelismTools.forEach(items, throwing)));
                Assertions.assertSame(thrown, Assertions.assertThrows(IllegalStateException.class,
                        () -> ParallelismTools.invokeAll(items, (i) -> {
                            throwing.accept(i);
                            return i;
                        })));
                @SuppressWarnings("unchecked")
                Callable<Integer>[] callables = items.stream().map((i) -> (Callable<Integer>) () -> {
                    throwing.accept(i);
                    return i;
                }).toArray(Callable[]::new);
                Assertions.assertSame(thrown, Assertions.assertThrows(IllegalStateException.class,
                        () -> ParallelismTools.invokeAll(callables)));
                CountedCompleter<Void> parent = new CountedCompleter<Void>() {
                    @Override
                    public void compute() {
                    }
                };
                Assertions.assertSame(thrown, Assertions.assertThrows(IllegalStateException.class, () -> {
                    ParallelismTools.submit(parent, items, throwing);
                    parent.tryComplete();
                    ParallelismTools.join(parent);
                }));
                // Each rethrow recorded the stack of the rethrowing thread
                Assertions.assertTrue(thrown.getSuppressed().length >= 4);
            }
        } finally {
            ParallelismTools.setParallel(wasParallel);
        }
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
        // With parallel processing disabled, submitted tasks are deferred until waited for, so the one after the one
        // that throws cannot have been started when joinAll() rethrows. Those given to supplyAsync() are run
        // immediately, so one not yet run is stood in for by a CompletableFuture that is never completed
        ParallelismTools.setParallel(false);
        try {
            final IllegalStateException thrown = new IllegalStateException("task 1");
            final AtomicIntegerArray runs = new AtomicIntegerArray(3);
            final List<CompletableFuture<Integer>> completables = new ArrayList<>();
            completables.add(ParallelismTools.supplyAsync(() -> runs.incrementAndGet(0)));
            completables.add(ParallelismTools.supplyAsync(() -> {
                runs.incrementAndGet(1);
                throw thrown;
            }));
            completables.add(new CompletableFuture<>());
            final Future<Integer> plain = ParallelismTools.submit(() -> runs.incrementAndGet(2));
            // Work chained on the CompletableFutures in order
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
            // Nor is the submitted one run by waiting for (or polling) it afterwards, even with its own get()
            Assertions.assertThrows(CancellationException.class, () -> completables.get(2).join());
            Assertions.assertTrue(plain.isDone());
            Assertions.assertThrows(CancellationException.class, plain::get);
            Assertions.assertEquals(0, runs.get(2));
        } finally {
            ParallelismTools.setParallel(wasParallel);
        }
    }

    @Test
    public void testRelayRunsOnceInputsReady() throws Exception {
        final boolean wasParallel = ParallelismTools.getParallel();
        try {
            // With parallel processing disabled, each stage of a relay (as in EDIF export) runs as soon as it is
            // chained, before the next task is supplied
            ParallelismTools.setParallel(false);
            final List<Integer> log = new ArrayList<>();
            CompletableFuture<Void> relay = CompletableFuture.completedFuture(null);
            for (int i = 0; i < 3; i++) {
                final int index = i;
                final CompletableFuture<Integer> piece = ParallelismTools.supplyAsync(() -> {
                    log.add(2 * index);
                    return index;
                });
                relay = relay.thenCombine(piece, (x, p) -> {
                    log.add(2 * p + 1);
                    return null;
                });
                Assertions.assertTrue(relay.isDone());
            }
            Assertions.assertEquals(Arrays.asList(0, 1, 2, 3, 4, 5), log);

            // Otherwise, a stage runs only once both its inputs are complete, on the thread that completes the later
            // Which needs parallel processing, which cannot be enabled if there is only one processor, or if it was
            // disabled at startup (RW_PARALLEL=0): then, the blocked task below would run on this thread, and hang
            Assumptions.assumeTrue(ParallelismTools.setParallel(true));
            final CompletableFuture<Integer> previous = ParallelismTools.supplyAsync(() -> 1);
            ParallelismTools.get(previous);
            final CountDownLatch release = new CountDownLatch(1);
            final AtomicReference<Thread> ranTask = new AtomicReference<>();
            final AtomicReference<Thread> ranStage = new AtomicReference<>();
            final CompletableFuture<Integer> piece = ParallelismTools.supplyAsync(() -> {
                release.await();
                ranTask.set(Thread.currentThread());
                return 2;
            });
            final CompletableFuture<Integer> stage = previous.thenCombine(piece, (x, p) -> {
                ranStage.set(Thread.currentThread());
                return x + p;
            });
            Assertions.assertFalse(stage.isDone());
            Assertions.assertNull(ranStage.get());
            release.countDown();
            Assertions.assertEquals(3, (int) stage.get());
            Assertions.assertNotNull(ranTask.get());
            Assertions.assertSame(ranTask.get(), ranStage.get());
        } finally {
            ParallelismTools.setParallel(wasParallel);
        }
    }

    @Test
    public void testJoinAllCancelsRestAfterFailureParallel() {
        boolean wasParallel = ParallelismTools.getParallel();
        CountDownLatch release = new CountDownLatch(1);
        try {
            // Parallel processing cannot be enabled if there is only one processor, or if it was disabled at startup
            // (RW_PARALLEL=0): then, the blocked tasks below would run on this thread, and hang
            Assumptions.assumeTrue(ParallelismTools.setParallel(true));
            IllegalStateException thrown = new IllegalStateException("task 0");
            List<CompletableFuture<Integer>> futures = new ArrayList<>();
            futures.add(ParallelismTools.supplyAsync(() -> {
                throw thrown;
            }));
            // Wait for it to have thrown, so that joinAll() does not help by running one of the blocked tasks below
            futures.get(0).handle((x, e) -> null).join();
            // More tasks than the pool has threads, each blocked until released
            for (int i = 0; i < ForkJoinPool.getCommonPoolParallelism() + 2; i++) {
                futures.add(ParallelismTools.supplyAsync(() -> {
                    release.await();
                    return 0;
                }));
            }
            CompletableFuture<Void> chained = CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]));

            Assertions.assertSame(thrown,
                    Assertions.assertThrows(IllegalStateException.class, () -> ParallelismTools.joinAll(futures)));
            // Those after it were cancelled, whether or not their tasks have started, so the chained work has
            // completed without waiting for them
            for (int i = 1; i < futures.size(); i++) {
                Assertions.assertTrue(futures.get(i).isCancelled());
            }
            Assertions.assertTrue(chained.isDone());
        } finally {
            release.countDown();
            ParallelismTools.setParallel(wasParallel);
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    public void testInvokeFirstSubmitRestNoTasks() {
        Assertions.assertTrue(ParallelismTools.invokeFirstSubmitRest(new Callable[0]).isEmpty());
    }

    @Test
    public void testJoinInOrder() {
        final AtomicIntegerArray runs = new AtomicIntegerArray(NUM_TASKS);
        final List<Future<Integer>> futures = new ArrayList<>();
        for (Callable<Integer> task : countingTasks(runs)) {
            futures.add(ParallelismTools.submit(task));
        }
        // Each value in order, its element set to null once waited for
        final Iterator<Integer> it = ParallelismTools.joinInOrder(futures);
        for (int i = 0; i < NUM_TASKS; i++) {
            Assertions.assertTrue(it.hasNext());
            Assertions.assertEquals(i, it.next());
            Assertions.assertNull(futures.get(i));
        }
        Assertions.assertFalse(it.hasNext());
        Assertions.assertThrows(NoSuchElementException.class, it::next);
        assertEachRunOnce(runs);
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
            // with ParallelismTools.get() or the Future's own get(), or polled with its own isDone()
            final AtomicIntegerArray submitted = new AtomicIntegerArray(3);
            final Future<Integer> future = ParallelismTools.submit(() -> submitted.incrementAndGet(0));
            Assertions.assertEquals(0, submitted.get(0));
            Assertions.assertEquals(1, ParallelismTools.get(future));
            Assertions.assertEquals(1, ParallelismTools.get(future));
            final Future<Integer> plain = ParallelismTools.submit(() -> submitted.incrementAndGet(1));
            Assertions.assertEquals(0, submitted.get(1));
            Assertions.assertEquals(1, (int) plain.get());
            Assertions.assertEquals(1, (int) plain.get(1, TimeUnit.SECONDS));
            final Future<Integer> polled = ParallelismTools.submit(() -> submitted.incrementAndGet(2));
            Assertions.assertEquals(0, submitted.get(2));
            Assertions.assertTrue(polled.isDone());
            Assertions.assertEquals(1, submitted.get(2));
            Assertions.assertTrue(polled.isDone());
            Assertions.assertEquals(1, (int) polled.get());
            assertEachRunOnce(submitted);

            // Unless this thread has been interrupted: then, as FutureTask.get() would rather than wait, its own get()
            // throws InterruptedException (clearing the interrupt status) without running it
            AtomicIntegerArray interrupted = new AtomicIntegerArray(1);
            Future<Integer> afterInterrupt = ParallelismTools.submit(() -> interrupted.incrementAndGet(0));
            Thread.currentThread().interrupt();
            Assertions.assertThrows(InterruptedException.class, afterInterrupt::get);
            Assertions.assertFalse(Thread.interrupted());
            Thread.currentThread().interrupt();
            Assertions.assertThrows(InterruptedException.class, () -> afterInterrupt.get(1, TimeUnit.SECONDS));
            Assertions.assertFalse(Thread.interrupted());
            Assertions.assertEquals(0, interrupted.get(0));
            Assertions.assertEquals(1, (int) afterInterrupt.get());
            assertEachRunOnce(interrupted);

            // Its exception is rethrown once it has been run there: as-is by ParallelismTools.get(), and wrapped in an
            // ExecutionException by the Future's own get()
            final Future<Integer> failing = ParallelismTools.submit(() -> {
                throw new IllegalStateException("submitted");
            });
            Assertions.assertThrows(IllegalStateException.class, () -> ParallelismTools.get(failing));
            final Future<Integer> failingPlain = ParallelismTools.submit(() -> {
                throw new IllegalStateException("submitted");
            });
            Assertions.assertTrue(Assertions.assertThrows(ExecutionException.class, failingPlain::get).getCause()
                    instanceof IllegalStateException);

            // A task given to supplyAsync() is run immediately, so work chained on it runs as soon as it is chained,
            // and composing it in any way completes
            final AtomicIntegerArray supplied = new AtomicIntegerArray(2);
            final CompletableFuture<Integer> completable = ParallelismTools.supplyAsync(
                    () -> supplied.incrementAndGet(0));
            Assertions.assertTrue(completable.isDone());
            Assertions.assertEquals(1, supplied.get(0));
            final CompletableFuture<Integer> chained = completable.thenApply((x) -> x + 1);
            Assertions.assertTrue(chained.isDone());
            Assertions.assertEquals(2, (int) chained.get());
            final CompletableFuture<Integer> other = ParallelismTools.supplyAsync(() -> supplied.incrementAndGet(1));
            CompletableFuture.allOf(completable, other).get();
            Assertions.assertEquals(1, (int) CompletableFuture.anyOf(completable, other).get());
            Assertions.assertEquals(2, (int) completable.thenCombine(other, Integer::sum).join());
            assertEachRunOnce(supplied);

            // Its exception completes it exceptionally
            final IllegalStateException thrown = new IllegalStateException("supplied");
            final CompletableFuture<Integer> failingSupplied = ParallelismTools.supplyAsync(() -> {
                throw thrown;
            });
            Assertions.assertTrue(failingSupplied.isCompletedExceptionally());
            Assertions.assertSame(thrown, Assertions.assertThrows(IllegalStateException.class,
                    () -> ParallelismTools.get(failingSupplied)));
        } finally {
            ParallelismTools.setParallel(wasParallel);
        }
    }
}

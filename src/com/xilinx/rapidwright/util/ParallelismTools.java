/*
 * Copyright (c) 2021-2022, Xilinx, Inc.
 * Copyright (c) 2022-2026, Advanced Micro Devices, Inc.
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

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Deque;
import java.util.Iterator;
import java.util.List;
import java.util.ListIterator;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.FutureTask;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RunnableFuture;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.Function;

import org.jetbrains.annotations.NotNull;

/**
 * Utilities to aid in parallel processing
 *
 * A class that abstracts away single-threaded and multi-threaded execution.
 * Single-threaded mode means that all tasks submitted will be executed
 * immediately (on the submitting thread), except for the tasks after the
 * first given to {@link #invokeFirstSubmitRest(Callable[])}, which are
 * deferred until {@link #joinFirst(Deque)} is called on them.
 */
public class ParallelismTools {
    /**
     * Name of the environment variable to disable parallel processing, set RW_PARALLEL=0 (or
     * RW_PARALLEL=false, case insensitive) to disable
     */
    public static final String RW_PARALLEL = "RW_PARALLEL";

    private static final AtomicInteger threadId = new AtomicInteger(0);

    /** A fixed-size thread pool with as many threads as there are processors
     * minus one, fed by a single task queue */
    private static final ThreadPoolExecutor pool;

    private static boolean parallel;

    static {
        final int maxParallelism = maxParallelism();
        final String value = System.getenv(RW_PARALLEL);

        if (value == null) {
            // Enable parallelism by default only if maxParallelism > 1
            parallel = (maxParallelism > 1);
        } else {
            parallel = !(value.equals("0") || value.equalsIgnoreCase("false"));
        }

        if (parallel) {
            pool = new ThreadPoolExecutor(
                    maxParallelism - 1,
                    maxParallelism - 1,
                    0, TimeUnit.MILLISECONDS,
                    new LinkedBlockingQueue<>(),
                    (r) -> {
                        Thread t = Executors.defaultThreadFactory().newThread(r);
                        t.setDaemon(true);
                        t.setName("RapidWright-ParallelismTools-Worker-" + threadId.getAndIncrement());
                        return t;
                    });
            pool.prestartAllCoreThreads();
        } else {
            pool = null;
        }
    }

    /**
     * Global setter to control parallel processing.
     * Has no effect (other than printing a warning) if {@link #maxParallelism()} is 1,
     * or if enabling parallel processing when it was disabled at startup by the
     * {@link #RW_PARALLEL} environment variable (since no thread pool was created).
     * @param parallel Enable parallel processing.
     */
    public static void setParallel(boolean parallel) {
        final int maxParallelism = maxParallelism();
        if (maxParallelism == 1) {
            System.out.println("WARNING: Parallel execution unsupported since maxParallelism() == 1.");
            return;
        }
        if (parallel && pool == null) {
            System.out.println("WARNING: Parallel execution unsupported since it was disabled at startup by "
                    + RW_PARALLEL + "=" + System.getenv(RW_PARALLEL) + ".");
            return;
        }
        ParallelismTools.parallel = parallel;
        if (parallel) {
            pool.prestartAllCoreThreads();
        }
    }

    /**
     * Global getter for current parallel processing state.
     * @return Current parallel processing state.
     */
    public static boolean getParallel() {
        return parallel;
    }

    /**
     * Submit a task-with-return-value to the thread pool.
     * @param task Task to be performed.
     * @param <T> Type returned by task.
     * @return A Future object holding the value returned by task.
     */
    public static <T> Future<T> submit(Callable<T> task) {
        if (!getParallel()) {
            try {
                return CompletableFuture.completedFuture(task.call());
            } catch (Exception e) {
                CompletableFuture<T> f = new CompletableFuture<>();
                f.completeExceptionally(e);
                return f;
            }
        }
        return pool.submit(task);
    }

    /**
     * Submit a task-without-return-value to the thread pool.
     * @param task Task to be performed.
     * @return A Future object used only to determine task completion.
     */
    public static Future<?> submit(Runnable task) {
        if (!getParallel()) {
            try {
                task.run();
                return CompletableFuture.completedFuture(null);
            } catch (Exception e) {
                CompletableFuture<?> f = new CompletableFuture<>();
                f.completeExceptionally(e);
                return f;
            }
        }
        return  pool.submit(task);
    }

    /**
     * Block until the task behind the given Future is complete.
     * If the task has not yet been claimed by another thread, steal it for
     * immediate execution on the current thread.
     * @param future Future representing previously submitted task.
     * @param <T> Type returned by task.
     * @return Value returned by task.
     */
    public static <T> T get(Future<T> future) {
        runIfUnclaimed(future);

        try {
            return future.get();
        } catch (InterruptedException | ExecutionException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * For a given list of value-returning-tasks, first submit all but the first task to
     * the thread pool to be executed in parallel, then execute that first task with the
     * current thread.
     * In single-threaded mode, all but the first task are not executed here but are
     * deferred until they are passed to {@link #joinFirst(Deque)}.
     * @param tasks List of tasks to be executed.
     * @param <T> Type returned by all tasks.
     * @return A Deque of Future objects corresponding to each task (in order).
     */
    @SafeVarargs
    public static <T> Deque<Future<T>> invokeFirstSubmitRest(@NotNull Callable<T>... tasks) {
        Deque<Future<T>> futures = new ArrayDeque<>(tasks.length);

        for (int i = 1; i < tasks.length; i++) {
            if (!getParallel()) {
                futures.addLast(adapt(tasks[i]));
            } else {
                futures.addLast(submit(tasks[i]));
            }
        }

        CompletableFuture<T> f = new CompletableFuture<>();
        try {
            f.complete(tasks[0].call());
        } catch (Exception e) {
            f.completeExceptionally(e);
        }
        futures.addFirst(f);

        return futures;
    }

    /**
     * For a given Deque of Futures, block until the first is complete and
     * remove it from the deque.
     * First, try and steal the task and execute it on this thread. If this
     * is not possible (indicating another thread has already claimed it)
     * then use this thread productively by stealing subsequent unclaimed
     * tasks to work on until the first is complete.
     * @param futures A Deque of Future objects corresponding to previously
     *                submitted tasks.
     * @param <T> Type returned by all tasks.
     * @return The value returned by the first task.
     */
    public static <T> T joinFirst(Deque<Future<T>> futures) {
        Future<T> first = futures.removeFirst();

        if (!getParallel()) {
            if (!first.isDone()) {
                if (first instanceof Runnable) {
                    ((Runnable) first).run();
                } else {
                    throw new RuntimeException();
                }
            }
        } else {
            // Try and invoke it on this thread, unless already done or claimed
            if (!runIfUnclaimed(first)) {
                // Not done: another thread must be running it already,
                // so steal whatever is next until it's done
                Iterator<Future<T>> it = futures.iterator();
                while (!first.isDone() && it.hasNext()) {
                    runIfUnclaimed(it.next());
                }
            }
        }

        try {
            return first.get();
        } catch (InterruptedException | ExecutionException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * For a given List of Futures, block until all are complete.
     * The list is walked in reverse order and tasks not yet claimed are
     * stolen so that they may be completed using the current thread.
     * @param futures A List of Future objects corresponding to previously
     *                submitted tasks.
     * @param <T> Type returned by all tasks.
     */
    public static <T> void join(List<? extends Future<? extends T>> futures) {
        if (getParallel()) {
            // Walk backwards and try and steal those not done
            ListIterator<? extends Future<? extends T>> it = futures.listIterator(futures.size());
            while (it.hasPrevious()) {
                runIfUnclaimed(it.previous());
            }
        }

        // Now block to wait for other threads to finish their tasks
        for (Future<? extends T> f : futures) {
            get(f);
        }
    }

    /**
     * Run a previously submitted task on the current thread, rather than wait
     * for a pool worker to get to it, unless it is already done or another
     * thread has already claimed it.
     * Callers steal from the back of the pool's FIFO queue (by walking their
     * futures newest first), the opposite end from where pool workers take tasks,
     * so as not to contend with them for the same tasks -- just as a ForkJoinPool
     * thief works the opposite end of a deque from its owner.
     * The task is run without removing it from the queue: FutureTask.run() does
     * nothing if another thread has already claimed the task, so the pool worker
     * that eventually dequeues it finds it already done. Dequeuing such a task is
     * an overhead, but far less than ThreadPoolExecutor.remove(), which scans the
     * queue from front to back (blocking pool workers while it does so) and is
     * thus quadratic over a whole invokeAll().
     * @param future Future representing a previously submitted task.
     * @param <T> Type returned by task.
     * @return True if the task is done (whether run by this thread or another),
     *         false if it is not (e.g. another thread is still running it).
     */
    private static <T> boolean runIfUnclaimed(Future<T> future) {
        if (!future.isDone() && (future instanceof Runnable)) {
            ((Runnable) future).run();
        }
        return future.isDone();
    }

    /**
     * Run the specified task on all items
     * @param items the items to call the task with
     * @param task the task that should be executed for all items
     * @param <T> item type
     */
    public static <T> void invokeAllRunnable(Collection<T> items, Consumer<T> task) {
        final Runnable[] runnables = items.stream()
                .map(i -> (Runnable)() -> task.accept(i))
                .toArray(Runnable[]::new);
        invokeAll(runnables);
    }

    /**
     * Given a list of tasks-without-return-value, block until all tasks
     * have been completed.
     * @param tasks List of tasks-without-return-value.
     */
    public static void invokeAll(@NotNull Runnable... tasks) {
        if (tasks.length == 0) {
            return;
        }
        if (!getParallel()) {
            for (Runnable task : tasks) {
                task.run();
            }
            return;
        }

        List<Future<?>> futures = new ArrayList<>(tasks.length);

        // Submit all but the last
        for (int i = 0; i < tasks.length - 1; i++) {
            futures.add(submit(tasks[i]));
        }

        // Invoke the last
        tasks[tasks.length - 1].run();

        // Now walk backwards and try and steal those not done
        ListIterator<Future<?>> it = futures.listIterator(futures.size());
        while (it.hasPrevious()) {
            runIfUnclaimed(it.previous());
        }

        // Now block
        it = futures.listIterator(0);
        while (it.hasNext()) {
            try {
                it.next().get();
            } catch (InterruptedException | ExecutionException e) {
                throw new RuntimeException(e);
            }
        }
    }

    /**
     * Run the specified task on all items, blocking until all have been completed
     * @param items the items to call the task with
     * @param task the task that should be executed for all items
     * @param <T> item type
     * @param <R> type returned by the task
     * @return A list of Future objects holding the value returned for each item (in order)
     */
    public static <T,R> List<Future<R>> invokeAll(Collection<T> items, Function<T,R> task) {
        @SuppressWarnings("unchecked")
        final Callable<R>[] callables = items.stream()
                .map(i -> (Callable<R>)() -> task.apply(i))
                .toArray(value -> (Callable<R>[])new Callable[value]); //Can't create generic arrays, so we need to cast
        return invokeAll(callables);
    }

    /**
     * Given a list of tasks-with-return-value, block until all tasks
     * have been completed.
     * @param tasks List of tasks-with-return-value.
     * @param <T> Type returned by all tasks.
     * @return A list of Future objects used to hold returned data.
     */
    @SafeVarargs
    public static <T> List<Future<T>> invokeAll(Callable<T>... tasks) {
        List<Future<T>> futures = new ArrayList<>(tasks.length);
        if (tasks.length == 0) {
            return futures;
        }

        if (!getParallel()) {
            for (Callable<T> task : tasks) {
                CompletableFuture<T> f = new CompletableFuture<>();
                try {
                    f.complete(task.call());
                } catch (Exception e) {
                    f.completeExceptionally(e);
                }
                futures.add(f);
            }
        } else {
            // Submit all but the last
            for (int i = 0; i < tasks.length - 1; i++) {
                futures.add(submit(tasks[i]));
            }

            // Invoke the last
            CompletableFuture<T> f = new CompletableFuture<>();
            try {
                f.complete(tasks[tasks.length - 1].call());
            } catch (Exception e) {
                f.completeExceptionally(e);
            }
            futures.add(f);

            // Now walk backwards and try and steal those not done
            ListIterator<Future<T>> it = futures.listIterator(futures.size() - 1 /* skip just-inserted */);
            while (it.hasPrevious()) {
                runIfUnclaimed(it.previous());
            }

            // Now block
            it = futures.listIterator(0);
            while (it.hasNext()) {
                try {
                    it.next().get();
                } catch (InterruptedException | ExecutionException e) {
                    throw new RuntimeException(e);
                }
            }
        }

        return futures;
    }

    /**
     * Adapt a task-with-return value into a RunnableFuture object that implements
     * the Future interface to be executed by the current thread (as opposed to
     * submitting it to thread pool queue).
     * The task is not run by this method; the caller must run() the returned object.
     * @param task Task with return value.
     * @param <T> Type returned by task.
     * @return A RunnableFuture object representing the (not yet started) task.
     */
    public static <T> RunnableFuture<T> adapt(Callable<T> task) {
        return new FutureTask<>(task);
    }

    /**
     * The number of parallel threads we want to use
     * @return number of parallel threads
     */
    public static int maxParallelism() {
        return Runtime.getRuntime().availableProcessors();
    }
}

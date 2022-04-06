/*
 * Copyright (c) 2021 Xilinx, Inc.
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

import org.jetbrains.annotations.NotNull;

import java.util.ArrayDeque;
import java.util.ArrayList;
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

/**
 * Utilities to aid in parallel processing
 *
 * A class that abstracts away single-threaded and multi-threaded execution.
 * Single-threaded mode means that all tasks submitted will be executed
 * immediately (on the submitting thread).
 */
public class ParallelismTools {
    /**
     * Name of the environment variable to disable parallel processing, set RW_PARALLEL=0 to disable
     */
    public static final String RW_PARALLEL = "RW_PARALLEL";

    /** A fixed-size thread pool with as many threads as there are processors
     * minus one, fed by a single task queue */
    private static final ThreadPoolExecutor pool = new ThreadPoolExecutor(
            Runtime.getRuntime().availableProcessors() - 1,
            Runtime.getRuntime().availableProcessors() - 1,
            0, TimeUnit.MILLISECONDS,
            new LinkedBlockingQueue<>(),
            (r) -> {
                Thread t = Executors.defaultThreadFactory().newThread(r);
                t.setDaemon(true);
                return t;
            });

    private static boolean parallel = true;

    static {
        String value = System.getenv(RW_PARALLEL);
        setParallel(value == null || !(value.equals("0") || value.equalsIgnoreCase("false")));
    }

    /**
     * Global setter to control parallel processing.
     * @param parallel Enable parallel processing.
     */
    public static void setParallel(boolean parallel) {
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
     * If necessary, steal the task from the job queue for immediate execution
     * on the current thread.
     * @param future Future representing previously submitted task.
     * @return Value returned by task.
     */
    public static <T> T get(Future<T> future) {
        trySteal(future);

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
     * @param tasks List of tasks to be executed.
     * @param <T> Type returned by all tasks.
     * @return A Deque of Future objects corresponding to each task (in order).
     */
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
     * First, try and steal the task from the queue and execute it on this
     * thread. If this is not possible (indicating another thread may
     * already be working on it) then use this thread productively by trying
     * to steal the next task to work on.
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
            // Try and remove it from the queue to invoke it, unless already done
            if (!trySteal(first)) {
                // Not done: another thread must be running it already,
                // so steal whatever is next until it's done
                Iterator<Future<T>> it = futures.iterator();
                while (!first.isDone() && it.hasNext()) {
                    trySteal(it.next());
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
     * The list is walked in reverse order and tasks are stolen from the
     * queue so that they may be completed using the current thread.
     * @param futures A List of Future objects corresponding to previously
     *                submitted tasks.
     * @param <T> Type returned by all tasks.
     */
    public static <T> void join(List<Future<T>> futures) {
        if (getParallel()) {
            // Walk backwards and try and steal those not done
            ListIterator<Future<T>> it = futures.listIterator(futures.size());
            while (it.hasPrevious()) {
                trySteal(it.previous());
            }
        }

        // Now block to wait for other threads to finish their tasks
        for (Future<T> f : futures) {
            get(f);
        }
    }

    private static <T> boolean trySteal(Future<T> future) {
        boolean doneOrStolen = future.isDone();
        if (!doneOrStolen && (future instanceof Runnable)) {
            doneOrStolen = pool.remove((Runnable) future);
            if (doneOrStolen) {
                ((Runnable) future).run();
            }
        }
        return doneOrStolen;
    }

    /**
     * Given a list of tasks-without-return-value, block until all tasks
     * have been completed.
     * @param tasks List of tasks-without-return-value.
     */
    public static void invokeAll(@NotNull Runnable... tasks) {
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
            trySteal(it.previous());
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
     * Given a list of tasks-with-return-value, block until all tasks
     * have been completed.
     * @param tasks List of tasks-with-return-value.
     * @param <T> Type returned by all tasks.
     * @return A list of Future objects used to hold returned data.
     */
    public static <T> List<Future<T>> invokeAll(Callable<T>... tasks) {
        List<Future<T>> futures = new ArrayList<>(tasks.length);

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
                trySteal(it.previous());
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
     * @param task Task with return value.
     * @param <T> Type returned by task.
     * @return A RunnableFuture object representing the task.
     */
    public static <T> RunnableFuture<T> adapt(Callable<T> task) {
        return new FutureTask<>(task);
    }
}

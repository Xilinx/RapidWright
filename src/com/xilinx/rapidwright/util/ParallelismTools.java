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
import java.util.Arrays;
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
    private static boolean parallel = false;

    private static final ThreadPoolExecutor pool = new ThreadPoolExecutor(
            Runtime.getRuntime().availableProcessors() - 1,
            Runtime.getRuntime().availableProcessors() - 1,
            100, TimeUnit.MILLISECONDS,
            new LinkedBlockingQueue<>(),
            (r) -> {
                Thread t = Executors.defaultThreadFactory().newThread(r);
                t.setDaemon(true);
                return t;
            });

    public static void setParallel(boolean parallel) {
        ParallelismTools.parallel = parallel;
        if (parallel) {
            pool.prestartAllCoreThreads();
        }
    }

    public static boolean getParallel() {
        return parallel;
    }

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

    public static <T> T get(Future<T> task) {
        trySteal(task);

        try {
            return task.get();
        } catch (InterruptedException | ExecutionException e) {
            throw new RuntimeException(e);
        }
    }

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

    public static <T> void join(List<Future<T>> futures) {
        if (getParallel()) {
            // Walk backwards and try and steal those not done
            ListIterator<Future<T>> it = futures.listIterator(futures.size());
            while (it.hasPrevious()) {
                trySteal(it.previous());
            }
        }

        // Now block
        for (Future<T> f : futures) {
            get(f);
        }
    }

    public static <T> List<Future<T>> invokeAll(List<Callable<T>> tasks) {
        List<Future<T>> futures = new ArrayList<>(tasks.size());

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
            for (int i = 0; i < tasks.size() - 1; i++) {
                futures.add(submit(tasks.get(i)));
            }

            // Invoke the last
            CompletableFuture<T> f = new CompletableFuture<>();
            try {
                f.complete(tasks.get(tasks.size() - 1).call());
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

    public static <T> List<Future<T>> invokeAll(@NotNull Callable<T>... tasks) {
        return invokeAll(Arrays.asList(tasks));
    }

    public static <T> RunnableFuture<T> adapt(Callable<T> task) {
        return new FutureTask<>(task);
    }

    public static RunnableFuture<?> adapt(Runnable task) {
        return new FutureTask<>(task, null);
    }
}

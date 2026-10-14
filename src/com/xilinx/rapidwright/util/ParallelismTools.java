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
import java.util.Arrays;
import java.util.Collection;
import java.util.Deque;
import java.util.Iterator;
import java.util.List;
import java.util.ListIterator;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountedCompleter;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.ForkJoinTask;
import java.util.concurrent.ForkJoinWorkerThread;
import java.util.concurrent.Future;
import java.util.concurrent.FutureTask;
import java.util.concurrent.RunnableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.jetbrains.annotations.NotNull;

/**
 * Utilities to aid in parallel processing
 *
 * A class that abstracts away single-threaded and multi-threaded execution.
 * Single-threaded mode means that all tasks submitted will be executed
 * immediately (on the submitting thread), except for the tasks after the
 * first given to {@link #invokeFirstSubmitRest(Callable[])}, which are
 * deferred until they are waited for ({@link #joinFirst(Deque)},
 * {@link #get(Future)} or {@link #join(List)}).
 */
public class ParallelismTools {
    /**
     * Name of the environment variable to disable parallel processing, set RW_PARALLEL=0 (or
     * RW_PARALLEL=false, case insensitive) to disable. Otherwise (if unset, or set to any
     * other value) parallel processing is enabled only if there is more than one processor.
     */
    public static final String RW_PARALLEL = "RW_PARALLEL";

    /** Whether parallel processing was enabled at startup (it cannot be enabled later otherwise) */
    private static final boolean parallelAtStartup;

    /** Volatile, as it may be changed (by {@link #setParallel(boolean)}) while other threads run tasks */
    private static volatile boolean parallel;

    static {
        final String value = System.getenv(RW_PARALLEL);

        if (value != null && (value.equals("0") || value.equalsIgnoreCase("false"))) {
            parallel = false;
        } else {
            // Otherwise enable parallelism only if there is more than one processor
            parallel = Runtime.getRuntime().availableProcessors() > 1;
        }

        parallelAtStartup = parallel;
    }

    /**
     * Global setter to control parallel processing.
     * Has no effect (other than printing a warning) if there is only one processor,
     * or if enabling parallel processing when it was disabled at startup by the
     * {@link #RW_PARALLEL} environment variable.
     * @param parallel Enable parallel processing.
     */
    public static void setParallel(boolean parallel) {
        if (Runtime.getRuntime().availableProcessors() == 1) {
            System.out.println("WARNING: Parallel execution unsupported since there is only one processor.");
            return;
        }
        if (parallel && !parallelAtStartup) {
            System.out.println("WARNING: Parallel execution unsupported since it was disabled at startup by "
                    + RW_PARALLEL + "=" + System.getenv(RW_PARALLEL) + ".");
            return;
        }
        ParallelismTools.parallel = parallel;
    }

    /**
     * Global getter for current parallel processing state.
     * @return Current parallel processing state.
     */
    public static boolean getParallel() {
        return parallel;
    }

    /**
     * Submit a task-with-return-value to the common ForkJoinPool, as a {@link FutureTask}
     * (rather than a ForkJoinTask): any thread waiting for it with {@link #get(Future)},
     * {@link #join(List)} or {@link #joinFirst(Deque)} runs it itself if no other thread
     * has claimed it yet, wherever it is in the pool's queues (and so even if the pool has
     * no threads), and any exception it throws is kept as-is.
     * When parallel processing is disabled, the task is executed immediately on the
     * current thread.
     * @param task Task to be performed.
     * @param <T> Type returned by task.
     * @return A Future object holding the value returned by task.
     */
    public static <T> Future<T> submit(Callable<T> task) {
        if (!getParallel()) {
            return callNow(task);
        }
        final RunnableFuture<T> future = adapt(task);
        ForkJoinPool.commonPool().execute(future);
        return future;
    }

    /**
     * Submit a task-without-return-value to the thread pool.
     * @param task Task to be performed.
     * @return A Future object used only to determine task completion.
     */
    public static Future<?> submit(Runnable task) {
        return submit(Executors.callable(task));
    }

    /**
     * Run a task-with-return-value immediately on the current thread.
     * @param task Task to be performed.
     * @param <T> Type returned by task.
     * @return A completed Future object holding the value returned by task, or the
     *         exception (or error) it threw, as a task run by the pool would.
     */
    private static <T> Future<T> callNow(Callable<T> task) {
        final RunnableFuture<T> future = adapt(task);
        future.run();
        return future;
    }

    /**
     * A CompletableFuture completed by a FutureTask that runs its task: so that, once that FutureTask has been
     * submitted to the pool, a thread waiting for the CompletableFuture with {@link #get(Future)}, {@link #join(List)}
     * or {@link #joinFirst(Deque)} can run it itself if no other thread has started it (see
     * {@link #runIfUnclaimed(Future)}), as for a Future from {@link #submit(Callable)}. A CompletableFuture's own task
     * (e.g. from CompletableFuture.supplyAsync()) cannot be run that way: the CompletableFuture has no reference to
     * it, and (unlike a FutureTask) it could run twice if run by a second thread.
     */
    private static final class CompletableFutureWithFutureTask<T> extends CompletableFuture<T> {
        /** Runs the task and completes this, at most once */
        private final FutureTask<Void> task;

        private CompletableFutureWithFutureTask(Callable<T> callable) {
            task = new FutureTask<>(() -> {
                try {
                    complete(callable.call());
                } catch (Throwable e) {
                    completeExceptionally(e);
                }
                return null;
            });
        }
    }

    /**
     * Submit a task-with-return-value to the common ForkJoinPool, as {@link #submit(Callable)} does (so a thread
     * waiting for it with {@link #get(Future)}, {@link #join(List)} or {@link #joinFirst(Deque)} also runs it itself if
     * no other thread has started it), but returning a CompletableFuture, so that further work can be chained on its
     * completion (by default, run by the thread that completes it). Any exception the task throws completes the
     * CompletableFuture exceptionally.
     * When parallel processing is disabled, the task is executed immediately on the current thread.
     * Waiting with the CompletableFuture's own get() or join() does not run the task. (From Java 9, a thread of the
     * pool that does so helps, within the JDK, which cannot be done from outside it, but only by running
     * CompletableFutures' own tasks, e.g. from CompletableFuture.supplyAsync(), at the head of its own queue.)
     * @param task Task to be performed.
     * @param <T> Type returned by task.
     * @return A CompletableFuture holding the value returned by task.
     */
    public static <T> CompletableFuture<T> submitCompletable(Callable<T> task) {
        final CompletableFutureWithFutureTask<T> future = new CompletableFutureWithFutureTask<>(task);
        if (getParallel()) {
            ForkJoinPool.commonPool().execute(future.task);
        } else {
            future.task.run();
        }
        return future;
    }

    /**
     * Submit a task-without-return-value to the common ForkJoinPool, as {@link #submitCompletable(Callable)} does.
     * @param task Task to be performed.
     * @return A CompletableFuture that completes (with null) once task has.
     */
    public static CompletableFuture<Void> submitCompletable(Runnable task) {
        return submitCompletable(() -> {
            task.run();
            return null;
        });
    }

    /**
     * Block until the task behind the given Future is complete.
     * If the task has not yet been claimed by another thread, steal it for
     * immediate execution on the current thread.
     * If the task threw an exception, it is rethrown as described in
     * {@link #getUnwrapped(Future)}.
     * @param future Future representing previously submitted task.
     * @param <T> Type returned by task.
     * @return Value returned by task.
     */
    public static <T> T get(Future<T> future) {
        runIfUnclaimed(future);
        return getUnwrapped(future);
    }

    /**
     * Block until the given Future is complete, and return its value.
     * A thread of a ForkJoinPool blocks with {@link ForkJoinPool#managedBlock}, so that the
     * pool can compensate for it (e.g. with a spare thread) rather than run with one thread
     * fewer.
     * If its task threw an unchecked exception (or error), that is rethrown as-is
     * rather than wrapped in an ExecutionException, with a suppressed exception
     * added to it recording the stack of the rethrowing thread (since the task may
     * have run on another thread); a checked exception, which cannot be rethrown
     * as-is, is wrapped in a RuntimeException. If the wait is interrupted, the
     * InterruptedException is wrapped in a RuntimeException, and the thread's
     * interrupt status is restored.
     * @param future Future representing previously submitted task.
     * @param <T> Type returned by task.
     * @return Value returned by task.
     */
    private static <T> T getUnwrapped(Future<T> future) {
        try {
            if (!future.isDone() && Thread.currentThread() instanceof ForkJoinWorkerThread) {
                ForkJoinPool.managedBlock(new ForkJoinPool.ManagedBlocker() {
                    @Override
                    public boolean block() throws InterruptedException {
                        try {
                            future.get();
                        } catch (ExecutionException e) {
                            // Rethrown by future.get() below
                        }
                        return true;
                    }

                    @Override
                    public boolean isReleasable() {
                        return future.isDone();
                    }
                });
            }
            return future.get();
        } catch (ExecutionException e) {
            final Throwable cause = e.getCause();
            if (cause instanceof RuntimeException || cause instanceof Error) {
                cause.addSuppressed(new Exception("Task exception rethrown by thread \""
                        + Thread.currentThread().getName() + "\""));
                if (cause instanceof RuntimeException) {
                    throw (RuntimeException) cause;
                }
                throw (Error) cause;
            }
            throw new RuntimeException(cause != null ? cause : e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(e);
        }
    }

    /**
     * For a given list of value-returning-tasks, first submit all but the first task to
     * the thread pool to be executed in parallel, then execute that first task with the
     * current thread.
     * In single-threaded mode, all but the first task are not executed here but are
     * deferred until they are passed to {@link #joinFirst(Deque)} (or waited for by
     * {@link #get(Future)} or {@link #join(List)}).
     * @param tasks List of tasks to be executed (if none, an empty Deque is returned).
     * @param <T> Type returned by all tasks.
     * @return A Deque of Future objects corresponding to each task (in order).
     */
    @SafeVarargs
    public static <T> Deque<Future<T>> invokeFirstSubmitRest(@NotNull Callable<T>... tasks) {
        final Deque<Future<T>> futures = new ArrayDeque<>(tasks.length);
        if (tasks.length == 0) {
            return futures;
        }

        final boolean parallel = getParallel();
        for (int i = 1; i < tasks.length; i++) {
            futures.addLast(parallel ? submit(tasks[i]) : adapt(tasks[i]));
        }
        futures.addFirst(callNow(tasks[0]));

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
        final Future<T> first = futures.removeFirst();
        if (!runIfUnclaimed(first)) {
            final Iterator<Future<T>> it = futures.iterator();
            while (!first.isDone() && it.hasNext()) {
                runIfUnclaimed(it.next());
            }
        }
        return getUnwrapped(first);
    }

    /**
     * For a given List of Futures, block until all are complete.
     * The list is walked in reverse order and tasks not yet claimed are
     * stolen so that they may be completed using the current thread.
     * See also {@link #joinAll(List)}, which steals them in order instead.
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
     * For a given List of Futures, block until all are complete, waiting for
     * each in order: meanwhile, tasks not yet claimed are stolen in order (the
     * oldest first, as the pool's workers also take them), so that the current
     * thread helps complete the tasks roughly in the List's order, unlike
     * {@link #join(List)}, which steals them in reverse order. Useful when the
     * tasks' results are consumed in the List's order as they complete (e.g. by
     * work chained on their CompletableFutures), so that fewer results are held
     * until the earlier ones have completed.
     * If a task threw an exception, it is rethrown (as described in
     * {@link #getUnwrapped(Future)}) once the tasks before it have completed.
     * @param futures A List of Future objects corresponding to previously
     *                submitted tasks, whose elements are accessed by index (so
     *                should be a random-access list, such as an ArrayList).
     */
    public static void joinAll(@NotNull List<? extends Future<?>> futures) {
        // Every task before this index has been run by this thread, or claimed by another
        int next = 0;
        for (int i = 0; i < futures.size(); i++) {
            final Future<?> future = futures.get(i);
            while (!future.isDone() && next < futures.size()) {
                runIfUnclaimed(futures.get(next++));
            }
            getUnwrapped(future);
        }
    }

    /**
     * Block until the given CountedCompleter has completed, meanwhile running on this thread any of its descendants
     * (tasks whose chain of completers leads to it) that no thread has started yet, rather than only blocking.
     * If it completed exceptionally, that exception is rethrown: the original, rather than the copy (of the same type,
     * with the original as its cause, and no message of its own) that ForkJoinTask makes of one thrown on another
     * thread; a checked exception is wrapped in a RuntimeException.
     * @param completer The CountedCompleter to wait for.
     */
    public static void join(@NotNull CountedCompleter<?> completer) {
        // Each timed wait first helps with any of its descendants that are queued. It is repeated, as a wait that finds
        // none queued would otherwise block until done, even if more were queued meanwhile. Calling
        // CountedCompleter.helpComplete() in this loop would be preferred, as it helps without waiting, but it only
        // exists from Java 9
        while (!completer.isDone()) {
            try {
                completer.get(1, TimeUnit.MILLISECONDS);
            } catch (TimeoutException | ExecutionException e) {
                // Not done, so look again for descendants to help with; or done exceptionally, rethrown below
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException(e);
            }
        }
        Throwable e = completer.getException();
        if (e == null) {
            return;
        }
        if (e.getCause() != null && e.getCause().getClass() == e.getClass()) {
            e = e.getCause();
        }
        if (e instanceof RuntimeException) {
            throw (RuntimeException) e;
        }
        if (e instanceof Error) {
            throw (Error) e;
        }
        throw new RuntimeException(e);
    }

    /**
     * Run a previously submitted task on the current thread, rather than wait
     * for a pool worker to get to it, unless it is already done or another
     * thread has already claimed it.
     * Callers steal by walking their futures newest first, whereas pool workers
     * take (and steal) the oldest queued tasks first, so as not to contend with
     * them for the same tasks.
     * A FutureTask is run without removing it from the pool's queue (which
     * ForkJoinPool does not support for tasks given to execute()): FutureTask.run()
     * does nothing if another thread has already claimed the task, so the pool
     * worker that eventually dequeues it finds it already done, which costs it
     * very little. Likewise for the FutureTask of a
     * {@link CompletableFutureWithFutureTask}. Any other Future (e.g. a ForkJoinTask,
     * which, unlike a FutureTask, could run again if run while another thread is
     * running it) is left to the thread that runs it.
     * @param future Future representing a previously submitted task.
     * @param <T> Type returned by task.
     * @return True if the task is done (whether run by this thread or another),
     *         false if it is not (e.g. another thread is still running it).
     */
    private static <T> boolean runIfUnclaimed(Future<T> future) {
        if (!future.isDone()) {
            if (future instanceof FutureTask) {
                ((FutureTask<T>) future).run();
            } else if (future instanceof CompletableFutureWithFutureTask) {
                ((CompletableFutureWithFutureTask<T>) future).task.run();
            }
        }
        return future.isDone();
    }

    /**
     * A stream of the given items: a parallel one (run on the common ForkJoinPool, as for
     * {@link #forEach(Collection, Consumer)}) when parallel processing is enabled, else a sequential one.
     * @param items The items to stream.
     * @param <T> Item type.
     * @return {@code items.parallelStream()} if {@link #getParallel()}, else {@code items.stream()}.
     */
    public static <T> Stream<T> stream(@NotNull Collection<T> items) {
        return getParallel() ? items.parallelStream() : items.stream();
    }

    /**
     * Run the specified task on all items, blocking until all have been completed.
     * When parallel processing is enabled, a parallel stream is used, where the calling thread
     * splits the items (using the collection's own Spliterator) into roughly four pieces per
     * thread of the common ForkJoinPool (or one per item, if there are fewer), which are
     * distributed by work stealing, and the calling thread also participates.
     * If the task throws for any item, that exception is rethrown (if thrown on another
     * thread, as a new exception of the same type with the original as its cause, where
     * possible); in that case, the task may not have been run for other items, or may still
     * be running.
     * @param items the items to call the task with
     * @param task the task that should be executed for all items
     * @param <T> item type
     */
    public static <T> void forEach(Collection<T> items, Consumer<T> task) {
        if (!getParallel()) {
            for (T item : items) {
                task.accept(item);
            }
            return;
        }
        items.parallelStream().forEach(task);
    }

    /**
     * Run the specified task on all items without waiting for it, in chunks that are children of the given
     * CountedCompleter: so that it only completes once the task has been run on all items, and a thread waiting for it
     * (or any of its completers) helps run any chunks not yet started. As {@link #forEach(Collection, Consumer)} does,
     * the items are split into roughly four chunks per {@link #maxParallelism()} thread (or one per item, if there are
     * fewer), each of which is forked to the common ForkJoinPool. If parallel processing is disabled, the task is
     * instead run on all items immediately, on the calling thread, with {@link #forEach(Collection, Consumer)}.
     * If the task throws for any item, the parent (and, unless it is overridden not to, each of its completers)
     * completes exceptionally; if parallel processing is disabled, the exception is instead thrown immediately.
     * @param parent The CountedCompleter to run the chunks as children of. Its pending count is increased by the
     *               number of chunks; it must still call tryComplete() itself, once it has submitted all its children.
     * @param items The items, whose elements are accessed by index (so should be a random-access list, such as an
     *              ArrayList), and which must not be modified until the parent has completed.
     * @param task The task to run on each item.
     * @param <T> Item type.
     */
    public static <T> void submit(@NotNull CountedCompleter<?> parent, @NotNull List<T> items,
                                  @NotNull Consumer<T> task) {
        if (!getParallel()) {
            forEach(items, task);
            return;
        }
        final int size = items.size();
        final int chunkSize = Math.max(1, size / (4 * maxParallelism()));
        for (int from = 0; from < size; from += chunkSize) {
            final int start = from;
            final int end = Math.min(size, from + chunkSize);
            submit(parent, () -> {
                for (int i = start; i < end; i++) {
                    task.accept(items.get(i));
                }
            });
        }
    }

    /**
     * Run the specified task without waiting for it, as a child of the given CountedCompleter: so that it only
     * completes once the task has run, and a thread waiting for it (or any of its completers) runs the task itself if
     * no other thread has started it. The task is forked to the common ForkJoinPool. If parallel processing is
     * disabled, the task is instead run immediately, on the calling thread.
     * If the task throws, the parent (and, unless it is overridden not to, each of its completers) completes
     * exceptionally; if parallel processing is disabled, the exception is instead thrown immediately.
     * @param parent The CountedCompleter to run the task as a child of. Its pending count is increased by one; it must
     *               still call tryComplete() itself, once it has submitted all its children.
     * @param task The task to run.
     */
    public static void submit(@NotNull CountedCompleter<?> parent, @NotNull Runnable task) {
        if (!getParallel()) {
            task.run();
            return;
        }
        parent.addToPendingCount(1);
        new CountedCompleter<Void>(parent) {
            @Override
            public void compute() {
                task.run();
                tryComplete();
            }
        }.fork();
    }

    /**
     * @see #forEach(Collection, Consumer)
     * @deprecated Renamed to {@link #forEach(Collection, Consumer)}
     */
    @Deprecated
    public static <T> void invokeAllRunnable(Collection<T> items, Consumer<T> task) {
        forEach(items, task);
    }

    /**
     * Given a list of tasks-without-return-value, block until all tasks
     * have been completed. Equivalent to
     * {@link #forEach(Collection, Consumer)} with each task as an item.
     * @param tasks List of tasks-without-return-value.
     */
    public static void invokeAll(@NotNull Runnable... tasks) {
        forEach(Arrays.asList(tasks), Runnable::run);
    }

    /**
     * Run the specified task on all items, blocking until all have been completed.
     * When parallel processing is enabled, the items are processed using a parallel stream, as
     * described for {@link #forEach(Collection, Consumer)}, including how exceptions are rethrown.
     * @param items the items to call the task with
     * @param task the task that should be executed for all items
     * @param <T> item type
     * @param <R> type returned by the task
     * @return A list of the values returned for each item, in the collection's encounter
     *         order (its iteration order, if the collection is ordered)
     */
    public static <T,R> List<R> invokeAll(Collection<T> items, Function<T,R> task) {
        if (!getParallel()) {
            final List<R> results = new ArrayList<>(items.size());
            for (T item : items) {
                results.add(task.apply(item));
            }
            return results;
        }
        return items.parallelStream().map(task).collect(Collectors.toList());
    }

    /**
     * Given a list of tasks-with-return-value, block until all tasks
     * have been completed, using {@link ForkJoinTask#invokeAll(Collection)}: all
     * but the first are forked (to the current thread's own queue, if it is a
     * thread of a ForkJoinPool, so to that pool, otherwise to the common pool),
     * the first is executed by the current thread, which then waits for the
     * others, executing those it can take back from its queue (how many depends
     * on the JDK), and, on a thread of a ForkJoinPool, also helping the threads
     * that took the others, or letting the pool compensate for it blocking.
     * If any task throws an exception, one of them (not necessarily the first in
     * order) is rethrown: if thrown on another thread, as a new exception of the
     * same type with the original as its cause, where possible, and a checked
     * exception wrapped in a RuntimeException. In that case, the other tasks may
     * have been cancelled or not run. When parallel processing is disabled, all
     * tasks are executed in order on the current thread, and then the first
     * exception (in order) is rethrown as described in {@link #getUnwrapped(Future)}.
     * @param tasks List of tasks-with-return-value.
     * @param <T> Type returned by all tasks.
     * @return A list of Future objects used to hold returned data.
     */
    @SafeVarargs
    public static <T> List<Future<T>> invokeAll(Callable<T>... tasks) {
        if (!getParallel()) {
            final List<Future<T>> futures = new ArrayList<>(tasks.length);
            for (Callable<T> task : tasks) {
                futures.add(callNow(task));
            }
            // Rethrow the first exception, in order
            for (Future<T> future : futures) {
                getUnwrapped(future);
            }
            return futures;
        }

        final List<ForkJoinTask<T>> forkJoinTasks = new ArrayList<>(tasks.length);
        for (Callable<T> task : tasks) {
            forkJoinTasks.add(ForkJoinTask.adapt(task));
        }
        ForkJoinTask.invokeAll(forkJoinTasks);
        // Every ForkJoinTask<T> is a Future<T>, so no need to copy the list
        @SuppressWarnings("unchecked")
        final List<Future<T>> futures = (List<Future<T>>) (List<?>) forkJoinTasks;
        return futures;
    }

    /**
     * Adapt a task-with-return value into a RunnableFuture object that implements
     * the Future interface to be executed by the current thread (as opposed to
     * submitting it to the thread pool).
     * The task is not run by this method; the caller must run() the returned object.
     * @param task Task with return value.
     * @param <T> Type returned by task.
     * @return A RunnableFuture object representing the (not yet started) task.
     */
    public static <T> RunnableFuture<T> adapt(Callable<T> task) {
        return new FutureTask<>(task);
    }

    /**
     * The number of threads that run tasks in parallel: the common ForkJoinPool's threads
     * (by default, as many as there are processors minus one) plus the calling thread,
     * which also runs tasks while waiting for them; or just the calling thread if parallel
     * processing is disabled.
     * @return number of parallel threads
     */
    public static int maxParallelism() {
        return (getParallel() ? ForkJoinPool.getCommonPoolParallelism() : 0) + 1;
    }
}

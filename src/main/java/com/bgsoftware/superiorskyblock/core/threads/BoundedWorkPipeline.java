package com.bgsoftware.superiorskyblock.core.threads;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.Function;
import java.util.function.IntSupplier;

/** Lazy round-robin work with one shared physical window, including async ACKs. */
public final class BoundedWorkPipeline<T> {
    private final Object lock = new Object();
    private final IntSupplier concurrency, jobLimit;
    private final Executor executor;
    private final Deque<Job<T>> waiting = new ArrayDeque<>();
    private final Set<Job<T>> jobs = new HashSet<>();
    private int active;
    private boolean pumping, pumpAgain;
    private Throwable closed;

    public BoundedWorkPipeline(IntSupplier concurrency, IntSupplier jobLimit, Executor executor) {
        this.concurrency = concurrency; this.jobLimit = jobLimit; this.executor = executor;
    }

    public CompletableFuture<Void> submit(Iterable<T> values, Function<T, CompletableFuture<?>> operation) {
        Job<T> job;
        try { job = new Job<>(values.iterator(), operation); }
        catch (Throwable failure) { return failed(failure); }
        synchronized (lock) {
            if (closed != null) return failed(closed);
            if (jobs.size() >= Math.max(1, jobLimit.getAsInt()))
                return failed(new RejectedExecutionException("Bulk chunk job admission limit reached"));
            jobs.add(job);
            waiting.addLast(job);
        }
        pump();
        return job.result;
    }

    private void pump() {
        synchronized (lock) {
            if (pumping) { pumpAgain = true; return; }
            pumping = true;
        }
        for (;;) {
            List<Work<T>> ready = new ArrayList<>();
            List<Job<T>> finished = new ArrayList<>();
            synchronized (lock) {
                pumpAgain = false;
                int limit = Math.max(1, concurrency.getAsInt());
                while (closed == null && active < limit && !waiting.isEmpty()) {
                    Job<T> job = waiting.removeFirst();
                    if (job.result.isCancelled() && job.failure == null)
                        job.failure = new java.util.concurrent.CancellationException("Bulk chunk job cancelled");
                    if (job.failure != null) {
                        if (job.active == 0 && jobs.remove(job)) finished.add(job);
                        continue;
                    }
                    try {
                        if (!job.values.hasNext()) {
                            job.exhausted = true;
                            if (job.active == 0 && jobs.remove(job)) finished.add(job);
                            continue;
                        }
                        T value = job.values.next();
                        ++active; ++job.active;
                        ready.add(new Work<>(job, value));
                        // Another waiting job receives the next available slot.
                        waiting.addLast(job);
                    } catch (Throwable failure) {
                        job.failure = failure;
                        if (job.active == 0 && jobs.remove(job)) finished.add(job);
                    }
                }
            }
            finished.forEach(BoundedWorkPipeline::complete);
            for (Work<T> work : ready) {
                try { executor.execute(() -> start(work)); }
                catch (Throwable failure) { finish(work.job, failure); }
            }
            synchronized (lock) {
                if (!pumpAgain) { pumping = false; return; }
            }
            // Synchronously completed work requests another iteration rather
            // than recursing once per coordinate and exhausting the Java stack.
        }
    }

    private void start(Work<T> work) {
        Throwable rejected;
        synchronized (lock) {
            rejected = closed != null ? closed : work.job.failure != null ? work.job.failure :
                    work.job.result.isCancelled() ? new java.util.concurrent.CancellationException("Bulk chunk job cancelled") : null;
        }
        if (rejected != null) { finish(work.job, rejected); return; }
        try {
            CompletableFuture<?> source = work.job.operation.apply(work.value);
            if (source == null) throw new IllegalStateException("Bulk chunk operation returned null");
            source.whenComplete((unused, failure) -> finish(work.job, failure));
        } catch (Throwable failure) { finish(work.job, failure); }
    }

    private void finish(Job<T> job, Throwable failure) {
        boolean finished;
        synchronized (lock) {
            --active; --job.active;
            if (failure != null && job.failure == null) job.failure = failure;
            if (job.failure != null) waiting.remove(job);
            finished = job.active == 0 && (job.exhausted || job.failure != null);
            if (finished) jobs.remove(job);
        }
        // A job failure must drain its already-started physical callbacks/IO
        // before its caller can proceed with recovery or another world epoch.
        if (finished) complete(job);
        pump();
    }

    public void close(Throwable reason) {
        java.util.Objects.requireNonNull(reason, "reason");
        List<Job<T>> pending;
        synchronized (lock) {
            if (closed != null) return;
            closed = reason;
            pending = new ArrayList<>(jobs);
            waiting.clear();
            for (Job<T> job : pending) {
                if (job.failure == null) job.failure = reason;
                if (job.active == 0) jobs.remove(job);
            }
        }
        // Shutdown can cancel owning schedulers, so callers fail immediately.
        // Admitted work retains its slots until its real source settles; this
        // permanently closed pipeline cannot admit replacements in the meantime.
        pending.forEach(job -> job.result.completeExceptionally(reason));
    }

    public int getActiveCount() { synchronized (lock) { return active; } }
    public int getJobCount() { synchronized (lock) { return jobs.size(); } }
    private static void complete(Job<?> job) {
        if (job.failure == null) job.result.complete(null);
        else job.result.completeExceptionally(job.failure);
    }
    private static CompletableFuture<Void> failed(Throwable reason) {
        CompletableFuture<Void> result = new CompletableFuture<>(); result.completeExceptionally(reason); return result;
    }
    private static final class Job<T> {
        final Iterator<T> values;
        final Function<T, CompletableFuture<?>> operation;
        final CompletableFuture<Void> result = new CompletableFuture<>();
        int active;
        boolean exhausted;
        Throwable failure;
        Job(Iterator<T> values, Function<T, CompletableFuture<?>> operation) { this.values = values; this.operation = operation; }
    }
    private static final class Work<T> {
        final Job<T> job; final T value;
        Work(Job<T> job, T value) { this.job = job; this.value = value; }
    }
}

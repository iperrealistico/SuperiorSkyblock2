package com.bgsoftware.superiorskyblock.pvc.runtime;

import java.util.Objects;
import java.util.function.Consumer;

/**
 * One outstanding callback per recipient, with latest-value coalescing.
 * Publication and delivery have a short, recipient-local ordering boundary;
 * the consumer must only enqueue packets or perform similarly bounded work.
 */
public final class PvcLatestDelivery<T> {
    public interface Scheduler {
        void schedule(Runnable task, Runnable retired);
    }

    private final Object lock = new Object();
    private final Scheduler scheduler;
    private final Consumer<T> consumer;
    private final Consumer<T> completed;
    private final Runnable retired;
    private T current;
    private boolean scheduled;
    private boolean closed;

    public PvcLatestDelivery(Scheduler scheduler, Consumer<T> consumer,
                             Consumer<T> completed, Runnable retired) {
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
        this.consumer = Objects.requireNonNull(consumer, "consumer");
        this.completed = Objects.requireNonNull(completed, "completed");
        this.retired = Objects.requireNonNull(retired, "retired");
    }

    public void publish(T value) {
        Objects.requireNonNull(value, "value");
        boolean enqueue = false;
        synchronized (this.lock) {
            if (this.closed) return;
            this.current = value;
            if (!this.scheduled) {
                this.scheduled = true;
                enqueue = true;
            }
        }
        if (enqueue) schedule();
    }

    public T current() {
        synchronized (this.lock) { return this.current; }
    }

    public boolean isIdle() {
        synchronized (this.lock) { return !this.scheduled; }
    }

    public void retire() {
        boolean notify = false;
        synchronized (this.lock) {
            if (!this.closed) {
                this.closed = true;
                this.scheduled = false;
                this.current = null;
                notify = true;
            }
        }
        if (notify) this.retired.run();
    }

    private void schedule() {
        try {
            this.scheduler.schedule(this::deliver, this::retire);
        } catch (RuntimeException | Error failure) {
            retire();
            throw failure;
        }
    }

    private void deliver() {
        T delivered;
        boolean again;
        try {
            synchronized (this.lock) {
                if (this.closed) return;
                delivered = this.current;
                // A concurrent hide/remove cannot publish and then be overtaken
                // by this delivery. No shared boss state is read in this lock.
                this.consumer.accept(delivered);
                again = this.current != delivered;
                if (!again) this.scheduled = false;
            }
        } catch (RuntimeException | Error failure) {
            retire();
            throw failure;
        }
        try {
            this.completed.accept(delivered);
        } catch (RuntimeException | Error failure) {
            retire();
            throw failure;
        }
        if (again) schedule();
    }
}

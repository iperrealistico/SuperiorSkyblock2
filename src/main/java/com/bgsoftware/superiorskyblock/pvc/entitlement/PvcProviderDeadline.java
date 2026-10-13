package com.bgsoftware.superiorskyblock.pvc.entitlement;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** A caller deadline must never pretend abandoned provider work has released its physical slot. */
final class PvcProviderDeadline {
    private PvcProviderDeadline() { }
    static <T> CompletableFuture<T> apply(CompletableFuture<T> source, ScheduledExecutorService timer,
            long timeout, TimeUnit unit, Runnable providerFinished) {
        CompletableFuture<T> result = new CompletableFuture<>();
        ScheduledFuture<?> deadline;
        try {
            deadline = timer.schedule(() -> result.completeExceptionally(new TimeoutException("Owner permission query timed out")), timeout, unit);
        } catch (RejectedExecutionException error) {
            result.completeExceptionally(error);
            deadline = null;
        }
        final ScheduledFuture<?> scheduled = deadline;
        result.whenComplete((value, error) -> { if (scheduled != null) scheduled.cancel(false); });
        source.whenComplete((value, error) -> {
            try {
                if (error == null) result.complete(value); else result.completeExceptionally(error);
            } finally {
                providerFinished.run();
            }
        });
        return result;
    }
}

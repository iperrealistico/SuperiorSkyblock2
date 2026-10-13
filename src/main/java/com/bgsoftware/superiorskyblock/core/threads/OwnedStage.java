package com.bgsoftware.superiorskyblock.core.threads;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.Supplier;

/** Starts live work on its owner and forwards only the actual stage outcome. */
public final class OwnedStage {
    private OwnedStage() { }
    public static <T> CompletableFuture<T> submit(Executor owner, Supplier<CompletableFuture<T>> operation) {
        CompletableFuture<T> result = new CompletableFuture<>();
        try {
            owner.execute(() -> {
                try {
                    CompletableFuture<T> source = operation.get();
                    if (source == null) throw new IllegalStateException("Owned operation returned null");
                    source.whenComplete((value, failure) -> {
                        if (failure == null) result.complete(value); else result.completeExceptionally(failure);
                    });
                } catch (Throwable failure) { result.completeExceptionally(failure); }
            });
        } catch (Throwable failure) { result.completeExceptionally(failure); }
        return result;
    }
}

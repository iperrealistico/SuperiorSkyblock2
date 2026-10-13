package com.bgsoftware.superiorskyblock.core.threads;

import org.junit.Test;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.junit.Assert.*;

public class OwnedStageTest {
    @Test public void workerEntryWaitsForOwnerAndThenForActualOperationCompletion() {
        Deque<Runnable> owner = new ArrayDeque<>();
        AtomicBoolean started = new AtomicBoolean();
        CompletableFuture<Void> physical = new CompletableFuture<>();
        CompletableFuture<Void> result = OwnedStage.submit(owner::add, () -> {
            started.set(true); return physical;
        });
        assertFalse(started.get()); assertFalse(result.isDone());
        owner.removeFirst().run();
        assertTrue(started.get()); assertFalse(result.isDone());
        physical.complete(null); assertTrue(result.isDone()); result.join();
    }
    @Test public void synchronousNativeOwnerFailurePropagatesWithoutSuccessCallback() {
        IllegalStateException failure = new IllegalStateException("native entity read failed");
        CompletableFuture<Void> result = OwnedStage.submit(Runnable::run, () -> { throw failure; });
        assertFailure(result, failure);
    }
    @Test public void asynchronousActualFailurePropagatesFromItsSource() {
        CompletableFuture<Void> physical = new CompletableFuture<>();
        CompletableFuture<Void> result = OwnedStage.submit(Runnable::run, () -> physical);
        IllegalStateException failure = new IllegalStateException("native clear failed");
        physical.completeExceptionally(failure); assertFailure(result, failure);
    }
    @Test public void retiredOwnerSchedulerFailsWithoutStartingNativeWork() {
        RejectedExecutionException failure = new RejectedExecutionException("scheduler stopped");
        AtomicBoolean started = new AtomicBoolean();
        CompletableFuture<Void> result = OwnedStage.submit(task -> { throw failure; }, () -> {
            started.set(true); return CompletableFuture.completedFuture(null);
        });
        assertFalse(started.get()); assertFailure(result, failure);
    }
    private static void assertFailure(CompletableFuture<Void> result, Throwable failure) {
        try { result.join(); fail("Native failure must propagate"); }
        catch (CompletionException expected) { assertSame(failure, expected.getCause()); }
    }
}

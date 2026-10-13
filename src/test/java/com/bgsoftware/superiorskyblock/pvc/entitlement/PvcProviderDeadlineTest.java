package com.bgsoftware.superiorskyblock.pvc.entitlement;

import org.junit.Test;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.Assert.*;

public class PvcProviderDeadlineTest {
    private ScheduledThreadPoolExecutor timer() {
        ScheduledThreadPoolExecutor timer = new ScheduledThreadPoolExecutor(1);
        timer.setRemoveOnCancelPolicy(true);
        return timer;
    }
    @Test public void callerTimeoutKeepsPhysicalAdmissionAndLateGrantsCannotPublish() throws Exception {
        ScheduledThreadPoolExecutor timer = timer();
        try {
            CompletableFuture<Integer> source = new CompletableFuture<>();
            AtomicInteger physicalSlots = new AtomicInteger(1), publishes = new AtomicInteger();
            CompletableFuture<Integer> result = PvcProviderDeadline.apply(source, timer, 10, TimeUnit.MILLISECONDS,
                    physicalSlots::decrementAndGet);
            result.thenAccept(value -> publishes.incrementAndGet());
            try { result.get(1, TimeUnit.SECONDS); fail("Caller must time out"); }
            catch (ExecutionException expected) { assertTrue(expected.getCause() instanceof TimeoutException); }
            assertEquals(1, physicalSlots.get());
            source.complete(99);
            assertEquals(0, physicalSlots.get()); assertEquals(0, publishes.get());
            assertTrue(result.isCompletedExceptionally());
        } finally { timer.shutdownNow(); }
    }
    @Test public void successfulProviderReleasesOnceAndCancelsUnusedDeadline() throws Exception {
        ScheduledThreadPoolExecutor timer = timer();
        try {
            CompletableFuture<Integer> source = new CompletableFuture<>();
            AtomicInteger releases = new AtomicInteger();
            CompletableFuture<Integer> result = PvcProviderDeadline.apply(source, timer, 1, TimeUnit.HOURS,
                    releases::incrementAndGet);
            source.complete(99); source.complete(1);
            assertEquals(Integer.valueOf(99), result.get(1, TimeUnit.SECONDS));
            assertEquals(1, releases.get()); assertEquals(0, timer.getQueue().size());
        } finally { timer.shutdownNow(); }
    }
    @Test public void providerFailureRetainsItsExactFailureAndReleasesPhysicalSlot() throws Exception {
        ScheduledThreadPoolExecutor timer = timer();
        try {
            CompletableFuture<Integer> source = new CompletableFuture<>();
            IllegalStateException failure = new IllegalStateException("synthetic provider unavailable");
            AtomicInteger releases = new AtomicInteger();
            CompletableFuture<Integer> result = PvcProviderDeadline.apply(source, timer, 1, TimeUnit.HOURS,
                    releases::incrementAndGet);
            source.completeExceptionally(failure);
            try { result.get(1, TimeUnit.SECONDS); fail("Provider failure must propagate"); }
            catch (ExecutionException expected) { assertSame(failure, expected.getCause()); }
            assertEquals(1, releases.get());
        } finally { timer.shutdownNow(); }
    }
    @Test public void shuttingDownTimerDoesNotInventPhysicalCompletion() throws Exception {
        ScheduledThreadPoolExecutor timer = timer(); timer.shutdownNow();
        CompletableFuture<Integer> source = new CompletableFuture<>();
        AtomicInteger releases = new AtomicInteger();
        CompletableFuture<Integer> result = PvcProviderDeadline.apply(source, timer, 1, TimeUnit.SECONDS,
                releases::incrementAndGet);
        try { result.get(1, TimeUnit.SECONDS); fail("Rejected deadline must fail closed"); }
        catch (ExecutionException expected) { assertTrue(expected.getCause() instanceof RejectedExecutionException); }
        assertEquals(0, releases.get());
        source.complete(99); assertEquals(1, releases.get());
    }
    @Test public void concurrentTimedOutSourcesKeepAllSlotsUntilActualCompletion() throws Exception {
        ScheduledThreadPoolExecutor timer = timer();
        try {
            AtomicInteger slots = new AtomicInteger(4);
            List<CompletableFuture<Integer>> sources = new ArrayList<>(), results = new ArrayList<>();
            for (int i = 0; i < 4; ++i) {
                CompletableFuture<Integer> source = new CompletableFuture<>(); sources.add(source);
                results.add(PvcProviderDeadline.apply(source, timer, 10, TimeUnit.MILLISECONDS, slots::decrementAndGet));
            }
            for (CompletableFuture<Integer> result : results) {
                try { result.get(1, TimeUnit.SECONDS); fail("Every caller must time out"); }
                catch (ExecutionException expected) { assertTrue(expected.getCause() instanceof TimeoutException); }
            }
            assertEquals(4, slots.get());
            sources.get(1).complete(1); sources.get(3).completeExceptionally(new IllegalStateException("offline"));
            assertEquals(2, slots.get());
            sources.get(0).complete(99); sources.get(2).complete(99);
            assertEquals(0, slots.get());
        } finally { timer.shutdownNow(); }
    }
}

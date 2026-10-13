package com.bgsoftware.superiorskyblock.core.threads;

import org.junit.Test;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Deque;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.Assert.*;

public class BoundedWorkPipelineTest {
    @Test public void largeFootprintUsesOneGlobalWindowAcrossJobsAndConsumesLazily() {
        BoundedWorkPipeline<Integer> pipeline = pipeline(8, 512);
        AtomicInteger consumed = new AtomicInteger();
        List<CompletableFuture<Void>> physical = new ArrayList<>();
        CompletableFuture<Void> first = pipeline.submit(countingValues(1000000, consumed), value -> held(physical));
        CompletableFuture<Void> second = pipeline.submit(countingValues(1000000, consumed), value -> held(physical));
        assertEquals(8, physical.size()); assertEquals(8, consumed.get());
        assertEquals(8, pipeline.getActiveCount()); assertEquals(2, pipeline.getJobCount());
        pipeline.close(new RejectedExecutionException("test shutdown"));
        assertTrue(first.isCompletedExceptionally()); assertTrue(second.isCompletedExceptionally());
        assertEquals(8, pipeline.getActiveCount());
        new ArrayList<>(physical).forEach(source -> source.complete(null));
        assertEquals(0, pipeline.getActiveCount()); assertEquals(0, pipeline.getJobCount());
        assertEquals(8, consumed.get());
    }

    @Test public void anotherIslandReceivesSlotsWithoutWaitingForTheLargeIslandToFinish() {
        BoundedWorkPipeline<Integer> pipeline = pipeline(2, 10);
        List<Integer> admitted = new ArrayList<>();
        List<CompletableFuture<Void>> physical = new ArrayList<>();
        pipeline.submit(Arrays.asList(1, 2, 3, 4, 5), value -> { admitted.add(value); return held(physical); });
        CompletableFuture<Void> second = pipeline.submit(Arrays.asList(100, 101), value -> { admitted.add(value); return held(physical); });
        physical.get(0).complete(null);
        physical.get(1).complete(null);
        assertEquals(Arrays.asList(1, 2, 3, 100), admitted);
        assertFalse(second.isDone()); assertEquals(2, pipeline.getActiveCount());
        pipeline.close(new RejectedExecutionException("test cleanup"));
        new ArrayList<>(physical).forEach(source -> source.complete(null));
        assertEquals(0, pipeline.getActiveCount());
    }

    @Test public void failureStopsNewCoordinatesButDrainsAlreadyStartedPhysicalAcknowledgements() {
        BoundedWorkPipeline<Integer> pipeline = pipeline(2, 10);
        List<CompletableFuture<Void>> physical = new ArrayList<>();
        CompletableFuture<Void> result = pipeline.submit(Arrays.asList(1, 2, 3, 4), value -> held(physical));
        IllegalStateException failure = new IllegalStateException("real IO failure");
        physical.get(0).completeExceptionally(failure);
        assertFalse(result.isDone()); assertEquals(1, pipeline.getActiveCount());
        assertEquals(2, physical.size());
        physical.get(1).complete(null);
        assertFailure(result, failure); assertEquals(0, pipeline.getActiveCount()); assertEquals(0, pipeline.getJobCount());
    }

    @Test public void cancellationDoesNotRecycleUnsettledPhysicalSlots() {
        BoundedWorkPipeline<Integer> pipeline = pipeline(2, 10);
        List<CompletableFuture<Void>> physical = new ArrayList<>();
        CompletableFuture<Void> result = pipeline.submit(Arrays.asList(1, 2, 3), value -> held(physical));
        result.cancel(false);
        assertEquals(2, pipeline.getActiveCount());
        physical.get(0).complete(null);
        assertEquals(1, pipeline.getActiveCount()); assertEquals(2, physical.size());
        physical.get(1).complete(null);
        assertEquals(0, pipeline.getActiveCount()); assertEquals(0, pipeline.getJobCount());
    }

    @Test public void jobAdmissionIsBoundedAndRejectionDoesNotConsumeItsFootprint() {
        BoundedWorkPipeline<Integer> pipeline = pipeline(1, 2);
        List<CompletableFuture<Void>> physical = new ArrayList<>();
        pipeline.submit(Collections.singletonList(1), value -> held(physical));
        pipeline.submit(Collections.singletonList(2), value -> held(physical));
        AtomicInteger consumed = new AtomicInteger();
        CompletableFuture<Void> rejected = pipeline.submit(countingValues(100000, consumed), value -> held(physical));
        assertTrue(rejected.isCompletedExceptionally()); assertEquals(0, consumed.get());
        assertEquals(2, pipeline.getJobCount()); assertEquals(1, pipeline.getActiveCount());
        pipeline.close(new RejectedExecutionException("test cleanup"));
        physical.get(0).complete(null);
    }

    @Test public void executorRejectionDoesNotLeakSlotsOrStartPhysicalWork() {
        AtomicInteger started = new AtomicInteger();
        RejectedExecutionException failure = new RejectedExecutionException("scheduler stopping");
        BoundedWorkPipeline<Integer> pipeline = new BoundedWorkPipeline<>(() -> 8, () -> 512, task -> { throw failure; });
        CompletableFuture<Void> result = pipeline.submit(Arrays.asList(1, 2, 3), value -> {
            started.incrementAndGet(); return CompletableFuture.completedFuture(null);
        });
        assertFailure(result, failure); assertEquals(0, started.get());
        assertEquals(0, pipeline.getActiveCount()); assertEquals(0, pipeline.getJobCount());
    }

    @Test public void immediateCompletionsUseATrampolineRatherThanOneRecursiveFramePerChunk() {
        BoundedWorkPipeline<Integer> pipeline = pipeline(8, 512);
        AtomicInteger consumed = new AtomicInteger(), completed = new AtomicInteger();
        CompletableFuture<Void> result = pipeline.submit(countingValues(100000, consumed), value -> {
            completed.incrementAndGet(); return CompletableFuture.completedFuture(null);
        });
        result.join(); assertEquals(100000, completed.get()); assertEquals(100000, consumed.get());
        assertEquals(0, pipeline.getActiveCount()); assertEquals(0, pipeline.getJobCount());
    }

    @Test public void threeActualAcknowledgementStagesScaleByWindowRatherThanOneTickPerCoordinate() {
        BoundedWorkPipeline<Integer> pipeline = pipeline(8, 512);
        Deque<CompletableFuture<Void>> physical = new ArrayDeque<>();
        AtomicInteger processed = new AtomicInteger();
        java.util.function.Function<Integer, CompletableFuture<?>> operation = value -> {
            processed.incrementAndGet(); CompletableFuture<Void> source = new CompletableFuture<>(); physical.add(source); return source;
        };
        CompletableFuture<Void> result = pipeline.submit(countingValues(1849, new AtomicInteger()), operation)
                .thenCompose(unused -> pipeline.submit(countingValues(1849, new AtomicInteger()), operation))
                .thenCompose(unused -> pipeline.submit(countingValues(1849, new AtomicInteger()), operation));
        int ownerTicks = 0;
        while (!result.isDone()) {
            assertTrue(pipeline.getActiveCount() <= 8);
            List<CompletableFuture<Void>> tick = new ArrayList<>(physical); physical.clear();
            assertFalse("A physical stage must continue making bounded progress", tick.isEmpty());
            tick.forEach(source -> source.complete(null)); ++ownerTicks;
            assertTrue("The regression must not devolve to 5547 sequential owner hops", ownerTicks <= 696);
        }
        result.join(); assertEquals(1849 * 3, processed.get()); assertEquals(696, ownerTicks);
    }

    private static BoundedWorkPipeline<Integer> pipeline(int concurrency, int jobs) {
        return new BoundedWorkPipeline<>(() -> concurrency, () -> jobs, Runnable::run);
    }
    private static CompletableFuture<Void> held(List<CompletableFuture<Void>> physical) {
        CompletableFuture<Void> source = new CompletableFuture<>(); physical.add(source); return source;
    }
    private static Iterable<Integer> countingValues(int count, AtomicInteger consumed) {
        return () -> new Iterator<Integer>() {
            int next;
            public boolean hasNext() { return next < count; }
            public Integer next() { consumed.incrementAndGet(); return next++; }
        };
    }
    private static void assertFailure(CompletableFuture<Void> result, Throwable failure) {
        try { result.join(); fail("Expected actual operation failure"); }
        catch (CompletionException expected) { assertSame(failure, expected.getCause()); }
    }
}

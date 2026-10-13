package com.bgsoftware.superiorskyblock.pvc.runtime;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.*;

public class PvcLatestDeliveryTest {
    @Test public void rapidChangesUseOneTaskAndDeliverOnlyLatestState() {
        ManualScheduler scheduler = new ManualScheduler();
        List<String> delivered = new ArrayList<>();
        PvcLatestDelivery<String> gate = gate(scheduler, delivered, new AtomicInteger());
        gate.publish("add"); gate.publish("progress"); gate.publish("hidden");
        assertEquals(1, scheduler.tasks.size());
        scheduler.runNext();
        assertEquals(java.util.Collections.singletonList("hidden"), delivered);
        assertTrue(gate.isIdle());
        assertTrue(scheduler.tasks.isEmpty());
    }

    @Test public void reentrantChangeWaitsForAnotherOwnedCallback() {
        ManualScheduler scheduler = new ManualScheduler();
        List<String> delivered = new ArrayList<>();
        AtomicReference<PvcLatestDelivery<String>> reference = new AtomicReference<>();
        PvcLatestDelivery<String> gate = new PvcLatestDelivery<>(scheduler, value -> {
            delivered.add(value);
            if (value.equals("add")) reference.get().publish("remove");
        }, value -> { }, () -> { });
        reference.set(gate);
        gate.publish("add");
        scheduler.runNext();
        assertEquals(java.util.Collections.singletonList("add"), delivered);
        assertEquals(1, scheduler.tasks.size());
        scheduler.runNext();
        assertEquals(java.util.Arrays.asList("add", "remove"), delivered);
        assertTrue(gate.isIdle());
    }

    @Test public void retirementDropsQueuedPacketsAndRunsCleanupOnce() {
        ManualScheduler scheduler = new ManualScheduler();
        AtomicInteger retired = new AtomicInteger();
        List<String> delivered = new ArrayList<>();
        PvcLatestDelivery<String> gate = gate(scheduler, delivered, retired);
        gate.publish("add");
        scheduler.retired.get().run();
        gate.retire(); gate.publish("remove");
        scheduler.runNext();
        assertTrue(delivered.isEmpty());
        assertEquals(1, retired.get());
        assertNull(gate.current());
        assertTrue(gate.isIdle());
    }

    @Test public void schedulingFailureCleansUpAndCannotAccumulateUpdates() {
        AtomicInteger retired = new AtomicInteger();
        PvcLatestDelivery<String> gate = new PvcLatestDelivery<>((task, callback) -> {
            throw new RejectedExecutionException("stopping");
        }, value -> fail("Cannot deliver after rejection"), value -> { }, retired::incrementAndGet);
        try { gate.publish("add"); fail("Expected rejection"); }
        catch (RejectedExecutionException expected) { }
        gate.publish("progress"); gate.retire();
        assertEquals(1, retired.get());
        assertTrue(gate.isIdle());
    }

    @Test public void concurrentPublicationsRemainBoundedAndFinalStateWins() throws Exception {
        ManualScheduler scheduler = new ManualScheduler();
        List<String> delivered = new ArrayList<>();
        PvcLatestDelivery<String> gate = gate(scheduler, delivered, new AtomicInteger());
        ExecutorService workers = Executors.newFixedThreadPool(4);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(4);
        try {
            for (int worker = 0; worker < 4; worker++) {
                final int id = worker;
                workers.submit(() -> {
                    try {
                        start.await();
                        for (int update = 0; update < 1000; update++) gate.publish(id + ":" + update);
                    } catch (InterruptedException error) {
                        Thread.currentThread().interrupt();
                    } finally { done.countDown(); }
                });
            }
            start.countDown();
            assertTrue(done.await(5, TimeUnit.SECONDS));
            gate.publish("removed");
            assertEquals(1, scheduler.tasks.size());
            scheduler.runNext();
            assertEquals(java.util.Collections.singletonList("removed"), delivered);
        } finally { workers.shutdownNow(); }
    }

    @Test public void publishAfterDeliveryIsNotLostDuringCompletion() {
        ManualScheduler scheduler = new ManualScheduler();
        List<String> delivered = new ArrayList<>();
        AtomicReference<PvcLatestDelivery<String>> reference = new AtomicReference<>();
        PvcLatestDelivery<String> gate = new PvcLatestDelivery<>(scheduler, delivered::add,
                value -> { if (value.equals("removed")) reference.get().publish("readded"); }, () -> { });
        reference.set(gate);
        gate.publish("removed"); scheduler.runNext();
        assertEquals(1, scheduler.tasks.size());
        assertEquals("readded", gate.current());
        scheduler.runNext();
        assertEquals(java.util.Arrays.asList("removed", "readded"), delivered);
        assertTrue(gate.isIdle());
    }

    @Test public void deliveryFailureRetiresRecipientAndDropsNewerStates() {
        ManualScheduler scheduler = new ManualScheduler();
        AtomicInteger retired = new AtomicInteger();
        PvcLatestDelivery<String> gate = new PvcLatestDelivery<>(scheduler, value -> {
            throw new IllegalStateException("connection closed");
        }, value -> fail("Failed delivery cannot complete"), retired::incrementAndGet);
        gate.publish("add");
        try { scheduler.runNext(); fail("Expected failure"); }
        catch (IllegalStateException expected) { }
        gate.publish("progress");
        assertEquals(1, retired.get());
        assertTrue(gate.isIdle());
        assertTrue(scheduler.tasks.isEmpty());
    }

    private static PvcLatestDelivery<String> gate(ManualScheduler scheduler, List<String> delivered,
                                                 AtomicInteger retired) {
        return new PvcLatestDelivery<>(scheduler, delivered::add, value -> { }, retired::incrementAndGet);
    }

    private static final class ManualScheduler implements PvcLatestDelivery.Scheduler {
        final ConcurrentLinkedQueue<Runnable> tasks = new ConcurrentLinkedQueue<>();
        final AtomicReference<Runnable> retired = new AtomicReference<>();
        @Override public void schedule(Runnable task, Runnable onRetired) {
            this.retired.set(onRetired);
            this.tasks.add(task);
        }
        void runNext() {
            Runnable task = this.tasks.poll();
            assertNotNull("Expected an owned callback", task);
            task.run();
        }
    }
}

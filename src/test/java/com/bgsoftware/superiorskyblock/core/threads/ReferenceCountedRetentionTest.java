package com.bgsoftware.superiorskyblock.core.threads;

import org.junit.Test;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.junit.Assert.*;

public class ReferenceCountedRetentionTest {
    @Test public void arenaReleaseCannotRemoveTheStorageRetryTicket() {
        AtomicInteger adds = new AtomicInteger(), removes = new AtomicInteger();
        ReferenceCountedRetention<String> leases = new ReferenceCountedRetention<>(key -> adds.incrementAndGet(), key -> removes.incrementAndGet());
        leases.acquire("chunk"); // Active arena.
        leases.acquire("chunk"); // In-flight storage/readback retry.
        leases.release("chunk"); // Arena becomes dormant.
        assertEquals(1, adds.get()); assertEquals(0, removes.get()); assertEquals(1, leases.references("chunk"));
        leases.release("chunk"); // Real storage ACK.
        assertEquals(1, removes.get()); assertEquals(0, leases.references("chunk"));
    }
    @Test public void storageAcknowledgementCannotRemoveAnActiveArenaTicket() {
        AtomicInteger removes = new AtomicInteger();
        ReferenceCountedRetention<String> leases = new ReferenceCountedRetention<>(key -> { }, key -> removes.incrementAndGet());
        leases.acquire("chunk"); leases.acquire("chunk");
        leases.release("chunk");
        assertEquals(0, removes.get()); assertEquals(1, leases.references("chunk"));
        leases.release("chunk"); assertEquals(1, removes.get());
    }
    @Test public void failedNativeAcquisitionDoesNotPublishAPhantomReference() {
        IllegalStateException failure = new IllegalStateException("native ticket admission failed");
        AtomicInteger removes = new AtomicInteger();
        ReferenceCountedRetention<String> leases = new ReferenceCountedRetention<>(key -> { throw failure; }, key -> removes.incrementAndGet());
        try { leases.acquire("chunk"); fail("Acquisition must fail"); }
        catch (IllegalStateException expected) { assertSame(failure, expected); }
        assertEquals(0, leases.references("chunk")); leases.release("chunk"); assertEquals(0, removes.get());
    }
    @Test public void failedLastRemovalRetainsItsPhysicalReferenceUntilGenuineRetry() {
        AtomicBoolean fail = new AtomicBoolean(true);
        AtomicInteger removals = new AtomicInteger();
        IllegalStateException failure = new IllegalStateException("native ticket removal failed");
        ReferenceCountedRetention<String> leases = new ReferenceCountedRetention<>(key -> { }, key -> {
            removals.incrementAndGet(); if (fail.get()) throw failure;
        });
        leases.acquire("chunk");
        try { leases.release("chunk"); fail("Removal must fail"); }
        catch (IllegalStateException expected) { assertSame(failure, expected); }
        assertEquals(1, leases.references("chunk"));
        fail.set(false); leases.release("chunk");
        assertEquals(0, leases.references("chunk")); assertEquals(2, removals.get());
    }
}

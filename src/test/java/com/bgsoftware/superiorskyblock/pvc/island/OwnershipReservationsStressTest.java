package com.bgsoftware.superiorskyblock.pvc.island;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.*;

/** Stress the real atomic admission gate, not a test imitation of the island service. */
public class OwnershipReservationsStressTest {
    @Test public void fiftyWorkersAdmitAndRetireTenThousandOwnersWithoutQuotaOrSlotLeaks() throws Exception {
        final int threads = 50, ownersPerThread = 200, capacity = 128;
        OwnershipReservations gate = new OwnershipReservations(capacity);
        ExecutorService workers = Executors.newFixedThreadPool(threads);
        CyclicBarrier start = new CyclicBarrier(threads);
        List<Future<?>> tasks = new ArrayList<>();
        try {
            for (int worker = 0; worker < threads; worker++) {
                final int offset = worker * ownersPerThread;
                tasks.add(workers.submit(() -> {
                    start.await(10, TimeUnit.SECONDS);
                    for (int index = offset + 1; index <= offset + ownersPerThread; index++) {
                        UUID owner = new UUID(0x505643L, index);
                        UUID first = new UUID(index, 1), second = new UUID(index, 2), rejected = new UUID(index, 3);
                        // Fifty concurrent workers hold at most one pair each, below
                        // the independent global bound; each third slot must still fail.
                        assertTrue(gate.reserve(first, owner, 0, 2));
                        assertTrue(gate.reserve(second, owner, 0, 2));
                        assertFalse(gate.reserve(rejected, owner, 0, 2));
                        assertEquals(2, gate.pending(owner));
                        assertTrue(gate.canCommit(first, 0, 2));
                        // A grant revoked during asynchronous work blocks both pending
                        // commits until one intent is cancelled; it deletes no island.
                        assertFalse(gate.canCommit(first, 0, 1));
                        gate.release(second);
                        assertTrue(gate.canCommit(first, 0, 1));
                        assertTrue(gate.convert(first));
                        assertEquals(0, gate.pending(owner));
                        // Converted intent still occupies global admission capacity until
                        // terminal acknowledgement, without double-counting its island.
                        assertTrue(gate.convert(first));
                        assertFalse(gate.reserve(rejected, owner, 1, 1));
                        assertTrue(gate.size() <= capacity);
                        gate.release(first); gate.release(first); gate.release(second);
                        assertEquals(0, gate.pending(owner));
                    }
                    return null;
                }));
            }
            for (Future<?> task : tasks) task.get(30, TimeUnit.SECONDS);
            assertEquals(0, gate.size());
            // Fill the global bound with independent, unlimited owners: neither a
            // large permission nor an idempotent replay bypasses queue admission.
            List<UUID> held = new ArrayList<>();
            for (int index = 0; index < capacity; index++) {
                UUID operation = new UUID(0x5145555545L, index), owner = new UUID(0x4f574e4552L, index);
                assertTrue(gate.reserve(operation, owner, 0, Integer.MAX_VALUE)); held.add(operation);
            }
            assertFalse(gate.reserve(UUID.randomUUID(), UUID.randomUUID(), 0, Integer.MAX_VALUE));
            for (UUID operation : held) gate.release(operation);
            assertEquals(0, gate.size());
        } finally {
            workers.shutdownNow();
            assertTrue("Admission stress workers did not terminate", workers.awaitTermination(10, TimeUnit.SECONDS));
        }
    }
}

package com.bgsoftware.superiorskyblock.pvc.entitlement;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.*;

/** Bounded publication/revocation pressure across owners, independent of Minecraft threads. */
public class PvcOwnerCacheStressTest {
    @Test public void fiftyPublishersKeepTenThousandOwnersIsolatedWithinTheCacheBound() throws Exception {
        final int threads = 50, ownersPerThread = 200, capacity = 128;
        PvcOwnerCache cache = new PvcOwnerCache();
        ExecutorService workers = Executors.newFixedThreadPool(threads);
        CyclicBarrier start = new CyclicBarrier(threads);
        List<Future<?>> tasks = new ArrayList<>();
        try {
            for (int worker = 0; worker < threads; worker++) {
                final int offset = worker * ownersPerThread;
                tasks.add(workers.submit(() -> {
                    start.await(10, TimeUnit.SECONDS);
                    for (int index = offset + 1; index <= offset + ownersPerThread; index++) {
                        UUID owner = owner(index);
                        EntitlementSnapshot granted = value(index, true);
                        cache.put(owner, granted, index, capacity);
                        PvcOwnerCache.Entry first = cache.get(owner, index + 10000L);
                        if (first != null) assertOwner(index, first.snapshot);
                        // A revoked/last-known policy cannot mutate the immutable object
                        // already held by a hot reader, or borrow another owner's grant.
                        cache.put(owner, granted.lastKnown(), index + 20000L, capacity);
                        assertTrue(granted.isAuthoritative());
                        PvcOwnerCache.Entry revoked = cache.get(owner, index + 30000L);
                        if (revoked != null) {
                            EntitlementSnapshot observed = revoked.snapshot;
                            assertOwner(index, observed);
                            assertFalse(observed.isFresh(index + 1L, 100000L));
                        }
                        assertTrue("Published cache exceeded its configured bound", cache.size() <= capacity);
                    }
                    return null;
                }));
            }
            for (Future<?> task : tasks) task.get(30, TimeUnit.SECONDS);
            assertEquals(capacity, cache.size());
            for (Map.Entry<UUID, PvcOwnerCache.Entry> entry : cache.entries()) {
                int index = (int) entry.getKey().getLeastSignificantBits();
                assertEquals(owner(index), entry.getKey());
                assertOwner(index, entry.getValue().snapshot);
                assertFalse(entry.getValue().snapshot.isAuthoritative());
            }
            cache.trim(16);
            assertEquals(16, cache.size());
        } finally {
            workers.shutdownNow();
            assertTrue("Stress workers did not terminate", workers.awaitTermination(10, TimeUnit.SECONDS));
        }
    }
    private static UUID owner(int index) { return new UUID(0x505643L, index); }
    private static EntitlementSnapshot value(int index, boolean authoritative) {
        return new EntitlementSnapshot(index % 99 + 1, index / 99 + 1, index, index, authoritative, index, 7);
    }
    private static void assertOwner(int index, EntitlementSnapshot snapshot) {
        assertEquals(index % 99 + 1, snapshot.getOwnershipLimit());
        assertEquals(index / 99 + 1, snapshot.getRadius());
        assertEquals(index, snapshot.getRevision());
        assertEquals(index, snapshot.getOwnerEpoch());
        assertEquals(7, snapshot.getGlobalEpoch());
    }
}

package com.bgsoftware.superiorskyblock.core.database.bridge;

import org.junit.Test;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import static org.junit.Assert.*;

public class IslandDirtySavesConcurrencyTest {
    @Test public void producersAndDrainNeverLoseAChestOrWriteIntoDetachedSnapshot() throws Exception {
        UUID island = UUID.randomUUID(); ExecutorService workers = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1); Set<Object> collected = new HashSet<>();
        try {
            Future<?> producer = workers.submit(() -> { start.await(); for (int i = 0; i < 64; i++)
                IslandsDatabaseBridge.markFutureSave(island, IslandsDatabaseBridge.FutureSave.ISLAND_CHESTS, i); return null; });
            Future<?> drain = workers.submit(() -> { start.await(); for (int i = 0; i < 64; i++) {
                Map<IslandsDatabaseBridge.FutureSave, Set<Object>> snapshot = IslandsDatabaseBridge.drainFutureSaves(island);
                if (snapshot != null) collected.addAll(snapshot.get(IslandsDatabaseBridge.FutureSave.ISLAND_CHESTS));
            } return null; });
            start.countDown(); producer.get(5, java.util.concurrent.TimeUnit.SECONDS); drain.get(5, java.util.concurrent.TimeUnit.SECONDS);
            Map<IslandsDatabaseBridge.FutureSave, Set<Object>> last = IslandsDatabaseBridge.drainFutureSaves(island);
            if (last != null) collected.addAll(last.get(IslandsDatabaseBridge.FutureSave.ISLAND_CHESTS));
            assertEquals(64, collected.size());
        } finally { workers.shutdownNow(); IslandsDatabaseBridge.drainFutureSaves(island); }
    }
    @Test public void drainingOneTypePreservesOtherAndLaterMarks() {
        UUID island = UUID.randomUUID();
        IslandsDatabaseBridge.markFutureSave(island, IslandsDatabaseBridge.FutureSave.BLOCK_COUNTS, Boolean.TRUE);
        IslandsDatabaseBridge.markFutureSave(island, IslandsDatabaseBridge.FutureSave.PERSISTENT_DATA, Boolean.TRUE);
        Set<Object> first = IslandsDatabaseBridge.drainFutureSave(island, IslandsDatabaseBridge.FutureSave.BLOCK_COUNTS);
        IslandsDatabaseBridge.markFutureSave(island, IslandsDatabaseBridge.FutureSave.BLOCK_COUNTS, Boolean.TRUE);
        Map<IslandsDatabaseBridge.FutureSave, Set<Object>> second = IslandsDatabaseBridge.drainFutureSaves(island);
        assertEquals(1, first.size()); assertEquals(2, second.size()); assertEquals(1, first.size());
    }
}

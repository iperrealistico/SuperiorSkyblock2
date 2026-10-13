package com.bgsoftware.superiorskyblock.missions;

import com.bgsoftware.superiorskyblock.missions.blocks.TrackedBlocksData;
import com.bgsoftware.superiorskyblock.missions.blocks.ChunkBitSet;
import com.bgsoftware.superiorskyblock.missions.farming.TrackedPlantsData;
import org.junit.Test;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import static org.junit.Assert.*;

public class TrackerSnapshotConcurrencyTest {
    @Test public void blockSnapshotsAreDetachedWhileDifferentChunksChange() throws Exception {
        TrackedBlocksData blocks = new TrackedBlocksData(); blocks.track(1L, 7);
        Map<Long, ChunkBitSet> first = blocks.getBlocks();
        ExecutorService workers = Executors.newFixedThreadPool(2);
        try {
            Future<?> firstChunk = workers.submit(() -> { for (int i = 0; i < 32; i++) blocks.track(1L, i); });
            Future<?> otherChunk = workers.submit(() -> { for (int i = 0; i < 32; i++) { blocks.track(2L, i); blocks.getBlocks(); } });
            firstChunk.get(5, java.util.concurrent.TimeUnit.SECONDS); otherChunk.get(5, java.util.concurrent.TimeUnit.SECONDS);
            assertTrue(first.get(1L).get(7)); assertFalse(first.get(1L).get(9)); assertFalse(first.containsKey(2L));
            assertTrue(blocks.contains(1L, () -> 31)); assertTrue(blocks.contains(2L, () -> 31));
        } finally { workers.shutdownNow(); }
    }
    @Test public void farmingSnapshotsKeepPlacerAndDoNotChangeAfterUntrack() throws Exception {
        TrackedPlantsData plants = new TrackedPlantsData(); UUID first = UUID.randomUUID(), second = UUID.randomUUID();
        plants.track(1L, 7, first); Map<Long, Map<Integer, UUID>> before = plants.getPlants();
        ExecutorService workers = Executors.newFixedThreadPool(2);
        try {
            Future<?> a = workers.submit(() -> { for (int i = 0; i < 32; i++) plants.track(1L, i, first); });
            Future<?> b = workers.submit(() -> { for (int i = 0; i < 32; i++) { plants.track(2L, i, second); plants.getPlants(); } });
            a.get(5, java.util.concurrent.TimeUnit.SECONDS); b.get(5, java.util.concurrent.TimeUnit.SECONDS);
            plants.untrack(1L, 7);
            assertEquals(first, before.get(1L).get(7)); assertFalse(before.containsKey(2L));
            assertNull(plants.getPlacer(1L, 7)); assertEquals(second, plants.getPlacer(2L, 31));
        } finally { workers.shutdownNow(); }
    }
}

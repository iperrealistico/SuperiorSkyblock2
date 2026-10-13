package com.bgsoftware.superiorskyblock.pvc.island;

import com.bgsoftware.superiorskyblock.core.Counter;
import com.bgsoftware.superiorskyblock.missions.common.tracker.RawDataTracker;
import com.bgsoftware.superiorskyblock.missions.blocks.ChunkBitSet;
import com.bgsoftware.superiorskyblock.missions.blocks.TrackedBlocksData;
import com.bgsoftware.superiorskyblock.missions.farming.TrackedPlantsData;
import org.junit.Test;
import java.util.Map;
import java.util.UUID;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.IntConsumer;
import static org.junit.Assert.*;

/** Exercise the retained built-in state objects, with several region-like writers per subject. */
public class MissionTrackerConcurrencyTest {
    @Test public void commonCountersAreExactAndSaveSnapshotsAreDetached() throws Exception {
        RawDataTracker tracker = new RawDataTracker();
        race(12, worker -> { for (int i = 0; i < 2000; i++) {
            tracker.track("same", 1);
            tracker.track("worker-" + worker, 1);
            if ((i & 31) == 0) tracker.getCounts().values().forEach(Counter::get);
        }});
        assertEquals(24000, tracker.getCount("same"));
        assertEquals(48000, tracker.getGlobalCounter());
        Map<String, Counter> snapshot = tracker.getCounts();
        tracker.track("same", 7);
        assertEquals(24000, snapshot.get("same").get());
        snapshot.get("same").set(0);
        assertEquals(24007, tracker.getCount("same"));
        tracker.clear(); assertEquals(0, tracker.getGlobalCounter()); assertTrue(tracker.getCounts().isEmpty());
        tracker.load("same", 10); tracker.load("same", 4); assertEquals(4, tracker.getGlobalCounter());
    }

    @Test public void concurrentChunkBitsSurviveAndIterationUsesAStableSnapshot() throws Exception {
        ChunkBitSet bits = new ChunkBitSet();
        race(16, worker -> { for (int i = 0; i < 256; i++) bits.set(worker * 256 + i); });
        for (int i = 0; i < 4096; i++) assertTrue(bits.get(i));
        ChunkBitSet saved = bits.copy();
        List<Integer> visited = new ArrayList<>();
        bits.forEach(index -> { visited.add(index); bits.clear(index); });
        assertEquals(4096, visited.size());
        for (int i = 0; i < 4096; i++) { assertFalse(bits.get(i)); assertTrue(saved.get(i)); }
    }

    @Test public void blockMapAndNestedBitsHaveDetachedSaveViews() throws Exception {
        TrackedBlocksData data = new TrackedBlocksData();
        race(12, worker -> { for (int i = 0; i < 200; i++) {
            data.track(5L, worker * 200 + i);
            if ((i & 15) == 0) data.getBlocks().forEach((key, bits) -> bits.forEach(index -> { }));
        }});
        Map<Long, ChunkBitSet> saved = data.getBlocks();
        assertTrue(data.untrack(5L, () -> 10));
        assertFalse(data.contains(5L, () -> 10)); assertTrue(saved.get(5L).get(10));
        saved.get(5L).clear(20); assertTrue(data.contains(5L, () -> 20));
        List<Integer> count = new ArrayList<>(); data.getBlocks().get(5L).forEach(count::add);
        assertEquals(2399, count.size());
    }

    @Test public void plantMetadataAcceptsConcurrentRegionsAndSnapshotDoesNotChange() throws Exception {
        TrackedPlantsData data = new TrackedPlantsData();
        UUID[] owners = new UUID[12]; for (int i = 0; i < owners.length; i++) owners[i] = UUID.randomUUID();
        race(12, worker -> { for (int i = 0; i < 200; i++) {
            data.track(11L, worker * 200 + i, owners[worker]);
            if ((i & 15) == 0) data.getPlants().values().forEach(Map::size);
        }});
        Map<Long, Map<Integer, UUID>> saved = data.getPlants(); assertEquals(2400, saved.get(11L).size());
        data.untrack(11L, 3); assertNull(data.getPlacer(11L, 3)); assertEquals(owners[0], saved.get(11L).get(3));
        for (int worker = 0; worker < 12; worker++) assertEquals(owners[worker], data.getPlacer(11L, worker * 200 + 100));
        try { saved.get(11L).clear(); fail("Nested plant snapshots must be immutable"); }
        catch (UnsupportedOperationException expected) { }
    }

    private static void race(int workers, IntConsumer work) throws Exception {
        CountDownLatch start = new CountDownLatch(1), complete = new CountDownLatch(workers);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        for (int i = 0; i < workers; i++) {
            final int id = i;
            new Thread(() -> { try { start.await(); work.accept(id); }
                catch (Throwable error) { failure.compareAndSet(null, error); }
                finally { complete.countDown(); } }, "mission-state-" + i).start();
        }
        start.countDown(); assertTrue("Concurrent tracker workers timed out", complete.await(30, TimeUnit.SECONDS));
        if (failure.get() != null) throw new AssertionError("Concurrent tracker failed", failure.get());
    }
}

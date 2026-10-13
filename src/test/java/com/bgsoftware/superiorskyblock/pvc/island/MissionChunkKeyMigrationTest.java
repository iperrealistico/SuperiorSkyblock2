package com.bgsoftware.superiorskyblock.pvc.island;

import com.bgsoftware.superiorskyblock.missions.common.tracker.ChunkKeyCodec;
import com.bgsoftware.superiorskyblock.missions.blocks.BlocksTracker;
import com.bgsoftware.superiorskyblock.missions.farming.PlantsTracker;
import org.bukkit.configuration.MemoryConfiguration;
import org.junit.Test;
import java.lang.reflect.Constructor;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import static org.junit.Assert.*;

public class MissionChunkKeyMigrationTest {
    @Test public void coordinateKeysPreserveBothSignedComponents() {
        int[] positions = {Integer.MIN_VALUE, -4096, -3, -1, 0, 1, 3, 4096, Integer.MAX_VALUE};
        Set<Long> keys = new HashSet<>();
        for (int x : positions) for (int z : positions) {
            long key = ChunkKeyCodec.encode(x, z);
            assertTrue(keys.add(key)); assertEquals(x, (int) (key >> 32)); assertEquals(z, (int) key);
        }
    }
    @Test public void legacyPositiveZIsExactButNegativeZCannotBeGuessed() {
        for (int x : new int[]{-19, -1, 0, 1, 19}) {
            long oldPositive = (long) x << 32 | 7;
            assertEquals(oldPositive, ChunkKeyCodec.encode(x, 7));
            assertFalse(ChunkKeyCodec.isAmbiguousLegacy(oldPositive, 1));
            long oldNegative = (long) x << 32 | -7;
            assertTrue(ChunkKeyCodec.isAmbiguousLegacy(oldNegative, 1));
            assertFalse(ChunkKeyCodec.isAmbiguousLegacy(ChunkKeyCodec.encode(x, -7), ChunkKeyCodec.VERSION));
        }
    }
    @Test public void blockMigrationQuarantinesOnlyAffectedTrackerAndPreservesInput() throws Exception {
        BlocksTracker tracker = isolated(BlocksTracker.class);
        MemoryConfiguration old = new MemoryConfiguration(); old.set("-7", Arrays.asList(12, 15));
        java.util.Map<String, Object> before = old.getValues(true);
        tracker.loadTrackedBlocks(BlocksTracker.TrackingType.PLACED_BLOCKS, "ambiguous-world", old, 1);
        assertFalse(tracker.isWorldAvailable("ambiguous-world")); assertTrue(tracker.isWorldAvailable("other-world"));
        assertFalse(tracker.canPersistProgress()); assertEquals(before, old.getValues(true));
        try { tracker.save(old); fail("Quarantined coordinates must never overwrite original data"); }
        catch (IllegalStateException expected) { }
        assertEquals(before, old.getValues(true));
        BlocksTracker safe = isolated(BlocksTracker.class);
        MemoryConfiguration modern = new MemoryConfiguration(); modern.set(Long.toString(ChunkKeyCodec.encode(12, -7)), Arrays.asList(12, 15));
        safe.loadTrackedBlocks(BlocksTracker.TrackingType.PLACED_BLOCKS, "modern-world", modern, 2);
        assertTrue(safe.canPersistProgress()); MemoryConfiguration saved = new MemoryConfiguration(); safe.save(saved);
        assertEquals(2, saved.getInt("tracker-data-version"));
        assertEquals(Arrays.asList(12, 15), saved.getIntegerList("tracked.placed.modern-world." + ChunkKeyCodec.encode(12, -7)));
    }
    @Test public void plantMigrationRetainsAmbiguousDataAndAcceptsNewNegativeCoordinates() throws Exception {
        PlantsTracker tracker = isolated(PlantsTracker.class); UUID placer = UUID.randomUUID();
        java.util.List<Integer> original = Arrays.asList(4, 9);
        tracker.load("ambiguous-world", -3L, original, placer, 1);
        assertFalse(tracker.isWorldAvailable("ambiguous-world")); assertTrue(tracker.isWorldAvailable("other-world"));
        assertFalse(tracker.canPersistProgress()); assertEquals(Arrays.asList(4, 9), original);
        MemoryConfiguration retained = new MemoryConfiguration(); retained.set("evidence", "unchanged");
        try { tracker.save(retained); fail("Quarantined plant progress must not be rewritten"); }
        catch (IllegalStateException expected) { }
        assertEquals("unchanged", retained.getString("evidence"));
        PlantsTracker safe = isolated(PlantsTracker.class);
        long key = ChunkKeyCodec.encode(-12, -3); safe.load("modern-world", key, original, placer, 2);
        MemoryConfiguration saved = new MemoryConfiguration(); safe.save(saved);
        assertEquals(2, saved.getInt("data-version"));
        assertEquals(original, saved.getIntegerList("placed-plants." + placer + ".modern-world." + key));
    }
    private static <T> T isolated(Class<T> type) throws Exception {
        Constructor<T> constructor = type.getDeclaredConstructor(); constructor.setAccessible(true); return constructor.newInstance();
    }
}

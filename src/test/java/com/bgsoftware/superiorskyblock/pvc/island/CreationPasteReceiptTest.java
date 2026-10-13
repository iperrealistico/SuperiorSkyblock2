package com.bgsoftware.superiorskyblock.pvc.island;
import com.bgsoftware.superiorskyblock.api.world.Dimension;
import com.bgsoftware.superiorskyblock.api.world.algorithm.IslandCreationAlgorithm.IslandCreationResult;
import org.bukkit.World;
import org.junit.Test;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.Assert.*;

public class CreationPasteReceiptTest {
    @Test public void clearedTransientPasteStateCannotEraseDelayedCreationReceipt() {
        String name = "PVC_RECEIPT_" + UUID.randomUUID(); Dimension.register(name, World.Environment.NORMAL);
        Dimension dimension = Dimension.getByName(name);
        List<IslandCreationResult.AffectedChunk> transientChunks = new ArrayList<>();
        transientChunks.add(new IslandCreationResult.AffectedChunk("islands", dimension, -7, 9));
        AtomicInteger entities = new AtomicInteger(); Runnable callback = entities::incrementAndGet;
        IslandCreationResult result = new IslandCreationResult(IslandCreationResult.Status.SUCCESS, null, null, true,
                transientChunks, callback);
        transientChunks.clear();
        transientChunks.add(new IslandCreationResult.AffectedChunk("other-island", dimension, 99, 101));
        assertEquals(1, result.getAffectedChunkSnapshot().size());
        assertEquals("islands", result.getAffectedChunkSnapshot().get(0).getWorldName());
        assertEquals(-7, result.getAffectedChunkSnapshot().get(0).getX()); assertEquals(9, result.getAffectedChunkSnapshot().get(0).getZ());
        result.getOnTeleportCallback().run(); assertEquals(1, entities.get());
        try { result.getAffectedChunkSnapshot().clear(); fail("The delayed receipt must be immutable"); }
        catch (UnsupportedOperationException expected) { }
    }
    @Test public void legacyConstructorsRemainAvailableWithoutClaimingTransientState() {
        IslandCreationResult result = new IslandCreationResult(IslandCreationResult.Status.SUCCESS, null, null, true);
        assertNull(result.getAffectedChunkSnapshot()); assertNull(result.getOnTeleportCallback()); assertTrue(result.shouldTeleportPlayer());
    }
}

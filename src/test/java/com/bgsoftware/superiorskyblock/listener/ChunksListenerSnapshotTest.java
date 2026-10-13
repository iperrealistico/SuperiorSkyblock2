package com.bgsoftware.superiorskyblock.listener;

import com.bgsoftware.superiorskyblock.api.world.Dimension;
import com.bgsoftware.superiorskyblock.api.world.WorldInfo;
import com.bgsoftware.superiorskyblock.core.ChunkPosition;
import com.bgsoftware.superiorskyblock.core.WorldInfoImpl;
import com.bgsoftware.superiorskyblock.core.collections.Chunk2ObjectMap;
import org.bukkit.World;
import org.junit.Test;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.junit.Assert.*;

public class ChunksListenerSnapshotTest {
    @Test public void consumedBiomeCoordinatesDoNotRetireTheListenerIdentity() {
        WorldInfo world = worldInfo();
        ChunkPosition identity = ChunksListener.captureLoadPosition(world, -7, 19);
        Chunk2ObjectMap<String> cache = new Chunk2ObjectMap<>();
        cache.put(identity, "calculated");
        // ChunksProvider consumes a supplied position; the listener continues
        // using its identity after the native biome operation returns.
        identity.release();
        identity.close();
        assertEquals(world.getName(), identity.getWorldName());
        assertEquals(-7, identity.getX());
        assertEquals(19, identity.getZ());
        assertEquals("calculated", cache.remove(identity.getWorldName(), identity.asPair()));
        assertTrue(cache.isEmpty());
    }

    @Test public void deferredIdentityRemainsStableAfterAnUnrelatedPooledPositionIsReused() throws Exception {
        WorldInfo world = worldInfo();
        ChunkPosition identity = ChunksListener.captureLoadPosition(world, 8, -12);
        CompletableFuture<Void> resume = new CompletableFuture<>();
        CompletableFuture<String> deferred = resume.thenApply(unused ->
                identity.getWorldName() + ":" + identity.getX() + ":" + identity.getZ());
        identity.release();
        ChunkPosition pooled = ChunkPosition.of(world, 100, 200);
        pooled.release();
        // The consumed pooled object is no longer ours; close only its new lease.
        ChunkPosition reused = ChunkPosition.of(world, 300, 400);
        try { resume.complete(null); }
        finally { reused.close(); }
        assertEquals(world.getName() + ":8:-12", deferred.get());
    }

    private static WorldInfo worldInfo() {
        String name = "PVC_CHUNK_IDENTITY_" + UUID.randomUUID();
        Dimension.register(name, World.Environment.THE_END);
        return new WorldInfoImpl("private_end", Dimension.getByName(name));
    }
}

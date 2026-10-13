package com.bgsoftware.superiorskyblock.world.chunk;

import org.bukkit.Chunk;
import org.bukkit.World;
import java.util.concurrent.CompletableFuture;

/** Modern implementations are loaded only on their exact native server ABI. */
public interface ChunkStorageAdapter {
    CompletableFuture<Void> save(Chunk ownedChunk, java.util.function.BooleanSupplier currentWriter,
                                 java.util.function.Consumer<Runnable> admitWriter);
    CompletableFuture<Boolean> isGenerated(World world, int x, int z);
    CompletableFuture<Chunk> loadExisting(World world, int x, int z);
    CompletableFuture<java.util.List<com.bgsoftware.superiorskyblock.core.ChunkPosition>> existingPositions(
            java.util.Collection<com.bgsoftware.superiorskyblock.core.ChunkPosition> positions);
}

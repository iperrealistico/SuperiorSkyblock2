package com.bgsoftware.superiorskyblock.world;

import com.bgsoftware.common.annotations.Nullable;
import com.bgsoftware.superiorskyblock.SuperiorSkyblockPlugin;
import com.bgsoftware.superiorskyblock.core.ChunkPosition;
import com.bgsoftware.superiorskyblock.core.threads.BukkitExecutor;
import com.bgsoftware.superiorskyblock.nms.world.ChunkReader;
import com.bgsoftware.superiorskyblock.world.chunk.ChunkLoadReason;
import com.bgsoftware.superiorskyblock.world.chunk.ChunksProvider;
import org.bukkit.World;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/** Captures immutable readers on chunk owners with one admitted chunk per job. */
public class WorldReader {
    private static final SuperiorSkyblockPlugin plugin = SuperiorSkyblockPlugin.getPlugin();
    private final World world;
    private final ChunkLoadReason reason;
    private final List<ChunkPosition> positions = new ArrayList<>();
    private final Map<ChunkPosition, ChunkReader> readers = new HashMap<>();
    private boolean finishCalled;
    private volatile boolean inFinishCallback;
    public WorldReader(World world, ChunkLoadReason reason) { this.world = world; this.reason = reason; }
    public void prepareChunk(ChunkPosition position) {
        if (finishCalled) throw new IllegalStateException("Cannot prepare chunks after WorldReader finish");
        positions.add(position.copy()); position.release();
    }
    @Nullable public ChunkReader getChunkReader(int blockX, int blockZ) {
        if (!inFinishCallback) throw new IllegalStateException("Chunk readers are available only inside finish callback");
        return readers.get(ChunkPosition.of(world, blockX >> 4, blockZ >> 4, false));
    }
    public void finish(Runnable onFinish) {
        finishAsync(onFinish).whenComplete((ignored, failure) -> {
            if (failure != null)
                plugin.getLogger().log(java.util.logging.Level.SEVERE, "Schematic capture failed", failure);
        });
    }
    public CompletableFuture<Void> finishAsync(Runnable onFinish) {
        if (finishCalled) throw new IllegalStateException("WorldReader already finished");
        finishCalled = true;
        return ChunksProvider.processSequential(positions, reason, chunk ->
                readers.put(ChunkPosition.of(chunk, false), plugin.getNMSWorld().createChunkReader(chunk)))
                .thenRunAsync(() -> {
                    inFinishCallback = true;
                    try { onFinish.run(); }
                    finally { inFinishCallback = false; }
                }, BukkitExecutor.ASYNC_EXECUTOR)
                .whenComplete((ignored, failure) -> { readers.clear(); positions.clear(); });
    }
}

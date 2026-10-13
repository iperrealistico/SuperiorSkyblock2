package com.bgsoftware.superiorskyblock.island.chunk;

import com.bgsoftware.superiorskyblock.SuperiorSkyblockPlugin;
import com.bgsoftware.superiorskyblock.api.island.Island;
import com.bgsoftware.superiorskyblock.api.world.Dimension;
import com.bgsoftware.superiorskyblock.api.world.WorldInfo;
import com.bgsoftware.superiorskyblock.api.wrappers.BlockPosition;
import com.bgsoftware.superiorskyblock.core.ChunkPosition;
import com.bgsoftware.superiorskyblock.core.collections.EnumerateMap;
import com.bgsoftware.superiorskyblock.core.database.bridge.IslandsDatabaseBridge;
import com.bgsoftware.superiorskyblock.core.database.sql.DBSession;
import com.bgsoftware.superiorskyblock.core.database.sql.SQLDatabaseBridge;
import com.bgsoftware.superiorskyblock.core.logging.Log;
import com.bgsoftware.superiorskyblock.core.threads.BukkitExecutor;

import java.util.BitSet;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.Supplier;

/** Cross-region dirty tracking with immutable snapshots and coalesced ordered saves. */
public class DirtyChunksContainer {
    private static final SuperiorSkyblockPlugin plugin = SuperiorSkyblockPlugin.getPlugin();
    private static final Map<UUID, DirtyChunksContainer> pending = new ConcurrentHashMap<>();
    private final Object stateLock = new Object();
    private final Object persistenceLock = new Object();
    private final EnumerateMap<Dimension, BitSet> dirtyChunks = new EnumerateMap<>(Dimension.values());
    private final AtomicBoolean saveScheduled = new AtomicBoolean();
    private final Island island;
    private final int minChunkX, minChunkZ, chunksInXAxis, chunksInZAxis, totalChunksCount;
    private final boolean shouldSave;
    private long revision;
    private long persistedRevision;
    private int consecutiveSaveFailures;
    public DirtyChunksContainer(Island island) {
        this.island = island;
        BlockPosition min = island.getMinimumPosition(), max = island.getMaximumPosition();
        minChunkX = min.getX() >> 4; minChunkZ = min.getZ() >> 4;
        chunksInXAxis = (max.getX() >> 4) - minChunkX + 1;
        chunksInZAxis = (max.getZ() >> 4) - minChunkZ + 1;
        totalChunksCount = Math.multiplyExact(chunksInXAxis, chunksInZAxis);
        shouldSave = !island.isSpawn();
    }
    public Island getIsland() { return island; }
    /** Only snapshot construction/admission is locked; never wait for SQL here. */
    public CompletableFuture<Void> withPersistenceLock(Supplier<CompletableFuture<Void>> submission) {
        synchronized (persistenceLock) { return submission.get(); }
    }
    public boolean isMarkedDirty(ChunkPosition position) {
        int index = getChunkIndex(position);
        synchronized (stateLock) {
            BitSet bits = dirtyChunks.get(position.getWorldsInfo().getDimension());
            return bits != null && bits.get(index);
        }
    }
    public void markEmpty(ChunkPosition position, boolean save) { mark(position, false, save); }
    public void markDirty(ChunkPosition position, boolean save) { mark(position, true, save); }
    private void mark(ChunkPosition position, boolean value, boolean save) {
        int index = getChunkIndex(position);
        boolean changed;
        synchronized (stateLock) {
            BitSet bits = dirtyChunks.computeIfAbsent(position.getWorldsInfo().getDimension(), ignored -> new BitSet(totalChunksCount));
            changed = bits.get(index) != value;
            if (changed) { bits.set(index, value); revision++; }
        }
        if (changed && save && shouldSave) scheduleSave();
    }
    private void scheduleSave() {
        scheduleSave(1L);
    }
    private void scheduleSave(long delay) {
        pending.put(island.getUniqueId(), this);
        if (!BukkitExecutor.isRunning() || DBSession.isCommitOutcomeUncertain()) return;
        if (!saveScheduled.compareAndSet(false, true)) return;
        try { BukkitExecutor.async(this::saveCoalesced, delay); }
        catch (java.util.concurrent.RejectedExecutionException ignored) { saveScheduled.set(false); /* shutdown flush owns pending state */ }
    }
    private void saveCoalesced() {
        persistNow().whenComplete((unused, error) -> {
            saveScheduled.set(false);
            boolean needsSave;
            boolean reportFailure;
            long retryDelay;
            synchronized (stateLock) {
                if (error == null) consecutiveSaveFailures = 0;
                else consecutiveSaveFailures = Math.min(7, consecutiveSaveFailures + 1);
                needsSave = revision != persistedRevision;
                retryDelay = error == null ? 1L : Math.min(1200L, 20L << (consecutiveSaveFailures - 1));
                reportFailure = error != null && (consecutiveSaveFailures == 1 || DBSession.isCommitOutcomeUncertain());
                if (!needsSave) pending.remove(island.getUniqueId(), this);
            }
            if (needsSave) scheduleSave(retryDelay);
            if (reportFailure)
                Log.error(error, "Dirty chunk state was not acknowledged for island ", island.getUniqueId(),
                        "; the pending snapshot is retained.");
        });
    }
    private CompletableFuture<Void> persistNow() {
        // No regional mutation waits for serialization or SQL admission.
        synchronized (persistenceLock) {
            long captured;
            synchronized (stateLock) { captured = revision; }
            CompletableFuture<Void> acknowledgement;
            try {
                if (island.getDatabaseBridge() instanceof SQLDatabaseBridge) {
                    acknowledgement = ((SQLDatabaseBridge) island.getDatabaseBridge()).executeAcknowledgedForced(
                            () -> IslandsDatabaseBridge.saveDirtyChunks(this));
                } else {
                    // Preserve the custom Paper provider's legacy contract. The
                    // Folia write-ahead path rejects providers without SQL ACKs.
                    IslandsDatabaseBridge.saveDirtyChunks(this);
                    acknowledgement = CompletableFuture.completedFuture(null);
                }
            } catch (Throwable failure) {
                acknowledgement = new CompletableFuture<>();
                acknowledgement.completeExceptionally(failure);
            }
            return acknowledgement.thenRun(() -> {
                synchronized (stateLock) { persistedRevision = Math.max(persistedRevision, captured); }
            });
        }
    }
    public static void flushPending() {
        for (DirtyChunksContainer container : pending.values()) {
            container.persistNow().whenComplete((unused, error) -> {
                if (error != null) {
                    Log.error(error, "Cannot acknowledge final dirty chunk snapshot for island ", container.island.getUniqueId());
                    return;
                }
                synchronized (container.stateLock) {
                    if (container.revision == container.persistedRevision)
                        pending.remove(container.island.getUniqueId(), container);
                }
            });
        }
    }
    public void getDirtyChunks(Consumer<ChunkPosition> consumer) {
        Map<Dimension, BitSet> snapshot = new java.util.LinkedHashMap<>();
        synchronized (stateLock) {
            for (Dimension dimension : Dimension.values()) {
                BitSet bits = dirtyChunks.get(dimension);
                if (bits != null && !bits.isEmpty()) snapshot.put(dimension, (BitSet) bits.clone());
            }
        }
        for (Map.Entry<Dimension, BitSet> entry : snapshot.entrySet()) {
            WorldInfo info = plugin.getGrid().getIslandsWorldInfo(island, entry.getKey());
            if (info == null) continue;
            BitSet bits = entry.getValue();
            for (int index = bits.nextSetBit(0); index >= 0; index = bits.nextSetBit(index + 1))
                consumer.accept(ChunkPosition.of(info, minChunkX + index / chunksInZAxis,
                        minChunkZ + index % chunksInZAxis, false));
        }
    }
    private int getChunkIndex(ChunkPosition position) {
        int x = position.getX() - minChunkX, z = position.getZ() - minChunkZ;
        if (x < 0 || z < 0 || x >= chunksInXAxis || z >= chunksInZAxis)
            throw new IllegalStateException("Chunk is outside reserved island boundaries: " + position);
        return x * chunksInZAxis + z;
    }
}

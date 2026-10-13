package com.bgsoftware.superiorskyblock.island.algorithm;

import com.bgsoftware.superiorskyblock.api.island.Island;
import com.bgsoftware.superiorskyblock.api.island.IslandChunkFlags;
import com.bgsoftware.superiorskyblock.api.island.algorithms.IslandEntitiesTrackerAlgorithm;
import com.bgsoftware.superiorskyblock.api.key.Key;
import com.bgsoftware.superiorskyblock.api.key.KeyMap;
import com.bgsoftware.superiorskyblock.core.ChunkPosition;
import com.bgsoftware.superiorskyblock.core.database.bridge.IslandsDatabaseBridge;
import com.bgsoftware.superiorskyblock.core.key.KeyIndicator;
import com.bgsoftware.superiorskyblock.core.key.map.KeyMaps;
import com.bgsoftware.superiorskyblock.core.key.Keys;
import com.bgsoftware.superiorskyblock.core.key.types.EntityTypeKey;
import com.bgsoftware.superiorskyblock.core.logging.Debug;
import com.bgsoftware.superiorskyblock.core.logging.Log;
import com.bgsoftware.superiorskyblock.core.threads.BukkitExecutor;
import com.bgsoftware.superiorskyblock.core.threads.OwnedStage;
import com.bgsoftware.superiorskyblock.island.IslandUtils;
import com.bgsoftware.superiorskyblock.island.upgrade.IslandUpgradeConstants;
import com.bgsoftware.superiorskyblock.world.BukkitEntities;
import com.bgsoftware.superiorskyblock.world.chunk.ChunksProvider;
import com.bgsoftware.superiorskyblock.world.chunk.ChunkLoadReason;
import com.google.common.base.Preconditions;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;

import java.util.Collections;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.UUID;

public class DefaultIslandEntitiesTrackerAlgorithm implements IslandEntitiesTrackerAlgorithm {

    private static final Set<EntityType> TRACKABLE_ENTITIES = initializeTrackableEntities();
    private static final long CALCULATE_DELAY = TimeUnit.MINUTES.toMillis(5);

    private final KeyMap<Integer> entityCounts = KeyMaps.createConcurrentHashMap(KeyIndicator.ENTITY_TYPE);
    private final EntityCountReconciliation reconciliation = new EntityCountReconciliation();
    private final EntitySlotReservations slots = new EntitySlotReservations(reconciliation.getLock());
    private final Map<UUID, Key> knownEntities = new HashMap<>();
    private final KeyMap<Integer> knownEntityCounts = KeyMaps.createConcurrentHashMap(KeyIndicator.ENTITY_TYPE);
    private static final java.util.concurrent.ConcurrentMap<UUID, DefaultIslandEntitiesTrackerAlgorithm> RESERVED_ENTITIES =
            new java.util.concurrent.ConcurrentHashMap<>();

    private final Island island;

    private final AtomicBoolean beingRecalculated = new AtomicBoolean();
    private volatile long lastCalculateTime = 0L;

    public DefaultIslandEntitiesTrackerAlgorithm(Island island) {
        this.island = island;
    }

    @Override
    public boolean trackEntity(Key key, int amount) {
        Preconditions.checkNotNull(key, "key parameter cannot be null.");

        Log.debug(Debug.ENTITY_SPAWN, island.getOwner().getName(), key, amount);

        if (amount <= 0) {
            Log.debugResult(Debug.ENTITY_SPAWN, "Return", "Negative Amount");
            return false;
        }

        if (!canTrackEntity(key)) {
            Log.debugResult(Debug.ENTITY_SPAWN, "Return", "Cannot Track Entity");
            return false;
        }

        synchronized (reconciliation.getLock()) {
            ChunkCountReconciliation.EntityIdentity identity = ChunkCountReconciliation.currentEntity();
            if (identity != null && amount == 1) {
                consumeReservation(identity.getUuid());
                if (knownEntities.containsKey(identity.getUuid())) return true;
                knownEntities.put(identity.getUuid(), key);
                knownEntityCounts.merge(key, 1, Integer::sum);
            }
            entityCounts.merge(key, amount, Integer::sum);
            reconciliation.record(identity == null ? null : identity.getUuid(), key, amount, true);
        }

        Log.debugResult(Debug.ENTITY_SPAWN, "Return", "Success");

        return true;
    }

    @Override
    public boolean untrackEntity(Key key, int amount) {
        Preconditions.checkNotNull(key, "key parameter cannot be null.");

        Log.debug(Debug.ENTITY_DESPAWN, island.getOwner().getName(), key, amount);

        if (amount <= 0) {
            Log.debugResult(Debug.ENTITY_DESPAWN, "Return", "Negative Amount");
            return false;
        }

        boolean currentlyTrackable = canTrackEntity(key);

        synchronized (reconciliation.getLock()) {
            ChunkCountReconciliation.EntityIdentity identity = ChunkCountReconciliation.currentEntity();
            UUID identityUuid = identity == null ? null : identity.getUuid();
            if (identityUuid != null && amount == 1) consumeReservation(identityUuid);
            if (!applyEntityRemoval(entityCounts, knownEntities, knownEntityCounts,
                    key, amount, identityUuid, currentlyTrackable))
                return false;
            reconciliation.record(identity == null ? null : identity.getUuid(), key, amount, false);
        }

        Log.debugResult(Debug.ENTITY_DESPAWN, "Return", "Success");

        return true;
    }

    /** A removed limit changes future admission, not the obligation to retire entities already counted. */
    static boolean applyEntityRemoval(KeyMap<Integer> counts, Map<UUID, Key> identities,
                                      KeyMap<Integer> identityCounts, Key key, int amount,
                                      UUID identity, boolean currentlyTrackable) {
        Key previous = identity == null || amount != 1 ? null : identities.get(identity);
        Key countedKey = previous == null ? key : previous;
        // An unknown UUID can retire only persisted numeric counts not yet attributed to live UUIDs.
        // This also makes duplicate death receipts harmless without retaining tombstones indefinitely.
        if (identity != null && amount == 1 && previous == null &&
                counts.getRaw(countedKey, 0) <= identityCounts.getRaw(countedKey, 0)) return false;
        if (!currentlyTrackable && previous == null && counts.getRaw(countedKey, 0) <= 0) return false;
        if (previous != null) {
            identities.remove(identity);
            identityCounts.computeIfPresent(countedKey, (ignored, current) -> current > 1 ? current - 1 : null);
        }
        counts.computeIfPresent(countedKey, (ignored, current) -> current > amount ? current - amount : null);
        return true;
    }

    @Override
    public int getEntityCount(Key key) {
        synchronized (reconciliation.getLock()) { return entityCounts.getOrDefault(key, 0); }
    }

    @Override
    public Map<Key, Integer> getEntitiesCounts() {
        synchronized (reconciliation.getLock()) {
            KeyMap<Integer> snapshot = KeyMaps.createHashMap(KeyIndicator.ENTITY_TYPE);
            snapshot.putAll(entityCounts);
            return Collections.unmodifiableMap(snapshot);
        }
    }

    @Override
    public void clearEntityCounts() {
        synchronized (reconciliation.getLock()) {
            reconciliation.invalidate();
            this.entityCounts.clear();
            knownEntities.clear();
            knownEntityCounts.clear();
            slots.clear().forEach(uuid -> RESERVED_ENTITIES.remove(uuid, this));
        }
    }

    @Override
    public void recalculateEntityCounts() {
        if (!beingRecalculated.compareAndSet(false, true))
            return;
        if (!canRecalculateEntityCounts()) { beingRecalculated.set(false); return; }

        Log.debug(Debug.CHUNK_CALCULATION_ENTITIES, island.getOwner().getName());

        final long token = reconciliation.begin();
        try {
            this.lastCalculateTime = System.currentTimeMillis();
            OwnedStage.submit(BukkitExecutor.isFolia() ? BukkitExecutor.ASYNC_EXECUTOR : BukkitExecutor.SYNC_EXECUTOR, () -> {
                List<ChunkPosition> positions = new ArrayList<>();
                IslandUtils.getChunkCoords(island, IslandChunkFlags.ONLY_PROTECTED | IslandChunkFlags.NO_EMPTY_CHUNKS)
                        .values().forEach(positions::addAll);
                // One bounded chunk at a time; reads are on its owner and no worker waits inside join/get.
                CompletableFuture<Void> scanning = ChunksProvider.processExistingSequential(positions,
                    ChunkLoadReason.ENTITIES_RECALCULATE, chunk -> {
                        Map<UUID, Key> chunkEntities = new HashMap<>();
                        for (Entity entity : chunk.getEntities()) {
                            if (BukkitExecutor.isFolia() && !BukkitExecutor.owns(entity))
                                throw new IllegalStateException("Entity calculation requires the entity owner");
                            if (entity.isDead() || BukkitEntities.canBypassEntityLimit(entity)) continue;
                            Key key = Keys.of(entity);
                            if (canTrackEntity(key)) chunkEntities.put(entity.getUniqueId(), key);
                        }
                        reconciliation.capture(token, chunkEntities);
                    });
                return scanning.thenRunAsync(() -> {
                    // Preparation may race new events. Retry a small number of detached copies; on churn retain live data.
                    for (int attempt = 0; attempt < 3; ++attempt) {
                        EntityCountReconciliation.Prepared prepared = reconciliation.prepare(token);
                        if (prepared == null) return;
                        if (reconciliation.finish(prepared, () -> {
                            entityCounts.clear(); entityCounts.putAll(prepared.getCounts());
                            knownEntities.clear(); knownEntities.putAll(prepared.getIdentities());
                            knownEntityCounts.clear(); knownEntityCounts.putAll(prepared.getCounts());
                        })) return;
                    }
                }, BukkitExecutor.ASYNC_EXECUTOR);
            }).whenComplete((unused, error) -> {
                reconciliation.cancel(token);
                if (error != null) {
                    this.lastCalculateTime = 0L;
                    Log.error(error, "An unexpected error occurred while calculating island entities:");
                }
                beingRecalculated.set(false);
                IslandsDatabaseBridge.saveEntityCounts(this.island);
            });
        } catch (RuntimeException | Error error) {
            reconciliation.cancel(token);
            this.lastCalculateTime = 0L;
            beingRecalculated.set(false);
            throw error;
        }
    }

    @Override
    public boolean canRecalculateEntityCounts() {
        long currentTime = System.currentTimeMillis();
        return currentTime - lastCalculateTime > CALCULATE_DELAY;
    }

    /** HIGHEST admission and MONITOR consumption use the very same counter/publication barrier. */
    public boolean reserveEntitySlot(UUID uuid, Key key, int limit) {
        if (limit < 0) return true;
        DefaultIslandEntitiesTrackerAlgorithm previous = RESERVED_ENTITIES.get(uuid);
        if (previous != null && previous != this) previous.releaseEntitySlot(uuid);
        synchronized (reconciliation.getLock()) {
            if (knownEntities.containsKey(uuid)) return true;
            if (!slots.reserve(uuid, key, entityCounts.getOrDefault(key, 0), limit)) return false;
            DefaultIslandEntitiesTrackerAlgorithm other = RESERVED_ENTITIES.putIfAbsent(uuid, this);
            if (other != null && other != this) { slots.release(uuid); return false; }
            return true;
        }
    }

    public void releaseEntitySlot(UUID uuid) {
        synchronized (reconciliation.getLock()) { consumeReservation(uuid); }
    }
    private void consumeReservation(UUID uuid) {
        slots.release(uuid);
        RESERVED_ENTITIES.remove(uuid, this);
    }
    public static void releaseEntityReservation(UUID uuid) {
        DefaultIslandEntitiesTrackerAlgorithm tracker = RESERVED_ENTITIES.get(uuid);
        if (tracker != null) tracker.releaseEntitySlot(uuid);
    }
    public static void releaseOtherEntityReservation(UUID uuid, Object targetTracker) {
        DefaultIslandEntitiesTrackerAlgorithm tracker = RESERVED_ENTITIES.get(uuid);
        if (tracker != null && tracker != targetTracker) tracker.releaseEntitySlot(uuid);
    }

    private boolean canTrackEntity(Key key) {
        if (island.getEntityLimit(key) != IslandUpgradeConstants.NO_LIMIT_VALUE)
            return true;

        if (key instanceof EntityTypeKey) {
            return TRACKABLE_ENTITIES.contains(((EntityTypeKey) key).getEntityType());
        } else {
            return key.toString().contains("MINECART");
        }
    }

    private static Set<EntityType> initializeTrackableEntities() {
        EnumSet<EntityType> trackableEntities = EnumSet.noneOf(EntityType.class);

        for (EntityType entityType : EntityType.values()) {
            if (entityType.name().contains("MINECART")) {
                trackableEntities.add(entityType);
            }
        }

        return trackableEntities.isEmpty() ? Collections.emptySet() : Collections.unmodifiableSet(trackableEntities);
    }

}

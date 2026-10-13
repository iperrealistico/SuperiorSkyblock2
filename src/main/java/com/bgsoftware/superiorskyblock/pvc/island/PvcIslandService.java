package com.bgsoftware.superiorskyblock.pvc.island;

import com.bgsoftware.common.databasebridge.sql.query.Column;
import com.bgsoftware.common.databasebridge.sql.transaction.InsertSQLDatabaseTransaction;
import com.bgsoftware.common.databasebridge.sql.transaction.UpdateSQLDatabaseTransaction;
import com.bgsoftware.common.databasebridge.transaction.IDatabaseTransaction;
import com.bgsoftware.superiorskyblock.SuperiorSkyblockPlugin;
import com.bgsoftware.superiorskyblock.api.data.DatabaseBridge;
import com.bgsoftware.superiorskyblock.api.island.Island;
import com.bgsoftware.superiorskyblock.api.wrappers.SuperiorPlayer;
import com.bgsoftware.superiorskyblock.core.database.DatabaseResult;
import com.bgsoftware.superiorskyblock.core.database.sql.DBSession;
import com.bgsoftware.superiorskyblock.core.logging.Log;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicBoolean;

/** Ownership indexes, stable IDs, selected view and durable operation identities. */
public final class PvcIslandService implements AutoCloseable {
    private final SuperiorSkyblockPlugin plugin;
    private final Map<UUID, Long> ids = new ConcurrentHashMap<>();
    private final Map<Long, UUID> uuids = new ConcurrentHashMap<>();
    private final Map<UUID, CompletableFuture<Long>> idWrites = new ConcurrentHashMap<>();
    private final Map<UUID, Map<UUID, Island>> owned = new ConcurrentHashMap<>();
    private final Map<UUID, UUID> selections = new ConcurrentHashMap<>();
    private final Map<UUID, CreationReservation> creations = new ConcurrentHashMap<>();
    private final Map<UUID, Island> candidates = new ConcurrentHashMap<>();
    private final Map<UUID, CompletableFuture<Void>> deletions = new ConcurrentHashMap<>();
    private final Map<UUID, CompletableFuture<Void>> resets = new ConcurrentHashMap<>();
    private final Map<UUID, UUID> transfers = new ConcurrentHashMap<>();
    private final Map<UUID, UUID> departingOwners = new ConcurrentHashMap<>();
    private final Map<UUID, AtomicLong> membershipRevisions = new ConcurrentHashMap<>();
    private final Map<UUID, AtomicLong> revisions = new ConcurrentHashMap<>();
    private final Map<UUID, AtomicLong> worldGenerations = new ConcurrentHashMap<>();
    private final Map<String, UUID> reservedNames = new ConcurrentHashMap<>();
    private final Map<UUID, String> creationNames = new ConcurrentHashMap<>();
    private final java.util.Set<UUID> recovering = ConcurrentHashMap.newKeySet();
    private final AtomicLong nextId = new AtomicLong();
    private final OwnershipReservations reservations = new OwnershipReservations(128);
    private final Object positionLock = new Object();
    private final Object operationLock = new Object();
    private final Object[] selectionLocks = new Object[64];
    private final Object[] worldWriteLocks = new Object[64];
    private volatile boolean ready;
    private volatile boolean closed;
    private volatile boolean initializationFailed;
    private volatile CompletableFuture<Void> initialization = new CompletableFuture<>();
    public PvcIslandService(SuperiorSkyblockPlugin plugin) { this.plugin = plugin;
        for (int i = 0; i < selectionLocks.length; i++) { selectionLocks[i] = new Object(); worldWriteLocks[i] = new Object(); } }
    public Object getPositionLock() { return positionLock; }
    public boolean isAvailable(UUID island) { return !candidates.containsKey(island) && !deletions.containsKey(island) && !transfers.containsKey(island) && !resets.containsKey(island); }
    public long getRevision(UUID island) { AtomicLong revision = revisions.get(island); return revision == null ? 0L : revision.get(); }
    /** Transient generation for confirmation requests, including remove/rejoin and role ABA. */
    public long getMembershipRevision(UUID island) { AtomicLong value = membershipRevisions.get(island); return value == null ? 0L : value.get(); }
    public void membershipChanged(UUID island) { membershipRevisions.computeIfAbsent(island, unused -> new AtomicLong()).incrementAndGet(); }
    public long getWorldGeneration(UUID island) { AtomicLong generation = worldGenerations.get(island); return generation == null ? 0L : generation.get(); }
    public long advanceWorldGeneration(UUID island) {
        synchronized (worldWriteLock(island)) { return worldGenerations.computeIfAbsent(island, unused -> new AtomicLong()).incrementAndGet(); }
    }
    private Object worldWriteLock(UUID island) { return worldWriteLocks[(island.hashCode() & Integer.MAX_VALUE) % worldWriteLocks.length]; }
    /** Only enqueue pre-serialized immutable native data here: no I/O, world reads or callbacks under this monitor. */
    public void runWorldWriteAdmission(Island island, long generation, Runnable enqueue) {
        synchronized (worldWriteLock(island.getUniqueId())) {
            if (!isCurrentWorldWriter(island, generation)) throw new IllegalStateException("A stale island world generation cannot submit storage writes");
            enqueue.run();
        }
    }
    public boolean isCreationPending(UUID island) { return creations.containsKey(island) || candidates.containsKey(island); }
    public boolean isCurrentWorldWriter(Island island, long generation) {
        if (closed || DBSession.isCommitOutcomeUncertain() || getWorldGeneration(island.getUniqueId()) != generation) return false;
        return candidates.get(island.getUniqueId()) == island || plugin.getGrid().getIslandByUUID(island.getUniqueId()) == island;
    }
    public boolean isRecovering(UUID island) { return recovering.contains(island); }
    public boolean isReady() { return ready; }
    public CompletableFuture<Void> whenReady() { return initialization; }
    public void blockOperations(Throwable error) { initializationFailed = true; ready = false; initialization.completeExceptionally(error); }

    public void loadMetadata() {
        if (plugin.getFactory().hasCustomDatabaseBridge())
            throw new IllegalStateException("PVC multi-island metadata requires the maintained SQL database provider");
        DBSession.createTable("pvc_island_ids", new Column("uuid", "UUID PRIMARY KEY"), new Column("short_id", "BIGINT"));
        DBSession.createTable("pvc_player_selection", new Column("player", "UUID PRIMARY KEY"), new Column("island", "UUID"));
        DBSession.createTable("pvc_island_jobs", new Column("operation", "UUID PRIMARY KEY"), new Column("owner", "UUID"),
                new Column("island", "UUID"), new Column("state", "TEXT"));
        DBSession.createTable("pvc_metadata", new Column("name", "VARCHAR(32) PRIMARY KEY"), new Column("value", "TEXT"));
        DBSession.createTable("pvc_deferred_deletes", new Column("island", "UUID PRIMARY KEY"), new Column("owner", "UUID"), new Column("state", "TEXT"));
        // The upstream createIndex helper creates UNIQUE indexes; owner must remain non-unique.
        DBSession.createIndex("pvc_island_id_index", "pvc_island_ids", "short_id");
        DatabaseBridge provider = plugin.getFactory().createDatabaseBridge((Island) null);
        if (!(provider instanceof com.bgsoftware.superiorskyblock.core.database.sql.SQLDatabaseBridge))
            throw new IllegalStateException("PVC metadata requires the acknowledged SQL provider");
        com.bgsoftware.superiorskyblock.core.database.sql.SQLDatabaseBridge bridge =
                (com.bgsoftware.superiorskyblock.core.database.sql.SQLDatabaseBridge) provider;
        java.util.concurrent.atomic.AtomicBoolean schemaPresent = new java.util.concurrent.atomic.AtomicBoolean();
        bridge.loadAllObjectsStrict("pvc_metadata", row -> {
            DatabaseResult result = new DatabaseResult(row);
            if ("schema".equals(result.getString("name").orElse(""))) {
                if (!"1".equals(result.getString("value").orElse("")) || schemaPresent.getAndSet(true))
                    throw new IllegalStateException("Unsupported or duplicate PVC metadata schema; restore the matching plugin version");
            }
        });
        bridge.loadAllObjectsStrict("pvc_island_ids", row -> {
            DatabaseResult result = new DatabaseResult(row);
            UUID uuid = result.getUUID("uuid").orElseThrow(() -> new IllegalStateException("Invalid stored island ID"));
            long id = result.getLong("short_id").orElse(0L);
            if (id <= 0 || uuids.putIfAbsent(id, uuid) != null || ids.putIfAbsent(uuid, id) != null)
                throw new IllegalStateException("Duplicate or invalid PVC island identifier");
            nextId.accumulateAndGet(id, Math::max);
        });
        bridge.loadAllObjectsStrict("pvc_player_selection", row -> {
            DatabaseResult result = new DatabaseResult(row);
            UUID player = result.getUUID("player").orElseThrow(() -> new IllegalStateException("Invalid selection player identity"));
            String island = result.getString("island").orElseThrow(() -> new IllegalStateException("Invalid selected island value"));
            if (!island.isEmpty()) selections.put(player, UUID.fromString(island));
        });
        CompletableFuture<Void> migration = schemaPresent.get() ? CompletableFuture.completedFuture(null) :
                insert("pvc_metadata", Arrays.asList("name", "value"), Arrays.asList("schema", "1"));
        for (Island island : plugin.getGrid().getIslands()) {
            register(island);
            migration = migration.thenCompose(unused -> ensureId(island.getUniqueId()).thenApply(id -> null));
        }
        for (SuperiorPlayer player : plugin.getPlayers().getAllPlayers()) {
            UUID selected = selections.get(player.getUniqueId());
            Island island = selected == null ? null : plugin.getGrid().getIslandByUUID(selected);
            if (island != null && island.isMember(player)) player.selectIsland(island);
            else player.selectIsland(player.getIslands().size() == 1 ? player.getIslands().get(0) : null);
        }
        List<CompletableFuture<Void>> recoveries = new ArrayList<>();
        List<Runnable> recover = new ArrayList<>();
        java.util.Set<UUID> pendingIslands = new java.util.HashSet<>();
        bridge.loadAllObjectsStrict("pvc_island_jobs", row -> {
            DatabaseResult result = new DatabaseResult(row);
            String state = result.getString("state").orElse("UNKNOWN");
            DisbandRefundPolicy.requireRecoverableState(state);
            if (!"ACTIVE".equals(state) && !"CANCELLED".equals(state) && !"DELETED".equals(state) && !"TRANSFER_DONE".equals(state) && !"RESET_DONE".equals(state) && !"PASTE_DONE".equals(state)) {
                UUID operation = result.getUUID("operation").orElseThrow(() -> new IllegalStateException("Invalid island journal operation"));
                UUID islandId = result.getUUID("island").orElseThrow(() -> new IllegalStateException("Invalid island journal target"));
                UUID ownerId = result.getUUID("owner").orElseThrow(() -> new IllegalStateException("Invalid island journal owner"));
                if (!pendingIslands.add(islandId)) throw new IllegalStateException("Several unfinished operations address the same island");
                CreationReservation interrupted = new CreationReservation(operation, ownerId, islandId);
                Island candidate = plugin.getGrid().getIslandByUUID(islandId);
                if (!("RESERVED".equals(state) || "CREATING".equals(state) || "DELETING".equals(state) ||
                        state.startsWith("TRANSFER_PENDING:") || state.startsWith("RESET_WORLD:") || state.startsWith("RESET_CHUNKS:") || state.startsWith("PASTE_DIM:")))
                    throw new IllegalStateException("Unknown non-terminal island journal state: " + state);
                if (candidate != null && !state.startsWith("TRANSFER_PENDING:") && !ownerId.equals(candidate.getOwner().getUniqueId()))
                    throw new IllegalStateException("Journal owner disagrees with the island row");
                if ((state.startsWith("RESET_") || state.startsWith("PASTE_DIM:")) && candidate instanceof com.bgsoftware.superiorskyblock.island.SIsland) {
                    validateResetAction(state);
                    recover.add(() -> recoveries.add(resumeReset((com.bgsoftware.superiorskyblock.island.SIsland) candidate, interrupted, state)));
                    return;
                }
                if (state.startsWith("TRANSFER_PENDING:") && candidate instanceof com.bgsoftware.superiorskyblock.island.SIsland) {
                    String[] parts = state.split(":");
                    if (parts.length != 4) throw new IllegalStateException("Transfer journal lacks an explicit membership before-image");
                    UUID target = UUID.fromString(parts[1]); int role = Integer.parseInt(parts[2]);
                    if (!"true".equals(parts[3]) && !"false".equals(parts[3])) throw new IllegalStateException("Invalid transfer membership before-image");
                    if (!ownerId.equals(candidate.getOwner().getUniqueId()) && !target.equals(candidate.getOwner().getUniqueId()))
                        throw new IllegalStateException("Transfer owner disagrees with the island row");
                    recover.add(() -> recoveries.add(acknowledge(candidate, () -> ((com.bgsoftware.superiorskyblock.island.SIsland) candidate)
                            .recoverTransfer(ownerId, target, role, Boolean.parseBoolean(parts[3])))
                            .thenCompose(unused -> state(interrupted, "CANCELLED"))));
                    return;
                }
                if (candidate == null) {
                    // A candidate is persisted before any paste; no row means no world write began.
                    recover.add(() -> recoveries.add(state(interrupted, "CANCELLED")));
                } else {
                    recover.add(() -> {
                        recovering.add(islandId);
                        creations.put(islandId, interrupted); candidates.put(islandId, candidate);
                        candidate.disbandIsland();
                        CompletableFuture<Void> recovery = deletions.get(islandId);
                        if (recovery != null) recoveries.add(recovery);
                    });
                }
            }
        });
        List<CreationReservation> deferredDeletes = new ArrayList<>();
        bridge.loadAllObjectsStrict("pvc_deferred_deletes", row -> {
            DatabaseResult result = new DatabaseResult(row);
            String state = result.getString("state").orElseThrow(() -> new IllegalStateException("Invalid deferred delete state"));
            if ("DONE".equals(state)) return;
            if (!"PENDING".equals(state)) throw new IllegalStateException("Invalid deferred delete intent");
            UUID island = result.getUUID("island").orElseThrow(() -> new IllegalStateException("Invalid deferred island"));
            UUID owner = result.getUUID("owner").orElseThrow(() -> new IllegalStateException("Invalid deferred owner"));
            deferredDeletes.add(new CreationReservation(UUID.randomUUID(), owner, island));
        });
        recover.forEach(Runnable::run);
        recoveries.add(migration);
        initialization = CompletableFuture.allOf(recoveries.toArray(new CompletableFuture[0])).thenCompose(unused -> {
            CompletableFuture<Void> chain = CompletableFuture.completedFuture(null);
            for (CreationReservation deferred : deferredDeletes) chain = chain.thenCompose(ignored -> {
                Island island = plugin.getGrid().getIslandByUUID(deferred.island);
                CompletableFuture<Void> deleted = CompletableFuture.completedFuture(null);
                if (island != null && deferred.owner.equals(island.getOwner().getUniqueId())) {
                    recovering.add(deferred.island); island.disbandIsland();
                    deleted = deletions.get(deferred.island);
                    if (deleted == null) return failed(new IllegalStateException("Deferred cleanup was not admitted"));
                }
                return deleted.thenCompose(value -> insert("pvc_deferred_deletes", Arrays.asList("island", "owner", "state"),
                        Arrays.asList(deferred.island.toString(), deferred.owner.toString(), "DONE")));
            });
            return chain;
        });
        initialization.whenComplete((unused, error) -> {
            if (error == null && !closed && !initializationFailed) ready = true;
            else if (error != null) Log.error(error, "PVC island initialization failed; creation and management remain blocked");
        });
    }

    public void register(Island island) {
        if (island.isSpawn() || island.getOwner() == null) return;
        reservations.atomic(() -> { owned.computeIfAbsent(island.getOwner().getUniqueId(), u -> new ConcurrentHashMap<>()).put(island.getUniqueId(), island); return null; });
        if (ready) ensureId(island.getUniqueId());
    }
    public void unregister(Island island) {
        reservations.atomic(() -> {
            Map<UUID, Island> properties = owned.get(island.getOwner().getUniqueId());
            if (properties != null) { properties.remove(island.getUniqueId()); if (properties.isEmpty()) owned.remove(island.getOwner().getUniqueId(), properties); }
            return null;
        });
        for (SuperiorPlayer player : island.getIslandMembers(true)) player.removeIslandMembership(island);
        membershipRevisions.remove(island.getUniqueId());
    }
    public void transfer(Island island, UUID previousOwner) {
        transfer(island, previousOwner, null);
    }
    public void transfer(Island island, UUID previousOwner, UUID operation) {
        revisions.computeIfAbsent(island.getUniqueId(), unused -> new AtomicLong()).incrementAndGet();
        reservations.atomic(() -> {
            if (transfers.containsKey(island.getUniqueId())) departingOwners.putIfAbsent(island.getUniqueId(), previousOwner);
            Map<UUID, Island> previous = owned.get(previousOwner);
            if (previous != null) { previous.remove(island.getUniqueId()); if (previous.isEmpty()) owned.remove(previousOwner, previous); }
            register(island); if (operation != null) reservations.convert(operation); return null;
        });
    }
    public UUID reserveTransfer(UUID newOwner) {
        UUID operation = UUID.randomUUID();
        return reservations.atomic(() -> reservations.reserve(operation, newOwner, ownedSlots(newOwner),
                plugin.getPvcEntitlements().getOwnershipLimit(newOwner)) ? operation : null);
    }
    public void releaseTransfer(UUID operation) { if (operation != null) reservations.release(operation); }
    public CompletableFuture<TransferReservation> beginTransfer(Island island, SuperiorPlayer target) {
        if (!ready) return failed(new IllegalStateException("Island metadata is initializing or recovering"));
        return plugin.getPvcEntitlements().refreshOwnershipLimit(target.getUniqueId()).thenCompose(limit -> {
            if (closed || !ready) return failed(new IllegalStateException("Island service is stopping"));
            UUID token = UUID.randomUUID();
            synchronized (operationLock) {
                if (!isAvailable(island.getUniqueId())) return failed(new IllegalStateException("The island has an operation in progress"));
                transfers.put(island.getUniqueId(), token);
            }
            UUID operation = reservations.atomic(() -> {
                UUID id = UUID.randomUUID(); return reservations.reserve(id, target.getUniqueId(), ownedSlots(target.getUniqueId()), limit) ? id : null;
            });
            if (operation == null) transfers.remove(island.getUniqueId(), token);
            if (operation == null) return failed(new IllegalStateException("The new owner has no free island slot"));
            CreationReservation intent = new CreationReservation(operation, island.getOwner().getUniqueId(), island.getUniqueId());
            int previousRole = target.getPlayerRole(island).getId();
            boolean previousMember = island.isMember(target);
            TransferReservation transfer = new TransferReservation(intent, target.getUniqueId(), previousRole, previousMember);
            return insert("pvc_island_jobs", Arrays.asList("operation", "owner", "island", "state"),
                    Arrays.asList(operation.toString(), intent.owner.toString(), intent.island.toString(),
                            "TRANSFER_PENDING:" + target.getUniqueId() + ":" + previousRole + ":" + previousMember))
                    .thenApply(unused -> transfer).whenComplete((value, error) -> { if (error != null && !uncertain(error)) { releaseTransfer(operation); transfers.remove(island.getUniqueId(), token); } });
        });
    }
    public CompletableFuture<Void> finishTransfer(TransferReservation transfer, boolean success) {
        return state(transfer.intent, success ? "TRANSFER_DONE" : "CANCELLED")
                .thenRun(() -> {
                    if (DBSession.isCommitOutcomeUncertain()) throw new IllegalStateException("SQL outcome is uncertain; transfer remains fenced for restart");
                    releaseTransfer(transfer.intent.operation); transfers.remove(transfer.intent.island); departingOwners.remove(transfer.intent.island);
                });
    }
    public CompletableFuture<Boolean> canCommitTransfer(TransferReservation transfer) {
        return plugin.getPvcEntitlements().refreshOwnershipLimit(transfer.target).thenApply(limit ->
                reservations.atomic(() -> reservations.canCommit(transfer.intent.operation, ownedSlots(transfer.target), limit)));
    }
    public boolean verifyTransfer(UUID operation, UUID target) {
        return reservations.atomic(() -> reservations.canCommit(operation, ownedSlots(target), plugin.getPvcEntitlements().getVerifiedOwnershipLimit(target)));
    }
    private int ownedSlots(UUID owner) {
        Map<UUID, Island> properties = owned.get(owner);
        int result = properties == null ? 0 : properties.size();
        for (Map.Entry<UUID, UUID> departure : departingOwners.entrySet())
            if (owner.equals(departure.getValue()) && (properties == null || !properties.containsKey(departure.getKey()))) result++;
        return result;
    }
    public void renameAtomically(Island island, String name, Runnable apply) {
        String key = com.bgsoftware.superiorskyblock.island.IslandNames.getNameForLookup(name);
        reservations.atomic(() -> {
            Island existing = key.isEmpty() ? null : plugin.getGrid().getIsland(name);
            UUID reserved = key.isEmpty() ? null : reservedNames.get(key);
            if ((existing != null && !existing.getUniqueId().equals(island.getUniqueId())) ||
                    (reserved != null && !reserved.equals(island.getUniqueId())))
                throw new IllegalArgumentException("An island already owns or reserves that name");
            apply.run(); return null;
        });
    }
    public List<Island> getOwnedIslands(UUID owner) {
        Map<UUID, Island> values = owned.get(owner);
        if (values == null) return Collections.emptyList();
        List<Island> result = new ArrayList<>(values.values()); result.sort(Comparator.comparingLong(Island::getShortId));
        result.removeIf(island -> isCreationPending(island.getUniqueId()));
        return Collections.unmodifiableList(result);
    }
    public long getShortId(UUID uuid) { return ids.getOrDefault(uuid, 0L); }
    public Island getByShortId(long id) { UUID uuid = uuids.get(id); return uuid == null ? null : plugin.getGrid().getIslandByUUID(uuid); }
    public CompletableFuture<Long> ensureId(UUID uuid) {
        Long previous = ids.get(uuid);
        if (previous != null) return CompletableFuture.completedFuture(previous);
        long id;
        synchronized (ids) {
            previous = ids.get(uuid); if (previous != null) return CompletableFuture.completedFuture(previous);
            CompletableFuture<Long> pending = idWrites.get(uuid); if (pending != null) return pending;
            id = nextId.incrementAndGet(); if (id <= 0) throw new IllegalStateException("Island identifier exhausted");
            final long allocated = id;
            CompletableFuture<Long> write = insert("pvc_island_ids", Arrays.asList("uuid", "short_id"), Arrays.asList(uuid.toString(), id))
                    .thenApply(ignored -> { ids.put(uuid, allocated); uuids.put(allocated, uuid); return allocated; });
            idWrites.put(uuid, write);
            write.whenComplete((value, error) -> idWrites.remove(uuid, write));
            return write;
        }
    }
    public void select(SuperiorPlayer player, Island island) {
        synchronized (selectionLocks[(player.getUniqueId().hashCode() & Integer.MAX_VALUE) % selectionLocks.length]) {
        // Late callbacks read the current view; they cannot persist an older selection over a newer one.
        island = player.getSelectedIsland();
        if (island == null) selections.remove(player.getUniqueId()); else selections.put(player.getUniqueId(), island.getUniqueId());
        if (ready) insert("pvc_player_selection", Arrays.asList("player", "island"),
                Arrays.asList(player.getUniqueId().toString(), island == null ? "" : island.getUniqueId().toString()))
                .exceptionally(error -> { Log.error(error, "Cannot persist PVC island selection"); return null; });
        }
    }
    public Island resolve(String selector) {
        if (selector == null) return null;
        if (selector.startsWith("name:")) return plugin.getGrid().getIsland(selector.substring(5));
        String value = selector.startsWith("id:") ? selector.substring(3) : selector.startsWith("#") ? selector.substring(1) : selector;
        if (!selector.startsWith("uuid:")) {
            try { return getByShortId(Long.parseLong(value)); } catch (NumberFormatException ignored) { }
            if (selector.startsWith("id:") || selector.startsWith("#")) return null;
        }
        try { return plugin.getGrid().getIslandByUUID(UUID.fromString(selector.startsWith("uuid:") ? selector.substring(5) : selector)); }
        catch (IllegalArgumentException ignored) { return selector.startsWith("uuid:") ? null : plugin.getGrid().getIsland(selector); }
    }
    public CompletableFuture<CreationReservation> reserveCreation(SuperiorPlayer owner, UUID island) {
        return reserveCreation(owner, island, "");
    }
    public CompletableFuture<CreationReservation> reserveCreation(SuperiorPlayer owner, UUID island, String islandName) {
        if (!ready) return failed(new PvcCreationDeniedException(PvcCreationDeniedException.Reason.POLICY_UNAVAILABLE, 0));
        UUID operation = UUID.randomUUID();
        String nameKey = islandName.trim().isEmpty() ? "" : com.bgsoftware.superiorskyblock.island.IslandNames.getNameForLookup(
                com.bgsoftware.superiorskyblock.core.formatting.Formatters.STRIP_COLOR_FORMATTER.format(islandName));
        return plugin.getPvcEntitlements().refreshOwnershipLimit(owner.getUniqueId()).exceptionally(error -> {
            throw new PvcCreationDeniedException(PvcCreationDeniedException.Reason.POLICY_UNAVAILABLE, 0, error);
        }).thenCompose(limit -> {
            com.bgsoftware.superiorskyblock.api.hooks.WorldsProviderCapabilities.require(plugin.getProviders().getWorldsProvider(),
                    com.bgsoftware.superiorskyblock.core.threads.BukkitExecutor.isFolia(), limit > 1,
                    plugin.getPvcConfig().getSizeMode() != com.bgsoftware.superiorskyblock.pvc.PvcConfig.SizeMode.LEGACY);
            if (!reservations.atomic(() -> {
                if (closed || !ready) throw new PvcCreationDeniedException(PvcCreationDeniedException.Reason.POLICY_UNAVAILABLE, 0);
                if (ownedSlots(owner.getUniqueId()) > 0 || reservations.pending(owner.getUniqueId()) > 0)
                    for (com.bgsoftware.superiorskyblock.api.missions.Mission<?> mission : plugin.getMissions().getIslandMissions())
                        if (!mission.supportsIslandSubjects()) throw new IllegalStateException("Mission provider is not multi-island qualified: " + mission.getName());
                boolean legacyCreation = plugin.getGrid().getIslandCreationAlgorithm() instanceof com.bgsoftware.superiorskyblock.island.algorithm.DefaultIslandCreationAlgorithm;
                if (!legacyCreation && (com.bgsoftware.superiorskyblock.core.threads.BukkitExecutor.isFolia() || limit > 1 ||
                        plugin.getPvcConfig().getSizeMode() != com.bgsoftware.superiorskyblock.pvc.PvcConfig.SizeMode.LEGACY))
                    throw new IllegalStateException("This creation addon requires a durable multi-island/Folia adapter; use the default creation algorithm");
                if (!nameKey.isEmpty() && (reservedNames.containsKey(nameKey) || plugin.getGrid().getIsland(islandName) != null))
                    throw new PvcCreationDeniedException(PvcCreationDeniedException.Reason.NAME_OCCUPIED, limit);
                if ((long) ownedSlots(owner.getUniqueId()) + reservations.pending(owner.getUniqueId()) >= limit)
                    throw new PvcCreationDeniedException(PvcCreationDeniedException.Reason.QUOTA_REACHED, limit);
                if (!reservations.reserve(operation, owner.getUniqueId(), ownedSlots(owner.getUniqueId()), limit))
                    throw new PvcCreationDeniedException(PvcCreationDeniedException.Reason.QUEUE_FULL, limit);
                if (!nameKey.isEmpty()) { reservedNames.put(nameKey, island); creationNames.put(island, nameKey); }
                return true;
            }))
                return failed(new PvcCreationDeniedException(PvcCreationDeniedException.Reason.QUEUE_FULL, limit));
            CreationReservation reservation = new CreationReservation(operation, owner.getUniqueId(), island);
            creations.put(island, reservation);
            return ensureId(island).thenCompose(id -> insert("pvc_island_jobs", Arrays.asList("operation", "owner", "island", "state"),
                    Arrays.asList(operation.toString(), owner.getUniqueId().toString(), island.toString(), "RESERVED"))).thenApply(ignored -> reservation)
                    .whenComplete((value, error) -> { if (error != null && !uncertain(error)) { reservations.release(operation); creations.remove(island); releaseName(island); } });
        });
    }
    public CompletableFuture<Boolean> commitCreation(Island candidate) {
        if (closed) return failed(new IllegalStateException("Island service is stopping"));
        UUID island = candidate.getUniqueId();
        CreationReservation creation = creations.get(island);
        if (creation == null) return CompletableFuture.completedFuture(false);
        candidates.putIfAbsent(island, candidate);
        return plugin.getPvcEntitlements().refreshOwnershipLimit(creation.owner).thenCompose(limit -> {
            boolean allowed = reservations.atomic(() -> {
                Map<UUID, Island> properties = owned.get(creation.owner);
                int active = ownedSlots(creation.owner);
                if (properties != null && properties.containsKey(island)) active--;
                return reservations.canCommit(creation.operation, active, limit);
            });
            return state(creation, allowed ? "CREATING" : "DELETING").thenApply(ignored -> {
                if (DBSession.isCommitOutcomeUncertain()) throw new IllegalStateException("SQL commit outcome is uncertain; creation remains reserved");
                boolean committed = allowed && reservations.atomic(() -> {
                    Map<UUID, Island> properties = owned.get(creation.owner);
                    int active = ownedSlots(creation.owner);
                    if (properties != null && properties.containsKey(island)) active--;
                    if (!reservations.canCommit(creation.operation, active, plugin.getPvcEntitlements().getVerifiedOwnershipLimit(creation.owner))) return false;
                    register(candidate); reservations.convert(creation.operation); return true;
                });
                // A revoked candidate keeps its intent/slot until acknowledged cleanup completes.
                return committed;
            });
        });
    }
    public void cancelCreation(UUID island) {
        if (closed || DBSession.isCommitOutcomeUncertain()) return; // Preserve the acknowledged intent for next-start recovery.
        CreationReservation creation = creations.get(island);
        if (creation == null) return;
        Island candidate = candidates.get(island);
        if (candidate != null) { candidate.disbandIsland(); return; }
        state(creation, "CANCELLED").whenComplete((ignored, error) -> { if (error == null) { creations.remove(island, creation); reservations.release(creation.operation); releaseName(island); } });
    }
    public CompletableFuture<Void> finishCreation(UUID island) {
        if (closed || DBSession.isCommitOutcomeUncertain()) return failed(new IllegalStateException("Island service is stopping or SQL outcome is uncertain"));
        CreationReservation creation = creations.get(island);
        if (creation == null) return failed(new IllegalStateException("Missing island activation intent"));
        return state(creation, "ACTIVE").thenRun(() -> {
            if (DBSession.isCommitOutcomeUncertain()) throw new IllegalStateException("SQL outcome is uncertain; activation remains fenced for restart");
            creations.remove(island, creation); candidates.remove(island); reservations.release(creation.operation); releaseName(island);
        });
    }
    public CompletableFuture<Void> recordCandidate(Island island) {
        CreationReservation reservation = creations.get(island.getUniqueId());
        if (reservation == null) return failed(new IllegalStateException("Missing durable creation reservation"));
        candidates.put(island.getUniqueId(), island);
        island.setSchematicGenerate(plugin.getSettings().getWorlds().getDefaultWorldDimension());
        CompletableFuture<Void> persisted = acknowledgeForced(island,
                () -> com.bgsoftware.superiorskyblock.core.database.bridge.IslandsDatabaseBridge.insertIsland(island, null));
        return persisted.thenCompose(unused -> state(reservation, "CREATING"));
    }

    /** Keep a constructed but unpublished candidate reachable even if a creation event/kickoff throws. */
    public void trackCandidate(Island island) {
        if (!creations.containsKey(island.getUniqueId())) throw new IllegalStateException("Missing creation admission");
        candidates.put(island.getUniqueId(), island);
    }

    public CompletableFuture<Void> acknowledge(Island island, Runnable operation) {
        if (closed || DBSession.isCommitOutcomeUncertain()) return failed(new IllegalStateException("Island service is stopping or SQL outcome is uncertain; durable intent retained"));
        if (!(island.getDatabaseBridge() instanceof com.bgsoftware.superiorskyblock.core.database.sql.SQLDatabaseBridge))
            return failed(new IllegalStateException("The database provider has no acknowledged transaction contract"));
        return ((com.bgsoftware.superiorskyblock.core.database.sql.SQLDatabaseBridge) island.getDatabaseBridge()).executeAcknowledged(operation);
    }

    /** Persist a write-ahead snapshot without publishing a shared database-mode change. */
    public CompletableFuture<Void> acknowledgeForced(Island island, Runnable operation) {
        if (closed || DBSession.isCommitOutcomeUncertain()) return failed(new IllegalStateException("Island service is stopping or SQL outcome is uncertain; durable intent retained"));
        if (!(island.getDatabaseBridge() instanceof com.bgsoftware.superiorskyblock.core.database.sql.SQLDatabaseBridge))
            return failed(new IllegalStateException("The database provider has no acknowledged transaction contract"));
        return ((com.bgsoftware.superiorskyblock.core.database.sql.SQLDatabaseBridge) island.getDatabaseBridge()).executeAcknowledgedForced(operation);
    }

    /** A reset owns the cell until cleanup, optional paste and metadata are acknowledged. */
    public CompletableFuture<Void> reset(com.bgsoftware.superiorskyblock.island.SIsland island, String action,
                                         java.util.function.Supplier<CompletableFuture<Void>> work) {
        validateResetAction(action);
        synchronized (operationLock) {
            if (closed || !ready || DBSession.isCommitOutcomeUncertain()) return failed(new IllegalStateException("Island service is stopping, recovering or SQL outcome is uncertain"));
            if (!isAvailable(island.getUniqueId())) return failed(new IllegalStateException("The island has an operation in progress"));
            advanceWorldGeneration(island.getUniqueId());
            CreationReservation intent = new CreationReservation(UUID.randomUUID(), island.getOwner().getUniqueId(), island.getUniqueId());
            CompletableFuture<Void> future = insert("pvc_island_jobs", Arrays.asList("operation", "owner", "island", "state"),
                    Arrays.asList(intent.operation.toString(), intent.owner.toString(), intent.island.toString(), action))
                    .thenCompose(unused -> closed ? failed(new IllegalStateException("Island service is stopping")) : work.get())
                    .thenCompose(unused -> closed ? failed(new IllegalStateException("Island service is stopping")) : state(intent, action.startsWith("PASTE_DIM:") ? "PASTE_DONE" : "RESET_DONE"));
            resets.put(island.getUniqueId(), future);
            future.whenComplete((unused, error) -> { if (error == null) resets.remove(island.getUniqueId(), future);
                else Log.error(error, "PVC reset is incomplete; the cell remains reserved until restart reconciliation"); });
            return future;
        }
    }

    /** First generation of a secondary dimension: the caller acknowledges world and final metadata before completing work. */
    public CompletableFuture<Void> dimensionGeneration(com.bgsoftware.superiorskyblock.island.SIsland island,
            com.bgsoftware.superiorskyblock.api.world.Dimension dimension,
            java.util.function.Supplier<CompletableFuture<Void>> work) {
        return reset(island, "PASTE_DIM:" + dimension.getName(), work);
    }

    /** Shutdown-safe intent only: actual physical deletion occurs after next-start reconciliation. */
    public CompletableFuture<Void> deferDeletion(Island island) {
        if (closed) return failed(new IllegalStateException("Journal deferred deletion before closing the island service"));
        return insert("pvc_deferred_deletes", Arrays.asList("island", "owner", "state"),
                Arrays.asList(island.getUniqueId().toString(), island.getOwner().getUniqueId().toString(), "PENDING"));
    }

    private CompletableFuture<Void> resumeReset(com.bgsoftware.superiorskyblock.island.SIsland island,
                                                CreationReservation intent, String action) {
        String[] parts = action.split(":");
        recovering.add(island.getUniqueId());
        CompletableFuture<Void> future;
        if ("RESET_WORLD".equals(parts[0]) && parts.length == 2)
            future = island.resetWorldInternalAsync(com.bgsoftware.superiorskyblock.api.world.Dimension.getByName(parts[1]));
        else if ("PASTE_DIM".equals(parts[0]) && parts.length == 2) {
            com.bgsoftware.superiorskyblock.api.world.Dimension dimension = com.bgsoftware.superiorskyblock.api.world.Dimension.getByName(parts[1]);
            // An interrupted first paste is cleared without replaying bonus/reward/event side effects.
            // The next real portal entry may retry it from the original schematic.
            future = island.resetChunksAsync(dimension, 0).thenCompose(unused -> acknowledgeForced(island,
                    () -> island.setSchematicGenerate(dimension, false)));
        }
        else if ("RESET_CHUNKS".equals(parts[0]) && parts.length == 3)
            future = "ALL".equals(parts[1]) ? island.resetChunksAsync(Integer.parseInt(parts[2])) :
                    island.resetChunksAsync(com.bgsoftware.superiorskyblock.api.world.Dimension.getByName(parts[1]), Integer.parseInt(parts[2]));
        else return failed(new IllegalStateException("Unknown durable reset intent: " + action));
        CompletableFuture<Void> acknowledged = future.thenCompose(unused -> state(intent, action.startsWith("PASTE_DIM:") ? "PASTE_DONE" : "RESET_DONE"));
        resets.put(island.getUniqueId(), acknowledged);
        acknowledged.whenComplete((unused, error) -> { if (error == null) { resets.remove(island.getUniqueId(), acknowledged); recovering.remove(island.getUniqueId()); } });
        return acknowledged;
    }
    private static void validateResetAction(String action) {
        String[] parts = action.split(":");
        if (("RESET_WORLD".equals(parts[0]) || "PASTE_DIM".equals(parts[0])) && parts.length == 2) {
            com.bgsoftware.superiorskyblock.api.world.Dimension.getByName(parts[1]); return;
        }
        if ("RESET_CHUNKS".equals(parts[0]) && parts.length == 3) {
            if (!"ALL".equals(parts[1])) com.bgsoftware.superiorskyblock.api.world.Dimension.getByName(parts[1]);
            Integer.parseInt(parts[2]); return;
        }
        throw new IllegalStateException("Invalid durable reset intent: " + action);
    }

    public CompletableFuture<Void> delete(Island island, java.util.function.Supplier<CompletableFuture<Void>> cleanup, Runnable finish) {
        return delete(island, cleanup, finish, false, () -> CompletableFuture.completedFuture(null));
    }

    public CompletableFuture<Void> delete(Island island, java.util.function.Supplier<CompletableFuture<Void>> cleanup, Runnable finish,
                                          boolean refundRequested, java.util.function.Supplier<CompletableFuture<Void>> refund) {
        final UUID identity = island.getUniqueId();
        final CreationReservation creation;
        final CreationReservation operation;
        final CompletableFuture<Void> result = new CompletableFuture<>();
        final AtomicBoolean intentStarted = new AtomicBoolean();
        final AtomicBoolean refundRejectedWithoutPayout = new AtomicBoolean();
        synchronized (operationLock) {
            CompletableFuture<Void> existing = deletions.get(identity); if (existing != null) return existing;
            if (closed) return failed(new IllegalStateException("Island service is stopping; no cleanup was started"));
            if (resets.containsKey(identity) || transfers.containsKey(identity))
                return failed(new IllegalStateException("The island has another operation in progress"));
            creation = creations.get(identity);
            operation = creation != null ? creation : new CreationReservation(UUID.randomUUID(), island.getOwner().getUniqueId(), identity);
            // The metadata-only placeholder rejects new operations before bank drain, without a recoverable delete intent.
            deletions.put(identity, result);
        }
        CompletableFuture<Void> quiet;
        try { quiet = island.getIslandBank().quiesceTransactions(); }
        catch (Throwable failure) { quiet = failed(failure); }
        quiet.thenCompose(unused -> {
            if (closed || DBSession.isCommitOutcomeUncertain()) return PvcIslandService.<Void>failed(new IllegalStateException("Island service is stopping or SQL outcome is uncertain"));
            advanceWorldGeneration(identity);
            // Read refund eligibility only after all admitted bank changes have settled.
            boolean payRefund = refundRequested && creation == null && island.getIslandBank().getBalance().signum() > 0;
            String phase = payRefund ? "DELETE_REFUND_PENDING" : "DELETING";
            intentStarted.set(true);
            CompletableFuture<Void> intent = creation != null ? state(operation, phase) :
                    insert("pvc_island_jobs", Arrays.asList("operation", "owner", "island", "state"),
                            Arrays.asList(operation.operation.toString(), operation.owner.toString(), operation.island.toString(), phase));
            return intent.thenCompose(value -> payRefund ? settleDisbandRefund(operation, refund, refundRejectedWithoutPayout) : CompletableFuture.completedFuture(null))
                    .thenCompose(value -> closed ? failed(new IllegalStateException("Island service is stopping")) : cleanup.get())
                    .thenCompose(value -> acknowledge(island, finish)).thenCompose(value -> state(operation, "DELETED"));
        }).whenComplete((unused, error) -> {
            if (error == null && !DBSession.isCommitOutcomeUncertain()) {
                creations.remove(identity); candidates.remove(identity);
                reservations.release(operation.operation); deletions.remove(identity, result); releaseName(identity); recovering.remove(identity);
                result.complete(null);
            } else {
                if (!intentStarted.get() || refundRejectedWithoutPayout.get() && !uncertain(error)) {
                    deletions.remove(identity, result);
                    island.getIslandBank().resumeTransactions();
                } else Log.error(error, "PVC island deletion is incomplete; metadata/intent remain fenced and an uncertain refund must not be retried");
                result.completeExceptionally(error == null ? new IllegalStateException("SQL deletion outcome is uncertain") : error);
            }
        });
        return result;
    }

    private CompletableFuture<Void> settleDisbandRefund(CreationReservation operation,
            java.util.function.Supplier<CompletableFuture<Void>> refund, AtomicBoolean rejectedWithoutPayout) {
        CompletableFuture<Void> paid;
        try { paid = closed ? failed(new DisbandRefundFailure("Server stopped before refund admission", true)) : refund.get(); }
        catch (Throwable failure) { paid = failed(failure); }
        return paid.handle((unused, error) -> {
            if (error == null) return state(operation, "DELETING");
            Throwable cause = error;
            while (cause instanceof java.util.concurrent.CompletionException && cause.getCause() != null) cause = cause.getCause();
            final Throwable outcome = cause;
            if (outcome instanceof DisbandRefundFailure && ((DisbandRefundFailure) outcome).hasNoPayout() && !uncertain(outcome)) {
                return state(operation, "CANCELLED").thenCompose(value -> {
                    rejectedWithoutPayout.set(true); return PvcIslandService.<Void>failed(outcome);
                });
            }
            // A crash/throw/partial acknowledgement cannot be compensated or replayed automatically.
            // If persisting HOLD fails, the already-acknowledged PENDING phase also blocks restart recovery.
            return state(operation, "DELETE_REFUND_HOLD").handle((value, journalFailure) -> {
                if (journalFailure != null) outcome.addSuppressed(journalFailure);
                return PvcIslandService.<Void>failed(outcome);
            }).thenCompose(value -> value);
        }).thenCompose(value -> value);
    }
    private CompletableFuture<Void> state(CreationReservation reservation, String state) {
        UpdateSQLDatabaseTransaction update = new UpdateSQLDatabaseTransaction("pvc_island_jobs", Collections.singletonList("state"), Collections.singletonList("operation"));
        update.bindObjects(Arrays.asList(state, reservation.operation.toString())); return DBSession.execute(update);
    }
    private static CompletableFuture<Void> insert(String table, List<String> columns, List<Object> values) {
        InsertSQLDatabaseTransaction transaction = new InsertSQLDatabaseTransaction(table, columns); transaction.bindObjects(values);
        return DBSession.execute(transaction);
    }
    private static <T> CompletableFuture<T> failed(Throwable error) { CompletableFuture<T> future = new CompletableFuture<>(); future.completeExceptionally(error); return future; }
    private static boolean uncertain(Throwable error) { return DBSession.isCommitOutcomeUncertain() || DBSession.hasUncertainCommit(error); }
    private void releaseName(UUID island) { String name = creationNames.remove(island); if (name != null) reservedNames.remove(name, island); }
    @Override public void close() { closed = true; ready = false; }
    public static final class CreationReservation {
        public final UUID operation, owner, island;
        private CreationReservation(UUID operation, UUID owner, UUID island) { this.operation = operation; this.owner = owner; this.island = island; }
    }
    public static final class TransferReservation {
        public final CreationReservation intent;
        public final UUID target;
        public final int previousRole;
        public final boolean previousMember;
        private TransferReservation(CreationReservation intent, UUID target, int previousRole, boolean previousMember) {
            this.intent = intent; this.target = target; this.previousRole = previousRole; this.previousMember = previousMember;
        }
    }
}

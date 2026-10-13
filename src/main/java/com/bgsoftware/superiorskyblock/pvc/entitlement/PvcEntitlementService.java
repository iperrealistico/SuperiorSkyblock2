package com.bgsoftware.superiorskyblock.pvc.entitlement;

import com.bgsoftware.superiorskyblock.SuperiorSkyblockPlugin;
import com.bgsoftware.superiorskyblock.api.island.Island;
import com.bgsoftware.superiorskyblock.api.upgrades.Upgrade;
import com.bgsoftware.superiorskyblock.api.upgrades.UpgradeLevel;
import com.bgsoftware.superiorskyblock.core.threads.BukkitExecutor;
import com.bgsoftware.superiorskyblock.island.SIsland;
import com.bgsoftware.superiorskyblock.pvc.PvcConfig;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.permissions.PermissionAttachmentInfo;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/** Owner-scoped derived policy. LP events accelerate convergence; bounded periodic refresh repairs missed events. */
public final class PvcEntitlementService implements AutoCloseable {
    private final SuperiorSkyblockPlugin plugin;
    private volatile PvcConfig config;
    private final PvcOwnerCache cache = new PvcOwnerCache();
    private final PvcPolicyStore store;
    private final ScheduledExecutorService coordinator;
    private final ThreadPoolExecutor storage;
    private final ArrayBlockingQueue<Request> queue;
    private final ConcurrentHashMap<UUID, Request> requests = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, Long> ownerEpochs = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, EntitlementSnapshot> unsaved = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, GeometryCursor> geometry = new ConcurrentHashMap<>();
    private final ConcurrentLinkedQueue<UUID> geometryOrder = new ConcurrentLinkedQueue<>();
    private final java.util.concurrent.Semaphore geometryAdmission;
    private final AtomicInteger inFlight = new AtomicInteger();
    private final java.util.Set<UUID> resolvingOwners = ConcurrentHashMap.newKeySet();
    private final AtomicLong revisions = new AtomicLong();
    private final AtomicLong allEpoch = new AtomicLong();
    private final AtomicBoolean saving = new AtomicBoolean();
    private final Object[] ownerMonitors = new Object[64];
    private volatile boolean closed;
    private boolean started;
    private int refreshOffset;

    public PvcEntitlementService(SuperiorSkyblockPlugin plugin, PvcConfig config) throws IOException {
        this.plugin = plugin;
        for (int i = 0; i < ownerMonitors.length; ++i) ownerMonitors[i] = new Object();
        this.config = config;
        this.store = new PvcPolicyStore(plugin.getDataFolder().toPath().resolve("pvc/owner-policy.properties"), config.getHistoryCapacity());
        this.queue = new ArrayBlockingQueue<>(config.getQueueCapacity());
        this.geometryAdmission = new java.util.concurrent.Semaphore(config.getQueueCapacity());
        java.util.concurrent.ScheduledThreadPoolExecutor timer = new java.util.concurrent.ScheduledThreadPoolExecutor(1, runnable -> {
            Thread thread = new Thread(runnable, "Superior-PVC-policy"); thread.setDaemon(true); return thread;
        });
        timer.setRemoveOnCancelPolicy(true);
        this.coordinator = timer;
        this.storage = new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<Runnable>(128), runnable -> {
                    Thread thread = new Thread(runnable, "Superior-PVC-policy-storage"); thread.setDaemon(true); return thread;
                }, new ThreadPoolExecutor.AbortPolicy());
    }

    public synchronized void start() {
        if (started || closed) return;
        validateMode(config);
        started = true;
        coordinator.scheduleWithFixedDelay(this::pulseSafely, 0L, 250L, TimeUnit.MILLISECONDS);
        coordinator.scheduleWithFixedDelay(this::saveSnapshots, 5L, 5L, TimeUnit.SECONDS);
    }

    public synchronized void reload(PvcConfig candidate) {
        validateReload(candidate);
        validateMode(candidate);
        config = candidate;
        cache.trim(config.getCacheCapacity());
        invalidateAll();
    }

    /** Call before mutating upstream managers/listeners when accepting a reload candidate. */
    public synchronized void validateReload(PvcConfig candidate) {
        if (candidate.getSizeMode() != config.getSizeMode())
            throw new IllegalArgumentException("Changing PVC size mode requires a normal restart");
        // Queue/history capacities are allocation boundaries and deliberately require a normal restart.
        if (candidate.getQueueCapacity() != config.getQueueCapacity() || candidate.getHistoryCapacity() != config.getHistoryCapacity())
            throw new IllegalArgumentException("Changing entitlement queue/history capacity requires a normal restart");
        if (candidate.getChunkLoadConcurrency() != config.getChunkLoadConcurrency() ||
                candidate.getChunkLoadQueueLimit() != config.getChunkLoadQueueLimit() ||
                candidate.getChunkLoadTimeoutSeconds() != config.getChunkLoadTimeoutSeconds())
            throw new IllegalArgumentException("Changing chunk-load admission settings requires a normal restart");
        if (candidate.getDragonArenaChunkBudget() != config.getDragonArenaChunkBudget() ||
                candidate.getDragonArenaQueueLimit() != config.getDragonArenaQueueLimit() ||
                candidate.getDragonArenaActivationTimeoutSeconds() != config.getDragonArenaActivationTimeoutSeconds())
            throw new IllegalArgumentException("Changing dragon-arena admission settings requires a normal restart");
    }

    private void validateMode(PvcConfig settings) {
        if (settings.getSizeMode() != PvcConfig.SizeMode.PERMISSION_AUTOMATIC) return;
        if (plugin.getSettings().isBuildOutsideIsland())
            throw new IllegalArgumentException("PERMISSION_AUTOMATIC requires build-outside-island: false");
        for (Upgrade upgrade : plugin.getUpgrades().getUpgrades()) {
            for (int level = 1; level <= upgrade.getMaxUpgradeLevel(); ++level) {
                UpgradeLevel value = upgrade.getUpgradeLevel(level);
                if (value != null && value.hasBorderSize())
                    throw new IllegalArgumentException("Disable border-size upgrade '" + upgrade.getName() + "' before enabling PERMISSION_AUTOMATIC");
            }
        }
    }

    public boolean isAutomaticSize() { return config.getSizeMode() == PvcConfig.SizeMode.PERMISSION_AUTOMATIC; }

    public EntitlementSnapshot getSnapshot(UUID owner) {
        long now = System.currentTimeMillis();
        PvcOwnerCache.Entry entry = cache.get(owner, now);
        if (entry != null) {
            EntitlementSnapshot snapshot = entry.snapshot;
            if (snapshot.isAuthoritative() && !currentEpoch(owner, snapshot)) {
                request(owner);
                return snapshot.lastKnown();
            }
            return snapshot;
        }
        EntitlementSnapshot saved = store.getLastKnown(owner);
        EntitlementSnapshot value = saved == null ? new EntitlementSnapshot(1, defaultRadius(), 0L, 0L, false) : saved;
        cache.put(owner, value, now, config.getCacheCapacity());
        request(owner);
        return value;
    }

    public int getOwnershipLimit(UUID owner) { return getSnapshot(owner).getOwnershipLimit(); }

    /**
     * Metadata commit recheck after an asynchronous journal acknowledgement.
     * A stale/evicted/invalidated value never authorizes admission; callers retry
     * the authoritative query or retain their reservation for safe cleanup.
     */
    public int getVerifiedOwnershipLimit(UUID owner) {
        if (closed || owner == null) throw new IllegalStateException("Owner policy is unavailable for commit");
        synchronized (ownerMonitor(owner)) {
            PvcOwnerCache.Entry entry = cache.peek(owner);
            EntitlementSnapshot snapshot = entry == null ? null : entry.snapshot;
            if (snapshot == null || !snapshot.isFresh(System.currentTimeMillis(), config.getRefreshMillis()) ||
                    !currentEpoch(owner, snapshot))
                throw new IllegalStateException("Owner permissions changed or expired before commit; retry");
            return snapshot.getOwnershipLimit();
        }
    }

    /** Admission must use this asynchronous authoritative result, never an expired permissive snapshot. */
    public CompletableFuture<Integer> refreshOwnershipLimit(UUID owner) {
        return request(owner).thenApply(snapshot -> {
            synchronized (ownerMonitor(owner)) {
                if (!snapshot.isAuthoritative() || !currentEpoch(owner, snapshot))
                    throw new IllegalStateException("Owner permissions changed before admission; retry");
                return snapshot.getOwnershipLimit();
            }
        });
    }

    public CompletableFuture<EntitlementSnapshot> refresh(UUID owner) { return request(owner); }

    public int getEffectiveRadius(Island island, int legacyRadius) {
        if (!isAutomaticSize() || island.getOwner() == null) return legacyRadius;
        UUID owner = island.getOwner().getUniqueId();
        Integer override = store.getOverride(owner);
        if (override != null) return Math.min(override, gridCap());
        int legacyOverride = island.getIslandSizeRaw();
        if (legacyOverride > 0) return Math.min(legacyOverride, gridCap());
        return Math.min(getSnapshot(owner).getRadius(), gridCap());
    }

    /** Owner-wide arithmetic never accidentally chooses an arbitrary legacy island exception. */
    public int getOwnerPolicyRadius(UUID owner) {
        Integer override = store.getOverride(owner);
        return Math.min(override == null ? getSnapshot(owner).getRadius() : override, gridCap());
    }

    public Integer getOwnerOverride(UUID owner) { return store.getOverride(owner); }

    public CompletableFuture<Void> setOwnerOverride(UUID owner, int radius) {
        if (radius < 1 || radius > gridCap()) return failed(new IllegalArgumentException("Radius is outside the saved grid cap"));
        return writeOverride(owner, radius);
    }

    public CompletableFuture<Void> clearOwnerOverride(UUID owner) { return writeOverride(owner, null); }

    /** Add on the serialized durable lane, rather than deriving a racy value before enqueueing. */
    public CompletableFuture<Void> addOwnerOverride(UUID owner, int delta) {
        CompletableFuture<?> ready = store.getOverride(owner) == null ? refresh(owner) : CompletableFuture.completedFuture(null);
        return ready.thenCompose(ignored -> {
            CompletableFuture<Void> result = new CompletableFuture<>();
            if (closed) return failed(new RejectedExecutionException("Policy service is stopped"));
            try {
                storage.execute(() -> {
                    try {
                        int fallback = 1;
                        if (store.getOverride(owner) == null) {
                            synchronized (ownerMonitor(owner)) {
                                PvcOwnerCache.Entry entry = cache.peek(owner);
                                EntitlementSnapshot snapshot = entry == null ? null : entry.snapshot;
                                if (snapshot == null || !snapshot.isFresh(System.currentTimeMillis(), config.getRefreshMillis()) ||
                                        !currentEpoch(owner, snapshot))
                                    throw new IllegalStateException("Owner permissions changed before additive override; retry");
                                fallback = snapshot.getRadius();
                            }
                        }
                        store.addOverride(owner, fallback, delta, gridCap());
                        markGeometryChanged(owner);
                        result.complete(null);
                    } catch (Throwable error) { result.completeExceptionally(error); }
                });
            } catch (RejectedExecutionException error) { result.completeExceptionally(error); }
            return result;
        });
    }

    private CompletableFuture<Void> writeOverride(UUID owner, Integer radius) {
        CompletableFuture<Void> future = new CompletableFuture<>();
        if (closed) return failed(new RejectedExecutionException("Policy service is stopped"));
        try {
            storage.execute(() -> {
                try {
                    store.setOverride(owner, radius);
                    markGeometryChanged(owner);
                    if (radius == null) invalidate(owner);
                    future.complete(null);
                } catch (Throwable error) { future.completeExceptionally(error); }
            });
        } catch (RejectedExecutionException error) { future.completeExceptionally(error); }
        return future;
    }

    public void invalidate(UUID owner) {
        if (closed || owner == null) return;
        synchronized (ownerMonitor(owner)) {
            PvcOwnerCache.Entry entry = cache.peek(owner);
            if (entry != null) entry.snapshot = entry.snapshot.lastKnown();
            if (entry != null || requests.containsKey(owner)) {
                ownerEpochs.compute(owner, (uuid, epoch) -> epoch == null ? 1L : epoch + 1L);
                request(owner);
            }
        }
    }

    public void invalidateAll() {
        allEpoch.incrementAndGet();
        for (Map.Entry<UUID, PvcOwnerCache.Entry> entry : cache.entries()) {
            synchronized (ownerMonitor(entry.getKey())) {
                entry.getValue().snapshot = entry.getValue().snapshot.lastKnown();
            }
            request(entry.getKey());
        }
    }

    private CompletableFuture<EntitlementSnapshot> request(UUID owner) {
        if (owner == null) return failed(new IllegalArgumentException("Owner UUID is required"));
        if (closed) return failed(new RejectedExecutionException("Policy service is stopped"));
        Request pending = requests.get(owner);
        if (pending != null) return pending.future;
        Request created = new Request(owner);
        pending = requests.putIfAbsent(owner, created);
        if (pending != null) return pending.future;
        created.future.whenComplete((value, error) -> {
            created.terminal.set(true);
            requests.remove(owner, created);
        });
        if (!queue.offer(created)) {
            requests.remove(owner, created);
            created.future.completeExceptionally(new RejectedExecutionException("Policy refresh queue is full"));
        } else {
            try {
                java.util.concurrent.ScheduledFuture<?> deadline = coordinator.schedule(() -> {
                    if (created.terminal.compareAndSet(false, true))
                        created.future.completeExceptionally(new TimeoutException("Owner policy admission timed out"));
                }, config.getTimeoutSeconds() * 2L, TimeUnit.SECONDS);
                created.future.whenComplete((value, error) -> deadline.cancel(false));
            } catch (RejectedExecutionException error) {
                requests.remove(owner, created);
                created.future.completeExceptionally(error);
            }
        }
        return created.future;
    }

    private void pulseSafely() {
        if (closed) return;
        try {
            int budget = config.getRefreshBatch();
            long now = System.currentTimeMillis();
            List<Map.Entry<UUID, PvcOwnerCache.Entry>> entries = cache.entries();
            int size = entries.size();
            int index = size == 0 ? 0 : refreshOffset % size;
            for (int scanned = 0; scanned < size && budget > 0; ++scanned) {
                Map.Entry<UUID, PvcOwnerCache.Entry> entry = entries.get(index);
                index = (index + 1) % size;
                if (now - entry.getValue().lastAccessMillis <= config.getRefreshMillis() * 2 &&
                        now >= entry.getValue().retryAfterMillis && !entry.getValue().snapshot.isFresh(now, config.getRefreshMillis())) {
                    request(entry.getKey());
                    --budget;
                }
                if (entry.getValue().geometryDirty) requestGeometry(entry.getKey());
            }
            refreshOffset = index;
            pump();
            refreshGeometry();
            // Epoch entries are invalidation tokens, not permanent per-user history.
            ownerEpochs.keySet().removeIf(owner -> cache.peek(owner) == null && !requests.containsKey(owner));
        } catch (Throwable error) { plugin.getLogger().warning("PVC policy coordinator failed: " + error.getClass().getSimpleName()); }
    }

    private void pump() {
        int budget = config.getRefreshBatch();
        while (!closed && budget-- > 0 && inFlight.get() < config.getMaxInFlight()) {
            Request request = queue.poll();
            if (request == null) return;
            UUID owner = request.owner;
            if (requests.get(owner) != request || request.started.get()) continue;
            if (!resolvingOwners.add(owner)) { queue.offer(request); continue; }
            if (!request.started.compareAndSet(false, true)) { resolvingOwners.remove(owner); continue; }
            inFlight.incrementAndGet();
            long epoch = ownerEpochs.getOrDefault(owner, 0L);
            long globalEpoch = allEpoch.get();
            CompletableFuture<OwnerPermissionData> permissions;
            try { permissions = resolve(owner); }
            catch (Throwable error) { permissions = failed(error); }
            CompletableFuture<OwnerPermissionData> timed = PvcProviderDeadline.apply(permissions, coordinator,
                    config.getTimeoutSeconds(), TimeUnit.SECONDS, () -> {
                // A timeout releases the caller, but an unresolved provider retains its physical admission slot.
                // This prevents a broken provider from accumulating unlimited abandoned I/O requests.
                inFlight.decrementAndGet();
                resolvingOwners.remove(owner);
            });
            timed.whenComplete((data, error) -> {
                try {
                    // A queued caller can expire before a later provider deadline.
                    // Its physical source still owns a slot, but may not publish
                    // advantages or invalidate a newer request's snapshot.
                    if (requests.get(owner) != request || !request.terminal.compareAndSet(false, true)) return;
                    if (closed) { request.future.completeExceptionally(new RejectedExecutionException("Policy service stopped")); return; }
                    if (epoch != ownerEpochs.getOrDefault(owner, 0L) || globalEpoch != allEpoch.get()) {
                        request.future.completeExceptionally(new IllegalStateException("Owner policy changed during resolution; retry"));
                        return;
                    }
                    if (error != null || data == null || !data.isKnown()) {
                        PvcOwnerCache.Entry old = cache.peek(owner);
                        if (old != null) {
                            old.snapshot = old.snapshot.lastKnown();
                            old.failures = Math.min(8, old.failures + 1);
                            old.retryAfterMillis = System.currentTimeMillis() + Math.min(300000L, 1000L << old.failures);
                        }
                        request.future.completeExceptionally(error == null ? new IllegalStateException("Owner permissions are unavailable") : error);
                        return;
                    }
                    EntitlementPolicy.Values values = EntitlementPolicy.resolve(data.getPermissions(), defaultRadius(), gridCap());
                    EntitlementSnapshot snapshot = new EntitlementSnapshot(values.getOwnershipLimit(), values.getRadius(),
                            System.currentTimeMillis(), revisions.incrementAndGet(), true, epoch, globalEpoch);
                    boolean geometryChanged;
                    synchronized (ownerMonitor(owner)) {
                        if (!currentEpoch(owner, snapshot)) {
                            request.future.completeExceptionally(new IllegalStateException("Owner policy changed during publication; retry"));
                            return;
                        }
                        PvcOwnerCache.Entry previous = cache.peek(owner);
                        geometryChanged = previous == null || !previous.snapshot.isAuthoritative() ||
                                previous.snapshot.getRadius() != snapshot.getRadius();
                        cache.put(owner, snapshot, System.currentTimeMillis(), config.getCacheCapacity());
                        PvcOwnerCache.Entry published = cache.peek(owner);
                        if (published != null) { published.failures = 0; published.retryAfterMillis = 0L; }
                    }
                    if (unsaved.size() < config.getHistoryCapacity() || unsaved.containsKey(owner)) unsaved.put(owner, snapshot);
                    if (geometryChanged) markGeometryChanged(owner);
                    request.future.complete(snapshot);
                } catch (Throwable failure) {
                    request.future.completeExceptionally(failure);
                } finally {
                    requests.remove(owner, request);
                }
            });
        }
    }

    private CompletableFuture<OwnerPermissionData> resolve(UUID owner) {
        Object provider = plugin.getProviders().getPermissionsProvider();
        if (provider instanceof OwnerPermissionProvider)
            return ((OwnerPermissionProvider) provider).resolveOwnerPermissions(owner,
                    plugin.getSettings().getWorlds().getDefaultWorldName(), gridCap(), config.getRadiusCandidates(), config.getMaxNodes());
        // Bukkit has no authoritative offline permission API. This fallback is expressly online-only.
        CompletableFuture<OwnerPermissionData> result = new CompletableFuture<>();
        BukkitExecutor.global(() -> {
            Player player = Bukkit.getPlayer(owner);
            if (player == null) { result.complete(OwnerPermissionData.unknown()); return; }
            BukkitExecutor.entity(player, () -> {
                try {
                    Map<String, Boolean> grants = new HashMap<>();
                    for (PermissionAttachmentInfo permission : player.getEffectivePermissions()) {
                        String node = permission.getPermission().toLowerCase(Locale.ROOT);
                        // Null attachments include Bukkit defaults/OP. They cannot grant fork ownership or radius.
                        if (permission.getAttachment() == null) continue;
                        if (node.startsWith(EntitlementPolicy.OWNERSHIP_PREFIX) || node.startsWith(EntitlementPolicy.RADIUS_PREFIX)) {
                            if (grants.size() >= config.getMaxNodes()) { result.complete(OwnerPermissionData.unknown()); return; }
                            grants.put(node, permission.getValue());
                        }
                    }
                    result.complete(OwnerPermissionData.known(grants));
                } catch (Throwable error) { result.completeExceptionally(error); }
            }, 0L, () -> result.complete(OwnerPermissionData.unknown()));
        });
        return result;
    }

    private void markGeometryChanged(UUID owner) {
        if (!isAutomaticSize() || closed) return;
        // A background reconciliation must not make an offline owner look active forever.
        if (cache.peek(owner) == null) getSnapshot(owner);
        PvcOwnerCache.Entry entry = cache.peek(owner);
        if (entry != null) {
            entry.geometryDirty = true;
            entry.geometryRevision = revisions.incrementAndGet();
        }
        requestGeometry(owner);
    }

    private void requestGeometry(UUID owner) {
        if (!isAutomaticSize() || closed) return;
        PvcOwnerCache.Entry entry = cache.peek(owner);
        if (entry != null) entry.geometryDirty = true;
        if (geometry.containsKey(owner) || !geometryAdmission.tryAcquire()) return;
        boolean admitted = false;
        try {
            long revision = entry == null ? 0L : entry.geometryRevision;
            GeometryCursor cursor = new GeometryCursor(revision, new ArrayList<>(plugin.getGrid().getOwnedIslands(owner)).iterator());
            if (geometry.putIfAbsent(owner, cursor) == null) {
                admitted = true;
                geometryOrder.offer(owner);
            }
        } finally { if (!admitted) geometryAdmission.release(); }
    }

    private void refreshGeometry() {
        int budget = config.getRefreshBatch();
        int owners = geometryOrder.size();
        while (budget > 0 && owners-- > 0) {
            UUID ownerId = geometryOrder.poll();
            if (ownerId == null) break;
            GeometryCursor cursor = geometry.get(ownerId);
            if (cursor == null) continue;
            Iterator<Island> iterator = cursor.iterator;
            int ownerBudget = Math.min(8, budget);
            try {
                while (ownerBudget-- > 0 && iterator.hasNext()) {
                    Island island = iterator.next();
                    --budget;
                    if (island instanceof SIsland && island.getOwner() != null && island.getOwner().getUniqueId().equals(ownerId))
                        ((SIsland) island).refreshEffectiveSize();
                }
            } catch (Throwable error) {
                plugin.getLogger().warning("PVC geometry feedback failed: " + error.getClass().getSimpleName());
            } finally {
                if (!iterator.hasNext()) {
                    if (geometry.remove(ownerId, cursor)) {
                        geometryAdmission.release();
                        PvcOwnerCache.Entry owner = cache.peek(ownerId);
                        if (owner != null && owner.geometryRevision == cursor.revision) owner.geometryDirty = false;
                    }
                } else if (geometry.get(ownerId) == cursor) geometryOrder.offer(ownerId);
            }
        }
    }

    private void saveSnapshots() {
        if (unsaved.isEmpty() || !saving.compareAndSet(false, true)) return;
        Map<UUID, EntitlementSnapshot> batch = new LinkedHashMap<>(unsaved);
        try {
            storage.execute(() -> {
                try {
                    store.remember(batch);
                    for (Map.Entry<UUID, EntitlementSnapshot> entry : batch.entrySet()) unsaved.remove(entry.getKey(), entry.getValue());
                } catch (IOException error) {
                    plugin.getLogger().warning("Could not persist last-known PVC policy cache; owner overrides are retained");
                } finally { saving.set(false); }
            });
        } catch (RejectedExecutionException error) { saving.set(false); }
    }

    public String describe(UUID owner) {
        EntitlementSnapshot snapshot = getSnapshot(owner);
        Integer override = store.getOverride(owner);
        int exceptions = 0;
        for (Island island : plugin.getGrid().getOwnedIslands(owner)) if (island.getIslandSizeRaw() > 0) ++exceptions;
        return "mode=" + config.getSizeMode() + ", quota=" +
                (snapshot.getOwnershipLimit() == Integer.MAX_VALUE ? "unlimited" : snapshot.getOwnershipLimit()) +
                ", radius=" + snapshot.getRadius() + ", ownerOverride=" + (override == null ? "none" : override) +
                ", legacyExceptions=" + exceptions + ", gridCap=" + gridCap() + ", authoritative=" +
                snapshot.isAuthoritative() + ", verifiedAt=" + snapshot.getVerifiedAtMillis() +
                ", revision=" + snapshot.getRevision() + ", cache=" + cache.size() + ", queued=" + requests.size();
    }

    private int gridCap() { return plugin.getSettings().getMaxIslandSize(); }
    private int defaultRadius() { return plugin.getSettings().getDefaultValues().getIslandSize(); }
    private boolean currentEpoch(UUID owner, EntitlementSnapshot snapshot) {
        return snapshot.getOwnerEpoch() == ownerEpochs.getOrDefault(owner, 0L) && snapshot.getGlobalEpoch() == allEpoch.get();
    }
    private Object ownerMonitor(UUID owner) { return ownerMonitors[(owner.hashCode() & Integer.MAX_VALUE) % ownerMonitors.length]; }
    private static <T> CompletableFuture<T> failed(Throwable error) {
        CompletableFuture<T> future = new CompletableFuture<>(); future.completeExceptionally(error); return future;
    }

    @Override public synchronized void close() {
        if (closed) return;
        closed = true;
        coordinator.shutdownNow();
        for (Request request : requests.values()) request.future.completeExceptionally(new RejectedExecutionException("Policy service stopped"));
        requests.clear(); queue.clear(); geometry.clear(); geometryOrder.clear();
        saveSnapshots();
        storage.shutdown();
        try {
            if (!storage.awaitTermination(10L, TimeUnit.SECONDS)) {
                plugin.getLogger().warning("PVC policy storage did not drain within the shutdown deadline");
                storage.shutdownNow();
            }
        } catch (InterruptedException error) { Thread.currentThread().interrupt(); storage.shutdownNow(); }
    }
    private static final class Request {
        final UUID owner;
        final AtomicBoolean started = new AtomicBoolean();
        final AtomicBoolean terminal = new AtomicBoolean();
        final CompletableFuture<EntitlementSnapshot> future = new CompletableFuture<>();
        Request(UUID owner) { this.owner = owner; }
    }
    private static final class GeometryCursor {
        final long revision;
        final Iterator<Island> iterator;
        GeometryCursor(long revision, Iterator<Island> iterator) { this.revision = revision; this.iterator = iterator; }
    }
}

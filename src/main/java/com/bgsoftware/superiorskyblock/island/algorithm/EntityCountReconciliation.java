package com.bgsoftware.superiorskyblock.island.algorithm;

import com.bgsoftware.superiorskyblock.api.key.Key;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** UUID presence makes an entity photographed in two chunks, or spawned before a later photograph, count once. */
final class EntityCountReconciliation {
    private final ChunkCountReconciliation lifecycle = new ChunkCountReconciliation();
    private final Map<UUID, Key> observed = new HashMap<>();

    Object getLock() { return lifecycle.getLock(); }
    long begin() {
        synchronized (getLock()) {
            long token = lifecycle.begin();
            lifecycle.configure(token, Collections.emptyList(), true);
            observed.clear();
            return token;
        }
    }

    void record(UUID uuid, Key key, int amount, boolean present) {
        // Caller holds the same monitor as the live counter mutation.
        if (lifecycle.activeToken() == 0) return;
        if (uuid == null || amount != 1) { lifecycle.invalidate(); return; }
        if (present) observed.put(uuid, key); else observed.remove(uuid);
        lifecycle.touch();
    }

    void capture(long token, Map<UUID, Key> chunkEntities) {
        synchronized (getLock()) {
            if (lifecycle.activeToken() != token) return;
            // The caller is still in the owning chunk callback. No Bukkit/entity reads occur under this lock.
            observed.putAll(chunkEntities);
            lifecycle.touch();
        }
    }

    Prepared prepare(long token) {
        ChunkCountReconciliation.Prepared stamp;
        Map<UUID, Key> snapshot;
        synchronized (getLock()) {
            stamp = lifecycle.prepare(token, Collections.emptyMap());
            if (stamp == null) return null;
            snapshot = new HashMap<>(observed);
        }
        Map<Key, Integer> counts = new HashMap<>();
        snapshot.values().forEach(key -> counts.merge(key, 1, Math::addExact));
        return new Prepared(stamp, counts, snapshot);
    }

    boolean finish(Prepared prepared, Runnable publication) {
        synchronized (getLock()) {
            if (!lifecycle.current(prepared.stamp)) return false;
            publication.run();
            lifecycle.finish(prepared.stamp.getToken());
            observed.clear();
            return true;
        }
    }

    void invalidate() { lifecycle.invalidate(); }
    void cancel(long token) {
        synchronized (getLock()) {
            if (lifecycle.activeToken() != token) return;
            lifecycle.cancel(token);
            observed.clear();
        }
    }

    static final class Prepared {
        private final ChunkCountReconciliation.Prepared stamp;
        private final Map<Key, Integer> counts;
        private final Map<UUID, Key> identities;
        private Prepared(ChunkCountReconciliation.Prepared stamp, Map<Key, Integer> counts, Map<UUID, Key> identities) {
            this.stamp = stamp; this.counts = Collections.unmodifiableMap(counts);
            this.identities = Collections.unmodifiableMap(identities);
        }
        Map<Key, Integer> getCounts() { return counts; }
        Map<UUID, Key> getIdentities() { return identities; }
    }
}

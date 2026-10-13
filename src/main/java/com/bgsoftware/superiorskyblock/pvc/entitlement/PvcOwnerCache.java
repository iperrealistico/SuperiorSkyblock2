package com.bgsoftware.superiorskyblock.pvc.entitlement;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Hot lookups are lock-free; a bounded LRU scan occurs only when publishing a new owner at capacity. */
final class PvcOwnerCache {
    private final ConcurrentHashMap<UUID, Entry> entries = new ConcurrentHashMap<>();
    Entry get(UUID owner, long nowMillis) {
        Entry entry = entries.get(owner);
        if (entry != null) entry.lastAccessMillis = nowMillis;
        return entry;
    }
    Entry peek(UUID owner) { return entries.get(owner); }
    synchronized void put(UUID owner, EntitlementSnapshot snapshot, long nowMillis, int capacity) {
        Entry old = entries.get(owner);
        if (old != null) { old.snapshot = snapshot; return; }
        while (entries.size() >= capacity) {
            UUID victim = null;
            long oldest = Long.MAX_VALUE;
            for (Map.Entry<UUID, Entry> entry : entries.entrySet()) {
                if (entry.getValue().lastAccessMillis < oldest) {
                    oldest = entry.getValue().lastAccessMillis;
                    victim = entry.getKey();
                }
            }
            if (victim == null) break;
            entries.remove(victim);
        }
        entries.put(owner, new Entry(snapshot, nowMillis));
    }
    synchronized void trim(int capacity) {
        while (entries.size() > capacity) {
            UUID victim = null;
            long oldest = Long.MAX_VALUE;
            for (Map.Entry<UUID, Entry> entry : entries.entrySet()) {
                if (entry.getValue().lastAccessMillis < oldest) {
                    oldest = entry.getValue().lastAccessMillis;
                    victim = entry.getKey();
                }
            }
            if (victim == null) return;
            entries.remove(victim);
        }
    }
    List<Map.Entry<UUID, Entry>> entries() { return new ArrayList<>(entries.entrySet()); }
    int size() { return entries.size(); }
    static final class Entry {
        volatile EntitlementSnapshot snapshot;
        volatile long lastAccessMillis;
        volatile long retryAfterMillis;
        volatile int failures;
        volatile boolean geometryDirty;
        volatile long geometryRevision;
        Entry(EntitlementSnapshot snapshot, long access) { this.snapshot = snapshot; this.lastAccessMillis = access; }
    }
}

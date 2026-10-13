package com.bgsoftware.superiorskyblock.island.algorithm;

import com.bgsoftware.superiorskyblock.api.key.Key;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Pending event slots share the tracker's accounting barrier and are never expired or evicted. */
final class EntitySlotReservations {
    private static final int MAX_PENDING = 4096;
    private final Object lock;
    private final Map<UUID, Key> reservations = new HashMap<>();
    private final Map<Key, Integer> counts = new HashMap<>();
    EntitySlotReservations(Object lock) { this.lock = lock; }
    boolean reserve(UUID entity, Key key, int existing, int limit) {
        synchronized (lock) {
            Key previous = reservations.get(entity);
            if (previous != null) return previous.equals(key);
            if (limit < 0) return true;
            if (reservations.size() >= MAX_PENDING || (long) existing + counts.getOrDefault(key, 0) + 1L > limit)
                return false;
            reservations.put(entity, key);
            counts.merge(key, 1, Integer::sum);
            return true;
        }
    }
    Key release(UUID entity) {
        synchronized (lock) {
            Key key = reservations.remove(entity);
            if (key != null) counts.computeIfPresent(key, (ignored, count) -> count <= 1 ? null : count - 1);
            return key;
        }
    }
    Set<UUID> clear() {
        synchronized (lock) {
            Set<UUID> previous = new HashSet<>(reservations.keySet());
            reservations.clear(); counts.clear();
            return previous;
        }
    }
}

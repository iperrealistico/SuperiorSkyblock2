package com.bgsoftware.superiorskyblock.core.threads;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Consumer;

/** Each key's acquire/release calls must be serialized by its actual owner. */
public final class ReferenceCountedRetention<K> {
    private final Map<K, Integer> references = new HashMap<>();
    private final Consumer<K> acquire, release;
    public ReferenceCountedRetention(Consumer<K> acquire, Consumer<K> release) {
        this.acquire = acquire; this.release = release;
    }
    public void acquire(K key) {
        synchronized (references) {
            Integer count = references.get(key);
            if (count != null) { references.put(key, count + 1); return; }
        }
        // Native work stays outside the shared metadata mutex. The owning
        // region serializes callers for this coordinate, including first use.
        acquire.accept(key);
        synchronized (references) { references.put(key, 1); }
    }
    public void release(K key) {
        synchronized (references) {
            Integer count = references.get(key);
            if (count == null) return;
            if (count > 1) { references.put(key, count - 1); return; }
        }
        // Failure keeps the last physical reference for truthful retry/ACK.
        release.accept(key);
        synchronized (references) { references.remove(key); }
    }
    public int references(K key) { synchronized (references) { return references.getOrDefault(key, 0); } }
}

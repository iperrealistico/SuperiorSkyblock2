package com.bgsoftware.superiorskyblock.missions.common.tracker;

import com.bgsoftware.superiorskyblock.core.Counter;
import com.bgsoftware.superiorskyblock.missions.common.requirements.IRequirements;

import java.util.Collections;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public abstract class DataTracker<K, R extends IRequirements<K>> {

    private final Map<K, Counter> trackedData;
    private final Counter globalCounter = new Counter(0);

    protected DataTracker(Map<K, Counter> trackedData) {
        this.trackedData = trackedData;
    }

    public synchronized void track(K key, int amount) {
        this.trackedData.computeIfAbsent(key, k -> new Counter(0)).inc(amount);
        globalCounter.inc(amount);
    }

    public synchronized void load(K blockKey, int amount) {
        Counter previous = this.trackedData.put(blockKey, new Counter(amount));
        globalCounter.inc(amount - (previous == null ? 0 : previous.get()));
    }

    public synchronized int getCount(K blockKey) {
        return Optional.ofNullable(this.trackedData.get(blockKey)).map(Counter::get).orElse(0);
    }

    public synchronized int getGlobalCounter() {
        return this.globalCounter.get();
    }

    public int getCounts(R blocks) {
        if (blocks.isContainsAll())
            return getGlobalCounter();

        List<K> keys = new ArrayList<>();
        blocks.forEach(keys::add);
        synchronized (this) {
            int count = 0;
            for (K key : keys) count += getCount(key);
            return count;
        }
    }

    public synchronized void clear() {
        this.trackedData.clear();
        this.globalCounter.set(0);
    }

    public synchronized Map<K, Counter> getCounts() {
        Map<K, Counter> snapshot = new HashMap<>();
        this.trackedData.forEach((key, value) -> snapshot.put(key, new Counter(value.get())));
        return Collections.unmodifiableMap(snapshot);
    }

}

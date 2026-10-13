package com.bgsoftware.superiorskyblock.missions.farming;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public class TrackedPlantsData {

    private final Map<Long, Map<Integer, UUID>> trackedData = new HashMap<>();

    public void track(long chunkKey, int block, UUID placer) {
        Map<Integer, UUID> chunk;
        synchronized (this) { chunk = trackedData.computeIfAbsent(chunkKey, i -> new HashMap<>()); }
        synchronized (chunk) { chunk.put(block, placer); }
    }

    public void untrack(long chunkKey, int block) {
        Map<Integer, UUID> chunkTrackedData;
        synchronized (this) { chunkTrackedData = this.trackedData.get(chunkKey); }
        if (chunkTrackedData != null) synchronized (chunkTrackedData) { chunkTrackedData.remove(block); }
    }

    public UUID getPlacer(long chunkKey, int block) {
        Map<Integer, UUID> chunkTrackedData;
        synchronized (this) { chunkTrackedData = this.trackedData.get(chunkKey); }
        if (chunkTrackedData == null) return null;
        synchronized (chunkTrackedData) { return chunkTrackedData.get(block); }
    }

    public Map<Long, Map<Integer, UUID>> getPlants() {
        Map<Long, Map<Integer, UUID>> sources;
        synchronized (this) { sources = new HashMap<>(trackedData); }
        Map<Long, Map<Integer, UUID>> snapshot = new HashMap<>();
        sources.forEach((chunk, blocks) -> {
            synchronized (blocks) { snapshot.put(chunk, java.util.Collections.unmodifiableMap(new HashMap<>(blocks))); }
        });
        return java.util.Collections.unmodifiableMap(snapshot);
    }

}

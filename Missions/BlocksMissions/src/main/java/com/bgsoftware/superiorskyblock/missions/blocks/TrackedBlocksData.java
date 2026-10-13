package com.bgsoftware.superiorskyblock.missions.blocks;

import org.bukkit.configuration.ConfigurationSection;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.IntSupplier;

public class TrackedBlocksData {

    private final Map<Long, ChunkBitSet> TRACKED_BLOCKS = new HashMap<>();

    public TrackedBlocksData(ConfigurationSection section) {
        for (String chunkKey : section.getKeys(false)) {
            List<Integer> trackedBlocks = section.getIntegerList(chunkKey);
            ChunkBitSet bitSet = new ChunkBitSet();
            trackedBlocks.forEach(bitSet::set);
            try {
                TRACKED_BLOCKS.put(Long.parseLong(chunkKey), bitSet);
            } catch (NumberFormatException ignored) {
            }
        }
    }

    public TrackedBlocksData() {

    }

    public void track(long chunkKey, int block) {
        ChunkBitSet chunk;
        synchronized (this) { chunk = TRACKED_BLOCKS.computeIfAbsent(chunkKey, key -> new ChunkBitSet()); }
        chunk.set(block);
    }

    public boolean untrack(long chunkKey, IntSupplier block) {
        int index = block.getAsInt();
        ChunkBitSet bitSet;
        synchronized (this) { bitSet = TRACKED_BLOCKS.get(chunkKey); }
        return bitSet != null && bitSet.clear(index);
    }

    public boolean contains(long chunkKey, IntSupplier block) {
        int index = block.getAsInt();
        ChunkBitSet bitSet;
        synchronized (this) { bitSet = TRACKED_BLOCKS.get(chunkKey); }
        return bitSet != null && bitSet.get(index);
    }

    public Map<Long, ChunkBitSet> getBlocks() {
        Map<Long, ChunkBitSet> sources;
        synchronized (this) { sources = new HashMap<>(TRACKED_BLOCKS); }
        Map<Long, ChunkBitSet> snapshot = new HashMap<>();
        sources.forEach((key, value) -> snapshot.put(key, value.copy()));
        return Collections.unmodifiableMap(snapshot);
    }

}

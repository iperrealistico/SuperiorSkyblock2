package com.bgsoftware.superiorskyblock.missions.farming;

import com.bgsoftware.superiorskyblock.api.wrappers.BlockPosition;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.configuration.ConfigurationSection;

import javax.annotation.Nullable;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public class PlantsTracker {

    public static final PlantsTracker INSTANCE = new PlantsTracker();

    private final Map<String, PlantsTrackingComponent> plantsTracker = new HashMap<>();
    private Map<String, TrackedPlantsData> rawData = null;
    private Map<String, Map<UUID, List<BlockPosition>>> legacyRawData = null;
    private final java.util.Set<String> blockedWorlds = java.util.concurrent.ConcurrentHashMap.newKeySet();

    public boolean isWorldAvailable(String world) { return !blockedWorlds.contains(world); }
    public boolean canPersistProgress() { return blockedWorlds.isEmpty(); }

    private PlantsTracker() {

    }

    public void track(Block block, UUID placer) {
        track(block.getWorld(), block.getX(), block.getY(), block.getZ(), placer);
    }

    public void track(World world, int x, int y, int z, UUID placer) {
        String name = world.getName();
        int minimumHeight = PlantsTrackingComponent.getMinimumHeight(world);
        synchronized (this) { component(name, minimumHeight).track(x, y, z, placer); }
    }

    public void untrack(Block block) {
        untrack(block.getWorld(), block.getX(), block.getY(), block.getZ());
    }

    public void untrack(World world, int x, int y, int z) {
        String name = world.getName();
        int minimumHeight = PlantsTrackingComponent.getMinimumHeight(world);
        synchronized (this) { component(name, minimumHeight).untrack(x, y, z); }
    }

    @Nullable
    public UUID getPlacer(Location location) {
        World world = location.getWorld();
        return world == null ? null : getPlacer(world, location.getBlockX(), location.getBlockY(), location.getBlockZ());
    }

    @Nullable
    public UUID getPlacer(World world, int x, int y, int z) {
        String name = world.getName();
        int minimumHeight = PlantsTrackingComponent.getMinimumHeight(world);
        synchronized (this) { return component(name, minimumHeight).getPlacer(x, y, z); }
    }

    public void load(String worldName, long chunkKey, List<Integer> blocks, UUID placer) {
        load(worldName, chunkKey, blocks, placer, 1);
    }

    public void load(String worldName, long chunkKey, List<Integer> blocks, UUID placer, int version) {
        if (version > com.bgsoftware.superiorskyblock.missions.common.tracker.ChunkKeyCodec.VERSION ||
                com.bgsoftware.superiorskyblock.missions.common.tracker.ChunkKeyCodec.isAmbiguousLegacy(chunkKey, version)) {
            if (blockedWorlds.add(worldName)) java.util.logging.Logger.getLogger("SuperiorSkyblock2").severe(
                    "Farming mission legacy chunk coordinates are ambiguous in world " + worldName +
                    "; only this tracker is quarantined. Original progress and migration backup are retained; resolve the file before restarting.");
            return;
        }
        synchronized (this) {
        if (this.rawData == null)
            this.rawData = new HashMap<>();
        TrackedPlantsData trackedPlantsData = this.rawData.computeIfAbsent(worldName, w -> new TrackedPlantsData());
        blocks.forEach(block -> trackedPlantsData.track(chunkKey, block, placer));
        }
    }

    public synchronized void loadLegacy(String worldName, BlockPosition plant, UUID placer) {
        if (this.legacyRawData == null)
            this.legacyRawData = new HashMap<>();
        this.legacyRawData.computeIfAbsent(worldName, w -> new LinkedHashMap<>())
                .computeIfAbsent(placer, i -> new LinkedList<>()).add(plant);
    }

    /** Capture metadata only; YAML construction and file writes happen outside tracker locks. */
    public void save(ConfigurationSection section) {
        if (!canPersistProgress()) throw new IllegalStateException("Ambiguous legacy tracker progress is quarantined and must not be overwritten");
        Map<String, Map<Long, Map<Integer, UUID>>> worlds = new LinkedHashMap<>();
        Map<String, Map<UUID, List<BlockPosition>>> legacy = new LinkedHashMap<>();
        synchronized (this) {
            this.plantsTracker.forEach((name, component) -> worlds.put(name, component.getPlants()));
            if (this.rawData != null) this.rawData.forEach((name, data) -> worlds.put(name, data.getPlants()));
            if (this.legacyRawData != null) this.legacyRawData.forEach((name, data) -> {
                Map<UUID, List<BlockPosition>> copy = new LinkedHashMap<>();
                data.forEach((placer, positions) -> copy.put(placer, new LinkedList<>(positions)));
                legacy.put(name, copy);
            });
        }
        // The tracker can be shared by several missions. Replace its namespaces on each snapshot,
        // rather than append duplicates or omit all autosaves after the first one.
        section.set("placed-plants", null);
        section.set("placed-plants-legacy", null);
        boolean hasData = false;
        for (Map.Entry<String, Map<Long, Map<Integer, UUID>>> world : worlds.entrySet()) {
            for (Map.Entry<Long, Map<Integer, UUID>> chunk : world.getValue().entrySet()) {
                Map<UUID, List<Integer>> byPlacer = new LinkedHashMap<>();
                chunk.getValue().forEach((block, placer) -> byPlacer.computeIfAbsent(placer, key -> new LinkedList<>()).add(block));
                for (Map.Entry<UUID, List<Integer>> placer : byPlacer.entrySet()) {
                    section.set("placed-plants." + placer.getKey() + "." + world.getKey() + "." + chunk.getKey(), placer.getValue());
                    hasData = true;
                }
            }
        }
        for (Map.Entry<String, Map<UUID, List<BlockPosition>>> world : legacy.entrySet()) {
            for (Map.Entry<UUID, List<BlockPosition>> placer : world.getValue().entrySet()) {
                for (BlockPosition plant : placer.getValue()) {
                    String key = world.getKey() + ";" + plant.getX() + ";" + plant.getY() + ";" + plant.getZ();
                    section.set("placed-plants-legacy." + key, placer.getKey().toString());
                    hasData = true;
                }
            }
        }
        section.set("data-version", com.bgsoftware.superiorskyblock.missions.common.tracker.ChunkKeyCodec.VERSION);
    }

    /** Called under the metadata monitor; no Bukkit/world calls occur here. */
    private PlantsTrackingComponent component(String name, int minimumHeight) {
        PlantsTrackingComponent component = plantsTracker.get(name);
        if (component == null) {
            TrackedPlantsData data = rawData == null ? null : rawData.remove(name);
            component = new PlantsTrackingComponent(minimumHeight, data == null ? new TrackedPlantsData() : data);
            plantsTracker.put(name, component);
        }
        Map<UUID, List<BlockPosition>> legacy = legacyRawData == null ? null : legacyRawData.remove(name);
        if (legacy != null) {
            for (Map.Entry<UUID, List<BlockPosition>> entry : legacy.entrySet())
                for (BlockPosition plant : entry.getValue())
                    component.track(plant.getX(), plant.getY(), plant.getZ(), entry.getKey());
        }
        return component;
    }

}

package com.bgsoftware.superiorskyblock.missions.blocks;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.configuration.ConfigurationSection;

import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

public class BlocksTracker {

    public static final BlocksTracker INSTANCE = new BlocksTracker();

    private final EnumMap<TrackingType, Map<String, BlocksTrackingComponent>> trackingComponentMap = new EnumMap<>(TrackingType.class);
    private EnumMap<TrackingType, Map<String, TrackedBlocksData>> rawData = null;
    private final java.util.Set<String> blockedWorlds = java.util.concurrent.ConcurrentHashMap.newKeySet();

    public boolean isWorldAvailable(String world) { return !blockedWorlds.contains(world); }
    public boolean canPersistProgress() { return blockedWorlds.isEmpty(); }


    private BlocksTracker() {

    }

    public void trackBlock(TrackingType trackingType, Block block) {
        getComponent(trackingType, block.getWorld()).add(block.getX(), block.getY(), block.getZ());
    }

    public boolean untrackBlock(TrackingType trackingType, Block block) {
        return ifComponentExists(trackingType, block.getWorld(), false,
                component -> component.remove(block.getX(), block.getY(), block.getZ()));
    }

    public boolean isTracked(TrackingType trackingType, Block block) {
        return ifComponentExists(trackingType, block.getWorld(), false,
                component -> component.contains(block.getX(), block.getY(), block.getZ()));
    }

    public boolean isTracked(TrackingType trackingType, Location blockLocation) {
        return ifComponentExists(trackingType, blockLocation.getWorld(), false, component ->
                component.contains(blockLocation.getBlockX(), blockLocation.getBlockY(), blockLocation.getBlockZ()));
    }

    public void loadTrackedBlocks(TrackingType type, String name, ConfigurationSection section) {
        loadTrackedBlocks(type, name, section, 1);
    }

    public void loadTrackedBlocks(TrackingType type, String name, ConfigurationSection section, int version) {
        boolean ambiguous = version > com.bgsoftware.superiorskyblock.missions.common.tracker.ChunkKeyCodec.VERSION;
        for (String key : section.getKeys(false)) {
            try { ambiguous |= com.bgsoftware.superiorskyblock.missions.common.tracker.ChunkKeyCodec.isAmbiguousLegacy(Long.parseLong(key), version); }
            catch (NumberFormatException invalid) { ambiguous = true; }
        }
        if (ambiguous) {
            if (blockedWorlds.add(name)) java.util.logging.Logger.getLogger("SuperiorSkyblock2").severe(
                    "Blocks mission legacy chunk coordinates are ambiguous in world " + name +
                    "; only this tracker is quarantined. Original progress and migration backup are retained; resolve the file before restarting.");
            return;
        }
        TrackedBlocksData data = new TrackedBlocksData(section);
        synchronized (this) {
            if (rawData == null) rawData = new EnumMap<>(TrackingType.class);
            rawData.computeIfAbsent(type, ignored -> new HashMap<>()).put(name, data);
        }
    }

    public void save(ConfigurationSection section) {
        if (!canPersistProgress()) throw new IllegalStateException("Ambiguous legacy tracker progress is quarantined and must not be overwritten");
        EnumMap<TrackingType, Map<String, Map<Long, ChunkBitSet>>> snapshot = new EnumMap<>(TrackingType.class);
        synchronized (this) {
            trackingComponentMap.forEach((type, worlds) -> worlds.forEach((name, component) ->
                    snapshot.computeIfAbsent(type, ignored -> new HashMap<>()).put(name, component.getBlocks())));
            if (rawData != null) rawData.forEach((type, worlds) -> worlds.forEach((name, data) ->
                    snapshot.computeIfAbsent(type, ignored -> new HashMap<>()).put(name, data.getBlocks())));
        }
        // Build YAML from detached state; never hold tracker locks across serialization.
        for (TrackingType type : TrackingType.values()) {
            section.set("tracked." + type.path, null);
            Map<String, Map<Long, ChunkBitSet>> worlds = snapshot.get(type);
            if (worlds == null) continue;
            worlds.forEach((name, chunks) -> chunks.forEach((key, bitSet) -> {
                List<Integer> blocks = new LinkedList<>();
                bitSet.forEach(blocks::add);
                if (!blocks.isEmpty()) section.set("tracked." + type.path + "." + name + "." + key, blocks);
            }));
        }
        section.set("tracker-data-version", com.bgsoftware.superiorskyblock.missions.common.tracker.ChunkKeyCodec.VERSION);
    }

    private BlocksTrackingComponent getComponent(TrackingType type, World world) {
        String name = world.getName();
        int minimumHeight = BlocksTrackingComponent.getMinimumHeight(world);
        synchronized (this) { return component(type, name, minimumHeight, true); }
    }

    private <R> R ifComponentExists(TrackingType type, World world, R def, Function<BlocksTrackingComponent, R> function) {
        String name = world.getName();
        int minimumHeight = BlocksTrackingComponent.getMinimumHeight(world);
        BlocksTrackingComponent component;
        synchronized (this) { component = component(type, name, minimumHeight, false); }
        return component == null ? def : function.apply(component);
    }

    /** Metadata only: world height and configuration reads were captured before acquiring the monitor. */
    private BlocksTrackingComponent component(TrackingType type, String name, int minimumHeight, boolean create) {
        Map<String, BlocksTrackingComponent> worlds = trackingComponentMap.get(type);
        BlocksTrackingComponent component = worlds == null ? null : worlds.get(name);
        if (component != null) return component;
        Map<String, TrackedBlocksData> pending = rawData == null ? null : rawData.get(type);
        TrackedBlocksData data = pending == null ? null : pending.remove(name);
        if (data == null && !create) return null;
        component = new BlocksTrackingComponent(minimumHeight);
        if (data != null) component.loadBlocks(data);
        trackingComponentMap.computeIfAbsent(type, ignored -> new HashMap<>()).put(name, component);
        return component;
    }

    public enum TrackingType {
        PLACED_BLOCKS("placed"), BROKEN_BLOCKS("broken");
        private final String path;
        TrackingType(String path) { this.path = path; }
    }

}

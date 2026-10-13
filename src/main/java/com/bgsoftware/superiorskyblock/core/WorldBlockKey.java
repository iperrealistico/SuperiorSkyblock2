package com.bgsoftware.superiorskyblock.core;

import org.bukkit.Location;
import java.util.Objects;
import java.util.UUID;

/** Detached immutable identity; never retains a mutable Location or a live World. */
public final class WorldBlockKey {
    private final UUID world;
    private final int x, y, z;
    public WorldBlockKey(UUID world, int x, int y, int z) {
        this.world = Objects.requireNonNull(world, "world"); this.x = x; this.y = y; this.z = z;
    }
    public static WorldBlockKey of(Location location) {
        return new WorldBlockKey(Objects.requireNonNull(location.getWorld(), "world").getUID(),
                location.getBlockX(), location.getBlockY(), location.getBlockZ());
    }
    @Override public boolean equals(Object value) {
        if (!(value instanceof WorldBlockKey)) return false;
        WorldBlockKey other = (WorldBlockKey) value;
        return world.equals(other.world) && x == other.x && y == other.y && z == other.z;
    }
    @Override public int hashCode() { return Objects.hash(world, x, y, z); }
}

package com.bgsoftware.superiorskyblock.world.chunk;

import com.bgsoftware.superiorskyblock.SuperiorSkyblockPlugin;
import com.bgsoftware.superiorskyblock.core.ChunkPosition;
import com.bgsoftware.superiorskyblock.core.threads.BukkitExecutor;
import com.bgsoftware.superiorskyblock.core.threads.ReferenceCountedRetention;
import org.bukkit.World;
import org.bukkit.plugin.Plugin;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

/** Shared owner-only plugin ticket references for arenas and native storage. */
public final class ChunkRetention {
    private static final SuperiorSkyblockPlugin plugin = SuperiorSkyblockPlugin.getPlugin();
    private static final Method ADD = method("addPluginChunkTicket"), REMOVE = method("removePluginChunkTicket");
    private static final ReferenceCountedRetention<ChunkPosition> references = new ReferenceCountedRetention<>(
            position -> {
                if (!invoke(ADD, position))
                    throw new IllegalStateException("Superior chunk ticket exists outside shared retention service");
            }, position -> invoke(REMOVE, position));
    private ChunkRetention() { }
    public static void acquire(World world, int x, int z) {
        requireOwner(world, x, z);
        references.acquire(ChunkPosition.of(world, x, z, false));
    }
    public static void release(World world, int x, int z) {
        requireOwner(world, x, z);
        references.release(ChunkPosition.of(world, x, z, false));
    }
    private static void requireOwner(World world, int x, int z) {
        if (!BukkitExecutor.owns(world, x, z))
            throw new IllegalStateException("Chunk retention requires the coordinate owner");
    }
    private static boolean invoke(Method method, ChunkPosition position) {
        try { return Boolean.TRUE.equals(method.invoke(position.getWorld(), position.getX(), position.getZ(), plugin)); }
        catch (InvocationTargetException failure) { throw new IllegalStateException("Native chunk retention failed", failure.getCause()); }
        catch (ReflectiveOperationException failure) { throw new IllegalStateException("Native chunk retention unavailable", failure); }
    }
    private static Method method(String name) {
        try { return World.class.getMethod(name, int.class, int.class, Plugin.class); }
        catch (ReflectiveOperationException failure) { throw new IllegalStateException("Qualified storage requires plugin chunk tickets", failure); }
    }
}

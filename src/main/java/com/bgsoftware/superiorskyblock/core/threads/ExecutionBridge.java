package com.bgsoftware.superiorskyblock.core.threads;

import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

/** Platform boundary. A global task never owns blocks or entities on Folia. */
public interface ExecutionBridge {
    boolean isFolia();
    boolean owns(Entity entity);
    boolean owns(World world, int chunkX, int chunkZ);
    BukkitTask global(Runnable task, long delay);
    BukkitTask globalTimer(Runnable task, long delay, long period);
    BukkitTask region(World world, int chunkX, int chunkZ, Runnable task, long delay);
    BukkitTask entity(Entity entity, Runnable task, long delay, Runnable retired);
    BukkitTask async(Runnable task, long delay);
    BukkitTask asyncTimer(Runnable task, long delay, long period);
    void close();
}

package com.bgsoftware.superiorskyblock.core.threads;

import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

final class LegacyExecutionBridge implements ExecutionBridge {
    private final Plugin plugin;
    LegacyExecutionBridge(Plugin plugin) { this.plugin = plugin; }
    public boolean isFolia() { return false; }
    public boolean owns(Entity entity) { return Bukkit.isPrimaryThread(); }
    public boolean owns(World world, int x, int z) { return Bukkit.isPrimaryThread(); }
    public BukkitTask global(Runnable task, long delay) {
        return Bukkit.getScheduler().runTaskLater(plugin, task, Math.max(0L, delay));
    }
    public BukkitTask globalTimer(Runnable task, long delay, long period) {
        return Bukkit.getScheduler().runTaskTimer(plugin, task, delay, period);
    }
    public BukkitTask region(World world, int x, int z, Runnable task, long delay) { return global(task, delay); }
    public BukkitTask entity(Entity entity, Runnable task, long delay, Runnable retired) {
        return global(() -> { if (entity.isValid()) task.run(); else if (retired != null) retired.run(); }, delay);
    }
    public BukkitTask async(Runnable task, long delay) {
        return Bukkit.getScheduler().runTaskLaterAsynchronously(plugin, task, Math.max(0L, delay));
    }
    public BukkitTask asyncTimer(Runnable task, long delay, long period) {
        return Bukkit.getScheduler().runTaskTimerAsynchronously(plugin, task, delay, period);
    }
    public void close() { Bukkit.getScheduler().cancelTasks(plugin); }
}

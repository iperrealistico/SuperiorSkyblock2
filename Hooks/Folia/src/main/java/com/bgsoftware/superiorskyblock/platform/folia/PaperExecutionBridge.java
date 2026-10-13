package com.bgsoftware.superiorskyblock.platform.folia;

import com.bgsoftware.superiorskyblock.core.threads.ExecutionBridge;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

/** Loaded reflectively only on Java 21+ Paper/Folia; the core remains Java 8. */
public final class PaperExecutionBridge implements ExecutionBridge {
    private final Plugin plugin;
    private final boolean folia;
    private final AtomicInteger ids = new AtomicInteger();
    private final Set<Handle> handles = ConcurrentHashMap.newKeySet();
    private volatile boolean closed;
    public PaperExecutionBridge(Plugin plugin) {
        this.plugin = plugin;
        boolean detected;
        try { Class.forName("io.papermc.paper.threadedregions.RegionizedServer"); detected = true; }
        catch (ClassNotFoundException ignored) { detected = false; }
        folia = detected;
    }
    public boolean isFolia() { return folia; }
    public boolean owns(Entity entity) { return Bukkit.isOwnedByCurrentRegion(entity); }
    public boolean owns(World world, int x, int z) { return Bukkit.isOwnedByCurrentRegion(world, x, z); }
    private Handle newHandle(boolean sync) {
        if (closed)
            throw new RejectedExecutionException("SuperiorSkyblock scheduler is closed");
        Handle handle = new Handle(ids.incrementAndGet(), sync);
        handles.add(handle);
        if (closed) { handle.cancel(); throw new RejectedExecutionException("SuperiorSkyblock scheduler is closed"); }
        return handle;
    }
    private BukkitTask schedule(Handle handle, Supplier<ScheduledTask> scheduler) {
        try { handle.attach(scheduler.get()); return handle; }
        catch (RuntimeException | Error failure) { handle.cancel(); throw failure; }
    }
    public BukkitTask global(Runnable runnable, long delay) {
        Handle h = newHandle(true);
        return schedule(h, () -> Bukkit.getGlobalRegionScheduler().runDelayed(plugin, task -> h.run(runnable, false), Math.max(1L, delay)));
    }
    public BukkitTask globalTimer(Runnable runnable, long delay, long period) {
        Handle h = newHandle(true);
        return schedule(h, () -> Bukkit.getGlobalRegionScheduler().runAtFixedRate(plugin, task -> h.run(runnable, true), Math.max(1L, delay), Math.max(1L, period)));
    }
    public BukkitTask region(World world, int x, int z, Runnable runnable, long delay) {
        Handle h = newHandle(true);
        return schedule(h, () -> Bukkit.getRegionScheduler().runDelayed(plugin, world, x, z, task -> h.run(runnable, false), Math.max(1L, delay)));
    }
    public BukkitTask entity(Entity entity, Runnable runnable, long delay, Runnable retired) {
        Handle h = newHandle(true);
        BukkitTask result = schedule(h, () -> entity.getScheduler().runDelayed(plugin, task -> h.run(runnable, false),
                () -> h.retire(retired), Math.max(1L, delay)));
        if (h.task == null)
            h.retire(retired);
        return result;
    }
    public BukkitTask async(Runnable runnable, long delay) {
        Handle h = newHandle(false);
        return schedule(h, () -> Bukkit.getAsyncScheduler().runDelayed(plugin, task -> h.run(runnable, false), Math.max(0L, delay) * 50L, TimeUnit.MILLISECONDS));
    }
    public BukkitTask asyncTimer(Runnable runnable, long delay, long period) {
        Handle h = newHandle(false);
        return schedule(h, () -> Bukkit.getAsyncScheduler().runAtFixedRate(plugin, task -> h.run(runnable, true), Math.max(0L, delay) * 50L, Math.max(1L, period) * 50L, TimeUnit.MILLISECONDS));
    }
    public void close() { closed = true; for (Handle handle : handles) handle.cancel(); handles.clear(); }
    private final class Handle implements BukkitTask {
        final int id; final boolean sync;
        volatile ScheduledTask task;
        volatile boolean cancelled; volatile boolean done;
        final AtomicBoolean retirementDelivered = new AtomicBoolean();
        Handle(int id, boolean sync) { this.id = id; this.sync = sync; }
        void attach(ScheduledTask scheduled) {
            this.task = scheduled;
            if (scheduled != null && cancelled)
                scheduled.cancel();
        }
        void retire(Runnable retired) {
            done = true;
            handles.remove(this);
            if (!cancelled && retired != null && retirementDelivered.compareAndSet(false, true))
                retired.run();
        }
        void run(Runnable runnable, boolean repeating) {
            try { if (!cancelled) runnable.run(); }
            catch (RuntimeException | Error failure) { cancel(); throw failure; }
            finally { if (!repeating) { done = true; handles.remove(this); } }
        }
        public int getTaskId() { return id; }
        public Plugin getOwner() { return plugin; }
        public boolean isSync() { return sync; }
        public boolean isCancelled() { return cancelled; }
        public void cancel() { cancelled = true; if (task != null) task.cancel(); handles.remove(this); }
    }
}

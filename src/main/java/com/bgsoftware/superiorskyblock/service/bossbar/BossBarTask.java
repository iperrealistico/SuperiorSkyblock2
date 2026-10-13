package com.bgsoftware.superiorskyblock.service.bossbar;

import com.bgsoftware.superiorskyblock.SuperiorSkyblockPlugin;
import com.bgsoftware.superiorskyblock.api.service.bossbar.BossBar;
import com.bgsoftware.superiorskyblock.core.threads.BukkitExecutor;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayDeque;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Bossbar lifetime follows its recipient across Folia region changes. */
public class BossBarTask implements Runnable {
    private static final SuperiorSkyblockPlugin plugin = SuperiorSkyblockPlugin.getPlugin();
    private static final BossBarTask EMPTY_TASK = new BossBarTask(EmptyBossBar.getInstance(), 0);
    private static final Map<UUID, Queue<BossBarTask>> PLAYERS_RUNNING_TASKS = new ConcurrentHashMap<>();
    private final BossBar bossBar;
    private final double progressToRemovePerTick;
    private final Set<UUID> registered = ConcurrentHashMap.newKeySet();
    private volatile Player owner;
    private volatile BukkitTask task;
    private volatile boolean cancelled;
    public static BossBarTask create(BossBar bar, double ticks) { return ticks <= 0 ? EMPTY_TASK : new BossBarTask(bar, ticks); }
    private BossBarTask(BossBar bar, double ticks) {
        bossBar = bar; progressToRemovePerTick = ticks <= 0 ? 0 : bar.getProgress() / ticks;
    }
    public void run() {
        if (cancelled || owner == null) return;
        double progress = Math.max(0D, bossBar.getProgress() - progressToRemovePerTick);
        bossBar.setProgress(progress);
        if (progress <= 0D) cancel();
        else task = BukkitExecutor.entity(owner, this, 1L, this::retire);
    }
    private void retire() { cancelled = true; unregisterAll(); }
    public synchronized void cancel() throws IllegalStateException {
        if (cancelled) return;
        cancelled = true;
        if (task != null) task.cancel();
        unregisterAll();
        if (owner != null) {
            try { BukkitExecutor.entity(owner, bossBar::removeAll); }
            catch (java.util.concurrent.RejectedExecutionException ignored) { /* Shutdown disconnects recipients. */ }
        }
    }
    private void unregisterAll() {
        for (UUID uuid : registered) {
            Queue<BossBarTask> queue = PLAYERS_RUNNING_TASKS.get(uuid);
            if (queue == null) continue;
            synchronized (queue) {
                queue.remove(this);
                if (queue.isEmpty()) PLAYERS_RUNNING_TASKS.remove(uuid, queue);
            }
        }
        registered.clear();
    }
    public void registerTask(Player player) {
        if (this == EMPTY_TASK) return;
        Queue<BossBarTask> queue = PLAYERS_RUNNING_TASKS.computeIfAbsent(player.getUniqueId(), ignored -> new ArrayDeque<>());
        BossBarTask expired = null;
        synchronized (queue) {
            if (queue.size() >= plugin.getSettings().getBossbarLimit()) expired = queue.poll();
            queue.add(this); registered.add(player.getUniqueId());
        }
        if (expired != null) expired.cancel();
        if (owner == null) {
            synchronized (this) {
                if (owner == null) { owner = player; task = BukkitExecutor.entity(player, this, 1L, this::retire); }
            }
        }
    }
    public void unregisterTask(Player player) {
        registered.remove(player.getUniqueId());
        Queue<BossBarTask> queue = PLAYERS_RUNNING_TASKS.get(player.getUniqueId());
        if (queue != null) synchronized (queue) {
            queue.remove(this); if (queue.isEmpty()) PLAYERS_RUNNING_TASKS.remove(player.getUniqueId(), queue);
        }
    }
}
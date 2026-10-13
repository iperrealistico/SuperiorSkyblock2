package com.bgsoftware.superiorskyblock.listener;

import com.bgsoftware.superiorskyblock.SuperiorSkyblockPlugin;
import com.bgsoftware.superiorskyblock.api.wrappers.SuperiorPlayer;
import com.bgsoftware.superiorskyblock.core.threads.BukkitExecutor;
import com.bgsoftware.superiorskyblock.player.SSuperiorPlayer;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;

/** Publish the actual UI after native open/close processing, always on its viewer owner. */
public final class PlayerSnapshotsListener implements Listener {
    private final SuperiorSkyblockPlugin plugin;
    private final java.util.concurrent.ConcurrentMap<java.util.UUID, Boolean> pending = new java.util.concurrent.ConcurrentHashMap<>();
    private volatile boolean stopped;
    public PlayerSnapshotsListener(SuperiorSkyblockPlugin plugin) { this.plugin = plugin; }
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void opened(InventoryOpenEvent event) {
        if (event.getPlayer() instanceof Player) refresh((Player) event.getPlayer());
    }
    @EventHandler(priority = EventPriority.MONITOR)
    public void closed(InventoryCloseEvent event) {
        if (event.getPlayer() instanceof Player) refresh((Player) event.getPlayer());
    }
    private void refresh(Player player) {
        if (stopped) return;
        BukkitExecutor.entity(player, () -> {
            SuperiorPlayer account = plugin.getPlayers().getSuperiorPlayer(player);
            if (account instanceof SSuperiorPlayer) ((SSuperiorPlayer) account).refreshOpenedView(player);
        }, 1L);
    }

    public void poll() {
        if (stopped) return;
        for (Player player : org.bukkit.Bukkit.getOnlinePlayers()) {
            java.util.UUID identity = player.getUniqueId();
            if (pending.putIfAbsent(identity, Boolean.TRUE) != null) continue;
            try {
                BukkitExecutor.entity(player, () -> {
                    try {
                        if (stopped) return;
                        SuperiorPlayer account = plugin.getPlayers().getSuperiorPlayer(player);
                        if (account instanceof SSuperiorPlayer) {
                            SSuperiorPlayer concrete = (SSuperiorPlayer) account;
                            concrete.refreshRuntimeState(player);
                            concrete.refreshLocationSnapshot(player);
                            concrete.refreshOpenedView(player);
                        }
                    } finally { pending.remove(identity); }
                }, 0L, () -> pending.remove(identity));
            } catch (java.util.concurrent.RejectedExecutionException stopping) { pending.remove(identity); }
        }
    }
    public void close() { stopped = true; pending.clear(); }
}

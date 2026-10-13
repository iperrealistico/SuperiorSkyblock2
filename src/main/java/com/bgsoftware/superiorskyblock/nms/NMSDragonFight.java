package com.bgsoftware.superiorskyblock.nms;

import com.bgsoftware.common.annotations.Nullable;
import com.bgsoftware.superiorskyblock.api.island.Island;
import com.bgsoftware.superiorskyblock.api.world.Dimension;
import com.bgsoftware.superiorskyblock.core.threads.BukkitExecutor;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.EnderDragon;
import org.bukkit.entity.Player;

public interface NMSDragonFight {

    void prepareEndWorld(World bukkitWorld);

    @Nullable
    EnderDragon getEnderDragon(Island island, Dimension dimension);

    void startDragonBattle(Island island, Location location);

    void removeDragonBattle(Island island, Dimension dimension);

    default java.util.concurrent.CompletableFuture<Void> removeDragonBattleAsync(Island island, Dimension dimension) {
        java.util.concurrent.CompletableFuture<Void> result = new java.util.concurrent.CompletableFuture<>();
        Runnable removal = () -> {
            try {
                removeDragonBattle(island, dimension);
                result.complete(null);
            } catch (Throwable failure) {
                result.completeExceptionally(failure);
            }
        };
        try {
            // An empty evacuation list may continue on the SQL writer. Native
            // legacy/Paper removal still requires the owning server thread.
            if (!BukkitExecutor.isFolia()) {
                BukkitExecutor.ensureMain(removal);
            } else {
                Location center = island.getCenter(dimension);
                BukkitExecutor.region(center.getWorld(), center.getBlockX() >> 4, center.getBlockZ() >> 4, removal);
            }
        } catch (Throwable failure) {
            result.completeExceptionally(failure);
        }
        return result;
    }

    void awardTheEndAchievement(Player player);

    default void onPlayerEnteredIsland(Island island, Dimension dimension) {
    }

}

package com.bgsoftware.superiorskyblock.world;

import com.bgsoftware.superiorskyblock.SuperiorSkyblockPlugin;
import com.bgsoftware.superiorskyblock.api.island.Island;
import com.bgsoftware.superiorskyblock.api.world.Dimension;
import com.bgsoftware.superiorskyblock.api.wrappers.SuperiorPlayer;
import com.bgsoftware.superiorskyblock.core.threads.BukkitExecutor;
import com.bgsoftware.superiorskyblock.island.flag.IslandFlags;
import com.bgsoftware.superiorskyblock.player.SSuperiorPlayer;
import com.bgsoftware.superiorskyblock.player.SuperiorNPCPlayer;
import org.bukkit.Location;
import org.bukkit.WeatherType;
import org.bukkit.World;
import org.bukkit.entity.Player;

/** Physical arrival reconciliation, separate from cancellable movement preflight. */
public final class FoliaPlayerPresence {
    private FoliaPlayerPresence() { }

    public static Target capture(SuperiorSkyblockPlugin plugin, Location destination) {
        Island island = plugin.getGrid().getIslandAt(destination);
        return new Target(destination.getWorld(), island, island == null || island.isSpawn() ? 0L :
                plugin.getPvcIslandService().getWorldGeneration(island.getUniqueId()));
    }

    public static boolean isCurrent(SuperiorSkyblockPlugin plugin, Target target) {
        return target.island == null || target.island.isSpawn() ||
                (plugin.getGrid().getIslandByUUID(target.island.getUniqueId()) == target.island &&
                        plugin.getPvcIslandService().isAvailable(target.island.getUniqueId()) &&
                        plugin.getPvcIslandService().isCurrentWorldWriter(target.island, target.generation));
    }

    /** Captures only the last published snapshot, never a foreign live player. */
    public static Location previousLocation(SuperiorSkyblockPlugin plugin, Player player) {
        SuperiorPlayer account = plugin.getPlayers().getSuperiorPlayer(player);
        return account instanceof SSuperiorPlayer ? ((SSuperiorPlayer) account).getLastLocationSnapshot() : null;
    }

    public static boolean reconcile(SuperiorSkyblockPlugin plugin, Player player, Island origin, Target target) {
        if (!BukkitExecutor.isFolia() || !BukkitExecutor.owns(player) || !player.isOnline() || !player.isValid())
            return false;
        Location actual = player.getLocation();
        Island actualIsland = plugin.getGrid().getIslandAt(actual);
        if (actual.getWorld() != target.world || actualIsland != target.island || !isCurrent(plugin, target))
            return false;
        SuperiorPlayer account = plugin.getPlayers().getSuperiorPlayer(player);
        if (account instanceof SuperiorNPCPlayer) return false;
        if (account instanceof SSuperiorPlayer) {
            SSuperiorPlayer concrete = (SSuperiorPlayer) account;
            concrete.refreshRuntimeState(player);
            concrete.refreshOpenedView(player);
            concrete.refreshLocationSnapshot(player, actual);
        }
        if (origin != null && origin != actualIsland &&
                (origin.isSpawn() || plugin.getGrid().getIslandByUUID(origin.getUniqueId()) == origin)) {
            origin.setPlayerInside(account, false);
            origin.removeEffects(account);
        }
        if (actualIsland != null) {
            actualIsland.setPlayerInside(account, true);
            actualIsland.applyEffects(account);
            if (account.hasIslandFlyEnabled() && !account.hasFlyGamemode())
                actualIsland.updateIslandFly(account);
        } else if (origin != null && account.hasIslandFlyEnabled() && !account.hasFlyGamemode()) {
            player.setAllowFlight(false);
            player.setFlying(false);
        }
        // Delayed preflight effects can execute while the native teleport is
        // still in flight. Reapply current policy only at the actual arrival.
        if (origin != null && origin != actualIsland) {
            player.resetPlayerTime();
            player.resetPlayerWeather();
        }
        if (actualIsland != null && (plugin.getSettings().getSpawn().isProtected() || !actualIsland.isSpawn())) {
            if (actualIsland.hasSettingsEnabled(IslandFlags.ALWAYS_DAY)) player.setPlayerTime(0, false);
            else if (actualIsland.hasSettingsEnabled(IslandFlags.ALWAYS_MIDDLE_DAY)) player.setPlayerTime(6000, false);
            else if (actualIsland.hasSettingsEnabled(IslandFlags.ALWAYS_NIGHT)) player.setPlayerTime(14000, false);
            else if (actualIsland.hasSettingsEnabled(IslandFlags.ALWAYS_MIDDLE_NIGHT)) player.setPlayerTime(18000, false);
            if (actualIsland.hasSettingsEnabled(IslandFlags.ALWAYS_SHINY)) player.setPlayerWeather(WeatherType.CLEAR);
            else if (actualIsland.hasSettingsEnabled(IslandFlags.ALWAYS_RAIN)) player.setPlayerWeather(WeatherType.DOWNFALL);
        }
        plugin.getNMSWorld().setWorldBorder(account, actualIsland);
        if (actualIsland != null && !actualIsland.isSpawn()) {
            Dimension dimension = plugin.getGrid().getIslandsWorldDimension(actual.getWorld());
            if (dimension != null && dimension.getEnvironment() == World.Environment.THE_END)
                plugin.getNMSDragonFight().onPlayerEnteredIsland(actualIsland, dimension);
        }
        return true;
    }

    public static final class Target {
        public final World world;
        public final Island island;
        public final long generation;
        public Target(World world, Island island, long generation) {
            this.world = world; this.island = island; this.generation = generation;
        }
    }
}

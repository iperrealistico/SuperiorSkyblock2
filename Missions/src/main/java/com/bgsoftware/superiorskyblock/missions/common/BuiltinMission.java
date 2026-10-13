package com.bgsoftware.superiorskyblock.missions.common;

import com.bgsoftware.superiorskyblock.api.SuperiorSkyblock;
import com.bgsoftware.superiorskyblock.api.missions.Mission;
import com.bgsoftware.superiorskyblock.api.missions.MissionLoadException;
import com.bgsoftware.superiorskyblock.api.wrappers.SuperiorPlayer;
import org.bukkit.Bukkit;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.LinkedList;
import java.util.List;

public abstract class BuiltinMission<V> extends Mission<V> {

    @Override public final boolean supportsIslandSubjects() { return true; }

    private final List<Listener> registeredListeners = new LinkedList<>();

    protected SuperiorSkyblock plugin;

    protected final boolean progressWorldsAvailable(SuperiorPlayer player, java.util.function.Predicate<String> available) {
        org.bukkit.World world = player.getWorld();
        if (world != null && !available.test(world.getName())) return false;
        com.bgsoftware.superiorskyblock.api.island.Island island = player.getIsland();
        if (island != null) for (com.bgsoftware.superiorskyblock.api.world.Dimension dimension :
                com.bgsoftware.superiorskyblock.api.world.Dimension.values()) {
            // A registered dimension need not be enabled on this backend. Never
            // load an optional world merely to inspect a mission's tracker.
            if (!plugin.getProviders().getWorldsProvider().isDimensionEnabled(dimension)) continue;
            com.bgsoftware.superiorskyblock.api.world.WorldInfo info = plugin.getGrid().getIslandsWorldInfo(island, dimension);
            // Missing metadata for an enabled world is unavailable, not proof
            // that its retained progress can safely complete.
            if (info == null || !available.test(info.getName())) return false;
        }
        return true;
    }

    protected final void laterPlayer(SuperiorPlayer player, Runnable action, long delay) {
        com.bgsoftware.superiorskyblock.api.island.Island island = player.getIsland();
        plugin.executePlayer(player, () -> {
            try (com.bgsoftware.superiorskyblock.api.island.IslandExecutionScope scope =
                         com.bgsoftware.superiorskyblock.api.island.IslandExecutionScope.open(island)) { action.run(); }
        }, delay);
    }

    protected final void laterRegion(org.bukkit.Location location, Runnable action, long delay) {
        com.bgsoftware.superiorskyblock.api.island.Island island = plugin.getGrid().getIslandAt(location);
        plugin.executeRegion(location, () -> {
            try (com.bgsoftware.superiorskyblock.api.island.IslandExecutionScope scope =
                         com.bgsoftware.superiorskyblock.api.island.IslandExecutionScope.open(island)) { action.run(); }
        }, delay);
    }

    @Override
    public final void load(JavaPlugin plugin, ConfigurationSection section) throws MissionLoadException {
        this.plugin = (SuperiorSkyblock) plugin;

        loadConfiguration(section);
        registerListeners();

        setClearMethod(this::clearModuleData);
    }

    protected abstract void loadConfiguration(ConfigurationSection section) throws MissionLoadException;

    protected abstract void registerListeners();

    protected abstract void clearModuleData(V data);

    protected final void registerListener(Listener listener) {
        Bukkit.getPluginManager().registerEvents(listener, plugin);
        this.registeredListeners.add(listener);
    }

    @Override
    public void onComplete(SuperiorPlayer superiorPlayer) {

    }

    @Override
    public void onCompleteFail(SuperiorPlayer superiorPlayer) {

    }

    public final void unload() {
        this.registeredListeners.forEach(HandlerList::unregisterAll);
        this.registeredListeners.clear();
    }

}

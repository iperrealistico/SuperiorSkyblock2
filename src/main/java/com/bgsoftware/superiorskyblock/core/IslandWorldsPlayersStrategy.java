package com.bgsoftware.superiorskyblock.core;

import com.bgsoftware.superiorskyblock.SuperiorSkyblockPlugin;
import com.bgsoftware.superiorskyblock.api.island.Island;
import com.bgsoftware.superiorskyblock.api.world.WorldInfo;
import com.bgsoftware.superiorskyblock.api.wrappers.SuperiorPlayer;
import com.bgsoftware.superiorskyblock.core.threads.BukkitExecutor;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;

public abstract class IslandWorldsPlayersStrategy implements ObjectsPool.Releasable, AutoCloseable {

    private static final LazyReference<ObjectsPool<IslandWorldsPlayersStrategy>> POOL = new LazyReference<ObjectsPool<IslandWorldsPlayersStrategy>>() {
        @Override
        protected ObjectsPool<IslandWorldsPlayersStrategy> create() {
            return plugin.getProviders().hasCustomWorldsSupport() ? new ObjectsPool<>(IslandWorldsPlayersStrategyMultiWorld::new) :
                    new ObjectsPool<>(IslandWorldsPlayersStrategySingleWorld::new);
        }
    };

    private static final SuperiorSkyblockPlugin plugin = SuperiorSkyblockPlugin.getPlugin();

    public static IslandWorldsPlayersStrategy create(Island island) {
        return POOL.get().obtain().initialize(island);
    }

    private IslandWorldsPlayersStrategy() {

    }

    protected abstract IslandWorldsPlayersStrategy initialize(Island island);

    @Override
    public void close() {
        release();
    }

    public abstract List<Player> getPlayers(WorldInfo worldInfo);

    public abstract List<SuperiorPlayer> getSuperiorPlayers(WorldInfo worldInfo);

    private static List<SuperiorPlayer> playersInWorld(List<SuperiorPlayer> candidates, WorldInfo worldInfo) {
        List<SuperiorPlayer> result = new ArrayList<>();
        for (SuperiorPlayer candidate : candidates) {
            World world = candidate.getWorld();
            if (world != null && world.getName().equals(worldInfo.getName()))
                result.add(candidate);
        }
        return result;
    }

    private static List<Player> onlineIdentities(List<SuperiorPlayer> candidates) {
        List<Player> result = new ArrayList<>();
        for (SuperiorPlayer candidate : candidates) {
            Player player = candidate.asPlayer();
            if (player != null)
                result.add(player);
        }
        return result;
    }

    private static class IslandWorldsPlayersStrategySingleWorld extends IslandWorldsPlayersStrategy {

        private List<SuperiorPlayer> playersInIsland = null;

        private IslandWorldsPlayersStrategySingleWorld() {
        }

        protected IslandWorldsPlayersStrategySingleWorld initialize(Island island) {
            this.playersInIsland = island.getAllPlayersInside();
            return this;
        }

        @Override
        public void release() {
            this.playersInIsland = null;
        }

        @Override
        public List<Player> getPlayers(WorldInfo worldInfo) {
            return onlineIdentities(getSuperiorPlayers(worldInfo));
        }

        @Override
        public List<SuperiorPlayer> getSuperiorPlayers(WorldInfo worldInfo) {
            return playersInWorld(this.playersInIsland, worldInfo);
        }

    }


    private static class IslandWorldsPlayersStrategyMultiWorld extends IslandWorldsPlayersStrategy {

        private Island island = null;

        private IslandWorldsPlayersStrategyMultiWorld() {

        }

        protected IslandWorldsPlayersStrategyMultiWorld initialize(Island island) {
            this.island = island;
            return this;
        }

        @Override
        public void release() {
            this.island = null;
        }

        @Override
        public List<Player> getPlayers(WorldInfo worldInfo) {
            if (BukkitExecutor.isFolia())
                return onlineIdentities(getSuperiorPlayers(worldInfo));
            World bukkitWorld = Bukkit.getWorld(worldInfo.getName());
            try (ObjectsPools.Wrapper<Location> wrapper = ObjectsPools.LOCATION.obtain()) {
                return new SequentialListBuilder<Player>()
                        .filter(player -> island.isInside(player.getLocation(wrapper.getHandle())))
                        .build(bukkitWorld.getPlayers());
            }
        }

        @Override
        public List<SuperiorPlayer> getSuperiorPlayers(WorldInfo worldInfo) {
            if (BukkitExecutor.isFolia())
                return playersInWorld(island.getAllPlayersInside(), worldInfo);
            World bukkitWorld = Bukkit.getWorld(worldInfo.getName());
            try (ObjectsPools.Wrapper<Location> wrapper = ObjectsPools.LOCATION.obtain()) {
                return new SequentialListBuilder<SuperiorPlayer>()
                        .filter(player -> island.isInside(player.getLocation(wrapper.getHandle())))
                        .build(bukkitWorld.getPlayers(), plugin.getPlayers()::getSuperiorPlayer);
            }
        }

    }

}

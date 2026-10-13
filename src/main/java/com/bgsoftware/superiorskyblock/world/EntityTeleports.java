package com.bgsoftware.superiorskyblock.world;

import com.bgsoftware.common.annotations.Nullable;
import com.bgsoftware.superiorskyblock.SuperiorSkyblockPlugin;
import com.bgsoftware.superiorskyblock.api.events.IslandSetHomeEvent;
import com.bgsoftware.superiorskyblock.api.island.Island;
import com.bgsoftware.superiorskyblock.api.island.IslandChunkFlags;
import com.bgsoftware.superiorskyblock.api.player.algorithm.PlayerTeleportAlgorithm;
import com.bgsoftware.superiorskyblock.api.service.region.MoveResult;
import com.bgsoftware.superiorskyblock.api.service.region.RegionManagerService;
import com.bgsoftware.superiorskyblock.api.world.Dimension;
import com.bgsoftware.superiorskyblock.api.world.WorldInfo;
import com.bgsoftware.superiorskyblock.api.wrappers.SuperiorPlayer;
import com.bgsoftware.superiorskyblock.core.ChunkPosition;
import com.bgsoftware.superiorskyblock.core.IslandWorlds;
import com.bgsoftware.superiorskyblock.core.ObjectsPools;
import com.bgsoftware.superiorskyblock.core.events.args.PluginEventArgs;
import com.bgsoftware.superiorskyblock.core.events.plugin.PluginEvent;
import com.bgsoftware.superiorskyblock.core.events.plugin.PluginEventsFactory;
import com.bgsoftware.superiorskyblock.core.formatting.Formatters;
import com.bgsoftware.superiorskyblock.core.logging.Debug;
import com.bgsoftware.superiorskyblock.core.logging.Log;
import com.bgsoftware.superiorskyblock.core.messages.Message;
import com.bgsoftware.superiorskyblock.core.threads.BukkitExecutor;
import com.bgsoftware.superiorskyblock.island.IslandUtils;
import com.bgsoftware.superiorskyblock.world.chunk.ChunkLoadReason;
import com.bgsoftware.superiorskyblock.world.chunk.ChunksProvider;
import com.google.common.base.Preconditions;
import org.bukkit.ChunkSnapshot;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import com.bgsoftware.superiorskyblock.player.SuperiorNPCPlayer;

import java.time.Duration;
import java.util.Comparator;
import java.util.LinkedList;
import java.util.Objects;
import java.util.Queue;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

public class EntityTeleports {

    private static final SuperiorSkyblockPlugin plugin = SuperiorSkyblockPlugin.getPlugin();

    private EntityTeleports() {
    }

    public static void warmupTeleport(SuperiorPlayer superiorPlayer, long warmupInMillis, TeleportCallback teleportCallback) {
        if (warmupInMillis > 0 && !superiorPlayer.hasBypassModeEnabled() &&
                !superiorPlayer.hasPermission("superior.admin.bypass.warmup")) {
            Message.TELEPORT_WARMUP.send(superiorPlayer, Formatters.TIME_FORMATTER.format(
                    Duration.ofMillis(warmupInMillis), superiorPlayer.getUserLocale()));

            superiorPlayer.runIfOnline(player -> superiorPlayer.setTeleportTask(BukkitExecutor.entity(player,
                    () -> teleportCallback.accept(true), Math.max(1L, warmupInMillis / 50))));
        } else {
            teleportCallback.accept(false);
        }
    }

    public static void teleport(Entity entity, Location location) {
        teleport(entity, location, null);
    }

    public static void teleport(Entity entity, Location location, @Nullable Consumer<PlayerTeleportAlgorithm.TeleportResult> teleportResult) {
        // Callers may pass a pooled or otherwise mutable location. The native
        // asynchronous path must own its detached destination throughout.
        Location target = location.clone();
        Island island = plugin.getGrid().getIslandAt(target);

        if (island != null) {
            plugin.getProviders().getWorldsProvider().prepareTeleport(island, target.clone(),
                    () -> teleportEntity(entity, target, teleportResult));
        } else {
            teleportEntity(entity, target, teleportResult);
        }
    }

    public static void teleportUntilSuccess(Entity entity, Location location, long cooldown, @Nullable Runnable onFinish) {
        teleportUntilSuccess(entity, location, cooldown, onFinish, 0);
    }

    private static void teleportUntilSuccess(Entity entity, Location location, long cooldown, @Nullable Runnable onFinish, int attempt) {
        teleport(entity, location, result -> {
            if (result != PlayerTeleportAlgorithm.TeleportResult.SUCCESS) {
                if (attempt >= 31) { plugin.getLogger().warning("Teleport retry budget exhausted for entity " + entity.getUniqueId()); return; }
                BukkitExecutor.entity(entity, () -> teleportUntilSuccess(entity, location, cooldown, onFinish, attempt + 1), Math.max(1L, cooldown));
            } else if (onFinish != null) {
                onFinish.run();
            }
        });
    }

    public static CompletableFuture<Location> findIslandSafeLocation(Island island, Dimension dimension) {
        CompletableFuture<Location> result = new CompletableFuture<>();
        IslandWorlds.accessIslandWorldAsync(island, dimension, true, islandWorldResult -> {
            islandWorldResult.ifRight(result::completeExceptionally).ifLeft(world ->
                    findIslandSafeLocation(island, dimension, result));
        });
        return result;
    }

    public static void findIslandSafeLocation(Island island, Dimension dimension, CompletableFuture<Location> result) {
        Location home = island.getIslandHome(dimension);
        if (home == null || home.getWorld() == null) {
            result.completeExceptionally(new IllegalStateException("Missing island home: " + island.getUniqueId()));
            return;
        }
        ChunksProvider.loadChunk(ChunkPosition.of(home), ChunkLoadReason.FIND_SAFE_SPOT,
                chunk -> findIslandSafeLocationOwned(island, dimension, result)).whenComplete((chunk, error) -> {
            if (error != null) result.completeExceptionally(error);
        });
    }

    private static void findIslandSafeLocationOwned(Island island, Dimension dimension, CompletableFuture<Location> result) {
        Location homeLocation = island.getIslandHome(dimension);

        Preconditions.checkNotNull(homeLocation, "Cannot find a suitable home location for island " +
                island.getUniqueId());

        World islandsWorld = Objects.requireNonNull(plugin.getGrid().getIslandsWorld(island, dimension), "world is null");
        float rotationYaw = homeLocation.getYaw();
        float rotationPitch = homeLocation.getPitch();

        Log.debug(Debug.FIND_SAFE_TELEPORT, island.getOwner().getName(), dimension.getName());

        // We first check that the home location is safe. If it is, we can return.
        {
            Block homeLocationBlock = homeLocation.getBlock();
            if (island.isSpawn() || WorldBlocks.isSafeBlock(homeLocationBlock)) {
                Log.debugResult(Debug.FIND_SAFE_TELEPORT, "Result Location", homeLocation);
                result.complete(homeLocation);
                return;
            }
        }

        // In case it is not safe anymore, we check in the same location if the highest block is safe.
        {
            Block teleportLocationHighestBlock = islandsWorld.getHighestBlockAt(homeLocation).getRelative(BlockFace.UP);
            if (WorldBlocks.isSafeBlock(teleportLocationHighestBlock)) {
                result.complete(adjustLocationToHome(island, teleportLocationHighestBlock, rotationYaw, rotationPitch));
                return;
            }
        }

        // The teleport location is not safe. We check for a safe spot in the center of the island.

        Location islandCenterLocation = island.getCenter(dimension);

        if (!islandCenterLocation.equals(homeLocation)) {
            ChunksProvider.loadChunk(ChunkPosition.of(islandCenterLocation), ChunkLoadReason.FIND_SAFE_SPOT, chunk -> {
                {
                    Block islandCenterBlock = islandCenterLocation.getBlock().getRelative(BlockFace.UP);
                    if (WorldBlocks.isSafeBlock(islandCenterBlock)) {
                        result.complete(adjustLocationToHome(island, islandCenterBlock, rotationYaw, rotationPitch));
                        return;
                    }
                }

                // The center is not safe, we check in the same location if the highest block is safe.
                {
                    Block islandCenterHighestBlock = islandsWorld.getHighestBlockAt(islandCenterLocation).getRelative(BlockFace.UP);
                    if (WorldBlocks.isSafeBlock(islandCenterHighestBlock)) {
                        result.complete(adjustLocationToHome(island, islandCenterHighestBlock, rotationYaw, rotationPitch));
                        return;
                    }
                }

                // The center is not safe; we look for a new spot on the island.
                findNewSafeSpotOnIsland(island, islandsWorld, homeLocation, rotationYaw, rotationPitch, result);
            }).whenComplete((chunk, error) -> { if (error != null) result.completeExceptionally(error); });
        } else {
            findNewSafeSpotOnIsland(island, islandsWorld, homeLocation, rotationYaw, rotationPitch, result);
        }
    }

    private static void findNewSafeSpotOnIsland(Island island, World islandsWorld, Location homeLocation,
                                                float rotationYaw, float rotationPitch, CompletableFuture<Location> result) {
        LinkedList<ChunkPosition> islandChunks = new LinkedList<>(IslandUtils.getChunkCoords(island,
                WorldInfo.of(islandsWorld), IslandChunkFlags.ONLY_PROTECTED | IslandChunkFlags.NO_EMPTY_CHUNKS));

        try (ChunkPosition homeChunk = ChunkPosition.of(homeLocation)) {
            islandChunks.sort(Comparator.comparingInt(o -> o.distanceSquared(homeChunk)));
        }

        findSafeSpotInChunk(island, islandChunks, islandsWorld, homeLocation, safeSpot -> {
            if (safeSpot != null) {
                result.complete(adjustLocationToHome(island, safeSpot.getBlock(), rotationYaw, rotationPitch));
            } else {
                result.complete(null);
            }
        });
    }

    private static void findSafeSpotInChunk(Island island, Queue<ChunkPosition> islandChunks, World islandsWorld,
                                            Location homeLocation, Consumer<Location> onResult) {
        ChunkPosition chunkPosition = islandChunks.poll();
        if (chunkPosition == null) {
            onResult.accept(null);
            return;
        }

        ChunksProvider.loadChunk(chunkPosition, ChunkLoadReason.FIND_SAFE_SPOT, chunk -> {
            ChunkSnapshot chunkSnapshot = chunk.getChunkSnapshot();

            if (WorldBlocks.isChunkEmpty(island, chunkSnapshot)) {
                BukkitExecutor.async(() -> findSafeSpotInChunk(island, islandChunks, islandsWorld, homeLocation, onResult));
                return;
            }

            // Custom key parsers may inspect live blocks. Classify the 256
            // candidate columns on their owner, then compare detached heights.
            int[] safeHeights = captureSafeHeights(chunkSnapshot, islandsWorld);

            BukkitExecutor.createTask().runAsync(v -> {
                Location closestSafeSpot = null;
                double closestSafeSpotDistance = 0;

                for (int x = 0; x < 16; x++) {
                    for (int z = 0; z < 16; z++) {
                        int safeY = safeHeights[(x << 4) | z];
                        if (safeY == Integer.MIN_VALUE)
                            continue;

                        int worldX = chunkSnapshot.getX() * 16 + x;
                        int worldZ = chunkSnapshot.getZ() * 16 + z;

                        Location safeSpot = new Location(islandsWorld, worldX, safeY, worldZ);

                        double distanceFromHome = safeSpot.distanceSquared(homeLocation);
                        if (closestSafeSpot == null || distanceFromHome < closestSafeSpotDistance) {
                            closestSafeSpotDistance = distanceFromHome;
                            closestSafeSpot = safeSpot;
                        }
                    }
                }

                return closestSafeSpot;
            }).runAsync(location -> {
                if (location != null) {
                    BukkitExecutor.region(location.getWorld(), location.getBlockX() >> 4, location.getBlockZ() >> 4, () -> {
                        if (WorldBlocks.isSafeBlock(location.getBlock())) onResult.accept(location);
                        else findSafeSpotInChunk(island, islandChunks, islandsWorld, homeLocation, onResult);
                    });
                } else {
                    findSafeSpotInChunk(island, islandChunks, islandsWorld, homeLocation, onResult);
                }
            }).future().whenComplete((unused, error) -> { if (error != null) onResult.accept(null); });

        }).whenComplete((chunk, error) -> {
            if (error != null) onResult.accept(null);
        });
    }

    private static int[] captureSafeHeights(ChunkSnapshot snapshot, World world) {
        int[] heights = new int[256];
        java.util.Arrays.fill(heights, Integer.MIN_VALUE);
        int minY = plugin.getNMSWorld().getMinHeight(world);
        int maxY = world.getMaxHeight();
        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                int y = snapshot.getHighestBlockYAt(x, z);
                // Support both the highest block and highest standing-position conventions.
                if (y > minY && y + 2 < maxY && WorldBlocks.isSafeStandingSpot(snapshot, x, y + 1, z))
                    heights[(x << 4) | z] = y + 1;
                else if (y - 1 > minY && y + 1 < maxY && WorldBlocks.isSafeStandingSpot(snapshot, x, y, z))
                    heights[(x << 4) | z] = y;
            }
        }
        return heights;
    }

    private static void teleportEntity(Entity entity, Location location, @Nullable Consumer<PlayerTeleportAlgorithm.TeleportResult> teleportResult) {
        java.util.concurrent.atomic.AtomicBoolean completed = new java.util.concurrent.atomic.AtomicBoolean();
        Consumer<PlayerTeleportAlgorithm.TeleportResult> reply = result -> {
            if (completed.compareAndSet(false, true) && teleportResult != null) teleportResult.accept(result);
        };
        BukkitExecutor.entity(entity, () -> {
            try {
                Player player = BukkitExecutor.isFolia() && entity instanceof Player && !entity.hasMetadata("NPC") ? (Player) entity : null;
                Location from = player == null ? null : player.getLocation();
                Island origin = from == null ? null : plugin.getGrid().getIslandAt(from);
                FoliaPlayerPresence.Target target = player == null ? null : FoliaPlayerPresence.capture(plugin, location);
                if (player != null) {
                    SuperiorPlayer account = plugin.getPlayers().getSuperiorPlayer(player);
                    if (!(account instanceof SuperiorNPCPlayer) &&
                            (!FoliaPlayerPresence.isCurrent(plugin, target) || plugin.getServices()
                                    .getService(RegionManagerService.class).handlePlayerTeleport(account, from, location) != MoveResult.SUCCESS ||
                                    !FoliaPlayerPresence.isCurrent(plugin, target))) {
                        FoliaPlayerPresence.reconcile(plugin, player, target.island, FoliaPlayerPresence.capture(plugin, from));
                        reply.accept(PlayerTeleportAlgorithm.TeleportResult.GENERAL_FAILURE);
                        return;
                    }
                }
                entity.eject();
                plugin.getProviders().getAsyncProvider().teleport(entity, location, res -> {
                    Runnable finish = () -> {
                        try {
                            boolean success = Boolean.TRUE.equals(res);
                            if (player != null) {
                                if (success) success = FoliaPlayerPresence.reconcile(plugin, player, origin, target);
                                else if (player.isOnline() && player.isValid())
                                    FoliaPlayerPresence.reconcile(plugin, player, target.island,
                                            FoliaPlayerPresence.capture(plugin, player.getLocation()));
                            }
                            reply.accept(success ? PlayerTeleportAlgorithm.TeleportResult.SUCCESS : PlayerTeleportAlgorithm.TeleportResult.GENERAL_FAILURE);
                        } catch (Throwable failure) {
                            plugin.getLogger().warning("Player teleport arrival reconciliation failed: " + failure);
                            reply.accept(PlayerTeleportAlgorithm.TeleportResult.GENERAL_FAILURE);
                        }
                    };
                    try { BukkitExecutor.entity(entity, finish, 0L, () -> reply.accept(PlayerTeleportAlgorithm.TeleportResult.GENERAL_FAILURE)); }
                    catch (java.util.concurrent.RejectedExecutionException stopping) { reply.accept(PlayerTeleportAlgorithm.TeleportResult.GENERAL_FAILURE); }
                });
            } catch (Throwable failure) {
                plugin.getLogger().warning("Player teleport failed before physical arrival: " + failure);
                reply.accept(PlayerTeleportAlgorithm.TeleportResult.GENERAL_FAILURE);
            }
        }, 0L, () -> reply.accept(PlayerTeleportAlgorithm.TeleportResult.GENERAL_FAILURE));
    }

    private static Location adjustLocationToHome(Island island, Block block, float yaw, float pitch) {
        Location newHomeLocation;

        try (ObjectsPools.Wrapper<Location> wrapper = ObjectsPools.LOCATION.obtain()) {
            Location location = block.getLocation(wrapper.getHandle()).add(0.5, 0, 0.5);
            location.setYaw(yaw);
            location.setPitch(pitch);

            PluginEvent<PluginEventArgs.IslandSetHome> event = PluginEventsFactory.callIslandSetHomeEvent(
                    island, (SuperiorPlayer) null, location, IslandSetHomeEvent.Reason.SAFE_HOME);

            if (event.isCancelled()) {
                newHomeLocation = location.clone();
            } else {
                newHomeLocation = event.getArgs().islandHome.clone();
                island.setIslandHome(newHomeLocation);
            }
        }

        Log.debugResult(Debug.FIND_SAFE_TELEPORT, "Result Location", newHomeLocation);

        return newHomeLocation;
    }

    public interface TeleportCallback {

        void accept(boolean afterWarmup);

    }

}

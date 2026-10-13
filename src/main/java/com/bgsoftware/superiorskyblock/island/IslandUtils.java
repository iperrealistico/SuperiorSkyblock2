package com.bgsoftware.superiorskyblock.island;

import com.bgsoftware.superiorskyblock.core.logging.Log;

import com.bgsoftware.superiorskyblock.SuperiorSkyblockPlugin;
import com.bgsoftware.superiorskyblock.api.enums.BorderColor;
import com.bgsoftware.superiorskyblock.api.enums.MemberRemoveReason;
import com.bgsoftware.superiorskyblock.api.island.Island;
import com.bgsoftware.superiorskyblock.api.island.IslandChunkFlags;
import com.bgsoftware.superiorskyblock.api.island.PlayerRole;
import com.bgsoftware.superiorskyblock.api.key.Key;
import com.bgsoftware.superiorskyblock.api.upgrades.cost.UpgradeCost;
import com.bgsoftware.superiorskyblock.api.world.Dimension;
import com.bgsoftware.superiorskyblock.api.world.WorldInfo;
import com.bgsoftware.superiorskyblock.api.wrappers.BlockPosition;
import com.bgsoftware.superiorskyblock.api.wrappers.SuperiorPlayer;
import com.bgsoftware.superiorskyblock.core.ChunkPosition;
import com.bgsoftware.superiorskyblock.core.EnumHelper;
import com.bgsoftware.superiorskyblock.core.ObjectsPools;
import com.bgsoftware.superiorskyblock.core.SequentialListBuilder;
import com.bgsoftware.superiorskyblock.core.collections.ArrayMap;
import com.bgsoftware.superiorskyblock.core.collections.EnumerateMap;
import com.bgsoftware.superiorskyblock.core.events.plugin.PluginEventsFactory;
import com.bgsoftware.superiorskyblock.core.formatting.Formatters;
import com.bgsoftware.superiorskyblock.core.messages.Message;
import com.bgsoftware.superiorskyblock.core.threads.SynchronizedTasks;
import com.bgsoftware.superiorskyblock.core.threads.BukkitExecutor;
import com.bgsoftware.superiorskyblock.island.privilege.IslandPrivileges;
import com.bgsoftware.superiorskyblock.island.role.SPlayerRole;
import com.bgsoftware.superiorskyblock.world.chunk.ChunkLoadReason;
import com.bgsoftware.superiorskyblock.world.chunk.ChunksProvider;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Biome;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.inventory.Inventory;
import org.bukkit.scheduler.BukkitTask;

import java.util.Collection;
import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Queue;
import java.util.Set;
import java.util.Collections;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

public class IslandUtils {

    private static final SuperiorSkyblockPlugin plugin = SuperiorSkyblockPlugin.getPlugin();
    private static final EnumerateMap<Dimension, Biome> DEFAULT_WORLD_BIOMES = new EnumerateMap<>(Dimension.values());

    public static final String DEFAULT_NONE_VALUE = "None";

    public static void disbandWithFeedback(Island island, org.bukkit.command.CommandSender sender, String actorName, Runnable success) {
        List<SuperiorPlayer> audience = island.getIslandMembers(true);
        SuperiorPlayer owner = island.getOwner();
        boolean refund = com.bgsoftware.superiorskyblock.module.BuiltinModules.BANK.getConfiguration().hasDisbandRefund();
        String refundValue = refund ? Formatters.NUMBER_FORMATTER.format(island.getIslandBank().getBalance().multiply(
                com.bgsoftware.superiorskyblock.module.BuiltinModules.BANK.getConfiguration().getDisbandRefund())) : "";
        island.disbandIslandAsync().whenComplete((unused, error) -> {
            Runnable feedback = () -> {
                if (error != null || com.bgsoftware.superiorskyblock.core.database.sql.DBSession.isCommitOutcomeUncertain()) {
                    if (error != null) Log.error(error, "Island deletion did not complete; its durable intent remains reserved");
                    Message.CUSTOM.send(sender, "&cThe island deletion is incomplete or uncertain. Staff must check recovery before retrying.", true);
                    return;
                }
                audience.forEach(member -> member.runIfOnline(player -> Message.DISBAND_ANNOUNCEMENT.send(member, actorName)));
                if (refund) owner.runIfOnline(player -> Message.DISBAND_ISLAND_BALANCE_REFUND.send(owner, refundValue));
                success.run();
            };
            if (sender instanceof org.bukkit.entity.Player) plugin.getPlayers().getSuperiorPlayer(sender).runIfOnline(player -> feedback.run());
            else feedback.run();
        });
    }
    private static final Object cropRefreshLock = new Object();
    private static final Map<UUID, CropRefreshJob> cropRefreshJobs = new HashMap<>();
    private static final Queue<CropRefreshJob> cropRefreshReady = new ArrayDeque<>();
    private static final Queue<CropRefreshJob> cropCapturePending = new ArrayDeque<>();
    private static BukkitTask cropRefreshTask;
    private static BukkitTask cropCaptureTask;

    /** Coalesced, loaded-only recalibration; no cell scan or chunk generation. */
    public static void recalibrateLoadedCropChunks(Island island) {
        CropRefreshJob job;
        synchronized (cropRefreshLock) {
            job = cropRefreshJobs.get(island.getUniqueId());
            if (job != null) { job.dirty = true; return; }
            job = new CropRefreshJob(island);
            cropRefreshJobs.put(island.getUniqueId(), job);
        }
        scheduleCropCapture(job);
    }

    private static void scheduleCropCapture(CropRefreshJob job) {
        synchronized (cropRefreshLock) {
            try {
                cropCapturePending.add(job);
                if (cropCaptureTask == null)
                    cropCaptureTask = BukkitExecutor.globalRepeating(IslandUtils::captureNextCropJob, 1L, 1L);
            } catch (Throwable stopping) {
                cropCapturePending.remove(job);
                cropRefreshJobs.remove(job.island.getUniqueId(), job);
            }
        }
    }

    private static void captureNextCropJob() {
        CropRefreshJob job;
        synchronized (cropRefreshLock) {
            job = cropCapturePending.poll();
            if (cropCapturePending.isEmpty() && cropCaptureTask != null) {
                cropCaptureTask.cancel();
                cropCaptureTask = null;
            }
        }
        if (job != null) {
            try { captureLoadedCropChunks(job); }
            catch (Throwable failure) {
                synchronized (cropRefreshLock) {
                    cropRefreshJobs.remove(job.island.getUniqueId(), job);
                    cropRefreshReady.remove(job);
                }
                Log.error(failure, "Could not capture loaded crop chunks for island " + job.island.getUniqueId());
            }
        }
    }

    private static void captureLoadedCropChunks(CropRefreshJob job) {
        synchronized (cropRefreshLock) {
            if (cropRefreshJobs.get(job.island.getUniqueId()) != job)
                return;
            job.dirty = false;
        }
        if (plugin.getGrid().getIslandByUUID(job.island.getUniqueId()) != job.island) {
            synchronized (cropRefreshLock) { cropRefreshJobs.remove(job.island.getUniqueId(), job); }
            return;
        }
        Queue<ChunkPosition> positions = new ArrayDeque<>();
        Set<UUID> seenWorlds = new HashSet<>();
        for (Dimension dimension : Dimension.values()) {
            if (plugin.getProviders().getWorldsProvider().isDimensionEnabled(dimension) && job.island.wasSchematicGenerated(dimension)) {
                World world = job.island.getCenter(dimension).getWorld();
                collectLoadedCropPositions(job.island, world, seenWorlds, positions);
            }
        }
        for (World world : plugin.getGrid().getRegisteredWorlds())
            collectLoadedCropPositions(job.island, world, seenWorlds, positions);
        synchronized (cropRefreshLock) {
            job.positions = positions;
            cropRefreshReady.add(job);
            if (cropRefreshTask == null)
                cropRefreshTask = BukkitExecutor.asyncRepeating(IslandUtils::drainCropRefreshes, 1L, 1L);
        }
    }

    private static void collectLoadedCropPositions(Island island, World world, Set<UUID> seenWorlds,
                                                   Queue<ChunkPosition> positions) {
        if (world == null || !seenWorlds.add(world.getUID()))
            return;
        // Read identities only; live chunk access occurs later on its owner.
        for (Chunk chunk : world.getLoadedChunks()) {
            if (island.isInside(world, chunk.getX(), chunk.getZ()))
                positions.add(ChunkPosition.of(world, chunk.getX(), chunk.getZ(), false));
        }
    }

    private static void drainCropRefreshes() {
        List<CropRefreshWork> work = new ArrayList<>();
        List<CropRefreshJob> recapture = new ArrayList<>();
        synchronized (cropRefreshLock) {
            int budget = Math.max(1, Math.min(8, plugin.getPvcConfig().getChunkLoadConcurrency()));
            for (int i = 0; i < budget && !cropRefreshReady.isEmpty(); i++) {
                CropRefreshJob job = cropRefreshReady.remove();
                ChunkPosition position = job.positions.poll();
                if (position != null)
                    work.add(new CropRefreshWork(job.island, position));
                if (!job.positions.isEmpty())
                    cropRefreshReady.add(job);
                else if (job.dirty)
                    recapture.add(job);
                else
                    cropRefreshJobs.remove(job.island.getUniqueId(), job);
            }
            if (cropRefreshReady.isEmpty() && cropRefreshTask != null) {
                cropRefreshTask.cancel();
                cropRefreshTask = null;
            }
        }
        for (CropRefreshWork item : work) {
            World world = item.position.getWorld();
            if (world == null)
                continue;
            try {
                BukkitExecutor.region(world, item.position.getX(), item.position.getZ(), () -> {
                    if (plugin.getGrid().getIslandByUUID(item.island.getUniqueId()) != item.island ||
                            !world.isChunkLoaded(item.position.getX(), item.position.getZ()))
                        return;
                    Chunk chunk = world.getChunkAt(item.position.getX(), item.position.getZ());
                    plugin.getNMSChunks().startTickingChunk(item.island, chunk, !item.island.isInsideRange(chunk));
                }, 1L);
            } catch (Throwable failure) { Log.error(failure, "Could not schedule loaded crop recalibration"); }
        }
        recapture.forEach(IslandUtils::scheduleCropCapture);
    }

    private static final class CropRefreshJob {
        final Island island;
        Queue<ChunkPosition> positions;
        boolean dirty;
        CropRefreshJob(Island island) { this.island = island; }
    }

    private static final class CropRefreshWork {
        final Island island;
        final ChunkPosition position;
        CropRefreshWork(Island island, ChunkPosition position) { this.island = island; this.position = position; }
    }

    private static Biome getDefaultBiomeForEnvironment(World.Environment environment) {
        switch (environment) {
            case NORMAL:
                return Biome.PLAINS;
            case NETHER:
                return Optional.ofNullable(EnumHelper.getEnum(Biome.class, "NETHER_WASTES", "NETHER", "HELL"))
                        .orElseThrow(IllegalArgumentException::new);
            case THE_END:
                return Optional.ofNullable(EnumHelper.getEnum(Biome.class, "THE_END", "SKY"))
                        .orElseThrow(IllegalArgumentException::new);
        }

        throw new IllegalArgumentException();
    }

    static {
        for (Dimension dimension : Dimension.values()) {
            Biome biome = Optional.ofNullable(plugin.getSettings().getWorlds().getDimensionConfig(dimension))
                    .map(config -> plugin.getNMSAlgorithms().getBiome(config.getBiome()))
                    .orElseGet(() -> getDefaultBiomeForEnvironment(dimension.getEnvironment()));
            DEFAULT_WORLD_BIOMES.put(dimension, biome);
        }
    }

    private IslandUtils() {

    }

    public static List<ChunkPosition> getChunkCoords(Island island, WorldInfo worldInfo, @IslandChunkFlags int flags) {
        List<ChunkPosition> chunkCoords = new LinkedList<>();

        boolean onlyProtected = (flags & IslandChunkFlags.ONLY_PROTECTED) != 0;
        boolean noEmptyChunks = (flags & IslandChunkFlags.NO_EMPTY_CHUNKS) != 0;

        BlockPosition min = onlyProtected ? island.getMinimumProtectedPosition() : island.getMinimumPosition();
        BlockPosition max = onlyProtected ? island.getMaximumProtectedPosition() : island.getMaximumPosition();

        for (int x = min.getX() >> 4; x <= max.getX() >> 4; x++) {
            for (int z = min.getZ() >> 4; z <= max.getZ() >> 4; z++) {
                if (!noEmptyChunks || island.isChunkDirty(worldInfo.getName(), x, z)) {
                    chunkCoords.add(ChunkPosition.of(worldInfo, x, z, false));
                }
            }
        }

        return chunkCoords;
    }

    public static Map<WorldInfo, List<ChunkPosition>> getChunkCoords(Island island, @IslandChunkFlags int flags) {
        Map<WorldInfo, List<ChunkPosition>> chunkCoords = new ArrayMap<>();

        for (Dimension dimension : Dimension.values()) {
            if (plugin.getProviders().getWorldsProvider().isDimensionEnabled(dimension) && island.wasSchematicGenerated(dimension)) {
                WorldInfo worldInfo = plugin.getGrid().getIslandsWorldInfo(island, dimension);
                List<ChunkPosition> chunkPositions = getChunkCoords(island, worldInfo, flags);
                if (!chunkPositions.isEmpty())
                    chunkCoords.put(worldInfo, chunkPositions);
            }
        }

        for (World registeredWorld : plugin.getGrid().getRegisteredWorlds()) {
            WorldInfo worldInfo = WorldInfo.of(registeredWorld);
            List<ChunkPosition> chunkPositions = getChunkCoords(island, worldInfo, flags);
            if (!chunkPositions.isEmpty())
                chunkCoords.put(worldInfo, chunkPositions);
        }

        return chunkCoords;
    }

    public static List<CompletableFuture<Chunk>> getAllChunksAsync(Island island, WorldInfo worldInfo, @IslandChunkFlags int flags,
                                                                   ChunkLoadReason chunkLoadReason,
                                                                   Consumer<Chunk> onChunkLoad) {
        List<ChunkPosition> positions = IslandUtils.getChunkCoords(island, worldInfo, flags);
        List<CompletableFuture<Chunk>> futures = new ArrayList<>(positions.size());
        for (int i = 0; i < positions.size(); i++)
            futures.add(new CompletableFuture<>());
        AtomicInteger cursor = new AtomicInteger();
        int workers = BukkitExecutor.isFolia() ? 1 : Math.min(8, plugin.getPvcConfig().getChunkLoadConcurrency());
        for (int i = 0; i < workers; i++)
            loadNextIslandChunk(positions, futures, cursor, chunkLoadReason, onChunkLoad);
        return futures;
    }

    private static void loadNextIslandChunk(List<ChunkPosition> positions, List<CompletableFuture<Chunk>> futures,
                                           AtomicInteger cursor, ChunkLoadReason reason, Consumer<Chunk> consumer) {
        int index = cursor.getAndIncrement();
        if (index >= positions.size())
            return;
        ChunksProvider.loadChunk(positions.get(index), reason, consumer).whenComplete((chunk, failure) -> {
            if (failure == null)
                futures.get(index).complete(chunk);
            else
                futures.get(index).completeExceptionally(failure);
            try { BukkitExecutor.async(() -> loadNextIslandChunk(positions, futures, cursor, reason, consumer)); }
            catch (Throwable stopping) {
                for (CompletableFuture<Chunk> future : futures)
                    future.completeExceptionally(stopping);
            }
        });
    }

    public static List<CompletableFuture<Chunk>> getAllChunksAsync(Island island, @IslandChunkFlags int flags,
                                                                   ChunkLoadReason chunkLoadReason,
                                                                   Consumer<Chunk> onChunkLoad) {
        List<CompletableFuture<Chunk>> chunkCoords = new LinkedList<>();

        for (Dimension dimension : Dimension.values()) {
            if (plugin.getProviders().getWorldsProvider().isDimensionEnabled(dimension) && island.wasSchematicGenerated(dimension)) {
                WorldInfo worldInfo = plugin.getGrid().getIslandsWorldInfo(island, dimension);
                chunkCoords.addAll(getAllChunksAsync(island, worldInfo, flags, chunkLoadReason, onChunkLoad));
            }
        }

        for (World registeredWorld : plugin.getGrid().getRegisteredWorlds()) {
            chunkCoords.addAll(getAllChunksAsync(island, WorldInfo.of(registeredWorld), flags, chunkLoadReason, onChunkLoad));
        }

        return chunkCoords;
    }

    public static boolean hasEnoughBalance(List<UpgradeCost> upgradeCosts, SuperiorPlayer superiorPlayer) {
        for (UpgradeCost upgradeCost : upgradeCosts) {
            if (!upgradeCost.hasEnoughBalance(superiorPlayer)) {
                return false;
            }
        }

        return true;
    }

    public static void updateIslandFly(Island island, SuperiorPlayer superiorPlayer) {
        superiorPlayer.runIfOnline(player -> {
            if (!player.getAllowFlight() && superiorPlayer.hasIslandFlyEnabled() && island.hasPermission(superiorPlayer, IslandPrivileges.FLY)) {
                player.setAllowFlight(true);
                player.setFlying(true);
                Message.ISLAND_FLY_ENABLED.send(player);
            } else if (player.getAllowFlight() && !island.hasPermission(superiorPlayer, IslandPrivileges.FLY)) {
                player.setAllowFlight(false);
                player.setFlying(false);
                Message.ISLAND_FLY_DISABLED.send(player);
            }
        });
    }

    public static void updateTradingMenus(Island island, SuperiorPlayer superiorPlayer) {
        superiorPlayer.runIfOnline(player -> {
            Inventory openInventory = player.getOpenInventory().getTopInventory();
            if (openInventory != null && openInventory.getType() == InventoryType.MERCHANT && !island.hasPermission(superiorPlayer, IslandPrivileges.VILLAGER_TRADING))
                player.closeInventory();
        });
    }

    public static void resetChunksExcludedFromList(Island island, Collection<ChunkPosition> excludedChunkPositions) {
        resetChunksExcludedFromListAsync(island, excludedChunkPositions).whenComplete((unused, error) -> {
            if (error != null) Log.error(error, "Excluded-chunk cleanup did not complete");
        });
    }

    public static CompletableFuture<Void> resetChunksExcludedFromListAsync(Island island, Collection<ChunkPosition> excludedChunkPositions) {
        Map<WorldInfo, List<ChunkPosition>> chunksToDelete = IslandUtils.getChunkCoords(island, 0);
        java.util.Set<ChunkPosition> excluded = new java.util.HashSet<>(excludedChunkPositions);
        CompletableFuture<Void> chain = CompletableFuture.completedFuture(null);
        for (List<ChunkPosition> positions : chunksToDelete.values()) {
            positions.removeIf(excluded::contains);
            chain = chain.thenCompose(unused -> deleteExistingChunksAsync(island, positions));
        }
        return chain;
    }

    public static void sendMessage(Island island, Message message, List<UUID> ignoredMembers, Object... args) {
        for (SuperiorPlayer islandMember : island.getIslandMembers(true)) {
            if (!ignoredMembers.contains(islandMember.getUniqueId()))
                message.send(islandMember, args);
        }
    }

    public static double getGeneratorPercentageDecimal(Island island, Key key, Dimension dimension) {
        int totalAmount = island.getGeneratorTotalAmount(dimension);
        return totalAmount == 0 ? 0 : (island.getGeneratorAmount(key, dimension) * 100D) / totalAmount;
    }

    public static PlayerRole getPlayerRole(Island island, SuperiorPlayer superiorPlayer) {
        if (island.isMember(superiorPlayer)) {
            return superiorPlayer.getPlayerRole(island);
        } else if (island.isCoop(superiorPlayer)) {
            return SPlayerRole.coopRole();
        } else {
            return SPlayerRole.guestRole();
        }
    }

    public static boolean checkTransferRestrictions(SuperiorPlayer superiorPlayer, Island island, SuperiorPlayer targetPlayer) {
        if (!superiorPlayer.getPlayerRole(island).isLastRole()) {
            Message.NO_TRANSFER_PERMISSION.send(superiorPlayer);
            return false;
        }

        if (!island.isMember(targetPlayer)) {
            Message.PLAYER_NOT_INSIDE_ISLAND.send(superiorPlayer);
            return false;
        }

        if (island.getOwner().getUniqueId().equals(targetPlayer.getUniqueId())) {
            Message.TRANSFER_ALREADY_LEADER.send(superiorPlayer);
            return false;
        }

        return true;
    }

    public static void handleTransferIsland(SuperiorPlayer caller, Island island, SuperiorPlayer target) {
        if (!checkTransferRestrictions(caller, island, target)) return;
        island.transferIslandAsync(target).whenComplete((success, error) -> {
            if (error != null) { Log.error(error, "Cannot complete ownership transfer"); return; }
            if (Boolean.TRUE.equals(success)) IslandUtils.sendMessage(island, Message.TRANSFER_BROADCAST, Collections.emptyList(), target.getName());
        });
    }

    public static boolean checkKickRestrictions(SuperiorPlayer superiorPlayer, Island island, SuperiorPlayer targetPlayer) {
        if (!island.isMember(targetPlayer)) {
            Message.PLAYER_NOT_INSIDE_ISLAND.send(superiorPlayer);
            return false;
        }

        if (!targetPlayer.getPlayerRole(island).isLessThan(superiorPlayer.getPlayerRole(island))) {
            Message.KICK_PLAYERS_WITH_LOWER_ROLE.send(superiorPlayer);
            return false;
        }

        return true;
    }

    public static void handleLeaveIsland(SuperiorPlayer superiorPlayer, Island island) {
        if (!PluginEventsFactory.callIslandQuitEvent(island, superiorPlayer))
            return;

        island.removeMember(superiorPlayer, MemberRemoveReason.LEAVE);

        IslandUtils.sendMessage(island, Message.LEAVE_ANNOUNCEMENT, Collections.emptyList(), superiorPlayer.getName());

        Message.LEFT_ISLAND.send(superiorPlayer);
    }

    public static void handleKickPlayer(SuperiorPlayer caller, Island island, SuperiorPlayer target) {
        handleKickPlayer(caller, caller.getName(), island, target);
    }

    public static void handleKickPlayer(SuperiorPlayer caller, String callerName, Island island, SuperiorPlayer target) {
        if (!PluginEventsFactory.callIslandKickEvent(island, caller, target))
            return;

        island.removeMember(target, MemberRemoveReason.KICK);

        IslandUtils.sendMessage(island, Message.KICK_ANNOUNCEMENT, Collections.emptyList(), target.getName(), callerName);

        Message.GOT_KICKED.send(target, callerName);
    }

    public static boolean checkBanRestrictions(SuperiorPlayer superiorPlayer, Island island, SuperiorPlayer targetPlayer) {
        Island playerIsland = superiorPlayer.getIsland();
        if (playerIsland != null && playerIsland.isMember(targetPlayer) &&
                !targetPlayer.getPlayerRole(island).isLessThan(superiorPlayer.getPlayerRole(island))) {
            Message.BAN_PLAYERS_WITH_LOWER_ROLE.send(superiorPlayer);
            return false;
        }

        if (island.isBanned(targetPlayer)) {
            Message.PLAYER_ALREADY_BANNED.send(superiorPlayer);
            return false;
        }

        return true;
    }

    public static void handleBanPlayer(SuperiorPlayer caller, Island island, SuperiorPlayer target) {
        if (!PluginEventsFactory.callIslandBanEvent(island, caller, target))
            return;

        island.banMember(target, caller);

        IslandUtils.sendMessage(island, Message.BAN_ANNOUNCEMENT, Collections.emptyList(), target.getName(), caller.getName());

        Message.GOT_BANNED.send(target, island.getOwner().getName());
    }

    public static void deleteChunks(Island island, List<ChunkPosition> chunkPositions, Runnable onFinish) {
        if (BukkitExecutor.isFolia()) {
            deleteChunksAsync(island, chunkPositions).whenComplete((unused, error) -> {
                if (error == null && onFinish != null) onFinish.run();
                else if (error != null) plugin.getLogger().log(java.util.logging.Level.SEVERE, "Island reset failed; cell must remain reserved", error);
            });
            return;
        }
        SynchronizedTasks synchronizedTasks = new SynchronizedTasks(1, onFinish);
        chunkPositions.forEach(chunkPosition -> {
            plugin.getStackedBlocks().removeStackedBlocks(chunkPosition);
            PluginEventsFactory.callIslandChunkResetEvent(island, chunkPosition);
        });
        plugin.getNMSChunks().deleteChunks(island, chunkPositions, synchronizedTasks::notifyTaskComplete);
        synchronizedTasks.waitAllAsync();
    }

    public static CompletableFuture<Void> deleteChunksAsync(Island island, List<ChunkPosition> positions) {
        long generation = plugin.getPvcIslandService().getWorldGeneration(island.getUniqueId());
        CompletableFuture<Void> clearing;
        if (!BukkitExecutor.isFolia()) {
            CompletableFuture<Void> result = new CompletableFuture<>();
            deleteChunks(island, positions, () -> result.complete(null));
            clearing = result;
        } else clearing = com.bgsoftware.superiorskyblock.world.chunk.ChunksProvider.processExistingSequential(positions,
                ChunkLoadReason.DELETE_CHUNK, chunk -> {
                    ChunkPosition position = ChunkPosition.of(chunk, false);
                    plugin.getStackedBlocks().removeStackedBlocks(position);
                    PluginEventsFactory.callIslandChunkResetEvent(island, position);
                }).thenCompose(unused -> plugin.getNMSChunks().deleteChunksAsync(island, positions));
        return clearing.thenCompose(unused -> com.bgsoftware.superiorskyblock.world.chunk.ChunkStorageBridge.saveChunksAsync(island, generation, positions))
                .thenRun(() -> {
                    if (!plugin.getPvcIslandService().isCurrentWorldWriter(island, generation))
                        throw new java.util.concurrent.RejectedExecutionException("Island cleanup generation retired");
                    positions.forEach(position -> island.markChunkEmpty(position.getWorld(), position.getX(), position.getZ(), true));
                });
    }

    public static CompletableFuture<Void> deleteExistingChunksAsync(Island island, List<ChunkPosition> positions) {
        // Native region-file headers include persisted spill even if dirty-bit
        // coalescing was interrupted by a crash. Absent coordinates never load.
        return com.bgsoftware.superiorskyblock.world.chunk.ChunkStorageBridge.existingPositions(positions)
                .thenCompose(existing -> deleteChunksAsync(island, existing));
    }

    public static boolean isValidRoleForLimit(PlayerRole playerRole) {
        return playerRole.isRoleLadder() && !playerRole.isFirstRole() && !playerRole.isLastRole();
    }

    public static boolean isWarpNameLengthValid(String warpName) {
        return warpName.length() <= getMaxWarpNameLength();
    }

    public static int getMaxWarpNameLength() {
        return 255;
    }

    public static boolean handleBorderColorUpdate(SuperiorPlayer superiorPlayer, BorderColor borderColor) {
        if (!superiorPlayer.hasWorldBorderEnabled()) {
            if (!PluginEventsFactory.callPlayerToggleBorderEvent(superiorPlayer))
                return false;

            superiorPlayer.toggleWorldBorder();
        }

        if (!PluginEventsFactory.callPlayerChangeBorderColorEvent(superiorPlayer, borderColor))
            return false;

        superiorPlayer.setBorderColor(borderColor);
        try (ObjectsPools.Wrapper<Location> wrapper = ObjectsPools.LOCATION.obtain()) {
            plugin.getNMSWorld().setWorldBorder(superiorPlayer,
                    plugin.getGrid().getIslandAt(superiorPlayer.getLocation(wrapper.getHandle())));
        }

        Message.BORDER_PLAYER_COLOR_UPDATED.send(superiorPlayer,
                Formatters.BORDER_COLOR_FORMATTER.format(borderColor, superiorPlayer.getUserLocale()));

        return true;
    }

    public static Biome getDefaultWorldBiome(Dimension dimension) {
        return Objects.requireNonNull(DEFAULT_WORLD_BIOMES.get(dimension));
    }

    public static List<Biome> getDefaultWorldBiomes() {
        return new SequentialListBuilder<Biome>().build(DEFAULT_WORLD_BIOMES.values());
    }

}

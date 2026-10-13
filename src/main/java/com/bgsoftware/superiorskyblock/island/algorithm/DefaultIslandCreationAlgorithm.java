package com.bgsoftware.superiorskyblock.island.algorithm;

import com.bgsoftware.superiorskyblock.SuperiorSkyblockPlugin;
import com.bgsoftware.superiorskyblock.api.data.DatabaseBridgeMode;
import com.bgsoftware.superiorskyblock.api.island.Island;
import com.bgsoftware.superiorskyblock.api.schematic.Schematic;
import com.bgsoftware.superiorskyblock.api.world.algorithm.IslandCreationAlgorithm;
import com.bgsoftware.superiorskyblock.api.wrappers.BlockPosition;
import com.bgsoftware.superiorskyblock.api.wrappers.SuperiorPlayer;
import com.bgsoftware.superiorskyblock.core.Text;
import com.bgsoftware.superiorskyblock.core.events.args.PluginEventArgs;
import com.bgsoftware.superiorskyblock.core.events.plugin.PluginEvent;
import com.bgsoftware.superiorskyblock.core.events.plugin.PluginEventsFactory;
import com.bgsoftware.superiorskyblock.core.logging.Debug;
import com.bgsoftware.superiorskyblock.core.logging.Log;
import com.bgsoftware.superiorskyblock.core.profiler.ProfileType;
import com.bgsoftware.superiorskyblock.core.profiler.Profiler;
import com.bgsoftware.superiorskyblock.island.builder.IslandBuilderImpl;
import com.google.common.base.Preconditions;
import org.bukkit.Location;
import org.bukkit.block.BlockFace;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;

public class DefaultIslandCreationAlgorithm implements IslandCreationAlgorithm {

    private static final DefaultIslandCreationAlgorithm INSTANCE = new DefaultIslandCreationAlgorithm();

    private static final SuperiorSkyblockPlugin plugin = SuperiorSkyblockPlugin.getPlugin();

    private DefaultIslandCreationAlgorithm() {

    }

    public static DefaultIslandCreationAlgorithm getInstance() {
        return INSTANCE;
    }

    @Override
    public CompletableFuture<IslandCreationResult> createIsland(UUID islandUUID, SuperiorPlayer owner,
                                                                BlockPosition lastIsland, String islandName,
                                                                Schematic schematic) {
        Preconditions.checkNotNull(islandUUID, "islandUUID parameter cannot be null.");
        Preconditions.checkNotNull(owner, "owner parameter cannot be null.");
        Preconditions.checkNotNull(lastIsland, "lastIsland parameter cannot be null.");
        Preconditions.checkNotNull(islandName, "islandName parameter cannot be null.");
        Preconditions.checkNotNull(schematic, "schematic parameter cannot be null.");
        return createIsland(Island.newBuilder()
                        .setOwner(owner)
                        .setUniqueId(islandUUID)
                        .setName(islandName)
                        .setSchematicName(schematic.getName())
                , lastIsland);
    }

    @Override
    public CompletableFuture<IslandCreationResult> createIsland(Island.Builder builder, BlockPosition lastIsland) {
        Preconditions.checkNotNull(builder, "builder parameter cannot be null.");
        Preconditions.checkArgument(builder instanceof IslandBuilderImpl, "Cannot create an island from custom builder.");
        Preconditions.checkNotNull(lastIsland, "lastIsland parameter cannot be null.");

        try {
            return createIslandInternal((IslandBuilderImpl) builder, lastIsland);
        } catch (Throwable error) {
            CompletableFuture<IslandCreationResult> result = new CompletableFuture<>();
            result.completeExceptionally(error);
            return result;
        }
    }

    private CompletableFuture<IslandCreationResult> createIslandInternal(IslandBuilderImpl builder, BlockPosition lastIsland) {
        Schematic schematic = builder.islandType == null ? null : plugin.getSchematics().getSchematic(builder.islandType);

        Preconditions.checkArgument(builder.owner != null, "Cannot create an island from builder with no valid owner.");
        Preconditions.checkArgument(schematic != null, "Cannot create an island from builder with invalid schematic name.");

        com.bgsoftware.superiorskyblock.api.hooks.WorldsProvider worldsProvider = plugin.getProviders().getWorldsProvider();
        com.bgsoftware.superiorskyblock.api.hooks.WorldsProviderCapabilities.require(worldsProvider,
                com.bgsoftware.superiorskyblock.core.threads.BukkitExecutor.isFolia(),
                plugin.getPvcEntitlements().getVerifiedOwnershipLimit(builder.owner.getUniqueId()) > 1,
                plugin.getPvcConfig().getSizeMode() != com.bgsoftware.superiorskyblock.pvc.PvcConfig.SizeMode.LEGACY);

        Log.debug(Debug.CREATE_ISLAND, builder.owner.getName(), schematic.getName(), lastIsland);

        // Making sure an island with the same name does not exist.
        if (!Text.isBlank(builder.islandName) && plugin.getGrid().getIsland(builder.islandName) != null) {
            Log.debugResult(Debug.CREATE_ISLAND, "Creation Failed", "Island with the name " + builder.islandName + " already exists.");
            return CompletableFuture.completedFuture(new IslandCreationResult(IslandCreationResult.Status.NAME_OCCUPIED, null, null, false));
        }

        long profiler = Profiler.start(ProfileType.CREATE_ISLAND, schematic.getName());

        Location islandLocation;
        synchronized (plugin.getPvcIslandService().getPositionLock()) {
        islandLocation = worldsProvider.getNextLocation(
                plugin.getGrid().getLastIslandPosition(),
                plugin.getSettings().getIslandHeight(),
                plugin.getSettings().getMaxIslandSize(),
                builder.owner.getUniqueId(),
                builder.uuid
        );
        plugin.getGrid().setLastIslandPosition(com.bgsoftware.superiorskyblock.core.SBlockPosition.of(islandLocation));
        }

        Log.debugResult(Debug.CREATE_ISLAND, "Next Island Position", islandLocation);

        Island island = builder.setCenter(islandLocation.add(0.5, 0, 0.5)).build();
        plugin.getPvcIslandService().trackCandidate(island);

        island.getDatabaseBridge().setDatabaseBridgeMode(DatabaseBridgeMode.IDLE);

        PluginEvent<PluginEventArgs.IslandCreate> event = PluginEventsFactory.callIslandCreateEvent(island, builder.owner, builder.islandType, plugin.getSettings().isTeleportOnCreate());

        if (event.isCancelled()) {
            Log.debugResult(Debug.CREATE_ISLAND, "Creation Failed", "Event was cancelled for creating the island '" + builder.islandName + "'.");
            builder.owner.removeIslandMembership(island);
            return CompletableFuture.completedFuture(new IslandCreationResult(IslandCreationResult.Status.EVENT_CANCELLED, null, null, false));
        }

        CompletableFuture<IslandCreationResult> completableFuture = new CompletableFuture<>();

        // Clone location as pasteSchematic uses the same location in later scheduled tasks
        Location location = islandLocation.clone().add(-0.5, -1.0, -0.5);

        java.util.concurrent.atomic.AtomicBoolean finished = new java.util.concurrent.atomic.AtomicBoolean();
        java.util.function.Consumer<Throwable> failure = error -> {
            if (!finished.compareAndSet(false, true)) return;
            island.getDatabaseBridge().setDatabaseBridgeMode(DatabaseBridgeMode.SAVE_DATA);
            try { worldsProvider.finishIslandCreation(islandLocation, builder.owner.getUniqueId(), builder.uuid); }
            catch (Throwable cleanupError) { error.addSuppressed(cleanupError); }
            completableFuture.completeExceptionally(error);
            endProfile(profiler);
        };
        plugin.getPvcIslandService().recordCandidate(island).whenComplete((unused, recordError) -> {
            if (recordError != null) { failure.accept(recordError); return; }
            try {
                schematic.pasteSchematic(island, location, () -> {
                    try {
                        worldsProvider.finishIslandCreation(islandLocation, builder.owner.getUniqueId(), builder.uuid);
                        island.getDatabaseBridge().setDatabaseBridgeMode(DatabaseBridgeMode.SAVE_DATA);
                        java.util.List<IslandCreationResult.AffectedChunk> affected = null;
                        Runnable onTeleport = null;
                        if (schematic instanceof com.bgsoftware.superiorskyblock.world.schematic.BaseSchematic) {
                            com.bgsoftware.superiorskyblock.world.schematic.BaseSchematic base = (com.bgsoftware.superiorskyblock.world.schematic.BaseSchematic) schematic;
                            affected = new java.util.ArrayList<>();
                            for (com.bgsoftware.superiorskyblock.core.ChunkPosition position : base.getAffectedChunks())
                                affected.add(new IslandCreationResult.AffectedChunk(position.getWorldName(), position.getWorldsInfo().getDimension(), position.getX(), position.getZ()));
                            onTeleport = base.onTeleportCallback();
                        }
                        if (finished.compareAndSet(false, true)) completableFuture.complete(new IslandCreationResult(
                                IslandCreationResult.Status.SUCCESS, island, islandLocation, event.getArgs().canTeleport, affected, onTeleport));
                        endProfile(profiler);
                    } catch (Throwable error) { failure.accept(error); }
                }, failure);
            } catch (Throwable error) { failure.accept(error); }
        });

        return completableFuture;
    }

    private static void endProfile(long id) {
        try { Profiler.end(id); }
        catch (Throwable error) { Log.error(error, "Creation profiling failed; domain outcome is retained"); }
    }

}

package com.bgsoftware.superiorskyblock.island.container;

import com.bgsoftware.common.annotations.Nullable;
import com.bgsoftware.superiorskyblock.SuperiorSkyblockPlugin;
import com.bgsoftware.superiorskyblock.api.island.Island;
import com.bgsoftware.superiorskyblock.api.island.SortingType;
import com.bgsoftware.superiorskyblock.api.island.container.IslandsContainer;
import com.bgsoftware.superiorskyblock.api.world.Dimension;
import com.bgsoftware.superiorskyblock.api.world.WorldInfo;
import com.bgsoftware.superiorskyblock.api.wrappers.BlockPosition;
import com.bgsoftware.superiorskyblock.core.IslandPosition;
import com.bgsoftware.superiorskyblock.core.LazyWorldLocation;
import com.bgsoftware.superiorskyblock.core.SWorldPosition;
import com.bgsoftware.superiorskyblock.core.SequentialListBuilder;
import com.bgsoftware.superiorskyblock.core.events.plugin.PluginEventType;
import com.bgsoftware.superiorskyblock.core.logging.Log;
import com.bgsoftware.superiorskyblock.core.threads.BukkitExecutor;
import com.bgsoftware.superiorskyblock.core.threads.Synchronized;
import com.bgsoftware.superiorskyblock.island.IslandNames;
import com.bgsoftware.superiorskyblock.island.container.grid.IslandsGrid;
import com.bgsoftware.superiorskyblock.island.container.grid.MultiWorldIslandsGrid;
import com.bgsoftware.superiorskyblock.island.container.grid.SingleWorldIslandsGrid;
import com.bgsoftware.superiorskyblock.island.top.SortingComparators;
import com.bgsoftware.superiorskyblock.island.top.SortingTypes;
import com.bgsoftware.superiorskyblock.island.top.metadata.IslandSortMetadata;
import com.bgsoftware.superiorskyblock.island.top.metadata.IslandSortPlayerMetadata;
import com.bgsoftware.superiorskyblock.island.top.metadata.IslandSortRatingMetadata;
import com.bgsoftware.superiorskyblock.island.top.metadata.IslandSortValueMetadata;
import com.google.common.base.Preconditions;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;

import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class DefaultIslandsContainer implements IslandsContainer {

    private final Map<UUID, Island> islandsByUUID = new ConcurrentHashMap<>();
    private final Map<String, Island> islandsByNames = new ConcurrentHashMap<>();
    private IslandsGrid islandsGrid;

    private final Map<SortingType, Synchronized<List<Island>>> sortedIslands = new ConcurrentHashMap<>();
    private final java.util.Set<SortingType> notifiedValues = ConcurrentHashMap.newKeySet();
    private final Object sortingLock = new Object();
    private final Map<SortingType, List<Runnable>> sortingCallbacks = new java.util.HashMap<>();
    private long membershipRevision;

    private final SuperiorSkyblockPlugin plugin;

    public DefaultIslandsContainer(SuperiorSkyblockPlugin plugin) {
        this.plugin = plugin;
        this.islandsGrid = plugin.getProviders().hasCustomWorldsSupport() ?
                new MultiWorldIslandsGrid() : new SingleWorldIslandsGrid();
        plugin.getPluginEventsDispatcher().registerCallback(PluginEventType.WORLD_PROVIDER_UPDATE_EVENT, this::onWorldsProviderUpdate);
    }

    @Override
    public void addIsland(Island island) {
        addIslandToGrid(island);

        synchronized (sortingLock) {
            this.islandsByUUID.put(island.getUniqueId(), island);
            ++membershipRevision;
            sortedIslands.forEach((type, sorted) -> {
                sorted.write(list -> { if (!list.contains(island)) list.add(island); });
                notifiedValues.add(type);
            });
        }
        plugin.getPvcIslandService().register(island);
        if (!island.getStrippedName().isEmpty()) this.islandsByNames.put(IslandNames.getNameForLookup(island.getStrippedName()), island);

    }

    private void addIslandToGrid(Island island) {
        BlockPosition center = island.getCenterPosition();
        long packedPos = IslandPosition.calculatePackedPosFromLocation(center.getX(), center.getZ());

        if (plugin.getProviders().hasCustomWorldsSupport()) {
            // We don't know the logic of the custom worlds support, therefore we add a position
            // for every possible world, so there won't be issues with detecting islands later.
            for (Dimension dimension : Dimension.values()) {
                if (plugin.getProviders().getWorldsProvider().isDimensionEnabled(dimension)) {
                    WorldInfo worldInfo = plugin.getGrid().getIslandsWorldInfo(island, dimension);
                    if (worldInfo != null)
                        islandsGrid.addIsland(worldInfo.getName(), packedPos, island);
                }
            }
        } else {
            islandsGrid.addIsland(null, packedPos, island);
        }
    }

    @Override
    public void removeIsland(Island island) {
        BlockPosition center = island.getCenterPosition();
        long packedPos = IslandPosition.calculatePackedPosFromLocation(center.getX(), center.getZ());

        if (plugin.getProviders().hasCustomWorldsSupport()) {
            for (Dimension dimension : Dimension.values()) {
                if (plugin.getProviders().getWorldsProvider().isDimensionEnabled(dimension)) {
                    WorldInfo worldInfo = plugin.getGrid().getIslandsWorldInfo(island, dimension);
                    if (worldInfo != null)
                        islandsGrid.removeIslandAt(worldInfo.getName(), packedPos);
                }
            }
        } else {
            islandsGrid.removeIslandAt(null, packedPos);
        }

        synchronized (sortingLock) {
            this.islandsByUUID.remove(island.getUniqueId(), island);
            ++membershipRevision;
            sortedIslands.forEach((type, sorted) -> {
                sorted.write(list -> list.remove(island));
                notifiedValues.add(type);
            });
        }
        plugin.getPvcIslandService().unregister(island);
        this.islandsByNames.remove(IslandNames.getNameForLookup(island.getStrippedName()), island);

    }

    @Nullable
    @Override
    public Island getIslandByUUID(UUID uuid) {
        return this.islandsByUUID.get(uuid);
    }

    @Override
    public Island getIslandByName(String name) {
        return this.islandsByNames.get(IslandNames.getNameForLookup(name));
    }

    @Override
    public void updateIslandName(Island island, String oldName) {
        this.islandsByNames.remove(IslandNames.getNameForLookup(oldName), island);
        if (!island.getStrippedName().isEmpty()) this.islandsByNames.put(IslandNames.getNameForLookup(island.getStrippedName()), island);
    }

    @Nullable
    @Override
    public Island getIslandAtPosition(int position, SortingType sortingType) {
        ensureSortingType(sortingType);
        return this.sortedIslands.get(sortingType).readAndGet(sortedIslands -> {
            return position < 0 || position >= sortedIslands.size() ? null : sortedIslands.get(position);
        });
    }

    @Override
    public int getIslandPosition(Island island, SortingType sortingType) {
        ensureSortingType(sortingType);
        return this.sortedIslands.get(sortingType).readAndGet(sortedIslands -> {
            return sortedIslands.indexOf(island);
        });
    }

    @Override
    public int getIslandsAmount() {
        return this.islandsByUUID.size();
    }

    @Nullable
    @Override
    public Island getIslandAt(Location location) {
        Island island = plugin.getProviders().hasCustomWorldsSupport() ?
                customWorldsSupportIslandLookup(location) : nativeIslandLookup(location);
        return island == null || !island.isInside(SWorldPosition.of(location)) ? null : island;
    }

    private Island customWorldsSupportIslandLookup(Location location) {
        long packedPos = IslandPosition.calculatePackedPosFromLocation(location.getBlockX(), location.getBlockZ());
        String worldName = LazyWorldLocation.getWorldName(location);
        return this.islandsGrid.getIslandAt(worldName, packedPos);
    }

    private Island nativeIslandLookup(Location location) {
        // We first check that the world is an islands world.
        World world = location.getWorld();
        if (world == null || !plugin.getGrid().isIslandsWorld(world))
            return null;

        long packedPos = IslandPosition.calculatePackedPosFromLocation(location.getBlockX(), location.getBlockZ());
        return this.islandsGrid.getIslandAt(null, packedPos);
    }

    @Override
    public void sortIslands(SortingType sortingType, Runnable onFinish) {
        this.sortIslands(sortingType, false, onFinish);
    }

    @Override
    public void sortIslands(SortingType sortingType, boolean forceSort, @Nullable Runnable onFinish) {
        ensureSortingType(sortingType);

        boolean start;
        synchronized (sortingLock) {
            List<Runnable> callbacks = sortingCallbacks.get(sortingType);
            if (callbacks != null) {
                if (onFinish != null) callbacks.add(onFinish);
                if (forceSort) notifiedValues.add(sortingType);
                return;
            }
            boolean dirty = notifiedValues.remove(sortingType);
            start = forceSort || dirty;
            if (start) {
                callbacks = new java.util.ArrayList<>();
                if (onFinish != null) callbacks.add(onFinish);
                sortingCallbacks.put(sortingType, callbacks);
            }
        }
        if (!start) { if (onFinish != null) onFinish.run(); return; }
        try { BukkitExecutor.ensureAsync(() -> runAdmittedSort(sortingType)); }
        catch (Throwable failure) { finishSort(sortingType); throw failure; }
    }

    @Override
    public void notifyChange(SortingType sortingType, Island island) {
        notifiedValues.add(sortingType);
    }

    @Override
    public List<Island> getSortedIslands(SortingType sortingType) {
        ensureSortingType(sortingType);
        return this.sortedIslands.get(sortingType).readAndGet(sortedIslands ->
                new SequentialListBuilder<Island>().build(sortedIslands));
    }

    @Override
    public List<Island> getIslandsUnsorted() {
        return new SequentialListBuilder<Island>().build(this.islandsByUUID.values());
    }

    @Override
    public void addSortingType(SortingType sortingType, boolean sort) {
        synchronized (sortingLock) {
            Preconditions.checkArgument(!sortedIslands.containsKey(sortingType), "You cannot register an existing sorting type to the database.");
            sortedIslands.put(sortingType, Synchronized.of(new java.util.ArrayList<>(islandsByUUID.values())));
            notifiedValues.add(sortingType);
            sortingCallbacks.put(sortingType, new java.util.ArrayList<>());
        }
        // Initial registration retains the immediate ordering contract; all later sorts are async.
        try { sortIslandsInternal(sortingType, null); }
        finally { finishSort(sortingType); }
    }

    private void ensureSortingType(SortingType sortingType) {
        Preconditions.checkState(sortedIslands.containsKey(sortingType), "The sorting-type " + sortingType + " doesn't exist in the database. Please contact author!");
    }

    private void sortIslandsInternal(SortingType sortingType, @Nullable Runnable onFinish) {
        final long capturedRevision;
        List<Island> existingIslands;
        synchronized (sortingLock) {
            capturedRevision = membershipRevision;
            existingIslands = new java.util.ArrayList<>(islandsByUUID.values());
        }
        existingIslands.removeIf(Island::isIgnored);

        List<Island> sortedIslands;

        if (existingIslands.size() <= 1) {
            sortedIslands = existingIslands;
        } else try {
            if (sortingType == SortingTypes.BY_LEVEL || sortingType == SortingTypes.BY_WORTH || sortingType == SortingTypes.BY_BANK ||
                    sortingType == SortingTypes.BY_PLAYERS || sortingType == SortingTypes.BY_RATING) {
                sortedIslands = sortIslandsBuiltinSortingType(existingIslands, sortingType);
            } else {
                sortedIslands = existingIslands;
                sortedIslands.sort(sortingType);
            }
        } catch (Throwable error) {
            Log.warn("An error occurred while sorting islands for sorting-type ", sortingType.getName(), ":");
            throw error;
        }

        synchronized (sortingLock) {
            if (capturedRevision != membershipRevision) {
                sortedIslands = IslandListReconciler.reconcile(sortedIslands, islandsByUUID.values());
                notifiedValues.add(sortingType);
            }
            this.sortedIslands.get(sortingType).set(sortedIslands);
        }

        if (onFinish != null)
            onFinish.run();
    }

    private void runAdmittedSort(SortingType type) {
        try { sortIslandsInternal(type, null); }
        catch (Throwable failure) { notifiedValues.add(type); Log.error(failure, "Island sorting failed"); }
        finally { finishSort(type); }
    }

    private void finishSort(SortingType type) {
        List<Runnable> callbacks;
        synchronized (sortingLock) { callbacks = sortingCallbacks.remove(type); }
        if (callbacks != null) for (Runnable callback : callbacks) {
            try { callback.run(); } catch (Throwable failure) { Log.error(failure, "Island sorting completion failed"); }
        }
    }

    private List<Island> sortIslandsBuiltinSortingType(List<Island> existingIslands, SortingType sortingType) {
        List<IslandSortMetadata<?>> islandMetadatas = new LinkedList<>();

        if (sortingType == SortingTypes.BY_WORTH)
            existingIslands.forEach(island -> islandMetadatas.add(new IslandSortValueMetadata(island, island.getWorth())));
        else if (sortingType == SortingTypes.BY_LEVEL)
            existingIslands.forEach(island -> islandMetadatas.add(new IslandSortValueMetadata(island, island.getIslandLevel())));
        else if (sortingType == SortingTypes.BY_BANK)
            existingIslands.forEach(island -> islandMetadatas.add(new IslandSortValueMetadata(island, island.getIslandBank().getBalance())));
        else if (sortingType == SortingTypes.BY_RATING)
            existingIslands.forEach(island -> islandMetadatas.add(new IslandSortRatingMetadata(island)));
        else /* BY_PLAYERS */
            existingIslands.forEach(island -> islandMetadatas.add(new IslandSortPlayerMetadata(island)));

        if (islandMetadatas.size() > 1) {
            islandMetadatas.sort(SortingComparators.ISLAND_METADATA_COMPARATOR);
        }

        existingIslands.clear();
        islandMetadatas.forEach(islandMetadata -> existingIslands.add(islandMetadata.getIsland()));

        return existingIslands;
    }

    private void onWorldsProviderUpdate() {
        if (plugin.getProviders().hasCustomWorldsSupport() && islandsGrid instanceof SingleWorldIslandsGrid) {
            // We need to upgrade the grid to a MultiWorldIslandsGrid
            this.islandsGrid = new MultiWorldIslandsGrid();
            // Re-add all registered islands
            for (Island island : this.islandsByUUID.values()) {
                addIslandToGrid(island);
            }
        }
    }

}

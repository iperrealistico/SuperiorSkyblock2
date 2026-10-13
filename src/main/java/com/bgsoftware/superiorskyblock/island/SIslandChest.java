package com.bgsoftware.superiorskyblock.island;

import com.bgsoftware.superiorskyblock.SuperiorSkyblockPlugin;
import com.bgsoftware.superiorskyblock.api.island.Island;
import com.bgsoftware.superiorskyblock.api.island.IslandChest;
import com.bgsoftware.superiorskyblock.api.wrappers.SuperiorPlayer;
import com.bgsoftware.superiorskyblock.core.database.bridge.IslandsDatabaseBridge;
import com.bgsoftware.superiorskyblock.core.threads.BukkitExecutor;
import com.google.common.base.Preconditions;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

import java.util.Arrays;
import java.util.concurrent.atomic.AtomicBoolean;

public class SIslandChest implements IslandChest {

    private static final SuperiorSkyblockPlugin plugin = SuperiorSkyblockPlugin.getPlugin();
    private final AtomicBoolean updateFlag = new AtomicBoolean(false);
    private final Island island;
    private final int index;
    private volatile Inventory inventory = plugin.getProviders().getUIProvider().createInventory(
            this, 9, plugin.getSettings().getIslandChests().getChestTitle());
    private int contentsUpdateCounter = 0;
    private volatile ItemStack[] snapshot = new ItemStack[9];
    private volatile int rows = 1;
    private final java.util.concurrent.atomic.AtomicReference<java.util.UUID> viewerLease = new java.util.concurrent.atomic.AtomicReference<>();

    public SIslandChest(Island island, int index) {
        this.island = island;
        this.index = index;
    }

    public static SIslandChest createChest(Island island, int index, ItemStack[] contents) {
        SIslandChest islandChest = new SIslandChest(island, index);
        islandChest.inventory = plugin.getProviders().getUIProvider().createInventory(
                islandChest, contents.length, plugin.getSettings().getIslandChests().getChestTitle());
        islandChest.inventory.setContents(contents);
        islandChest.snapshot = copy(contents); islandChest.rows = contents.length / 9;
        return islandChest;
    }

    @Override
    public Island getIsland() {
        return island;
    }

    @Override
    public int getIndex() {
        return index;
    }

    @Override
    public int getRows() {
        return rows;
    }

    @Override
    public void setRows(int rows) {
        Preconditions.checkArgument(rows >= 1 && rows <= 6, "Chest rows must be between one and six");
        Runnable resize = () -> {
            try {
                updateFlag.set(true);
                ItemStack[] oldContents = getContents();
                for (int i = rows * 9; i < oldContents.length; i++)
                    Preconditions.checkArgument(oldContents[i] == null || oldContents[i].getType() == org.bukkit.Material.AIR,
                            "Empty the removed chest rows before shrinking the inventory");
                Inventory oldInventory = inventory;
                java.util.List<org.bukkit.entity.HumanEntity> viewers = new java.util.ArrayList<>(oldInventory.getViewers());
                inventory = plugin.getProviders().getUIProvider().createInventory(this, 9 * rows, plugin.getSettings().getIslandChests().getChestTitle());
                inventory.setContents(Arrays.copyOf(oldContents, 9 * rows));
                snapshot = copy(inventory.getContents()); this.rows = rows;
                viewers.forEach(humanEntity -> {
                    BukkitExecutor.entity(humanEntity, () -> {
                        if (humanEntity.getOpenInventory().getTopInventory().equals(oldInventory)) humanEntity.openInventory(inventory);
                    });
                });
            } finally {
                updateFlag.set(false);
            }
        };
        java.util.UUID holder = viewerLease.get();
        org.bukkit.entity.Player viewer = holder == null ? null : org.bukkit.Bukkit.getPlayer(holder);
        if (BukkitExecutor.isFolia() && viewer != null)
            BukkitExecutor.entity(viewer, () -> { snapshot = copy(inventory.getContents()); resize.run(); }, 0L, () -> { viewerLease.compareAndSet(holder, null); BukkitExecutor.global(resize); });
        else BukkitExecutor.global(resize);
    }

    @Override
    public ItemStack[] getContents() {
        return BukkitExecutor.isFolia() ? copy(snapshot) : inventory.getContents();
    }

    @Override
    public void openChest(SuperiorPlayer superiorPlayer) {
        Preconditions.checkNotNull(superiorPlayer, "superiorPlayer parameter cannot be null.");
        superiorPlayer.runIfOnline(player -> {
            if (BukkitExecutor.isFolia()) {
                java.util.UUID lease = viewerLease.get();
                if (lease != null && !lease.equals(player.getUniqueId())) {
                    org.bukkit.entity.Player existing = org.bukkit.Bukkit.getPlayer(lease);
                    if (existing == null || !existing.isOnline()) viewerLease.compareAndSet(lease, null);
                }
                if (!viewerLease.compareAndSet(null, player.getUniqueId()) && !player.getUniqueId().equals(viewerLease.get())) {
                    com.bgsoftware.superiorskyblock.pvc.PvcMessages.send(player, "This island chest is currently in use. Please try again shortly."); return;
                }
            }
            player.openInventory(getInventory());
        });
    }

    @Override
    public Inventory getInventory() {
        return inventory;
    }

    public boolean isUpdating() {
        return updateFlag.get();
    }

    public void updateContents() {
        if (BukkitExecutor.isFolia()) {
            java.util.UUID holder = viewerLease.get();
            org.bukkit.entity.Player viewer = holder == null ? null : org.bukkit.Bukkit.getPlayer(holder);
            if (viewer != null) BukkitExecutor.entity(viewer, () -> { snapshot = copy(inventory.getContents()); persistContents(); }, 1L,
                    () -> viewerLease.compareAndSet(holder, null));
            return;
        }
        persistContents();
    }

    public void onViewerClose(org.bukkit.entity.Player player, Inventory closedInventory) {
        if (updateFlag.get()) return;
        snapshot = copy(closedInventory.getContents());
        viewerLease.compareAndSet(player.getUniqueId(), null);
        persistContents();
    }

    private synchronized void persistContents() {
        if (++contentsUpdateCounter >= 50) {
            contentsUpdateCounter = 0;
            IslandsDatabaseBridge.saveIslandChest(island, this);
        } else {
            IslandsDatabaseBridge.markIslandChestsToBeSaved(island, this);
        }
    }

    private static ItemStack[] copy(ItemStack[] contents) {
        ItemStack[] result = new ItemStack[contents.length];
        for (int i = 0; i < contents.length; i++) result[i] = contents[i] == null ? null : contents[i].clone();
        return result;
    }

}

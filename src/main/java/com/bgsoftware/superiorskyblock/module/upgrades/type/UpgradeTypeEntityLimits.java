package com.bgsoftware.superiorskyblock.module.upgrades.type;

import com.bgsoftware.common.annotations.Nullable;
import com.bgsoftware.superiorskyblock.SuperiorSkyblockPlugin;
import com.bgsoftware.superiorskyblock.api.island.Island;
import com.bgsoftware.superiorskyblock.commands.ISuperiorCommand;
import com.bgsoftware.superiorskyblock.core.EnumHelper;
import com.bgsoftware.superiorskyblock.core.Materials;
import com.bgsoftware.superiorskyblock.core.ObjectsPools;
import com.bgsoftware.superiorskyblock.core.PlayerHand;
import com.bgsoftware.superiorskyblock.core.collections.AutoRemovalMap;
import com.bgsoftware.superiorskyblock.core.collections.CollectionsFactory;
import com.bgsoftware.superiorskyblock.core.collections.Location2ObjectMap;
import com.bgsoftware.superiorskyblock.core.collections.view.Int2ObjectMapView;
import com.bgsoftware.superiorskyblock.core.formatting.Formatters;
import com.bgsoftware.superiorskyblock.core.key.Keys;
import com.bgsoftware.superiorskyblock.core.messages.Message;
import com.bgsoftware.superiorskyblock.core.threads.BukkitExecutor;
import com.bgsoftware.superiorskyblock.island.algorithm.DefaultIslandEntitiesTrackerAlgorithm;
import com.bgsoftware.superiorskyblock.module.upgrades.commands.CmdAdminAddEntityLimit;
import com.bgsoftware.superiorskyblock.module.upgrades.commands.CmdAdminRemoveEntityLimit;
import com.bgsoftware.superiorskyblock.module.upgrades.commands.CmdAdminSetEntityLimit;
import com.bgsoftware.superiorskyblock.world.BukkitEntities;
import com.bgsoftware.superiorskyblock.world.BukkitItems;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.Animals;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.entity.EntityBreedEvent;
import org.bukkit.event.hanging.HangingPlaceEvent;
import org.bukkit.event.player.PlayerInteractAtEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.vehicle.VehicleCreateEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;

import java.lang.ref.WeakReference;
import java.util.Arrays;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

public class UpgradeTypeEntityLimits implements IUpgradeType {

    @Nullable
    private static final Material GOLDEN_DANDELION_TYPE = EnumHelper.getEnum(Material.class, "GOLDEN_DANDELION");

    private final com.google.common.cache.Cache<java.util.UUID, SpawningPlayerData> entityBreederPlayers =
            com.google.common.cache.CacheBuilder.newBuilder().maximumSize(4096).expireAfterWrite(2, TimeUnit.SECONDS).build();
    private final com.google.common.cache.Cache<com.bgsoftware.superiorskyblock.core.WorldBlockKey, SpawningPlayerData> vehiclesOwners =
            com.google.common.cache.CacheBuilder.newBuilder().maximumSize(4096).expireAfterWrite(2, TimeUnit.SECONDS).build();
    private final com.google.common.cache.Cache<SpawnPosition, SpawningPlayerData> spawnEggPlayers =
            com.google.common.cache.CacheBuilder.newBuilder().maximumSize(4096).expireAfterWrite(2, TimeUnit.SECONDS).build();

    private final SuperiorSkyblockPlugin plugin;

    public UpgradeTypeEntityLimits(SuperiorSkyblockPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public List<Listener> getListeners() {
        List<Listener> listeners = new LinkedList<>();

        listeners.add(new EntityLimitsListener());

        checkEntityBreedListener().ifPresent(listeners::add);
        checkEntityPlaceListener().ifPresent(listeners::add);

        return listeners;
    }

    @Override
    public List<ISuperiorCommand> getCommands() {
        return Arrays.asList(new CmdAdminAddEntityLimit(), new CmdAdminRemoveEntityLimit(), new CmdAdminSetEntityLimit());
    }

    private Optional<Listener> checkEntityBreedListener() {
        try {
            Class.forName("org.bukkit.event.entity.EntityBreedEvent");
            return Optional.of(new EntityLimitsBreedListener());
        } catch (ClassNotFoundException error) {
            return Optional.empty();
        }
    }

    private Optional<Listener> checkEntityPlaceListener() {
        try {
            Class.forName("org.bukkit.event.entity.EntityPlaceEvent");
            return Optional.of(new EntityLimitsPlaceListener());
        } catch (ClassNotFoundException error) {
            return Optional.empty();
        }
    }

    /** Default trackers reserve the event UUID atomically; external algorithms retain their existing contract. */
    private boolean hasReachedEntityLimit(Island island, Entity entity) {
        com.bgsoftware.superiorskyblock.api.key.Key key = Keys.of(entity);
        if (island.getEntitiesTracker() instanceof DefaultIslandEntitiesTrackerAlgorithm)
            return !((DefaultIslandEntitiesTrackerAlgorithm) island.getEntitiesTracker()).reserveEntitySlot(
                    entity.getUniqueId(), key, island.getEntityLimit(key));
        return island.hasReachedEntityLimit(key).join();
    }

    private class EntityLimitsListener implements Listener {


        @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
        public void onEntitySpawn(CreatureSpawnEvent e) {
            Entity entity = e.getEntity();
            EntityType entityType = entity.getType();

            if (BukkitEntities.canBypassEntityLimit(entity) || !BukkitEntities.canHaveLimit(entityType))
                return;

            Island island = plugin.getGrid().getIslandAt(e.getLocation());

            if (island == null)
                return;

            SpawningPlayerData spawningPlayerData = getSpawningPlayerFromSpawnEvent(e);
            Player spawningPlayer = spawningPlayerData == null ? null : spawningPlayerData.player.get();

            boolean hasReachedLimit = hasReachedEntityLimit(island, entity);

            if (hasReachedLimit) {
                e.setCancelled(true);
                if (spawningPlayer != null) BukkitExecutor.entity(spawningPlayer, () -> {
                    if (!spawningPlayer.isOnline()) return;
                    Message.REACHED_ENTITY_LIMIT.send(spawningPlayer, Formatters.CAPITALIZED_FORMATTER.format(entityType.toString()));
                });
                if (spawningPlayerData != null) spawningPlayerData.refundItems();
            }
        }

        @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
        public void onHangingPlace(HangingPlaceEvent e) {
            Entity entity = e.getEntity();
            EntityType entityType = entity.getType();

            if (BukkitEntities.canBypassEntityLimit(entity) || !BukkitEntities.canHaveLimit(entityType))
                return;

            Island island;
            try (ObjectsPools.Wrapper<Location> wrapper = ObjectsPools.LOCATION.obtain()) {
                island = plugin.getGrid().getIslandAt(entity.getLocation(wrapper.getHandle()));
            }

            if (island == null)
                return;

            boolean hasReachedLimit = hasReachedEntityLimit(island, entity);

            if (hasReachedLimit) {
                e.setCancelled(true);
                Message.REACHED_ENTITY_LIMIT.send(e.getPlayer(), Formatters.CAPITALIZED_FORMATTER.format(entityType.toString()));
            }
        }

        @EventHandler
        public void onVehicleSpawn(PlayerInteractEvent e) {
            if (e.getAction() != Action.RIGHT_CLICK_BLOCK)
                return;

            PlayerHand playerHand = BukkitItems.getHand(e);
            if (playerHand != PlayerHand.MAIN_HAND)
                return;

            ItemStack handItem = BukkitItems.getHandItem(e.getPlayer(), playerHand);
            if (handItem == null)
                return;

            Material handType = handItem.getType();

            // Check if minecart or boat
            boolean isMinecart = Materials.isRail(e.getClickedBlock().getType()) && Materials.isMinecart(handType);
            boolean isBoat = Materials.isBoat(handType);
            if (!isMinecart && !isBoat)
                return;

            try (ObjectsPools.Wrapper<Location> wrapper = ObjectsPools.LOCATION.obtain()) {
                Location blockLocation = e.getClickedBlock().getLocation(wrapper.getHandle());
                Island island = plugin.getGrid().getIslandAt(blockLocation);

                if (island == null)
                    return;

                Location futureEntitySpawnLocation = isMinecart ? blockLocation :
                        blockLocation.add(0, 1, 0);

                vehiclesOwners.put(com.bgsoftware.superiorskyblock.core.WorldBlockKey.of(futureEntitySpawnLocation), new SpawningPlayerData(e.getPlayer()));
            }
        }

        @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
        public void onVehicleSpawn(VehicleCreateEvent e) {
            Entity entity = e.getVehicle();
            EntityType entityType = entity.getType();

            if (BukkitEntities.canBypassEntityLimit(entity) || !BukkitEntities.canHaveLimit(entityType))
                return;

            Island island;
            SpawningPlayerData vehicleOwnerData;

            try (ObjectsPools.Wrapper<Location> wrapper = ObjectsPools.LOCATION.obtain()) {
                Location entityLocation = entity.getLocation(wrapper.getHandle());
                island = plugin.getGrid().getIslandAt(entityLocation);

                if (island == null)
                    return;

                vehicleOwnerData = vehiclesOwners.asMap().remove(com.bgsoftware.superiorskyblock.core.WorldBlockKey.of(entityLocation));
            }

            Player vehicleOwner = vehicleOwnerData == null ? null : vehicleOwnerData.player.get();

            boolean hasReachedLimit = hasReachedEntityLimit(island, entity);

            if (hasReachedLimit) {
                entity.remove();
                if (vehicleOwner != null) BukkitExecutor.entity(vehicleOwner, () -> {
                    if (vehicleOwner.isOnline()) Message.REACHED_ENTITY_LIMIT.send(vehicleOwner,
                            Formatters.CAPITALIZED_FORMATTER.format(entityType.toString()));
                });
            }
        }

        @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
        public void onSpawnEggUse(PlayerInteractEvent e) {
            if (e.getAction() != Action.RIGHT_CLICK_BLOCK || e.getItem() == null)
                return;

            PlayerHand usedHand = BukkitItems.getHand(e);
            ItemStack usedItem = BukkitItems.getHandItem(e.getPlayer(), usedHand);
            EntityType spawnEggEntityType = usedItem == null ? EntityType.UNKNOWN :
                    usedItem.getType() == Material.ARMOR_STAND ? EntityType.ARMOR_STAND :
                            BukkitItems.getEntityType(usedItem);

            if (spawnEggEntityType == EntityType.UNKNOWN || !BukkitEntities.canHaveLimit(spawnEggEntityType))
                return;

            Island island;
            try (ObjectsPools.Wrapper<Location> wrapper = ObjectsPools.LOCATION.obtain()) {
                island = plugin.getGrid().getIslandAt(e.getClickedBlock().getLocation(wrapper.getHandle()));
            }
            if (island == null)
                return;

            SpawningPlayerData pending = new SpawningPlayerData(e.getPlayer());
            spawnEggPlayers.put(new SpawnPosition(spawnEggEntityType, e.getClickedBlock().getRelative(e.getBlockFace()).getLocation()), pending);
            spawnEggPlayers.put(new SpawnPosition(spawnEggEntityType, e.getClickedBlock().getLocation()), pending);
        }

        @Nullable
        private SpawningPlayerData getSpawningPlayerFromSpawnEvent(CreatureSpawnEvent event) {
            EntityType entityType = event.getEntityType();
            if (entityType == EntityType.ARMOR_STAND) return claimEggActor(entityType, event.getLocation());

            switch (event.getSpawnReason()) {
                case SPAWNER_EGG:
                    return claimEggActor(entityType, event.getLocation());
                case BREEDING:
                    return entityBreederPlayers.asMap().remove(event.getEntity().getUniqueId());
            }

            return null;
        }

        private SpawningPlayerData claimEggActor(EntityType type, Location location) {
            SpawningPlayerData data = spawnEggPlayers.asMap().remove(new SpawnPosition(type, location));
            return data != null && data.claimed.compareAndSet(false, true) ? data : null;
        }

    }

    private class EntityLimitsPlaceListener implements Listener {

        @Nullable
        private final EntityType CUSHION_TYPE = EnumHelper.getEnum(EntityType.class, "CUSHION");

        @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
        public void onEntityPlace(org.bukkit.event.entity.EntityPlaceEvent e) {
            Entity entity = e.getEntity();
            EntityType entityType = entity.getType();

            if (entityType != CUSHION_TYPE || BukkitEntities.canBypassEntityLimit(entity)
                    || !BukkitEntities.canHaveLimit(entityType)) {
                return;
            }

            Island island;
            try (ObjectsPools.Wrapper<Location> wrapper = ObjectsPools.LOCATION.obtain()) {
                island = plugin.getGrid().getIslandAt(entity.getLocation(wrapper.getHandle()));
            }

            if (island == null) {
                return;
            }

            boolean hasReachedLimit = hasReachedEntityLimit(island, entity);

            if (hasReachedLimit) {
                entity.remove();
                Message.REACHED_ENTITY_LIMIT.send(e.getPlayer(), Formatters.CAPITALIZED_FORMATTER.format(entityType.toString()));
            }
        }

        @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = false)
        public void onEntityPlaceCompletion(org.bukkit.event.entity.EntityPlaceEvent event) {
            if (event.isCancelled() || event.getEntity().isDead())
                DefaultIslandEntitiesTrackerAlgorithm.releaseEntityReservation(event.getEntity().getUniqueId());
        }

    }

    private class EntityLimitsBreedListener implements Listener {

        private final com.google.common.cache.Cache<java.util.UUID, SpawningPlayerData> trackedBreedItems =
                com.google.common.cache.CacheBuilder.newBuilder().maximumSize(4096)
                        .expireAfterWrite(2, TimeUnit.MINUTES).build();

        @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
        public void onEntityBreed(EntityBreedEvent e) {
            Entity child = e.getEntity();
            EntityType childEntityType = child.getType();

            if (!(e.getBreeder() instanceof Player) || !BukkitEntities.canHaveLimit(childEntityType))
                return;

            Island island;
            try (ObjectsPools.Wrapper<Location> wrapper = ObjectsPools.LOCATION.obtain()) {
                island = plugin.getGrid().getIslandAt(child.getLocation(wrapper.getHandle()));
            }

            if (island == null)
                return;

            SpawningPlayerData father = trackedBreedItems.asMap().remove(e.getFather().getUniqueId());
            SpawningPlayerData mother = e.getFather().equals(e.getMother()) ? null :
                    trackedBreedItems.asMap().remove(e.getMother().getUniqueId());
            SpawningPlayerData breeding = new SpawningPlayerData((Player) e.getBreeder());
            if (father != null) breeding.refunds.addAll(father.refunds);
            if (mother != null) breeding.refunds.addAll(mother.refunds);
            entityBreederPlayers.put(child.getUniqueId(), breeding);
        }

        @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
        public void onEntityFeed(PlayerInteractAtEntityEvent e) {
            if (!(e.getRightClicked() instanceof Animals))
                return;

            PlayerHand usedHand = BukkitItems.getHand(e);
            ItemStack usedItem = BukkitItems.getHandItem(e.getPlayer(), usedHand);

            if (usedItem == null || (usedItem.getType() != GOLDEN_DANDELION_TYPE &&
                    !plugin.getNMSEntities().isAnimalFood(usedItem, (Animals) e.getRightClicked())))
                return;

            // We want to calculate the amount of items consumed by breeding this animal.
            // We do that by checking the held item one tick later, and subtracting the
            // amount after 1 tick of the item from the original amount.
            int mainHandSlot = e.getPlayer().getInventory().getHeldItemSlot();
            int originalAmount = usedItem.getAmount();
            ItemStack breedItem = usedItem.clone();
            java.util.UUID fedEntity = e.getRightClicked().getUniqueId();

            BukkitExecutor.entity(e.getPlayer(), () -> {
                ItemStack inventoryItem = usedHand == PlayerHand.MAIN_HAND ?
                        e.getPlayer().getInventory().getItem(mainHandSlot) :
                        BukkitItems.getHandItem(e.getPlayer(), PlayerHand.OFF_HAND);

                boolean isInventoryItemEmpty = inventoryItem == null || inventoryItem.getType() == Material.AIR;

                if (!isInventoryItemEmpty && !inventoryItem.isSimilar(breedItem))
                    return;

                int currAmount = isInventoryItemEmpty ? 0 : inventoryItem.getAmount();

                int consumedAmount = originalAmount - currAmount;
                if (consumedAmount <= 0)
                    return;

                breedItem.setAmount(consumedAmount);
                trackedBreedItems.put(fedEntity, new SpawningPlayerData(e.getPlayer(), breedItem));
            }, 5L);
        }

    }

    private static final class SpawnPosition {
        private final EntityType type;
        private final com.bgsoftware.superiorskyblock.core.WorldBlockKey position;
        SpawnPosition(EntityType type, Location location) { this.type = type; this.position = com.bgsoftware.superiorskyblock.core.WorldBlockKey.of(location); }
        @Override public boolean equals(Object value) {
            if (!(value instanceof SpawnPosition)) return false;
            SpawnPosition other = (SpawnPosition) value;
            return type == other.type && position.equals(other.position);
        }
        @Override public int hashCode() { return 31 * type.hashCode() + position.hashCode(); }
    }

    private static class SpawningPlayerData {

        private final WeakReference<Player> player;
        private final List<ItemRefund> refunds = new LinkedList<>();
        private final java.util.concurrent.atomic.AtomicBoolean claimed = new java.util.concurrent.atomic.AtomicBoolean();

        SpawningPlayerData(Player player) {
            this(player, (ItemStack) null);
        }

        SpawningPlayerData(Player player, @Nullable ItemStack itemStack) {
            this.player = new WeakReference<>(player);
            addItem(itemStack);
        }

        SpawningPlayerData(Player player, ItemStack... itemStacks) {
            this.player = new WeakReference<>(player);
            for (ItemStack itemStack : itemStacks)
                addItem(itemStack);
        }

        private void addItem(@Nullable ItemStack itemStack) {
            if (itemStack != null && itemStack.getType() != Material.AIR && itemStack.getAmount() > 0)
                this.refunds.add(new ItemRefund(this.player, itemStack.clone()));
        }

        private void refundItems() {
            for (ItemRefund refund : refunds) {
                Player actor = refund.player.get();
                if (actor == null) continue;
                BukkitExecutor.entity(actor, () -> {
                    if (!actor.isOnline()) return;
                    try (ObjectsPools.Wrapper<Location> wrapper = ObjectsPools.LOCATION.obtain()) {
                        BukkitItems.addItem(refund.item.clone(), actor.getInventory(), actor.getLocation(wrapper.getHandle()));
                    }
                });
            }
        }

    }

    private static final class ItemRefund {
        final WeakReference<Player> player;
        final ItemStack item;
        ItemRefund(WeakReference<Player> player, ItemStack item) { this.player = player; this.item = item; }
    }

}

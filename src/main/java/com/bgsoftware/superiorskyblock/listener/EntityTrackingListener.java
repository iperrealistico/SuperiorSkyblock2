package com.bgsoftware.superiorskyblock.listener;

import com.bgsoftware.superiorskyblock.SuperiorSkyblockPlugin;
import com.bgsoftware.superiorskyblock.api.service.world.WorldRecordService;
import com.bgsoftware.superiorskyblock.api.island.Island;
import com.bgsoftware.superiorskyblock.core.key.Keys;
import com.bgsoftware.superiorskyblock.island.algorithm.DefaultIslandEntitiesTrackerAlgorithm;
import com.bgsoftware.superiorskyblock.module.BuiltinModules;
import com.bgsoftware.superiorskyblock.module.upgrades.type.UpgradeTypeEntityLimits;
import com.bgsoftware.superiorskyblock.world.BukkitEntities;
import com.bgsoftware.superiorskyblock.core.LazyReference;
import com.bgsoftware.superiorskyblock.platform.event.GameEvent;
import com.bgsoftware.superiorskyblock.platform.event.GameEventPriority;
import com.bgsoftware.superiorskyblock.platform.event.GameEventType;
import com.bgsoftware.superiorskyblock.platform.event.args.GameEventArgs;
import org.bukkit.entity.Entity;

public class EntityTrackingListener extends AbstractGameEventListener {

    private final LazyReference<WorldRecordService> worldRecordService = new LazyReference<WorldRecordService>() {
        @Override
        protected WorldRecordService create() {
            return plugin.getServices().getService(WorldRecordService.class);
        }
    };

    public EntityTrackingListener(SuperiorSkyblockPlugin plugin) {
        super(plugin);
        this.registerListeners();
    }

    private void onEntitySpawn(GameEvent<GameEventArgs.EntitySpawnEvent> e) {
        Entity entity = e.getArgs().entity;
        try {
            // Monitor also receives cancellations so HIGHEST reservations cannot leak.
            if (e.isCancelled() || entity.isDead() || !plugin.getGrid().isIslandsWorld(entity.getWorld())) return;
            this.worldRecordService.get().recordEntitySpawn(entity);
        } finally {
            DefaultIslandEntitiesTrackerAlgorithm.releaseEntityReservation(entity.getUniqueId());
        }
    }

    private void onEntitySpawnAdmission(GameEvent<GameEventArgs.EntitySpawnEvent> event) {
        Entity entity = event.getArgs().entity;
        // These have dedicated Bukkit HIGHEST handlers, including their actor messages and refunds.
        if (entity instanceof org.bukkit.entity.LivingEntity || entity instanceof org.bukkit.entity.Hanging ||
                entity instanceof org.bukkit.entity.Vehicle) return;
        if (entity.isDead() || BukkitEntities.canBypassEntityLimit(entity) || !BukkitEntities.canHaveLimit(entity.getType()) ||
                !BuiltinModules.UPGRADES.isUpgradeTypeEnabled(UpgradeTypeEntityLimits.class)) return;
        Island island = plugin.getGrid().getIslandAt(entity.getLocation());
        if (island == null || !(island.getEntitiesTracker() instanceof DefaultIslandEntitiesTrackerAlgorithm)) return;
        com.bgsoftware.superiorskyblock.api.key.Key key = Keys.of(entity);
        if (!((DefaultIslandEntitiesTrackerAlgorithm) island.getEntitiesTracker()).reserveEntitySlot(
                entity.getUniqueId(), key, island.getEntityLimit(key))) event.setCancelled();
    }

    private void onEntityDeath(GameEvent<GameEventArgs.EntityDeathEvent> e) {
        Entity entity = e.getArgs().entity;

        // We do not care about spawn island, and therefore only island worlds are relevant.
        if (!plugin.getGrid().isIslandsWorld(entity.getWorld())) {
            return;
        }

        this.worldRecordService.get().recordEntityDespawn(entity);
    }

    /* INTERNAL */

    private void registerListeners() {
        registerCallback(GameEventType.ENTITY_SPAWN_EVENT, GameEventPriority.HIGHEST, this::onEntitySpawnAdmission);
        registerCallback(GameEventType.ENTITY_SPAWN_EVENT, GameEventPriority.MONITOR, false, this::onEntitySpawn);
        registerCallback(GameEventType.ENTITY_DEATH_EVENT, GameEventPriority.MONITOR, this::onEntityDeath);
    }

}

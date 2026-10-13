package com.bgsoftware.superiorskyblock.missions;

import com.bgsoftware.superiorskyblock.api.key.Key;
import com.bgsoftware.superiorskyblock.api.key.KeySet;
import com.bgsoftware.superiorskyblock.api.island.Island;
import com.bgsoftware.superiorskyblock.api.island.IslandExecutionScope;
import com.bgsoftware.superiorskyblock.api.missions.MissionLoadException;
import com.bgsoftware.superiorskyblock.api.wrappers.SuperiorPlayer;
import com.bgsoftware.superiorskyblock.SuperiorSkyblockPlugin;
import com.bgsoftware.superiorskyblock.core.threads.BukkitExecutor;
import com.bgsoftware.superiorskyblock.missions.common.BuiltinMission;
import com.bgsoftware.superiorskyblock.missions.common.Placeholders;
import com.bgsoftware.superiorskyblock.missions.common.requirements.KeyRequirements;
import com.bgsoftware.superiorskyblock.missions.common.tracker.KeyDataTracker;
import com.bgsoftware.wildstacker.api.WildStackerAPI;
import org.bukkit.Bukkit;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.projectiles.ProjectileSource;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;

public final class KillsMissions extends BuiltinMission<KeyDataTracker> implements Listener {

    private final Map<KeyRequirements, Integer> requiredEntities = new LinkedHashMap<>();

    private volatile Function<LivingEntity, Integer> getEntityCount = entity -> 1;

    @Override
    protected void loadConfiguration(ConfigurationSection section) throws MissionLoadException {
        ConfigurationSection requiredEntitiesSection = section.getConfigurationSection("required-entities");

        if (requiredEntitiesSection == null)
            throw new MissionLoadException("You must have the \"required-entities\" section in the config.");

        for (String key : requiredEntitiesSection.getKeys(false)) {
            KeySet entityTypes = KeySet.createKeySet();
            section.getStringList("required-entities." + key + ".types").forEach(entityTypeName ->
                    entityTypes.add(Key.ofEntityType(entityTypeName.toUpperCase(Locale.ENGLISH))));
            int requiredAmount = section.getInt("required-entities." + key + ".amount");
            requiredEntities.put(new KeyRequirements(entityTypes), requiredAmount);
        }
    }

    @Override
    protected void registerListeners() {
        registerListener(this);

        plugin.executeGlobal(() -> {
            if (plugin.getServer().getPluginManager().isPluginEnabled("WildStacker")) {
                this.getEntityCount = WildStackerAPI::getEntityAmount;
            } else {
                this.getEntityCount = entity -> 1;
            }
        }, 1L);
    }

    @Override
    protected void clearModuleData(KeyDataTracker data) {
        data.clear();
    }

    @Override
    public double getProgress(SuperiorPlayer superiorPlayer) {
        KeyDataTracker killsTracker = get(superiorPlayer);

        if (killsTracker == null)
            return 0.0;

        int requiredEntities = 0;
        int kills = 0;

        for (Map.Entry<KeyRequirements, Integer> requiredEntity : this.requiredEntities.entrySet()) {
            requiredEntities += requiredEntity.getValue();
            kills += Math.min(killsTracker.getCounts(requiredEntity.getKey()), requiredEntity.getValue());
        }

        return (double) kills / requiredEntities;
    }

    @Override
    public int getProgressValue(SuperiorPlayer superiorPlayer) {
        KeyDataTracker killsTracker = get(superiorPlayer);

        if (killsTracker == null)
            return 0;

        int kills = 0;

        for (Map.Entry<KeyRequirements, Integer> requiredEntity : this.requiredEntities.entrySet())
            kills += Math.min(killsTracker.getCounts(requiredEntity.getKey()), requiredEntity.getValue());

        return kills;
    }

    @Override
    public void saveProgress(ConfigurationSection section) {
        for (Map.Entry<UUID, KeyDataTracker> entry : subjectEntrySet()) {
            String uuid = entry.getKey().toString();
            entry.getValue().getCounts().forEach((killedType, count) ->
                    section.set(uuid + "." + killedType, count.get()));
        }
    }

    @Override
    public void loadProgress(ConfigurationSection section) {
        for (String uuid : section.getKeys(false)) {
            KeyDataTracker killsTracker = new KeyDataTracker();
            UUID playerUUID = UUID.fromString(uuid);

            loadSubjectData(UUID.fromString(uuid), killsTracker);

            for (String key : section.getConfigurationSection(uuid).getKeys(false)) {
                killsTracker.load(Key.ofEntityType(key), section.getInt(uuid + "." + key));
            }
        }
    }

    @Override
    public void formatItem(SuperiorPlayer superiorPlayer, ItemStack itemStack) {
        KeyDataTracker killsTracker = getOrCreate(superiorPlayer, s -> new KeyDataTracker());

        if (killsTracker == null)
            return;

        ItemMeta itemMeta = itemStack.getItemMeta();

        if (itemMeta == null)
            return;

        if (itemMeta.hasDisplayName())
            itemMeta.setDisplayName(Placeholders.parseKeyPlaceholders(this.requiredEntities, killsTracker, itemMeta.getDisplayName(), false));

        if (itemMeta.hasLore()) {
            List<String> lore = new ArrayList<>();
            for (String line : Objects.requireNonNull(itemMeta.getLore()))
                lore.add(Placeholders.parseKeyPlaceholders(this.requiredEntities, killsTracker, line, false));
            itemMeta.setLore(lore);
        }

        itemStack.setItemMeta(itemMeta);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEntityKill(EntityDeathEvent e) {
        LivingEntity victim = e.getEntity();
        Key killedType = getMissionEntityKey(victim);
        if (killedType == null)
            return;

        EntityDamageEvent damageCause = victim.getLastDamageCause();

        if (!(damageCause instanceof EntityDamageByEntityEvent))
            return;

        EntityDamageByEntityEvent entityDamageByEntityEvent = (EntityDamageByEntityEvent) damageCause;

        // Capture every live victim fact on the death event's owner. The shooter
        // can already be in another region (or world); never inspect it here.
        org.bukkit.Location deathLocation = victim.getLocation();
        Island target = plugin.getGrid().getIslandAt(deathLocation);
        UUID deathWorld = deathLocation.getWorld().getUID();
        int killedAmount = this.getEntityCount.apply(victim);
        Entity source = entityDamageByEntityEvent.getDamager();
        if (source instanceof Player) {
            creditKiller((Player) source, target, deathWorld, killedType, killedAmount);
        } else if (source instanceof Projectile) {
            Projectile projectile = (Projectile) source;
            // Usually the projectile and victim share ownership. If they do
            // not, the entity scheduler follows the projectile before reading
            // its live shooter reference. A retired projectile is not credited.
            BukkitExecutor.entity(projectile, () -> {
                ProjectileSource shooter = projectile.getShooter();
                if (shooter instanceof Player)
                    creditKiller((Player) shooter, target, deathWorld, killedType, killedAmount);
            });
        }
    }

    private void creditKiller(Player killer, Island target, UUID deathWorld, Key killedType, int killedAmount) {
        BukkitExecutor.entity(killer, () -> {
            if (!killer.isOnline() || !deathWorld.equals(killer.getWorld().getUID())) return;
            SuperiorPlayer superiorPlayer = this.plugin.getPlayers().getSuperiorPlayer(killer);
            if (!isCurrentTarget(target, superiorPlayer)) return;
            try (IslandExecutionScope scope = IslandExecutionScope.open(target)) {
                if (!this.plugin.getMissions().canCompleteNoProgress(superiorPlayer, this)) return;
                KeyDataTracker killsTracker = getOrCreate(superiorPlayer, s -> new KeyDataTracker());
                if (killsTracker == null) return;
                killsTracker.track(killedType, killedAmount);
                plugin.executePlayer(superiorPlayer, () -> {
                    if (!killer.isOnline() || !deathWorld.equals(killer.getWorld().getUID()) ||
                            !isCurrentTarget(target, superiorPlayer)) return;
                    try (IslandExecutionScope rewardScope = IslandExecutionScope.open(target)) {
                        if (canComplete(superiorPlayer))
                            this.plugin.getMissions().rewardMission(this, superiorPlayer, true);
                    }
                }, 2L);
            }
        });
    }

    private boolean isCurrentTarget(@Nullable Island target, SuperiorPlayer player) {
        if (target == null) return !getIslandMission();
        return plugin.getGrid().getIslandByUUID(target.getUniqueId()) == target && target.isMember(player) &&
                (!(plugin instanceof SuperiorSkyblockPlugin) ||
                        ((SuperiorSkyblockPlugin) plugin).getPvcIslandService().isAvailable(target.getUniqueId()));
    }

    @Nullable
    private Key getMissionEntityKey(@Nullable Entity entity) {
        if (entity == null || entity instanceof ArmorStand)
            return null;

        Key entityKey = Key.of(entity);

        for (KeyRequirements requirement : requiredEntities.keySet()) {
            if (requirement.contains(entityKey))
                return entityKey;
        }

        return null;
    }

}

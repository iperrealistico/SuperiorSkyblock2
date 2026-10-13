package com.bgsoftware.superiorskyblock.missions;

import com.bgsoftware.superiorskyblock.api.key.Key;
import com.bgsoftware.superiorskyblock.api.key.KeySet;
import com.bgsoftware.superiorskyblock.api.missions.MissionLoadException;
import com.bgsoftware.superiorskyblock.api.objects.Pair;
import com.bgsoftware.superiorskyblock.api.wrappers.SuperiorPlayer;
import com.bgsoftware.superiorskyblock.core.threads.BukkitExecutor;
import com.bgsoftware.superiorskyblock.core.key.KeyIndicator;
import com.bgsoftware.superiorskyblock.core.key.map.KeyMaps;
import com.bgsoftware.superiorskyblock.missions.common.BuiltinMission;
import com.bgsoftware.superiorskyblock.missions.common.Placeholders;
import com.bgsoftware.superiorskyblock.missions.common.requirements.KeyRequirements;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.player.PlayerPickupItemEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.ItemMeta;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;


@SuppressWarnings("unused")
public final class ItemsMissions extends BuiltinMission<ItemsMissions.ItemsTracker> implements Listener {

    private static final long COUNT_INVENTORY_THRESHOLD = TimeUnit.SECONDS.toMillis(1);

    private final Map<KeyRequirements, Integer> requiredItems = new LinkedHashMap<>();
    private int totalRequiredAmount;

    @Override
    protected void loadConfiguration(ConfigurationSection section) throws MissionLoadException {
        ConfigurationSection requiredItemsSection = section.getConfigurationSection("required-items");

        if (requiredItemsSection == null)
            throw new MissionLoadException("You must have the \"required-items\" section in the config.");

        for (String key : requiredItemsSection.getKeys(false)) {
            KeySet itemStacks = KeySet.createKeySet();
            section.getStringList("required-items." + key + ".types").forEach(itemStackName ->
                    itemStacks.add(Key.ofMaterialAndData(itemStackName.toUpperCase(Locale.ENGLISH))));
            int requiredAmount = section.getInt("required-items." + key + ".amount");
            requiredItems.put(new KeyRequirements(itemStacks), requiredAmount);
            this.totalRequiredAmount += requiredAmount;
        }
    }

    @Override
    protected void registerListeners() {
        registerListener(this);
    }

    @Override
    protected void clearModuleData(ItemsTracker data) {
        data.clear();
    }

    @Override
    public double getProgress(SuperiorPlayer superiorPlayer) {
        int totalItemAmount = getProgressValue(superiorPlayer);
        if (totalItemAmount <= 0)
            return 0D;

        double progress = (double) totalItemAmount / this.totalRequiredAmount;

        return Math.max(0, progress);
    }

    @Override
    public int getProgressValue(SuperiorPlayer superiorPlayer) {
        Player player = superiorPlayer.asPlayer();

        if (player == null)
            return 0;

        ItemsTracker itemsTracker = getOrCreate(superiorPlayer, s -> new ItemsTracker());

        if (itemsTracker == null)
            return 0;

        return countItemsForPlayerInternal(player, itemsTracker);
    }

    private int countItemsForPlayerInternal(Player player, ItemsTracker itemsTracker) {
        return countSnapshot(player, itemsTracker, false).totalItemAmount;
    }

    /** Inventory references never escape this player's owning thread. */
    private CountSnapshot countSnapshot(Player player, ItemsTracker itemsTracker, boolean force) {
        long now = System.currentTimeMillis();
        CountSnapshot cached = itemsTracker.cached(player.getUniqueId(), this, now);
        if (!force && cached != null) return cached;
        if (BukkitExecutor.isFolia() && !BukkitExecutor.owns(player)) {
            if (force) throw new IllegalStateException("Item mission consumption requires the player's owning thread");
            return cached == null ? CountSnapshot.EMPTY : cached;
        }
        ItemProgress[] progressLookup = createProgressLookup();
        Map<Key, Integer> counts = KeyMaps.createHashMap(KeyIndicator.MATERIAL);
        Map<Integer, Integer> removals = new LinkedHashMap<>();
        int total = 0;
        ItemStack[] contents = player.getInventory().getContents();
        for (int slot = 0; slot < contents.length; slot++) {
            ItemStack item = contents[slot];
            Pair<Key, Integer> data = getMissionItemData(item);
            if (data == null) continue;
            int counted = progressLookup[data.getValue()].track(item.getAmount());
            if (counted > 0) {
                removals.put(slot, counted);
                counts.put(data.getKey(), counts.getOrDefault(data.getKey(), 0) + counted);
                total += counted;
            }
        }
        CountSnapshot result = new CountSnapshot(this, counts, removals, total, now + COUNT_INVENTORY_THRESHOLD);
        itemsTracker.publish(player.getUniqueId(), result);
        return result;
    }

    @Override
    public void onComplete(SuperiorPlayer superiorPlayer) {
        ItemsTracker tracker = get(superiorPlayer);
        Player player = superiorPlayer.asPlayer();
        if (tracker == null || player == null)
            throw new IllegalStateException("Item mission actor is no longer available");
        // Recount after the completion event, rather than consume a stale or another member's cache.
        CountSnapshot snapshot = countSnapshot(player, tracker, true);
        if (snapshot.totalItemAmount < this.totalRequiredAmount)
            throw new IllegalStateException("Required mission items changed before consumption");
        PlayerInventory inventory = player.getInventory();
        for (Map.Entry<Integer, Integer> removal : snapshot.removals.entrySet()) {
            ItemStack item = inventory.getItem(removal.getKey());
            if (item == null || item.getAmount() < removal.getValue())
                throw new IllegalStateException("Mission inventory changed during consumption");
        }
        for (Map.Entry<Integer, Integer> removal : snapshot.removals.entrySet()) {
            ItemStack item = inventory.getItem(removal.getKey()).clone();
            int remaining = item.getAmount() - removal.getValue();
            item.setAmount(remaining);
            inventory.setItem(removal.getKey(), remaining == 0 ? null : item);
        }
        tracker.invalidate(superiorPlayer.getUniqueId());
    }

    @Override
    public void formatItem(SuperiorPlayer superiorPlayer, ItemStack itemStack) {
        ItemsTracker itemsTracker = getOrCreate(superiorPlayer, s -> new ItemsTracker());

        if (itemsTracker == null)
            return;

        Player actor = superiorPlayer.asPlayer();
        CountSnapshot counts = actor == null ? CountSnapshot.EMPTY : countSnapshot(actor, itemsTracker, false);
        ItemMeta itemMeta = itemStack.getItemMeta();

        if (itemMeta == null)
            return;

        if (itemMeta.hasDisplayName())
            itemMeta.setDisplayName(Placeholders.parseKeyPlaceholders(this.requiredItems, counts::getCount, itemMeta.getDisplayName(), true));

        if (itemMeta.hasLore()) {
            List<String> lore = new ArrayList<>();
            for (String line : Objects.requireNonNull(itemMeta.getLore()))
                lore.add(Placeholders.parseKeyPlaceholders(this.requiredItems, counts::getCount, line, true));
            itemMeta.setLore(lore);
        }

        itemStack.setItemMeta(itemMeta);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onItemPickup(PlayerPickupItemEvent e) {
        ItemStack itemStack = e.getItem().getItemStack();
        handleItemPickup(e.getPlayer(), e.getItem().getItemStack());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onInventoryClick(InventoryClickEvent e) {
        if (!(e.getWhoClicked() instanceof Player) || e.getClickedInventory() == null ||
                e.getClickedInventory().getType() != InventoryType.CHEST || e.getClick() != ClickType.SHIFT_LEFT)
            return;

        handleItemPickup((Player) e.getWhoClicked(), e.getCurrentItem());
    }

    private void handleItemPickup(Player player, @Nullable ItemStack itemStack) {
        if (getMissionItemData(itemStack) == null)
            return;

        SuperiorPlayer superiorPlayer = this.plugin.getPlayers().getSuperiorPlayer(player);

        if (!this.plugin.getMissions().canCompleteNoProgress(superiorPlayer, this))
            return;

        laterPlayer(superiorPlayer, () -> superiorPlayer.runIfOnline(unused -> {
            ItemsTracker tracker = get(superiorPlayer);
            if (tracker != null) tracker.invalidate(superiorPlayer.getUniqueId());
            if (canComplete(superiorPlayer))
                this.plugin.getMissions().rewardMission(this, superiorPlayer, true);
        }), 2L);
    }

    @Nullable
    private Pair<Key, Integer> getMissionItemData(@Nullable ItemStack itemStack) {
        if (itemStack == null || itemStack.getType() == Material.AIR)
            return null;

        Key itemKey = Key.of(itemStack);

        int index = 0;
        for (KeyRequirements requirements : requiredItems.keySet()) {
            if (requirements.contains(itemKey))
                return new Pair<>(requirements.getKey(itemKey), index);
            ++index;
        }

        return null;
    }

    private ItemProgress[] createProgressLookup() {
        ItemProgress[] lookupArray = new ItemProgress[this.requiredItems.size()];

        int index = 0;
        for (Integer requiredAmount : this.requiredItems.values())
            lookupArray[index++] = new ItemProgress(requiredAmount);

        return lookupArray;
    }

    private static class ItemProgress {

        private final int requiredCount;
        private int count;

        ItemProgress(int requiredCount) {
            this.requiredCount = requiredCount;
        }

        int track(int delta) {
            int newCount = this.count + delta;

            if (newCount >= this.requiredCount) {
                int oldCount = this.count;
                this.count = this.requiredCount;
                return this.requiredCount - oldCount;
            } else {
                this.count = newCount;
                return delta;
            }
        }

    }

    /** Bounded immutable per-actor caches; shared island subjects never share live inventories. */
    public class ItemsTracker {
        private final LinkedHashMap<java.util.UUID, CountSnapshot> actors = new LinkedHashMap<>();

        synchronized CountSnapshot cached(java.util.UUID actor, Object definition, long now) {
            CountSnapshot value = actors.get(actor);
            return value != null && value.definition == definition && now < value.expiresAt ? value : null;
        }

        synchronized void publish(java.util.UUID actor, CountSnapshot value) {
            actors.put(actor, value);
            while (actors.size() > 128) actors.remove(actors.keySet().iterator().next());
        }

        synchronized void invalidate(java.util.UUID actor) { actors.remove(actor); }
        synchronized void clear() { actors.clear(); }
    }

    private static final class CountSnapshot {
        static final CountSnapshot EMPTY = new CountSnapshot(null, java.util.Collections.emptyMap(),
                java.util.Collections.emptyMap(), 0, 0);
        final Object definition;
        final Map<Key, Integer> counts;
        final Map<Integer, Integer> removals;
        final int totalItemAmount;
        final long expiresAt;

        CountSnapshot(Object definition, Map<Key, Integer> counts, Map<Integer, Integer> removals,
                      int totalItemAmount, long expiresAt) {
            this.definition = definition;
            this.counts = java.util.Collections.unmodifiableMap(counts);
            this.removals = java.util.Collections.unmodifiableMap(removals);
            this.totalItemAmount = totalItemAmount;
            this.expiresAt = expiresAt;
        }

        int getCount(Key key) { return counts.getOrDefault(key, 0); }
    }

}

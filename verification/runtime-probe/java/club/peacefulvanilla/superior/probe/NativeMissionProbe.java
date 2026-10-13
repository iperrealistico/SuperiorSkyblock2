package club.peacefulvanilla.superior.probe;

import com.bgsoftware.superiorskyblock.SuperiorSkyblockPlugin;
import com.bgsoftware.superiorskyblock.api.island.Island;
import com.bgsoftware.superiorskyblock.api.island.IslandExecutionScope;
import com.bgsoftware.superiorskyblock.api.island.PlayerRole;
import com.bgsoftware.superiorskyblock.api.data.DatabaseBridgeMode;
import com.bgsoftware.superiorskyblock.api.events.PostIslandCreateEvent;
import com.bgsoftware.superiorskyblock.api.missions.Mission;
import com.bgsoftware.superiorskyblock.api.player.algorithm.PlayerTeleportAlgorithm;
import com.bgsoftware.superiorskyblock.api.wrappers.SuperiorPlayer;
import com.bgsoftware.superiorskyblock.core.threads.BukkitExecutor;
import com.bgsoftware.superiorskyblock.core.SBlockPosition;
import com.bgsoftware.superiorskyblock.core.database.sql.SQLDatabaseBridge;
import com.bgsoftware.superiorskyblock.module.BuiltinModules;
import com.bgsoftware.superiorskyblock.pvc.entitlement.EntitlementPolicy;
import net.luckperms.api.LuckPermsProvider;
import net.luckperms.api.node.types.PermissionNode;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Biome;
import org.bukkit.block.data.Ageable;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockGrowEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/** Native crafting/plant/item observer for two actual fixture clients; never a release component. */
public final class NativeMissionProbe implements Listener {
    private static final String PRIMARY = "PVCSkyProbe", AUXILIARY = "PVCSkyProbeB";
    private static final List<String> MISSION_NAMES = List.of("pvc_craft", "pvc_farm", "pvc_items");
    private final JavaPlugin observer;
    private final SuperiorSkyblockPlugin superior;
    private final Path root;
    private final AtomicInteger primaryCraftClicks = new AtomicInteger(), auxiliaryCraftClicks = new AtomicInteger();
    private final AtomicInteger placements = new AtomicInteger(), grows = new AtomicInteger(), boneMealUses = new AtomicInteger(), pickups = new AtomicInteger();
    private final java.util.Set<java.util.UUID> completedCreations = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private volatile Location crop;
    private volatile boolean failed, checkedCraft, checkedFarm, checkedActorCache, checkedConsumption;
    private volatile boolean distinctRegionalActors;

    public NativeMissionProbe(JavaPlugin observer, SuperiorSkyblockPlugin superior) {
        this.observer = observer; this.superior = superior;
        this.root = observer.getServer().getWorldContainer().toPath().toAbsolutePath().normalize();
        guard();
        Bukkit.getPluginManager().registerEvents(this, observer);
    }
    private void guard() {
        if (root.getParent() == null || !root.getParent().getFileName().toString().equals("local-servers")
                || !root.getFileName().toString().startsWith("superior-runtime-")
                || !Files.isRegularFile(root.resolve(".superior-owned"))
                || !Files.isRegularFile(root.resolve(".superior-missions-owned"))
                || observer.getServer().getPort() < 29010 || observer.getServer().getPort() > 29029
                || !"SQLITE".equalsIgnoreCase(superior.getSettings().getDatabase().getType())
                || superior.getSettings().getMaxIslandSize() > 128
                || superior.getSettings().getDefaultValues().getIslandSize() < 24)
            throw new IllegalStateException("Mission observer requires a separate owned small-grid fixture");
        for (String name : MISSION_NAMES) {
            Mission<?> mission = superior.getMissions().getMission(name);
            if (mission == null || !mission.getIslandMission() || !mission.supportsIslandSubjects())
                throw new IllegalStateException("Missing qualified native island mission " + name);
        }
    }
    /** Root routes mission- suffixes from the actual primary account; auxiliary uses ordinary commands. */
    public boolean execute(Player actor, String action) {
        if (!PRIMARY.equals(actor.getName())) return true;
        try {
            guard();
            switch (action) {
                case "prepare" -> prepare(actor);
                case "status" -> status(actor);
                case "craftcheck" -> craftcheck(actor);
                case "plant" -> plant(actor);
                case "grow" -> grow(actor);
                case "farmcheck" -> farmcheck(actor);
                case "items" -> items(actor);
                case "actorcache" -> actorcache(actor);
                case "auxitems" -> auxiliaryItems(actor);
                case "consumecheck" -> consumecheck(actor);
                case "finish" -> checkpoint(actor, false);
                case "restartfinish" -> checkpoint(actor, true);
                default -> throw new IllegalArgumentException("Unknown fixed native mission case");
            }
        } catch (Throwable error) { fail(actor, error); }
        return true;
    }
    private SuperiorPlayer account(Player player) { return superior.getPlayers().getSuperiorPlayer(player); }
    private List<Island> owned() {
        return superior.getGrid().getOwnedIslands(accountUuid()).stream().sorted(Comparator.comparingLong(Island::getShortId)).toList();
    }
    private java.util.UUID accountUuid() {
        SuperiorPlayer value = superior.getPlayers().getSuperiorPlayer(PRIMARY);
        if (value == null) throw new IllegalStateException("Primary fixture account is unavailable");
        return value.getUniqueId();
    }
    private Island first() { List<Island> islands = owned(); if (islands.size() != 2) throw new IllegalStateException("Two actual islands are required"); return islands.get(0); }
    private Island second() { List<Island> islands = owned(); if (islands.size() != 2) throw new IllegalStateException("Two actual islands are required"); return islands.get(1); }
    private CompletableFuture<List<Player>> players(Player primary) {
        CompletableFuture<List<Player>> result = new CompletableFuture<>();
        BukkitExecutor.global(() -> {
            Player auxiliary = Bukkit.getPlayerExact(AUXILIARY);
            if (auxiliary == null) result.completeExceptionally(new IllegalStateException("Connect the real auxiliary fixture client"));
            else result.complete(List.of(primary, auxiliary));
        });
        return result;
    }
    private <T> CompletableFuture<T> entity(Player player, Supplier<T> action) {
        CompletableFuture<T> result = new CompletableFuture<>();
        BukkitExecutor.entity(player, () -> {
            try { result.complete(action.get()); } catch (Throwable error) { result.completeExceptionally(error); }
        }, 0L, () -> result.completeExceptionally(new IllegalStateException("Actual mission client retired")));
        return result;
    }
    private CompletableFuture<Void> teleport(Player player, Location destination) {
        CompletableFuture<Void> result = new CompletableFuture<>();
        BukkitExecutor.entity(player, () -> {
            player.setGameMode(GameMode.SURVIVAL); player.setInvulnerable(true); player.setAllowFlight(true); player.setFlying(true);
            account(player).teleportWithResult(destination, status -> {
                if (status == PlayerTeleportAlgorithm.TeleportResult.SUCCESS) result.complete(null);
                else result.completeExceptionally(new IllegalStateException("Native mission teleport failed: " + status));
            });
        }, 0L, () -> result.completeExceptionally(new IllegalStateException("Actual mission client retired")));
        return result;
    }
    private CompletableFuture<Void> ordinaryPermissions(Player player, boolean owner) {
        var api = LuckPermsProvider.get();
        return entity(player, () -> {
            if (player.isOp()) throw new IllegalStateException("Native mission clients must not be OP");
            return null;
        }).thenCompose(unused -> api.getUserManager().loadUser(player.getUniqueId())).thenCompose(user -> {
            for (String node : List.of("superior.island", "superior.island.mission", "superior.island.teleport", "superior.island.select"))
                user.data().add(PermissionNode.builder(node).build());
            if (owner) user.data().add(PermissionNode.builder(EntitlementPolicy.OWNERSHIP_PREFIX + "2").build());
            return api.getUserManager().saveUser(user);
        }).thenCompose(unused -> {
            superior.getPvcEntitlements().invalidate(player.getUniqueId());
            return superior.getPvcEntitlements().refreshOwnershipLimit(player.getUniqueId());
        }).thenAccept(limit -> {
            if (owner && limit != 2) throw new IllegalStateException("Fixture owner did not receive the explicit two-island limit");
        });
    }
    private String schematic() {
        if (superior.getSchematics().getSchematic("classic") != null) return "classic";
        if (superior.getSchematics().getSchematic("normal") != null) return "normal";
        throw new IllegalStateException("A real classic or normal schematic is required");
    }
    private CompletableFuture<Void> create(Player player, String name, int expected) {
        CompletableFuture<Void> submitted = new CompletableFuture<>();
        BukkitExecutor.entity(player, () -> {
            try {
                superior.getGrid().createIsland(Island.newBuilder().setOwner(account(player)).setName(name).setSchematicName(schematic()), Biome.PLAINS, false);
                submitted.complete(null);
            } catch (Throwable error) { submitted.completeExceptionally(error); }
        }, 0L, () -> submitted.completeExceptionally(new IllegalStateException("Creation actor retired")));
        return submitted.thenCompose(unused -> await(() -> owned().size() == expected
                && !superior.getGrid().hasActiveCreateRequest(account(player))
                && owned().stream().allMatch(island -> superior.getPvcIslandService().isAvailable(island.getUniqueId())
                        && (!superior.getSettings().isTeleportOnCreate() || completedCreations.contains(island.getUniqueId()))), 2400));
    }
    /** Advance only this disposable fixture's real acknowledged cursor; no island or event is fabricated. */
    private CompletableFuture<Void> advancePrivateCursor() {
        CompletableFuture<Void> result = new CompletableFuture<>();
        BukkitExecutor.global(() -> {
            try {
                guard();
                List<Island> islands = owned();
                if (islands.size() != 1 || !superior.getPvcIslandService().isAvailable(islands.get(0).getUniqueId())
                        || !islands.get(0).isCurrentlyActive())
                    throw new IllegalStateException("First actual island must be ACTIVE before private cursor advance");
                var grid = superior.getGrid();
                if (!(grid.getDatabaseBridge() instanceof SQLDatabaseBridge bridge)
                        || bridge.getDatabaseBridgeMode() != DatabaseBridgeMode.SAVE_DATA)
                    throw new IllegalStateException("Fixture cursor requires the actual acknowledged SQL grid bridge");
                var firstCenter = islands.get(0).getCenterPosition();
                long step = Math.multiplyExact(superior.getSettings().getMaxIslandSize(), 3);
                long existing = Math.max(Math.abs((long) firstCenter.getX()), Math.abs((long) firstCenter.getZ()));
                long far = ((existing + 8192L + step - 1L) / step) * step;
                if (far + step + superior.getSettings().getMaxIslandSize() >= 29999984L)
                    throw new IllegalStateException("Private verification coordinate would exceed the supported world bounds");
                synchronized (superior.getPvcIslandService().getPositionLock()) {
                    bridge.executeAcknowledged(() -> grid.setLastIslandPosition(SBlockPosition.of((int) far,
                            superior.getSettings().getIslandHeight(), (int) far))).whenComplete((unused, error) -> {
                        if (error != null) result.completeExceptionally(error); else result.complete(null);
                    });
                }
            } catch (Throwable error) { result.completeExceptionally(error); }
        });
        return result;
    }
    private void assertSeparateActor(Player owner, Player other, Island standing) {
        if (!standing.isInside(owner.getLocation())) throw new IllegalStateException("Actual client is outside its assigned physical island");
        if (BukkitExecutor.isFolia() && (!Bukkit.isOwnedByCurrentRegion(owner) || Bukkit.isOwnedByCurrentRegion(other)))
            throw new IllegalStateException("The actual Folia clients must belong to different owning region actors");
    }
    private void assertFarIslands() {
        var one = first().getCenterPosition(); var two = second().getCenterPosition();
        long x = (long) one.getX() - two.getX(), z = (long) one.getZ() - two.getZ();
        if (x * x + z * z < 4096L * 4096L) throw new IllegalStateException("Actual mission islands are not sufficiently separated");
    }
    private CompletableFuture<Void> await(BooleanSupplier condition, int ticks) {
        CompletableFuture<Void> result = new CompletableFuture<>();
        Runnable poll = new Runnable() {
            private int left = ticks;
            @Override public void run() {
                try {
                    if (condition.getAsBoolean()) result.complete(null);
                    else if (--left <= 0) result.completeExceptionally(new IllegalStateException("Native mission state did not converge"));
                    else BukkitExecutor.global(this, 1L);
                } catch (Throwable error) { result.completeExceptionally(error); }
            }
        };
        BukkitExecutor.global(poll);
        return result;
    }
    private Location pad(Island island) {
        Location home = island.getIslandHome(superior.getSettings().getWorlds().getDefaultWorldDimension());
        if (home == null || home.getWorld() == null) throw new IllegalStateException("Actual island home is missing");
        return new Location(home.getWorld(), (home.getBlockX() >> 4) * 16 + 8.5, home.getBlockY(), (home.getBlockZ() >> 4) * 16 + 8.5);
    }
    private CompletableFuture<Void> platform(Island island) {
        Location center = pad(island);
        CompletableFuture<Void> result = new CompletableFuture<>();
        center.getWorld().getChunkAtAsync(center.getBlockX() >> 4, center.getBlockZ() >> 4, true).whenComplete((chunk, error) -> {
            if (error != null) { result.completeExceptionally(error); return; }
            BukkitExecutor.region(center.getWorld(), center.getBlockX() >> 4, center.getBlockZ() >> 4, () -> {
                try {
                    for (int x = -3; x <= 3; x++) for (int z = -3; z <= 3; z++) {
                        center.getWorld().getBlockAt(center.getBlockX() + x, center.getBlockY() - 2, center.getBlockZ() + z).setType(Material.STONE);
                        center.getWorld().getBlockAt(center.getBlockX() + x, center.getBlockY() - 1, center.getBlockZ() + z).setType(Material.STONE);
                        for (int y = 0; y <= 3; y++) center.getWorld().getBlockAt(center.getBlockX() + x, center.getBlockY() + y, center.getBlockZ() + z).setType(Material.AIR);
                    }
                    center.clone().add(0, -1, 0).getBlock().setType(Material.FARMLAND);
                    center.clone().add(1, -1, 0).getBlock().setType(Material.WATER);
                    center.clone().add(-1, -1, 0).getBlock().setType(Material.SEA_LANTERN);
                    result.complete(null);
                } catch (Throwable failure) { result.completeExceptionally(failure); }
            });
        });
        return result;
    }
    private void prepare(Player actor) {
        if (!owned().isEmpty() || !superior.getGrid().getIslands().isEmpty()) throw new IllegalStateException("Use an empty separate mission fixture");
        players(actor).thenCompose(pair -> CompletableFuture.allOf(ordinaryPermissions(pair.get(0), true), ordinaryPermissions(pair.get(1), false)))
                .thenCompose(unused -> create(actor, "MissionA", 1)).thenCompose(unused -> advancePrivateCursor())
                .thenCompose(unused -> create(actor, "MissionB", 2))
                .thenCompose(unused -> players(actor)).thenCompose(pair -> {
                    assertFarIslands();
                    SuperiorPlayer member = account(pair.get(1));
                    PlayerRole role = superior.getRoles().getRoles().stream().filter(value -> value.isRoleLadder() && !value.isLastRole())
                            .findFirst().orElseThrow(() -> new IllegalStateException("A normal member role is required"));
                    return superior.getPvcIslandService().acknowledge(first(), () -> first().addMember(member, role))
                            .thenCompose(ignored -> superior.getPvcIslandService().acknowledge(second(), () -> second().addMember(member, role)));
                }).thenCompose(unused -> CompletableFuture.allOf(platform(first()), platform(second())))
                .thenCompose(unused -> players(actor)).thenCompose(pair -> {
                    crop = pad(first());
                    return CompletableFuture.allOf(entity(pair.get(0), () -> kit(pair.get(0))), entity(pair.get(1), () -> kit(pair.get(1))))
                            .thenCompose(ignored -> CompletableFuture.allOf(teleport(pair.get(0), pad(first()).add(-2, 0, 1)),
                                    teleport(pair.get(1), pad(second()).add(-2, 0, 1))))
                            .thenCompose(ignored -> CompletableFuture.allOf(entity(pair.get(0), () -> {
                                        assertSeparateActor(pair.get(0), pair.get(1), first()); account(pair.get(0)).selectIsland(second()); return null;
                                    }), entity(pair.get(1), () -> {
                                        assertSeparateActor(pair.get(1), pair.get(0), second()); account(pair.get(1)).selectIsland(first()); return null;
                                    }))).thenRun(() -> distinctRegionalActors = BukkitExecutor.isFolia());
                }).whenComplete((unused, error) -> {
                    if (error != null) fail(actor, error);
                    else reply(actor, "SUPERIOR_MISSION_PREPARED first=" + first().getShortId() + " second=" + second().getShortId()
                            + " crop=" + crop.getBlockX() + "," + crop.getBlockY() + "," + crop.getBlockZ()
                            + " regionalActors=" + (distinctRegionalActors ? "distinct" : "not-applicable"));
                });
    }
    private Void kit(Player player) {
        player.getInventory().clear();
        player.getInventory().setItem(0, new ItemStack(Material.OAK_LOG, 2));
        player.getInventory().setItem(1, new ItemStack(Material.WHEAT_SEEDS, 16));
        player.getInventory().setItem(2, new ItemStack(Material.BONE_MEAL, 32));
        return null;
    }
    private CompletableFuture<Snapshot> snapshot(Player player) {
        return entity(player, () -> {
            int[] progress = new int[6]; boolean[] done = new boolean[6]; int index = 0;
            for (Island island : List.of(first(), second())) try (IslandExecutionScope ignored = IslandExecutionScope.open(island)) {
                for (String name : MISSION_NAMES) {
                    Mission<?> mission = superior.getMissions().getMission(name);
                    if (!mission.isProgressAvailable(account(player))) throw new IllegalStateException("Mission progress was quarantined");
                    progress[index] = mission.getProgressValue(account(player));
                    done[index++] = superior.getMissions().hasCompleted(account(player), mission);
                }
            }
            return new Snapshot(progress, done, count(player, Material.STONE), account(player).getSelectedIsland().getShortId());
        });
    }
    private CompletableFuture<List<Snapshot>> snapshots(Player actor) {
        return players(actor).thenCompose(pair -> {
            CompletableFuture<Snapshot> primary = snapshot(pair.get(0)), auxiliary = snapshot(pair.get(1));
            return CompletableFuture.allOf(primary, auxiliary).thenApply(unused -> List.of(primary.join(), auxiliary.join()));
        });
    }
    private void status(Player actor) {
        snapshots(actor).whenComplete((pair, error) -> {
            if (error != null) fail(actor, error);
            else {
                Snapshot one = pair.get(0), two = pair.get(1);
                reply(actor, "SUPERIOR_MISSION_STATUS craftA=" + one.progress[0] + " craftB=" + one.progress[3]
                        + " farmA=" + one.progress[1] + " farmB=" + one.progress[4]
                        + " primaryItemsA=" + one.progress[2] + " auxItemsA=" + two.progress[2]
                        + " craftDoneA=" + one.done[0] + " craftDoneB=" + one.done[3]
                        + " farmDoneA=" + one.done[1] + " farmDoneB=" + one.done[4]
                        + " itemsDoneA=" + one.done[2] + " itemsDoneB=" + one.done[5]
                        + " primaryStone=" + one.stone + " auxStone=" + two.stone
                        + " primarySelected=" + one.selected + " auxSelected=" + two.selected
                        + " primaryCraftEvents=" + primaryCraftClicks.get() + " auxCraftEvents=" + auxiliaryCraftClicks.get()
                        + " plantEvents=" + placements.get() + " growEvents=" + grows.get() + " boneMealEvents=" + boneMealUses.get()
                        + " pickupEvents=" + pickups.get());
            }
        });
    }
    private void craftcheck(Player actor) {
        snapshots(actor).whenComplete((pair, error) -> {
            if (error != null) { fail(actor, error); return; }
            try {
                for (Snapshot state : pair)
                    if (state.progress[0] != 4 || state.progress[3] != 4 || !state.done[0] || state.done[3])
                        throw new IllegalStateException("Crafting progress/completion leaked between island UUID subjects");
                if (primaryCraftClicks.get() == 0 || auxiliaryCraftClicks.get() == 0)
                    throw new IllegalStateException("Both native crafting result clicks must be observed");
                checkedCraft = true; reply(actor, "SUPERIOR_MISSION_CRAFT_ISOLATION_PASS");
            } catch (Throwable failure) { fail(actor, failure); }
        });
    }
    private void plant(Player actor) {
        if (!checkedCraft) throw new IllegalStateException("Complete native crafting checks first");
        players(actor).thenCompose(pair -> teleport(pair.get(0), pad(first()).add(-2, 0, 1)))
                .whenComplete((unused, error) -> { if (error != null) fail(actor, error); else reply(actor, "SUPERIOR_MISSION_PLANT_READY"); });
    }
    private void grow(Player actor) {
        if (placements.get() == 0) throw new IllegalStateException("The primary must really plant the crop");
        players(actor).thenCompose(pair -> CompletableFuture.allOf(teleport(pair.get(0), pad(second()).add(-2, 0, 1)),
                teleport(pair.get(1), pad(first()).add(-2, 0, 1))))
                .thenCompose(unused -> players(actor)).thenCompose(pair -> CompletableFuture.allOf(
                        entity(pair.get(0), () -> { account(pair.get(0)).selectIsland(second()); return null; }),
                        entity(pair.get(1), () -> { account(pair.get(1)).selectIsland(second()); return null; })))
                .whenComplete((unused, error) -> { if (error != null) fail(actor, error); else reply(actor, "SUPERIOR_MISSION_GROW_READY"); });
    }
    private void farmcheck(Player actor) {
        snapshots(actor).whenComplete((pair, error) -> {
            if (error != null) { fail(actor, error); return; }
            try {
                for (Snapshot state : pair) if (state.progress[1] != 1 || state.progress[4] != 0 || !state.done[1] || state.done[4])
                    throw new IllegalStateException("Farming did not follow the physical crop's island UUID subject");
                if (placements.get() == 0 || grows.get() == 0 || boneMealUses.get() == 0)
                    throw new IllegalStateException("Actual planting, native growth and auxiliary interaction were not all observed");
                checkedFarm = true; reply(actor, "SUPERIOR_MISSION_FARM_ISOLATION_PASS");
            } catch (Throwable failure) { fail(actor, failure); }
        });
    }
    private void items(Player actor) {
        if (!checkedFarm) throw new IllegalStateException("Complete native farming checks first");
        players(actor).thenCompose(pair -> CompletableFuture.allOf(teleport(pair.get(0), pad(first()).add(-2, 0, 1)),
                teleport(pair.get(1), pad(first()).add(3, 0, -3))))
                .thenCompose(unused -> entity(actor, () -> {
                    Location actual = actor.getLocation();
                    actual.getWorld().dropItem(actual, new ItemStack(Material.STONE, 2)).setPickupDelay(0);
                    return null;
                })).whenComplete((unused, error) -> { if (error != null) fail(actor, error); else reply(actor, "SUPERIOR_MISSION_NATIVE_ITEMS_DROPPED"); });
    }
    private void actorcache(Player actor) {
        snapshots(actor).whenComplete((pair, error) -> {
            if (error != null) { fail(actor, error); return; }
            try {
                if (pair.get(0).progress[2] != 2 || pair.get(1).progress[2] != 0
                        || pair.get(0).stone != 2 || pair.get(1).stone != 0 || pickups.get() == 0)
                    throw new IllegalStateException("Shared item mission subject reused a foreign actor's inventory cache");
                checkedActorCache = true; reply(actor, "SUPERIOR_MISSION_ACTOR_CACHE_PASS");
            } catch (Throwable failure) { fail(actor, failure); }
        });
    }
    private void auxiliaryItems(Player actor) {
        if (!checkedActorCache) throw new IllegalStateException("Check the actual pickup and separate actor caches first");
        players(actor).thenCompose(pair -> entity(pair.get(1), () -> {
            pair.get(1).getInventory().addItem(new ItemStack(Material.STONE, 2)); return null;
        })).whenComplete((unused, error) -> { if (error != null) fail(actor, error); else reply(actor, "SUPERIOR_MISSION_AUX_ITEMS_READY"); });
    }
    private void consumecheck(Player actor) {
        snapshots(actor).whenComplete((pair, error) -> {
            if (error != null) { fail(actor, error); return; }
            try {
                if (!pair.get(0).done[2] || pair.get(0).done[5] || pair.get(0).stone != 0 || pair.get(1).stone != 2)
                    throw new IllegalStateException("Completing primary item mission consumed the wrong actor or wrong island");
                checkedConsumption = true; reply(actor, "SUPERIOR_MISSION_NATIVE_CONSUMPTION_PASS");
            } catch (Throwable failure) { fail(actor, failure); }
        });
    }
    private void checkpoint(Player actor, boolean restart) {
        if (failed || (!restart && (!checkedCraft || !checkedFarm || !checkedActorCache || !checkedConsumption)))
            throw new IllegalStateException("Native mission checks are incomplete");
        if (!restart && BukkitExecutor.isFolia() && !distinctRegionalActors)
            throw new IllegalStateException("Native mission flow did not prove separate Folia actor ownership");
        assertFarIslands();
        snapshots(actor).thenAccept(pair -> {
            Properties state = new Properties();
            state.setProperty("first.uuid", first().getUniqueId().toString()); state.setProperty("second.uuid", second().getUniqueId().toString());
            state.setProperty("first.id", Long.toString(first().getShortId())); state.setProperty("second.id", Long.toString(second().getShortId()));
            for (int i = 0; i < pair.size(); i++) {
                Snapshot snapshot = pair.get(i);
                if (snapshot.progress[0] != 4 || snapshot.progress[3] != 4 || snapshot.progress[1] != 1 || snapshot.progress[4] != 0
                        || !snapshot.done[0] || snapshot.done[3] || !snapshot.done[1] || snapshot.done[4]
                        || !snapshot.done[2] || !snapshot.done[5] || snapshot.stone != 0)
                    throw new IllegalStateException("Native mission progress/completion did not remain isolated");
                state.setProperty("actor." + i + ".selected", Long.toString(snapshot.selected));
                for (int index = 0; index < 6; index++) {
                    state.setProperty("actor." + i + ".progress." + index, Integer.toString(snapshot.progress[index]));
                    state.setProperty("actor." + i + ".done." + index, Boolean.toString(snapshot.done[index]));
                }
            }
            superior.executeAsync(() -> {
                try {
                    guard(); Path saved = root.resolve("mission-probe-state.properties");
                    if (restart) {
                        verifyPersistedMissions();
                        Properties previous = new Properties(); try (var input = Files.newInputStream(saved)) { previous.load(input); }
                        if (!state.equals(previous)) throw new IllegalStateException("Mission subject state or persistent selection changed on restart");
                    } else {
                        superior.getMissions().saveMissionsData();
                        verifyPersistedMissions();
                        if (Files.exists(saved)) throw new IllegalStateException("Native mission checkpoint already exists");
                        try (var output = Files.newOutputStream(saved)) { state.store(output, "Synthetic native mission fixture state"); }
                    }
                    String marker = restart ? "SUPERIOR_MISSION_RESTART_PASS" : "SUPERIOR_MISSION_PROBE_PASS";
                    observer.getLogger().info(marker); reply(actor, marker);
                } catch (Throwable error) { fail(actor, error); }
            }, 0L);
        }).exceptionally(error -> { fail(actor, error); return null; });
    }
    /** Validate actual on-disk subject progress and committed native completion rows, not a swallowed save result. */
    private void verifyPersistedMissions() throws Exception {
        Path data = BuiltinModules.MISSIONS.getDataStoreFolder().toPath();
        YamlConfiguration craft = loadYaml(data.resolve("pvc_craft.yml"));
        for (Island island : List.of(first(), second())) {
            ConfigurationSection subject = craft.getConfigurationSection(island.getUniqueId().toString());
            if (subject == null) throw new IllegalStateException("Crafting YAML lacks the actual island UUID subject");
            int planks = 0;
            for (String key : subject.getKeys(false)) {
                ItemStack item = subject.getItemStack(key + ".item");
                if (item != null && item.getType() == Material.OAK_PLANKS) planks += subject.getInt(key + ".amount");
            }
            if (planks != 4) throw new IllegalStateException("Actual crafting YAML did not persist four native planks per island");
        }
        if (craft.contains(accountUuid().toString())) throw new IllegalStateException("Crafting YAML leaked progress into the owner UUID");
        YamlConfiguration farm = loadYaml(data.resolve("pvc_farm.yml"));
        if (farm.getInt("data-version") != 2 || grown(farm, first()) != 1 || grown(farm, second()) != 0
                || farm.contains("grown-plants." + accountUuid()))
            throw new IllegalStateException("Farming YAML did not preserve the isolated version-2 island subject");
        // ItemsMissions intentionally persists no inventory cache: its completions
        // belong to native SQL. Read actual committed rows through the fork's
        // strict shared-connection gateway; never synthesize an items YAML file.
        Map<String, Integer> rows = new HashMap<>();
        new SQLDatabaseBridge().loadAllObjectsStrict("islands_missions", row -> {
            String name = String.valueOf(row.get("name"));
            if (MISSION_NAMES.contains(name)) rows.put(String.valueOf(row.get("island")) + ":" + name,
                    Integer.parseInt(String.valueOf(row.get("finish_count"))));
        });
        Map<String, Integer> expected = new HashMap<>();
        expected.put(first().getUniqueId() + ":pvc_craft", 1);
        expected.put(first().getUniqueId() + ":pvc_farm", 1);
        expected.put(first().getUniqueId() + ":pvc_items", 1);
        expected.put(second().getUniqueId() + ":pvc_items", 1);
        if (!rows.equals(expected)) throw new IllegalStateException("Committed mission completion rows differ from the native isolated flow");
    }
    private static YamlConfiguration loadYaml(Path path) throws Exception {
        if (!Files.isRegularFile(path)) throw new IllegalStateException("Required actual mission progress file is missing");
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.loadFromString(Files.readString(path, java.nio.charset.StandardCharsets.UTF_8));
        return yaml;
    }
    private static int grown(YamlConfiguration yaml, Island island) {
        ConfigurationSection subject = yaml.getConfigurationSection("grown-plants." + island.getUniqueId());
        if (subject == null) return 0;
        int total = 0;
        for (Object value : subject.getValues(false).values()) {
            if (!(value instanceof Number)) throw new IllegalStateException("Invalid persisted native crop count");
            total += ((Number) value).intValue();
        }
        return total;
    }
    private static int count(Player player, Material material) {
        int result = 0;
        for (ItemStack item : player.getInventory().getContents()) if (item != null && item.getType() == material) result += item.getAmount();
        return result;
    }
    private boolean atCrop(org.bukkit.block.Block block) {
        Location point = crop;
        return point != null && block.getWorld() == point.getWorld() && block.getX() == point.getBlockX()
                && block.getY() == point.getBlockY() && block.getZ() == point.getBlockZ();
    }
    @EventHandler(priority = EventPriority.MONITOR)
    public void onCreated(PostIslandCreateEvent event) {
        if (PRIMARY.equals(event.getPlayer().getName())) completedCreations.add(event.getIsland().getUniqueId());
    }
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent event) {
        if (PRIMARY.equals(event.getPlayer().getName()) && atCrop(event.getBlock()) && event.getBlock().getType() == Material.WHEAT) placements.incrementAndGet();
    }
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onGrow(BlockGrowEvent event) {
        if (atCrop(event.getBlock()) && event.getNewState().getType() == Material.WHEAT
                && event.getNewState().getBlockData() instanceof Ageable) grows.incrementAndGet();
    }
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onInteract(PlayerInteractEvent event) {
        if (AUXILIARY.equals(event.getPlayer().getName()) && event.getAction() == Action.RIGHT_CLICK_BLOCK
                && event.getClickedBlock() != null && atCrop(event.getClickedBlock()) && event.getItem() != null
                && event.getItem().getType() == Material.BONE_MEAL) boneMealUses.incrementAndGet();
    }
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onCraft(InventoryClickEvent event) {
        if (event.getRawSlot() != 0 || event.getCurrentItem() == null || event.getCurrentItem().getType() != Material.OAK_PLANKS) return;
        if (PRIMARY.equals(event.getWhoClicked().getName())) primaryCraftClicks.incrementAndGet();
        if (AUXILIARY.equals(event.getWhoClicked().getName())) auxiliaryCraftClicks.incrementAndGet();
    }
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPickup(EntityPickupItemEvent event) {
        if (event.getEntity() instanceof Player player && PRIMARY.equals(player.getName()) && event.getItem().getItemStack().getType() == Material.STONE) pickups.incrementAndGet();
    }
    private void reply(Player actor, String text) { superior.executePlayer(account(actor), () -> actor.sendMessage(text), 0L); }
    private void fail(Player actor, Throwable error) {
        failed = true; observer.getLogger().log(java.util.logging.Level.SEVERE, "SUPERIOR_MISSION_PROBE_FAIL", error);
        reply(actor, "SUPERIOR_MISSION_PROBE_FAIL " + error.getClass().getSimpleName());
    }
    private static final class Snapshot {
        final int[] progress; final boolean[] done; final int stone; final long selected;
        Snapshot(int[] progress, boolean[] done, int stone, long selected) {
            this.progress = progress; this.done = done; this.stone = stone; this.selected = selected;
        }
    }
}

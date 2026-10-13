package club.peacefulvanilla.superior.probe;

import com.bgsoftware.superiorskyblock.SuperiorSkyblockPlugin;
import com.bgsoftware.superiorskyblock.api.config.SettingsManager;
import com.bgsoftware.superiorskyblock.api.island.Island;
import com.bgsoftware.superiorskyblock.api.island.cache.IslandCacheKey;
import com.bgsoftware.superiorskyblock.api.player.algorithm.PlayerTeleportAlgorithm;
import com.bgsoftware.superiorskyblock.api.schematic.Schematic;
import com.bgsoftware.superiorskyblock.api.world.Dimension;
import com.bgsoftware.superiorskyblock.api.wrappers.SuperiorPlayer;
import com.bgsoftware.superiorskyblock.core.threads.BukkitExecutor;
import com.bgsoftware.superiorskyblock.pvc.entitlement.EntitlementPolicy;
import com.bgsoftware.superiorskyblock.world.RegionalDragonArena;
import net.luckperms.api.LuckPerms;
import net.luckperms.api.LuckPermsProvider;
import net.luckperms.api.node.Node;
import net.luckperms.api.node.types.PermissionNode;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.EnderCrystal;
import org.bukkit.entity.EnderDragon;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;
import java.util.function.Supplier;

/** Isolated native observer; never bundled in the release and never fabricates a Bukkit event. */
public final class SuperiorAdvancedProbe {
    private static final String PRIMARY = "PVCSkyProbe";
    private static final String AUXILIARY = "PVCSkyProbeB";
    private final JavaPlugin observer;
    private final SuperiorSkyblockPlugin superior;
    private volatile boolean failed;
    private volatile boolean opBaseline, wildcard, wildcardRevoked;
    private volatile boolean prepared, active, hidden, shown, killed, respawned, released, reactivated;
    private volatile Dimension end;
    private volatile List<Island> arenas = Collections.emptyList();
    private volatile UUID killedDragon;
    private volatile long mergesBefore, splitsBefore;

    public SuperiorAdvancedProbe(JavaPlugin observer, SuperiorSkyblockPlugin superior) {
        this.observer = observer;
        this.superior = superior;
        guardFixture();
    }

    /** Root command adapter passes only the fixed action after the advanced- prefix. */
    public boolean execute(Player primary, String action) {
        if (!primary.getName().equals(PRIMARY)) return true;
        guardFixture();
        try {
            switch (action) {
                case "opbaseline" -> policyBaseline(primary);
                case "wildcard" -> policyWildcard(primary, true);
                case "revokewildcard" -> policyWildcard(primary, false);
                case "addsizepair" -> additivePair(primary);
                case "endprepare" -> prepareEnd(primary);
                case "endenter" -> enterArenas(primary, false);
                case "endactive" -> assertActive(primary, false);
                case "bosshide" -> bossState(primary, false);
                case "bossshow" -> bossState(primary, true);
                case "killfirst" -> killFirst(primary);
                case "deathwait" -> waitForDeath(primary);
                case "respawnfirst" -> respawnFirst(primary);
                case "respawnwait" -> waitForRespawn(primary);
                case "endleave" -> leaveArenas(primary);
                case "released" -> assertReleased(primary);
                case "endreactivate" -> enterArenas(primary, true);
                case "status" -> status(primary);
                case "finish" -> finish(primary);
                default -> throw new IllegalArgumentException("Unknown fixed advanced test case");
            }
        } catch (Throwable error) { fail(primary, error); }
        return true;
    }

    private void guardFixture() {
        Path root = observer.getServer().getWorldContainer().toPath().toAbsolutePath().normalize();
        if (root.getParent() == null || !root.getParent().getFileName().toString().equals("local-servers") ||
                !root.getFileName().toString().startsWith("superior-runtime-") ||
                !Files.isRegularFile(root.resolve(".superior-owned")) ||
                observer.getServer().getPort() < 29010 || observer.getServer().getPort() > 29029)
            throw new IllegalStateException("Advanced observer refuses an unowned or production fixture");
    }

    private void policyBaseline(Player primary) {
        if (superior.getPvcEntitlements().getOwnerOverride(primary.getUniqueId()) != null)
            throw new IllegalStateException("Clear the test account owner override before policy baseline");
        primary.setOp(true); // The command itself is running on this real player's entity owner.
        LuckPerms api = LuckPermsProvider.get();
        api.getUserManager().loadUser(primary.getUniqueId()).thenCompose(user -> {
            for (Node node : new ArrayList<>(user.getNodes())) {
                String key = node.getKey();
                if (node instanceof PermissionNode && (key.startsWith(EntitlementPolicy.OWNERSHIP_PREFIX) ||
                        key.startsWith(EntitlementPolicy.RADIUS_PREFIX) || key.equals("*") ||
                        key.equals("superior.*") || key.equals("superior.island.*") || key.equals("superior.island.size.*")))
                    user.data().remove(node);
            }
            return api.getUserManager().saveUser(user);
        }).thenCompose(unused -> awaitPolicy(primary, 1, defaultRadius()))
                .whenComplete((unused, error) -> {
                    if (error != null) fail(primary, error);
                    else { opBaseline = true; reply(primary, "SUPERIOR_ADV_OP_BASELINE_PASS quota=1 radius=" + defaultRadius()); }
                });
    }

    private void policyWildcard(Player primary, boolean add) {
        if (!opBaseline) throw new IllegalStateException("Run the OP baseline first");
        LuckPerms api = LuckPermsProvider.get();
        api.getUserManager().loadUser(primary.getUniqueId()).thenCompose(user -> {
            PermissionNode node = PermissionNode.builder("superior.*").build();
            if (add) user.data().add(node); else user.data().remove(node);
            return api.getUserManager().saveUser(user);
        }).thenCompose(unused -> awaitPolicy(primary, add ? Integer.MAX_VALUE : 1, add ? gridCap() : defaultRadius()))
                .whenComplete((unused, error) -> {
                    if (error != null) { fail(primary, error); return; }
                    if (add) { wildcard = true; reply(primary, "SUPERIOR_ADV_WILDCARD_PASS quota=unlimited radius=" + gridCap()); }
                    else BukkitExecutor.entity(primary, () -> {
                        primary.setOp(false);
                        wildcardRevoked = true;
                        reply(primary, "SUPERIOR_ADV_WILDCARD_REVOKED_PASS quota=1 radius=" + defaultRadius());
                    });
                });
    }

    private CompletableFuture<Void> awaitPolicy(Player primary, int quota, int radius) {
        superior.getPvcEntitlements().invalidate(primary.getUniqueId());
        return await(() -> superior.getPvcEntitlements().refresh(primary.getUniqueId()).thenApply(value ->
                value.isAuthoritative() && value.getOwnershipLimit() == quota && value.getRadius() == radius), 30);
    }

    private void additivePair(Player primary) {
        if (!Integer.valueOf(120).equals(superior.getPvcEntitlements().getOwnerOverride(primary.getUniqueId())))
            throw new IllegalStateException("The fixed additive test requires the existing durable owner override 120");
        BukkitExecutor.global(() -> {
            try {
                if (!Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "is admin addsize " + PRIMARY + " 4") ||
                        !Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "is admin addsize " + PRIMARY + " 6"))
                    throw new IllegalStateException("A real admin addsize command was not dispatched");
                // Owner overrides are published only after durable replacement;
                // observing 130 therefore crosses both real command write ACKs.
                await(() -> CompletableFuture.completedFuture(Integer.valueOf(130).equals(
                        superior.getPvcEntitlements().getOwnerOverride(primary.getUniqueId())) &&
                        superior.getGrid().getOwnedIslands(primary.getUniqueId()).stream().allMatch(value -> value.getIslandSize() == 130)), 15)
                        .whenComplete((unused, error) -> {
                            if (error != null) fail(primary, error);
                            else reply(primary, "SUPERIOR_ADV_ADDSIZE_PAIR_PASS radius=130 increments=4,6 durable=true");
                        });
            } catch (Throwable error) { fail(primary, error); }
        });
    }

    private void prepareEnd(Player primary) {
        if (!BukkitExecutor.isFolia()) throw new IllegalStateException("Regional dragon qualification requires a real Folia lane");
        end = Dimension.values().stream().filter(value -> value.getEnvironment() == World.Environment.THE_END)
                .findFirst().orElseThrow(() -> new IllegalStateException("No configured End dimension"));
        SettingsManager.Worlds.End settings = (SettingsManager.Worlds.End) superior.getSettings().getWorlds().getDimensionConfig(end);
        if (!settings.isEnabled() || !settings.isDragonFight()) throw new IllegalStateException("Enable the isolated End/dragon profile before startup");
        List<Island> owned = superior.getGrid().getOwnedIslands(primary.getUniqueId()).stream()
                .sorted(Comparator.comparingLong(Island::getShortId)).toList();
        if (owned.size() < 2) throw new IllegalStateException("Two real persisted islands are required");
        arenas = List.of(owned.get(0), owned.get(1));
        Location first = arenas.get(0).getCenter(end), second = arenas.get(1).getCenter(end);
        if (first.getWorld() != second.getWorld() || Math.max(Math.abs(first.getX() - second.getX()), Math.abs(first.getZ() - second.getZ())) < 1024)
            throw new IllegalStateException("The two End arenas must be separated in their actual shared world");
        mergesBefore = trackingCounter("getMergeCount"); splitsBefore = trackingCounter("getSplitCount");
        CompletableFuture<Void> preparation = CompletableFuture.completedFuture(null);
        for (Island island : arenas) preparation = preparation.thenCompose(unused -> prepareArena(island, settings));
        preparation.whenComplete((unused, error) -> {
            if (error != null) fail(primary, error);
            else { prepared = true; reply(primary, "SUPERIOR_ADV_END_PREPARED islands=" + ids()); }
        });
    }

    private CompletableFuture<Void> prepareArena(Island island, SettingsManager.Worlds.End settings) {
        return island.accessIslandWorld(end).thenCompose(world -> {
            Schematic schematic = superior.getSchematics().getSchematic(island.getSchematicName() + "_" + end.getName().toLowerCase(java.util.Locale.ROOT));
            if (schematic == null) schematic = superior.getSchematics().getSchematic("classic_the_end");
            if (schematic == null) return CompletableFuture.failedFuture(new IllegalStateException("The actual End schematic is missing"));
            Schematic selected = schematic;
            Location center = island.getCenter(end);
            CompletableFuture<Void> pasted = new CompletableFuture<>();
            if (island.wasSchematicGenerated(end)) pasted.complete(null);
            else selected.pasteSchematic(island, center.clone().add(-0.5, -1, -0.5), () -> pasted.complete(null), pasted::completeExceptionally);
            return pasted.thenCompose(unused -> superior.getPvcIslandService().acknowledge(island, () -> {
                island.setDimensionEnabled(end, true);
                island.setSchematicGenerate(end);
                island.setIslandHome(end, selected.adjustRotation(center.clone()));
            })).thenCompose(unused -> region(center, () -> {
                superior.getNMSDragonFight().startDragonBattle(island, settings.getPortalOffset().applyToLocation(center.clone()));
                return null;
            })).thenCompose(unused -> await(() -> CompletableFuture.completedFuture(fight(island) != null), 15));
        });
    }

    private void enterArenas(Player primary, boolean reentry) {
        if (!prepared || (reentry && !released)) throw new IllegalStateException("Prepare the arenas and release them before re-entry");
        players(primary).thenCompose(players -> {
            List<CompletableFuture<Void>> arrivals = new ArrayList<>();
            for (int index = 0; index < 2; index++) {
                Player visitor = players.get(index); Island island = arenas.get(index);
                Location home = island.getIslandHome(end);
                if (home == null) return CompletableFuture.failedFuture(new IllegalStateException("Missing real End home"));
                arrivals.add(teleport(visitor, home.clone().add(0, 3, 0)));
            }
            return CompletableFuture.allOf(arrivals.toArray(new CompletableFuture[0]));
        }).whenComplete((unused, error) -> {
            if (error != null) fail(primary, error);
            else { if (reentry) assertActive(primary, true); else reply(primary, "SUPERIOR_ADV_END_ENTERED twoRealClients=true"); }
        });
    }

    private void assertActive(Player primary, boolean reentry) {
        requirePrepared();
        AtomicLong nextDiagnostic = new AtomicLong();
        await(() -> snapshots().whenComplete((values, error) -> {
            long now = System.nanoTime(), next = nextDiagnostic.get();
            if (now >= next && nextDiagnostic.compareAndSet(next, now + TimeUnit.SECONDS.toNanos(5)))
                logActiveDiagnostic(primary, values, error, reentry);
        }).thenApply(values -> values.stream().allMatch(value -> value.ready && value.owned && value.tickets == 289 && value.physicalTickets == 289 && value.dragon != null) &&
                !values.get(0).dragon.equals(values.get(1).dragon) && !values.get(0).boss.equals(values.get(1).boss) &&
                RegionalDragonArena.getReservedChunkCount() >= 578), 90).whenComplete((unused, error) -> {
            if (error != null) fail(primary, error);
            else { if (reentry) reactivated = true; else active = true;
                reply(primary, reentry ? "SUPERIOR_ADV_REACTIVATED_PASS" : "SUPERIOR_ADV_TWO_ACTIVE_FIGHTS_PASS"); status(primary); }
        });
    }

    private void bossState(Player primary, boolean visible) {
        if (!active) throw new IllegalStateException("Wait for both real active fights");
        players(primary).thenCompose(players -> {
            List<CompletableFuture<Void>> actions = new ArrayList<>();
            for (int index = 0; index < 2; index++) {
                Player visitor = players.get(index); Island island = arenas.get(index);
                CompletableFuture<Void> action = new CompletableFuture<>(); actions.add(action);
                BukkitExecutor.entity(visitor, () -> {
                    try {
                        Location center = island.getCenter(end);
                        if (!BukkitExecutor.owns(center.getWorld(), center.getBlockX() >> 4, center.getBlockZ() >> 4))
                            throw new IllegalStateException("Visitor does not own its merged arena");
                        Object event = invoke(requiredFight(island), "getRegionalBossEvent");
                        Object handle = invoke(visitor, "getHandle");
                        invoke(event, "setVisible", visible);
                        invoke(event, "removePlayer", handle);
                        invoke(event, "addPlayer", handle);
                        action.complete(null);
                    } catch (Throwable error) { action.completeExceptionally(error); }
                }, 0L, () -> action.completeExceptionally(new IllegalStateException("Real visitor retired during boss test")));
            }
            return CompletableFuture.allOf(actions.toArray(new CompletableFuture[0]));
        }).thenCompose(unused -> later(6)).thenCompose(unused -> snapshots()).whenComplete((values, error) -> {
            if (error != null) fail(primary, error);
            else if (values.stream().anyMatch(value -> value.visible != visible || value.viewers != 1))
                fail(primary, new IllegalStateException("Boss viewer/visibility state did not converge"));
            else { if (visible) shown = true; else hidden = true;
                reply(primary, visible ? "SUPERIOR_ADV_BOSS_SHOW_PASS" : "SUPERIOR_ADV_BOSS_HIDE_PASS"); }
        });
    }

    private void killFirst(Player primary) {
        if (!shown) throw new IllegalStateException("Complete the real hide/re-add/show cycle first");
        onFight(arenas.get(0), battle -> {
            EnderDragon dragon = superior.getNMSDragonFight().getEnderDragon(arenas.get(0), end);
            if (dragon == null || !BukkitExecutor.owns(dragon)) throw new IllegalStateException("No owned first-arena dragon");
            killedDragon = dragon.getUniqueId(); dragon.setHealth(0);
            return null;
        }).whenComplete((unused, error) -> {
            if (error != null) fail(primary, error); else reply(primary, "SUPERIOR_ADV_NATIVE_DRAGON_DEATH_STARTED uuid=" + killedDragon);
        });
    }

    private void waitForDeath(Player primary) {
        if (killedDragon == null) throw new IllegalStateException("Start the real native dragon death first");
        await(() -> onFight(arenas.get(0), battle -> {
            // The native fight sets this flag and portal only when the death
            // animation completes. Do not read a retired entity's live state.
            return Boolean.TRUE.equals(invoke(battle, "hasPreviouslyKilledDragon")) && invokeProtected(battle, "getPortalPos") != null;
        }), 45).whenComplete((unused, error) -> {
            if (error != null) fail(primary, error);
            else { killed = true; reply(primary, "SUPERIOR_ADV_NATIVE_DRAGON_DEATH_PASS"); }
        });
    }

    private void respawnFirst(Player primary) {
        if (!killed) throw new IllegalStateException("Wait for native death animation and exit portal first");
        onFight(arenas.get(0), battle -> {
            Location center = arenas.get(0).getCenter(end);
            Object nativePortal = invokeProtected(battle, "getPortalPos");
            if (nativePortal == null) throw new IllegalStateException("Native death did not create an exit portal");
            Location portal = new Location(center.getWorld(), ((Number) invoke(nativePortal, "getX")).intValue(),
                    ((Number) invoke(nativePortal, "getY")).intValue(), ((Number) invoke(nativePortal, "getZ")).intValue());
            for (int[] offset : new int[][]{{2, 0}, {-2, 0}, {0, 2}, {0, -2}}) {
                Location crystal = new Location(portal.getWorld(), portal.getBlockX() + offset[0] + 0.5, portal.getBlockY() + 1,
                        portal.getBlockZ() + offset[1] + 0.5);
                if (!BukkitExecutor.owns(crystal.getWorld(), crystal.getBlockX() >> 4, crystal.getBlockZ() >> 4))
                    throw new IllegalStateException("Respawn crystal chunk is foreign");
                portal.getWorld().spawn(crystal, EnderCrystal.class);
            }
            if (!Boolean.TRUE.equals(invoke(battle, "tryRespawn"))) throw new IllegalStateException("Native four-crystal respawn was rejected");
            return null;
        }).whenComplete((unused, error) -> {
            if (error != null) fail(primary, error); else reply(primary, "SUPERIOR_ADV_NATIVE_RESPAWN_STARTED");
        });
    }

    private void waitForRespawn(Player primary) {
        if (!killed) throw new IllegalStateException("No completed first dragon death");
        await(() -> onFight(arenas.get(0), battle -> {
            EnderDragon dragon = superior.getNMSDragonFight().getEnderDragon(arenas.get(0), end);
            return dragon != null && BukkitExecutor.owns(dragon) && dragon.isValid() && dragon.getHealth() > 0 && !dragon.getUniqueId().equals(killedDragon);
        }), 90).whenComplete((unused, error) -> {
            if (error != null) fail(primary, error);
            else { respawned = true; reply(primary, "SUPERIOR_ADV_NATIVE_RESPAWN_PASS"); }
        });
    }

    private void leaveArenas(Player primary) {
        requirePrepared();
        players(primary).thenCompose(players -> {
            Location home = arenas.get(0).getIslandHome(superior.getSettings().getWorlds().getDefaultWorldDimension());
            if (home == null) return CompletableFuture.failedFuture(new IllegalStateException("Missing real normal home"));
            return CompletableFuture.allOf(teleport(players.get(0), home.clone().add(0, 3, 0)), teleport(players.get(1), home.clone().add(2, 3, 0)));
        }).whenComplete((unused, error) -> {
            if (error != null) fail(primary, error); else reply(primary, "SUPERIOR_ADV_END_LEFT twoRealClients=true");
        });
    }

    private void assertReleased(Player primary) {
        requirePrepared();
        await(() -> CompletableFuture.completedFuture(RegionalDragonArena.getReservedChunkCount() == 0 &&
                arenas.stream().allMatch(island -> {
                    RegionalDragonArena arena = (RegionalDragonArena) invoke(requiredFight(island), "getRegionalArena");
                    return !arena.isReady() && arena.getTicketedChunkCount() == 0 && physicalTickets(island) == 0;
                })), 45).whenComplete((unused, error) -> {
            if (error != null) fail(primary, error);
            else { released = true; reply(primary, "SUPERIOR_ADV_INACTIVE_TICKETS_RELEASED_PASS"); status(primary); }
        });
    }

    private CompletableFuture<List<Player>> players(Player primary) {
        CompletableFuture<List<Player>> result = new CompletableFuture<>();
        BukkitExecutor.global(() -> {
            Player auxiliary = Bukkit.getPlayerExact(AUXILIARY);
            if (auxiliary == null) result.completeExceptionally(new IllegalStateException("Connect the actual auxiliary client " + AUXILIARY));
            else result.complete(List.of(primary, auxiliary));
        });
        return result;
    }

    private CompletableFuture<Void> teleport(Player visitor, Location destination) {
        CompletableFuture<Void> result = new CompletableFuture<>();
        BukkitExecutor.entity(visitor, () -> {
            visitor.setGameMode(GameMode.CREATIVE); visitor.setInvulnerable(true); visitor.setAllowFlight(true); visitor.setFlying(true);
            SuperiorPlayer account = superior.getPlayers().getSuperiorPlayer(visitor);
            account.teleportWithResult(destination, status -> {
                if (status == PlayerTeleportAlgorithm.TeleportResult.SUCCESS) result.complete(null);
                else result.completeExceptionally(new IllegalStateException("Actual test teleport failed: " + status));
            });
        }, 0L, () -> result.completeExceptionally(new IllegalStateException("Actual client retired during teleport")));
        return result;
    }

    private CompletableFuture<List<FightSnapshot>> snapshots() {
        List<CompletableFuture<FightSnapshot>> values = new ArrayList<>();
        for (Island island : arenas) values.add(onFight(island, battle -> {
            RegionalDragonArena arena = (RegionalDragonArena) invoke(battle, "getRegionalArena");
            Object boss = invoke(battle, "getRegionalBossEvent");
            boolean owned = Boolean.TRUE.equals(invoke(battle, "canTickRegionally"));
            UUID dragon = (UUID) invoke(battle, "getNativeDragonUuid");
            return new FightSnapshot(arena.isReady(), owned, arena.getTicketedChunkCount(), physicalTickets(island), dragon,
                    (UUID) invoke(boss, "getId"), Boolean.TRUE.equals(invoke(boss, "isVisible")),
                    ((java.util.Collection<?>) invoke(boss, "getPlayers")).size(), arenaLoadingMetadata(arena) + ',' + fightPresenceMetadata(island, battle));
        }));
        return CompletableFuture.allOf(values.toArray(new CompletableFuture[0])).thenApply(unused -> values.stream().map(CompletableFuture::join).toList());
    }

    private <T> CompletableFuture<T> onFight(Island island, Function<Object, T> action) {
        return region(island.getCenter(end), () -> action.apply(requiredFight(island)));
    }

    private int physicalTickets(Island island) {
        Location center = island.getCenter(end);
        int count = 0, centerX = center.getBlockX() >> 4, centerZ = center.getBlockZ() >> 4;
        // In both qualified Folia source bundles the per-coordinate API is a
        // metadata-only ChunkHolderManager query under its ticket area lock.
        // The all-world variant loads foreign chunks and must not be used here.
        for (int x = centerX - 8; x <= centerX + 8; x++)
            for (int z = centerZ - 8; z <= centerZ + 8; z++)
                if (center.getWorld().getPluginChunkTickets(x, z).contains(superior)) ++count;
        return count;
    }

    private <T> CompletableFuture<T> region(Location location, Supplier<T> action) {
        CompletableFuture<T> result = new CompletableFuture<>();
        try {
            BukkitExecutor.region(location.getWorld(), location.getBlockX() >> 4, location.getBlockZ() >> 4, () -> {
                try { result.complete(action.get()); } catch (Throwable error) { result.completeExceptionally(error); }
            });
        } catch (Throwable error) { result.completeExceptionally(error); }
        return result;
    }

    @SuppressWarnings({"rawtypes", "unchecked"}) private Object fight(Island island) {
        IslandCacheKey key = IslandCacheKey.getByName("DRAGON_FIGHT");
        return key == null ? null : island.getCache().get(key);
    }

    private Object requiredFight(Island island) {
        Object fight = fight(island);
        if (fight == null) throw new IllegalStateException("Missing native island fight " + island.getShortId());
        return fight;
    }

    private Object invoke(Object target, String name, Object... arguments) {
        try {
            for (Method method : target.getClass().getMethods()) {
                if (!method.getName().equals(name) || method.getParameterCount() != arguments.length) continue;
                return method.invoke(target, arguments);
            }
            throw new NoSuchMethodException(target.getClass().getName() + "." + name);
        } catch (InvocationTargetException wrapped) {
            Throwable cause = wrapped.getCause();
            if (cause instanceof RuntimeException exception) throw exception;
            if (cause instanceof Error error) throw error;
            throw new IllegalStateException(cause);
        } catch (ReflectiveOperationException error) { throw new IllegalStateException(error); }
    }

    private Object invokeProtected(Object target, String name) {
        try {
            for (Class<?> type = target.getClass(); type != null; type = type.getSuperclass()) {
                try {
                    Method method = type.getDeclaredMethod(name); method.setAccessible(true);
                    return method.invoke(target);
                } catch (NoSuchMethodException nextSuperclass) { }
            }
            throw new NoSuchMethodException(name);
        } catch (ReflectiveOperationException error) { throw new IllegalStateException(error); }
    }

    private CompletableFuture<Void> await(Supplier<CompletableFuture<Boolean>> condition, int seconds) {
        CompletableFuture<Void> result = new CompletableFuture<>();
        result.orTimeout(seconds + 2L, TimeUnit.SECONDS);
        poll(condition, System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds), result);
        return result;
    }

    private void poll(Supplier<CompletableFuture<Boolean>> condition, long deadline, CompletableFuture<Void> result) {
        if (result.isDone()) return;
        if (System.nanoTime() >= deadline) { result.completeExceptionally(new IllegalStateException("Actual native condition timed out")); return; }
        CompletableFuture<Boolean> check;
        try { check = condition.get(); }
        catch (Throwable error) { check = CompletableFuture.failedFuture(error); }
        check.whenComplete((ready, error) -> {
            if (error == null && Boolean.TRUE.equals(ready)) result.complete(null);
            else BukkitExecutor.async(() -> poll(condition, deadline, result), 5L);
        });
    }

    private CompletableFuture<Void> later(long ticks) {
        CompletableFuture<Void> result = new CompletableFuture<>();
        BukkitExecutor.async(() -> result.complete(null), ticks); return result;
    }

    private long trackingCounter(String name) {
        String version = Bukkit.getBukkitVersion().startsWith("26.2") ? "v26_2" : "v1_21_11";
        try {
            Class<?> factory = Class.forName("com.bgsoftware.superiorskyblock.platform.folia.nms." + version + ".RegionalTrackingFactory", true,
                    superior.getPluginClassLoader());
            return ((Number) factory.getMethod(name).invoke(null)).longValue();
        } catch (ReflectiveOperationException error) { throw new IllegalStateException("Actual region lifecycle counters are unavailable", error); }
    }

    private void status(Player primary) {
        reply(primary, "SUPERIOR_ADV_STATUS reserved=" + RegionalDragonArena.getReservedChunkCount() + " queued=" +
                RegionalDragonArena.getQueuedArenaCount() + " merges=" + (prepared ? trackingCounter("getMergeCount") - mergesBefore : 0) +
                " splits=" + (prepared ? trackingCounter("getSplitCount") - splitsBefore : 0) + " islands=" + ids());
        if (prepared) snapshots().whenComplete((values, error) -> {
            if (error != null) fail(primary, error);
            else reply(primary, "SUPERIOR_ADV_FIGHTS firstDragon=" + values.get(0).dragon + " secondDragon=" + values.get(1).dragon +
                    " firstBoss=" + values.get(0).boss + " secondBoss=" + values.get(1).boss + " viewers=" +
                    values.get(0).viewers + "," + values.get(1).viewers + " tickets=" + values.get(0).tickets + "," + values.get(1).tickets +
                    " physicalTickets=" + values.get(0).physicalTickets + "," + values.get(1).physicalTickets);
        });
    }

    private void logActiveDiagnostic(Player primary, List<FightSnapshot> values, Throwable error, boolean reentry) {
        StringBuilder diagnostic = new StringBuilder("SUPERIOR_ADV_ACTIVE_DIAGNOSTIC reentry=").append(reentry)
                .append(" reserved=").append(RegionalDragonArena.getReservedChunkCount())
                .append(" queued=").append(RegionalDragonArena.getQueuedArenaCount());
        try { diagnostic.append(" trackingFailures=").append(trackingCounter("getFailureCount")); }
        catch (Throwable unavailable) { diagnostic.append(" trackingFailures=unavailable:").append(failureSummary(unavailable)); }
        if (error != null) diagnostic.append(" snapshotFailure=").append(failureSummary(error));
        else for (int index = 0; index < values.size(); ++index) {
            FightSnapshot value = values.get(index);
            diagnostic.append(" fight").append(index + 1).append("={island=").append(arenas.get(index).getUniqueId())
                    .append(",ready=").append(value.ready).append(",owned=").append(value.owned)
                    .append(",tickets=").append(value.tickets).append(",physicalTickets=").append(value.physicalTickets)
                    .append(",dragon=").append(value.dragon).append(",boss=").append(value.boss)
                    .append(",viewers=").append(value.viewers).append(",visible=").append(value.visible)
                    .append(',').append(value.arenaLoading).append('}');
        }
        String fights = diagnostic.toString();
        arrivalDiagnostics(primary).whenComplete((actors, failure) -> observer.getLogger().info(fights +
                (failure == null ? " actors=" + actors : " actors=unavailable:" + failureSummary(failure))));
    }

    /** Read actual positions only on the two real actors; no arrival or fight state is changed here. */
    private CompletableFuture<String> arrivalDiagnostics(Player primary) {
        return players(primary).thenCompose(visitors -> {
            List<CompletableFuture<String>> captures = new ArrayList<>();
            for (int index = 0; index < 2; ++index) {
                Player visitor = visitors.get(index);
                Island expected = arenas.get(index);
                CompletableFuture<String> capture = new CompletableFuture<>();
                capture.orTimeout(3L, TimeUnit.SECONDS);
                captures.add(capture);
                try {
                    BukkitExecutor.entity(visitor, () -> {
                        try {
                            if (!BukkitExecutor.owns(visitor)) throw new IllegalStateException("Diagnostic callback does not own its actor");
                            Location actual = visitor.getLocation();
                            Island at = superior.getGrid().getIslandAt(actual);
                            Dimension dimension = superior.getGrid().getIslandsWorldDimension(actual.getWorld());
                            capture.complete("{player=" + visitor.getUniqueId() + ",world=" + actual.getWorld().getName() +
                                    ",environment=" + actual.getWorld().getEnvironment() + ",gridDimension=" +
                                    (dimension == null ? "none" : dimension.getName()) + ",xyz=" + actual.getX() + ',' +
                                    actual.getY() + ',' + actual.getZ() + ",gridAt=" + (at == null ? "none" : at.getShortId()) +
                                    ",expected=" + expected.getShortId() + ",matches=" + (at == expected) +
                                    ",available=" + superior.getPvcIslandService().isAvailable(expected.getUniqueId()) +
                                    ",generated=" + expected.wasSchematicGenerated(end) + ",generation=" +
                                    superior.getPvcIslandService().getWorldGeneration(expected.getUniqueId()) +
                                    ",pendingEndArrival=" + pendingEndArrival(visitor.getUniqueId()) +
                                    ",insideA=" + insideSnapshots(arenas.get(0)) + ",insideB=" + insideSnapshots(arenas.get(1)) + '}');
                        } catch (Throwable failure) { capture.completeExceptionally(failure); }
                    }, 0L, () -> capture.completeExceptionally(new IllegalStateException("Diagnostic actor callback retired")));
                } catch (Throwable failure) { capture.completeExceptionally(failure); }
            }
            return CompletableFuture.allOf(captures.toArray(new CompletableFuture[0]))
                    .thenApply(unused -> captures.stream().map(CompletableFuture::join).reduce((a, b) -> a + ';' + b).orElse("none"));
        }).orTimeout(4L, TimeUnit.SECONDS);
    }

    private String insideSnapshots(Island island) {
        List<SuperiorPlayer> inside = island.getAllPlayersInside();
        StringBuilder result = new StringBuilder("[");
        for (int index = 0; index < Math.min(8, inside.size()); ++index) {
            SuperiorPlayer account = inside.get(index);
            // SSuperiorPlayer#getWorld uses its immutable published location when the actor is foreign.
            World world = account.getWorld();
            if (index > 0) result.append(';');
            result.append(account.getUniqueId()).append('@').append(world == null ? "none" : world.getName());
        }
        return result.append("]count=").append(inside.size()).toString();
    }

    private String pendingEndArrival(UUID actor) {
        try {
            Object callbacks = readMetadataField(superior.getGameEventsDispatcher(), "callbacks");
            Object[] registeredTypes = ((Object[]) readMetadataField(callbacks, "values")).clone();
            for (Object byPriority : registeredTypes) {
                if (!(byPriority instanceof java.util.Map<?, ?>)) continue;
                for (Object registrations : ((java.util.Map<?, ?>) byPriority).values()) {
                    if (!(registrations instanceof java.util.List<?>)) continue;
                    for (Object registration : ((java.util.List<?>) registrations).toArray()) {
                        Object listener = readMetadataField(registration, "listener");
                        if (!listener.getClass().getName().equals("com.bgsoftware.superiorskyblock.listener.PlayersListener")) continue;
                        Object arrival = ((java.util.Map<?, ?>) readMetadataField(listener, "pendingEndArrivals")).get(actor);
                        if (arrival == null) return "none";
                        Island island = (Island) readMetadataField(arrival, "island");
                        World world = (World) readMetadataField(arrival, "world");
                        long expires = ((Number) readMetadataField(arrival, "deadline")).longValue();
                        return "{island=" + island.getShortId() + ",world=" + world.getName() + ",generation=" +
                                readMetadataField(arrival, "generation") + ",remainingMillis=" +
                                TimeUnit.NANOSECONDS.toMillis(expires - System.nanoTime()) + '}';
                    }
                }
            }
            return "listener-not-found";
        } catch (Throwable unavailable) { return "unavailable:" + failureSummary(unavailable); }
    }

    private Object readMetadataField(Object target, String name) throws ReflectiveOperationException {
        for (Class<?> type = target.getClass(); type != null; type = type.getSuperclass()) {
            try {
                Field field = type.getDeclaredField(name);
                field.setAccessible(true);
                return field.get(target);
            } catch (NoSuchFieldException nextSuperclass) { }
        }
        throw new NoSuchFieldException(name);
    }

    private String fightPresenceMetadata(Island island, Object battle) {
        try {
            return "stopped=" + readMetadataField(battle, "stopped") + ",hasEndPlayers=" + invokeProtected(battle, "hasEndPlayers") +
                    ",available=" + superior.getPvcIslandService().isAvailable(island.getUniqueId()) +
                    ",generated=" + island.wasSchematicGenerated(end) + ",generation=" +
                    superior.getPvcIslandService().getWorldGeneration(island.getUniqueId());
        } catch (Throwable unavailable) { return "presence=unavailable:" + failureSummary(unavailable); }
    }

    /** Test-only inspection of the existing arena future, on the same center-owned snapshot callback. */
    private String arenaLoadingMetadata(RegionalDragonArena arena) {
        try {
            CompletableFuture<?> loading;
            synchronized (arena) {
                Field field = RegionalDragonArena.class.getDeclaredField("loading");
                field.setAccessible(true);
                loading = (CompletableFuture<?>) field.get(arena);
            }
            // The arena clears this future after release and does not retain a last failure.
            if (loading == null) return "arenaLoading=none,arenaFailure=not-retained";
            if (!loading.isDone()) return "arenaLoading=pending,arenaFailure=not-retained";
            try {
                loading.getNow(null);
                return "arenaLoading=completed,arenaFailure=not-retained";
            } catch (Throwable failure) {
                return "arenaLoading=failed,arenaFailure=" + failureSummary(failure);
            }
        } catch (Throwable unavailable) {
            return "arenaLoading=unavailable,arenaFailure=" + failureSummary(unavailable);
        }
    }

    private String failureSummary(Throwable failure) {
        Throwable cause = failure;
        while ((cause instanceof java.util.concurrent.CompletionException || cause instanceof InvocationTargetException ||
                cause instanceof java.util.concurrent.ExecutionException) && cause.getCause() != null)
            cause = cause.getCause();
        String message = String.valueOf(cause.getMessage()).replaceAll("[\\r\\n\\t]", " ");
        if (message.length() > 256) message = message.substring(0, 256);
        return cause.getClass().getSimpleName() + ':' + message;
    }

    private void finish(Player primary) {
        if (failed || !opBaseline || !wildcard || !wildcardRevoked || !prepared || !active || !hidden || !shown ||
                !killed || !respawned || !released || !reactivated)
            throw new IllegalStateException("Advanced native sequence is incomplete");
        if (trackingCounter("getMergeCount") <= mergesBefore || trackingCounter("getSplitCount") <= splitsBefore)
            throw new IllegalStateException("Actual merge/split callbacks were not both observed");
        if (trackingCounter("getFailureCount") != 0)
            throw new IllegalStateException("A native regional tracking installation/callback failed");
        reply(primary, "SUPERIOR_ADVANCED_PROBE_PASS twoRealClients=true nativeDeathRespawn=true actualMergeSplit=true");
        observer.getLogger().info("SUPERIOR_ADVANCED_PROBE_PASS");
    }

    private String ids() { return arenas.stream().map(value -> Long.toString(value.getShortId())).reduce((a, b) -> a + "," + b).orElse("none"); }
    private int gridCap() { return superior.getSettings().getMaxIslandSize(); }
    private int defaultRadius() { return Math.min(gridCap(), superior.getSettings().getDefaultValues().getIslandSize()); }
    private void requirePrepared() { if (!prepared) throw new IllegalStateException("Prepare the actual arenas first"); }
    private void reply(Player primary, String message) { BukkitExecutor.entity(primary, () -> primary.sendMessage(message)); }
    private void fail(Player primary, Throwable error) { failed = true; observer.getLogger().log(java.util.logging.Level.SEVERE, "SUPERIOR_ADVANCED_PROBE_FAIL", error); reply(primary, "SUPERIOR_ADVANCED_PROBE_FAIL " + error.getClass().getSimpleName()); }
    private record FightSnapshot(boolean ready, boolean owned, int tickets, int physicalTickets, UUID dragon, UUID boss, boolean visible, int viewers, String arenaLoading) { }
}

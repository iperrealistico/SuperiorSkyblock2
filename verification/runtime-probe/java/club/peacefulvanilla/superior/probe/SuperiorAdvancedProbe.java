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
import com.bgsoftware.superiorskyblock.core.ChunkPosition;
import com.bgsoftware.superiorskyblock.island.SIsland;
import com.bgsoftware.superiorskyblock.pvc.entitlement.EntitlementPolicy;
import com.bgsoftware.superiorskyblock.world.RegionalDragonArena;
import com.bgsoftware.superiorskyblock.world.chunk.ChunkStorageBridge;
import net.luckperms.api.LuckPerms;
import net.luckperms.api.LuckPermsProvider;
import net.luckperms.api.node.Node;
import net.luckperms.api.node.types.PermissionNode;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
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
    private volatile boolean occupiedEndReset;
    private volatile boolean nativeTopology;
    private volatile Object resetFirstFight;
    private NativeTopologyProbe topology;
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
                case "native-topology" -> qualifyNativeTopology(primary);
                case "occupiedendreset" -> resetOccupiedEnd(primary);
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
        CompletableFuture<Void> preparation = prepareCommonSpawnPlatform();
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
            })).thenCompose(unused -> prepareEndWaitingPlatform(island)).thenCompose(unused -> region(center, () -> {
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
                arrivals.add(teleport(visitor, safeEndPosition(island)));
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
        if (!active || !released) throw new IllegalStateException("Test explicit visibility only after genuine End departure and ticket release");
        players(primary).thenCompose(players -> {
            List<CompletableFuture<Void>> actions = new ArrayList<>();
            for (int index = 0; index < 2; index++) {
                Player visitor = players.get(index); Island island = arenas.get(index);
                CompletableFuture<Void> action = new CompletableFuture<>(); actions.add(action);
                BukkitExecutor.entity(visitor, () -> {
                    try {
                        Object handle = invoke(visitor, "getHandle");
                        onFight(island, battle -> {
                            RegionalDragonArena arena = (RegionalDragonArena) invoke(battle, "getRegionalArena");
                            if (arena.isReady() || arena.getTicketedChunkCount() != 0 || physicalTickets(island) != 0)
                                throw new IllegalStateException("Visibility test requires a genuinely inactive fight");
                            Object event = invoke(battle, "getRegionalBossEvent");
                            invoke(event, "setVisible", visible);
                            invoke(event, "removePlayer", handle);
                            invoke(event, "addPlayer", handle);
                            return null;
                        }).whenComplete((unused, failure) -> {
                            if (failure == null) action.complete(null); else action.completeExceptionally(failure);
                        });
                    } catch (Throwable error) { action.completeExceptionally(error); }
                }, 0L, () -> action.completeExceptionally(new IllegalStateException("Real visitor retired during boss test")));
            }
            return CompletableFuture.allOf(actions.toArray(new CompletableFuture[0]));
        }).thenCompose(unused -> later(6)).thenCompose(unused -> snapshots()).whenComplete((values, error) -> {
            if (error != null) fail(primary, error);
            else if (values.stream().anyMatch(value -> value.visible != visible || value.viewers != 1))
                fail(primary, new IllegalStateException("Inactive boss viewer/visibility state did not converge: " + values));
            else { if (visible) shown = true; else hidden = true;
                reply(primary, visible ? "SUPERIOR_ADV_BOSS_SHOW_PASS" : "SUPERIOR_ADV_BOSS_HIDE_PASS"); }
        });
    }

    private void killFirst(Player primary) {
        if (!active) throw new IllegalStateException("Wait for both real active fights before native dragon death");
        teleport(primary, safeEndPosition(arenas.get(0))).thenCompose(unused -> onFight(arenas.get(0), battle -> {
            EnderDragon dragon = superior.getNMSDragonFight().getEnderDragon(arenas.get(0), end);
            if (dragon == null || !BukkitExecutor.owns(dragon)) throw new IllegalStateException("No owned first-arena dragon");
            killedDragon = dragon.getUniqueId(); dragon.setHealth(0);
            return null;
        })).whenComplete((unused, error) -> {
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
        onActor(primary, () -> {
            Location location = primary.getLocation();
            if (location.getWorld() != arenas.get(0).getCenter(end).getWorld() || superior.getGrid().getIslandAt(location) != arenas.get(0))
                throw new IllegalStateException("Actual primary left End A before native respawn: " + location);
            return location.getWorld().getName() + ':' + location.getX() + ',' + location.getY() + ',' + location.getZ();
        }).thenCompose(actualActor -> onFight(arenas.get(0), battle -> {
            Location center = arenas.get(0).getCenter(end);
            boolean owned = Boolean.TRUE.equals(invoke(battle, "canTickRegionally"));
            boolean hasPlayers = Boolean.TRUE.equals(invokeProtected(battle, "hasEndPlayers"));
            Object stageBefore = readDiagnosticField(battle, "respawnStage");
            observer.getLogger().info("SUPERIOR_ADV_RESPAWN_INPUT actor=" + actualActor + " owned=" + owned +
                    " hasEndPlayers=" + hasPlayers + " stageBefore=" + stageBefore);
            if (!owned || !hasPlayers || stageBefore != null)
                throw new IllegalStateException("Native respawn requires the real owned arena, actual End visitor and no previous respawn stage");
            Object nativePortal = invokeProtected(battle, "getPortalPos");
            if (nativePortal == null) throw new IllegalStateException("Native death did not create an exit portal");
            Location portal = new Location(center.getWorld(), ((Number) invoke(nativePortal, "getX")).intValue(),
                    ((Number) invoke(nativePortal, "getY")).intValue(), ((Number) invoke(nativePortal, "getZ")).intValue());
            List<EnderCrystal> spawned = new ArrayList<>();
            for (int[] offset : new int[][]{{2, 0}, {-2, 0}, {0, 2}, {0, -2}}) {
                Location crystal = new Location(portal.getWorld(), portal.getBlockX() + offset[0] + 0.5, portal.getBlockY() + 1,
                        portal.getBlockZ() + offset[1] + 0.5);
                if (!BukkitExecutor.owns(crystal.getWorld(), crystal.getBlockX() >> 4, crystal.getBlockZ() >> 4))
                    throw new IllegalStateException("Respawn crystal chunk is foreign");
                spawned.add(portal.getWorld().spawn(crystal, EnderCrystal.class));
            }
            if (spawned.size() != 4 || spawned.stream().anyMatch(crystal -> !BukkitExecutor.owns(crystal) || !crystal.isValid()))
                throw new IllegalStateException("Four actual owned native crystals were not present");
            Object stageAfterSpawn = readDiagnosticField(battle, "respawnStage");
            boolean accepted = Boolean.TRUE.equals(invoke(battle, "tryRespawn"));
            Object stageAfterCall = readDiagnosticField(battle, "respawnStage");
            observer.getLogger().info("SUPERIOR_ADV_RESPAWN_STAGE actualCrystals=" + spawned.stream().map(EnderCrystal::getUniqueId).toList() +
                    " stageAfterSpawn=" + stageAfterSpawn + " tryRespawn=" + accepted + " stageAfterCall=" + stageAfterCall);
            if ((!accepted && stageAfterSpawn == null) || stageAfterCall == null)
                throw new IllegalStateException("Native four-crystal respawn was rejected without an actual started stage");
            return null;
        })).whenComplete((unused, error) -> {
            if (error != null) fail(primary, error); else reply(primary, "SUPERIOR_ADV_NATIVE_RESPAWN_STARTED");
        });
    }

    private Location safeEndPosition(Island island) {
        Location home = island.getIslandHome(end);
        if (home == null) throw new IllegalStateException("Missing real End home");
        return home.clone().add(12, 3, 0);
    }

    private CompletableFuture<Void> prepareEndWaitingPlatform(Island island) {
        Location floor = safeEndPosition(island).subtract(0, 1, 0);
        ChunkPosition position = ChunkPosition.of(floor.getWorld(), floor.getBlockX() >> 4, floor.getBlockZ() >> 4, false);
        return ((SIsland) island).prepareWorldWriteIntent(List.of(position))
                .thenCompose(unused -> floor.getWorld().getChunkAtAsync(position.getX(), position.getZ(), true))
                .thenCompose(unused -> region(floor, () -> {
                    for (int x = -1; x <= 1; ++x) for (int z = -1; z <= 1; ++z)
                        floor.getWorld().getBlockAt(floor.getBlockX() + x, floor.getBlockY(), floor.getBlockZ() + z).setType(Material.BEDROCK, false);
                    return null;
                })).thenCompose(unused -> ChunkStorageBridge.saveChunksAsync(island, List.of(position)));
    }

    /** The private shared void-world fixture still needs a genuine safe common spawn. */
    private CompletableFuture<Void> prepareCommonSpawnPlatform() {
        guardFixture();
        Island spawnIsland = superior.getGrid().getSpawnIsland();
        Location spawn = spawnIsland.getIslandHome(null);
        if (spawn == null || spawn.getWorld() == null || !Double.isFinite(spawn.getX()) ||
                !Double.isFinite(spawn.getY()) || !Double.isFinite(spawn.getZ()))
            return CompletableFuture.failedFuture(new IllegalStateException("Common spawn must be an actual finite configured location"));
        World world = spawn.getWorld();
        Path fixture = observer.getServer().getWorldContainer().toPath().toAbsolutePath().normalize();
        try {
            if (Bukkit.getWorld(world.getUID()) != world || world.getEnvironment() != World.Environment.NORMAL ||
                    !world.getWorldFolder().toPath().toRealPath().startsWith(fixture.toRealPath()) ||
                    spawn.getBlockY() - 1 < world.getMinHeight() || spawn.getBlockY() + 2 >= world.getMaxHeight())
                throw new IllegalStateException("Configured common spawn is outside the owned preloaded normal fixture or safe build height");
        } catch (java.io.IOException invalidWorldPath) {
            return CompletableFuture.failedFuture(invalidWorldPath);
        }
        List<Location> floor = new ArrayList<>();
        java.util.Set<ChunkPosition> chunks = new java.util.LinkedHashSet<>();
        for (int x = -1; x <= 1; ++x) for (int z = -1; z <= 1; ++z) {
            Location block = new Location(world, spawn.getBlockX() + x, spawn.getBlockY() - 1, spawn.getBlockZ() + z);
            Island occupied = superior.getGrid().getIslandAt(block);
            if ((occupied != null && !occupied.isSpawn()) || intersectsPlayerCell(block))
                return CompletableFuture.failedFuture(new IllegalStateException("Common-spawn floor intersects a reserved player island"));
            floor.add(block);
            chunks.add(ChunkPosition.of(world, block.getBlockX() >> 4, block.getBlockZ() >> 4, false));
        }
        CompletableFuture<Void> prepared = CompletableFuture.completedFuture(null);
        for (ChunkPosition chunk : chunks)
            prepared = prepared.thenCompose(unused -> world.getChunkAtAsync(chunk.getX(), chunk.getZ(), true).thenApply(loaded -> null));
        for (Location block : floor)
            prepared = prepared.thenCompose(unused -> region(block, () -> {
                if (!BukkitExecutor.owns(world, block.getBlockX() >> 4, block.getBlockZ() >> 4))
                    throw new IllegalStateException("Common-spawn floor write has no actual chunk owner");
                Island occupied = superior.getGrid().getIslandAt(block);
                if ((occupied != null && !occupied.isSpawn()) || intersectsPlayerCell(block))
                    throw new IllegalStateException("Common-spawn floor became a reserved player island");
                block.getBlock().setType(Material.BEDROCK, false);
                return null;
            }));
        // SpawnIsland has no player ownership/generation; the native collection
        // path acknowledges the actual four boundary chunks without inventing an owner.
        prepared = prepared.thenCompose(unused -> ChunkStorageBridge.saveChunksAsync(chunks));
        for (Location block : floor)
            prepared = prepared.thenCompose(unused -> region(block, () -> {
                if (block.getBlock().getType() != Material.BEDROCK)
                    throw new IllegalStateException("Native common-spawn platform block is missing after its storage acknowledgement");
                return null;
            }));
        return prepared.thenRun(() -> observer.getLogger().info("SUPERIOR_ADV_COMMON_SPAWN_PLATFORM_ACK blocks=9 chunks=" +
                chunks.size() + " world=" + world.getName() + " spawn=" + spawn.getX() + ',' + spawn.getY() + ',' + spawn.getZ()));
    }

    private boolean intersectsPlayerCell(Location location) {
        // Spawn protection can take precedence in getIslandAt; independently
        // reject every registered reserved player cell before a fixture write.
        for (Island island : superior.getGrid().getIslands())
            if (!island.isSpawn() && island.isInside(location)) return true;
        return false;
    }

    private Object readDiagnosticField(Object target, String name) {
        try { return readMetadataField(target, name); }
        catch (ReflectiveOperationException unavailable) { throw new IllegalStateException("Native diagnostic field unavailable: " + name, unavailable); }
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
                    Object current = fight(island);
                    if (current == null && occupiedEndReset && island == arenas.get(0)) current = resetFirstFight;
                    if (current == null) throw new IllegalStateException("A fight unexpectedly disappeared before release qualification");
                    RegionalDragonArena arena = (RegionalDragonArena) invoke(current, "getRegionalArena");
                    return !arena.isReady() && arena.getTicketedChunkCount() == 0 && physicalTickets(island) == 0;
                })), 45).whenComplete((unused, error) -> {
            if (error != null) fail(primary, error);
            else { released = true; reply(primary, "SUPERIOR_ADV_INACTIVE_TICKETS_RELEASED_PASS"); status(primary); }
        });
    }

    private void resetOccupiedEnd(Player primary) {
        if (!reactivated || !nativeTopology || !hidden || !shown || !killed || !respawned || occupiedEndReset)
            throw new IllegalStateException("Complete the real fight, inactive boss component and reactivation gates first");
        Island source = arenas.get(0), neighbour = arenas.get(1);
        UUID identity = source.getUniqueId(); long shortId = source.getShortId();
        Location cleared = source.getCenter(end).clone().add(10, 20, 0);
        Location retained = neighbour.getCenter(end).clone().add(10, 20, 0);
        resetFirstFight = requiredFight(source);
        RegionalDragonArena previousArena = (RegionalDragonArena) invoke(resetFirstFight, "getRegionalArena");
        players(primary).thenCompose(visitors -> {
            Player auxiliary = visitors.get(1);
            return onActor(primary, () -> {
                if (primary.getWorld() != cleared.getWorld() || superior.getGrid().getIslandAt(primary.getLocation()) != source)
                    throw new IllegalStateException("Primary must actually occupy End A before the reset");
                return null;
            }).thenCompose(unused -> onActor(auxiliary, () -> {
                if (auxiliary.getWorld() != retained.getWorld() || superior.getGrid().getIslandAt(auxiliary.getLocation()) != neighbour)
                    throw new IllegalStateException("Auxiliary must actually occupy End B before the reset");
                return null;
            })).thenCompose(unused -> seedEndSentinel(source, cleared))
                    .thenCompose(unused -> seedEndSentinel(neighbour, retained))
                    .thenCompose(unused -> onFight(neighbour, battle -> {
                        EnderDragon dragon = superior.getNMSDragonFight().getEnderDragon(neighbour, end);
                        if (dragon == null || !BukkitExecutor.owns(dragon) || !dragon.isValid() || dragon.getHealth() <= 0)
                            throw new IllegalStateException("The neighbour must have its actual owned live dragon");
                        return new ResetBaseline(dragon.getUniqueId(), superior.getPvcIslandService().getWorldGeneration(neighbour.getUniqueId()),
                                superior.getPvcIslandService().getWorldGeneration(source.getUniqueId()), captureStableEndTerrain(neighbour));
                    })).thenCompose(before -> committedResetOperations(identity).thenCompose(previousResets -> onActor(primary, () -> {
                        if (primary.getWorld() != cleared.getWorld() || superior.getGrid().getIslandAt(primary.getLocation()) != source)
                            throw new IllegalStateException("Primary left End A before the actual reset call");
                        return ((SIsland) source).resetWorldAsync(end);
                    }).thenCompose(Function.identity()).orTimeout(180L, TimeUnit.SECONDS)
                            .thenCompose(unused -> await(() -> CompletableFuture.completedFuture(
                                    superior.getPvcIslandService().isAvailable(identity) && ChunkStorageBridge.getPendingCount() == 0), 20))
                            .thenCompose(unused -> region(cleared, () -> {
                                if (cleared.getBlock().getType() != Material.AIR) throw new IllegalStateException("Reset End A sentinel remains physically present");
                                if (superior.getGrid().getIslandByUUID(identity) != source || source.getShortId() != shortId ||
                                        source.wasSchematicGenerated(end) || superior.getPvcIslandService().getWorldGeneration(identity) <= before.sourceGeneration)
                                    throw new IllegalStateException("End reset lost identity, generation acknowledgement or ungenerated state");
                                return null;
                            })).thenCompose(unused -> onActor(primary, () -> {
                                Location spawn = superior.getGrid().getSpawnIsland().getIslandHome(null);
                                Location actual = primary.getLocation();
                                if (spawn == null || actual.getWorld() != spawn.getWorld() ||
                                        Math.abs(actual.getX() - spawn.getX()) > 1 || Math.abs(actual.getZ() - spawn.getZ()) > 1 ||
                                        Math.abs(actual.getY() - spawn.getY()) > 3)
                                    throw new IllegalStateException("Occupied End reset did not evacuate the primary to the actual common spawn: " + actual);
                                return null;
                            })).thenCompose(unused -> onActor(auxiliary, () -> {
                                if (auxiliary.getWorld() != retained.getWorld() || superior.getGrid().getIslandAt(auxiliary.getLocation()) != neighbour)
                                    throw new IllegalStateException("Reset A displaced the actual auxiliary from End B");
                                return null;
                            })).thenCompose(unused -> onFight(neighbour, battle -> {
                                EnderDragon dragon = superior.getNMSDragonFight().getEnderDragon(neighbour, end);
                                if (dragon == null || !BukkitExecutor.owns(dragon) || !dragon.isValid() || dragon.getHealth() <= 0 ||
                                        !dragon.getUniqueId().equals(before.neighbourDragon) || retained.getBlock().getType() != Material.BEDROCK ||
                                        superior.getPvcIslandService().getWorldGeneration(neighbour.getUniqueId()) != before.neighbourGeneration)
                                    throw new IllegalStateException("Reset A changed the neighbour's actual dragon, physical sentinel or generation");
                                for (BlockSnapshot block : before.terrain)
                                    if (!block.data.equals(block.location.getBlock().getBlockData().getAsString()))
                                        throw new IllegalStateException("Reset A changed captured native End B terrain at " + block.location);
                                return null;
                            })).thenCompose(unused -> await(() -> region(source.getCenter(end), () ->
                                    fight(source) == null && !previousArena.isReady() && previousArena.getTicketedChunkCount() == 0 &&
                                            physicalTickets(source) == 0 && RegionalDragonArena.getReservedChunkCount() == 289), 45))
                            .thenCompose(unused -> committedResetOperations(identity).thenApply(committed -> {
                                if (committed.stream().allMatch(previousResets::contains))
                                    throw new IllegalStateException("Occupied End reset has no new actual committed RESET_DONE journal row");
                                return null;
                            }))
                            .thenRun(() -> {
                                occupiedEndReset = true;
                                observer.getLogger().info("SUPERIOR_ADV_OCCUPIED_END_RESET_PASS uuid=" + identity + " shortId=" + shortId +
                                        " sourceGeneration=" + superior.getPvcIslandService().getWorldGeneration(identity) +
                                        " neighbourDragon=" + before.neighbourDragon + " terrainSamples=" + before.terrain.size() +
                                        " nativeResetFuture=completed storagePending=" + ChunkStorageBridge.getPendingCount());
                                reply(primary, "SUPERIOR_ADV_OCCUPIED_END_RESET_PASS spawn=true sameIdentity=true EndGenerated=false sourceTickets=0 neighbour=unchanged");
                            })));
        }).whenComplete((unused, error) -> { if (error != null) fail(primary, error); });
    }

    private void qualifyNativeTopology(Player primary) {
        if (!reactivated || occupiedEndReset || nativeTopology)
            throw new IllegalStateException("Qualify native topology after actual End reactivation and before occupied reset");
        if (topology == null) topology = new NativeTopologyProbe(observer, superior);
        players(primary).thenCompose(visitors -> {
            List<CompletableFuture<Void>> present = new ArrayList<>();
            for (int index = 0; index < 2; ++index) {
                Player visitor = visitors.get(index); Island expected = arenas.get(index);
                present.add(onActor(visitor, () -> {
                    if (visitor.getWorld() != expected.getCenter(end).getWorld() || superior.getGrid().getIslandAt(visitor.getLocation()) != expected)
                        throw new IllegalStateException("Topology fixture requires both actual clients in their separate End arenas");
                    return null;
                }));
            }
            return CompletableFuture.allOf(present.toArray(new CompletableFuture[0]));
        }).thenCompose(unused -> topology.run(primary)).whenComplete((unused, error) -> {
            if (error != null) fail(primary, error);
            else {
                nativeTopology = true;
                observer.getLogger().info("SUPERIOR_NATIVE_TOPOLOGY_PASS realRegions=true realMergeSplit=true observerTicketsReleased=true");
                reply(primary, "SUPERIOR_NATIVE_TOPOLOGY_PASS");
            }
        });
    }

    private CompletableFuture<Void> seedEndSentinel(Island island, Location location) {
        ChunkPosition position = ChunkPosition.of(location.getWorld(), location.getBlockX() >> 4, location.getBlockZ() >> 4, false);
        return ((SIsland) island).prepareWorldWriteIntent(List.of(position))
                .thenCompose(unused -> region(location, () -> { location.getBlock().setType(Material.BEDROCK, false); return null; }))
                .thenCompose(unused -> ChunkStorageBridge.saveChunksAsync(island, List.of(position)));
    }

    private List<BlockSnapshot> captureStableEndTerrain(Island island) {
        Location center = island.getCenter(end); List<BlockSnapshot> samples = new ArrayList<>();
        for (int x = -4; x <= 4 && samples.size() < 32; ++x)
            for (int z = -4; z <= 4 && samples.size() < 32; ++z)
                for (int y = -8; y <= 8 && samples.size() < 32; ++y) {
                    Location at = center.clone().add(x, y, z);
                    Material type = at.getBlock().getType();
                    if (type == Material.BEDROCK || type == Material.OBSIDIAN)
                        samples.add(new BlockSnapshot(at, at.getBlock().getBlockData().getAsString()));
                }
        // The genuine Classic terrain can contain only dirt/grass after native
        // dragon block destruction. Include the previously built and storage-
        // acknowledged waiting platform; capture its actual blocks, not an
        // invented expectation or an AIR-only sample.
        Location floor = safeEndPosition(island).subtract(0, 1, 0);
        for (int x = -1; x <= 1; ++x) for (int z = -1; z <= 1; ++z) {
            Location at = floor.clone().add(x, 0, z);
            if (!BukkitExecutor.owns(at.getWorld(), at.getBlockX() >> 4, at.getBlockZ() >> 4))
                throw new IllegalStateException("End B waiting-platform terrain is foreign to the actual arena owner");
            if (at.getBlock().getType() != Material.BEDROCK)
                throw new IllegalStateException("The real persisted End B waiting platform is missing at " + at);
            samples.add(new BlockSnapshot(at, at.getBlock().getBlockData().getAsString()));
        }
        if (samples.isEmpty()) throw new IllegalStateException("No actual stable native End B terrain samples in the real schematic");
        return List.copyOf(samples);
    }

    private CompletableFuture<java.util.Set<String>> committedResetOperations(UUID island) {
        CompletableFuture<java.util.Set<String>> result = new CompletableFuture<>();
        BukkitExecutor.async(() -> {
            try {
                java.util.Set<String> operations = new java.util.HashSet<>();
                new com.bgsoftware.superiorskyblock.core.database.sql.SQLDatabaseBridge().loadAllObjectsStrict("pvc_island_jobs", row -> {
                    if (island.toString().equals(String.valueOf(row.get("island"))) && "RESET_DONE".equals(String.valueOf(row.get("state"))))
                        operations.add(String.valueOf(row.get("operation")));
                });
                result.complete(java.util.Set.copyOf(operations));
            } catch (Throwable error) { result.completeExceptionally(error); }
        });
        return result.orTimeout(10L, TimeUnit.SECONDS);
    }

    private <T> CompletableFuture<T> onActor(Player actor, Supplier<T> action) {
        CompletableFuture<T> result = new CompletableFuture<>();
        BukkitExecutor.entity(actor, () -> {
            try {
                if (!BukkitExecutor.owns(actor)) throw new IllegalStateException("Actual client callback has no entity ownership");
                result.complete(action.get());
            } catch (Throwable error) { result.completeExceptionally(error); }
        }, 0L, () -> result.completeExceptionally(new IllegalStateException("Actual client callback retired")));
        return result;
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
        if (prepared && !occupiedEndReset) snapshots().whenComplete((values, error) -> {
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
                !killed || !respawned || !released || !reactivated || !nativeTopology || !occupiedEndReset)
            throw new IllegalStateException("Advanced native sequence is incomplete");
        if (trackingCounter("getMergeCount") <= mergesBefore || trackingCounter("getSplitCount") <= splitsBefore)
            throw new IllegalStateException("Actual merge/split callbacks were not both observed");
        if (trackingCounter("getFailureCount") != 0)
            throw new IllegalStateException("A native regional tracking installation/callback failed");
        reply(primary, "SUPERIOR_ADVANCED_PROBE_PASS twoRealClients=true nativeDeathRespawn=true actualMergeSplit=true occupiedEndReset=true");
        observer.getLogger().info("SUPERIOR_ADVANCED_PROBE_PASS");
    }

    private String ids() { return arenas.stream().map(value -> Long.toString(value.getShortId())).reduce((a, b) -> a + "," + b).orElse("none"); }
    private int gridCap() { return superior.getSettings().getMaxIslandSize(); }
    private int defaultRadius() { return Math.min(gridCap(), superior.getSettings().getDefaultValues().getIslandSize()); }
    private void requirePrepared() { if (!prepared) throw new IllegalStateException("Prepare the actual arenas first"); }
    private void reply(Player primary, String message) { BukkitExecutor.entity(primary, () -> primary.sendMessage(message)); }
    private void fail(Player primary, Throwable error) { failed = true; observer.getLogger().log(java.util.logging.Level.SEVERE, "SUPERIOR_ADVANCED_PROBE_FAIL", error); reply(primary, "SUPERIOR_ADVANCED_PROBE_FAIL " + error.getClass().getSimpleName()); }
    private record FightSnapshot(boolean ready, boolean owned, int tickets, int physicalTickets, UUID dragon, UUID boss, boolean visible, int viewers, String arenaLoading) { }
    private record BlockSnapshot(Location location, String data) { }
    private record ResetBaseline(UUID neighbourDragon, long neighbourGeneration, long sourceGeneration, List<BlockSnapshot> terrain) { }
}

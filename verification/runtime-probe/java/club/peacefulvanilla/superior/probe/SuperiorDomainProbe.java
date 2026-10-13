package club.peacefulvanilla.superior.probe;

import com.bgsoftware.superiorskyblock.SuperiorSkyblockPlugin;
import com.bgsoftware.superiorskyblock.api.island.Island;
import com.bgsoftware.superiorskyblock.api.island.IslandExecutionScope;
import com.bgsoftware.superiorskyblock.api.island.PlayerRole;
import com.bgsoftware.superiorskyblock.api.world.Dimension;
import com.bgsoftware.superiorskyblock.api.wrappers.SuperiorPlayer;
import com.bgsoftware.superiorskyblock.island.SIsland;
import com.bgsoftware.superiorskyblock.pvc.entitlement.EntitlementPolicy;
import net.luckperms.api.LuckPermsProvider;
import net.luckperms.api.node.Node;
import net.luckperms.api.node.types.PermissionNode;
import org.bukkit.Bukkit;
import org.bukkit.block.Biome;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

/** Destructive domain observer for a separate tiny, owned fixture; never distributed. */
public final class SuperiorDomainProbe {
    private static final String PRIMARY = "PVCSkyProbe", AUXILIARY = "PVCSkyProbeB";
    private final JavaPlugin observer;
    private final SuperiorSkyblockPlugin superior;
    private final Path root;
    private volatile boolean failed;
    private final java.util.concurrent.atomic.AtomicBoolean storageTestActive = new java.util.concurrent.atomic.AtomicBoolean();
    private final java.util.concurrent.atomic.AtomicLong maximumObservedId = new java.util.concurrent.atomic.AtomicLong();

    public SuperiorDomainProbe(JavaPlugin observer, SuperiorSkyblockPlugin superior) {
        this.observer = observer; this.superior = superior;
        root = observer.getServer().getWorldContainer().toPath().toAbsolutePath().normalize();
        guard();
    }
    private void guard() {
        if (root.getParent() == null || !root.getParent().getFileName().toString().equals("local-servers") ||
                !root.getFileName().toString().startsWith("superior-runtime-") || !Files.isRegularFile(root.resolve(".superior-owned")) ||
                observer.getServer().getPort() < 29010 || observer.getServer().getPort() > 29029 ||
                superior.getSettings().getMaxIslandSize() > 128)
            throw new IllegalStateException("Domain observer requires an owned tiny-grid fixture, never Cielo/production");
    }
    /** Root adapter routes only domain- action suffixes from the actual authenticated primary client. */
    public boolean execute(Player actor, String action) {
        if (!PRIMARY.equals(actor.getName())) return true;
        guard();
        try {
            switch (action) {
                case "create-race" -> createRace(actor);
                case "memberships" -> memberships(actor);
                case "select-first" -> { primary().selectIsland(primary().getIslands().get(0)); reply(actor, "SUPERIOR_DOMAIN_SELECTION_READY"); }
                case "selected-stable" -> selectedStable(actor);
                case "transfer-two" -> transferTwo(actor);
                case "transfer-denied" -> transferDenied(actor);
                case "reset" -> reset(actor);
                case "delete" -> delete(actor);
                case "cold-reset-intent" -> coldReset(actor);
                case "cold-transfer-intent" -> coldTransfer(actor);
                case "cold-delete-intent" -> coldDelete(actor);
                case "cold-delete-check" -> coldDeleteCheck(actor);
                case "storage-reset-failure" -> storageFailure(actor, false, false);
                case "storage-delete-failure" -> storageFailure(actor, true, false);
                case "storage-reset-hold" -> storageFailure(actor, false, true);
                case "storage-delete-hold" -> storageFailure(actor, true, true);
                case "storage-restart-check" -> storageRestartCheck(actor);
                case "checkpoint" -> checkpoint(actor, false);
                case "restart-check" -> checkpoint(actor, true);
                case "status" -> status(actor);
                default -> throw new IllegalArgumentException("Unknown fixed domain case");
            }
        } catch (Throwable error) { fail(actor, error); }
        return true;
    }
    private SuperiorPlayer primary() { return superior.getPlayers().getSuperiorPlayer(requirePlayer(PRIMARY)); }
    private SuperiorPlayer auxiliary() { return superior.getPlayers().getSuperiorPlayer(requirePlayer(AUXILIARY)); }
    private Player requirePlayer(String name) {
        Player player = Bukkit.getPlayerExact(name);
        if (player == null) throw new IllegalStateException("The second real fixture client must be connected: " + name);
        return player;
    }
    private List<Island> owned(SuperiorPlayer player) { return player.getOwnedIslands().stream().sorted(Comparator.comparingLong(Island::getShortId)).toList(); }
    private List<Island> all() {
        List<Island> islands = superior.getGrid().getIslands().stream().sorted(Comparator.comparingLong(Island::getShortId)).toList();
        islands.forEach(island -> maximumObservedId.accumulateAndGet(island.getShortId(), Math::max));
        return islands;
    }
    private CompletableFuture<Void> quota(SuperiorPlayer player, int limit) {
        var api = LuckPermsProvider.get();
        return api.getUserManager().loadUser(player.getUniqueId()).thenCompose(user -> {
            for (Node node : new ArrayList<>(user.getNodes()))
                if (node.getKey().startsWith(EntitlementPolicy.OWNERSHIP_PREFIX)) user.data().remove(node);
            user.data().add(PermissionNode.builder(EntitlementPolicy.OWNERSHIP_PREFIX + limit).build());
            return api.getUserManager().saveUser(user);
        }).thenCompose(unused -> {
            superior.getPvcEntitlements().invalidate(player.getUniqueId());
            return superior.getPvcEntitlements().refreshOwnershipLimit(player.getUniqueId());
        }).thenAccept(actual -> { if (actual != limit) throw new IllegalStateException("Unexpected fixture quota: " + actual); });
    }
    private String schematic() {
        if (superior.getSchematics().getSchematic("normal") != null) return "normal";
        return superior.getSchematics().getSchematics().stream()
                .filter(name -> !name.endsWith("_nether") && !name.endsWith("_the_end") && !name.endsWith("_normal"))
                .findFirst().orElseThrow(() -> new IllegalStateException("No real default schematic"));
    }
    private void createRace(Player actor) {
        SuperiorPlayer owner = primary();
        if (!owner.getOwnedIslands().isEmpty() || !all().isEmpty()) throw new IllegalStateException("Use an empty separate domain fixture");
        actor.setOp(false);
        quota(owner, 2).thenRun(() -> {
            String type = schematic();
            CompletableFuture<Void> one = CompletableFuture.runAsync(() -> superior.getGrid().createIsland(
                    Island.newBuilder().setOwner(owner).setName("DomainA").setSchematicName(type), Biome.PLAINS, false));
            CompletableFuture<Void> two = CompletableFuture.runAsync(() -> superior.getGrid().createIsland(
                    Island.newBuilder().setOwner(owner).setName("DomainB").setSchematicName(type), Biome.PLAINS, false));
            CompletableFuture.allOf(one, two).thenCompose(unused -> await(() -> owned(owner).size() == 2 && all().stream().allMatch(
                    island -> superior.getPvcIslandService().isAvailable(island.getUniqueId())), 120)).thenRun(() -> {
                superior.getGrid().createIsland(Island.newBuilder().setOwner(owner).setName("DomainDenied").setSchematicName(type), Biome.PLAINS, false);
                superior.executeAsync(() -> { if (owned(owner).size() != 2) fail(actor, new IllegalStateException("Quota oversubscribed"));
                    else reply(actor, "SUPERIOR_DOMAIN_CREATE_RACE_PASS count=2"); }, 60L);
            }).exceptionally(error -> { fail(actor, error); return null; });
        }).exceptionally(error -> { fail(actor, error); return null; });
    }
    private void memberships(Player actor) {
        SuperiorPlayer owner = primary(), member = auxiliary(); List<Island> islands = owned(owner);
        if (islands.size() != 2) throw new IllegalStateException("Create two islands first");
        List<PlayerRole> roles = superior.getRoles().getRoles().stream().filter(role -> role.isRoleLadder() && !role.isLastRole()).toList();
        if (roles.size() < 2) throw new IllegalStateException("Fixture must provide two distinct non-owner ladder roles");
        CompletableFuture<Void> chain = CompletableFuture.completedFuture(null);
        for (int i = 0; i < 2; i++) {
            Island island = islands.get(i); PlayerRole role = roles.get(i);
            chain = chain.thenCompose(unused -> superior.getPvcIslandService().acknowledge(island, () -> {
                if (!island.isMember(member)) island.addMember(member, role); else member.setPlayerRole(island, role);
            }));
        }
        chain.thenRun(() -> {
            if (member.getIslands().size() != 2 || member.getPlayerRole(islands.get(0)).equals(member.getPlayerRole(islands.get(1))))
                throw new IllegalStateException("Membership/roles collapsed across islands");
            reply(actor, "SUPERIOR_DOMAIN_MEMBERSHIP_PASS");
        }).exceptionally(error -> { fail(actor, error); return null; });
    }
    private void selectedStable(Player actor) {
        List<Island> islands = owned(primary());
        if (islands.size() != 2 || primary().getSelectedIsland() != islands.get(0)) throw new IllegalStateException("One-off selector changed persistent selection");
        reply(actor, "SUPERIOR_DOMAIN_SELECTOR_PASS selected=" + islands.get(0).getShortId());
    }
    private void transferTwo(Player actor) {
        SuperiorPlayer target = auxiliary(); List<Island> islands = owned(primary());
        if (islands.size() != 2 || !owned(target).isEmpty()) throw new IllegalStateException("Transfer phase requires two primary islands and no auxiliary ownership");
        quota(target, 2).thenCompose(unused -> {
            CompletableFuture<Boolean> one = islands.get(0).transferIslandAsync(target), two = islands.get(1).transferIslandAsync(target);
            return CompletableFuture.allOf(one, two).thenRun(() -> {
                if (!one.join() || !two.join() || owned(target).size() != 2 || !owned(primary()).isEmpty())
                    throw new IllegalStateException("Concurrent incoming transfers double-counted or corrupted quota");
            });
        }).thenRun(() -> reply(actor, "SUPERIOR_DOMAIN_TRANSFER_TWO_PASS")).exceptionally(error -> { fail(actor, error); return null; });
    }
    private void transferDenied(Player actor) {
        SuperiorPlayer target = primary(); List<Island> islands = owned(auxiliary());
        if (islands.size() != 2) throw new IllegalStateException("Run transfer-two first");
        quota(target, 1).thenCompose(unused -> islands.get(0).transferIslandAsync(target)).thenCompose(accepted -> {
            if (!accepted) throw new IllegalStateException("First transfer should fit quota1");
            return islands.get(1).transferIslandAsync(target).handle((denied, error) -> {
                if (error == null && Boolean.TRUE.equals(denied)) throw new IllegalStateException("Second transfer exceeded quota1");
                if (owned(target).size() != 1 || owned(auxiliary()).size() != 1) throw new IllegalStateException("Denied transfer changed ownership");
                reply(actor, "SUPERIOR_DOMAIN_TRANSFER_DENIED_PASS"); return null;
            });
        }).exceptionally(error -> { fail(actor, error); return null; });
    }
    private void reset(Player actor) {
        Island island = owned(primary()).get(0); UUID id = island.getUniqueId(); long shortId = island.getShortId();
        Island neighbour = all().stream().filter(value -> value != island).findFirst().orElseThrow();
        Location cleared = sentinel(island), retained = sentinel(neighbour);
        seed(island, cleared).thenCompose(unused -> seed(neighbour, retained))
                .thenCompose(unused -> ((SIsland) island).resetWorldAsync(defaultDimension()))
                .thenCompose(unused -> assertBlock(cleared, Material.AIR)).thenCompose(unused -> assertBlock(retained, Material.REDSTONE_BLOCK)).thenRun(() -> {
            if (superior.getGrid().getIslandByUUID(id) != island || island.getShortId() != shortId || !island.wasSchematicGenerated(defaultDimension()))
                throw new IllegalStateException("Reset lost stable identity or schematic metadata");
            reply(actor, "SUPERIOR_DOMAIN_RESET_PASS");
        }).exceptionally(error -> { fail(actor, error); return null; });
    }
    private void delete(Player actor) {
        Island island = owned(auxiliary()).get(0); UUID id = island.getUniqueId();
        Island neighbour = owned(primary()).get(0); Location cleared = sentinel(island), retained = sentinel(neighbour);
        seed(island, cleared).thenCompose(unused -> seed(neighbour, retained)).thenCompose(unused -> island.disbandIslandAsync())
                .thenCompose(unused -> assertBlock(cleared, Material.AIR)).thenCompose(unused -> assertBlock(retained, Material.REDSTONE_BLOCK)).thenRun(() -> {
            if (auxiliary().getIslands().stream().anyMatch(value -> value.getUniqueId().equals(id)) || !superior.getGrid().isKnownIslandUUID(id))
                throw new IllegalStateException("Deleted membership or durable ID tombstone is incorrect");
            reply(actor, "SUPERIOR_DOMAIN_DELETE_PASS");
        }).exceptionally(error -> { fail(actor, error); return null; });
    }
    private Dimension defaultDimension() { return superior.getSettings().getWorlds().getDefaultWorldDimension(); }
    private void coldReset(Player actor) {
        SIsland island = (SIsland) owned(primary()).get(0);
        Location cleared = sentinel(island);
        seed(island, cleared).thenCompose(unused -> asyncIO(() -> {
            Properties expectation = new Properties();
            expectation.setProperty("air", encode(cleared));
            try (var out = Files.newOutputStream(root.resolve("domain-physical.properties"))) { expectation.store(out, "Cold reset physical sentinel"); }
        })).thenCompose(unused -> superior.getPvcIslandService().reset(island, "RESET_WORLD:" + defaultDimension().getName(), () -> {
            reply(actor, "SUPERIOR_DOMAIN_COLD_RESET_INTENT_ACK"); return new CompletableFuture<>();
        })).exceptionally(error -> { fail(actor, error); return null; });
    }
    private void coldTransfer(Player actor) {
        SuperiorPlayer owner = primary(), target = auxiliary(); Island island = owned(owner).get(0);
        quota(target, 2).thenCompose(unused -> superior.getPvcIslandService().beginTransfer(island, target)).thenCompose(intent ->
                superior.getPvcIslandService().acknowledge(island, () -> ((SIsland) island).recoverTransfer(target.getUniqueId(),
                        owner.getUniqueId(), owner.getPlayerRole(island).getId(), true)))
                .thenRun(() -> reply(actor, "SUPERIOR_DOMAIN_COLD_TRANSFER_INTENT_ACK"))
                .exceptionally(error -> { fail(actor, error); return null; });
    }
    private void coldDelete(Player actor) {
        Island island = owned(primary()).get(0);
        seed(island, sentinel(island)).thenCompose(unused -> superior.getPvcIslandService().deferDeletion(island))
                .thenRun(() -> reply(actor, "SUPERIOR_DOMAIN_COLD_DELETE_INTENT_ACK"))
                .exceptionally(error -> { fail(actor, error); return null; });
    }

    private Location sentinel(Island island) { Location location = island.getCenter(defaultDimension()).clone().add(10, 0, 0); location.setY(100); return location; }
    private CompletableFuture<Void> seed(Island island, Location location) {
        com.bgsoftware.superiorskyblock.core.ChunkPosition position = com.bgsoftware.superiorskyblock.core.ChunkPosition.of(
                location.getWorld(), location.getBlockX() >> 4, location.getBlockZ() >> 4, false);
        return ((SIsland) island).prepareWorldWriteIntent(List.of(position))
                .thenCompose(unused -> region(location, () -> location.getBlock().setType(Material.REDSTONE_BLOCK, false)));
    }
    private CompletableFuture<Void> assertBlock(Location location, Material expected) {
        return region(location, () -> { if (location.getBlock().getType() != expected)
            throw new IllegalStateException("Physical sentinel mismatch at " + location + ": expected " + expected + " got " + location.getBlock().getType()); });
    }
    private CompletableFuture<Void> region(Location location, Runnable work) {
        CompletableFuture<Void> result = new CompletableFuture<>();
        superior.executeRegion(location, () -> { try { work.run(); result.complete(null); } catch (Throwable error) { result.completeExceptionally(error); } }, 0L);
        return result;
    }
    private void coldDeleteCheck(Player actor) {
        superior.executeAsync(() -> {
            try {
                Properties before = new Properties(); try (var in = Files.newInputStream(root.resolve("domain-state.properties"))) { before.load(in); }
                if (!owned(primary()).isEmpty()) throw new IllegalStateException("Cold deletion retained primary ownership");
                List<Location> oldSentinels = new ArrayList<>(); long previousId = Long.parseLong(before.getProperty("max-observed-id", "0"));
                for (String key : before.stringPropertyNames()) if (key.startsWith("id.")) {
                    UUID id = UUID.fromString(key.substring(3));
                    if (superior.getGrid().getIslandByUUID(id) != null || !superior.getGrid().isKnownIslandUUID(id))
                        throw new IllegalStateException("Cold-deleted live grid/tombstone mismatch: " + id);
                    previousId = Math.max(previousId, Long.parseLong(before.getProperty(key)));
                    oldSentinels.add(decode(before.getProperty("sentinel." + id)));
                }
                final long retiredMaximum = previousId;
                CompletableFuture<Void> checks = CompletableFuture.completedFuture(null);
                for (Location position : oldSentinels) checks = checks.thenCompose(unused -> assertBlock(position, Material.AIR));
                checks.thenRun(() -> superior.getGrid().createIsland(Island.newBuilder().setOwner(primary()).setName("DomainFresh").setSchematicName(schematic()), Biome.PLAINS, false))
                        .thenCompose(unused -> await(() -> owned(primary()).size() == 1 && superior.getPvcIslandService().isAvailable(owned(primary()).get(0).getUniqueId()), 120))
                        .thenRun(() -> { if (owned(primary()).get(0).getShortId() <= retiredMaximum) throw new IllegalStateException("An old shortID was reused");
                            reply(actor, "SUPERIOR_DOMAIN_COLD_DELETE_PASS fresh=" + owned(primary()).get(0).getShortId()); })
                        .exceptionally(error -> { fail(actor, error); return null; });
            } catch (Throwable error) { fail(actor, error); }
        }, 0L);
    }
    /** Test-only adapter wrapping: the release plugin exposes no failure-injection interface. */
    private void storageFailure(Player actor, boolean deleting, boolean hold) {
        if (!storageTestActive.compareAndSet(false, true)) throw new IllegalStateException("A storage observer case is already active");
        SIsland island = (SIsland) owned(primary()).get(0);
        if (!superior.getPvcIslandService().isAvailable(island.getUniqueId())) throw new IllegalStateException("Use an available island");
        CompletableFuture<Island> neighbour = ensureStorageNeighbour(island);
        neighbour.thenCompose(other -> {
            Location cleared = sentinel(island), retained = sentinel(other);
            return seed(island, cleared).thenCompose(unused -> seed(other, retained))
                    .thenCompose(unused -> persistSeed(island, cleared)).thenCompose(unused -> persistSeed(other, retained))
                    .thenCompose(unused -> asyncIO(() -> {
                        Properties expected = snapshot();
                        if (deleting) {
                            String target = island.getUniqueId().toString();
                            for (String key : new ArrayList<>(expected.stringPropertyNames()))
                                if (key.equals("id." + target) || key.equals("owner." + target) || key.equals("sentinel." + target) || key.startsWith("role." + target + ".")) expected.remove(key);
                            for (SuperiorPlayer player : List.of(primary(), auxiliary())) if (player.getSelectedIsland() == island) {
                                List<Island> remaining = player.getIslands().stream().filter(value -> value != island).toList();
                                expected.setProperty("selected." + player.getUniqueId(), remaining.size() == 1 ? remaining.get(0).getUniqueId().toString() : "none");
                            }
                        }
                        expected.setProperty("io.target", island.getUniqueId().toString());
                        expected.setProperty("io.mode", deleting ? "delete" : "reset");
                        expected.setProperty("io.air", encode(cleared)); expected.setProperty("io.neighbour", encode(retained));
                        try (var out = Files.newOutputStream(root.resolve("domain-storage.properties"))) { expected.store(out, "Native storage fault recovery expectation"); }
                    })).thenCompose(unused -> {
                        try {
                            if (com.bgsoftware.superiorskyblock.world.chunk.ChunkStorageBridge.getPendingCount() != 0)
                                throw new IllegalStateException("Wait until unrelated storage operations have drained");
                            java.lang.reflect.Field field = com.bgsoftware.superiorskyblock.world.chunk.ChunkStorageBridge.class.getDeclaredField("adapter");
                            field.setAccessible(true);
                            java.lang.reflect.Method resolve = com.bgsoftware.superiorskyblock.world.chunk.ChunkStorageBridge.class.getDeclaredMethod("adapter");
                            resolve.setAccessible(true);
                            final com.bgsoftware.superiorskyblock.world.chunk.ChunkStorageAdapter original =
                                    (com.bgsoftware.superiorskyblock.world.chunk.ChunkStorageAdapter) resolve.invoke(null);
                            if (original == null) throw new IllegalStateException("Native storage adapter required for fault observer");
                            java.util.concurrent.atomic.AtomicBoolean armed = new java.util.concurrent.atomic.AtomicBoolean(true);
                            com.bgsoftware.superiorskyblock.world.chunk.ChunkStorageAdapter wrapper = new com.bgsoftware.superiorskyblock.world.chunk.ChunkStorageAdapter() {
                                public CompletableFuture<Void> save(org.bukkit.Chunk chunk, java.util.function.BooleanSupplier current,
                                        java.util.function.Consumer<Runnable> admit) {
                                    if (chunk.getWorld().getName().equals(cleared.getWorld().getName()) && chunk.getX() == cleared.getBlockX() >> 4 &&
                                            chunk.getZ() == cleared.getBlockZ() >> 4 && armed.compareAndSet(true, false)) {
                                        if (hold) { reply(actor, "SUPERIOR_DOMAIN_STORAGE_" + (deleting ? "DELETE" : "RESET") + "_WORLD_ACK_HELD"); return new CompletableFuture<>(); }
                                        CompletableFuture<Void> failure = new CompletableFuture<>();
                                        failure.completeExceptionally(new java.io.IOException("Owned fixture native storage failure, one shot")); return failure;
                                    }
                                    return original.save(chunk, current, admit);
                                }
                                public CompletableFuture<Boolean> isGenerated(World world, int x, int z) { return original.isGenerated(world, x, z); }
                                public CompletableFuture<org.bukkit.Chunk> loadExisting(World world, int x, int z) { return original.loadExisting(world, x, z); }
                                public CompletableFuture<List<com.bgsoftware.superiorskyblock.core.ChunkPosition>> existingPositions(
                                        java.util.Collection<com.bgsoftware.superiorskyblock.core.ChunkPosition> positions) { return original.existingPositions(positions); }
                            };
                            field.set(null, wrapper);
                            CompletableFuture<Void> operation;
                            try { operation = deleting ? island.disbandIslandAsync() : island.resetWorldAsync(defaultDimension()); }
                            catch (Throwable kickoff) { field.set(null, original); throw kickoff; }
                            return operation.handle((done, error) -> {
                                try { field.set(null, original); }
                                catch (IllegalAccessException restore) { throw new java.util.concurrent.CompletionException(restore); }
                                if (hold) throw new IllegalStateException("Held world ACK unexpectedly completed before the owned process was killed", error);
                                if (error == null || armed.get()) throw new IllegalStateException("Expected native storage error was not exercised");
                                if (superior.getPvcIslandService().isAvailable(island.getUniqueId()) || superior.getGrid().getIslandByUUID(island.getUniqueId()) != island ||
                                        owned(primary()).stream().noneMatch(value -> value.getUniqueId().equals(island.getUniqueId())))
                                    throw new IllegalStateException("Storage failure released island/cell metadata");
                                return null;
                            }).thenCompose(done -> asyncIO(() -> assertJournal(island.getUniqueId(), deleting ? "DELETING" : "RESET_WORLD:" + defaultDimension().getName())))
                                    .thenCompose(done -> assertBlock(retained, Material.REDSTONE_BLOCK))
                                    .thenRun(() -> reply(actor, "SUPERIOR_DOMAIN_STORAGE_" + (deleting ? "DELETE" : "RESET") + "_FAILURE_PASS"));
                        } catch (Throwable error) { return CompletableFuture.<Void>failedFuture(error); }
                    });
        }).whenComplete((unused, error) -> {
            storageTestActive.set(false); if (error != null) fail(actor, error);
        });
    }
    private CompletableFuture<Island> ensureStorageNeighbour(Island target) {
        Island present = all().stream().filter(value -> value != target).findFirst().orElse(null);
        if (present != null) return CompletableFuture.completedFuture(present);
        SuperiorPlayer owner = auxiliary();
        return quota(owner, 2).thenRun(() -> superior.getGrid().createIsland(Island.newBuilder().setOwner(owner)
                .setName("DomainIOGuard").setSchematicName(schematic()), Biome.PLAINS, false))
                .thenCompose(unused -> await(() -> owned(owner).stream().anyMatch(value -> superior.getPvcIslandService().isAvailable(value.getUniqueId())), 120))
                .thenApply(unused -> owned(owner).get(0));
    }
    private CompletableFuture<Void> persistSeed(Island island, Location location) {
        return com.bgsoftware.superiorskyblock.world.chunk.ChunkStorageBridge.saveChunksAsync(island, List.of(
                com.bgsoftware.superiorskyblock.core.ChunkPosition.of(location.getWorld(), location.getBlockX() >> 4, location.getBlockZ() >> 4, false)));
    }
    private void assertJournal(UUID island, String state) {
        java.util.concurrent.atomic.AtomicBoolean found = new java.util.concurrent.atomic.AtomicBoolean();
        // The maintained bridge performs actual strict native SQL reads under
        // DBSession's shared-connection lock. Its public descriptor uses only
        // java.util/function types, avoiding the release's relocated Common API.
        new com.bgsoftware.superiorskyblock.core.database.sql.SQLDatabaseBridge().loadAllObjectsStrict("pvc_island_jobs", row -> {
            if (island.toString().equals(String.valueOf(row.get("island"))) &&
                    state.equals(String.valueOf(row.get("state")))) found.set(true);
        });
        if (!found.get()) throw new IllegalStateException("Native nonterminal journal intent was not retained");
    }
    private void storageRestartCheck(Player actor) {
        superior.executeAsync(() -> {
            try {
                Properties expected = new Properties(); try (var in = Files.newInputStream(root.resolve("domain-storage.properties"))) { expected.load(in); }
                UUID target = UUID.fromString(expected.getProperty("io.target")); boolean deleting = "delete".equals(expected.getProperty("io.mode"));
                Location cleared = decode(expected.getProperty("io.air")), retained = decode(expected.getProperty("io.neighbour"));
                maximumObservedId.accumulateAndGet(Long.parseLong(expected.getProperty("max-observed-id")), Math::max);
                for (String key : new ArrayList<>(expected.stringPropertyNames())) if (key.startsWith("io.")) expected.remove(key);
                if (!expected.equals(snapshot())) throw new IllegalStateException("Storage failure cold recovery lost owner/roles/selection/IDs");
                Island recovered = superior.getGrid().getIslandByUUID(target);
                if (deleting ? recovered != null || !superior.getGrid().isKnownIslandUUID(target) : recovered == null || !superior.getPvcIslandService().isAvailable(target))
                    throw new IllegalStateException("Storage failure recovery did not finish the intended operation");
                assertBlock(cleared, Material.AIR).thenCompose(unused -> assertBlock(retained, Material.REDSTONE_BLOCK))
                        .thenRun(() -> reply(actor, "SUPERIOR_DOMAIN_STORAGE_RESTART_PASS mode=" + (deleting ? "delete" : "reset")))
                        .exceptionally(error -> { fail(actor, error); return null; });
            } catch (Throwable error) { fail(actor, error); }
        }, 0L);
    }

    private Properties snapshot() {
        Properties state = new Properties();
        for (Island island : all()) {
            state.setProperty("id." + island.getUniqueId(), Long.toString(island.getShortId()));
            state.setProperty("owner." + island.getUniqueId(), island.getOwner().getUniqueId().toString());
            Location sentinel = sentinel(island);
            state.setProperty("sentinel." + island.getUniqueId(), encode(sentinel));
            for (SuperiorPlayer member : island.getIslandMembers(true)) state.setProperty("role." + island.getUniqueId() + "." + member.getUniqueId(), Integer.toString(member.getPlayerRole(island).getId()));
        }
        for (SuperiorPlayer player : List.of(primary(), auxiliary())) state.setProperty("selected." + player.getUniqueId(),
                player.getSelectedIsland() == null ? "none" : player.getSelectedIsland().getUniqueId().toString());
        state.setProperty("max-observed-id", Long.toString(maximumObservedId.get()));
        return state;
    }
    private void checkpoint(Player actor, boolean restart) {
        if (failed) throw new IllegalStateException("An earlier domain assertion failed");
        Path file = root.resolve("domain-state.properties");
        superior.executeAsync(() -> {
            try {
                if (restart) {
                    Properties previous = new Properties(); try (var in = Files.newInputStream(file)) { previous.load(in); }
                    maximumObservedId.accumulateAndGet(Long.parseLong(previous.getProperty("max-observed-id", "0")), Math::max);
                    if (!previous.equals(snapshot())) throw new IllegalStateException("Owner/role/selection/ID changed across cold restart");
                    Path physical = root.resolve("domain-physical.properties");
                    if (Files.isRegularFile(physical)) {
                        Properties expectation = new Properties(); try (var in = Files.newInputStream(physical)) { expectation.load(in); }
                        assertBlock(decode(expectation.getProperty("air")), Material.AIR)
                                .thenCompose(unused -> asyncIO(() -> Files.delete(physical)))
                                .thenRun(() -> reply(actor, "SUPERIOR_DOMAIN_RESTART_PASS physical=AIR"))
                                .exceptionally(error -> { fail(actor, error); return null; });
                        return;
                    }
                } else try (var out = Files.newOutputStream(file)) { snapshot().store(out, "Guarded synthetic native domain state"); }
                reply(actor, restart ? "SUPERIOR_DOMAIN_RESTART_PASS" : "SUPERIOR_DOMAIN_CHECKPOINT_ACK");
            } catch (Throwable error) { fail(actor, error); }
        }, 0L);
    }
    private String encode(Location location) { return location.getWorld().getName() + "," + location.getBlockX() + "," + location.getBlockY() + "," + location.getBlockZ(); }
    private Location decode(String encoded) {
        String[] values = encoded.split(",");
        World world = Bukkit.getWorld(values[0]); if (world == null) throw new IllegalStateException("Sentinel world is not loaded");
        return new Location(world, Integer.parseInt(values[1]), Integer.parseInt(values[2]), Integer.parseInt(values[3]));
    }
    @FunctionalInterface private interface IOAction { void run() throws Exception; }
    private CompletableFuture<Void> asyncIO(IOAction work) {
        CompletableFuture<Void> result = new CompletableFuture<>();
        superior.executeAsync(() -> { try { work.run(); result.complete(null); } catch (Throwable error) { result.completeExceptionally(error); } }, 0L);
        return result;
    }
    private void status(Player actor) { reply(actor, "SUPERIOR_DOMAIN_STATUS ready=" + superior.getPvcIslandService().isReady() +
            " primary=" + owned(primary()).size() + " auxiliary=" + owned(auxiliary()).size() + " total=" + all().size()); }
    private CompletableFuture<Void> await(Supplier<Boolean> test, int attempts) {
        CompletableFuture<Void> future = new CompletableFuture<>();
        superior.executeAsync(() -> {
            try {
                if (test.get()) future.complete(null);
                else if (attempts <= 0) future.completeExceptionally(new IllegalStateException("Native domain assertion timed out"));
                else await(test, attempts - 1).whenComplete((unused, error) -> { if (error == null) future.complete(null); else future.completeExceptionally(error); });
            } catch (Throwable error) { future.completeExceptionally(error); }
        }, 20L);
        return future;
    }
    private void reply(Player actor, String message) { superior.executePlayer(superior.getPlayers().getSuperiorPlayer(actor), () -> actor.sendMessage(message), 0L); observer.getLogger().info(message); }
    private void fail(Player actor, Throwable error) { failed = true; observer.getLogger().log(java.util.logging.Level.SEVERE, "SUPERIOR_DOMAIN_FAIL", error); reply(actor, "SUPERIOR_DOMAIN_FAIL " + error.getClass().getSimpleName()); }
}

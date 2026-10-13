package club.peacefulvanilla.superior.probe;

import com.bgsoftware.superiorskyblock.SuperiorSkyblockPlugin;
import com.bgsoftware.superiorskyblock.api.island.Island;
import com.bgsoftware.superiorskyblock.api.player.algorithm.PlayerTeleportAlgorithm;
import com.bgsoftware.superiorskyblock.api.wrappers.SuperiorPlayer;
import com.bgsoftware.superiorskyblock.core.messages.Message;
import com.bgsoftware.superiorskyblock.core.threads.BukkitExecutor;
import com.bgsoftware.superiorskyblock.pvc.PvcConfig;
import com.bgsoftware.superiorskyblock.pvc.entitlement.EntitlementPolicy;
import net.luckperms.api.LuckPermsProvider;
import net.luckperms.api.node.types.PermissionNode;
import org.bukkit.ChatColor;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Properties;
import java.util.concurrent.CompletableFuture;

/** Test-only LEGACY observer. The release never contains this class or its private fixture. */
public final class SuperiorLegacyProbe {
    private static final String ACCOUNT = "PVCSkyProbe";
    private final JavaPlugin observer;
    private final SuperiorSkyblockPlugin superior;
    private final Path root;
    private volatile boolean failed;
    private boolean checkedHome, checkedDenial;

    public SuperiorLegacyProbe(JavaPlugin observer, SuperiorSkyblockPlugin superior) {
        this.observer = observer;
        this.superior = superior;
        this.root = observer.getServer().getWorldContainer().toPath().toAbsolutePath().normalize();
        guard();
    }

    private void guard() {
        if (root.getParent() == null || !root.getParent().getFileName().toString().equals("local-servers")
                || !root.getFileName().toString().startsWith("superior-runtime-")
                || !Files.isRegularFile(root.resolve(".superior-owned"))
                || observer.getServer().getPort() < 29010 || observer.getServer().getPort() > 29029)
            throw new IllegalStateException("Legacy observer refuses unowned or production paths");
        if (superior.getPvcConfig().getSizeMode() != PvcConfig.SizeMode.LEGACY
                || superior.getSettings().getDefaultValues().getIslandSize() != 100)
            throw new IllegalStateException("Legacy observer requires the existing Cielo size configuration");
    }

    /** Root routes legacy- suffixes here only from the real authenticated fixed fixture client. */
    public boolean execute(Player player, String action) {
        if (!ACCOUNT.equals(player.getName())) return true;
        try {
            guard();
            switch (action) {
                case "ready" -> ready(player);
                case "status" -> status(player);
                case "away" -> away(player);
                case "homecheck" -> homecheck(player);
                case "denycheck" -> denycheck(player);
                case "finish" -> finish(player, false);
                case "restartfinish" -> finish(player, true);
                case "upgrade-check" -> upgradeCheck(player, false);
                case "upgrade-restart-check" -> upgradeCheck(player, true);
                default -> throw new IllegalArgumentException("Unknown fixed LEGACY case");
            }
        } catch (Throwable error) { fail(player, error); }
        return true;
    }

    private SuperiorPlayer account(Player player) { return superior.getPlayers().getSuperiorPlayer(player); }
    private List<Island> owned(Player player) {
        return superior.getGrid().getOwnedIslands(player.getUniqueId()).stream()
                .filter(island -> !island.isSpawn()).sorted(Comparator.comparingLong(Island::getShortId)).toList();
    }
    private Island sole(Player player) {
        List<Island> islands = owned(player);
        if (islands.size() != 1 || islands.get(0).getIslandSize() != 100 || islands.get(0).getShortId() <= 0)
            throw new IllegalStateException("Expected one actual radius-100 island with a durable short ID");
        Island island = islands.get(0);
        Island selected = account(player).getSelectedIsland();
        if (selected == null || !selected.getUniqueId().equals(island.getUniqueId())
                || superior.getGrid().hasActiveCreateRequest(account(player))
                || !superior.getPvcIslandService().isAvailable(island.getUniqueId()))
            throw new IllegalStateException("Sole island selection or terminal creation state is inconsistent");
        return island;
    }

    private void ready(Player player) {
        if (player.isOp()) throw new IllegalStateException("LEGACY probe must exercise a non-OP player");
        player.setAllowFlight(true); player.setFlying(true); player.setInvulnerable(true);
        var api = LuckPermsProvider.get();
        api.getUserManager().loadUser(player.getUniqueId()).thenCompose(user -> {
            if (user.getNodes().stream().anyMatch(node -> node.getKey().startsWith(EntitlementPolicy.OWNERSHIP_PREFIX)
                    || node.getKey().startsWith(EntitlementPolicy.RADIUS_PREFIX)
                    || node.getKey().equals("superior.*") || node.getKey().equals("*")))
                throw new IllegalStateException("LEGACY probe requires an account without entitlement grants");
            for (String suffix : List.of("create", "teleport", "list", "select"))
                user.data().add(PermissionNode.builder("superior.island." + suffix).build());
            user.data().add(PermissionNode.builder("superior.island").build());
            user.data().add(PermissionNode.builder("superior.island.schematic.classic").build());
            return api.getUserManager().saveUser(user);
        }).thenCompose(unused -> superior.getPvcEntitlements().refreshOwnershipLimit(player.getUniqueId()))
                .thenCompose(limit -> {
                    if (limit != 1) throw new IllegalStateException("Default account must have authoritative ownership quota 1");
                    return inspectMigration();
                }).whenComplete((state, error) -> {
                    if (error != null) fail(player, error);
                    else {
                        try {
                            reply(player, "SUPERIOR_LEGACY_READY mode=LEGACY quota=1 preserved=" + state.getProperty("keys")
                                    + " skipSingleMenu=" + superior.getSettings().isSkipOneItemMenus()
                                    + " denial=" + encodedMessage(player, Message.ISLAND_ALREADY_EXIST)
                                    + " home=" + encodedMessage(player, Message.TELEPORTED_SUCCESS));
                        } catch (Throwable failure) { fail(player, failure); }
                    }
                });
    }

    private void status(Player player) {
        List<Island> islands = owned(player);
        Island selected = account(player).getSelectedIsland();
        reply(player, "SUPERIOR_LEGACY_STATUS mode=" + superior.getPvcConfig().getSizeMode()
                + " count=" + islands.size() + " quota=" + superior.getPvcEntitlements().getOwnershipLimit(player.getUniqueId())
                + " ids=" + islands.stream().map(island -> Long.toString(island.getShortId())).reduce((a, b) -> a + "," + b).orElse("none")
                + " uuids=" + islands.stream().map(island -> island.getUniqueId().toString()).reduce((a, b) -> a + "," + b).orElse("none")
                + " radii=" + islands.stream().map(island -> Integer.toString(island.getIslandSize())).reduce((a, b) -> a + "," + b).orElse("none")
                + " selected=" + (selected == null ? 0 : selected.getShortId())
                + " pending=" + superior.getGrid().hasActiveCreateRequest(account(player)));
    }

    private void away(Player player) {
        Island island = sole(player);
        Location home = island.getIslandHome(superior.getSettings().getWorlds().getDefaultWorldDimension());
        if (home == null || home.getWorld() == null) throw new IllegalStateException("Actual island home is missing");
        account(player).teleportWithResult(home.clone().add(12, 4, 0), result -> {
            if (result != PlayerTeleportAlgorithm.TeleportResult.SUCCESS)
                fail(player, new IllegalStateException("Native away teleport failed: " + result));
            else reply(player, "SUPERIOR_LEGACY_AWAY_READY");
        });
    }

    private void homecheck(Player player) {
        Island island = sole(player);
        Location home = island.getIslandHome(superior.getSettings().getWorlds().getDefaultWorldDimension());
        Location actual = player.getLocation();
        if (home == null || actual.getWorld() != home.getWorld() || actual.distanceSquared(home) > 25
                || !island.isInside(actual))
            throw new IllegalStateException("Argument-free /is home did not return to the sole island home");
        checkedHome = true;
        reply(player, "SUPERIOR_LEGACY_HOME_PASS island=" + island.getShortId());
    }

    private void denycheck(Player player) {
        sole(player);
        superior.getPvcEntitlements().refreshOwnershipLimit(player.getUniqueId()).whenComplete((quota, error) -> {
            if (error != null) fail(player, error);
            else BukkitExecutor.entity(player, () -> {
                try {
                    if (quota != 1) throw new IllegalStateException("Second-island attempt had an unexpected quota");
                    sole(player);
                    checkedDenial = true;
                    reply(player, "SUPERIOR_LEGACY_QUOTA_DENIAL_PASS");
                } catch (Throwable failure) { fail(player, failure); }
            }, 0L, () -> fail(player, new IllegalStateException("Real player retired before terminal denial check")));
        });
    }

    private CompletableFuture<Properties> inspectMigration() {
        CompletableFuture<Properties> result = new CompletableFuture<>();
        superior.executeAsync(() -> {
            try {
                guard();
                Path beforePath = root.resolve("staged-before-config.yml");
                Path configPath = superior.getDataFolder().toPath().resolve("config.yml");
                byte[] beforeBytes = Files.readAllBytes(beforePath), currentBytes = Files.readAllBytes(configPath);
                byte[] backupBytes = Files.readAllBytes(configPath.resolveSibling("config.yml.pvc-backup"));
                if (!Arrays.equals(beforeBytes, backupBytes))
                    throw new IllegalStateException("Migration backup differs from the staged pre-start bytes");
                YamlConfiguration before = parse(beforeBytes), current = parse(currentBytes);
                if (before.contains("pvc")) throw new IllegalStateException("Exact LEGACY source fixture must not preseed PVC settings");
                int count = 0;
                for (String key : before.getKeys(true)) {
                    if (!current.contains(key)) throw new IllegalStateException("Existing config key was removed: " + key);
                    if (before.isConfigurationSection(key)) {
                        if (!current.isConfigurationSection(key)) throw new IllegalStateException("Existing config mapping changed: " + key);
                    } else if (!Objects.equals(before.get(key), current.get(key)))
                        throw new IllegalStateException("Existing config value changed: " + key);
                    count++;
                }
                if (!"LEGACY".equals(current.getString("pvc.size-mode")))
                    throw new IllegalStateException("Automatic migration did not select LEGACY");
                Properties state = new Properties();
                state.setProperty("keys", Integer.toString(count));
                state.setProperty("source.sha256", sha256(beforeBytes));
                state.setProperty("backup.sha256", sha256(backupBytes));
                state.setProperty("migrated.sha256", sha256(currentBytes));
                result.complete(state);
            } catch (Throwable error) { result.completeExceptionally(error); }
        }, 0);
        return result;
    }

    private void finish(Player player, boolean restart) {
        if (failed || !checkedHome || (!restart && !checkedDenial))
            throw new IllegalStateException("Required native LEGACY checks are incomplete");
        Island island = sole(player);
        Properties identity = new Properties();
        identity.setProperty("owner", player.getUniqueId().toString());
        identity.setProperty("island.uuid", island.getUniqueId().toString());
        identity.setProperty("island.short-id", Long.toString(island.getShortId()));
        identity.setProperty("island.radius", Integer.toString(island.getIslandSize()));
        identity.setProperty("selected", Long.toString(account(player).getSelectedIsland().getShortId()));
        inspectMigration().thenAccept(state -> {
            state.putAll(identity);
            Path saved = root.resolve("legacy-probe-state.properties");
            try {
                guard();
                if (restart) {
                    Properties original = new Properties();
                    try (var input = Files.newInputStream(saved)) { original.load(input); }
                    if (!state.equals(original)) throw new IllegalStateException("Restart altered identity, selection, radius or migration bytes");
                } else {
                    if (Files.exists(saved)) throw new IllegalStateException("Initial LEGACY checkpoint already exists");
                    try (var output = Files.newOutputStream(saved)) { state.store(output, "Synthetic owned LEGACY fixture checkpoint"); }
                }
                String marker = restart ? "SUPERIOR_LEGACY_RESTART_PASS" : "SUPERIOR_LEGACY_PROBE_PASS";
                observer.getLogger().info(marker + " uuid=" + island.getUniqueId() + " shortId=" + island.getShortId()
                        + " radius=100 selected=" + identity.getProperty("selected") + " preserved=" + state.getProperty("keys"));
                reply(player, marker);
            } catch (Throwable error) { throw new java.util.concurrent.CompletionException(error); }
        }).whenComplete((unused, error) -> { if (error != null) fail(player, error); });
    }
    private static YamlConfiguration parse(byte[] bytes) throws Exception {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.loadFromString(new String(bytes, StandardCharsets.UTF_8));
        return yaml;
    }
    private static String sha256(byte[] bytes) throws Exception {
        StringBuilder hash = new StringBuilder();
        for (byte value : MessageDigest.getInstance("SHA-256").digest(bytes)) hash.append(String.format("%02x", value & 255));
        return hash.toString();
    }
    private String encodedMessage(Player player, Message message, Object... arguments) {
        String text = ChatColor.stripColor(ChatColor.translateAlternateColorCodes('&',
                message.getMessage(account(player).getUserLocale(), arguments)));
        if (text == null || text.isEmpty()) throw new IllegalStateException("Required native feedback is disabled");
        return Base64.getEncoder().encodeToString(text.getBytes(StandardCharsets.UTF_8));
    }
    private void guardUpgrade() {
        guard();
        if (!Files.isRegularFile(root.resolve(".superior-upgrade-owned")) || BukkitExecutor.isFolia()
                || !"127.0.0.1".equals(observer.getServer().getIp()) || !"Leaf".equalsIgnoreCase(observer.getServer().getName()))
            throw new IllegalStateException("Populated upgrade checks require the separately owned private Leaf fixture");
    }
    private void upgradeCheck(Player player, boolean restart) {
        guardUpgrade();
        CompletableFuture<Properties> checkpoint = new CompletableFuture<>();
        superior.executeAsync(() -> {
            try {
                guardUpgrade(); Path path = root.resolve("upstream-island-checkpoint.properties");
                if (Files.size(path) > 262144) throw new IllegalStateException("Original populated checkpoint is oversized");
                byte[] bytes = Files.readAllBytes(path);
                String expected = new String(Files.readAllBytes(root.resolve("upstream-island-checkpoint.properties.sha256")), StandardCharsets.US_ASCII).trim();
                if (!expected.matches("[0-9a-f]{64}") || !expected.equals(sha256(bytes)))
                    throw new IllegalStateException("Immutable original checkpoint digest changed");
                Properties loaded = new Properties();
                try (var input = new java.io.ByteArrayInputStream(bytes)) { loaded.load(input); }
                if (!"1".equals(loaded.getProperty("format")) || !"2026.3".equals(loaded.getProperty("source.plugin.version"))
                        || !"552a9ad27b2ccf0b6a8e124805220333c125e361a992a8e82deb62088ba831c1".equals(loaded.getProperty("source.jar.sha256"))
                        || !Bukkit.getMinecraftVersion().equals(loaded.getProperty("source.minecraft.version"))
                        || !ACCOUNT.equals(loaded.getProperty("owner.name")) || !player.getUniqueId().toString().equals(loaded.getProperty("owner.uuid")))
                    throw new IllegalStateException("Checkpoint provenance or actual owner identity differs");
                loaded.setProperty("checkpoint.sha256", expected);
                checkpoint.complete(loaded);
            } catch (Throwable error) { checkpoint.completeExceptionally(error); }
        }, 0L);
        checkpoint.thenCombine(inspectMigration(), (before, migration) -> new UpgradeState(before, migration))
                .thenCompose(state -> onUpgradePlayer(player, () -> {
                    verifyUpgradeMetadata(player, state.before); return state;
                })).thenCompose(state -> {
                    Island island = sole(player);
                    World world = island.getCenter(superior.getSettings().getWorlds().getDefaultWorldDimension()).getWorld();
                    int count = boundedCount(state.before, "blocks.count", 16, 512);
                    java.util.Set<String> chunks = new java.util.HashSet<>();
                    List<CompletableFuture<?>> loads = new ArrayList<>();
                    for (int index = 0; index < count; index++) {
                        int x = integer(state.before, "block." + index + ".x") >> 4, z = integer(state.before, "block." + index + ".z") >> 4;
                        if (chunks.add(x + ":" + z)) loads.add(world.getChunkAtAsync(x, z, true));
                    }
                    if (chunks.size() > 9) throw new IllegalStateException("Original solid sample exceeds its bounded chunk footprint");
                    return CompletableFuture.allOf(loads.toArray(new CompletableFuture<?>[0])).thenApply(unused -> state);
                }).thenCompose(state -> onUpgradePlayer(player, () -> {
                    Island island = verifyUpgradeMetadata(player, state.before);
                    World world = island.getCenter(superior.getSettings().getWorlds().getDefaultWorldDimension()).getWorld();
                    int count = boundedCount(state.before, "blocks.count", 16, 512);
                    for (int index = 0; index < count; index++) {
                        String key = "block." + index;
                        Block block = world.getBlockAt(integer(state.before, key + ".x"), integer(state.before, key + ".y"), integer(state.before, key + ".z"));
                        if (!block.getType().name().equals(required(state.before, key + ".material"))
                                || !block.getBlockData().getAsString().equals(required(state.before, key + ".data")))
                            throw new IllegalStateException("Original actual island solid changed during fork migration at sample " + index);
                    }
                    state.migration.setProperty("format", "1");
                    state.migration.setProperty("checkpoint.sha256", required(state.before, "checkpoint.sha256"));
                    state.migration.setProperty("island.uuid", island.getUniqueId().toString());
                    state.migration.setProperty("island.short-id", Long.toString(island.getShortId()));
                    state.migration.setProperty("selected", Long.toString(account(player).getSelectedIsland().getShortId()));
                    state.migration.setProperty("radius", Integer.toString(island.getIslandSize()));
                    state.migration.setProperty("solids", Integer.toString(count));
                    return state;
                })).thenAccept(state -> superior.executeAsync(() -> {
                    try {
                        guardUpgrade(); Path migrated = root.resolve("upstream-migration-state.properties");
                        if (restart) {
                            Properties before = new Properties(); try (var input = Files.newInputStream(migrated)) { before.load(input); }
                            if (!state.migration.equals(before))
                                throw new IllegalStateException("Upgrade short ID/selection, original checkpoint or migration bytes changed after restart");
                        } else {
                            ByteArrayOutputStream encoded = new ByteArrayOutputStream();
                            state.migration.store(encoded, "Immutable synthetic populated fork migration state v1");
                            try (FileChannel output = FileChannel.open(migrated, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
                                ByteBuffer bytes = ByteBuffer.wrap(encoded.toByteArray()); while (bytes.hasRemaining()) output.write(bytes); output.force(true);
                            }
                        }
                        String marker = restart ? "SUPERIOR_UPGRADE_RESTART_PASS" : "SUPERIOR_UPGRADE_MIGRATION_PASS";
                        observer.getLogger().info(marker + " uuid=" + state.migration.getProperty("island.uuid")
                                + " shortId=" + state.migration.getProperty("island.short-id") + " radius=100 solids=" + state.migration.getProperty("solids"));
                        reply(player, marker);
                    } catch (Throwable error) { fail(player, error); }
                }, 0L)).exceptionally(error -> { fail(player, error); return null; });
    }
    private Island verifyUpgradeMetadata(Player player, Properties before) {
        guardUpgrade();
        if (player.isOp()) throw new IllegalStateException("Populated fork upgrade check requires revoked synthetic OP");
        Island island = sole(player);
        if (!island.getUniqueId().toString().equals(required(before, "island.uuid"))
                || !island.getOwner().getUniqueId().equals(player.getUniqueId())
                || island.getIslandSize() != integer(before, "island.radius") || island.getIslandSizeRaw() != integer(before, "island.raw-size")
                || account(player).getOwnedIslands().size() != 1 || account(player).getIslands().size() != 1)
            throw new IllegalStateException("Original singleton ownership, UUID or size changed during fork migration");
        var dimension = superior.getSettings().getWorlds().getDefaultWorldDimension();
        verifyLocation(before, "center", island.getCenter(dimension)); verifyLocation(before, "home", island.getIslandHome(dimension));
        List<SuperiorPlayer> members = new ArrayList<>(island.getIslandMembers(true));
        members.sort(Comparator.comparing(member -> member.getUniqueId().toString()));
        int count = boundedCount(before, "members.count", 1, 16);
        if (members.size() != count) throw new IllegalStateException("Original member roster changed during migration");
        for (int index = 0; index < count; index++) {
            SuperiorPlayer member = members.get(index); String key = "member." + index;
            if (!member.getUniqueId().toString().equals(required(before, key + ".uuid"))
                    || member.getPlayerRole(island).getId() != integer(before, key + ".role.id")
                    || !member.getPlayerRole(island).getName().equals(required(before, key + ".role.name")) || !island.isMember(member))
                throw new IllegalStateException("Original per-island member role changed during migration");
        }
        Location center = island.getCenter(dimension);
        int samples = boundedCount(before, "blocks.count", 16, 512);
        for (int index = 0; index < samples; index++) {
            String key = "block." + index;
            if (Math.abs((long) integer(before, key + ".x") - center.getBlockX()) > 8
                    || Math.abs((long) integer(before, key + ".z") - center.getBlockZ()) > 8
                    || Math.abs((long) integer(before, key + ".y") - center.getBlockY()) > 12)
                throw new IllegalStateException("Original sample lies outside the bounded upstream observation volume");
        }
        return island;
    }
    private <T> CompletableFuture<T> onUpgradePlayer(Player player, java.util.function.Supplier<T> action) {
        CompletableFuture<T> result = new CompletableFuture<>();
        BukkitExecutor.entity(player, () -> {
            try { result.complete(action.get()); } catch (Throwable error) { result.completeExceptionally(error); }
        }, 0L, () -> result.completeExceptionally(new IllegalStateException("Actual upgrade client retired")));
        return result;
    }
    private static void verifyLocation(Properties state, String key, Location actual) {
        if (actual == null || actual.getWorld() == null || !actual.getWorld().getName().equals(required(state, key + ".world.name"))
                || !actual.getWorld().getUID().toString().equals(required(state, key + ".world.uuid"))
                || Double.compare(actual.getX(), Double.parseDouble(required(state, key + ".x"))) != 0
                || Double.compare(actual.getY(), Double.parseDouble(required(state, key + ".y"))) != 0
                || Double.compare(actual.getZ(), Double.parseDouble(required(state, key + ".z"))) != 0
                || Float.compare(actual.getYaw(), Float.parseFloat(required(state, key + ".yaw"))) != 0
                || Float.compare(actual.getPitch(), Float.parseFloat(required(state, key + ".pitch"))) != 0)
            throw new IllegalStateException("Original " + key + " world or coordinates changed during migration");
    }
    private static int boundedCount(Properties state, String key, int minimum, int maximum) {
        int value = integer(state, key); if (value < minimum || value > maximum) throw new IllegalStateException("Invalid checkpoint count " + key); return value;
    }
    private static int integer(Properties state, String key) { return Integer.parseInt(required(state, key)); }
    private static String required(Properties state, String key) {
        String value = state.getProperty(key); if (value == null) throw new IllegalStateException("Missing immutable checkpoint key " + key); return value;
    }
    private static final class UpgradeState {
        final Properties before, migration;
        UpgradeState(Properties before, Properties migration) { this.before = before; this.migration = migration; }
    }
    private void reply(Player player, String message) {
        superior.executePlayer(account(player), () -> player.sendMessage(message), 0);
    }
    private void fail(Player player, Throwable error) {
        failed = true;
        observer.getLogger().log(java.util.logging.Level.SEVERE, "SUPERIOR_LEGACY_PROBE_FAIL", error);
        reply(player, "SUPERIOR_LEGACY_PROBE_FAIL " + error.getClass().getSimpleName());
    }
}

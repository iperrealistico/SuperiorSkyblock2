package club.peacefulvanilla.superior.seed;

import com.bgsoftware.superiorskyblock.api.SuperiorSkyblockAPI;
import com.bgsoftware.superiorskyblock.api.events.PostIslandCreateEvent;
import com.bgsoftware.superiorskyblock.api.island.Island;
import com.bgsoftware.superiorskyblock.api.world.Dimension;
import com.bgsoftware.superiorskyblock.api.wrappers.SuperiorPlayer;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/** Java8 observer using only unchanged upstream public API and Paper Bukkit; never shipped. */
public final class NativeUpstreamSeed extends JavaPlugin implements Listener {
    private static final String ACCOUNT = "PVCSkyProbe";
    private static final String ORIGINAL_SHA = "552a9ad27b2ccf0b6a8e124805220333c125e361a992a8e82deb62088ba831c1";
    private static final List<String> STABLE_SOLIDS = Collections.unmodifiableList(Arrays.asList(
            "STONE", "COBBLESTONE", "DIRT", "GRASS_BLOCK", "BEDROCK", "CHEST", "OAK_LOG", "OAK_WOOD"));
    private Path root;
    private volatile UUID completedCreation;
    private volatile boolean recording;

    @Override public void onEnable() {
        root = getServer().getWorldContainer().toPath().toAbsolutePath().normalize();
        guard();
        Plugin original = Bukkit.getPluginManager().getPlugin("SuperiorSkyblock2");
        if (original == null || !original.isEnabled() || !"2026.3".equals(original.getDescription().getVersion()))
            throw new IllegalStateException("Seed requires the unchanged original SuperiorSkyblock2 2026.3");
        try {
            if (!ORIGINAL_SHA.equals(fileHash(root.resolve("plugins/SuperiorSkyblock2.jar"))))
                throw new IllegalStateException("Original SuperiorSkyblock2 artifact digest differs");
        } catch (Exception error) { throw new IllegalStateException("Cannot verify unchanged baseline artifact", error); }
        Bukkit.getPluginManager().registerEvents(this, this);
        getLogger().info("SUPERIOR_UPSTREAM_SEED_OBSERVER_READY");
    }
    private void guard() {
        if (root.getParent() == null || !"local-servers".equals(root.getParent().getFileName().toString())
                || !root.getFileName().toString().startsWith("superior-runtime-")
                || !Files.isRegularFile(root.resolve(".superior-owned"))
                || !Files.isRegularFile(root.resolve(".superior-upgrade-owned"))
                || getServer().getPort() < 29010 || getServer().getPort() > 29029
                || !"127.0.0.1".equals(getServer().getIp()) || !"Leaf".equalsIgnoreCase(getServer().getName()))
            throw new IllegalStateException("Upstream seed refuses non-Leaf, unowned or production fixtures");
        try {
            Class.forName("io.papermc.paper.threadedregions.RegionizedServer");
            throw new IllegalStateException("Unchanged upstream seed is intentionally Leaf-only");
        } catch (ClassNotFoundException expected) { }
    }
    @Override public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player) || !ACCOUNT.equals(sender.getName())) return true;
        final Player player = (Player) sender;
        try {
            guard();
            String action = args.length == 0 ? "status" : args[0];
            if ("ready".equals(action)) {
                if (SuperiorSkyblockAPI.getPlayer(player).getIsland() != null)
                    throw new IllegalStateException("Seed requires an empty actual upstream account");
                // Only this fixed private account receives temporary Bukkit OP;
                // no LuckPerms API/group/default policy or production grant is used.
                player.setOp(true); player.setAllowFlight(true); player.setFlying(true); player.setInvulnerable(true);
                player.sendMessage("SUPERIOR_UPSTREAM_SEED_READY");
            } else if ("status".equals(action)) {
                SuperiorPlayer account = SuperiorSkyblockAPI.getPlayer(player);
                Island island = account.getIsland();
                player.sendMessage("SUPERIOR_UPSTREAM_SEED_STATUS count=" + (island == null ? 0 : 1)
                        + " radius=" + (island == null ? 0 : island.getIslandSize())
                        + " pending=" + SuperiorSkyblockAPI.getGrid().hasActiveCreateRequest(account)
                        + " postCreated=" + (island != null && island.getUniqueId().equals(completedCreation)));
            } else if ("record".equals(action)) record(player);
            else throw new IllegalArgumentException("Unknown fixed upstream seed action");
        } catch (Throwable error) { fail(player, error); }
        return true;
    }
    private void record(final Player player) {
        if (recording || Files.exists(root.resolve("upstream-island-checkpoint.properties")))
            throw new IllegalStateException("Immutable upstream checkpoint already exists or is recording");
        final SuperiorPlayer account = SuperiorSkyblockAPI.getPlayer(player);
        final Island island = account.getIsland();
        if (island == null || !island.getOwner().getUniqueId().equals(player.getUniqueId()) || island.getIslandSize() != 100
                || SuperiorSkyblockAPI.getGrid().hasActiveCreateRequest(account)
                || (SuperiorSkyblockAPI.getSettings().isTeleportOnCreate() && !island.getUniqueId().equals(completedCreation)))
            throw new IllegalStateException("A real client must finish creating its upstream radius-100 island first");
        recording = true;
        final Dimension dimension = SuperiorSkyblockAPI.getSettings().getWorlds().getDefaultWorldDimension();
        final Location center = island.getCenter(dimension);
        final World world = center.getWorld();
        if (world == null) throw new IllegalStateException("Actual upstream island world is unavailable");
        List<CompletableFuture<?>> loads = new ArrayList<CompletableFuture<?>>();
        for (int x = (center.getBlockX() - 8) >> 4; x <= (center.getBlockX() + 8) >> 4; x++)
            for (int z = (center.getBlockZ() - 8) >> 4; z <= (center.getBlockZ() + 8) >> 4; z++)
                loads.add(world.getChunkAtAsync(x, z, true));
        CompletableFuture.allOf(loads.toArray(new CompletableFuture<?>[loads.size()])).whenComplete((unused, error) -> {
            if (error != null) { failLater(player, error); return; }
            Bukkit.getScheduler().runTaskLater(this, new Runnable() {
                @Override public void run() {
                    try {
                        guard();
                        if (account.getIsland() == null || !account.getIsland().getUniqueId().equals(island.getUniqueId()))
                            throw new IllegalStateException("Actual upstream ownership changed during observation");
                        final Properties state = snapshot(player, island, dimension);
                        player.setOp(false);
                        if (player.isOp()) throw new IllegalStateException("Synthetic upstream OP was not removed");
                        Bukkit.getScheduler().runTaskAsynchronously(NativeUpstreamSeed.this, new Runnable() {
                            @Override public void run() {
                                try {
                                    guard();
                                    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                                    state.store(bytes, "Immutable synthetic populated upstream island checkpoint v1");
                                    byte[] encoded = bytes.toByteArray();
                                    Path checkpoint = root.resolve("upstream-island-checkpoint.properties");
                                    writeNew(checkpoint, encoded);
                                    writeNew(root.resolve("upstream-island-checkpoint.properties.sha256"),
                                            (hash(encoded) + "\n").getBytes(StandardCharsets.US_ASCII));
                                    getLogger().info("SUPERIOR_UPSTREAM_SEED_PASS uuid=" + state.getProperty("island.uuid")
                                            + " solids=" + state.getProperty("blocks.count") + " sha256=" + hash(encoded));
                                    Bukkit.getScheduler().runTask(NativeUpstreamSeed.this, new Runnable() {
                                        @Override public void run() { player.sendMessage("SUPERIOR_UPSTREAM_SEED_PASS"); }
                                    });
                                } catch (Throwable failure) { failLater(player, failure); }
                            }
                        });
                    } catch (Throwable failure) { fail(player, failure); }
                }
            }, 30L);
        });
    }
    private Properties snapshot(Player player, Island island, Dimension dimension) {
        Properties state = new Properties();
        state.setProperty("format", "1"); state.setProperty("source.plugin.version", "2026.3");
        state.setProperty("source.jar.sha256", ORIGINAL_SHA); state.setProperty("source.minecraft.version", Bukkit.getMinecraftVersion());
        state.setProperty("owner.uuid", player.getUniqueId().toString()); state.setProperty("owner.name", ACCOUNT);
        state.setProperty("island.uuid", island.getUniqueId().toString()); state.setProperty("island.radius", Integer.toString(island.getIslandSize()));
        state.setProperty("island.raw-size", Integer.toString(island.getIslandSizeRaw()));
        Location center = island.getCenter(dimension), home = island.getIslandHome(dimension);
        location(state, "center", center); location(state, "home", home);
        List<SuperiorPlayer> members = new ArrayList<SuperiorPlayer>(island.getIslandMembers(true));
        Collections.sort(members, Comparator.comparing((SuperiorPlayer value) -> value.getUniqueId().toString()));
        state.setProperty("members.count", Integer.toString(members.size()));
        for (int index = 0; index < members.size(); index++) {
            SuperiorPlayer member = members.get(index); String key = "member." + index;
            state.setProperty(key + ".uuid", member.getUniqueId().toString());
            state.setProperty(key + ".role.id", Integer.toString(member.getPlayerRole().getId()));
            state.setProperty(key + ".role.name", member.getPlayerRole().getName());
        }
        int solids = 0;
        for (int x = -8; x <= 8; x++) for (int y = -12; y <= 12; y++) for (int z = -8; z <= 8; z++) {
            Block block = center.getWorld().getBlockAt(center.getBlockX() + x, center.getBlockY() + y, center.getBlockZ() + z);
            Material material = block.getType();
            if (!STABLE_SOLIDS.contains(material.name())) continue;
            if (solids >= 512) throw new IllegalStateException("Unexpectedly large upstream solid sample");
            String key = "block." + solids++;
            state.setProperty(key + ".x", Integer.toString(block.getX())); state.setProperty(key + ".y", Integer.toString(block.getY()));
            state.setProperty(key + ".z", Integer.toString(block.getZ())); state.setProperty(key + ".material", material.name());
            state.setProperty(key + ".data", block.getBlockData().getAsString());
        }
        if (solids < 16) throw new IllegalStateException("Upstream island is not populated with enough actual stable solid blocks");
        state.setProperty("blocks.count", Integer.toString(solids));
        return state;
    }
    private static void location(Properties state, String key, Location location) {
        if (location == null || location.getWorld() == null) throw new IllegalStateException("Upstream location/world is missing");
        state.setProperty(key + ".world.name", location.getWorld().getName()); state.setProperty(key + ".world.uuid", location.getWorld().getUID().toString());
        state.setProperty(key + ".x", Double.toString(location.getX())); state.setProperty(key + ".y", Double.toString(location.getY()));
        state.setProperty(key + ".z", Double.toString(location.getZ())); state.setProperty(key + ".yaw", Float.toString(location.getYaw()));
        state.setProperty(key + ".pitch", Float.toString(location.getPitch()));
    }
    private static void writeNew(Path path, byte[] bytes) throws Exception {
        try (FileChannel channel = FileChannel.open(path, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
            ByteBuffer buffer = ByteBuffer.wrap(bytes); while (buffer.hasRemaining()) channel.write(buffer); channel.force(true);
        }
    }
    private static String fileHash(Path file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream input = Files.newInputStream(file)) {
            byte[] buffer = new byte[65536]; int count; while ((count = input.read(buffer)) >= 0) if (count > 0) digest.update(buffer, 0, count);
        }
        return hex(digest.digest());
    }
    private static String hash(byte[] bytes) throws Exception { return hex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
    private static String hex(byte[] bytes) {
        StringBuilder text = new StringBuilder(); for (byte value : bytes) text.append(String.format("%02x", value & 255)); return text.toString();
    }
    @EventHandler(priority = EventPriority.MONITOR)
    public void created(PostIslandCreateEvent event) {
        if (ACCOUNT.equals(event.getPlayer().getName())) completedCreation = event.getIsland().getUniqueId();
    }
    private void failLater(final Player player, final Throwable error) {
        Bukkit.getScheduler().runTask(this, new Runnable() { @Override public void run() { fail(player, error); } });
    }
    private void fail(Player player, Throwable error) {
        getLogger().log(java.util.logging.Level.SEVERE, "SUPERIOR_UPSTREAM_SEED_FAIL", error);
        player.setOp(false); player.sendMessage("SUPERIOR_UPSTREAM_SEED_FAIL " + error.getClass().getSimpleName());
    }
}

package club.peacefulvanilla.superior.probe;

import com.bgsoftware.superiorskyblock.SuperiorSkyblockPlugin;
import com.bgsoftware.superiorskyblock.api.island.Island;
import com.bgsoftware.superiorskyblock.api.wrappers.SuperiorPlayer;
import net.luckperms.api.LuckPerms;
import net.luckperms.api.LuckPermsProvider;
import net.luckperms.api.node.types.PermissionNode;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Properties;

/** Test-only native observer: excluded from the release and refuses every production path. */
public final class SuperiorProbe extends JavaPlugin {
    private static final String ACCOUNT = "PVCSkyProbe";
    private Path root;
    private SuperiorSkyblockPlugin superior;
    private SuperiorAdvancedProbe advanced;
    private SuperiorDomainProbe domain;
    private SuperiorLegacyProbe legacy;
    private NativeMissionProbe mission;
    private SuperiorPlayerDiagnostics playerDiagnostics;
    private boolean failed;

    @Override public void onEnable() {
        root = getServer().getWorldContainer().toPath().toAbsolutePath().normalize();
        if (root.getParent() == null || !root.getParent().getFileName().toString().equals("local-servers")
                || !root.getFileName().toString().startsWith("superior-runtime-")
                || !Files.isRegularFile(root.resolve(".superior-owned"))
                || getServer().getPort() < 29010 || getServer().getPort() > 29029)
            throw new IllegalStateException("Probe refuses unowned or production fixture");
        superior = (SuperiorSkyblockPlugin) Bukkit.getPluginManager().getPlugin("SuperiorSkyblock2");
        if (superior == null || !superior.isEnabled()) throw new IllegalStateException("Fork is not enabled");
        advanced = new SuperiorAdvancedProbe(this, superior);
        playerDiagnostics = SuperiorPlayerDiagnostics.start(this);
        getLogger().info("SUPERIOR_PROBE_READY");
    }

    @Override public void onDisable() {
        if (playerDiagnostics != null) playerDiagnostics.close();
    }

    @Override public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player) || !player.getName().equals(ACCOUNT)) return true;
        String action = args.length == 0 ? "status" : args[0];
        if (action.startsWith("advanced-")) return advanced.execute(player, action.substring(9));
        if (action.startsWith("domain-")) {
            if (domain == null) domain = new SuperiorDomainProbe(this, superior);
            return domain.execute(player, action.substring(7));
        }
        if (action.startsWith("legacy-")) {
            if (legacy == null) legacy = new SuperiorLegacyProbe(this, superior);
            return legacy.execute(player, action.substring(7));
        }
        if (action.startsWith("mission-")) {
            if (mission == null) mission = new NativeMissionProbe(this, superior);
            return mission.execute(player, action.substring(8));
        }
        try {
            switch (action) {
                case "permissions" -> permissions(player);
                case "limit3" -> grant(player, "superior.island.ownership.limit.3", true);
                case "limit1" -> grant(player, "superior.island.ownership.limit.3", false);
                case "radius80" -> grant(player, "superior.island.size.radius.80", true);
                case "radius160" -> grant(player, "superior.island.size.radius.160", true);
                case "revoke160" -> grant(player, "superior.island.size.radius.160", false);
                case "donor240" -> grant(player, "superior.island.size.radius.240", true);
                case "revokedonor" -> grant(player, "superior.island.size.radius.240", false);
                case "override120" -> superior.executeGlobal(() -> {
                    Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "is admin setsize " + ACCOUNT + " 120");
                    reply(player, "SUPERIOR_ADMIN_REQUESTED");
                }, 0);
                case "clearoverride" -> superior.executeGlobal(() -> {
                    Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "is admin clearsizeoverride " + ACCOUNT);
                    reply(player, "SUPERIOR_ADMIN_REQUESTED");
                }, 0);
                case "status" -> status(player);
                case "physical" -> {
                    if (args.length != 2) throw new IllegalArgumentException("Fixed owned short ID required");
                    long expected = Long.parseLong(args[1]);
                    Island actual = superior.getGrid().getIslandAt(player.getLocation());
                    if (actual == null || actual.getShortId() != expected || !owned(player).contains(actual))
                        reply(player, "SUPERIOR_PHYSICAL_HOME_WAIT expected=" + expected + " actual=" +
                                (actual == null ? "none" : actual.getShortId()) + " position=" + player.getLocation());
                    else reply(player, "SUPERIOR_PHYSICAL_HOME_PASS id=" + expected);
                }
                case "finish" -> finish(player, false);
                case "restartfinish" -> finish(player, true);
                default -> throw new IllegalArgumentException("Unknown fixed test case");
            }
        } catch (Throwable error) { fail(player, error); }
        return true;
    }

    private void permissions(Player player) {
        player.setAllowFlight(true);
        player.setFlying(true);
        player.setInvulnerable(true);
        LuckPerms api = LuckPermsProvider.get();
        api.getUserManager().loadUser(player.getUniqueId()).thenCompose(user -> {
            for (String suffix : List.of("create", "teleport", "list", "select", "name", "setteleport", "team", "disband", "open"))
                user.data().add(PermissionNode.builder("superior.island." + suffix).build());
            user.data().add(PermissionNode.builder("superior.island").build());
            user.data().add(PermissionNode.builder("superior.island.schematic.normal").build());
            return api.getUserManager().saveUser(user);
        }).whenComplete((unused, error) -> {
            if (error != null) fail(player, error); else reply(player, "SUPERIOR_PERMISSIONS_READY");
        });
    }

    private void grant(Player player, String node, boolean enabled) {
        LuckPerms api = LuckPermsProvider.get();
        api.getUserManager().loadUser(player.getUniqueId()).thenCompose(user -> {
            if (enabled) user.data().add(PermissionNode.builder(node).build());
            else user.data().remove(PermissionNode.builder(node).build());
            return api.getUserManager().saveUser(user);
        }).whenComplete((unused, error) -> {
            if (error != null) fail(player, error); else reply(player, "SUPERIOR_POLICY_SAVED " + node + "=" + enabled);
        });
    }

    private List<Island> owned(Player player) {
        return superior.getGrid().getOwnedIslands(player.getUniqueId()).stream()
                .sorted(Comparator.comparingLong(Island::getShortId)).toList();
    }
    private void status(Player player) {
        SuperiorPlayer account = superior.getPlayers().getSuperiorPlayer(player);
        List<Island> islands = owned(player);
        String ids = islands.stream().map(island -> Long.toString(island.getShortId())).reduce((a, b) -> a + "," + b).orElse("none");
        String radii = islands.stream().map(island -> Integer.toString(island.getIslandSize())).reduce((a, b) -> a + "," + b).orElse("none");
        Island selected = account.getSelectedIsland();
        reply(player, "SUPERIOR_STATUS count=" + islands.size() + " quota=" + superior.getPvcEntitlements().getOwnershipLimit(player.getUniqueId())
                + " ids=" + ids + " radii=" + radii + " selected=" + (selected == null ? 0 : selected.getShortId())
                + " available=" + islands.stream().allMatch(island -> superior.getPvcIslandService().isAvailable(island.getUniqueId())));
    }

    private void finish(Player player, boolean restart) throws Exception {
        if (failed) throw new IllegalStateException("Earlier native failure");
        List<Island> islands = owned(player);
        if (islands.size() != 3 || islands.stream().anyMatch(island -> island.getIslandSize() != 120 || island.getShortId() <= 0 ||
                !superior.getPvcIslandService().isAvailable(island.getUniqueId())) )
            throw new IllegalStateException("Expected three durable islands, each radius 120");
        SuperiorPlayer account = superior.getPlayers().getSuperiorPlayer(player);
        Properties state = new Properties();
        Path saved = root.resolve("probe-state.properties");
        for (int i = 0; i < islands.size(); i++) state.setProperty("island." + i, islands.get(i).getUniqueId() + ":" + islands.get(i).getShortId());
        state.setProperty("selected", Long.toString(account.getSelectedIsland().getShortId()));
        java.util.List<java.util.concurrent.CompletableFuture<Void>> blocks = new java.util.ArrayList<>();
        for (Island island : islands) {
            var center = island.getCenter(superior.getSettings().getWorlds().getDefaultWorldDimension());
            var checked = new java.util.concurrent.CompletableFuture<Void>(); blocks.add(checked);
            superior.executeRegion(center, () -> {
                try {
                    int solids = 0;
                    for (int x = -3; x <= 3; ++x) for (int z = -3; z <= 3; ++z) for (int y = -3; y <= 1; ++y)
                        if (center.getWorld().getBlockAt(center.getBlockX() + x, center.getBlockY() + y,
                                center.getBlockZ() + z).getType().isSolid()) ++solids;
                    if (solids == 0) throw new IllegalStateException("Published Classic island contains no actual solid blocks: " + island.getShortId());
                    checked.complete(null);
                } catch (Throwable error) { checked.completeExceptionally(error); }
            }, 0);
        }
        java.util.concurrent.CompletableFuture.allOf(blocks.toArray(new java.util.concurrent.CompletableFuture[0]))
                .whenComplete((unused, worldError) -> {
            if (worldError != null) { fail(player, worldError); return; }
            superior.executeAsync(() -> {
            try {
                if (restart) {
                    Properties before = new Properties();
                    try (var in = Files.newInputStream(saved)) { before.load(in); }
                    if (!state.equals(before)) throw new IllegalStateException("IDs or selected island changed after restart");
                } else try (var out = Files.newOutputStream(saved)) { state.store(out, "Synthetic isolated state"); }
                reply(player, restart ? "SUPERIOR_RESTART_PASS" : "SUPERIOR_PROBE_PASS");
                getLogger().info(restart ? "SUPERIOR_RESTART_PASS" : "SUPERIOR_PROBE_PASS");
            } catch (Throwable error) { fail(player, error); }
            }, 0);
        });
    }
    private void reply(Player player, String message) {
        superior.executePlayer(superior.getPlayers().getSuperiorPlayer(player), () -> player.sendMessage(message), 0);
    }
    private void fail(Player player, Throwable error) {
        failed = true;
        getLogger().log(java.util.logging.Level.SEVERE, "SUPERIOR_PROBE_FAIL", error);
        if (superior != null) reply(player, "SUPERIOR_PROBE_FAIL " + error.getClass().getSimpleName());
    }
}

package club.peacefulvanilla.superior.probe;

import com.bgsoftware.superiorskyblock.SuperiorSkyblockPlugin;
import com.bgsoftware.superiorskyblock.api.commands.SuperiorCommand;
import com.bgsoftware.superiorskyblock.api.events.IslandChatEvent;
import com.bgsoftware.superiorskyblock.api.events.PlayerChangeChatStateEvent;
import com.bgsoftware.superiorskyblock.api.events.IslandRateEvent;
import com.bgsoftware.superiorskyblock.api.events.IslandRemoveRatingEvent;
import com.bgsoftware.superiorskyblock.api.enums.Rating;
import com.bgsoftware.superiorskyblock.api.island.Island;
import com.bgsoftware.superiorskyblock.api.island.IslandExecutionScope;
import com.bgsoftware.superiorskyblock.api.missions.Mission;
import com.bgsoftware.superiorskyblock.api.upgrades.Upgrade;
import com.bgsoftware.superiorskyblock.api.wrappers.SuperiorPlayer;
import com.bgsoftware.superiorskyblock.core.database.sql.SQLDatabaseBridge;
import com.bgsoftware.superiorskyblock.core.threads.BukkitExecutor;
import com.bgsoftware.superiorskyblock.core.menu.Menus;
import com.bgsoftware.superiorskyblock.core.menu.impl.MenuConfirmKick;
import com.bgsoftware.superiorskyblock.core.menu.impl.MenuConfirmBan;
import com.bgsoftware.superiorskyblock.core.menu.view.AbstractMenuView;
import com.bgsoftware.superiorskyblock.core.menu.view.IIslandMenuView;
import com.bgsoftware.superiorskyblock.api.menu.MenuCommands;
import com.bgsoftware.superiorskyblock.api.menu.button.click.ButtonClickContext;
import com.bgsoftware.superiorskyblock.api.menu.button.MenuTemplateButton;
import com.bgsoftware.superiorskyblock.api.menu.button.PagedMenuTemplateButton;
import com.bgsoftware.superiorskyblock.api.menu.view.PagedMenuView;
import com.bgsoftware.superiorskyblock.api.menu.layout.PagedMenuLayout;
import com.bgsoftware.superiorskyblock.api.menu.view.MenuView;
import com.bgsoftware.superiorskyblock.api.island.PlayerRole;
import com.bgsoftware.superiorskyblock.core.key.Keys;
import com.bgsoftware.superiorskyblock.commands.player.CmdTeam;
import com.bgsoftware.superiorskyblock.island.privilege.IslandPrivileges;
import com.bgsoftware.superiorskyblock.module.upgrades.commands.CmdAdminSetUpgrade;
import com.bgsoftware.superiorskyblock.player.chat.ChatStates;
import net.luckperms.api.LuckPermsProvider;
import net.luckperms.api.node.types.PermissionNode;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.command.CommandSender;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/** Reads real command effects from two private protocol clients; never packaged in the release. */
public final class NativeCommandProbe implements Listener {
    private static final String PRIMARY = "PVCSkyProbe", AUXILIARY = "PVCSkyProbeB";
    private static final String UPGRADE = "pvc-native", MISSION = "pvc_command_items";
    private final JavaPlugin observer;
    private final SuperiorSkyblockPlugin superior;
    private final Path root;
    private final Set<String> capturedActors = ConcurrentHashMap.newKeySet();
    private volatile UUID chatAuthor, chatIsland, toggleAuthor;
    private volatile String chatMessage;
    private volatile boolean failed, chatChecked, ratingsChecked, warpChecked, upgradeChecked,
            missionChecked, demoteChecked, staleDemoteChecked, kickChecked, staleKickChecked, sizeChecked, payloadChecked,
            zeroOwnedSizeChecked, bulkDisbandsChecked;
    private int demoteRoleA, demoteRoleB, changedDemoteRole;
    private int primaryDisbands, auxiliaryDisbands;
    private volatile UUID adminRatingAuthor, removedRatingAuthor, consoleTeamIsland;
    private volatile boolean recheckChecked;
    private PlayerRole previousBanRole;

    public NativeCommandProbe(JavaPlugin observer, SuperiorSkyblockPlugin superior) {
        this.observer = observer; this.superior = superior;
        root = observer.getServer().getWorldContainer().toPath().toAbsolutePath().normalize();
        guard(); Bukkit.getPluginManager().registerEvents(this, observer);
        SuperiorCommand original = superior.getCommands().getAdminCommand("setupgrade");
        if (!(original instanceof CmdAdminSetUpgrade)) throw new IllegalStateException("The original setupgrade handler is required");
        superior.getCommands().registerAdminCommand(new SetupgradeTrace(original));
        SuperiorCommand originalTeam = superior.getCommands().getCommand("team");
        if (!(originalTeam instanceof CmdTeam)) throw new IllegalStateException("Original team handler required");
        superior.getCommands().registerCommand(new TeamTrace(originalTeam));
    }

    private void guard() {
        if (root.getParent() == null || !root.getParent().getFileName().toString().equals("local-servers") ||
                !root.getFileName().toString().startsWith("superior-runtime-") ||
                !Files.isRegularFile(root.resolve(".superior-owned")) ||
                !Files.isRegularFile(root.resolve(".superior-commands-owned")) ||
                observer.getServer().getPort() < 29010 || observer.getServer().getPort() > 29029 ||
                !"SQLITE".equalsIgnoreCase(superior.getSettings().getDatabase().getType()) ||
                superior.getSettings().getMaxIslandSize() > 128 ||
                superior.getSettings().getDefaultValues().getIslandSize() < 24)
            throw new IllegalStateException("Command observer requires a fresh owned small-grid fixture");
        if (superior.getUpgrades().getUpgrade(UPGRADE) == null || superior.getMissions().getMission(MISSION) == null)
            throw new IllegalStateException("The fixed native command upgrade and island mission must be enabled");
    }

    public boolean execute(Player actor, String action) {
        if (!PRIMARY.equals(actor.getName())) return true;
        try {
            guard();
            switch (action) {
                case "prepare" -> prepare(actor);
                case "limit3" -> quota(actor, true);
                case "limit1" -> quota(actor, false);
                case "auxlimit3" -> auxiliary().whenComplete((other, error) -> {
                    if (error != null) fail(actor, error); else quota(actor, other, true);
                });
                case "status" -> status(actor);
                case "tab-ready" -> tabReady(actor);
                case "chatcheck" -> chatcheck(actor);
                case "warpcheck" -> warpcheck(actor);
                case "ratingscheck" -> ratingscheck(actor);
                case "upgradecheck" -> upgradecheck(actor);
                case "items" -> items(actor);
                case "missioncheck" -> missioncheck(actor);
                case "zero-sizecheck" -> zeroSizecheck(actor);
                case "disbands-snapshot" -> disbandsSnapshot(actor);
                case "disbandscheck" -> disbandscheck(actor);
                case "demote-snapshot" -> snapshotDemote(actor, false);
                case "stale-demote-snapshot" -> snapshotDemote(actor, true);
                case "approve-demote" -> approve(actor, "demote");
                case "demotecheck" -> demotecheck(actor);
                case "stale-demotecheck" -> staleDemotecheck(actor);
                case "approve-kick" -> approve(actor, "kick");
                case "kickcheck" -> kickcheck(actor);
                case "stale-kickcheck" -> staleKickcheck(actor);
                case "approve-setsize" -> approve(actor, "setsize");
                case "approve-addsize" -> approve(actor, "addsize");
                case "approve-removeratings" -> approve(actor, "removeratings");
                case "approve-adddisbands" -> approve(actor, "adddisbands");
                case "approve-setgenerator" -> approve(actor, "setgenerator");
                case "approve-addgenerator" -> approve(actor, "addgenerator");
                case "sizecheck" -> sizecheck(actor);
                case "payloadcheck" -> payloadcheck(actor);
                case "tail-prepare" -> tailPrepare(actor);
                case "tail-status" -> tailStatus(actor);
                case "tail-inspect" -> tailInspect(actor);
                case "tail-console" -> tailConsole(actor);
                case "tail-expel" -> tailExpel(actor);
                case "tail-menuhook" -> tailMenuHook(actor);
                case "tail-openkick" -> tailOpenKick(actor);
                case "tail-openban" -> tailOpenBan(actor);
                case "tail-guicheck" -> tailGuiCheck(actor);
                case "tail-globalcheck" -> tailGlobalCheck(actor);
                case "tail-globalhomecheck" -> tailGlobalHomeCheck(actor);
                case "tail-list-slot" -> tailListSlot(actor);
                case "tail-eventclear" -> tailEventClear(actor);
                case "tail-eventcheck" -> tailEventCheck(actor);
                case "tail-finish" -> tailFinish(actor);
                case "shared-state" -> NativeSharedStateProbe.run(observer, superior, actor).whenComplete((unused, error) -> {
                    if (error != null) fail(actor, error); else reply(actor, "SUPERIOR_SHARED_STATE_PASS actors=2 loadTest=false");
                });
                case "finish" -> checkpoint(actor, false);
                case "restartfinish" -> checkpoint(actor, true);
                default -> throw new IllegalArgumentException("Unknown fixed native command case");
            }
        } catch (Throwable error) { fail(actor, error); }
        return true;
    }

    /** Only the fixed cmdall payload is accepted; this is not an arbitrary console runner. */
    public boolean handleConsole(CommandSender sender, String[] arguments) {
        if (!(sender instanceof ConsoleCommandSender) || arguments.length == 0 || !"command-capture".equals(arguments[0])) return false;
        guard();
        if (arguments.length != 4 || !List.of(PRIMARY, AUXILIARY).contains(arguments[1]) ||
                !"--island".equals(arguments[2]) || !"id:0".equals(arguments[3]))
            throw new IllegalStateException("The literal --island payload was stripped or changed");
        capturedActors.add(arguments[1]);
        observer.getLogger().info("SUPERIOR_COMMAND_LITERAL_CAPTURE actor=" + arguments[1] + " selector=literal-id:0");
        return true;
    }

    private SuperiorPlayer account(String name) {
        SuperiorPlayer value = superior.getPlayers().getSuperiorPlayer(name);
        if (value == null) throw new IllegalStateException("Fixed native account missing: " + name);
        return value;
    }
    private List<Island> owned() {
        return superior.getGrid().getOwnedIslands(account(PRIMARY).getUniqueId()).stream()
                .sorted(Comparator.comparingLong(Island::getShortId)).toList();
    }
    private Island first() { List<Island> islands = owned(); if (islands.size() != 2) throw new IllegalStateException("Exactly two native owned islands required"); return islands.get(0); }
    private Island second() { return owned().get(1); }
    private Upgrade upgrade() { return superior.getUpgrades().getUpgrade(UPGRADE); }
    private Mission<?> mission() { return superior.getMissions().getMission(MISSION); }
    private CompletableFuture<Player> auxiliary() {
        CompletableFuture<Player> result = new CompletableFuture<>();
        BukkitExecutor.global(() -> {
            Player other = Bukkit.getPlayerExact(AUXILIARY);
            if (other == null) result.completeExceptionally(new IllegalStateException("Connect the real auxiliary native client"));
            else result.complete(other);
        });
        return result;
    }
    private <T> CompletableFuture<T> onPlayer(Player player, Supplier<T> action) {
        CompletableFuture<T> result = new CompletableFuture<>();
        BukkitExecutor.entity(player, () -> {
            try { result.complete(action.get()); } catch (Throwable error) { result.completeExceptionally(error); }
        }, 0L, () -> result.completeExceptionally(new IllegalStateException("Actual native command client retired")));
        return result;
    }

    private void prepare(Player actor) {
        if (!owned().isEmpty() || Files.exists(root.resolve("command-probe-state.properties")))
            throw new IllegalStateException("Initial command fixture must contain no owned islands or old checkpoint");
        auxiliary().thenCompose(other -> CompletableFuture.allOf(permissions(actor), permissions(other),
                onPlayer(actor, () -> { actor.setInvulnerable(true); actor.setAllowFlight(true); actor.setFlying(true); return null; }),
                onPlayer(other, () -> { other.setInvulnerable(true); other.setAllowFlight(true); other.setFlying(true); return null; })))
                .whenComplete((unused, error) -> {
                    if (error != null) fail(actor, error); else reply(actor, "SUPERIOR_COMMAND_PREPARED accounts=2 quota=1");
                });
    }
    private CompletableFuture<Void> permissions(Player player) {
        var api = LuckPermsProvider.get();
        return api.getUserManager().loadUser(player.getUniqueId()).thenCompose(user -> {
            user.data().add(PermissionNode.builder("superior.island").build());
            user.data().add(PermissionNode.builder("superior.island.schematic.normal").build());
            for (String suffix : List.of("create", "teleport", "help", "name", "rankup", "mission", "localchat",
                    "warp", "setwarp", "rate", "ratings", "promote", "demote", "kick", "setrole",
                    "counts", "values", "team", "lang", "setdiscord", "delwarp", "visit", "warps", "top"))
                user.data().add(PermissionNode.builder(requiredPermission(false, suffix)).build());
            user.data().add(PermissionNode.builder("superior.admin").build());
            for (String suffix : List.of("join", "removeratings", "rankup", "msgall", "cmdall", "demote", "kick", "setsize", "addsize", "clearsizeoverride", "givedisbands",
                    "setbonus", "setgenerator", "addgenerator", "delwarp", "setrate"))
                user.data().add(PermissionNode.builder(requiredPermission(true, suffix)).build());
            return api.getUserManager().saveUser(user);
        });
    }
    private String requiredPermission(boolean administrative, String alias) {
        SuperiorCommand command = administrative ? superior.getCommands().getAdminCommand(alias) : superior.getCommands().getCommand(alias);
        if (command == null || command.getPermission() == null || command.getPermission().isEmpty())
            throw new IllegalStateException("Required fixture command or permission is missing: " + (administrative ? "admin " : "") + alias);
        return command.getPermission();
    }
    private void quota(Player actor, boolean expanded) {
        quota(actor, actor, expanded);
    }
    private void quota(Player actor, Player subject, boolean expanded) {
        var api = LuckPermsProvider.get();
        api.getUserManager().loadUser(subject.getUniqueId()).thenCompose(user -> {
            var node = PermissionNode.builder("superior.island.ownership.limit.3").build();
            if (expanded) user.data().add(node); else user.data().remove(node);
            return api.getUserManager().saveUser(user);
        }).thenCompose(unused -> superior.getPvcEntitlements().refreshOwnershipLimit(subject.getUniqueId()))
                .whenComplete((limit, error) -> {
                    if (error != null) fail(actor, error);
                    else if (limit != (expanded ? 3 : 1)) fail(actor, new IllegalStateException("Actual owner quota did not converge"));
                    else reply(actor, (actor == subject ? "SUPERIOR_COMMAND_QUOTA quota=" : "SUPERIOR_COMMAND_AUX_QUOTA quota=") + limit);
                });
    }

    /** Observe the positive TAB precondition without relaxing the plugin's expired-policy fence. */
    private void tabReady(Player actor) {
        superior.getPvcEntitlements().refreshOwnershipLimit(actor.getUniqueId()).thenCompose(refreshedLimit ->
                onPlayer(actor, () -> {
                    int limit = superior.getPvcEntitlements().getVerifiedOwnershipLimit(actor.getUniqueId());
                    var snapshot = superior.getPvcEntitlements().getSnapshot(actor.getUniqueId());
                    long now = System.currentTimeMillis();
                    long ttl = superior.getPvcConfig().getRefreshMillis();
                    int memberships = account(PRIMARY).getIslands().size();
                    if (refreshedLimit <= 1 || limit <= 1 || snapshot.getOwnershipLimit() != limit ||
                            !snapshot.isFresh(now, ttl) || memberships <= 1)
                        throw new IllegalStateException("Positive native TAB requires a fresh expanded quota and multiple actual memberships");
                    actor.sendMessage("SUPERIOR_COMMAND_TAB_READY verifiedAt=" + snapshot.getVerifiedAtMillis() +
                            " currentTime=" + now + " quota=" + limit + " memberships=" + memberships + " ttl=" + ttl);
                    return null;
                })).whenComplete((unused, error) -> {
                    if (error != null) fail(actor, error);
                });
    }

    private void status(Player actor) {
        auxiliary().thenCompose(other -> onPlayer(other, () -> {
            Island standing = superior.getGrid().getIslandAt(other.getLocation());
            return standing == null ? 0L : standing.getShortId();
        })).whenComplete((physical, error) -> {
            if (error != null) { fail(actor, error); return; }
            List<Island> islands = owned();
            Island a = islands.isEmpty() ? null : islands.get(0), b = islands.size() < 2 ? null : islands.get(1);
            SuperiorPlayer owner = account(PRIMARY), member = account(AUXILIARY);
            String ids = islands.stream().map(island -> Long.toString(island.getShortId())).reduce((one, two) -> one + "," + two).orElse("none");
            reply(actor, "SUPERIOR_COMMAND_STATUS count=" + islands.size() + " quota=" + superior.getPvcEntitlements().getOwnershipLimit(owner.getUniqueId()) +
                    " ids=" + ids + " selected=" + (owner.getSelectedIsland() == null ? 0 : owner.getSelectedIsland().getShortId()) +
                    " available=" + islands.stream().allMatch(island -> superior.getPvcIslandService().isAvailable(island.getUniqueId())) +
                    " auxPhysical=" + physical + " auxOwned=" + member.getOwnedIslands().size() + " memberA=" + (a != null && a.isMember(member)) + " memberB=" + (b != null && b.isMember(member)) +
                    " roleA=" + (a == null ? -1 : member.getPlayerRole(a).getWeight()) + " roleB=" + (b == null ? -1 : member.getPlayerRole(b).getWeight()) +
                    " ratingsA=" + (a == null ? 0 : a.getRatingAmount()) + " ratingsB=" + (b == null ? 0 : b.getRatingAmount()) +
                    " levelA=" + (a == null ? 0 : a.getUpgradeLevel(upgrade()).getLevel()) + " levelB=" + (b == null ? 0 : b.getUpgradeLevel(upgrade()).getLevel()) +
                    " missionA=" + (a != null && a.hasCompletedMission(mission())) + " missionB=" + (b != null && b.hasCompletedMission(mission())) +
                    " sizeA=" + (a == null ? 0 : a.getIslandSize()) + " sizeB=" + (b == null ? 0 : b.getIslandSize()) +
                    " ownerOverride=" + (superior.getPvcEntitlements().getOwnerOverride(owner.getUniqueId()) == null ? -1 :
                            superior.getPvcEntitlements().getOwnerOverride(owner.getUniqueId())) +
                    " disbandsPrimary=" + owner.getDisbands() + " disbandsAuxiliary=" + member.getDisbands() +
                    " payloadActors=" + capturedActors.size());
        });
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void chat(IslandChatEvent event) {
        if (event.getMessage().equals("PVC_NATIVE_MEMBER_CHAT")) {
            chatAuthor = event.getPlayer().getUniqueId(); chatIsland = event.getIsland().getUniqueId(); chatMessage = event.getMessage();
        }
    }
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void toggle(PlayerChangeChatStateEvent event) {
        if (AUXILIARY.equals(event.getPlayer().getName()) && event.getNewChatState() == ChatStates.LOCAL_CHAT)
            toggleAuthor = event.getPlayer().getUniqueId();
    }
    private void chatcheck(Player actor) {
        if (!account(AUXILIARY).getUniqueId().equals(chatAuthor) || !first().getUniqueId().equals(chatIsland) ||
                !"PVC_NATIVE_MEMBER_CHAT".equals(chatMessage) || !account(AUXILIARY).getUniqueId().equals(toggleAuthor) ||
                account(PRIMARY).getChatState() != ChatStates.GLOBAL || account(AUXILIARY).getChatState() != ChatStates.LOCAL_CHAT)
            throw new IllegalStateException("Localchat attributed the member's command or toggle to its island owner");
        chatChecked = true; reply(actor, "SUPERIOR_COMMAND_LOCALCHAT_PASS author=actual-member owner=unchanged");
    }
    private void warpcheck(Player actor) {
        auxiliary().thenCompose(other -> onPlayer(other, () -> {
            if (first().isMember(account(AUXILIARY)) || !first().isInside(other.getLocation()) || first().getWarp("pvc-public") == null ||
                    first().getWarp("pvc-public").hasPrivateFlag()) throw new IllegalStateException("Actual external client did not reach the public warp");
            return null;
        })).whenComplete((unused, error) -> {
            if (error != null) fail(actor, error); else { warpChecked = true; reply(actor, "SUPERIOR_COMMAND_PUBLIC_WARP_PASS visitor=actual-external"); }
        });
    }
    private void ratingscheck(Player actor) {
        if (first().getRatingAmount() != 0 || second().getRatingAmount() != 1 || !second().getRatings().containsKey(account(AUXILIARY).getUniqueId()))
            throw new IllegalStateException("Explicit rating clear touched another island or author clear failed");
        ratingsChecked = true; reply(actor, "SUPERIOR_COMMAND_RATINGS_PASS first=0 second=1");
    }
    private void upgradecheck(Player actor) {
        if (first().getUpgradeLevel(upgrade()).getLevel() != 2 || second().getUpgradeLevel(upgrade()).getLevel() != 2)
            throw new IllegalStateException("Configured player/admin upgrade commands did not advance their actual targets");
        upgradeChecked = true; reply(actor, "SUPERIOR_COMMAND_UPGRADE_PASS first=2 second=2");
    }
    private void items(Player actor) {
        if (!upgradeChecked) throw new IllegalStateException("Qualify normal configured upgrades before the real mission reward");
        onPlayer(actor, () -> { actor.getInventory().addItem(new ItemStack(Material.STONE, 2)); return null; })
                .whenComplete((unused, error) -> {
                    if (error != null) fail(actor, error); else reply(actor, "SUPERIOR_COMMAND_ITEMS_READY amount=2");
                });
    }
    private void missioncheck(Player actor) {
        if (!first().hasCompletedMission(mission()) || second().hasCompletedMission(mission()) ||
                first().getUpgradeLevel(upgrade()).getLevel() != 3 || second().getUpgradeLevel(upgrade()).getLevel() != 2)
            throw new IllegalStateException("Actual completed island mission lost its target across configured reward scheduling");
        missionChecked = true; reply(actor, "SUPERIOR_COMMAND_MISSION_REWARD_PASS first=3 second=2");
    }
    private void zeroSizecheck(Player actor) {
        if (!owned().isEmpty() || !Integer.valueOf(32).equals(superior.getPvcEntitlements().getOwnerOverride(account(PRIMARY).getUniqueId())))
            throw new IllegalStateException("Zero-island nickname size approval did not persist its owner-wide override");
        zeroOwnedSizeChecked = true; reply(actor, "SUPERIOR_COMMAND_ZERO_OWNED_SIZE_PASS override=32 owned=0");
    }
    private void disbandsSnapshot(Player actor) {
        primaryDisbands = account(PRIMARY).getDisbands(); auxiliaryDisbands = account(AUXILIARY).getDisbands();
        reply(actor, "SUPERIOR_COMMAND_DISBANDS_SNAPSHOT primary=" + primaryDisbands + " auxiliary=" + auxiliaryDisbands);
    }
    private void disbandscheck(Player actor) {
        if (account(PRIMARY).getDisbands() != primaryDisbands + 3 || account(AUXILIARY).getDisbands() != auxiliaryDisbands + 3)
            throw new IllegalStateException("Dangerous player wildcard applied the bulk mutation more than once or lost a frozen account");
        bulkDisbandsChecked = true; reply(actor, "SUPERIOR_COMMAND_BULK_DISBANDS_PASS increment=3 each=once");
    }
    private void snapshotDemote(Player actor, boolean stale) {
        SuperiorPlayer member = account(AUXILIARY);
        if (!first().isMember(member) || !second().isMember(member)) throw new IllegalStateException("Real member must join both islands");
        if (stale) changedDemoteRole = member.getPlayerRole(second()).getWeight();
        else { demoteRoleA = member.getPlayerRole(first()).getWeight(); demoteRoleB = member.getPlayerRole(second()).getWeight(); }
        reply(actor, stale ? "SUPERIOR_COMMAND_STALE_DEMOTE_SNAPSHOT" : "SUPERIOR_COMMAND_DEMOTE_SNAPSHOT");
    }
    private void approve(Player actor, String command) {
        if (!List.of("demote", "kick", "setsize", "addsize", "removeratings", "adddisbands", "setgenerator", "addgenerator").contains(command))
            throw new IllegalArgumentException("Unexpected approval");
        var registered = superior.getCommands().getAdminCommand(command);
        if (registered == null || !superior.getCommands().hasPendingDangerousRequests(registered))
            throw new IllegalStateException("Submit a real dangerous command before console approval");
        BukkitExecutor.global(() -> {
            try {
                if (!Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "is admin " + command))
                    throw new IllegalStateException("Native console approval was not handled");
                reply(actor, "SUPERIOR_COMMAND_APPROVAL_DISPATCHED command=" + command);
            } catch (Throwable error) { fail(actor, error); }
        });
    }
    private void demotecheck(Player actor) {
        var member = account(AUXILIARY);
        if (member.getPlayerRole(first()).getWeight() >= demoteRoleA || member.getPlayerRole(second()).getWeight() != demoteRoleB)
            throw new IllegalStateException("Dangerous demote followed the actor's later selection");
        demoteChecked = true; reply(actor, "SUPERIOR_COMMAND_DANGEROUS_DEMOTE_PASS");
    }
    private void staleDemotecheck(Player actor) {
        if (account(AUXILIARY).getPlayerRole(second()).getWeight() != changedDemoteRole + 1)
            throw new IllegalStateException("Old demote request mutated the subject after a real role change");
        staleDemoteChecked = true; reply(actor, "SUPERIOR_COMMAND_STALE_DEMOTE_PASS");
    }
    private void kickcheck(Player actor) {
        if (first().isMember(account(AUXILIARY)) || !second().isMember(account(AUXILIARY)))
            throw new IllegalStateException("Dangerous kick did not affect only its frozen member target");
        kickChecked = true; reply(actor, "SUPERIOR_COMMAND_DANGEROUS_KICK_PASS");
    }
    private void staleKickcheck(Player actor) {
        if (!first().isMember(account(AUXILIARY)) || !second().isMember(account(AUXILIARY)))
            throw new IllegalStateException("Old kick request affected a removed and rejoined membership");
        staleKickChecked = true; reply(actor, "SUPERIOR_COMMAND_STALE_KICK_PASS");
    }
    private void sizecheck(Player actor) {
        if (first().getIslandSize() != 52 || second().getIslandSize() != 52)
            throw new IllegalStateException("Owner-wide size approval failed or changed the later selected owner");
        sizeChecked = true; reply(actor, "SUPERIOR_COMMAND_DANGEROUS_SIZE_PASS first=52 second=52");
    }
    private void payloadcheck(Player actor) {
        if (!capturedActors.equals(Set.of(PRIMARY, AUXILIARY))) throw new IllegalStateException("Cmdall lost a member or literal selector payload");
        payloadChecked = true; reply(actor, "SUPERIOR_COMMAND_LITERAL_PAYLOAD_PASS actors=2");
    }

    private Island third() {
        List<Island> islands = account(AUXILIARY).getOwnedIslands();
        if (islands.size() != 1) throw new IllegalStateException("The functional recheck requires exactly one auxiliary-owned small island");
        return islands.get(0);
    }
    private void tailPrepare(Player actor) {
        var dimension = superior.getSettings().getWorlds().getDefaultWorldDimension();
        for (Island island : List.of(first(), second(), third())) {
            island.setBonusWorth(java.math.BigDecimal.ZERO);
            // An exact denominator makes 25% and 35% representable without rounding ambiguity.
            island.removeGeneratorAmount(Keys.ofMaterialAndData("STONE"), dimension);
            island.setGeneratorAmount(Keys.ofMaterialAndData("COBBLESTONE"), 195, dimension);
        }
        first().setVisitorsLocation(actor.getLocation());
        first().createWarp("admin --island id:" + second().getShortId(), actor.getLocation(), null);
        first().createWarp("user --island id:" + second().getShortId(), actor.getLocation(), null);
        second().createWarp("admin", actor.getLocation(), null);
        second().createWarp("user", actor.getLocation(), null);
        adminRatingAuthor = null; removedRatingAuthor = null; consoleTeamIsland = null;
        reply(actor, "SUPERIOR_COMMAND_RECHECK_PREPARED third=" + third().getShortId());
    }
    private void tailStatus(Player actor) {
        List<Island> all = List.of(first(), second(), third());
        var dimension = superior.getSettings().getWorlds().getDefaultWorldDimension();
        reply(actor, "SUPERIOR_COMMAND_RECHECK_STATUS third=" + third().getShortId() +
                " bonusA=" + first().getBonusWorth() + " bonusB=" + second().getBonusWorth() + " bonusC=" + third().getBonusWorth() +
                " discordA=" + java.util.Base64.getEncoder().encodeToString(java.util.Objects.toString(first().getDiscord(), "").getBytes(java.nio.charset.StandardCharsets.UTF_8)) +
                " adminWarpGone=" + (first().getWarp("admin --island id:" + second().getShortId()) == null) +
                " userWarpGone=" + (first().getWarp("user --island id:" + second().getShortId()) == null) +
                " baseWarpsPreserved=" + (second().getWarp("admin") != null && second().getWarp("user") != null) +
                " generators=" + all.stream().map(island -> String.valueOf(island.getGeneratorPercentage(Keys.ofMaterialAndData("STONE"), dimension))).collect(java.util.stream.Collectors.joining(",")));
    }
    private void tailInspect(Player actor) {
        MenuView<?, ?> view = account(PRIMARY).getOpenedView();
        if (!(view instanceof IIslandMenuView actual) || actual.getIsland() != third())
            throw new IllegalStateException("The public selector did not open the external island's real menu");
        reply(actor, "SUPERIOR_COMMAND_RECHECK_PUBLIC_GUI_PASS target=external-third");
    }
    private void tailConsole(Player actor) {
        BukkitExecutor.global(() -> {
            try {
                superior.getCommands().dispatchSubCommand(Bukkit.getConsoleSender(), "team", "--island id:" + third().getShortId());
                superior.getCommands().dispatchSubCommand(Bukkit.getConsoleSender(), "expel", AUXILIARY + " --island id:" + first().getShortId());
                reply(actor, "SUPERIOR_COMMAND_RECHECK_CONSOLE_SENT target=explicit");
            } catch (Throwable error) { fail(actor, error); }
        });
    }
    private void tailExpel(Player actor) {
        BukkitExecutor.global(() -> {
            try {
                superior.getCommands().dispatchSubCommand(Bukkit.getConsoleSender(), "expel", AUXILIARY + " --island id:" + third().getShortId());
                reply(actor, "SUPERIOR_COMMAND_RECHECK_EXPEL_SENT target=matching");
            } catch (Throwable error) { fail(actor, error); }
        });
    }
    private void tailMenuHook(Player actor) {
        MenuView<?, ?> view = account(PRIMARY).getOpenedView();
        if (!(view instanceof IIslandMenuView target) || target.getIsland() != first())
            throw new IllegalStateException("An actual counts GUI of the first island must be open");
        MenuCommands.getInstance().runCommand(view, "[admin] setbonus %player% worth 43",
                ButtonClickContext.create(actor, (MenuView) view, 0, ClickType.LEFT));
        reply(actor, "SUPERIOR_COMMAND_RECHECK_MENUHOOK_SENT target=captured-first");
    }
    private void tailOpenKick(Player actor) {
        Menus.MENU_CONFIRM_KICK.createView(account(PRIMARY), new MenuConfirmKick.Args(first(), account(AUXILIARY)))
                .whenComplete((view, error) -> {
                    if (error != null) fail(actor, error); else reply(actor, "SUPERIOR_COMMAND_RECHECK_KICK_GUI_READY");
                });
    }
    private void tailOpenBan(Player actor) {
        previousBanRole = first().getRequiredPlayerRole(IslandPrivileges.BAN_MEMBER);
        first().setPermission(account(AUXILIARY).getPlayerRole(first()), IslandPrivileges.BAN_MEMBER);
        auxiliary().thenCompose(other -> onPlayer(other, () -> Menus.MENU_CONFIRM_BAN.createView(account(AUXILIARY),
                new MenuConfirmBan.Args(first(), account(PRIMARY)))))
                .thenCompose(view -> view).whenComplete((view, error) -> {
                    if (error != null) fail(actor, error); else reply(actor, "SUPERIOR_COMMAND_RECHECK_BAN_GUI_READY");
                });
    }
    private void tailGuiCheck(Player actor) {
        if (!first().isMember(account(AUXILIARY)) || first().isBanned(account(PRIMARY)))
            throw new IllegalStateException("The stale membership GUI or current ban hierarchy changed membership");
        first().setPermission(previousBanRole, IslandPrivileges.BAN_MEMBER);
        previousBanRole = null;
        reply(actor, "SUPERIOR_COMMAND_RECHECK_GUI_PASS membership=epoch ban=current-hierarchy");
    }
    private void tailGlobalCheck(Player actor) {
        MenuView<?, ?> view = account(PRIMARY).getOpenedView();
        if (!(view instanceof AbstractMenuView<?, ?> actual) || actual.getActionTarget() != null || !actual.isIslandContextCurrent())
            throw new IllegalStateException("The real language menu is incidentally bound to an island");
        account(PRIMARY).selectIsland(second());
        MenuCommands.getInstance().runCommand(view, "[player] is home",
                ButtonClickContext.create(actor, (MenuView) view, 10, ClickType.LEFT));
        reply(actor, "SUPERIOR_COMMAND_RECHECK_GLOBAL_GUI_PASS context=personal");
    }
    private void tailGlobalHomeCheck(Player actor) {
        onPlayer(actor, () -> {
            if (!second().isInside(actor.getLocation()))
                throw new IllegalStateException("An unbound personal menu suppressed the natural current-island home command");
            return null;
        }).whenComplete((unused, error) -> {
            if (error != null) fail(actor, error); else reply(actor, "SUPERIOR_COMMAND_RECHECK_GLOBAL_HOME_PASS target=current-selection");
        });
    }
    private void tailListSlot(Player actor) {
        if (!Menus.MENU_GLOBAL_WARPS.isVisitorWarps())
            throw new IllegalStateException("Private visitor GUI qualification requires global-warps visitor-warps:true");
        auxiliary().thenCompose(other -> onPlayer(other, () -> {
            MenuView<?, ?> view = account(AUXILIARY).getOpenedView();
            if (view == null || view.getMenu() != Menus.MENU_GLOBAL_WARPS && view.getMenu() != Menus.MENU_TOP_ISLANDS ||
                    !(view instanceof AbstractMenuView<?, ?> actual) || actual.getActionTarget() != null)
                throw new IllegalStateException("An actual global visitor/top GUI must be open for the auxiliary client");
            for (int slot = 0; slot < view.getInventory().getSize(); slot++) {
                var template = view.getMenu().getLayout().getButton(slot);
                if (template instanceof PagedMenuTemplateButton<?, ?> paged && view instanceof PagedMenuView<?, ?, ?> list) {
                    int index = paged.getButtonIndex() +
                            ((PagedMenuLayout<?>) view.getMenu().getLayout()).getObjectsPerPageCount() * (list.getCurrentPage() - 1);
                    if (index < list.getPagedObjects().size() && list.getPagedObjects().get(index) == first() && view.getInventory().getItem(slot) != null)
                        return slot;
                }
            }
            throw new IllegalStateException("The original first island is absent from the actual global listing");
        })).whenComplete((slot, error) -> {
            if (error != null) fail(actor, error); else reply(actor, "SUPERIOR_COMMAND_RECHECK_LIST_SLOT slot=" + slot);
        });
    }
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void adminRate(IslandRateEvent event) {
        if (event.getPlayer() == null && PRIMARY.equals(event.getIsland().getOwner().getName()))
            adminRatingAuthor = event.getRatingPlayer() == null ? null : event.getRatingPlayer().getUniqueId();
    }
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void adminRemoveRate(IslandRemoveRatingEvent event) {
        if (event.getPlayer() == null && PRIMARY.equals(event.getIsland().getOwner().getName()))
            removedRatingAuthor = event.getRatingPlayer() == null ? null : event.getRatingPlayer().getUniqueId();
    }
    private void tailEventClear(Player actor) {
        BukkitExecutor.global(() -> {
            try {
                superior.getCommands().dispatchSubCommand(Bukkit.getConsoleSender(), "admin", "setrate id:" + first().getShortId() + " " + AUXILIARY + " FIVE_STARS");
                superior.getCommands().dispatchSubCommand(Bukkit.getConsoleSender(), "admin", "setrate id:" + first().getShortId() + " " + AUXILIARY + " UNKNOWN");
                reply(actor, "SUPERIOR_COMMAND_RECHECK_RATING_SENT subject=auxiliary");
            } catch (Throwable error) { fail(actor, error); }
        });
    }
    private void tailEventCheck(Player actor) {
        UUID expected = account(AUXILIARY).getUniqueId();
        if (!expected.equals(adminRatingAuthor) || !expected.equals(removedRatingAuthor) || first().getRatingAmount() != 0)
            throw new IllegalStateException("Admin setrate changed/emitted another rating author");
        reply(actor, "SUPERIOR_COMMAND_RECHECK_RATING_PASS author=actual-target");
    }
    private void tailFinish(Player actor) {
        if (!third().getUniqueId().equals(consoleTeamIsland) || first().getBonusWorth().intValueExact() != 43 ||
                second().getBonusWorth().signum() != 0 || third().getBonusWorth().signum() != 0 ||
                !first().getDiscord().equals("Join --island id:" + second().getShortId()) ||
                first().getWarp("admin --island id:" + second().getShortId()) != null ||
                first().getWarp("user --island id:" + second().getShortId()) != null ||
                second().getWarp("admin") == null || second().getWarp("user") == null)
            throw new IllegalStateException("The functional command recheck is incomplete");
        var dimension = superior.getSettings().getWorlds().getDefaultWorldDimension();
        for (Island island : List.of(first(), second(), third()))
            if (island.getGeneratorPercentage(Keys.ofMaterialAndData("STONE"), dimension) != 35)
                throw new IllegalStateException("Approved wildcard generators lost their percentage on a later island");
        recheckChecked = true;
        reply(actor, "SUPERIOR_COMMAND_RECHECK_PASS actors=2 smallIslands=3 loadTest=false");
    }

    private void checkpoint(Player actor, boolean restart) {
        if (failed || (!restart && (!chatChecked || !warpChecked || !ratingsChecked || !upgradeChecked || !missionChecked ||
                !demoteChecked || !staleDemoteChecked || !kickChecked || !staleKickChecked || !sizeChecked || !payloadChecked ||
                !zeroOwnedSizeChecked || !bulkDisbandsChecked || !recheckChecked)))
            throw new IllegalStateException("Native command qualification is incomplete");
        var member = account(AUXILIARY);
        if (owned().size() != 2 || !first().isMember(member) || !second().isMember(member) ||
                first().getUpgradeLevel(upgrade()).getLevel() != 3 || second().getUpgradeLevel(upgrade()).getLevel() != 2 ||
                !first().hasCompletedMission(mission()) || second().hasCompletedMission(mission()) ||
                first().getIslandSize() != 52 || second().getIslandSize() != 52 ||
                first().getRatingAmount() != 0 || second().getRatingAmount() != 0)
            throw new IllegalStateException("Native command state is not the expected durable checkpoint");
        Properties state = new Properties();
        for (Island island : owned()) {
            String prefix = "island." + island.getShortId() + ".";
            state.setProperty(prefix + "uuid", island.getUniqueId().toString());
            state.setProperty(prefix + "member-role", Integer.toString(member.getPlayerRole(island).getId()));
            state.setProperty(prefix + "level", Integer.toString(island.getUpgradeLevel(upgrade()).getLevel()));
            state.setProperty(prefix + "ratings", Integer.toString(island.getRatingAmount()));
            state.setProperty(prefix + "size", Integer.toString(island.getIslandSize()));
            state.setProperty(prefix + "mission", Boolean.toString(island.hasCompletedMission(mission())));
        }
        state.setProperty("selected", Long.toString(account(PRIMARY).getSelectedIsland().getShortId()));
        state.setProperty("primary.disbands", Integer.toString(account(PRIMARY).getDisbands()));
        state.setProperty("auxiliary.disbands", Integer.toString(account(AUXILIARY).getDisbands()));
        state.setProperty("owner.override", String.valueOf(superior.getPvcEntitlements().getOwnerOverride(account(PRIMARY).getUniqueId())));
        if (!(superior.getGrid().getDatabaseBridge() instanceof SQLDatabaseBridge database))
            throw new IllegalStateException("Actual default SQL bridge is required");
        database.executeAcknowledged(() -> { }).whenComplete((unused, error) -> {
            if (error != null) { fail(actor, error); return; }
            superior.executeAsync(() -> {
                try {
                    guard(); Path saved = root.resolve("command-probe-state.properties");
                    if (restart) {
                        Properties before = new Properties(); try (var input = Files.newInputStream(saved)) { before.load(input); }
                        if (!before.equals(state)) throw new IllegalStateException("Command target state changed after normal restart");
                    } else {
                        if (Files.exists(saved)) throw new IllegalStateException("Native command checkpoint already exists");
                        try (var output = Files.newOutputStream(saved)) { state.store(output, "Synthetic private native command state"); }
                    }
                    String marker = restart ? "SUPERIOR_COMMAND_RESTART_PASS" : "SUPERIOR_COMMAND_PROBE_PASS";
                    observer.getLogger().info(marker); reply(actor, marker);
                } catch (Throwable failure) { fail(actor, failure); }
            }, 0L);
        });
    }
    private void reply(Player actor, String message) {
        superior.executePlayer(superior.getPlayers().getSuperiorPlayer(actor), () -> actor.sendMessage(message), 0L);
    }
    private void fail(Player actor, Throwable error) {
        failed = true; observer.getLogger().log(java.util.logging.Level.SEVERE, "SUPERIOR_COMMAND_PROBE_FAIL", error);
        reply(actor, "SUPERIOR_COMMAND_PROBE_FAIL " + error.getClass().getSimpleName());
    }

    /** Keeps the real mutation handler and its type identity; observes only its invocation boundary. */
    private final class SetupgradeTrace extends CmdAdminSetUpgrade {
        private final SuperiorCommand original;
        SetupgradeTrace(SuperiorCommand original) { this.original = original; }
        @Override public void execute(SuperiorSkyblockPlugin plugin, CommandSender sender, String[] arguments) {
            Island target = IslandExecutionScope.current();
            SuperiorPlayer subject = arguments.length > 2 ? plugin.getPlayers().getSuperiorPlayer(arguments[2]) : null;
            observer.getLogger().info("SUPERIOR_COMMAND_SETUPGRADE_TRACE target=" + (target == null ? "none" : target.getShortId()) +
                    " active=" + IslandExecutionScope.isActive() + " explicit=" + IslandExecutionScope.isExplicitTarget() +
                    " inherited=" + IslandExecutionScope.isInheritedTarget() +
                    " thread=" + Thread.currentThread().getName().replace(' ', '_') +
                    " subject=" + (subject == null ? "none" : subject.getName()) +
                    " member=" + (target != null && subject != null && target.isMember(subject)));
            original.execute(plugin, sender, arguments);
        }
    }

    /** Observes the original console handler only after it returns without a player cast failure. */
    private final class TeamTrace extends CmdTeam {
        private final SuperiorCommand original;
        TeamTrace(SuperiorCommand original) { this.original = original; }
        @Override public void execute(SuperiorSkyblockPlugin plugin, CommandSender sender, String[] arguments) {
            Island captured = IslandExecutionScope.current();
            original.execute(plugin, sender, arguments);
            if (sender instanceof ConsoleCommandSender && captured != null) consoleTeamIsland = captured.getUniqueId();
        }
    }
}

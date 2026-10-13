package com.bgsoftware.superiorskyblock.commands;

import com.bgsoftware.superiorskyblock.core.threads.BukkitExecutor;

import com.bgsoftware.common.annotations.Nullable;
import com.bgsoftware.superiorskyblock.SuperiorSkyblockPlugin;
import com.bgsoftware.superiorskyblock.api.commands.SuperiorCommand;
import com.bgsoftware.superiorskyblock.api.handlers.CommandsManager;
import com.bgsoftware.superiorskyblock.api.objects.Pair;
import com.bgsoftware.superiorskyblock.api.wrappers.SuperiorPlayer;
import com.bgsoftware.superiorskyblock.core.Manager;
import com.bgsoftware.superiorskyblock.core.events.plugin.PluginEventType;
import com.bgsoftware.superiorskyblock.core.events.plugin.PluginEventsDispatcher;
import com.bgsoftware.superiorskyblock.core.formatting.Formatters;
import com.bgsoftware.superiorskyblock.core.io.FileClassLoader;
import com.bgsoftware.superiorskyblock.core.io.Files;
import com.bgsoftware.superiorskyblock.core.io.JarFiles;
import com.bgsoftware.superiorskyblock.core.io.loader.FilesLookup;
import com.bgsoftware.superiorskyblock.core.io.loader.FilesLookupFactory;
import com.bgsoftware.superiorskyblock.core.logging.Debug;
import com.bgsoftware.superiorskyblock.core.logging.Log;
import com.bgsoftware.superiorskyblock.core.messages.Message;
import com.bgsoftware.superiorskyblock.core.threads.BukkitExecutor;
import com.bgsoftware.superiorskyblock.player.PlayerLocales;
import com.google.common.base.Preconditions;
import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.command.defaults.BukkitCommand;
import org.bukkit.entity.Player;

import java.io.File;
import java.lang.reflect.Constructor;
import java.time.Duration;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

@SuppressWarnings("UnstableApiUsage")
public class CommandsManagerImpl extends Manager implements CommandsManager {

    private static volatile Set<String> DANGEROUS_COMMANDS_CACHE = Collections.emptySet();

    private final Map<UUID, Map<String, Long>> commandsCooldown = new java.util.concurrent.ConcurrentHashMap<>();
    private final Map<String, Cache<UUID, DangerousCommandRequest>> dangerousCommandsRequests = new java.util.concurrent.ConcurrentHashMap<>();

    private final CommandsMap playerCommandsMap;
    private final CommandsMap adminCommandsMap;

    private Set<Runnable> pendingCommands = new HashSet<>();

    private PluginCommand pluginCommand;
    private String label = null;

    public CommandsManagerImpl(SuperiorSkyblockPlugin plugin, CommandsMap playerCommandsMap, CommandsMap adminCommandsMap) {
        super(plugin);
        this.playerCommandsMap = playerCommandsMap;
        this.adminCommandsMap = adminCommandsMap;
    }

    @Override
    public void loadData() {
        // Pending requests of dangerous commands should not survive reloads.
        cancelAllDangerousRequests();

        String islandCommand = plugin.getSettings().getIslandCommand();
        label = islandCommand.split(",")[0];

        pluginCommand = new PluginCommand(label);

        String[] commandSections = islandCommand.split(",");

        if (commandSections.length > 1) {
            pluginCommand.setAliases(Arrays.asList(Arrays.copyOfRange(commandSections, 1, commandSections.length)));
        }

        plugin.getNMSAlgorithms().registerCommand(pluginCommand);

        playerCommandsMap.loadDefaultCommands();
        playerCommandsMap.registerCommand(new com.bgsoftware.superiorskyblock.commands.player.CmdIslandsList());
        playerCommandsMap.registerCommand(new com.bgsoftware.superiorskyblock.commands.player.CmdIslandSelect());
        adminCommandsMap.loadDefaultCommands();

        loadCommands();

        if (this.pendingCommands != null) {
            Set<Runnable> pendingCommands = new HashSet<>(this.pendingCommands);
            this.pendingCommands = null;
            pendingCommands.forEach(Runnable::run);
        }
    }

    @Override
    public void registerCommand(SuperiorCommand superiorCommand) {
        Preconditions.checkNotNull(superiorCommand, "superiorCommand parameter cannot be null.");

        if (pendingCommands != null) {
            pendingCommands.add(() -> registerCommand(superiorCommand));
            return;
        }

        playerCommandsMap.registerCommand(superiorCommand);
    }

    @Override
    public void unregisterCommand(SuperiorCommand superiorCommand) {
        playerCommandsMap.unregisterCommand(superiorCommand);
    }

    @Override
    public void registerAdminCommand(SuperiorCommand superiorCommand) {
        if (pendingCommands != null) {
            pendingCommands.add(() -> registerAdminCommand(superiorCommand));
            return;
        }

        Preconditions.checkNotNull(superiorCommand, "superiorCommand parameter cannot be null.");
        adminCommandsMap.registerCommand(superiorCommand);
    }

    @Override
    public void unregisterAdminCommand(SuperiorCommand superiorCommand) {
        Preconditions.checkNotNull(superiorCommand, "superiorCommand parameter cannot be null.");
        adminCommandsMap.unregisterCommand(superiorCommand);
    }

    @Override
    public List<SuperiorCommand> getSubCommands() {
        return getSubCommands(false);
    }

    @Override
    public List<SuperiorCommand> getSubCommands(boolean includeDisabled) {
        return playerCommandsMap.getSubCommands(includeDisabled);
    }

    @Nullable
    @Override
    public SuperiorCommand getCommand(String commandLabel) {
        return playerCommandsMap.getCommand(commandLabel);
    }

    @Override
    public List<SuperiorCommand> getAdminSubCommands() {
        return adminCommandsMap.getSubCommands(true);
    }

    @Nullable
    @Override
    public SuperiorCommand getAdminCommand(String commandLabel) {
        return adminCommandsMap.getCommand(commandLabel);
    }

    @Override
    public void dispatchSubCommand(CommandSender sender, String subCommand) {
        dispatchSubCommand(sender, subCommand, null);
    }

    @Override
    public void dispatchSubCommand(CommandSender sender, String subCommand, @Nullable String args) {
        // We first check that the sub command is enabled.
        if (getCommand(subCommand) == null) {
            Bukkit.dispatchCommand(sender, this.label + " " + subCommand + (args == null ? "" : " " + args));
            return;
        }

        String[] argsSplit = args == null ? null : args.split(" ");
        String[] commandArguments;

        if (argsSplit == null || (argsSplit.length == 1 && argsSplit[0].isEmpty())) {
            commandArguments = new String[1];
            commandArguments[0] = subCommand;
        } else {
            commandArguments = new String[argsSplit.length + 1];
            commandArguments[0] = subCommand;
            System.arraycopy(argsSplit, 0, commandArguments, 1, argsSplit.length);
        }

        pluginCommand.execute(sender, "", commandArguments);
    }

    public String getLabel() {
        return label;
    }

    public static void registerListeners(PluginEventsDispatcher dispatcher) {
        dispatcher.registerCallback(PluginEventType.SETTINGS_UPDATE_EVENT, CommandsManagerImpl::onSettingsUpdate);
        // Admin commands can be registered after settings are loaded (e.g. by modules).
        dispatcher.registerCallback(PluginEventType.COMMANDS_UPDATE_EVENT, CommandsManagerImpl::onSettingsUpdate);
    }

    @Override
    public boolean isDangerousCommand(SuperiorCommand command) {
        return DANGEROUS_COMMANDS_CACHE.contains(getCommandLabel(command));
    }

    @Override
    public void submitDangerousRequest(Player player, SuperiorCommand command, String[] args) {
        String commandLabel = getCommandLabel(command);
        String executedCommand = "/" + this.label + " " + String.join(" ", args);
        com.bgsoftware.superiorskyblock.api.island.Island target = com.bgsoftware.superiorskyblock.api.island.IslandExecutionScope.isExplicitTarget() ?
                com.bgsoftware.superiorskyblock.pvc.island.IslandExecutionContext.current() : null;
        List<com.bgsoftware.superiorskyblock.api.island.Island> targets = new java.util.ArrayList<>();
        List<UUID> owners = new java.util.ArrayList<>();
        UUID ratingAuthor = null;
        UUID subjectUUID = null;
        List<UUID> bulkPlayers = new java.util.ArrayList<>();
        boolean ownerOperation = "clearsizeoverride".equals(commandLabel) ||
                plugin.getPvcEntitlements().isAutomaticSize() && Arrays.asList("setsize", "addsize").contains(commandLabel);
        if (ownerOperation && args.length > 2) {
            Set<UUID> unique = new java.util.LinkedHashSet<>();
            if (target != null && target.getOwner() != null) unique.add(target.getOwner().getUniqueId());
            else if ("*".equals(args[2]) && !"clearsizeoverride".equals(commandLabel)) {
                for (com.bgsoftware.superiorskyblock.api.island.Island island : plugin.getGrid().getIslands())
                    if (island.getOwner() != null) unique.add(island.getOwner().getUniqueId());
            } else {
                String name = args[2].startsWith("owner:") ? args[2].substring(6) : args[2];
                SuperiorPlayer owner = plugin.getPlayers().getSuperiorPlayer(name);
                if (owner == null) {
                    com.bgsoftware.superiorskyblock.api.island.Island island = plugin.getPvcIslandService().resolve(name);
                    owner = island == null ? null : island.getOwner();
                }
                if (owner == null) { Message.INVALID_ISLAND.send(player); return; }
                unique.add(owner.getUniqueId());
            }
            owners.addAll(unique);
            if (owners.isEmpty()) { Message.INVALID_ISLAND.send(player); return; }
        } else if (command instanceof com.bgsoftware.superiorskyblock.commands.admin.CmdAdminRemoveRatings && target == null && args.length > 2 &&
                !"*".equals(args[2]) && plugin.getPlayers().getSuperiorPlayer(args[2]) != null) {
            ratingAuthor = plugin.getPlayers().getSuperiorPlayer(args[2]).getUniqueId();
            targets.addAll(plugin.getGrid().getIslands());
        } else if (target != null) targets.add(target);
        else if (command instanceof IAdminIslandCommand && args.length > 2) {
            if ("*".equals(args[2])) targets.addAll(plugin.getGrid().getIslands());
            else {
                target = com.bgsoftware.superiorskyblock.commands.arguments.CommandArguments.getIsland(plugin, player, args[2]).getIsland();
                if (target == null) return; targets.add(target);
            }
        } else if (command instanceof IAdminPlayerCommand && requiresFrozenIsland(command, args) && args.length > 2) {
            SuperiorPlayer subject = plugin.getPlayers().getSuperiorPlayer(args[2]);
            com.bgsoftware.superiorskyblock.api.island.Island subjectIsland = subject == null ? null :
                    com.bgsoftware.superiorskyblock.commands.arguments.CommandArguments.resolvePlayerIsland(subject, true);
            if (subjectIsland == null) {
                Message.CUSTOM.send(player, "&cChoose an explicit --island target before requesting this operation.", true); return;
            }
            targets.add(subjectIsland);
        } else if (command instanceof com.bgsoftware.superiorskyblock.commands.admin.CmdAdminData && args.length > 4 && "island".equalsIgnoreCase(args[3])) {
            target = com.bgsoftware.superiorskyblock.commands.arguments.CommandArguments.getIsland(plugin, player, args[4]).getIsland();
            if (target == null) return;
            targets.add(target);
        } else if (command.getAliases().contains("purge")) targets.addAll(plugin.getGrid().getIslands());
        if (command instanceof IAdminPlayerCommand && args.length > 2) {
            if (((IAdminPlayerCommand) command).supportMultiplePlayers() && "*".equals(args[2])) {
                for (SuperiorPlayer subject : plugin.getPlayers().getAllPlayers()) bulkPlayers.add(subject.getUniqueId());
            } else {
                SuperiorPlayer subject = plugin.getPlayers().getSuperiorPlayer(args[2]);
                if (subject == null) { Message.INVALID_PLAYER.send(player, args[2]); return; }
                subjectUUID = subject.getUniqueId();
            }
        }
        List<IslandTarget> frozen = new java.util.ArrayList<>();
        for (com.bgsoftware.superiorskyblock.api.island.Island island : targets)
            frozen.add(new IslandTarget(island, plugin.getPvcIslandService().getRevision(island.getUniqueId()), plugin.getPvcIslandService().getMembershipRevision(island.getUniqueId())));

        // Overrides any previous request of the player for this command, and resets its expiry time.
        dangerousCommandsRequests.computeIfAbsent(commandLabel, l -> CacheBuilder.newBuilder()
                .expireAfterWrite(1, TimeUnit.MINUTES)
                .build()
        ).put(player.getUniqueId(), new DangerousCommandRequest(player.getUniqueId(), player.getName(),
                executedCommand, args.clone(), System.currentTimeMillis(), frozen, owners, ratingAuthor, subjectUUID, bulkPlayers));

        Message.DANGEROUS_COMMAND_REQUEST_SENT.send(player, executedCommand);
        Message.DANGEROUS_COMMAND_REQUEST_CONSOLE.send(Bukkit.getConsoleSender(), player.getName(),
                executedCommand, this.label + " admin " + commandLabel);
    }

    @Override
    public boolean hasPendingDangerousRequests(SuperiorCommand command) {
        Cache<UUID, DangerousCommandRequest> requests = dangerousCommandsRequests.get(getCommandLabel(command));
        if (requests == null)
            return false;

        requests.cleanUp();
        return requests.size() > 0;
    }

    @Override
    public void approveDangerousRequests(CommandSender sender, SuperiorCommand command) {
        Cache<UUID, DangerousCommandRequest> requests = dangerousCommandsRequests.get(getCommandLabel(command));
        if (requests == null)
            return;

        List<DangerousCommandRequest> requestsToExecute = new LinkedList<>(requests.asMap().values());
        // We clear the requests before executing them, so they cannot be executed twice.
        requests.invalidateAll();

        requestsToExecute.sort(Comparator.comparingLong(request -> request.creationTime));

        for (DangerousCommandRequest request : requestsToExecute) {
            Player player = Bukkit.getPlayer(request.playerUUID);

            if (player == null || !player.isOnline()) {
                Message.DANGEROUS_COMMAND_PLAYER_OFFLINE.send(sender, request.playerName, request.executedCommand);
                continue;
            }

            BukkitExecutor.entity(player, () -> {
                List<com.bgsoftware.superiorskyblock.api.island.Island> resolved = new java.util.ArrayList<>();
                boolean valid = true;
                for (IslandTarget snapshot : request.targets) {
                    com.bgsoftware.superiorskyblock.api.island.Island island = plugin.getGrid().getIslandByUUID(snapshot.island);
                    if (island == null || !plugin.getPvcIslandService().isAvailable(snapshot.island) ||
                            !java.util.Objects.equals(snapshot.owner, island.getOwner() == null ? null : island.getOwner().getUniqueId()) ||
                            snapshot.revision != plugin.getPvcIslandService().getRevision(snapshot.island) ||
                            snapshot.membershipRevision != plugin.getPvcIslandService().getMembershipRevision(snapshot.island)) { valid = false; break; }
                    resolved.add(island);
                }
                if (!valid || !CommandsHelper.hasCommandAccess(command, player) || System.currentTimeMillis() - request.creationTime > 60000L ||
                        !request.owners.isEmpty() && !"clearsizeoverride".equals(getCommandLabel(command)) && !plugin.getPvcEntitlements().isAutomaticSize()) {
                    Message.DANGEROUS_COMMAND_NO_ACCESS.send(sender, request.playerName, request.executedCommand); return;
                }
                String[] approvedArgs = request.args.clone();
                if (request.subject != null) {
                    SuperiorPlayer subject = plugin.getPlayers().getSuperiorPlayer(request.subject);
                    SuperiorPlayer namedSubject = subject == null ? null : plugin.getPlayers().getSuperiorPlayer(subject.getName());
                    if (namedSubject == null || !request.subject.equals(namedSubject.getUniqueId())) {
                        Message.DANGEROUS_COMMAND_NO_ACCESS.send(sender, request.playerName, request.executedCommand); return;
                    }
                    approvedArgs[2] = subject.getName();
                }
                Message.DANGEROUS_COMMAND_APPROVED.send(player, request.executedCommand);
                try {
                    if (!request.bulkPlayers.isEmpty()) {
                        List<SuperiorPlayer> subjects = new java.util.ArrayList<>();
                        for (UUID subjectId : request.bulkPlayers) {
                            SuperiorPlayer subject = plugin.getPlayers().getSuperiorPlayer(subjectId);
                            if (subject == null) throw new IllegalStateException("A captured player no longer exists");
                            subjects.add(subject);
                        }
                        ((IAdminPlayerCommand) command).execute(plugin, player, Collections.unmodifiableList(subjects), approvedArgs);
                    } else if (!request.owners.isEmpty() && !"clearsizeoverride".equals(getCommandLabel(command))) {
                        for (UUID ownerUUID : request.owners)
                            if (plugin.getPlayers().getSuperiorPlayer(ownerUUID) == null)
                                throw new IllegalStateException("The captured owner no longer exists");
                        com.bgsoftware.superiorskyblock.commands.arguments.NumberArgument<Integer> number =
                                com.bgsoftware.superiorskyblock.commands.arguments.CommandArguments.getSize(player, approvedArgs[3]);
                        if (number.isSucceed()) com.bgsoftware.superiorskyblock.commands.admin.PvcSizeCommands.setFrozenOwners(
                                plugin, player, request.owners, number.getNumber(), "addsize".equals(getCommandLabel(command)));
                    } else if (!request.owners.isEmpty()) {
                        for (UUID ownerUUID : request.owners) {
                            SuperiorPlayer owner = plugin.getPlayers().getSuperiorPlayer(ownerUUID);
                            if (owner == null) throw new IllegalStateException("The captured owner no longer exists");
                            SuperiorPlayer named = plugin.getPlayers().getSuperiorPlayer(owner.getName());
                            if (named == null || !ownerUUID.equals(named.getUniqueId())) throw new IllegalStateException("The captured owner identity changed");
                            approvedArgs[2] = "owner:" + owner.getName();
                            try (com.bgsoftware.superiorskyblock.api.island.IslandExecutionScope scope =
                                         com.bgsoftware.superiorskyblock.api.island.IslandExecutionScope.open(null, false)) {
                                command.execute(plugin, player, approvedArgs);
                            }
                        }
                    } else if (request.ratingAuthor != null) {
                        SuperiorPlayer author = plugin.getPlayers().getSuperiorPlayer(request.ratingAuthor);
                        if (author == null) throw new IllegalStateException("The captured rating author no longer exists");
                        ((com.bgsoftware.superiorskyblock.commands.admin.CmdAdminRemoveRatings) command).execute(plugin, player, author, resolved, approvedArgs);
                    } else {
                        if (resolved.isEmpty()) resolved.add(null);
                        for (com.bgsoftware.superiorskyblock.api.island.Island island : resolved) {
                            try (com.bgsoftware.superiorskyblock.api.island.IslandExecutionScope scope =
                                         com.bgsoftware.superiorskyblock.api.island.IslandExecutionScope.open(island)) {
                                command.execute(plugin, player, approvedArgs);
                            }
                        }
                    }
                } catch (Throwable error) { Log.error(error, "Cannot execute the approved island operation", request.executedCommand); }
            });
        }
    }

    @Override
    public void cancelDangerousRequests(UUID playerUUID) {
        for (Cache<UUID, DangerousCommandRequest> requests : dangerousCommandsRequests.values()) {
            DangerousCommandRequest request = requests.getIfPresent(playerUUID);
            if (request != null) {
                requests.invalidate(playerUUID);
                Message.DANGEROUS_COMMAND_PLAYER_QUIT.send(Bukkit.getConsoleSender(), request.playerName, request.executedCommand);
            }
        }
    }

    @Override
    public void cancelAllDangerousRequests() {
        for (Cache<UUID, DangerousCommandRequest> requests : dangerousCommandsRequests.values()) {
            for (DangerousCommandRequest request : requests.asMap().values()) {
                Player player = Bukkit.getPlayer(request.playerUUID);
                if (player != null)
                    Message.DANGEROUS_COMMAND_CANCELLED.send(player, request.executedCommand);
            }
        }

        dangerousCommandsRequests.clear();
    }

    private static void onSettingsUpdate() {
        Set<String> dangerous = new HashSet<>();
        SuperiorSkyblockPlugin plugin = SuperiorSkyblockPlugin.getPlugin();
        plugin.getSettings().getDangerousCommands().forEach(commandLabel -> {
            SuperiorCommand superiorCommand = plugin.getCommands().getAdminCommand(commandLabel);
            if (superiorCommand != null)
                dangerous.add(getCommandLabel(superiorCommand));
        });
        DANGEROUS_COMMANDS_CACHE = Collections.unmodifiableSet(dangerous);
    }

    private boolean requiresFrozenIsland(SuperiorCommand command, String[] args) {
        if (((IAdminPlayerCommand) command).requireIsland()) return true;
        if (!(command instanceof com.bgsoftware.superiorskyblock.module.missions.commands.CmdAdminMission) || args.length < 5) return false;
        if ("*".equals(args[4])) return !plugin.getMissions().getIslandMissions().isEmpty();
        com.bgsoftware.superiorskyblock.api.missions.Mission<?> mission = plugin.getMissions().getMission(args[4]);
        return mission != null && mission.getIslandMission();
    }

    private static String getCommandLabel(SuperiorCommand command) {
        return command.getAliases().get(0).toLowerCase(Locale.ENGLISH);
    }

    @SuppressWarnings("ResultOfMethodCallIgnored")
    private void loadCommands() {
        File commandsFolder = new File(plugin.getDataFolder(), "commands");

        if (!commandsFolder.exists()) {
            commandsFolder.mkdirs();
            return;
        }

        List<File> folderFiles = Files.listFolderFiles(commandsFolder, false, file -> file.getName().endsWith(".jar"));
        if (folderFiles.isEmpty())
            return;

        try (FilesLookup filesLookup = FilesLookupFactory.getInstance().lookupFolder(commandsFolder)) {
            for (File file : folderFiles) {
                try {
                    String fileName = file.getName();
                    file = filesLookup.getFile(fileName);

                    FileClassLoader classLoader = new FileClassLoader(file, plugin.getPluginClassLoader(),
                            plugin.getNMSAlgorithms().getClassProcessor());

                    //noinspection deprecation
                    Class<?> commandClass = JarFiles.getClass(file.toURL(), SuperiorCommand.class, classLoader).getLeft();

                    if (commandClass == null)
                        continue;

                    SuperiorCommand superiorCommand = createInstance(commandClass);

                    if (file.getName().toLowerCase(Locale.ENGLISH).contains("admin")) {
                        registerAdminCommand(superiorCommand);
                        Log.info("Successfully loaded external admin command: ", file.getName().split("\\.")[0]);
                    } else {
                        registerCommand(superiorCommand);
                        Log.info("Successfully loaded external command: ", file.getName().split("\\.")[0]);
                    }

                } catch (Exception error) {
                    Log.error(error, "An unexpected error occurred while loading an external command ", file.getName(), ":");
                }
            }
        }

    }

    private SuperiorCommand createInstance(Class<?> clazz) throws Exception {
        Preconditions.checkArgument(SuperiorCommand.class.isAssignableFrom(clazz), "Class " + clazz + " is not a SuperiorCommand.");

        for (Constructor<?> constructor : clazz.getConstructors()) {
            if (constructor.getParameterCount() == 0) {
                if (!constructor.isAccessible())
                    constructor.setAccessible(true);

                return (SuperiorCommand) constructor.newInstance();
            }
        }

        throw new IllegalArgumentException("Class " + clazz + " has no valid constructors.");
    }

    private class PluginCommand extends BukkitCommand {

        PluginCommand(String islandCommandLabel) {
            super(islandCommandLabel);
        }

        @Override
        public boolean execute(CommandSender sender, String label, String[] args) {
            return executeWithPolicy(sender, label, args, false);
        }

        private boolean executeWithPolicy(CommandSender sender, String label, String[] args, boolean resumed) {
            if (!plugin.getPvcIslandService().isReady()) {
                Message.CUSTOM.send(sender, "&eIsland metadata is being initialized or recovered. Please wait.", true); return false;
            }
            if (sender instanceof Player) {
                Player actor = (Player) sender;
                SuperiorPlayer account = plugin.getPlayers().getSuperiorPlayer(sender);
                if (account.getIslands().size() > 1) {
                    plugin.getPvcEntitlements().getSnapshot(account.getUniqueId());
                    try { plugin.getPvcEntitlements().getVerifiedOwnershipLimit(account.getUniqueId()); }
                    catch (IllegalStateException unavailable) {
                        if (resumed) { policyUnavailable(sender); return false; }
                        String[] capturedArgs = args.clone();
                        com.bgsoftware.superiorskyblock.api.island.Island captured =
                                com.bgsoftware.superiorskyblock.api.island.IslandExecutionScope.current();
                        boolean wasExplicit = com.bgsoftware.superiorskyblock.api.island.IslandExecutionScope.isExplicitTarget();
                        boolean wasInherited = com.bgsoftware.superiorskyblock.api.island.IslandExecutionScope.isInheritedTarget();
                        com.bgsoftware.superiorskyblock.api.island.Island selected = account.getSelectedIsland();
                        IslandTarget snapshot = captured == null ? null : new IslandTarget(captured,
                                plugin.getPvcIslandService().getRevision(captured.getUniqueId()),
                                plugin.getPvcIslandService().getMembershipRevision(captured.getUniqueId()));
                        plugin.getPvcEntitlements().refreshOwnershipLimit(account.getUniqueId()).whenComplete((limit, error) ->
                                BukkitExecutor.entity(actor, () -> {
                                    if (!actor.isOnline() || error != null) { if (actor.isOnline()) policyUnavailable(actor); return; }
                                    com.bgsoftware.superiorskyblock.api.island.Island target;
                                    if (snapshot != null) {
                                        target = plugin.getGrid().getIslandByUUID(snapshot.island);
                                        if (target == null || !plugin.getPvcIslandService().isAvailable(snapshot.island) ||
                                                !java.util.Objects.equals(snapshot.owner, target.getOwner() == null ? null : target.getOwner().getUniqueId()) ||
                                                snapshot.revision != plugin.getPvcIslandService().getRevision(snapshot.island) ||
                                                snapshot.membershipRevision != plugin.getPvcIslandService().getMembershipRevision(snapshot.island)) {
                                            Message.INVALID_ISLAND.send(actor); return;
                                        }
                                    } else {
                                        target = limit > 1 ? selected : MultiIslandCommands.legacyIsland(account.getOwnedIslands(), account.getIslands(), selected);
                                        if (target != null && (!target.isMember(account) || !plugin.getPvcIslandService().isAvailable(target.getUniqueId()))) {
                                            Message.INVALID_ISLAND.send(actor); return;
                                        }
                                    }
                                    try (com.bgsoftware.superiorskyblock.api.island.IslandExecutionScope scope = wasInherited ?
                                                 com.bgsoftware.superiorskyblock.api.island.IslandExecutionScope.openInherited(target) :
                                                 com.bgsoftware.superiorskyblock.api.island.IslandExecutionScope.open(target, wasExplicit)) {
                                        executeWithPolicy(actor, label, capturedArgs, true);
                                    } catch (Throwable failure) { Log.error(failure, "Cannot resume an island command after policy refresh"); }
                                }));
                        return true;
                    }
                }
            }
            boolean advanced = MultiIslandCommands.unlocked(plugin, sender);
            boolean administrative = isAdministrativeCommand(sender, args);
            IslandSelectorParser.Result parsed;
            try {
                parsed = IslandSelectorParser.parse(args, advanced || administrative || !(sender instanceof Player),
                        this::playerFamily, this::adminFamily, false);
            } catch (IllegalArgumentException invalid) {
                Message.CUSTOM.send(sender, "&cUse one --island <id|uuid|name> selector before message text.", true);
                return false;
            }
            com.bgsoftware.superiorskyblock.api.island.Island target = parsed.selector == null ? null :
                    plugin.getPvcIslandService().resolve(parsed.selector);
            if (parsed.selector != null && target == null) { Message.INVALID_ISLAND.send(sender); return false; }
            if (target != null && sender instanceof Player) {
                SuperiorPlayer player = plugin.getPlayers().getSuperiorPlayer(sender);
                String family = parsed.arguments.length == 0 ? "tp" : playerFamily(parsed.arguments[0]);
                boolean external = Arrays.asList("admin", "visit", "warp", "show", "rate", "ratings", "accept").contains(family);
                if (!external && !target.isMember(player)) { Message.INVALID_ISLAND.send(sender); return false; }
            }
            try (CommandPresentation presentation = CommandPresentation.open(advanced || administrative || !(sender instanceof Player));
                 com.bgsoftware.superiorskyblock.api.island.IslandExecutionScope scope = executionScope(sender, target, parsed.selector != null)) {
                return executeResolved(sender, label, parsed.arguments);
            }
        }

        private void policyUnavailable(CommandSender sender) {
            Message.CUSTOM.send(sender, "&eIsland commands are temporarily unavailable. Please try again shortly.", true);
        }

        private String playerFamily(String alias) {
            SuperiorCommand command = playerCommandsMap.getCommand(alias);
            return command == null ? "" : getCommandLabel(command);
        }

        private String adminFamily(String alias) {
            SuperiorCommand command = adminCommandsMap.getCommand(alias);
            return command == null ? "" : getCommandLabel(command);
        }

        private boolean isAdministrativeCommand(CommandSender sender, String[] args) {
            for (int i = 0; i < args.length; i++) {
                if ("--island".equalsIgnoreCase(args[i])) { ++i; continue; }
                if ("--".equals(args[i])) continue;
                SuperiorCommand command = playerCommandsMap.getCommand(args[i]);
                return command != null && "admin".equals(getCommandLabel(command)) && CommandsHelper.hasCommandAccess(command, sender);
            }
            return false;
        }

        private com.bgsoftware.superiorskyblock.api.island.IslandExecutionScope executionScope(CommandSender sender,
                com.bgsoftware.superiorskyblock.api.island.Island target, boolean explicit) {
            if (explicit) return com.bgsoftware.superiorskyblock.api.island.IslandExecutionScope.open(target);
            com.bgsoftware.superiorskyblock.api.island.Island inherited = com.bgsoftware.superiorskyblock.api.island.IslandExecutionScope.current();
            if (inherited != null) {
                if (!(sender instanceof Player) || com.bgsoftware.superiorskyblock.api.island.IslandExecutionScope.isInheritedTarget())
                    return com.bgsoftware.superiorskyblock.api.island.IslandExecutionScope.openInherited(inherited);
                return com.bgsoftware.superiorskyblock.api.island.IslandExecutionScope.open(inherited,
                        com.bgsoftware.superiorskyblock.api.island.IslandExecutionScope.isExplicitTarget());
            }
            return com.bgsoftware.superiorskyblock.api.island.IslandExecutionScope.open(sender instanceof Player ?
                    MultiIslandCommands.implicitIsland(plugin, plugin.getPlayers().getSuperiorPlayer(sender)) : null, false);
        }

        private boolean executeResolved(CommandSender sender, String label, String[] args) {
            java.util.Locale locale = PlayerLocales.getLocale(sender);

            String executedSubCommand;

            if (args.length > 0) {
                executedSubCommand = args[0];

                Log.debug(Debug.EXECUTE_COMMAND, sender.getName(), executedSubCommand);

                SuperiorCommand command = playerCommandsMap.getCommand(executedSubCommand);
                if (command != null) {
                    if (MultiIslandCommands.personalTool(command) && !MultiIslandCommands.unlocked(plugin, sender)) {
                        Message.INVALID_COMMAND.send(sender, locale, executedSubCommand);
                        return false;
                    }
                    if (!(sender instanceof Player) && !command.canBeExecutedByConsole()) {
                        Message.CUSTOM.send(sender, "&cCan be executed only by players!", true);
                        return false;
                    }

                    if (!CommandsHelper.hasCommandAccess(command, sender)) {
                        Log.debugResult(Debug.EXECUTE_COMMAND, "Return Missing Permission", command.getPermission());

                        if (!plugin.getSettings().isHelpOnNoPermission())
                            Message.NO_COMMAND_PERMISSION.send(sender, locale, command.getPermission());
                        else if (!"help".equalsIgnoreCase(executedSubCommand))
                            dispatchSubCommand(sender, "help");

                        return false;
                    }

                    if (args.length < command.getMinArgs() || args.length > command.getMaxArgs()) {
                        Log.debugResult(Debug.EXECUTE_COMMAND, "Return Incorrect Usage", command.getUsage(locale));
                        Message.COMMAND_USAGE.send(sender, locale, getLabel() + " " + command.getUsage(locale));
                        return false;
                    }

                    if (sender instanceof Player) {
                        UUID uuid = ((Player) sender).getUniqueId();
                        SuperiorPlayer superiorPlayer = plugin.getPlayers().getSuperiorPlayer(uuid);
                        if (!superiorPlayer.hasPermission("superior.admin.bypass.cooldowns")) {
                            Pair<Integer, String> commandCooldown = getCooldown(command);
                            if (commandCooldown != null) {
                                String commandLabel = command.getAliases().get(0);

                                Map<String, Long> playerCooldowns = commandsCooldown.get(uuid);
                                long timeNow = System.currentTimeMillis();

                                if (playerCooldowns != null) {
                                    Long timeToExecute = playerCooldowns.get(commandLabel);
                                    if (timeToExecute != null) {
                                        if (timeNow < timeToExecute) {
                                            String formattedTime = Formatters.TIME_FORMATTER.format(Duration.ofMillis(timeToExecute - timeNow), locale);
                                            Log.debugResult(Debug.EXECUTE_COMMAND, "Return Cooldown", formattedTime);
                                            Message.COMMAND_COOLDOWN_FORMAT.send(sender, locale, formattedTime);
                                            return false;
                                        }
                                    }
                                }

                                commandsCooldown.computeIfAbsent(uuid, u -> new java.util.concurrent.ConcurrentHashMap<>()).put(commandLabel,
                                        timeNow + commandCooldown.getKey());
                            }
                        }
                    }

                    command.execute(plugin, sender, args);
                    return true;
                }

                if (!plugin.getSettings().isHelpOnInvalidCommand())
                    Message.INVALID_COMMAND.send(sender, locale, executedSubCommand);
                else if (!"help".equalsIgnoreCase(executedSubCommand))
                    dispatchSubCommand(sender, "help");

                return false;
            }

            if (sender instanceof Player) {
                SuperiorPlayer superiorPlayer = plugin.getPlayers().getSuperiorPlayer(sender);

                if (superiorPlayer != null) {
                    String subCommandToExecute;

                    if (!superiorPlayer.hasIsland())
                        subCommandToExecute = "create";
                    else if (superiorPlayer.hasToggledPanel())
                        subCommandToExecute = "panel";
                    else
                        subCommandToExecute = "tp";

                    dispatchSubCommand(sender, subCommandToExecute);
                    return false;
                }
            }

            return false;
        }

        @Override
        public List<String> tabComplete(CommandSender sender, String label, String[] args) {
            boolean advanced = MultiIslandCommands.unlocked(plugin, sender);
            boolean administrative = isAdministrativeCommand(sender, args);
            IslandSelectorParser.Result parsed;
            try {
                parsed = IslandSelectorParser.parse(args, advanced || administrative || !(sender instanceof Player),
                        this::playerFamily, this::adminFamily, true);
            } catch (IllegalArgumentException invalid) { return Collections.emptyList(); }
            if (parsed.completingSelector) {
                List<com.bgsoftware.superiorskyblock.api.island.Island> choices = administrative || !(sender instanceof Player) ? plugin.getGrid().getIslands() :
                        plugin.getPlayers().getSuperiorPlayer(sender).getIslands();
                List<String> result = new java.util.ArrayList<>();
                String prefix = parsed.selector;
                for (com.bgsoftware.superiorskyblock.api.island.Island island : choices) {
                    if (!plugin.getPvcIslandService().isAvailable(island.getUniqueId())) continue;
                    String value = prefix.startsWith("uuid:") ? "uuid:" + island.getUniqueId() :
                            prefix.startsWith("name:") ? "name:" + island.getName() :
                            prefix.startsWith("id:") ? "id:" + island.getShortId() :
                            prefix.startsWith("#") ? "#" + island.getShortId() : Long.toString(island.getShortId());
                    if (value.toLowerCase(Locale.ENGLISH).startsWith(prefix.toLowerCase(Locale.ENGLISH))) result.add(value);
                }
                return result;
            }
            com.bgsoftware.superiorskyblock.api.island.Island target = parsed.selector == null ? null : plugin.getPvcIslandService().resolve(parsed.selector);
            if (parsed.selector != null && target == null) return Collections.emptyList();
            if (target != null && sender instanceof Player && !administrative) {
                String family = parsed.arguments.length == 0 ? "tp" : playerFamily(parsed.arguments[0]);
                if (!Arrays.asList("visit", "warp", "show", "rate", "ratings", "accept").contains(family) &&
                        !target.isMember(plugin.getPlayers().getSuperiorPlayer(sender))) return Collections.emptyList();
            }
            try (CommandPresentation presentation = CommandPresentation.open(advanced || administrative || !(sender instanceof Player));
                 com.bgsoftware.superiorskyblock.api.island.IslandExecutionScope scope = executionScope(sender, target, parsed.selector != null)) {
                return tabCompleteResolved(sender, label, parsed.arguments);
            }
        }

        private List<String> tabCompleteResolved(CommandSender sender, String label, String[] args) {
            if (args.length > 0) {
                SuperiorCommand command = playerCommandsMap.getCommand(args[0]);
                if (command != null) {
                    return CommandsHelper.shouldDisplayCommandForPlayer(command, sender) ?
                            command.tabComplete(plugin, sender, args) : Collections.emptyList();
                }
            }

            List<String> list = new LinkedList<>();

            for (SuperiorCommand subCommand : getSubCommands()) {
                if (CommandsHelper.shouldDisplayCommandForPlayer(subCommand, sender)) {
                    List<String> aliases = new LinkedList<>(subCommand.getAliases());
                    aliases.addAll(plugin.getSettings().getCommandAliases().getOrDefault(aliases.get(0).toLowerCase(Locale.ENGLISH), Collections.emptyList()));
                    for (String alias : aliases) {
                        if (args.length == 0 || alias.contains(args[0].toLowerCase(Locale.ENGLISH))) {
                            list.add(alias);
                        }
                    }
                }
            }

            return list;
        }

    }

    private static class DangerousCommandRequest {

        private final UUID playerUUID;
        private final String playerName;
        private final String executedCommand;
        private final String[] args;
        private final long creationTime;
        private final List<IslandTarget> targets;
        private final List<UUID> owners, bulkPlayers;
        private final UUID ratingAuthor, subject;

        DangerousCommandRequest(UUID playerUUID, String playerName, String executedCommand, String[] args, long creationTime,
                                List<IslandTarget> targets, List<UUID> owners, UUID ratingAuthor, UUID subject, List<UUID> bulkPlayers) {
            this.playerUUID = playerUUID;
            this.playerName = playerName;
            this.executedCommand = executedCommand;
            this.args = args;
            this.creationTime = creationTime;
            this.targets = Collections.unmodifiableList(new java.util.ArrayList<>(targets));
            this.owners = Collections.unmodifiableList(new java.util.ArrayList<>(owners));
            this.bulkPlayers = Collections.unmodifiableList(new java.util.ArrayList<>(bulkPlayers));
            this.ratingAuthor = ratingAuthor;
            this.subject = subject;
        }

    }

    private static final class IslandTarget {
        final UUID island, owner; final long revision, membershipRevision;
        IslandTarget(com.bgsoftware.superiorskyblock.api.island.Island target, long revision, long membershipRevision) {
            this.island = target.getUniqueId(); this.owner = target.getOwner() == null ? null : target.getOwner().getUniqueId(); this.revision = revision; this.membershipRevision = membershipRevision;
        }
    }

    @Nullable
    private Pair<Integer, String> getCooldown(SuperiorCommand command) {
        for (String alias : command.getAliases()) {
            Pair<Integer, String> commandCooldown = plugin.getSettings().getCommandsCooldown().get(alias);
            if (commandCooldown != null)
                return commandCooldown;
        }

        return null;
    }

}

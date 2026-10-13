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
        if (target != null) targets.add(target);
        else if (command instanceof IAdminIslandCommand && args.length > 2) {
            if ("*".equals(args[2])) targets.addAll(plugin.getGrid().getIslands());
            else {
                target = com.bgsoftware.superiorskyblock.commands.arguments.CommandArguments.getIsland(plugin, player, args[2]).getIsland();
                if (target == null) return; targets.add(target);
            }
        } else if (command instanceof IAdminPlayerCommand && requiresFrozenIsland(command, args) && args.length > 2) {
            SuperiorPlayer subject = plugin.getPlayers().getSuperiorPlayer(args[2]);
            if (subject == null || subject.getIslands().size() != 1) {
                Message.CUSTOM.send(player, "&cChoose an explicit --island target before requesting this operation.", true); return;
            }
            targets.add(subject.getIslands().get(0));
        } else if (command.getAliases().contains("purge")) targets.addAll(plugin.getGrid().getIslands());
        if (plugin.getPvcConfig().getSizeMode() == com.bgsoftware.superiorskyblock.pvc.PvcConfig.SizeMode.PERMISSION_AUTOMATIC &&
                Arrays.asList("setsize", "addsize", "clearsizeoverride").contains(commandLabel)) {
            Set<UUID> owners = new HashSet<>(); targets.removeIf(island -> !owners.add(island.getOwner().getUniqueId()));
        }
        List<IslandTarget> frozen = new java.util.ArrayList<>();
        for (com.bgsoftware.superiorskyblock.api.island.Island island : targets)
            frozen.add(new IslandTarget(island, plugin.getPvcIslandService().getRevision(island.getUniqueId())));

        // Overrides any previous request of the player for this command, and resets its expiry time.
        dangerousCommandsRequests.computeIfAbsent(commandLabel, l -> CacheBuilder.newBuilder()
                .expireAfterWrite(1, TimeUnit.MINUTES)
                .build()
        ).put(player.getUniqueId(), new DangerousCommandRequest(player.getUniqueId(), player.getName(),
                executedCommand, args.clone(), System.currentTimeMillis(), frozen));

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
                            snapshot.revision != plugin.getPvcIslandService().getRevision(snapshot.island)) { valid = false; break; }
                    resolved.add(island);
                }
                if (!valid || !CommandsHelper.hasCommandAccess(command, player) || System.currentTimeMillis() - request.creationTime > 60000L) {
                    Message.DANGEROUS_COMMAND_NO_ACCESS.send(sender, request.playerName, request.executedCommand); return;
                }
                Message.DANGEROUS_COMMAND_APPROVED.send(player, request.executedCommand);
                if (resolved.isEmpty()) resolved.add(null);
                for (com.bgsoftware.superiorskyblock.api.island.Island island : resolved) {
                    try (com.bgsoftware.superiorskyblock.pvc.island.IslandExecutionContext scope = com.bgsoftware.superiorskyblock.pvc.island.IslandExecutionContext.open(island)) {
                        command.execute(plugin, player, request.args);
                    } catch (Throwable error) { Log.error(error, "Cannot execute the approved island operation", request.executedCommand); }
                }
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
            if (!plugin.getPvcIslandService().isReady()) {
                Message.CUSTOM.send(sender, "&eIsland metadata is being initialized or recovered. Please wait.", true); return false;
            }
            List<String> stripped = new java.util.ArrayList<>();
            com.bgsoftware.superiorskyblock.api.island.Island explicit = null;
            for (int i = 0; i < args.length; i++) {
                if ("--island".equalsIgnoreCase(args[i])) {
                    if (explicit != null || ++i >= args.length) {
                        Message.CUSTOM.send(sender, "&cUse one --island <id|uuid|name> selector.", true); return false;
                    }
                    explicit = plugin.getPvcIslandService().resolve(args[i]);
                    if (explicit == null) { Message.INVALID_ISLAND.send(sender); return false; }
                } else stripped.add(args[i]);
            }
            if (explicit != null && sender instanceof Player) {
                SuperiorPlayer player = plugin.getPlayers().getSuperiorPlayer(sender);
                SuperiorCommand selectedCommand = stripped.isEmpty() ? null : playerCommandsMap.getCommand(stripped.get(0));
                String family = selectedCommand == null ? "tp" : selectedCommand.getAliases().get(0).toLowerCase(Locale.ENGLISH);
                boolean external = Arrays.asList("admin", "visit", "warp", "show", "rate", "ratings", "accept").contains(family);
                if (!external && !explicit.isMember(player)) { Message.INVALID_ISLAND.send(sender); return false; }
            }
            boolean explicitSelector = explicit != null || com.bgsoftware.superiorskyblock.api.island.IslandExecutionScope.isExplicitTarget();
            if (explicit == null && sender instanceof Player) {
                explicit = com.bgsoftware.superiorskyblock.api.island.IslandExecutionScope.current();
                if (explicit == null) explicit = plugin.getPlayers().getSuperiorPlayer(sender).getSelectedIsland();
            }
            try (com.bgsoftware.superiorskyblock.api.island.IslandExecutionScope ignored =
                         com.bgsoftware.superiorskyblock.api.island.IslandExecutionScope.open(explicit, explicitSelector)) {
                return executeResolved(sender, label, stripped.toArray(new String[0]));
            }
        }

        private boolean executeResolved(CommandSender sender, String label, String[] args) {
            java.util.Locale locale = PlayerLocales.getLocale(sender);

            String executedSubCommand;

            if (args.length > 0) {
                executedSubCommand = args[0];

                Log.debug(Debug.EXECUTE_COMMAND, sender.getName(), executedSubCommand);

                SuperiorCommand command = playerCommandsMap.getCommand(executedSubCommand);
                if (command != null) {
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
                    return false;
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
            List<String> stripped = new java.util.ArrayList<>();
            com.bgsoftware.superiorskyblock.api.island.Island target = null;
            boolean explicit = false;
            for (int i = 0; i < args.length; i++) {
                if ("--island".equalsIgnoreCase(args[i])) {
                    if (i + 1 >= args.length) return Collections.singletonList("--island <ID>");
                    if (i + 2 == args.length) {
                        SuperiorCommand command = stripped.isEmpty() ? null : playerCommandsMap.getCommand(stripped.get(0));
                        boolean administrator = command != null && "admin".equalsIgnoreCase(command.getAliases().get(0)) && CommandsHelper.hasCommandAccess(command, sender);
                        List<com.bgsoftware.superiorskyblock.api.island.Island> choices = administrator ? plugin.getGrid().getIslands() :
                                sender instanceof Player ? plugin.getPlayers().getSuperiorPlayer(sender).getIslands() : Collections.emptyList();
                        List<String> result = new java.util.ArrayList<>();
                        for (com.bgsoftware.superiorskyblock.api.island.Island island : choices)
                            if (plugin.getPvcIslandService().isAvailable(island.getUniqueId()) && Long.toString(island.getShortId()).startsWith(args[i + 1]))
                                result.add(Long.toString(island.getShortId()));
                        return result;
                    }
                    explicit = true; target = plugin.getPvcIslandService().resolve(args[++i]);
                    if (target == null) return Collections.emptyList();
                } else stripped.add(args[i]);
            }
            if (!explicit && sender instanceof Player) target = plugin.getPlayers().getSuperiorPlayer(sender).getSelectedIsland();
            try (com.bgsoftware.superiorskyblock.api.island.IslandExecutionScope scope =
                         com.bgsoftware.superiorskyblock.api.island.IslandExecutionScope.open(target, explicit)) {
                return tabCompleteResolved(sender, label, stripped.toArray(new String[0]));
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

        DangerousCommandRequest(UUID playerUUID, String playerName, String executedCommand, String[] args, long creationTime,
                                List<IslandTarget> targets) {
            this.playerUUID = playerUUID;
            this.playerName = playerName;
            this.executedCommand = executedCommand;
            this.args = args;
            this.creationTime = creationTime;
            this.targets = Collections.unmodifiableList(new java.util.ArrayList<>(targets));
        }

    }

    private static final class IslandTarget {
        final UUID island, owner; final long revision;
        IslandTarget(com.bgsoftware.superiorskyblock.api.island.Island target, long revision) {
            this.island = target.getUniqueId(); this.owner = target.getOwner() == null ? null : target.getOwner().getUniqueId(); this.revision = revision;
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

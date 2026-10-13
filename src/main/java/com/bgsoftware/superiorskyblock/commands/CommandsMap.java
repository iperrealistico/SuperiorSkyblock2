package com.bgsoftware.superiorskyblock.commands;

import com.bgsoftware.common.annotations.Nullable;
import com.bgsoftware.superiorskyblock.SuperiorSkyblockPlugin;
import com.bgsoftware.superiorskyblock.api.commands.SuperiorCommand;
import com.bgsoftware.superiorskyblock.core.SequentialListBuilder;
import com.bgsoftware.superiorskyblock.core.events.plugin.PluginEventType;
import com.bgsoftware.superiorskyblock.core.events.plugin.PluginEventsDispatcher;
import com.bgsoftware.superiorskyblock.core.events.plugin.PluginEventsFactory;
import com.google.common.base.Preconditions;

import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

public abstract class CommandsMap {

    private static volatile Set<String> DISABLED_COMMANDS_CACHE = Collections.emptySet();

    private static void onSettingsUpdate() {
        Set<String> disabled = new HashSet<>();
        SuperiorSkyblockPlugin plugin = SuperiorSkyblockPlugin.getPlugin();
        plugin.getSettings().getDisabledCommands().forEach(commandLabel -> {
            SuperiorCommand superiorCommand = plugin.getCommands().getCommand(commandLabel);
            if (superiorCommand != null && !(superiorCommand instanceof IAdminIslandCommand))
                disabled.add(superiorCommand.getAliases().get(0).toLowerCase(Locale.ENGLISH));
        });
        DISABLED_COMMANDS_CACHE = Collections.unmodifiableSet(disabled);
    }

    public static void registerListeners(PluginEventsDispatcher dispatcher) {
        dispatcher.registerCallback(PluginEventType.SETTINGS_UPDATE_EVENT, CommandsMap::onSettingsUpdate);
    }

    private final Map<String, SuperiorCommand> subCommands = new java.util.concurrent.ConcurrentSkipListMap<>();
    private final Map<String, List<SuperiorCommand>> aliasesToCommand = new java.util.concurrent.ConcurrentHashMap<>();

    protected final SuperiorSkyblockPlugin plugin;

    protected CommandsMap(SuperiorSkyblockPlugin plugin) {
        this.plugin = plugin;
    }

    public abstract void loadDefaultCommands();

    public void registerCommand(SuperiorCommand superiorCommand) {
        List<String> aliases = new LinkedList<>(superiorCommand.getAliases());
        String label = aliases.remove(0).toLowerCase(Locale.ENGLISH);
        aliases.addAll(plugin.getSettings().getCommandAliases().getOrDefault(label, Collections.emptyList()));

        removeCommand(label);
        subCommands.put(label, superiorCommand);

        for (String alias : aliases) {
            aliasesToCommand.computeIfAbsent(alias.toLowerCase(Locale.ENGLISH), a -> new java.util.concurrent.CopyOnWriteArrayList<>()).add(superiorCommand);
        }

        PluginEventsFactory.callCommandsUpdateEvent();
    }

    public void unregisterCommand(SuperiorCommand superiorCommand) {
        Preconditions.checkNotNull(superiorCommand, "superiorCommand parameter cannot be null.");

        List<String> aliases = new LinkedList<>(superiorCommand.getAliases());
        String label = aliases.remove(0).toLowerCase(Locale.ENGLISH);
        aliases.addAll(plugin.getSettings().getCommandAliases().getOrDefault(label, Collections.emptyList()));

        removeCommand(label);

        PluginEventsFactory.callCommandsUpdateEvent();
    }

    @Nullable
    public SuperiorCommand getCommand(String label) {
        label = label.toLowerCase(Locale.ENGLISH);
        SuperiorCommand superiorCommand = subCommands.get(label);
        if (superiorCommand != null && isCommandEnabled(superiorCommand))
            return superiorCommand;

        List<SuperiorCommand> commandAliases = aliasesToCommand.getOrDefault(label, Collections.emptyList());
        for (SuperiorCommand commandAlias : commandAliases) {
            if (isCommandEnabled(commandAlias)) {
                return commandAlias;
            }
        }

        return null;
    }

    public List<SuperiorCommand> getSubCommands(boolean includeDisabled) {
        SequentialListBuilder<SuperiorCommand> listBuilder = new SequentialListBuilder<>();

        if (!includeDisabled)
            listBuilder.filter(this::isCommandEnabled);

        return listBuilder.build(this.subCommands.values());
    }

    protected void clearCommands() {
        this.subCommands.clear();
        this.aliasesToCommand.clear();
    }

    private boolean isCommandEnabled(SuperiorCommand superiorCommand) {
        return superiorCommand instanceof IAdminIslandCommand ||
                !DISABLED_COMMANDS_CACHE.contains(superiorCommand.getAliases().get(0).toLowerCase(Locale.ENGLISH));
    }

    private void removeCommand(String label) {
        if (subCommands.remove(label) != null) {
            aliasesToCommand.values().forEach(commandsList ->
                    commandsList.removeIf(sC -> sC.getAliases().get(0).equalsIgnoreCase(label)));
        }
    }

}

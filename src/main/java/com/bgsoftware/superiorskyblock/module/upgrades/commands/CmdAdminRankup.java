package com.bgsoftware.superiorskyblock.module.upgrades.commands;

import com.bgsoftware.common.annotations.Nullable;
import com.bgsoftware.superiorskyblock.SuperiorSkyblockPlugin;
import com.bgsoftware.superiorskyblock.api.events.IslandUpgradeEvent;
import com.bgsoftware.superiorskyblock.api.island.Island;
import com.bgsoftware.superiorskyblock.api.island.IslandExecutionScope;
import com.bgsoftware.superiorskyblock.api.service.placeholders.PlaceholdersService;
import com.bgsoftware.superiorskyblock.api.upgrades.Upgrade;
import com.bgsoftware.superiorskyblock.api.upgrades.UpgradeLevel;
import com.bgsoftware.superiorskyblock.api.wrappers.SuperiorPlayer;
import com.bgsoftware.superiorskyblock.commands.CommandTabCompletes;
import com.bgsoftware.superiorskyblock.commands.IAdminIslandCommand;
import com.bgsoftware.superiorskyblock.commands.arguments.CommandArguments;
import com.bgsoftware.superiorskyblock.core.LazyReference;
import com.bgsoftware.superiorskyblock.core.events.args.PluginEventArgs;
import com.bgsoftware.superiorskyblock.core.events.plugin.PluginEvent;
import com.bgsoftware.superiorskyblock.core.events.plugin.PluginEventsFactory;
import com.bgsoftware.superiorskyblock.core.messages.Message;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.Collections;
import java.util.List;
import java.util.UUID;

public class CmdAdminRankup implements IAdminIslandCommand {

    private static final SuperiorSkyblockPlugin plugin = SuperiorSkyblockPlugin.getPlugin();
    private static final LazyReference<PlaceholdersService> placeholdersService = new LazyReference<PlaceholdersService>() {
        @Override
        protected PlaceholdersService create() {
            return plugin.getServices().getService(PlaceholdersService.class);
        }
    };

    @Override
    public List<String> getAliases() {
        return Collections.singletonList("rankup");
    }

    @Override
    public String getPermission() {
        return "superior.admin.rankup";
    }

    @Override
    public String getUsage(java.util.Locale locale) {
        return "admin rankup <" +
                Message.COMMAND_ARGUMENT_PLAYER_NAME.getMessage(locale) + "/" +
                Message.COMMAND_ARGUMENT_ISLAND_NAME.getMessage(locale) + "/" +
                Message.COMMAND_ARGUMENT_ALL_ISLANDS.getMessage(locale) + "> <" +
                Message.COMMAND_ARGUMENT_UPGRADE_NAME.getMessage(locale) + ">";
    }

    @Override
    public String getDescription(java.util.Locale locale) {
        return Message.COMMAND_DESCRIPTION_ADMIN_RANKUP.getMessage(locale);
    }

    @Override
    public int getMinArgs() {
        return 4;
    }

    @Override
    public int getMaxArgs() {
        return 4;
    }

    @Override
    public boolean canBeExecutedByConsole() {
        return true;
    }

    @Override
    public boolean supportMultipleIslands() {
        return true;
    }

    @Override
    public void execute(SuperiorSkyblockPlugin plugin, CommandSender sender, @Nullable SuperiorPlayer targetPlayer, List<Island> islands, String[] args) {
        Upgrade upgrade = CommandArguments.getUpgrade(plugin, sender, args[3]);

        if (upgrade == null)
            return;

        SuperiorPlayer playerSender = sender instanceof Player ? plugin.getPlayers().getSuperiorPlayer(sender) : null;

        int islandsChangedCount = 0;
        for (Island island : islands) {
            try (IslandExecutionScope scope = IslandExecutionScope.open(island, true)) {
                UpgradeLevel currentLevel = island.getUpgradeLevel(upgrade);
                UpgradeLevel nextLevel = upgrade.getUpgradeLevel(currentLevel.getLevel() + 1);

                PluginEvent<PluginEventArgs.IslandUpgrade> event = PluginEventsFactory.callIslandUpgradeEvent(
                        island, playerSender, upgrade, currentLevel, nextLevel, IslandUpgradeEvent.Cause.PLAYER_RANKUP);

                if (!event.isCancelled()) {
                    SuperiorPlayer owner = island.getOwner();
                    UUID ownerId = owner.getUniqueId();
                    long revision = plugin.getPvcIslandService().getRevision(island.getUniqueId());
                    ConfiguredUpgradeCommands.Result result = ConfiguredUpgradeCommands.execute(event.getArgs().commands,
                            command -> CmdRankup.isOwnUpgradeMutation(plugin, parseCommand(command, owner)),
                            command -> {
                                String parsed = parseCommand(command, owner);
                                try { return com.bgsoftware.superiorskyblock.commands.CapturedCommandDispatch.dispatch(
                                        plugin, Bukkit.getConsoleSender(), parsed, island); }
                                catch (Throwable error) {
                                    com.bgsoftware.superiorskyblock.core.logging.Log.error(error, "Cannot execute the configured island upgrade command", parsed);
                                    return false;
                                }
                            },
                            () -> plugin.getPvcIslandService().isAvailable(island.getUniqueId()) &&
                                    ownerId.equals(island.getOwner().getUniqueId()) &&
                                    revision == plugin.getPvcIslandService().getRevision(island.getUniqueId()),
                            () -> nextLevel != null && island.getUpgradeLevel(upgrade).getLevel() >= nextLevel.getLevel(),
                            () -> { });
                    if (result == ConfiguredUpgradeCommands.Result.SUCCESS) ++islandsChangedCount;
                    else {
                        com.bgsoftware.superiorskyblock.pvc.PvcMessages.send(sender, "Island &e" + island.getShortId() +
                                " &7could not complete this upgrade. Earlier configured actions may already have taken effect.");
                    }
                }
            }
        }

        if (islandsChangedCount == 0) return;
        if (islandsChangedCount < islands.size()) {
            com.bgsoftware.superiorskyblock.pvc.PvcMessages.send(sender, "Completed the configured upgrade for &e" + islandsChangedCount +
                    " &7of &e" + islands.size() + " &7islands.");
            return;
        }

        if (islands.size() > 1)
            Message.RANKUP_SUCCESS_ALL.send(sender, upgrade.getName());
        else if (targetPlayer == null)
            Message.RANKUP_SUCCESS_NAME.send(sender, upgrade.getName(), islands.get(0).getName());
        else
            Message.RANKUP_SUCCESS.send(sender, upgrade.getName(), targetPlayer.getName());
    }

    private static String parseCommand(String command, SuperiorPlayer owner) {
        return placeholdersService.get().parsePlaceholders(owner.asOfflinePlayer(), command
                .replace("%player%", owner.getName()).replace("%leader%", owner.getName()));
    }

    @Override
    public List<String> adminTabComplete(SuperiorSkyblockPlugin plugin, CommandSender sender, Island island, String[] args) {
        return args.length == 4 ? CommandTabCompletes.getUpgrades(plugin, args[3]) : Collections.emptyList();
    }

}

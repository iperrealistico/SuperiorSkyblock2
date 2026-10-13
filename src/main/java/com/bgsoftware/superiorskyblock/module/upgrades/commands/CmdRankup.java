package com.bgsoftware.superiorskyblock.module.upgrades.commands;

import com.bgsoftware.superiorskyblock.SuperiorSkyblockPlugin;
import com.bgsoftware.superiorskyblock.api.events.IslandUpgradeEvent;
import com.bgsoftware.superiorskyblock.api.island.Island;
import com.bgsoftware.superiorskyblock.api.island.IslandExecutionScope;
import com.bgsoftware.superiorskyblock.api.island.IslandPrivilege;
import com.bgsoftware.superiorskyblock.api.modules.ModuleLogger;
import com.bgsoftware.superiorskyblock.api.service.placeholders.PlaceholdersService;
import com.bgsoftware.superiorskyblock.api.upgrades.Upgrade;
import com.bgsoftware.superiorskyblock.api.upgrades.UpgradeLevel;
import com.bgsoftware.superiorskyblock.api.upgrades.cost.UpgradeCost;
import com.bgsoftware.superiorskyblock.api.world.GameSound;
import com.bgsoftware.superiorskyblock.api.wrappers.SuperiorPlayer;
import com.bgsoftware.superiorskyblock.commands.CommandTabCompletes;
import com.bgsoftware.superiorskyblock.commands.IPermissibleCommand;
import com.bgsoftware.superiorskyblock.commands.arguments.CommandArguments;
import com.bgsoftware.superiorskyblock.core.GameSoundImpl;
import com.bgsoftware.superiorskyblock.core.LazyReference;
import com.bgsoftware.superiorskyblock.core.events.args.PluginEventArgs;
import com.bgsoftware.superiorskyblock.core.events.plugin.PluginEvent;
import com.bgsoftware.superiorskyblock.core.events.plugin.PluginEventsFactory;
import com.bgsoftware.superiorskyblock.core.formatting.Formatters;
import com.bgsoftware.superiorskyblock.core.messages.Message;
import com.bgsoftware.superiorskyblock.island.IslandUtils;
import com.bgsoftware.superiorskyblock.island.privilege.IslandPrivileges;
import com.bgsoftware.superiorskyblock.island.upgrade.SUpgradeLevel;
import com.bgsoftware.superiorskyblock.module.BuiltinModules;
import org.bukkit.Bukkit;

import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

public class CmdRankup implements IPermissibleCommand {

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
        return "superior.island.rankup";
    }

    @Override
    public String getUsage(java.util.Locale locale) {
        return "rankup <" + Message.COMMAND_ARGUMENT_UPGRADE_NAME.getMessage(locale) + ">";
    }

    @Override
    public String getDescription(java.util.Locale locale) {
        return Message.COMMAND_DESCRIPTION_RANKUP.getMessage(locale);
    }

    @Override
    public int getMinArgs() {
        return 2;
    }

    @Override
    public int getMaxArgs() {
        return 2;
    }

    @Override
    public boolean canBeExecutedByConsole() {
        return false;
    }

    @Override
    public IslandPrivilege getPrivilege() {
        return IslandPrivileges.RANKUP;
    }

    @Override
    public Message getPermissionLackMessage() {
        return Message.NO_RANKUP_PERMISSION;
    }

    @Override
    public void execute(SuperiorSkyblockPlugin plugin, SuperiorPlayer superiorPlayer, Island island, String[] args) {
        Upgrade upgrade = CommandArguments.getUpgrade(plugin, superiorPlayer, args[1]);

        if (upgrade == null)
            return;

        UpgradeLevel currentLevel = island.getUpgradeLevel(upgrade);
        UpgradeLevel nextLevel = upgrade.getUpgradeLevel(currentLevel.getLevel() + 1);

        String permission = nextLevel == null ? "" : nextLevel.getPermission();

        if (!permission.isEmpty() && !superiorPlayer.hasPermission(permission)) {
            Message.NO_UPGRADE_PERMISSION.send(superiorPlayer);
            return;
        }

        boolean hasNextLevel;

        if (island.hasActiveUpgradeCooldown()) {
            long timeNow = System.currentTimeMillis();
            long lastUpgradeTime = island.getLastTimeUpgrade();
            long duration = lastUpgradeTime + plugin.getSettings().getUpgradeCooldown() - timeNow;
            Message.UPGRADE_COOLDOWN_FORMAT.send(superiorPlayer, Formatters.TIME_FORMATTER.format(
                    Duration.ofMillis(duration), superiorPlayer.getUserLocale()));
            hasNextLevel = false;
        } else {
            String requiredCheckFailure = nextLevel == null ? "" : nextLevel.checkRequirements(superiorPlayer);

            if (!requiredCheckFailure.isEmpty()) {
                Message.CUSTOM.send(superiorPlayer, requiredCheckFailure, false);
                hasNextLevel = false;
            } else {
                PluginEvent<PluginEventArgs.IslandUpgrade> event = PluginEventsFactory.callIslandUpgradeEvent(
                        island, superiorPlayer, upgrade, currentLevel, nextLevel, IslandUpgradeEvent.Cause.PLAYER_RANKUP);

                List<UpgradeCost> upgradeCosts = event.getArgs().upgradeCosts;

                if (event.isCancelled()) {
                    hasNextLevel = false;

                } else if (!IslandUtils.hasEnoughBalance(upgradeCosts, superiorPlayer)) {
                    Message.NOT_ENOUGH_MONEY_TO_UPGRADE.send(superiorPlayer);
                    hasNextLevel = false;

                } else {
                    UUID owner = island.getOwner().getUniqueId();
                    long revision = plugin.getPvcIslandService().getRevision(island.getUniqueId());
                    ConfiguredUpgradeCommands.Result result;
                    try (IslandExecutionScope scope = IslandExecutionScope.open(island, true)) {
                        result = ConfiguredUpgradeCommands.execute(event.getArgs().commands,
                                command -> isOwnUpgradeMutation(plugin, parseCommand(command, superiorPlayer, island)),
                                command -> {
                                    String parsed = parseCommand(command, superiorPlayer, island);
                                    try { return com.bgsoftware.superiorskyblock.commands.CapturedCommandDispatch.dispatch(
                                            plugin, Bukkit.getConsoleSender(), parsed, island); }
                                    catch (Throwable error) {
                                        ModuleLogger logger = (ModuleLogger) BuiltinModules.UPGRADES.getLogger();
                                        logger.e("An unexpected error occurred while executing command:\n" + parsed, error);
                                        return false;
                                    }
                                },
                                () -> plugin.getPvcIslandService().isAvailable(island.getUniqueId()) &&
                                        owner.equals(island.getOwner().getUniqueId()) &&
                                        revision == plugin.getPvcIslandService().getRevision(island.getUniqueId()) &&
                                        island.hasPermission(superiorPlayer, IslandPrivileges.RANKUP),
                                () -> nextLevel != null && island.getUpgradeLevel(upgrade).getLevel() >= nextLevel.getLevel(),
                                () -> upgradeCosts.forEach(upgradeCost -> upgradeCost.withdrawCost(superiorPlayer)));
                    }
                    hasNextLevel = result == ConfiguredUpgradeCommands.Result.SUCCESS;
                    if (!hasNextLevel)
                        Message.CUSTOM.send(superiorPlayer, "&cThe upgrade could not be completed. Earlier configured actions may already have taken effect.", true);
                }
            }
        }

        SUpgradeLevel.ItemData itemData = ((SUpgradeLevel) currentLevel).getItemData();
        if (itemData != null) {
            GameSound sound = hasNextLevel ? itemData.hasNextLevelSound : itemData.noNextLevelSound;
            if (sound != null)
                superiorPlayer.runIfOnline(player -> GameSoundImpl.playSound(player, sound));
        }
    }

    private static String parseCommand(String command, SuperiorPlayer superiorPlayer, Island island) {
        return placeholdersService.get().parsePlaceholders(superiorPlayer.asOfflinePlayer(), command
                .replace("%player%", superiorPlayer.getName())
                .replace("%leader%", island.getOwner().getName()));
    }

    static boolean isOwnUpgradeMutation(SuperiorSkyblockPlugin plugin, String command) {
        String[] words = command.trim().split("\\s+");
        if (words.length < 3) return false;
        String label = words[0].toLowerCase(Locale.ENGLISH);
        if (label.startsWith("/")) label = label.substring(1);
        int namespace = label.indexOf(':');
        if (namespace >= 0) {
            if (!label.substring(0, namespace).equals(plugin.getDescription().getName().toLowerCase(Locale.ENGLISH))) return false;
            label = label.substring(namespace + 1);
        }
        boolean ownLabel = false;
        for (String alias : plugin.getSettings().getIslandCommand().split(","))
            if (label.equalsIgnoreCase(alias.trim())) { ownLabel = true; break; }
        return ownLabel && plugin.getCommands().getCommand(words[1]) instanceof com.bgsoftware.superiorskyblock.commands.player.CmdAdmin &&
                plugin.getCommands().getAdminCommand(words[2]) instanceof CmdAdminSetUpgrade;
    }

    @Override
    public List<String> tabComplete(SuperiorSkyblockPlugin plugin, SuperiorPlayer superiorPlayer, Island island, String[] args) {
        return args.length == 2 ? CommandTabCompletes.getUpgrades(plugin, args[1]) : Collections.emptyList();
    }

}

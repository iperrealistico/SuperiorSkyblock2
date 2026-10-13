package com.bgsoftware.superiorskyblock.commands.player;

import com.bgsoftware.superiorskyblock.SuperiorSkyblockPlugin;
import com.bgsoftware.superiorskyblock.api.island.Island;
import com.bgsoftware.superiorskyblock.api.island.IslandExecutionScope;
import com.bgsoftware.superiorskyblock.api.island.warps.IslandWarp;
import com.bgsoftware.superiorskyblock.api.wrappers.SuperiorPlayer;
import com.bgsoftware.superiorskyblock.commands.CommandTabCompletes;
import com.bgsoftware.superiorskyblock.commands.ISuperiorCommand;
import com.bgsoftware.superiorskyblock.commands.arguments.CommandArguments;
import com.bgsoftware.superiorskyblock.core.events.plugin.PluginEventsFactory;
import com.bgsoftware.superiorskyblock.core.menu.view.MenuViewWrapper;
import com.bgsoftware.superiorskyblock.core.messages.Message;
import org.bukkit.command.CommandSender;

import java.util.Collections;
import java.util.LinkedList;
import java.util.List;
import java.util.function.Function;

public class CmdWarp implements ISuperiorCommand {

    @Override
    public List<String> getAliases() {
        return Collections.singletonList("warp");
    }

    @Override
    public String getPermission() {
        return "superior.island.warp";
    }

    @Override
    public String getUsage(java.util.Locale locale) {
        return "warp [" +
                Message.COMMAND_ARGUMENT_PLAYER_NAME.getMessage(locale) + "/" +
                Message.COMMAND_ARGUMENT_ISLAND_NAME.getMessage(locale) + "] [" +
                Message.COMMAND_ARGUMENT_WARP_NAME.getMessage(locale) + "]";
    }

    @Override
    public String getDescription(java.util.Locale locale) {
        return Message.COMMAND_DESCRIPTION_WARP.getMessage(locale);
    }

    @Override
    public int getMinArgs() {
        return 1;
    }

    @Override
    public int getMaxArgs() {
        return 3;
    }

    @Override
    public boolean canBeExecutedByConsole() {
        return false;
    }

    @Override
    public void execute(SuperiorSkyblockPlugin plugin, CommandSender sender, String[] args) {
        SuperiorPlayer superiorPlayer = plugin.getPlayers().getSuperiorPlayer(sender);

        Island explicitTarget = IslandExecutionScope.isExplicitTarget() ? IslandExecutionScope.current() : null;
        Island senderIsland = explicitTarget == null && args.length == 1 ?
                CommandArguments.getSenderIsland(plugin, sender).getIsland() : superiorPlayer.getIsland();
        WarpRequest request = resolveRequest(args, explicitTarget, senderIsland,
                selector -> CommandArguments.getIsland(plugin, sender, selector).getIsland());
        Island targetIsland = request.island;
        String targetWarpName = request.warpName;

        if (targetIsland == null)
            return;

        if (!plugin.getPvcIslandService().isAvailable(targetIsland.getUniqueId())) {
            com.bgsoftware.superiorskyblock.pvc.PvcMessages.send(sender, "This island has an operation in progress. Please wait.");
            return;
        }

        if (targetWarpName == null) {
            plugin.getMenus().openWarpCategories(superiorPlayer, MenuViewWrapper.fromView(superiorPlayer.getOpenedView()), targetIsland);
            return;
        }

        IslandWarp islandWarp = targetWarpName == null ? null : targetIsland.getWarp(targetWarpName);

        if (islandWarp == null) {
            Message.INVALID_WARP.send(superiorPlayer, targetWarpName);
            return;
        }

        if (!targetIsland.isMember(superiorPlayer) && islandWarp.hasPrivateFlag()) {
            Message.INVALID_WARP.send(superiorPlayer, targetWarpName);
            return;
        }

        if (PluginEventsFactory.callIslandWarpTeleportEvent(targetIsland, superiorPlayer, islandWarp))
            targetIsland.warpPlayer(superiorPlayer, targetWarpName);
    }

    @Override
    public List<String> tabComplete(SuperiorSkyblockPlugin plugin, CommandSender sender, String[] args) {
        SuperiorPlayer superiorPlayer = plugin.getPlayers().getSuperiorPlayer(sender);
        Island playerIsland = superiorPlayer.getIsland();
        Island explicitTarget = IslandExecutionScope.isExplicitTarget() ? IslandExecutionScope.current() : null;

        switch (args.length) {
            case 2: {
                if (explicitTarget != null)
                    return completeWarps(explicitTarget, superiorPlayer, args[1]);
                List<String> tabCompletes = new LinkedList<>(CommandTabCompletes.getPlayerIslandsExceptSender(plugin, sender, args[1], true,
                        (player, island) -> island.getIslandWarps().values().stream().anyMatch(islandWarp ->
                                island.isMember(superiorPlayer) || !islandWarp.hasPrivateFlag())));

                if (playerIsland != null) {
                    for (String warpName : playerIsland.getIslandWarps().keySet()) {
                        if (warpName.startsWith(args[1]))
                            tabCompletes.add(warpName);
                    }
                }

                return tabCompletes.isEmpty() ? Collections.emptyList() : tabCompletes;
            }
            case 3: {
                Island targetIsland = explicitTarget != null ? explicitTarget : CommandArguments.findIsland(plugin, sender, args[1]);
                return completeWarps(targetIsland, superiorPlayer, args[2]);
            }
        }

        return Collections.emptyList();
    }

    static WarpRequest resolveRequest(String[] args, Island explicitTarget, Island senderIsland,
                                      Function<String, Island> resolver) {
        if (args.length == 1)
            return new WarpRequest(explicitTarget != null ? explicitTarget : senderIsland, null);
        if (args.length == 2) {
            if (explicitTarget != null)
                return new WarpRequest(explicitTarget, args[1]);
            // Keep the original shorthand: an existing warp on the sender's island wins over an island name.
            if (senderIsland != null && senderIsland.getWarp(args[1]) != null)
                return new WarpRequest(senderIsland, args[1]);
            return new WarpRequest(resolver.apply(args[1]), null);
        }
        return new WarpRequest(explicitTarget != null ? explicitTarget : resolver.apply(args[1]), args[2]);
    }

    static List<String> completeWarps(Island target, SuperiorPlayer viewer, String argument) {
        if (target == null) return Collections.emptyList();
        boolean member = target.isMember(viewer);
        List<String> result = new LinkedList<>();
        target.getIslandWarps().forEach((name, warp) -> {
            if (name.startsWith(argument) && (member || !warp.hasPrivateFlag())) result.add(name);
        });
        return result;
    }

    static final class WarpRequest {
        final Island island;
        final String warpName;
        WarpRequest(Island island, String warpName) { this.island = island; this.warpName = warpName; }
    }

}

package com.bgsoftware.superiorskyblock.commands.player;

import com.bgsoftware.superiorskyblock.SuperiorSkyblockPlugin;
import com.bgsoftware.superiorskyblock.api.island.Island;
import com.bgsoftware.superiorskyblock.api.wrappers.SuperiorPlayer;
import com.bgsoftware.superiorskyblock.commands.ISuperiorCommand;
import com.bgsoftware.superiorskyblock.core.messages.Message;
import org.bukkit.command.CommandSender;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

public final class CmdIslandSelect implements ISuperiorCommand {
    @Override public List<String> getAliases() { return java.util.Arrays.asList("select", "use"); }
    @Override public String getPermission() { return "superior.island.select"; }
    @Override public String getUsage(Locale locale) { return "select <island>"; }
    @Override public String getDescription(Locale locale) { return "Select your island for subsequent commands"; }
    @Override public int getMinArgs() { return 2; }
    @Override public int getMaxArgs() { return 2; }
    @Override public boolean canBeExecutedByConsole() { return false; }
    @Override public void execute(SuperiorSkyblockPlugin plugin, CommandSender sender, String[] args) {
        SuperiorPlayer player = plugin.getPlayers().getSuperiorPlayer(sender);
        Island island = plugin.getPvcIslandService().resolve(args[1]);
        if (island == null || !plugin.getPvcIslandService().isAvailable(island.getUniqueId()) || !island.isMember(player)) { Message.INVALID_ISLAND.send(sender); return; }
        player.selectIsland(island);
        com.bgsoftware.superiorskyblock.pvc.PvcMessages.send(sender, "&6Selected island &e#" + island.getShortId() + " &8| &e" + island.getName());
    }
    @Override public List<String> tabComplete(SuperiorSkyblockPlugin plugin, CommandSender sender, String[] args) {
        if (args.length != 2) return Collections.emptyList();
        List<String> result = new ArrayList<>();
        for (Island island : plugin.getPlayers().getSuperiorPlayer(sender).getIslands()) {
            String id = String.valueOf(island.getShortId()); if (id.startsWith(args[1])) result.add(id);
        }
        return result;
    }
}

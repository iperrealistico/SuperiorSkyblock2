package com.bgsoftware.superiorskyblock.commands.player;

import com.bgsoftware.superiorskyblock.SuperiorSkyblockPlugin;
import com.bgsoftware.superiorskyblock.api.island.Island;
import com.bgsoftware.superiorskyblock.api.wrappers.SuperiorPlayer;
import com.bgsoftware.superiorskyblock.commands.ISuperiorCommand;
import com.bgsoftware.superiorskyblock.core.messages.Message;
import org.bukkit.command.CommandSender;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

public final class CmdIslandsList implements ISuperiorCommand {
    @Override public List<String> getAliases() { return java.util.Arrays.asList("list", "islands"); }
    @Override public String getPermission() { return "superior.island.list"; }
    @Override public String getUsage(Locale locale) { return "list [page]"; }
    @Override public String getDescription(Locale locale) { return "List your owned and team islands"; }
    @Override public int getMinArgs() { return 1; }
    @Override public int getMaxArgs() { return 2; }
    @Override public boolean canBeExecutedByConsole() { return false; }
    @Override public void execute(SuperiorSkyblockPlugin plugin, CommandSender sender, String[] args) {
        SuperiorPlayer player = plugin.getPlayers().getSuperiorPlayer(sender);
        List<Island> islands = player.getIslands(); int page = 1;
        try { if (args.length == 2) page = Integer.parseInt(args[1]); }
        catch (NumberFormatException error) { com.bgsoftware.superiorskyblock.pvc.PvcMessages.send(sender, "Invalid page."); return; }
        int pages = Math.max(1, (islands.size() + 9) / 10);
        if (page < 1 || page > pages) { com.bgsoftware.superiorskyblock.pvc.PvcMessages.send(sender, "Page must be between &e1 &7and &e" + pages + "&7."); return; }
        com.bgsoftware.superiorskyblock.pvc.PvcMessages.send(sender, "&6Your islands &8(&e" + page + "&8/&e" + pages + "&8)");
        for (int i = (page - 1) * 10; i < Math.min(page * 10, islands.size()); i++) {
            Island island = islands.get(i);
            com.bgsoftware.superiorskyblock.pvc.PvcMessages.send(sender, "&e#" + island.getShortId() + " &8| &e" + island.getName() + " &8[&7" +
                    (island.getOwner().equals(player) ? "Owner" : player.getPlayerRole(island).getName()) + "&8] &6/is select &e" + island.getShortId());
        }
    }
    @Override public List<String> tabComplete(SuperiorSkyblockPlugin plugin, CommandSender sender, String[] args) { return Collections.emptyList(); }
}

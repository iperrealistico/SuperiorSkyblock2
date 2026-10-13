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
        catch (NumberFormatException error) { Message.CUSTOM.send(sender, "&cInvalid page.", true); return; }
        int pages = Math.max(1, (islands.size() + 9) / 10);
        if (page < 1 || page > pages) { Message.CUSTOM.send(sender, "&cPage must be between 1 and " + pages + ".", true); return; }
        Message.CUSTOM.send(sender, "&6Your islands &7(" + page + "/" + pages + ")", true);
        for (int i = (page - 1) * 10; i < Math.min(page * 10, islands.size()); i++) {
            Island island = islands.get(i);
            Message.CUSTOM.send(sender, "&e#" + island.getShortId() + " &7" + island.getName() + " &8[" +
                    (island.getOwner().equals(player) ? "Owner" : player.getPlayerRole(island).getName()) + "] &7/is select " + island.getShortId(), true);
        }
    }
    @Override public List<String> tabComplete(SuperiorSkyblockPlugin plugin, CommandSender sender, String[] args) { return Collections.emptyList(); }
}

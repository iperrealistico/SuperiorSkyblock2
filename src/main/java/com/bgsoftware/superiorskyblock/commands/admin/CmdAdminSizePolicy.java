package com.bgsoftware.superiorskyblock.commands.admin;

import com.bgsoftware.superiorskyblock.SuperiorSkyblockPlugin;
import com.bgsoftware.superiorskyblock.api.wrappers.SuperiorPlayer;
import com.bgsoftware.superiorskyblock.commands.ISuperiorCommand;
import org.bukkit.command.CommandSender;

import java.util.Collections;
import java.util.List;

public final class CmdAdminSizePolicy implements ISuperiorCommand {
    @Override public List<String> getAliases() { return Collections.singletonList("sizepolicy"); }
    @Override public String getPermission() { return "superior.admin.sizepolicy"; }
    @Override public String getUsage(java.util.Locale locale) { return "admin sizepolicy <owner>"; }
    @Override public String getDescription(java.util.Locale locale) { return "Inspect and refresh an owner's island quota, radius, overrides and provider freshness."; }
    @Override public int getMinArgs() { return 3; }
    @Override public int getMaxArgs() { return 3; }
    @Override public boolean canBeExecutedByConsole() { return true; }
    @Override public List<String> tabComplete(SuperiorSkyblockPlugin plugin, CommandSender sender, String[] args) {
        return Collections.emptyList();
    }
    @Override public void execute(SuperiorSkyblockPlugin plugin, CommandSender sender, String[] args) {
        SuperiorPlayer owner = PvcSizeCommands.owner(plugin, sender, args[2]);
        if (owner == null) return;
        plugin.getPvcEntitlements().refresh(owner.getUniqueId()).whenComplete((snapshot, error) ->
                PvcSizeCommands.feedback(plugin, sender, (error == null ? "" : "Provider unavailable; last-known policy: ") +
                        "&6" + plugin.getPvcEntitlements().describe(owner.getUniqueId())
                                .replace(", ", " &8| &6").replace("=", " &8: &e")));
    }
}

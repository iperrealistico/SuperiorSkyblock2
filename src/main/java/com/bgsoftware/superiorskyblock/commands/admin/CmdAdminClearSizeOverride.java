package com.bgsoftware.superiorskyblock.commands.admin;

import com.bgsoftware.superiorskyblock.SuperiorSkyblockPlugin;
import com.bgsoftware.superiorskyblock.api.island.Island;
import com.bgsoftware.superiorskyblock.api.wrappers.SuperiorPlayer;
import com.bgsoftware.superiorskyblock.commands.ISuperiorCommand;
import com.bgsoftware.superiorskyblock.island.SIsland;
import org.bukkit.command.CommandSender;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;

public final class CmdAdminClearSizeOverride implements ISuperiorCommand {
    @Override public List<String> getAliases() { return Collections.singletonList("clearsizeoverride"); }
    @Override public String getPermission() { return "superior.admin.clearsizeoverride"; }
    @Override public String getUsage(java.util.Locale locale) { return "admin clearsizeoverride <owner> [legacy]"; }
    @Override public String getDescription(java.util.Locale locale) { return "Clear an owner-wide size override; optionally clear its legacy island exceptions."; }
    @Override public int getMinArgs() { return 3; }
    @Override public int getMaxArgs() { return 4; }
    @Override public boolean canBeExecutedByConsole() { return true; }
    @Override public List<String> tabComplete(SuperiorSkyblockPlugin plugin, CommandSender sender, String[] args) {
        return args.length == 4 && "legacy".startsWith(args[3].toLowerCase(java.util.Locale.ROOT)) ?
                Collections.singletonList("legacy") : Collections.emptyList();
    }
    @Override public void execute(SuperiorSkyblockPlugin plugin, CommandSender sender, String[] args) {
        if (args.length == 4 && !args[3].equalsIgnoreCase("legacy")) {
            PvcSizeCommands.feedback(plugin, sender, "Use [legacy] only when intentionally clearing per-island fixed-size exceptions.");
            return;
        }
        SuperiorPlayer owner = PvcSizeCommands.owner(plugin, sender, args[2]);
        if (owner == null) return;
        boolean legacy = args.length == 4;
        plugin.getPvcEntitlements().clearOwnerOverride(owner.getUniqueId()).whenComplete((ignored, error) -> {
            if (error != null) { PvcSizeCommands.feedback(plugin, sender, "Could not clear the durable owner override."); return; }
            CompletableFuture<Void> clears = CompletableFuture.completedFuture(null);
            if (legacy) {
                for (Island island : plugin.getGrid().getOwnedIslands(owner.getUniqueId())) {
                    clears = clears.thenCompose(unused -> plugin.getPvcIslandService().acknowledge(island, () -> {
                        synchronized (island) {
                            if (!(island instanceof SIsland) || island.getOwner() == null ||
                                    !island.getOwner().getUniqueId().equals(owner.getUniqueId()) ||
                                    !plugin.getPvcIslandService().isAvailable(island.getUniqueId()))
                                throw new IllegalStateException("The legacy island target changed or is busy; retry");
                            ((SIsland) island).clearLegacySizeOverride();
                        }
                    }));
                }
            }
            clears.whenComplete((unused, legacyError) -> {
                if (legacyError != null) {
                    PvcSizeCommands.feedback(plugin, sender, "Owner override cleared, but legacy exceptions could not all be committed. Retry [legacy] and inspect /is admin sizepolicy.");
                    return;
                }
                PvcSizeCommands.feedback(plugin, sender, "Owner override cleared. " +
                        (legacy ? "Legacy size exceptions committed; the configured size policy resumes." :
                                "Any legacy per-island exceptions remain; inspect /is admin sizepolicy."));
            });
        });
    }
}

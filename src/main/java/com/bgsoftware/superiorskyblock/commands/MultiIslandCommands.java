package com.bgsoftware.superiorskyblock.commands;

import com.bgsoftware.superiorskyblock.SuperiorSkyblockPlugin;
import com.bgsoftware.superiorskyblock.api.commands.SuperiorCommand;
import com.bgsoftware.superiorskyblock.api.island.Island;
import com.bgsoftware.superiorskyblock.api.wrappers.SuperiorPlayer;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import java.util.List;

/** Multiple-island presentation requires both real alternatives and a current quota grant. */
public final class MultiIslandCommands {
    private MultiIslandCommands() { }
    public static boolean unlocked(SuperiorSkyblockPlugin plugin, SuperiorPlayer player) {
        if (player == null || player.getIslands().size() <= 1) return false;
        plugin.getPvcEntitlements().getSnapshot(player.getUniqueId());
        try { return enabled(player.getIslands().size(), plugin.getPvcEntitlements().getVerifiedOwnershipLimit(player.getUniqueId())); }
        catch (IllegalStateException unavailable) {
            plugin.getPvcEntitlements().refreshOwnershipLimit(player.getUniqueId()).exceptionally(error -> null);
            return false;
        }
    }
    static boolean enabled(int islands, int limit) { return islands > 1 && limit > 1; }
    public static boolean unlocked(SuperiorSkyblockPlugin plugin, CommandSender sender) {
        return sender instanceof Player && unlocked(plugin, plugin.getPlayers().getSuperiorPlayer(sender));
    }
    public static boolean personalTool(SuperiorCommand command) {
        return command instanceof com.bgsoftware.superiorskyblock.commands.player.CmdIslandsList ||
                command instanceof com.bgsoftware.superiorskyblock.commands.player.CmdIslandSelect;
    }
    public static Island implicitIsland(SuperiorSkyblockPlugin plugin, SuperiorPlayer player) {
        if (unlocked(plugin, player)) return player.getSelectedIsland();
        return legacyIsland(player.getOwnedIslands(), player.getIslands(), player.getSelectedIsland());
    }
    static Island legacyIsland(List<Island> owned, List<Island> memberships, Island selected) {
        if (owned.size() == 1) return owned.get(0);
        if (!owned.isEmpty()) return owned.contains(selected) ? selected : owned.get(0);
        return memberships.contains(selected) ? selected : memberships.isEmpty() ? null : memberships.get(0);
    }
}

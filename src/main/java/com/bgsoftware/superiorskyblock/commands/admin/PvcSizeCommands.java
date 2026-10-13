package com.bgsoftware.superiorskyblock.commands.admin;

import com.bgsoftware.superiorskyblock.SuperiorSkyblockPlugin;
import com.bgsoftware.superiorskyblock.api.island.Island;
import com.bgsoftware.superiorskyblock.api.wrappers.SuperiorPlayer;
import com.bgsoftware.superiorskyblock.core.threads.BukkitExecutor;
import com.bgsoftware.superiorskyblock.pvc.entitlement.PvcEntitlementService;
import com.bgsoftware.superiorskyblock.pvc.PvcMessages;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

public final class PvcSizeCommands {
    private PvcSizeCommands() { }

    static void feedback(SuperiorSkyblockPlugin plugin, CommandSender sender, String text) {
        Runnable deliver = () -> PvcMessages.send(sender, text);
        if (sender instanceof Player) BukkitExecutor.entity((Player) sender, deliver);
        else BukkitExecutor.global(deliver);
    }

    static SuperiorPlayer owner(SuperiorSkyblockPlugin plugin, CommandSender sender, String argument) {
        Island captured = com.bgsoftware.superiorskyblock.pvc.island.IslandExecutionContext.current();
        if (captured != null && com.bgsoftware.superiorskyblock.api.island.IslandExecutionScope.isExplicitTarget()) return captured.getOwner();
        String name = argument.startsWith("owner:") ? argument.substring("owner:".length()) : argument;
        SuperiorPlayer player = plugin.getPlayers().getSuperiorPlayer(name);
        if (player != null) return player;
        Island island = plugin.getPvcIslandService().resolve(name);
        if (island != null && island.getOwner() != null) return island.getOwner();
        feedback(plugin, sender, "Unknown owner. Use a known player nickname or an island name.");
        return null;
    }

    static void set(SuperiorSkyblockPlugin plugin, CommandSender sender, String argument, int number, boolean additive) {
        Set<UUID> owners = new LinkedHashSet<>();
        Island captured = com.bgsoftware.superiorskyblock.pvc.island.IslandExecutionContext.current();
        if (captured != null && captured.getOwner() != null &&
                com.bgsoftware.superiorskyblock.api.island.IslandExecutionScope.isExplicitTarget()) {
            owners.add(captured.getOwner().getUniqueId());
        } else if (argument.equals("*")) {
            for (Island island : plugin.getGrid().getIslands())
                if (island.getOwner() != null) owners.add(island.getOwner().getUniqueId());
        } else {
            SuperiorPlayer owner = owner(plugin, sender, argument);
            if (owner == null) return;
            owners.add(owner.getUniqueId());
        }
        setFrozenOwners(plugin, sender, owners, number, additive);
    }

    /** A confirmation keeps its captured owners and the original bounded bulk write chain. */
    public static void setFrozenOwners(SuperiorSkyblockPlugin plugin, CommandSender sender,
                                      Iterable<UUID> capturedOwners, int number, boolean additive) {
        PvcEntitlementService service = plugin.getPvcEntitlements();
        Set<UUID> owners = new LinkedHashSet<>();
        for (UUID owner : capturedOwners) owners.add(owner);
        if (owners.isEmpty()) { feedback(plugin, sender, "No island owners matched."); return; }
        // Chaining admits one durable write at a time rather than flooding storage with bulk futures.
        CompletableFuture<Void> work = CompletableFuture.completedFuture(null);
        for (UUID owner : owners) {
            work = work.thenCompose(ignored -> {
                if (!additive) return service.setOwnerOverride(owner, number);
                return service.addOwnerOverride(owner, number);
            });
        }
        work.whenComplete((ignored, error) -> feedback(plugin, sender, error == null ?
                "Owner-wide size override saved for &e" + owners.size() + " &7owner(s), including future islands." :
                "Size update failed or a bulk update stopped. Inspect &6/is admin sizepolicy &7for the affected owner(s)."));
    }
}

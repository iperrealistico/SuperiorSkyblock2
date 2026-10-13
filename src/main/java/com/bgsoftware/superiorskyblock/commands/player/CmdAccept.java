package com.bgsoftware.superiorskyblock.commands.player;

import com.bgsoftware.superiorskyblock.SuperiorSkyblockPlugin;
import com.bgsoftware.superiorskyblock.api.events.IslandJoinEvent;
import com.bgsoftware.superiorskyblock.api.island.Island;
import com.bgsoftware.superiorskyblock.api.wrappers.SuperiorPlayer;
import com.bgsoftware.superiorskyblock.commands.CommandTabCompletes;
import com.bgsoftware.superiorskyblock.commands.ISuperiorCommand;
import com.bgsoftware.superiorskyblock.commands.MultiIslandCommands;
import com.bgsoftware.superiorskyblock.core.events.plugin.PluginEventsFactory;
import com.bgsoftware.superiorskyblock.core.messages.Message;
import com.bgsoftware.superiorskyblock.island.IslandUtils;
import com.bgsoftware.superiorskyblock.island.role.SPlayerRole;
import org.bukkit.command.CommandSender;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;

public class CmdAccept implements ISuperiorCommand {

    @Override
    public List<String> getAliases() {
        return Arrays.asList("accept", "join");
    }

    @Override
    public String getPermission() {
        return "superior.island.accept";
    }

    @Override
    public String getUsage(java.util.Locale locale) {
        return "accept [" +
                Message.COMMAND_ARGUMENT_PLAYER_NAME.getMessage(locale) + "/" +
                Message.COMMAND_ARGUMENT_ISLAND_NAME.getMessage(locale) + "]";
    }

    @Override
    public String getDescription(java.util.Locale locale) {
        return Message.COMMAND_DESCRIPTION_ACCEPT.getMessage(locale);
    }

    @Override
    public int getMinArgs() {
        return 1;
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
    public void execute(SuperiorSkyblockPlugin plugin, CommandSender sender, String[] args) {
        SuperiorPlayer superiorPlayer = plugin.getPlayers().getSuperiorPlayer(sender);

        SuperiorPlayer targetPlayer;
        Island island;
        boolean precise = MultiIslandCommands.unlocked(plugin, superiorPlayer);

        if (com.bgsoftware.superiorskyblock.api.island.IslandExecutionScope.isExplicitTarget()) {
            island = com.bgsoftware.superiorskyblock.api.island.IslandExecutionScope.current();
            targetPlayer = island == null ? null : island.getOwner();
        } else if (args.length == 1) {
            InviteSelection selection = selectInvitation(superiorPlayer.getInvites(), Collections.emptyList(), null,
                    precise, false, candidate -> plugin.getPvcIslandService().isAvailable(candidate.getUniqueId()) &&
                            candidate.isInvited(superiorPlayer));
            if (selection.ambiguous) {
                sender.sendMessage(org.bukkit.ChatColor.YELLOW + "You have several invitations. Use /is accept <island ID>.");
                return;
            }
            island = selection.island;
            targetPlayer = null;
        } else {
            targetPlayer = plugin.getPlayers().getSuperiorPlayer(args[1]);
            if (targetPlayer == null) island = plugin.getPvcIslandService().resolve(args[1]);
            else {
                InviteSelection selection = selectInvitation(targetPlayer.getIslands(), targetPlayer.getOwnedIslands(),
                        targetPlayer.getSelectedIsland(), precise, true,
                        candidate -> plugin.getPvcIslandService().isAvailable(candidate.getUniqueId()) &&
                                candidate.isInvited(superiorPlayer));
                if (selection.ambiguous) {
                    sender.sendMessage(org.bukkit.ChatColor.YELLOW + "That player invited you to several islands. Use the island ID.");
                    return;
                }
                island = selection.island;
            }
        }

        if (island == null || !plugin.getPvcIslandService().isAvailable(island.getUniqueId()) || !island.isInvited(superiorPlayer)) {
            Message.NO_ISLAND_INVITE.send(superiorPlayer);
            return;
        }

        int teamLimit = island.getTeamLimit();
        if (teamLimit >= 0 && island.getIslandMembers(true).size() >= teamLimit) {
            Message.JOIN_FULL_ISLAND.send(superiorPlayer);
            island.revokeInvite(superiorPlayer);
            return;
        }

        if (!PluginEventsFactory.callIslandJoinEvent(island, superiorPlayer, IslandJoinEvent.Cause.INVITE))
            return;

        IslandUtils.sendMessage(island, Message.JOIN_ANNOUNCEMENT, Collections.emptyList(), superiorPlayer.getName());

        island.addMember(superiorPlayer, SPlayerRole.defaultRole());
        superiorPlayer.selectIsland(island);

        if (targetPlayer == null)
            Message.JOINED_ISLAND_NAME.send(superiorPlayer, island.getName());
        else
            Message.JOINED_ISLAND.send(superiorPlayer, targetPlayer.getName());
    }

    @Override
    public List<String> tabComplete(SuperiorSkyblockPlugin plugin, CommandSender sender, String[] args) {
        SuperiorPlayer superiorPlayer = plugin.getPlayers().getSuperiorPlayer(sender);
        return args.length == 2 ? CommandTabCompletes.getOnlinePlayersAndIslands(plugin, args[1],
                plugin.getSettings().isTabCompleteHideVanished(), (onlinePlayer, onlineIsland) ->
                        onlineIsland.isInvited(superiorPlayer)) : Collections.emptyList();
    }

    /** Limited callers retain the upstream first invitation; nicknames prefer the sender's owned island. */
    static InviteSelection selectInvitation(List<Island> invitations, List<Island> owned, Island primary,
                                            boolean precise, boolean namedSender, Predicate<Island> eligible) {
        LinkedHashMap<UUID, Island> distinct = new LinkedHashMap<>();
        for (Island island : invitations)
            if (island != null && eligible.test(island)) distinct.putIfAbsent(island.getUniqueId(), island);
        List<Island> choices = new ArrayList<>(distinct.values());
        if (choices.isEmpty()) return new InviteSelection(null, false);
        if (precise && choices.size() > 1) return new InviteSelection(null, true);
        if (!namedSender || choices.size() == 1) return new InviteSelection(choices.get(0), false);
        Set<UUID> ownedIds = new HashSet<>();
        for (Island island : owned) ownedIds.add(island.getUniqueId());
        Island primaryChoice = primary == null ? null : distinct.get(primary.getUniqueId());
        if (primaryChoice != null && ownedIds.contains(primaryChoice.getUniqueId()))
            return new InviteSelection(primaryChoice, false);
        Island firstOwned = null;
        for (Island candidate : choices)
            if (ownedIds.contains(candidate.getUniqueId()) && (firstOwned == null || candidate.getShortId() < firstOwned.getShortId()))
                firstOwned = candidate;
        if (firstOwned != null) return new InviteSelection(firstOwned, false);
        return new InviteSelection(primaryChoice != null ? primaryChoice : choices.get(0), false);
    }

    static final class InviteSelection {
        final Island island;
        final boolean ambiguous;
        InviteSelection(Island island, boolean ambiguous) { this.island = island; this.ambiguous = ambiguous; }
    }

}

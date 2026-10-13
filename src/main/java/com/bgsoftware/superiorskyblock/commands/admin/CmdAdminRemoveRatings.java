package com.bgsoftware.superiorskyblock.commands.admin;

import com.bgsoftware.common.annotations.Nullable;
import com.bgsoftware.superiorskyblock.SuperiorSkyblockPlugin;
import com.bgsoftware.superiorskyblock.api.island.Island;
import com.bgsoftware.superiorskyblock.api.island.IslandExecutionScope;
import com.bgsoftware.superiorskyblock.api.wrappers.SuperiorPlayer;
import com.bgsoftware.superiorskyblock.commands.CommandTabCompletes;
import com.bgsoftware.superiorskyblock.commands.IAdminIslandCommand;
import com.bgsoftware.superiorskyblock.commands.arguments.CommandArguments;
import com.bgsoftware.superiorskyblock.core.events.plugin.PluginEventsFactory;
import com.bgsoftware.superiorskyblock.core.messages.Message;
import org.bukkit.command.CommandSender;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;

public class CmdAdminRemoveRatings implements IAdminIslandCommand {
    @Override
    public List<String> getAliases() {
        return Arrays.asList("removeratings", "rratings", "rr");
    }

    @Override
    public String getPermission() {
        return "superior.admin.removeratings";
    }

    @Override
    public String getUsage(java.util.Locale locale) {
        return "admin removeratings <" +
                Message.COMMAND_ARGUMENT_PLAYER_NAME.getMessage(locale) + "/" +
                Message.COMMAND_ARGUMENT_ISLAND_NAME.getMessage(locale) + "/" +
                Message.COMMAND_ARGUMENT_ALL_ISLANDS.getMessage(locale) + ">";
    }

    @Override
    public String getDescription(java.util.Locale locale) {
        return Message.COMMAND_DESCRIPTION_ADMIN_REMOVE_RATINGS.getMessage(locale);
    }

    @Override
    public int getMinArgs() {
        return 3;
    }

    @Override
    public int getMaxArgs() {
        return 3;
    }

    @Override
    public boolean canBeExecutedByConsole() {
        return true;
    }

    @Override
    public boolean supportMultipleIslands() {
        return true;
    }

    @Override
    public void execute(SuperiorSkyblockPlugin plugin, CommandSender sender, String[] args) {
        Island explicitTarget = IslandExecutionScope.isExplicitTarget() ? IslandExecutionScope.current() : null;
        if (explicitTarget != null && !plugin.getPvcIslandService().isAvailable(explicitTarget.getUniqueId())) {
            Message.CUSTOM.send(sender, "&cThis island has an operation in progress. Please wait.", true);
            return;
        }
        RatingTargets targets = resolveTargets(args[2], explicitTarget,
                plugin.getPlayers()::getSuperiorPlayer,
                argument -> CommandArguments.getIsland(plugin, sender, argument).getIsland(),
                plugin.getGrid()::getIslands);
        if (!targets.islands.isEmpty())
            execute(plugin, sender, targets.author, targets.islands, args);
    }

    @Override
    public void execute(SuperiorSkyblockPlugin plugin, CommandSender sender, @Nullable SuperiorPlayer targetPlayer, List<Island> islands, String[] args) {
        boolean removingAllRatings = targetPlayer == null;
        int islandsChangedCount = applyTargets(new RatingTargets(targetPlayer, islands),
                island -> PluginEventsFactory.callIslandClearRatingsEvent(island, sender),
                island -> PluginEventsFactory.callIslandRemoveRatingEvent(island, sender, targetPlayer));

        if (islandsChangedCount <= 0)
            return;

        if (!removingAllRatings)
            Message.RATE_REMOVE_ALL.send(sender, targetPlayer.getName());
        else if (islands.size() == 1)
            Message.RATE_REMOVE_ALL.send(sender, islands.get(0).getName());
        else
            Message.RATE_REMOVE_ALL_ISLANDS.send(sender);
    }

    @Override
    public List<String> tabComplete(SuperiorSkyblockPlugin plugin, CommandSender sender, String[] args) {
        if (args.length != 3) return Collections.emptyList();
        LinkedHashSet<String> result = new LinkedHashSet<>();
        if ("*".contains(args[2])) result.add("*");
        // A nickname selects the author, so multiple memberships must not hide it.
        result.addAll(CommandTabCompletes.getOnlinePlayers(plugin, args[2], false));
        result.addAll(CommandTabCompletes.getOnlinePlayersAndIslands(plugin, args[2], false, null));
        return Collections.unmodifiableList(new ArrayList<>(result));
    }

    /** A selected island clears its ratings; a nickname removes that author's ratings globally. */
    static RatingTargets resolveTargets(String argument, Island explicitTarget,
                                        Function<String, SuperiorPlayer> authors,
                                        Function<String, Island> islands,
                                        Supplier<List<Island>> allIslands) {
        if (explicitTarget != null) return new RatingTargets(null, Collections.singletonList(explicitTarget));
        if ("*".equals(argument)) return new RatingTargets(null, allIslands.get());
        SuperiorPlayer author = authors.apply(argument);
        if (author != null) return new RatingTargets(author, allIslands.get());
        Island island = islands.apply(argument);
        return new RatingTargets(null, island == null ? Collections.emptyList() : Collections.singletonList(island));
    }

    static int applyTargets(RatingTargets targets, Predicate<Island> clearAllowed, Predicate<Island> authorAllowed) {
        int changed = 0;
        for (Island island : targets.islands) {
            if (targets.author == null) {
                if (clearAllowed.test(island)) { island.removeRatings(); ++changed; }
            } else if (authorAllowed.test(island)) {
                island.removeRating(targets.author); ++changed;
            }
        }
        return changed;
    }

    static final class RatingTargets {
        final SuperiorPlayer author;
        final List<Island> islands;
        RatingTargets(SuperiorPlayer author, List<Island> islands) {
            this.author = author;
            this.islands = Collections.unmodifiableList(new ArrayList<>(islands));
        }
    }

}

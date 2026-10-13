package com.bgsoftware.superiorskyblock.commands.admin;

import com.bgsoftware.superiorskyblock.api.island.Island;
import com.bgsoftware.superiorskyblock.api.island.IslandExecutionScope;
import com.bgsoftware.superiorskyblock.api.wrappers.SuperiorPlayer;

import java.util.function.Predicate;

/** Administrative member completion must inspect the candidate, not the administrator's selection. */
final class AdminMemberTargets {
    private AdminMemberTargets() { }

    static boolean anyEligibleIsland(SuperiorPlayer player, Predicate<Island> eligible) {
        Island explicit = IslandExecutionScope.isExplicitTarget() ? IslandExecutionScope.current() : null;
        if (explicit != null)
            return explicit.isMember(player) && eligible.test(explicit);
        for (Island island : player.getIslands())
            if (island.isMember(player) && eligible.test(island)) return true;
        return false;
    }

    static boolean transferMember(Island island, SuperiorPlayer owner, SuperiorPlayer candidate) {
        return !owner.getUniqueId().equals(candidate.getUniqueId()) && island.isMember(candidate);
    }
}

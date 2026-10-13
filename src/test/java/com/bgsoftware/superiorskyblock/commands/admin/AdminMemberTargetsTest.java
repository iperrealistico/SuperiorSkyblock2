package com.bgsoftware.superiorskyblock.commands.admin;

import com.bgsoftware.superiorskyblock.api.island.Island;
import com.bgsoftware.superiorskyblock.api.island.IslandExecutionScope;
import com.bgsoftware.superiorskyblock.api.wrappers.SuperiorPlayer;
import org.junit.Test;

import java.lang.reflect.Proxy;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import static org.junit.Assert.*;

public class AdminMemberTargetsTest {
    @Test public void unrelatedAdministratorSelectionDoesNotHideCandidateMembers() {
        Island first = island(true), second = island(true), administratorIsland = island(false);
        SuperiorPlayer candidate = player(Arrays.asList(first, second));
        try (IslandExecutionScope ignored = IslandExecutionScope.open(administratorIsland, false)) {
            assertTrue(AdminMemberTargets.anyEligibleIsland(candidate, island -> island == second));
        }
        try (IslandExecutionScope ignored = IslandExecutionScope.open(null, false)) {
            assertTrue(AdminMemberTargets.anyEligibleIsland(candidate, island -> island == first));
        }
    }

    @Test public void explicitIslandDoesNotFallBackToAnEligibleOtherMembership() {
        Island first = island(true), second = island(true);
        SuperiorPlayer candidate = player(Arrays.asList(first, second));
        try (IslandExecutionScope ignored = IslandExecutionScope.open(first)) {
            assertFalse(AdminMemberTargets.anyEligibleIsland(candidate, island -> island == second));
        }
        try (IslandExecutionScope ignored = IslandExecutionScope.open(second)) {
            assertTrue(AdminMemberTargets.anyEligibleIsland(candidate, island -> island == second));
        }
    }

    @Test public void explicitNonMemberNeverReachesRolePredicate() {
        SuperiorPlayer candidate = player(Arrays.asList(island(true)));
        try (IslandExecutionScope ignored = IslandExecutionScope.open(island(false))) {
            assertFalse(AdminMemberTargets.anyEligibleIsland(candidate, island -> {
                fail("A foreign island must not be checked as this player's role"); return true;
            }));
        }
    }

    @Test public void soleMembershipWorksWithoutAnySelector() {
        Island sole = island(true);
        assertTrue(AdminMemberTargets.anyEligibleIsland(player(Arrays.asList(sole)), island -> island == sole));
        assertFalse(AdminMemberTargets.anyEligibleIsland(player(Arrays.asList(sole)), island -> false));
    }

    @Test public void kickAndDemoteDeclareIslandDependentConfirmation() {
        assertTrue(new CmdAdminKick().requireIsland());
        assertTrue(new CmdAdminDemote().requireIsland());
    }

    @Test public void newOwnerMustBeAMemberOfTheTargetRegardlessOfOtherSelections() {
        Island target = island(true), foreign = island(false);
        SuperiorPlayer owner = player(Arrays.asList(target));
        SuperiorPlayer memberWithOtherIslands = player(Arrays.asList(target, foreign));
        try (IslandExecutionScope ignored = IslandExecutionScope.open(foreign, false)) {
            assertTrue(AdminMemberTargets.transferMember(target, owner, memberWithOtherIslands));
            assertFalse(AdminMemberTargets.transferMember(target, owner, owner));
            assertFalse(AdminMemberTargets.transferMember(foreign, owner, memberWithOtherIslands));
        }
    }

    private static Island island(boolean member) {
        UUID id = UUID.randomUUID();
        return (Island) Proxy.newProxyInstance(Island.class.getClassLoader(), new Class<?>[]{Island.class},
                (proxy, method, args) -> {
                    if ("isMember".equals(method.getName())) return member;
                    if ("getUniqueId".equals(method.getName())) return id;
                    if ("equals".equals(method.getName())) return proxy == args[0];
                    if ("hashCode".equals(method.getName())) return id.hashCode();
                    return null;
                });
    }

    private static SuperiorPlayer player(List<Island> islands) {
        UUID id = UUID.randomUUID();
        return (SuperiorPlayer) Proxy.newProxyInstance(SuperiorPlayer.class.getClassLoader(),
                new Class<?>[]{SuperiorPlayer.class}, (proxy, method, args) -> {
                    if ("getIslands".equals(method.getName())) return islands;
                    if ("getUniqueId".equals(method.getName())) return id;
                    if ("getIsland".equals(method.getName())) {
                        fail("Member completion must not consult the administrator's lexical target");
                    }
                    return null;
                });
    }
}

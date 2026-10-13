package com.bgsoftware.superiorskyblock.commands.player;

import com.bgsoftware.superiorskyblock.api.island.Island;
import org.junit.Test;

import java.lang.reflect.Proxy;
import java.util.Arrays;
import java.util.Collections;
import java.util.UUID;

import static org.junit.Assert.*;

public class InviteSelectionTest {
    @Test public void limitedAcceptWithoutArgumentsKeepsOriginalInvitationOrder() {
        Island first = island(99), later = island(1);
        CmdAccept.InviteSelection selection = CmdAccept.selectInvitation(Arrays.asList(first, later),
                Collections.emptyList(), null, false, false, island -> true);
        assertSame(first, selection.island); assertFalse(selection.ambiguous);
    }

    @Test public void multiIslandPresentationOnlyRequiresSelectionForDistinctEligibleInvitations() {
        Island first = island(1), second = island(2);
        CmdAccept.InviteSelection ambiguous = CmdAccept.selectInvitation(Arrays.asList(first, second),
                Collections.emptyList(), null, true, false, island -> true);
        assertNull(ambiguous.island); assertTrue(ambiguous.ambiguous);
        CmdAccept.InviteSelection duplicate = CmdAccept.selectInvitation(Arrays.asList(first, first),
                Collections.emptyList(), null, true, false, island -> true);
        assertSame(first, duplicate.island); assertFalse(duplicate.ambiguous);
        CmdAccept.InviteSelection oneEligible = CmdAccept.selectInvitation(Arrays.asList(first, second),
                Collections.emptyList(), null, true, false, island -> island == second);
        assertSame(second, oneEligible.island); assertFalse(oneEligible.ambiguous);
    }

    @Test public void limitedNicknamePrefersAnOwnedInvitationOverTheSendersSelectedTeamIsland() {
        Island team = island(1), owned = island(2);
        CmdAccept.InviteSelection selection = CmdAccept.selectInvitation(Arrays.asList(team, owned),
                Collections.singletonList(owned), team, false, true, island -> true);
        assertSame(owned, selection.island); assertFalse(selection.ambiguous);
    }

    @Test public void limitedNicknameUsesOwnedPrimaryThenOldestOwnedEligibleIsland() {
        Island first = island(1), second = island(2), third = island(3);
        CmdAccept.InviteSelection primary = CmdAccept.selectInvitation(Arrays.asList(third, first, second),
                Arrays.asList(first, second, third), second, false, true, island -> true);
        assertSame(second, primary.island); assertFalse(primary.ambiguous);
        CmdAccept.InviteSelection fallback = CmdAccept.selectInvitation(Arrays.asList(third, first, second),
                Arrays.asList(third, second, first), second, false, true, island -> island != second);
        assertSame(first, fallback.island); assertFalse(fallback.ambiguous);
    }

    @Test public void nicknameWithNoOwnedIslandUsesEligiblePrimaryBeforeOtherTeamInvitations() {
        Island first = island(1), primary = island(2);
        CmdAccept.InviteSelection selection = CmdAccept.selectInvitation(Arrays.asList(first, primary),
                Collections.emptyList(), primary, false, true, island -> true);
        assertSame(primary, selection.island); assertFalse(selection.ambiguous);
    }

    @Test public void preciseNicknameDoesNotSilentlyPickOwnedPrimaryWhenSeveralInvitationsExist() {
        Island first = island(1), second = island(2);
        CmdAccept.InviteSelection selection = CmdAccept.selectInvitation(Arrays.asList(first, second),
                Arrays.asList(first, second), first, true, true, island -> true);
        assertNull(selection.island); assertTrue(selection.ambiguous);
    }

    @Test public void missingOrUnavailableInvitationsNeverProduceIdentifierAdviceForLimitedCaller() {
        Island unavailable = island(1);
        CmdAccept.InviteSelection selection = CmdAccept.selectInvitation(Arrays.asList(null, unavailable),
                Collections.singletonList(unavailable), unavailable, false, true, island -> false);
        assertNull(selection.island); assertFalse(selection.ambiguous);
    }

    private static Island island(long shortId) {
        UUID id = UUID.randomUUID();
        return (Island) Proxy.newProxyInstance(Island.class.getClassLoader(), new Class<?>[]{Island.class},
                (proxy, method, args) -> {
                    if ("getUniqueId".equals(method.getName())) return id;
                    if ("getShortId".equals(method.getName())) return shortId;
                    if ("equals".equals(method.getName())) return proxy == args[0];
                    if ("hashCode".equals(method.getName())) return id.hashCode();
                    return null;
                });
    }
}

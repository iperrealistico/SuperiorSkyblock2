package com.bgsoftware.superiorskyblock.commands.admin;

import com.bgsoftware.superiorskyblock.api.island.Island;
import com.bgsoftware.superiorskyblock.api.wrappers.SuperiorPlayer;
import org.junit.Test;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.*;

public class RatingTargetsTest {
    @Test public void explicitIslandClearsOnlyThatIslandEvenWhenArgumentIsOwnerNickname() {
        Fixture selected = new Fixture(), other = new Fixture();
        CmdAdminRemoveRatings.RatingTargets targets = CmdAdminRemoveRatings.resolveTargets("owner", selected.island,
                argument -> { fail("An explicit island must not become a rating author"); return null; },
                argument -> { fail("An explicit island must not be resolved again"); return null; },
                () -> { fail("An explicit island must not inspect the global grid"); return null; });
        assertEquals(1, CmdAdminRemoveRatings.applyTargets(targets, island -> true, island -> {
            fail("Clearing an island must not use the author event"); return false;
        }));
        assertEquals(1, selected.cleared.get()); assertEquals(0, other.cleared.get());
        assertTrue(selected.authors.isEmpty()); assertTrue(other.authors.isEmpty());
    }

    @Test public void nicknameRemovesAuthorsVotesGloballyWithoutResolvingTheirIslands() {
        Fixture first = new Fixture(), second = new Fixture();
        SuperiorPlayer author = author();
        CmdAdminRemoveRatings.RatingTargets targets = CmdAdminRemoveRatings.resolveTargets("author", null,
                argument -> author,
                argument -> { fail("An author with zero or multiple islands must not be ambiguous"); return null; },
                () -> Arrays.asList(first.island, second.island));
        assertEquals(2, CmdAdminRemoveRatings.applyTargets(targets, island -> {
            fail("An author's removal must not clear everyone's votes"); return false;
        }, island -> true));
        assertEquals(Collections.singletonList(author), first.authors);
        assertEquals(Collections.singletonList(author), second.authors);
        assertEquals(0, first.cleared.get()); assertEquals(0, second.cleared.get());
    }

    @Test public void positionalIslandAndWildcardKeepTheirOriginalClearSemantics() {
        Fixture first = new Fixture(), second = new Fixture();
        CmdAdminRemoveRatings.RatingTargets single = CmdAdminRemoveRatings.resolveTargets("id:2", null,
                argument -> null, argument -> second.island, () -> Arrays.asList(first.island, second.island));
        assertEquals(1, CmdAdminRemoveRatings.applyTargets(single, island -> true, island -> false));
        CmdAdminRemoveRatings.RatingTargets all = CmdAdminRemoveRatings.resolveTargets("*", null,
                argument -> { fail("Wildcard is not an author"); return null; },
                argument -> { fail("Wildcard is not a single island"); return null; },
                () -> Arrays.asList(first.island, second.island));
        assertEquals(2, CmdAdminRemoveRatings.applyTargets(all, island -> true, island -> false));
        assertEquals(1, first.cleared.get()); assertEquals(2, second.cleared.get());
    }

    @Test public void cancelledEventsDoNotModifyAnyRatings() {
        Fixture island = new Fixture();
        CmdAdminRemoveRatings.RatingTargets clear = CmdAdminRemoveRatings.resolveTargets("2", island.island,
                argument -> null, argument -> null, Collections::emptyList);
        assertEquals(0, CmdAdminRemoveRatings.applyTargets(clear, ignored -> false, ignored -> false));
        CmdAdminRemoveRatings.RatingTargets authored = CmdAdminRemoveRatings.resolveTargets("author", null,
                ignored -> author(), ignored -> null, () -> Collections.singletonList(island.island));
        assertEquals(0, CmdAdminRemoveRatings.applyTargets(authored, ignored -> false, ignored -> false));
        assertEquals(0, island.cleared.get()); assertTrue(island.authors.isEmpty());
    }

    private static SuperiorPlayer author() {
        return (SuperiorPlayer) Proxy.newProxyInstance(SuperiorPlayer.class.getClassLoader(),
                new Class<?>[]{SuperiorPlayer.class}, (proxy, method, args) -> {
                    if ("equals".equals(method.getName())) return proxy == args[0];
                    if ("getIslands".equals(method.getName()) || "getIsland".equals(method.getName()))
                        fail("The author operation is independent of island ownership or membership");
                    return null;
                });
    }

    private static final class Fixture {
        final AtomicInteger cleared = new AtomicInteger();
        final List<SuperiorPlayer> authors = new ArrayList<>();
        final Island island = (Island) Proxy.newProxyInstance(Island.class.getClassLoader(),
                new Class<?>[]{Island.class}, (proxy, method, args) -> {
                    if ("removeRatings".equals(method.getName())) { cleared.incrementAndGet(); return null; }
                    if ("removeRating".equals(method.getName())) { authors.add((SuperiorPlayer) args[0]); return null; }
                    if ("getOwner".equals(method.getName())) fail("Island owners are not the author of an island clear");
                    if ("equals".equals(method.getName())) return proxy == args[0];
                    return null;
                });
    }
}

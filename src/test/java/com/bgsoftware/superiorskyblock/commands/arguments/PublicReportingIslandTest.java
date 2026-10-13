package com.bgsoftware.superiorskyblock.commands.arguments;

import com.bgsoftware.superiorskyblock.api.island.Island;
import org.junit.Test;
import java.lang.reflect.Proxy;
import static org.junit.Assert.*;

public class PublicReportingIslandTest {
    @Test public void preciseForeignReportsUseTheirCapturedDestinationInsteadOfAMembershipLookup() {
        Island foreign = island();
        IslandArgument expected = new IslandArgument(foreign, null);
        assertSame(expected, CommandArguments.reportingIsland(true, foreign, () -> {
            fail("Public inspection must not query the caller's membership wrapper"); return null;
        }, target -> { assertSame(foreign, target); return expected; }));
    }

    @Test public void ordinarySingletonReportsRetainTheNormalMembershipResolver() {
        IslandArgument ordinary = new IslandArgument(island(), null);
        assertSame(ordinary, CommandArguments.reportingIsland(false, island(), () -> ordinary,
                target -> { fail("An implicit scope must not grant foreign inspection"); return null; }));
        assertSame(ordinary, CommandArguments.reportingIsland(true, null, () -> ordinary,
                target -> { fail("A missing subject has no reporting target"); return null; }));
    }

    @Test public void anUnavailableCapturedReportNeverFallsBackToAnotherIsland() {
        assertSame(IslandArgument.EMPTY, CommandArguments.reportingIsland(true, island(), () -> {
            fail("An expired reporting destination must not become the selected island"); return null;
        }, target -> IslandArgument.EMPTY));
    }

    private static Island island() {
        return (Island) Proxy.newProxyInstance(Island.class.getClassLoader(), new Class<?>[]{Island.class},
                (proxy, method, arguments) -> { throw new AssertionError("The routing test does not need Bukkit/island internals"); });
    }
}

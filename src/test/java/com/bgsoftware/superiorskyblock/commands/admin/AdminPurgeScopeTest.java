package com.bgsoftware.superiorskyblock.commands.admin;

import com.bgsoftware.superiorskyblock.api.island.Island;
import com.bgsoftware.superiorskyblock.api.island.IslandExecutionScope;
import org.junit.Test;

import java.lang.reflect.Proxy;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.*;

public class AdminPurgeScopeTest {
    @Test public void ordinaryPurgeRemainsGlobalWithOrWithoutAdministratorSelection() {
        Island first = island(), second = island();
        List<Island> all = Arrays.asList(first, second);
        assertSame(all, CmdAdminPurge.purgeCandidates(all));
        try (IslandExecutionScope ignored = IslandExecutionScope.open(first, false)) {
            assertSame(all, CmdAdminPurge.purgeCandidates(all));
        }
        try (IslandExecutionScope ignored = IslandExecutionScope.open(null, false)) {
            assertSame(all, CmdAdminPurge.purgeCandidates(all));
        }
    }

    @Test public void explicitPurgeOnlyUsesItsFrozenTarget() {
        Island first = island(), second = island();
        try (IslandExecutionScope ignored = IslandExecutionScope.open(second)) {
            assertEquals(Collections.singletonList(second), CmdAdminPurge.purgeCandidates(Arrays.asList(first, second)));
        }
    }

    private static Island island() {
        return (Island) Proxy.newProxyInstance(Island.class.getClassLoader(), new Class<?>[]{Island.class},
                (proxy, method, args) -> "equals".equals(method.getName()) ? proxy == args[0] : null);
    }
}

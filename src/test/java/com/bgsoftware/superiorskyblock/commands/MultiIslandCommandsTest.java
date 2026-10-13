package com.bgsoftware.superiorskyblock.commands;

import com.bgsoftware.superiorskyblock.api.island.Island;
import org.junit.Test;
import java.lang.reflect.Proxy;
import java.util.Arrays;
import java.util.Collections;
import static org.junit.Assert.*;

public class MultiIslandCommandsTest {
    @Test public void quotaOneKeepsToolsHiddenRegardlessOfExistingMembershipCount() {
        for (int memberships : new int[]{0, 1, 2, 99}) assertFalse(MultiIslandCommands.enabled(memberships, 1));
    }
    @Test public void higherQuotaStillKeepsToolsHiddenUntilASecondIslandActuallyExists() {
        for (int quota : new int[]{2, 99, Integer.MAX_VALUE}) {
            assertFalse(MultiIslandCommands.enabled(0, quota)); assertFalse(MultiIslandCommands.enabled(1, quota));
        }
    }
    @Test public void realAlternativesAndHigherQuotaUnlockToolsIncludingUnlimited() {
        assertTrue(MultiIslandCommands.enabled(2, 2)); assertTrue(MultiIslandCommands.enabled(99, Integer.MAX_VALUE));
    }
    @Test public void quotaRevocationImmediatelyHidesToolsWithoutDeletingExistingIslands() {
        assertTrue(MultiIslandCommands.enabled(2, 2));
        assertFalse(MultiIslandCommands.enabled(2, 1)); assertFalse(MultiIslandCommands.enabled(2, 0));
    }
    @Test public void singleOwnedIslandWinsOverAnAdditionalSelectedTeamIsland() {
        Island owned = island(), team = island();
        assertSame(owned, MultiIslandCommands.legacyIsland(Collections.singletonList(owned), Arrays.asList(owned, team), team));
    }
    @Test public void revokedMultipleOwnerRetainsValidOwnedSelectionOrFallsBackToFirstOwnedIsland() {
        Island first = island(), second = island(), team = island();
        assertSame(second, MultiIslandCommands.legacyIsland(Arrays.asList(first, second), Arrays.asList(first, second, team), second));
        assertSame(first, MultiIslandCommands.legacyIsland(Arrays.asList(first, second), Arrays.asList(first, second, team), team));
        assertSame(first, MultiIslandCommands.legacyIsland(Arrays.asList(first, second), Arrays.asList(first, second, team), null));
    }
    @Test public void memberWithoutOwnershipKeepsValidSelectionAndHasSafeFallbacks() {
        Island first = island(), second = island(), removed = island();
        assertSame(second, MultiIslandCommands.legacyIsland(Collections.emptyList(), Arrays.asList(first, second), second));
        assertSame(first, MultiIslandCommands.legacyIsland(Collections.emptyList(), Arrays.asList(first, second), removed));
        assertNull(MultiIslandCommands.legacyIsland(Collections.emptyList(), Collections.emptyList(), removed));
    }
    private static Island island() {
        return (Island) Proxy.newProxyInstance(Island.class.getClassLoader(), new Class<?>[]{Island.class}, (proxy, method, args) -> {
            if ("equals".equals(method.getName())) return proxy == args[0];
            if ("hashCode".equals(method.getName())) return System.identityHashCode(proxy);
            return null;
        });
    }
}

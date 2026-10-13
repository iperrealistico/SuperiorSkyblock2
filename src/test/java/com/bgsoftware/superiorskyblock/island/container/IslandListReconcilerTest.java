package com.bgsoftware.superiorskyblock.island.container;

import com.bgsoftware.superiorskyblock.api.island.Island;
import org.junit.Test;
import java.lang.reflect.Proxy;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import static org.junit.Assert.*;

public class IslandListReconcilerTest {
    @Test public void staleSortDropsRemovedIslandAndKeepsNewRegisteredIsland() {
        Island removed = island(UUID.randomUUID(), false), kept = island(UUID.randomUUID(), false), added = island(UUID.randomUUID(), false);
        List<Island> updated = IslandListReconciler.reconcile(Arrays.asList(removed, kept), Arrays.asList(kept, added));
        assertEquals(2, updated.size()); assertSame(kept, updated.get(0)); assertSame(added, updated.get(1));
    }
    @Test public void replacementWithSameUuidPublishesCurrentObjectAndOmitsIgnored() {
        UUID id = UUID.randomUUID(); Island old = island(id, false), replacement = island(id, false), ignored = island(UUID.randomUUID(), true);
        List<Island> updated = IslandListReconciler.reconcile(Arrays.asList(old, ignored), Arrays.asList(replacement, ignored));
        assertEquals(1, updated.size()); assertSame(replacement, updated.get(0));
    }
    private static Island island(UUID uuid, boolean ignored) {
        return (Island) Proxy.newProxyInstance(Island.class.getClassLoader(), new Class<?>[]{Island.class},
                (proxy, method, args) -> method.getName().equals("getUniqueId") ? uuid : method.getName().equals("isIgnored") ? ignored : null);
    }
}

package com.bgsoftware.superiorskyblock.pvc.island;

import com.bgsoftware.superiorskyblock.api.island.Island;
import com.bgsoftware.superiorskyblock.api.island.IslandExecutionScope;
import org.junit.Test;
import java.lang.reflect.Proxy;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import static org.junit.Assert.*;

public class IslandExecutionScopeTest {
    @Test public void implicitContextDoesNotOverrideExplicitArgumentsAndNestedScopeRestores() {
        Island selected = island(), requested = island();
        try (IslandExecutionScope first = IslandExecutionScope.open(selected, false)) {
            assertSame(selected, IslandExecutionScope.current()); assertFalse(IslandExecutionScope.isExplicitTarget());
            try (IslandExecutionScope second = IslandExecutionScope.open(requested)) {
                assertSame(requested, IslandExecutionScope.current()); assertTrue(IslandExecutionScope.isExplicitTarget());
            }
            assertSame(selected, IslandExecutionScope.current()); assertFalse(IslandExecutionScope.isExplicitTarget());
        }
        assertFalse(IslandExecutionScope.isActive());
    }
    @Test public void nullScopeAndExceptionRestorePreviousTarget() {
        Island selected = island();
        try (IslandExecutionScope first = IslandExecutionScope.open(selected)) {
            try (IslandExecutionScope second = IslandExecutionScope.open(null)) {
                assertTrue(IslandExecutionScope.isActive()); assertNull(IslandExecutionScope.current());
                throw new IllegalStateException("cancel");
            } catch (IllegalStateException expected) { assertSame(selected, IslandExecutionScope.current()); }
        }
        assertFalse(IslandExecutionScope.isActive());
    }
    @Test public void targetsDoNotLeakIntoOtherWorkers() throws Exception {
        try (IslandExecutionScope ignored = IslandExecutionScope.open(island())) {
            assertFalse(CompletableFuture.supplyAsync(IslandExecutionScope::isActive).get());
        }
    }
    static Island island() {
        UUID id = UUID.randomUUID();
        return (Island) Proxy.newProxyInstance(Island.class.getClassLoader(), new Class<?>[]{Island.class},
                (proxy, method, args) -> "getUniqueId".equals(method.getName()) ? id : null);
    }
}

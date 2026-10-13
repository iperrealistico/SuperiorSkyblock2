package com.bgsoftware.superiorskyblock.pvc.island;

import com.bgsoftware.superiorskyblock.api.island.Island;
import com.bgsoftware.superiorskyblock.api.island.IslandExecutionScope;
import org.junit.Test;
import java.util.concurrent.CompletableFuture;
import static org.junit.Assert.*;

public class InheritedIslandScopeTest {
    @Test public void inheritedTargetIsNeitherExplicitNorAnInactiveContext() {
        Island captured = IslandExecutionScopeTest.island();
        try (IslandExecutionScope ignored = IslandExecutionScope.openInherited(captured)) {
            assertScope(captured, false, true);
        }
        assertInactive();
    }
    @Test public void explicitNestedDestinationRestoresInheritedCallerOnClose() {
        Island captured = IslandExecutionScopeTest.island(), requested = IslandExecutionScopeTest.island();
        try (IslandExecutionScope inherited = IslandExecutionScope.openInherited(captured)) {
            try (IslandExecutionScope explicit = IslandExecutionScope.open(requested)) { assertScope(requested, true, false); }
            assertScope(captured, false, true);
        }
        assertInactive();
    }
    @Test public void inheritedNestedCommandRestoresExplicitOuterDestination() {
        Island outer = IslandExecutionScopeTest.island(), captured = IslandExecutionScopeTest.island();
        try (IslandExecutionScope explicit = IslandExecutionScope.open(outer)) {
            try (IslandExecutionScope inherited = IslandExecutionScope.openInherited(captured)) { assertScope(captured, false, true); }
            assertScope(outer, true, false);
        }
        assertInactive();
    }
    @Test public void ordinaryNestedSelectionTemporarilyClearsInheritedAuthority() {
        Island captured = IslandExecutionScopeTest.island(), selected = IslandExecutionScopeTest.island();
        try (IslandExecutionScope inherited = IslandExecutionScope.openInherited(captured)) {
            try (IslandExecutionScope ordinary = IslandExecutionScope.open(selected, false)) { assertScope(selected, false, false); }
            assertScope(captured, false, true);
        }
        assertInactive();
    }
    @Test public void nullNestedScopeAndExceptionsRestoreCapturedTarget() {
        Island captured = IslandExecutionScopeTest.island();
        try (IslandExecutionScope inherited = IslandExecutionScope.openInherited(captured)) {
            try (IslandExecutionScope empty = IslandExecutionScope.open(null)) {
                assertScope(null, true, false);
                throw new IllegalStateException("cancel nested command");
            } catch (IllegalStateException expected) { assertScope(captured, false, true); }
        }
        assertInactive();
    }
    @Test public void capturedTargetsDoNotLeakIntoUnrelatedWorkers() throws Exception {
        try (IslandExecutionScope inherited = IslandExecutionScope.openInherited(IslandExecutionScopeTest.island())) {
            assertFalse(CompletableFuture.supplyAsync(() -> IslandExecutionScope.isActive() ||
                    IslandExecutionScope.isInheritedTarget() || IslandExecutionScope.isExplicitTarget() ||
                    IslandExecutionScope.current() != null).get());
        }
        assertInactive();
    }
    private static void assertScope(Island target, boolean explicit, boolean inherited) {
        assertTrue(IslandExecutionScope.isActive()); assertSame(target, IslandExecutionScope.current());
        assertEquals(explicit, IslandExecutionScope.isExplicitTarget()); assertEquals(inherited, IslandExecutionScope.isInheritedTarget());
    }
    private static void assertInactive() {
        assertFalse(IslandExecutionScope.isActive()); assertFalse(IslandExecutionScope.isExplicitTarget());
        assertFalse(IslandExecutionScope.isInheritedTarget()); assertNull(IslandExecutionScope.current());
    }
}

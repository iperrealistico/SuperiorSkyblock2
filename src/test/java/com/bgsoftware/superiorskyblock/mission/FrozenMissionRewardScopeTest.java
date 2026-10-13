package com.bgsoftware.superiorskyblock.mission;

import com.bgsoftware.superiorskyblock.api.island.Island;
import com.bgsoftware.superiorskyblock.api.island.IslandExecutionScope;
import com.bgsoftware.superiorskyblock.api.wrappers.SuperiorPlayer;
import org.junit.Test;

import java.lang.reflect.Proxy;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.*;

public class FrozenMissionRewardScopeTest {
    @Test public void scheduledRewardRestoresItsSubjectInsteadOfTheLaterSelectedIsland() {
        Fixture first = new Fixture(), second = new Fixture();
        FrozenMissionRewardScope frozen = first.freeze();
        try (IslandExecutionScope later = IslandExecutionScope.open(second.island, false)) {
            assertTrue(frozen.runIfCurrent(() -> {
                assertSame(first.island, IslandExecutionScope.current());
                assertTrue(IslandExecutionScope.isExplicitTarget());
            }));
            assertSame(second.island, IslandExecutionScope.current());
            assertFalse(IslandExecutionScope.isExplicitTarget());
        }
        assertFalse(IslandExecutionScope.isActive());
    }

    @Test public void ownerOrRevisionChangeRejectsTheOldRewardWithoutFollowingAnotherIsland() {
        Fixture fixture = new Fixture();
        FrozenMissionRewardScope ownerChanged = fixture.freeze();
        fixture.owner.set(player(UUID.randomUUID()));
        assertFalse(ownerChanged.runIfCurrent(() -> fail("Transferred subject must not receive the old reward")));
        FrozenMissionRewardScope revisionChanged = fixture.freeze();
        fixture.revision.incrementAndGet();
        assertFalse(revisionChanged.runIfCurrent(() -> fail("Reset subject must not receive the old reward")));
        assertFalse(IslandExecutionScope.isActive());
    }

    @Test public void unpublishedSubjectFailsClosedAndExceptionsDoNotLeakScope() {
        Fixture fixture = new Fixture();
        FrozenMissionRewardScope frozen = fixture.freeze();
        fixture.available.set(false);
        assertFalse(frozen.runIfCurrent(() -> fail("Deleted subject must not execute rewards")));
        fixture.available.set(true);
        try {
            frozen.runIfCurrent(() -> { throw new IllegalStateException("external provider failed"); });
            fail("External failure must propagate");
        } catch (IllegalStateException expected) { assertEquals("external provider failed", expected.getMessage()); }
        assertFalse(IslandExecutionScope.isActive());
    }

    private static final class Fixture {
        final UUID id = UUID.randomUUID();
        final AtomicReference<SuperiorPlayer> owner = new AtomicReference<>(player(UUID.randomUUID()));
        final AtomicLong revision = new AtomicLong(7L);
        final AtomicBoolean available = new AtomicBoolean(true);
        final Island island = (Island) Proxy.newProxyInstance(Island.class.getClassLoader(), new Class<?>[]{Island.class},
                (proxy, method, arguments) -> method.getName().equals("getUniqueId") ? id :
                        method.getName().equals("getOwner") ? owner.get() : null);
        FrozenMissionRewardScope freeze() {
            return new FrozenMissionRewardScope(island, subject -> available.get(), subject -> revision.get());
        }
    }

    private static SuperiorPlayer player(UUID id) {
        return (SuperiorPlayer) Proxy.newProxyInstance(SuperiorPlayer.class.getClassLoader(), new Class<?>[]{SuperiorPlayer.class},
                (proxy, method, arguments) -> method.getName().equals("getUniqueId") ? id : null);
    }
}

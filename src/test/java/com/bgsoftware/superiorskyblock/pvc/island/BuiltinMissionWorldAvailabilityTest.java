package com.bgsoftware.superiorskyblock.pvc.island;

import com.bgsoftware.superiorskyblock.api.SuperiorSkyblock;
import com.bgsoftware.superiorskyblock.api.handlers.GridManager;
import com.bgsoftware.superiorskyblock.api.handlers.ProvidersManager;
import com.bgsoftware.superiorskyblock.api.hooks.WorldsProvider;
import com.bgsoftware.superiorskyblock.api.island.Island;
import com.bgsoftware.superiorskyblock.api.world.Dimension;
import com.bgsoftware.superiorskyblock.api.world.WorldInfo;
import com.bgsoftware.superiorskyblock.api.wrappers.SuperiorPlayer;
import com.bgsoftware.superiorskyblock.missions.common.BuiltinMission;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;
import org.junit.Test;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Predicate;

import static org.junit.Assert.*;

public class BuiltinMissionWorldAvailabilityTest {
    @Test public void disabledOptionalWorldIsNotQueriedOrLoaded() {
        AtomicInteger queried = new AtomicInteger();
        TestMission mission = fixture(null, null, false, queried);
        assertTrue(mission.available(name -> { fail("Disabled worlds must not reach the tracker"); return false; }));
        assertEquals(0, queried.get());
    }

    @Test public void enabledWorldUsesAvailableMetadataAndPreservesQuarantine() {
        AtomicInteger queried = new AtomicInteger();
        TestMission mission = fixture("islands", null, true, queried);
        Set<String> checked = new HashSet<>();
        assertTrue(mission.available(name -> { checked.add(name); return true; }));
        assertEquals(1, queried.get()); assertEquals(java.util.Collections.singleton("islands"), checked);
        assertFalse(mission.available(name -> !"islands".equals(name)));
    }

    @Test public void enabledWorldWithoutMetadataFailsClosed() {
        AtomicInteger queried = new AtomicInteger();
        TestMission mission = fixture(null, null, true, queried);
        assertFalse(mission.available(name -> true));
        assertEquals(1, queried.get());
    }

    @Test public void actualWorldQuarantineStillAppliesWithOptionalDimensionsDisabled() {
        AtomicInteger queried = new AtomicInteger();
        TestMission mission = fixture(null, "actual-required-world", false, queried);
        assertFalse(mission.available(name -> !"actual-required-world".equals(name)));
        assertEquals(0, queried.get());
    }

    private static TestMission fixture(String islandWorld, String actualWorld, boolean enabled, AtomicInteger queried) {
        String unique = "PVC_MISSION_WORLD_" + UUID.randomUUID();
        Dimension.register(unique, World.Environment.NORMAL);
        Dimension dimension = Dimension.getByName(unique);
        WorldInfo info = islandWorld == null ? null : new WorldInfo() {
            @Override public String getName() { return islandWorld; }
            @Override public World.Environment getEnvironment() { return World.Environment.NORMAL; }
            @Override public Dimension getDimension() { return dimension; }
        };
        WorldsProvider worlds = proxy(WorldsProvider.class, (instance, method, args) ->
                "isDimensionEnabled".equals(method.getName()) ? enabled && args[0] == dimension : null);
        ProvidersManager providers = proxy(ProvidersManager.class, (instance, method, args) ->
                "getWorldsProvider".equals(method.getName()) ? worlds : null);
        GridManager grid = proxy(GridManager.class, (instance, method, args) -> {
            if ("getIslandsWorldInfo".equals(method.getName())) {
                queried.incrementAndGet();
                assertSame("A disabled dimension was inspected", dimension, args[1]);
                return info;
            }
            return null;
        });
        SuperiorSkyblock plugin = proxy(SuperiorSkyblock.class, (instance, method, args) -> {
            if ("getProviders".equals(method.getName())) return providers;
            if ("getGrid".equals(method.getName())) return grid;
            return null;
        });
        World current = actualWorld == null ? null : proxy(World.class, (instance, method, args) ->
                "getName".equals(method.getName()) ? actualWorld : null);
        Island island = proxy(Island.class, (instance, method, args) -> null);
        SuperiorPlayer player = proxy(SuperiorPlayer.class, (instance, method, args) -> {
            if ("getWorld".equals(method.getName())) return current;
            if ("getIsland".equals(method.getName())) return island;
            return null;
        });
        return new TestMission(plugin, player);
    }

    private static <T> T proxy(Class<T> type, InvocationHandler handler) {
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, handler));
    }

    private static final class TestMission extends BuiltinMission<Integer> {
        private final SuperiorPlayer actor;
        private TestMission(SuperiorSkyblock owner, SuperiorPlayer actor) { this.plugin = owner; this.actor = actor; }
        private boolean available(Predicate<String> available) { return progressWorldsAvailable(actor, available); }
        @Override protected void loadConfiguration(ConfigurationSection section) { }
        @Override protected void registerListeners() { }
        @Override protected void clearModuleData(Integer value) { }
        @Override public double getProgress(SuperiorPlayer player) { return 0; }
    }
}

package com.bgsoftware.superiorskyblock.pvc.island;

import com.bgsoftware.superiorskyblock.api.island.Island;
import com.bgsoftware.superiorskyblock.api.island.IslandExecutionScope;
import com.bgsoftware.superiorskyblock.api.missions.Mission;
import com.bgsoftware.superiorskyblock.api.wrappers.SuperiorPlayer;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.plugin.java.JavaPlugin;
import org.junit.Test;
import java.lang.reflect.Proxy;
import java.util.UUID;
import static org.junit.Assert.*;

public class MissionIslandSubjectTest {
    @Test public void oneOwnerHasIndependentIslandProgressAndOwnershipTransferPreservesIt() {
        CounterMission mission = new CounterMission(); mission.setIslandMission(true);
        SuperiorPlayer owner = player(), newOwner = player();
        Island first = IslandExecutionScopeTest.island(), second = IslandExecutionScopeTest.island();
        try (IslandExecutionScope ignored = IslandExecutionScope.open(first)) { mission.insertData(owner, 5); }
        try (IslandExecutionScope ignored = IslandExecutionScope.open(second)) { mission.insertData(owner, 9); }
        mission.transferData(owner, newOwner);
        try (IslandExecutionScope ignored = IslandExecutionScope.open(first)) {
            assertEquals(Integer.valueOf(5), mission.get(newOwner)); mission.clearData(newOwner);
        }
        try (IslandExecutionScope ignored = IslandExecutionScope.open(second)) { assertEquals(Integer.valueOf(9), mission.get(owner)); }
    }
    @Test public void playerMissionsRemainPerPlayerAcrossIslands() {
        CounterMission mission = new CounterMission(); SuperiorPlayer player = player();
        try (IslandExecutionScope ignored = IslandExecutionScope.open(IslandExecutionScopeTest.island())) { mission.insertData(player, 3); }
        try (IslandExecutionScope ignored = IslandExecutionScope.open(IslandExecutionScopeTest.island())) { assertEquals(Integer.valueOf(3), mission.get(player)); }
    }
    @Test public void noTargetCannotWriteProgressIntoAnotherIsland() {
        CounterMission mission = new CounterMission(); mission.setIslandMission(true);
        SuperiorPlayer player = player(); Island island = IslandExecutionScopeTest.island();
        try (IslandExecutionScope ignored = IslandExecutionScope.open(island)) { mission.insertData(player, 4); }
        try (IslandExecutionScope ignored = IslandExecutionScope.open(null)) { mission.insertData(player, 99); assertNull(mission.get(player)); }
        try (IslandExecutionScope ignored = IslandExecutionScope.open(island)) { assertEquals(Integer.valueOf(4), mission.get(player)); }
    }
    private static SuperiorPlayer player() {
        UUID id = UUID.randomUUID();
        return (SuperiorPlayer) Proxy.newProxyInstance(SuperiorPlayer.class.getClassLoader(), new Class<?>[]{SuperiorPlayer.class},
                (proxy, method, args) -> {
                    if ("getIsland".equals(method.getName())) return IslandExecutionScope.current();
                    if ("getUniqueId".equals(method.getName())) return id;
                    if ("hashCode".equals(method.getName())) return id.hashCode();
                    if ("equals".equals(method.getName())) return proxy == args[0];
                    return null;
                });
    }
    private static final class CounterMission extends Mission<Integer> {
        @Override public boolean supportsIslandSubjects() { return true; }
        @Override public void load(JavaPlugin plugin, ConfigurationSection section) { }
        @Override public double getProgress(SuperiorPlayer player) { Integer value = get(player); return value == null ? 0 : value; }
        @Override public void onComplete(SuperiorPlayer player) { }
        @Override public void onCompleteFail(SuperiorPlayer player) { }
    }
}

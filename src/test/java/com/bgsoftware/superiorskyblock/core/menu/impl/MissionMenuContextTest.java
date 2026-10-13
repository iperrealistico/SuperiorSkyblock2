package com.bgsoftware.superiorskyblock.core.menu.impl;

import com.bgsoftware.superiorskyblock.api.missions.Mission;
import com.bgsoftware.superiorskyblock.api.missions.MissionCategory;
import com.bgsoftware.superiorskyblock.api.wrappers.SuperiorPlayer;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.plugin.java.JavaPlugin;
import org.junit.Test;
import java.lang.reflect.Proxy;
import java.util.Arrays;
import static org.junit.Assert.*;

public class MissionMenuContextTest {
    @Test public void onlyPersonalOrEmptyCategoriesDoNotCaptureAnIsland() {
        assertFalse(MenuMissionsCategory.requiresIslandContext(category()));
        assertFalse(MenuMissionsCategory.requiresIslandContext(category(mission(false), mission(false))));
    }
    @Test public void mixedCategoriesPreserveTheirIslandTargetEvenWhenTheyAlsoContainPersonalMissions() {
        assertTrue(MenuMissionsCategory.requiresIslandContext(category(mission(false), mission(true))));
        assertTrue(MenuMissionsCategory.requiresIslandContext(category(mission(true))));
    }
    private static MissionCategory category(Mission<?>... missions) {
        return (MissionCategory) Proxy.newProxyInstance(MissionCategory.class.getClassLoader(), new Class<?>[]{MissionCategory.class},
                (proxy, method, args) -> "getMissions".equals(method.getName()) ? Arrays.asList(missions) : null);
    }
    private static Mission<?> mission(boolean island) {
        Mission<Void> mission = new Mission<Void>() {
            @Override public void load(JavaPlugin plugin, ConfigurationSection config) { }
            @Override public double getProgress(SuperiorPlayer player) { return 0; }
            @Override public void onComplete(SuperiorPlayer player) { }
            @Override public void onCompleteFail(SuperiorPlayer player) { }
        };
        mission.setIslandMission(island); return mission;
    }
}

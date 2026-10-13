package com.bgsoftware.superiorskyblock.island;

import org.junit.Test;
import com.bgsoftware.superiorskyblock.api.island.Island;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayDeque;
import java.util.Map;
import java.util.UUID;
import static org.junit.Assert.*;

public class CropRefreshCompletionTest {
    @Test public void shutdownRejectsLateAdmissionsWithoutSchedulingWork() throws Exception {
        IslandUtils.closeCropRefreshes();
        IslandUtils.recalibrateLoadedCropChunks(null);
        Field jobs = IslandUtils.class.getDeclaredField("cropRefreshJobs"); jobs.setAccessible(true);
        assertTrue(((Map<?, ?>) jobs.get(null)).isEmpty());
        IslandUtils.openCropRefreshes();
        Field closed = IslandUtils.class.getDeclaredField("cropRefreshClosed"); closed.setAccessible(true);
        assertFalse(closed.getBoolean(null));
        IslandUtils.closeCropRefreshes();
    }
    @Test public void jobRemainsAdmittedUntilItsLastPhysicalCallbackCompletes() throws Exception {
        IslandUtils.closeCropRefreshes();
        try {
            UUID identity = UUID.randomUUID();
            Island island = (Island) Proxy.newProxyInstance(Island.class.getClassLoader(), new Class<?>[]{Island.class},
                    (proxy, method, args) -> method.getName().equals("getUniqueId") ? identity : null);
            Class<?> type = Class.forName("com.bgsoftware.superiorskyblock.island.IslandUtils$CropRefreshJob");
            Constructor<?> constructor = type.getDeclaredConstructor(Island.class); constructor.setAccessible(true);
            Object job = constructor.newInstance(island);
            Field positions = type.getDeclaredField("positions"); positions.setAccessible(true); positions.set(job, new ArrayDeque<>());
            Field outstanding = type.getDeclaredField("outstanding"); outstanding.setAccessible(true); outstanding.setInt(job, 2);
            Field jobs = IslandUtils.class.getDeclaredField("cropRefreshJobs"); jobs.setAccessible(true);
            ((Map<UUID, Object>) jobs.get(null)).put(identity, job);
            Field active = IslandUtils.class.getDeclaredField("cropRefreshOutstanding"); active.setAccessible(true); active.setInt(null, 2);
            Method complete = IslandUtils.class.getDeclaredMethod("completeCropRefreshWork", type); complete.setAccessible(true);
            complete.invoke(null, job);
            assertEquals(1, active.getInt(null)); assertTrue(((Map<?, ?>) jobs.get(null)).containsKey(identity));
            complete.invoke(null, job);
            assertEquals(0, active.getInt(null)); assertFalse(((Map<?, ?>) jobs.get(null)).containsKey(identity));
        } finally { IslandUtils.closeCropRefreshes(); }
    }
    @Test public void retiredCallbackCannotConsumeANewLifecycleBudget() throws Exception {
        IslandUtils.closeCropRefreshes();
        Class<?> type = Class.forName("com.bgsoftware.superiorskyblock.island.IslandUtils$CropRefreshJob");
        Constructor<?> constructor = type.getDeclaredConstructor(Island.class); constructor.setAccessible(true);
        Object retired = constructor.newInstance((Island) null);
        IslandUtils.closeCropRefreshes();
        Field active = IslandUtils.class.getDeclaredField("cropRefreshOutstanding"); active.setAccessible(true); active.setInt(null, 1);
        Method complete = IslandUtils.class.getDeclaredMethod("completeCropRefreshWork", type); complete.setAccessible(true);
        try { complete.invoke(null, retired); assertEquals(1, active.getInt(null)); }
        finally { IslandUtils.closeCropRefreshes(); }
    }
}

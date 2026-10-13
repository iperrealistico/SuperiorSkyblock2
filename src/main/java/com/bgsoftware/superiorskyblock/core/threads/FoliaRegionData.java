package com.bgsoftware.superiorskyblock.core.threads;

import java.lang.reflect.Method;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/** NMS-free access to Folia's current region data, valid only on its owning thread. */
public final class FoliaRegionData {
    private static final ConcurrentMap<Class<?>, Method> currentData = new ConcurrentHashMap<>();
    private static final ConcurrentMap<Class<?>, Method> tickerLists = new ConcurrentHashMap<>();
    private FoliaRegionData() { }
    private static Method method(ConcurrentMap<Class<?>, Method> cache, Class<?> type, String name) {
        return cache.computeIfAbsent(type, key -> {
            try { return key.getMethod(name); }
            catch (ReflectiveOperationException error) { throw new IllegalStateException("Unsupported Folia region accessor: " + name, error); }
        });
    }
    @SuppressWarnings("unchecked")
    public static <T> List<T> blockEntityTickers(Object level) {
        try {
            Object data = method(currentData, level.getClass(), "getCurrentWorldData").invoke(level);
            if (data == null) throw new IllegalStateException("No current Folia region owns this operation");
            return (List<T>) method(tickerLists, data.getClass(), "getBlockEntityTickers").invoke(data);
        } catch (ReflectiveOperationException error) { throw new IllegalStateException("Folia regional ticker access failed", error); }
    }
}

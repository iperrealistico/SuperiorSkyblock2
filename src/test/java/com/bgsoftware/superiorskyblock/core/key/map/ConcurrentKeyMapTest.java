package com.bgsoftware.superiorskyblock.core.key.map;

import com.bgsoftware.superiorskyblock.api.key.Key;
import com.bgsoftware.superiorskyblock.api.key.KeyMap;
import com.bgsoftware.superiorskyblock.core.LazyReference;
import com.bgsoftware.superiorskyblock.core.key.KeyIndicator;
import com.bgsoftware.superiorskyblock.core.key.types.CustomKey;
import com.bgsoftware.superiorskyblock.core.key.types.EntityTypeKey;
import com.bgsoftware.superiorskyblock.core.key.types.LazyKey;
import org.bukkit.entity.EntityType;
import org.junit.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.*;

/** Finite correctness schedules, not a benchmark or server load test. */
public class ConcurrentKeyMapTest {
    @Test public void mergeAndComputeAreAtomicAcrossTypedAndCustomBuckets() throws Exception {
        KeyMap<Integer> map = KeyMaps.createConcurrentHashMap(KeyIndicator.ENTITY_TYPE);
        Key type = EntityTypeKey.of(EntityType.COW), custom = key("widget", null);
        race(6, () -> {
            for (int i = 0; i < 400; ++i) {
                map.merge(type, 1, Integer::sum);
                map.compute(custom, (k, v) -> v == null ? 1 : v + 1);
            }
        });
        assertEquals(Integer.valueOf(2400), map.getRaw(type, -1));
        assertEquals(Integer.valueOf(2400), map.getRaw(custom, -1));
        assertEquals(2, map.size());
        map.removeIf(k -> k.equals(custom)); assertEquals(1, map.size());
        map.entrySet().iterator().next();
        java.util.Iterator<java.util.Map.Entry<Key, Integer>> iterator = map.entrySet().iterator();
        iterator.next(); iterator.remove(); assertTrue(map.isEmpty());
    }

    @Test public void exactMutationsNeverReadTheGlobalFallback() {
        KeyMap<Integer> map = KeyMaps.createConcurrentHashMap(KeyIndicator.MATERIAL);
        Key global = key("widget", null), exact = key("widget", "red");
        map.put(global, 20);
        assertEquals(Integer.valueOf(20), map.get(exact));
        assertEquals(Integer.valueOf(-1), map.getRaw(exact, -1));
        map.compute(exact, (k, old) -> { assertNull(old); return 2; });
        assertEquals(Integer.valueOf(20), map.get(global));
        assertEquals(Integer.valueOf(2), map.getRaw(exact, -1));
        assertTrue(map.replace(exact, 2, 3)); assertTrue(map.remove(exact, 3));
        assertEquals(Integer.valueOf(20), map.get(exact));
        Key missing = key("absent", null);
        LazyKey<CustomKey> lazy = new LazyKey<>(CustomKey.class, new LazyReference<CustomKey>() {
            @Override protected CustomKey create() { return (CustomKey) missing; }
        });
        assertSame(global, map.getKey(lazy, global));
    }

    @Test public void lazyPromotionCannotLoseConcurrentCustomWrites() throws Exception {
        KeyMap<Integer> map = KeyMaps.createConcurrentHashMap(KeyIndicator.CUSTOM);
        Key custom = key("widget", null), type = EntityTypeKey.of(EntityType.COW);
        map.put(custom, 1);
        race(6, () -> {
            for (int i = 0; i < 200; ++i) {
                map.merge(custom, 1, Integer::sum);
                map.computeIfAbsent(type, k -> 1);
            }
        });
        assertEquals(Integer.valueOf(1201), map.getRaw(custom, -1));
        assertEquals(Integer.valueOf(1), map.get(type)); assertEquals(2, map.size());
    }

    @Test public void lazyReferencePublishesOnlyOneInstance() throws Exception {
        AtomicInteger creates = new AtomicInteger();
        LazyReference<Object> reference = new LazyReference<Object>() {
            @Override protected Object create() { creates.incrementAndGet(); return new Object(); }
        };
        AtomicReference<Object> identity = new AtomicReference<>();
        race(6, () -> {
            Object value = reference.get(); identity.compareAndSet(null, value); assertSame(identity.get(), value);
        });
        assertEquals(1, creates.get()); assertSame(identity.get(), reference.getIfPresent().get());
    }

    @Test public void escapedMapAndEntryViewsStillWriteTheCurrentMapAfterPromotion() {
        KeyMap<Integer> map = KeyMaps.createConcurrentHashMap(KeyIndicator.CUSTOM);
        Key custom = key("widget", null), type = EntityTypeKey.of(EntityType.COW);
        map.put(custom, 1);
        java.util.Map<Key, Integer> view = map.asMap();
        java.util.Set<java.util.Map.Entry<Key, Integer>> entries = map.entrySet();
        java.util.Iterator<java.util.Map.Entry<Key, Integer>> iterator = entries.iterator();
        java.util.Map.Entry<Key, Integer> oldEntry = iterator.next();
        map.put(type, 1);
        view.merge(custom, 1, Integer::sum); assertEquals(Integer.valueOf(2), map.getRaw(custom, -1));
        oldEntry.setValue(3); assertEquals(Integer.valueOf(3), map.getRaw(custom, -1));
        iterator.remove(); assertEquals(Integer.valueOf(-1), map.getRaw(custom, -1));
        assertEquals(1, entries.size()); assertSame(view, map.asMap());
    }

    private static CustomKey key(String global, String sub) { return CustomKey.of(global, sub, KeyIndicator.CUSTOM); }
    private static void race(int count, Runnable work) throws Exception {
        CountDownLatch start = new CountDownLatch(1), done = new CountDownLatch(count);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        for (int i = 0; i < count; ++i) new Thread(() -> {
            try { start.await(); work.run(); } catch (Throwable error) { failure.compareAndSet(null, error); }
            finally { done.countDown(); }
        }, "key-map-correctness-" + i).start();
        start.countDown(); assertTrue(done.await(15, TimeUnit.SECONDS));
        if (failure.get() != null) throw new AssertionError(failure.get());
    }
}

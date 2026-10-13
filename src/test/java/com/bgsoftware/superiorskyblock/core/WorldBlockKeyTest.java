package com.bgsoftware.superiorskyblock.core;

import org.junit.Test;
import org.bukkit.Location;
import org.bukkit.World;
import java.lang.reflect.Proxy;
import java.util.UUID;
import static org.junit.Assert.*;

public class WorldBlockKeyTest {
    @Test public void locationsAreDetachedAndWorldsNeverAlias() {
        UUID identity = UUID.randomUUID();
        World world = (World) Proxy.newProxyInstance(World.class.getClassLoader(), new Class<?>[]{World.class},
                (proxy, method, args) -> method.getName().equals("getUID") ? identity : null);
        Location original = new Location(world, -0.1, 64.9, 15.99);
        WorldBlockKey key = WorldBlockKey.of(original);
        original.add(100, 100, 100);
        assertEquals(new WorldBlockKey(identity, -1, 64, 15), key);
        assertNotEquals(new WorldBlockKey(UUID.randomUUID(), -1, 64, 15), key);
    }
    @Test public void expiringDedupeHasOneAtomicWinner() throws Exception {
        com.google.common.cache.Cache<WorldBlockKey, Boolean> cache = com.google.common.cache.CacheBuilder.newBuilder()
                .maximumSize(16).expireAfterWrite(1, java.util.concurrent.TimeUnit.SECONDS).build();
        WorldBlockKey key = new WorldBlockKey(UUID.randomUUID(), 1, 2, 3);
        java.util.concurrent.CountDownLatch start = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.atomic.AtomicInteger winners = new java.util.concurrent.atomic.AtomicInteger();
        Thread first = new Thread(() -> claim(cache, key, start, winners));
        Thread second = new Thread(() -> claim(cache, key, start, winners));
        first.start(); second.start(); start.countDown(); first.join(1000); second.join(1000);
        assertFalse(first.isAlive()); assertFalse(second.isAlive()); assertEquals(1, winners.get());
    }
    private void claim(com.google.common.cache.Cache<WorldBlockKey, Boolean> cache, WorldBlockKey key,
                       java.util.concurrent.CountDownLatch start, java.util.concurrent.atomic.AtomicInteger winners) {
        try { start.await(); if (cache.asMap().putIfAbsent(key, Boolean.TRUE) == null) winners.incrementAndGet(); }
        catch (InterruptedException stopped) { Thread.currentThread().interrupt(); }
    }
}

package com.bgsoftware.superiorskyblock.pvc.entitlement;

import org.junit.Test;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import static org.junit.Assert.*;

public class PvcPolicyStoreTest {
    @Test public void durableOverridesAndLastKnownSnapshotsRemainDistinctAfterRestart() throws Exception {
        Path file = Files.createTempDirectory("superior-owner-policy").resolve("owner-policy.properties");
        UUID owner = UUID.randomUUID();
        PvcPolicyStore store = new PvcPolicyStore(file, 8);
        store.setOverride(owner, 800);
        Map<UUID, EntitlementSnapshot> batch = new LinkedHashMap<>();
        batch.put(owner, new EntitlementSnapshot(99, 1000, 12345, 4, true));
        store.remember(batch);
        PvcPolicyStore restarted = new PvcPolicyStore(file, 8);
        assertEquals(Integer.valueOf(800), restarted.getOverride(owner));
        assertEquals(1000, restarted.getLastKnown(owner).getRadius());
        assertFalse(restarted.getLastKnown(owner).isAuthoritative());
        restarted.setOverride(owner, null);
        assertNull(new PvcPolicyStore(file, 8).getOverride(owner));
        assertTrue(Files.exists(file.resolveSibling("owner-policy.properties.bak")));
    }
    @Test public void boundedDerivedHistoryNeverEvictsRealOwnerOverrides() throws Exception {
        Path file = Files.createTempDirectory("superior-owner-history").resolve("owner-policy.properties");
        UUID first = UUID.randomUUID(), second = UUID.randomUUID();
        PvcPolicyStore store = new PvcPolicyStore(file, 1);
        store.setOverride(first, 700);
        Map<UUID, EntitlementSnapshot> batch = new LinkedHashMap<>();
        batch.put(first, new EntitlementSnapshot(1, 100, 1, 1, true));
        batch.put(second, new EntitlementSnapshot(2, 200, 2, 2, true));
        store.remember(batch);
        PvcPolicyStore restarted = new PvcPolicyStore(file, 1);
        assertEquals(Integer.valueOf(700), restarted.getOverride(first));
        assertNull(restarted.getLastKnown(first)); assertEquals(200, restarted.getLastKnown(second).getRadius());
    }
    @Test public void malformedExistingOverrideIsNeverDiscarded() throws Exception {
        Path file = Files.createTempDirectory("superior-owner-invalid").resolve("owner-policy.properties");
        byte[] original = ("format=1\noverride." + UUID.randomUUID() + "=broken\n").getBytes(StandardCharsets.UTF_8);
        Files.write(file, original);
        try { new PvcPolicyStore(file, 8); fail("Malformed policy must fail closed"); }
        catch (java.io.IOException expected) { assertArrayEquals(original, Files.readAllBytes(file)); }
    }
    @Test public void failedDurableWriteDoesNotPublishAnOverride() throws Exception {
        Path parent = Files.createTempFile("superior-owner-blocked", ".file");
        UUID owner = UUID.randomUUID();
        PvcPolicyStore store = new PvcPolicyStore(parent.resolve("owner-policy.properties"), 8);
        try { store.setOverride(owner, 900); fail("A regular file cannot contain a policy file"); }
        catch (java.io.IOException expected) { assertNull(store.getOverride(owner)); }
    }
    @Test public void permissionCacheCannotBecomeAFixedDonorOverride() throws Exception {
        Path file = Files.createTempDirectory("superior-donor-cache").resolve("owner-policy.properties");
        UUID owner = UUID.randomUUID();
        PvcPolicyStore store = new PvcPolicyStore(file, 8);
        Map<UUID, EntitlementSnapshot> batch = new LinkedHashMap<>();
        batch.put(owner, new EntitlementSnapshot(1, 1000, 10, 1, true)); store.remember(batch);
        batch.put(owner, new EntitlementSnapshot(1, 200, 20, 2, true)); store.remember(batch);
        PvcPolicyStore restarted = new PvcPolicyStore(file, 8);
        assertNull(restarted.getOverride(owner)); assertEquals(200, restarted.getLastKnown(owner).getRadius());
    }

    @Test public void concurrentAdditiveOverridesRetainEveryIncrementAfterRestart() throws Exception {
        Path file = Files.createTempDirectory("superior-additive-overrides").resolve("owner-policy.properties");
        UUID owner = UUID.randomUUID();
        PvcPolicyStore store = new PvcPolicyStore(file, 8);
        store.setOverride(owner, 100);
        java.util.concurrent.ExecutorService workers = java.util.concurrent.Executors.newFixedThreadPool(4);
        java.util.concurrent.CountDownLatch start = new java.util.concurrent.CountDownLatch(1);
        java.util.List<java.util.concurrent.Future<?>> results = new java.util.ArrayList<>();
        try {
            for (int worker = 0; worker < 4; worker++) results.add(workers.submit(() -> {
                start.await();
                for (int increment = 0; increment < 3; increment++) store.addOverride(owner, 1, 1, 1000);
                return null;
            }));
            start.countDown();
            for (java.util.concurrent.Future<?> result : results) result.get(5, java.util.concurrent.TimeUnit.SECONDS);
        } finally { workers.shutdownNow(); }
        assertEquals(Integer.valueOf(112), store.getOverride(owner));
        assertEquals(Integer.valueOf(112), new PvcPolicyStore(file, 8).getOverride(owner));
    }

    @Test public void additiveOverrideUsesVerifiedFallbackAndRejectsOverflowWithoutChangingStorage() throws Exception {
        Path file = Files.createTempDirectory("superior-additive-cap").resolve("owner-policy.properties");
        UUID owner = UUID.randomUUID();
        PvcPolicyStore store = new PvcPolicyStore(file, 8);
        assertEquals(220, store.addOverride(owner, 200, 20, 1000));
        byte[] before = Files.readAllBytes(file);
        try { store.addOverride(owner, 999, Integer.MAX_VALUE, 1000); fail("Overflow must not wrap into an allowed radius"); }
        catch (IllegalArgumentException expected) { }
        assertArrayEquals(before, Files.readAllBytes(file));
        assertEquals(Integer.valueOf(220), new PvcPolicyStore(file, 8).getOverride(owner));
        assertEquals(210, store.addOverride(owner, 1, -10, 1000));
    }
}

package com.bgsoftware.superiorskyblock.pvc.entitlement;

import org.junit.Test;
import java.util.HashMap;
import java.util.Map;
import static org.junit.Assert.*;

public class EntitlementPolicyTest {
    @Test public void noGrantUsesOneAndConfiguredDefault() {
        EntitlementPolicy.Values values = EntitlementPolicy.resolve(new HashMap<>(), 100, 1000);
        assertEquals(1, values.getOwnershipLimit()); assertEquals(100, values.getRadius());
    }
    @Test public void highestNumericGrantsWinWithoutSumming() {
        Map<String, Boolean> grants = new HashMap<>();
        grants.put(EntitlementPolicy.OWNERSHIP_PREFIX + "2", true);
        grants.put(EntitlementPolicy.OWNERSHIP_PREFIX + "99", true);
        grants.put(EntitlementPolicy.RADIUS_PREFIX + "100", true);
        grants.put(EntitlementPolicy.RADIUS_PREFIX + "800", true);
        EntitlementPolicy.Values values = EntitlementPolicy.resolve(grants, 20, 1000);
        assertEquals(99, values.getOwnershipLimit()); assertEquals(800, values.getRadius());
    }
    @Test public void explicitFalseAndMalformedNodesNeverGrant() {
        Map<String, Boolean> grants = new HashMap<>();
        grants.put(EntitlementPolicy.OWNERSHIP_PREFIX + "99", false);
        grants.put(EntitlementPolicy.OWNERSHIP_PREFIX + "2147483648", true);
        grants.put(EntitlementPolicy.OWNERSHIP_PREFIX + "-1", true);
        grants.put(EntitlementPolicy.OWNERSHIP_PREFIX + "01", true);
        grants.put(EntitlementPolicy.RADIUS_PREFIX + "999999999999999999", true);
        grants.put(EntitlementPolicy.RADIUS_PREFIX + "0", true);
        grants.put(EntitlementPolicy.RADIUS_PREFIX + "donor", true);
        assertEquals(1, EntitlementPolicy.resolve(grants, 100, 1000).getOwnershipLimit());
        assertEquals(100, EntitlementPolicy.resolve(grants, 100, 1000).getRadius());
    }
    @Test public void grantBelowDefaultIsAuthoritativeAndHardCapCannotBeExceeded() {
        Map<String, Boolean> grants = new HashMap<>();
        grants.put(EntitlementPolicy.RADIUS_PREFIX + "50", true);
        assertEquals(50, EntitlementPolicy.resolve(grants, 100, 1000).getRadius());
        grants.put(EntitlementPolicy.RADIUS_PREFIX + "4000", true);
        assertEquals(1000, EntitlementPolicy.resolve(grants, 100, 1000).getRadius());
    }
    @Test public void donorRemovalReturnsToRemainingRankWithoutArithmetic() {
        Map<String, Boolean> grants = new HashMap<>();
        grants.put(EntitlementPolicy.RADIUS_PREFIX + "200", true);
        grants.put(EntitlementPolicy.RADIUS_PREFIX + "1000", true);
        assertEquals(1000, EntitlementPolicy.resolve(grants, 100, 1000).getRadius());
        grants.remove(EntitlementPolicy.RADIUS_PREFIX + "1000");
        assertEquals(200, EntitlementPolicy.resolve(grants, 100, 1000).getRadius());
    }
    @Test public void unlimitedIsDistinctAndMustBeResolvedTrue() {
        Map<String, Boolean> grants = new HashMap<>();
        grants.put(EntitlementPolicy.OWNERSHIP_PREFIX + "99", true);
        grants.put(EntitlementPolicy.UNLIMITED, false);
        assertEquals(99, EntitlementPolicy.resolve(grants, 100, 1000).getOwnershipLimit());
        grants.put(EntitlementPolicy.UNLIMITED, true);
        assertEquals(Integer.MAX_VALUE, EntitlementPolicy.resolve(grants, 100, 1000).getOwnershipLimit());
    }
    @Test public void unexpandedWildcardDoesNotInventAnInfiniteNumericSequence() {
        Map<String, Boolean> grants = new HashMap<>();
        grants.put(EntitlementPolicy.RADIUS_PREFIX + "*", true);
        grants.put("*", true);
        assertEquals(100, EntitlementPolicy.resolve(grants, 100, 1000).getRadius());
    }
    @Test public void lastKnownSnapshotNeverAuthorizesAdmission() {
        EntitlementSnapshot fresh = new EntitlementSnapshot(99, 1000, 10000, 4, true);
        assertTrue(fresh.isFresh(10001, 30000));
        assertFalse(fresh.lastKnown().isFresh(10001, 30000));
        assertFalse(fresh.isFresh(9999, 30000));
        assertFalse(fresh.isFresh(40000, 30000));
        assertEquals(1000, fresh.lastKnown().getRadius());
    }
    @Test public void cacheIsBoundedAndRecentlyReadOwnerSurvivesEviction() {
        PvcOwnerCache cache = new PvcOwnerCache();
        java.util.UUID first = java.util.UUID.randomUUID(), second = java.util.UUID.randomUUID(), third = java.util.UUID.randomUUID();
        EntitlementSnapshot value = new EntitlementSnapshot(1, 100, 1, 1, true);
        cache.put(first, value, 1, 2); cache.put(second, value, 2, 2);
        cache.get(first, 4); cache.put(third, value, 5, 2);
        assertEquals(2, cache.size()); assertNotNull(cache.peek(first)); assertNull(cache.peek(second));
    }
    @Test public void tableDrivenNumericQuotaAndRadiusResolution() {
        String[] suffixes = {"1", "2", "99", "2147483647", "2147483648", "-2", "0", "2.5", "01"};
        int[] expectedQuotas = {1, 2, 99, Integer.MAX_VALUE, 1, 1, 1, 1, 1};
        int[] expectedRadii = {1, 2, 99, 1000, 100, 100, 100, 100, 100};
        for (int i = 0; i < suffixes.length; ++i) {
            Map<String, Boolean> grants = new HashMap<>();
            grants.put(EntitlementPolicy.OWNERSHIP_PREFIX + suffixes[i], true);
            grants.put(EntitlementPolicy.RADIUS_PREFIX + suffixes[i], true);
            EntitlementPolicy.Values values = EntitlementPolicy.resolve(grants, 100, 1000);
            assertEquals(suffixes[i], expectedQuotas[i], values.getOwnershipLimit());
            assertEquals(suffixes[i], expectedRadii[i], values.getRadius());
        }
    }
    @Test public void concurrentPublicationAndEvictionNeverExceedCapacity() throws Exception {
        PvcOwnerCache cache = new PvcOwnerCache();
        java.util.concurrent.ExecutorService workers = java.util.concurrent.Executors.newFixedThreadPool(4);
        java.util.List<java.util.concurrent.Future<?>> tasks = new java.util.ArrayList<>();
        try {
            for (int worker = 0; worker < 4; ++worker) {
                tasks.add(workers.submit(() -> {
                    for (int i = 0; i < 500; ++i) {
                        java.util.UUID owner = java.util.UUID.randomUUID();
                        cache.put(owner, new EntitlementSnapshot(99, 1000, i, i, true), i, 64);
                        cache.get(owner, i + 1);
                        assertTrue(cache.size() <= 64);
                    }
                }));
            }
            for (java.util.concurrent.Future<?> task : tasks) task.get(5, java.util.concurrent.TimeUnit.SECONDS);
            assertEquals(64, cache.size());
        } finally { workers.shutdownNow(); }
    }
}

package com.bgsoftware.superiorskyblock.island.algorithm;

import com.bgsoftware.superiorskyblock.api.key.Key;
import com.bgsoftware.superiorskyblock.api.key.KeyMap;
import com.bgsoftware.superiorskyblock.core.key.KeyIndicator;
import com.bgsoftware.superiorskyblock.core.key.map.KeyMaps;
import com.bgsoftware.superiorskyblock.core.key.types.CustomKey;
import org.junit.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static org.junit.Assert.*;

public class EntityLimitChangeRetirementTest {
    private static final Key KEY = CustomKey.of("LIMITED_ENTITY", null, KeyIndicator.CUSTOM);

    @Test public void removingALimitAfterSpawnStillRetiresBothCountsAndUuidsBeforeTheLimitIsAddedAgain() {
        KeyMap<Integer> counts = KeyMaps.createConcurrentHashMap(KeyIndicator.ENTITY_TYPE);
        Map<UUID, Key> identities = new HashMap<>();
        KeyMap<Integer> identityCounts = KeyMaps.createConcurrentHashMap(KeyIndicator.ENTITY_TYPE);
        UUID first = UUID.randomUUID(), second = UUID.randomUUID();
        counts.put(KEY, 2); identityCounts.put(KEY, 2); identities.put(first, KEY); identities.put(second, KEY);
        // The configured limit was removed: ordinary new entities of this type are no longer tracked.
        assertTrue(DefaultIslandEntitiesTrackerAlgorithm.applyEntityRemoval(counts, identities, identityCounts, KEY, 1, first, false));
        assertEquals(Integer.valueOf(1), counts.getRaw(KEY, 0)); assertFalse(identities.containsKey(first));
        assertTrue(DefaultIslandEntitiesTrackerAlgorithm.applyEntityRemoval(counts, identities, identityCounts, KEY, 1, second, false));
        assertEquals(Integer.valueOf(0), counts.getRaw(KEY, 0));
        assertTrue(identities.isEmpty()); assertTrue(identityCounts.isEmpty());
        // Re-adding a limit of one must permit a fresh entity: no dead counter or UUID survives.
        EntitySlotReservations slots = new EntitySlotReservations(new Object());
        assertTrue(slots.reserve(UUID.randomUUID(), KEY, counts.getRaw(KEY, 0), 1));
    }

    @Test public void numericCountsLoadedBeforeUuidObservationAreAlsoRetiredWhileUntrackedTypesStayUntouched() {
        KeyMap<Integer> counts = KeyMaps.createConcurrentHashMap(KeyIndicator.ENTITY_TYPE);
        Map<UUID, Key> identities = new HashMap<>();
        KeyMap<Integer> identityCounts = KeyMaps.createConcurrentHashMap(KeyIndicator.ENTITY_TYPE);
        counts.put(KEY, 2);
        assertTrue(DefaultIslandEntitiesTrackerAlgorithm.applyEntityRemoval(counts, identities, identityCounts,
                KEY, 1, UUID.randomUUID(), false));
        assertEquals(Integer.valueOf(1), counts.getRaw(KEY, 0)); assertTrue(identityCounts.isEmpty());
        assertTrue(DefaultIslandEntitiesTrackerAlgorithm.applyEntityRemoval(counts, identities, identityCounts, KEY, 1, null, false));
        assertTrue(counts.isEmpty());
        assertFalse(DefaultIslandEntitiesTrackerAlgorithm.applyEntityRemoval(counts, identities, identityCounts, KEY, 1, UUID.randomUUID(), false));
        assertTrue(counts.isEmpty()); assertTrue(identities.isEmpty());
    }

    @Test public void untrackedUuidCannotRetireFullyAttributedCountsAfterLimitRemoval() {
        KeyMap<Integer> counts = KeyMaps.createConcurrentHashMap(KeyIndicator.ENTITY_TYPE);
        KeyMap<Integer> identityCounts = KeyMaps.createConcurrentHashMap(KeyIndicator.ENTITY_TYPE);
        Map<UUID, Key> identities = new HashMap<>();
        UUID first = UUID.randomUUID(), second = UUID.randomUUID();
        counts.put(KEY, 2); identityCounts.put(KEY, 2); identities.put(first, KEY); identities.put(second, KEY);
        assertFalse(DefaultIslandEntitiesTrackerAlgorithm.applyEntityRemoval(counts, identities, identityCounts,
                KEY, 1, UUID.randomUUID(), false));
        assertEquals(Integer.valueOf(2), counts.getRaw(KEY, 0));
        assertEquals(Integer.valueOf(2), identityCounts.getRaw(KEY, 0));
        assertEquals(2, identities.size());
    }

    @Test public void duplicateDeathDoesNotRetireAnotherKnownEntity() {
        KeyMap<Integer> counts = KeyMaps.createConcurrentHashMap(KeyIndicator.ENTITY_TYPE);
        KeyMap<Integer> identityCounts = KeyMaps.createConcurrentHashMap(KeyIndicator.ENTITY_TYPE);
        Map<UUID, Key> identities = new HashMap<>();
        UUID first = UUID.randomUUID(), second = UUID.randomUUID();
        counts.put(KEY, 2); identityCounts.put(KEY, 2); identities.put(first, KEY); identities.put(second, KEY);
        assertTrue(DefaultIslandEntitiesTrackerAlgorithm.applyEntityRemoval(counts, identities, identityCounts,
                KEY, 1, first, false));
        assertFalse(DefaultIslandEntitiesTrackerAlgorithm.applyEntityRemoval(counts, identities, identityCounts,
                KEY, 1, first, false));
        assertEquals(Integer.valueOf(1), counts.getRaw(KEY, 0));
        assertEquals(Integer.valueOf(1), identityCounts.getRaw(KEY, 0));
        assertEquals(1, identities.size()); assertEquals(KEY, identities.get(second));
    }
}

package com.bgsoftware.superiorskyblock.island.algorithm;

import com.bgsoftware.superiorskyblock.api.key.Key;
import com.bgsoftware.superiorskyblock.core.key.KeyIndicator;
import com.bgsoftware.superiorskyblock.core.key.types.CustomKey;
import org.junit.Test;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.*;

public class EntityCountReconciliationTest {
    private static final Key KEY = CustomKey.of("TRACKED", null, KeyIndicator.CUSTOM);

    @Test public void spawnThenMigrationIntoALaterSnapshotDoesNotCountTwice() {
        EntityCountReconciliation reconciliation = new EntityCountReconciliation();
        long token = reconciliation.begin();
        UUID first = UUID.randomUUID(), spawned = UUID.randomUUID();
        reconciliation.capture(token, Collections.singletonMap(first, KEY));
        synchronized (reconciliation.getLock()) { reconciliation.record(spawned, KEY, 1, true); }
        Map<UUID, Key> laterChunk = new HashMap<>(); laterChunk.put(first, KEY); laterChunk.put(spawned, KEY);
        reconciliation.capture(token, laterChunk);
        EntityCountReconciliation.Prepared prepared = reconciliation.prepare(token);
        assertEquals(Integer.valueOf(2), prepared.getCounts().get(KEY));
        AtomicInteger committed = new AtomicInteger();
        assertTrue(reconciliation.finish(prepared, () -> committed.set(prepared.getCounts().get(KEY))));
        assertEquals(2, committed.get());
    }

    @Test public void deathAfterSnapshotAndConcurrentPreparationPreserveTheActualPresence() {
        EntityCountReconciliation reconciliation = new EntityCountReconciliation();
        long token = reconciliation.begin(); UUID id = UUID.randomUUID();
        reconciliation.capture(token, Collections.singletonMap(id, KEY));
        EntityCountReconciliation.Prepared stale = reconciliation.prepare(token);
        synchronized (reconciliation.getLock()) { reconciliation.record(id, KEY, 1, false); }
        assertFalse(reconciliation.finish(stale, () -> fail("Stale counts must not be committed")));
        EntityCountReconciliation.Prepared current = reconciliation.prepare(token);
        assertTrue(current.getCounts().isEmpty());
        assertTrue(reconciliation.finish(current, () -> {}));
    }

    @Test public void typeOnlyAndBulkApiChangesDeclineReplacementAndOldCallbacksCannotRetireANewRun() {
        EntityCountReconciliation reconciliation = new EntityCountReconciliation();
        long token = reconciliation.begin();
        synchronized (reconciliation.getLock()) { reconciliation.record(null, KEY, 1, true); }
        assertNull(reconciliation.prepare(token)); reconciliation.cancel(token);
        long next = reconciliation.begin(); UUID id = UUID.randomUUID();
        synchronized (reconciliation.getLock()) { reconciliation.record(id, KEY, 1, true); }
        reconciliation.cancel(token);
        assertEquals(Integer.valueOf(1), reconciliation.prepare(next).getCounts().get(KEY));
        synchronized (reconciliation.getLock()) { reconciliation.record(id, KEY, 2, true); }
        assertNull(reconciliation.prepare(next)); reconciliation.cancel(next);
    }
}

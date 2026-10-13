package com.bgsoftware.superiorskyblock.island.algorithm;

import com.bgsoftware.superiorskyblock.api.key.Key;
import com.bgsoftware.superiorskyblock.api.world.Dimension;
import com.bgsoftware.superiorskyblock.api.world.WorldInfo;
import com.bgsoftware.superiorskyblock.core.ChunkPosition;
import com.bgsoftware.superiorskyblock.core.key.KeyIndicator;
import com.bgsoftware.superiorskyblock.core.key.types.CustomKey;
import org.bukkit.World;
import org.junit.Test;

import java.math.BigInteger;
import java.util.Arrays;
import java.util.Collections;

import static org.junit.Assert.*;

public class ChunkCountReconciliationTest {
    private static final Key KEY = CustomKey.of("COUNTED", null, KeyIndicator.CUSTOM);

    @Test public void onlyEventsAfterTheActualChunkPhotographAreReplayed() {
        ChunkCountReconciliation journal = new ChunkCountReconciliation();
        ChunkPosition a = position("world", 1), b = position("world", 2);
        long token = journal.begin(); journal.configure(token, Arrays.asList(a, b), true);
        record(journal, "world", 1, 2); // Already included by the later photograph.
        journal.captured(token, a); record(journal, "world", 1, 3);
        record(journal, "world", 2, 5); journal.captured(token, b);
        record(journal, "world", 2, -1);
        record(journal, "other-world", 1, 4); // A distinct, not-enumerated chunk.
        ChunkCountReconciliation.Prepared prepared = journal.prepare(token, Collections.singletonMap(KEY, BigInteger.TEN));
        assertEquals(BigInteger.valueOf(16), prepared.getCounts().get(KEY));
        synchronized (journal.getLock()) { assertTrue(journal.current(prepared)); journal.finish(token); }
        assertEquals(0L, journal.activeToken());
    }

    @Test public void preparationMustBeRetriedWhenAnEventArrivesBeforePublication() {
        ChunkCountReconciliation journal = new ChunkCountReconciliation();
        ChunkPosition chunk = position("world", 1);
        long token = journal.begin(); journal.configure(token, Collections.singletonList(chunk), true);
        journal.captured(token, chunk);
        ChunkCountReconciliation.Prepared old = journal.prepare(token, Collections.singletonMap(KEY, BigInteger.TEN));
        record(journal, "world", 1, 1);
        synchronized (journal.getLock()) { assertFalse(journal.current(old)); }
        ChunkCountReconciliation.Prepared fresh = journal.prepare(token, Collections.singletonMap(KEY, BigInteger.TEN));
        assertEquals(BigInteger.valueOf(11), fresh.getCounts().get(KEY));
        synchronized (journal.getLock()) { assertTrue(journal.current(fresh)); journal.finish(token); }
    }

    @Test public void unknownApiMutationsAndLegacySnapshotsDeclineReplacement() {
        ChunkCountReconciliation journal = new ChunkCountReconciliation();
        long token = journal.begin(); journal.configure(token, Collections.emptyList(), true);
        synchronized (journal.getLock()) { journal.record(KEY, BigInteger.ONE); }
        assertNull(journal.prepare(token, Collections.singletonMap(KEY, BigInteger.TEN)));
        journal.cancel(token);
        long next = journal.begin(); journal.configure(next, Collections.emptyList(), false);
        record(journal, "world", 1, 1);
        assertNull(journal.prepare(next, Collections.singletonMap(KEY, BigInteger.TEN)));
        journal.cancel(token); assertEquals(next, journal.activeToken()); // A stale callback cannot retire a new epoch.
        journal.cancel(next); assertEquals(0L, journal.activeToken());
    }

    @Test public void cancellationsDoNotCarryDeltasIntoTheNextCalculationAndCountsStayPositive() {
        ChunkCountReconciliation journal = new ChunkCountReconciliation();
        long token = journal.begin(); journal.configure(token, Collections.emptyList(), true);
        record(journal, "world", 1, 10); journal.cancel(token);
        long next = journal.begin(); journal.configure(next, Collections.emptyList(), true);
        record(journal, "world", 1, -3);
        assertTrue(journal.prepare(next, Collections.singletonMap(KEY, BigInteger.ONE)).getCounts().isEmpty());
        journal.cancel(next);
    }

    @Test public void entityDerivedBlocksDeclineReplacementRatherThanReplayAMigratingVehicleTwice() {
        ChunkCountReconciliation journal = new ChunkCountReconciliation();
        long token = journal.begin(); journal.configure(token, Collections.emptyList(), true);
        try (ChunkCountReconciliation.EntityScope identity = ChunkCountReconciliation.forEntity(
                java.util.UUID.randomUUID(), KEY)) {
            record(journal, "world", 1, 1);
        }
        assertNull(journal.prepare(token, Collections.singletonMap(KEY, BigInteger.ONE)));
        journal.cancel(token);
    }

    @Test public void globalDisplayCountsAndSpecificWorthContributionsUseTheirOwnKeys() {
        ChunkCountReconciliation journal = new ChunkCountReconciliation();
        long token = journal.begin(); journal.configure(token, Collections.emptyList(), true);
        Key exact = CustomKey.of("COUNTED", "VARIANT", KeyIndicator.CUSTOM);
        try (ChunkCountReconciliation.Scope scope = ChunkCountReconciliation.at("world", 1, 0)) {
            synchronized (journal.getLock()) { journal.record(KEY, exact, BigInteger.ONE.negate()); }
        }
        ChunkCountReconciliation.Prepared prepared = journal.prepare(token, Collections.singletonMap(KEY, BigInteger.TEN));
        assertEquals(BigInteger.valueOf(9), prepared.getCounts().get(KEY));
        assertEquals(BigInteger.TEN, prepared.getRawCounts().get(KEY));
        assertEquals(BigInteger.ONE.negate(), prepared.getRawCounts().get(exact));
        journal.cancel(token);
    }

    private static void record(ChunkCountReconciliation journal, String world, int x, int delta) {
        try (ChunkCountReconciliation.Scope ignored = ChunkCountReconciliation.at(world, x, 0)) {
            synchronized (journal.getLock()) { journal.record(KEY, BigInteger.valueOf(delta)); }
        }
    }
    private static ChunkPosition position(String world, int x) {
        WorldInfo info = new WorldInfo() {
            @Override public String getName() { return world; }
            @Override public World.Environment getEnvironment() { return World.Environment.NORMAL; }
            @Override public Dimension getDimension() { return null; }
        };
        return ChunkPosition.of(info, x, 0, false);
    }
}

package com.bgsoftware.superiorskyblock.island.algorithm;

import com.bgsoftware.superiorskyblock.api.key.Key;
import com.bgsoftware.superiorskyblock.core.key.KeyIndicator;
import com.bgsoftware.superiorskyblock.core.key.types.CustomKey;
import org.junit.Test;

import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.*;

public class EntitySlotReservationsTest {
    private static final Key KEY = CustomKey.of("LIMITED", null, KeyIndicator.CUSTOM);

    @Test public void competingRegionsCannotBothReserveTheLastSlot() throws Exception {
        Object barrier = new Object();
        EntitySlotReservations reservations = new EntitySlotReservations(barrier);
        CountDownLatch start = new CountDownLatch(1), done = new CountDownLatch(2);
        AtomicInteger accepted = new AtomicInteger();
        AtomicReference<UUID> winner = new AtomicReference<>();
        AtomicReference<Throwable> error = new AtomicReference<>();
        for (int i = 0; i < 2; ++i) new Thread(() -> {
            try {
                start.await(); UUID id = UUID.randomUUID();
                synchronized (barrier) {
                    if (reservations.reserve(id, KEY, 0, 1)) { accepted.incrementAndGet(); winner.set(id); }
                }
            } catch (Throwable failure) { error.compareAndSet(null, failure); }
            finally { done.countDown(); }
        }, "entity-slot-correctness-" + i).start();
        start.countDown(); assertTrue(done.await(10L, TimeUnit.SECONDS));
        if (error.get() != null) throw new AssertionError(error.get());
        assertEquals(1, accepted.get());
        synchronized (barrier) {
            assertEquals(KEY, reservations.release(winner.get()));
            int nowTracked = 1; // Consumption and the counter increment share this barrier in the actual tracker.
            assertFalse(reservations.reserve(UUID.randomUUID(), KEY, nowTracked, 1));
        }
    }

    @Test public void cancelledSpawnsReleaseTheSlotAndAReceiptConsumesItOnlyOnce() {
        EntitySlotReservations reservations = new EntitySlotReservations(new Object());
        UUID cancelled = UUID.randomUUID(), next = UUID.randomUUID();
        assertTrue(reservations.reserve(cancelled, KEY, 0, 1));
        assertFalse(reservations.reserve(next, KEY, 0, 1));
        assertEquals(KEY, reservations.release(cancelled));
        assertTrue(reservations.reserve(next, KEY, 0, 1));
        assertTrue(reservations.reserve(next, KEY, 0, 1)); // Duplicate event admission is the same UUID reservation.
        assertEquals(KEY, reservations.release(next)); assertNull(reservations.release(next));
        assertTrue(reservations.reserve(UUID.randomUUID(), KEY, 0, 1));
    }

    @Test public void independentKeysAndResetDoNotShareOrCarryPendingCounts() {
        EntitySlotReservations reservations = new EntitySlotReservations(new Object());
        Key second = CustomKey.of("OTHER", null, KeyIndicator.CUSTOM);
        UUID first = UUID.randomUUID(), other = UUID.randomUUID();
        assertTrue(reservations.reserve(first, KEY, 0, 1));
        assertTrue(reservations.reserve(other, second, 0, 1));
        assertEquals(2, reservations.clear().size());
        assertNull(reservations.release(first)); assertNull(reservations.release(other));
        assertTrue(reservations.reserve(UUID.randomUUID(), KEY, 0, 1));
    }
}

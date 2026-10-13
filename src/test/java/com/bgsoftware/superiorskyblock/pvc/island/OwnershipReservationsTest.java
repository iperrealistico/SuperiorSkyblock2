package com.bgsoftware.superiorskyblock.pvc.island;
import org.junit.Test;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.Assert.*;

public class OwnershipReservationsTest {
    @Test public void defaultQuotaCountsPendingAndReleaseIsIdempotent() {
        OwnershipReservations ledger = new OwnershipReservations(10); UUID owner = UUID.randomUUID(), operation = UUID.randomUUID();
        assertTrue(ledger.reserve(operation, owner, 0, 1));
        assertFalse(ledger.reserve(UUID.randomUUID(), owner, 0, 1));
        assertTrue(ledger.canCommit(operation, 0, 1));
        ledger.release(operation); ledger.release(operation); assertEquals(0, ledger.pending(owner));
    }
    @Test public void downgradeRejectsPendingCommitButDoesNotDestroyOwnership() {
        OwnershipReservations ledger = new OwnershipReservations(10); UUID owner = UUID.randomUUID(), operation = UUID.randomUUID();
        assertTrue(ledger.reserve(operation, owner, 1, 2)); assertFalse(ledger.canCommit(operation, 1, 1));
    }
    @Test public void differentOwnersShareBoundedAdmission() {
        OwnershipReservations ledger = new OwnershipReservations(1);
        assertTrue(ledger.reserve(UUID.randomUUID(), UUID.randomUUID(), 0, Integer.MAX_VALUE));
        assertFalse(ledger.reserve(UUID.randomUUID(), UUID.randomUUID(), 0, Integer.MAX_VALUE));
    }
    @Test public void concurrentCreatesCannotOversubscribe() throws Exception {
        OwnershipReservations ledger = new OwnershipReservations(128); UUID owner = UUID.randomUUID();
        CountDownLatch start = new CountDownLatch(1), complete = new CountDownLatch(30); AtomicInteger admitted = new AtomicInteger();
        for (int i = 0; i < 30; i++) new Thread(() -> {
            try { start.await(); if (ledger.reserve(UUID.randomUUID(), owner, 0, 2)) admitted.incrementAndGet(); }
            catch (InterruptedException error) { Thread.currentThread().interrupt(); }
            finally { complete.countDown(); }
        }).start();
        start.countDown(); complete.await(); assertEquals(2, admitted.get());
    }
    @Test public void reusedOperationCannotChangeOwner() {
        OwnershipReservations ledger = new OwnershipReservations(10); UUID operation = UUID.randomUUID(), owner = UUID.randomUUID();
        assertTrue(ledger.reserve(operation, owner, 0, 1)); assertTrue(ledger.reserve(operation, owner, 0, 1));
        assertFalse(ledger.reserve(operation, UUID.randomUUID(), 0, 1)); assertEquals(1, ledger.pending(owner));
    }
    @Test public void convertedTransfersDoNotDoubleCountOwnedSlotsAndRetainAdmissionUntilAck() {
        OwnershipReservations ledger = new OwnershipReservations(2); UUID owner = UUID.randomUUID();
        UUID first = UUID.randomUUID(), second = UUID.randomUUID();
        assertTrue(ledger.reserve(first, owner, 0, 2)); assertTrue(ledger.reserve(second, owner, 0, 2));
        assertTrue(ledger.convert(first)); assertTrue(ledger.convert(first));
        assertEquals(1, ledger.pending(owner)); assertEquals(2, ledger.size());
        assertTrue(ledger.canCommit(second, 1, 2));
        ledger.release(first); assertEquals(1, ledger.pending(owner)); assertEquals(1, ledger.size());
        assertTrue(ledger.convert(second)); ledger.release(second); assertEquals(0, ledger.pending(owner));
    }
}

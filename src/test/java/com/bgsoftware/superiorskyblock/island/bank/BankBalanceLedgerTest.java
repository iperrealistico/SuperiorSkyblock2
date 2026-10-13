package com.bgsoftware.superiorskyblock.island.bank;

import org.junit.Test;
import java.math.BigDecimal;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import static org.junit.Assert.*;

public class BankBalanceLedgerTest {
    private static BigDecimal money(String value) { return new BigDecimal(value); }

    @Test public void concurrentWithdrawalsCannotPromiseTheSameFunds() throws Exception {
        BankBalanceLedger bank = new BankBalanceLedger(); bank.setBalance(money("100"));
        ExecutorService workers = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Future<BankBalanceLedger.Reservation> first = workers.submit(() -> { start.await(); return bank.reserveWithdrawal(money("80")); });
            Future<BankBalanceLedger.Reservation> second = workers.submit(() -> { start.await(); return bank.reserveWithdrawal(money("80")); });
            start.countDown();
            BankBalanceLedger.Reservation a = first.get(5, java.util.concurrent.TimeUnit.SECONDS);
            BankBalanceLedger.Reservation b = second.get(5, java.util.concurrent.TimeUnit.SECONDS);
            assertEquals(0, a.amount.add(b.amount).compareTo(money("100")));
            assertNull(bank.reserveWithdrawal(BigDecimal.ONE));
            bank.settle(a, a.amount); bank.settle(b, b.amount);
            assertEquals(0, bank.getBalance().signum());
        } finally { workers.shutdownNow(); }
    }
    @Test public void pendingDepositsReserveLimitAndFailureReleasesIt() {
        BankBalanceLedger bank = new BankBalanceLedger(); bank.setBalance(money("70"));
        BankBalanceLedger.Reservation first = bank.reserveDeposit(money("30"), money("100"));
        assertNotNull(first); assertNull(bank.reserveDeposit(BigDecimal.ONE, money("100")));
        bank.rollback(first);
        assertNotNull(bank.reserveDeposit(money("30"), money("100")));
        assertEquals(0, bank.getBalance().compareTo(money("70")));
    }
    @Test public void partialPayoutReleasesOnlyTheUnusedReservation() {
        BankBalanceLedger bank = new BankBalanceLedger(); bank.setBalance(money("100"));
        BankBalanceLedger.Reservation held = bank.reserveWithdrawal(money("80"));
        bank.settle(held, money("50"));
        assertEquals(0, bank.reserveWithdrawal(money("100")).amount.compareTo(money("50")));
    }
    @Test public void adminCannotSpendFundsAlreadyPromisedToAPlayer() {
        BankBalanceLedger bank = new BankBalanceLedger(); bank.setBalance(money("100"));
        bank.reserveWithdrawal(money("80"));
        try { bank.adjust(money("-30")); fail("Reserved payout must remain covered"); } catch (IllegalArgumentException expected) { }
        assertEquals(0, bank.adjust(money("-20")).compareTo(money("80")));
    }
    @Test public void invalidProviderAmountCannotSilentlyInvalidateReservation() {
        BankBalanceLedger bank = new BankBalanceLedger(); bank.setBalance(money("100"));
        BankBalanceLedger.Reservation held = bank.reserveWithdrawal(money("40"));
        try { bank.settle(held, money("50")); fail("Overpayment needs operator reconciliation"); } catch (IllegalArgumentException expected) { }
        assertEquals(0, bank.reserveWithdrawal(money("100")).amount.compareTo(money("60")));
    }
    @Test public void aReservationCanBeSettledOnlyOnce() {
        BankBalanceLedger bank = new BankBalanceLedger(); bank.setBalance(money("10"));
        BankBalanceLedger.Reservation held = bank.reserveWithdrawal(money("5")); bank.settle(held, held.amount);
        try { bank.settle(held, held.amount); fail(); } catch (IllegalStateException expected) { }
        assertEquals(0, bank.getBalance().compareTo(money("5")));
    }
}

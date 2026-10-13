package com.bgsoftware.superiorskyblock.island.bank;

import com.bgsoftware.superiorskyblock.island.bank.logs.CacheBankLogs;
import org.junit.Test;
import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.util.concurrent.CompletableFuture;
import static org.junit.Assert.*;

public class BankQuiescenceTest {
    private static SIslandBank bank() { return new SIslandBank(null, () -> false, new CacheBankLogs(), ignored -> { }); }
    private static void state(SIslandBank bank, String field, Object value) throws Exception {
        Field target = SIslandBank.class.getDeclaredField(field); target.setAccessible(true); target.set(bank, value);
    }
    private static Object state(SIslandBank bank, String field) throws Exception {
        Field target = SIslandBank.class.getDeclaredField(field); target.setAccessible(true); return target.get(bank);
    }
    @Test public void payoutReservationAndPhysicalWriterMustBothDrainBeforeDeletion() throws Exception {
        SIslandBank bank = bank();
        BankBalanceLedger ledger = (BankBalanceLedger) state(bank, "ledger"); ledger.setBalance(new BigDecimal("10"));
        BankBalanceLedger.Reservation payout = ledger.reserveWithdrawal(new BigDecimal("4"));
        state(bank, "admitted", 1); state(bank, "writerRunning", true);
        CompletableFuture<Void> quiet = bank.quiesceTransactions();
        assertFalse(quiet.isDone()); assertTrue((Boolean) state(bank, "quiescing"));
        ledger.settle(payout, payout.amount); state(bank, "admitted", 0);
        bank.checkQuiescence(); assertFalse("The physical SQL writer still owns its slot", quiet.isDone());
        state(bank, "writerRunning", false); bank.checkQuiescence();
        assertTrue(quiet.isDone()); assertFalse(quiet.isCompletedExceptionally());
        assertEquals(0, bank.getBalance().compareTo(new BigDecimal("6")));
        bank.resumeTransactions(); assertFalse((Boolean) state(bank, "quiescing"));
    }
    @Test public void uncertainOutcomeFailsDrainAndCannotBeReopened() throws Exception {
        SIslandBank bank = bank(); state(bank, "uncertainOutcome", new IllegalStateException("controlled ambiguous payout"));
        CompletableFuture<Void> quiet = bank.quiesceTransactions();
        assertTrue(quiet.isCompletedExceptionally()); bank.resumeTransactions();
        assertTrue((Boolean) state(bank, "quiescing"));
    }
    @Test public void lastUiContinuationRemainsPartOfQuiescence() throws Exception {
        SIslandBank bank = bank();
        java.util.Set<CompletableFuture<?>> pending = (java.util.Set<CompletableFuture<?>>) state(bank, "pendingWithdrawals");
        CompletableFuture<Object> response = new CompletableFuture<>(); pending.add(response);
        CompletableFuture<Void> quiet = bank.quiesceTransactions(); assertFalse(quiet.isDone());
        pending.remove(response); bank.checkQuiescence(); assertTrue(quiet.isDone());
        bank.resumeTransactions();
    }
}

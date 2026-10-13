package com.bgsoftware.superiorskyblock.pvc.island;

import com.bgsoftware.superiorskyblock.api.hooks.EconomyProvider;
import org.junit.Test;
import java.math.BigDecimal;
import static org.junit.Assert.*;

public class DisbandRefundPolicyTest {
    @Test public void acknowledgedExactRefundMayProceed() {
        DisbandRefundPolicy.acknowledge(new EconomyProvider.EconomyResult(8.5D), new BigDecimal("8.5"));
    }
    @Test public void rejectedZeroPayoutCanAbortBeforeWorldCleanup() {
        try { DisbandRefundPolicy.acknowledge(new EconomyProvider.EconomyResult("controlled rejection", 0D), BigDecimal.TEN); fail(); }
        catch (DisbandRefundFailure failure) { assertTrue(failure.hasNoPayout()); }
    }
    @Test public void partialOrMissingReceiptMustRemainHeld() {
        for (EconomyProvider.EconomyResult receipt : new EconomyProvider.EconomyResult[]{
                new EconomyProvider.EconomyResult(5D), new EconomyProvider.EconomyResult("partial", 5D), null}) {
            try { DisbandRefundPolicy.acknowledge(receipt, BigDecimal.TEN); fail(); }
            catch (DisbandRefundFailure failure) { assertFalse(failure.hasNoPayout()); }
        }
    }
    @Test public void pendingAndHeldRefundsNeverEnterAutomaticRestartDeletion() {
        for (String state : new String[]{"DELETE_REFUND_PENDING", "DELETE_REFUND_HOLD"}) {
            try { DisbandRefundPolicy.requireRecoverableState(state); fail(); }
            catch (IllegalStateException expected) { assertTrue(expected.getMessage().contains("operator reconciliation")); }
        }
        DisbandRefundPolicy.requireRecoverableState("DELETING");
    }
}

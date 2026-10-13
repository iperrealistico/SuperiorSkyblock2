package com.bgsoftware.superiorskyblock.pvc.island;

import com.bgsoftware.superiorskyblock.api.hooks.EconomyProvider;
import java.math.BigDecimal;

/** Provider acknowledgements are checked; this does not promise external crash atomicity or compensation. */
public final class DisbandRefundPolicy {
    private DisbandRefundPolicy() { }
    public static BigDecimal providerAmount(BigDecimal amount) {
        double value = amount.doubleValue();
        if (amount.signum() < 0 || !Double.isFinite(value))
            throw new DisbandRefundFailure("Refund amount cannot be represented by the economy provider", true);
        return BigDecimal.valueOf(value);
    }
    public static void acknowledge(EconomyProvider.EconomyResult result, BigDecimal requested) {
        if (result == null || !Double.isFinite(result.getTransactionMoney()))
            throw new DisbandRefundFailure("Refund provider returned no reliable acknowledgement", false);
        if (result.hasFailed()) {
            boolean noPayout = result.getTransactionMoney() == 0D;
            throw new DisbandRefundFailure("Refund provider rejected payment: " + result.getErrorMessage(), noPayout);
        }
        if (BigDecimal.valueOf(result.getTransactionMoney()).compareTo(requested) != 0)
            throw new DisbandRefundFailure("Refund provider acknowledged a different or partial payment", false);
    }
    public static void requireRecoverableState(String state) {
        if ("DELETE_REFUND_PENDING".equals(state) || "DELETE_REFUND_HOLD".equals(state))
            throw new IllegalStateException("An external disband refund needs operator reconciliation before startup. " +
                    "Island metadata and funds evidence are retained; do not retry the refund or automatically delete the island.");
    }
}

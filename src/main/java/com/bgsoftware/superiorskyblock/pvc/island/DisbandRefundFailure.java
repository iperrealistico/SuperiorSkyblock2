package com.bgsoftware.superiorskyblock.pvc.island;

/** A known zero-payout rejection can release a deletion; every other refund failure needs operator review. */
public final class DisbandRefundFailure extends IllegalStateException {
    private final boolean noPayout;
    public DisbandRefundFailure(String message, boolean noPayout) { super(message); this.noPayout = noPayout; }
    public boolean hasNoPayout() { return noPayout; }
}

package com.bgsoftware.superiorskyblock.island.bank;

import java.math.BigDecimal;
import java.math.RoundingMode;

/** Pure per-bank admission and settlement. Never calls events, providers, scheduling or persistence. */
final class BankBalanceLedger {
    private volatile BigDecimal balance = BigDecimal.ZERO;
    private BigDecimal withdrawals = BigDecimal.ZERO, deposits = BigDecimal.ZERO;
    BigDecimal getBalance() { return balance; }
    synchronized void setBalance(BigDecimal value) {
        if (withdrawals.signum() != 0 || deposits.signum() != 0)
            throw new IllegalStateException("Cannot replace a bank balance while transactions are reserved");
        balance = value.setScale(2, RoundingMode.HALF_DOWN);
    }
    synchronized boolean canDeposit(BigDecimal amount, BigDecimal limit) {
        return amount.signum() > 0 && (limit.signum() <= 0 || balance.add(deposits).add(amount).compareTo(limit) <= 0);
    }
    synchronized Reservation reserveDeposit(BigDecimal amount, BigDecimal limit) {
        if (!canDeposit(amount, limit)) return null;
        deposits = deposits.add(amount);
        return new Reservation(amount, true);
    }
    synchronized Reservation reserveWithdrawal(BigDecimal wanted) {
        BigDecimal amount = balance.subtract(withdrawals).max(BigDecimal.ZERO).min(wanted);
        if (amount.signum() <= 0) return null;
        withdrawals = withdrawals.add(amount);
        return new Reservation(amount, false);
    }
    synchronized BigDecimal settle(Reservation reservation, BigDecimal actual) {
        requireActive(reservation);
        if (actual.signum() < 0 || actual.compareTo(reservation.amount) > 0)
            throw new IllegalArgumentException("Economy provider returned an amount outside its reservation");
        release(reservation);
        balance = balance.add(reservation.deposit ? actual : actual.negate()).setScale(3, RoundingMode.HALF_DOWN);
        return balance;
    }
    synchronized void rollback(Reservation reservation) {
        if (reservation != null && reservation.active) release(reservation);
    }
    synchronized BigDecimal adjust(BigDecimal amount) {
        if (amount.signum() < 0 && balance.subtract(withdrawals).add(amount).signum() < 0)
            throw new IllegalArgumentException("Reserved or unavailable bank balance");
        balance = balance.add(amount).setScale(3, RoundingMode.HALF_DOWN);
        return balance;
    }
    private void requireActive(Reservation reservation) {
        if (reservation == null || !reservation.active) throw new IllegalStateException("Bank reservation already settled");
    }
    private void release(Reservation reservation) {
        requireActive(reservation);
        if (reservation.deposit) deposits = deposits.subtract(reservation.amount);
        else withdrawals = withdrawals.subtract(reservation.amount);
        reservation.active = false;
    }
    static final class Reservation {
        final BigDecimal amount; final boolean deposit; private boolean active = true;
        Reservation(BigDecimal amount, boolean deposit) { this.amount = amount; this.deposit = deposit; }
    }
}

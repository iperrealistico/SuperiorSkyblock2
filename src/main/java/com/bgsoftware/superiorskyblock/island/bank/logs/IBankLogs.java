package com.bgsoftware.superiorskyblock.island.bank.logs;

import com.bgsoftware.superiorskyblock.api.island.bank.BankTransaction;

import java.util.List;
import java.util.UUID;

public interface IBankLogs {

    int getLastTransactionPosition();

    /** Atomically reserve a unique position, including unsuccessful transaction attempts. */
    int nextTransactionPosition();

    /** Database-backed logs reject transactions until asynchronous cursor preload completes. */
    default boolean isReady() { return true; }

    List<BankTransaction> getTransactions();

    List<BankTransaction> getTransactions(UUID playerUUID);

    void addTransaction(BankTransaction bankTransaction, UUID senderUUID, boolean loadFromDatabase);

}

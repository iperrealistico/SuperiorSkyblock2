package com.bgsoftware.superiorskyblock.island.bank.logs;

import com.bgsoftware.superiorskyblock.api.island.bank.BankTransaction;
import com.bgsoftware.superiorskyblock.core.SequentialListBuilder;
import com.bgsoftware.superiorskyblock.core.threads.Synchronized;
import com.bgsoftware.superiorskyblock.island.top.SortingComparators;

import java.util.Collections;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class CacheBankLogs implements IBankLogs {

    private final Synchronized<SortedSet<BankTransaction>> transactions = Synchronized.of(new TreeSet<>(SortingComparators.BANK_TRANSACTIONS_COMPARATOR));
    private final Map<UUID, Synchronized<List<BankTransaction>>> transactionsByPlayers = new ConcurrentHashMap<>();
    private final java.util.concurrent.atomic.AtomicInteger position = new java.util.concurrent.atomic.AtomicInteger();

    @Override
    public int getLastTransactionPosition() {
        return position.get();
    }

    @Override public int nextTransactionPosition() { return position.incrementAndGet(); }

    @Override
    public List<BankTransaction> getTransactions() {
        return transactions.readAndGet(bankTransactions -> new SequentialListBuilder<BankTransaction>().build(bankTransactions));
    }

    @Override
    public List<BankTransaction> getTransactions(UUID playerUUID) {
        Synchronized<List<BankTransaction>> transactions = this.transactionsByPlayers.get(playerUUID);
        return transactions == null ? Collections.emptyList() : transactions.readAndGet(list ->
                Collections.unmodifiableList(new java.util.ArrayList<>(list)));
    }

    @Override
    public void addTransaction(BankTransaction bankTransaction, UUID senderUUID, boolean loadFromDatabase) {
        position.accumulateAndGet(bankTransaction.getPosition(), Math::max);
        transactions.write(transactions -> transactions.add(bankTransaction));
        transactionsByPlayers.computeIfAbsent(senderUUID, p -> Synchronized.of(new LinkedList<>()))
                .write(transactions -> transactions.add(bankTransaction));
    }

}

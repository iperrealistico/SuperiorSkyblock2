package com.bgsoftware.superiorskyblock.island.bank.logs;

import com.bgsoftware.superiorskyblock.api.data.DatabaseFilter;
import com.bgsoftware.superiorskyblock.api.island.Island;
import com.bgsoftware.superiorskyblock.api.island.bank.BankTransaction;
import com.bgsoftware.superiorskyblock.core.SequentialListBuilder;
import com.bgsoftware.superiorskyblock.core.database.DatabaseResult;
import com.bgsoftware.superiorskyblock.core.database.sql.DBSession;
import com.bgsoftware.superiorskyblock.core.database.sql.SQLDatabaseBridge;
import com.bgsoftware.superiorskyblock.core.database.sql.ResultSetMapBridge;
import com.bgsoftware.common.databasebridge.sql.query.QueryResult;
import com.bgsoftware.superiorskyblock.core.logging.Log;
import com.bgsoftware.superiorskyblock.core.threads.BukkitExecutor;
import com.bgsoftware.superiorskyblock.island.bank.SBankTransaction;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/** Cache misses never run JDBC on the caller. Readers receive the last detached snapshot. */
public class DatabaseBankLogs implements IBankLogs {
    private final Island island;
    private final Object lock = new Object();
    private final Map<Integer, BankTransaction> session = new TreeMap<>();
    private final AtomicInteger position = new AtomicInteger();
    private volatile List<BankTransaction> snapshot = Collections.emptyList();
    private volatile boolean ready;
    private boolean refreshing;
    private boolean historyRequested;
    private long refreshedAt;

    public DatabaseBankLogs(Island island) {
        this.island = island;
        refresh(false);
    }
    @Override public boolean isReady() { if (!ready) refresh(false); return ready; }
    @Override public int getLastTransactionPosition() { return position.get(); }
    @Override public int nextTransactionPosition() {
        if (!ready) throw new IllegalStateException("Bank log cursor is loading");
        return position.incrementAndGet();
    }
    @Override public List<BankTransaction> getTransactions() {
        refresh(true);
        List<BankTransaction> history;
        List<BankTransaction> tail;
        synchronized (lock) { history = snapshot; tail = new ArrayList<>(session.values()); }
        // Snapshot reads already return a detached whole history in the legacy API. Merge sorted
        // components linearly here, rather than copying/sorting the whole history on every write.
        List<BankTransaction> result = new ArrayList<>(history.size() + tail.size());
        int a = 0, b = 0;
        while (a < history.size() || b < tail.size()) {
            if (a == history.size()) result.add(tail.get(b++));
            else if (b == tail.size()) result.add(history.get(a++));
            else {
                int comparison = Integer.compare(history.get(a).getPosition(), tail.get(b).getPosition());
                if (comparison < 0) result.add(history.get(a++));
                else if (comparison > 0) result.add(tail.get(b++));
                else { ++a; result.add(tail.get(b++)); }
            }
        }
        return Collections.unmodifiableList(result);
    }
    @Override public List<BankTransaction> getTransactions(UUID player) {
        return new SequentialListBuilder<BankTransaction>().filter(transaction ->
                player.equals(transaction.getPlayer()) || transaction.getPlayer() == null && player.equals(new UUID(0L, 0L)))
                .build(getTransactions());
    }
    @Override public void addTransaction(BankTransaction transaction, UUID sender, boolean loadFromDatabase) {
        synchronized (lock) {
            position.accumulateAndGet(transaction.getPosition(), Math::max);
            session.put(transaction.getPosition(), transaction);
        }
    }
    private void refresh(boolean history) {
        synchronized (lock) {
            if (refreshing) { historyRequested |= history; return; }
            if (!history && ready || history && refreshedAt != 0L && System.nanoTime() - refreshedAt < TimeUnit.MINUTES.toNanos(10L)) return;
            refreshing = true;
        }
        try { BukkitExecutor.async(() -> loadSnapshot(history)); }
        catch (Throwable failure) {
            synchronized (lock) { refreshing = false; }
            Log.error(failure, "Could not schedule bank logs preload");
        }
    }
    private void loadSnapshot(boolean history) {
        Map<Integer, BankTransaction> loaded = new TreeMap<>();
        AtomicInteger maximum = new AtomicInteger();
        boolean succeeded = false;
        try {
            if (island.getDatabaseBridge() instanceof SQLDatabaseBridge) {
                if (!DBSession.isReady()) throw new IllegalStateException("Bank SQL connection is unavailable");
                java.util.concurrent.atomic.AtomicReference<Throwable> failure = new java.util.concurrent.atomic.AtomicReference<>();
                java.util.concurrent.atomic.AtomicBoolean queried = new java.util.concurrent.atomic.AtomicBoolean();
                DBSession.customQuery(sqlQuery(history),
                        new QueryResult<java.sql.PreparedStatement>().onSuccess(statement -> {
                            statement.setString(1, island.getUniqueId().toString());
                            try (java.sql.ResultSet rows = statement.executeQuery()) {
                                queried.set(true);
                                if (!history) {
                                    if (rows.next()) maximum.set(rows.getInt(1));
                                } else while (rows.next()) {
                                    maximum.accumulateAndGet(rows.getInt("position"), Math::max);
                                    SBankTransaction.fromDatabase(new DatabaseResult(new ResultSetMapBridge(rows)))
                                            .ifPresent(transaction -> loaded.put(transaction.getPosition(), transaction));
                                }
                            }
                        }).onFail(failure::set));
                if (failure.get() != null || !queried.get())
                    throw new IllegalStateException("Bank logs query failed", failure.get());
            } else island.getDatabaseBridge().loadObject("bank_transactions",
                    DatabaseFilter.fromFilter("island", island.getUniqueId().toString()), row -> {
                        DatabaseResult result = new DatabaseResult(row);
                        maximum.accumulateAndGet(result.getInt("position").orElse(0), Math::max);
                        if (history) SBankTransaction.fromDatabase(result).ifPresent(transaction -> loaded.put(transaction.getPosition(), transaction));
                    });
            synchronized (lock) {
                position.accumulateAndGet(maximum.get(), Math::max);
                if (history) {
                    // Preserve uncommitted rows. Already read-back rows no longer need a second session copy.
                    session.entrySet().removeIf(entry -> loaded.containsKey(entry.getKey()));
                    loaded.putAll(session);
                    snapshot = Collections.unmodifiableList(new ArrayList<>(loaded.values()));
                    refreshedAt = System.nanoTime();
                }
                ready = true; succeeded = true;
            }
        } catch (Throwable failure) {
            Log.error(failure, "Could not preload bank log cursor; transactions remain disabled until it loads");
        } finally {
            boolean follow;
            synchronized (lock) { refreshing = false; follow = succeeded && historyRequested && !history; historyRequested = false; }
            if (follow) refresh(true);
        }
    }

    /** Common customQuery replaces the table prefix; the island identity remains a bound value. */
    static String sqlQuery(boolean history) {
        return "SELECT " + (history ? "*" : "COALESCE(MAX(`position`),0)") +
                " FROM `{prefix}bank_transactions` WHERE `island`=?";
    }
}

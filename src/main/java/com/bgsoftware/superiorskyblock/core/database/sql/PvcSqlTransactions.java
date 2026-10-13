package com.bgsoftware.superiorskyblock.core.database.sql;

import com.bgsoftware.common.databasebridge.sql.transaction.SQLDatabaseTransaction;
import com.bgsoftware.common.databasebridge.transaction.IDatabaseTransaction;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** Ordered bounded writes with a real JDBC commit barrier and visible failures. */
public final class PvcSqlTransactions implements AutoCloseable {
    @FunctionalInterface public interface Work { void execute(Connection connection) throws Exception; }
    /** Retains connection ownership until work returns; does not close shared SQLite connections. */
    @FunctionalInterface public interface ConnectionScope { void use(Work work) throws Exception; }
    private final ConnectionScope connections;
    private final ThreadPoolExecutor executor;
    private final String prefix;
    private final java.util.concurrent.Semaphore admission;
    private final java.util.concurrent.atomic.AtomicBoolean closed = new java.util.concurrent.atomic.AtomicBoolean();
    private final java.util.function.Consumer<Throwable> onUncertainCommit;
    private volatile boolean uncertainCommit;
    public PvcSqlTransactions(ConnectionScope connections, int capacity, String prefix) {
        this(connections, capacity, prefix, failure -> { });
    }
    public PvcSqlTransactions(ConnectionScope connections, int capacity, String prefix,
                              java.util.function.Consumer<Throwable> onUncertainCommit) {
        if (capacity < 1) throw new IllegalArgumentException("Positive SQL admission capacity required");
        this.connections = connections; this.prefix = prefix;
        this.onUncertainCommit = onUncertainCommit;
        this.admission = new java.util.concurrent.Semaphore(capacity + 1);
        this.executor = new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<Runnable>(capacity), task -> {
                    Thread thread = new Thread(task, "Superior-PVC-SQL"); thread.setDaemon(true); return thread;
                }, new ThreadPoolExecutor.AbortPolicy());
    }
    public CompletableFuture<Void> submit(Collection<IDatabaseTransaction> transactions) {
        CompletableFuture<Void> result = new CompletableFuture<>();
        if (closed.get() || !admission.tryAcquire()) {
            result.completeExceptionally(new RejectedExecutionException("SQL admission is closed or full"));
            return result;
        }
        try {
            List<Statement> statements = new ArrayList<>(transactions.size());
            for (IDatabaseTransaction transaction : transactions) {
                if (!(transaction instanceof SQLDatabaseTransaction))
                    throw new IllegalArgumentException("Only maintained SQL transactions are supported");
                SQLDatabaseTransaction<?> sql = (SQLDatabaseTransaction<?>) transaction;
                List<List<Object>> rows = new ArrayList<>();
                for (SQLDatabaseTransaction.DatabaseValues values : sql.getValues()) {
                    List<Object> row = new ArrayList<>(values.values.size());
                    for (Object value : values.values) row.add(value instanceof byte[] ? ((byte[]) value).clone() : value);
                    rows.add(Collections.unmodifiableList(row));
                }
                statements.add(new Statement(sql.buildQuery().replace("{prefix}", prefix), rows));
            }
            if (statements.isEmpty()) { admission.release(); result.complete(null); return result; }
            executor.execute(new Pending(statements, result));
        } catch (Throwable failure) { admission.release(); result.completeExceptionally(failure); }
        return result;
    }
    private final class Pending implements Runnable {
        final List<Statement> statements;
        final CompletableFuture<Void> result;
        Pending(List<Statement> statements, CompletableFuture<Void> result) { this.statements = statements; this.result = result; }
        @Override public void run() {
            Throwable failure = null;
            try { connections.use(connection -> executeAtomic(connection, statements)); }
            catch (Throwable error) {
                failure = error;
                if (hasUncertainCommit(error)) {
                    uncertainCommit = true;
                    closed.set(true);
                    executor.shutdown();
                    List<Runnable> denied = new ArrayList<>();
                    executor.getQueue().drainTo(denied);
                    for (Runnable task : denied) {
                        admission.release();
                        ((Pending) task).result.completeExceptionally(new CommitOutcomeUnknownException(
                                "SQL writer stopped after an uncertain durable outcome; reconcile on restart", error));
                    }
                    try { onUncertainCommit.accept(error); }
                    catch (Throwable callbackFailure) { error.addSuppressed(callbackFailure); }
                }
            }
            finally { admission.release(); }
            if (failure == null) result.complete(null); else result.completeExceptionally(failure);
        }
    }
    private static void executeAtomic(Connection connection, List<Statement> statements) throws SQLException {
        boolean autoCommit = connection.getAutoCommit();
        if (!autoCommit) throw new SQLException("Refusing to join an unrelated JDBC transaction");
        connection.setAutoCommit(false);
        Throwable original = null;
        boolean commitAttempted = false;
        boolean committed = false;
        boolean rollbackFailed = false;
        try {
            for (Statement statement : statements) {
                try (PreparedStatement prepared = connection.prepareStatement(statement.sql)) {
                    for (List<Object> values : statement.rows) {
                        prepared.clearParameters();
                        for (int i = 0; i < values.size(); ++i) prepared.setObject(i + 1, values.get(i));
                        prepared.executeUpdate();
                    }
                }
            }
            commitAttempted = true;
            connection.commit();
            committed = true;
        } catch (SQLException | RuntimeException | Error failure) {
            original = commitAttempted ? new CommitOutcomeUnknownException("JDBC commit outcome requires reconciliation", failure) : failure;
            try { connection.rollback(); }
            catch (SQLException rollback) {
                rollbackFailed = true;
                original = new CommitOutcomeUnknownException("JDBC rollback could not confirm an aborted transaction", original);
                original.addSuppressed(rollback);
            }
            if (original instanceof CommitOutcomeUnknownException) throw (CommitOutcomeUnknownException) original;
            throw failure;
        } finally {
            // Enabling auto-commit after a failed rollback could commit a partial batch.
            if (!rollbackFailed) {
                try { connection.setAutoCommit(autoCommit); }
                catch (SQLException restore) {
                    CommitOutcomeUnknownException uncertain = new CommitOutcomeUnknownException(committed ?
                            "JDBC commit succeeded but connection cleanup failed; do not compensate" :
                            "JDBC connection state could not be restored; reconcile before further writes", restore);
                    if (original != null) uncertain.addSuppressed(original);
                    throw uncertain;
                }
            }
        }
    }
    public static final class CommitOutcomeUnknownException extends SQLException {
        public CommitOutcomeUnknownException(String message, Throwable cause) { super(message, cause); }
    }
    public static boolean hasUncertainCommit(Throwable failure) {
        for (Throwable current = failure; current != null; current = current.getCause())
            if (current instanceof CommitOutcomeUnknownException) return true;
        return false;
    }
    public boolean isCommitOutcomeUncertain() { return uncertainCommit; }
    public int getPendingCount() { return executor.getQueue().size(); }
    @Override public void close() {
        closed.set(true);
        executor.shutdown();
        try { if (executor.awaitTermination(30, TimeUnit.SECONDS)) return; }
        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
        for (Runnable task : executor.shutdownNow())
            if (task instanceof PvcSqlTransactions.Pending) {
                admission.release();
                ((Pending) task).result.completeExceptionally(new RejectedExecutionException("SQL writer stopped before commit"));
            }
    }
    private static final class Statement {
        final String sql; final List<List<Object>> rows;
        Statement(String sql, List<List<Object>> rows) { this.sql = sql; this.rows = rows; }
    }
}

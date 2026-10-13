package com.bgsoftware.superiorskyblock.core.database.sql;

import com.bgsoftware.common.databasebridge.sql.transaction.CustomSQLDatabaseTransaction;
import com.bgsoftware.common.databasebridge.transaction.IDatabaseTransaction;
import org.junit.Test;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.AbstractCollection;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Iterator;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.Assert.*;

/** Real SQLite assertions cover commit/rollback visibility, not only invocation mocks. */
public class PvcSqlTransactionsTest {
    private Connection database() throws Exception {
        Class.forName("org.sqlite.JDBC");
        Connection connection = DriverManager.getConnection("jdbc:sqlite::memory:");
        try (java.sql.Statement statement = connection.createStatement()) {
            statement.executeUpdate("CREATE TABLE pvc_records (id INTEGER PRIMARY KEY, value TEXT NOT NULL, payload BLOB)");
        }
        return connection;
    }
    private CustomSQLDatabaseTransaction insert(int id, String value, byte[] payload) {
        CustomSQLDatabaseTransaction transaction = new CustomSQLDatabaseTransaction(
                "INSERT INTO {prefix}records (id,value,payload) VALUES (?,?,?)");
        transaction.bindObjects(Arrays.asList(id, value, payload));
        return transaction;
    }
    private int count(Connection connection) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("SELECT COUNT(*) FROM pvc_records");
             ResultSet result = statement.executeQuery()) { result.next(); return result.getInt(1); }
    }
    private Throwable failure(CompletableFuture<Void> future) throws Exception {
        try { future.get(3, TimeUnit.SECONDS); fail("An unsuccessful durable operation must never be acknowledged"); return null; }
        catch (ExecutionException expected) { return expected.getCause(); }
    }

    @Test public void realCommitIsVisibleWhenFutureAcknowledgesAndPrefixIsApplied() throws Exception {
        try (Connection connection = database(); PvcSqlTransactions writes = new PvcSqlTransactions(work -> work.execute(connection), 8, "pvc_")) {
            writes.submit(Arrays.<IDatabaseTransaction>asList(insert(1, "one", new byte[]{1}), insert(2, "two", new byte[]{2})))
                    .get(3, TimeUnit.SECONDS);
            assertEquals(2, count(connection)); assertTrue(connection.getAutoCommit());
        }
    }
    @Test public void laterConstraintFailureRollsBackEarlierRowsOfTheSameSubmission() throws Exception {
        try (Connection connection = database(); PvcSqlTransactions writes = new PvcSqlTransactions(work -> work.execute(connection), 8, "pvc_")) {
            Throwable error = failure(writes.submit(Arrays.<IDatabaseTransaction>asList(
                    insert(1, "first", new byte[]{1}), insert(1, "duplicate", new byte[]{2}))));
            assertTrue(error instanceof SQLException);
            assertEquals(0, count(connection)); assertTrue(connection.getAutoCommit());
            writes.submit(Collections.<IDatabaseTransaction>singletonList(insert(3, "after rollback", new byte[]{3})))
                    .get(3, TimeUnit.SECONDS);
            assertEquals(1, count(connection));
        }
    }
    @Test public void missingTableFailsExceptionallyAndDoesNotCommitOtherStatements() throws Exception {
        try (Connection connection = database(); PvcSqlTransactions writes = new PvcSqlTransactions(work -> work.execute(connection), 8, "pvc_")) {
            CustomSQLDatabaseTransaction missing = new CustomSQLDatabaseTransaction("INSERT INTO nonexistent_table (id) VALUES (?)");
            missing.bindObjects(Collections.singletonList(2));
            assertTrue(failure(writes.submit(Arrays.<IDatabaseTransaction>asList(insert(1, "first", new byte[]{1}), missing))) instanceof SQLException);
            assertEquals(0, count(connection)); assertTrue(connection.getAutoCommit());
        }
    }
    @Test public void commitFailureIsNotSuccessAndRealUncommittedRowsAreRolledBack() throws Exception {
        try (Connection connection = database()) {
            AtomicInteger rollbacks = new AtomicInteger();
            Connection failedCommit = (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(), new Class<?>[]{Connection.class},
                    (proxy, method, arguments) -> {
                        if (method.getName().equals("commit")) throw new SQLException("synthetic commit failure");
                        if (method.getName().equals("rollback")) rollbacks.incrementAndGet();
                        try { return method.invoke(connection, arguments); }
                        catch (InvocationTargetException wrapped) { throw wrapped.getCause(); }
                    });
            try (PvcSqlTransactions writes = new PvcSqlTransactions(work -> work.execute(failedCommit), 8, "pvc_")) {
                Throwable error = failure(writes.submit(Collections.<IDatabaseTransaction>singletonList(insert(1, "pending", new byte[]{1}))));
                assertTrue(PvcSqlTransactions.hasUncertainCommit(error));
                assertEquals("synthetic commit failure", error.getCause().getMessage());
                assertEquals(1, rollbacks.get()); assertEquals(0, count(connection)); assertTrue(connection.getAutoCommit());
                assertTrue(writes.isCommitOutcomeUncertain());
            }
        }
    }
    @Test public void committedRowsSurviveCleanupFailureAndPoisonFurtherAdmission() throws Exception {
        try (Connection connection = database()) {
            AtomicInteger callbacks = new AtomicInteger(), rollbacks = new AtomicInteger();
            Connection cleanupFailure = (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(), new Class<?>[]{Connection.class},
                    (proxy, method, arguments) -> {
                        if (method.getName().equals("setAutoCommit") && Boolean.TRUE.equals(arguments[0]))
                            throw new SQLException("synthetic post-commit cleanup failure");
                        if (method.getName().equals("rollback")) rollbacks.incrementAndGet();
                        try { return method.invoke(connection, arguments); }
                        catch (InvocationTargetException wrapped) { throw wrapped.getCause(); }
                    });
            try (PvcSqlTransactions writes = new PvcSqlTransactions(work -> work.execute(cleanupFailure), 8, "pvc_", error -> callbacks.incrementAndGet())) {
                assertTrue(PvcSqlTransactions.hasUncertainCommit(failure(writes.submit(Collections.<IDatabaseTransaction>singletonList(insert(1, "committed", new byte[]{1}))))));
                assertEquals(1, count(connection)); assertEquals(0, rollbacks.get()); assertEquals(1, callbacks.get());
                assertTrue(writes.isCommitOutcomeUncertain());
                assertTrue(failure(writes.submit(Collections.<IDatabaseTransaction>singletonList(insert(2, "denied", new byte[]{2})))) instanceof RejectedExecutionException);
                assertEquals(1, count(connection));
            }
        }
    }
    @Test public void lostCommitAcknowledgmentDoesNotPretendRowsWereRolledBack() throws Exception {
        try (Connection connection = database()) {
            Connection lostAck = (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(), new Class<?>[]{Connection.class},
                    (proxy, method, arguments) -> {
                        try {
                            Object result = method.invoke(connection, arguments);
                            if (method.getName().equals("commit")) throw new SQLException("synthetic lost commit acknowledgment");
                            return result;
                        } catch (InvocationTargetException wrapped) { throw wrapped.getCause(); }
                    });
            try (PvcSqlTransactions writes = new PvcSqlTransactions(work -> work.execute(lostAck), 8, "pvc_")) {
                assertTrue(PvcSqlTransactions.hasUncertainCommit(failure(writes.submit(Collections.<IDatabaseTransaction>singletonList(insert(1, "durable", new byte[]{1}))))));
                assertEquals(1, count(connection)); assertTrue(writes.isCommitOutcomeUncertain());
            }
        }
    }
    @Test public void failedRollbackNeverAutoCommitsPartialRows() throws Exception {
        try (Connection connection = database()) {
            AtomicInteger unsafeRestores = new AtomicInteger();
            Connection failedRollback = (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(), new Class<?>[]{Connection.class},
                    (proxy, method, arguments) -> {
                        if (method.getName().equals("rollback")) throw new SQLException("synthetic rollback failure");
                        if (method.getName().equals("setAutoCommit") && Boolean.TRUE.equals(arguments[0])) unsafeRestores.incrementAndGet();
                        try { return method.invoke(connection, arguments); }
                        catch (InvocationTargetException wrapped) { throw wrapped.getCause(); }
                    });
            try (PvcSqlTransactions writes = new PvcSqlTransactions(work -> work.execute(failedRollback), 8, "pvc_")) {
                assertTrue(PvcSqlTransactions.hasUncertainCommit(failure(writes.submit(Arrays.<IDatabaseTransaction>asList(
                        insert(1, "pending", new byte[]{1}), insert(1, "duplicate", new byte[]{2}))))));
                assertEquals(0, unsafeRestores.get()); assertFalse(connection.getAutoCommit());
                connection.rollback(); connection.setAutoCommit(true); assertEquals(0, count(connection));
            }
        }
    }
    @Test public void unrelatedTransactionIsNeitherJoinedNorRolledBack() throws Exception {
        try (Connection connection = database(); PvcSqlTransactions writes = new PvcSqlTransactions(work -> work.execute(connection), 8, "pvc_")) {
            connection.setAutoCommit(false);
            try (PreparedStatement statement = connection.prepareStatement("INSERT INTO pvc_records (id,value) VALUES (9,'external')")) {
                statement.executeUpdate();
            }
            assertTrue(failure(writes.submit(Collections.<IDatabaseTransaction>singletonList(insert(1, "ours", new byte[]{1})))) instanceof SQLException);
            assertFalse(connection.getAutoCommit()); assertEquals(1, count(connection));
            connection.rollback(); connection.setAutoCommit(true); assertEquals(0, count(connection));
        }
    }
    @Test public void uncheckedDriverErrorRollsBackInsteadOfAutoCommittingPartialRows() throws Exception {
        try (Connection connection = database()) {
            AtomicInteger updates = new AtomicInteger();
            Connection failingDriver = (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(), new Class<?>[]{Connection.class},
                    (proxy, method, arguments) -> {
                        try {
                            Object result = method.invoke(connection, arguments);
                            if (!method.getName().equals("prepareStatement")) return result;
                            PreparedStatement prepared = (PreparedStatement) result;
                            return Proxy.newProxyInstance(PreparedStatement.class.getClassLoader(), new Class<?>[]{PreparedStatement.class},
                                    (statementProxy, statementMethod, statementArguments) -> {
                                        if (statementMethod.getName().equals("executeUpdate") && updates.incrementAndGet() == 2)
                                            throw new AssertionError("synthetic driver error");
                                        try { return statementMethod.invoke(prepared, statementArguments); }
                                        catch (InvocationTargetException wrapped) { throw wrapped.getCause(); }
                                    });
                        } catch (InvocationTargetException wrapped) { throw wrapped.getCause(); }
                    });
            try (PvcSqlTransactions writes = new PvcSqlTransactions(work -> work.execute(failingDriver), 8, "pvc_")) {
                assertTrue(failure(writes.submit(Arrays.<IDatabaseTransaction>asList(
                        insert(1, "first", new byte[]{1}), insert(2, "second", new byte[]{2})))) instanceof AssertionError);
                assertEquals(0, count(connection)); assertTrue(connection.getAutoCommit());
            }
        }
    }
    @Test public void boundedQueueRejectsInsteadOfAcknowledgingAnUnexecutedWrite() throws Exception {
        try (Connection connection = database()) {
            CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
            AtomicInteger calls = new AtomicInteger();
            try (PvcSqlTransactions writes = new PvcSqlTransactions(work -> {
                if (calls.incrementAndGet() == 1) { entered.countDown(); if (!release.await(3, TimeUnit.SECONDS)) throw new SQLException("test admission gate timeout"); }
                work.execute(connection);
            }, 1, "pvc_")) {
                CompletableFuture<Void> first = writes.submit(Collections.<IDatabaseTransaction>singletonList(insert(1, "first", new byte[]{1})));
                assertTrue(entered.await(3, TimeUnit.SECONDS));
                CompletableFuture<Void> queued = writes.submit(Collections.<IDatabaseTransaction>singletonList(insert(2, "queued", new byte[]{2})));
                assertEquals(1, writes.getPendingCount());
                CompletableFuture<Void> rejected = writes.submit(Collections.<IDatabaseTransaction>singletonList(insert(3, "rejected", new byte[]{3})));
                assertTrue(failure(rejected) instanceof RejectedExecutionException);
                // A rejected submission must not iterate or clone a large payload
                // before admission: queue size alone would not bound heap usage.
                AbstractCollection<IDatabaseTransaction> unmaterialized = new AbstractCollection<IDatabaseTransaction>() {
                    @Override public int size() { throw new AssertionError("Rejected payload was inspected"); }
                    @Override public Iterator<IDatabaseTransaction> iterator() { throw new AssertionError("Rejected payload was materialized"); }
                };
                assertTrue(failure(writes.submit(unmaterialized)) instanceof RejectedExecutionException);
                assertEquals(1, writes.getPendingCount());
                release.countDown(); first.get(3, TimeUnit.SECONDS); queued.get(3, TimeUnit.SECONDS);
                assertEquals(2, count(connection));
            } finally { release.countDown(); }
        }
    }
    @Test public void queuedRowsAndBinaryPayloadAreImmutableAfterSubmission() throws Exception {
        try (Connection connection = database()) {
            CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
            AtomicInteger calls = new AtomicInteger();
            try (PvcSqlTransactions writes = new PvcSqlTransactions(work -> {
                if (calls.incrementAndGet() == 1) { entered.countDown(); if (!release.await(3, TimeUnit.SECONDS)) throw new SQLException("test snapshot gate timeout"); }
                work.execute(connection);
            }, 4, "pvc_")) {
                CompletableFuture<Void> blocker = writes.submit(Collections.<IDatabaseTransaction>singletonList(insert(1, "blocker", new byte[]{0})));
                assertTrue(entered.await(3, TimeUnit.SECONDS));
                byte[] payload = {1, 2, 3};
                CustomSQLDatabaseTransaction transaction = insert(2, "original", payload);
                List<IDatabaseTransaction> mutableBatch = new ArrayList<>(); mutableBatch.add(transaction);
                CompletableFuture<Void> queued = writes.submit(mutableBatch);
                transaction.bindObjects(Arrays.asList(3, "added after submission", new byte[]{9}));
                payload[0] = 9; mutableBatch.clear();
                release.countDown(); blocker.get(3, TimeUnit.SECONDS); queued.get(3, TimeUnit.SECONDS);
                assertEquals(2, count(connection));
                try (PreparedStatement statement = connection.prepareStatement("SELECT value,payload FROM pvc_records WHERE id=2");
                     ResultSet result = statement.executeQuery()) {
                    assertTrue(result.next()); assertEquals("original", result.getString(1)); assertArrayEquals(new byte[]{1, 2, 3}, result.getBytes(2));
                }
            } finally { release.countDown(); }
        }
    }
    @Test public void closedWriterCannotAcknowledgeNewWrites() throws Exception {
        try (Connection connection = database()) {
            PvcSqlTransactions writes = new PvcSqlTransactions(work -> work.execute(connection), 8, "pvc_");
            writes.close();
            assertTrue(failure(writes.submit(Collections.<IDatabaseTransaction>singletonList(insert(1, "after close", new byte[]{1})))) instanceof RejectedExecutionException);
            assertTrue(failure(writes.submit(Collections.<IDatabaseTransaction>emptyList())) instanceof RejectedExecutionException);
            assertEquals(0, count(connection));
        }
    }
}

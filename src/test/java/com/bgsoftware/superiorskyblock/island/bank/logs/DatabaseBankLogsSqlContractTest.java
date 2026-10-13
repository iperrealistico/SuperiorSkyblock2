package com.bgsoftware.superiorskyblock.island.bank.logs;

import com.bgsoftware.common.databasebridge.logger.ILogger;
import com.bgsoftware.common.databasebridge.sql.query.QueryResult;
import com.bgsoftware.common.databasebridge.sql.session.SQLDatabaseSession;
import com.bgsoftware.common.databasebridge.transaction.DatabaseTransactionsExecutor;
import org.junit.Test;
import org.sqlite.SQLiteDataSource;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.*;

/** The production queries pass through Common's real prefix builder and real SQLite preparation. */
public class DatabaseBankLogsSqlContractTest {
    @Test public void cursorUsesBoundIslandAndReturnsZeroForAnEmptyHistory() throws Exception {
        try (Session session = new Session("")) {
            session.seed("owner-a", 2); session.seed("owner-a", 7); session.seed("owner-b", 99);
            assertEquals(Arrays.asList(7), query(session, false, "owner-a"));
            assertEquals(Arrays.asList(0), query(session, false, "absent"));
        }
    }

    @Test public void historyUsesMaintainedPrefixAndNeverExpandsTheBoundIdentity() throws Exception {
        try (Session session = new Session("pvc_")) {
            session.seed("owner-a", 2); session.seed("owner-a", 7); session.seed("owner-b", 99);
            List<Integer> history = query(session, true, "owner-a");
            java.util.Collections.sort(history);
            assertEquals(Arrays.asList(2, 7), history);
            assertTrue(query(session, true, "owner-a' OR 1=1 --").isEmpty());
        }
    }

    private static List<Integer> query(Session session, boolean history, String island) {
        List<Integer> positions = new ArrayList<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        AtomicBoolean called = new AtomicBoolean();
        session.customQuery(DatabaseBankLogs.sqlQuery(history), new QueryResult<PreparedStatement>()
                .onSuccess(statement -> {
                    statement.setString(1, island);
                    try (ResultSet rows = statement.executeQuery()) {
                        called.set(true);
                        while (rows.next()) positions.add(history ? rows.getInt("position") : rows.getInt(1));
                    }
                }).onFail(failure::set));
        assertNull("The maintained SQL builder must prepare and execute the bank query", failure.get());
        assertTrue("The cursor/history callback must execute before customQuery closes its statement", called.get());
        return positions;
    }

    private static final class BankArgs extends SQLDatabaseSession.Args {
        private BankArgs(String prefix) {
            super(prefix, "Bank logs SQL contract test", new ILogger() {
                @Override public void error(String message, Throwable error) { throw new AssertionError(message, error); }
                @Override public boolean hasDebugEnabled() { return false; }
                @Override public void debug(String message) { }
                @Override public void info(String message) { }
            });
        }
    }

    private static final class Session extends SQLDatabaseSession<BankArgs> implements AutoCloseable {
        private Connection anchor;
        private Session(String prefix) throws Exception {
            super(new BankArgs(prefix));
            assertTrue(connect());
            try (java.sql.Statement statement = anchor.createStatement()) {
                statement.executeUpdate("CREATE TABLE `" + prefix + "bank_transactions` (`island` TEXT, `position` INTEGER)");
            }
        }
        @Override public boolean connect() {
            SQLiteDataSource database = new SQLiteDataSource();
            database.setUrl("jdbc:sqlite:file:bank-logs-" + UUID.randomUUID() + "?mode=memory&cache=shared");
            this.dataSource = database;
            try { anchor = database.getConnection(); ready.complete(null); return true; }
            catch (SQLException failure) { throw new IllegalStateException(failure); }
        }
        private void seed(String island, int position) throws SQLException {
            try (PreparedStatement statement = anchor.prepareStatement("INSERT INTO `" + args.getDbPrefix() +
                    "bank_transactions` (`island`,`position`) VALUES (?,?)")) {
                statement.setString(1, island); statement.setInt(2, position); statement.executeUpdate();
            }
        }
        @Override public void close() {
            // These finite source tests own the Common sessions in their isolated test JVM.
            DatabaseTransactionsExecutor.stopActiveExecutors();
            try { if (anchor != null) anchor.close(); }
            catch (SQLException failure) { throw new IllegalStateException(failure); }
        }
    }
}

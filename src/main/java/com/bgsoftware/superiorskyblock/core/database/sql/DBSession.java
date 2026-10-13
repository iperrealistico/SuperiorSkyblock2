package com.bgsoftware.superiorskyblock.core.database.sql;

import com.bgsoftware.common.databasebridge.DatabaseSessionFactory;
import com.bgsoftware.common.databasebridge.logger.ILogger;
import com.bgsoftware.common.databasebridge.session.IDatabaseSession;
import com.bgsoftware.common.databasebridge.sql.query.Column;
import com.bgsoftware.common.databasebridge.sql.query.QueryResult;
import com.bgsoftware.common.databasebridge.sql.session.MariaDBDatabaseSession;
import com.bgsoftware.common.databasebridge.sql.session.MySQLDatabaseSession;
import com.bgsoftware.common.databasebridge.sql.session.SQLDatabaseSession;
import com.bgsoftware.common.databasebridge.sql.session.SQLiteDatabaseSession;
import com.bgsoftware.common.databasebridge.transaction.DatabaseTransactionsExecutor;
import com.bgsoftware.common.databasebridge.transaction.IDatabaseTransaction;
import com.bgsoftware.superiorskyblock.SuperiorSkyblockPlugin;
import com.bgsoftware.superiorskyblock.api.config.SettingsManager;
import com.bgsoftware.superiorskyblock.api.data.DatabaseFilter;
import com.bgsoftware.superiorskyblock.core.logging.Debug;
import com.bgsoftware.superiorskyblock.core.logging.Log;

import java.io.File;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.Collection;
import java.util.concurrent.CompletableFuture;

public class DBSession {

    private static final ILogger LOGGER = new ILogger() {
        @Override
        public void error(String message, Throwable error) {
            Log.error(error, message);
        }

        @Override
        public boolean hasDebugEnabled() {
            return Log.isDebugged(Debug.DATABASE_QUERY);
        }

        @Override
        public void debug(String message) {
            if (hasDebugEnabled())
                Log.debug(Debug.DATABASE_QUERY, message);
        }

        @Override
        public void info(String message) {
            Log.info(message);
        }
    };

    private static volatile SQLDatabaseSession<?> globalSession = null;
    private static final Object SQL_ACCESS = new Object();
    private static PvcSqlTransactions writes;

    private DBSession() {

    }

    public static boolean isReady() {
        return globalSession != null;
    }

    public static void waitForConnection() {
        if (isReady())
            globalSession.waitForConnection();
    }

    public static boolean createConnection(SuperiorSkyblockPlugin plugin) {
        SQLDatabaseSession<?> session = createSessionInternal(plugin, true);

        if (session.connect()) {
            globalSession = session;
            writes = new PvcSqlTransactions(work -> {
                synchronized (SQL_ACCESS) {
                    java.util.concurrent.atomic.AtomicReference<Throwable> failure = new java.util.concurrent.atomic.AtomicReference<>();
                    java.util.concurrent.atomic.AtomicBoolean called = new java.util.concurrent.atomic.AtomicBoolean();
                    session.customQuery("SELECT 1", new QueryResult<PreparedStatement>().onSuccess(statement -> {
                        called.set(true);
                        try { work.execute(statement.getConnection()); }
                        catch (java.sql.SQLException error) { throw error; }
                        catch (Exception error) { throw new java.sql.SQLException("Maintained SQL transaction failed", error); }
                    }).onFail(failure::set));
                    if (failure.get() != null) throw new java.sql.SQLException("JDBC commit failed", failure.get());
                    if (!called.get()) throw new java.sql.SQLException("JDBC connection callback was not invoked");
                }
            }, 1024, "MYSQL".equals(plugin.getSettings().getDatabase().getType()) ||
                    "MARIADB".equals(plugin.getSettings().getDatabase().getType()) ?
                    plugin.getSettings().getDatabase().getPrefix() : "", failure -> {
                plugin.getPvcIslandService().blockOperations(failure);
                plugin.getLogger().log(java.util.logging.Level.SEVERE,
                        "SQL outcome is uncertain. Ownership operations are paused; restart normally to reconcile durable journals. Do not retry transfers or resets before reconciliation.", failure);
            });
            return true;
        }

        return false;
    }

    public static CompletableFuture<Void> execute(IDatabaseTransaction transaction) {
        return execute(java.util.Collections.singletonList(transaction));
    }

    public static CompletableFuture<Void> execute(IDatabaseTransaction... transactions) {
        return execute(java.util.Arrays.asList(transactions));
    }

    public static CompletableFuture<Void> execute(Collection<IDatabaseTransaction> transactions) {
        if (writes == null) {
            CompletableFuture<Void> failure = new CompletableFuture<>();
            failure.completeExceptionally(new IllegalStateException("SQL connection is not ready"));
            return failure;
        }
        CompletableFuture<Void> result = writes.submit(transactions);
        result.whenComplete((unused, error) -> {
            if (error != null) Log.error(error, "A maintained SQL write failed; durable operation was not acknowledged.");
        });
        return result;
    }

    public static void createTable(String tableName, Column... columns) {
        withSession(session -> session.createTable(tableName, columns, QueryResult.EMPTY_VOID_QUERY_RESULT));
    }

    public static void createIndex(String indexName, String tableName, String... columns) {
        withSession(session -> session.createIndex(indexName, tableName, columns, QueryResult.EMPTY_VOID_QUERY_RESULT));
    }

    public static void modifyColumnType(String tableName, String columnName, String newType) {
        withSession(session -> session.modifyColumnType(tableName, columnName, newType, QueryResult.EMPTY_VOID_QUERY_RESULT));
    }

    public static void addColumn(String tableName, String columnName, String type) {
        withSession(session -> session.addColumn(tableName, columnName, type, QueryResult.EMPTY_VOID_QUERY_RESULT));
    }

    public static void removePrimaryKey(String tableName, String columnName) {
        withSession(session -> session.removePrimaryKey(tableName, columnName, QueryResult.EMPTY_VOID_QUERY_RESULT));
    }

    public static void select(String tableName, String filters, QueryResult<ResultSet> queryResult) {
        withSession(session -> session.select(tableName, filters, queryResult));
    }

    public static void setJournalMode(String jounralMode, QueryResult<ResultSet> queryResult) {
        withSession(session -> session.setJournalMode(jounralMode, queryResult));
    }

    public static void customQuery(String query, QueryResult<PreparedStatement> queryResult) {
        withSession(session -> session.customQuery(query, queryResult));
    }

    public static boolean hasUncertainCommit(Throwable failure) {
        return PvcSqlTransactions.hasUncertainCommit(failure);
    }

    public static boolean isCommitOutcomeUncertain() {
        return writes != null && writes.isCommitOutcomeUncertain();
    }

    private static void withSession(java.util.function.Consumer<SQLDatabaseSession<?>> work) {
        synchronized (SQL_ACCESS) {
            if (globalSession != null) work.accept(globalSession);
        }
    }

    public static void close() {
        if (isReady()) {
            if (writes != null) writes.close();
            DatabaseTransactionsExecutor.stopActiveExecutors();
            synchronized (SQL_ACCESS) {
                globalSession.close();
                globalSession = null;
            }
        }
    }

    public static String getColumnFilter(DatabaseFilter filter) {
        StringBuilder columnIdentifier = new StringBuilder();
        if (filter != null) {
            filter.forEach((column, value) -> {
                if (columnIdentifier.length() == 0) {
                    columnIdentifier.append(String.format(" WHERE %s=?", column));
                } else {
                    columnIdentifier.append(String.format(" AND %s=?", column));
                }
            });
        }
        return columnIdentifier.toString();
    }

    private static SQLDatabaseSession<?> createSessionInternal(SuperiorSkyblockPlugin plugin, boolean logging) {
        SettingsManager.Database database = plugin.getSettings().getDatabase();

        IDatabaseSession.Args args;
        switch (database.getType()) {
            case "MYSQL":
                args = new MySQLDatabaseSession.Args(database.getAddress(), database.getPort(), database.getDBName(),
                        database.getUsername(), database.getPassword(), database.getPrefix(), database.hasSSL(),
                        database.hasPublicKeyRetrieval(), database.getWaitTimeout(), database.getMaxLifetime(),
                        "SuperiorSkyblock Database Thread", LOGGER);
                break;
            case "MARIADB":
                args = new MariaDBDatabaseSession.Args(database.getAddress(), database.getPort(), database.getDBName(),
                        database.getUsername(), database.getPassword(), database.getPrefix(), database.hasSSL(),
                        database.hasPublicKeyRetrieval(), database.getWaitTimeout(), database.getMaxLifetime(),
                        "SuperiorSkyblock Database Thread", LOGGER);
                break;
            default:
                File databaseFile = new File(plugin.getDataFolder(), "datastore/database.db");
                args = new SQLiteDatabaseSession.Args(databaseFile,
                        "SuperiorSkyblock Database Thread", LOGGER);
                break;
        }

        SQLDatabaseSession<?> session = (SQLDatabaseSession<?>) DatabaseSessionFactory.createSession(args);
        if (logging)
            session.setLogging(true);
        return session;
    }

}


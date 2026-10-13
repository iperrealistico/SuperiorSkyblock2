package com.bgsoftware.superiorskyblock.core.database.sql;

import com.bgsoftware.common.annotations.Nullable;
import com.bgsoftware.common.databasebridge.sql.query.QueryResult;
import com.bgsoftware.common.databasebridge.sql.transaction.DeleteSQLDatabaseTransaction;
import com.bgsoftware.common.databasebridge.sql.transaction.InsertSQLDatabaseTransaction;
import com.bgsoftware.common.databasebridge.sql.transaction.UpdateSQLDatabaseTransaction;
import com.bgsoftware.common.databasebridge.transaction.IDatabaseTransaction;
import com.bgsoftware.superiorskyblock.api.data.DatabaseBridge;
import com.bgsoftware.superiorskyblock.api.data.DatabaseBridgeMode;
import com.bgsoftware.superiorskyblock.api.data.DatabaseFilter;
import com.bgsoftware.superiorskyblock.api.objects.Pair;
import com.bgsoftware.superiorskyblock.core.logging.Log;
import com.bgsoftware.superiorskyblock.core.mutable.MutableObject;

import java.sql.ResultSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

public class SQLDatabaseBridge implements DatabaseBridge {

    private volatile DatabaseBridgeMode databaseBridgeMode = DatabaseBridgeMode.IDLE;
    private final ThreadLocal<List<IDatabaseTransaction>> batchTransactions = new ThreadLocal<>();
    private final ThreadLocal<List<IDatabaseTransaction>> acknowledgedWrites = new ThreadLocal<>();
    private final ThreadLocal<Boolean> forcedAdmission = new ThreadLocal<>();

    /** Owned write-ahead metadata for an already-persisted island; never changes shared bridge mode. */
    public java.util.concurrent.CompletableFuture<Void> executeAcknowledgedForced(Runnable operation) {
        if (forcedAdmission.get() != null) throw new IllegalStateException("Nested forced SQL admission");
        forcedAdmission.set(Boolean.TRUE);
        try { return executeAcknowledged(operation); }
        finally { forcedAdmission.remove(); }
    }

    public boolean isWriteAdmissionForced() { return Boolean.TRUE.equals(forcedAdmission.get()); }

    /** Capture this operation's row writes without exposing a mutable batch to other region threads. */
    public java.util.concurrent.CompletableFuture<Void> executeAcknowledged(Runnable operation) {
        if (acknowledgedWrites.get() != null) throw new IllegalStateException("Nested acknowledged SQL batch");
        List<IDatabaseTransaction> transactions = new java.util.ArrayList<>();
        acknowledgedWrites.set(transactions);
        try { operation.run(); }
        catch (Throwable error) {
            java.util.concurrent.CompletableFuture<Void> result = new java.util.concurrent.CompletableFuture<>();
            result.completeExceptionally(error); return result;
        } finally { acknowledgedWrites.remove(); }
        return transactions.isEmpty() ? java.util.concurrent.CompletableFuture.completedFuture(null) : DBSession.execute(transactions);
    }

    public SQLDatabaseBridge() {

    }

    @Override
    public void loadAllObjects(String table, Consumer<Map<String, Object>> resultConsumer) {
        DBSession.select(table, "", new QueryResult<ResultSet>().onSuccess(resultSet -> {
            while (resultSet.next()) {
                try {
                    resultConsumer.accept(new ResultSetMapBridge(resultSet));
                } catch (Exception error) {
                    Log.entering("ENTER", table);
                    Log.error(error, "An unexpected error occurred while loading data from database:");
                }
            }
        }).onFail(QueryResult.PRINT_ERROR));
    }

    public void loadAllObjectsStrict(String table, Consumer<Map<String, Object>> consumer) {
        java.util.concurrent.atomic.AtomicReference<Throwable> failure = new java.util.concurrent.atomic.AtomicReference<>();
        DBSession.select(table, "", new QueryResult<ResultSet>().onSuccess(result -> {
            try { while (result.next()) consumer.accept(new ResultSetMapBridge(result)); }
            catch (Throwable error) { failure.set(error); }
        }).onFail(failure::set));
        if (failure.get() != null) throw new IllegalStateException("Cannot validate PVC metadata table " + table, failure.get());
    }

    @Override
    public void batchOperations(boolean batchOperations) {
        if (batchOperations) {
            if (batchTransactions.get() != null) throw new IllegalStateException("Nested legacy SQL batch");
            batchTransactions.set(new LinkedList<>());
        } else if (batchTransactions.get() != null) {
            List<IDatabaseTransaction> transactions = batchTransactions.get(); batchTransactions.remove();
            DBSession.execute(transactions).exceptionally(error -> { Log.error(error, "Cannot commit SQL batch"); return null; });
        }
    }

    @Override
    public void updateObject(String table, @Nullable DatabaseFilter filter, Pair<String, Object>[] columns) {
        if (databaseBridgeMode != DatabaseBridgeMode.SAVE_DATA && !isWriteAdmissionForced())
            return;

        List<String> filteredColumns = filter == null ? null : new LinkedList<>();
        List<String> columnNames = new LinkedList<>();
        List<Object> values = new LinkedList<>();

        for (Pair<String, Object> column : columns) {
            columnNames.add(column.getKey());
            values.add(column.getValue());
        }

        if (filter != null) {
            filter.forEach((column, value) -> {
                filteredColumns.add(column);
                values.add(value);
            });
        }

        UpdateSQLDatabaseTransaction transaction = new UpdateSQLDatabaseTransaction(table, columnNames, filteredColumns);
        transaction.bindObjects(values);
        submitTransaction(transaction);
    }

    @Override
    public void insertObject(String table, Pair<String, Object>... columns) {
        if (databaseBridgeMode != DatabaseBridgeMode.SAVE_DATA && !isWriteAdmissionForced())
            return;

        List<String> columnNames = new LinkedList<>();
        List<Object> values = new LinkedList<>();

        for (Pair<String, Object> column : columns) {
            columnNames.add(column.getKey());
            values.add(column.getValue());
        }

        InsertSQLDatabaseTransaction transaction = new InsertSQLDatabaseTransaction(table, columnNames);
        transaction.bindObjects(values);
        submitTransaction(transaction);
    }

    @Override
    public void deleteObject(String table, @Nullable DatabaseFilter filter) {
        if (databaseBridgeMode != DatabaseBridgeMode.SAVE_DATA && !isWriteAdmissionForced())
            return;

        List<String> filteredColumns = filter == null ? null : new LinkedList<>();
        List<Object> values = new LinkedList<>();

        if (filter != null) {
            filter.forEach((column, value) -> {
                filteredColumns.add(column);
                values.add(value + "");
            });
        }

        DeleteSQLDatabaseTransaction transaction = new DeleteSQLDatabaseTransaction(table, filteredColumns);
        transaction.bindObjects(values);

        submitTransaction(transaction);
    }

    @Override
    public void loadObject(String table, DatabaseFilter filter, Consumer<Map<String, Object>> resultConsumer) {
        MutableObject<String> columnFilter = new MutableObject<>(DBSession.getColumnFilter(filter));

        filter.forEach((column, value) -> {
            columnFilter.setValue(columnFilter.getValue().replaceFirst("\\?", value instanceof String ?
                    String.format("'%s'", value) : value.toString()));
        });

        DBSession.select(table, columnFilter.getValue(), new QueryResult<ResultSet>().onSuccess(resultSet -> {
            while (resultSet.next()) {
                try {
                    resultConsumer.accept(new ResultSetMapBridge(resultSet));
                } catch (Exception error) {
                    Log.entering("ENTER", table, columnFilter);
                    Log.error(error, "An unexpected error occurred while loading data from database:");
                }
            }
        }).onFail(QueryResult.PRINT_ERROR));
    }

    @Override
    public void setDatabaseBridgeMode(DatabaseBridgeMode databaseBridgeMode) {
        this.databaseBridgeMode = databaseBridgeMode;
    }

    @Override
    public DatabaseBridgeMode getDatabaseBridgeMode() {
        return this.databaseBridgeMode;
    }

    private void submitTransaction(IDatabaseTransaction transaction) {
        List<IDatabaseTransaction> acknowledged = acknowledgedWrites.get();
        if (acknowledged != null) { acknowledged.add(transaction); return; }
        if (batchTransactions.get() != null) {
            batchTransactions.get().add(transaction);
        } else {
            DBSession.execute(transaction);
        }
    }

}

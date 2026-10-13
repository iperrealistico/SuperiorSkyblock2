package com.bgsoftware.superiorskyblock.island.bank.logs;

import com.bgsoftware.superiorskyblock.api.island.bank.BankTransaction;
import org.junit.Test;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import static org.junit.Assert.*;

public class CacheBankLogsConcurrencyTest {
    @Test public void positionAllocationIsUniqueAndResumesAfterLoadedCursor() throws Exception {
        CacheBankLogs logs = new CacheBankLogs(); UUID player = UUID.randomUUID();
        logs.addTransaction(transaction(90, player), player, true);
        ExecutorService workers = Executors.newFixedThreadPool(4);
        try {
            List<Future<Integer>> futures = new ArrayList<>();
            for (int i = 0; i < 32; i++) futures.add(workers.submit(logs::nextTransactionPosition));
            Set<Integer> positions = new HashSet<>();
            for (Future<Integer> future : futures) positions.add(future.get(5, java.util.concurrent.TimeUnit.SECONDS));
            assertEquals(32, positions.size()); assertEquals(Integer.valueOf(91), java.util.Collections.min(positions));
            assertEquals(122, logs.getLastTransactionPosition());
        } finally { workers.shutdownNow(); }
    }
    @Test public void returnedPlayerHistoryIsDetachedFromFutureWrites() {
        CacheBankLogs logs = new CacheBankLogs(); UUID player = UUID.randomUUID();
        logs.addTransaction(transaction(1, player), player, false);
        List<BankTransaction> before = logs.getTransactions(player);
        logs.addTransaction(transaction(2, player), player, false);
        assertEquals(1, before.size()); assertEquals(2, logs.getTransactions(player).size());
    }
    private static BankTransaction transaction(int position, UUID player) {
        return (BankTransaction) Proxy.newProxyInstance(BankTransaction.class.getClassLoader(), new Class<?>[]{BankTransaction.class},
                (proxy, method, args) -> method.getName().equals("getPosition") ? position : method.getName().equals("getPlayer") ? player : null);
    }
}

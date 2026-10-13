package com.bgsoftware.superiorskyblock.island.bank;

import com.bgsoftware.common.annotations.Nullable;
import com.bgsoftware.superiorskyblock.SuperiorSkyblockPlugin;
import com.bgsoftware.superiorskyblock.api.enums.BankAction;
import com.bgsoftware.superiorskyblock.api.hooks.EconomyProvider;
import com.bgsoftware.superiorskyblock.api.island.Island;
import com.bgsoftware.superiorskyblock.api.island.bank.BankTransaction;
import com.bgsoftware.superiorskyblock.api.island.bank.IslandBank;
import com.bgsoftware.superiorskyblock.api.wrappers.SuperiorPlayer;
import com.bgsoftware.superiorskyblock.commands.CapturedCommandDispatch;
import com.bgsoftware.superiorskyblock.core.Text;
import com.bgsoftware.superiorskyblock.core.database.bridge.IslandsDatabaseBridge;
import com.bgsoftware.superiorskyblock.core.events.args.PluginEventArgs;
import com.bgsoftware.superiorskyblock.core.events.plugin.PluginEvent;
import com.bgsoftware.superiorskyblock.core.events.plugin.PluginEventsFactory;
import com.bgsoftware.superiorskyblock.core.formatting.Formatters;
import com.bgsoftware.superiorskyblock.core.logging.Log;
import com.bgsoftware.superiorskyblock.core.messages.Message;
import com.bgsoftware.superiorskyblock.core.threads.BukkitExecutor;
import com.bgsoftware.superiorskyblock.island.IslandUtils;
import com.bgsoftware.superiorskyblock.island.bank.logs.CacheBankLogs;
import com.bgsoftware.superiorskyblock.island.bank.logs.DatabaseBankLogs;
import com.bgsoftware.superiorskyblock.island.bank.logs.IBankLogs;
import com.bgsoftware.superiorskyblock.island.privilege.IslandPrivileges;
import com.bgsoftware.superiorskyblock.island.top.SortingTypes;
import com.bgsoftware.superiorskyblock.module.BuiltinModules;
import com.google.common.base.Preconditions;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.math.BigDecimal;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

/** Reservations protect a bank across region threads; external work always runs outside its monitor. */
public class SIslandBank implements IslandBank {
    private static final SuperiorSkyblockPlugin plugin = SuperiorSkyblockPlugin.getPlugin();
    private static final BigDecimal MONEY_FAILURE = BigDecimal.valueOf(-1);
    private static final UUID CONSOLE_UUID = new UUID(0L, 0L);
    private static final int MAX_PENDING_SETTLEMENTS = 128;
    private static final java.util.Set<SIslandBank> ACTIVE_BANKS = java.util.concurrent.ConcurrentHashMap.newKeySet();

    private final BankBalanceLedger ledger = new BankBalanceLedger();
    private final Object settlementLock = new Object();
    private final Object persistenceAdmission = new Object();
    private final Deque<Settlement> pendingWrites = new ArrayDeque<>();
    private final Island island;
    private final Supplier<Boolean> isGiveInterestFailed;
    private final IBankLogs bankLogs;
    private boolean writerRunning;
    private Settlement physicallyAdmitted;
    private int admitted;
    private volatile Throwable uncertainOutcome;
    private final java.util.Set<CompletableFuture<BankTransaction>> pendingWithdrawals = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private volatile boolean stopping;
    private volatile boolean quiescing;
    private final java.util.Set<CompletableFuture<Void>> quiescenceWaiters = new java.util.HashSet<>();
    private final java.util.function.Consumer<CompletableFuture<Void>> quiescenceDeadline;

    public SIslandBank(Island island, Supplier<Boolean> isGiveInterestFailed) {
        this(island, isGiveInterestFailed, !BuiltinModules.BANK.getConfiguration().isBankLogs() || BuiltinModules.BANK.getConfiguration().isCacheAllLogs()
                ? new CacheBankLogs() : new DatabaseBankLogs(island), null);
    }
    SIslandBank(Island island, Supplier<Boolean> isGiveInterestFailed, IBankLogs logs,
                java.util.function.Consumer<CompletableFuture<Void>> deadline) {
        this.island = island;
        this.isGiveInterestFailed = isGiveInterestFailed;
        this.bankLogs = logs;
        this.quiescenceDeadline = deadline == null ? this::scheduleQuiescenceDeadline : deadline;
    }

    @Override public BigDecimal getBalance() { return ledger.getBalance(); }
    @Override public void setBalance(BigDecimal balance) {
        synchronized (settlementLock) {
            if (quiescing || stopping || uncertainOutcome != null || admitted != 0)
                throw new IllegalStateException("A loading balance cannot replace an active, draining or uncertain bank");
            ledger.setBalance(balance);
        }
        if (isGiveInterestFailed.get()) island.giveInterest(false);
    }

    @Override public boolean canDepositMoney(BigDecimal amount) {
        Preconditions.checkNotNull(amount, "amount parameter cannot be null.");
        return uncertainOutcome == null && !stopping && !quiescing && plugin.getPvcIslandService().isAvailable(island.getUniqueId()) &&
                ledger.canDeposit(amount, island.getBankLimit());
    }

    @Override public BankTransaction depositMoney(SuperiorPlayer player, BigDecimal amount) {
        Preconditions.checkNotNull(player, "superiorPlayer parameter cannot be null.");
        Preconditions.checkNotNull(amount, "amount parameter cannot be null.");
        String denied = availability();
        if (denied != null) return failed(player.getUniqueId(), true, denied);
        if (!island.hasPermission(player, IslandPrivileges.DEPOSIT_MONEY)) return failed(player.getUniqueId(), true, "No permission");
        if (amount.signum() <= 0) return failed(player.getUniqueId(), true, "Invalid amount");
        BigDecimal playerBalance = plugin.getProviders().getBankEconomyProvider().getBalance(player);
        PluginEvent<PluginEventArgs.IslandBankDeposit> event = PluginEventsFactory.callIslandBankDepositEvent(island, player, amount);
        if (event.isCancelled()) return failed(player.getUniqueId(), true, event.getArgs().failureReason);
        if (playerBalance.compareTo(amount) < 0) return failed(player.getUniqueId(), true, "Not enough money");
        BankBalanceLedger.Reservation reservation;
        synchronized (settlementLock) {
            if (uncertainOutcome != null || stopping || quiescing || !plugin.getPvcIslandService().isAvailable(island.getUniqueId()))
                return failed(player.getUniqueId(), true, "Bank operations are paused");
            if (admitted >= MAX_PENDING_SETTLEMENTS) return failed(player.getUniqueId(), true, "Bank transaction queue is full");
            reservation = ledger.reserveDeposit(amount, island.getBankLimit());
            if (reservation != null) { ++admitted; ACTIVE_BANKS.add(this); }
        }
        if (reservation == null) return failed(player.getUniqueId(), true, "Exceed bank limit");
        try {
            EconomyProvider.EconomyResult result = plugin.getProviders().withdrawMoneyForBanks(player, reservation.amount);
            if (result.hasFailed()) { rollback(reservation); return failed(player.getUniqueId(), true, result.getErrorMessage()); }
            Settlement settled = settle(player.getUniqueId(), true, reservation, BigDecimal.valueOf(result.getTransactionMoney()));
            announce(player, settled.transaction, true);
            return settled.transaction;
        } catch (Throwable failure) {
            freeze(failure); // A throwing external provider may have already changed the player's balance.
            return failed(player.getUniqueId(), true, "Economy outcome is uncertain; bank operations are paused");
        }
    }

    @Override public BankTransaction depositAdminMoney(CommandSender sender, BigDecimal amount) {
        Preconditions.checkNotNull(sender, "commandSender parameter cannot be null.");
        Preconditions.checkNotNull(amount, "amount parameter cannot be null.");
        UUID actor = actor(sender);
        String denied = availability();
        if (denied != null) return failed(actor, true, denied);
        if (amount.signum() <= 0) return failed(actor, true, "Invalid amount");
        if (!admitAdministrative()) return failed(actor, true, "Bank transactions are paused or the queue is full");
        PluginEvent<PluginEventArgs.IslandBankDeposit> event;
        try { event = PluginEventsFactory.callIslandBankDepositEvent(island, sender, amount); }
        catch (Throwable failure) { releaseAdministrative(); throw failure; }
        if (event.isCancelled()) return recordAdministrativeFailure(actor, true, event.getArgs().failureReason, amount);
        Settlement settled = adjustAdmitted(actor, true, amount);
        if (settled == null) return failed(actor, true, "Bank operations are paused");
        refresh();
        return settled.transaction;
    }

    @Override public BankTransaction withdrawMoney(SuperiorPlayer player, BigDecimal amount, @Nullable List<String> commands) {
        Preconditions.checkNotNull(player, "superiorPlayer parameter cannot be null.");
        Preconditions.checkNotNull(amount, "amount parameter cannot be null.");
        if (commands != null && !commands.isEmpty() && BukkitExecutor.isFolia())
            return failed(player.getUniqueId(), false, "Configured withdrawals on Folia require withdrawMoneyAsync");
        Withdrawal withdrawal = prepareWithdrawal(player, amount);
        if (withdrawal.failure != null) return withdrawal.failure;
        try {
            BigDecimal actual;
            if (commands == null || commands.isEmpty()) {
                EconomyProvider.EconomyResult result = plugin.getProviders().depositMoneyForBanks(player, withdrawal.reservation.amount);
                if (result.hasFailed()) { rollback(withdrawal.reservation); return failed(player.getUniqueId(), false, result.getErrorMessage()); }
                actual = BigDecimal.valueOf(result.getTransactionMoney());
            } else {
                dispatchCommands(player, new ArrayList<>(commands), withdrawal);
                actual = withdrawal.reservation.amount;
            }
            Settlement settled = settle(player.getUniqueId(), false, withdrawal.reservation, actual);
            announce(player, settled.transaction, false);
            return settled.transaction;
        } catch (StaleWithdrawal failure) {
            rollback(withdrawal.reservation);
            return failed(player.getUniqueId(), false, failure.getMessage());
        } catch (Throwable failure) {
            freeze(failure);
            return failed(player.getUniqueId(), false, "Withdrawal outcome is uncertain; bank operations are paused");
        }
    }

    @Override public CompletableFuture<BankTransaction> withdrawMoneyAsync(SuperiorPlayer player, BigDecimal amount, @Nullable List<String> commands) {
        if (commands == null || commands.isEmpty()) return CompletableFuture.completedFuture(withdrawMoney(player, amount, commands));
        Preconditions.checkNotNull(player, "superiorPlayer parameter cannot be null.");
        Preconditions.checkNotNull(amount, "amount parameter cannot be null.");
        Withdrawal withdrawal = prepareWithdrawal(player, amount); // Events and economy remain on the caller's owning thread.
        if (withdrawal.failure != null) return CompletableFuture.completedFuture(withdrawal.failure);
        List<String> capturedCommands = new ArrayList<>(commands);
        CompletableFuture<BankTransaction> answer = new CompletableFuture<>();
        pendingWithdrawals.add(answer); ACTIVE_BANKS.add(this);
        final java.util.concurrent.atomic.AtomicBoolean claimed = new java.util.concurrent.atomic.AtomicBoolean();
        try {
            org.bukkit.scheduler.BukkitTask deadline = BukkitExecutor.async(() -> {
                if (answer.isDone()) return;
                if (claimed.compareAndSet(false, true)) {
                    rollback(withdrawal.reservation);
                    answer.complete(failed(player.getUniqueId(), false, "Configured withdrawal expired before dispatch"));
                } else {
                    java.util.concurrent.TimeoutException timeout = new java.util.concurrent.TimeoutException("Configured bank settlement did not acknowledge before deadline");
                    freeze(timeout); answer.completeExceptionally(timeout);
                }
            }, 20L * plugin.getPvcConfig().getChunkLoadTimeoutSeconds());
            answer.whenComplete((value, failure) -> { if (deadline != null) deadline.cancel(); pendingWithdrawals.remove(answer); retireIfIdle(); });
            BukkitExecutor.global(() -> {
                if (!claimed.compareAndSet(false, true)) return;
                if (answer.isDone() || stopping) { rollback(withdrawal.reservation); return; }
                try {
                    dispatchCommands(player, capturedCommands, withdrawal);
                    Settlement settled = settle(player.getUniqueId(), false, withdrawal.reservation, withdrawal.reservation.amount);
                    settled.durable.whenComplete((unused, failure) -> {
                        if (failure != null) answer.completeExceptionally(failure);
                        else {
                            if (answer.complete(settled.transaction)) announce(player, settled.transaction, false);
                        }
                    });
                } catch (StaleWithdrawal failure) {
                    rollback(withdrawal.reservation);
                    answer.complete(failed(player.getUniqueId(), false, failure.getMessage()));
                } catch (Throwable failure) {
                    freeze(failure);
                    answer.complete(failed(player.getUniqueId(), false, "Configured command outcome is uncertain; bank operations are paused"));
                }
            });
        } catch (Throwable failure) {
            if (claimed.compareAndSet(false, true)) rollback(withdrawal.reservation);
            pendingWithdrawals.remove(answer); answer.completeExceptionally(failure);
            retireIfIdle();
        }
        return answer;
    }

    private Withdrawal prepareWithdrawal(SuperiorPlayer player, BigDecimal amount) {
        String denied = availability();
        if (denied != null) return new Withdrawal(failed(player.getUniqueId(), false, denied));
        if (!island.hasPermission(player, IslandPrivileges.WITHDRAW_MONEY)) return new Withdrawal(failed(player.getUniqueId(), false, "No permission"));
        if (amount.signum() <= 0) return new Withdrawal(failed(player.getUniqueId(), false, "Invalid amount"));
        UUID owner = island.getOwner().getUniqueId();
        long revision = plugin.getPvcIslandService().getRevision(island.getUniqueId());
        long membership = plugin.getPvcIslandService().getMembershipRevision(island.getUniqueId());
        BankBalanceLedger.Reservation reservation;
        synchronized (settlementLock) {
            if (uncertainOutcome != null || stopping || quiescing || !plugin.getPvcIslandService().isAvailable(island.getUniqueId()))
                return new Withdrawal(failed(player.getUniqueId(), false, "Bank operations are paused"));
            if (admitted >= MAX_PENDING_SETTLEMENTS) return new Withdrawal(failed(player.getUniqueId(), false, "Bank transaction queue is full"));
            reservation = ledger.reserveWithdrawal(amount);
            if (reservation != null) { ++admitted; ACTIVE_BANKS.add(this); }
        }
        if (reservation == null) return new Withdrawal(failed(player.getUniqueId(), false, "Bank is empty or funds are reserved"));
        try {
            PluginEvent<PluginEventArgs.IslandBankWithdraw> event = PluginEventsFactory.callIslandBankWithdrawEvent(island, player, reservation.amount);
            if (event.isCancelled()) {
                rollback(reservation);
                return new Withdrawal(failed(player.getUniqueId(), false, event.getArgs().failureReason));
            }
            // {1} is the reserved payout, not the entire balance another pending withdrawal may already own.
            return new Withdrawal(reservation, owner, revision, membership, reservation.amount.toPlainString());
        } catch (Throwable failure) { rollback(reservation); throw failure; }
    }

    private void dispatchCommands(SuperiorPlayer player, List<String> commands, Withdrawal withdrawal) {
        if (plugin.getGrid().getIslandByUUID(island.getUniqueId()) != island ||
                !withdrawal.owner.equals(island.getOwner().getUniqueId()) ||
                withdrawal.revision != plugin.getPvcIslandService().getRevision(island.getUniqueId()) ||
                withdrawal.membership != plugin.getPvcIslandService().getMembershipRevision(island.getUniqueId()) ||
                !plugin.getPvcIslandService().isAvailable(island.getUniqueId()) ||
                !island.hasPermission(player, IslandPrivileges.WITHDRAW_MONEY))
            throw new StaleWithdrawal("Island or bank permissions changed before withdrawal");
        // Bukkit's true is dispatch acknowledgement, not a transactional receipt for arbitrary external plugins.
        for (String command : commands) {
            String resolved = command.replace("{0}", player.getName()).replace("{1}", withdrawal.balance);
            if (!CapturedCommandDispatch.dispatch(plugin, Bukkit.getConsoleSender(), resolved, island))
                throw new IllegalStateException("Configured bank command was not acknowledged");
        }
    }

    @Override public BankTransaction withdrawAdminMoney(CommandSender sender, BigDecimal amount) {
        Preconditions.checkNotNull(sender, "commandSender parameter cannot be null.");
        Preconditions.checkNotNull(amount, "amount parameter cannot be null.");
        UUID actor = actor(sender);
        String denied = availability();
        if (denied != null) return failed(actor, false, denied);
        if (amount.signum() <= 0) return failed(actor, false, "Invalid amount");
        if (!admitAdministrative()) return failed(actor, false, "Bank transactions are paused or the queue is full");
        PluginEvent<PluginEventArgs.IslandBankWithdraw> event;
        try { event = PluginEventsFactory.callIslandBankWithdrawEvent(island, sender, amount); }
        catch (Throwable failure) { releaseAdministrative(); throw failure; }
        if (event.isCancelled()) return recordAdministrativeFailure(actor, false, event.getArgs().failureReason, amount);
        Settlement settled;
        try { settled = adjustAdmitted(actor, false, amount); }
        catch (IllegalArgumentException unavailable) { return recordAdministrativeFailure(actor, false, unavailable.getMessage(), amount); }
        if (settled == null) return failed(actor, false, "Bank operations are paused");
        refresh();
        return settled.transaction;
    }

    private boolean admitAdministrative() {
        synchronized (settlementLock) {
            if (uncertainOutcome != null || stopping || quiescing || !plugin.getPvcIslandService().isAvailable(island.getUniqueId()) || admitted >= MAX_PENDING_SETTLEMENTS)
                return false;
            ++admitted; ACTIVE_BANKS.add(this); return true;
        }
    }
    private void releaseAdministrative() {
        synchronized (settlementLock) { --admitted; }
        retireIfIdle();
    }
    private Settlement adjustAdmitted(UUID actor, boolean deposit, BigDecimal amount) {
        Settlement settled;
        synchronized (settlementLock) {
            if (uncertainOutcome != null || stopping) { --admitted; return null; }
            settled = record(actor, deposit, amount, ledger.adjust(deposit ? amount : amount.negate()));
        }
        drainWrites();
        return settled;
    }

    private BankTransaction recordAdministrativeFailure(UUID actor, boolean deposit, String reason, BigDecimal amount) {
        BankTransaction transaction;
        boolean persist;
        synchronized (settlementLock) {
            transaction = new SBankTransaction(actor, deposit ? BankAction.DEPOSIT_FAILED : BankAction.WITHDRAW_FAILED,
                    bankLogs.nextTransactionPosition(), System.currentTimeMillis(), Text.isBlank(reason) ? "Transaction cancelled" : reason, amount);
            persist = BuiltinModules.BANK.getConfiguration().isBankLogs() && uncertainOutcome == null && !stopping;
            if (persist) {
                bankLogs.addTransaction(transaction, actor == null ? CONSOLE_UUID : actor, false);
                pendingWrites.addLast(new Settlement(transaction, ledger.getBalance()));
            } else --admitted;
        }
        if (persist) drainWrites(); else retireIfIdle();
        refresh();
        return transaction;
    }

    private Settlement settle(UUID actor, boolean deposit, BankBalanceLedger.Reservation reservation, BigDecimal amount) {
        Settlement settled;
        synchronized (settlementLock) { settled = record(actor, deposit, amount, ledger.settle(reservation, amount)); }
        drainWrites();
        return settled;
    }

    /** Memory-only, called while settlementLock is held so write snapshots retain commit order. */
    private Settlement record(UUID actor, boolean deposit, BigDecimal amount, BigDecimal balance) {
        BankTransaction transaction = new SBankTransaction(actor, deposit ? BankAction.DEPOSIT_COMPLETED : BankAction.WITHDRAW_COMPLETED,
                bankLogs.nextTransactionPosition(), System.currentTimeMillis(), "", amount);
        if (BuiltinModules.BANK.getConfiguration().isBankLogs())
            bankLogs.addTransaction(transaction, actor == null ? CONSOLE_UUID : actor, false);
        Settlement settled = new Settlement(transaction, balance);
        pendingWrites.addLast(settled);
        return settled;
    }

    private void rollback(BankBalanceLedger.Reservation reservation) {
        synchronized (settlementLock) { ledger.rollback(reservation); --admitted; }
        retireIfIdle();
    }

    private void drainWrites() {
        synchronized (settlementLock) {
            if (writerRunning || pendingWrites.isEmpty()) return;
            writerRunning = true;
        }
        ACTIVE_BANKS.add(this);
        try { BukkitExecutor.async(this::persistNext); }
        catch (Throwable failure) { freeze(failure); failWrites(failure); }
    }

    private void persistNext() {
        Settlement next;
        CompletableFuture<Void> persisted = null;
        synchronized (persistenceAdmission) {
            if (stopping) return;
            synchronized (settlementLock) {
                next = pendingWrites.peekFirst();
                if (next == null) writerRunning = false;
            }
            if (next != null) {
                physicallyAdmitted = next;
                // Standard SQL only captures immutable statements/enqueues here; it never runs JDBC.
                // A custom bridge may perform I/O and must instead be invoked outside this admission monitor.
                if (island.getDatabaseBridge() instanceof com.bgsoftware.superiorskyblock.core.database.sql.SQLDatabaseBridge)
                    persisted = persist(next);
            }
        }
        if (next == null) { retireIfIdle(); return; }
        if (persisted == null) persisted = persist(next);
        persisted.whenComplete((unused, failure) -> {
            if (failure != null) { freeze(failure); failWrites(failure); return; }
            boolean retired;
            synchronized (persistenceAdmission) {
                synchronized (settlementLock) {
                    retired = stopping || pendingWrites.peekFirst() != next;
                    if (!retired) { pendingWrites.removeFirst(); --admitted; }
                }
                if (physicallyAdmitted == next) physicallyAdmitted = null;
            }
            if (retired) return;
            next.durable.complete(null);
            // Only the database ACK releases the next physical write snapshot.
            try { BukkitExecutor.async(this::persistNext); }
            catch (Throwable stopping) { freeze(stopping); failWrites(stopping); }
        });
    }

    private CompletableFuture<Void> persist(Settlement settled) {
        try { return IslandsDatabaseBridge.saveBankSettlement(island, settled.balance,
                BuiltinModules.BANK.getConfiguration().isBankLogs() ? settled.transaction : null); }
        catch (Throwable failure) { CompletableFuture<Void> result = new CompletableFuture<>(); result.completeExceptionally(failure); return result; }
    }

    private void failWrites(Throwable failure) {
        List<Settlement> pending;
        synchronized (settlementLock) { pending = new ArrayList<>(pendingWrites); pendingWrites.clear(); writerRunning = false; }
        for (Settlement settled : pending) settled.durable.completeExceptionally(failure);
    }

    private String availability() {
        if (stopping) return "Bank is stopping";
        if (quiescing || !plugin.getPvcIslandService().isAvailable(island.getUniqueId())) return "Island bank is waiting for a lifecycle operation";
        if (uncertainOutcome != null) return "Bank outcome is uncertain; operations are paused";
        if (!bankLogs.isReady()) return "Bank logs are loading; try again shortly";
        return null;
    }
    private void freeze(Throwable failure) {
        uncertainOutcome = failure;
        checkQuiescence();
        Log.error(failure, "Bank settlement outcome is uncertain; do not retry payouts before operator reconciliation");
    }

    private void retireIfIdle() {
        synchronized (settlementLock) {
            if (!writerRunning && pendingWrites.isEmpty() && pendingWithdrawals.isEmpty() && admitted == 0)
                ACTIVE_BANKS.remove(this);
        }
        checkQuiescence();
    }

    @Override public CompletableFuture<Void> quiesceTransactions() {
        CompletableFuture<Void> result = new CompletableFuture<>();
        Throwable failure;
        boolean idle;
        synchronized (settlementLock) {
            quiescing = true;
            failure = uncertainOutcome != null ? uncertainOutcome : stopping ? new java.util.concurrent.RejectedExecutionException("Bank is stopping") : null;
            idle = admitted == 0 && pendingWrites.isEmpty() && pendingWithdrawals.isEmpty() && !writerRunning;
            if (failure == null && !idle) { quiescenceWaiters.add(result); ACTIVE_BANKS.add(this); }
        }
        if (failure != null) result.completeExceptionally(failure);
        else if (idle) result.complete(null);
        else {
            result.whenComplete((value, error) -> { synchronized (settlementLock) { quiescenceWaiters.remove(result); } });
            try { quiescenceDeadline.accept(result); }
            catch (Throwable rejected) { freeze(rejected); result.completeExceptionally(rejected); }
        }
        return result;
    }
    private void scheduleQuiescenceDeadline(CompletableFuture<Void> result) {
        org.bukkit.scheduler.BukkitTask timeout = BukkitExecutor.async(() -> {
            if (!result.isDone()) {
                java.util.concurrent.TimeoutException failure = new java.util.concurrent.TimeoutException("Bank transactions did not become quiescent; deletion must not proceed");
                freeze(failure); result.completeExceptionally(failure);
            }
        }, 20L * plugin.getPvcConfig().getChunkLoadTimeoutSeconds());
        result.whenComplete((value, error) -> { if (timeout != null) timeout.cancel(); });
    }
    void checkQuiescence() {
        List<CompletableFuture<Void>> complete;
        Throwable failure;
        synchronized (settlementLock) {
            failure = uncertainOutcome != null ? uncertainOutcome : stopping ? new java.util.concurrent.RejectedExecutionException("Bank is stopping") : null;
            if (failure == null && (admitted != 0 || !pendingWrites.isEmpty() || !pendingWithdrawals.isEmpty() || writerRunning)) return;
            complete = new ArrayList<>(quiescenceWaiters); quiescenceWaiters.clear();
        }
        for (CompletableFuture<Void> waiter : complete) {
            if (failure == null) waiter.complete(null); else waiter.completeExceptionally(failure);
        }
    }
    @Override public void resumeTransactions() {
        synchronized (settlementLock) { if (!stopping && uncertainOutcome == null) quiescing = false; }
        retireIfIdle();
    }

    /** The plugin calls this before cancelling owning schedulers: no caller is left with a hanging future. */
    public static void shutdownPending() {
        for (SIslandBank bank : new ArrayList<>(ACTIVE_BANKS)) {
            java.util.concurrent.RejectedExecutionException failure = new java.util.concurrent.RejectedExecutionException("Bank is stopping; pending external outcomes must not be retried automatically");
            synchronized (bank.persistenceAdmission) {
                bank.stopping = true;
                List<Settlement> queued;
                synchronized (bank.settlementLock) { queued = new ArrayList<>(bank.pendingWrites); }
                // Preserve accepted standard-SQL writes before DBSession.close drains its writer.
                // No synchronous JDBC and no wait/join occur on the shutdown thread.
                if (bank.island.getDatabaseBridge() instanceof com.bgsoftware.superiorskyblock.core.database.sql.SQLDatabaseBridge)
                    for (Settlement settled : queued) if (settled != bank.physicallyAdmitted) {
                        try { bank.persist(settled).whenComplete((unused, error) -> {
                            if (error != null) bank.freeze(error);
                        }); } catch (Throwable error) { bank.freeze(error); }
                    }
            }
            for (CompletableFuture<BankTransaction> answer : bank.pendingWithdrawals) answer.completeExceptionally(failure);
            bank.failWrites(failure);
            bank.checkQuiescence();
        }
        ACTIVE_BANKS.clear();
    }
    private BankTransaction failed(UUID actor, boolean deposit, String reason) {
        int position = bankLogs.isReady() ? bankLogs.nextTransactionPosition() : 0;
        return new SBankTransaction(actor, deposit ? BankAction.DEPOSIT_FAILED : BankAction.WITHDRAW_FAILED,
                position, System.currentTimeMillis(), Text.isBlank(reason) ? "Transaction cancelled" : reason, MONEY_FAILURE);
    }
    private void announce(SuperiorPlayer player, BankTransaction transaction, boolean deposit) {
        IslandUtils.sendMessage(island, deposit ? Message.DEPOSIT_ANNOUNCEMENT : Message.WITHDRAW_ANNOUNCEMENT,
                Collections.emptyList(), player.getName(), Formatters.NUMBER_FORMATTER.format(transaction.getAmount()));
        refresh();
    }
    private void refresh() {
        plugin.getGrid().getIslandsContainer().notifyChange(SortingTypes.BY_BANK, island);
        plugin.getMenus().refreshBankLogs(island);
        plugin.getMenus().refreshIslandBank(island);
    }
    private static UUID actor(CommandSender sender) { return sender instanceof Player ? ((Player) sender).getUniqueId() : null; }
    @Override public List<BankTransaction> getAllTransactions() { return bankLogs.getTransactions(); }
    @Override public List<BankTransaction> getTransactions(SuperiorPlayer player) { return bankLogs.getTransactions(player.getUniqueId()); }
    @Override public List<BankTransaction> getConsoleTransactions() { return bankLogs.getTransactions(CONSOLE_UUID); }
    @Override public void loadTransaction(BankTransaction transaction) {
        bankLogs.addTransaction(transaction, transaction.getPlayer() == null ? CONSOLE_UUID : transaction.getPlayer(), true);
    }

    private static final class Settlement {
        final BankTransaction transaction; final BigDecimal balance;
        final CompletableFuture<Void> durable = new CompletableFuture<>();
        Settlement(BankTransaction transaction, BigDecimal balance) { this.transaction = transaction; this.balance = balance; }
    }
    private static final class Withdrawal {
        final BankBalanceLedger.Reservation reservation; final UUID owner; final long revision, membership;
        final String balance; final BankTransaction failure;
        Withdrawal(BankTransaction failure) { this.failure = failure; reservation = null; owner = null; revision = membership = 0L; balance = null; }
        Withdrawal(BankBalanceLedger.Reservation reservation, UUID owner, long revision, long membership, String balance) {
            this.reservation = reservation; this.owner = owner; this.revision = revision; this.membership = membership; this.balance = balance; failure = null;
        }
    }
    private static final class StaleWithdrawal extends IllegalStateException {
        StaleWithdrawal(String message) { super(message); }
    }
}

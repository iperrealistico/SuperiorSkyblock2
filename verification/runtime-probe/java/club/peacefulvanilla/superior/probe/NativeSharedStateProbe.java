package club.peacefulvanilla.superior.probe;

import com.bgsoftware.superiorskyblock.SuperiorSkyblockPlugin;
import com.bgsoftware.superiorskyblock.api.island.Island;
import com.bgsoftware.superiorskyblock.api.island.IslandBlockFlags;
import com.bgsoftware.superiorskyblock.api.island.BlockChangeResult;
import com.bgsoftware.superiorskyblock.api.key.Key;
import com.bgsoftware.superiorskyblock.core.key.Keys;
import com.bgsoftware.superiorskyblock.core.threads.BukkitExecutor;
import org.bukkit.Bukkit;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/** Two functional actor actions on shared metadata; no player/load simulation or throughput benchmark. */
public final class NativeSharedStateProbe {
    private NativeSharedStateProbe() { }
    public static CompletableFuture<Void> run(JavaPlugin observer, SuperiorSkyblockPlugin superior, Player actor) {
        java.nio.file.Path root = observer.getServer().getWorldContainer().toPath().toAbsolutePath().normalize();
        if (root.getParent() == null || !root.getParent().getFileName().toString().equals("local-servers") ||
                !root.getFileName().toString().startsWith("superior-runtime-") ||
                !java.nio.file.Files.isRegularFile(root.resolve(".superior-owned")) ||
                !java.nio.file.Files.isRegularFile(root.resolve(".superior-commands-owned")))
            return failed(new IllegalStateException("Shared-state observer only accepts the owned commands fixture"));
        List<Island> owned = superior.getGrid().getOwnedIslands(actor.getUniqueId());
        Island target = owned.stream().min(Comparator.comparingLong(Island::getShortId)).orElseThrow();
        Player other = Bukkit.getPlayerExact("PVCSkyProbeB");
        if (other == null || owned.size() < 2) return failed(new IllegalStateException("Two fixture actors and multiple islands required"));
        verifyRefundReceipts(actor, other);
        Key block = Keys.ofMaterialAndData("DIAMOND_BLOCK"), entity = Keys.of(EntityType.MINECART);
        BigInteger original = target.getExactBlockCountAsBigInteger(block);
        BigDecimal worth = target.getWorth(), unitWorth = superior.getBlockValues().getBlockValue(block).getWorth();
        int originalEntities = target.getEntitiesTracker().getEntityCount(entity);
        BigDecimal bank = target.getIslandBank().getBalance();
        observer.getLogger().info("SUPERIOR_SHARED_STATE_BASELINE " + state(target, block, entity, original, worth, originalEntities));
        java.util.concurrent.atomic.AtomicReference<org.bukkit.Location> restore = new java.util.concurrent.atomic.AtomicReference<>();
        CompletableFuture<Void> separated = new CompletableFuture<>();
        BukkitExecutor.entity(other, () -> {
            if (!BukkitExecutor.isFolia()) { separated.complete(null); return; }
            org.bukkit.Location previous = other.getLocation().clone(); restore.set(previous);
            other.setAllowFlight(true); other.setFlying(true);
            org.bukkit.Location remote = previous.clone().add(4096, 0, 4096);
            other.teleportAsync(remote).whenComplete((success, error) -> {
                if (error != null) separated.completeExceptionally(error);
                else if (!Boolean.TRUE.equals(success)) separated.completeExceptionally(new IllegalStateException("Functional actor separation failed"));
                else separated.complete(null);
            });
        }, 0L, () -> separated.completeExceptionally(new IllegalStateException("Auxiliary actor retired")));
        return separated.thenCompose(unused -> CompletableFuture.allOf(action(actor, () -> {
            if (BukkitExecutor.isFolia() && BukkitExecutor.owns(other)) throw new IllegalStateException("Fixture actors must occupy actual distinct Folia regions");
            add(target, block, entity);
        }), action(other, () -> add(target, block, entity)))).thenCompose(unused -> {
            observer.getLogger().info("SUPERIOR_SHARED_STATE_ADDED " + state(target, block, entity,
                    original.add(BigInteger.valueOf(2)), worth.add(unitWorth.multiply(BigDecimal.valueOf(2))), originalEntities + 2));
            if (!target.getExactBlockCountAsBigInteger(block).equals(original.add(BigInteger.valueOf(2))) ||
                    target.getWorth().compareTo(worth.add(unitWorth.multiply(BigDecimal.valueOf(2)))) != 0 ||
                    target.getEntitiesTracker().getEntityCount(entity) != originalEntities + 2)
                return failed(new IllegalStateException("Shared tracker mutation lost a count or derived worth: " +
                        state(target, block, entity, original.add(BigInteger.valueOf(2)),
                                worth.add(unitWorth.multiply(BigDecimal.valueOf(2))), originalEntities + 2)));
            return CompletableFuture.allOf(action(actor, () -> remove(target, block, entity)), action(other, () -> remove(target, block, entity)));
        }).thenCompose(unused -> {
            observer.getLogger().info("SUPERIOR_SHARED_STATE_REMOVED " + state(target, block, entity, original, worth, originalEntities));
            if (!target.getExactBlockCountAsBigInteger(block).equals(original) || target.getWorth().compareTo(worth) != 0 ||
                    target.getEntitiesTracker().getEntityCount(entity) != originalEntities)
                return failed(new IllegalStateException("Shared tracker cleanup did not restore exact metadata: " +
                        state(target, block, entity, original, worth, originalEntities)));
            return CompletableFuture.allOf(action(actor, () -> target.getIslandBank().depositAdminMoney(Bukkit.getConsoleSender(), BigDecimal.ONE)),
                    action(other, () -> target.getIslandBank().depositAdminMoney(Bukkit.getConsoleSender(), BigDecimal.ONE)));
        }).thenCompose(unused -> {
            if (target.getIslandBank().getBalance().compareTo(bank.add(BigDecimal.valueOf(2))) != 0)
                return failed(new IllegalStateException("Concurrent administrative bank deposits lost a settlement"));
            return CompletableFuture.allOf(action(actor, () -> target.getIslandBank().withdrawAdminMoney(Bukkit.getConsoleSender(), BigDecimal.ONE)),
                    action(other, () -> target.getIslandBank().withdrawAdminMoney(Bukkit.getConsoleSender(), BigDecimal.ONE)));
        }).thenCompose(unused -> {
            if (target.getIslandBank().getBalance().compareTo(bank) != 0)
                throw new IllegalStateException("Bank cleanup did not restore fixture balance");
            return target.getIslandBank().quiesceTransactions();
        }).thenCompose(unused -> verifyUncachedBankReadback(target)).thenCompose(unused -> {
            target.getIslandBank().resumeTransactions();
            org.bukkit.Location previous = restore.get();
            if (previous == null) return CompletableFuture.completedFuture(null);
            CompletableFuture<Void> arrival = new CompletableFuture<>();
            BukkitExecutor.entity(other, () -> other.teleportAsync(previous).whenComplete((success, error) -> {
                if (error != null) arrival.completeExceptionally(error);
                else if (!Boolean.TRUE.equals(success)) arrival.completeExceptionally(new IllegalStateException("Auxiliary restore failed"));
                else arrival.complete(null);
            }), 0L, () -> arrival.completeExceptionally(new IllegalStateException("Auxiliary actor retired")));
            return arrival;
        }).thenRun(() -> observer.getLogger().info("SUPERIOR_SHARED_STATE_NATIVE_PASS actors=2 sharedIsland=" +
                target.getShortId() + " blockActions=4 entityActions=4 adminBankActions=4 durableBankACK=true uncachedSQLReadback=true"))
                .orTimeout(45, TimeUnit.SECONDS);
    }
    private static String state(Island target, Key block, Key entity, BigInteger expectedBlocks,
                                BigDecimal expectedWorth, int expectedEntities) {
        return "blocks=" + target.getExactBlockCountAsBigInteger(block) + "/" + expectedBlocks +
                " worth=" + target.getWorth() + "/" + expectedWorth +
                " entities=" + target.getEntitiesTracker().getEntityCount(entity) + "/" + expectedEntities +
                " rawWorth=" + target.getRawWorth() + " bonusWorth=" + target.getBonusWorth() +
                " bank=" + target.getIslandBank().getBalance() + " entityRecalc=" + entityRecalculating(target);
    }
    private static Object entityRecalculating(Island target) {
        try {
            var flag = target.getEntitiesTracker().getClass().getDeclaredField("beingRecalculated"); flag.setAccessible(true);
            return ((java.util.concurrent.atomic.AtomicBoolean) flag.get(target.getEntitiesTracker())).get();
        } catch (ReflectiveOperationException unsupported) { return "unavailable:" + unsupported.getClass().getSimpleName(); }
    }
    private static void add(Island target, Key block, Key entity) {
        if (target.handleBlockPlaceWithResult(block, 1, IslandBlockFlags.SAVE_BLOCK_COUNTS) != BlockChangeResult.SUCCESS ||
                !target.getEntitiesTracker().trackEntity(entity, 1)) throw new IllegalStateException("Fixture material/entity was not tracked");
    }
    private static void verifyRefundReceipts(Player first, Player second) {
        try {
            Class<?> type = Class.forName("com.bgsoftware.superiorskyblock.module.upgrades.type.UpgradeTypeEntityLimits$SpawningPlayerData");
            java.lang.reflect.Constructor<?> constructor = type.getDeclaredConstructor(Player.class, org.bukkit.inventory.ItemStack.class);
            constructor.setAccessible(true);
            var wheat = new org.bukkit.inventory.ItemStack(org.bukkit.Material.WHEAT, 1);
            var carrot = new org.bukkit.inventory.ItemStack(org.bukkit.Material.CARROT, 2);
            Object father = constructor.newInstance(first, wheat), mother = constructor.newInstance(second, carrot);
            java.lang.reflect.Field refunds = type.getDeclaredField("refunds"); refunds.setAccessible(true);
            List<?> paternal = (List<?>) refunds.get(father), maternal = (List<?>) refunds.get(mother);
            java.lang.reflect.Field player = paternal.get(0).getClass().getDeclaredField("player"); player.setAccessible(true);
            java.lang.reflect.Field item = paternal.get(0).getClass().getDeclaredField("item"); item.setAccessible(true);
            wheat.setAmount(64); carrot.setAmount(64);
            if (((java.lang.ref.WeakReference<?>) player.get(paternal.get(0))).get() != first ||
                    ((java.lang.ref.WeakReference<?>) player.get(maternal.get(0))).get() != second ||
                    ((org.bukkit.inventory.ItemStack) item.get(paternal.get(0))).getAmount() != 1 ||
                    ((org.bukkit.inventory.ItemStack) item.get(maternal.get(0))).getAmount() != 2)
                throw new IllegalStateException("Parent refund receipts lost their feeding actor or detached amount");
        } catch (ReflectiveOperationException failure) { throw new IllegalStateException("Could not verify maintained refund receipts", failure); }
    }
    private static CompletableFuture<Void> verifyUncachedBankReadback(Island island) {
        var actual = island.getIslandBank().getAllTransactions();
        int expected = actual.stream().mapToInt(com.bgsoftware.superiorskyblock.api.island.bank.BankTransaction::getPosition).max().orElse(0);
        if (expected < 4) return failed(new IllegalStateException("The private bank log fixture did not record four adjustments"));
        var readback = new com.bgsoftware.superiorskyblock.island.bank.logs.DatabaseBankLogs(island);
        CompletableFuture<Void> answer = new CompletableFuture<>();
        java.util.concurrent.atomic.AtomicInteger attempts = new java.util.concurrent.atomic.AtomicInteger();
        org.bukkit.scheduler.BukkitTask checking = BukkitExecutor.asyncRepeating(() -> {
            try {
                if (readback.isReady()) {
                    if (readback.getLastTransactionPosition() != expected)
                        throw new IllegalStateException("Uncached SQL cursor disagrees with the durable bank settlement");
                    var rows = readback.getTransactions();
                    int maximum = rows.stream().mapToInt(com.bgsoftware.superiorskyblock.api.island.bank.BankTransaction::getPosition).max().orElse(0);
                    if (maximum == expected && rows.size() >= 4 && rows.stream().map(com.bgsoftware.superiorskyblock.api.island.bank.BankTransaction::getPosition).distinct().count() == rows.size())
                        answer.complete(null);
                }
                if (!answer.isDone() && attempts.incrementAndGet() >= 40)
                    throw new IllegalStateException("Uncached SQL history did not finish its finite readback");
            } catch (Throwable failure) { answer.completeExceptionally(failure); }
        }, 1L, 5L);
        answer.whenComplete((value, failure) -> { if (checking != null) checking.cancel(); });
        return answer;
    }
    private static void remove(Island target, Key block, Key entity) {
        if (target.handleBlockBreakWithResult(block, 1, IslandBlockFlags.SAVE_BLOCK_COUNTS) != BlockChangeResult.SUCCESS ||
                !target.getEntitiesTracker().untrackEntity(entity, 1)) throw new IllegalStateException("Fixture metadata cleanup was rejected");
    }
    private static CompletableFuture<Void> action(Player actor, Runnable work) {
        CompletableFuture<Void> result = new CompletableFuture<>();
        try { BukkitExecutor.entity(actor, () -> {
            try { work.run(); result.complete(null); } catch (Throwable error) { result.completeExceptionally(error); }
        }, 1L, () -> result.completeExceptionally(new IllegalStateException("Fixture actor retired"))); }
        catch (Throwable failure) { result.completeExceptionally(failure); }
        return result;
    }
    private static <T> CompletableFuture<T> failed(Throwable failure) {
        CompletableFuture<T> result = new CompletableFuture<>(); result.completeExceptionally(failure); return result;
    }
}

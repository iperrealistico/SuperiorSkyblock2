package com.bgsoftware.superiorskyblock.core.threads;

import com.bgsoftware.common.annotations.Nullable;
import com.bgsoftware.superiorskyblock.SuperiorSkyblockPlugin;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.Consumer;
import java.util.function.Function;

/** Scheduling boundary. On Folia, sync/global means metadata only, never world ownership. */
public final class BukkitExecutor {
    public static final Executor SYNC_EXECUTOR = BukkitExecutor::ensureMain;
    public static final Executor ASYNC_EXECUTOR = BukkitExecutor::ensureAsync;
    private static volatile SuperiorSkyblockPlugin plugin;
    private static volatile ExecutionBridge bridge;
    private static volatile State state = State.RUNNING;
    private BukkitExecutor() { }

    public static synchronized void init(SuperiorSkyblockPlugin owner) {
        if (bridge != null && plugin == owner && state == State.RUNNING) return;
        plugin = owner;
        state = State.RUNNING;
        boolean modern = false;
        boolean folia = false;
        try { Class.forName("io.papermc.paper.threadedregions.RegionizedServer"); folia = true; }
        catch (ClassNotFoundException ignored) { }
        try {
            Bukkit.class.getMethod("getRegionScheduler");
            modern = javaMajorVersion() >= 21;
        } catch (ReflectiveOperationException ignored) { }
        if (modern) {
            try {
                bridge = (ExecutionBridge) Class.forName("com.bgsoftware.superiorskyblock.platform.folia.PaperExecutionBridge", true,
                        owner.getPluginClassLoader()).getConstructor(Plugin.class).newInstance(owner);
            } catch (ReflectiveOperationException | LinkageError error) {
                if (folia) throw new IllegalStateException("Folia execution bridge could not be loaded", error);
                owner.getLogger().warning("Modern execution bridge unavailable; using Bukkit scheduler on Paper: " + error);
            }
        }
        if (bridge == null) {
            if (folia) throw new IllegalStateException("Folia requires the Java 21+ regional execution bridge");
            bridge = new LegacyExecutionBridge(owner);
        }
    }
    private static int javaMajorVersion() {
        String version = System.getProperty("java.specification.version", "8");
        return Integer.parseInt(version.startsWith("1.") ? version.substring(2) : version.split("\\.")[0]);
    }
    public static boolean isFolia() { return bridge != null && bridge.isFolia(); }
    public static boolean isRunning() { return bridge != null && state == State.RUNNING; }
    public static boolean owns(Entity entity) { return bridge != null && bridge.owns(entity); }
    public static boolean owns(World world, int x, int z) { return bridge != null && bridge.owns(world, x, z); }
    private static void accepting() {
        if (bridge == null || state != State.RUNNING) throw new RejectedExecutionException("SuperiorSkyblock execution is stopping");
    }
    @Nullable public static BukkitTask global(Runnable task) { return sync(task, 0L); }
    @Nullable public static BukkitTask global(Runnable task, long delay) { return sync(task, delay); }
    @Nullable public static BukkitTask ensureMain(Runnable task) {
        accepting();
        if (!isFolia() && Bukkit.isPrimaryThread()) { task.run(); return null; }
        return bridge.global(task, 0L);
    }
    @Nullable public static BukkitTask ensureAsync(Runnable task) {
        if (state == State.PREPARE_SHUTDOWN) { task.run(); return null; }
        accepting();
        return bridge.async(task, 0L);
    }
    @Nullable public static BukkitTask sync(Runnable task) { return sync(task, 0L); }
    @Nullable public static BukkitTask sync(Runnable task, long delay) {
        accepting(); return bridge.global(task, delay);
    }
    @Nullable public static BukkitTask region(World world, int x, int z, Runnable task) { return region(world, x, z, task, 0L); }
    @Nullable public static BukkitTask region(World world, int x, int z, Runnable task, long delay) {
        accepting();
        if (delay <= 0L && owns(world, x, z)) { task.run(); return null; }
        return bridge.region(world, x, z, task, delay);
    }
    @Nullable public static BukkitTask entity(Entity entity, Runnable task) { return entity(entity, task, 0L, null); }
    @Nullable public static BukkitTask entity(Entity entity, Runnable task, long delay) { return entity(entity, task, delay, null); }
    @Nullable public static BukkitTask entity(Entity entity, Runnable task, long delay, Runnable retired) {
        accepting();
        if (delay <= 0L && owns(entity)) { task.run(); return null; }
        return bridge.entity(entity, task, delay, retired);
    }
    @Nullable public static BukkitTask async(Runnable task) { return async(task, 0L); }
    @Nullable public static BukkitTask async(Runnable task, long delay) {
        if (state == State.PREPARE_SHUTDOWN) { task.run(); return null; }
        accepting(); return bridge.async(task, delay);
    }
    public static void asyncTimer(Runnable task, long delay) { accepting(); bridge.asyncTimer(task, delay, delay); }
    public static void timer(Runnable task, long delay) { accepting(); bridge.globalTimer(task, delay, delay); }
    public static BukkitTask asyncRepeating(Runnable task, long delay, long period) { accepting(); return bridge.asyncTimer(task, delay, period); }
    public static BukkitTask globalRepeating(Runnable task, long delay, long period) { accepting(); return bridge.globalTimer(task, delay, period); }
    public static NestedTask<Void> createTask() { return new NestedTask<Void>(CompletableFuture.completedFuture(null)); }
    public static void prepareShutdown() { state = State.PREPARE_SHUTDOWN; }
    public static void close(SuperiorSkyblockPlugin owner) {
        state = State.SHUTDOWN;
        if (bridge != null) bridge.close();
    }
    public static final class NestedTask<T> {
        private final CompletableFuture<T> value;
        private NestedTask(CompletableFuture<T> value) { this.value = value; }
        private <R> NestedTask<R> next(Function<T, R> function, Executor executor) {
            // thenApplyAsync propagates source failures and completes callback exceptions.
            return new NestedTask<>(value.thenApplyAsync(function, executor));
        }
        public <R> NestedTask<R> runSync(Function<T, R> function) { return next(function, SYNC_EXECUTOR); }
        public NestedTask<Void> runSync(Consumer<T> consumer) { return next(v -> { consumer.accept(v); return null; }, SYNC_EXECUTOR); }
        public <R> NestedTask<R> runAsync(Function<T, R> function) { return next(function, ASYNC_EXECUTOR); }
        public NestedTask<Void> runAsync(Consumer<T> consumer) { return next(v -> { consumer.accept(v); return null; }, ASYNC_EXECUTOR); }
        public NestedTask<Void> runRegion(World world, int x, int z, Consumer<T> consumer) {
            return next(v -> { consumer.accept(v); return null; }, task -> region(world, x, z, task));
        }
        public NestedTask<Void> runEntity(Entity entity, Consumer<T> consumer) {
            return next(v -> { consumer.accept(v); return null; }, task -> entity(entity, task));
        }
        public CompletableFuture<T> future() { return value; }
    }
    private enum State { RUNNING, PREPARE_SHUTDOWN, SHUTDOWN }
}

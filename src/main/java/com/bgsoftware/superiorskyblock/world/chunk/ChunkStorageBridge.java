package com.bgsoftware.superiorskyblock.world.chunk;

import com.bgsoftware.superiorskyblock.SuperiorSkyblockPlugin;
import com.bgsoftware.superiorskyblock.core.ChunkPosition;
import com.bgsoftware.superiorskyblock.core.QualifiedNativeVersion;
import com.bgsoftware.superiorskyblock.core.threads.BukkitExecutor;
import com.bgsoftware.superiorskyblock.api.island.Island;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.World;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/** Bounded native storage acknowledgements; no IO future is joined on a tick thread. */
public final class ChunkStorageBridge {
    private static final SuperiorSkyblockPlugin plugin = SuperiorSkyblockPlugin.getPlugin();
    private static final Object lock = new Object();
    private static final Queue<Job<?>> waiting = new ArrayDeque<>();
    private static final java.util.Set<Job<?>> active = new java.util.HashSet<>();
    private static volatile ChunkStorageAdapter adapter;
    private static boolean resolved, stopped;
    private static BukkitTask expiry;
    private ChunkStorageBridge() { }

    private static synchronized ChunkStorageAdapter adapter() {
        if (resolved) return adapter;
        String version = Bukkit.getBukkitVersion();
        String suffix = QualifiedNativeVersion.adapterFor(version);
        if (suffix != null) {
            try {
                adapter = (ChunkStorageAdapter) Class.forName(
                        "com.bgsoftware.superiorskyblock.platform.folia.nms." + suffix + ".RegionalChunkStorage",
                        true, plugin.getPluginClassLoader()).getConstructor().newInstance();
            } catch (ReflectiveOperationException | LinkageError error) {
                throw new IllegalStateException("Native storage acknowledgements unavailable for " + version, error);
            }
        } else if (BukkitExecutor.isFolia()) {
            throw new IllegalStateException("Unqualified Folia storage ABI: " + version);
        }
        resolved = true;
        return adapter;
    }

    public static CompletableFuture<Boolean> isGenerated(World world, int x, int z) {
        try {
            ChunkStorageAdapter current = adapter();
            if (current != null) return current.isGenerated(world, x, z);
            CompletableFuture<Boolean> result = new CompletableFuture<>();
            BukkitExecutor.region(world, x, z, () -> {
                try { result.complete(world.loadChunk(x, z, false)); }
                catch (Throwable error) { result.completeExceptionally(error); }
            });
            return result;
        } catch (Throwable error) { return failed(error); }
    }

    public static CompletableFuture<Chunk> loadExistingChunk(World world, int x, int z) {
        try {
            ChunkStorageAdapter current = adapter();
            if (current != null) return current.loadExisting(world, x, z);
            CompletableFuture<Chunk> result = new CompletableFuture<>();
            BukkitExecutor.region(world, x, z, () -> {
                try { result.complete(world.loadChunk(x, z, false) ? world.getChunkAt(x, z) : null); }
                catch (Throwable error) { result.completeExceptionally(error); }
            });
            return result;
        } catch (Throwable error) { return failed(error); }
    }

    /** Region-file headers are queried in batches without generating or loading absent chunks. */
    public static CompletableFuture<List<ChunkPosition>> existingPositions(Collection<ChunkPosition> positions) {
        try {
            ChunkStorageAdapter current = adapter();
            if (current != null) return submit(() -> current.existingPositions(positions));
            List<ChunkPosition> existing = new ArrayList<>();
            return ChunksProvider.processExistingSequential(positions, ChunkLoadReason.DELETE_CHUNK,
                    chunk -> existing.add(ChunkPosition.of(chunk, false))).thenApply(unused -> existing);
        } catch (Throwable error) { return failed(error); }
    }

    /** Lazy positions share the bulk window until their real storage acknowledgement. */
    public static CompletableFuture<Void> saveChunksAsync(Collection<ChunkPosition> positions) {
        return saveChunksAsync(positions, () -> true, Runnable::run);
    }
    public static CompletableFuture<Void> saveChunksAsync(Island island, Collection<ChunkPosition> positions) {
        return saveChunksAsync(island, plugin.getPvcIslandService().getWorldGeneration(island.getUniqueId()), positions);
    }
    public static CompletableFuture<Void> saveChunksAsync(Island island, long generation, Collection<ChunkPosition> positions) {
        return saveChunksAsync(positions, () -> plugin.getPvcIslandService().isCurrentWorldWriter(island, generation),
                enqueue -> plugin.getPvcIslandService().runWorldWriteAdmission(island, generation, enqueue));
    }
    private static CompletableFuture<Void> saveChunksAsync(Collection<ChunkPosition> positions, BooleanSupplier currentWriter, Consumer<Runnable> admitWriter) {
        if (positions.isEmpty()) return CompletableFuture.completedFuture(null);
        try {
            if (adapter() == null) {
                // Existing Java8 platforms retain Bukkit's world-save contract,
                // once per operation/world. This can be expensive and is never
                // used on qualified modern servers or Folia.
                java.util.Set<World> worlds = new java.util.LinkedHashSet<>();
                for (ChunkPosition position : positions) worlds.add(position.getWorld());
                CompletableFuture<Void> result = new CompletableFuture<>();
                BukkitExecutor.global(() -> {
                    try {
                        if (!currentWriter.getAsBoolean()) throw new RejectedExecutionException("World write generation retired");
                        for (World world : worlds) world.save();
                        if (!currentWriter.getAsBoolean()) throw new RejectedExecutionException("World write generation retired");
                        result.complete(null);
                    }
                    catch (Throwable error) { result.completeExceptionally(error); }
                });
                return result;
            }
            return ChunksProvider.processExistingBoundedAsync(positions, ChunkLoadReason.API_REQUEST, chunk -> {
                if (!currentWriter.getAsBoolean()) return failed(new RejectedExecutionException("World write generation retired"));
                return saveChunkAsync(chunk, currentWriter, admitWriter);
            });
        } catch (Throwable error) { return failed(error); }
    }

    public static CompletableFuture<Void> saveChunkAsync(Chunk chunk) {
        return saveChunkAsync(chunk, () -> true, Runnable::run);
    }
    private static CompletableFuture<Void> saveChunkAsync(Chunk chunk, BooleanSupplier currentWriter, Consumer<Runnable> admitWriter) {
        return submit(() -> {
            CompletableFuture<Void> capture = new CompletableFuture<>();
            BukkitExecutor.region(chunk.getWorld(), chunk.getX(), chunk.getZ(), () -> {
                if (capture.isDone()) return;
                try { adapter().save(chunk, currentWriter, admitWriter).whenComplete((unused, error) -> {
                    if (error == null) capture.complete(null); else capture.completeExceptionally(error);
                }); } catch (Throwable error) { capture.completeExceptionally(error); }
            });
            return capture;
        });
    }

    private static <T> CompletableFuture<T> submit(Supplier<CompletableFuture<T>> operation) {
        Job<T> request = new Job<>(operation);
        List<Job<?>> ready;
        synchronized (lock) {
            if (stopped) return failed(new RejectedExecutionException("Chunk storage stopped"));
            if (waiting.size() + active.size() >= plugin.getPvcConfig().getChunkLoadQueueLimit())
                return failed(new RejectedExecutionException("Chunk storage admission limit reached"));
            waiting.add(request);
            if (expiry == null) expiry = BukkitExecutor.asyncRepeating(ChunkStorageBridge::expire, 20L, 20L);
            ready = admit();
        }
        ready.forEach(ChunkStorageBridge::begin);
        return request.result;
    }

    private static List<Job<?>> admit() {
        List<Job<?>> result = new ArrayList<>();
        while (!stopped && active.size() < plugin.getPvcConfig().getChunkLoadConcurrency() && !waiting.isEmpty()) {
            Job<?> request = waiting.remove(); active.add(request); result.add(request);
        }
        return result;
    }

    private static <T> void begin(Job<T> request) {
        if (request.result.isDone()) return;
        try { request.operation.get().whenComplete((value, error) -> finish(request, value, error)); }
        catch (Throwable error) { finish(request, null, error); }
    }

    private static <T> void finish(Job<T> request, T value, Throwable error) {
        Throwable reason = error;
        while (reason instanceof java.util.concurrent.CompletionException && reason.getCause() != null) reason = reason.getCause();
        if (reason instanceof TimeoutException) {
            plugin.getPvcIslandService().blockOperations(reason);
            stop();
            return;
        }
        List<Job<?>> ready;
        synchronized (lock) {
            if (!active.remove(request) && !waiting.remove(request)) return;
            ready = admit();
        }
        if (error == null) request.result.complete(value); else request.result.completeExceptionally(error);
        ready.forEach(ChunkStorageBridge::begin);
    }

    private static void expire() {
        List<Job<?>> expired = new ArrayList<>();
        long timeout = TimeUnit.SECONDS.toNanos(plugin.getPvcConfig().getChunkLoadTimeoutSeconds());
        synchronized (lock) {
            long now = System.nanoTime();
            for (Job<?> job : waiting) if (now - job.created >= timeout) expired.add(job);
            for (Job<?> job : active) if (now - job.created >= timeout) expired.add(job);
        }
        if (!expired.isEmpty()) {
            plugin.getPvcIslandService().blockOperations(new TimeoutException("Chunk storage admission/acknowledgement timed out"));
            stop();
        }
    }

    public static void stop() {
        List<Job<?>> pending;
        synchronized (lock) {
            stopped = true; pending = new ArrayList<>(waiting); pending.addAll(active); waiting.clear(); active.clear();
            if (expiry != null) { expiry.cancel(); expiry = null; }
        }
        for (Job<?> request : pending) request.result.completeExceptionally(new RejectedExecutionException("Storage stopping; world intent remains durable"));
    }
    public static int getPendingCount() { synchronized (lock) { return waiting.size() + active.size(); } }
    private static <T> CompletableFuture<T> failed(Throwable error) { CompletableFuture<T> result = new CompletableFuture<>(); result.completeExceptionally(error); return result; }
    private static final class Job<T> {
        final Supplier<CompletableFuture<T>> operation;
        final CompletableFuture<T> result = new CompletableFuture<>();
        final long created = System.nanoTime();
        Job(Supplier<CompletableFuture<T>> operation) { this.operation = operation; }
    }
}

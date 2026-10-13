package com.bgsoftware.superiorskyblock.world.chunk;

import com.bgsoftware.common.annotations.Nullable;
import com.bgsoftware.superiorskyblock.SuperiorSkyblockPlugin;
import com.bgsoftware.superiorskyblock.core.ChunkPosition;
import com.bgsoftware.superiorskyblock.core.threads.BukkitExecutor;
import com.bgsoftware.superiorskyblock.core.threads.BoundedWorkPipeline;
import org.bukkit.Chunk;
import org.bukkit.World;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;
import java.util.function.Function;

/** Bounded admission, coalesced loads and region-owned callbacks, including cache hits. */
public final class ChunksProvider {
    private static final SuperiorSkyblockPlugin plugin = SuperiorSkyblockPlugin.getPlugin();
    private static final Object lock = new Object();
    private static final Map<LoadKey, Request> requests = new HashMap<>();
    private static final Deque<Request> queue = new ArrayDeque<>();
    private static int inFlight;
    private static volatile boolean stopped;
    private static BukkitTask timeoutTask;
    private static final BoundedWorkPipeline<ChunkPosition> bulk = new BoundedWorkPipeline<>(
            () -> plugin.getPvcConfig().getChunkLoadConcurrency(),
            () -> plugin.getPvcConfig().getChunkLoadQueueLimit(), BukkitExecutor::ensureAsync);
    private ChunksProvider() { }

    public static CompletableFuture<Chunk> loadChunk(ChunkPosition position, ChunkLoadReason reason,
                                                    @Nullable Consumer<Chunk> consumer) {
        return loadChunkInternal(position, reason, consumer, false);
    }
    public static CompletableFuture<Chunk> loadExistingChunk(ChunkPosition position, ChunkLoadReason reason,
                                                             @Nullable Consumer<Chunk> consumer) {
        return loadChunkInternal(position, reason, consumer, true);
    }
    private static CompletableFuture<Chunk> loadChunkInternal(ChunkPosition position, ChunkLoadReason reason,
                                                               @Nullable Consumer<Chunk> consumer, boolean existingOnly) {
        final ChunkPosition key = position.copy();
        final LoadKey loadKey = new LoadKey(key, existingOnly);
        position.release();
        final Request request;
        List<Request> ready;
        synchronized (lock) {
            if (stopped) return failed(new RejectedExecutionException("Chunk service stopped"));
            Request existing = requests.get(loadKey);
            if (existing == null) {
                int limit = Math.max(1, plugin.getPvcConfig().getChunkLoadQueueLimit());
                if (requests.size() >= limit)
                    return failed(new RejectedExecutionException("SuperiorSkyblock chunk admission limit reached"));
                request = new Request(loadKey, reason);
                requests.put(loadKey, request);
                queue.addLast(request);
            } else request = existing;
            if (timeoutTask == null)
                timeoutTask = BukkitExecutor.asyncRepeating(ChunksProvider::expire, 20L, 20L);
            ready = takeReady();
        }
        ready.forEach(ChunksProvider::begin);
        if (consumer == null) return request.future;
        CompletableFuture<Chunk> result = new CompletableFuture<>();
        request.future.whenComplete((chunk, error) -> {
            if (error != null) { result.completeExceptionally(error); return; }
            if (chunk == null) { result.complete(null); return; }
            try {
                BukkitExecutor.region(chunk.getWorld(), chunk.getX(), chunk.getZ(), () -> {
                    try { consumer.accept(chunk); result.complete(chunk); }
                    catch (Throwable failure) { result.completeExceptionally(failure); }
                });
            } catch (Throwable failure) { result.completeExceptionally(failure); }
        });
        return result;
    }
    private static List<Request> takeReady() {
        int max = Math.max(1, plugin.getPvcConfig().getChunkLoadConcurrency());
        List<Request> ready = new ArrayList<>();
        while (!stopped && inFlight < max && !queue.isEmpty()) {
            Request request = queue.removeFirst();
            if (request.terminal) continue;
            request.started = true; inFlight++; ready.add(request);
        }
        return ready;
    }
    private static void begin(Request request) {
        World world = request.position.getWorld();
        if (world == null) { finish(request, null, new IllegalStateException("Islands world unavailable")); return; }
        if (request.key.existingOnly) {
            ChunkStorageBridge.isGenerated(world, request.position.getX(), request.position.getZ()).whenComplete((generated, error) -> {
                if (error != null) finish(request, null, error);
                else if (generated) beginLoad(request, world);
                else finish(request, null, null);
            });
        } else beginLoad(request, world);
    }
    private static void beginLoad(Request request, World world) {
        try {
            BukkitExecutor.region(world, request.position.getX(), request.position.getZ(), () -> {
                if (request.terminal || stopped) return;
                try {
                    if (world.isChunkLoaded(request.position.getX(), request.position.getZ())) {
                        finish(request, world.getChunkAt(request.position.getX(), request.position.getZ()), null);
                    } else {
                        (request.key.existingOnly ? ChunkStorageBridge.loadExistingChunk(world, request.position.getX(), request.position.getZ()) :
                                plugin.getProviders().getChunksProvider().loadChunk(world, request.position.getX(), request.position.getZ()))
                                .whenComplete((chunk, error) -> {
                                    if (error != null) { finish(request, null, error); return; }
                                    try { BukkitExecutor.region(world, request.position.getX(), request.position.getZ(), () -> finish(request, chunk, null)); }
                                    catch (Throwable failure) { finish(request, null, failure); }
                                });
                    }
                } catch (Throwable failure) { finish(request, null, failure); }
            });
        } catch (Throwable failure) { finish(request, null, failure); }
    }
    private static void finish(Request request, Chunk chunk, Throwable error) {
        List<Request> ready;
        synchronized (lock) {
            if (request.terminal) return;
            request.terminal = true;
            requests.remove(request.key, request);
            queue.remove(request);
            if (request.started) inFlight--;
            ready = takeReady();
        }
        if (error == null && (chunk != null || request.key.existingOnly)) request.future.complete(chunk);
        else request.future.completeExceptionally(error == null ? new IllegalStateException("Chunk provider returned null") : error);
        ready.forEach(ChunksProvider::begin);
    }
    private static void expire() {
        long timeout = TimeUnit.SECONDS.toNanos(Math.max(1, plugin.getPvcConfig().getChunkLoadTimeoutSeconds()));
        List<Request> queuedExpired = new ArrayList<>();
        List<Request> ready;
        boolean startedExpired = false;
        synchronized (lock) {
            long now = System.nanoTime();
            Iterator<Request> iterator = requests.values().iterator();
            while (iterator.hasNext()) {
                Request request = iterator.next();
                if (now - request.created < timeout) continue;
                if (request.started) {
                    startedExpired = true;
                } else {
                    // Retire queued work while holding the admission lock: it
                    // must not start between timeout detection and completion.
                    request.terminal = true;
                    iterator.remove();
                    queue.remove(request);
                    queuedExpired.add(request);
                }
            }
            if (startedExpired) stopped = true;
            ready = startedExpired ? java.util.Collections.emptyList() : takeReady();
        }
        for (Request request : queuedExpired)
            request.future.completeExceptionally(new TimeoutException("Queued chunk load timed out: " + request.position));
        if (startedExpired) {
            // Native chunk/IO futures have no reliable cancellation contract.
            // Reusing their slots after timeout would admit unbounded work.
            plugin.getPvcIslandService().blockOperations(new TimeoutException("In-flight chunk load timed out; restart required"));
            stop();
            return;
        }
        ready.forEach(ChunksProvider::begin);
    }
    /** One load/callback in flight for this job; does not preallocate a future per chunk. */
    public static CompletableFuture<Void> processSequential(Iterable<ChunkPosition> positions, ChunkLoadReason reason,
                                                             Consumer<Chunk> consumer) {
        return processSequential(positions, reason, consumer, false);
    }
    public static CompletableFuture<Void> processExistingSequential(Iterable<ChunkPosition> positions, ChunkLoadReason reason,
                                                                      Consumer<Chunk> consumer) {
        return processSequential(positions, reason, consumer, true);
    }
    private static CompletableFuture<Void> processSequential(Iterable<ChunkPosition> positions, ChunkLoadReason reason,
                                                               Consumer<Chunk> consumer, boolean existingOnly) {
        CompletableFuture<Void> result = new CompletableFuture<>();
        Iterator<ChunkPosition> iterator = positions.iterator();
        processNext(iterator, reason, consumer, result, existingOnly);
        return result;
    }
    private static void processNext(Iterator<ChunkPosition> iterator, ChunkLoadReason reason, Consumer<Chunk> consumer,
                                    CompletableFuture<Void> result, boolean existingOnly) {
        if (result.isDone()) return;
        final ChunkPosition next;
        try { if (!iterator.hasNext()) { result.complete(null); return; } next = iterator.next(); }
        catch (Throwable failure) { result.completeExceptionally(failure); return; }
        loadChunkInternal(next, reason, consumer, existingOnly).whenComplete((chunk, error) -> {
            if (error != null) result.completeExceptionally(error);
            else {
                try { BukkitExecutor.async(() -> processNext(iterator, reason, consumer, result, existingOnly)); }
                catch (Throwable failure) { result.completeExceptionally(failure); }
            }
        });
    }
    private static <T> CompletableFuture<T> failed(Throwable error) {
        CompletableFuture<T> future = new CompletableFuture<>(); future.completeExceptionally(error); return future;
    }

    /** Independent reset/storage work shares one global lazy window, not one window per island. */
    public static CompletableFuture<Void> processExistingBounded(Iterable<ChunkPosition> positions, ChunkLoadReason reason,
                                                                 Consumer<Chunk> consumer) {
        return processExistingBoundedAsync(positions, reason, chunk -> {
            consumer.accept(chunk);
            return CompletableFuture.completedFuture(null);
        });
    }

    public static CompletableFuture<Void> processExistingBoundedAsync(Iterable<ChunkPosition> positions, ChunkLoadReason reason,
                                                                      Function<Chunk, CompletableFuture<?>> consumer) {
        return bulk.submit(positions, position -> loadExistingChunk(position, reason, null).thenCompose(chunk -> {
            if (chunk == null) return CompletableFuture.completedFuture(null);
            CompletableFuture<Void> callback = new CompletableFuture<>();
            try {
                BukkitExecutor.region(chunk.getWorld(), chunk.getX(), chunk.getZ(), () -> {
                    try {
                        consumer.apply(chunk).whenComplete((unused, failure) -> {
                            if (failure == null) callback.complete(null); else callback.completeExceptionally(failure);
                        });
                    } catch (Throwable failure) { callback.completeExceptionally(failure); }
                });
            } catch (Throwable failure) { callback.completeExceptionally(failure); }
            return callback;
        }));
    }
    public static void stop() {
        bulk.close(new RejectedExecutionException("Chunk service stopped"));
        ChunkStorageBridge.stop();
        List<Request> pending;
        synchronized (lock) {
            stopped = true; pending = new ArrayList<>(requests.values());
            if (timeoutTask != null) { timeoutTask.cancel(); timeoutTask = null; }
        }
        for (Request request : pending) finish(request, null, new RejectedExecutionException("Chunk service stopped"));
    }
    public static void start() { stopped = false; }
    public static int getPendingCount() { synchronized (lock) { return requests.size(); } }
    private static final class Request {
        final LoadKey key;
        final ChunkPosition position; final ChunkLoadReason reason;
        final CompletableFuture<Chunk> future = new CompletableFuture<>();
        final long created = System.nanoTime();
        volatile boolean terminal; boolean started;
        Request(LoadKey key, ChunkLoadReason reason) { this.key = key; this.position = key.position; this.reason = reason; }
    }
    private static final class LoadKey {
        final ChunkPosition position; final boolean existingOnly;
        LoadKey(ChunkPosition position, boolean existingOnly) { this.position = position; this.existingOnly = existingOnly; }
        @Override public int hashCode() { return 31 * position.hashCode() + (existingOnly ? 1 : 0); }
        @Override public boolean equals(Object other) { return other instanceof LoadKey && existingOnly == ((LoadKey) other).existingOnly && position.equals(((LoadKey) other).position); }
    }
}

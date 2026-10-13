package com.bgsoftware.superiorskyblock.world;

import com.bgsoftware.superiorskyblock.SuperiorSkyblockPlugin;
import com.bgsoftware.superiorskyblock.core.ChunkPosition;
import com.bgsoftware.superiorskyblock.core.threads.BukkitExecutor;
import com.bgsoftware.superiorskyblock.world.chunk.ChunkLoadReason;
import com.bgsoftware.superiorskyblock.world.chunk.ChunksProvider;
import com.bgsoftware.superiorskyblock.world.chunk.ChunkRetention;
import org.bukkit.World;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;

/** A bounded, contiguous arena. No fight runs before every chunk belongs to its actor. */
public final class RegionalDragonArena {
    private static final SuperiorSkyblockPlugin plugin = SuperiorSkyblockPlugin.getPlugin();
    private static final Object admissionLock = new Object();
    private static final Queue<Admission> waiting = new ArrayDeque<>();
    private static int reservedChunks;
    private static BukkitTask admissionTask;
    private static final int RADIUS = 8;
    private static final int CHUNKS = (RADIUS * 2 + 1) * (RADIUS * 2 + 1);

    private final World world;
    private final int centerX, centerZ;
    private final List<ChunkPosition> positions = new ArrayList<>(CHUNKS);
    private final List<ChunkPosition> ticketed = new ArrayList<>();
    private volatile boolean requested, ready, closing;
    private volatile long expires;
    private volatile long generation;
    private Runnable onReady;
    private Consumer<Throwable> onFailure;
    private CompletableFuture<Void> loading;
    private boolean reserved;
    private boolean removingTickets;
    private int releaseAttempts;
    private CompletableFuture<Void> releaseResult = CompletableFuture.completedFuture(null);

    public RegionalDragonArena(World world, int centerX, int centerZ) {
        this.world = world;
        this.centerX = centerX;
        this.centerZ = centerZ;
        for (int x = centerX - RADIUS; x <= centerX + RADIUS; x++)
            for (int z = centerZ - RADIUS; z <= centerZ + RADIUS; z++)
                positions.add(ChunkPosition.of(world, x, z, false));
    }

    /** Admission is metadata only; loading and tickets are always chunk owned. */
    public synchronized boolean request(Runnable onReady, Consumer<Throwable> onFailure) {
        if (closing || requested)
            return false;
        synchronized (admissionLock) {
            if (waiting.size() >= plugin.getPvcConfig().getDragonArenaQueueLimit())
                return false;
            this.onReady = onReady;
            this.onFailure = onFailure;
            this.requested = true;
            ++this.generation;
            this.expires = System.nanoTime() + TimeUnit.SECONDS.toNanos(
                    plugin.getPvcConfig().getDragonArenaActivationTimeoutSeconds());
            waiting.add(new Admission(this, generation));
            if (admissionTask == null)
                admissionTask = BukkitExecutor.asyncRepeating(RegionalDragonArena::admit, 1L, 10L);
        }
        return true;
    }

    public boolean isReady() {
        return ready && !closing;
    }

    public static int getReservedChunkCount() {
        synchronized (admissionLock) { return reservedChunks; }
    }

    public static int getQueuedArenaCount() {
        synchronized (admissionLock) { return waiting.size(); }
    }

    public synchronized int getTicketedChunkCount() {
        return ticketed.size();
    }

    public boolean ownsArena() {
        if (!isReady())
            return false;
        for (ChunkPosition position : positions)
            if (!BukkitExecutor.owns(world, position.getX(), position.getZ()))
                return false;
        return true;
    }

    private static void admit() {
        List<Admission> admitted = new ArrayList<>();
        List<Admission> expired = new ArrayList<>();
        synchronized (admissionLock) {
            int budget = plugin.getPvcConfig().getDragonArenaChunkBudget();
            while (!waiting.isEmpty()) {
                Admission entry = waiting.peek();
                RegionalDragonArena arena = entry.arena;
                if (!arena.requested || arena.closing || arena.generation != entry.generation) { waiting.remove(); continue; }
                if (System.nanoTime() >= arena.expires) { waiting.remove(); expired.add(entry); continue; }
                if (reservedChunks + CHUNKS > budget)
                    break;
                waiting.remove();
                reservedChunks += CHUNKS;
                arena.reserved = true;
                admitted.add(entry);
            }
        }
        expired.forEach(entry -> entry.arena.fail(entry.generation, new TimeoutException("Dragon arena admission timed out")));
        admitted.forEach(entry -> entry.arena.load(entry.generation));
    }

    private synchronized void load(long expectedGeneration) {
        if (closing || !requested || generation != expectedGeneration)
            return;
        loading = ChunksProvider.processSequential(positions, ChunkLoadReason.API_REQUEST, chunk -> {
            synchronized (RegionalDragonArena.this) {
                if (closing || !requested || generation != expectedGeneration)
                    throw new RejectedExecutionException("Dragon arena was released during loading");
                acquireTicket(chunk.getX(), chunk.getZ());
                ticketed.add(ChunkPosition.of(chunk, false));
            }
        });
        loading.whenComplete((ignored, failure) -> {
            if (generation != expectedGeneration || !requested)
                return;
            if (failure != null) { fail(expectedGeneration, failure); return; }
            awaitRegion(expectedGeneration);
        });
    }

    private void awaitRegion(long expectedGeneration) {
        try {
            BukkitExecutor.region(world, centerX, centerZ, () -> {
                if (closing || !requested || generation != expectedGeneration)
                    return;
                boolean owned = true;
                for (ChunkPosition position : positions) {
                    if (!BukkitExecutor.owns(world, position.getX(), position.getZ())) { owned = false; break; }
                }
                if (owned) {
                    synchronized (RegionalDragonArena.this) {
                        if (closing || !requested || generation != expectedGeneration)
                            return;
                        ready = true;
                        try { onReady.run(); }
                        catch (Throwable failure) { fail(expectedGeneration, failure); }
                    }
                } else if (System.nanoTime() >= expires) {
                    fail(expectedGeneration, new TimeoutException("Contiguous dragon arena did not acquire one Folia region"));
                } else {
                    BukkitExecutor.async(() -> awaitRegion(expectedGeneration), 2L);
                }
            }, 1L);
        } catch (Throwable failure) { fail(expectedGeneration, failure); }
    }

    private void fail(long expectedGeneration, Throwable failure) {
        Consumer<Throwable> handler;
        synchronized (this) {
            if (generation != expectedGeneration || !requested)
                return;
            handler = onFailure;
            release();
        }
        if (handler != null)
            handler.accept(failure);
    }

    /** Safe to request from any thread, including domain deletion callbacks. */
    public void release() {
        releaseAsync();
    }

    /** Completes only after every real native ticket removal is acknowledged. */
    public CompletableFuture<Void> releaseAsync() {
        CompletableFuture<Void> pending;
        CompletableFuture<Void> result;
        final long releaseGeneration;
        synchronized (this) {
            if (closing)
                return releaseResult;
            closing = true;
            releaseResult = result = new CompletableFuture<>();
            requested = false;
            ready = false;
            releaseAttempts = 0;
            pending = loading;
            releaseGeneration = ++generation;
        }
        synchronized (admissionLock) {
            waiting.removeIf(entry -> entry.arena == this);
        }
        if (pending == null || pending.isDone())
            removeTickets(releaseGeneration);
        else
            pending.whenComplete((ignored, failure) -> removeTickets(releaseGeneration));
        return result;
    }

    private synchronized void removeTickets(long releaseGeneration) {
        if (removingTickets || generation != releaseGeneration || !closing)
            return;
        removingTickets = true;
        List<CompletableFuture<Void>> removals = new ArrayList<>();
        for (ChunkPosition position : new ArrayList<>(ticketed)) {
            CompletableFuture<Void> removal = new CompletableFuture<>();
            removals.add(removal);
            try {
                BukkitExecutor.region(world, position.getX(), position.getZ(), () -> {
                    try {
                        releaseTicket(position.getX(), position.getZ());
                        synchronized (RegionalDragonArena.this) {
                            if (generation == releaseGeneration)
                                ticketed.remove(position);
                        }
                        removal.complete(null);
                    }
                    catch (Throwable error) { removal.completeExceptionally(error); }
                });
            } catch (Throwable stopped) { removal.completeExceptionally(stopped); }
        }
        CompletableFuture.allOf(removals.toArray(new CompletableFuture[0]))
                .whenComplete((ignored, failure) -> {
                    if (failure == null) {
                        finishRelease(releaseGeneration);
                        return;
                    }
                    int attempt;
                    synchronized (RegionalDragonArena.this) {
                        if (generation != releaseGeneration || !closing)
                            return;
                        removingTickets = false;
                        attempt = ++releaseAttempts;
                    }
                    if (attempt == 1)
                        plugin.getLogger().log(java.util.logging.Level.SEVERE,
                                "Dragon arena ticket release failed; its chunk reservation remains held", failure);
                    if (attempt < 3) {
                        try { BukkitExecutor.async(() -> removeTickets(releaseGeneration), 20L); }
                        catch (RejectedExecutionException stopping) { releaseResult.completeExceptionally(stopping); }
                    } else {
                        releaseResult.completeExceptionally(failure);
                    }
                });
    }

    private void finishRelease(long releaseGeneration) {
        CompletableFuture<Void> result;
        synchronized (this) {
            if (generation != releaseGeneration || !closing) return;
            synchronized (admissionLock) {
                if (reserved) { reserved = false; reservedChunks -= CHUNKS; }
            }
            closing = false;
            removingTickets = false;
            loading = null;
            onReady = null;
            onFailure = null;
            result = releaseResult;
        }
        result.complete(null);
    }

    private static final class Admission {
        final RegionalDragonArena arena;
        final long generation;
        Admission(RegionalDragonArena arena, long generation) { this.arena = arena; this.generation = generation; }
    }

    private void acquireTicket(int x, int z) {
        ChunkRetention.acquire(world, x, z);
    }

    private void releaseTicket(int x, int z) {
        ChunkRetention.release(world, x, z);
    }
}

package com.bgsoftware.superiorskyblock.platform.folia.nms.v1_21_11;

import ca.spottedleaf.concurrentutil.util.Priority;
import ca.spottedleaf.concurrentutil.map.ConcurrentLong2ReferenceChainedHashTable;
import ca.spottedleaf.moonrise.common.PlatformHooks;
import ca.spottedleaf.moonrise.patches.chunk_system.io.MoonriseRegionFileIO;
import ca.spottedleaf.moonrise.patches.chunk_system.io.MoonriseRegionFileIO.RegionDataController;
import ca.spottedleaf.moonrise.patches.chunk_system.io.MoonriseRegionFileIO.RegionFileType;
import ca.spottedleaf.moonrise.patches.chunk_system.level.ChunkSystemServerLevel;
import ca.spottedleaf.moonrise.patches.chunk_system.scheduling.NewChunkHolder;
import com.bgsoftware.superiorskyblock.SuperiorSkyblockPlugin;
import com.bgsoftware.superiorskyblock.core.threads.BukkitExecutor;
import com.bgsoftware.superiorskyblock.core.ChunkPosition;
import com.bgsoftware.superiorskyblock.world.chunk.ChunkStorageAdapter;
import com.bgsoftware.superiorskyblock.world.chunk.NativeRegionFiles;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.storage.SerializableChunkData;
import org.bukkit.Chunk;
import org.bukkit.World;
import org.bukkit.craftbukkit.CraftWorld;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.EnumMap;
import java.util.Map;
import java.util.List;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/**
 * Owner captures, native ordered IO and a real per-region-file fsync/readback.
 * This class deliberately imports no Folia-only runtime classes: the matching
 * Paper/Leaf and Folia releases share the qualified Moonrise IO contracts;
 * the region-file bridge tolerates Leaf's IRegionFile return descriptors.
 */
public final class RegionalChunkStorage implements ChunkStorageAdapter {
    private static final String TOKEN = "pvc:world-write-token";
    private static final Field TASKS = field(RegionDataController.class, "chunkTasks");
    private static final Field FAILED_WRITE = taskFailure();
    private static final Method GET_STRING = stringGetter();
    private static final SuperiorSkyblockPlugin plugin = SuperiorSkyblockPlugin.getPlugin();

    @Override
    public CompletableFuture<Boolean> isGenerated(World world, int x, int z) {
        CompletableFuture<Boolean> result = new CompletableFuture<>();
        try {
            ServerLevel level = ((CraftWorld) world).getHandle();
            NewChunkHolder holder = ((ChunkSystemServerLevel) level).moonrise$getChunkTaskScheduler().chunkHolderManager.getChunkHolder(x, z);
            // Published immutable completion metadata, not a live block/entity read.
            NewChunkHolder.ChunkCompletion completion = holder == null ? null : holder.getLastChunkCompletion();
            if (completion != null && completion.genStatus() == net.minecraft.world.level.chunk.status.ChunkStatus.FULL) {
                result.complete(true);
            } else {
                MoonriseRegionFileIO.loadDataAsync(level, x, z, RegionFileType.CHUNK_DATA, false, Priority.NORMAL)
                    .whenComplete((tag, error) -> {
                        if (error != null) result.completeExceptionally(error);
                        else {
                            try { String status = tag == null ? "" : string(tag, "Status");
                                result.complete("minecraft:full".equals(status) || "full".equals(status));
                            } catch (Throwable failure) { result.completeExceptionally(failure); }
                        }
                    });
            }
        } catch (Throwable error) { result.completeExceptionally(error); }
        return result;
    }

    @Override
    public CompletableFuture<List<ChunkPosition>> existingPositions(Collection<ChunkPosition> positions) {
        Map<String, List<ChunkPosition>> regions = new LinkedHashMap<>();
        for (ChunkPosition position : positions) {
            String key = position.getWorldsInfo().getName() + ":" + (position.getX() >> 5) + ":" + (position.getZ() >> 5);
            regions.computeIfAbsent(key, ignored -> new ArrayList<>()).add(position);
        }
        CompletableFuture<List<ChunkPosition>> result = new CompletableFuture<>();
        scanRegion(regions.values().iterator(), new ArrayList<>(), result);
        return result;
    }

    private static void scanRegion(Iterator<List<ChunkPosition>> groups, List<ChunkPosition> existing,
                                    CompletableFuture<List<ChunkPosition>> result) {
        if (result.isDone()) return;
        try {
            if (!groups.hasNext()) { result.complete(existing); return; }
            List<ChunkPosition> positions = groups.next();
            ChunkPosition first = positions.get(0);
            ServerLevel level = ((CraftWorld) first.getWorld()).getHandle();
            RegionDataController controller = MoonriseRegionFileIO.getControllerFor(level, RegionFileType.CHUNK_DATA);
            controller.createRegionIoTask(first.getX(), first.getZ(), () -> {
                try {
                    Object region = NativeRegionFiles.existing(controller.getCache(), first.getX(), first.getZ());
                    for (ChunkPosition position : positions) {
                        NewChunkHolder holder = ((ChunkSystemServerLevel) level).moonrise$getChunkTaskScheduler().chunkHolderManager.getChunkHolder(position.getX(), position.getZ());
                        NewChunkHolder.ChunkCompletion completion = holder == null ? null : holder.getLastChunkCompletion();
                        boolean loaded = completion != null && completion.genStatus() == net.minecraft.world.level.chunk.status.ChunkStatus.FULL;
                        Object pending = currentTask(controller, position.getX(), position.getZ());
                        if (loaded || pending != null || (region != null && NativeRegionFiles.hasChunk(region, new net.minecraft.world.level.ChunkPos(position.getX(), position.getZ()))))
                            existing.add(position);
                    }
                    BukkitExecutor.async(() -> scanRegion(groups, existing, result));
                } catch (Throwable error) { result.completeExceptionally(error); }
            }, Priority.NORMAL).queue();
        } catch (Throwable error) { result.completeExceptionally(error); }
    }

    @Override
    public CompletableFuture<Chunk> loadExisting(World world, int x, int z) {
        return world.getChunkAtAsync(x, z, false);
    }

    @Override
    public CompletableFuture<Void> save(Chunk chunk, BooleanSupplier currentWriter, Consumer<Runnable> admitWriter) {
        CompletableFuture<Void> result = new CompletableFuture<>();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(plugin.getPvcConfig().getChunkLoadTimeoutSeconds());
        org.bukkit.scheduler.BukkitTask timeout = BukkitExecutor.async(() ->
                result.completeExceptionally(new TimeoutException("Native storage acknowledgement timed out")),
                20L * plugin.getPvcConfig().getChunkLoadTimeoutSeconds());
        result.whenComplete((unused, error) -> { if (timeout != null) timeout.cancel(); });
        capture(chunk, currentWriter, admitWriter, deadline, 0, result);
        return result;
    }

    private static void capture(Chunk chunk, BooleanSupplier currentWriter, Consumer<Runnable> admitWriter,
                                long deadline, int attempt, CompletableFuture<Void> result) {
        if (result.isDone()) return;
        try {
            if (!currentWriter.getAsBoolean()) throw new java.util.concurrent.RejectedExecutionException("World write generation retired before capture");
            if (!BukkitExecutor.owns(chunk.getWorld(), chunk.getX(), chunk.getZ()))
                throw new IllegalStateException("Storage capture requires the chunk owner");
            ServerLevel level = ((CraftWorld) chunk.getWorld()).getHandle();
            NewChunkHolder holder = ((ChunkSystemServerLevel) level).moonrise$getChunkTaskScheduler().chunkHolderManager.getChunkHolder(chunk.getX(), chunk.getZ());
            if (holder == null || !(holder.getCurrentChunk() instanceof LevelChunk))
                throw new IllegalStateException("Storage capture requires a retained full chunk");
            if (holder.getEntityChunk() == null || holder.getEntityChunk().isTransient() || holder.getPoiChunk() == null) {
                if (System.nanoTime() >= deadline) throw new TimeoutException("Native entity/POI payload did not become complete");
                BukkitExecutor.region(chunk.getWorld(), chunk.getX(), chunk.getZ(),
                        () -> capture(chunk, currentWriter, admitWriter, deadline, attempt, result), 1L);
                return;
            }
            LevelChunk nativeChunk = (LevelChunk) holder.getCurrentChunk();
            SerializableChunkData blocks = SerializableChunkData.copyOf(level, nativeChunk);
            PlatformHooks.get().chunkSyncSave(level, nativeChunk, blocks);
            Map<RegionFileType, CompoundTag> other = new EnumMap<>(RegionFileType.class);
            // Entity/POI data are captured by this same owner, never serialized
            // from live holders on an IO worker. Null means a native data deletion.
            if (holder.getEntityChunk() != null && !holder.getEntityChunk().isTransient())
                other.put(RegionFileType.ENTITY_DATA, holder.getEntityChunk().save());
            else throw new IllegalStateException("Storage capture requires the complete entity chunk");
            if (holder.getPoiChunk() != null) other.put(RegionFileType.POI_DATA, holder.getPoiChunk().save());
            else throw new IllegalStateException("Storage capture requires the loaded POI chunk");
            holder.save(false); // Native dirty flags and extension hooks remain intact.
            BukkitExecutor.async(() -> {
                try {
                    other.put(RegionFileType.CHUNK_DATA, blocks.write());
                    String token = UUID.randomUUID().toString();
                    if (!currentWriter.getAsBoolean()) throw new java.util.concurrent.RejectedExecutionException("World write generation retired before IO admission");
                    for (CompoundTag tag : other.values()) if (tag != null) tag.putString(TOKEN, token);
                    admitWriter.accept(() -> {
                        for (Map.Entry<RegionFileType, CompoundTag> entry : other.entrySet())
                            MoonriseRegionFileIO.scheduleSave(level, chunk.getX(), chunk.getZ(), entry.getValue(), entry.getKey(), Priority.NORMAL);
                    });
                    awaitWrites(chunk, currentWriter, admitWriter, level, other, token, deadline, attempt, result);
                } catch (Throwable error) { result.completeExceptionally(error); }
            });
        } catch (Throwable error) { result.completeExceptionally(error); }
    }

    private static void awaitWrites(Chunk chunk, BooleanSupplier currentWriter, Consumer<Runnable> admitWriter, ServerLevel level, Map<RegionFileType, CompoundTag> expected,
                                    String token, long deadline, int attempt, CompletableFuture<Void> result) {
        if (result.isDone()) return;
        try {
            if (!currentWriter.getAsBoolean()) throw new java.util.concurrent.RejectedExecutionException("World write generation retired before acknowledgement");
            if (System.nanoTime() >= deadline) throw new TimeoutException("Native chunk storage acknowledgement timed out");
            boolean idle = true;
            for (RegionFileType type : expected.keySet()) {
                RegionDataController controller = MoonriseRegionFileIO.getControllerFor(level, type);
                Object task = currentTask(controller, chunk.getX(), chunk.getZ());
                if (task != null) {
                    idle = false;
                }
            }
            if (!idle) { BukkitExecutor.async(() -> awaitWrites(chunk, currentWriter, admitWriter, level, expected, token, deadline, attempt, result), 1L); return; }
            CompletableFuture<?>[] verified = new CompletableFuture<?>[expected.size()];
            int index = 0;
            for (Map.Entry<RegionFileType, CompoundTag> entry : expected.entrySet()) {
                RegionDataController controller = MoonriseRegionFileIO.getControllerFor(level, entry.getKey());
                CompletableFuture<Void> verification = new CompletableFuture<>();
                verified[index++] = verification;
                controller.createRegionIoTask(chunk.getX(), chunk.getZ(), () -> {
                    try {
                        CompoundTag onDisk = null;
                        boolean finished = false;
                        for (int read = 0; read < 4 && !finished; read++) {
                            RegionDataController.ReadData data = controller.readData(chunk.getX(), chunk.getZ());
                            if (data.result() == RegionDataController.ReadData.ReadResult.NO_DATA) finished = true;
                            else { onDisk = controller.finishRead(chunk.getX(), chunk.getZ(), data); finished = onDisk != null; }
                        }
                        if (!finished) throw new IOException("Region-file readback did not stabilize");
                        if (entry.getValue() == null ? onDisk != null : onDisk == null || !token.equals(string(onDisk, TOKEN)))
                            throw new SupersededSnapshot();
                        Object region = NativeRegionFiles.loaded(controller.getCache(), chunk.getX(), chunk.getZ());
                        if (region == null && onDisk != null) throw new IOException("Region file was evicted before durable flush");
                        if (region != null) NativeRegionFiles.flush(region);
                        verification.complete(null);
                    } catch (Throwable error) { verification.completeExceptionally(error); }
                }, Priority.NORMAL).queue();
            }
            CompletableFuture.allOf(verified).whenComplete((unused, error) -> {
                if (error == null) {
                    if (currentWriter.getAsBoolean()) result.complete(null);
                    else result.completeExceptionally(new java.util.concurrent.RejectedExecutionException("World write generation retired after IO"));
                }
                else if (cause(error) instanceof SupersededSnapshot && attempt < 2 && System.nanoTime() < deadline) {
                    try { BukkitExecutor.region(chunk.getWorld(), chunk.getX(), chunk.getZ(),
                            () -> capture(chunk, currentWriter, admitWriter, deadline, attempt + 1, result)); }
                    catch (Throwable stopped) { result.completeExceptionally(stopped); }
                } else result.completeExceptionally(error);
            });
        } catch (Throwable error) { result.completeExceptionally(error); }
    }

    private static Throwable cause(Throwable error) {
        while ((error instanceof java.util.concurrent.CompletionException || error instanceof java.util.concurrent.ExecutionException) && error.getCause() != null)
            error = error.getCause();
        return error;
    }
    private static long packed(int x, int z) { return (long) x & 0xFFFFFFFFL | ((long) z & 0xFFFFFFFFL) << 32; }
    private static Field field(Class<?> type, String name) {
        try { Field field = type.getDeclaredField(name); field.setAccessible(true); return field; }
        catch (ReflectiveOperationException error) { throw new ExceptionInInitializerError(error); }
    }
    @SuppressWarnings("unchecked")
    private static Object currentTask(RegionDataController controller, int x, int z) throws ReflectiveOperationException {
        Object[] observed = new Object[1];
        // Same key-level publication boundary as native tryCompleteWrite. No IO,
        // world state or scheduler work occurs while this metadata lock is held.
        ((ConcurrentLong2ReferenceChainedHashTable<Object>) TASKS.get(controller)).compute(packed(x, z), (key, task) -> {
            try {
                if (task != null && FAILED_WRITE.getBoolean(task))
                    throw new java.io.UncheckedIOException(new IOException("Native region-file write failed"));
                observed[0] = task;
                return task;
            } catch (IllegalAccessException error) { throw new IllegalStateException(error); }
        });
        return observed[0];
    }
    private static Field taskFailure() {
        try { return field(Class.forName(MoonriseRegionFileIO.class.getName() + "$ChunkIOTask"), "failedWrite"); }
        catch (ClassNotFoundException error) { throw new ExceptionInInitializerError(error); }
    }
    private static Method stringGetter() {
        try { return CompoundTag.class.getMethod("getString", String.class); }
        catch (ReflectiveOperationException error) { throw new ExceptionInInitializerError(error); }
    }
    private static String string(CompoundTag tag, String key) throws ReflectiveOperationException {
        Object value = GET_STRING.invoke(tag, key);
        return String.valueOf(value instanceof Optional<?> ? ((Optional<?>) value).orElse(null) : value);
    }
    private static final class SupersededSnapshot extends IOException { SupersededSnapshot() { super("A newer native save superseded the write token"); } }
}

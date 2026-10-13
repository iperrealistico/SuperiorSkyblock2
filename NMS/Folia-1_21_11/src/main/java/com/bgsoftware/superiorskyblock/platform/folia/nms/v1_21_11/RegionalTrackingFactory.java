package com.bgsoftware.superiorskyblock.platform.folia.nms.v1_21_11;

import io.papermc.paper.threadedregions.RegionizedData;
import io.papermc.paper.threadedregions.RegionizedWorldData;
import io.papermc.paper.threadedregions.TickRegions;
import it.unimi.dsi.fastutil.longs.Long2ReferenceOpenHashMap;
import it.unimi.dsi.fastutil.objects.ReferenceOpenHashSet;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.redstone.CollectingNeighborUpdater;
import net.minecraft.world.ticks.LevelTicks;
import org.bukkit.World;
import org.bukkit.craftbukkit.CraftWorld;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicLong;

/** Region-local replacements preserve queues and follow Folia's merge/split lifecycle. */
public final class RegionalTrackingFactory {
    private static final ConcurrentMap<ServerLevel, RegionizedData<State>> worlds = new ConcurrentHashMap<>();
    private static final AtomicLong merges = new AtomicLong();
    private static final AtomicLong splits = new AtomicLong();
    private static final AtomicLong failures = new AtomicLong();
    private static final AtomicLong installs = new AtomicLong();
    private static final Method GET_REGION_DATA;
    private static final Field BLOCK_TICKS;
    private static final Field NEIGHBOR_UPDATER;
    private static final Field[] TICK_STATE;
    private static final Field[] NEIGHBOR_STATE;
    static {
        try {
            GET_REGION_DATA = TickRegions.TickRegionData.class.getDeclaredMethod("getOrCreateRegionizedData", RegionizedData.class);
            GET_REGION_DATA.setAccessible(true);
            BLOCK_TICKS = RegionizedWorldData.class.getDeclaredField("blockLevelTicks"); BLOCK_TICKS.setAccessible(true);
            NEIGHBOR_UPDATER = RegionizedWorldData.class.getDeclaredField("neighborUpdater"); NEIGHBOR_UPDATER.setAccessible(true);
            TICK_STATE = stateFields(LevelTicks.class);
            NEIGHBOR_STATE = stateFields(CollectingNeighborUpdater.class);
        } catch (ReflectiveOperationException error) { throw new ExceptionInInitializerError(error); }
    }
    private RegionalTrackingFactory() { }
    public static long getMergeCount() { return merges.get(); }
    public static long getSplitCount() { return splits.get(); }
    public static long getFailureCount() { return failures.get(); }
    public static long getInstallCount() { return installs.get(); }
    public static void prepare(World world) {
        ServerLevel level = ((CraftWorld) world).getHandle();
        worlds.computeIfAbsent(level, RegionalTrackingFactory::newHolder);
    }
    public static void install(World world) {
        ServerLevel level = ((CraftWorld) world).getHandle();
        RegionizedData<State> holder = worlds.computeIfAbsent(level, RegionalTrackingFactory::newHolder);
        State state = holder.get();
        if (state == null) throw new IllegalStateException("Regional tracking requires an owning region");
        if (state.failure != null) throw new IllegalStateException("Regional tracking lifecycle failed", state.failure);
    }
    private static RegionizedData<State> newHolder(ServerLevel level) {
        return new RegionizedData<>(level, region -> {
            try {
                RegionizedWorldData data = (RegionizedWorldData) GET_REGION_DATA.invoke(region, level.worldRegionData);
                State state = new State(level, data); installSafely(state); return state;
            } catch (Throwable error) { State failed = new State(level, null); failed.failure = error; failures.incrementAndGet(); return failed; }
        }, new RegionizedData.RegioniserCallback<State>() {
            // These callbacks run with region locks: metadata-only, no scheduling,
            // logging, I/O or live block/entity access; never throw into Folia.
            public void merge(State from, State into, long offset) { merges.incrementAndGet(); installSafely(into); }
            public void split(State from, int shift, Long2ReferenceOpenHashMap<State> regionToData, ReferenceOpenHashSet<State> dataSet) {
                splits.incrementAndGet();
                for (State state : dataSet) installSafely(state);
            }
        });
    }
    private static void installSafely(State state) {
        if (state.failure != null) return;
        try {
            RegionizedWorldData data = state.data;
            if (!(data.getBlockLevelTicks() instanceof BlockLevelTicksTracker)) {
                Object original = data.getBlockLevelTicks();
                if (original.getClass() != LevelTicks.class)
                    throw new IllegalStateException("Another plugin replaced regional block ticks: " + original.getClass().getName());
                BlockLevelTicksTracker replacement = new BlockLevelTicksTracker(state.level);
                copyState(TICK_STATE, original, replacement);
                BLOCK_TICKS.set(data, replacement);
                installs.incrementAndGet();
            }
            if (!(data.neighborUpdater instanceof CollectingNeighborUpdaterTracker)) {
                Object original = data.neighborUpdater;
                if (original.getClass() != CollectingNeighborUpdater.class)
                    throw new IllegalStateException("Another plugin replaced regional neighbour updates: " + original.getClass().getName());
                CollectingNeighborUpdaterTracker replacement = new CollectingNeighborUpdaterTracker(state.level);
                copyState(NEIGHBOR_STATE, original, replacement);
                NEIGHBOR_UPDATER.set(data, replacement);
            }
        } catch (Throwable failure) { state.failure = failure; failures.incrementAndGet(); }
    }
    private static Field[] stateFields(Class<?> base) {
        java.util.List<Field> fields = new java.util.ArrayList<>();
        for (Field field : base.getDeclaredFields()) {
            if (Modifier.isStatic(field.getModifiers())) continue;
            field.setAccessible(true); fields.add(field);
        }
        return fields.toArray(new Field[0]);
    }
    private static void copyState(Field[] fields, Object source, Object destination) throws ReflectiveOperationException {
        for (Field field : fields) field.set(destination, field.get(source));
    }
    private static final class State {
        final ServerLevel level; final RegionizedWorldData data;
        volatile Throwable failure;
        State(ServerLevel level, RegionizedWorldData data) { this.level = level; this.data = data; }
    }
}

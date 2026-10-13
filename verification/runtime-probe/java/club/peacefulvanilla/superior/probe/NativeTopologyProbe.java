package club.peacefulvanilla.superior.probe;

import com.bgsoftware.superiorskyblock.SuperiorSkyblockPlugin;
import com.bgsoftware.superiorskyblock.api.island.Island;
import com.bgsoftware.superiorskyblock.api.world.Dimension;
import com.bgsoftware.superiorskyblock.core.QualifiedNativeVersion;
import com.bgsoftware.superiorskyblock.core.threads.BukkitExecutor;
import com.bgsoftware.superiorskyblock.core.threads.FoliaRegionTrackers;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.server.PluginDisableEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

/** Actual holder topology exercise; no native lifecycle callback is invoked by this observer. */
public final class NativeTopologyProbe implements Listener {
    private final JavaPlugin observer;
    private final SuperiorSkyblockPlugin superior;
    private final AtomicBoolean started = new AtomicBoolean();
    private final AtomicBoolean closed = new AtomicBoolean();
    private volatile World world;
    private volatile Island island;
    private volatile List<Point> points = List.of();
    private long mergesBefore, splitsBefore, failuresBefore;

    public NativeTopologyProbe(JavaPlugin observer, SuperiorSkyblockPlugin superior) {
        this.observer = observer; this.superior = superior;
        Path root = observer.getServer().getWorldContainer().toPath().toAbsolutePath().normalize();
        if (root.getParent() == null || !root.getParent().getFileName().toString().equals("local-servers") ||
                !root.getFileName().toString().startsWith("superior-runtime-") ||
                !Files.isRegularFile(root.resolve(".superior-owned")) || !BukkitExecutor.isFolia() ||
                observer.getServer().getPort() < 29010 || observer.getServer().getPort() > 29029 ||
                superior.getSettings().getMaxIslandSize() != 1000)
            throw new IllegalStateException("Native topology requires an owned max1000 Folia fixture");
        Bukkit.getPluginManager().registerEvents(this, observer);
    }

    /** One shot; completion includes proof and removal of every observer-owned ticket. */
    public CompletableFuture<Void> run(Player primary) {
        if (!started.compareAndSet(false, true)) return CompletableFuture.failedFuture(new IllegalStateException("Topology is one-shot"));
        CompletableFuture<Void> result = new CompletableFuture<>();
        CompletableFuture<Void> work = actorsOutsideNormal(primary).thenRun(() -> prepare(primary))
                .thenCompose(unused -> CompletableFuture.allOf(load(points.get(0)), load(points.get(1))))
                .thenCompose(unused -> await(() -> regions().thenApply(value -> value.stable && value.a != value.b), 30))
                .thenRun(() -> {
                    mergesBefore = counter("getMergeCount"); splitsBefore = counter("getSplitCount");
                    failuresBefore = counter("getFailureCount");
                    if (failuresBefore != 0) throw new IllegalStateException("Native tracking already failed");
                    observer.getLogger().info("SUPERIOR_TOPOLOGY_ANCHORS_DISTINCT coordinates=" + points);
                })
                .thenCompose(unused -> CompletableFuture.allOf(load(points.get(2)), load(points.get(3))))
                .thenCompose(unused -> await(() -> regions().thenApply(value -> value.stable && value.a == value.b &&
                        counter("getMergeCount") > mergesBefore), 30))
                .thenRun(() -> {
                    observer.getLogger().info("SUPERIOR_TOPOLOGY_ACTUAL_MERGE delta=" + (counter("getMergeCount") - mergesBefore));
                    remove(points.get(2)); remove(points.get(3));
                })
                .thenCompose(unused -> await(() -> regions().thenApply(value -> value.stable && value.a != value.b &&
                        counter("getSplitCount") > splitsBefore), 60))
                .thenCompose(unused -> actorsOutsideNormal(primary))
                .thenRun(() -> {
                    if (counter("getFailureCount") != failuresBefore)
                        throw new IllegalStateException("Native regional tracking failed during topology change");
                    observer.getLogger().info("SUPERIOR_TOPOLOGY_ACTUAL_SPLIT delta=" + (counter("getSplitCount") - splitsBefore));
                }).orTimeout(150L, TimeUnit.SECONDS);
        work.whenComplete((unused, error) -> {
            try {
                close();
                if (error == null) result.complete(null); else result.completeExceptionally(error);
            } catch (Throwable cleanup) {
                if (error != null) cleanup.addSuppressed(error);
                result.completeExceptionally(cleanup);
            }
        });
        return result;
    }

    private void prepare(Player primary) {
        if (closed.get()) throw new IllegalStateException("Topology observer is closed");
        List<Island> owned = superior.getGrid().getOwnedIslands(primary.getUniqueId()).stream()
                .sorted(Comparator.comparingLong(Island::getShortId)).toList();
        if (owned.isEmpty()) throw new IllegalStateException("Actual owned reserved cell is required");
        island = owned.get(0);
        Dimension normal = superior.getSettings().getWorlds().getDefaultWorldDimension();
        if (normal.getEnvironment() != World.Environment.NORMAL) throw new IllegalStateException("Use the normal shared cell");
        Location center = island.getCenter(normal); world = center.getWorld();
        if (world == null || world.getEnvironment() != World.Environment.NORMAL || regionShift() != 4 ||
                !superior.getPvcIslandService().isAvailable(island.getUniqueId()))
            throw new IllegalStateException("Topology requires available NORMAL cell and actual grid-exponent4");
        int sectionX = (center.getBlockX() >> 4) >> 4, sectionZ = (center.getBlockZ() >> 4) >> 4;
        points = List.of(point(sectionX - 3, sectionZ), point(sectionX + 3, sectionZ),
                point(sectionX, sectionZ), point(sectionX, sectionZ + 3));
        for (Point point : points) {
            for (int dx : new int[]{-2, 2}) for (int dz : new int[]{-2, 2}) {
                Location edge = new Location(world, (point.x + dx) * 16 + (dx < 0 ? 0 : 15),
                        center.getY(), (point.z + dz) * 16 + (dz < 0 ? 0 : 15));
                if (!island.isInside(edge) || superior.getGrid().getIslandAt(edge) != island)
                    throw new IllegalStateException("Requested topology FULL halo leaves the owned reserved cell");
            }
        }
    }

    private static Point point(int sectionX, int sectionZ) { return new Point(sectionX * 16 + 8, sectionZ * 16 + 8); }

    /** At most two callers are admitted in each stage; no whole-cell or bridge polling loads. */
    private CompletableFuture<Void> load(Point point) {
        if (closed.get()) return CompletableFuture.failedFuture(new IllegalStateException("Topology closed before chunk load"));
        return world.getChunkAtAsync(point.x, point.z, true).orTimeout(30L, TimeUnit.SECONDS).thenCompose(chunk -> owned(point, () -> {
            if (closed.get()) throw new IllegalStateException("Late native load after topology cancellation");
            if (superior.getGrid().getIslandByUUID(island.getUniqueId()) != island ||
                    !superior.getPvcIslandService().isAvailable(island.getUniqueId()))
                throw new IllegalStateException("Reserved cell changed during topology exercise");
            if (!world.addPluginChunkTicket(point.x, point.z, observer))
                throw new IllegalStateException("Observer ticket already existed unexpectedly");
            // Cancellation can race a queued owner callback. Remove any late
            // ticket immediately rather than allowing it to escape close().
            if (closed.get()) { world.removePluginChunkTicket(point.x, point.z, observer); throw new IllegalStateException("Topology cancelled during ticket admission"); }
            FoliaRegionTrackers.install(world);
            return null;
        }));
    }

    private <T> CompletableFuture<T> owned(Point point, Supplier<T> action) {
        CompletableFuture<T> result = new CompletableFuture<>();
        try {
            BukkitExecutor.region(world, point.x, point.z, () -> {
                try {
                    if (!BukkitExecutor.owns(world, point.x, point.z)) throw new IllegalStateException("Topology query lacks real owner");
                    result.complete(action.get());
                } catch (Throwable error) { result.completeExceptionally(error); }
            });
        } catch (Throwable error) { result.completeExceptionally(error); }
        return result.orTimeout(5L, TimeUnit.SECONDS);
    }

    private CompletableFuture<Regions> regions() {
        if (closed.get()) return CompletableFuture.failedFuture(new IllegalStateException("Topology closed before owner query"));
        // Query only still-ticketed anchors. Scheduling an emptied bridge would
        // create native scheduler tickets and prevent the very split we test.
        return owned(points.get(0), this::currentRegion).thenCompose(a ->
                owned(points.get(1), this::currentRegion).thenCompose(b ->
                        owned(points.get(0), this::currentRegion).thenApply(again -> {
                            observer.getLogger().fine("Native topology owner IDs=" + a + ',' + b + ',' + again);
                            return new Regions(a, b, a.equals(again));
                        })));
    }

    private long currentRegion() {
        try {
            Class<?> scheduler = Class.forName("io.papermc.paper.threadedregions.TickRegionScheduler", true,
                    superior.getPluginClassLoader());
            Object region = scheduler.getMethod("getCurrentRegion").invoke(null);
            if (region == null) throw new IllegalStateException("Native owner region is absent");
            return region.getClass().getField("id").getLong(region);
        } catch (ReflectiveOperationException error) { throw new IllegalStateException("Native topology owner query failed", error); }
    }

    private int regionShift() {
        try {
            return ((Number) Class.forName("io.papermc.paper.threadedregions.TickRegions", true, superior.getPluginClassLoader())
                    .getMethod("getRegionChunkShift").invoke(null)).intValue();
        } catch (ReflectiveOperationException error) { throw new IllegalStateException("Native region grid query failed", error); }
    }

    private long counter(String method) {
        try {
            String adapter = QualifiedNativeVersion.adapterFor(Bukkit.getBukkitVersion());
            if (adapter == null) throw new IllegalStateException("Unqualified native tracker version");
            Class<?> factory = Class.forName("com.bgsoftware.superiorskyblock.platform.folia.nms." + adapter +
                    ".RegionalTrackingFactory", true, superior.getPluginClassLoader());
            return ((Number) factory.getMethod(method).invoke(null)).longValue();
        } catch (ReflectiveOperationException error) { throw new IllegalStateException("Actual tracker counter query failed", error); }
    }

    private CompletableFuture<Void> actorsOutsideNormal(Player primary) {
        CompletableFuture<Player> lookup = new CompletableFuture<>();
        BukkitExecutor.global(() -> {
            Player auxiliary = Bukkit.getPlayerExact("PVCSkyProbeB");
            if (!"PVCSkyProbe".equals(primary.getName()) || auxiliary == null || Bukkit.getOnlinePlayers().size() != 2)
                lookup.completeExceptionally(new IllegalStateException("Exactly two actual fixture actors are required"));
            else lookup.complete(auxiliary);
        });
        return lookup.thenCompose(auxiliary -> CompletableFuture.allOf(actorInEnd(primary), actorInEnd(auxiliary)));
    }

    private CompletableFuture<Void> actorInEnd(Player actor) {
        CompletableFuture<Void> result = new CompletableFuture<>();
        BukkitExecutor.entity(actor, () -> {
            if (!actor.isOnline() || !actor.isValid() || actor.getWorld().getEnvironment() != World.Environment.THE_END)
                result.completeExceptionally(new IllegalStateException("Actual actors must remain outside NORMAL in End"));
            else result.complete(null);
        }, 0L, () -> result.completeExceptionally(new IllegalStateException("Actual topology actor retired")));
        return result.orTimeout(5L, TimeUnit.SECONDS);
    }

    private CompletableFuture<Void> await(Supplier<CompletableFuture<Boolean>> predicate, int seconds) {
        CompletableFuture<Void> result = new CompletableFuture<>();
        poll(predicate, System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds), result);
        return result;
    }

    private void poll(Supplier<CompletableFuture<Boolean>> predicate, long deadline, CompletableFuture<Void> result) {
        if (result.isDone()) return;
        if (closed.get() || System.nanoTime() >= deadline) {
            result.completeExceptionally(new IllegalStateException("Genuine native topology did not converge before deadline")); return;
        }
        CompletableFuture<Boolean> check;
        try { check = predicate.get(); } catch (Throwable error) { result.completeExceptionally(error); return; }
        check.whenComplete((ready, error) -> {
            if (error != null) result.completeExceptionally(error);
            else if (Boolean.TRUE.equals(ready)) result.complete(null);
            else try { BukkitExecutor.async(() -> poll(predicate, deadline, result), 5L); }
            catch (Throwable stopping) { result.completeExceptionally(stopping); }
        });
    }

    private void remove(Point point) {
        // Official CraftWorld delegates these APIs directly to thread-safe native
        // TicketStorage. They neither load a chunk nor invoke a lifecycle callback.
        world.removePluginChunkTicket(point.x, point.z, observer);
        if (world.getPluginChunkTickets(point.x, point.z).contains(observer))
            throw new IllegalStateException("Observer's actual ticket was not removed");
    }

    /** Also safe for plugin disable: remove only this observer's four possible tickets. */
    public void close() {
        closed.set(true);
        Throwable failure = null;
        for (Point point : points) try { remove(point); }
        catch (Throwable error) { if (failure == null) failure = error; else failure.addSuppressed(error); }
        if (failure != null) throw new IllegalStateException("Native topology ticket cleanup failed", failure);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onDisable(PluginDisableEvent event) { if (event.getPlugin() == observer) close(); }

    private record Point(int x, int z) { }
    private record Regions(long a, long b, boolean stable) { }
}

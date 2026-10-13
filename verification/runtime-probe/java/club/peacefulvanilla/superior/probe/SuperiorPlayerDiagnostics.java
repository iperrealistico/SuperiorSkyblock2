package club.peacefulvanilla.superior.probe;

import com.bgsoftware.superiorskyblock.core.threads.BukkitExecutor;
import com.bgsoftware.superiorskyblock.api.events.PostIslandCreateEvent;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/** Bounded read-only native connection observer for the one canonical private fixture account. */
final class SuperiorPlayerDiagnostics implements AutoCloseable, Listener {
    private static final String ACCOUNT = "PVCSkyProbe";
    private final JavaPlugin observer;
    private final AtomicBoolean closed = new AtomicBoolean();
    private final AtomicInteger sequence = new AtomicInteger();
    private final AtomicInteger postCreated = new AtomicInteger();
    private final AtomicLong lastPostCreatedMillis = new AtomicLong();
    private final long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(120);
    private volatile ScheduledTask timer;

    private SuperiorPlayerDiagnostics(JavaPlugin observer) {
        this.observer = observer;
    }

    static SuperiorPlayerDiagnostics start(JavaPlugin observer) {
        SuperiorPlayerDiagnostics diagnostics = new SuperiorPlayerDiagnostics(observer);
        diagnostics.guardFixture();
        Bukkit.getPluginManager().registerEvents(diagnostics, observer);
        diagnostics.timer = Bukkit.getAsyncScheduler().runAtFixedRate(observer, task -> {
            if (diagnostics.closed.get() || System.nanoTime() >= diagnostics.deadline || !observer.isEnabled()) {
                diagnostics.close();
                return;
            }
            diagnostics.requestSnapshot();
        }, 1L, 5L, TimeUnit.SECONDS);
        return diagnostics;
    }

    private void requestSnapshot() {
        final int sample = sequence.incrementAndGet();
        try {
            guardFixture();
            BukkitExecutor.global(() -> {
                if (closed.get() || !observer.isEnabled()) return;
                Plugin superior = Bukkit.getPluginManager().getPlugin("SuperiorSkyblock2");
                boolean loaded = superior != null && superior.isEnabled();
                Player canonical = Bukkit.getPlayerExact(ACCOUNT); // Identity lookup only; no entity state on this thread.
                if (canonical == null) {
                    observer.getLogger().info("SUPERIOR_PLAYER_DIAGNOSTIC sample=" + sample + " pluginLoaded=" + loaded + " canonical=absent");
                    return;
                }
                observer.getLogger().info("SUPERIOR_PLAYER_DIAGNOSTIC_SCHEDULED sample=" + sample + " pluginLoaded=" + loaded);
                ScheduledTask accepted = canonical.getScheduler().run(observer, task -> capture(canonical, loaded, sample),
                        () -> observer.getLogger().info("SUPERIOR_PLAYER_DIAGNOSTIC_RETIRED sample=" + sample));
                if (accepted == null)
                    observer.getLogger().info("SUPERIOR_PLAYER_DIAGNOSTIC_NOT_ACCEPTED sample=" + sample);
            });
        } catch (Throwable failure) {
            observer.getLogger().info("SUPERIOR_PLAYER_DIAGNOSTIC_UNAVAILABLE sample=" + sample + " cause=" + summary(failure));
        }
    }

    private void capture(Player canonical, boolean pluginLoaded, int sample) {
        if (closed.get() || System.nanoTime() >= deadline || !observer.isEnabled()) return;
        try {
            guardFixture();
            if (!Bukkit.isOwnedByCurrentRegion(canonical)) {
                observer.getLogger().info("SUPERIOR_PLAYER_DIAGNOSTIC sample=" + sample + " owns=false stateRead=refused");
                return;
            }
            boolean sameIdentity = Bukkit.getPlayer(canonical.getUniqueId()) == canonical;
            Location location = canonical.getLocation();
            StringBuilder message = new StringBuilder("SUPERIOR_PLAYER_DIAGNOSTIC sample=").append(sample)
                    .append(" pluginLoaded=").append(pluginLoaded).append(" canonical=").append(sameIdentity)
                    .append(" uuid=").append(canonical.getUniqueId()).append(" online=").append(canonical.isOnline())
                    .append(" valid=").append(canonical.isValid()).append(" dead=").append(canonical.isDead())
                    .append(" postCreated=").append(postCreated.get()).append(" lastPostCreatedMillis=").append(lastPostCreatedMillis.get())
                    .append(" owns=true world=").append(location.getWorld().getName())
                    .append(" xyz=").append(location.getX()).append(',').append(location.getY()).append(',').append(location.getZ());
            try {
                Object handle = canonical.getClass().getMethod("getHandle").invoke(canonical);
                Object connection = readField(handle, "connection");
                message.append(" nativeRemoved=").append(handle.getClass().getMethod("isRemoved").invoke(handle))
                        .append(" nativeRemovalReason=").append(handle.getClass().getMethod("getRemovalReason").invoke(handle))
                        .append(" schedulerPurged=").append(canonical.getClass().getMethod("isPurged").invoke(canonical))
                        .append(" changingDimension=").append(handle.getClass().getMethod("isChangingDimension").invoke(handle))
                        .append(" hasClientLoaded=").append(connection.getClass().getMethod("hasClientLoaded").invoke(connection))
                        .append(" connectionDisconnected=").append(connection.getClass().getMethod("isDisconnected").invoke(connection))
                        .append(" awaitingPosition=").append(readField(connection, "awaitingPositionFromClient"))
                        .append(" awaitingTeleport=").append(readField(connection, "awaitingTeleport"))
                        .append(" awaitingTeleportTime=").append(readField(connection, "awaitingTeleportTime"))
                        .append(" waitingForRespawn=").append(readField(connection, "waitingForRespawn"))
                        .append(" clientLoadedTimeoutTimer=").append(readField(connection, "clientLoadedTimeoutTimer"))
                        .append(" waitingForSwitchToConfig=").append(readField(connection, "waitingForSwitchToConfig"));
            } catch (Throwable unavailable) {
                message.append(" nativeMetadata=unavailable:").append(summary(unavailable));
            }
            observer.getLogger().info(message.toString());
        } catch (Throwable unavailable) {
            observer.getLogger().info("SUPERIOR_PLAYER_DIAGNOSTIC_UNAVAILABLE sample=" + sample + " cause=" + summary(unavailable));
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPostCreated(PostIslandCreateEvent event) {
        if (closed.get() || System.nanoTime() >= deadline || !event.getPlayer().getName().equals(ACCOUNT)) return;
        long now = System.currentTimeMillis();
        int count = postCreated.incrementAndGet();
        lastPostCreatedMillis.set(now);
        String marker = "SUPERIOR_PLAYER_POST_CREATED count=" + count + " epochMillis=" + now +
                " island=" + event.getIsland().getUniqueId() + " shortId=" + event.getIsland().getShortId();
        observer.getLogger().info(marker);
        Player canonical = Bukkit.getPlayer(event.getPlayer().getUniqueId());
        if (canonical != null)
            BukkitExecutor.entity(canonical, () -> canonical.sendMessage(marker), 0L,
                    () -> observer.getLogger().info("SUPERIOR_PLAYER_POST_CREATED_RETIRED count=" + count));
    }

    /** Names above were verified in both official patched Folia source bundles, not inferred from mappings. */
    private Object readField(Object target, String name) throws ReflectiveOperationException {
        for (Class<?> type = target.getClass(); type != null; type = type.getSuperclass()) {
            try {
                Field field = type.getDeclaredField(name);
                field.setAccessible(true);
                return field.get(target);
            } catch (NoSuchFieldException nextSuperclass) { }
        }
        throw new NoSuchFieldException(name);
    }

    private void guardFixture() {
        Path root = observer.getServer().getWorldContainer().toPath().toAbsolutePath().normalize();
        if (root.getParent() == null || !root.getParent().getFileName().toString().equals("local-servers") ||
                !root.getFileName().toString().startsWith("superior-runtime-") ||
                !Files.isRegularFile(root.resolve(".superior-owned")) || !observer.getServer().getIp().equals("127.0.0.1") ||
                observer.getServer().getPort() < 29010 || observer.getServer().getPort() > 29029)
            throw new IllegalStateException("Player diagnostics refuses an unowned or non-loopback fixture");
    }

    private String summary(Throwable failure) {
        Throwable cause = failure;
        while ((cause instanceof InvocationTargetException || cause instanceof java.util.concurrent.CompletionException) && cause.getCause() != null)
            cause = cause.getCause();
        String message = String.valueOf(cause.getMessage()).replaceAll("[\\r\\n\\t]", " ");
        return cause.getClass().getSimpleName() + ':' + (message.length() > 256 ? message.substring(0, 256) : message);
    }

    @Override public void close() {
        if (closed.compareAndSet(false, true)) {
            if (timer != null) timer.cancel();
            HandlerList.unregisterAll(this);
        }
    }
}

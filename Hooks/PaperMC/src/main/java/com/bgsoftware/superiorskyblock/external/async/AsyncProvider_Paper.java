package com.bgsoftware.superiorskyblock.external.async;

import com.bgsoftware.superiorskyblock.external.async.AsyncProvider;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import com.bgsoftware.superiorskyblock.core.threads.BukkitExecutor;

import java.util.function.Consumer;

public class AsyncProvider_Paper implements AsyncProvider {

    @Override
    public void teleport(Entity entity, Location location, Consumer<Boolean> teleportResult) {
        java.util.concurrent.atomic.AtomicBoolean replied = new java.util.concurrent.atomic.AtomicBoolean();
        Consumer<Boolean> reply = value -> { if (teleportResult != null && replied.compareAndSet(false, true)) teleportResult.accept(value); };
        BukkitExecutor.entity(entity, () -> {
            try {
                entity.teleportAsync(location).whenComplete((result, error) -> {
                    try { BukkitExecutor.entity(entity, () -> reply.accept(error == null && Boolean.TRUE.equals(result)), 0L, () -> reply.accept(false)); }
                    catch (Throwable failure) { reply.accept(false); }
                });
            } catch (Throwable failure) { reply.accept(false); }
        }, 0L, () -> reply.accept(false));
    }

}
